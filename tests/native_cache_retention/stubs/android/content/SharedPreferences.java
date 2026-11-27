package android.content;

/** Minimal JVM replacement for the Android shared preferences interface. */
public interface SharedPreferences {
    interface Editor {
        Editor putBoolean(String key, boolean value);

        Editor putInt(String key, int value);

        Editor putString(String key, String value);

        Editor remove(String key);

        void apply();

        boolean commit();
    }

    interface OnSharedPreferenceChangeListener {
        void onSharedPreferenceChanged(SharedPreferences prefs, String key);
    }

    boolean getBoolean(String key, boolean fallback);

    int getInt(String key, int fallback);

    String getString(String key, String fallback);

    Editor edit();

    void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener);

    void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener);
}
