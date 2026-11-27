package cc.joycreator.avatar.solve;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The meshes a drawing is drawn with, line for line from the pilot (al-solve.js): a plain grid ({@code grid}), the same grid cut
 * in two along a line across a limb ({@code splitGrid}), and a drawing's outline read row by row as a turned shape
 * ({@code lathe}).
 */
final class Meshes {
    private Meshes() {}

    static final class Grid {
        float[] v, uv;
        int[] idx;
        int nx, ny;
        /** for a split mesh: the two halves */
        int[] back, front;
        String splitKey;
        Grid split;
    }

    /** One drawing's grid: 1 cell for a still drawing, finer for one that bends (dense 1), finest for a limb or pins (dense 2). */
    static Grid grid(double nx0, double ny0, int iw, int ih, int dense) {
        double cellPx = dense == 2 ? 9 : 16, cap = dense == 2 ? 46 : 20;
        int nx = dense != 0 ? (int) Limbs.clamp(Math.round(iw / cellPx), 3, cap) : 1, ny = dense != 0 ? (int) Limbs.clamp(Math.round(ih / cellPx), 3, cap) : 1;
        Grid g = new Grid();
        g.v = new float[(nx + 1) * (ny + 1) * 2];
        g.uv = new float[g.v.length];
        g.idx = new int[nx * ny * 6];
        int o = 0;
        for (int j = 0; j <= ny; j++) for (int i = 0; i <= nx; i++) {
            g.v[o] = (float) (nx0 + iw * (double) i / nx); g.v[o + 1] = (float) (ny0 + ih * (double) j / ny);
            g.uv[o] = (float) ((double) i / nx); g.uv[o + 1] = (float) ((double) j / ny);
            o += 2;
        }
        o = 0;
        for (int j = 0; j < ny; j++) for (int i = 0; i < nx; i++) {
            int p = j * (nx + 1) + i;
            g.idx[o++] = p; g.idx[o++] = p + 1; g.idx[o++] = p + nx + 1; g.idx[o++] = p + 1; g.idx[o++] = p + nx + 2; g.idx[o++] = p + nx + 1;
        }
        g.nx = nx; g.ny = ny;
        return g;
    }

    /** A limb's grid cut in two along a line across it (at = 0 shoulder .. 1 hand); the halves share the seam's vertices. */
    static Grid splitGrid(Grid g, JSONObject l, double at, boolean tipBack) {
        JSONObject A = l.optJSONObject("a"), B = l.optJSONObject("b"), C = l.optJSONObject("c");
        double ax = J.x(A), ay = J.y(A), bx = J.x(B), by = J.y(B), cx = J.x(C), cy = J.y(C);
        String key = Math.round(ax) + "," + Math.round(ay) + "," + Math.round(bx) + "," + Math.round(by) + "," + Math.round(cx) + "," + Math.round(cy) + "|" + at + "|" + tipBack;
        if (key.equals(g.splitKey)) return g.split;
        double L1 = Limbs.hyp(bx - ax, by - ay), L2 = Limbs.hyp(cx - bx, cy - by);
        if (L1 == 0) L1 = 1;
        if (L2 == 0) L2 = 1;
        double tot = L1 + L2;
        List<Double> v = new ArrayList<>(), uv = new ArrayList<>(), t = new ArrayList<>();
        for (float f : g.v) v.add((double) f);
        for (float f : g.uv) uv.add((double) f);
        int n0 = g.v.length / 2;
        for (int i = 0; i < n0; i++) {
            double x = g.v[i * 2], y = g.v[i * 2 + 1];
            double u1 = Limbs.clamp(((x - ax) * (bx - ax) + (y - ay) * (by - ay)) / (L1 * L1), -0.4, 1), d1 = Limbs.hyp(x - (ax + u1 * (bx - ax)), y - (ay + u1 * (by - ay)));
            double u2 = Limbs.clamp(((x - bx) * (cx - bx) + (y - by) * (cy - by)) / (L2 * L2), 0, 1.5), d2 = Limbs.hyp(x - (bx + u2 * (cx - bx)), y - (by + u2 * (cy - by)));
            t.add((d1 <= d2 ? u1 * L1 / tot : (L1 + u2 * L2) / tot) - at);
        }
        Map<String, Integer> made = new HashMap<>();
        List<Integer> root = new ArrayList<>(), tip = new ArrayList<>();
        for (int i = 0; i < g.idx.length; i += 3) {
            int a = g.idx[i], b = g.idx[i + 1], c = g.idx[i + 2];
            boolean sa = t.get(a) >= 0, sb = t.get(b) >= 0, sc = t.get(c) >= 0;
            if (sa == sb && sb == sc) { List<Integer> to = sa ? tip : root; to.add(a); to.add(b); to.add(c); continue; }
            int lone = sa != sb && sa != sc ? a : (sb != sa && sb != sc ? b : c), o1 = lone == a ? b : a, o2 = lone == c ? b : c;
            int m1 = mid(lone, o1, v, uv, t, made), m2 = mid(lone, o2, v, uv, t, made);
            List<Integer> lt = t.get(lone) >= 0 ? tip : root, ot = t.get(lone) >= 0 ? root : tip;
            lt.add(lone); lt.add(m1); lt.add(m2);
            ot.add(o1); ot.add(o2); ot.add(m2); ot.add(o1); ot.add(m2); ot.add(m1);
        }
        Grid sp = new Grid();
        sp.v = toF(v); sp.uv = toF(uv); sp.idx = g.idx; sp.nx = g.nx; sp.ny = g.ny;
        sp.back = toI(tipBack ? tip : root); sp.front = toI(tipBack ? root : tip);
        g.splitKey = key; g.split = sp;
        return sp;
    }

    private static int mid(int p, int q, List<Double> v, List<Double> uv, List<Double> t, Map<String, Integer> made) {
        String k = p < q ? p + "_" + q : q + "_" + p;
        Integer had = made.get(k);
        if (had != null) return had;
        double f = t.get(p) / (t.get(p) - t.get(q));
        int id = v.size() / 2;
        v.add(v.get(p * 2) + (v.get(q * 2) - v.get(p * 2)) * f); v.add(v.get(p * 2 + 1) + (v.get(q * 2 + 1) - v.get(p * 2 + 1)) * f);
        uv.add(uv.get(p * 2) + (uv.get(q * 2) - uv.get(p * 2)) * f); uv.add(uv.get(p * 2 + 1) + (uv.get(q * 2 + 1) - uv.get(p * 2 + 1)) * f);
        t.add(0.0);
        made.put(k, id);
        return id;
    }

    private static float[] toF(List<Double> l) { float[] o = new float[l.size()]; for (int i = 0; i < o.length; i++) o[i] = (float) (double) l.get(i); return o; }

    private static int[] toI(List<Integer> l) { int[] o = new int[l.size()]; for (int i = 0; i < o.length; i++) o[i] = l.get(i); return o; }

    /** A drawing's outline, row by row, as the radius of a solid at that height (an egg, a pear, a snout). */
    static final class Lathe {
        double y0, Rmax;
        int n;
        float[] cx, R;
    }

    static Lathe lathe(double nx0, double ny0, int iw, int ih, byte[] al) {
        float[] cx = new float[ih], R = new float[ih];
        double Rmax = 1;
        int first = -1, last = -1;
        for (int y = 0; y < ih; y++) {
            int l = -1, r = -1;
            for (int x = 0; x < iw; x++) if ((al[y * iw + x] & 0xff) > 60) { if (l < 0) l = x; r = x; }
            if (l >= 0) { cx[y] = (float) (nx0 + (l + r + 1) / 2.0); R[y] = (float) Math.max(3, (r - l + 1) / 2.0); if (first < 0) first = y; last = y; if (R[y] > Rmax) Rmax = R[y]; }
        }
        if (first < 0) return null;
        for (int y = 0; y < ih; y++) {
            if (y < first) { cx[y] = cx[first]; R[y] = R[first]; }
            else if (y > last) { cx[y] = cx[last]; R[y] = R[last]; }
            else if (R[y] == 0) { cx[y] = cx[y - 1]; R[y] = R[y - 1]; }
        }
        float[] R2 = new float[ih], c2 = new float[ih];
        for (int y = 0; y < ih; y++) {
            double s = 0, sc = 0; int m = 0;
            for (int k = -4; k <= 4; k++) { int q = y + k; if (q < 0 || q >= ih) continue; s += R[q]; sc += cx[q]; m++; }
            R2[y] = (float) (s / m); c2[y] = (float) (sc / m);
        }
        Lathe L = new Lathe();
        L.y0 = ny0; L.n = ih; L.cx = c2; L.R = R2; L.Rmax = Rmax;
        return L;
    }
}
