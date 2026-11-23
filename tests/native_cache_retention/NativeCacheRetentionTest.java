import app.morphe.extension.ym.NativeCacheRetention;
import app.morphe.extension.ym.ScopedPlayHost;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JVM tests for the native account-scoped cache preference.
 *
 * <p>The real extension resolves the account from the native identity and reads the cache only
 * through reflection, so the fakes in this directory reproduce the reflected field names and the
 * stubs replace the Android platform and account adapter. Only {@code qualified} and
 * {@code generation} are package-private, so those two (and the diagnostic counters used for
 * isolation) are driven reflectively.
 */
public final class NativeCacheRetentionTest {
    private static final long MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000;
    private static final Object IDENTITY = new Object();
    private static final String[] COUNTERS =
            {"reports", "scopeReports", "qualifiedReports", "matchingReads"};

    private static final Method QUALIFIED = method("qualified",
            String.class, String.class, int.class, int.class, long.class);
    private static final Method GENERATION = method("generation");

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static Method method(String name, Class<?>... parameters) {
        try {
            Method m = NativeCacheRetention.class.getDeclaredMethod(name, parameters);
            m.setAccessible(true);
            return m;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("missing method " + name, e);
        }
    }

    public static void main(String[] args) {
        observeAndApply();
        nativeGuards();
        lookupBound();
        mixedFormats();
        auxiliaryState();
        ageWindow();
        invalidation();
        bindingGuards();
        noMutation();
        System.out.println("NativeCacheRetention tests passed");
    }

    private static void observeAndApply() {
        reset();
        augy backend = audioPair();
        bind("acct", backend);
        qualify("acct", "hot");

        check("cold".equals(apply(backend, backend.m, "hot")),
                "apply must spare the preferred entry");
        reported("capacity preferred entry spared");

        Log.clear();
        check("hot".equals(observe(backend, backend.m, "hot")),
                "observe must not change the victim");
        reported("capacity would spare preferred entry");

        reset();
        backend = audioPair();
        bind("acct", backend);
        qualify("acct", "hot");
        check("cold".equals(select(backend, backend.m, "cold")),
                "unpreferred original is kept");
        notReported("capacity preferred entry spared");

        reset();
        backend = new augy();
        backend.audio("cold", 140);
        backend.audio("hot", 251);
        bind("acct", backend);
        qualify("acct", "hot");
        check("hot".equals(apply(backend, backend.m, "hot")),
                "only the native first entry can be replaced");
        notReported("capacity preferred entry spared");

        reset();
        backend = new augy();
        backend.audio("hot1", 140);
        backend.audio("hot2", 249);
        backend.audio("hot3", 251);
        bind("acct", backend);
        qualify("acct", "hot1");
        qualify("acct", "hot2");
        qualify("acct", "hot3");
        check("hot1".equals(apply(backend, backend.m, "hot1")),
                "an all-preferred order keeps the native first entry");
        notReported("capacity preferred entry spared");

        reset();
        backend = audioPair();
        bind("acct", backend);
        qualify("acct", "hot", 1, 2, generation());
        check("hot".equals(apply(backend, backend.m, "hot")),
                "a play count below the threshold is not preferred");
        qualify("acct", "", 2, 2, generation());
        check("hot".equals(apply(backend, backend.m, "hot")),
                "an invalid track id is not preferred");
    }

    private static void nativeGuards() {
        reset();
        augy backend = audioPair();
        bind("acct", backend);
        qualify("acct", "hot");

        check("hot".equals(NativeCacheRetention.selectCapacityVictim(backend, backend.m, "hot")),
                "selection without a capacity call is a no-op");

        augy other = audioPair();
        check("hot".equals(evict(other, backend, backend.m, "hot", true)),
                "another writer is not ours");

        NativeCacheRetention.capacityCall(backend, 0);
        try {
            check("hot".equals(NativeCacheRetention.selectCapacityVictim(
                    backend, backend.m, "hot")),
                    "a non-positive excess registers no writer");
        } finally {
            NativeCacheRetention.endCapacityCall();
        }

        check("hot".equals(evict(backend, backend, new LinkedHashSet<>(backend.m), "hot", true)),
                "a copied order is not the native order");

        check("hot".equals(evict(backend, new augy(), backend.m, "hot", true)),
                "an unbound backend is a no-op");

        check(apply(backend, backend.m, null) == null,
                "a null original stays null");
    }

    private static void lookupBound() {
        reset();
        augy backend = new augy();
        backend.audio("hot", 140);
        for (int i = 1; i <= 7; i++) backend.audio("p" + i, 249);
        backend.audio("cold", 251);
        bind("acct", backend);
        qualify("acct", "hot");
        for (int i = 1; i <= 7; i++) qualify("acct", "p" + i);
        check("hot".equals(apply(backend, backend.m, "hot")),
                "a cold entry past the lookup bound is not inspected");
        notReported("capacity preferred entry spared");

        reset();
        backend = new augy();
        backend.audio("hot", 140);
        for (int i = 1; i <= 6; i++) backend.audio("p" + i, 249);
        backend.audio("cold", 251);
        bind("acct", backend);
        qualify("acct", "hot");
        for (int i = 1; i <= 6; i++) qualify("acct", "p" + i);
        check("cold".equals(apply(backend, backend.m, "hot")),
                "a cold entry at the lookup bound is inspected");
        reported("capacity preferred entry spared");
    }

    private static void mixedFormats() {
        reset();
        augy backend = new augy();
        mixed(backend, "hot", 140, 18);
        backend.audio("cold", 251);
        bind("acct", backend);
        qualify("acct", "hot");
        check("hot".equals(apply(backend, backend.m, "hot")),
                "a mixed original is not audio-only");
        reported("capacity preference ineligible");

        reset();
        backend = new augy();
        backend.audio("hot", 140);
        mixed(backend, "mixed", 251, 18);
        backend.audio("cold", 249);
        bind("acct", backend);
        qualify("acct", "hot");
        check("cold".equals(apply(backend, backend.m, "hot")),
                "a mixed candidate is skipped");
        reported("capacity preferred entry spared");

        reset();
        backend = new augy();
        backend.audio("hot", 140);
        backend.m.add("broken");
        backend.n.put("broken", new aufs("other-id",
                formats("broken", 249)));
        backend.audio("cold", 251);
        bind("acct", backend);
        qualify("acct", "hot");
        check("cold".equals(apply(backend, backend.m, "hot")),
                "a mismatched entry id is skipped");
        reported("capacity preferred entry spared");

        reset();
        backend = new augy();
        backend.audio("hot", 140);
        backend.m.add("alien");
        Map<aufr, Object> alien = new LinkedHashMap<>();
        alien.put(new aufr(251), "alien");
        alien.put(null, "alien/unknown");
        backend.n.put("alien", new aufs("alien", alien));
        backend.audio("cold", 249);
        bind("acct", backend);
        qualify("acct", "hot");
        check("cold".equals(apply(backend, backend.m, "hot")),
                "an alien representation type is skipped");
        reported("capacity preferred entry spared");
    }

    private static void auxiliaryState() {
        reset();
        augy backend = audioPair();
        backend.A.put("pending/stream", new Object());
        bind("acct", backend);
        qualify("acct", "hot");
        check("hot".equals(apply(backend, backend.m, "hot")),
                "non-empty auxiliary state disables the preference");
        reported("capacity preference ineligible");
    }

    private static void ageWindow() {
        reset();
        augy backend = audioPair();
        bind("acct", backend);
        SystemClock.setElapsedRealtime(50_000L);
        qualify("acct", "hot");

        SystemClock.setElapsedRealtime(50_000L + MAX_AGE_MS - 1);
        check("cold".equals(apply(backend, backend.m, "hot")),
                "a preference younger than the maximum age is honoured");
        reported("capacity preferred entry spared");

        Log.clear();
        SystemClock.setElapsedRealtime(50_000L + MAX_AGE_MS);
        check("hot".equals(apply(backend, backend.m, "hot")),
                "a preference at the maximum age has expired");
        notReported("capacity preferred entry spared");

        SystemClock.setElapsedRealtime(49_999L);
        check("hot".equals(apply(backend, backend.m, "hot")),
                "a clock before the recorded time is not preferred");
        notReported("capacity preferred entry spared");

        SystemClock.setElapsedRealtime(50_000L + MAX_AGE_MS + 1);
        check("hot".equals(apply(backend, backend.m, "hot")),
                "a preference past the maximum age has expired");
        notReported("capacity preferred entry spared");
    }

    private static void invalidation() {
        reset();
        augy backend = audioPair();
        bind("acct", backend);
        qualify("acct", "hot");
        NativeCacheRetention.invalidate();
        check("hot".equals(apply(backend, backend.m, "hot")),
                "invalidate discards the preference");
        notReported("capacity preferred entry spared");

        reset();
        backend = audioPair();
        bind("acct", backend);
        qualify("acct", "hot");
        ScopedPlayHost.accountChanged();
        check("hot".equals(apply(backend, backend.m, "hot")),
                "an account change discards the preference");

        reset();
        backend = audioPair();
        bind("acct", backend);
        long stale = generation();
        NativeCacheRetention.invalidate();
        bind("acct", backend);
        qualify("acct", "hot", 2, 2, stale);
        check("hot".equals(apply(backend, backend.m, "hot")),
                "a stale generation must not qualify");

        reset();
        backend = audioPair();
        bind("acctA", backend);
        qualify("acctA", "hot");
        bind("acctB", backend);
        check("hot".equals(apply(backend, backend.m, "hot")),
                "an account switch clears the preference");
        notReported("capacity preferred entry spared");

        reset();
        backend = audioPair();
        bind("acctA", backend);
        qualify("acctB", "hot", 2, 2, generation());
        check("hot".equals(apply(backend, backend.m, "hot")),
                "another account cannot qualify on this scope");
    }

    private static void bindingGuards() {
        reset();
        augy backend = audioPair();
        ScopedPlayHost.setAccount("acct");
        NativeCacheRetention.scopedBackend(IDENTITY, backend);
        notReported("capacity scope bound");
        qualify("acct", "hot");
        check("hot".equals(apply(backend, backend.m, "hot")),
                "a binding without a matching scope read is ignored");

        reset();
        backend = audioPair();
        NativeCacheRetention.beginScopeRead();
        NativeCacheRetention.invalidate();
        ScopedPlayHost.setAccount("acct");
        NativeCacheRetention.scopedBackend(IDENTITY, backend);
        notReported("capacity scope bound");
        qualify("acct", "hot");
        check("hot".equals(apply(backend, backend.m, "hot")),
                "a scope read from before an invalidation is ignored");

        reset();
        backend = audioPair();
        NativeCacheRetention.beginScopeRead();
        ScopedPlayHost.setAccount("acct");
        NativeCacheRetention.scopedBackend(IDENTITY, backend);
        reported("capacity scope bound");

        reset();
        backend = audioPair();
        NativeCacheRetention.beginScopeRead();
        ScopedPlayHost.setAccount(null);
        NativeCacheRetention.scopedBackend(IDENTITY, backend);
        reported("capacity scope unavailable");
        qualify("acct", "hot");
        check("hot".equals(apply(backend, backend.m, "hot")),
                "a missing account cannot bind the scope");

        reset();
        backend = audioPair();
        NativeCacheRetention.beginScopeRead();
        ScopedPlayHost.setAccount("acct");
        NativeCacheRetention.scopedBackend(IDENTITY, new Object());
        reported("capacity scope unavailable");
        check("hot".equals(apply(backend, backend.m, "hot")),
                "a foreign backend cannot bind the scope");

        reset();
        backend = audioPair();
        NativeCacheRetention.beginScopeRead();
        ScopedPlayHost.setAccount("acct");
        NativeCacheRetention.scopedBackend(null, backend);
        reported("capacity scope unavailable");
    }

    private static void noMutation() {
        reset();
        augy backend = audioPair();
        backend.audio("extra", 250);
        bind("acct", backend);
        qualify("acct", "hot");

        LinkedHashSet<String> order = backend.m;
        List<String> orderBefore = new ArrayList<>(backend.m);
        Map<String, aufs> entriesBefore = new LinkedHashMap<>(backend.n);
        Map<aufr, Object> hotFormatsBefore = new LinkedHashMap<>(backend.n.get("hot").f);
        Map<aufr, Object> coldFormatsBefore = new LinkedHashMap<>(backend.n.get("cold").f);

        check("cold".equals(apply(backend, backend.m, "hot")),
                "the preferred entry is spared");
        check(backend.m == order, "native order instance is unchanged");
        check(new ArrayList<>(backend.m).equals(orderBefore),
                "native order is unchanged: " + backend.m);
        check(backend.n.keySet().equals(entriesBefore.keySet()),
                "entry keys are unchanged: " + backend.n.keySet());
        for (Map.Entry<String, aufs> entry : entriesBefore.entrySet()) {
            check(backend.n.get(entry.getKey()) == entry.getValue(),
                    "entry identity is unchanged: " + entry.getKey());
        }
        check(backend.n.get("hot").f.equals(hotFormatsBefore),
                "preferred formats are unchanged");
        check(backend.n.get("cold").f.equals(coldFormatsBefore),
                "candidate formats are unchanged");
        check(backend.A.isEmpty(), "auxiliary state stays empty");

        Log.clear();
        check("hot".equals(observe(backend, backend.m, "hot")),
                "observe keeps the native victim");
        check(backend.m == order, "observe keeps the native order instance");
        check(new ArrayList<>(backend.m).equals(orderBefore),
                "observe keeps the native order");
        check(backend.n.keySet().equals(entriesBefore.keySet()),
                "observe keeps the entry keys");
    }

    private static augy audioPair() {
        augy backend = new augy();
        backend.audio("hot", 140, 251);
        backend.audio("cold", 249);
        return backend;
    }

    private static Map<aufr, Object> formats(String key, int... itags) {
        Map<aufr, Object> formats = new LinkedHashMap<>();
        for (int itag : itags) formats.put(new aufr(itag), key + "/" + itag);
        return formats;
    }

    private static void mixed(augy backend, String key, int... itags) {
        backend.n.put(key, new aufs(key, formats(key, itags)));
        backend.m.add(key);
    }

    private static void reset() {
        NativeCacheRetention.invalidate();
        ScopedPlayHost.setAccount(null);
        SystemClock.setElapsedRealtime(1_000L);
        Log.clear();
        for (String name : COUNTERS) {
            try {
                Field field = NativeCacheRetention.class.getDeclaredField(name);
                field.setAccessible(true);
                ((AtomicInteger) field.get(null)).set(0);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("missing counter " + name, e);
            }
        }
    }

    private static void bind(String account, Object backend) {
        ScopedPlayHost.setAccount(account);
        NativeCacheRetention.beginScopeRead();
        NativeCacheRetention.scopedBackend(IDENTITY, backend);
    }

    private static long generation() {
        try {
            return (Long) GENERATION.invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("generation failed", e);
        }
    }

    private static void qualify(String account, String track) {
        qualify(account, track, 2, 2, generation());
    }

    private static void qualify(String account, String track, int count, int threshold,
                                long generation) {
        try {
            QUALIFIED.invoke(null, account, track, count, threshold, generation);
        } catch (InvocationTargetException e) {
            throw new AssertionError("qualified threw", e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("qualified failed", e);
        }
    }

    private static String apply(Object backend, LinkedHashSet<String> order, String original) {
        return evict(backend, backend, order, original, true);
    }

    private static String observe(Object backend, LinkedHashSet<String> order, String original) {
        return evict(backend, backend, order, original, false);
    }

    private static String select(Object backend, LinkedHashSet<String> order, String original) {
        NativeCacheRetention.capacityCall(backend, 1);
        try {
            return NativeCacheRetention.selectCapacityVictim(backend, order, original);
        } finally {
            NativeCacheRetention.endCapacityCall();
        }
    }

    private static String evict(Object writer, Object backend, LinkedHashSet<String> order,
                                String original, boolean apply) {
        NativeCacheRetention.capacityCall(writer, 1);
        try {
            return apply
                    ? NativeCacheRetention.selectCapacityVictim(backend, order, original)
                    : NativeCacheRetention.observeCapacityVictim(backend, order, original);
        } finally {
            NativeCacheRetention.endCapacityCall();
        }
    }

    private static void reported(String message) {
        int count = Log.count(message);
        check(count == 1, "expected one \"" + message + "\" report, got " + count);
    }

    private static void notReported(String message) {
        int count = Log.count(message);
        check(count == 0, "expected no \"" + message + "\" report, got " + count);
    }
}
