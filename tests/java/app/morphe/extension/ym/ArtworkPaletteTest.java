package app.morphe.extension.ym;

public final class ArtworkPaletteTest {
    private static final int WHITE = 0xffffffff;

    public static void main(String[] args) {
        check(ArtworkPalette.contrastRatio(WHITE, 0xff000000) == 21, "black/white WCAG contrast");
        check(ArtworkPalette.protectPlayerPixel(WHITE) == 0xff505050, "white artwork");
        check(ArtworkPalette.protectPlayerPixel(0xff2a0b40) == 0xff2a0b40, "preserve dark hue");
        check(ArtworkPalette.protectPlayerPixel(0xffa90000) == 0xff500000, "protect saturated red");
        check(ArtworkPalette.protectPlayerPixel(0xfffff0a0) == 0xff504b32, "protect pale yellow");
        // Exhaust the entire guarded RGB cube: this includes every possible pixel produced by
        // bitmap filtering, opacity, and crossfade between any two guarded colors.
        double worst = Double.MAX_VALUE;
        int max = ArtworkPalette.MAX_PLAYER_CHANNEL;
        for (int r = 0; r <= max; r++) for (int g = 0; g <= max; g++) for (int b = 0; b <= max; b++) {
            int color = 0xff000000 | r << 16 | g << 8 | b;
            worst = Math.min(worst, ArtworkPalette.secondaryContrast(color));
            check(ArtworkPalette.relativeLuminance(color) <= ArtworkPalette.MAX_PLAYER_LUMINANCE,
                    "player background luminance");
        }
        check(worst >= 4.5, "67% native white contrast: " + worst);

        check(ArtworkPalette.imageSeed(new int[]{0, 0x20808080}) == 0, "transparent artwork ignored");
        check(ArtworkPalette.imageSeed(new int[]{WHITE, WHITE, WHITE}) == WHITE, "white artwork stays neutral");
        check(ArtworkPalette.imageSeed(new int[]{0xff0044fa, 0xff205cff, WHITE}) !=
                ArtworkPalette.imageSeed(new int[]{0xfffa4400, 0xffff5c20, WHITE}), "visible cover hue follows the track");
        check(ArtworkPalette.nativeSeed(WHITE, 0xffe00000) == 0xffe00000, "prefer meaningful hue");
        check(ArtworkPalette.nativeSeed(0, 0xff00c1b0) == 0xff00c1b0, "absent stop");
        check(ArtworkPalette.nativeSeed(0xff808080, 0xff404040) == 0xff6a6a6a, "neutral swatches");
        check(ArtworkPalette.nativeSeed(0xff102030, 0xff203040) == 0xff152535, "blend related swatches");
        for (int artwork : new int[]{WHITE, 0xff000000, 0xffe20d15, 0xff155cff, 0xff29d61c, 0xffffff00}) {
            double previousFlat = -1, previousRaised = -1;
            for (int strength = 0; strength <= 100; strength++) {
                int flat = ArtworkPalette.surfaceColor(artwork, strength, false);
                int raised = ArtworkPalette.surfaceColor(artwork, strength, true);
                double flatL = ArtworkPalette.relativeLuminance(flat), raisedL = ArtworkPalette.relativeLuminance(raised);
                check(flatL < raisedL, "app surface roles differ");
                check(flatL >= previousFlat && raisedL >= previousRaised, "monotone app surface luminance");
                previousFlat = flatL;
                previousRaised = raisedL;
                for (int color : new int[]{flat, raised}) {
                    check(color >>> 24 == 255, "opaque app surface");
                    check(ArtworkPalette.contrastRatio(WHITE, color) >= 7, "app surface white text");
                    check(ArtworkPalette.contrastRatio(0xff909090, color) >= 4.5, "app surface tertiary text");
                    check(ArtworkPalette.secondaryContrast(color) >= 4.5, "app surface translucent text");
                }
            }
        }
        check(ArtworkPalette.surfaceColor(0xffdd3311, 100, false)
                != ArtworkPalette.surfaceColor(0xff1122ff, 100, false), "tint follows track");
        System.out.printf("ArtworkPalette tests passed; worst native translucent-text contrast %.2f:1%n", worst);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
