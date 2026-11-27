package app.morphe.extension.ym;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.function.Predicate;

/**
 * Picks a cache entry to evict from a collection that is already in its native order.
 * Pure helper: no cache, media, expiry or DRM access and no state between calls.
 */
final class CacheEvictionPreference {
    private CacheEvictionPreference() {}

    /**
     * Returns the first element of {@code nativeOrder} unless it is preferred to keep.
     * In that case the first element that is not preferred to keep is returned, looking
     * at no more than {@code maxInspected} elements. When no such element exists, or the
     * bound is invalid, the original first element is returned.
     *
     * <p>The iterable must not be empty. The iterable is only read, never reordered or
     * modified. A {@link RuntimeException} thrown by the predicate falls back to the
     * first element.
     *
     * @throws NoSuchElementException when {@code nativeOrder} has no elements
     */
    static <T> T select(Iterable<T> nativeOrder, Predicate<? super T> isPreferredToKeep,
            int maxInspected) {
        Iterator<T> iterator = nativeOrder.iterator();
        // Empty input is a caller error and must stay visible.
        T first = iterator.next();
        if (maxInspected < 1) return first;
        try {
            if (!isPreferredToKeep.test(first)) return first;
            int inspected = 1;
            while (inspected < maxInspected && iterator.hasNext()) {
                T candidate = iterator.next();
                inspected++;
                if (!isPreferredToKeep.test(candidate)) return candidate;
            }
        } catch (RuntimeException failure) {
            // The predicate or the iterable is unreliable; keep the native first entry.
            return first;
        }
        return first;
    }
}
