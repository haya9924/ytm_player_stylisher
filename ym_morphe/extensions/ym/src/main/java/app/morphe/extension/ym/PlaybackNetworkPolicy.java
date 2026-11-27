package app.morphe.extension.ym;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

/** Admission-only policy: never queues, cancels or delays a host callback. */
public final class PlaybackNetworkPolicy {
    static final String ENABLED = "playback_network_priority";
    private static final PlaybackPriorityState STATE = new PlaybackPriorityState();
    private static SharedPreferences preferences;
    // SharedPreferences holds listeners weakly; retain this non-Activity listener.
    private static final SharedPreferences.OnSharedPreferenceChangeListener LISTENER =
            (prefs, key) -> { if (ENABLED.equals(key)) reload(prefs); };

    private PlaybackNetworkPolicy() {}

    public static synchronized void initialize(Context context) {
        if (preferences != null || context == null) return;
        try {
            Context app = context.getApplicationContext();
            if (app == null) return;
            SharedPreferences prefs = app.getSharedPreferences(GradientSettingsActivity.PREFS, Context.MODE_PRIVATE);
            prefs.registerOnSharedPreferenceChangeListener(LISTENER);
            reload(prefs);
            preferences = prefs;
        } catch (RuntimeException ignored) {
            STATE.setEnabled(false);
        }
    }

    private static void reload(SharedPreferences prefs) {
        try { STATE.setEnabled(prefs.getBoolean(ENABLED, false)); }
        catch (RuntimeException ignored) { STATE.setEnabled(false); }
    }

    static void setEnabled(Context context, boolean enabled) {
        initialize(context);
        SharedPreferences prefs = context.getSharedPreferences(GradientSettingsActivity.PREFS, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(ENABLED, enabled).apply();
        STATE.setEnabled(enabled);
    }

    public static void onUrgent(Object owner) { STATE.onUrgent(owner, SystemClock.elapsedRealtime()); }
    public static void onPlaying(Object owner) { STATE.onPlaying(owner, SystemClock.elapsedRealtime()); }
    public static void onTerminal(Object owner) { STATE.onTerminal(owner); }
    public static boolean shouldSuppress() {
        boolean suppress = STATE.shouldSuppress(SystemClock.elapsedRealtime());
        DataSavingDiagnostics.onEligiblePrefetch(!suppress);
        return suppress;
    }
}
