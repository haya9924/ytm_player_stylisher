package android.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Lightweight JVM replacement that records diagnostics instead of printing them. */
public final class Log {
    private static final List<String> MESSAGES = Collections.synchronizedList(new ArrayList<>());

    private Log() {}

    public static int d(String tag, String message) {
        MESSAGES.add(tag + ": " + message);
        return 0;
    }

    public static List<String> messages() {
        synchronized (MESSAGES) {
            return new ArrayList<>(MESSAGES);
        }
    }

    public static int count(String message) {
        int matches = 0;
        for (String entry : messages()) {
            if (entry.endsWith(": " + message)) matches++;
        }
        return matches;
    }

    public static void clear() {
        MESSAGES.clear();
    }
}
