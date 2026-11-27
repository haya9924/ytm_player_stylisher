package app.morphe.extension.ym;

import android.util.Log;

import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * Fixed, low-volume probe for the shared cache suppliers. Each call classifies
 * the supplied object coarsely and logs constant text only: the object is never
 * retained and no class name or identifier is logged. Every path logs at most
 * {@link #LOG_LIMIT} times, and RuntimeExceptions are swallowed so a probe can
 * never affect playback or cache selection.
 */
public final class SharedCacheSupplierProbe {
    private static final String TAG = "YmCacheProbe";

    private static final int LOG_LIMIT = 8;

    /* One bounded slot per path and category: scoped 0-3, unscoped 4-7. */
    private static final int SCOPED_NULL_SLOT = 0;
    private static final int SCOPED_SIMPLE_SLOT = 1;
    private static final int SCOPED_ALTERNATE_SLOT = 2;
    private static final int SCOPED_UNKNOWN_SLOT = 3;
    private static final int UNSCOPED_NULL_SLOT = 4;
    private static final int UNSCOPED_SIMPLE_SLOT = 5;
    private static final int UNSCOPED_ALTERNATE_SLOT = 6;
    private static final int UNSCOPED_UNKNOWN_SLOT = 7;
    private static final int SLOT_COUNT = 8;

    private static final String SCOPED_NULL_MESSAGE = "scoped supplier: null";
    private static final String SCOPED_SIMPLE_MESSAGE = "scoped supplier: simple";
    private static final String SCOPED_ALTERNATE_MESSAGE = "scoped supplier: alternate";
    private static final String SCOPED_UNKNOWN_MESSAGE = "scoped supplier: unknown";
    private static final String UNSCOPED_NULL_MESSAGE = "unscoped supplier: null";
    private static final String UNSCOPED_SIMPLE_MESSAGE = "unscoped supplier: simple";
    private static final String UNSCOPED_ALTERNATE_MESSAGE = "unscoped supplier: alternate";
    private static final String UNSCOPED_UNKNOWN_MESSAGE = "unscoped supplier: unknown";

    private static final AtomicIntegerArray logged = new AtomicIntegerArray(SLOT_COUNT);

    private SharedCacheSupplierProbe() {}

    /** Classifies the account-scoped supplier result and logs at most 8 lines. */
    public static void scoped(Object supplier) {
        try {
            record(supplier, SCOPED_NULL_SLOT, SCOPED_SIMPLE_SLOT, SCOPED_ALTERNATE_SLOT,
                    SCOPED_UNKNOWN_SLOT, SCOPED_NULL_MESSAGE, SCOPED_SIMPLE_MESSAGE,
                    SCOPED_ALTERNATE_MESSAGE, SCOPED_UNKNOWN_MESSAGE);
        } catch (RuntimeException ignored) {
            // The probe must never disturb cache selection.
        }
    }

    /** Classifies the unscoped supplier result and logs at most 8 lines. */
    public static void unscoped(Object supplier) {
        try {
            record(supplier, UNSCOPED_NULL_SLOT, UNSCOPED_SIMPLE_SLOT, UNSCOPED_ALTERNATE_SLOT,
                    UNSCOPED_UNKNOWN_SLOT, UNSCOPED_NULL_MESSAGE, UNSCOPED_SIMPLE_MESSAGE,
                    UNSCOPED_ALTERNATE_MESSAGE, UNSCOPED_UNKNOWN_MESSAGE);
        } catch (RuntimeException ignored) {
            // The probe must never disturb cache selection.
        }
    }

    private static void record(Object supplier, int nullSlot, int simpleSlot, int alternateSlot,
            int unknownSlot, String nullMessage, String simpleMessage, String alternateMessage,
            String unknownMessage) {
        if (supplier == null) {
            log(nullSlot, nullMessage);
            return;
        }
        String name;
        try {
            name = supplier.getClass().getName();
        } catch (RuntimeException unavailableClass) {
            log(unknownSlot, unknownMessage);
            return;
        }
        if ("augy".equals(name)) {
            log(alternateSlot, alternateMessage);
        } else if ("sgu".equals(name)) {
            log(simpleSlot, simpleMessage);
        } else {
            log(unknownSlot, unknownMessage);
        }
    }

    /** Emits the message at most {@link #LOG_LIMIT} times for its slot. */
    private static void log(int slot, String message) {
        try {
            while (true) {
                int current = logged.get(slot);
                if (current >= LOG_LIMIT) return;
                if (logged.compareAndSet(slot, current, current + 1)) {
                    Log.d(TAG, message);
                    return;
                }
            }
        } catch (RuntimeException ignored) {
            // Logging may be unavailable, e.g. in a stripped runtime.
        }
    }
}
