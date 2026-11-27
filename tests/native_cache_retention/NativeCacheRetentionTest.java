import app.morphe.extension.ym.NativeCacheRetention;
import app.morphe.extension.ym.ScopedPlayHost;

import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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
    private static final Class<?> BACKEND_TYPE = backendType();
    private static final Method BACKEND_EPOCH = method("backendEpoch");
    private static final Method OBSERVE_BACKEND = method("observeBackend", Object.class, boolean.class);
    private static final Method CLEAR_BACKEND = method("clearBackendObservation", boolean.class);
    private static final Method BACKEND_IS_CURRENT =
            method("backendIsCurrent", BACKEND_TYPE, Object.class);
    private static final Field OBSERVED_BACKEND = field("observedBackend");
    private static final Field SPARED = field("spared");
    private static final Method HYDRATE = method("hydratePersisted",
            String.class, byte[].class, Set.class, long.class);
    private static final Method APPLY_PREFERENCES =
            method("applyPreferences", boolean.class, int.class);
    private static final Method CAPTURE_TELEMETRY = method("captureCacheTelemetry");

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

    /** Resolves the private Backend holder type without depending on nested-class ordering. */
    private static Class<?> backendType() {
        for (Class<?> nested : NativeCacheRetention.class.getDeclaredClasses()) {
            if (nested.getSimpleName().equals("Backend")) return nested;
        }
        throw new AssertionError("missing Backend holder");
    }

    private static Field field(String name) {
        try {
            Field f = NativeCacheRetention.class.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("missing field " + name, e);
        }
    }

    public static void main(String[] args) {
        observeAndApply();
        badgeRequiresCompletedEvictionAndHit();
        nativeGuards();
        lookupBound();
        mixedFormats();
        auxiliaryState();
        ageWindow();
        invalidation();
        bindingGuards();
        backendObservation();
        policyDecoupling();
        unscopedBackendHook();
        decisionFencing();
        testBudgetCap();
        resolveCacheBudgetAndPreferences();
        retentionDisabledIsNoOp();
        captureCacheTelemetryReadsLiveState();
        hydratePersistedColdStart();
        noMutation();
        System.out.println("NativeCacheRetention tests passed");
    }

    private static void badgeRequiresCompletedEvictionAndHit() {
        reset();
        augy backend = audioPair();
        bind("acct", backend);
        qualify("acct", "hot");
        try {
            Method current = NativeCacheRetention.class.getDeclaredMethod("currentPlayback", String.class, String.class);
            current.setAccessible(true);
            current.invoke(null, "acct", "hot");
            NativeCacheRetention.capacityCall(backend, 1);
            NativeCacheRetention.observeCapacityVictim(backend, backend.m, "hot");
            NativeCacheRetention.capacityVictimRemoved();
            NativeCacheRetention.cachedLookup(backend, new audf("hot"));
            check(!NativeCacheRetention.currentTrackSpared(), "shadow does not earn the mark");
            NativeCacheRetention.capacityCall(backend, 1);
            check("cold".equals(NativeCacheRetention.selectCapacityVictim(backend, backend.m, "hot")), "apply selects cold");
            check(!NativeCacheRetention.currentTrackSpared(), "selection alone is not confirmation");
            NativeCacheRetention.capacityVictimRemoved();
            check(!NativeCacheRetention.currentTrackSpared(), "need a subsequent native hit");
            NativeCacheRetention.cachedLookup(backend, new audf("hot"));
            check(NativeCacheRetention.currentTrackSpared(), "same track and backend are confirmed");
            current.invoke(null, "acct", "cold");
            check(!NativeCacheRetention.currentTrackSpared(), "another track never inherits the badge");
            NativeCacheRetention.invalidate();
            check(!NativeCacheRetention.currentTrackSpared(), "invalidation hides the badge");
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
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

    private static void backendObservation() {
        reset();
        augy backend = audioPair();
        bind("acct", backend);
        long epoch = backendEpoch();
        long scopeGen = generation();
        check(epoch > 0, "binding observes the scoped backend");

        bind("acct", backend);
        check(backendEpoch() == epoch, "re-binding the same backend does not advance backendEpoch");
        check(generation() == scopeGen, "re-binding the same backend does not advance scopeGeneration");
        check(backendIsCurrent(observedBackend(), backend),
                "re-binding keeps the same scoped observation current");

        NativeCacheRetention.beginScopeRead();
        NativeCacheRetention.invalidate();
        check(observedBackend() == null, "invalidate clears the scoped backend observation");
        check(backendEpoch() > epoch, "invalidate advances backendEpoch");
        long clearedEpoch = backendEpoch();
        long clearedGeneration = generation();

        // A scope read from before the invalidation must not resurrect or invalidate anything.
        Log.clear();
        ScopedPlayHost.setAccount("acct");
        NativeCacheRetention.scopedBackend(IDENTITY, backend);
        check(observedBackend() == null, "stale scopedBackend leaves observedBackend untouched");
        check(backendEpoch() == clearedEpoch, "stale scopedBackend does not advance backendEpoch");
        check(generation() == clearedGeneration,
                "stale scopedBackend does not invalidate the newer generation");
        notReported("capacity scope unavailable");
        notReported("capacity scope bound");

        reset();
        backend = audioPair();
        observeUnscoped(backend);
        Object unscoped = observedBackend();
        long unscopedEpoch = backendEpoch();
        check(unscoped != null && backendIsCurrent(unscoped, backend), "unscoped backend is current");
        NativeCacheRetention.invalidate();
        check(observedBackend() == unscoped, "invalidate keeps the unscoped backend observation");
        check(backendEpoch() == unscopedEpoch, "keeping the unscoped observation keeps the epoch");
        check(backendIsCurrent(unscoped, backend), "unscoped backend stays current after invalidate");

        clearBackendObservation(false);
        check(observedBackend() == null, "full clear removes the unscoped observation");
        check(backendEpoch() > unscopedEpoch, "full clear advances backendEpoch");
        check(!backendIsCurrent(unscoped, backend), "cleared snapshot is not current");
    }

    private static void policyDecoupling() {
        qualifyBeforeScopedDiscovery();
        candidateAdmittedBeforeDiscovery();
        backendReplacementPreservesQualifications();
        unscopedBackendAppliesAdmittedAccount();
        unscopedRejectsCrossAccountMixing();
    }

    /** (1) A track may be admitted before any backend is observed; binding then keeps the epoch. */
    private static void qualifyBeforeScopedDiscovery() {
        reset();
        augy backend = audioPair();
        long gen = generation();
        check(observedBackend() == null, "no backend is observed after reset");
        qualify("acct", "hot", 2, 2, gen);
        reported("capacity preference ready");
        check("hot".equals(apply(backend, backend.m, "hot")),
                "a policy without an observed backend never changes the native victim");

        bind("acct", backend);
        check(generation() == gen, "first scoped discovery does not advance the account epoch");
        check("cold".equals(apply(backend, backend.m, "hot")),
                "the admitted preference applies once a backend is discovered");
        reported("capacity preferred entry spared");
    }

    /** (2) A recording candidate admitted before discovery stays valid across the first bind. */
    private static void candidateAdmittedBeforeDiscovery() {
        reset();
        augy backend = audioPair();
        long gen = generation();
        bind("acct", backend);
        check(generation() == gen, "first scoped bind does not advance the account epoch");
        qualify("acct", "hot", 2, 2, gen);
        reported("capacity preference ready");
        check("cold".equals(apply(backend, backend.m, "hot")),
                "the pre-discovery candidate admits the preference");
        reported("capacity preferred entry spared");
    }

    /** (3) Replacing the backend keeps the epoch and the qualified tracks, and stales the old one. */
    private static void backendReplacementPreservesQualifications() {
        reset();
        augy first = audioPair();
        augy second = audioPair();
        bind("acct", first);
        qualify("acct", "hot");
        long epoch = backendEpoch();
        long gen = generation();

        bind("acct", second);
        check(backendEpoch() > epoch, "replacing the backend advances backendEpoch");
        check(generation() == gen, "same-account backend replacement keeps the account epoch");
        check("cold".equals(apply(second, second.m, "hot")),
                "the replaced backend applies the retained preference");
        check("hot".equals(apply(first, first.m, "hot")),
                "the stale backend no longer applies the preference");

        // Replacing an observed backend with an invalid one reports unavailable and clears it.
        long beforeInvalid = generation();
        NativeCacheRetention.beginScopeRead();
        ScopedPlayHost.setAccount("acct");
        NativeCacheRetention.scopedBackend(IDENTITY, new Object());
        reported("capacity scope unavailable");
        check(observedBackend() == null, "an invalid binding clears the scoped observation");
        check(generation() == beforeInvalid + 1, "an invalid binding invalidates the epoch");
    }

    /** (4) An unscoped observed backend applies the admitted account's preference. */
    private static void unscopedBackendAppliesAdmittedAccount() {
        reset();
        augy backend = audioPair();
        observeUnscoped(backend);
        qualify("acct", "hot");
        check("cold".equals(apply(backend, backend.m, "hot")),
                "an unscoped backend applies the admitted account policy");

        NativeCacheRetention.invalidate();
        check("hot".equals(apply(backend, backend.m, "hot")),
                "invalidate discards the preference");
        check(backendIsCurrent(observedBackend(), backend),
                "the unscoped observation survives invalidate");

        qualify("acct2", "hot");
        check("cold".equals(apply(backend, backend.m, "hot")),
                "a new account re-qualifies on the same unscoped backend");
    }

    /** (5) Two accounts may not mix within one epoch, even without a scoped binding. */
    private static void unscopedRejectsCrossAccountMixing() {
        reset();
        augy backend = audioPair();
        observeUnscoped(backend);
        qualify("acctA", "hot");
        reported("capacity preference ready");
        int ready = Log.count("capacity preference ready");
        qualify("acctB", "cold", 2, 2, generation());
        check(Log.count("capacity preference ready") == ready,
                "another account cannot qualify in the same epoch");
        check("cold".equals(apply(backend, backend.m, "hot")),
                "the first account keeps the preference");
        // The admitted account still owns the epoch, so it can keep qualifying.
        qualify("acctA", "cold", 2, 2, generation());
        check(Log.count("capacity preference ready") == ready + 1,
                "the admitted account can still qualify after a foreign account is rejected");
    }

    /** Ticket 4: the public hook entry point the unscoped supplier now calls. */
    private static void unscopedBackendHook() {
        reset();
        // reset() deliberately keeps unscoped observations, so start from a clean slate here.
        clearBackendObservation(false);
        augy backend = audioPair();
        long gen = generation();

        // Rejected values report the legacy message and never mutate observation or the epoch.
        NativeCacheRetention.unscopedBackend(null);
        reported("native unscoped cache supplied");
        NativeCacheRetention.unscopedBackend(new Object());
        Log.clear();
        NativeCacheRetention.unscopedBackend(new Object());
        reported("native unscoped cache supplied");
        check(observedBackend() == null, "a foreign value never becomes the observed backend");
        check(generation() == gen, "a foreign value never advances the account epoch");

        Log.clear();
        NativeCacheRetention.unscopedBackend(backend);
        reported("native unscoped cache bound");
        Object observed = observedBackend();
        check(backendIsCurrent(observed, backend), "the unscoped backend is observed");
        long observedEpoch = backendEpoch();
        check(generation() == gen, "unscoped discovery does not advance the account epoch");

        Log.clear();
        NativeCacheRetention.unscopedBackend(backend);
        notReported("native unscoped cache bound");
        check(backendEpoch() == observedEpoch, "a repeated unscoped observe is idempotent");
        check(observedBackend() == observed, "a repeated unscoped observe keeps the same snapshot");

        NativeCacheRetention.unscopedBackend(new Object());
        reported("native unscoped cache supplied");
        NativeCacheRetention.unscopedBackend(null);
        check(observedBackend() == observed, "a foreign value never clobbers the valid observation");
        check(backendIsCurrent(observed, backend), "the valid unscoped observation survives");

        // End-to-end: admission, eviction decision, native hit, badge, then a new account.
        reset();
        backend = audioPair();
        NativeCacheRetention.unscopedBackend(backend);
        qualify("acct", "hot");
        currentPlayback("acct", "hot");
        NativeCacheRetention.capacityCall(backend, 1);
        check("cold".equals(NativeCacheRetention.selectCapacityVictim(
                backend, backend.m, "hot")), "the unscoped backend spares the preferred entry");
        NativeCacheRetention.capacityVictimRemoved();
        NativeCacheRetention.endCapacityCall();
        NativeCacheRetention.cachedLookup(backend, new audf("hot"));
        check(NativeCacheRetention.currentTrackSpared(), "the unscoped backend confirms the badge");

        NativeCacheRetention.invalidate();
        check(!NativeCacheRetention.currentTrackSpared(), "invalidate clears the badge");
        check("hot".equals(select(backend, backend.m, "hot")),
                "invalidate keeps the unscoped backend but drops the preference");
        check(backendIsCurrent(observedBackend(), backend),
                "invalidate keeps the unscoped backend observed");
        qualify("acct2", "hot");
        check("cold".equals(select(backend, backend.m, "hot")),
                "a new account qualifies immediately on the same unscoped backend");

        // Reverse order: admission before the supplier result arrives.
        reset();
        backend = audioPair();
        gen = generation();
        qualify("acct", "hot");
        NativeCacheRetention.unscopedBackend(backend);
        check(generation() == gen, "observing after admission keeps the account epoch");
        check("cold".equals(select(backend, backend.m, "hot")),
                "a policy admitted before discovery still applies");
    }

    /**
     * Ticket 7: durable qualifications restored at cold start protect already-qualified tracks
     * without waiting for a new in-session {@code qualified()} call.
     */
    private static void hydratePersistedColdStart() {
        reset();
        clearBackendObservation(false);
        final byte[] secret = secret(0);
        final augy backend = audioPair();
        final String hotKey = trackKey(secret, "acct", "hot");

        // 1. Hydration alone protects an already-qualified track on an unscoped backend.
        NativeCacheRetention.unscopedBackend(backend);
        hydrate("acct", secret, keys(hotKey), generation());
        reported("capacity preference restored");
        check("cold".equals(select(backend, backend.m, "hot")),
                "a hydrated preference spares the track without a new qualified() call");
        Log.clear();
        check("cold".equals(select(backend, backend.m, "cold")),
                "an unlisted original is never replaced");
        notReported("capacity preferred entry spared");
        long epoch = generation();
        check(epoch > 0L, "hydration keeps the account epoch usable");

        // 2. Stale generation is rejected.
        NativeCacheRetention.invalidate();
        Log.clear();
        long stale = epoch;
        check(generation() > stale, "invalidate advanced the epoch");
        hydrate("acct", secret, keys(hotKey), stale);
        notReported("capacity preference restored");
        check("hot".equals(select(backend, backend.m, "hot")),
                "a stale generation cannot restore preferences");

        // 3. Cross-account hydration is rejected once an account owns the epoch.
        reset();
        clearBackendObservation(false);
        NativeCacheRetention.unscopedBackend(backend);
        qualify("acct", "hot");
        hydrate("acct2", secret, keys(trackKey(secret, "acct2", "cold")), generation());
        notReported("capacity preference restored");
        check("cold".equals(select(backend, backend.m, "hot")),
                "the owning account keeps its preference");

        // 4. Invalid input never publishes.
        reset();
        clearBackendObservation(false);
        NativeCacheRetention.unscopedBackend(backend);
        hydrate(null, secret, keys(hotKey), generation());
        hydrate("acct", null, keys(hotKey), generation());
        hydrate("acct", new byte[31], keys(hotKey), generation());
        hydrate("acct", secret, keys(), generation());
        notReported("capacity preference restored");
        check("hot".equals(select(backend, backend.m, "hot")), "invalid hydration is inert");

        // 5. invalidate() clears the restored preference.
        reset();
        clearBackendObservation(false);
        NativeCacheRetention.unscopedBackend(backend);
        hydrate("acct", secret, keys(hotKey), generation());
        check("cold".equals(select(backend, backend.m, "hot")), "restored before invalidate");
        NativeCacheRetention.invalidate();
        check("hot".equals(select(backend, backend.m, "hot")),
                "invalidate discards the restored preference");
        check(backendIsCurrent(observedBackend(), backend),
                "invalidate still keeps the unscoped backend observation");

        // 6. A real account switch clears the restored preference.
        reset();
        clearBackendObservation(false);
        augy first = audioPair();
        augy second = audioPair();
        bind("acct", first);
        hydrate("acct", secret, keys(hotKey), generation());
        check("cold".equals(apply(first, first.m, "hot")), "restored on the scoped account");
        bind("acct2", second);
        check("hot".equals(apply(second, second.m, "hot")),
                "an account switch discards another account's restored preference");

        // 7. A restored preference and an in-session qualification coexist. The candidate is skipped
        // only when it is preferred, so each order proves one of the two sets is in effect.
        reset();
        clearBackendObservation(false);
        augy hydratedFirst = new augy();
        hydratedFirst.audio("cold", 140);
        hydratedFirst.audio("hot", 249);
        hydratedFirst.audio("tail", 249);
        NativeCacheRetention.unscopedBackend(hydratedFirst);
        hydrate("acct", secret, keys(hotKey), generation());
        long gen = generation();
        qualify("acct", "cold", 2, 2, gen);
        check("tail".equals(select(hydratedFirst, hydratedFirst.m, "cold")),
                "the restored track is skipped next to the in-session one");

        reset();
        clearBackendObservation(false);
        augy qualifiedFirst = new augy();
        qualifiedFirst.audio("hot", 140);
        qualifiedFirst.audio("cold", 251);
        qualifiedFirst.audio("tail", 249);
        NativeCacheRetention.unscopedBackend(qualifiedFirst);
        hydrate("acct", secret, keys(hotKey), generation());
        gen = generation();
        qualify("acct", "cold", 2, 2, gen);
        check("tail".equals(select(qualifiedFirst, qualifiedFirst.m, "hot")),
                "the in-session track is skipped next to the restored one");
        qualify("acct", "hot", 2, 2, gen);
        check("tail".equals(select(qualifiedFirst, qualifiedFirst.m, "hot")),
                "re-qualifying the hydrated track keeps it preferred");

        // 8. The stored secret is a defensive copy: mutating the caller's array is harmless.
        reset();
        clearBackendObservation(false);
        NativeCacheRetention.unscopedBackend(backend);
        byte[] callerSecret = secret(0);
        hydrate("acct", callerSecret, keys(hotKey), generation());
        java.util.Arrays.fill(callerSecret, (byte) 0);
        check("cold".equals(select(backend, backend.m, "hot")),
                "hydration keeps its own secret copy");
    }

    private static byte[] secret(int first) {
        int length = secretBytes();
        byte[] bytes = new byte[length];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (first + i);
        return bytes;
    }

    /** ScopedPlaybackKeys is package-private, so its constants and derivations are reflected. */
    private static int secretBytes() {
        try {
            Field f = Class.forName("app.morphe.extension.ym.ScopedPlaybackKeys")
                    .getDeclaredField("SECRET_BYTES");
            f.setAccessible(true);
            return (Integer) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("SECRET_BYTES read failed", e);
        }
    }

    private static String trackKey(byte[] secret, String account, String track) {
        try {
            Class<?> keys = Class.forName("app.morphe.extension.ym.ScopedPlaybackKeys");
            Method m = keys.getDeclaredMethod("deriveTrackKey",
                    byte[].class, String.class, String.class);
            m.setAccessible(true);
            String key = (String) m.invoke(null, secret, account, track);
            check(key != null, "track key derivation failed for " + track);
            return key;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("deriveTrackKey failed", e);
        }
    }

    private static Set<String> keys(String... values) {
        Set<String> set = new LinkedHashSet<>();
        Collections.addAll(set, values);
        return set;
    }

    private static void hydrate(String account, byte[] secret, Set<String> keys, long generation) {
        try {
            HYDRATE.invoke(null, account, secret, keys, generation);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("hydratePersisted failed", e);
        }
    }

    /** Ticket 6: the opt-in test budget cap is pure and can only lower the host budget. */
    private static void testBudgetCap() {
        final long mib = 1024L * 1024L;
        final long nativeBudget = 256L * mib;

        check(NativeCacheRetention.capTestBudgetMiB(nativeBudget, 0) == nativeBudget,
                "0 MiB disables the cap");
        check(NativeCacheRetention.capTestBudgetMiB(nativeBudget, -1) == nativeBudget,
                "a negative cap disables the cap");
        check(NativeCacheRetention.capTestBudgetMiB(0L, 16) == 0L,
                "a non-positive host budget is returned unchanged");
        check(NativeCacheRetention.capTestBudgetMiB(-5L, 16) == -5L,
                "a negative host budget is returned unchanged");
        check(NativeCacheRetention.capTestBudgetMiB(nativeBudget, 16) == 16L * mib,
                "a smaller cap lowers the budget");
        check(NativeCacheRetention.capTestBudgetMiB(8L * mib, 16) == 8L * mib,
                "a cap above the native budget never raises it");
        check(NativeCacheRetention.capTestBudgetMiB(nativeBudget, 256) == nativeBudget,
                "a cap equal to the native budget is idempotent");
        check(NativeCacheRetention.capTestBudgetMiB(nativeBudget, 1) == 1L * mib,
                "the smallest cap is honoured");
    }

    /** Runtime preferences: the user budget replaces the host budget, the test cap only lowers it. */
    private static void resolveCacheBudgetAndPreferences() {
        final long mib = 1024L * 1024L;
        final long nativeBudget = 256L * mib;

        applyPreferences(true, 0);
        check(NativeCacheRetention.resolveCacheBudget(nativeBudget, 0) == nativeBudget,
                "automatic budget keeps the host budget");
        check(NativeCacheRetention.resolveCacheBudget(0L, 0) == 0L,
                "an unknown host budget stays unknown");

        applyPreferences(true, 64);
        check(NativeCacheRetention.resolveCacheBudget(nativeBudget, 0) == 64L * mib,
                "the user budget replaces the host budget");
        check(NativeCacheRetention.resolveCacheBudget(512L * mib, 0) == 64L * mib,
                "the user budget also lowers a larger host budget");
        check(NativeCacheRetention.resolveCacheBudget(nativeBudget, 16) == 16L * mib,
                "the test cap lowers the user budget");
        check(NativeCacheRetention.resolveCacheBudget(nativeBudget, 2048) == 64L * mib,
                "the test cap never raises the user budget");
        applyPreferences(true, 0);
        check(NativeCacheRetention.resolveCacheBudget(0L, 16) == 0L,
                "a zero host budget stays zero under a cap");
        applyPreferences(true, 64);

        // Invalid preferences fall back to automatic and never poison the budget.
        for (int invalid : new int[] {-1, 1, 15, 2049}) {
            applyPreferences(true, invalid);
            check(NativeCacheRetention.resolveCacheBudget(nativeBudget, 0) == nativeBudget,
                    "invalid preference " + invalid + " falls back to automatic");
        }
        applyPreferences(true, 2048);
        check(NativeCacheRetention.resolveCacheBudget(nativeBudget, 0) == 2048L * mib,
                "the maximum preference is honoured");
        applyPreferences(true, 16);
        check(NativeCacheRetention.resolveCacheBudget(nativeBudget, 0) == 16L * mib,
                "the minimum preference is honoured");

        // The last positive native budget is remembered for telemetry.
        applyPreferences(true, 0);
        NativeCacheRetention.resolveCacheBudget(300L * mib, 0);
        check(telemetry().nativeBudgetBytes == 300L * mib,
                "the native budget is remembered");
        NativeCacheRetention.resolveCacheBudget(0L, 0);
        check(telemetry().nativeBudgetBytes == 300L * mib,
                "a missing native budget keeps the last known one");

        // Restore the defaults so later tests are unaffected.
        applyPreferences(true, 0);
        NativeCacheRetention.resolveCacheBudget(nativeBudget, 0);
    }

    /** Disabling retention must make the capacity decision a strict no-op. */
    private static void retentionDisabledIsNoOp() {
        reset();
        augy backend = audioPair();
        bind("acct", backend);
        qualify("acct", "hot");
        applyPreferences(false, 0);
        try {
            check("hot".equals(apply(backend, backend.m, "hot")),
                    "disabled retention keeps the native victim");
            notReported("capacity preferred entry spared");
            check("hot".equals(observe(backend, backend.m, "hot")),
                    "disabled retention also keeps the observed victim");
        } finally {
            applyPreferences(true, 0);
        }
    }

    /** Live telemetry reads used bytes, audio entries, sizes, and the spared counter. */
    private static void captureCacheTelemetryReadsLiveState() {
        reset();
        applyPreferences(true, 0);
        final long mib = 1024L * 1024L;

        // No observed backend: everything degrades to the known budget and zero counters.
        clearBackendObservation(false);
        NativeCacheRetention.resolveCacheBudget(256L * mib, 0);
        Telemetry absent = telemetry();
        check(absent != null, "telemetry is always available");
        check(absent.nativeBudgetBytes == 256L * mib, "the last native budget is reported");
        check(absent.usedBytes == 0L && absent.audioTracks == 0 && absent.audioBytes == 0L,
                "without a backend the cache counters are zero");
        check(absent.sessionSparedCount >= 0, "the spared counter is never negative");

        // A backend without the optional telemetry fields keeps working.
        augy plain = audioPair();
        bind("acct", plain);
        Telemetry legacy = telemetry();
        check(legacy.usedBytes == 0L && legacy.audioTracks == 0,
                "a backend without telemetry fields reports zero");

        // A backend with the live fields reports used bytes and per-entry sizes.
        augy live = new augy();
        live.audio("hot", 140, 251);
        live.audio("cold", 249);
        live.video("video", 136);
        live.e = new AtomicLong(7L * mib);
        live.h = new java.util.concurrent.locks.ReentrantLock();
        live.n.get("hot").size = 4_000_000L;
        live.n.get("cold").size = 2_500_000L;
        live.n.get("video").size = 9_000_000L;
        bind("acct", live);
        qualify("acct", "hot");

        Telemetry liveTelemetry = telemetry();
        check(liveTelemetry.nativeBudgetBytes == 256L * mib, "the native budget is reported");
        check(liveTelemetry.usedBytes == 7L * mib, "used bytes come from the host atomic: "
                + liveTelemetry.usedBytes);
        check(liveTelemetry.audioTracks == 2, "only audio entries are counted: "
                + liveTelemetry.audioTracks);
        check(liveTelemetry.audioBytes == 6_500_000L, "audio sizes are summed: "
                + liveTelemetry.audioBytes);
        check(liveTelemetry.preferredAudioTracks == 1, "preferred audio tracks are counted: "
                + liveTelemetry.preferredAudioTracks);
        check(!((java.util.concurrent.locks.ReentrantLock) live.h).isLocked(),
                "the host lock is released again");

        // The session-spared counter follows published decisions.
        Telemetry beforeSpare = telemetry();
        currentPlayback("acct", "hot");
        NativeCacheRetention.capacityCall(live, 1);
        check("cold".equals(NativeCacheRetention.selectCapacityVictim(live, live.m, "hot")),
                "the preferred entry is spared");
        NativeCacheRetention.capacityVictimRemoved();
        NativeCacheRetention.endCapacityCall();
        Telemetry afterSpare = telemetry();
        check(afterSpare.sessionSparedCount == beforeSpare.sessionSparedCount + 1,
                "a published spare increments the session counter");

        // A foreign lock type and unreadable entries never throw.
        augy hostile = audioPair();
        hostile.h = new Object();
        hostile.e = "not-a-long";
        bind("acct", hostile);
        Telemetry hostileTelemetry =
                telemetry();
        check(hostileTelemetry != null && hostileTelemetry.usedBytes == 0L,
                "unexpected field shapes degrade to zero");
    }

    /** Ticket 5: a decision must be provably current on both epochs to become a badge. */
    private static void decisionFencing() {
        backendReplacementBetweenSelectAndRemoved();
        accountInvalidationBetweenSelectAndRemoved();
        backendReplacementBetweenRemovedAndLookup();
        unscopedReplacementKeepsPolicyAndFencesBadge();
    }

    /** (1) A backend replacement after selection fences the pending decision by backendEpoch. */
    private static void backendReplacementBetweenSelectAndRemoved() {
        reset();
        augy first = audioPair();
        augy second = audioPair();
        bind("acct", first);
        qualify("acct", "hot");
        currentPlayback("acct", "hot");

        NativeCacheRetention.capacityCall(first, 1);
        check("cold".equals(NativeCacheRetention.selectCapacityVictim(
                first, first.m, "hot")), "the first backend spares the preferred entry");
        bind("acct", second);
        NativeCacheRetention.capacityVictimRemoved();
        NativeCacheRetention.endCapacityCall();
        check(spared() == null, "a stale backendEpoch fences the decision before publication");
        NativeCacheRetention.cachedLookup(first, new audf("hot"));
        check(!NativeCacheRetention.currentTrackSpared(),
                "the stale backend cannot confirm after replacement");
        NativeCacheRetention.cachedLookup(second, new audf("hot"));
        check(!NativeCacheRetention.currentTrackSpared(),
                "the replacement backend never inherits the stale decision");
    }

    /** (2) An account invalidation after selection fences the pending decision by accountEpoch. */
    private static void accountInvalidationBetweenSelectAndRemoved() {
        reset();
        augy backend = audioPair();
        bind("acct", backend);
        qualify("acct", "hot");
        currentPlayback("acct", "hot");

        NativeCacheRetention.capacityCall(backend, 1);
        check("cold".equals(NativeCacheRetention.selectCapacityVictim(
                backend, backend.m, "hot")), "the backend spares the preferred entry");
        NativeCacheRetention.invalidate();
        NativeCacheRetention.capacityVictimRemoved();
        NativeCacheRetention.endCapacityCall();
        check(spared() == null, "a stale accountEpoch fences the decision before publication");
        NativeCacheRetention.cachedLookup(backend, new audf("hot"));
        check(!NativeCacheRetention.currentTrackSpared(),
                "an invalidated decision never becomes a badge");
    }

    /** (3) A replacement after publication stales both confirmation and the badge itself. */
    private static void backendReplacementBetweenRemovedAndLookup() {
        reset();
        augy first = audioPair();
        augy second = audioPair();
        bind("acct", first);
        qualify("acct", "hot");
        currentPlayback("acct", "hot");

        NativeCacheRetention.capacityCall(first, 1);
        check("cold".equals(NativeCacheRetention.selectCapacityVictim(
                first, first.m, "hot")), "the first backend spares the preferred entry");
        NativeCacheRetention.capacityVictimRemoved();
        NativeCacheRetention.endCapacityCall();
        bind("acct", second);

        NativeCacheRetention.cachedLookup(first, new audf("hot"));
        check(!NativeCacheRetention.currentTrackSpared(),
                "the replaced backend cannot confirm the badge");
        NativeCacheRetention.cachedLookup(second, new audf("hot"));
        check(!NativeCacheRetention.currentTrackSpared(),
                "a replaced backend carries no badge to the new observation");
    }

    /** (4) An unscoped replacement keeps the policy but drops a badge from the old backend. */
    private static void unscopedReplacementKeepsPolicyAndFencesBadge() {
        reset();
        clearBackendObservation(false);
        augy first = audioPair();
        augy second = audioPair();
        NativeCacheRetention.unscopedBackend(first);
        qualify("acct", "hot");
        currentPlayback("acct", "hot");

        NativeCacheRetention.capacityCall(first, 1);
        check("cold".equals(NativeCacheRetention.selectCapacityVictim(
                first, first.m, "hot")), "the unscoped backend spares the preferred entry");
        NativeCacheRetention.capacityVictimRemoved();
        NativeCacheRetention.endCapacityCall();
        Object decision = spared();
        check(decision != null, "a current decision is published");
        check(sparedField(decision, "accountEpoch") == generation(),
                "the published decision is fenced on the account epoch");
        check(sparedField(decision, "backendEpoch") == backendEpoch(),
                "the published decision is fenced on the backend epoch");
        NativeCacheRetention.cachedLookup(first, new audf("hot"));
        check(NativeCacheRetention.currentTrackSpared(), "the badge is confirmed on the first backend");

        long gen = generation();
        NativeCacheRetention.unscopedBackend(second);
        check(generation() == gen, "unscoped replacement does not advance the account epoch");
        check(spared() == decision, "the decision object itself is not mutated by replacement");
        check(!NativeCacheRetention.currentTrackSpared(),
                "the replaced backend's badge does not survive");
        NativeCacheRetention.cachedLookup(first, new audf("hot"));
        check(!NativeCacheRetention.currentTrackSpared(),
                "the old backend cannot re-confirm the badge");
        check("cold".equals(select(second, second.m, "hot")),
                "the retained policy still applies on the replacement backend");
    }

    /** Mirrors the package-private {@code CacheTelemetry} value object. */
    private static final class Telemetry {
        final long nativeBudgetBytes;
        final long usedBytes;
        final int audioTracks;
        final long audioBytes;
        final int preferredAudioTracks;
        final int sessionSparedCount;

        Telemetry(Object value) {
            Class<?> type = value.getClass();
            nativeBudgetBytes = longField(type, value, "nativeBudgetBytes");
            usedBytes = longField(type, value, "usedBytes");
            audioTracks = intField(type, value, "audioTracks");
            audioBytes = longField(type, value, "audioBytes");
            preferredAudioTracks = intField(type, value, "preferredAudioTracks");
            sessionSparedCount = intField(type, value, "sessionSparedCount");
        }

        private static long longField(Class<?> type, Object value, String name) {
            try {
                Field f = type.getDeclaredField(name);
                f.setAccessible(true);
                return (Long) f.get(value);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("CacheTelemetry." + name + " read failed", e);
            }
        }

        private static int intField(Class<?> type, Object value, String name) {
            try {
                Field f = type.getDeclaredField(name);
                f.setAccessible(true);
                return (Integer) f.get(value);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("CacheTelemetry." + name + " read failed", e);
            }
        }
    }

    private static void applyPreferences(boolean enabled, int budgetMiB) {
        try {
            APPLY_PREFERENCES.invoke(null, enabled, budgetMiB);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("applyPreferences failed", e);
        }
    }

    private static Telemetry telemetry() {
        try {
            Object value = CAPTURE_TELEMETRY.invoke(null);
            check(value != null, "captureCacheTelemetry returned null");
            return new Telemetry(value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("captureCacheTelemetry failed", e);
        }
    }

    private static void currentPlayback(String account, String track) {
        try {
            Method m = NativeCacheRetention.class.getDeclaredMethod(
                    "currentPlayback", String.class, String.class);
            m.setAccessible(true);
            m.invoke(null, account, track);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("currentPlayback failed", e);
        }
    }

    private static void clearBackendObservation(boolean scopedOnly) {
        try {
            CLEAR_BACKEND.invoke(null, scopedOnly);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("clearBackendObservation failed", e);
        }
    }

    private static Object observeUnscoped(Object backend) {
        try {
            Object observed = OBSERVE_BACKEND.invoke(null, backend, false);
            check(observed != null, "unscoped observation is recorded");
            return observed;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("observeBackend failed", e);
        }
    }

    private static Object observedBackend() {
        try {
            return OBSERVED_BACKEND.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("observedBackend read failed", e);
        }
    }

    private static Object spared() {
        try {
            return SPARED.get(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("spared read failed", e);
        }
    }

    /** Reads a long field from the private Spared decision object. */
    private static long sparedField(Object decision, String name) {
        try {
            Field f = decision.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return (Long) f.get(decision);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Spared." + name + " read failed", e);
        }
    }

    private static long backendEpoch() {
        try {
            return (Long) BACKEND_EPOCH.invoke(null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("backendEpoch failed", e);
        }
    }

    private static boolean backendIsCurrent(Object snapshot, Object candidate) {
        try {
            return (Boolean) BACKEND_IS_CURRENT.invoke(null, snapshot, candidate);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("backendIsCurrent failed", e);
        }
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
