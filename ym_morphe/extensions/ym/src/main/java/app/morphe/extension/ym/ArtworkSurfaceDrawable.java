package app.morphe.extension.ym;

import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

/** Wraps the stock background; XML android:level is the palette role, not the drawable level. */
public final class ArtworkSurfaceDrawable extends DrawableWrapper {
    static final int CANVAS = 0;
    static final int CHROME = 1;
    static final int RAISED = 2;
    private static final int[] ATTRS = { android.R.attr.drawable, android.R.attr.level };
    private final Paint paint = new Paint();
    private final RectF layerBounds = new RectF();
    private int tintColor;
    private int solidTint;
    private ColorStateList stockFill;
    private int stockAttribute;
    private static final int[] STOCK_FALLBACK = { android.R.attr.color };
    private int role;
    private boolean standIn;
    private int alpha = 255;

    public ArtworkSurfaceDrawable() {
        super(null);
    }

    ArtworkSurfaceDrawable(Drawable stock, int role) {
        this();
        this.role = role;
        installStock(stock);
    }

    private void installStock(Drawable stock) {
        standIn = stock instanceof ColorDrawable && ((ColorDrawable) stock).getColor() >>> 24 == 0;
        stockFill = stock instanceof GradientDrawable && ((GradientDrawable) stock).getColors() == null
                ? ((GradientDrawable) stock).getColor() : null;
        setDrawable(stock.mutate());
    }

    @Override public void inflate(Resources resources, XmlPullParser parser, AttributeSet attrs, Resources.Theme theme)
            throws XmlPullParserException {
        TypedArray array = theme == null ? resources.obtainAttributes(attrs, ATTRS)
                : theme.obtainStyledAttributes(attrs, ATTRS, 0, 0);
        try {
            role = array.getInt(1, CANVAS);
            TypedValue value = array.peekValue(0);
            if (value != null && value.type == TypedValue.TYPE_ATTRIBUTE) {
                // ResourcesImpl inflates XML without a theme, then applies the context theme.
                stockAttribute = value.data;
                TypedArray fallback = resources.obtainAttributes(attrs, STOCK_FALLBACK);
                try {
                    installStock(new ColorDrawable(fallback.getColor(0, 0xff000000)));
                } finally {
                    fallback.recycle();
                }
            } else {
                Drawable stock = array.getDrawable(0);
                if (stock == null) throw new XmlPullParserException("Missing stock artwork background", parser, null);
                installStock(stock);
            }
        } finally {
            array.recycle();
        }
    }

    @Override public boolean canApplyTheme() {
        return stockAttribute != 0 || (getDrawable() != null && getDrawable().canApplyTheme());
    }

    @Override public void applyTheme(Resources.Theme theme) {
        if (stockAttribute != 0) {
            TypedArray resolved = theme.obtainStyledAttributes(new int[] { stockAttribute });
            try {
                TypedValue value = resolved.peekValue(0);
                if (value != null && value.type != TypedValue.TYPE_ATTRIBUTE) {
                    Drawable stock = resolved.getDrawable(0);
                    if (stock != null) installStock(stock);
                }
            } finally {
                resolved.recycle();
            }
        }
        if (getDrawable() != null && getDrawable().canApplyTheme()) getDrawable().applyTheme(theme);
        solidTint = 0;
        if (getDrawable() instanceof GradientDrawable) {
            GradientDrawable shape = (GradientDrawable) getDrawable();
            stockFill = shape.getColors() == null ? shape.getColor() : null;
        }
    }

    @Override public void setAlpha(int alpha) {
        if (this.alpha == alpha) return;
        this.alpha = alpha;
        super.setAlpha(alpha);
        if (standIn) invalidateSelf();
    }

    @Override public int getAlpha() {
        return alpha;
    }

    @Override public int getOpacity() {
        return standIn ? PixelFormat.TRANSLUCENT : super.getOpacity();
    }

    @Override public void draw(Canvas canvas) {
        int color = ArtworkTheme.color(this, role);
        Drawable stock = getDrawable();
        if (stockFill != null) {
            // Setting just the solid fill keeps the native popup border and stateful outlines.
            if (solidTint != color) {
                solidTint = color;
                ((GradientDrawable) stock).setColor(color == 0 ? stockFill : ColorStateList.valueOf(color));
            }
            super.draw(canvas);
            return;
        }
        if (color == 0 || stock == null) {
            super.draw(canvas);
            return;
        }
        Rect bounds = getBounds();
        if (bounds.isEmpty()) return;
        if (stock instanceof ColorDrawable) {
            paint.setColor(color);
            paint.setAlpha(standIn ? alpha : stock.getAlpha());
            canvas.drawRect(bounds, paint);
        } else {
            if (tintColor != color) {
                tintColor = color;
                paint.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
            }
            layerBounds.set(bounds);
            // Preserve stock corner geometry, drawing insets and per-pixel alpha.
            int save = canvas.saveLayer(layerBounds, paint);
            super.draw(canvas);
            canvas.restoreToCount(save);
        }
    }
}
