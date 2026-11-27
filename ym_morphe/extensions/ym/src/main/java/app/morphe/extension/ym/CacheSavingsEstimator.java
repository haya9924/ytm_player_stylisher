package app.morphe.extension.ym;

/**
 * Pure estimate of how many bytes cache retention can save per month.
 *
 * <p>This is a value/calculation class on purpose: it has no Android dependency, performs no I/O,
 * and keeps no state, so it runs unchanged in standard {@code --release 17} JVM tests.
 *
 * <p>The model is deliberately conservative. It starts from a typical monthly working set and
 * repeat-play baseline, derives an LRU hit ratio from the usable cache capacity, and only then adds
 * the extra repeat plays that priority retention can rescue from eviction. Observed playback
 * history, when enough of it exists, replaces part of the modelled qualifying-track ratio.
 */
final class CacheSavingsEstimator {
    /** Fallback host budget when the native backend has not reported one yet. */
    static final long DEFAULT_STANDARD_BUDGET_BYTES = 256L * 1024L * 1024L;
    /** Observed average audio track size on the reference device (~3.8 MB). */
    static final long DEFAULT_AVG_TRACK_BYTES = 3_800_000L;
    /** Reference song length used to normalize the listening criteria (3m30s). */
    static final long REFERENCE_TRACK_SECONDS = 210L;
    /** Matches {@code NativeCacheRetention.MAX_TRACKS}. */
    static final int MAX_PROTECTED_TRACKS = 50;

    /*
     * Budget validation is duplicated here instead of referencing CacheRetentionPreferences, whose
     * SharedPreferences parameter would drag Android into the pure-Java test compile. Keep these in
     * sync with CacheRetentionPreferences.MIN_CACHE_BUDGET_MIB / MAX_CACHE_BUDGET_MIB.
     */
    private static final int MIN_BUDGET_MIB = 16;
    private static final int MAX_BUDGET_MIB = 2048;

    private static final long MIB = 1024L * 1024L;
    private static final long MIN_AVG_TRACK_BYTES = 1_000_000L;
    private static final long MAX_AVG_TRACK_BYTES = 12_000_000L;
    private static final int MAX_CAPACITY_TRACKS = 100_000;
    private static final double DEFAULT_WORKING_SET_TRACKS = 150.0;
    private static final double DEFAULT_MONTHLY_PLAYS = 600.0;
    private static final int[] NO_PLAYS = new int[0];

    private CacheSavingsEstimator() {}

    /** Inputs read from the live cache and the durable playback counter at one instant. */
    static final class Snapshot {
        /** Native host budget in bytes; values {@code <= 0} fall back to the standard budget. */
        final long nativeBudgetBytes;
        final long currentUsedBytes;
        final int cachedAudioTracks;
        final long cachedAudioBytes;
        /** Recorded session counts per track; possibly empty, never mutated. */
        final int[] recordedPlayCounts;
        final int sessionSparedCount;
        /** Free storage in bytes; values {@code <= 0} mean unknown. */
        final long usableStorageBytes;

        Snapshot(long nativeBudgetBytes, long currentUsedBytes, int cachedAudioTracks,
                long cachedAudioBytes, int[] recordedPlayCounts, int sessionSparedCount,
                long usableStorageBytes) {
            this.nativeBudgetBytes = nativeBudgetBytes;
            this.currentUsedBytes = currentUsedBytes;
            this.cachedAudioTracks = cachedAudioTracks;
            this.cachedAudioBytes = cachedAudioBytes;
            this.recordedPlayCounts = recordedPlayCounts == null || recordedPlayCounts.length == 0
                    ? NO_PLAYS
                    : recordedPlayCounts.clone();
            this.sessionSparedCount = sessionSparedCount;
            this.usableStorageBytes = usableStorageBytes;
        }
    }

    /** Immutable result of {@link #estimate}. */
    static final class Estimate {
        final long effectiveBudgetBytes;
        final long standardBudgetBytes;
        final long avgTrackBytes;
        final int capacityTracks;
        final long effectiveListenSeconds;
        final int recordedTrackCount;
        final int recordedQualifyingCount;
        final int estimatedProtectedTracks;
        final long monthlyStandardSavedBytes;
        final long monthlyTotalSavedBytes;
        final long monthlyRetentionBonusBytes;
        final long monthlyDeltaVsStandardBytes;
        final boolean usedObservedHistory;
        final boolean lowStorageWarning;
        final boolean budgetTightForProtectedWarning;

        Estimate(long effectiveBudgetBytes, long standardBudgetBytes, long avgTrackBytes,
                int capacityTracks, long effectiveListenSeconds, int recordedTrackCount,
                int recordedQualifyingCount, int estimatedProtectedTracks,
                long monthlyStandardSavedBytes, long monthlyTotalSavedBytes,
                long monthlyRetentionBonusBytes, long monthlyDeltaVsStandardBytes,
                boolean usedObservedHistory, boolean lowStorageWarning,
                boolean budgetTightForProtectedWarning) {
            this.effectiveBudgetBytes = effectiveBudgetBytes;
            this.standardBudgetBytes = standardBudgetBytes;
            this.avgTrackBytes = avgTrackBytes;
            this.capacityTracks = capacityTracks;
            this.effectiveListenSeconds = effectiveListenSeconds;
            this.recordedTrackCount = recordedTrackCount;
            this.recordedQualifyingCount = recordedQualifyingCount;
            this.estimatedProtectedTracks = estimatedProtectedTracks;
            this.monthlyStandardSavedBytes = monthlyStandardSavedBytes;
            this.monthlyTotalSavedBytes = monthlyTotalSavedBytes;
            this.monthlyRetentionBonusBytes = monthlyRetentionBonusBytes;
            this.monthlyDeltaVsStandardBytes = monthlyDeltaVsStandardBytes;
            this.usedObservedHistory = usedObservedHistory;
            this.lowStorageWarning = lowStorageWarning;
            this.budgetTightForProtectedWarning = budgetTightForProtectedWarning;
        }
    }

    /**
     * Estimates monthly savings for the snapshot and the chosen preferences.
     *
     * @param snapshot live telemetry; required
     * @param retentionEnabled whether priority retention is active
     * @param budgetMiB chosen budget in MiB; {@code 0} uses the host budget, invalid values are
     *                  treated as {@code 0}
     * @param criteria qualification criteria; required
     * @return an immutable estimate with all derived warnings
     */
    static Estimate estimate(Snapshot snapshot, boolean retentionEnabled, int budgetMiB,
            CacheRetentionCriteria criteria) {
        if (snapshot == null) throw new NullPointerException("snapshot");
        if (criteria == null) throw new NullPointerException("criteria");
        int budget = isValidBudgetMiB(budgetMiB) ? budgetMiB : 0;

        long standardBudgetBytes = snapshot.nativeBudgetBytes > 0L
                ? snapshot.nativeBudgetBytes : DEFAULT_STANDARD_BUDGET_BYTES;
        long effectiveBudgetBytes = budget > 0 ? budget * MIB : standardBudgetBytes;
        long avgTrackBytes = averageTrackBytes(snapshot);
        int capacityTracks = capacityTracks(effectiveBudgetBytes, avgTrackBytes);
        int standardCapacityTracks = capacityTracks(standardBudgetBytes, avgTrackBytes);

        int[] counts = snapshot.recordedPlayCounts;
        int recordedTrackCount = counts.length;
        int recordedQualifyingCount = 0;
        long totalRecordedPlays = 0L;
        for (int count : counts) {
            if (count >= criteria.playCount) recordedQualifyingCount++;
            if (count > 0) totalRecordedPlays += count;
        }
        boolean usedObservedHistory = recordedTrackCount >= 5;

        double workingSetTracks = DEFAULT_WORKING_SET_TRACKS;
        if (usedObservedHistory) {
            workingSetTracks = Math.min(400.0, Math.max(80.0, recordedTrackCount * 3.0));
        }
        // Baseline 600 monthly plays, scaled up (never down) when the observed history is heavier.
        double monthlyTotalPlays = DEFAULT_MONTHLY_PLAYS;
        if (totalRecordedPlays > 0L) {
            monthlyTotalPlays = Math.max(monthlyTotalPlays,
                    Math.max(450.0, Math.min(2400.0, totalRecordedPlays * 4.0)));
        }
        double repeatPlays = Math.max(0.0, monthlyTotalPlays - workingSetTracks);

        // Criteria strictness relative to the 30s / 50% point on a 210s reference song.
        double referenceSeconds = Math.min(criteria.seconds,
                REFERENCE_TRACK_SECONDS * criteria.percent / 100.0);
        double strictness = referenceSeconds <= 30.0
                ? 1.0 + 0.25 * (30.0 - referenceSeconds) / 29.0
                : Math.max(0.05, 30.0 / referenceSeconds);

        long monthlyStandardSavedBytes = Math.round(repeatPlays
                * lruHitRatio(standardCapacityTracks, workingSetTracks) * avgTrackBytes);
        long plainLruAtChosenBudget = Math.round(repeatPlays
                * lruHitRatio(capacityTracks, workingSetTracks) * avgTrackBytes);

        int estimatedProtectedTracks = 0;
        long monthlyRetentionBonusBytes = 0L;
        if (retentionEnabled) {
            double playShare = Math.min(1.0,
                    Math.pow(2.0 / Math.max(1, criteria.playCount), 0.85)) * strictness;
            double qualifyingTracks;
            if (usedObservedHistory && recordedTrackCount > 0) {
                double observedRatio = (double) recordedQualifyingCount / recordedTrackCount;
                double modelRatio = 0.28 * playShare;
                qualifyingTracks = workingSetTracks * (0.65 * observedRatio + 0.35 * modelRatio);
            } else {
                qualifyingTracks = workingSetTracks * Math.min(0.65, 0.24 * playShare);
            }
            qualifyingTracks = Math.max(0.0, Math.min(workingSetTracks, qualifyingTracks));
            estimatedProtectedTracks = (int) Math.min(MAX_PROTECTED_TRACKS,
                    Math.min(capacityTracks, Math.round(qualifyingTracks)));
            if (estimatedProtectedTracks > 0) {
                // Repeats of protected tracks that plain LRU would have evicted. The bounded
                // factors keep tiny caches from claiming a full protected working set.
                double evictedRepeatShare = 1.0 - lruHitRatio(capacityTracks, workingSetTracks);
                double protectedRepeatShare = Math.min(0.80,
                        (double) estimatedProtectedTracks / MAX_PROTECTED_TRACKS * 0.72);
                double tinyCacheFactor = capacityTracks >= 4 ? 1.0 : 0.25;
                double budgetHeadroom = Math.min(1.0, capacityTracks / 8.0);
                double rescuedRepeats = repeatPlays * evictedRepeatShare * protectedRepeatShare
                        * tinyCacheFactor * budgetHeadroom * strictness;
                monthlyRetentionBonusBytes = Math.max(0L, Math.round(rescuedRepeats * avgTrackBytes));
            }
        }
        long monthlyTotalSavedBytes = plainLruAtChosenBudget + monthlyRetentionBonusBytes;
        long monthlyDeltaVsStandardBytes = monthlyTotalSavedBytes - monthlyStandardSavedBytes;

        long effectiveListenSeconds = Math.max(1L, Math.round(Math.min((double) criteria.seconds,
                REFERENCE_TRACK_SECONDS * criteria.percent / 100.0)));
        boolean lowStorageWarning = snapshot.usableStorageBytes > 0L
                && effectiveBudgetBytes > snapshot.usableStorageBytes / 2L;
        boolean budgetTightForProtectedWarning = retentionEnabled && estimatedProtectedTracks > 0
                && (long) estimatedProtectedTracks * avgTrackBytes > (effectiveBudgetBytes * 4L) / 5L;

        return new Estimate(effectiveBudgetBytes, standardBudgetBytes, avgTrackBytes, capacityTracks,
                effectiveListenSeconds, recordedTrackCount, recordedQualifyingCount,
                estimatedProtectedTracks, monthlyStandardSavedBytes, monthlyTotalSavedBytes,
                monthlyRetentionBonusBytes, monthlyDeltaVsStandardBytes, usedObservedHistory,
                lowStorageWarning, budgetTightForProtectedWarning);
    }

    /** Budget validation matching {@code CacheRetentionPreferences.isValidBudgetMiB}. */
    static boolean isValidBudgetMiB(int budgetMiB) {
        return budgetMiB == 0 || (budgetMiB >= MIN_BUDGET_MIB && budgetMiB <= MAX_BUDGET_MIB);
    }

    /**
     * LRU cache-hit fraction for a working set. The shifted and scaled exponential approaches
     * {@code 0.92} asymptotically, so it stays strictly increasing inside {@code [0.02, 0.92]}.
     */
    static double lruHitRatio(int capTracks, double workingSet) {
        double exponent = -1.35 * capTracks / Math.max(1.0, workingSet);
        return 0.02 + 0.90 * (1.0 - Math.exp(exponent));
    }

    private static long averageTrackBytes(Snapshot snapshot) {
        if (snapshot.cachedAudioTracks <= 0 || snapshot.cachedAudioBytes <= 0L) {
            return DEFAULT_AVG_TRACK_BYTES;
        }
        long observed = snapshot.cachedAudioBytes / snapshot.cachedAudioTracks;
        return Math.max(MIN_AVG_TRACK_BYTES, Math.min(MAX_AVG_TRACK_BYTES, observed));
    }

    private static int capacityTracks(long budgetBytes, long avgTrackBytes) {
        long raw = budgetBytes / Math.max(1L, avgTrackBytes);
        return (int) Math.max(1L, Math.min(MAX_CAPACITY_TRACKS, raw));
    }
}
