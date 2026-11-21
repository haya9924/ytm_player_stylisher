package app.morphe.extension.ym;

/**
 * Decides when a track counts as fully listened and when cached audio may be retained.
 * Pure value object: no Android or media dependencies.
 */
final class CacheRetentionCriteria {
    static final int DEFAULT_PLAY_COUNT = 2;
    static final int DEFAULT_SECONDS = 30;
    static final int DEFAULT_PERCENT = 50;
    static final int MAX_PLAY_COUNT = 999;
    static final int MAX_SECONDS = 3600;
    static final int MAX_PERCENT = 100;

    private static final int MIN = 1;

    final int playCount;
    final int seconds;
    final int percent;

    CacheRetentionCriteria(int playCount, int seconds, int percent) {
        if (playCount < MIN || playCount > MAX_PLAY_COUNT) {
            throw new IllegalArgumentException("playCount out of range: " + playCount);
        }
        if (seconds < MIN || seconds > MAX_SECONDS) {
            throw new IllegalArgumentException("seconds out of range: " + seconds);
        }
        if (percent < MIN || percent > MAX_PERCENT) {
            throw new IllegalArgumentException("percent out of range: " + percent);
        }
        this.playCount = playCount;
        this.seconds = seconds;
        this.percent = percent;
    }

    /**
     * Threshold in milliseconds: the smaller of the fixed seconds cap and the
     * percentage of the track. Unknown or non-positive durations use only the
     * seconds cap. Ceiling division keeps short tracks from being undercounted.
     */
    long requiredListenedMillis(long durationMillis) {
        long secondsMillis = seconds * 1000L;
        if (durationMillis <= 0L) return secondsMillis;
        // Also guarantees durationMillis * percent cannot overflow below.
        if (durationMillis >= secondsMillis * 100L) return secondsMillis;
        long scaled = (durationMillis * percent + 99L) / 100L;
        return Math.min(secondsMillis, scaled);
    }

    boolean countsPlayback(long listenedMillis, long durationMillis) {
        return listenedMillis >= requiredListenedMillis(durationMillis);
    }

    boolean shouldRetain(int qualifiedPlayCount) {
        return qualifiedPlayCount >= playCount;
    }
}
