package app.morphe.extension.ym;

import android.os.SystemClock;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;

/**
 * Retains already-cached audio by steering the host's capacity-victim choice towards entries the
 * admitted account has played. Applies an account-qualified audio preference to whichever native
 * {@code augy} backend is currently observed, scoped or unscoped.
 *
 * <p>Observation only: this class never mutates host maps, cache budgets, or stream expiration, and
 * it never fetches audio itself. Any decision that is not provably current is abandoned.
 */
public final class NativeCacheRetention {
    private static final int MAX_LOOKUP = 8;
    private static final int MAX_TRACKS = 50;
    private static final long MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000;
    private static final AtomicInteger reports = new AtomicInteger();
    private static final AtomicInteger scopeReports = new AtomicInteger();
    private static final AtomicInteger qualifiedReports = new AtomicInteger();
    private static final AtomicInteger matchingReads = new AtomicInteger();
    private static final ThreadLocal<WeakReference<Object>> capacityWriter = new ThreadLocal<>();
    private static final ThreadLocal<Long> scopeRead = new ThreadLocal<>();
    private static final ThreadLocal<Spared> pendingSpare = new ThreadLocal<>();
    private static volatile Spared spared;
    private static volatile String currentTrack;
    private static volatile String currentAccount;
    private static volatile long accountEpoch;
    private static volatile Policy activePolicy;
    private static final LinkedHashMap<String, Long> qualified = new LinkedHashMap<>();
    private static String qualifiedAccount;
    /** Guarded by POLICY_LOCK. */
    private static String scopedAccount;
    /** Restored installation-bound track keys, or null. Guarded by POLICY_LOCK. */
    private static Persisted persisted;
    private static final Object POLICY_LOCK = new Object();
    private static volatile Backend observedBackend;
    private static volatile long backendEpoch;

    /*
     * Runtime preferences. The Android-facing defaults are mirrored here so this class keeps zero
     * android.content / GradientSettingsActivity dependencies, which keeps the native JVM tests
     * completely decoupled from the Android UI. Bridge.initialize() publishes the real values.
     */
    private static volatile boolean retentionEnabled = true;
    private static volatile int userBudgetMiB = CacheRetentionPreferences.DEFAULT_CACHE_BUDGET_MIB;
    private static volatile long lastNativeBudgetBytes = CacheSavingsEstimator.DEFAULT_STANDARD_BUDGET_BYTES;
    private static final AtomicInteger sessionSparedCount = new AtomicInteger();

    private NativeCacheRetention() {}

    /**
     * Publishes the current preference snapshot. Idempotent and safe to call repeatedly; a budget
     * outside the supported range falls back to the automatic host budget.
     */
    static void applyPreferences(boolean enabled, int budgetMiB) {
        retentionEnabled = enabled;
        userBudgetMiB = CacheRetentionPreferences.isValidBudgetMiB(budgetMiB)
                ? budgetMiB : CacheRetentionPreferences.DEFAULT_CACHE_BUDGET_MIB;
    }

    /**
     * Resolves the cache budget the host should use.
     *
     * <p>The native budget is remembered for telemetry. A positive user budget replaces it, a
     * positive {@code testCapMiB} can only lower the result, and {@code 0} means "use the host
     * budget unchanged".
     */
    public static long resolveCacheBudget(long nativeBudget, int testCapMiB) {
        if (nativeBudget > 0L) lastNativeBudgetBytes = nativeBudget;
        long resolved = userBudgetMiB > 0 ? userBudgetMiB * 1024L * 1024L : nativeBudget;
        if (testCapMiB > 0 && resolved > 0L) {
            resolved = Math.min(resolved, testCapMiB * 1024L * 1024L);
        }
        return resolved;
    }

    /** Opt-in test hook only: caps the host cache budget to min(nativeBudget, capMiB MiB). */
    public static long capTestBudgetMiB(long nativeBudget, int capMiB) {
        if (capMiB <= 0 || nativeBudget <= 0L) return nativeBudget;
        long capBytes = capMiB * 1024L * 1024L;
        return Math.min(nativeBudget, capBytes);
    }

    public static void beginScopeRead() {
        scopeRead.set(accountEpoch);
    }

    /** Called only with the original identity argument and cache returned to the native player. */
    public static void scopedBackend(Object identity, Object backend) {
        Long began = scopeRead.get();
        scopeRead.remove();
        if (began == null) {
            if (identity == null) unscopedSupplier();
            return;
        }
        if (began != accountEpoch) return;
        try {
            // Resolve the account and reflection metadata before taking the policy monitor.
            String account = ScopedPlayHost.accountKey(identity);
            Fields fields = account != null && backend != null
                    && "augy".equals(backend.getClass().getName())
                    ? resolveFields(backend.getClass()) : null;
            boolean unavailable = false;
            synchronized (POLICY_LOCK) {
                if (began != accountEpoch) return;
                if (account == null || fields == null) {
                    if (identity != null || scopedAccount != null
                            || (observedBackend != null && observedBackend.scoped)) invalidateLocked();
                    unavailable = true;
                } else {
                    // Idempotent re-bind: same backend, same account, nothing to publish or report.
                    if (observedBackend != null && observedBackend.matches(backend)
                            && observedBackend.scoped && account.equals(scopedAccount)) return;
                    boolean switched = (qualifiedAccount != null && !qualifiedAccount.equals(account))
                            || (scopedAccount != null && !scopedAccount.equals(account));
                    if (switched) {
                        // Retire the previous account's policy BEFORE publishing the new backend so a
                        // lock-free reader never pairs the new backend with the old epoch/policy.
                        accountEpoch++;
                        spared = null;
                        qualified.clear();
                        activePolicy = null;
                        persisted = null;
                    }
                    observeBackendLocked(backend, fields, true);
                    scopedAccount = account;
                    qualifiedAccount = account;
                    if (!qualified.isEmpty() && activePolicy == null) {
                        activePolicy = new Policy(account, accountEpoch,
                                new HashMap<>(qualified), persisted);
                    }
                }
            }
            report(scopeReports, unavailable ? "capacity scope unavailable" : "capacity scope bound");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            boolean stale;
            synchronized (POLICY_LOCK) {
                stale = began != accountEpoch;
                if (!stale) invalidateLocked();
            }
            // A stale scope read must never invalidate the epoch that replaced it.
            if (!stale) report(scopeReports, "capacity scope unavailable");
        }
    }

    public static void unscopedSupplier() {
        scopeRead.remove();
        report(scopeReports, "native unscoped cache supplied");
    }

    /**
     * Called with the cache returned by the shared (non account-scoped) supplier. Observation is
     * deliberately independent from the admitted account: it never advances the account epoch nor
     * discards admitted tracks, so a candidate admitted before discovery stays valid.
     */
    public static void unscopedBackend(Object backend) {
        scopeRead.remove();
        if (backend == null || !"augy".equals(backend.getClass().getName())) {
            report(scopeReports, "native unscoped cache supplied");
            return;
        }
        Backend current = observedBackend;
        if (current != null && current.matches(backend) && !current.scoped) return;
        try {
            Fields fields = resolveFields(backend.getClass());
            synchronized (POLICY_LOCK) {
                if (observedBackend != null && observedBackend.matches(backend)
                        && !observedBackend.scoped) return;
                observeBackendLocked(backend, fields, false);
                // The active observation is unscoped now: no account is bound to it. Cross-account
                // mixing within the epoch is still prevented by qualifiedAccount.
                scopedAccount = null;
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            report(scopeReports, "native unscoped cache supplied");
            return;
        }
        report(scopeReports, "native unscoped cache bound");
    }

    /** Account transitions are observed in the native providers, not guessed from cache use. */
    public static void invalidate() {
        synchronized (POLICY_LOCK) {
            invalidateLocked();
        }
    }

    private static void invalidateLocked() {
        accountEpoch++;
        activePolicy = null;
        spared = null;
        qualified.clear();
        qualifiedAccount = null;
        scopedAccount = null;
        persisted = null;
        clearBackendObservationLocked(true);
    }

    static void currentPlayback(String account, String track) {
        currentTrack = null;
        currentAccount = account;
        currentTrack = track;
    }

    static boolean badgeMayBeActive() {
        Spared hit = spared;
        return observedBackend != null && activePolicy != null && hit != null
                && hit.accountEpoch == accountEpoch && hit.backendEpoch == backendEpoch;
    }

    public static boolean currentTrackSpared() {
        Spared hit = spared;
        Backend currentBackend = observedBackend;
        Policy currentPolicy = activePolicy;
        long epoch = accountEpoch;
        if (hit == null || currentBackend == null || currentPolicy == null
                || hit.accountEpoch != epoch || currentPolicy.accountEpoch != epoch
                || hit.backendEpoch != currentBackend.epoch
                || !backendIsCurrent(currentBackend, hit.backend.get())
                || !currentPolicy.account.equals(currentAccount)
                || !hit.track.equals(currentTrack)) return false;
        long age = SystemClock.elapsedRealtime() - hit.confirmedAt;
        return hit.confirmedAt >= 0 && age >= 0 && age < 5_000;
    }

    /**
     * A completed capacity decision. Fenced on both epochs so a decision cannot survive an account
     * transition or a backend replacement. The host call being observed is {@code augy.E(id, true)},
     * which may drop only part of an entry's stored spans; this never claims a whole entry was freed.
     */
    private static final class Spared {
        final WeakReference<Object> backend;
        final String track;
        final long accountEpoch;
        final long backendEpoch;
        volatile long confirmedAt = -1;
        Spared(Object backend, String track, long accountEpoch, long backendEpoch) {
            this.backend = new WeakReference<>(backend);
            this.track = track;
            this.accountEpoch = accountEpoch;
            this.backendEpoch = backendEpoch;
        }
    }

    static long generation() {
        return accountEpoch;
    }

    /**
     * Called off the player thread after reading a successfully committed play count. Qualification
     * is an account fact, so it no longer requires an observed backend.
     */
    static void qualified(String account, String track, int count, int threshold, long generation) {
        if (count < threshold || threshold < 1
                || !QualifiedPlayCounter.isValidId(account) || !QualifiedPlayCounter.isValidId(track)) return;
        long now = SystemClock.elapsedRealtime();
        String reported = null;
        synchronized (POLICY_LOCK) {
            if (generation != accountEpoch) return;
            // Never mix two accounts within one epoch, scoped or not.
            if (scopedAccount != null && !scopedAccount.equals(account)) return;
            if (qualifiedAccount != null && !qualifiedAccount.equals(account)) return;
            qualifiedAccount = account;
            qualified.remove(track);
            qualified.put(track, now);
            while (qualified.size() > MAX_TRACKS) qualified.remove(qualified.keySet().iterator().next());
            activePolicy = new Policy(account, accountEpoch, new HashMap<>(qualified), persisted);
            reported = "capacity preference ready";
        }
        if (reported != null) report(qualifiedReports, reported);
    }

    /**
     * Restores durable qualifications for {@code account} after a cold start. Only derived
     * installation-bound track keys are supplied; the raw account and track identifiers are never
     * stored, logged, or persisted. The copies are made outside {@link #POLICY_LOCK}, which only
     * publishes the already-built immutable snapshot.
     *
     * @param account opaque account identifier, re-validated here
     * @param secret installation secret, exactly {@code SECRET_BYTES} bytes; defensively copied
     * @param trackKeys derived track keys from the durable counter store; defensively copied
     * @param generation the account epoch the caller observed before doing disk work
     */
    static void hydratePersisted(String account, byte[] secret, Set<String> trackKeys, long generation) {
        if (!QualifiedPlayCounter.isValidId(account)
                || secret == null || secret.length != ScopedPlaybackKeys.SECRET_BYTES
                || trackKeys == null || trackKeys.isEmpty()) return;
        byte[] secretCopy = Arrays.copyOf(secret, secret.length);
        Set<String> keysCopy = Collections.unmodifiableSet(new HashSet<>(trackKeys));
        Persisted restored = new Persisted(secretCopy, keysCopy);
        synchronized (POLICY_LOCK) {
            if (generation != accountEpoch) return;
            if (scopedAccount != null && !scopedAccount.equals(account)) return;
            if (qualifiedAccount != null && !qualifiedAccount.equals(account)) return;
            qualifiedAccount = account;
            persisted = restored;
            activePolicy = new Policy(account, accountEpoch, new HashMap<>(qualified), restored);
        }
        report(qualifiedReports, "capacity preference restored");
    }

    /** The native lookup has already produced a cached span; does not read it again. */
    public static void cachedLookup(Object backend, Object decodedKey) {
        Backend currentBackend = observedBackend;
        Policy currentPolicy = activePolicy;
        long epoch = accountEpoch;
        if (currentBackend == null || currentPolicy == null || decodedKey == null
                || !backendIsCurrent(currentBackend, backend)
                || currentPolicy.accountEpoch != epoch) return;
        try {
            Fields fields = currentBackend.fields;
            if (decodedKey.getClass() != fields.keyClass) return;
            String key = (String) fields.keyId.get(decodedKey);
            Spared hit = spared;
            if (hit != null && hit.accountEpoch == epoch && hit.backendEpoch == currentBackend.epoch
                    && hit.backend.get() == backend
                    && hit.track.equals(key)) hit.confirmedAt = SystemClock.elapsedRealtime();
            if (currentPolicy.prefers(key, SystemClock.elapsedRealtime())) {
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

    public static void capacityCall(Object writer, int excessComparison) {
        if (excessComparison > 0) capacityWriter.set(new WeakReference<>(writer));
        else capacityWriter.remove();
    }

    public static void endCapacityCall() {
        capacityWriter.remove();
        pendingSpare.remove();
    }

    /**
     * Marks the return of the host's capacity eviction call ({@code augy.E(id, true)}). The call may
     * remove only some of an entry's stored spans depending on its flags, so this never implies that
     * a whole entry was deleted. The decision is published only if both epochs still match: a
     * backend replacement or account transition between selection and this return fences it out.
     */
    public static void capacityVictimRemoved() {
        Spared decision = pendingSpare.get();
        pendingSpare.remove();
        if (decision == null || decision.accountEpoch != accountEpoch
                || decision.backendEpoch != backendEpoch) return;
        if (!backendIsCurrent(observedBackend, decision.backend.get())) return;
        spared = decision;
        sessionSparedCount.incrementAndGet();
    }

    private static String choose(Object backend, LinkedHashSet<?> nativeOrder, String original, boolean apply) {
        pendingSpare.remove();
        WeakReference<Object> writer = capacityWriter.get();
        capacityWriter.remove();
        // Disabled retention is a strict no-op: no backend, policy, or host state is read.
        if (!retentionEnabled) return original;
        if (writer == null || writer.get() != backend) return original;
        Backend currentBackend = observedBackend;
        Policy currentPolicy = activePolicy;
        long epoch = accountEpoch;
        if (currentBackend == null || currentPolicy == null || original == null
                || !backendIsCurrent(currentBackend, backend) || currentPolicy.accountEpoch != epoch) {
            return original;
        }
        try {
            long now = SystemClock.elapsedRealtime();
            if (!currentPolicy.prefers(original, now) || nativeOrder == null || original.isEmpty()) return original;
            Fields fields = currentBackend.fields;
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
                if (!(key instanceof String) || currentPolicy.prefers((String) key, now)) continue;
                if (!fields.audioOnly(entries, (String) key)) continue;
                if (!backendIsCurrent(currentBackend, backend) || activePolicy != currentPolicy
                        || accountEpoch != epoch) return original;
                report(reports, apply ? "capacity preferred entry spared" : "capacity would spare preferred entry");
                if (apply) {
                    // Fence on both epochs: the backend that served this decision must still be the
                    // observed one, and the admitted account must not have changed since admission.
                    pendingSpare.set(new Spared(backend, original, epoch, currentBackend.epoch));
                }
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

    /*
     * Backend observation. The policy monitor never performs reflection or touches host state:
     * Fields is resolved lock-free and only the in-memory backend snapshot is mutated here. The
     * snapshot is intentionally independent from the account epoch: discovering or replacing a
     * backend never invalidates admitted tracks for the same account.
     */

    static Backend observeBackend(Object candidate, boolean scoped) {
        if (candidate == null || !"augy".equals(candidate.getClass().getName())) return null;
        Backend current = observedBackend;
        if (current != null && current.matches(candidate) && current.scoped == scoped) return current;
        try {
            Fields fields = resolveFields(candidate.getClass());
            synchronized (POLICY_LOCK) {
                return observeBackendLocked(candidate, fields, scoped);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    /** Lock-free metadata lookup; reuses the current snapshot when the cache class is unchanged. */
    static Fields resolveFields(Class<?> cache) throws ReflectiveOperationException {
        Backend current = observedBackend;
        return current != null && current.fields.cacheClass == cache
                ? current.fields : new Fields(cache);
    }

    /** Called only under POLICY_LOCK; caller has already validated the candidate and resolved Fields. */
    private static Backend observeBackendLocked(Object candidate, Fields fields, boolean scoped) {
        Backend current = observedBackend;
        if (current != null && current.matches(candidate) && current.scoped == scoped) return current;
        backendEpoch++;
        Backend observed = new Backend(candidate, fields, backendEpoch, scoped);
        observedBackend = observed;
        return observed;
    }

    static void clearBackendObservation(boolean scopedOnly) {
        synchronized (POLICY_LOCK) {
            clearBackendObservationLocked(scopedOnly);
        }
    }

    private static void clearBackendObservationLocked(boolean scopedOnly) {
        Backend current = observedBackend;
        if (current == null) return;
        if (scopedOnly && !current.scoped) return;
        observedBackend = null;
        backendEpoch++;
    }

    /** Lock-free by contract: later this runs while the native cache lock is already held. */
    static boolean backendIsCurrent(Backend snapshot, Object candidate) {
        return snapshot != null && candidate != null && observedBackend == snapshot
                && snapshot.epoch == backendEpoch && snapshot.ref.get() == candidate;
    }

    static long backendEpoch() {
        return backendEpoch;
    }

    private static final class Backend {
        final WeakReference<Object> ref;
        final Fields fields;
        final long epoch;
        final boolean scoped;

        Backend(Object backend, Fields fields, long epoch, boolean scoped) {
            this.ref = new WeakReference<>(backend);
            this.fields = fields;
            this.epoch = epoch;
            this.scoped = scoped;
        }

        boolean matches(Object candidate) {
            return candidate != null && ref.get() == candidate;
        }
    }

    /** Immutable account policy, decoupled from whichever backend currently serves the cache. */
    /**
     * Durable qualification snapshot restored at cold start: the installation secret and the derived
     * track keys only. The secret is never persisted or logged by this class, and no raw identifier
     * is retained. Reads are pure in-memory HMAC derivations, so they are safe under the host lock.
     */
    private static final class Persisted {
        final byte[] secret;
        final Set<String> trackKeys;

        Persisted(byte[] secret, Set<String> trackKeys) {
            this.secret = secret;
            this.trackKeys = trackKeys;
        }

        boolean prefers(String account, String track) {
            String key = ScopedPlaybackKeys.deriveTrackKey(secret, account, track);
            return key != null && trackKeys.contains(key);
        }
    }

    /**
     * Immutable account policy, decoupled from whichever backend currently serves the cache. The
     * in-session window is checked first and stays O(1); the durable set is consulted only on a miss.
     */
    private static final class Policy {
        final String account;
        final long accountEpoch;
        final Map<String, Long> preferred;
        final Persisted persisted;

        Policy(String account, long accountEpoch, Map<String, Long> preferred, Persisted persisted) {
            this.account = account;
            this.accountEpoch = accountEpoch;
            this.preferred = preferred;
            this.persisted = persisted;
        }

        boolean prefers(String track, long now) {
            Long since = preferred.get(track);
            if (since != null && now >= since && now - since < MAX_AGE_MS) return true;
            return persisted != null && !persisted.trackKeys.isEmpty()
                    && persisted.prefers(account, track);
        }
    }

    /** The account that currently owns the retention policy, or {@code null}. */
    static String activeAccountKey() {
        String account = currentAccount;
        if (account != null) return account;
        Policy policy = activePolicy;
        return policy == null ? null : policy.account;
    }

    /** Immutable live cache telemetry for the settings screen and the savings estimator. */
    static final class CacheTelemetry {
        final long nativeBudgetBytes;
        final long usedBytes;
        final int audioTracks;
        final long audioBytes;
        final int preferredAudioTracks;
        final int sessionSparedCount;

        CacheTelemetry(long nativeBudgetBytes, long usedBytes, int audioTracks, long audioBytes,
                int preferredAudioTracks, int sessionSparedCount) {
            this.nativeBudgetBytes = nativeBudgetBytes;
            this.usedBytes = usedBytes;
            this.audioTracks = audioTracks;
            this.audioBytes = audioBytes;
            this.preferredAudioTracks = preferredAudioTracks;
            this.sessionSparedCount = sessionSparedCount;
        }
    }

    /**
     * Reads the live cache and counter state without ever holding {@link #POLICY_LOCK} while
     * touching the host cache. A missing field, an unavailable lock, or an unexpectedly shaped
     * host object degrades the affected value to zero instead of failing the caller.
     */
    static CacheTelemetry captureCacheTelemetry() {
        Backend backend = observedBackend;
        Policy policy = activePolicy;
        Object raw = backend == null ? null : backend.ref.get();
        long usedBytes = 0L;
        long audioBytes = 0L;
        int audioTracks = 0;
        int preferredAudioTracks = 0;
        if (raw != null) {
            Fields fields = backend.fields;
            try {
                if (fields.usedBytes != null) {
                    Object value = fields.usedBytes.get(raw);
                    if (value instanceof AtomicLong) {
                        usedBytes = ((AtomicLong) value).get();
                    } else if (value instanceof Number) {
                        usedBytes = ((Number) value).longValue();
                    }
                }
                if (fields.hostLock != null) {
                    Object candidate = fields.hostLock.get(raw);
                    if (candidate instanceof Lock) {
                        Lock lock = (Lock) candidate;
                        boolean locked = false;
                        try {
                            locked = lock.tryLock(25L, TimeUnit.MILLISECONDS);
                            if (locked) {
                                Map<?, ?> entries = (Map<?, ?>) fields.entries.get(raw);
                                if (entries != null) {
                                    long now = SystemClock.elapsedRealtime();
                                    for (Map.Entry<?, ?> entry : entries.entrySet()) {
                                        if (!(entry.getKey() instanceof String)) continue;
                                        String key = (String) entry.getKey();
                                        if (!fields.audioOnly(entries, key)) continue;
                                        audioTracks++;
                                        if (fields.entrySize != null) {
                                            Object size = fields.entrySize.invoke(entry.getValue());
                                            if (size instanceof Number) {
                                                audioBytes += ((Number) size).longValue();
                                            }
                                        }
                                        if (policy != null && policy.prefers(key, now)) {
                                            preferredAudioTracks++;
                                        }
                                    }
                                }
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        } finally {
                            if (locked) lock.unlock();
                        }
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                // Telemetry is strictly observational.
            }
        }
        return new CacheTelemetry(lastNativeBudgetBytes, usedBytes, audioTracks, audioBytes,
                preferredAudioTracks, sessionSparedCount.get());
    }

    private static final class Fields {
        final Class<?> cacheClass;
        final Field order, auxiliary, entries, entryId, formats, keyId;
        /** Optional live telemetry fields; {@code null} when the host build does not expose them. */
        final Field usedBytes, hostLock;
        final Class<?> entryClass, formatClass, keyClass;
        final Method itag, entrySize;

        Fields(Class<?> cache) throws ReflectiveOperationException {
            cacheClass = cache;
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
            // Telemetry is best effort: a build without these members must still work.
            usedBytes = optionalField(cache, "e");
            hostLock = optionalField(cache, "h");
            entrySize = optionalMethod(entryClass, "a");
        }

        private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        }

        private static Field optionalField(Class<?> owner, String name) {
            try {
                return field(owner, name);
            } catch (NoSuchFieldException | RuntimeException unavailable) {
                return null;
            }
        }

        private static Method optionalMethod(Class<?> owner, String name) {
            try {
                Method m = owner.getDeclaredMethod(name);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException | RuntimeException unavailable) {
                return null;
            }
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
