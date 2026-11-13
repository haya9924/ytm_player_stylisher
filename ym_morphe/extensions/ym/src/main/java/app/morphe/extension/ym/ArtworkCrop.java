package app.morphe.extension.ym;

/** Center-cover bounds for the visible portion of a player as it expands and collapses. */
final class ArtworkCrop {
    private ArtworkCrop() {}

    static boolean centerCover(int left, int top, int right, int bottom, int size, float[] out) {
        int width = right - left;
        int height = bottom - top;
        if (width <= 0 || height <= 0 || size <= 0 || out.length < 4) return false;
        float extent = Math.max(width, height);
        float cx = (left + (float) right) / 2f;
        float cy = (top + (float) bottom) / 2f;
        out[0] = cx - extent / 2f;
        out[1] = cy - extent / 2f;
        out[2] = cx + extent / 2f;
        out[3] = cy + extent / 2f;
        return true;
    }
}
