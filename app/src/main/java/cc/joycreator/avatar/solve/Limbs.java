package cc.joycreator.avatar.solve;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Limbs, line for line from the pilot (al-solve.js): the two-bone reach with a rounded elbow ({@code solveLimb},
 * {@code warpLimb}), the foot bar that keeps a foot level and rolls it ({@code warpFoot}), and the rubber hose
 * ({@code solveHose}, {@code warpHose}). A limb record is {@code { a, b, c }} (shoulder, elbow, hand) and its settings.
 */
final class Limbs {
    private Limbs() {}

    static double hyp(double x, double y) { return Math.sqrt(x * x + y * y); }

    static double clamp(double v, double a, double b) { return v < a ? a : v > b ? b : v; }

    static double smooth(double t) { t = clamp(t, 0, 1); return t * t * (3 - 2 * t); }

    /** The solved bones: stretch, the two turns, the elbow and wrist. */
    static final class Bend {
        double st, r1, r2;
        double[] e, w;
    }

    static Bend solveLimb(JSONObject l, double Tx, double Ty) {
        JSONObject A = l.optJSONObject("a"), B = l.optJSONObject("b"), C = l.optJSONObject("c");
        double ax = J.x(A), ay = J.y(A), bx = J.x(B), by = J.y(B), cx = J.x(C), cy = J.y(C);
        double L1 = hyp(bx - ax, by - ay), L2 = hyp(cx - bx, cy - by);
        if (L1 < 1 || L2 < 1) return null;
        double dx = Tx - ax, dy = Ty - ay, d = hyp(dx, dy);
        if (d < 0.001) { dx = cx - ax; dy = cy - ay; d = hyp(dx, dy); if (d == 0) d = 1; }
        double ux = dx / d, uy = dy / d, st = 1, full = L1 + L2;
        double stMax = 1 + J.d(l, "stretch", 0.12);
        if (d > full) st = Math.min(d / full, stMax);
        double l1 = L1 * st, l2 = L2 * st;
        double dd = clamp(d, Math.abs(l1 - l2) + 0.001, l1 + l2 - 0.001);
        double An = Math.acos(clamp((l1 * l1 + dd * dd - l2 * l2) / (2 * l1 * dd), -1, 1));
        double cr = (cx - ax) * (by - ay) - (cy - ay) * (bx - ax), side;
        if (Math.abs(cr) > 0.09 * L1 * L2) side = cr > 0 ? 1 : -1;
        else {                                       // a straight limb bends the way it was told to: elbows out and down, knees forward
            JSONArray out = l.optJSONArray("out");
            double base = Math.atan2(uy, ux), ox = out != null ? out.optDouble(0, 0) : 0, oy = out != null ? out.optDouble(1, 0) : 1;
            side = (Math.cos(base + An) * ox + Math.sin(base + An) * oy) >= (Math.cos(base - An) * ox + Math.sin(base - An) * oy) ? 1 : -1;
        }
        if (J.t(l, "flip")) side = -side;
        double th = Math.atan2(uy, ux) + side * An;
        double ex = ax + Math.cos(th) * l1, ey = ay + Math.sin(th) * l1;
        double hx = Tx - ex, hy = Ty - ey, hd = hyp(hx, hy);
        if (hd == 0) hd = 1;
        double wx = ex + hx / hd * l2, wy = ey + hy / hd * l2;
        Bend k = new Bend();
        k.st = st;
        k.r1 = Math.atan2(ey - ay, ex - ax) - Math.atan2(by - ay, bx - ax);
        k.r2 = Math.atan2(wy - ey, wx - ex) - Math.atan2(cy - by, cx - bx);
        k.e = new double[] {ex, ey};
        k.w = new double[] {wx, wy};
        return k;
    }

    static float[] warpLimb(float[] src, JSONObject l, Bend k) {
        float[] out = new float[src.length];
        JSONObject A = l.optJSONObject("a"), B = l.optJSONObject("b"), C = l.optJSONObject("c");
        double ax = J.x(A), ay = J.y(A), bx = J.x(B), by = J.y(B), cx = J.x(C), cy = J.y(C);
        double L1 = hyp(bx - ax, by - ay), L2 = hyp(cx - bx, cy - by);
        double d1x = (bx - ax) / L1, d1y = (by - ay) / L1, d2x = (cx - bx) / L2, d2y = (cy - by) / L2;
        double R = J.d(l, "soft", 0.3) * Math.min(L1, L2) + 0.5;
        double c1 = Math.cos(k.r1) * k.st, s1 = Math.sin(k.r1) * k.st, c2 = Math.cos(k.r2) * k.st, s2 = Math.sin(k.r2) * k.st;
        double dr = k.r2 - k.r1;
        while (dr > Math.PI) dr -= 2 * Math.PI;
        while (dr < -Math.PI) dr += 2 * Math.PI;
        for (int i = 0; i < src.length; i += 2) {
            double x = src[i], y = src[i + 1];
            double qx = x - bx, qy = y - by;
            double s = ((qx * d1x + qy * d1y) + (qx * d2x + qy * d2y)) * 0.5;
            double w = smooth((s + R) / (2 * R));
            if (w <= 0) { out[i] = (float) (ax + c1 * (x - ax) - s1 * (y - ay)); out[i + 1] = (float) (ay + s1 * (x - ax) + c1 * (y - ay)); continue; }
            if (w >= 1) { out[i] = (float) (k.e[0] + c2 * qx - s2 * qy); out[i + 1] = (float) (k.e[1] + s2 * qx + c2 * qy); continue; }
            double an = k.r1 + dr * w, ca = Math.cos(an) * k.st, sa = Math.sin(an) * k.st;
            out[i] = (float) (k.e[0] + ca * qx - sa * qy); out[i + 1] = (float) (k.e[1] + sa * qx + ca * qy);
        }
        return out;
    }

    private static double dseg(double x, double y, double x0, double y0, double x1, double y1) {
        double dx = x1 - x0, dy = y1 - y0, L2 = dx * dx + dy * dy;
        if (L2 == 0) L2 = 1;
        double t = clamp(((x - x0) * dx + (y - y0) * dy) / L2, 0, 1);
        return hyp(x - x0 - dx * t, y - y0 - dy * t);
    }

    /** A foot with a foot bar stays level and rolls heel to toe. Writes into {@code out}. */
    static float[] warpFoot(float[] src, float[] out, JSONObject l, Bend k, double roll) {
        JSONObject f = l.optJSONObject("foot"), C = l.optJSONObject("c"), B = l.optJSONObject("b"), heel = f.optJSONObject("heel"), toe = f.optJSONObject("toe");
        double cx = J.x(C), cy = J.y(C), bx = J.x(B), by = J.y(B), hx = J.x(heel), hy = J.y(heel), tx = J.x(toe), ty = J.y(toe);
        double Rf = Math.max(3, hyp(cx - bx, cy - by) * 0.16), ang = -roll * (tx >= hx ? 1 : -1), cs = Math.cos(ang), sn = Math.sin(ang), wx = k.w[0], wy = k.w[1];
        for (int i = 0; i < src.length; i += 2) {
            double x = src[i], y = src[i + 1], w = smooth((dseg(x, y, bx, by, cx, cy) - dseg(x, y, hx, hy, tx, ty)) / (2 * Rf) + 0.5);
            if (w <= 0) continue;
            double rx = x - cx, ry = y - cy, fx = wx + cs * rx - sn * ry, fy = wy + sn * rx + cs * ry;
            out[i] = (float) (out[i] + (fx - out[i]) * w); out[i + 1] = (float) (out[i + 1] + (fy - out[i + 1]) * w);
        }
        return out;
    }

    /** A rubber hose laid on a curve from the shoulder to wherever the hand is. */
    static final class Hose {
        double[] C, T, mid;
        double L0, ex, ey, t, ang;
    }

    static Hose solveHose(JSONObject l, double Tx, double Ty, double t) {
        JSONObject A = l.optJSONObject("a"), C = l.optJSONObject("c"), B = l.optJSONObject("b");
        double ax = J.x(A), ay = J.y(A), ex = J.x(C) - ax, ey = J.y(C) - ay, L0 = hyp(ex, ey);
        if (L0 < 1) return null;
        ex /= L0; ey /= L0;
        double dx = Tx - ax, dy = Ty - ay, d = hyp(dx, dy);
        if (d < 0.001) { dx = ex; dy = ey; d = 0.001; }
        double ux = dx / d, uy = dy / d, maxL = L0 * (1 + J.d(l, "stretch", 0.12));
        if (d > maxL) d = maxL;
        double give = J.d(l, "give", 0), arc = d >= L0 ? d : d + (L0 - d) * (1 - give);
        double h = d * Math.sqrt(Math.max(0, 0.375 * (arc / d - 1)));
        h = Math.max(Math.min(h, arc * 0.7), J.z(l, "bow") * d);
        JSONArray out = l.optJSONArray("out");
        double nx = -uy, ny = ux, rest = ex * (J.y(B) - ay) - ey * (J.x(B) - ax), side;
        if (Math.abs(rest) > 0.04 * L0) side = rest > 0 ? 1 : -1;
        else side = clamp((nx * (out != null ? out.optDouble(0, 0) : 0) + ny * (out != null ? out.optDouble(1, 0) : 1)) * 4, -1, 1);
        if (J.t(l, "flip")) side = -side;
        double Ex = ax + ux * d, Ey = ay + uy * d, Cx = ax + ux * d / 2 + nx * side * 2 * h, Cy = ay + uy * d / 2 + ny * side * 2 * h;
        Hose k = new Hose();
        k.C = new double[] {Cx, Cy}; k.T = new double[] {Ex, Ey}; k.L0 = L0; k.ex = ex; k.ey = ey; k.t = t;
        k.mid = new double[] {0.25 * ax + 0.5 * Cx + 0.25 * Ex, 0.25 * ay + 0.5 * Cy + 0.25 * Ey};
        k.ang = Math.atan2(Ey - Cy, Ex - Cx);
        return k;
    }

    static float[] warpHose(float[] src, JSONObject l, Hose k) {
        float[] out = new float[src.length];
        JSONObject A = l.optJSONObject("a");
        double ax = J.x(A), ay = J.y(A), wv = J.z(l, "wave"), wn = J.d(l, "waves", 1.5) * 6.2832, wt = J.d(l, "waveRate", 1.3) * 6.2832 * k.t;
        double thick = J.t(l, "thick") ? l.optDouble("thick", 1) : 1;
        for (int i = 0; i < src.length; i += 2) {
            double px = src[i] - ax, py = src[i + 1] - ay, u = (px * k.ex + py * k.ey) / k.L0, w = -px * k.ey + py * k.ex, uc = u < 0 ? 0 : u > 1 ? 1 : u, iu = 1 - uc;
            double bx = iu * iu * ax + 2 * uc * iu * k.C[0] + uc * uc * k.T[0], by = iu * iu * ay + 2 * uc * iu * k.C[1] + uc * uc * k.T[1];
            double tx = iu * (k.C[0] - ax) + uc * (k.T[0] - k.C[0]), ty = iu * (k.C[1] - ay) + uc * (k.T[1] - k.C[1]), tl = hyp(tx, ty);
            if (tl == 0) tl = 1;
            tx /= tl; ty /= tl;
            double off = w * thick + (wv != 0 ? wv * Math.sin(wn * uc - wt) * Math.sin(3.1416 * uc) : 0), ext = (u - uc) * k.L0;
            out[i] = (float) (bx + tx * ext - ty * off); out[i + 1] = (float) (by + ty * ext + tx * off);
        }
        return out;
    }
}
