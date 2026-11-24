package android.os;

/** Lightweight JVM replacement for the platform uptime clock. */
public final class SystemClock {
    private static volatile long nowMs = 1_000L;

    private SystemClock() {}

    public static long elapsedRealtime() {
        return nowMs;
    }

    /** Test-only helper, not part of the Android contract. */
    public static void setElapsedRealtime(long value) {
        nowMs = value;
    }
}
