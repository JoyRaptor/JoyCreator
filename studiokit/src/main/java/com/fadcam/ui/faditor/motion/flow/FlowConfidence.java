package com.fadcam.ui.faditor.motion.flow;

/**
 * Per-pixel trust in a forward flow field (STU-7 C6, spec §6 "confidence").
 *
 * <p>Three independent signals, multiplied:
 * <ul>
 *   <li><b>Consistency.</b> Following the forward vector and then the backward field at the
 *       landing point should return home. Where it does not, the point was hidden in the other
 *       frame (occlusion) or the match is wrong. Threshold after Sundaram, Brox and Keutzer
 *       (ECCV 2010): occluded when |f + b'|² > 0.01 (|f|² + |b'|²) + 0.5 px².</li>
 *   <li><b>Photometric.</b> The two frames should look alike at matched points.</li>
 *   <li><b>Texture.</b> A flat region gives no motion evidence (the aperture problem): the
 *       smaller eigenvalue of the local structure tensor must be large enough.</li>
 * </ul>
 * Low confidence never means "invent motion". The renderer softens toward the centre frame or
 * falls back to the global camera fit (spec §6 "unsure motion").
 */
public final class FlowConfidence {

    /** Constants. The GPU confidence pass must match these. */
    public record Params(double consistencyRel, double consistencyAbsPx2, double photoSigma,
                         double textureTau, int textureRadius, boolean useConsistency) {
        public static final Params DEFAULT = new Params(0.01, 0.5, 0.08, 1e-4, 2, true);

        public Params withoutConsistency() {
            return new Params(consistencyRel, consistencyAbsPx2, photoSigma, textureTau,
                    textureRadius, false);
        }
    }

    private FlowConfidence() {}

    /** The forward field with confidence attached. {@code backward} runs {@code to -> from}. */
    public static FlowField attach(FlowField forward, FlowField backward,
                                   LumaImage from, LumaImage to, Params params) {
        return forward.withConfidence(compute(forward, backward, from, to, params));
    }

    public static float[] compute(FlowField forward, FlowField backward,
                                  LumaImage from, LumaImage to, Params params) {
        int w = forward.width, h = forward.height;
        if (backward.width != w || backward.height != h || from.width != w || from.height != h
                || to.width != w || to.height != h) {
            throw new IllegalArgumentException("confidence inputs differ in size");
        }
        float[] out = new float[w * h];
        double sigma2 = 2 * params.photoSigma() * params.photoSigma();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double fu = forward.u(x, y), fv = forward.v(x, y);
                double tx = x + fu, ty = y + fv;
                double c = 1;
                if (params.useConsistency()) {
                    c *= consistency(fu, fv, backward.sampleU(tx, ty), backward.sampleV(tx, ty),
                            tx, ty, w, h, params);
                }
                double r = to.sample(tx, ty) - from.at(x, y);
                c *= Math.exp(-(r * r) / sigma2);
                double lam = minEigen(from, x, y, params.textureRadius());
                c *= lam / (lam + params.textureTau());
                out[y * w + x] = (float) c;
            }
        }
        return out;
    }

    /** True where the forward/backward check says the point is hidden or mismatched. */
    public static boolean occluded(FlowField forward, FlowField backward, int x, int y,
                                   Params params) {
        double fu = forward.u(x, y), fv = forward.v(x, y);
        double tx = x + fu, ty = y + fv;
        if (tx < 0 || ty < 0 || tx > forward.width - 1 || ty > forward.height - 1) return true;
        double bu = backward.sampleU(tx, ty), bv = backward.sampleV(tx, ty);
        double du = fu + bu, dv = fv + bv;
        double lim = params.consistencyRel() * (fu * fu + fv * fv + bu * bu + bv * bv)
                + params.consistencyAbsPx2();
        return du * du + dv * dv > lim;
    }

    private static double consistency(double fu, double fv, double bu, double bv,
                                      double tx, double ty, int w, int h, Params params) {
        // Leaving the frame: the point has no partner to agree with.
        if (tx < 0 || ty < 0 || tx > w - 1 || ty > h - 1) return 0;
        double du = fu + bu, dv = fv + bv;
        double lim = params.consistencyRel() * (fu * fu + fv * fv + bu * bu + bv * bv)
                + params.consistencyAbsPx2();
        double d2 = du * du + dv * dv;
        if (d2 > lim) return 0;
        return Math.exp(-d2 / lim);
    }

    /** Smaller eigenvalue of the structure tensor summed over a (2r+1)² window. */
    private static double minEigen(LumaImage img, int x, int y, int r) {
        double a = 0, b = 0, c = 0;
        for (int j = -r; j <= r; j++) {
            for (int i = -r; i <= r; i++) {
                double gx = img.gradX(x + i, y + j), gy = img.gradY(x + i, y + j);
                a += gx * gx;
                b += gx * gy;
                c += gy * gy;
            }
        }
        double tr = a + c;
        double disc = Math.sqrt(Math.max(0, (a - c) * (a - c) + 4 * b * b));
        return Math.max(0, 0.5 * (tr - disc));
    }
}
