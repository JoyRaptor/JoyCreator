package cc.joycreator.textlab;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RIVETS, placed as a sign-maker places them: the twin of {@code TL.rivetsOf} in
 * {@code tools/textlab-web/tl-glyphs.js}, sum for sum. Along a line {@code level} em in from the outline (the
 * middle of the raised frame), one at every corner, then evenly spaced in a straight line between corners,
 * never further apart than {@code gap}. A loop with no corners (an O) starts at its top. The order of the result
 * matters (each particle takes the next rivet), so the maps here keep insertion order, as JavaScript's do.
 */
final class TlRivets {

    private TlRivets() {}

    /** [x, y] in the piece's cell, em. */
    @NonNull
    static List<double[]> of(@NonNull TlGlyphs.Pic pic, double level, double gap) {
        final float[] d = pic.d;
        final int w = pic.w, h = pic.h, E = TlGlyphs.GLYPH_EM;
        // 1. the line, as closed loops (marching squares over the distance picture)
        Map<String, double[]> at = new LinkedHashMap<>();
        Map<String, List<String>> nb = new LinkedHashMap<>();
        int[][][] SEG = new int[16][][];
        SEG[1] = new int[][]{{3, 0}};
        SEG[2] = new int[][]{{0, 1}};
        SEG[3] = new int[][]{{3, 1}};
        SEG[4] = new int[][]{{1, 2}};
        SEG[6] = new int[][]{{0, 2}};
        SEG[7] = new int[][]{{3, 2}};
        SEG[8] = new int[][]{{2, 3}};
        SEG[9] = new int[][]{{0, 2}};
        SEG[11] = new int[][]{{1, 2}};
        SEG[12] = new int[][]{{3, 1}};
        SEG[13] = new int[][]{{0, 1}};
        SEG[14] = new int[][]{{3, 0}};
        for (int y = 0; y < h - 1; y++) {
            for (int x = 0; x < w - 1; x++) {
                double f00 = d[y * w + x] - level, f10 = d[y * w + x + 1] - level;
                double f11 = d[(y + 1) * w + x + 1] - level, f01 = d[(y + 1) * w + x] - level;
                int c = (f00 > 0 ? 1 : 0) | (f10 > 0 ? 2 : 0) | (f11 > 0 ? 4 : 0) | (f01 > 0 ? 8 : 0);
                if (c == 0 || c == 15) continue;
                int[][] segs = SEG[c];
                if (c == 5 || c == 10) {
                    boolean mid = (f00 + f10 + f11 + f01) / 4 > 0;
                    segs = (c == 5) == mid ? new int[][]{{0, 1}, {2, 3}} : new int[][]{{3, 0}, {1, 2}};
                }
                for (int[] s : segs) {
                    // the edge points are made in the order the look book makes them: first a, then b
                    String a = edge(at, d, w, level, E, x, y, s[0]);
                    String b = edge(at, d, w, level, E, x, y, s[1]);
                    link(nb, a, b);
                }
            }
        }
        List<List<double[]>> loops = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String start : nb.keySet()) {
            if (seen.contains(start)) continue;
            List<double[]> loop = new ArrayList<>();
            String prev = null, cur = start;
            while (cur != null && !seen.contains(cur)) {
                seen.add(cur);
                loop.add(at.get(cur));
                List<String> ns = nb.get(cur);
                String nx = ns.get(0).equals(prev) ? (ns.size() > 1 ? ns.get(1) : null) : ns.get(0);
                prev = cur;
                cur = nx;
            }
            if (loop.size() > 8) loops.add(loop);
        }
        // 2. each loop: evenly resampled, its corners found, then the rivets
        List<double[]> out = new ArrayList<>();
        double step = 0.004, minGap = Math.max(gap * 0.45, 0.07);
        for (List<double[]> loop : loops) {
            int ln = loop.size();
            double[] L = new double[ln + 1];
            for (int i = 1; i <= ln; i++) {
                double[] a = loop.get(i - 1), b = loop.get(i % ln);
                L[i] = L[i - 1] + Math.hypot(b[0] - a[0], b[1] - a[1]);
            }
            double total = L[ln];
            if (total < 0.25) continue;   // too small a loop to hold rivets
            int n = (int) Math.max(8, Math.round(total / step));
            double[][] P = new double[n][];
            for (int i = 0, j = 0; i < n; i++) {
                double s = ((double) i / n) * total;
                while (L[j + 1] < s) j++;
                double[] a = loop.get(j), b = loop.get((j + 1) % ln);
                double t = (s - L[j]) / Math.max(L[j + 1] - L[j], 1e-9);
                P[i] = new double[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t};
            }
            int k = (int) Math.max(3, Math.round(0.035 / step));
            double[] turn = new double[n];
            for (int i = 0; i < n; i++) {
                double[] a = P[(i - k + n) % n], b = P[i], c = P[(i + k) % n];
                double ux = b[0] - a[0], uy = b[1] - a[1], vx = c[0] - b[0], vy = c[1] - b[1];
                double den = Math.hypot(ux, uy) * Math.hypot(vx, vy);
                if (den == 0) den = 1;
                turn[i] = Math.acos(Math.max(-1, Math.min(1, (ux * vx + uy * vy) / den)));
            }
            List<Integer> cand = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if (turn[i] < 0.6) continue;
                boolean peak = true;
                for (int j = 1; j <= k && peak; j++) peak = turn[i] >= turn[(i + j) % n] && turn[i] > turn[(i - j + n) % n];
                if (peak) cand.add(i);
            }
            cand.sort((a, b) -> Double.compare(turn[b], turn[a]));
            final double unit = total / n;
            List<Integer> kept = new ArrayList<>();
            for (int i : cand) {
                boolean far = true;
                for (int j : kept) {
                    double g = Math.abs(i - j) * unit;
                    if (Math.min(g, total - g) <= minGap) {
                        far = false;
                        break;
                    }
                }
                if (far) kept.add(i);
            }
            kept.sort(Integer::compare);
            if (kept.isEmpty()) {
                int top = 0;
                for (int i = 1; i < n; i++) if (P[i][1] > P[top][1] + 1e-6) top = i;
                long m = Math.max(3, Math.round(total / gap));
                for (int q = 0; q < m; q++) place(out, P, n, unit, top * unit + (q * total) / m);
            } else {
                for (int q = 0; q < kept.size(); q++) {
                    double s0 = kept.get(q) * unit;
                    double s1 = q + 1 < kept.size() ? kept.get(q + 1) * unit : kept.get(0) * unit + total;
                    long m = Math.max(1, Math.round((s1 - s0) / gap));
                    for (int r = 0; r < m; r++) place(out, P, n, unit, s0 + ((s1 - s0) * r) / m);
                }
            }
        }
        return out;
    }

    private static void place(List<double[]> out, double[][] P, int n, double unit, double s) {
        int i = (int) (((Math.round(s / unit) % n) + n) % n);
        out.add(P[i]);
    }

    /** Edge {@code e} (0 top, 1 right, 2 bottom, 3 left) of cell (x, y): its key, its point made on first use. */
    private static String edge(Map<String, double[]> at, float[] d, int w, double level, int E, int x, int y, int e) {
        switch (e) {
            case 0: return pt(at, d, w, level, E, "h" + x + "," + y, x, y, x + 1, y);
            case 1: return pt(at, d, w, level, E, "v" + (x + 1) + "," + y, x + 1, y, x + 1, y + 1);
            case 2: return pt(at, d, w, level, E, "h" + x + "," + (y + 1), x, y + 1, x + 1, y + 1);
            default: return pt(at, d, w, level, E, "v" + x + "," + y, x, y, x, y + 1);
        }
    }

    private static String pt(Map<String, double[]> at, float[] d, int w, double level, int E, String k,
                             int x0, int y0, int x1, int y1) {
        if (!at.containsKey(k)) {
            double a = d[y0 * w + x0] - level, b = d[y1 * w + x1] - level, t = a / (a - b);
            at.put(k, new double[]{(x0 + (x1 - x0) * t + 0.5) / E, (y0 + (y1 - y0) * t + 0.5) / E});
        }
        return k;
    }

    private static void link(Map<String, List<String>> nb, String a, String b) {
        if (!nb.containsKey(a)) nb.put(a, new ArrayList<>());
        if (!nb.containsKey(b)) nb.put(b, new ArrayList<>());
        nb.get(a).add(b);
        nb.get(b).add(a);
    }
}
