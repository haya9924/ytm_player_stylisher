package app.morphe.extension.ym;

public final class CacheRetentionCriteriaTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void equal(long actual, long expected, String message) {
        check(actual == expected, message + ": " + actual + " != " + expected);
    }

    private static void required(CacheRetentionCriteria criteria, long duration, long expected) {
        equal(criteria.requiredListenedMillis(duration),
                expected, "required for duration " + duration);
    }

    private static void invalid(int playCount, int seconds, int percent) {
        try {
            new CacheRetentionCriteria(playCount, seconds, percent);
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("accepted invalid criteria "
                + playCount + "/" + seconds + "/" + percent);
    }

    public static void main(String[] args) {
        defaults();
        thresholdBeforeAndAtMatch();
        shortTrackRoundsUp();
        hugeAndUnknownDurations();
        changesAndLimits();
        invalidInputRejected();
        System.out.println("CacheRetentionCriteria tests passed");
    }

    private static void defaults() {
        equal(CacheRetentionCriteria.DEFAULT_PLAY_COUNT, 2, "default play count");
        equal(CacheRetentionCriteria.DEFAULT_SECONDS, 30, "default seconds");
        equal(CacheRetentionCriteria.DEFAULT_PERCENT, 50, "default percent");

        CacheRetentionCriteria criteria = new CacheRetentionCriteria(
                CacheRetentionCriteria.DEFAULT_PLAY_COUNT,
                CacheRetentionCriteria.DEFAULT_SECONDS,
                CacheRetentionCriteria.DEFAULT_PERCENT);
        equal(criteria.playCount, 2, "playCount field");
        equal(criteria.seconds, 30, "seconds field");
        equal(criteria.percent, 50, "percent field");

        // Default playback: 40s * 50% = 20s, below the 30s cap.
        required(criteria, 40000L, 20000L);
        check(!criteria.countsPlayback(19999L, 40000L), "just below must not count");
        check(criteria.countsPlayback(20000L, 40000L), "exact threshold must count");

        check(!criteria.shouldRetain(0), "0 plays must not retain");
        check(!criteria.shouldRetain(1), "1 play must not retain");
        check(criteria.shouldRetain(2), "2 plays must retain");
        check(criteria.shouldRetain(3), "above play count must retain");
    }

    private static void thresholdBeforeAndAtMatch() {
        CacheRetentionCriteria criteria = new CacheRetentionCriteria(3, 30, 50);

        // 60s * 50% = 30s, exactly the seconds cap.
        required(criteria, 60000L, 30000L);
        check(!criteria.countsPlayback(29999L, 60000L), "just below cap must not count");
        check(criteria.countsPlayback(30000L, 60000L), "at cap must count");

        // Longer track stays capped at 30s.
        required(criteria, 600000L, 30000L);
        check(!criteria.countsPlayback(29999L, 600000L), "below cap on long track");
        check(criteria.countsPlayback(30000L, 600000L), "at cap on long track");

        check(!criteria.shouldRetain(2), "2 of 3 plays must not retain");
        check(criteria.shouldRetain(3), "3 of 3 plays must retain");
    }

    private static void shortTrackRoundsUp() {
        CacheRetentionCriteria criteria = new CacheRetentionCriteria(2, 30, 50);
        // One millisecond at 50% rounds up to 1ms, never 0ms.
        required(criteria, 1L, 1L);
        required(criteria, 3L, 2L);
        required(criteria, 101L, 51L);
        required(criteria, 1001L, 501L);
        check(!criteria.countsPlayback(500L, 1001L), "rounded threshold below");
        check(criteria.countsPlayback(501L, 1001L), "rounded threshold at");

        CacheRetentionCriteria onePercent = new CacheRetentionCriteria(2, 30, 1);
        required(onePercent, 1000L, 10L);
        required(onePercent, 1L, 1L);
        required(onePercent, 200L, 2L);

        CacheRetentionCriteria full = new CacheRetentionCriteria(2, 30, 100);
        required(full, 1L, 1L);
        required(full, 12345L, 12345L);
        required(full, 29999L, 29999L);
        required(full, 30000L, 30000L);
    }

    private static void hugeAndUnknownDurations() {
        CacheRetentionCriteria criteria = new CacheRetentionCriteria(2, 30, 50);
        required(criteria, Long.MAX_VALUE, 30000L);
        required(criteria, Long.MAX_VALUE / 2L, 30000L);
        required(criteria, 3000000000L, 30000L);

        // Unknown, zero and negative durations fall back to the seconds cap only.
        required(criteria, 0L, 30000L);
        required(criteria, -1L, 30000L);
        required(criteria, Long.MIN_VALUE, 30000L);
        check(criteria.countsPlayback(30000L, 0L), "unknown duration at cap counts");
        check(!criteria.countsPlayback(29999L, 0L), "unknown duration below cap");

        CacheRetentionCriteria maximum = new CacheRetentionCriteria(
                CacheRetentionCriteria.MAX_PLAY_COUNT,
                CacheRetentionCriteria.MAX_SECONDS,
                CacheRetentionCriteria.MAX_PERCENT);
        required(maximum, Long.MAX_VALUE, 3600000L);
        required(maximum, 7200000L, 3600000L);
        required(maximum, 3600000L, 3600000L);
        check(maximum.shouldRetain(999), "maximum play count reached");
        check(!maximum.shouldRetain(998), "below maximum play count");
    }

    private static void changesAndLimits() {
        CacheRetentionCriteria changed = new CacheRetentionCriteria(5, 10, 25);
        equal(changed.playCount, 5, "changed playCount");
        equal(changed.seconds, 10, "changed seconds");
        equal(changed.percent, 25, "changed percent");
        required(changed, 40000L, 10000L);
        required(changed, 8000L, 2000L);
        check(changed.countsPlayback(2000L, 8000L), "changed percentage threshold");
        check(!changed.countsPlayback(1999L, 8000L), "changed percentage below");
        check(!changed.shouldRetain(4), "changed play count below");
        check(changed.shouldRetain(5), "changed play count at");

        CacheRetentionCriteria minimum = new CacheRetentionCriteria(1, 1, 1);
        equal(minimum.playCount, 1, "minimum playCount");
        equal(minimum.seconds, 1, "minimum seconds");
        equal(minimum.percent, 1, "minimum percent");
        required(minimum, 0L, 1000L);
        required(minimum, 100000L, 1000L);
        required(minimum, 500L, 5L);
        check(minimum.shouldRetain(1), "minimum play count at");
        check(!minimum.shouldRetain(0), "minimum play count below");

        equal(CacheRetentionCriteria.MAX_PLAY_COUNT, 999, "max play count");
        equal(CacheRetentionCriteria.MAX_SECONDS, 3600, "max seconds");
        equal(CacheRetentionCriteria.MAX_PERCENT, 100, "max percent");
    }

    private static void invalidInputRejected() {
        invalid(0, 30, 50);
        invalid(1000, 30, 50);
        invalid(-1, 30, 50);

        invalid(2, 0, 50);
        invalid(2, 3601, 50);
        invalid(2, -1, 50);

        invalid(2, 30, 0);
        invalid(2, 30, 101);
        invalid(2, 30, -1);

        invalid(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE);
        invalid(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
    }
}
