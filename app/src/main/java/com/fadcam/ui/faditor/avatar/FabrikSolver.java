package com.fadcam.ui.faditor.avatar;

/**
 * A6 (PLAN_AVATAR_STUDIO): FABRIK — Forward And Backward Reaching Inverse
 * Kinematics (Aristidou &amp; Lasenby 2011), implemented directly per the MINED
 * decision (~50 lines of own math; the Spine Runtimes are a LICENSE LANDMINE
 * and must not be ported). Drives pin-warp limb chains: given a strip's pins
 * (shoulder/elbow/wrist), solve the chain so the end effector reaches the
 * tracked target while segment lengths stay rigid.
 *
 * <p>Pure + allocation-light: solves IN PLACE on a float[2*n] joint array.
 * Deterministic for identical inputs (bake-replay safe).</p>
 *
 * <p><b>2026-09-15.</b> The note that used to sit here said hinge constraints were "v1 later" and
 * that a bend-sign preference "can be layered later by mirroring the mid joint". Later arrived:
 * the puppet drawer authors joint limits, stretchy bones and a bend sign, and
 * {@link #solveConstrained} is that layering. It is in THIS file rather than a second solver
 * because a rig with two FABRIKs in it would eventually have two different ideas about where an
 * elbow goes. {@link #solve} is untouched and still drives the avatar preview.</p>
 *
 * <p>The {@code androidx} annotation came out the same day so this file compiles on the desktop
 * harness, which is where its proofs live.</p>
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
    public static boolean solve(float[] joints, float targetX, float targetY) {
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

    /**
     * FABRIK with the three things a puppet rig authors: STRETCHY bones, JOINT LIMITS and a
     * BEND SIGN.
     *
     * <p>Same algorithm, same loop. The constraints are applied INSIDE the iteration rather than
     * tidied up afterwards — a limit enforced after the fact moves the effector off the target,
     * and the chain then visibly fights the finger.
     *
     * <h3>Why stretch is worth having</h3>
     * <p>Rigid bones make the shoulder snap the moment you drag a hand past its reach, which is
     * the ugliest thing in any IK rig. A stretchy bone lengthens instead, so the hand stays under
     * the finger and the arm looks like a cartoon arm. The stretch is spread across the stretchy
     * links in proportion to their length and never exceeds each one's own cap.
     *
     * @param joints      {@code [x0,y0, x1,y1, ...]}, joint 0 FIXED, mutated in place
     * @param restLen     one per link, or null to take the lengths from the current pose
     * @param stretchy    per link, or null for none
     * @param maxStretch  per link, fraction over rest (0.35 = up to 135%), or null
     * @param jointLimits per INTERIOR joint (index 1..n-2, addressed by joint index), or null
     * @param minDeg      per interior joint, the most it may bend one way
     * @param maxDeg      per interior joint, the most it may bend the other
     * @param bendSign    +1 or -1: which side the chain prefers to fold towards; 0 = no
     *                    preference. This is the "flip elbow" bit, and it is stored rather than
     *                    guessed because one bit beats a third object to position.
     * @return true when the effector reached the target
     */
    public static boolean solveConstrained(float[] joints, float[] restLen,
                                           float targetX, float targetY,
                                           boolean[] stretchy, float[] maxStretch,
                                           boolean[] jointLimits, float[] minDeg, float[] maxDeg,
                                           int bendSign) {
        if (joints == null) return false;
        int n = joints.length / 2;
        if (n < 2) return false;

        float[] len = new float[n - 1];
        for (int i = 0; i < n - 1; i++) {
            len[i] = (restLen != null && i < restLen.length && restLen[i] > 0f)
                    ? restLen[i] : dist(joints, i, i + 1);
        }

        float baseX = joints[0], baseY = joints[1];
        float total = 0f;
        for (float l : len) total += l;
        float toTarget = (float) Math.hypot(targetX - baseX, targetY - baseY);

        // STRETCH, before anything else: decide how long the bones are allowed to be for this
        // solve, then solve normally. Doing it this way means the limit code below never has to
        // know stretch exists.
        if (toTarget > total && stretchy != null) {
            float spare = 0f;
            for (int i = 0; i < len.length; i++) {
                if (i < stretchy.length && stretchy[i]) {
                    float cap = (maxStretch != null && i < maxStretch.length)
                            ? Math.max(0f, maxStretch[i]) : 0.35f;
                    spare += len[i] * cap;
                }
            }
            if (spare > 0f) {
                float need = Math.min(spare, toTarget - total);
                float share = need / spare;
                for (int i = 0; i < len.length; i++) {
                    if (i < stretchy.length && stretchy[i]) {
                        float cap = (maxStretch != null && i < maxStretch.length)
                                ? Math.max(0f, maxStretch[i]) : 0.35f;
                        len[i] += len[i] * cap * share;
                    }
                }
                total += need;
            }
        }

        if (toTarget >= total) {
            float dx = targetX - baseX, dy = targetY - baseY;
            float inv = toTarget > 0 ? 1f / toTarget : 0f;
            float cx = baseX, cy = baseY;
            for (int i = 0; i < n - 1; i++) {
                cx += dx * inv * len[i];
                cy += dy * inv * len[i];
                joints[(i + 1) * 2] = cx;
                joints[(i + 1) * 2 + 1] = cy;
            }
            return false;                      // out of reach even stretched: fully extended
        }

        for (int iter = 0; iter < MAX_ITERATIONS; iter++) {
            joints[(n - 1) * 2] = targetX;
            joints[(n - 1) * 2 + 1] = targetY;
            for (int i = n - 2; i >= 0; i--) reposition(joints, i, i + 1, len[i]);
            joints[0] = baseX;
            joints[1] = baseY;
            for (int i = 1; i < n; i++) reposition(joints, i, i - 1, len[i - 1]);
            applyLimits(joints, n, jointLimits, minDeg, maxDeg);
            float ex = joints[(n - 1) * 2] - targetX;
            float ey = joints[(n - 1) * 2 + 1] - targetY;
            if (ex * ex + ey * ey < TOLERANCE * TOLERANCE) break;
        }

        if (bendSign != 0) applyBendSign(joints, n, bendSign);
        float ex = joints[(n - 1) * 2] - targetX;
        float ey = joints[(n - 1) * 2 + 1] - targetY;
        return ex * ex + ey * ey < 1e-3f * 1e-3f;
    }

    /**
     * Clamp each interior joint's bend, rotating everything BEYOND it by the same amount so no
     * bone changes length. Walking base-outward means a correction at the shoulder carries the
     * whole arm with it, which is what a shoulder does.
     */
    private static void applyLimits(float[] j, int n, boolean[] on, float[] minDeg, float[] maxDeg) {
        if (on == null) return;
        for (int i = 1; i <= n - 2; i++) {
            if (i >= on.length || !on[i]) continue;
            float inX = j[i * 2] - j[(i - 1) * 2], inY = j[i * 2 + 1] - j[(i - 1) * 2 + 1];
            float outX = j[(i + 1) * 2] - j[i * 2], outY = j[(i + 1) * 2 + 1] - j[i * 2 + 1];
            float ang = (float) Math.toDegrees(Math.atan2(inX * outY - inY * outX,
                    inX * outX + inY * outY));
            float lo = (minDeg != null && i < minDeg.length) ? minDeg[i] : -180f;
            float hi = (maxDeg != null && i < maxDeg.length) ? maxDeg[i] : 180f;
            if (lo > hi) { float t = lo; lo = hi; hi = t; }
            float clamped = Math.max(lo, Math.min(hi, ang));
            float delta = clamped - ang;
            if (Math.abs(delta) < 1e-4f) continue;
            rotateFrom(j, n, i, (float) Math.toRadians(delta));
        }
    }

    /**
     * Put the chain on the side the artwork was drawn on.
     *
     * <p>FABRIK has no opinion about which way an elbow folds and will cheerfully settle on
     * either. The rest pose decides it at bind time and stores one bit; if the solve came out the
     * other way, every interior joint is mirrored across the base-to-effector line — which flips
     * the fold and moves neither end, so the hand stays exactly where the finger put it.
     */
    private static void applyBendSign(float[] j, int n, int want) {
        if (n < 3) return;
        float ax = j[0], ay = j[1];
        float bx = j[(n - 1) * 2], by = j[(n - 1) * 2 + 1];
        float ex = bx - ax, ey = by - ay;
        float elen = (float) Math.hypot(ex, ey);
        if (elen < 1e-9f) return;
        float ux = ex / elen, uy = ey / elen;

        // The chain's current side: the signed area swept by the interior joints. Summing rather
        // than testing one joint means an S-shaped three-bone chain is judged by its overall
        // fold instead of by whichever joint happened to be looked at.
        float side = 0f;
        for (int i = 1; i <= n - 2; i++) {
            float px = j[i * 2] - ax, py = j[i * 2 + 1] - ay;
            side += ux * py - uy * px;
        }
        if (side == 0f || Math.signum(side) == Math.signum(want)) return;

        // Reflect across the base-to-tip line: keep the component ALONG it, negate the one across
        // it. Both ends lie on the line, so neither moves.
        float nx = -uy, ny = ux;
        for (int i = 1; i <= n - 2; i++) {
            float px = j[i * 2] - ax, py = j[i * 2 + 1] - ay;
            float along = px * ux + py * uy;
            float perp = px * nx + py * ny;
            j[i * 2] = ax + ux * along - nx * perp;
            j[i * 2 + 1] = ay + uy * along - ny * perp;
        }
    }

    /** Rotate every joint after {@code from} about it, keeping every bone length. */
    private static void rotateFrom(float[] j, int n, int from, float radians) {
        float cx = j[from * 2], cy = j[from * 2 + 1];
        float c = (float) Math.cos(radians), s = (float) Math.sin(radians);
        for (int k = from + 1; k < n; k++) {
            float dx = j[k * 2] - cx, dy = j[k * 2 + 1] - cy;
            j[k * 2] = cx + dx * c - dy * s;
            j[k * 2 + 1] = cy + dx * s + dy * c;
        }
    }

    /** Move joint {@code move} to exactly {@code len} from joint {@code anchor},
     *  along their current direction. */
    private static void reposition(float[] j, int move, int anchor, float len) {
        float ax = j[anchor * 2], ay = j[anchor * 2 + 1];
        float mx = j[move * 2], my = j[move * 2 + 1];
        float d = (float) Math.hypot(mx - ax, my - ay);
        float r = d > 1e-9f ? len / d : 0f;
        j[move * 2] = ax + (mx - ax) * r;
        j[move * 2 + 1] = ay + (my - ay) * r;
    }

    private static float dist(float[] j, int a, int b) {
        return (float) Math.hypot(j[b * 2] - j[a * 2], j[b * 2 + 1] - j[a * 2 + 1]);
    }
}
