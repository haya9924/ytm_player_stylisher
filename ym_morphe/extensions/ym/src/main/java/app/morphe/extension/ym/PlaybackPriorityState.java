package app.morphe.extension.ym;

import java.lang.ref.WeakReference;

/**
 * Tracks whether speculative work should be suppressed while the observed player is
 * currently in an urgent (or just-recovered) playback state.
 *
 * <p>Pure Java on purpose: the time base is supplied by the caller as a monotonic millisecond
 * value, so the state machine can be exercised deterministically on the JVM.
 */
public final class PlaybackPriorityState {
    /** Fixed protection window for an urgent claim; repeated urgent calls never extend it. */
    static final long URGENT_PROTECTION_MS = 15000L;
    /** Short protection window while the urgent owner is recovering (e.g. buffering). */
    static final long RECOVERY_PROTECTION_MS = 3000L;

    private enum Phase {
        UNKNOWN,
        URGENT,
        RECOVERY
    }

    private boolean enabled;
    private WeakReference<Object> ownerRef;
    private Phase phase = Phase.UNKNOWN;
    private long deadline;
    private long intervalStart;

    /** Disabled by default: nothing is suppressed until the caller enables the state. */
    public synchronized void setEnabled(boolean enabled) {
        if (this.enabled == enabled) return;
        this.enabled = enabled;
        reset();
    }

    /** Claims ownership for {@code owner} and starts a fixed urgent protection deadline. */
    public synchronized void onUrgent(Object owner, long now) {
        if (!enabled || owner == null || now < 0L) return;
        Object current = ownerRef == null ? null : ownerRef.get();
        boolean sameOwner = current != null && current == owner;
        // Same owner and still urgent: keep the original fixed deadline, never extend it.
        if (sameOwner && phase == Phase.URGENT) return;
        // Same owner after recovery: a rebuffer starts a fresh protection interval.
        claim(owner, now);
    }

    /** Shrinks the claim to a short recovery window, but only for the claiming owner. */
    public synchronized void onPlaying(Object owner, long now) {
        if (!enabled || ownerRef == null || now < 0L) return;
        Object current = ownerRef.get();
        if (current == null) {
            reset();
            return;
        }
        if (now < intervalStart) { reset(); return; }
        if (current != owner || phase != Phase.URGENT) return;
        deadline = Math.min(deadline, now + RECOVERY_PROTECTION_MS);
        phase = Phase.RECOVERY;
    }

    /** Clears the claim only when {@code owner} is the current owner. */
    public synchronized void onTerminal(Object owner) {
        if (!enabled || ownerRef == null) return;
        Object current = ownerRef.get();
        if (current == null || current == owner) reset();
    }

    /** True during the bounded playback protection window; uncertainty fails open. */
    public synchronized boolean shouldSuppress(long now) {
        if (!enabled || ownerRef == null || phase == Phase.UNKNOWN) return false;
        Object current = ownerRef.get();
        if (current == null) {
            reset();
            return false;
        }
        // Invalid or backwards clock relative to this interval: fail open.
        if (now < 0L || now < intervalStart) return false;
        return now < deadline;
    }

    private void claim(Object owner, long now) {
        long base = Math.max(0L, now);
        ownerRef = new WeakReference<>(owner);
        phase = Phase.URGENT;
        intervalStart = base;
        deadline = base + URGENT_PROTECTION_MS;
    }

    private void reset() {
        ownerRef = null;
        phase = Phase.UNKNOWN;
        deadline = 0L;
        intervalStart = 0L;
    }
}
