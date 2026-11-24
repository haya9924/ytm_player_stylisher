package app.morphe.extension.ym;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Records one qualified playback session per admitted native watch-time callback source.
 *
 * <p>Hot-path contract: {@link #admit}, {@link #sample}, and {@link #release} never touch the
 * disk and never derive keys. They only update the single weakly held candidate. When the fixed
 * listening threshold is reached exactly one snapshot is submitted to a bounded single-thread
 * executor. Rejection leaves the session unclaimed so a later sample can retry; an accepted
 * submission is claimed, so duplicate submissions are impossible.
 *
 * <p>Worker contract: the worker loads or creates a per-install 32-byte token in the app's
 * no-backup directory, derives scoped keys with {@link ScopedPlaybackKeys}, and records the
 * session once with {@link QualifiedPlayCounter}. Raw account, track, session, and token values
 * are never persisted or logged. A counter store that exists without its token fails closed and
 * is never truncated or rewritten.
 *
 * <p>Recording is gated by the parent patch-time option: the caller only invokes
 * {@link #initialize} when recording is enabled. Without a successful initialization every entry
 * point is a no-op, and the bridge records only in the app process on API 28 or newer.
 */
public final class ScopedPlaybackRecordingBridge {
    private static final String TAG = "YmCacheRecord";
    private static final String TOKEN_FILE = "install.key";
    private static final String COUNTER_FILE = "qualified.plays";
    private static final int QUEUE_CAPACITY = 4;
    private static final int SECRET_BYTES = ScopedPlaybackKeys.SECRET_BYTES;
    private static final String STORE_DIRECTORY = "ym_scoped_recording_v1";

    private static final Object LOCK = new Object();

    private static volatile boolean recording;
    private static volatile Context appContext;
    private static volatile ExecutorService executor;
    private static Candidate candidate;

    private ScopedPlaybackRecordingBridge() {}

    /**
     * Enables recording once, in the app process only.
     *
     * <p>On API levels without {@link Application#getProcessName()} the bridge fails closed and
     * stays disabled. Repeated calls and calls from other processes are ignored.
     */
    public static void initialize(Context context) {
        if (!isAppProcess(context)) return;
        Context app;
        try {
            app = context.getApplicationContext();
        } catch (RuntimeException unavailable) {
            return;
        }
        if (app == null) return;
        synchronized (LOCK) {
            if (executor != null) return;
            appContext = app;
            executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                    runnable -> {
                        Thread thread = new Thread(runnable, "ym-cache-record");
                        thread.setDaemon(true);
                        return thread;
                    },
                    new ThreadPoolExecutor.AbortPolicy());
            recording = true;
        }
    }

    /**
     * Replaces the current candidate with a new generation.
     *
     * <p>Every call supersedes the previous candidate, even when the input is null or invalid, so
     * stale sources can never qualify afterwards. The criteria are read from the current
     * preference snapshot now and are fixed for this generation.
     */
    public static void admit(Object stats2, String account, String track, String session) {
        if (!recording) return;
        SharedPreferences prefs = preferences();
        CacheRetentionCriteria criteria = CacheRetentionPreferences.read(prefs);
        synchronized (LOCK) {
            Candidate previous = candidate;
            if (previous != null) previous.terminal = true;
            if (executor == null || stats2 == null
                    || !QualifiedPlayCounter.isValidId(account)
                    || !QualifiedPlayCounter.isValidId(track)
                    || !QualifiedPlayCounter.isValidId(session)) {
                candidate = new Candidate(stats2);
                return;
            }
            candidate = new Candidate(stats2, account, track, session, criteria);
        }
    }

    /**
     * Advances the verified native cumulative watch time for the admitted source.
     *
     * <p>Negative or decreasing values are ignored; nothing is estimated. The first positive
     * duration fixes the threshold for this generation, and reaching it submits the session to
     * the worker at most once.
     */
    public static void sample(Object stats2, long durationMillis, long watchedMillis) {
        if (!recording || stats2 == null) return;
        RecordTask task;
        synchronized (LOCK) {
            Candidate current = candidate;
            if (current == null || current.terminal || current.claimed) return;
            if (current.stats2.get() != stats2) return;
            if (watchedMillis < 0L || watchedMillis < current.watchedMillis) return;
            current.watchedMillis = watchedMillis;
            if (current.requiredMillis < 0L) {
                if (durationMillis <= 0L) return;
                current.requiredMillis = current.criteria.requiredListenedMillis(durationMillis);
            }
            if (watchedMillis < current.requiredMillis) return;
            current.claimed = true;
            task = new RecordTask(current, current.account, current.track, current.session);
        }
        submit(task);
    }

    /** Drops the admitted source without recording anything. */
    public static void release(Object stats2) {
        synchronized (LOCK) {
            Candidate current = candidate;
            if (current == null) return;
            if (stats2 != null && current.stats2.get() != stats2) return;
            current.terminal = true;
            candidate = null;
        }
    }

    private static SharedPreferences preferences() {
        Context context = appContext;
        if (context == null) return null;
        try {
            return context.getSharedPreferences(GradientSettingsActivity.PREFS, Context.MODE_PRIVATE);
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private static boolean isAppProcess(Context context) {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false;
        try {
            String process = Application.getProcessName();
            return process != null && process.equals(context.getPackageName());
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private static void submit(RecordTask task) {
        ExecutorService pool = executor;
        if (pool == null) {
            retry(task.generation);
            return;
        }
        try {
            pool.execute(task);
        } catch (RuntimeException rejected) {
            // Rejected or shutting down: leave the session unclaimed so it may retry.
            retry(task.generation);
        }
    }

    private static void retry(Candidate generation) {
        synchronized (LOCK) {
            if (candidate == generation) generation.claimed = false;
        }
    }

    private static QualifiedPlayCounter.RecordResult record(
            String account, String track, String session, int threshold, long scopeGeneration) {
        Context context = appContext;
        if (context == null) return QualifiedPlayCounter.RecordResult.UNAVAILABLE;
        File directory;
        try {
            File root = context.getNoBackupFilesDir();
            directory = root == null ? null : new File(root, STORE_DIRECTORY);
        } catch (RuntimeException unavailable) {
            return QualifiedPlayCounter.RecordResult.UNAVAILABLE;
        }
        if (directory == null) return QualifiedPlayCounter.RecordResult.UNAVAILABLE;
        if (!directory.isDirectory() && !directory.mkdirs()) {
            return QualifiedPlayCounter.RecordResult.UNAVAILABLE;
        }
        File store = new File(directory, COUNTER_FILE);
        byte[] token = token(directory, store);
        // A store without its key fails closed; nothing is written or truncated.
        if (token == null) return QualifiedPlayCounter.RecordResult.UNAVAILABLE;
        ScopedPlaybackKeys.Keys keys =
                ScopedPlaybackKeys.derive(token, account, track, session);
        if (keys == null) return QualifiedPlayCounter.RecordResult.UNAVAILABLE;
        QualifiedPlayCounter counter = new QualifiedPlayCounter(store.toPath());
        QualifiedPlayCounter.RecordResult result = counter.recordOnce(keys.account, keys.track, keys.session);
        if (result != QualifiedPlayCounter.RecordResult.UNAVAILABLE) {
            NativeCacheRetention.qualified(account, track, counter.count(keys.account, keys.track),
                    threshold, scopeGeneration);
        }
        return result;
    }

    private static byte[] token(File directory, File store) {
        File tokenFile = new File(directory, TOKEN_FILE);
        try {
            if (tokenFile.isFile()) {
                if (tokenFile.length() != SECRET_BYTES) return null;
                byte[] existing = Files.readAllBytes(tokenFile.toPath());
                return existing.length == SECRET_BYTES ? existing : null;
            }
            // Never create a key next to an existing store: that would orphan recorded sessions.
            if (tokenFile.exists() || store.exists()) return null;
            byte[] created = new byte[SECRET_BYTES];
            new SecureRandom().nextBytes(created);
            Path temporary = Files.createTempFile(directory.toPath(), "install-key-", ".tmp");
            try {
                try (FileChannel channel = FileChannel.open(temporary,
                        StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                    ByteBuffer buffer = ByteBuffer.wrap(created);
                    while (buffer.hasRemaining()) channel.write(buffer);
                    channel.force(true);
                }
                Files.move(temporary, tokenFile.toPath(), StandardCopyOption.ATOMIC_MOVE);
                return created;
            } catch (IOException moveFailed) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException | SecurityException ignored) {
                    // Best effort only.
                }
                return null;
            }
        } catch (IOException | SecurityException unavailable) {
            return null;
        }
    }

    private static void logOutcome(QualifiedPlayCounter.RecordResult result) {
        String message;
        switch (result) {
            case RECORDED:
                message = "outcome recorded";
                break;
            case ALREADY_RECORDED:
                message = "outcome already recorded";
                break;
            case SATURATED:
                message = "outcome saturated";
                break;
            default:
                return;
        }
        try {
            Log.d(TAG, message);
        } catch (RuntimeException ignored) {
            // Logging may be unavailable, e.g. in a stripped runtime.
        }
    }

    private static final class Candidate {
        final WeakReference<Object> stats2;
        final String account;
        final String track;
        final String session;
        final CacheRetentionCriteria criteria;
        final long scopeGeneration = NativeCacheRetention.generation();
        long requiredMillis = -1L;
        long watchedMillis = -1L;
        boolean claimed;
        boolean terminal;

        Candidate(Object stats2) {
            this.stats2 = new WeakReference<>(stats2);
            this.account = null;
            this.track = null;
            this.session = null;
            this.criteria = null;
            this.terminal = true;
        }

        Candidate(Object stats2, String account, String track, String session,
                CacheRetentionCriteria criteria) {
            this.stats2 = new WeakReference<>(stats2);
            this.account = account;
            this.track = track;
            this.session = session;
            this.criteria = criteria;
        }
    }

    /** One qualified qualification attempt; captures its generation so it can never rebound. */
    private static final class RecordTask implements Runnable {
        final Candidate generation;
        private final String account;
        private final String track;
        private final String session;

        RecordTask(Candidate generation, String account, String track, String session) {
            this.generation = generation;
            this.account = account;
            this.track = track;
            this.session = session;
        }

        @Override
        public void run() {
            synchronized (LOCK) {
                if (candidate != generation || generation.terminal) return;
            }
            // In-flight work retains the account captured at admission.
            QualifiedPlayCounter.RecordResult result;
            try {
                result = record(account, track, session, generation.criteria.playCount,
                        generation.scopeGeneration);
            } catch (RuntimeException unavailable) {
                result = QualifiedPlayCounter.RecordResult.UNAVAILABLE;
            }
            if (result == QualifiedPlayCounter.RecordResult.UNAVAILABLE) {
                synchronized (LOCK) {
                    if (candidate == generation) generation.terminal = true;
                }
                return;
            }
            // At most one fixed outcome line per successful qualification.
            logOutcome(result);
            synchronized (LOCK) {
                if (candidate == generation) generation.terminal = true;
            }
        }
    }
}
