package app.morphe.extension.ym;

/**
 * JVM tests for the pure-Java data-savings estimator.
 *
 * <p>Every assertion is on derived values only: the class reads no clock, no file, and no Android
 * API, so the same inputs must always produce the same estimate.
 */
public final class CacheSavingsEstimatorTest {
    private static final long MIB = 1024L * 1024L;
    private static final CacheRetentionCriteria DEFAULT =
            new CacheRetentionCriteria(2, 30, 50);

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        defaults();
        retentionOnVersusOff();
        budgetIncreasesTotal();
        playCountReducesBonus();
        secondsAndPercentAffectStrictness();
        observedHistoryDrivesAverages();
        capacityWarnings();
        invalidInputsAreClamped();
        monotonicLruRatio();
        defensiveCopies();
        System.out.println("CacheSavingsEstimator tests passed");
    }

    private static CacheSavingsEstimator.Snapshot snapshot(long nativeBudget, int tracks,
            long audioBytes, int[] plays, long usable) {
        return new CacheSavingsEstimator.Snapshot(nativeBudget, 0L, tracks, audioBytes, plays, 0, usable);
    }

    private static CacheSavingsEstimator.Estimate estimate(CacheSavingsEstimator.Snapshot snapshot,
            boolean retention, int budgetMiB, CacheRetentionCriteria criteria) {
        return CacheSavingsEstimator.estimate(snapshot, retention, budgetMiB, criteria);
    }

    private static void defaults() {
        check(CacheSavingsEstimator.DEFAULT_STANDARD_BUDGET_BYTES == 256L * MIB,
                "default standard budget is 256 MiB");
        check(CacheSavingsEstimator.DEFAULT_AVG_TRACK_BYTES == 3_800_000L,
                "default average track is ~3.8 MB");
        check(CacheSavingsEstimator.REFERENCE_TRACK_SECONDS == 210L, "reference song is 210s");
        check(CacheSavingsEstimator.MAX_PROTECTED_TRACKS == 50, "at most 50 protected tracks");

        CacheSavingsEstimator.Estimate on = estimate(
                snapshot(0L, 0, 0L, null, 0L), true, 0, DEFAULT);
        check(on.effectiveBudgetBytes == CacheSavingsEstimator.DEFAULT_STANDARD_BUDGET_BYTES,
                "missing native budget falls back to the standard budget");
        check(on.standardBudgetBytes == CacheSavingsEstimator.DEFAULT_STANDARD_BUDGET_BYTES,
                "standard budget is the fallback");
        check(on.avgTrackBytes == CacheSavingsEstimator.DEFAULT_AVG_TRACK_BYTES,
                "no observed cache uses the default average");
        check(on.capacityTracks > 1, "capacity is derived from budget and average");
        check(on.effectiveListenSeconds == 30L, "default effective listen window is 30s");
        check(on.recordedTrackCount == 0 && on.recordedQualifyingCount == 0,
                "no recorded history");
        check(!on.usedObservedHistory, "no observed history below five tracks");
        check(on.estimatedProtectedTracks > 0, "retention protects qualifying tracks");
        check(on.estimatedProtectedTracks <= CacheSavingsEstimator.MAX_PROTECTED_TRACKS,
                "protected tracks are bounded");
        check(on.monthlyStandardSavedBytes > 0L, "standard LRU already saves something");
        check(on.monthlyRetentionBonusBytes > 0L, "retention adds savings");
        check(on.monthlyTotalSavedBytes == on.monthlyStandardSavedBytes + on.monthlyRetentionBonusBytes,
                "total is plain LRU plus the retention bonus");
        check(on.monthlyDeltaVsStandardBytes
                == on.monthlyTotalSavedBytes - on.monthlyStandardSavedBytes, "delta is derived");
        check(!on.lowStorageWarning, "unknown storage never warns");
        check(!on.budgetTightForProtectedWarning || on.estimatedProtectedTracks > 0,
                "tight warning requires protected tracks");
    }

    private static void retentionOnVersusOff() {
        CacheSavingsEstimator.Snapshot input = snapshot(256L * MIB, 64, 64L * 3_800_000L,
                null, 0L);
        CacheSavingsEstimator.Estimate on = estimate(input, true, 0, DEFAULT);
        CacheSavingsEstimator.Estimate off = estimate(input, false, 0, DEFAULT);

        check(off.estimatedProtectedTracks == 0, "disabled retention protects nothing");
        check(off.monthlyRetentionBonusBytes == 0L, "disabled retention has no bonus");
        check(off.monthlyTotalSavedBytes == off.monthlyStandardSavedBytes,
                "disabled retention equals plain LRU");
        check(off.monthlyDeltaVsStandardBytes == 0L, "disabled retention has no delta");
        check(off.monthlyStandardSavedBytes == on.monthlyStandardSavedBytes,
                "the standard estimate is independent of the retention flag");
        check(on.monthlyTotalSavedBytes > off.monthlyTotalSavedBytes,
                "enabled retention saves more than plain LRU");
        check(on.monthlyDeltaVsStandardBytes > 0L, "enabled retention beats the standard budget");
    }

    private static void budgetIncreasesTotal() {
        CacheSavingsEstimator.Snapshot input = snapshot(256L * MIB, 400, 400L * 3_500_000L,
                new int[] {5, 4, 3, 2, 2, 1, 1}, 0L);
        // A chosen budget is always exact; only increasing chosen budgets must be monotonic.
        long previousTotal = -1L;
        long previousCapacity = -1;
        int[] budgets = {16, 32, 64, 128, 256, 512, 1024, 2048};
        for (int budget : budgets) {
            CacheSavingsEstimator.Estimate e = estimate(input, true, budget, DEFAULT);
            check(e.monthlyTotalSavedBytes >= previousTotal,
                    "budget " + budget + " must not reduce total savings: "
                            + e.monthlyTotalSavedBytes + " < " + previousTotal);
            check(e.capacityTracks >= previousCapacity, "budget never reduces capacity");
            check(e.effectiveBudgetBytes == budget * MIB, "chosen budget is honoured");
            previousTotal = e.monthlyTotalSavedBytes;
            previousCapacity = e.capacityTracks;
        }
        // 0 is "automatic": it resolves to the host budget rather than to the smallest budget.
        CacheSavingsEstimator.Estimate automatic = estimate(input, true, 0, DEFAULT);
        check(automatic.effectiveBudgetBytes == 256L * MIB,
                "0 uses the reported host budget");
        check(automatic.monthlyTotalSavedBytes
                == estimate(input, true, 256, DEFAULT).monthlyTotalSavedBytes,
                "0 and an explicit host-sized budget agree");
    }

    private static void playCountReducesBonus() {
        CacheSavingsEstimator.Snapshot input = snapshot(256L * MIB, 300, 300L * 3_600_000L,
                new int[] {9, 8, 7, 6, 5, 4, 3, 2, 1}, 0L);
        long previousBonus = Long.MAX_VALUE;
        int previousProtected = Integer.MAX_VALUE;
        int previousQualifying = Integer.MAX_VALUE;
        for (int playCount = 1; playCount <= 100; playCount++) {
            CacheRetentionCriteria criteria = new CacheRetentionCriteria(playCount, 30, 50);
            CacheSavingsEstimator.Estimate e = estimate(input, true, 0, criteria);
            check(e.recordedQualifyingCount <= previousQualifying,
                    "play count " + playCount + " must not increase qualifying tracks");
            check(e.estimatedProtectedTracks <= previousProtected,
                    "play count " + playCount + " must not increase protected tracks");
            check(e.monthlyRetentionBonusBytes <= previousBonus,
                    "play count " + playCount + " must not increase the bonus");
            previousBonus = e.monthlyRetentionBonusBytes;
            previousProtected = e.estimatedProtectedTracks;
            previousQualifying = e.recordedQualifyingCount;
        }
        check(previousBonus == 0L, "a very high play count removes the bonus entirely");
    }

    private static void secondsAndPercentAffectStrictness() {
        CacheSavingsEstimator.Snapshot input = snapshot(256L * MIB, 120, 120L * 3_800_000L,
                null, 0L);

        check(estimate(input, true, 0, new CacheRetentionCriteria(2, 5, 100))
                .effectiveListenSeconds == 5L, "fixed seconds caps the listen window");
        check(estimate(input, true, 0, new CacheRetentionCriteria(2, 3600, 100))
                .effectiveListenSeconds == 210L, "the whole reference song is the cap");
        check(estimate(input, true, 0, new CacheRetentionCriteria(2, 3600, 10))
                .effectiveListenSeconds == 21L, "10 percent of 210s is 21s");
        check(estimate(input, true, 0, new CacheRetentionCriteria(2, 3600, 50))
                .effectiveListenSeconds == 105L, "50 percent of 210s is 105s");

        // Increasing seconds above 30s lowers strictness and therefore the retention bonus.
        long previousBonus = Long.MAX_VALUE;
        for (int seconds : new int[] {30, 45, 60, 90, 120, 180, 300}) {
            CacheSavingsEstimator.Estimate e = estimate(input, true, 0,
                    new CacheRetentionCriteria(2, seconds, 100));
            check(e.monthlyRetentionBonusBytes <= previousBonus,
                    "seconds " + seconds + " must not increase the bonus");
            previousBonus = e.monthlyRetentionBonusBytes;
        }

        // A higher percent is a stricter criterion too: the effective window grows.
        long fivePercentBonus = estimate(input, true, 0,
                new CacheRetentionCriteria(2, 3600, 5)).monthlyRetentionBonusBytes;
        long fiftyPercentBonus = estimate(input, true, 0,
                new CacheRetentionCriteria(2, 3600, 50)).monthlyRetentionBonusBytes;
        check(fivePercentBonus >= fiftyPercentBonus,
                "a stricter percentage must not increase the bonus");
    }

    private static void observedHistoryDrivesAverages() {
        CacheSavingsEstimator.Snapshot observed = snapshot(256L * MIB, 100, 100L * 5_000_000L,
                new int[] {4, 4, 3, 2, 1}, 0L);
        CacheSavingsEstimator.Estimate e = estimate(observed, true, 0, DEFAULT);
        check(e.avgTrackBytes == 5_000_000L, "cached bytes / tracks drive the average: " + e.avgTrackBytes);
        check(e.usedObservedHistory, "five recorded tracks use observed history");
        check(e.recordedTrackCount == 5, "recorded track count");
        check(e.recordedQualifyingCount == 4, "counts >= playCount qualify: " + e.recordedQualifyingCount);

        CacheSavingsEstimator.Estimate absent = estimate(
                snapshot(256L * MIB, 0, 0L, new int[] {4}, 0L), true, 0, DEFAULT);
        check(!absent.usedObservedHistory, "one count is below the observed-history threshold");
        check(absent.recordedTrackCount == 1 && absent.recordedQualifyingCount == 1,
                "a single count is still reported");
        check(!estimate(snapshot(256L * MIB, 0, 0L, new int[0], 0L), true, 0, DEFAULT)
                .usedObservedHistory, "an empty history is not observed");

        CacheSavingsEstimator.Estimate tiny = estimate(
                snapshot(256L * MIB, 10, 0L, null, 0L), true, 0, DEFAULT);
        check(tiny.avgTrackBytes == CacheSavingsEstimator.DEFAULT_AVG_TRACK_BYTES,
                "a non-positive cached size falls back to the default average");

        CacheSavingsEstimator.Estimate clampedLow = estimate(
                snapshot(256L * MIB, 100, 100L * 100L, null, 0L), true, 0, DEFAULT);
        check(clampedLow.avgTrackBytes == 1_000_000L,
                "the observed average is clamped from below: " + clampedLow.avgTrackBytes);
        CacheSavingsEstimator.Estimate clampedHigh = estimate(
                snapshot(256L * MIB, 10, 10L * 20_000_000L, null, 0L), true, 0, DEFAULT);
        check(clampedHigh.avgTrackBytes == 12_000_000L,
                "the observed average is clamped from above: " + clampedHigh.avgTrackBytes);

        // Observed history changes the modelled monthly volume, so the totals stay sane.
        check(e.monthlyTotalSavedBytes > 0L && e.monthlyTotalSavedBytes < 10L * 1024L * MIB,
                "observed-history savings stay in a plausible range");
    }

    private static void capacityWarnings() {
        CacheSavingsEstimator.Snapshot tight = snapshot(256L * MIB, 10, 10L * 3_800_000L, null,
                100L * MIB);
        CacheSavingsEstimator.Estimate low = estimate(tight, true, 0, DEFAULT);
        check(low.lowStorageWarning, "a budget above half the free storage warns");

        CacheSavingsEstimator.Estimate roomy = estimate(
                snapshot(256L * MIB, 10, 10L * 3_800_000L, null, 8L * 1024L * MIB),
                true, 0, DEFAULT);
        check(!roomy.lowStorageWarning, "plenty of free storage does not warn");

        CacheSavingsEstimator.Estimate unknown = estimate(
                snapshot(256L * MIB, 10, 10L * 3_800_000L, null, 0L), true, 0, DEFAULT);
        check(!unknown.lowStorageWarning, "unknown storage does not warn");

        // A chosen budget larger than half the free storage also warns.
        CacheSavingsEstimator.Estimate chosen = estimate(
                snapshot(256L * MIB, 10, 10L * 3_800_000L, null, 128L * MIB),
                true, 128, DEFAULT);
        check(chosen.lowStorageWarning, "the chosen budget is compared to free storage");

        CacheSavingsEstimator.Estimate tightBudget = estimate(
                snapshot(256L * MIB, 0, 0L, null, 0L), true, 16, DEFAULT);
        check(tightBudget.budgetTightForProtectedWarning,
                "a 16 MiB cache cannot hold a protected working set");
        CacheSavingsEstimator.Estimate wideBudget = estimate(
                snapshot(256L * MIB, 0, 0L, null, 0L), true, 512, DEFAULT);
        check(!wideBudget.budgetTightForProtectedWarning,
                "a wide cache is not reported as tight");
        CacheSavingsEstimator.Estimate disabled = estimate(
                snapshot(256L * MIB, 0, 0L, null, 0L), false, 16, DEFAULT);
        check(!disabled.budgetTightForProtectedWarning,
                "disabled retention never reports a protected-budget warning");
    }

    private static void invalidInputsAreClamped() {
        CacheSavingsEstimator.Snapshot input = snapshot(128L * MIB, 0, 0L, null, 0L);
        for (int budget : new int[] {-1, 1, 15, 2049, Integer.MAX_VALUE}) {
            CacheSavingsEstimator.Estimate e = estimate(input, true, budget, DEFAULT);
            check(e.effectiveBudgetBytes == 128L * MIB,
                    "invalid budget " + budget + " falls back to the host budget");
        }
        check(estimate(input, true, 16, DEFAULT).effectiveBudgetBytes == 16L * MIB,
                "the minimum valid budget is honoured");
        check(estimate(input, true, 2048, DEFAULT).effectiveBudgetBytes == 2048L * MIB,
                "the maximum valid budget is honoured");
        try {
            estimate(input, true, 0, null);
            check(false, "a null criteria must be rejected");
        } catch (NullPointerException expected) {
            // Expected contract.
        }
        try {
            CacheSavingsEstimator.estimate(null, true, 0, DEFAULT);
            check(false, "a null snapshot must be rejected");
        } catch (NullPointerException expected) {
            // Expected contract.
        }
    }

    private static void monotonicLruRatio() {
        double previous = -1.0;
        for (int cap = 1; cap <= 400; cap++) {
            double ratio = CacheSavingsEstimator.lruHitRatio(cap, 150.0);
            check(ratio >= 0.02 && ratio <= 0.92, "hit ratio is bounded: " + ratio);
            check(ratio > previous, "hit ratio strictly increases at cap " + cap);
            previous = ratio;
        }
    }

    private static void defensiveCopies() {
        int[] plays = {3, 2, 1};
        CacheSavingsEstimator.Snapshot input = snapshot(256L * MIB, 0, 0L, plays, 0L);
        plays[0] = 999;
        check(input.recordedPlayCounts[0] == 3, "the snapshot keeps a defensive copy");

        CacheSavingsEstimator.Snapshot empty = snapshot(256L * MIB, 0, 0L, null, 0L);
        check(empty.recordedPlayCounts.length == 0, "a null history becomes an empty array");
        check(estimate(empty, true, 0, DEFAULT).recordedTrackCount == 0,
                "an empty history stays empty in the estimate");
        check(estimate(empty, true, 0, DEFAULT).monthlyTotalSavedBytes > 0L,
                "an empty history still produces the baseline estimate");
    }
}
