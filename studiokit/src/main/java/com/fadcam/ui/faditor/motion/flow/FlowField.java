package com.fadcam.ui.faditor.motion.flow;

/**
 * A dense motion field between two decoded SOURCE frames (STU-7 C6, spec §6).
 *
 * <p>Conventions, fixed so every producer and consumer agrees:
 * <ul>
 *   <li>The vector at pixel p of the {@code from} frame says where that point is in the
 *       {@code to} frame: {@code p_to = p + (u, v)}.</li>
 *   <li>Units are pixels of THIS field's grid. {@link #scaledTo} converts to another resolution.</li>
 *   <li>{@code fromPtsUs} / {@code toPtsUs} are the real presentation times of the two source
 *       frames. A backward field has {@code toPtsUs < fromPtsUs}.</li>
 *   <li>Confidence is 0..1 per pixel; 1 everywhere until a {@link FlowConfidence} pass sets it.</li>
 * </ul>
 * Arrays are owned by the field and never exposed for writing.
 */
public final class FlowField {
    public final int width;
    public final int height;
    public final long fromPtsUs;
    public final long toPtsUs;
    private final float[] u;
    private final float[] v;
    private final float[] confidence;

    public FlowField(int width, int height, float[] u, float[] v, float[] confidence,
                     long fromPtsUs, long toPtsUs) {
        int n = width * height;
        if (width <= 0 || height <= 0 || u.length != n || v.length != n
                || (confidence != null && confidence.length != n)) {
            throw new IllegalArgumentException("field size mismatch");
        }
        this.width = width;
        this.height = height;
        this.u = u;
        this.v = v;
        this.confidence = confidence;
        this.fromPtsUs = fromPtsUs;
        this.toPtsUs = toPtsUs;
    }

    public static FlowField zero(int width, int height, long fromPtsUs, long toPtsUs) {
        int n = width * height;
        return new FlowField(width, height, new float[n], new float[n], null, fromPtsUs, toPtsUs);
    }

    public float u(int x, int y) { return u[index(x, y)]; }

    public float v(int x, int y) { return v[index(x, y)]; }

    public float confidence(int x, int y) {
        return confidence == null ? 1f : confidence[index(x, y)];
    }

    public boolean hasConfidence() { return confidence != null; }

    /** Bilinear u at a continuous position (clamped to the edge). */
    public float sampleU(double x, double y) { return bilinear(u, x, y); }

    /** Bilinear v at a continuous position (clamped to the edge). */
    public float sampleV(double x, double y) { return bilinear(v, x, y); }

    /** The same field with a confidence map attached (copied). */
    public FlowField withConfidence(float[] c) {
        return new FlowField(width, height, u, v, c.clone(), fromPtsUs, toPtsUs);
    }

    /**
     * Resampled onto a {@code w x h} grid covering the same picture; vectors are rescaled to the
     * new grid's pixels. Used both to upsample coarse flow and to move between analysis and
     * render resolution.
     */
    public FlowField scaledTo(int w, int h) {
        float sx = (float) w / width;
        float sy = (float) h / height;
        int n = w * h;
        float[] nu = new float[n], nv = new float[n];
        float[] nc = confidence == null ? null : new float[n];
        for (int y = 0; y < h; y++) {
            double srcY = (y + 0.5) / sy - 0.5;
            for (int x = 0; x < w; x++) {
                double srcX = (x + 0.5) / sx - 0.5;
                int i = y * w + x;
                nu[i] = bilinear(u, srcX, srcY) * sx;
                nv[i] = bilinear(v, srcX, srcY) * sy;
                if (nc != null) nc[i] = bilinear(confidence, srcX, srcY);
            }
        }
        return new FlowField(w, h, nu, nv, nc, fromPtsUs, toPtsUs);
    }

    /**
     * Factor that turns this field's vectors into the displacement over a shutter window of
     * {@code windowProjectUs} project time, for a clip playing at {@code speed} (source time per
     * project time: 2 = twice as fast, negative = reversed, 0 = frozen).
     *
     * <p>Flow is measured between real source frames; the shutter is open in project time. A
     * frozen clip has no motion; a reversed clip moves the other way; a 2x clip smears twice as
     * far. This is the ONE place that conversion happens (spec §6 "speed").
     */
    public double displacementScale(long windowProjectUs, double speed) {
        long pairUs = toPtsUs - fromPtsUs;
        if (pairUs == 0 || speed == 0 || windowProjectUs <= 0) return 0;
        return (windowProjectUs * speed) / pairUs;
    }

    private int index(int x, int y) {
        x = x < 0 ? 0 : (x >= width ? width - 1 : x);
        y = y < 0 ? 0 : (y >= height ? height - 1 : y);
        return y * width + x;
    }

    private float bilinear(float[] a, double x, double y) {
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        float fx = (float) (x - x0);
        float fy = (float) (y - y0);
        float p = a[index(x0, y0)], q = a[index(x0 + 1, y0)];
        float r = a[index(x0, y0 + 1)], s = a[index(x0 + 1, y0 + 1)];
        return (p + (q - p) * fx) * (1 - fy) + (r + (s - r) * fx) * fy;
    }
}
