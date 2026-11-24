package app.morphe.extension.ym;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Predicate;

public final class CacheEvictionPreferenceTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static <T> void same(T actual, T expected, String message) {
        check(actual == expected, message + ": " + actual + " != " + expected);
    }

    public static void main(String[] args) {
        nativeOrderWithoutPolicy();
        hotThenCold();
        allHot();
        scanLimit();
        predicateFailure();
        invalidBound();
        emptyInputRejected();
        noMutationOrReordering();
        System.out.println("CacheEvictionPreference tests passed");
    }

    private static void nativeOrderWithoutPolicy() {
        List<String> entries = Arrays.asList("a", "b", "c");
        Predicate<String> nonePreferred = value -> false;
        same(CacheEvictionPreference.select(entries, nonePreferred, 3), "a",
                "nothing preferred keeps native first");
        same(CacheEvictionPreference.select(entries, nonePreferred, 1), "a",
                "single inspected keeps native first");
        same(CacheEvictionPreference.select(entries, nonePreferred, 100), "a",
                "large bound keeps native first");
    }

    private static void hotThenCold() {
        // Preferred (hot) entries are protected; the first cold one is chosen.
        List<String> entries = Arrays.asList("hot1", "hot2", "cold", "hot3");
        Predicate<String> hot = value -> value.startsWith("hot");
        same(CacheEvictionPreference.select(entries, hot, 4), "cold",
                "first unprotected wins");
        same(CacheEvictionPreference.select(entries, hot, 3), "cold",
                "first unprotected within bound");
        same(CacheEvictionPreference.select(entries, hot, 2), "hot1",
                "no unprotected within bound falls back to first");
        same(CacheEvictionPreference.select(entries, hot, 1), "hot1",
                "only first inspected falls back to first");
        same(CacheEvictionPreference.select(entries, hot, 0), "hot1",
                "zero bound falls back to first");
        same(CacheEvictionPreference.select(entries, hot, -1), "hot1",
                "negative bound falls back to first");
    }

    private static void allHot() {
        List<String> entries = Arrays.asList("hot1", "hot2", "hot3");
        Predicate<String> hot = value -> value.startsWith("hot");
        same(CacheEvictionPreference.select(entries, hot, 3), "hot1",
                "all preferred falls back to first");
        same(CacheEvictionPreference.select(entries, hot, 1000), "hot1",
                "all preferred with large bound falls back to first");

        List<String> single = Collections.singletonList("only");
        same(CacheEvictionPreference.select(single, hot, 1000), "only",
                "single preferred element stays");
        same(CacheEvictionPreference.select(single, value -> false, 1000), "only",
                "single unprotected element stays");
    }

    private static void scanLimit() {
        List<String> entries = Arrays.asList("hot1", "hot2", "hot3", "cold");
        Predicate<String> hot = value -> value.startsWith("hot");

        // maxInspected counts every inspected element, including the first.
        same(CacheEvictionPreference.select(entries, hot, 3), "hot1",
                "cold just past bound is not inspected");
        same(CacheEvictionPreference.select(entries, hot, 4), "cold",
                "cold at bound is inspected");
        same(CacheEvictionPreference.select(entries, hot, Integer.MAX_VALUE), "cold",
                "maximum bound reaches cold");

        // The scan must not run past the requested count.
        CountingPredicate counting = new CountingPredicate(hot);
        same(CacheEvictionPreference.select(entries, counting, 2), "hot1",
                "bound stops scanning");
        check(counting.calls == 2, "predicate calls bounded: " + counting.calls);
    }

    private static void predicateFailure() {
        List<String> entries = Arrays.asList("a", "b", "c");
        Predicate<String> throwsOnFirst = value -> {
            throw new IllegalStateException("boom");
        };
        same(CacheEvictionPreference.select(entries, throwsOnFirst, 3), "a",
                "throwing predicate falls back to first");

        Predicate<String> throwsLater = value -> {
            if (value.equals("b")) throw new IllegalArgumentException("boom");
            return true;
        };
        same(CacheEvictionPreference.select(entries, throwsLater, 3), "a",
                "failure during scan falls back to first");

        List<String> brokenIterable = new AbstractListWithFailingIterator("a", "b");
        same(CacheEvictionPreference.select(brokenIterable, value -> true, 3), "a",
                "failing iteration falls back to first");
    }

    private static void invalidBound() {
        List<Integer> entries = Arrays.asList(1, 2, 3);
        Predicate<Integer> allPreferred = value -> true;
        same(CacheEvictionPreference.select(entries, allPreferred, 0), 1,
                "zero bound");
        same(CacheEvictionPreference.select(entries, allPreferred, -5), 1,
                "negative bound");
        same(CacheEvictionPreference.select(entries, allPreferred, Integer.MIN_VALUE), 1,
                "minimum bound");
    }

    private static void emptyInputRejected() {
        try {
            CacheEvictionPreference.select(new ArrayList<String>(), value -> false, 3);
        } catch (NoSuchElementException expected) {
            return;
        }
        throw new AssertionError("empty input was accepted");
    }

    private static void noMutationOrReordering() {
        List<String> entries = new ArrayList<>(Arrays.asList("hot1", "hot2", "cold"));
        List<String> snapshot = new ArrayList<>(entries);
        same(CacheEvictionPreference.select(entries, value -> value.startsWith("hot"), 3),
                "cold", "selection from mutable list");
        check(entries.equals(snapshot), "list must stay unchanged: " + entries);

        // Repeated calls stay independent: still the same cold entry, same order.
        same(CacheEvictionPreference.select(entries, value -> value.startsWith("hot"), 3),
                "cold", "second call result");
        check(entries.equals(snapshot), "list unchanged after repeated calls");
    }

    private static final class CountingPredicate implements Predicate<String> {
        private final Predicate<String> delegate;
        int calls;

        CountingPredicate(Predicate<String> delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean test(String value) {
            calls++;
            return delegate.test(value);
        }
    }

    private static final class AbstractListWithFailingIterator extends ArrayList<String> {
        AbstractListWithFailingIterator(String... values) {
            super(Arrays.asList(values));
        }

        @Override
        public Iterator<String> iterator() {
            return new Iterator<String>() {
                private int index;

                @Override
                public boolean hasNext() {
                    if (index >= 1) throw new IllegalStateException("iteration failed");
                    return index < size();
                }

                @Override
                public String next() {
                    return get(index++);
                }
            };
        }
    }
}
