package android.content;

import java.io.File;

/**
 * Minimal JVM replacement for the Android context.
 *
 * <p>Only the members used by the preference bridge exist. The tests never obtain a real instance;
 * the native cache tests drive {@code NativeCacheRetention} directly.
 */
public class Context {
    public static final int MODE_PRIVATE = 0;

    public Context getApplicationContext() {
        return this;
    }

    public String getPackageName() {
        return "app.morphe.test";
    }

    public File getNoBackupFilesDir() {
        return null;
    }

    public SharedPreferences getSharedPreferences(String name, int mode) {
        return null;
    }
}
