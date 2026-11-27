package app.morphe.extension.ym;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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

import java.io.File;

/** A small standalone settings screen; does not depend on Morphe's shared extension. */
public final class GradientSettingsActivity extends Activity {
    static final String PREFS = "ym_morphe_settings";
    static final String STRENGTH = "artwork_color_strength";
    static final int DEFAULT_STRENGTH = 100;

    /** Budget presets: value in MiB; 0 means the standard host budget. */
    private static final int[] PRESET_MIB = {0, 128, 256, 512, 1024, 2048};
    private static final String[] PRESET_LABELS = {"標準", "128MB", "256MB", "512MB", "1024MB", "2048MB"};
    private static final String INCOMPLETE_PREVIEW =
            "有効な数値を入力すると推測削減量を再計算します（キャッシュ上限: 0 または 16〜2048 MB）";
    private static final int PRESET_SELECTED_COLOR = 0xFF8CC8FF;

    private Switch retentionSwitch;
    private EditText budgetField;
    private EditText playCountField;
    private EditText secondsField;
    private EditText percentField;
    private TextView previewBody;
    private TextView actualStatsBody;
    private Button[] presetButtons;
    private int[] presetDefaultColors;
    private boolean previewReady;
    /** Live telemetry; written on the UI thread only, after a background read posts back. */
    private CacheSavingsEstimator.Snapshot latestSnapshot;

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
     * Cache retention and cache-limit section with a live estimated data-savings preview.
     *
     * <p>Every control refreshes the estimate immediately; the real device telemetry is loaded on a
     * daemon thread so the UI thread never touches disk I/O or the host cache lock.
     */
    private void addCacheRetentionSection(LinearLayout layout, int pad) {
        final SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        // Keep the in-memory policy in sync even when the recording bridge was never initialized
        // (for example when the playback-recording patch option is disabled).
        NativeCacheRetention.applyPreferences(
                CacheRetentionPreferences.readRetentionEnabled(prefs),
                CacheRetentionPreferences.readBudgetMiB(prefs));

        TextView heading = new TextView(this);
        heading.setText("音声キャッシュの優先保持と上限設定");
        heading.setTextSize(18);
        heading.setPadding(0, pad, 0, 0);
        layout.addView(heading);

        retentionSwitch = new Switch(this);
        retentionSwitch.setText("よく聴く曲の音声キャッシュを優先保持");
        retentionSwitch.setPadding(0, pad / 2, 0, 0);
        retentionSwitch.setChecked(CacheRetentionPreferences.readRetentionEnabled(prefs));
        retentionSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (previewReady) updatePreview();
        });
        layout.addView(retentionSwitch);

        TextView description = new TextView(this);
        description.setText("一定以上聴いた曲の音声キャッシュを、容量不足時の自動削除から優先的に守ります"
                + "（完全なオフライン保存ではなく、有効期限切れや端末側のキャッシュ掃除で消える場合があります）。");
        layout.addView(description);

        addCacheBudgetControls(layout, pad);
        addRetentionCriteriaControls(layout, pad, prefs);
        addPreviewCard(layout, pad);

        TextView status = new TextView(this);
        status.setPadding(0, pad / 2, 0, 0);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        layout.addView(status);

        Button save = new Button(this);
        save.setText("キャッシュ設定を保存");
        save.setOnClickListener(v -> saveCacheSettings(prefs, status));
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(-2, -2);
        saveParams.gravity = Gravity.END;
        layout.addView(save, saveParams);

        // A fast zero/default snapshot makes the preview usable immediately; the real telemetry
        // arrives a moment later and updates the same panel without flicker.
        latestSnapshot = new CacheSavingsEstimator.Snapshot(0L, 0L, 0, 0L, null, 0, 0L);
        previewReady = true;
        updatePreview();
        refreshTelemetryAsync();
    }

    /** Cache limit field plus six quick-select presets laid out as two rows of three. */
    private void addCacheBudgetControls(LinearLayout layout, int pad) {
        budgetField = numberField(layout, "キャッシュ上限（0＝標準／アプリ任せ、"
                        + CacheRetentionPreferences.MIN_CACHE_BUDGET_MIB + "〜"
                        + CacheRetentionPreferences.MAX_CACHE_BUDGET_MIB + " MB）",
                CacheRetentionPreferences.readBudgetMiB(
                        getSharedPreferences(PREFS, MODE_PRIVATE)), pad);

        presetButtons = new Button[PRESET_MIB.length];
        presetDefaultColors = new int[PRESET_MIB.length];
        LinearLayout row = null;
        for (int i = 0; i < PRESET_MIB.length; i++) {
            // Two rows of three keep every label readable on a 1080px phone.
            if (i % 3 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(0, pad / 4, 0, 0);
                layout.addView(row);
            }
            final int budget = PRESET_MIB[i];
            Button preset = new Button(this);
            preset.setText(PRESET_LABELS[i]);
            preset.setSingleLine(true);
            preset.setTextSize(13);
            preset.setMinWidth(0);
            preset.setMinimumWidth(0);
            preset.setPadding(0, 0, 0, 0);
            preset.setContentDescription(PRESET_LABELS[i] + "に設定");
            preset.setOnClickListener(v -> {
                budgetField.setText(String.valueOf(budget));
                setFieldError(budgetField, null);
                if (previewReady) updatePreview();
            });
            row.addView(preset, new LinearLayout.LayoutParams(0, -2, 1f));
            presetButtons[i] = preset;
            presetDefaultColors[i] = preset.getCurrentTextColor();
        }
    }

    /** The three retention criteria fields, initialized from the saved preferences. */
    private void addRetentionCriteriaControls(LinearLayout layout, int pad, SharedPreferences prefs) {
        TextView heading = new TextView(this);
        heading.setText("優先保持の基準");
        heading.setTextSize(16);
        heading.setPadding(0, pad, 0, 0);
        layout.addView(heading);

        CacheRetentionCriteria saved = CacheRetentionPreferences.read(prefs);
        playCountField = numberField(layout,
                "保持に必要な再生回数（1〜" + CacheRetentionCriteria.MAX_PLAY_COUNT + "回）",
                saved.playCount, pad);
        secondsField = numberField(layout,
                "最低再生時間（1〜" + CacheRetentionCriteria.MAX_SECONDS + "秒）",
                saved.seconds, pad);
        percentField = numberField(layout,
                "曲の長さに対する割合（1〜" + CacheRetentionCriteria.MAX_PERCENT + "%）",
                saved.percent, pad);

        TextView note = new TextView(this);
        note.setText("1回と数えるのに必要な時間は、設定した秒数と、曲の長さに設定割合を掛けた時間のうち短い方です。"
                + "曲の長さが不明な場合は設定した秒数を使います。");
        layout.addView(note);
    }

    /** Rounded, subtly tinted card that hosts both the estimate and the real device statistics. */
    private void addPreviewCard(LinearLayout layout, int pad) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(pad * 2 / 3, pad * 2 / 3, pad * 2 / 3, pad * 2 / 3);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0x22FFFFFF);
        background.setCornerRadius(12 * getResources().getDisplayMetrics().density);
        background.setStroke((int) getResources().getDisplayMetrics().density, 0x44FFFFFF);
        card.setBackground(background);
        LinearLayout.LayoutParams cardParams =
                new LinearLayout.LayoutParams(-1, -2);
        cardParams.topMargin = pad / 2;
        layout.addView(card, cardParams);

        TextView cardTitle = new TextView(this);
        cardTitle.setText("推測データ削減量プレビュー（保存前に確認できます）");
        cardTitle.setTextSize(15);
        cardTitle.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(cardTitle);

        previewBody = new TextView(this);
        previewBody.setPadding(0, pad / 3, 0, 0);
        card.addView(previewBody);

        actualStatsBody = new TextView(this);
        actualStatsBody.setPadding(0, pad / 2, 0, 0);
        actualStatsBody.setTextColor(0xFFB0BEC5);
        card.addView(actualStatsBody);
    }

    /** Validates every field, persists all five values, and applies them in memory immediately. */
    private void saveCacheSettings(SharedPreferences prefs, TextView status) {
        int budgetValue = parseBudgetMiB(budgetField);
        int playCountValue = parseInRange(playCountField, CacheRetentionCriteria.MAX_PLAY_COUNT);
        int secondsValue = parseInRange(secondsField, CacheRetentionCriteria.MAX_SECONDS);
        int percentValue = parseInRange(percentField, CacheRetentionCriteria.MAX_PERCENT);
        setFieldError(budgetField, budgetValue >= 0 ? null
                : "0（標準）または " + CacheRetentionPreferences.MIN_CACHE_BUDGET_MIB + "〜"
                + CacheRetentionPreferences.MAX_CACHE_BUDGET_MIB + " MBの整数で入力してください");
        setFieldError(playCountField, playCountValue >= 1 ? null
                : "1〜" + CacheRetentionCriteria.MAX_PLAY_COUNT + "回の整数で入力してください");
        setFieldError(secondsField, secondsValue >= 1 ? null
                : "1〜" + CacheRetentionCriteria.MAX_SECONDS + "秒の整数で入力してください");
        setFieldError(percentField, percentValue >= 1 ? null
                : "1〜" + CacheRetentionCriteria.MAX_PERCENT + "%の整数で入力してください");
        if (budgetValue < 0 || playCountValue < 1 || secondsValue < 1 || percentValue < 1) {
            // Invalid input saves nothing: stored values and every field stay as they are.
            status.setText("入力内容を確認してください。保存は行っていません。");
            return;
        }
        boolean retentionEnabled = retentionSwitch.isChecked();
        if (!CacheRetentionPreferences.saveAll(prefs, retentionEnabled, budgetValue,
                playCountValue, secondsValue, percentValue)) {
            status.setText("保存できませんでした。もう一度お試しください。");
            return;
        }
        // Apply in memory right away; the stored preferences stay the source of truth.
        NativeCacheRetention.applyPreferences(retentionEnabled, budgetValue);
        updatePreview();
        refreshTelemetryAsync();
        status.setText("保存して適用しました（優先保持: " + (retentionEnabled ? "オン" : "オフ")
                + " / 上限: " + (budgetValue == 0 ? "標準" : budgetValue + " MB")
                + " / 基準: " + playCountValue + "回・" + secondsValue + "秒・" + percentValue + "%）。");
    }

    /** Recomputes the estimate from the current inputs; never touches disk or the host cache. */
    private void updatePreview() {
        if (previewBody == null || latestSnapshot == null) return;
        int budgetValue = parseBudgetMiB(budgetField);
        int playCountValue = parseInRange(playCountField, CacheRetentionCriteria.MAX_PLAY_COUNT);
        int secondsValue = parseInRange(secondsField, CacheRetentionCriteria.MAX_SECONDS);
        int percentValue = parseInRange(percentField, CacheRetentionCriteria.MAX_PERCENT);
        highlightPreset(budgetValue);
        updateActualStats(latestSnapshot, budgetValue);
        if (budgetValue < 0 || playCountValue < 1 || secondsValue < 1 || percentValue < 1) {
            previewBody.setText(INCOMPLETE_PREVIEW);
            return;
        }
        CacheSavingsEstimator.Estimate estimate;
        try {
            estimate = CacheSavingsEstimator.estimate(latestSnapshot, retentionSwitch.isChecked(),
                    budgetValue, new CacheRetentionCriteria(playCountValue, secondsValue, percentValue));
        } catch (RuntimeException | LinkageError unavailable) {
            previewBody.setText("推測値を計算できませんでした。入力内容を確認してください。");
            return;
        }
        StringBuilder text = new StringBuilder();
        text.append("推測データ削減量（月間目安）: ").append(formatMiB(estimate.monthlyTotalSavedBytes))
                .append(" / 月");
        long delta = estimate.monthlyDeltaVsStandardBytes;
        text.append("\n標準 ").append(formatMiB(estimate.standardBudgetBytes))
                .append("・保持なし比: ").append(delta >= 0 ? "+" : "-")
                .append(formatMiB(Math.abs(delta)));
        text.append("\nうち優先保持による節約: ");
        if (!retentionSwitch.isChecked()) {
            text.append("オフ");
        } else {
            text.append(formatMiB(estimate.monthlyRetentionBonusBytes)).append(" / 月");
        }
        text.append("\n\n実効上限と収容目安: ").append(formatMiB(estimate.effectiveBudgetBytes))
                .append("（約 ").append(estimate.capacityTracks).append(" 曲分）");
        text.append("\n保護枠見込み: 約 ").append(estimate.estimatedProtectedTracks)
                .append(" 曲（最大").append(CacheSavingsEstimator.MAX_PROTECTED_TRACKS)
                .append("曲・7日間）");
        text.append("\n1回のカウント基準（3分30秒の曲）: ")
                .append(estimate.effectiveListenSeconds).append(" 秒以上の再生");
        text.append("\n\n試算根拠: ");
        if (estimate.usedObservedHistory) {
            text.append("実測ベース（記録 ").append(estimate.recordedTrackCount)
                    .append("曲中 ").append(estimate.recordedQualifyingCount)
                    .append("曲が基準一致 / 1曲平均 ").append(formatMiB(estimate.avgTrackBytes))
                    .append("）");
        } else {
            text.append("標準モデルで試算（記録済み ").append(estimate.recordedTrackCount)
                    .append("曲・さらに再生すると実測値で精密化）");
        }
        if (estimate.lowStorageWarning) {
            text.append("\n\n⚠ 指定上限が端末の空き容量（約 ")
                    .append(formatMiB(latestSnapshot.usableStorageBytes))
                    .append("）の半分を超えています。");
        }
        if (estimate.budgetTightForProtectedWarning) {
            text.append("\n\n⚠ 保護対象曲に対して上限が小さめのため、優先曲同士で押し出しが起きやすくなります。");
        }
        text.append("\n\n※ 推測値です。実際の通信削減量は聴く曲の重複度・有効期限・OS のキャッシュ掃除で変動し、動画再生は対象外です。");
        previewBody.setText(text.toString());
    }

    /** Real device state, kept separate from the modelled estimate. */
    private void updateActualStats(CacheSavingsEstimator.Snapshot snapshot, int budgetValue) {
        if (actualStatsBody == null) return;
        StringBuilder text = new StringBuilder();
        long nativeBudget = snapshot.nativeBudgetBytes > 0L
                ? snapshot.nativeBudgetBytes : CacheSavingsEstimator.DEFAULT_STANDARD_BUDGET_BYTES;
        text.append("現在キャッシュ: ")
                .append(snapshot.currentUsedBytes > 0L ? formatMiB(snapshot.currentUsedBytes) : "未計測")
                .append(" / 上限 ").append(formatMiB(nativeBudget));
        if (snapshot.cachedAudioTracks > 0) {
            text.append("（音声 ").append(snapshot.cachedAudioTracks).append("曲・")
                    .append(formatMiB(snapshot.cachedAudioBytes)).append("）");
        }
        if (budgetValue > 0) {
            text.append("\n設定上限: ").append(formatMiB(budgetValue * 1024L * 1024L));
        }
        if (snapshot.sessionSparedCount > 0) {
            text.append("\n今回の起動で優先保護した回数: ")
                    .append(snapshot.sessionSparedCount).append(" 回");
        }
        actualStatsBody.setText(text.toString());
    }

    /**
     * Loads the real telemetry off the UI thread. Disk reads and the host cache lock never run on
     * the main thread; the result is posted back only while the activity is still alive.
     */
    private void refreshTelemetryAsync() {
        final Context appCtx = getApplicationContext();
        Thread worker = new Thread(() -> {
            CacheSavingsEstimator.Snapshot snapshot;
            try {
                NativeCacheRetention.CacheTelemetry telemetry =
                        NativeCacheRetention.captureCacheTelemetry();
                int[] playCounts = ScopedPlaybackRecordingBridge.readRecordedPlayCounts(
                        appCtx, NativeCacheRetention.activeAccountKey());
                long usableBytes = safeUsableBytes(appCtx);
                snapshot = new CacheSavingsEstimator.Snapshot(
                        telemetry.nativeBudgetBytes, telemetry.usedBytes, telemetry.audioTracks,
                        telemetry.audioBytes, playCounts, telemetry.sessionSparedCount, usableBytes);
            } catch (RuntimeException | LinkageError unavailable) {
                // A failed read keeps the previous snapshot; never crash the worker.
                return;
            }
            final CacheSavingsEstimator.Snapshot loaded = snapshot;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                latestSnapshot = loaded;
                if (previewReady) updatePreview();
            });
        }, "ym-cache-ui-stats");
        worker.setDaemon(true);
        worker.start();
    }

    /** Free space from the cache volume, or {@code 0} when it cannot be determined. */
    private static long safeUsableBytes(Context context) {
        if (context == null) return 0L;
        try {
            File external = context.getExternalCacheDir();
            if (external != null) {
                long usable = external.getUsableSpace();
                if (usable > 0L) return usable;
            }
            File internal = context.getCacheDir();
            if (internal != null) {
                long usable = internal.getUsableSpace();
                if (usable > 0L) return usable;
            }
        } catch (RuntimeException unavailable) {
            // Storage is unknown; the estimator treats 0 as "no warning".
        }
        return 0L;
    }

    /** Highlights the preset matching the current budget, or clears every highlight when none. */
    private void highlightPreset(int budgetValue) {
        if (presetButtons == null) return;
        for (int i = 0; i < presetButtons.length; i++) {
            boolean selected = PRESET_MIB[i] == budgetValue;
            Button preset = presetButtons[i];
            preset.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            preset.setTextColor(selected ? PRESET_SELECTED_COLOR : presetDefaultColors[i]);
        }
    }

    /** Formats bytes as whole/one-decimal MiB, keeping sub-MiB values readable. */
    static String formatMiB(long bytes) {
        if (bytes <= 0L) return "0 MB";
        double mib = bytes / (1024.0 * 1024.0);
        if (mib >= 100.0) return Math.round(mib) + " MB";
        if (mib >= 10.0) return String.format(java.util.Locale.ROOT, "%.0f MB", mib);
        return String.format(java.util.Locale.ROOT, "%.1f MB", mib);
    }

    /** Returns {@code 0} (standard) or a value in {@code [16, 2048]}; {@code -1} when invalid. */
    static int parseBudgetMiB(EditText field) {
        if (field == null) return -1;
        String text = field.getText() == null ? "" : field.getText().toString().trim();
        if (text.isEmpty()) return -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') return -1;
        }
        try {
            int value = Integer.parseInt(text);
            return CacheRetentionPreferences.isValidBudgetMiB(value) ? value : -1;
        } catch (NumberFormatException overflow) {
            return -1;
        }
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
            @Override public void afterTextChanged(Editable s) {
                // Every keystroke clears the field's own error and refreshes the live preview.
                setFieldError(field, null);
                if (previewReady) updatePreview();
            }
        });
        layout.addView(field);
        return field;
    }

    /** Returns the parsed value, or -1 when empty, non-numeric, overflowing or out of range. */
    private static int parseInRange(EditText field, int max) {
        if (field == null) return -1;
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
