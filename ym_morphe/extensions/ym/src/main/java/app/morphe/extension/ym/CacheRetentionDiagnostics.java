package app.morphe.extension.ym;

import android.util.Log;

import java.util.concurrent.atomic.AtomicInteger;

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
    private static final AtomicInteger stats2DispatchedLogs = new AtomicInteger();

    private CacheRetentionDiagnostics() {}

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

    /** Observes the native stats2 watch time total being advanced, not track identity. */
    public static void stats2Accumulated() {
        logBounded(stats2AccumulatedLogs, "native stats2 watch time advanced");
    }

    /** Observes an already dispatched native stats2 progress event. */
    public static void stats2Dispatched() {
        logBounded(stats2DispatchedLogs, "native stats2 progress dispatched");
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
