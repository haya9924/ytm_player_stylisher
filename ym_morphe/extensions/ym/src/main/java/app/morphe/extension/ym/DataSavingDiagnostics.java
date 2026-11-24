package app.morphe.extension.ym;

import android.util.Log;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fixed, low-volume diagnostics for data-saving decisions. The patch turns
 * logging on only when the opted-in diagnostics build invokes {@link #enable()};
 * otherwise every entry point returns immediately. Only constant text is
 * logged: no class names, object values, identifiers, urls or network access.
 * Every entry point swallows RuntimeExceptions so a diagnostic can never affect
 * playback or the admission policy.
 */
public final class DataSavingDiagnostics {
    private static final String TAG = "YmDataSaving";

    private static final int LOG_LIMIT = 8;

    private static final String PREFETCH_ADMITTED_MESSAGE = "eligible prefetch: admitted";
    private static final String PREFETCH_BLOCKED_MESSAGE = "eligible prefetch: blocked";
    private static final String RESTORE_SUBMITTED_MESSAGE = "restore follow-up: submitted";
    private static final String RESTORE_SKIPPED_MESSAGE = "restore follow-up: skipped";

    private static final AtomicInteger prefetchAdmittedLogs = new AtomicInteger();
    private static final AtomicInteger prefetchBlockedLogs = new AtomicInteger();
    private static final AtomicInteger restoreSubmittedLogs = new AtomicInteger();
    private static final AtomicInteger restoreSkippedLogs = new AtomicInteger();

    private DataSavingDiagnostics() {}

    private static volatile boolean enabled;

    /** Application startup hook in diagnostics builds only. */
    public static void enable() {
        enabled = true;
    }

    /**
     * Records the admission decision for an already eligible prefetch request,
     * logged at most 8 times per outcome. The caller has already filtered requests
     * outside its index, so nothing else is inspected here.
     */
    public static void onEligiblePrefetch(boolean allowed) {
        if (!enabled) return;
        try {
            AtomicInteger count = allowed ? prefetchAdmittedLogs : prefetchBlockedLogs;
            if (count.getAndUpdate(value -> Math.min(value + 1, LOG_LIMIT)) < LOG_LIMIT) {
                Log.d(TAG, allowed ? PREFETCH_ADMITTED_MESSAGE : PREFETCH_BLOCKED_MESSAGE);
            }
        } catch (RuntimeException ignored) {
            // Diagnostics must never disturb playback.
        }
    }

    /** Records a submitted restore follow-up, logged at most 8 times. */
    public static void onRestoreFollowupSubmitted() {
        if (!enabled) return;
        try {
            if (restoreSubmittedLogs.getAndUpdate(value -> Math.min(value + 1, LOG_LIMIT))
                    < LOG_LIMIT) {
                Log.d(TAG, RESTORE_SUBMITTED_MESSAGE);
            }
        } catch (RuntimeException ignored) {
            // Diagnostics must never disturb playback.
        }
    }

    /** Records a skipped restore follow-up, logged at most 8 times. */
    public static void onRestoreFollowupSkipped() {
        if (!enabled) return;
        try {
            if (restoreSkippedLogs.getAndUpdate(value -> Math.min(value + 1, LOG_LIMIT))
                    < LOG_LIMIT) {
                Log.d(TAG, RESTORE_SKIPPED_MESSAGE);
            }
        } catch (RuntimeException ignored) {
            // Diagnostics must never disturb playback.
        }
    }
}
