package app.morphe.extension.ym;

import android.net.TrafficStats;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fixed, low-volume diagnostics for cache retention. Only constant text is
 * logged: no class names, object values, identifiers, files or network access.
 * Every entry point swallows RuntimeExceptions so a diagnostic can never
 * affect playback.
 */
public final class CacheRetentionDiagnostics {
    private static final String TAG = "YmCacheDiag";

    private static final int WATCH_TIME_LIMIT = 32;
    private static final String WATCH_TIME_MESSAGE = "watch-time callback";

    private static final int CACHE_NULL = 1;
    private static final int CACHE_SIMPLE = 1 << 1;
    private static final int CACHE_ALTERNATE = 1 << 2;
    private static final int CACHE_UNKNOWN = 1 << 3;

    private static final String CACHE_NULL_MESSAGE = "cache supplier returned: null";
    private static final String CACHE_SIMPLE_MESSAGE = "cache supplier returned: simple";
    private static final String CACHE_ALTERNATE_MESSAGE = "cache supplier returned: alternate";
    private static final String CACHE_UNKNOWN_MESSAGE = "cache supplier returned: unknown";

    private static final AtomicInteger watchTimeLogs = new AtomicInteger();
    private static final AtomicInteger cacheCategoriesLogged = new AtomicInteger();
    private static final AtomicInteger cachedSpanLogs = new AtomicInteger();
    private static final AtomicInteger progressingLogs = new AtomicInteger();
    private static final AtomicInteger progressSampleLogs = new AtomicInteger();
    private static final AtomicInteger stats2AccumulatedLogs = new AtomicInteger();
    private static final AtomicInteger freshStats2CreatedLogs = new AtomicInteger();
    private static final AtomicInteger freshStats2AccumulatedLogs = new AtomicInteger();
    private static final AtomicInteger stats2DispatchedLogs = new AtomicInteger();
    private static final AtomicInteger capacityRemovalLogs = new AtomicInteger();
    private static final AtomicInteger capacityAccountingLogs = new AtomicInteger();

    private static final int STATS2_ACCUMULATED_LIMIT = 32;

    /** Unconditional capacity budget lines, kept small so the probe stays quiet. */
    private static final int CAPACITY_BUDGET_LOG_LIMIT = 4;
    /** Additional lines emitted only when the over-budget state flips. */
    private static final int CAPACITY_PRESSURE_LOG_LIMIT = 16;
    private static final int PRESSURE_UNKNOWN = 0;
    private static final int PRESSURE_WITHIN_BUDGET = 1;
    private static final int PRESSURE_OVER_BUDGET = 2;

    private static final int UID_TRAFFIC_LOG_LIMIT = 32;
    private static final long UID_TRAFFIC_MIN_INTERVAL_MS = 5000L;

    private static final AtomicInteger capacityBudgetLogs = new AtomicInteger();
    private static final AtomicInteger capacityPressureLogs = new AtomicInteger();
    private static final AtomicInteger capacityPressureState = new AtomicInteger(PRESSURE_UNKNOWN);

    private static final AtomicInteger uidTrafficLogs = new AtomicInteger();
    /** Sentinel until the first sample so an early boot clock cannot suppress it. */
    private static final AtomicLong uidTrafficLastSampleMs = new AtomicLong(-1L);

    /** Diagnostic candidate only: the latest locally created stats2 object, weakly held. */
    private static volatile WeakReference<Object> freshStats2Candidate =
            new WeakReference<>(null);

    private CacheRetentionDiagnostics() {}

    /**
     * Observes an already known capacity budget comparison. Only aggregate byte
     * counts and the over-budget flag are logged; negative or overflowing inputs
     * are rejected without logging. The first four calls log, later calls log only
     * when the pressure state changes, so volume is bounded by 4 + 16 lines.
     */
    private static final ThreadLocal<Long> priorCapacity = new ThreadLocal<>();

    public static void capacityBefore(long before) {
        if (capacityAccountingLogs.get() < 16) priorCapacity.set(before);
    }

    public static void capacityAfter(long after) {
        Long before = priorCapacity.get();
        priorCapacity.remove();
        if (before != null) capacityAccounting(before, after);
    }

    private static void capacityAccounting(long before, long after) {
        if (before < 0L || after < 0L) return;
        try {
            if (capacityAccountingLogs.getAndUpdate(n -> Math.min(n + 1, 16)) < 16) {
                log("capacity accounting: before=" + before + " after=" + after);
            }
        } catch (RuntimeException ignored) {
            // Read-only probe.
        }
    }

    public static void capacityBudgetPacked(long requestedTotal, int unused, long limitBytes) {
        try {
            if (requestedTotal < 0L || limitBytes < 0L) return;
            long remainingHeadroomBytes = limitBytes - requestedTotal;
            // Both operands are non-negative, so the comparison cannot overflow.
            boolean overBudget = requestedTotal > limitBytes;

            int budgetCount = capacityBudgetLogs.getAndUpdate(
                    value -> Math.min(value + 1, CAPACITY_BUDGET_LOG_LIMIT));
            boolean firstSample = budgetCount < CAPACITY_BUDGET_LOG_LIMIT;

            int state = overBudget ? PRESSURE_OVER_BUDGET : PRESSURE_WITHIN_BUDGET;
            boolean stateChanged = capacityPressureState.getAndSet(state) != state;
            // The first lines already report the current state, so they must not
            // consume the budget reserved for later transitions.
            boolean pressureSample = !firstSample && stateChanged &&
                    capacityPressureLogs.getAndUpdate(
                            value -> Math.min(value + 1, CAPACITY_PRESSURE_LOG_LIMIT))
                            < CAPACITY_PRESSURE_LOG_LIMIT;

            if (!firstSample && !pressureSample) return;
            log("capacity budget: requiredTotal=" + requestedTotal
                    + " limit=" + limitBytes
                    + " headroom=" + remainingHeadroomBytes
                    + " overBudget=" + overBudget);
        } catch (RuntimeException ignored) {
            // Diagnostics must never disturb playback.
        }
    }

    /**
     * Samples this process uid's already accumulated traffic counters for matched
     * A/B comparison. The raw uid is never logged, only aggregate bytes, at most
     * once per 5000ms and at most 32 times per process. No cache or network work
     * is added: the platform counters are read only.
     */
    public static void sampleUidTraffic() {
        try {
            long nowMs = SystemClock.elapsedRealtime();
            long lastMs = uidTrafficLastSampleMs.get();
            if (lastMs >= 0L && nowMs - lastMs < UID_TRAFFIC_MIN_INTERVAL_MS) return;
            if (!uidTrafficLastSampleMs.compareAndSet(lastMs, nowMs)) return;
            if (uidTrafficLogs.getAndUpdate(
                    value -> Math.min(value + 1, UID_TRAFFIC_LOG_LIMIT))
                    >= UID_TRAFFIC_LOG_LIMIT) return;

            long receivedBytes = TrafficStats.getUidRxBytes(Process.myUid());
            long transmittedBytes = TrafficStats.getUidTxBytes(Process.myUid());
            if (receivedBytes < 0L || transmittedBytes < 0L) {
                log("uid traffic unavailable");
                return;
            }
            log("uid traffic: rx=" + receivedBytes + " tx=" + transmittedBytes);
        } catch (RuntimeException ignored) {
            // Diagnostics must never disturb playback.
        }
    }

    /** Records a watch-time callback, logged at most 32 times per process. */
    public static void watchTimeCallback() {
        try {
            if (watchTimeLogs.getAndIncrement() < WATCH_TIME_LIMIT) {
                log(WATCH_TIME_MESSAGE);
            }
        } catch (RuntimeException ignored) {
            // Diagnostics must never disturb playback.
        }
    }

    public static void playerProgressing() {
        logBounded(progressingLogs, "media engine progressing");
    }

    public static void playerProgressSample() {
        logBounded(progressSampleLogs, "local director progress dispatched");
    }

    /**
     * Observes the native stats2 watch time total being advanced, not track identity.
     * Logs a second line only when this session still holds the same locally created
     * diagnostic candidate, so the two lines cannot be attributed to different objects.
     */
    public static void stats2Accumulated(Object stats2) {
        try {
            if (stats2AccumulatedLogs.getAndIncrement() < STATS2_ACCUMULATED_LIMIT) {
                log("native stats2 watch time advanced");
                WeakReference<Object> candidate = freshStats2Candidate;
                if (candidate.get() == stats2 &&
                        freshStats2AccumulatedLogs.getAndIncrement() < STATS2_ACCUMULATED_LIMIT) {
                    log("fresh local stats2 watch time advanced");
                }
            }
        } catch (RuntimeException ignored) {
            // Diagnostics must never disturb playback.
        }
    }

    /**
     * Records the latest locally created stats2 object as a diagnostic candidate.
     * Identity is only compared with the accumulating object; nothing is inspected.
     */
    public static void primaryStats2Created(Object stats2) {
        try {
            freshStats2Candidate = new WeakReference<>(stats2);
            if (freshStats2CreatedLogs.getAndUpdate(value -> Math.min(value + 1, 32)) < 32) {
                log("fresh local stats2 created");
            }
        } catch (RuntimeException ignored) {
            // Diagnostics must never disturb playback.
        }
    }

    /** Observes an already dispatched native stats2 progress event. */
    public static void stats2Dispatched() {
        logBounded(stats2DispatchedLogs, "native stats2 progress dispatched");
    }

    /** Observes the native capacity removal callback returning, not the removed bytes. */
    public static void capacityRemovalReturned() {
        try {
            logBounded(capacityRemovalLogs, "native capacity removal returned");
        } catch (RuntimeException ignored) {
            // Diagnostics must never disturb playback.
        }
    }

    private static void logBounded(AtomicInteger count, String message) {
        if (count.getAndUpdate(value -> Math.min(value + 1, 32)) < 32) log(message);
    }

    /** Observes an existing lookup returning a cached span, not bytes consumed by playback. */
    public static void alternateCachedSpan() {
        if (cachedSpanLogs.getAndUpdate(value -> Math.min(value + 1, 32)) < 32) {
            log("alternate cache: cached span returned");
        }
    }

    /** Records the returned cache, classified coarsely and logged once per category. */
    public static void cacheSupplierReturned(Object cache) {
        try {
            if (cache == null) {
                logOnce(CACHE_NULL, CACHE_NULL_MESSAGE);
                return;
            }
            String name;
            try {
                name = cache.getClass().getName();
            } catch (RuntimeException unknownClass) {
                logOnce(CACHE_UNKNOWN, CACHE_UNKNOWN_MESSAGE);
                return;
            }
            if ("sgu".equals(name)) {
                logOnce(CACHE_SIMPLE, CACHE_SIMPLE_MESSAGE);
            } else if ("augy".equals(name)) {
                logOnce(CACHE_ALTERNATE, CACHE_ALTERNATE_MESSAGE);
            } else {
                logOnce(CACHE_UNKNOWN, CACHE_UNKNOWN_MESSAGE);
            }
        } catch (RuntimeException ignored) {
            // Diagnostics must never disturb playback.
        }
    }

    /** Logs the message only for the first caller that claims the category bit. */
    private static void logOnce(int category, String message) {
        while (true) {
            int current = cacheCategoriesLogged.get();
            if ((current & category) != 0) return;
            if (cacheCategoriesLogged.compareAndSet(current, current | category)) {
                log(message);
                return;
            }
        }
    }

    private static void log(String message) {
        try {
            Log.d(TAG, message);
        } catch (RuntimeException ignored) {
            // Logging may be unavailable, e.g. in a stripped runtime.
        }
    }
}
