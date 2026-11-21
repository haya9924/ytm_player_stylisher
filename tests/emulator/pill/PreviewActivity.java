package app.morphe.extension.ym;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Runs the production settings screen and view helper without the ARM-only host APK. */
public final class PreviewActivity extends Activity {
    @Override protected void onStart() {
        super.onStart();
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        page.setPadding(padding, padding, padding, padding);

        TextView title = new TextView(this);
        title.setText("エミュレーター検証用プレイヤー");
        title.setTextSize(22);
        page.addView(title);

        TextView note = new TextView(this);
        note.setText("YouTube Music 本体ではありません");
        page.addView(note);

        Button pill = new Button(this);
        pill.setText("音声  /  動画");
        page.addView(pill);
        AudioVideoPill.apply(pill);
        boolean expectedHidden = getSharedPreferences(GradientSettingsActivity.PREFS, MODE_PRIVATE)
                .getBoolean(AudioVideoPill.ENABLED, false);
        if (expectedHidden) {
            if (pill.getVisibility() != View.GONE || pill.getLayoutParams().width != 0
                    || pill.getLayoutParams().height != 0 || pill.getImportantForAccessibility()
                    != View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS) {
                throw new AssertionError("Enabled: pill must collapse and be inaccessible");
            }
        } else if (pill.getVisibility() != View.VISIBLE || pill.getLayoutParams().height == 0) {
            throw new AssertionError("Disabled: native pill properties must stay intact");
        }

        Button settings = new Button(this);
        settings.setText("ym_morphe プレイヤー設定");
        settings.setOnClickListener(v -> startActivity(new Intent(this, GradientSettingsActivity.class)));
        page.addView(settings);
        setContentView(page);
    }
}
