package app.morphe.extension.ym;

import android.os.SystemClock;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Capacity-only preference for existing audio entries on the native account-scoped backend. */
public final class NativeCacheRetention {
    private static final int MAX_LOOKUP = 8;
    private static final int MAX_TRACKS = 50;
    private static final long MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000;
    private static final AtomicInteger reports = new AtomicInteger();
    private static final AtomicInteger scopeReports = new AtomicInteger();
    private static final AtomicInteger qualifiedReports = new AtomicInteger();
    private static final AtomicInteger matchingReads = new AtomicInteger();
    private static final ThreadLocal<WeakReference<Object>> capacityWriter = new ThreadLocal<>();
    private static volatile Scope scope;
    private static volatile long scopeGeneration;
    private static volatile boolean observedBinding;
    private static final LinkedHashMap<String, Long> qualified = new LinkedHashMap<>();
    private static String qualifiedAccount;

    private NativeCacheRetention() {}

    /** Called only with the original identity argument and cache returned to the native player. */
    public static synchronized void scopedBackend(Object identity, Object backend) {
        observedBinding = true;
        try {
            String account = ScopedPlayHost.accountKey(identity);
            if (account == null || backend == null || !"augy".equals(backend.getClass().getName())) {
                if (identity != null || scope != null) invalidate();
                report(scopeReports, "capacity scope unavailable");
                return;
            }
            Scope current = scope;
            if (current != null && current.backend.get() == backend && current.account.equals(account)) return;
            scope = null;
            if (!account.equals(qualifiedAccount)) {
                scopeGeneration++;
                qualified.clear();
                qualifiedAccount = account;
            }
            scope = new Scope(backend, account, new Fields(backend.getClass()), new HashMap<>(qualified));
            report(scopeReports, "capacity scope bound");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            invalidate();
            report(scopeReports, "capacity scope unavailable");
        }
    }

    /** Account transitions are observed in the native providers, not guessed from cache use. */
    public static synchronized void invalidate() {
        scope = null;
        scopeGeneration++;
        qualified.clear();
        qualifiedAccount = null;
    }

    static long generation() {
        return scopeGeneration;
    }

    /** Called off the player thread after reading a successfully committed play count. */
    static synchronized void qualified(String account, String track, int count, int threshold, long generation) {
        if (!observedBinding || generation != scopeGeneration || count < threshold || threshold < 1
                || !QualifiedPlayCounter.isValidId(track)) return;
        Scope current = scope;
        if (current == null || !current.account.equals(account) || current.backend.get() == null) return;
        qualified.remove(track);
        qualified.put(track, SystemClock.elapsedRealtime());
        while (qualified.size() > MAX_TRACKS) qualified.remove(qualified.keySet().iterator().next());
        Object backend = current.backend.get();
        if (backend != null) {
            scope = new Scope(backend, account, current.fields, new HashMap<>(qualified));
            report(qualifiedReports, "capacity preference ready");
        }
    }

    /** The native lookup has already produced a cached span; does not read it again. */
    public static void cachedLookup(Object backend, Object decodedKey) {
        Scope current = scope;
        if (current == null || current.backend.get() != backend || decodedKey == null) return;
        try {
            Fields fields = current.fields;
            if (decodedKey.getClass() != fields.keyClass) return;
            String key = (String) fields.keyId.get(decodedKey);
            if (current.prefers(key, SystemClock.elapsedRealtime())) {
                report(matchingReads, "preferred document matched native cached lookup");
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // Lookup is observational and never changes the returned span.
        }
    }

    public static String selectCapacityVictim(Object backend, LinkedHashSet<?> nativeOrder, String original) {
        return choose(backend, nativeOrder, original, true);
    }

    public static String observeCapacityVictim(Object backend, LinkedHashSet<?> nativeOrder, String original) {
        return choose(backend, nativeOrder, original, false);
    }

    public static void capacityCall(Object writer) {
        capacityWriter.set(new WeakReference<>(writer));
    }

    public static void endCapacityCall() {
        capacityWriter.remove();
    }

    private static String choose(Object backend, LinkedHashSet<?> nativeOrder, String original, boolean apply) {
        WeakReference<Object> writer = capacityWriter.get();
        capacityWriter.remove();
        if (writer == null || writer.get() != backend) return original;
        Scope current = scope;
        if (current == null || current.backend.get() != backend || original == null) return original;
        try {
            long now = SystemClock.elapsedRealtime();
            if (!current.prefers(original, now) || nativeOrder == null || original.isEmpty()) return original;
            Fields fields = current.fields;
            if (fields.order.get(backend) != nativeOrder) return original;
            Map<?, ?> auxiliary = (Map<?, ?>) fields.auxiliary.get(backend);
            Map<?, ?> entries = (Map<?, ?>) fields.entries.get(backend);
            if (auxiliary == null || !auxiliary.isEmpty() || entries == null || !fields.audioOnly(entries, original)) {
                report(reports, "capacity preference ineligible");
                return original;
            }
            Iterator<?> keys = nativeOrder.iterator();
            if (!keys.hasNext() || !original.equals(keys.next())) return original;
            for (int i = 1; i < MAX_LOOKUP && keys.hasNext(); ++i) {
                Object key = keys.next();
                if (!(key instanceof String) || current.prefers((String) key, now)) continue;
                if (!fields.audioOnly(entries, (String) key)) continue;
                if (scope != current) return original;
                report(reports, apply ? "capacity preferred entry spared" : "capacity would spare preferred entry");
                return apply ? (String) key : original;
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // Never alter the native capacity path on a classification error.
        }
        return original;
    }

    private static void report(AtomicInteger counter, String message) {
        try {
            if (counter.getAndUpdate(n -> Math.min(n + 1, 16)) < 16) Log.d("YmCacheDiag", message);
        } catch (RuntimeException ignored) {
        }
    }

    private static final class Scope {
        final WeakReference<Object> backend;
        final String account;
        final Fields fields;
        final Map<String, Long> preferred;

        Scope(Object backend, String account, Fields fields, Map<String, Long> preferred) {
            this.backend = new WeakReference<>(backend);
            this.account = account;
            this.fields = fields;
            this.preferred = preferred;
        }

        boolean prefers(String track, long now) {
            Long since = preferred.get(track);
            return since != null && now >= since && now - since < MAX_AGE_MS;
        }
    }

    private static final class Fields {
        final Field order, auxiliary, entries, entryId, formats, keyId;
        final Class<?> entryClass, formatClass, keyClass;
        final Method itag;

        Fields(Class<?> cache) throws ReflectiveOperationException {
            ClassLoader loader = cache.getClassLoader();
            entryClass = Class.forName("aufs", false, loader);
            formatClass = Class.forName("aufr", false, loader);
            keyClass = Class.forName("audf", false, loader);
            keyId = field(keyClass, "a");
            order = field(cache, "m");
            auxiliary = field(cache, "A");
            entries = field(cache, "n");
            entryId = field(entryClass, "c");
            formats = field(entryClass, "f");
            itag = formatClass.getDeclaredMethod("a");
            itag.setAccessible(true);
        }

        private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        }

        boolean audioOnly(Map<?, ?> entries, String key) throws ReflectiveOperationException {
            Object entry = entries.get(key);
            if (!entryClass.isInstance(entry) || !key.equals(entryId.get(entry))) return false;
            Map<?, ?> map = (Map<?, ?>) formats.get(entry);
            if (map == null || map.isEmpty() || map.size() > MAX_LOOKUP) return false;
            for (Object representation : map.keySet()) {
                if (!formatClass.isInstance(representation)) return false;
                int value = (Integer) itag.invoke(representation);
                if (value != 140 && value != 249 && value != 250 && value != 251) return false;
            }
            return true;
        }
    }
}
