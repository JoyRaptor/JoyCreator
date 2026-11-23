package com.fadcam.ui.faditor.motion.flow;

/**
 * A single-channel float image (luma, 0..1 nominal) for motion analysis. Immutable after
 * construction; pixel (x, y) lives at {@code px[y * width + x]}. Sampling clamps to the edge.
 *
 * <p>STU-7 C6: the input every flow estimator reads. The GPU estimator reads the same luma at the
 * same resolution, so this class also defines what "1/8 resolution luma" means.
 */
public final class LumaImage {
    public final int width;
    public final int height;
    private final float[] px;

    public LumaImage(int width, int height, float[] px) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("empty image");
        if (px.length != width * height) throw new IllegalArgumentException("size mismatch");
        this.width = width;
        this.height = height;
        this.px = px;
    }

    /** Rec. 709 luma of sRGB-coded ARGB pixels, kept in coded space (matches the shader). */
    public static LumaImage fromArgb(int width, int height, int[] argb) {
        float[] out = new float[width * height];
        for (int i = 0; i < out.length; i++) {
            int c = argb[i];
            out[i] = (0.2126f * ((c >> 16) & 0xff) + 0.7152f * ((c >> 8) & 0xff)
                    + 0.0722f * (c & 0xff)) / 255f;
        }
        return new LumaImage(width, height, out);
    }

    public float at(int x, int y) {
        x = x < 0 ? 0 : (x >= width ? width - 1 : x);
        y = y < 0 ? 0 : (y >= height ? height - 1 : y);
        return px[y * width + x];
    }

    /** Bilinear sample at a continuous pixel position (pixel centres on integers). */
    public float sample(double x, double y) {
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        float fx = (float) (x - x0);
        float fy = (float) (y - y0);
        float a = at(x0, y0), b = at(x0 + 1, y0), c = at(x0, y0 + 1), d = at(x0 + 1, y0 + 1);
        return (a + (b - a) * fx) * (1 - fy) + (c + (d - c) * fx) * fy;
    }

    /** Central-difference gradient along x at an integer pixel. */
    public float gradX(int x, int y) { return 0.5f * (at(x + 1, y) - at(x - 1, y)); }

    /** Central-difference gradient along y at an integer pixel. */
    public float gradY(int x, int y) { return 0.5f * (at(x, y + 1) - at(x, y - 1)); }

    /**
     * Half resolution: output pixel x covers source pixels 2x-1..2x+2 with [1 3 3 1]/8 weights in
     * each axis, so its centre sits at source 2x+0.5 (the convention {@link FlowField#scaledTo}
     * uses).
     */
    public LumaImage half() {
        int w = Math.max(1, width / 2);
        int h = Math.max(1, height / 2);
        float[] out = new float[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int sx = 2 * x, sy = 2 * y;
                float sum = 0;
                for (int j = -1; j <= 2; j++) {
                    float wy = (j == -1 || j == 2) ? 1 : 3;
                    for (int i = -1; i <= 2; i++) {
                        float wx = (i == -1 || i == 2) ? 1 : 3;
                        sum += wx * wy * at(sx + i, sy + j);
                    }
                }
                out[y * w + x] = sum / 64f;
            }
        }
        return new LumaImage(w, h, out);
    }
}
