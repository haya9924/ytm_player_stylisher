package app.morphe.extension.ym;

/** Small, allocation-bounded box blur. Pure Java so the kernel can be unit tested on the JVM. */
final class ArtworkBlur {
    private ArtworkBlur() {}

    static int[] blur(int[] pixels, int size, int radius, int passes) {
        if (size <= 0 || radius < 0 || passes < 0 || pixels.length != size * size) {
            throw new IllegalArgumentException("Invalid blur input");
        }
        int[] source = pixels.clone();
        int[] target = new int[source.length];
        for (int pass = 0; pass < passes; pass++) {
            axis(source, target, size, radius, true);
            axis(target, source, size, radius, false);
        }
        return source;
    }

    private static void axis(int[] input, int[] output, int size, int radius, boolean horizontal) {
        int divisor = radius * 2 + 1;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int r = 0, g = 0, b = 0;
                for (int delta = -radius; delta <= radius; delta++) {
                    int xx = horizontal ? Math.max(0, Math.min(size - 1, x + delta)) : x;
                    int yy = horizontal ? y : Math.max(0, Math.min(size - 1, y + delta));
                    int color = input[yy * size + xx];
                    r += (color >>> 16) & 255;
                    g += (color >>> 8) & 255;
                    b += color & 255;
                }
                output[y * size + x] = 0xff000000 | (r / divisor << 16) | (g / divisor << 8) | (b / divisor);
            }
        }
    }
}
