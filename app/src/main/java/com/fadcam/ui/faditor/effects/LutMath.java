package com.fadcam.ui.faditor.effects;

/**
 * PREVIEW-1: a colour grade applied to a small picture on the CPU, so a grade can be listed with a swatch.
 *
 * <p>Pure Java (no Android classes) so it is tested off the device. The grade is the packed {@code N x N*N}
 * bitmap {@link LutManager} makes: the pixel at row {@code r*N + g}, column {@code b} holds the output colour for the input
 * colour with red index r, green g and blue b (the same walk {@link LutManager#applyIntensity} uses).</p>
 */
public final class LutMath {
    private LutMath() {}

    /**
     * Grade every pixel of {@code src} (ARGB ints) through the packed LUT, blending the eight nearest entries.
     *
     * @param lut packed LUT pixels, {@code n * n * n} ints
     * @return the graded pixels, same length as {@code src}; {@code src} itself if the LUT is not {@code n * n * n} long
     */
    public static int[] apply(int[] lut, int n, int[] src) {
        if (n < 2 || lut.length != n * n * n) return src;
        int[] out = new int[src.length];
        float scale = (n - 1) / 255f;
        for (int i = 0; i < src.length; i++) {
            int c = src[i];
            float r = ((c >> 16) & 0xFF) * scale, g = ((c >> 8) & 0xFF) * scale, b = (c & 0xFF) * scale;
            int r0 = Math.min(n - 2, (int) r), g0 = Math.min(n - 2, (int) g), b0 = Math.min(n - 2, (int) b);
            float fr = r - r0, fg = g - g0, fb = b - b0;
            float or = 0, og = 0, ob = 0;
            for (int dr = 0; dr < 2; dr++) {
                for (int dg = 0; dg < 2; dg++) {
                    for (int db = 0; db < 2; db++) {
                        float w = (dr == 0 ? 1 - fr : fr) * (dg == 0 ? 1 - fg : fg) * (db == 0 ? 1 - fb : fb);
                        if (w == 0f) continue;
                        int v = lut[((r0 + dr) * n + (g0 + dg)) * n + (b0 + db)];
                        or += w * ((v >> 16) & 0xFF);
                        og += w * ((v >> 8) & 0xFF);
                        ob += w * (v & 0xFF);
                    }
                }
            }
            out[i] = (c & 0xFF000000) | (clamp(or) << 16) | (clamp(og) << 8) | clamp(ob);
        }
        return out;
    }

    private static int clamp(float v) {
        int i = Math.round(v);
        return i < 0 ? 0 : Math.min(i, 255);
    }
}
