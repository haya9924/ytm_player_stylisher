package android.os;

/** Minimal JVM replacement for the platform build information. */
public final class Build {
    private Build() {}

    public static final class VERSION {
        public static final int SDK_INT = 35;

        private VERSION() {}
    }

    public static final class VERSION_CODES {
        public static final int P = 28;

        private VERSION_CODES() {}
    }
}
