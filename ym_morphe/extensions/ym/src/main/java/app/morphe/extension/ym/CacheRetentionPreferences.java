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
    static final String RETENTION_ENABLED = "cache_retention_enabled";
    static final String CACHE_BUDGET_MIB = "cache_budget_mib";

    /** {@code 0} means the automatic/standard host budget is used. */
    static final int DEFAULT_CACHE_BUDGET_MIB = 0;
    static final int MIN_CACHE_BUDGET_MIB = 16;
    static final int MAX_CACHE_BUDGET_MIB = 2048;

    private CacheRetentionPreferences() {}

    /** Whether priority cache retention is enabled. Defaults to {@code true}. */
    static boolean readRetentionEnabled(SharedPreferences prefs) {
        if (prefs == null) return true;
        try {
            return prefs.getBoolean(RETENTION_ENABLED, true);
        } catch (RuntimeException missingOrWrongType) {
            return true;
        }
    }

    /**
     * Returns the user cache budget in MiB. {@code 0} (automatic) and any value inside
     * {@code [MIN_CACHE_BUDGET_MIB, MAX_CACHE_BUDGET_MIB]} are accepted; anything else falls back
     * to {@link #DEFAULT_CACHE_BUDGET_MIB} and therefore to the host default.
     */
    static int readBudgetMiB(SharedPreferences prefs) {
        if (prefs == null) return DEFAULT_CACHE_BUDGET_MIB;
        try {
            int value = prefs.getInt(CACHE_BUDGET_MIB, DEFAULT_CACHE_BUDGET_MIB);
            return isValidBudgetMiB(value) ? value : DEFAULT_CACHE_BUDGET_MIB;
        } catch (RuntimeException missingOrWrongType) {
            return DEFAULT_CACHE_BUDGET_MIB;
        }
    }

    /** {@code 0} (automatic) or a value inside the supported budget range. */
    static boolean isValidBudgetMiB(int budgetMiB) {
        return budgetMiB == 0
                || (budgetMiB >= MIN_CACHE_BUDGET_MIB && budgetMiB <= MAX_CACHE_BUDGET_MIB);
    }

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

    /**
     * Saves the retention flag, the budget, and the three criteria values in one atomic editor
     * commit. Returns false and writes nothing when the budget or any criterion is invalid.
     */
    static boolean saveAll(SharedPreferences prefs, boolean retentionEnabled, int budgetMiB,
            int playCount, int seconds, int percent) {
        if (prefs == null || !isValidBudgetMiB(budgetMiB)) return false;
        try {
            // The criteria constructor rejects values outside the supported ranges.
            new CacheRetentionCriteria(playCount, seconds, percent);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
        try {
            prefs.edit()
                    .putBoolean(RETENTION_ENABLED, retentionEnabled)
                    .putInt(CACHE_BUDGET_MIB, budgetMiB)
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
