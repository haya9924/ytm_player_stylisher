package app.morphe.extension.ym;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;

/**
 * Hides the player's audio/video switch pill.
 *
 * <p>Applied once after the host resolves the pill from its layout. The change is intentionally
 * not reverted at runtime, so the settings screen asks the user to restart the app.
 */
public final class AudioVideoPill {
    /** Shared preference key; disabled by default. */
    public static final String ENABLED = "hide_audio_video_pill";

    private AudioVideoPill() {}

    /** Injection point; null-safe and a no-op unless the user enabled the option. */
    public static void apply(View view) {
        if (view == null) return;
        if (!isEnabled(view.getContext())) return;

        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params != null) {
            // Upstream-proven collapse: keep margins/gravity, zero only the size.
            params.width = 0;
            params.height = 0;
            view.setLayoutParams(params);
        }
        view.setVisibility(View.GONE);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }

    private static boolean isEnabled(Context context) {
        if (context == null) return false;
        try {
            Context app = context.getApplicationContext();
            SharedPreferences prefs = (app == null ? context : app)
                    .getSharedPreferences(GradientSettingsActivity.PREFS, Context.MODE_PRIVATE);
            return prefs.getBoolean(ENABLED, false);
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
