package app.morphe.extension.ym;

import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

/**
 * Durable counter of distinct qualified sessions per account and track.
 *
 * <p>Pure Java on purpose: the store is a single versioned snapshot file so it can be exercised
 * on the JVM. Every operation reopens and re-parses the file; nothing is cached in memory. The
 * snapshot is written to a temporary sibling and moved into place with an atomic move. There is
 * deliberately no non-atomic fallback: if the platform cannot move atomically the record is
 * reported as {@link RecordResult#UNAVAILABLE} and the previous file is left untouched.
 *
 * <p>Contract: one process and one writer instance own a given file. Operations are synchronized,
 * so a single instance may be shared between threads. A store that exists but cannot be parsed is
 * never rewritten; it is reported as unavailable instead of being replaced by guessed data.
 *
 * <p>Storage is bounded: at most {@value #MAX_PER_TRACK} sessions are kept per account/track pair
 * and at most {@value #MAX_SESSIONS} sessions in total. When a bound is reached new sessions are
 * rejected rather than evicting an existing entry.
 */
final class QualifiedPlayCounter {
    enum RecordResult {
        RECORDED,
        ALREADY_RECORDED,
        SATURATED,
        UNAVAILABLE
    }

    /** A track stops counting at this many distinct sessions. */
    static final int MAX_PER_TRACK = CacheRetentionCriteria.MAX_PLAY_COUNT;
    /** Upper bound for the whole snapshot; no entry is evicted to make room. */
    static final int MAX_SESSIONS = 8192;
    /** Maximum UTF-8 byte length of an opaque account, track, or session identifier. */
    static final int MAX_ID_BYTES = 256;

    private static final byte[] MAGIC = {'Y', 'M', 'P', 'C'};
    private static final int VERSION = 1;
    private static final int HEADER_BYTES = MAGIC.length + 1 + 4;
    private static final int LENGTH_BYTES = 4;
    private static final int MAX_FILE_BYTES =
            HEADER_BYTES + MAX_SESSIONS * (3 * LENGTH_BYTES + 3 * MAX_ID_BYTES);

    private final Path file;

    QualifiedPlayCounter(Path file) {
        if (file == null) throw new NullPointerException("file");
        this.file = file;
    }

    /**
     * Records {@code session} once for the given account and track.
     *
     * @return {@link RecordResult#RECORDED} when the session was added,
     *         {@link RecordResult#ALREADY_RECORDED} when this exact session was already stored,
     *         {@link RecordResult#SATURATED} when the track already has {@value #MAX_PER_TRACK}
     *         sessions, or {@link RecordResult#UNAVAILABLE} when the input is invalid, the store is
     *         malformed, the global bound is reached, or the snapshot cannot be persisted.
     */
    synchronized RecordResult recordOnce(String account, String track, String session) {
        if (!isValidId(account) || !isValidId(track) || !isValidId(session)) {
            return RecordResult.UNAVAILABLE;
        }
        ReadResult read = read();
        if (read.status == Status.INVALID) return RecordResult.UNAVAILABLE;
        Store store = read.status == Status.MISSING ? new Store() : read.store;
        if (store.contains(account, track, session)) return RecordResult.ALREADY_RECORDED;
        if (store.count(account, track) >= MAX_PER_TRACK) return RecordResult.SATURATED;
        if (store.size >= MAX_SESSIONS) return RecordResult.UNAVAILABLE;
        if (!store.add(account, track, session)) return RecordResult.UNAVAILABLE;
        if (!persist(store)) return RecordResult.UNAVAILABLE;
        return RecordResult.RECORDED;
    }

    /**
     * Number of recorded sessions for the account/track pair, clamped to
     * {@value #MAX_PER_TRACK}. A missing store counts as zero; invalid input, a malformed store,
     * or an unreadable file returns {@code -1}.
     */
    synchronized int count(String account, String track) {
        if (!isValidId(account) || !isValidId(track)) return -1;
        ReadResult read = read();
        if (read.status == Status.MISSING) return 0;
        if (read.status == Status.INVALID) return -1;
        return Math.min(read.store.count(account, track), MAX_PER_TRACK);
    }

    private ReadResult read() {
        long size;
        try {
            size = Files.size(file);
        } catch (NoSuchFileException e) {
            return new ReadResult(Status.MISSING, null);
        } catch (IOException | SecurityException e) {
            return new ReadResult(Status.INVALID, null);
        }
        if (size < HEADER_BYTES || size > MAX_FILE_BYTES) {
            return new ReadResult(Status.INVALID, null);
        }
        byte[] data;
        try {
            data = Files.readAllBytes(file);
        } catch (IOException | SecurityException e) {
            return new ReadResult(Status.INVALID, null);
        }
        return parse(data);
    }

    private static ReadResult parse(byte[] data) {
        if (data.length < HEADER_BYTES || data.length > MAX_FILE_BYTES) {
            return new ReadResult(Status.INVALID, null);
        }
        ByteBuffer buffer = ByteBuffer.wrap(data);
        try {
            for (byte expected : MAGIC) {
                if (buffer.get() != expected) return new ReadResult(Status.INVALID, null);
            }
            if (buffer.get() != VERSION) return new ReadResult(Status.INVALID, null);
            int count = buffer.getInt();
            if (count < 0 || count > MAX_SESSIONS) return new ReadResult(Status.INVALID, null);

            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            Store store = new Store();
            for (int i = 0; i < count; i++) {
                String account = readId(buffer, decoder);
                String track = readId(buffer, decoder);
                String session = readId(buffer, decoder);
                if (account == null || track == null || session == null) {
                    return new ReadResult(Status.INVALID, null);
                }
                // A duplicate or an out-of-bound entry makes the whole snapshot malformed.
                if (!store.add(account, track, session)) {
                    return new ReadResult(Status.INVALID, null);
                }
            }
            if (buffer.hasRemaining()) return new ReadResult(Status.INVALID, null);
            return new ReadResult(Status.OK, store);
        } catch (BufferUnderflowException e) {
            return new ReadResult(Status.INVALID, null);
        }
    }

    private static String readId(ByteBuffer buffer, CharsetDecoder decoder) {
        if (buffer.remaining() < LENGTH_BYTES) return null;
        int length = buffer.getInt();
        if (length < 1 || length > MAX_ID_BYTES) return null;
        if (buffer.remaining() < length) return null;
        byte[] bytes = new byte[length];
        buffer.get(bytes);
        String id;
        try {
            id = decoder.reset().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
        // Apply the same rules as the writer so a parsed snapshot can only hold valid ids.
        return isValidId(id) ? id : null;
    }

    private boolean persist(Store store) {
        byte[] data = encode(store);
        Path parent = file.toAbsolutePath().getParent();
        if (parent == null) return false;
        Path temporary = null;
        try {
            Files.createDirectories(parent);
            temporary = Files.createTempFile(parent, "qualified-play-counter-", ".tmp");
            try (FileChannel channel = FileChannel.open(
                    temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(data);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Files.move(temporary, file,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException | SecurityException e) {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException | SecurityException ignored) {
                    // Best effort only; the previous snapshot is still intact.
                }
            }
            return false;
        }
    }

    private static byte[] encode(Store store) {
        int total = HEADER_BYTES;
        for (Map.Entry<String, LinkedHashMap<String, LinkedHashSet<String>>> account
                : store.accounts.entrySet()) {
            int accountLength = utf8(account.getKey()).length;
            for (Map.Entry<String, LinkedHashSet<String>> track : account.getValue().entrySet()) {
                int trackLength = utf8(track.getKey()).length;
                for (String session : track.getValue()) {
                    total += 3 * LENGTH_BYTES + accountLength + trackLength
                            + utf8(session).length;
                }
            }
        }
        ByteBuffer buffer = ByteBuffer.allocate(total);
        buffer.put(MAGIC);
        buffer.put((byte) VERSION);
        buffer.putInt(store.size);
        for (Map.Entry<String, LinkedHashMap<String, LinkedHashSet<String>>> account
                : store.accounts.entrySet()) {
            for (Map.Entry<String, LinkedHashSet<String>> track : account.getValue().entrySet()) {
                for (String session : track.getValue()) {
                    putId(buffer, account.getKey());
                    putId(buffer, track.getKey());
                    putId(buffer, session);
                }
            }
        }
        return buffer.array();
    }

    private static void putId(ByteBuffer buffer, String id) {
        byte[] bytes = utf8(id);
        buffer.putInt(bytes.length);
        buffer.put(bytes);
    }

    static boolean isValidId(String id) {
        if (id == null || id.length() > MAX_ID_BYTES || id.trim().isEmpty()) return false;
        byte[] bytes = utf8(id);
        if (bytes.length < 1 || bytes.length > MAX_ID_BYTES) return false;
        // Reject unpaired surrogates: they would not survive the UTF-8 round trip.
        return id.equals(new String(bytes, StandardCharsets.UTF_8));
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private enum Status {
        OK,
        MISSING,
        INVALID
    }

    private static final class ReadResult {
        final Status status;
        final Store store;

        ReadResult(Status status, Store store) {
            this.status = status;
            this.store = store;
        }
    }

    /** Insertion-ordered account -> track -> session set. */
    private static final class Store {
        private final LinkedHashMap<String, LinkedHashMap<String, LinkedHashSet<String>>> accounts =
                new LinkedHashMap<>();
        private int size;

        boolean contains(String account, String track, String session) {
            LinkedHashMap<String, LinkedHashSet<String>> tracks = accounts.get(account);
            if (tracks == null) return false;
            LinkedHashSet<String> sessions = tracks.get(track);
            return sessions != null && sessions.contains(session);
        }

        int count(String account, String track) {
            LinkedHashMap<String, LinkedHashSet<String>> tracks = accounts.get(account);
            if (tracks == null) return 0;
            LinkedHashSet<String> sessions = tracks.get(track);
            return sessions == null ? 0 : sessions.size();
        }

        /**
         * Adds the session.
         *
         * @return {@code false} when the session already exists or a storage bound would be
         *         exceeded, in which case nothing is changed.
         */
        boolean add(String account, String track, String session) {
            if (size >= MAX_SESSIONS) return false;
            LinkedHashMap<String, LinkedHashSet<String>> tracks = accounts.get(account);
            if (tracks == null) {
                tracks = new LinkedHashMap<>();
                accounts.put(account, tracks);
            }
            LinkedHashSet<String> sessions = tracks.get(track);
            if (sessions == null) {
                sessions = new LinkedHashSet<>();
                tracks.put(track, sessions);
            }
            if (sessions.contains(session)) return false;
            if (sessions.size() >= MAX_PER_TRACK) return false;
            sessions.add(session);
            size++;
            return true;
        }
    }
}
