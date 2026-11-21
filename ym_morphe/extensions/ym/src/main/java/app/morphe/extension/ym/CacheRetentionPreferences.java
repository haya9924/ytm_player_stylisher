package app.morphe.extension.ym;

import android.content.SharedPreferences;

/**
 * Reads and saves the cache-retention criteria in the existing settings file.
 * Storage only: no play counting, no media or cache access.
 */
public final class CacheRetentionPreferences {
    static final String PLAY_COUNT = "cache_retention_play_count";
    static final String SECONDS = "cache_retention_seconds";
    static final String PERCENT = "cache_retention_percent";

    private CacheRetentionPreferences() {}

    /**
     * Returns the saved criteria. A missing, out-of-range or wrongly typed value
     * falls back to its own default without affecting the other two values.
     */
    static CacheRetentionCriteria read(SharedPreferences prefs) {
        return new CacheRetentionCriteria(
                readInt(prefs, PLAY_COUNT, CacheRetentionCriteria.DEFAULT_PLAY_COUNT,
                        CacheRetentionCriteria.MAX_PLAY_COUNT),
                readInt(prefs, SECONDS, CacheRetentionCriteria.DEFAULT_SECONDS,
                        CacheRetentionCriteria.MAX_SECONDS),
                readInt(prefs, PERCENT, CacheRetentionCriteria.DEFAULT_PERCENT,
                        CacheRetentionCriteria.MAX_PERCENT));
    }

    /**
     * Saves all three values together in a single editor commit. Returns false and
     * writes nothing when any value is missing, out of range or the prefs are unavailable.
     */
    static boolean save(SharedPreferences prefs, int playCount, int seconds, int percent) {
        if (prefs == null) return false;
        try {
            // The criteria constructor rejects values outside the supported ranges.
            new CacheRetentionCriteria(playCount, seconds, percent);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
        try {
            prefs.edit()
                    .putInt(PLAY_COUNT, playCount)
                    .putInt(SECONDS, seconds)
                    .putInt(PERCENT, percent)
                    .apply();
            return true;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private static int readInt(SharedPreferences prefs, String key, int fallback, int max) {
        if (prefs == null) return fallback;
        try {
            int value = prefs.getInt(key, fallback);
            return value < 1 || value > max ? fallback : value;
        } catch (RuntimeException missingOrWrongType) {
            return fallback;
        }
    }
}
