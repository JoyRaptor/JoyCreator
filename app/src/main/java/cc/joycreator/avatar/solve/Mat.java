package cc.joycreator.avatar.solve;

/**
 * A 2x3 affine matrix as {@code double[6]} = [a b c d e f]: x' = a x + c y + e, y' = b x + d y + f.
 * Line for line the pilot's {@code AL.M} (tools/avatarlab/al-solve.js), so the solver ports without re-deriving anything.
 */
public final class Mat {
    private Mat() {}

    public static double[] id() { return new double[] {1, 0, 0, 1, 0, 0}; }

    public static double[] mul(double[] m, double[] n) {
        return new double[] {
            m[0] * n[0] + m[2] * n[1], m[1] * n[0] + m[3] * n[1],
            m[0] * n[2] + m[2] * n[3], m[1] * n[2] + m[3] * n[3],
            m[0] * n[4] + m[2] * n[5] + m[4], m[1] * n[4] + m[3] * n[5] + m[5]};
    }

    /** Translate, rotate (radians) and scale about a pivot. */
    public static double[] trs(double tx, double ty, double rot, double sx, double sy, double px, double py) {
        double c = Math.cos(rot), s = Math.sin(rot), a = c * sx, b = s * sx, c2 = -s * sy, d = c * sy;
        return new double[] {a, b, c2, d, px - (a * px + c2 * py) + tx, py - (b * px + d * py) + ty};
    }

    public static double[] flipX(double ax) { return new double[] {-1, 0, 0, 1, 2 * ax, 0}; }

    public static double[] ap(double[] m, double x, double y) {
        return new double[] {m[0] * x + m[2] * y + m[4], m[1] * x + m[3] * y + m[5]};
    }

    public static double[] inv(double[] m) {
        double det = m[0] * m[3] - m[1] * m[2];
        if (det == 0) det = 1e-9;                       // (the pilot's `|| 1e-9`)
        return new double[] {m[3] / det, -m[1] / det, -m[2] / det, m[0] / det,
            (m[2] * m[5] - m[3] * m[4]) / det, (m[1] * m[4] - m[0] * m[5]) / det};
    }

    /** The direction a drawing-space angle points once a matrix has been applied (a mirror turns it over). */
    public static double wAng(double[] m, double a) {
        double cs = Math.cos(a), sn = Math.sin(a);
        return Math.atan2(m[1] * cs + m[3] * sn, m[0] * cs + m[2] * sn);
    }
}
