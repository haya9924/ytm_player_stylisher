package app.morphe.extension.ym;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Android smoke test using production classes, real XML inflation and attached Views. */
public final class ArtworkThemeCheckActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences preferences;
    private TextView status;
    private ImageView image;
    private ArtworkGradientView gradient;
    private View canvas, chrome, raised;
    private int stock, warm;
    private final Object metadataLoader = new Object();
    private final Object alternateLoader = new Object();
    private Bitmap lifecycleTile;
    private int lifecycleSurface;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences(GradientSettingsActivity.PREFS, MODE_PRIVATE);
        preferences.edit().clear().apply();
        ArtworkTheme.initialize(this);
        ArtworkTheme.bindPlaybackArtwork(metadataLoader);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        status = label("ym_morphe 検証用（YouTube Music 本体ではありません）");
        page.addView(status, -1, 70);
        WatchWhileLayout owner = new WatchWhileLayout(this);
        image = new ImageView(this);
        image.setId(getResources().getIdentifier("player_thumbnail", "id", getPackageName()));
        setImage(0xfff06132);
        gradient = new ArtworkGradientView(this, null);
        owner.addView(image, new FrameLayout.LayoutParams(48, 48));
        owner.addView(gradient, new FrameLayout.LayoutParams(-1, -1));
        TextView player = label("プレイヤー：白い曲名\nアーティスト名（67% 白）");
        player.setTextColor(ArtworkPalette.SECONDARY_TEXT_COLOR);
        owner.addView(player, new FrameLayout.LayoutParams(-1, -1));
        page.addView(owner, -1, 180);
        canvas = addSurface(page, "アプリ背景", "artwork_canvas");
        chrome = addSurface(page, "バー", "artwork_chrome");
        raised = addSurface(page, "メニュー", "artwork_raised_color");
        setContentView(page);
        main.postDelayed(this::verifyOff, 600);
    }

    private void verifyOff() {
        check(!preferences.getBoolean(ArtworkTheme.ENABLED, false), "default off");
        stock = sample(canvas.getBackground(), 48, 48);
        android.util.TypedValue background = new android.util.TypedValue();
        check(getTheme().resolveAttribute(android.R.attr.colorBackground, background, true), "stock theme attribute");
        int resolved = background.resourceId == 0 ? background.data : getColor(background.resourceId);
        check(stock == resolved, "stock theme attribute resolved after the unthemed XML load");
        Drawable unthemed = getResources().getDrawable(
                getResources().getIdentifier("artwork_canvas", "drawable", getPackageName()), null);
        check(sample(unthemed, 48, 48) == 0xff336699, "safe resource fallback when no theme is provided");
        deliver(0xffff5a1f);
        main.post(() -> {
            check(sample(canvas.getBackground(), 48, 48) == stock, "off ignores palette");
            preferences.edit().putBoolean(ArtworkTheme.ENABLED, true).apply();
            main.post(() -> {
                warm = checkRoles();
                check(warm != stock, "attached canvas changes on enable");
                deliver(0xff225aff);
                main.post(this::verifyCold);
            });
        });
    }

    private void verifyCold() {
        check(checkRoles() != warm, "warm and cold themes differ");
        Drawable rounded = resource("artwork_raised_rounded");
        int upper = sample(raised.getBackground(), 48, 48);
        check(sample(rounded, 48, 48) == upper, "rounded fill matches role");
        check(sample(rounded, 1, 1) >>> 24 == 0, "rounded corner stays transparent");
        deliver(Color.WHITE);
        // Native publisher may report a sentinel even while the artwork remains visible.
        ArtworkTheme.onNativePaletteUnavailable();
        main.postDelayed(this::verifyWhite, 1000);
    }

    private void verifyWhite() {
        int neutral = checkRoles();
        check((neutral >> 8 & 0xffff) == (neutral & 0xffff), "white artwork yields a neutral app tone");
        Bitmap rendered = Bitmap.createBitmap(gradient.getWidth(), gradient.getHeight(), Bitmap.Config.ARGB_8888);
        gradient.draw(new Canvas(rendered));
        int player = rendered.getPixel(rendered.getWidth() / 2, rendered.getHeight() / 2);
        rendered.recycle();
        check(player == 0xff505050, "white blurred player expected #505050, got " + Integer.toHexString(player));
        check(ArtworkPalette.secondaryContrast(player) >= 4.5 && player != neutral, "native 67% text and distinct app tone");
        Drawable current = canvas.getBackground();
        preferences.edit().putBoolean(ArtworkTheme.ENABLED, false).apply();
        main.post(() -> {
            check(canvas.getBackground() == current && sample(current, 48, 48) == stock, "switch off restores stock without reinflation");
            preferences.edit().putBoolean(ArtworkTheme.ENABLED, true).apply();
            main.post(() -> {
                check(sample(current, 48, 48) == neutral, "switch on replays latest palette");
                ArtworkTheme.onPlaybackArtwork(metadataLoader, null, null);
                main.post(() -> {
                    check(sample(current, 48, 48) == stock, "no artwork restores stock");
                    verifyLifecycle(() -> {
                        deliver(Color.WHITE);
                        status.setText("PASS — ジャケット連動と白ジャケットの可読性");
                        Log.i("YmArtworkTest", "PASS: XML drawable, same-view toggle, track changes, reset, white gradient, role contrast and artwork lifecycle");
                    });
                });
            });
        });
    }

    private void verifyLifecycle(Runnable done) {
        int primary = 0xff1e88e5, secondary = 0xff43a047;
        int nativeSurface = ArtworkPalette.surfaceColor(ArtworkPalette.nativeSeed(primary, secondary), 100, false);
        check(ArtworkTheme.playbackTile() == null, "no tile before the native-palette lifecycle");
        ArtworkTheme.onNativePalette(primary, secondary);
        main.post(() -> {
            check(ArtworkTheme.playbackTile() == null, "native palette does not synthesize a tile");
            check(sample(canvas.getBackground(), 48, 48) == nativeSurface, "native palette is an independent fallback");
            check(nativeSurface != stock, "native fallback differs from stock");
            verifyLoaderBind(primary, secondary, nativeSurface, done);
        });
    }

    private void verifyLoaderBind(int primary, int secondary, int nativeSurface, Runnable done) {
        ArtworkTheme.bindPlaybackArtwork(alternateLoader);
        check(ArtworkTheme.playbackTile() == null, "rebinding without a sample leaves no tile");
        deliver(alternateLoader, 0xff2255ff);
        main.post(() -> {
            check(ArtworkTheme.playbackTile() != null, "bound loader delivers a tile");
            check(sample(canvas.getBackground(), 48, 48) != stock, "tile overrides the stock surface");
            ArtworkTheme.bindPlaybackArtwork(metadataLoader);
            check(ArtworkTheme.playbackTile() == null, "binding a different loader clears the tile");
            main.post(() -> {
                check(sample(canvas.getBackground(), 48, 48) == stock, "different-loader bind restores stock");
                ArtworkTheme.bindPlaybackArtwork(metadataLoader);
                deliver(metadataLoader, 0xff10b981);
                main.post(() -> {
                    lifecycleTile = ArtworkTheme.playbackTile();
                    check(lifecycleTile != null, "re-delivered metadata samples a tile");
                    lifecycleSurface = sample(canvas.getBackground(), 48, 48);
                    ArtworkTheme.bindPlaybackArtwork(metadataLoader);
                    main.post(() -> {
                        check(ArtworkTheme.playbackTile() == lifecycleTile, "same-loader bind preserves the tile");
                        check(sample(canvas.getBackground(), 48, 48) == lifecycleSurface, "same-loader bind keeps the surface");
                        verifyUnbound(primary, secondary, nativeSurface, done);
                    });
                });
            });
        });
    }

    private void verifyUnbound(int primary, int secondary, int nativeSurface, Runnable done) {
        Bitmap ignoredArtwork = artwork(0xffff0000);
        ArtworkTheme.onPlaybackArtwork(new Object(), ignoredArtwork, null);
        ignoredArtwork.recycle();
        ArtworkTheme.onPlaybackArtwork(new Object(), null, null);
        main.post(() -> {
            check(ArtworkTheme.playbackTile() == lifecycleTile, "unbound callbacks leave the tile unchanged");
            check(sample(canvas.getBackground(), 48, 48) == lifecycleSurface, "unbound callbacks leave the surface unchanged");
            ArtworkTheme.onPlayerArtwork(null);
            Bitmap ignoredPlayer = artwork(0xff00ff00);
            ArtworkTheme.onPlayerArtwork(ignoredPlayer);
            ignoredPlayer.recycle();
            main.post(() -> {
                check(ArtworkTheme.playbackTile() == lifecycleTile, "metadata tile is protected from player callbacks");
                check(sample(canvas.getBackground(), 48, 48) == lifecycleSurface, "metadata surface is protected from player nulls");
                verifyClears(primary, secondary, nativeSurface, done);
            });
        });
    }

    private void verifyClears(int primary, int secondary, int nativeSurface, Runnable done) {
        ArtworkTheme.onNativePalette(primary, secondary);
        main.post(() -> {
            check(ArtworkTheme.playbackTile() == lifecycleTile, "native palette keeps the sampled tile");
            ArtworkTheme.onPlaybackArtwork(metadataLoader, null, null);
            check(ArtworkTheme.playbackTile() == null, "null metadata clears the tile");
            main.post(() -> {
                check(sample(canvas.getBackground(), 48, 48) == nativeSurface, "null metadata keeps the native palette fallback");
                Bitmap fallback = artwork(0xff00c853);
                ArtworkTheme.onPlayerArtwork(fallback);
                fallback.recycle();
                main.post(() -> {
                    Bitmap playerTile = ArtworkTheme.playbackTile();
                    check(playerTile != null, "player fallback samples a tile");
                    int playerSurface = sample(canvas.getBackground(), 48, 48);
                    check(playerSurface != stock, "player tile overrides the stock surface");
                    ArtworkTheme.onPlayerArtwork(null);
                    main.post(() -> {
                        check(ArtworkTheme.playbackTile() == playerTile, "fallback player null keeps the tile");
                        check(sample(canvas.getBackground(), 48, 48) == playerSurface, "fallback player null keeps the surface");
                        ArtworkTheme.onPlaybackArtwork(metadataLoader, null, null);
                        check(ArtworkTheme.playbackTile() == null, "metadata clear drops the player tile");
                        main.post(() -> {
                            check(sample(canvas.getBackground(), 48, 48) == nativeSurface, "cleared player tile returns to native fallback");
                            deliver(metadataLoader, 0xff0ea5e9);
                            main.post(() -> {
                                check(ArtworkTheme.playbackTile() != null, "valid metadata samples a fresh tile");
                                check(sample(canvas.getBackground(), 48, 48) != stock, "metadata tile overrides the stock surface");
                                Bitmap transparent = artwork(0);
                                ArtworkTheme.onPlaybackArtwork(metadataLoader, transparent, null);
                                transparent.recycle();
                                check(ArtworkTheme.playbackTile() == null, "transparent sample failure clears the tile");
                                main.post(() -> {
                                    check(sample(canvas.getBackground(), 48, 48) == nativeSurface, "transparent failure falls back to the native palette");
                                    done.run();
                                });
                            });
                        });
                    });
                });
            });
        });
    }

    private int checkRoles() {
        int base = sample(canvas.getBackground(), 48, 48);
        int bar = sample(chrome.getBackground(), 48, 48);
        int menu = sample(raised.getBackground(), 48, 48);
        check(base != bar && base != menu && bar != menu, "app surface roles differ");
        for (int color : new int[]{base, bar, menu}) {
            check(ArtworkPalette.contrastRatio(0xff909090, color) >= 4.5, "app tertiary contrast");
        }
        return base;
    }

    private void deliver(int color) {
        deliver(metadataLoader, color);
    }

    private void deliver(Object loader, int color) {
        Bitmap bitmap = artwork(color);
        ArtworkTheme.onPlaybackArtwork(loader, bitmap, null);
        bitmap.recycle();
    }

    private Bitmap artwork(int color) {
        Bitmap bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(color);
        return bitmap;
    }

    private void setImage(int color) {
        Bitmap bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(color);
        image.setImageDrawable(new BitmapDrawable(getResources(), bitmap));
    }

    private TextView label(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(Color.WHITE);
        label.setPadding(16, 12, 16, 0);
        return label;
    }

    private View addSurface(LinearLayout page, String label, String drawable) {
        TextView view = label(label);
        view.setBackground(resource(drawable));
        page.addView(view, -1, 80);
        return view;
    }

    private Drawable resource(String name) {
        int id = getResources().getIdentifier(name, "drawable", getPackageName());
        check(id != 0, "missing drawable " + name);
        return getDrawable(id);
    }

    private int sample(Drawable drawable, int x, int y) {
        Bitmap bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
        android.graphics.Rect bounds = drawable.copyBounds();
        drawable.setBounds(0, 0, 96, 96);
        drawable.draw(new Canvas(bitmap));
        drawable.setBounds(bounds);
        int result = bitmap.getPixel(x, y);
        bitmap.recycle();
        return result;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
