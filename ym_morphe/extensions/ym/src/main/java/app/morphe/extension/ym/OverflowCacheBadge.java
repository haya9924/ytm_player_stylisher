package app.morphe.extension.ym;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewTreeObserver;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Draws a small, non-interactive capacity-retention badge over the player overflow anchor.
 *
 * <p>The badge is an overlay drawable, so it never changes the anchor's own drawable,
 * visibility, click listener, accessibility properties or the parent layout. It only
 * becomes visible while the native cache retention signal confirms the current track.
 */
final class OverflowCacheBadge implements Runnable, View.OnLayoutChangeListener,
        View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
    private static final long POLL_MS = 750L;
    private static final Map<View, WeakReference<OverflowCacheBadge>> badges = new WeakHashMap<>();

    private final View anchor;
    private final BadgeDrawable drawable = new BadgeDrawable();
    private boolean listening;
    private boolean active;
    private boolean scheduled;
    private boolean overlayAdded;
    private ViewTreeObserver observer;

    private OverflowCacheBadge(View anchor) {
        this.anchor = anchor;
    }

    /** Attaches at most one badge to an anchor; repeated binds reuse the live badge. */
    static void attach(View anchor) {
        if (anchor == null) return;
        try {
            OverflowCacheBadge existing;
            synchronized (badges) {
                WeakReference<OverflowCacheBadge> reference = badges.get(anchor);
                existing = reference == null ? null : reference.get();
            }
            if (existing != null) {
                existing.start();
                return;
            }
            OverflowCacheBadge badge = new OverflowCacheBadge(anchor);
            synchronized (badges) {
                badges.put(anchor, new WeakReference<>(badge));
            }
            badge.start();
        } catch (RuntimeException ignored) {
            // An overlay is optional; the original listener and the row stay untouched.
        }
    }

    private void start() {
        if (!listening) {
            listening = true;
            anchor.addOnAttachStateChangeListener(this);
        }
        if (anchor.isAttachedToWindow()) activate();
    }

    private void activate() {
        if (active) return;
        active = true;
        anchor.addOnLayoutChangeListener(this);
        observer = anchor.getViewTreeObserver();
        if (observer.isAlive()) observer.addOnPreDrawListener(this);
        updateBounds();
        refreshEvidence();
        if (shown()) schedule();
    }

    /** Removes all callbacks, listeners registered here and the overlay drawable. */
    private void deactivate() {
        scheduled = false;
        anchor.removeCallbacks(this);
        if (active) {
            anchor.removeOnLayoutChangeListener(this);
            if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(this);
            observer = null;
            active = false;
        }
        hideBadge();
    }

    private boolean shown() {
        return anchor.isAttachedToWindow() && anchor.isShown()
                && anchor.getWindowVisibility() == View.VISIBLE
                && anchor.getWidth() > 0 && anchor.getHeight() > 0;
    }

    private void schedule() {
        if (scheduled || !shown() || (!overlayAdded && !NativeCacheRetention.badgeMayBeActive())) return;
        scheduled = anchor.postDelayed(this, POLL_MS);
    }

    @Override public void run() {
        scheduled = false;
        if (!anchor.isAttachedToWindow()) {
            deactivate();
            return;
        }
        try {
            updateBounds();
            refreshEvidence();
        } catch (RuntimeException ignored) {
            hideBadge();
        }
        if (shown()) schedule();
        else hideBadge();
    }

    @Override public void onViewAttachedToWindow(View view) {
        activate();
    }

    @Override public void onViewDetachedFromWindow(View view) {
        deactivate();
    }

    @Override public void onLayoutChange(View view, int left, int top, int right, int bottom,
            int oldLeft, int oldTop, int oldRight, int oldBottom) {
        try {
            updateBounds();
        } catch (RuntimeException ignored) {
            hideBadge();
        }
        if (shown()) schedule();
    }

    @Override public boolean onPreDraw() {
        // Covers layout-direction changes and resumes polling after the player window reappears.
        if (anchor.isAttachedToWindow()) {
            try {
                updateBounds();
            } catch (RuntimeException ignored) {
                hideBadge();
            }
            if (shown()) schedule();
        }
        return true;
    }

    private void refreshEvidence() {
        if (!shown() || !currentTrackSpared()) {
            hideBadge();
            return;
        }
        if (!overlayAdded) {
            try {
                anchor.getOverlay().add(drawable);
                overlayAdded = true;
            } catch (RuntimeException ignored) {
                overlayAdded = false;
            }
        }
    }

    private static boolean currentTrackSpared() {
        try {
            return NativeCacheRetention.currentTrackSpared();
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private void updateBounds() {
        int width = anchor.getWidth();
        int height = anchor.getHeight();
        int margin = dp(1);
        int badgeWidth = dp(12);
        int badgeHeight = dp(9);
        if (width <= 0 || height <= 0) {
            hideBadge();
            return;
        }
        badgeWidth = Math.min(badgeWidth, Math.max(0, width - 2 * margin));
        badgeHeight = Math.min(badgeHeight, Math.max(0, height - 2 * margin));
        if (badgeWidth <= 0 || badgeHeight <= 0) {
            hideBadge();
            return;
        }
        boolean rtl = anchor.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        int left = rtl ? margin : width - margin - badgeWidth;
        int top = margin;
        Rect current = drawable.getBounds();
        if (current.left == left && current.top == top
                && current.right == left + badgeWidth && current.bottom == top + badgeHeight) return;
        drawable.setBounds(left, top, left + badgeWidth, top + badgeHeight);
        drawable.invalidateSelf();
    }

    private int dp(int value) {
        return Math.round(value * anchor.getResources().getDisplayMetrics().density);
    }

    private void hideBadge() {
        if (!overlayAdded) return;
        overlayAdded = false;
        try {
            anchor.getOverlay().remove(drawable);
        } catch (RuntimeException ignored) {
            // The overlay may already be detached; the drawable reference is dropped locally.
        }
    }

    /** A white rounded badge with a dark downward arrow, readable on either player theme. */
    private static final class BadgeDrawable extends Drawable {
        private final Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path arrow = new Path();

        BadgeDrawable() {
            background.setStyle(Paint.Style.FILL);
            background.setColor(Color.WHITE);
            border.setStyle(Paint.Style.STROKE);
            border.setColor(0x99000000);
            arrowPaint.setStyle(Paint.Style.FILL);
            arrowPaint.setColor(Color.BLACK);
        }

        @Override public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            int width = bounds.width();
            int height = bounds.height();
            if (width <= 0 || height <= 0) return;
            float radius = Math.max(1f, Math.min(width, height) * 0.25f);
            border.setStrokeWidth(Math.max(1f, Math.min(width, height) * 0.08f));
            canvas.drawRoundRect(bounds.left, bounds.top, bounds.right, bounds.bottom,
                    radius, radius, background);
            float inset = border.getStrokeWidth() / 2f;
            canvas.drawRoundRect(bounds.left + inset, bounds.top + inset,
                    bounds.right - inset, bounds.bottom - inset, radius, radius, border);

            float left = bounds.left + width * 0.30f;
            float right = bounds.left + width * 0.70f;
            float top = bounds.top + height * 0.34f;
            float bottom = bounds.top + height * 0.68f;
            float center = bounds.left + width * 0.50f;
            arrow.reset();
            arrow.moveTo(left, top);
            arrow.lineTo(right, top);
            arrow.lineTo(center, bottom);
            arrow.close();
            canvas.drawPath(arrow, arrowPaint);
        }

        @Override public void setAlpha(int alpha) {
            background.setAlpha(alpha);
            border.setAlpha(alpha);
            arrowPaint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override public void setColorFilter(ColorFilter colorFilter) {
            background.setColorFilter(colorFilter);
            border.setColorFilter(colorFilter);
            arrowPaint.setColorFilter(colorFilter);
            invalidateSelf();
        }

        @Override public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
