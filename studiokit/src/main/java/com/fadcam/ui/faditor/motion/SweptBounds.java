package com.fadcam.ui.faditor.motion;

/** Pixel scissor covering every quad actually drawn by a discrete shutter schedule. */
public record SweptBounds(int left, int top, int right, int bottom) {
    public static SweptBounds ofSamples(ItemMotionSampler sampler, long[] timesUs,
                                        int frameWidth, int frameHeight, double haloPx) {
        if (frameWidth <= 0 || frameHeight <= 0 || !Double.isFinite(haloPx) || haloPx < 0) {
            throw new IllegalArgumentException("Invalid scissor dimensions or halo");
        }
        if (timesUs.length == 0) return new SweptBounds(0, 0, 0, 0);

        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (long timeUs : timesUs) {
            var affine = sampler.at(timeUs);
            for (int u = 0; u <= 1; u++) {
                for (int v = 0; v <= 1; v++) {
                    double x = affine.x(u, v);
                    double y = affine.y(u, v);
                    // Without a valid bound the caller may still have to render the full frame.
                    if (!Double.isFinite(x) || !Double.isFinite(y)) {
                        return new SweptBounds(0, 0, frameWidth, frameHeight);
                    }
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        return new SweptBounds(clamp((int) Math.floor(minX - haloPx), frameWidth),
                clamp((int) Math.floor(minY - haloPx), frameHeight),
                clamp((int) Math.ceil(maxX + haloPx), frameWidth),
                clamp((int) Math.ceil(maxY + haloPx), frameHeight));
    }

    private static int clamp(int px, int limit) { return Math.max(0, Math.min(px, limit)); }
}
