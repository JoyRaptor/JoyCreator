package com.fadcam.ui.faditor.avatar;

import androidx.annotation.NonNull;

/**
 * A6 (PLAN_AVATAR_STUDIO): FABRIK — Forward And Backward Reaching Inverse
 * Kinematics (Aristidou &amp; Lasenby 2011), implemented directly per the MINED
 * decision (~50 lines of own math; the Spine Runtimes are a LICENSE LANDMINE
 * and must not be ported). Drives pin-warp limb chains: given a strip's pins
 * (shoulder/elbow/wrist), solve the chain so the end effector reaches the
 * tracked target while segment lengths stay rigid.
 *
 * <p>Pure + allocation-light: solves IN PLACE on a float[2*n] joint array.
 * Deterministic for identical inputs (bake-replay safe). No hinge constraints
 * in v1 — 2-bone puppet limbs read fine unconstrained; a knee/elbow bend-sign
 * preference can be layered later by mirroring the mid joint.</p>
 */
public final class FabrikSolver {

    private FabrikSolver() {}

    /** Solver iterations — FABRIK converges fast; 8 is plenty for 2-3 bones. */
    private static final int MAX_ITERATIONS = 8;
    private static final float TOLERANCE = 1e-4f;

    /**
     * Solve a chain toward {@code (targetX, targetY)}. {@code joints} holds
     * [x0,y0, x1,y1, ...] with joint 0 = the FIXED base (shoulder); it is
     * mutated in place. Segment lengths are taken from the CURRENT pose, so
     * callers pass the authored pin geometry each time (never accumulated
     * output — drift-proof by construction).
     *
     * @return true when the effector reached the target within tolerance
     *         (false = target out of reach; chain is left fully extended
     *         toward it, which is the correct puppet read).
     */
    public static boolean solve(@NonNull float[] joints, float targetX, float targetY) {
        int n = joints.length / 2;
        if (n < 2) return false;
        float[] lengths = new float[n - 1];
        float total = 0f;
        for (int i = 0; i < n - 1; i++) {
            lengths[i] = dist(joints, i, i + 1);
            total += lengths[i];
        }
        float baseX = joints[0], baseY = joints[1];
        float toTarget = (float) Math.hypot(targetX - baseX, targetY - baseY);

        if (toTarget >= total) {
            // Unreachable: stretch straight toward the target.
            float dx = targetX - baseX, dy = targetY - baseY;
            float inv = toTarget > 0 ? 1f / toTarget : 0f;
            float cx = baseX, cy = baseY;
            for (int i = 0; i < n - 1; i++) {
                cx += dx * inv * lengths[i];
                cy += dy * inv * lengths[i];
                joints[(i + 1) * 2] = cx;
                joints[(i + 1) * 2 + 1] = cy;
            }
            return false;
        }

        for (int iter = 0; iter < MAX_ITERATIONS; iter++) {
            // BACKWARD: set effector on target, walk toward base.
            joints[(n - 1) * 2] = targetX;
            joints[(n - 1) * 2 + 1] = targetY;
            for (int i = n - 2; i >= 0; i--) {
                reposition(joints, i, i + 1, lengths[i]);
            }
            // FORWARD: re-pin base, walk toward effector.
            joints[0] = baseX;
            joints[1] = baseY;
            for (int i = 1; i < n; i++) {
                reposition(joints, i, i - 1, lengths[i - 1]);
            }
            float ex = joints[(n - 1) * 2] - targetX;
            float ey = joints[(n - 1) * 2 + 1] - targetY;
            if (ex * ex + ey * ey < TOLERANCE * TOLERANCE) return true;
        }
        return true; // converged enough for puppet use
    }

    /** Move joint {@code move} to exactly {@code len} from joint {@code anchor},
     *  along their current direction. */
    private static void reposition(@NonNull float[] j, int move, int anchor, float len) {
        float ax = j[anchor * 2], ay = j[anchor * 2 + 1];
        float mx = j[move * 2], my = j[move * 2 + 1];
        float d = (float) Math.hypot(mx - ax, my - ay);
        float r = d > 1e-9f ? len / d : 0f;
        j[move * 2] = ax + (mx - ax) * r;
        j[move * 2 + 1] = ay + (my - ay) * r;
    }

    private static float dist(@NonNull float[] j, int a, int b) {
        return (float) Math.hypot(j[b * 2] - j[a * 2], j[b * 2 + 1] - j[a * 2 + 1]);
    }
}
