package app.morphe.extension.ym;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Switch;

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
        layout.setFocusableInTouchMode(true);
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
        explanation.setText("0%：テーマの背景色　100%：ぼかしたジャケット画像。文字が読めるよう明るさを調整します。");
        layout.addView(explanation);
        ArtworkTheme.initialize(this);
        Switch artworkTheme = new Switch(this);
        artworkTheme.setText("ジャケットの色をアプリ全体に反映");
        artworkTheme.setPadding(0, pad, 0, 0);
        SharedPreferences preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        artworkTheme.setChecked(preferences.getBoolean(ArtworkTheme.ENABLED, false));
        artworkTheme.setOnCheckedChangeListener((button, checked) ->
                preferences.edit().putBoolean(ArtworkTheme.ENABLED, checked).apply());
        layout.addView(artworkTheme);
        TextView themeDescription = new TextView(this);
        themeDescription.setText("再生中のジャケットに合わせて、アプリの背景やバーの色を変えます。プレイヤーとは明るさを変えて見分けやすくします。再起動は不要です。");
        layout.addView(themeDescription);
        PlaybackNetworkPolicy.initialize(this);
        Switch pills = new Switch(this);
        pills.setText("音声・動画の切り替えボタンを非表示");
        pills.setPadding(0, pad, 0, 0);
        pills.setChecked(getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(AudioVideoPill.ENABLED, false));
        pills.setOnCheckedChangeListener((button, checked) ->
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putBoolean(AudioVideoPill.ENABLED, checked).apply());
        layout.addView(pills);
        TextView pillsDescription = new TextView(this);
        pillsDescription.setText("プレイヤー上部の切り替えボタンだけを隠します。変更後はアプリを再起動してください。");
        layout.addView(pillsDescription);
        Switch priority = new Switch(this);
        priority.setText("曲の再生を優先（実験的）");
        priority.setPadding(0, pad, 0, 0);
        priority.setChecked(getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(PlaybackNetworkPolicy.ENABLED, false));
        priority.setOnCheckedChangeListener((button, checked) -> PlaybackNetworkPolicy.setEnabled(this, checked));
        layout.addView(priority);
        TextView priorityDescription = new TextView(this);
        priorityDescription.setText("準備・バッファリング・シーク中は、別の曲の新規先読みを控えます。再生開始後は最大3秒で制限を解除します。画像やログ、開始済みの通信は制御しません。次曲の開始が遅れる場合があります。初期設定はオフです。");
        layout.addView(priorityDescription);
        addCacheRetentionSection(layout, pad);
        Button close = new Button(this);
        close.setText("閉じる");
        close.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(-2, -2);
        buttonParams.gravity = Gravity.END;
        layout.addView(close, buttonParams);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackground(ArtworkTheme.background(new android.graphics.drawable.ColorDrawable(
                android.graphics.Color.TRANSPARENT), ArtworkSurfaceDrawable.CANVAS));
        scroll.addView(layout);
        setContentView(scroll);
        if (state == null) layout.requestFocus();
    }

    /**
     * Storage-only section: the criteria are saved, but nothing is counted or cached yet.
     * All three fields must be valid together; otherwise nothing is written at all.
     */
    private void addCacheRetentionSection(LinearLayout layout, int pad) {
        TextView heading = new TextView(this);
        heading.setText("キャッシュ保持の基準（準備中）");
        heading.setTextSize(18);
        heading.setPadding(0, pad, 0, 0);
        layout.addView(heading);
        TextView description = new TextView(this);
        description.setText("現段階では設定のみ保存し、再生回数の記録やキャッシュの保持は行いません。\n"
                + "1回と数えるのに必要な時間は、設定した秒数と、曲の長さに設定割合を掛けた時間のうち短い方です。"
                + "曲の長さが不明な場合は設定した秒数を使います。初期設定は2回・30秒・50%です。");
        layout.addView(description);

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        CacheRetentionCriteria saved = CacheRetentionPreferences.read(prefs);
        EditText playCount = numberField(layout, "保持に必要な再生回数（1〜" + CacheRetentionCriteria.MAX_PLAY_COUNT + "回）",
                saved.playCount, pad);
        EditText seconds = numberField(layout, "最低再生時間（1〜" + CacheRetentionCriteria.MAX_SECONDS + "秒）",
                saved.seconds, pad);
        EditText percent = numberField(layout, "曲の長さに対する割合（1〜" + CacheRetentionCriteria.MAX_PERCENT + "%）",
                saved.percent, pad);

        TextView status = new TextView(this);
        status.setPadding(0, pad / 2, 0, 0);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        layout.addView(status);
        Button save = new Button(this);
        save.setText("基準を保存");
        save.setOnClickListener(v -> {
            int playCountValue = parseInRange(playCount, CacheRetentionCriteria.MAX_PLAY_COUNT);
            int secondsValue = parseInRange(seconds, CacheRetentionCriteria.MAX_SECONDS);
            int percentValue = parseInRange(percent, CacheRetentionCriteria.MAX_PERCENT);
            boolean playCountOk = playCountValue >= 1;
            boolean secondsOk = secondsValue >= 1;
            boolean percentOk = percentValue >= 1;
            setFieldError(playCount, playCountOk ? null
                    : "1〜" + CacheRetentionCriteria.MAX_PLAY_COUNT + "回の整数で入力してください");
            setFieldError(seconds, secondsOk ? null
                    : "1〜" + CacheRetentionCriteria.MAX_SECONDS + "秒の整数で入力してください");
            setFieldError(percent, percentOk ? null
                    : "1〜" + CacheRetentionCriteria.MAX_PERCENT + "%の整数で入力してください");
            if (!playCountOk || !secondsOk || !percentOk) {
                // Invalid input saves nothing: the stored criteria and all fields stay as they are.
                status.setText("入力内容を確認してください。保存は行っていません。");
                return;
            }
            if (!CacheRetentionPreferences.save(prefs, playCountValue, secondsValue, percentValue)) {
                status.setText("保存できませんでした。もう一度お試しください。");
                return;
            }
            status.setText("保存しました（" + playCountValue + "回・" + secondsValue + "秒・" + percentValue + "%）。"
                    + "この基準はまだ動作に反映されません。");
        });
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(-2, -2);
        saveParams.gravity = Gravity.END;
        layout.addView(save, saveParams);
    }

    private EditText numberField(LinearLayout layout, String labelText, int value, int pad) {
        TextView label = new TextView(this);
        label.setText(labelText);
        label.setPadding(0, pad / 2, 0, 0);
        layout.addView(label);
        EditText field = new EditText(this);
        field.setId(View.generateViewId());
        // Digits only: no sign, decimal separator or other IME characters.
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        field.setSingleLine(true);
        field.setText(String.valueOf(value));
        field.setContentDescription(labelText);
        label.setLabelFor(field.getId());
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { setFieldError(field, null); }
        });
        layout.addView(field);
        return field;
    }

    /** Returns the parsed value, or -1 when empty, non-numeric, overflowing or out of range. */
    private static int parseInRange(EditText field, int max) {
        String text = field.getText() == null ? "" : field.getText().toString().trim();
        if (text.isEmpty()) return -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') return -1;
        }
        try {
            int value = Integer.parseInt(text);
            return value < 1 || value > max ? -1 : value;
        } catch (NumberFormatException overflow) {
            return -1;
        }
    }

    /** Shows or clears a per-field error without blocking the field for correction. */
    private static void setFieldError(EditText field, String message) {
        field.setError(message);
    }
}
