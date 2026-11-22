package app.morphe.extension.ym;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import app.morphe.extension.ym.QualifiedPlayCounter.RecordResult;

public final class QualifiedPlayCounterTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void result(RecordResult actual, RecordResult expected, String message) {
        check(actual == expected, message + ": " + actual + " != " + expected);
    }

    private static void count(QualifiedPlayCounter counter, String account, String track,
            int expected, String message) {
        int actual = counter.count(account, track);
        check(actual == expected, message + ": " + actual + " != " + expected);
    }

    private static Path dir;

    public static void main(String[] args) throws Exception {
        dir = Files.createTempDirectory("qualified-play-counter-test");
        try {
            missingStoreIsEmptyAndCreatedOnFirstRecord();
            repeatAndNonAdjacentSessionsCountOnce();
            accountAndTrackAreSeparated();
            reopenKeepsCounts();
            blankAndOversizedIdsAreRejected();
            malformedSnapshotsFailClosedAndAreRetained();
            truncatedAndTrailingDataAreInvalid();
            perTrackSaturationRejectsNewSessions();
            globalBoundFailsSafeWithoutEviction();
            blockedPathsFailClosed();
            System.out.println("QualifiedPlayCounter tests passed");
        } finally {
            delete(dir);
        }
    }

    private static void missingStoreIsEmptyAndCreatedOnFirstRecord() throws IOException {
        Path file = dir.resolve("missing/store.bin");
        QualifiedPlayCounter counter = new QualifiedPlayCounter(file);
        count(counter, "acct", "track", 0, "missing store must count zero");
        result(counter.recordOnce("acct", "track", "s1"), RecordResult.RECORDED, "first record");
        count(counter, "acct", "track", 1, "after first record");
        check(Files.size(file) > 0, "snapshot must be written");
        byte[] head = Files.readAllBytes(file);
        check(Arrays.equals(Arrays.copyOf(head, 4), new byte[] {'Y', 'M', 'P', 'C'}),
                "snapshot magic");
        check(head[4] == 1, "snapshot version");
    }

    private static void repeatAndNonAdjacentSessionsCountOnce() throws IOException {
        Path file = dir.resolve("repeat.bin");
        QualifiedPlayCounter counter = new QualifiedPlayCounter(file);
        result(counter.recordOnce("acct", "track", "a"), RecordResult.RECORDED, "session a");
        result(counter.recordOnce("acct", "track", "b"), RecordResult.RECORDED, "session b");
        result(counter.recordOnce("acct", "track", "c"), RecordResult.RECORDED, "session c");
        count(counter, "acct", "track", 3, "three distinct sessions");
        // Non-adjacent repeat of an older session must not add anything.
        result(counter.recordOnce("acct", "track", "a"), RecordResult.ALREADY_RECORDED, "repeat a");
        result(counter.recordOnce("acct", "track", "a"), RecordResult.ALREADY_RECORDED, "repeat a 2");
        count(counter, "acct", "track", 3, "repeats must not count");
    }

    private static void accountAndTrackAreSeparated() throws IOException {
        Path file = dir.resolve("separate.bin");
        QualifiedPlayCounter counter = new QualifiedPlayCounter(file);
        result(counter.recordOnce("acct1", "track", "s"), RecordResult.RECORDED, "acct1");
        result(counter.recordOnce("acct2", "track", "s"), RecordResult.RECORDED, "acct2");
        result(counter.recordOnce("acct1", "track2", "s"), RecordResult.RECORDED, "track2");
        count(counter, "acct1", "track", 1, "acct1/track");
        count(counter, "acct2", "track", 1, "acct2/track");
        count(counter, "acct1", "track2", 1, "acct1/track2");
        count(counter, "acct3", "track", 0, "unknown account");
        count(counter, "acct1", "track3", 0, "unknown track");
    }

    private static void reopenKeepsCounts() throws IOException {
        Path file = dir.resolve("reopen.bin");
        result(new QualifiedPlayCounter(file).recordOnce("acct", "track", "s1"),
                RecordResult.RECORDED, "reopen first");
        QualifiedPlayCounter reopened = new QualifiedPlayCounter(file);
        count(reopened, "acct", "track", 1, "count after reopen");
        result(reopened.recordOnce("acct", "track", "s1"), RecordResult.ALREADY_RECORDED,
                "repeat after reopen");
        result(reopened.recordOnce("acct", "track", "s2"), RecordResult.RECORDED, "second after reopen");
        count(new QualifiedPlayCounter(file), "acct", "track", 2, "count after second reopen");
    }

    private static void blankAndOversizedIdsAreRejected() throws IOException {
        Path file = dir.resolve("invalid-input.bin");
        QualifiedPlayCounter counter = new QualifiedPlayCounter(file);
        result(counter.recordOnce("", "track", "s"), RecordResult.UNAVAILABLE, "blank account");
        result(counter.recordOnce("acct", "  ", "s"), RecordResult.UNAVAILABLE, "blank track");
        result(counter.recordOnce("acct", "track", null), RecordResult.UNAVAILABLE, "null session");
        check(!Files.exists(file), "invalid ids must not create a store");
        check(counter.count("acct", "track") == 0, "no persisted count after rejected input");
        check(counter.count("", "track") == -1, "blank account count is invalid");
        check(counter.count("acct", null) == -1, "null track count is invalid");

        String max = "x".repeat(QualifiedPlayCounter.MAX_ID_BYTES);
        result(counter.recordOnce("acct", "track", max), RecordResult.RECORDED, "max length id");
        count(counter, "acct", "track", 1, "max length id counted");
        String tooLong = max + "x";
        result(counter.recordOnce("acct", "track", tooLong), RecordResult.UNAVAILABLE,
                "oversized id");
        result(counter.recordOnce(tooLong, "track", "s2"), RecordResult.UNAVAILABLE,
                "oversized account");
        count(counter, "acct", "track", 1, "oversized id not persisted");

        String multibyte = "\u3042".repeat(QualifiedPlayCounter.MAX_ID_BYTES / 3 + 1);
        result(counter.recordOnce("acct", "track", multibyte), RecordResult.UNAVAILABLE,
                "multibyte over byte bound");
    }

    private static void malformedSnapshotsFailClosedAndAreRetained() throws IOException {
        List<byte[]> snapshots = new ArrayList<>();
        snapshots.add("not a snapshot".getBytes(StandardCharsets.US_ASCII));
        snapshots.add(new byte[] {'Y', 'M', 'P', 'X', 1, 0, 0, 0, 0});
        snapshots.add(new byte[] {'Y', 'M', 'P', 'C', 2, 0, 0, 0, 0});
        // Negative entry count.
        snapshots.add(new byte[] {'Y', 'M', 'P', 'C', 1, (byte) 0xff, (byte) 0xff, (byte) 0xff,
                (byte) 0xff});
        // Count above the global bound.
        snapshots.add(new byte[] {'Y', 'M', 'P', 'C', 1, 0, 0, 0x20, 0x01});
        // One entry, but the declared count is two (EOF mid-snapshot).
        snapshots.add(withDeclaredCount(snapshot(new String[][] {{"a", "t", "s"}}), 2));
        // Zero-length id.
        snapshots.add(snapshot(new String[][] {{"", "t", "s"}}));
        // Duplicate triple.
        snapshots.add(snapshot(new String[][] {{"a", "t", "s"}, {"a", "t", "s"}}));

        int index = 0;
        for (byte[] bytes : snapshots) {
            Path file = dir.resolve("malformed-" + index++ + ".bin");
            Files.write(file, bytes);
            QualifiedPlayCounter counter = new QualifiedPlayCounter(file);
            check(counter.count("a", "t") == -1, "malformed count must be -1");
            result(counter.recordOnce("a", "t", "new"), RecordResult.UNAVAILABLE,
                    "record on malformed store");
            check(Arrays.equals(Files.readAllBytes(file), bytes),
                    "malformed snapshot must be retained unchanged");
        }
    }

    private static void truncatedAndTrailingDataAreInvalid() throws IOException {
        Path file = dir.resolve("trailing.bin");
        QualifiedPlayCounter counter = new QualifiedPlayCounter(file);
        result(counter.recordOnce("a", "t", "s"), RecordResult.RECORDED, "seed");
        byte[] good = Files.readAllBytes(file);
        count(counter, "a", "t", 1, "seed count");

        byte[] trailing = Arrays.copyOf(good, good.length + 1);
        trailing[good.length] = 7;
        Files.write(file, trailing);
        check(counter.count("a", "t") == -1, "trailing byte must be invalid");
        result(counter.recordOnce("a", "t", "s2"), RecordResult.UNAVAILABLE, "record with trailing");
        check(Arrays.equals(Files.readAllBytes(file), trailing), "trailing snapshot retained");

        Files.write(file, Arrays.copyOf(good, good.length - 1));
        check(counter.count("a", "t") == -1, "truncated snapshot must be invalid");

        Files.write(file, new byte[] {'Y', 'M', 'P'});
        check(counter.count("a", "t") == -1, "short file must be invalid");

        Files.write(file, good);
        count(counter, "a", "t", 1, "restored snapshot counts again");
    }

    private static void perTrackSaturationRejectsNewSessions() throws IOException {
        Path file = dir.resolve("saturated.bin");
        List<String[]> entries = new ArrayList<>();
        for (int i = 0; i < QualifiedPlayCounter.MAX_PER_TRACK; i++) {
            entries.add(new String[] {"acct", "track", "s" + i});
        }
        // A second track must stay untouched by the first track's saturation.
        entries.add(new String[] {"acct", "other", "s0"});
        Files.write(file, snapshot(entries));

        QualifiedPlayCounter counter = new QualifiedPlayCounter(file);
        count(counter, "acct", "track", QualifiedPlayCounter.MAX_PER_TRACK, "full track count");
        result(counter.recordOnce("acct", "track", "s0"), RecordResult.ALREADY_RECORDED,
                "existing session on full track");
        result(counter.recordOnce("acct", "track", "fresh"), RecordResult.SATURATED,
                "new session on full track");
        count(counter, "acct", "track", QualifiedPlayCounter.MAX_PER_TRACK, "count unchanged");
        result(counter.recordOnce("acct", "other", "s1"), RecordResult.RECORDED,
                "other track still records");
        count(counter, "acct", "other", 2, "other track count");

        // 998 sessions must still accept exactly one more.
        Path almostFull = dir.resolve("almost-full.bin");
        List<String[]> partial = new ArrayList<>();
        for (int i = 0; i < QualifiedPlayCounter.MAX_PER_TRACK - 1; i++) {
            partial.add(new String[] {"acct", "track", "s" + i});
        }
        Files.write(almostFull, snapshot(partial));
        QualifiedPlayCounter partialCounter = new QualifiedPlayCounter(almostFull);
        result(partialCounter.recordOnce("acct", "track", "last"), RecordResult.RECORDED,
                "last slot on track");
        count(partialCounter, "acct", "track", QualifiedPlayCounter.MAX_PER_TRACK, "track now full");
        result(partialCounter.recordOnce("acct", "track", "overflow"), RecordResult.SATURATED,
                "track saturated after filling");
    }

    private static void globalBoundFailsSafeWithoutEviction() throws IOException {
        Path file = dir.resolve("global.bin");
        List<String[]> entries = new ArrayList<>();
        int perTrack = Math.min(QualifiedPlayCounter.MAX_PER_TRACK,
                QualifiedPlayCounter.MAX_SESSIONS / 16);
        int tracks = 0;
        while (entries.size() < QualifiedPlayCounter.MAX_SESSIONS) {
            String track = "track" + tracks++;
            for (int i = 0; i < perTrack
                    && entries.size() < QualifiedPlayCounter.MAX_SESSIONS; i++) {
                entries.add(new String[] {"acct", track, "s" + i});
            }
        }
        Files.write(file, snapshot(entries));

        QualifiedPlayCounter counter = new QualifiedPlayCounter(file);
        count(counter, "acct", "track0", perTrack, "global seed count");
        result(counter.recordOnce("acct", "track0", "s0"), RecordResult.ALREADY_RECORDED,
                "existing session when globally full");
        result(counter.recordOnce("acct", "new-track", "s"), RecordResult.UNAVAILABLE,
                "new pair when globally full");
        byte[] before = Files.readAllBytes(file);
        result(counter.recordOnce("acct", "track1", "extra"), RecordResult.UNAVAILABLE,
                "new session on known pair when globally full");
        check(Arrays.equals(Files.readAllBytes(file), before), "full store must not be rewritten");
        count(counter, "acct", "track0", perTrack, "no eviction of existing entries");
        count(counter, "acct", "track1", perTrack, "track1 unchanged");
    }

    private static void blockedPathsFailClosed() throws IOException {
        Path directory = dir.resolve("as-directory.bin");
        Files.createDirectory(directory);
        QualifiedPlayCounter counter = new QualifiedPlayCounter(directory);
        check(counter.count("a", "t") == -1, "directory path count is -1");
        result(counter.recordOnce("a", "t", "s"), RecordResult.UNAVAILABLE, "directory path record");
        check(Files.isDirectory(directory), "directory must be left intact");

        Path parentFile = dir.resolve("not-a-directory");
        Files.write(parentFile, new byte[] {42});
        QualifiedPlayCounter blocked = new QualifiedPlayCounter(parentFile.resolve("store.bin"));
        result(blocked.recordOnce("a", "t", "s"), RecordResult.UNAVAILABLE, "parent is a file");
        check(Arrays.equals(Files.readAllBytes(parentFile), new byte[] {42}),
                "blocked store must leave its parent file untouched");
    }

    private static byte[] snapshot(String[][] entries) throws IOException {
        List<String[]> list = Arrays.asList(entries);
        return snapshot(list);
    }

    private static byte[] snapshot(List<String[]> entries) throws IOException {
        return snapshotBytes(entries, entries.size());
    }

    private static byte[] withDeclaredCount(byte[] base, int declaredCount) {
        byte[] copy = base.clone();
        int offset = 5;
        copy[offset] = (byte) (declaredCount >>> 24);
        copy[offset + 1] = (byte) (declaredCount >>> 16);
        copy[offset + 2] = (byte) (declaredCount >>> 8);
        copy[offset + 3] = (byte) declaredCount;
        return copy;
    }

    private static byte[] snapshotBytes(List<String[]> entries, int count) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write('Y');
        out.write('M');
        out.write('P');
        out.write('C');
        out.write(1);
        out.write(count >>> 24);
        out.write(count >>> 16);
        out.write(count >>> 8);
        out.write(count);
        for (String[] entry : entries) {
            for (String id : entry) {
                byte[] bytes = id.getBytes(StandardCharsets.UTF_8);
                out.write(bytes.length >>> 24);
                out.write(bytes.length >>> 16);
                out.write(bytes.length >>> 8);
                out.write(bytes.length);
                out.write(bytes);
            }
        }
        return out.toByteArray();
    }

    private static void delete(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Test cleanup only.
                }
            });
        }
    }
}
