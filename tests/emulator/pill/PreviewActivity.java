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

        // Mirrors the target player row: horizontal, centered, full width. The pill is a
        // width-0/weight-1 spacer between the edge buttons and must keep that slot when hidden.
        LinearLayout controlRow = new LinearLayout(this);
        controlRow.setOrientation(LinearLayout.HORIZONTAL);
        controlRow.setGravity(Gravity.CENTER);
        Button controlLeft = new Button(this);
        controlLeft.setText("前");
        controlRow.addView(controlLeft, wrapParams());
        controlRow.addView(new View(this), new LinearLayout.LayoutParams(0, 0, 1f));
        Button controlRight = new Button(this);
        controlRight.setText("次");
        controlRow.addView(controlRight, wrapParams());
        page.addView(controlRow, matchParams());

        LinearLayout weightedRow = row();
        Button weightedLeft = edgeButton("前");
        weightedRow.addView(weightedLeft, wrapParams());
        Button weightedPill = new Button(this);
        weightedPill.setText("音声  /  動画");
        weightedRow.addView(weightedPill, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button weightedRight = edgeButton("次");
        weightedRow.addView(weightedRight, wrapParams());
        page.addView(weightedRow, matchParams());
        AudioVideoPill.apply(weightedPill);

        // A non-weighted pill keeps the legacy behaviour and is fully GONE when enabled.
        LinearLayout plainRow = row();
        plainRow.addView(edgeButton("前"), wrapParams());
        Button plainPill = new Button(this);
        plainPill.setText("音声  /  動画");
        plainRow.addView(plainPill, wrapParams());
        plainRow.addView(edgeButton("次"), wrapParams());
        page.addView(plainRow, matchParams());
        AudioVideoPill.apply(plainPill);

        Button settings = new Button(this);
        settings.setText("ym_morphe プレイヤー設定");
        settings.setOnClickListener(v -> startActivity(new Intent(this, GradientSettingsActivity.class)));
        page.addView(settings);

        // Detached layout is enough to resolve weighted widths, so the checks stay synchronous.
        int width = getResources().getDisplayMetrics().widthPixels;
        page.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        page.layout(0, 0, width, page.getMeasuredHeight());

        LinearLayout.LayoutParams weightedParams =
                (LinearLayout.LayoutParams) weightedPill.getLayoutParams();
        if (weightedParams.weight != 1f || weightedParams.width != 0) {
            throw new AssertionError("Weighted pill must keep its width-0/weight-1 params");
        }
        if (controlLeft.getLeft() != weightedLeft.getLeft()
                || controlRight.getLeft() != weightedRight.getLeft()) {
            throw new AssertionError("Disabled: weighted pill must keep the edge button layout");
        }
        if (expectedHidden) {
            if (weightedPill.getVisibility() != View.INVISIBLE
                    || weightedParams.height != 0
                    || weightedPill.getMeasuredWidth() <= 0
                    || weightedPill.getMeasuredHeight() != 0
                    || weightedPill.getImportantForAccessibility()
                    != View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS) {
                throw new AssertionError(
                        "Enabled: weighted pill must stay invisible in a width-0/weight-1 slot");
            }
            if (plainPill.getVisibility() != View.GONE
                    || plainPill.getImportantForAccessibility()
                    != View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS) {
                throw new AssertionError("Enabled: non-weighted pill must be GONE");
            }
        } else {
            if (weightedPill.getVisibility() != View.VISIBLE
                    || weightedParams.height != LinearLayout.LayoutParams.WRAP_CONTENT
                    || plainPill.getVisibility() != View.VISIBLE
                    || plainPill.getLayoutParams().width
                    != LinearLayout.LayoutParams.WRAP_CONTENT) {
                throw new AssertionError("Disabled: pill properties must stay intact");
            }
        }

        setContentView(page);
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        return row;
    }

    private Button edgeButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        return button;
    }

    private static LinearLayout.LayoutParams wrapParams() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private static LinearLayout.LayoutParams matchParams() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }
}
