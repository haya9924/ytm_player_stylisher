package app.morphe.extension.ym;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableContainer;
import android.graphics.drawable.DrawableWrapper;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.ImageView;

import java.lang.ref.WeakReference;

/** Blends the theme background with a small, blurred copy of the current artwork. */
public final class ArtworkGradientView extends View implements Runnable, ViewTreeObserver.OnPreDrawListener {
    private static final long REFRESH_MS = 500L;
    private static final long MISSING_GRACE_MS = 5000L;
    private static final long CACHE_MAX_AGE_MS = 60000L;
    private static final long CROSSFADE_MS = 300L;
    // Only a tiny blurred thumbnail is retained, never the original album image.
    private static Bitmap cachedBlur;
    private static long cachedAt;
    private static final int SIZE = 32;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF destination = new RectF();
    private final Rect visibleBounds = new Rect();
    private final Rect checkedBounds = new Rect();
    private final float[] crop = new float[4];
    private WeakReference<Bitmap> lastBitmap = new WeakReference<>(null);
    private long missingSince;
    private Bitmap blurred;
    private Bitmap previousBlur;
    private long transitionStarted;
    private int lastGeneration = -1;
    private int strength = -1;
    private int thumbnailId;
    private int miniPagerId;
    private int miniThumbnailId;
    private View owner;

    public ArtworkGradientView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        thumbnailId = context.getResources().getIdentifier("player_thumbnail", "id", context.getPackageName());
        miniPagerId = context.getResources().getIdentifier("mini_player_view_pager_container", "id", context.getPackageName());
        miniThumbnailId = context.getResources().getIdentifier("swipeable_mini_player_thumbnail", "id", context.getPackageName());
        setClickable(false);
        setFocusable(false);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        owner = findOwner();
        getViewTreeObserver().addOnPreDrawListener(this);
        if (blurred == null && cachedBlur != null && !cachedBlur.isRecycled()
                && SystemClock.elapsedRealtime() - cachedAt < CACHE_MAX_AGE_MS) {
            blurred = cachedBlur;
            missingSince = SystemClock.uptimeMillis();
        }
        requestRefresh();
    }

    @Override protected void onDetachedFromWindow() {
        removeCallbacks(this);
        if (getViewTreeObserver().isAlive()) getViewTreeObserver().removeOnPreDrawListener(this);
        visibleBounds.setEmpty();
        owner = null;
        previousBlur = null;
        lastBitmap.clear();
        lastGeneration = -1;
        missingSince = 0;
        super.onDetachedFromWindow();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE && isAttachedToWindow()) requestRefresh();
    }

    @Override public void onVisibilityAggregated(boolean visible) {
        super.onVisibilityAggregated(visible);
        if (visible && isAttachedToWindow()) requestRefresh();
    }

    private void requestRefresh() {
        removeCallbacks(this);
        post(this);
    }

    @Override public boolean onPreDraw() {
        // The player can move without resizing this view. Recompute only when the
        // visible portion changes, so the gradient follows the collapse animation.
        if (!getLocalVisibleRect(checkedBounds)) checkedBounds.setEmpty();
        if (!visibleBounds.equals(checkedBounds)) {
            visibleBounds.set(checkedBounds);
            invalidate();
        }
        return true;
    }

    @Override public void run() {
        try {
            int next = Math.max(0, Math.min(100, getContext()
                    .getSharedPreferences(GradientSettingsActivity.PREFS, Context.MODE_PRIVATE)
                    .getInt(GradientSettingsActivity.STRENGTH, GradientSettingsActivity.DEFAULT_STRENGTH)));
            if (strength != next) {
                strength = next;
                invalidate();
            }
            if (isShown() && getWindowVisibility() == VISIBLE && strength > 0) {
                if (owner == null) owner = findOwner();
                updateArtwork();
            }
        } catch (RuntimeException ignored) {
            // Image loaders may temporarily replace or recycle a drawable during resume.
            markArtworkUnavailable();
        } finally {
            if (isAttachedToWindow()) postDelayed(this, REFRESH_MS);
        }
    }

    private View findOwner() {
        ViewParent parent = getParent();
        while (parent instanceof View) {
            View view = (View) parent;
            if (view.getClass().getName().endsWith(".WatchWhileLayout")) return view;
            parent = view.getParent();
        }
        return null;
    }

    private void updateArtwork() {
        if (owner == null) { markArtworkUnavailable(); return; }
        Bitmap liveTile = ArtworkTheme.playbackTile();
        if (liveTile != null) {
            missingSince = 0;
            if (blurred != liveTile) showTile(liveTile);
            return;
        }
        View candidate = thumbnailId == 0 ? null : owner.findViewById(thumbnailId);
        Bitmap bitmap = candidate instanceof ImageView && candidate.isShown()
                && candidate.getGlobalVisibleRect(checkedBounds)
                ? findBitmap(((ImageView) candidate).getDrawable(), 0) : null;
        if (!usable(bitmap)) {
            // The swipeable miniplayer uses a different image. Restrict the fallback
            // to the pager and prefer the most visible item rather than a preloaded page.
            View pager = miniPagerId == 0 ? null : owner.findViewById(miniPagerId);
            MiniCandidate best = new MiniCandidate();
            if (miniThumbnailId != 0 && pager != null) findVisibleMini(pager, best);
            bitmap = best.bitmap;
        }
        if (!usable(bitmap)) { markArtworkUnavailable(); return; }
        int generation = bitmap.getGenerationId();
        if (bitmap == lastBitmap.get() && generation == lastGeneration && blurred != null) {
            missingSince = 0;
            return;
        }
        Bitmap sample = bitmap;
        boolean copied = false;
        try {
            if (Build.VERSION.SDK_INT >= 26 && bitmap.getConfig() == Bitmap.Config.HARDWARE) {
                if ((long) bitmap.getWidth() * bitmap.getHeight() > 4_000_000L) { markArtworkUnavailable(); return; }
                sample = bitmap.copy(Bitmap.Config.ARGB_8888, false);
                if (sample == null) { markArtworkUnavailable(); return; }
                copied = true;
            }
            Bitmap next = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(next);
            canvas.drawColor(themeColor());
            canvas.drawBitmap(sample, null, new Rect(0, 0, SIZE, SIZE), paint);
            int[] pixels = new int[SIZE * SIZE];
            next.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE);
            int[] soft = ArtworkBlur.blur(pixels, SIZE, 4, 3);
            // Guard the pixels before filtering, cropping or crossfading so every frame is
            // safe for the native white and translucent-white text and controls.
            for (int i = 0; i < soft.length; i++) soft[i] = ArtworkPalette.protectPlayerPixel(soft[i]);
            next.setPixels(soft, 0, SIZE, 0, 0, SIZE, SIZE);
            showTile(next);
            lastBitmap = new WeakReference<>(bitmap);
            lastGeneration = generation;
            missingSince = 0;
        } finally {
            if (copied) sample.recycle();
        }
    }

    private void showTile(Bitmap next) {
        previousBlur = blurred;
        transitionStarted = SystemClock.uptimeMillis();
        blurred = next;
        cachedBlur = next;
        cachedAt = SystemClock.elapsedRealtime();
        invalidate();
    }

    private static boolean usable(Bitmap bitmap) {
        return bitmap != null && !bitmap.isRecycled() && bitmap.getWidth() >= 2 && bitmap.getHeight() >= 2;
    }

    private static final class MiniCandidate {
        Bitmap bitmap;
        long area;
    }

    private void findVisibleMini(View view, MiniCandidate best) {
        if (view.getVisibility() != VISIBLE) return;
        if (view.getId() == miniThumbnailId && view instanceof ImageView) {
            Rect visible = new Rect();
            if (view.getGlobalVisibleRect(visible)) {
                long area = (long) visible.width() * visible.height();
                Bitmap bitmap = findBitmap(((ImageView) view).getDrawable(), 0);
                if (area > best.area && usable(bitmap)) {
                    best.area = area;
                    best.bitmap = bitmap;
                }
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) findVisibleMini(group.getChildAt(i), best);
        }
    }

    private static Bitmap findBitmap(Drawable drawable, int depth) {
        if (drawable == null || depth > 6) return null;
        if (drawable instanceof BitmapDrawable) return ((BitmapDrawable) drawable).getBitmap();
        if (drawable instanceof LayerDrawable) {
            LayerDrawable layers = (LayerDrawable) drawable;
            for (int i = layers.getNumberOfLayers() - 1; i >= 0; i--) {
                Bitmap bitmap = findBitmap(layers.getDrawable(i), depth + 1);
                if (bitmap != null && !bitmap.isRecycled()) return bitmap;
            }
        }
        if (drawable instanceof DrawableWrapper) {
            return findBitmap(((DrawableWrapper) drawable).getDrawable(), depth + 1);
        }
        if (drawable instanceof DrawableContainer) {
            Drawable current = drawable.getCurrent();
            if (current != drawable) return findBitmap(current, depth + 1);
        }
        return null;
    }

    private void markArtworkUnavailable() {
        if (missingSince == 0) missingSince = SystemClock.uptimeMillis();
        if (SystemClock.uptimeMillis() - missingSince >= MISSING_GRACE_MS) clearArtwork();
    }

    private int themeColor() {
        TypedValue value = new TypedValue();
        if (getContext().getTheme().resolveAttribute(android.R.attr.colorBackground, value, true)) {
            if (value.resourceId != 0) {
                try { return getResources().getColor(value.resourceId, getContext().getTheme()); }
                catch (RuntimeException ignored) { /* use fallback */ }
            } else if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT && value.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                return value.data;
            }
        }
        return Color.BLACK;
    }

    private void clearArtwork() {
        lastBitmap.clear();
        lastGeneration = -1;
        missingSince = 0;
        if (blurred != null) {
            if (cachedBlur == blurred) { cachedBlur = null; cachedAt = 0; }
            blurred = null;
            previousBlur = null;
            invalidate();
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (strength == 0) {
            canvas.drawColor(ArtworkPalette.protectPlayerPixel(themeColor()));
            return;
        }
        // Pending/unsupported artwork must not mask the native player's own guarded gradient.
        if (blurred == null || strength < 0 || getWidth() == 0 || getHeight() == 0) return;
        canvas.drawColor(ArtworkPalette.protectPlayerPixel(themeColor()));
        Rect bounds = visibleBounds.isEmpty() ? new Rect(0, 0, getWidth(), getHeight()) : visibleBounds;
        if (!ArtworkCrop.centerCover(bounds.left, bounds.top, bounds.right, bounds.bottom, SIZE, crop)) return;
        destination.set(crop[0], crop[1], crop[2], crop[3]);
        long elapsed = SystemClock.uptimeMillis() - transitionStarted;
        int opacity = Math.round(255 * strength / 100f);
        if (previousBlur != null && elapsed < CROSSFADE_MS) {
            // Blend the two opaque images first, then apply the user's opacity once.
            int save = canvas.saveLayerAlpha(0, 0, getWidth(), getHeight(), opacity);
            paint.setAlpha(255);
            canvas.drawBitmap(previousBlur, null, destination, paint);
            paint.setAlpha(Math.round(255f * Math.max(0, elapsed) / CROSSFADE_MS));
            canvas.drawBitmap(blurred, null, destination, paint);
            canvas.restoreToCount(save);
            postInvalidateOnAnimation();
        } else {
            previousBlur = null;
            paint.setAlpha(opacity);
            canvas.drawBitmap(blurred, null, destination, paint);
        }
        paint.setAlpha(255);
    }
}
