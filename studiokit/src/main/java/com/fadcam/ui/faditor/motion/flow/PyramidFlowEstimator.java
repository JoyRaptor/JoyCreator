package com.fadcam.ui.faditor.motion.flow;

import java.util.ArrayList;
import java.util.List;

/**
 * Reference dense optical flow: coarse-to-fine patch inverse search, then densification
 * (the structure of Kroeger et al., "Fast Optical Flow using Dense Inverse Search", ECCV 2016,
 * without the variational refinement).
 *
 * <p>STU-7 C6. This pure-Java version is the TEST ORACLE and the written specification for the
 * GPU estimator (fragment passes, same steps, same constants) and the yardstick for the OpenCV
 * DIS bake-off (spec §6). It is deterministic: the same two images always give the same field.
 *
 * <p>Per level, coarse to fine:
 * <ol>
 *   <li>Start from the coarser level's field, upsampled (zero at the coarsest).</li>
 *   <li>For each patch on a regular grid, refine one translation by inverse-compositional
 *       Gauss-Newton on mean-normalised intensities (robust to a brightness change), keeping a
 *       step only when it lowers the patch error.</li>
 *   <li>Densify: every pixel takes the average of the patches covering it, each weighted by
 *       1 / max(floor, |photometric error at that pixel|), so a patch straddling a motion edge
 *       defers to the patch that actually matches there.</li>
 * </ol>
 * Identical inputs give an exactly zero field (every error is zero, so no step is taken).
 */
public final class PyramidFlowEstimator {

    /** Tuning. {@link #DEFAULT} is the contract the GPU version must match. */
    public record Settings(int maxLevels, int patch, int stride, int iterations,
                           int minLevelSize, boolean meanNormalise) {
        public static final Settings DEFAULT = new Settings(6, 8, 4, 12, 12, true);

        public Settings {
            if (maxLevels < 1 || patch < 2 || stride < 1 || iterations < 1 || minLevelSize < patch) {
                throw new IllegalArgumentException("bad flow settings");
            }
        }

        public Settings withMaxLevels(int levels) {
            return new Settings(levels, patch, stride, iterations, minLevelSize, meanNormalise);
        }

        public Settings withMeanNormalise(boolean on) {
            return new Settings(maxLevels, patch, stride, iterations, minLevelSize, on);
        }
    }

    private static final float WEIGHT_FLOOR = 1f / 255f;
    private static final double MIN_DET = 1e-9;
    private static final double STOP_STEP = 1e-3;

    private final Settings settings;

    public PyramidFlowEstimator(Settings settings) { this.settings = settings; }

    public PyramidFlowEstimator() { this(Settings.DEFAULT); }

    /** Flow from {@code from} to {@code to} (same size), stamped with their source PTS. */
    public FlowField estimate(LumaImage from, LumaImage to, long fromPtsUs, long toPtsUs) {
        if (from.width != to.width || from.height != to.height) {
            throw new IllegalArgumentException("frames differ in size");
        }
        List<LumaImage> a = pyramid(from);
        List<LumaImage> b = pyramid(to);
        FlowField field = null;
        for (int level = a.size() - 1; level >= 0; level--) {
            LumaImage i0 = a.get(level), i1 = b.get(level);
            field = field == null
                    ? FlowField.zero(i0.width, i0.height, fromPtsUs, toPtsUs)
                    : field.scaledTo(i0.width, i0.height);
            field = refineLevel(i0, i1, field);
        }
        return field;
    }

    private List<LumaImage> pyramid(LumaImage base) {
        List<LumaImage> levels = new ArrayList<>();
        levels.add(base);
        LumaImage cur = base;
        while (levels.size() < settings.maxLevels()
                && Math.min(cur.width / 2, cur.height / 2) >= settings.minLevelSize()) {
            cur = cur.half();
            levels.add(cur);
        }
        return levels;
    }

    private FlowField refineLevel(LumaImage i0, LumaImage i1, FlowField init) {
        int w = i0.width, h = i0.height;
        int p = Math.min(settings.patch(), Math.min(w, h));
        int[] xs = grid(w, p, settings.stride());
        int[] ys = grid(h, p, settings.stride());
        int n = w * h;
        float[] sumU = new float[n], sumV = new float[n], sumW = new float[n];
        float[] tpl = new float[p * p], gx = new float[p * p], gy = new float[p * p];
        for (int py : ys) {
            for (int px : xs) {
                // Initial guess: the incoming field at the patch centre.
                double cx = px + (p - 1) * 0.5, cy = py + (p - 1) * 0.5;
                double[] uv = {init.sampleU(cx, cy), init.sampleV(cx, cy)};
                searchPatch(i0, i1, px, py, p, uv, tpl, gx, gy);
                // Densify this patch's vote into the pixels it covers.
                for (int j = 0; j < p; j++) {
                    for (int i = 0; i < p; i++) {
                        int x = px + i, y = py + j;
                        float err = Math.abs(i1.sample(x + uv[0], y + uv[1]) - i0.at(x, y));
                        float wt = 1f / Math.max(WEIGHT_FLOOR, err);
                        int k = y * w + x;
                        sumU[k] += (float) (wt * uv[0]);
                        sumV[k] += (float) (wt * uv[1]);
                        sumW[k] += wt;
                    }
                }
            }
        }
        float[] u = new float[n], v = new float[n];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int k = y * w + x;
                if (sumW[k] > 0) {
                    u[k] = sumU[k] / sumW[k];
                    v[k] = sumV[k] / sumW[k];
                } else {
                    u[k] = init.u(x, y);
                    v[k] = init.v(x, y);
                }
            }
        }
        return new FlowField(w, h, u, v, null, init.fromPtsUs, init.toPtsUs);
    }

    /** Inverse-compositional translation search for one patch; refines {@code uv} in place. */
    private void searchPatch(LumaImage i0, LumaImage i1, int px, int py, int p, double[] uv,
                             float[] tpl, float[] gx, float[] gy) {
        int m = p * p;
        double meanT = 0, hxx = 0, hxy = 0, hyy = 0;
        for (int j = 0, k = 0; j < p; j++) {
            for (int i = 0; i < p; i++, k++) {
                tpl[k] = i0.at(px + i, py + j);
                gx[k] = i0.gradX(px + i, py + j);
                gy[k] = i0.gradY(px + i, py + j);
                meanT += tpl[k];
                hxx += gx[k] * gx[k];
                hxy += gx[k] * gy[k];
                hyy += gy[k] * gy[k];
            }
        }
        meanT /= m;
        double det = hxx * hyy - hxy * hxy;
        if (det < MIN_DET) return;   // flat patch: nothing to lock on to; keep the guess
        boolean norm = settings.meanNormalise();
        double prevErr = patchError(i1, px, py, p, uv, tpl, meanT, norm, null, null, null);
        double[] b = new double[2];
        for (int it = 0; it < settings.iterations(); it++) {
            patchError(i1, px, py, p, uv, tpl, meanT, norm, gx, gy, b);
            double du = (hyy * b[0] - hxy * b[1]) / det;
            double dv = (hxx * b[1] - hxy * b[0]) / det;
            double nu = uv[0] - du, nv = uv[1] - dv;
            double[] trial = {nu, nv};
            double err = patchError(i1, px, py, p, trial, tpl, meanT, norm, null, null, null);
            if (!(err < prevErr)) break;          // never accept a step that matches worse
            uv[0] = nu;
            uv[1] = nv;
            prevErr = err;
            if (du * du + dv * dv < STOP_STEP * STOP_STEP) break;
        }
    }

    /**
     * Sum of squared (optionally mean-normalised) differences between the warped target and the
     * template. When {@code b} is given, also accumulates the Gauss-Newton right-hand side
     * {@code sum(grad T * e)} with {@code e = warped - template}.
     */
    private static double patchError(LumaImage i1, int px, int py, int p, double[] uv,
                                     float[] tpl, double meanT, boolean norm,
                                     float[] gx, float[] gy, double[] b) {
        int m = p * p;
        double meanW = 0;
        float[] warped = new float[m];
        for (int j = 0, k = 0; j < p; j++) {
            for (int i = 0; i < p; i++, k++) {
                warped[k] = i1.sample(px + i + uv[0], py + j + uv[1]);
                meanW += warped[k];
            }
        }
        meanW /= m;
        double offset = norm ? meanW - meanT : 0;
        double sse = 0;
        if (b != null) { b[0] = 0; b[1] = 0; }
        for (int k = 0; k < m; k++) {
            double e = warped[k] - tpl[k] - offset;
            sse += e * e;
            if (b != null) {
                b[0] += gx[k] * e;
                b[1] += gy[k] * e;
            }
        }
        return sse;
    }

    /** Patch origins covering [0, size) with the last patch flush to the far edge. */
    private static int[] grid(int size, int patch, int stride) {
        int last = Math.max(0, size - patch);
        int count = last / stride + 1 + (last % stride == 0 ? 0 : 1);
        int[] out = new int[count];
        for (int i = 0; i < count; i++) out[i] = Math.min(last, i * stride);
        return out;
    }
}
