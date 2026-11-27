package app.morphe.extension.ym;

/** Android-independent sRGB palette and contrast calculations. */
final class ArtworkPalette {
    static final int SECONDARY_TEXT_COLOR = 0xaaffffff; // ytm_color_white_at_67pct
    static final int MAX_PLAYER_CHANNEL = 80;
    static final double MAX_PLAYER_LUMINANCE = 0.081;
    private static final int OPAQUE = 0xff000000;
    private static final double[] LINEAR = new double[256];

    static {
        for (int channel = 0; channel < 256; channel++) {
            double value = channel / 255.0;
            LINEAR[channel] = value <= 0.04045 ? value / 12.92 : Math.pow((value + .055) / 1.055, 2.4);
        }
    }

    private ArtworkPalette() {}

    /** Scales along the original RGB hue. Bounding each channel (not only luminance) gives
     * the translucent native white text >=4.5:1, including saturated backgrounds. Bilinear
     * filtering and crossfading cannot leave the bounded RGB cube. */
    static int protectPlayerPixel(int color) {
        int r = red(color), g = green(color), b = blue(color);
        int max = Math.max(r, Math.max(g, b));
        if (max <= MAX_PLAYER_CHANNEL) return OPAQUE | color;
        return rgb((r * MAX_PLAYER_CHANNEL + max / 2) / max,
                (g * MAX_PLAYER_CHANNEL + max / 2) / max, (b * MAX_PLAYER_CHANNEL + max / 2) / max);
    }

    /** Representative cover hue before contrast limiting. Ignore transparency and favor
     * a coherent hue over isolated grey/white pixels without amplifying nearly grey covers. */
    static int imageSeed(int[] pixels) {
        int[] weights = new int[12], reds = new int[12], greens = new int[12], blues = new int[12];
        int count = 0, rSum = 0, gSum = 0, bSum = 0, best = 0;
        for (int pixel : pixels) {
            if (pixel >>> 24 < 128) continue;
            int r = red(pixel), g = green(pixel), b = blue(pixel);
            rSum += r; gSum += g; bSum += b; count++;
            int hi = Math.max(r, Math.max(g, b)), chroma = hi - Math.min(r, Math.min(g, b));
            if (chroma < 24 || hi < 36) continue;
            int hue = hi == r ? 2 * (g - b) / chroma
                    : hi == g ? 4 + 2 * (b - r) / chroma : 8 + 2 * (r - g) / chroma;
            int i = (hue + 12) % 12;
            weights[i] += chroma; reds[i] += r * chroma; greens[i] += g * chroma; blues[i] += b * chroma;
            if (weights[i] > weights[best]) best = i;
        }
        if (count == 0) return 0;
        int weight = weights[best];
        return weight >= count * 10 ? rgb(reds[best] / weight, greens[best] / weight, blues[best] / weight)
                : rgb(rSum / count, gSum / count, bSum / count);
    }

    /** The same two album-dependent stops used by the native gradient; prefer a useful hue
     * when one stop is neutral, or when opposite hues would cancel each other. */
    static int nativeSeed(int primary, int secondary) {
        if (primary >>> 24 == 0) return OPAQUE | secondary;
        if (secondary >>> 24 == 0) return OPAQUE | primary;
        int one = chroma(primary), two = chroma(secondary);
        if (one < 12 && two >= 12) return OPAQUE | secondary;
        if (two < 12 && one >= 12) return OPAQUE | primary;
        int blend = rgb((2 * red(primary) + red(secondary)) / 3,
                (2 * green(primary) + green(secondary)) / 3,
                (2 * blue(primary) + blue(secondary)) / 3);
        return chroma(blend) * 2 < Math.max(one, two)
                ? OPAQUE | (one >= two ? primary : secondary) : blend;
    }

    /** Flat and raised surfaces retain the hue but stay independently legible with the host's
     * fixed foreground colors (even the subdued #909090). */
    static int surfaceColor(int artwork, int strength, boolean raised) {
        double ratio = Math.max(0, Math.min(100, strength)) / 100.0;
        double limit = raised ? .014 + .0054 * ratio : .003 + .007 * ratio;
        int r = red(artwork), g = green(artwork), b = blue(artwork);
        int max = Math.max(r, Math.max(g, b));
        double mute = raised ? .65 : .17;
        int rr = 255, gg = 255, bb = 255;
        if (chroma(artwork) >= 12) {
            rr = muted(r, max, mute);
            gg = muted(g, max, mute);
            bb = muted(b, max, mute);
        }
        int low = 0, high = 10000;
        while (low < high) {
            int step = (low + high + 1) / 2;
            if (relativeLuminance(scaled(rr, gg, bb, step)) <= limit) low = step;
            else high = step - 1;
        }
        return scaled(rr, gg, bb, low);
    }

    static double relativeLuminance(int color) {
        return .2126 * LINEAR[red(color)] + .7152 * LINEAR[green(color)] + .0722 * LINEAR[blue(color)];
    }

    static double contrastRatio(int first, int second) {
        double a = relativeLuminance(first), b = relativeLuminance(second);
        return (Math.max(a, b) + .05) / (Math.min(a, b) + .05);
    }

    static double secondaryContrast(int background) {
        return contrastRatio(rgb(whiteOver(red(background)), whiteOver(green(background)),
                whiteOver(blue(background))), background);
    }

    private static int whiteOver(int value) { return (255 * 170 + 85 * value + 127) / 255; }
    private static int red(int color) { return color >>> 16 & 255; }
    private static int green(int color) { return color >>> 8 & 255; }
    private static int blue(int color) { return color & 255; }
    private static int rgb(int r, int g, int b) { return OPAQUE | r << 16 | g << 8 | b; }
    private static int chroma(int color) {
        int r = red(color), g = green(color), b = blue(color);
        return Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
    }
    private static int muted(int channel, int max, double mute) {
        return (int) Math.round(255 * (mute + (1 - mute) * channel / max));
    }
    private static int scaled(int r, int g, int b, int step) {
        return rgb((r * step + 5000) / 10000, (g * step + 5000) / 10000, (b * step + 5000) / 10000);
    }
}
