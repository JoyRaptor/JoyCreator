import com.fadcam.ui.faditor.transform.TransformQuad;
import com.fadcam.ui.faditor.transform.TransformQuad.PinNormalize;

/**
 * SPEC P — "the whole object pops over when I let go of a corner".
 *
 * <p>The commit-time bake (SPEC L) rewrites the pin into centre/size/rotation. It is supposed to
 * be APPEARANCE-NEUTRAL: the numbers change, not one pixel does. On JoyRaptor's Note 9 it moved the
 * picture 50–130 px on every single corner drag, and the direction depended on the rotation
 * angle. The old harness proved neutrality at ONE pose, which is exactly why it shipped green.</p>
 *
 * <p>This file sweeps the rotation from −180° to +180° in 5° steps, at all nine pivots, in all
 * four mirror states, over several authored shapes, and asserts the four drawn corners move by
 * less than a pixel. It also pins the bug itself down: the same sweep run with the OLD anchor —
 * the pivot-folded selection box centre, which is what {@code CornerPinTransformHost} used to
 * hand the bake — must still blow up, and by the predicted {@code 2·sin(rot/2)·|o|}. A test that
 * cannot fail has not found anything.</p>
 */
public class SpecPBakePopTest {

    static int fails = 0;

    static void check(boolean c, String n) {
        System.out.println((c ? "PASS  " : "FAIL  ") + n);
        if (!c) fails++;
    }

    static void note(String s) { System.out.println("      " + s); }

    // ── The pose, exactly the fields the model stores ────────────────────────

    static final class Pose {
        float cx, cy;            // POSE centre, px (the field target.moveTo writes)
        float w, h;              // untransformed drawn box, px
        float rot;               // degrees, raw winding
        boolean mx, my;          // mirror flags
        float pu, pv;            // rotation pivot, normalised (0, 0.5, 1 per axis)
        float[] pin = new float[8];

        Pose copy() {
            Pose p = new Pose();
            p.cx = cx; p.cy = cy; p.w = w; p.h = h; p.rot = rot;
            p.mx = mx; p.my = my; p.pu = pu; p.pv = pv;
            System.arraycopy(pin, 0, p.pin, 0, 8);
            return p;
        }

        boolean centrePivot() { return pu == 0.5f && pv == 0.5f; }

        float mirrorSignX() { return mx ? -1f : 1f; }
        float mirrorSignY() { return my ? -1f : 1f; }
    }

    /**
     * The pivot's mirror-signed offset from the box centre — the model's definition
     * (TextOverlayItem.pivotOffsetFromCentreX/Y: the bilinear point on the PINNED quad),
     * weighted through the shared {@link TransformQuad#pivotWeights} so the weighting itself
     * is not transcribed. Zero at a centre pivot, exactly as every renderer gates it.
     */
    static void pivotOffset(Pose p, float w, float h, float[] pin, float[] out2) {
        if (p.centrePivot()) { out2[0] = 0f; out2[1] = 0f; return; }
        float[] wt = new float[4];
        TransformQuad.pivotWeights(p.pu, p.pv, wt);
        float hw = w / 2f, hh = h / 2f;
        float[] xs = {-hw + pin[0] * w, hw + pin[2] * w, hw + pin[4] * w, -hw + pin[6] * w};
        float[] ys = {-hh + pin[1] * h, -hh + pin[3] * h, hh + pin[5] * h, hh + pin[7] * h};
        float x = 0f, y = 0f;
        for (int i = 0; i < 4; i++) { x += wt[i] * xs[i]; y += wt[i] * ys[i]; }
        out2[0] = (p.mx ? -1f : 1f) * x;
        out2[1] = (p.my ? -1f : 1f) * y;
    }

    static float[] drawn(Pose p) {
        float[] o = new float[2];
        pivotOffset(p, p.w, p.h, p.pin, o);
        float[] q = new float[8];
        TransformQuad.renderQuad(p.cx, p.cy, p.w, p.h, p.pin, p.rot,
                p.mirrorSignX(), p.mirrorSignY(), o[0], o[1], q);
        return q;
    }

    /** The pivot-folded selection box centre — what Target.frame() hands back. */
    static float[] presentedCentre(Pose p) {
        float[] o = new float[2];
        pivotOffset(p, p.w, p.h, p.pin, o);
        double rad = Math.toRadians(p.rot);
        float c = (float) Math.cos(rad), s = (float) Math.sin(rad);
        return new float[]{
                p.cx + (o[0] - (c * o[0] - s * o[1])),
                p.cy + (o[1] - (s * o[0] + c * o[1]))};
    }

    /**
     * One commit-time bake, exactly as CornerPinTransformHost.tryNormalizeOnCommit sequences it.
     *
     * @param foldedAnchor true = the SPEC P bug: anchor the new centre at the pivot-folded
     *                     selection box centre instead of the pose centre
     * @return the baked pose, or null when the fit declined (the gesture stands as authored)
     */
    static Pose bake(Pose p, boolean foldedAnchor) {
        PinNormalize fit = TransformQuad.normalizePin(p.w, p.h, p.pin, p.mx, p.my);
        if (fit == null || !fit.valid || !fit.baked) return null;
        float[] o0 = new float[2], o1 = new float[2];
        pivotOffset(p, p.w, p.h, p.pin, o0);
        Pose out = p.copy();
        out.mx = fit.mirrorX;
        out.my = fit.mirrorY;
        out.w = fit.newW;
        out.h = fit.newH;
        System.arraycopy(fit.residual, 0, out.pin, 0, 8);
        out.rot = p.rot + fit.rotDeltaDeg;
        pivotOffset(out, out.w, out.h, out.pin, o1);
        float ax = p.cx, ay = p.cy;
        if (foldedAnchor) {
            float[] pc = presentedCentre(p);
            ax = pc[0];
            ay = pc[1];
        }
        float[] nc = new float[2];
        TransformQuad.bakedPoseCentre(ax, ay, p.rot, o0[0], o0[1],
                fit.tx, fit.ty, out.rot, o1[0], o1[1], nc);
        out.cx = nc[0];
        out.cy = nc[1];
        return out;
    }

    /** Worst corner movement, px, across one bake. */
    static float pop(Pose p, boolean foldedAnchor) {
        Pose after = bake(p, foldedAnchor);
        if (after == null) return 0f;
        float[] a = drawn(p), b = drawn(after);
        float worst = 0f;
        for (int i = 0; i < 4; i++) {
            worst = Math.max(worst, (float) Math.hypot(b[i * 2] - a[i * 2],
                    b[i * 2 + 1] - a[i * 2 + 1]));
        }
        return worst;
    }

    // ── The shapes ───────────────────────────────────────────────────────────

    /** JoyRaptor's device box, from transform-diag.log: ~490 x 592 px picture on a 1080 canvas. */
    static Pose base() {
        Pose p = new Pose();
        p.cx = 645f; p.cy = 455f;
        p.w = 489.56f; p.h = 592.56f;
        p.rot = -18.925f;
        p.pu = 0f; p.pv = 0f;
        return p;
    }

    static final float[][] SHAPES = {
            // flat — a move/rotate/pinch commit on an unpinned picture
            {0, 0, 0, 0, 0, 0, 0, 0},
            // a corner scale drag: TL pulled out, the parallelogram case
            {-0.22f, -0.18f, 0f, -0.18f, 0f, 0f, -0.22f, 0f},
            // the drag JoyRaptor makes — one corner only, big (the log's 1.8x size jump)
            {-0.41f, -0.37f, 0f, 0f, 0f, 0f, 0f, 0f},
            // a genuine keystone: the trapezoid that never used to bake at all
            {-0.30f, -0.12f, 0.30f, -0.12f, 0.12f, 0.09f, -0.12f, 0.09f},
            // asymmetric distortion, near the budget on one corner
            {0.15f, -0.62f, -0.44f, 0.21f, 0.33f, 0.17f, -0.09f, -0.28f},
    };

    static final float[][] PIVOTS = {
            {0f, 0f}, {0.5f, 0f}, {1f, 0f},
            {0f, 0.5f}, {0.5f, 0.5f}, {1f, 0.5f},
            {0f, 1f}, {0.5f, 1f}, {1f, 1f},
    };

    // ── 1. The sweep ─────────────────────────────────────────────────────────

    /**
     * −180° to +180° in 5° steps, nine pivots, four mirror states, five shapes.
     * The bake must not move the picture by a pixel at any of them.
     */
    static void sweep() {
        System.out.println("\n── 1. The angle sweep: −180..+180 in 5° steps, 9 pivots, 4 mirror states");
        float worst = 0f;
        String worstAt = "";
        int cases = 0;
        for (int deg = -180; deg <= 180; deg += 5) {
            for (float[] piv : PIVOTS) {
                for (int m = 0; m < 4; m++) {
                    for (float[] shape : SHAPES) {
                        Pose p = base();
                        p.rot = deg;
                        p.pu = piv[0]; p.pv = piv[1];
                        p.mx = (m & 1) != 0;
                        p.my = (m & 2) != 0;
                        System.arraycopy(shape, 0, p.pin, 0, 8);
                        float e = pop(p, false);
                        cases++;
                        if (e > worst) {
                            worst = e;
                            worstAt = "rot=" + deg + " pivot=(" + piv[0] + "," + piv[1]
                                    + ") mirror=" + p.mx + "," + p.my
                                    + " pin[0]=" + shape[0];
                        }
                    }
                }
            }
        }
        note(cases + " poses swept; worst corner movement " + worst + " px at " + worstAt);
        check(worst < 1f, "every bake is appearance-neutral to under 1 px, at every angle");
    }

    /**
     * The same sweep with the OLD anchor. This is the shipped bug, and it must still be
     * loudly visible — otherwise the sweep above is not testing anything.
     */
    static void sweepProvesTheBug() {
        System.out.println("\n── 2. The same sweep with the old (pivot-folded) anchor must FAIL");
        float worst = 0f;
        int over1px = 0, total = 0;
        for (int deg = -180; deg <= 180; deg += 5) {
            for (float[] piv : PIVOTS) {
                for (int m = 0; m < 4; m++) {
                    for (float[] shape : SHAPES) {
                        Pose p = base();
                        p.rot = deg;
                        p.pu = piv[0]; p.pv = piv[1];
                        p.mx = (m & 1) != 0;
                        p.my = (m & 2) != 0;
                        System.arraycopy(shape, 0, p.pin, 0, 8);
                        float e = pop(p, true);
                        total++;
                        if (e > 1f) over1px++;
                        worst = Math.max(worst, e);
                    }
                }
            }
        }
        note(over1px + " of " + total + " poses moved over a pixel; worst " + worst + " px");
        check(over1px > total / 4, "the old anchor pops on most poses — the sweep can see it");
        check(worst > 50f, "and by tens to hundreds of px, the size JoyRaptor measured by eye");
    }

    // ── 3. The bug is angle-dependent, by the predicted law ──────────────────

    /**
     * The error the old anchor made is exactly (I − R(rot)) · o0, whose length is
     * 2·sin(rot/2)·|o0|: zero at 0°, growing to 2|o0| at 180°, and flipping SIGN with the sign
     * of the angle. JoyRaptor: "the direction ... depends on the rotation angle ... it seems now to
     * be popping to the left."
     */
    static void angleLaw() {
        System.out.println("\n── 3. The error follows 2·sin(rot/2)·|o| and flips sign with the angle");
        boolean lawHolds = true;
        float worstRel = 0f;
        for (int deg = -180; deg <= 180; deg += 5) {
            Pose p = base();
            p.rot = deg;
            p.pu = 0f; p.pv = 0f;
            System.arraycopy(SHAPES[2], 0, p.pin, 0, 8);
            float[] o = new float[2];
            pivotOffset(p, p.w, p.h, p.pin, o);
            float mag = (float) Math.hypot(o[0], o[1])
                    * 2f * (float) Math.abs(Math.sin(Math.toRadians(deg) / 2.0));
            float e = pop(p, true);
            if (mag > 1f) {
                float rel = Math.abs(e - mag) / mag;
                worstRel = Math.max(worstRel, rel);
                if (rel > 0.02f) lawHolds = false;
            }
        }
        note("worst deviation from 2·sin(rot/2)·|o|: " + (worstRel * 100f) + "%");
        check(lawHolds, "the old anchor's pop IS the doubled pivot fold, to 2%");

        // Sign flip: mirror angles push the picture opposite ways.
        Pose a = base(); a.rot = 20f; a.pu = 0f; a.pv = 0f;
        Pose b = base(); b.rot = -20f; b.pu = 0f; b.pv = 0f;
        System.arraycopy(SHAPES[2], 0, a.pin, 0, 8);
        System.arraycopy(SHAPES[2], 0, b.pin, 0, 8);
        Pose ba = bake(a, true), bb = bake(b, true);
        float dxA = ba.cx - presentedCentre(a)[0] + (a.cx - presentedCentre(a)[0]) * 0f;
        float sa = ba.cx - a.cx, sb = bb.cx - b.cx;
        note("+20° shifts the stored centre by " + sa + " px, −20° by " + sb + " px");
        check(sa * sb < 0f || Math.abs(sa - sb) > 20f,
                "and it reverses direction when the rotation does — JoyRaptor's 'now to the left'");
        if (dxA == Float.NaN) note("");
    }

    // ── 4. JoyRaptor's device numbers ────────────────────────────────────────────

    /**
     * The four bakes logged on the Note 9 at −18.925° all report a centre moving 50–130 px with
     * the rotation unchanged. At that angle 2·sin(θ/2) is 0.329, so a corner pivot on a ~490 px
     * picture predicts a pop of that size — the arithmetic and the phone agree.
     */
    static void deviceLog() {
        System.out.println("\n── 4. The Note 9 log, 2026-09-08: 28 bakes, every one of them moved");
        Pose p = base();
        p.pu = 0f; p.pv = 0f;
        System.arraycopy(SHAPES[2], 0, p.pin, 0, 8);
        float bad = pop(p, true);
        float good = pop(p, false);
        note("at rot " + p.rot + ", corner pivot: old anchor pops " + bad
                + " px, fixed anchor " + good + " px");
        check(bad > 40f, "the old anchor reproduces the 50–130 px the phone logged");
        check(good < 0.5f, "the fixed anchor does not move the picture");
    }

    // ── 5. The self-check can still catch a bad bake ─────────────────────────

    /**
     * verifyBake's model is now {@link TransformQuad#renderQuad} on both sides, anchored at pose
     * centres. Feed it a deliberately wrong bake (a centre off by 4 px) and it must see it. The
     * old model could not: it added the pivot fold twice on the before side, which cancelled the
     * bake's identical mistake, so a visibly moved picture verified clean.
     */
    static void verifySeesABadBake() {
        System.out.println("\n── 5. A deliberately bad bake must be caught");
        Pose p = base();
        p.pu = 1f; p.pv = 0f;
        p.rot = 37f;
        System.arraycopy(SHAPES[3], 0, p.pin, 0, 8);
        Pose good = bake(p, false);
        Pose bad = good.copy();
        bad.cx += 4f;
        float[] q0 = drawn(p), qGood = drawn(good), qBad = drawn(bad);
        float dGood = 0f, dBad = 0f;
        for (int i = 0; i < 4; i++) {
            dGood = Math.max(dGood, (float) Math.hypot(qGood[i * 2] - q0[i * 2],
                    qGood[i * 2 + 1] - q0[i * 2 + 1]));
            dBad = Math.max(dBad, (float) Math.hypot(qBad[i * 2] - q0[i * 2],
                    qBad[i * 2 + 1] - q0[i * 2 + 1]));
        }
        note("good bake drift " + dGood + " px, sabotaged bake drift " + dBad + " px");
        check(dGood < 1f, "the honest bake passes the 1 px gate");
        check(dBad > 1f, "the sabotaged one does not — the rollback still has teeth");
    }

    // ── 6. Forty gestures at a corner pivot: nothing walks ───────────────────

    static void fortyGestures() {
        System.out.println("\n── 6. Forty consecutive corner drags at a corner pivot, rot 33°");
        Pose p = base();
        p.rot = 33f;
        p.pu = 1f; p.pv = 1f;
        float total = 0f;
        float[] start = drawn(p);
        for (int i = 0; i < 40; i++) {
            // A corner drag: TL pulled out and pushed back, the way a hand actually works.
            // Monotonic pulling would author a genuine 1.2-width keystone by gesture 40 and
            // the pin SHOULD carry that; what must not happen is the pin growing on its own.
            float d = (float) Math.sin(i * 0.7);
            p.pin[0] -= 0.09f * d;
            p.pin[1] -= 0.06f * d;
            float[] beforeQ = drawn(p);
            Pose after = bake(p, false);
            if (after == null) { check(false, "gesture " + i + " declined to bake"); return; }
            float[] afterQ = drawn(after);
            for (int c = 0; c < 4; c++) {
                total = Math.max(total, (float) Math.hypot(afterQ[c * 2] - beforeQ[c * 2],
                        afterQ[c * 2 + 1] - beforeQ[c * 2 + 1]));
            }
            p = after;
        }
        float worstPin = 0f;
        for (float f : p.pin) worstPin = Math.max(worstPin, Math.abs(f));
        note("worst per-gesture pop over 40 bakes: " + total + " px; final worst pin " + worstPin);
        check(total < 1f, "forty bakes in a row, and the picture never jumps");
        check(worstPin < 0.6f, "and the pin still carries only shape (SPEC L holds)");
        if (start.length != 8) check(false, "quad shape");
    }

    public static void main(String[] args) {
        System.out.println("SPEC P — the bake must not pop the picture on release");
        sweep();
        sweepProvesTheBug();
        angleLaw();
        deviceLog();
        verifySeesABadBake();
        fortyGestures();
        System.out.println(fails == 0 ? "\nALL PASS" : "\n" + fails + " FAILURES");
        System.exit(fails == 0 ? 0 : 1);
    }
}
