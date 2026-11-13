package app.morphe.extension.ym;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/** A small standalone settings screen; does not depend on Morphe's shared extension. */
public final class GradientSettingsActivity extends Activity {
    static final String PREFS = "ym_morphe_settings";
    static final String STRENGTH = "artwork_color_strength";
    static final int DEFAULT_STRENGTH = 100;

    /** Called before the host starts a preference intent; keep all other intents untouched. */
    public static void routeIntent(Activity activity, Intent intent) {
        if (activity != null && intent != null
                && "app.morphe.extension.ym.GRADIENT_SETTINGS".equals(intent.getAction())) {
            intent.setClass(activity, GradientSettingsActivity.class);
        }
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (24 * density + 0.5f);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(pad, pad, pad, pad);
        TextView title = new TextView(this);
        title.setText("グラデーションのジャケット色への寄せ具合");
        title.setTextSize(20);
        layout.addView(title);
        TextView value = new TextView(this);
        value.setPadding(0, pad / 2, 0, 0);
        layout.addView(value);
        SeekBar seek = new SeekBar(this);
        seek.setMax(100);
        int initial = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(STRENGTH, DEFAULT_STRENGTH);
        seek.setProgress(Math.max(0, Math.min(100, initial)));
        value.setText(seek.getProgress() + "%");
        seek.setContentDescription("ジャケット色への寄せ具合");
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                value.setText(progress + "%");
                if (fromUser) getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(STRENGTH, progress).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        layout.addView(seek);
        TextView explanation = new TextView(this);
        explanation.setText("0%：テーマの背景色　100%：ぼかしたジャケット画像");
        layout.addView(explanation);
        Button close = new Button(this);
        close.setText("閉じる");
        close.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(-2, -2);
        buttonParams.gravity = Gravity.END;
        layout.addView(close, buttonParams);
        setContentView(layout);
    }
}
