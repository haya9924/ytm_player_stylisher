package app.morphe.extension.ym;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static app.morphe.extension.ym.PlaybackSessionTracker.Result.*;

public final class PlaybackSessionTrackerTest {
    private static final CacheRetentionCriteria CRITERIA = new CacheRetentionCriteria(2, 30, 50);

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("ym-listening-test-");
        try {
            checkSessions(dir.resolve("sessions.bin"));
            checkRetry(dir.resolve("retry.bin"));
        } finally {
            try (Stream<Path> paths = Files.walk(dir)) {
                for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) {
                    Files.deleteIfExists(path);
                }
            }
        }
        System.out.println("PlaybackSessionTracker tests passed");
    }

    private static void checkSessions(Path file) {
        PlaybackSessionTracker tracker = new PlaybackSessionTracker(file);
        expect(PENDING, tracker.begin(1, "a", "track", "s1", 20_000, CRITERIA));
        expect(PENDING, tracker.progress(1, 9_999));
        expect(PENDING, tracker.begin(1, "a", "track", "s1", 20_000, CRITERIA));
        expect(IGNORED, tracker.progress(1, 9_998)); // duplicate start did not reset progress
        expect(IGNORED, tracker.begin(1, "a", "track", "s1", 1_000, CRITERIA));
        expect(PENDING, tracker.progress(1, 9_999)); // unchanged threshold
        expect(IGNORED, tracker.begin(0, null, null, null, 0, null));
        expect(RECORDED, tracker.progress(1, 10_000));
        expect(RECORDED, tracker.progress(1, 20_000));
        expect(RECORDED, tracker.end(1));
        expect(IGNORED, tracker.begin(1, "a", "track", "s1", 20_000, CRITERIA));

        expect(PENDING, tracker.begin(2, "a", "track", "s2", -1, CRITERIA));
        expect(IGNORED, tracker.progress(1, 60_000));
        expect(IGNORED, tracker.progress(2, -1));
        expect(PENDING, tracker.progress(2, 29_999));
        expect(RECORDED, tracker.progress(2, 30_000));
        expect(REJECTED, tracker.begin(4, "", "track", "s3", 0, CRITERIA));
        expect(IGNORED, tracker.progress(2, 60_000));
        expect(IGNORED, tracker.begin(3, "a", "track", "s3", 0, CRITERIA));
        expect(PENDING, tracker.begin(5, "b", "track", "s1", 20_000, CRITERIA));
        expect(RECORDED, tracker.progress(5, 10_000));

        PlaybackSessionTracker reopened = new PlaybackSessionTracker(file);
        expect(PENDING, reopened.begin(1, "a", "track", "s1", 20_000, CRITERIA));
        expect(ALREADY_RECORDED, reopened.progress(1, 10_000));
        QualifiedPlayCounter reader = new QualifiedPlayCounter(file);
        if (reader.count("a", "track") != 2 || reader.count("b", "track") != 1) {
            throw new AssertionError("expected two distinct plays for a and one for b");
        }
    }

    private static void checkRetry(Path file) throws Exception {
        Files.createDirectory(file);
        PlaybackSessionTracker tracker = new PlaybackSessionTracker(file);
        expect(PENDING, tracker.begin(1, "a", "track", "s1", 20_000, CRITERIA));
        expect(UNAVAILABLE, tracker.progress(1, 10_000));
        Files.delete(file);
        expect(RECORDED, tracker.progress(1, 10_000));
    }

    private static void expect(PlaybackSessionTracker.Result expected,
            PlaybackSessionTracker.Result actual) {
        if (expected != actual) throw new AssertionError("expected " + expected + ", got " + actual);
    }
}
