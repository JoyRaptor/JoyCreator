package com.fadcam.ui.faditor.avatar.face;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * FACE-1 face pins (the owner's idea, 11-22): any of the face's 478 points as a moving signal, so a
 * pin on the character can follow, say, the corner of your mouth. A point's movement is measured
 * from your neutral face with the head's own turn, tilt, nod, place and distance taken out: the
 * head's rigid frame is fitted each frame to a set of points that do not move with expression
 * (forehead, nose bridge, eye corners, cheekbones, temples), by the best rotation (Horn's
 * quaternion method), and every point is read in that frame. So turning your head moves the
 * character's head (the head signals) and leaves its pins alone; smiling moves the pin.
 *
 * <p>Points come in as x, y, z per point, 0..1 of the upright picture's width (y already scaled to
 * width units by the caller's aspect). Offsets come out in units of the face's own size (the
 * spread of the steady points), x + toward the screen's right like a reflection, y + down.
 * Pure Java; the web pilot's {@code al-face.js} is the same, line for line.</p>
 */
public final class FacePoints {

    private FacePoints() {}

    public static final int COUNT = 478;

    /** Points that hold still while the face makes expressions: the head's own frame comes from these. */
    static final int[] STEADY = {10, 151, 9, 8, 168, 6, 197, 195, 5, 4, 1, 33, 263, 133, 362, 234, 454, 127, 356,
            21, 251, 54, 284};

    /**
     * The face map's named points: {mesh index, the person's side (1 right, -1 left, 0 middle), name}.
     * Sides are the person's own; a map drawn like a mirror shows their right on the screen's right.
     */
    public static final Object[][] NAMED = {
            {61, 1, "mouth corner"}, {291, -1, "mouth corner"},
            {0, 0, "upper lip"}, {17, 0, "lower lip"}, {13, 0, "upper lip, inside"}, {14, 0, "lower lip, inside"},
            {37, 1, "upper lip side"}, {267, -1, "upper lip side"}, {84, 1, "lower lip side"}, {314, -1, "lower lip side"},
            {159, 1, "upper lid"}, {145, 1, "lower lid"}, {386, -1, "upper lid"}, {374, -1, "lower lid"},
            {107, 1, "brow, inner"}, {105, 1, "brow, middle"}, {70, 1, "brow, outer"},
            {336, -1, "brow, inner"}, {334, -1, "brow, middle"}, {300, -1, "brow, outer"},
            {98, 1, "nostril"}, {327, -1, "nostril"}, {1, 0, "nose tip"},
            {205, 1, "cheek"}, {425, -1, "cheek"}, {152, 0, "chin"}, {172, 1, "jaw corner"}, {397, -1, "jaw corner"},
    };

    /**
     * The points in the head's own frame: centred on the steady points, divided by their spread.
     * {@code xyz} is x, y, z per point (y in width units). Returns a new array, or null if too few points.
     */
    @Nullable
    public static float[] normalize(@NonNull float[] xyz) {
        int n = xyz.length / 3;
        if (n < COUNT - 10) return null;
        double cx = 0, cy = 0, cz = 0;
        for (int i : STEADY) { cx += xyz[i * 3]; cy += xyz[i * 3 + 1]; cz += xyz[i * 3 + 2]; }
        cx /= STEADY.length; cy /= STEADY.length; cz /= STEADY.length;
        double ss = 0;
        for (int i : STEADY) {
            double dx = xyz[i * 3] - cx, dy = xyz[i * 3 + 1] - cy, dz = xyz[i * 3 + 2] - cz;
            ss += dx * dx + dy * dy + dz * dz;
        }
        double s = Math.sqrt(ss / STEADY.length);
        if (s < 1e-6) return null;
        float[] out = new float[n * 3];
        for (int i = 0; i < n; i++) {
            out[i * 3] = (float) ((xyz[i * 3] - cx) / s);
            out[i * 3 + 1] = (float) ((xyz[i * 3 + 1] - cy) / s);
            out[i * 3 + 2] = (float) ((xyz[i * 3 + 2] - cz) / s);
        }
        return out;
    }

    /**
     * The movement of the chosen points from the neutral face, in the head's own frame.
     * {@code rest} and {@code now} are {@link #normalize}d. {@code side} turns the picture's x into the
     * screen's: -1 for a reflection of an unmirrored front-camera picture (the default), +1 otherwise.
     * Returns dx, dy per chosen point (face-size units), or null when either face is missing.
     */
    @Nullable
    public static float[] offsets(@Nullable float[] rest, @Nullable float[] now, @NonNull int[] chosen, int side) {
        if (rest == null || now == null || rest.length != now.length) return null;
        double[] r = rotation(rest, now); // rest -> now
        float[] out = new float[chosen.length * 2];
        for (int k = 0; k < chosen.length; k++) {
            int i = chosen[k];
            if (i < 0 || i * 3 + 2 >= now.length) continue;
            double x = now[i * 3], y = now[i * 3 + 1], z = now[i * 3 + 2];
            // back into the neutral face's frame: R^T * now
            double ux = r[0] * x + r[3] * y + r[6] * z;
            double uy = r[1] * x + r[4] * y + r[7] * z;
            out[k * 2] = (float) ((ux - rest[i * 3]) * side);
            out[k * 2 + 1] = (float) (uy - rest[i * 3 + 1]);
        }
        return out;
    }

    /**
     * The rotation (row-major 3x3) that best carries the steady points of {@code a} onto {@code b}
     * (both already centred and scaled): Horn's closed form, the top eigenvector of a 4x4 matrix.
     */
    @NonNull
    static double[] rotation(@NonNull float[] a, @NonNull float[] b) {
        double sxx = 0, sxy = 0, sxz = 0, syx = 0, syy = 0, syz = 0, szx = 0, szy = 0, szz = 0;
        for (int i : STEADY) {
            double ax = a[i * 3], ay = a[i * 3 + 1], az = a[i * 3 + 2];
            double bx = b[i * 3], by = b[i * 3 + 1], bz = b[i * 3 + 2];
            sxx += ax * bx; sxy += ax * by; sxz += ax * bz;
            syx += ay * bx; syy += ay * by; syz += ay * bz;
            szx += az * bx; szy += az * by; szz += az * bz;
        }
        double[][] n = {
                {sxx + syy + szz, syz - szy, szx - sxz, sxy - syx},
                {syz - szy, sxx - syy - szz, sxy + syx, szx + sxz},
                {szx - sxz, sxy + syx, -sxx + syy - szz, syz + szy},
                {sxy - syx, szx + sxz, syz + szy, -sxx - syy + szz}};
        double[] q = topEigenvector(n);
        double w = q[0], x = q[1], y = q[2], z = q[3];
        return new double[]{
                1 - 2 * (y * y + z * z), 2 * (x * y - w * z), 2 * (x * z + w * y),
                2 * (x * y + w * z), 1 - 2 * (x * x + z * z), 2 * (y * z - w * x),
                2 * (x * z - w * y), 2 * (y * z + w * x), 1 - 2 * (x * x + y * y)};
    }

    /** Cyclic Jacobi on a symmetric 4x4; the unit eigenvector of the largest eigenvalue. */
    @NonNull
    static double[] topEigenvector(@NonNull double[][] m) {
        double[][] a = new double[4][4], v = new double[4][4];
        for (int i = 0; i < 4; i++) { System.arraycopy(m[i], 0, a[i], 0, 4); v[i][i] = 1; }
        for (int sweep = 0; sweep < 60; sweep++) {
            double off = 0;
            for (int p = 0; p < 4; p++) for (int q = p + 1; q < 4; q++) off += a[p][q] * a[p][q];
            if (off < 1e-22) break;
            for (int p = 0; p < 4; p++) {
                for (int q = p + 1; q < 4; q++) {
                    if (Math.abs(a[p][q]) < 1e-30) continue;
                    double theta = (a[q][q] - a[p][p]) / (2 * a[p][q]);
                    double t = Math.signum(theta) / (Math.abs(theta) + Math.sqrt(theta * theta + 1));
                    if (theta == 0) t = 1;
                    double c = 1 / Math.sqrt(t * t + 1), s = t * c;
                    for (int k = 0; k < 4; k++) {
                        double akp = a[k][p], akq = a[k][q];
                        a[k][p] = c * akp - s * akq;
                        a[k][q] = s * akp + c * akq;
                    }
                    for (int k = 0; k < 4; k++) {
                        double apk = a[p][k], aqk = a[q][k];
                        a[p][k] = c * apk - s * aqk;
                        a[q][k] = s * apk + c * aqk;
                    }
                    for (int k = 0; k < 4; k++) {
                        double vkp = v[k][p], vkq = v[k][q];
                        v[k][p] = c * vkp - s * vkq;
                        v[k][q] = s * vkp + c * vkq;
                    }
                }
            }
        }
        int best = 0;
        for (int i = 1; i < 4; i++) if (a[i][i] > a[best][best]) best = i;
        double[] e = {v[0][best], v[1][best], v[2][best], v[3][best]};
        double len = Math.sqrt(e[0] * e[0] + e[1] * e[1] + e[2] * e[2] + e[3] * e[3]);
        for (int i = 0; i < 4; i++) e[i] /= len;
        return e;
    }
}
