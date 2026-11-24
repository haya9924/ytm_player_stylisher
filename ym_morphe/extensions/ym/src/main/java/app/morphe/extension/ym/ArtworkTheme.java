package app.morphe.extension.ym;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;

import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

/** Process-local palette for the currently playing item. Views/drawables are never retained. */
public final class ArtworkTheme {
    static final String ENABLED = "artwork_app_theme";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final WeakHashMap<Drawable, Boolean> SURFACES = new WeakHashMap<>();
    private static final WeakHashMap<Drawable, Integer> LITHO_TINTS = new WeakHashMap<>();
    private static final SharedPreferences.OnSharedPreferenceChangeListener PREFERENCE_LISTENER =
            (preferences, key) -> {
                if (key == null || ENABLED.equals(key)) applyPreference(preferences);
            };
    private static final Runnable APPLY = ArtworkTheme::apply;
    private static SharedPreferences preferences;
    private static boolean pending;
    private static boolean enabled;
    private static boolean hasArtwork;
    private static int first;
    private static int second;
    private static WeakReference<Object> playbackLoader = new WeakReference<>(null);
    private static WeakReference<Bitmap> sampled = new WeakReference<>(null);
    private static int sampledGeneration;
    private static int sampledSeed;
    private static boolean metadataArtwork;
    private static Bitmap albumTile;
    private static final int TILE_SIZE = 32;
    private static int canvas;
    private static int chrome;
    private static int raised;

    private ArtworkTheme() {}

    /** Application startup hook. Keep the preference listener alive for this process. */
    public static synchronized void initialize(Context context) {
        if (preferences != null) return;
        preferences = context.getApplicationContext()
                .getSharedPreferences(GradientSettingsActivity.PREFS, Context.MODE_PRIVATE);
        preferences.registerOnSharedPreferenceChangeListener(PREFERENCE_LISTENER);
        applyPreference(preferences);
    }

    // Called by the same publisher that delivers the album palette to the native player.
    public static synchronized void onNativePalette(int primary, int secondary) {
        if (hasArtwork && primary == first && secondary == second) return;
        first = primary;
        second = secondary;
        hasArtwork = true;
        scheduleApply();
    }

    public static synchronized void onNativePaletteUnavailable() {
        if (!hasArtwork) return;
        hasArtwork = false;
        scheduleApply();
    }

    public static synchronized void bindPlaybackArtwork(Object loader) {
        if (loader == playbackLoader.get()) return;
        playbackLoader = new WeakReference<>(loader);
        clearSampledArtwork();
        hasArtwork = false;
    }

    public static synchronized void onPlaybackArtwork(Object loader, Bitmap large, Bitmap small) {
        if (Looper.myLooper() != Looper.getMainLooper() || loader != playbackLoader.get()) return;
        boolean largeUsable = usable(large);
        boolean smallUsable = usable(small);
        if (!largeUsable && !smallUsable) {
            clearSampledArtwork();
            return;
        }
        Bitmap preferred = smallUsable ? small : large;
        if (sampleArtwork(preferred)) {
            metadataArtwork = true;
            return;
        }
        Bitmap other = preferred == large ? small : large;
        if (other != preferred && usable(other) && sampleArtwork(other)) {
            metadataArtwork = true;
            return;
        }
        clearSampledArtwork();
    }

    /** Full-player callback uses the host's current artwork, never a nearby pager item. */
    public static synchronized void onPlayerArtwork(Bitmap bitmap) {
        if (Looper.myLooper() != Looper.getMainLooper() || metadataArtwork) return;
        // A null or unusable frame is transient on teardown; keep the last valid tile.
        // A failed sample of a real bitmap must not leave the stale tile behind.
        if (!usable(bitmap)) return;
        if (!sampleArtwork(bitmap)) clearSampledArtwork();
    }

    /** Drop the sampled tile only; the native palette stays valid until its owner replaces it. */
    private static void clearSampledArtwork() {
        metadataArtwork = false;
        albumTile = null;
        sampled.clear();
        sampledGeneration = 0;
        sampledSeed = 0;
        scheduleApply();
    }

    private static boolean usable(Bitmap bitmap) {
        return bitmap != null && !bitmap.isRecycled() && bitmap.getWidth() >= 8 && bitmap.getHeight() >= 8;
    }

    private static boolean sampleArtwork(Bitmap bitmap) {
        int generation = bitmap.getGenerationId();
        if (bitmap == sampled.get() && generation == sampledGeneration && albumTile != null) return true;
        Bitmap source = bitmap;
        try {
            if (bitmap.getConfig() == Bitmap.Config.HARDWARE) {
                if ((long) bitmap.getWidth() * bitmap.getHeight() > 4_000_000L) return false;
                source = bitmap.copy(Bitmap.Config.ARGB_8888, false);
                if (source == null) return false;
            }
            Bitmap tile = Bitmap.createBitmap(TILE_SIZE, TILE_SIZE, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(tile);
            canvas.drawBitmap(source, null, new Rect(0, 0, TILE_SIZE, TILE_SIZE), new Paint(Paint.FILTER_BITMAP_FLAG));
            int[] pixels = new int[TILE_SIZE * TILE_SIZE];
            tile.getPixels(pixels, 0, TILE_SIZE, 0, 0, TILE_SIZE, TILE_SIZE);
            int seed = ArtworkPalette.imageSeed(pixels);
            if (seed == 0) return false;
            int[] soft = ArtworkBlur.blur(pixels, TILE_SIZE, 4, 3);
            for (int i = 0; i < soft.length; i++) soft[i] = ArtworkPalette.protectPlayerPixel(soft[i]);
            tile.setPixels(soft, 0, TILE_SIZE, 0, 0, TILE_SIZE, TILE_SIZE);
            sampled = new WeakReference<>(bitmap);
            sampledGeneration = generation;
            albumTile = tile;
            sampledSeed = seed;
            scheduleApply();
            return true;
        } catch (RuntimeException ignored) {
            return false;
        } finally {
            if (source != null && source != bitmap) source.recycle();
        }
    }

    static synchronized Bitmap playbackTile() { return albumTile; }

    private static synchronized void applyPreference(SharedPreferences preferences) {
        boolean next = preferences.getBoolean(ENABLED, false);
        if (enabled == next) return;
        enabled = next;
        scheduleApply();
    }

    private static void scheduleApply() {
        if (pending) return;
        pending = true;
        MAIN.post(APPLY);
    }

    private static void apply() {
        int seed = 0;
        boolean active;
        synchronized (ArtworkTheme.class) {
            pending = false;
            active = enabled && (albumTile != null || hasArtwork);
            if (active) seed = albumTile != null ? sampledSeed : ArtworkPalette.nativeSeed(first, second);
        }
        int base = active ? ArtworkPalette.surfaceColor(seed, 100, false) : 0;
        int upper = active ? ArtworkPalette.surfaceColor(seed, 100, true) : 0;
        int bar = active ? mix(base, upper) : 0;
        if (base == canvas && upper == raised && bar == chrome) return;
        canvas = base;
        raised = upper;
        chrome = bar;
        // Drawables in a hardware display list must invalidate their owning View on change.
        // Snapshot first: invalidating a callback must not interrupt weak-map iteration.
        for (Drawable drawable : SURFACES.keySet().toArray(new Drawable[0])) {
            if (drawable != null) drawable.invalidateSelf();
        }
    }

    // Main-thread drawing only. 0 denotes that the stock drawable should draw unchanged.
    static int color(Drawable owner, int role) {
        track(owner);
        return role == 0 ? canvas : role == 1 ? chrome : raised;
    }

    private static void track(Drawable owner) {
        if (!SURFACES.containsKey(owner)) SURFACES.put(owner, Boolean.TRUE);
    }

    /** Only the known Litho fills; do not touch the transparent pressed/focused state. */
    public static void updateLithoPaint(Drawable owner, Paint paint, int original) {
        if (Looper.myLooper() != Looper.getMainLooper()) return;
        int live = paint.getColor();
        Integer previous = LITHO_TINTS.get(owner);
        if ((live >>> 24) == 0 || ((live & 0xffffff) != (original & 0xffffff)
                && (previous == null || (live & 0xffffff) != (previous & 0xffffff)))) return;
        int next;
        switch (original) {
            case 0xff000000:
            case 0xff030303:
                next = canvas;
                break;
            case 0xff212121:
                next = raised;
                break;
            default:
                next = 0;
        }
        if (next == 0) {
            if (previous != null) {
                LITHO_TINTS.remove(owner);
                paint.setColor((live & 0xff000000) | (original & 0xffffff));
            }
            if (original == 0xff000000 || original == 0xff030303 || original == 0xff212121) track(owner);
        } else {
            track(owner);
            if (previous == null || previous != next) LITHO_TINTS.put(owner, next);
            int tinted = (live & 0xff000000) | (next & 0xffffff);
            if (tinted != live) paint.setColor(tinted);
        }
    }

    /** Preserve transparent native states while protecting visible white player controls. */
    public static int readablePlayerBackground(int color) {
        return (color & 0xff000000) | (ArtworkPalette.protectPlayerPixel(color) & 0xffffff);
    }

    public static void protectPlayerStops(int[] colors) {
        for (int i = 0; i < colors.length; i++) {
            colors[i] = readablePlayerBackground(colors[i]);
        }
    }

    static ArtworkSurfaceDrawable background(Drawable stock, int role) {
        return new ArtworkSurfaceDrawable(stock, role);
    }

    private static int mix(int a, int b) {
        return 0xff000000 | (((a >>> 16 & 255) + (b >>> 16 & 255)) >> 1) << 16
                | (((a >>> 8 & 255) + (b >>> 8 & 255)) >> 1) << 8
                | ((a & 255) + (b & 255)) >> 1;
    }
}
