package cc.joycreator.avatar.solve;

import java.util.Arrays;
import java.util.List;

/**
 * Bending by points, line for line from the pilot (al-solve.js): rigid moving-least-squares with an optional strength per pin
 * ({@code warpMLS}), how far each pin is from every part of a drawing measured THROUGH the drawing ({@code geoFields}), and the
 * one-piece sheet a face is bent as last ({@code sheetLattice}, {@code rigidSheet}, {@code bendBySheet}, {@code sheetHold}).
 */
final class Warps {
    private Warps() {}

    /** Distances through a drawing from each pin, on a 4 px lattice. */
    static final class Geo {
        int cell, cw, ch;
        double x, y;
        float[][] f;
        String key;
    }

    static float[] warpMLS(float[] src, List<double[]> P, List<double[]> Q, Geo geo, double[] K) {
        float[] out = new float[src.length];
        int n = P.size(), live = n;
        if (K != null) { live = 0; for (int k0 = 0; k0 < n; k0++) if (K[k0] > 1e-4) live++; }
        if (n == 0 || live == 0) { System.arraycopy(src, 0, out, 0, src.length); return out; }
        double[] W = new double[n];
        for (int i = 0; i < src.length; i += 2) {
            double x = src[i], y = src[i + 1], sw = 0, px = 0, py = 0, qx = 0, qy = 0, w;
            int gi = -1;
            if (geo != null) gi = (int) (Limbs.clamp(Math.floor((y - geo.y) / geo.cell), 0, geo.ch - 1) * geo.cw + Limbs.clamp(Math.floor((x - geo.x) / geo.cell), 0, geo.cw - 1));
            for (int j = 0; j < n; j++) {
                double[] pj = P.get(j), qj = Q.get(j);
                double dx = pj[0] - x, dy = pj[1] - y, d2 = dx * dx + dy * dy;
                if (geo != null) { double gd = geo.f[j][gi] * geo.cell; d2 = Math.max(d2, gd * gd); }     // the long way round, when there is one
                w = 1 / Math.pow(d2 + 1e-3, 1.15);
                if (K != null) w *= K[j] > 1e-4 ? K[j] : 0;
                W[j] = w; sw += w; px += w * pj[0]; py += w * pj[1]; qx += w * qj[0]; qy += w * qj[1];
            }
            px /= sw; py /= sw; qx /= sw; qy /= sw;
            double a = 0, b = 0;
            for (int j = 0; j < n; j++) {
                double[] pj = P.get(j), qj = Q.get(j);
                double ax = pj[0] - px, ay = pj[1] - py, bx = qj[0] - qx, by = qj[1] - qy;
                a += W[j] * (ax * bx + ay * by); b += W[j] * (ax * by - ay * bx);
            }
            double len = Limbs.hyp(a, b);
            if (len < 1e-9) { out[i] = (float) (x + (qx - px)); out[i + 1] = (float) (y + (qy - py)); continue; }
            double c = a / len, s = b / len, vx = x - px, vy = y - py;
            out[i] = (float) (qx + c * vx - s * vy); out[i + 1] = (float) (qy + s * vx + c * vy);
        }
        return out;
    }

    /** Distances from each pin through the drawing (alpha above 40 is solid; crossing empty space costs six times as much). */
    static Geo geoFields(double nx0, double ny0, int iw, int ih, byte[] alpha, List<double[]> pins) {
        int cell = 4, cw = (int) Math.ceil(iw / (double) cell), chh = (int) Math.ceil(ih / (double) cell);
        byte[] solid = new byte[cw * chh];
        for (int y = 0; y < ih; y += 2) for (int x = 0; x < iw; x += 2) if ((alpha[y * iw + x] & 0xff) > 40) solid[(y / cell) * cw + (x / cell)] = 1;
        Geo g = new Geo();
        g.cell = cell; g.cw = cw; g.ch = chh; g.x = nx0; g.y = ny0;
        g.f = new float[pins.size()][];
        for (int pi = 0; pi < pins.size(); pi++) {
            double[] p = pins.get(pi);
            float[] d = new float[cw * chh];
            Arrays.fill(d, 1e9f);
            int sx = (int) Limbs.clamp(Math.floor((p[0] - nx0) / cell), 0, cw - 1), sy = (int) Limbs.clamp(Math.floor((p[1] - ny0) / cell), 0, chh - 1);
            Heap heap = new Heap();
            d[sy * cw + sx] = 0; heap.push(0, sy * cw + sx);
            while (heap.size > 0) {
                double ev = heap.topV(); int k = heap.topK(); heap.pop();
                if (ev > d[k] + 1e-3) continue;
                int kx = k % cw, ky = k / cw;
                for (int oy = -1; oy <= 1; oy++) for (int ox = -1; ox <= 1; ox++) {
                    if (ox == 0 && oy == 0) continue;
                    int qx = kx + ox, qy = ky + oy;
                    if (qx < 0 || qy < 0 || qx >= cw || qy >= chh) continue;
                    int q = qy * cw + qx;
                    double nd = ev + (ox != 0 && oy != 0 ? 1.414 : 1) * (solid[q] != 0 ? 1 : 6);
                    if (nd < d[q] - 1e-3) { d[q] = (float) nd; heap.push(nd, q); }
                }
            }
            g.f[pi] = d;
        }
        return g;
    }

    /** The pilot's binary heap, kept the same so equal distances settle in the same order. */
    private static final class Heap {
        double[] v = new double[256];
        int[] k = new int[256];
        int size;

        void push(double val, int key) {
            if (size == v.length) { v = Arrays.copyOf(v, size * 2); k = Arrays.copyOf(k, size * 2); }
            v[size] = val; k[size] = key;
            int c = size++;
            while (c > 0) { int q = (c - 1) >> 1; if (v[q] <= v[c]) break; swap(q, c); c = q; }
        }

        double topV() { return v[0]; }

        int topK() { return k[0]; }

        void pop() {
            size--;
            if (size > 0) {
                v[0] = v[size]; k[0] = k[size];
                int c = 0;
                for (;;) {
                    int l = c * 2 + 1, r = l + 1, m = c;
                    if (l < size && v[l] < v[m]) m = l;
                    if (r < size && v[r] < v[m]) m = r;
                    if (m == c) break;
                    swap(m, c); c = m;
                }
            }
        }

        private void swap(int a, int b) { double t = v[a]; v[a] = v[b]; v[b] = t; int u = k[a]; k[a] = k[b]; k[b] = u; }
    }

    /** A close ring of points just outside a sheet's rest bounds: the sheet is held still at its edges. */
    static java.util.List<double[]> sheetHold(double[] b) {
        java.util.List<double[]> o = new java.util.ArrayList<>();
        if (b == null) return o;
        double w = b[2] - b[0], hh = b[3] - b[1], x0 = b[0] - w * 0.04, x1 = b[2] + w * 0.04, y0 = b[1] - hh * 0.04, y1 = b[3] + hh * 0.04;
        int N = 6;
        for (int i = 0; i <= N; i++) {
            double t = i / (double) N;
            o.add(new double[] {x0 + (x1 - x0) * t, y0}); o.add(new double[] {x0 + (x1 - x0) * t, y1});
            if (i > 0 && i < N) { o.add(new double[] {x0, y0 + (y1 - y0) * t}); o.add(new double[] {x1, y0 + (y1 - y0) * t}); }
        }
        return o;
    }

    /** The sheet's bend on a lattice over it: how far each lattice point moves. */
    static final class Lattice {
        double x0, y0, W, H;
        int nx, ny;
        float[] d;
    }

    static Lattice sheetLattice(double[] b, List<double[]> P, List<double[]> Q, double[] K) {
        if (b == null) return null;
        double w = b[2] - b[0], hh = b[3] - b[1], x0 = b[0] - w * 0.04, y0 = b[1] - hh * 0.04, W = w * 1.08, H = hh * 1.08;
        int nx = (int) Limbs.clamp(Math.round(W / 16), 4, 32), ny = (int) Limbs.clamp(Math.round(H / 16), 4, 32);
        float[] src = new float[(nx + 1) * (ny + 1) * 2];
        int o = 0;
        for (int j = 0; j <= ny; j++) for (int i = 0; i <= nx; i++) { src[o++] = (float) (x0 + W * i / nx); src[o++] = (float) (y0 + H * j / ny); }
        float[] dst = rigidSheet(src, P, Q, K);
        for (int i = 0; i < dst.length; i++) dst[i] -= src[i];
        Lattice L = new Lattice();
        L.x0 = x0; L.y0 = y0; L.W = W; L.H = H; L.nx = nx; L.ny = ny; L.d = dst;
        return L;
    }

    /** Rigid moving-least-squares with a closer falloff (1 / distance to the 4th). */
    static float[] rigidSheet(float[] src, List<double[]> P, List<double[]> Q, double[] K) {
        float[] out = new float[src.length];
        int n = P.size();
        float[] W = new float[n];
        for (int i = 0; i < src.length; i += 2) {
            double x = src[i], y = src[i + 1], sw = 0, px = 0, py = 0, qx = 0, qy = 0, w;
            for (int j = 0; j < n; j++) {
                double[] pj = P.get(j), qj = Q.get(j);
                double dx = pj[0] - x, dy = pj[1] - y, iv = 1 / (dx * dx + dy * dy + 1);
                w = iv * iv * (K[j] > 1e-4 ? K[j] : 0);
                W[j] = (float) w; sw += w; px += w * pj[0]; py += w * pj[1]; qx += w * qj[0]; qy += w * qj[1];
            }
            if (!(sw > 0)) { out[i] = (float) x; out[i + 1] = (float) y; continue; }
            px /= sw; py /= sw; qx /= sw; qy /= sw;
            double a = 0, b = 0;
            for (int j = 0; j < n; j++) {
                double[] pj = P.get(j), qj = Q.get(j);
                double ax = pj[0] - px, ay = pj[1] - py, bx = qj[0] - qx, by = qj[1] - qy;
                a += W[j] * (ax * bx + ay * by); b += W[j] * (ax * by - ay * bx);
            }
            double len = Limbs.hyp(a, b);
            if (len < 1e-9) { out[i] = (float) (x + (qx - px)); out[i + 1] = (float) (y + (qy - py)); continue; }
            double c = a / len, s = b / len, vx = x - px, vy = y - py;
            out[i] = (float) (qx + c * vx - s * vy); out[i + 1] = (float) (qy + s * vx + c * vy);
        }
        return out;
    }

    /** Bend drawn points (on the stage) by the sheet's lattice, in the sheet's own frame. In place. */
    static void bendBySheet(float[] w, Lattice L, double[] CI, double[] CM) {
        float[] D = L.d;
        int r = L.nx + 1;
        double sx = L.nx / L.W, sy = L.ny / L.H;
        for (int i = 0; i < w.length; i += 2) {
            double lx = CI[0] * w[i] + CI[2] * w[i + 1] + CI[4], ly = CI[1] * w[i] + CI[3] * w[i + 1] + CI[5];
            double u = Limbs.clamp((lx - L.x0) * sx, 0, L.nx), v = Limbs.clamp((ly - L.y0) * sy, 0, L.ny);
            int ci = u < L.nx ? (int) Math.floor(u) : L.nx - 1, cj = v < L.ny ? (int) Math.floor(v) : L.ny - 1;
            double fu = u - ci, fv = v - cj;
            int a = (cj * r + ci) * 2, b = a + 2, c = a + r * 2, d = c + 2;
            double g0 = (1 - fu) * (1 - fv), g1 = fu * (1 - fv), g2 = (1 - fu) * fv, g3 = fu * fv;
            lx += D[a] * g0 + D[b] * g1 + D[c] * g2 + D[d] * g3; ly += D[a + 1] * g0 + D[b + 1] * g1 + D[c + 1] * g2 + D[d + 1] * g3;
            w[i] = (float) (CM[0] * lx + CM[2] * ly + CM[4]); w[i + 1] = (float) (CM[1] * lx + CM[3] * ly + CM[5]);
        }
    }
}
