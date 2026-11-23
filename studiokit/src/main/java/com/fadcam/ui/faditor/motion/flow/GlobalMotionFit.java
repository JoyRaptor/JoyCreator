package com.fadcam.ui.faditor.motion.flow;

import java.util.Random;

/**
 * One affine camera motion fitted to a flow field, ignoring things that move on their own
 * (STU-7 C6, spec §3 "Camera move only" and §6 "unsure motion").
 *
 * <p>Model: {@code u = a0 + a1 x + a2 y}, {@code v = b0 + b1 x + b2 y}, in field pixels. That
 * covers pans, tilts, zooms and small rolls. Fit: deterministic RANSAC over confident samples,
 * then a confidence-weighted least-squares refinement on the inliers. A plain least-squares fit
 * would be dragged by a person walking through the shot; RANSAC lets the background win.
 */
public final class GlobalMotionFit {

    /** The fitted motion. {@code inlierFraction} says how much of the picture agreed. */
    public record Affine(double a0, double a1, double a2, double b0, double b1, double b2,
                         double inlierFraction) {
        public static final Affine NONE = new Affine(0, 0, 0, 0, 0, 0, 0);

        public double u(double x, double y) { return a0 + a1 * x + a2 * y; }

        public double v(double x, double y) { return b0 + b1 * x + b2 * y; }
    }

    /** Settings; {@code ransac=false} is a plain weighted least-squares fit (negative control). */
    public record Params(int sampleStep, double minConfidence, double inlierPx, int iterations,
                         long seed, boolean ransac) {
        public static final Params DEFAULT = new Params(2, 0.3, 0.6, 300, 0x5EEDL, true);

        public Params withoutRansac() {
            return new Params(sampleStep, minConfidence, inlierPx, iterations, seed, false);
        }
    }

    private GlobalMotionFit() {}

    public static Affine fit(FlowField field, Params params) {
        int cap = (field.width / params.sampleStep() + 1) * (field.height / params.sampleStep() + 1);
        double[] xs = new double[cap], ys = new double[cap], us = new double[cap];
        double[] vs = new double[cap], ws = new double[cap];
        int n = 0;
        for (int y = 0; y < field.height; y += params.sampleStep()) {
            for (int x = 0; x < field.width; x += params.sampleStep()) {
                double c = field.confidence(x, y);
                if (c < params.minConfidence()) continue;
                xs[n] = x; ys[n] = y; us[n] = field.u(x, y); vs[n] = field.v(x, y); ws[n] = c;
                n++;
            }
        }
        if (n < 3) return Affine.NONE;
        boolean[] in = new boolean[n];
        if (!params.ransac()) {
            java.util.Arrays.fill(in, true);
            return refine(xs, ys, us, vs, ws, in, n, 1.0);
        }
        Random rnd = new Random(params.seed());
        int best = -1;
        double lim2 = params.inlierPx() * params.inlierPx();
        boolean[] trial = new boolean[n];
        for (int it = 0; it < params.iterations(); it++) {
            int i = rnd.nextInt(n), j = rnd.nextInt(n), k = rnd.nextInt(n);
            Affine m = exact(xs, ys, us, vs, i, j, k);
            if (m == null) continue;
            int count = 0;
            for (int s = 0; s < n; s++) {
                double du = m.u(xs[s], ys[s]) - us[s], dv = m.v(xs[s], ys[s]) - vs[s];
                trial[s] = du * du + dv * dv <= lim2;
                if (trial[s]) count++;
            }
            if (count > best) {
                best = count;
                System.arraycopy(trial, 0, in, 0, n);
            }
        }
        if (best < 3) return Affine.NONE;
        return refine(xs, ys, us, vs, ws, in, n, (double) best / n);
    }

    /** Affine through three samples exactly; null when they are (nearly) collinear. */
    private static Affine exact(double[] xs, double[] ys, double[] us, double[] vs,
                                int i, int j, int k) {
        double[][] m = {{1, xs[i], ys[i]}, {1, xs[j], ys[j]}, {1, xs[k], ys[k]}};
        if (Math.abs(det3(m)) < 1e-6) return null;
        double[] a = solveNormal(m, new double[]{us[i], us[j], us[k]});
        double[] b = solveNormal(m, new double[]{vs[i], vs[j], vs[k]});
        if (a == null || b == null) return null;
        return new Affine(a[0], a[1], a[2], b[0], b[1], b[2], 0);
    }

    /** Confidence-weighted least squares over the inliers (normal equations, 3x3). */
    private static Affine refine(double[] xs, double[] ys, double[] us, double[] vs, double[] ws,
                                 boolean[] in, int n, double inlierFraction) {
        double[][] m = new double[3][3];
        double[] ru = new double[3], rv = new double[3];
        for (int s = 0; s < n; s++) {
            if (!in[s]) continue;
            double[] row = {1, xs[s], ys[s]};
            for (int r = 0; r < 3; r++) {
                for (int c = 0; c < 3; c++) m[r][c] += ws[s] * row[r] * row[c];
                ru[r] += ws[s] * row[r] * us[s];
                rv[r] += ws[s] * row[r] * vs[s];
            }
        }
        double[] a = solveNormal(m, ru);
        double[] b = solveNormal(m, rv);
        if (a == null || b == null) return Affine.NONE;
        return new Affine(a[0], a[1], a[2], b[0], b[1], b[2], inlierFraction);
    }

    private static double[] solveNormal(double[][] m, double[] r) {
        double d = det3(m);
        if (Math.abs(d) < 1e-12) return null;
        double[] out = new double[3];
        for (int c = 0; c < 3; c++) {
            double[][] t = {m[0].clone(), m[1].clone(), m[2].clone()};
            for (int row = 0; row < 3; row++) t[row][c] = r[row];
            out[c] = det3(t) / d;
        }
        return out;
    }

    private static double det3(double[][] m) {
        return m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1])
                - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
                + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]);
    }
}
