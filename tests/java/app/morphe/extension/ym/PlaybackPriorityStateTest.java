package app.morphe.extension.ym;

public final class PlaybackPriorityStateTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void suppress(PlaybackPriorityState state, long now, boolean expected) {
        check(state.shouldSuppress(now) == expected, "suppress at " + now + " expected " + expected);
    }

    public static void main(String[] args) throws Exception {
        defaultsAreDisabled();
        urgentWindowIsFixed();
        recoveryShrinksWindow();
        nonOwnerCannotClaimOrClear();
        rebufferAfterRecoveryStartsNewInterval();
        disablingResetsState();
        badClocksFailOpen();
        weakOwnerFailsOpen();
        System.out.println("PlaybackPriorityState tests passed");
    }

    private static void defaultsAreDisabled() {
        PlaybackPriorityState state = new PlaybackPriorityState();
        Object owner = new Object();
        suppress(state, 0L, false);
        state.onUrgent(owner, 0L);
        suppress(state, 0L, false);
        state.onPlaying(owner, 0L);
        suppress(state, 0L, false);
        state.setEnabled(false);
        suppress(state, 0L, false);
    }

    private static void urgentWindowIsFixed() {
        PlaybackPriorityState state = new PlaybackPriorityState();
        state.setEnabled(true);
        suppress(state, 0L, false);
        Object owner = new Object();
        state.onUrgent(owner, 1000L);
        suppress(state, 1000L, true);
        suppress(state, 1000L + 14999L, true);
        suppress(state, 1000L + 15000L, false);
        suppress(state, 1000L + 60000L, false);

        // Repeated urgent calls from the same owner must not extend the fixed deadline.
        state.onUrgent(owner, 14000L);
        suppress(state, 1000L + 15000L, false);
        state.onUrgent(owner, 15000L);
        suppress(state, 1000L + 15000L, false);

        // A different owner claims a new interval.
        Object other = new Object();
        state.onUrgent(other, 20000L);
        suppress(state, 20000L, true);
        suppress(state, 20000L + 14999L, true);
        suppress(state, 20000L + 15000L, false);
    }

    private static void recoveryShrinksWindow() {
        PlaybackPriorityState state = new PlaybackPriorityState();
        state.setEnabled(true);
        Object owner = new Object();
        state.onUrgent(owner, 0L);
        state.onPlaying(owner, 1000L);
        suppress(state, 3999L, true);
        suppress(state, 4000L, false);

        // Playing again must not extend the recovery deadline.
        state.onPlaying(owner, 3500L);
        suppress(state, 4000L, false);
        state.onUrgent(owner, 10000L);
        state.onPlaying(owner, 24000L);
        suppress(state, 24999L, true);
        suppress(state, 25000L, false);
    }

    private static void nonOwnerCannotClaimOrClear() {
        PlaybackPriorityState state = new PlaybackPriorityState();
        state.setEnabled(true);
        Object owner = new Object();
        Object stranger = new Object();
        state.onUrgent(owner, 0L);

        state.onPlaying(stranger, 1000L);
        suppress(state, 14000L, true);

        state.onTerminal(stranger);
        suppress(state, 14000L, true);

        // A playing event with no claim must not create one.
        PlaybackPriorityState fresh = new PlaybackPriorityState();
        fresh.setEnabled(true);
        fresh.onPlaying(stranger, 0L);
        suppress(fresh, 0L, false);
        fresh.onTerminal(stranger);
        suppress(fresh, 0L, false);
    }

    private static void rebufferAfterRecoveryStartsNewInterval() {
        PlaybackPriorityState state = new PlaybackPriorityState();
        state.setEnabled(true);
        Object owner = new Object();
        state.onUrgent(owner, 0L);
        state.onPlaying(owner, 1000L);
        suppress(state, 4000L, false);

        // Rebuffer: protection returns for a fresh fixed window.
        state.onUrgent(owner, 5000L);
        suppress(state, 5000L, true);
        suppress(state, 5000L + 14999L, true);
        suppress(state, 5000L + 15000L, false);

        // Repeated urgent in the new interval still must not extend it.
        state.onUrgent(owner, 19000L);
        suppress(state, 5000L + 15000L, false);
    }

    private static void disablingResetsState() {
        PlaybackPriorityState state = new PlaybackPriorityState();
        state.setEnabled(true);
        Object owner = new Object();
        state.onUrgent(owner, 0L);
        suppress(state, 0L, true);

        state.setEnabled(false);
        suppress(state, 0L, false);

        // Re-enabling starts from an unknown, fail-open state.
        state.setEnabled(true);
        suppress(state, 0L, false);
        state.onPlaying(owner, 0L);
        suppress(state, 0L, false);
        state.onTerminal(owner);
        suppress(state, 0L, false);

        // A fresh claim after re-enable works normally.
        state.onUrgent(owner, 100L);
        suppress(state, 100L, true);
    }

    private static void badClocksFailOpen() {
        PlaybackPriorityState state = new PlaybackPriorityState();
        state.setEnabled(true);
        Object owner = new Object();

        // Negative timestamps never claim.
        state.onUrgent(owner, -5000L);
        suppress(state, -1L, false);
        suppress(state, 0L, false);

        // Rollback relative to the interval start fails open.
        state.onUrgent(owner, 10000L);
        suppress(state, 9999L, false);
        suppress(state, 0L, false);
        suppress(state, 10000L, true);
        state.onPlaying(owner, 9999L);
        suppress(state, 10000L, false);
    }

    private static void weakOwnerFailsOpen() throws Exception {
        PlaybackPriorityState state = new PlaybackPriorityState();
        state.setEnabled(true);
        state.onUrgent(new Object(), 0L);
        suppress(state, 0L, true);

        boolean collected = false;
        for (int attempt = 0; attempt < 50 && !collected; attempt++) {
            System.gc();
            Thread.sleep(5L);
            collected = !state.shouldSuppress(0L);
        }
        check(collected, "weakly referenced owner was not collected");

        // Once collected, the state is unknown and a new owner can claim.
        Object owner = new Object();
        state.onUrgent(owner, 1000L);
        suppress(state, 1000L, true);
        state.onTerminal(owner);
        suppress(state, 1000L, false);
    }
}
