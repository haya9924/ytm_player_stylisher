package app.morphe.extension.ym;

/**
 * JVM replacement for the account adapter.
 *
 * <p>The real class resolves the account from the native identity object. On the JVM the account is
 * supplied directly so {@link NativeCacheRetention} can be driven without an APK. The account
 * transition path is kept identical: it discards the cache scope through
 * {@link NativeCacheRetention#invalidate()}.
 */
public final class ScopedPlayHost {
    private static volatile String account;

    private ScopedPlayHost() {}

    /** Test-only: sets the account reported for the next identity, or {@code null} for none. */
    public static void setAccount(String value) {
        account = value;
    }

    static String accountKey(Object identity) {
        return identity == null ? null : account;
    }

    /** Mirrors the real account-change side effect on the native cache scope. */
    public static void accountChanged() {
        NativeCacheRetention.invalidate();
    }
}
