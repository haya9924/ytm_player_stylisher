package app.morphe.extension.ym;

import android.util.Log;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fixed, low-volume diagnostics for playback restore. Only constant text and the
 * supplied numeric offset or boolean are logged: no class names, object values,
 * identifiers, files or network access. Every entry point swallows
 * RuntimeExceptions so a diagnostic can never affect playback.
 */
public final class PlaybackRestoreDiagnostics {
    private static final String TAG = "YmCacheDiag";

    private static final int LOG_LIMIT = 8;

    private static final AtomicInteger restoredPausedLogs = new AtomicInteger();
    private static final AtomicInteger streamExpiredTrueLogs = new AtomicInteger();
    private static final AtomicInteger streamExpiredFalseLogs = new AtomicInteger();

    private PlaybackRestoreDiagnostics() {}

    /** Records the fixed offset of a paused restore, logged at most 8 times. */
    public static void restoredPaused(long offsetMs) {
        try {
            if (restoredPausedLogs.getAndUpdate(value -> Math.min(value + 1, LOG_LIMIT))
                    < LOG_LIMIT) {
                Log.d(TAG, "playback restored paused: offsetMs=" + offsetMs);
            }
        } catch (RuntimeException ignored) {
            // Diagnostics must never disturb playback.
        }
    }

    /** Records the expired flag, logged at most 8 times per boolean value. */
    public static void streamExpired(boolean expired) {
        try {
            AtomicInteger count = expired ? streamExpiredTrueLogs : streamExpiredFalseLogs;
            if (count.getAndUpdate(value -> Math.min(value + 1, LOG_LIMIT)) < LOG_LIMIT) {
                Log.d(TAG, "stream expired: " + expired);
            }
        } catch (RuntimeException ignored) {
            // Diagnostics must never disturb playback.
        }
    }
}
