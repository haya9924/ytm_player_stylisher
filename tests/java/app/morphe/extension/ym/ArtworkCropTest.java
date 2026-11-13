package app.morphe.extension.ym;

public final class ArtworkCropTest {
    private static void equal(float actual, float expected) {
        if (Math.abs(actual - expected) > .001f) throw new AssertionError(actual + " != " + expected);
    }

    public static void main(String[] args) {
        float[] out = new float[4];
        if (!ArtworkCrop.centerCover(0, 0, 1080, 2340, 32, out)) throw new AssertionError();
        equal(out[0], -630); equal(out[1], 0); equal(out[2], 1710); equal(out[3], 2340);
        // Same image fills a wide 80px mini player, not just the top strip of the full-screen crop.
        if (!ArtworkCrop.centerCover(0, 0, 1080, 80, 32, out)) throw new AssertionError();
        equal(out[0], 0); equal(out[1], -500); equal(out[2], 1080); equal(out[3], 580);
        // Offsets matter when a parent clips the player on any edge.
        if (!ArtworkCrop.centerCover(20, 100, 1020, 180, 32, out)) throw new AssertionError();
        equal(out[0], 20); equal(out[1], -360); equal(out[2], 1020); equal(out[3], 640);
        float priorTop = Float.NaN;
        for (int height = 1; height <= 2340; height++) {
            if (!ArtworkCrop.centerCover(0, 0, 1080, height, 32, out)) throw new AssertionError();
            if (out[0] > 0 || out[1] > 0 || out[2] < 1080 || out[3] < height) {
                throw new AssertionError("visible bounds not covered at " + height);
            }
            equal(out[2] - out[0], out[3] - out[1]);
            if (!Float.isNaN(priorTop) && Math.abs(out[1] - priorTop) > 1) {
                throw new AssertionError("crop jumped during collapse");
            }
            priorTop = out[1];
        }
        if (ArtworkCrop.centerCover(0, 0, 0, 80, 32, out)) throw new AssertionError();
        System.out.println("ArtworkCrop tests passed");
    }
}
