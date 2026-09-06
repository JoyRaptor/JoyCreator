import com.fadcam.ui.faditor.transform.TransformQuad;
import com.fadcam.ui.faditor.transform.TransformQuad.PinNormalize;

/**
 * SPEC G — stop trivial edits from running out of corner-pin budget.
 *
 * <p>Pinned to TransformQuad.normalizePin off device: every check is about the arithmetic
 * JoyRaptor's complaint is really about, not about the implementation. A flip, fold, rotation or
 * scale of a rectangle is a PARALLELOGRAM — centre, size, rotation and a mirror — and needs
 * no pin at all; only a genuine perspective distortion does.
 *
 * <p>Composition order under test (the one all three renderers implement): bitmap → MIRROR
 * → pin → rotate/scale → translate, mirror about the unpinned box centre:
 * X = R(d) . M . (B + off.s) + t.
 */
public class PinNormalizeTest {
    static int fails = 0;

    static void check(boolean c, String n) {
        System.out.println((c ? "PASS  " : "FAIL  ") + n);
        if (!c) fails++;
    }

    static void checkNear(float want, float got, float tol, String n) {
        boolean c = Math.abs(got - want) <= tol;
        System.out.println((c ? "PASS  " : "FAIL  ") + n
                + (c ? "" : "  (got " + got + ", want " + want + " +- " + tol + ")"));
        if (!c) fails++;
    }

    // The complaint's arithmetic, from an undistorted picture: a flip spends half the
    // budget (offsets of 1.0 against MAX_OFFSET 2) and a fold spends all of it (2.0),
    // while the distortion the user authored — a 5% nudge — costs 0.05.
    static void complaintArithmetic() {
        float[] flipH = {1f, 0f, -1f, 0f, -1f, 0f, 1f, 0f};
        float worst = 0f;
        for (float v : flipH) worst = Math.max(worst, Math.abs(v));
        check(worst == 1f, "complaint: one flip from flat writes offsets of 1.0 (half the budget)");

        // A fold reflected across the top edge of a 400x300 box.
        float w = 400f, h = 300f;
        float[] q = {-w / 2, -h / 2, w / 2, -h / 2, w / 2, h / 2, -w / 2, h / 2};
        TransformQuad.foldOverEdge(q, TransformQuad.TOP);
        float worstF = 0f;
        for (int i = 0; i < 4; i++) {
            float bx = (i == 0 || i == 3) ? -w / 2 : w / 2;
            float by = (i < 2) ? -h / 2 : h / 2;
            worstF = Math.max(worstF, Math.abs((q[i * 2] - bx) / w));
            worstF = Math.max(worstF, Math.abs((q[i * 2 + 1] - by) / h));
        }
        checkNear(2f, worstF, 1e-4f, "complaint: one fold from flat writes offsets of 2.0 (all of it)");
    }

    // Recompose X' = R(d).M.(B' + off'.s') + t and compare against X = B + off.s
    // (pose frame, single mirror state folded into M0 for the before-side).
    static float recomposeErr(float w, float h, float[] off,
                              boolean m0x, boolean m0y,
                              PinNormalize fit) {
        float s0x = m0x ? -1f : 1f, s0y = m0y ? -1f : 1f;
        float s1x = fit.mirrorX ? -1f : 1f, s1y = fit.mirrorY ? -1f : 1f;
        double rad = Math.toRadians(fit.rotDeltaDeg);
        float cs = (float) Math.cos(rad), sn = (float) Math.sin(rad);
        float nHw = fit.newW / 2f, nHh = fit.newH / 2f;
        float[] bx0 = {-w / 2, w / 2, w / 2, -w / 2};
        float[] by0 = {-h / 2, -h / 2, h / 2, h / 2};
        float[] bx1 = {-nHw, nHw, nHw, -nHw};
        float[] by1 = {-nHh, -nHh, nHh, nHh};
        float worst = 0f;
        for (int i = 0; i < 4; i++) {
            float xa = s0x * (bx0[i] + off[i * 2] * w);
            float ya = s0y * (by0[i] + off[i * 2 + 1] * h);
            float lx = bx1[i] + fit.residual[i * 2] * fit.newW;
            float ly = by1[i] + fit.residual[i * 2 + 1] * fit.newH;
            float xb = cs * (s1x * lx) - sn * (s1y * ly) + fit.tx;
            float yb = sn * (s1x * lx) + cs * (s1y * ly) + fit.ty;
            worst = Math.max(worst, (float) Math.hypot(xb - xa, yb - ya));
        }
        return worst;
    }

    static float maxAbs(float[] a) {
        float m = 0f;
        for (float v : a) m = Math.max(m, Math.abs(v));
        return m;
    }

    static void identity() {
        PinNormalize f = TransformQuad.normalizePin(400f, 300f, new float[8], false, false);
        check(f.valid, "identity: valid");
        check(f.rotDeltaDeg == 0f && !f.mirrorX && !f.mirrorY, "identity: no rotation, no mirror");
        check(f.newW == 400f && f.newH == 300f, "identity: box untouched");
        check(f.tx == 0f && f.ty == 0f, "identity: centre untouched");
        check(maxAbs(f.residual) == 0f, "identity: residual exactly zero");
    }

    static void flipBakes() {
        // flipH from flat, as CornerPinTransformHost.flip writes it (dx' = s - dx).
        PinNormalize f = TransformQuad.normalizePin(400f, 300f,
                new float[]{1f, 0f, -1f, 0f, -1f, 0f, 1f, 0f}, false, false);
        check(f.valid && f.mirrorX && !f.mirrorY, "flipH bakes to mirrorX, not to pin");
        check(f.rotDeltaDeg == 0f, "flipH adds no rotation");
        check(maxAbs(f.residual) == 0f, "flipH residual exactly zero");
        check(recomposeErr(400f, 300f, new float[]{1f, 0f, -1f, 0f, -1f, 0f, 1f, 0f},
                false, false, f) < 1e-3f, "flipH round-trips within a pixel");

        PinNormalize g = TransformQuad.normalizePin(400f, 300f,
                new float[]{0f, 1f, 0f, 1f, 0f, -1f, 0f, -1f}, false, false);
        check(g.valid && g.mirrorY && !g.mirrorX, "flipV bakes to mirrorY, not to pin");
        check(g.rotDeltaDeg == 0f, "flipV adds no rotation");
        check(maxAbs(g.residual) == 0f, "flipV residual exactly zero");

        // Stability: baking an already-mirrored flat picture is a no-op (this is the bug
        // that un-mirrored the picture when the fit only saw the pin).
        PinNormalize s = TransformQuad.normalizePin(400f, 300f, new float[8], true, false);
        check(s.valid && s.baked && s.mirrorX && !s.mirrorY, "mirrored-flat bake keeps mirrorX");
        check(s.rotDeltaDeg == 0f && maxAbs(s.residual) == 0f && s.newW == 400f,
                "mirrored-flat bake touches nothing else");
    }

    static void foldBakes() {
        float w = 400f, h = 300f;
        float[] q = {-w / 2, -h / 2, w / 2, -h / 2, w / 2, h / 2, -w / 2, h / 2};
        TransformQuad.foldOverEdge(q, TransformQuad.TOP);
        float[] off = new float[8];
        for (int i = 0; i < 4; i++) {
            float bx = (i == 0 || i == 3) ? -w / 2 : w / 2;
            float by = (i < 2) ? -h / 2 : h / 2;
            off[i * 2] = (q[i * 2] - bx) / w;
            off[i * 2 + 1] = (q[i * 2 + 1] - by) / h;
        }
        PinNormalize f = TransformQuad.normalizePin(w, h, off, false, false);
        check(f.valid && f.mirrorY && !f.mirrorX, "fold over top bakes to mirrorY");
        check(f.rotDeltaDeg == 0f, "fold over top adds no rotation");
        check(maxAbs(f.residual) == 0f, "fold residual exactly zero");

        float[] q2 = {-w / 2, -h / 2, w / 2, -h / 2, w / 2, h / 2, -w / 2, h / 2};
        TransformQuad.foldOverEdge(q2, TransformQuad.LEFT);
        float[] off2 = new float[8];
        for (int i = 0; i < 4; i++) {
            float bx = (i == 0 || i == 3) ? -w / 2 : w / 2;
            float by = (i < 2) ? -h / 2 : h / 2;
            off2[i * 2] = (q2[i * 2] - bx) / w;
            off2[i * 2 + 1] = (q2[i * 2 + 1] - by) / h;
        }
        PinNormalize g = TransformQuad.normalizePin(w, h, off2, false, false);
        check(g.valid && g.mirrorX && !g.mirrorY, "fold over left bakes to mirrorX");
        check(maxAbs(g.residual) == 0f, "fold-left residual exactly zero");
    }

    static void diagonalFoldBakes() {
        // Reflection across the 45-degree diagonal (square box): mirror PLUS a quarter turn.
        float[] off = {0f, 0f, -1f, 1f, 0f, 0f, 1f, -1f};
        PinNormalize f = TransformQuad.normalizePin(400f, 400f, off, false, false);
        check(f.valid, "diagonal fold: valid");
        check(f.mirrorX != f.mirrorY, "diagonal fold bakes to exactly one mirror flag");
        checkNear(90f, Math.abs(f.rotDeltaDeg), 0.05f, "diagonal fold carries a 90-degree turn");
        check(maxAbs(f.residual) == 0f, "diagonal fold residual exactly zero");
        check(recomposeErr(400f, 400f, off, false, false, f) < 1e-3f, "diagonal fold round-trips");
    }

    static void rotateAndScaleBake() {        float w = 400f, h = 300f;
        // A 30-degree turn written as pin (what a distort-drag in a circle leaves behind).
        double rad = Math.toRadians(30);
        float cs = (float) Math.cos(rad), sn = (float) Math.sin(rad);
        float[] bx = {-w / 2, w / 2, w / 2, -w / 2};
        float[] by = {-h / 2, -h / 2, h / 2, h / 2};
        float[] off = new float[8];
        for (int i = 0; i < 4; i++) {
            float rx = cs * bx[i] - sn * by[i], ry = sn * bx[i] + cs * by[i];
            off[i * 2] = (rx - bx[i]) / w;
            off[i * 2 + 1] = (ry - by[i]) / h;
        }
        PinNormalize f = TransformQuad.normalizePin(w, h, off, false, false);
        check(f.valid && !f.mirrorX && !f.mirrorY, "rotated quad bakes with no mirror");
        checkNear(30f, f.rotDeltaDeg, 0.05f, "rotation is EXTRACTED (delta ~= 30), not left in the pin");
        check(maxAbs(f.residual) == 0f, "rotated residual exactly zero");
        check(recomposeErr(w, h, off, false, false, f) < 1e-3f, "rotation round-trips");

        // Uniform 1.5x.
        float[] sc = new float[8];
        for (int i = 0; i < 4; i++) {
            sc[i * 2] = bx[i] * 0.5f / w;
            sc[i * 2 + 1] = by[i] * 0.5f / h;
        }
        PinNormalize g = TransformQuad.normalizePin(w, h, sc, false, false);
        check(g.valid, "uniform scale: valid");
        checkNear(600f, g.newW, 0.5f, "uniform scale bakes width 400 -> 600");
        checkNear(450f, g.newH, 0.5f, "uniform scale bakes height 300 -> 450");
        check(g.rotDeltaDeg == 0f && maxAbs(g.residual) == 0f, "uniform scale: no rotation, no pin");

        // Non-uniform 2x by 0.5x.
        float[] an = new float[8];
        for (int i = 0; i < 4; i++) {
            an[i * 2] = bx[i] * 1f / w;
            an[i * 2 + 1] = by[i] * -0.5f / h;
        }
        PinNormalize k = TransformQuad.normalizePin(w, h, an, false, false);
        check(k.valid, "non-uniform scale: valid");
        checkNear(800f, k.newW, 0.5f, "non-uniform scale bakes width 400 -> 800");
        checkNear(150f, k.newH, 0.5f, "non-uniform scale bakes height 300 -> 150");
        check(maxAbs(k.residual) == 0f, "non-uniform scale: no pin");
    }

    static void nudgeStaysSmall() {
        // JoyRaptor's 5%: one corner nudged 5% of the height. Stores ~0.05, not 1.05 —
        // and must NOT churn the box (the scale chain stays linked, no size write).
        float w = 400f, h = 300f;
        float[] off = new float[8];
        off[0] = 0.05f; off[1] = 0.05f;
        PinNormalize f = TransformQuad.normalizePin(w, h, off, false, false);
        check(f.valid, "nudge: valid");
        checkNear(0.05f, maxAbs(f.residual), 0.015f, "5% nudge stores ~0.05 in the pin");
        check(f.newW == w && f.newH == h, "5% nudge does not touch the box (chain stays linked)");
        check(f.rotDeltaDeg == 0f, "5% nudge adds no rotation");
        check(recomposeErr(w, h, off, false, false, f) < 0.05f, "nudge round-trips within a pixel");
    }

    static void realTrapezoidSurvives() {
        // A genuine perspective taper stays in the pin — the bake must not flatten it.
        float w = 400f, h = 300f;
        float[] off = {0.3f, 0f, -0.3f, 0f, -0.15f, 0f, 0.15f, 0f};
        PinNormalize f = TransformQuad.normalizePin(w, h, off, false, false);
        check(f.valid, "trapezoid: valid");
        checkNear(0.3f, maxAbs(f.residual), 0.03f, "genuine taper still lives in the pin (~0.3)");
        check(recomposeErr(w, h, off, false, false, f) < 1e-2f, "trapezoid round-trips");
    }

    // Full host-level simulation: (centre, mirrorX, mirrorY, off) state, flip = toggle +
    // negate, fold = reflect the composed quad and re-express as pin. Acceptance criterion 1:
    // flip, flip, fold, fold, fold, flip all succeed with the pin still at/near zero —
    // and every BAKE (representation change) moves no corner (criterion 3), even though the
    // GESTURES (folds) legitimately move the picture.
    static class Model {
        float cx, cy;
        boolean mx, my;
        float[] off = new float[8];
        float w = 400f, h = 300f;
        float rot = 0f;
    }

    static float[] compose(Model m) {
        float sx = m.mx ? -1f : 1f, sy = m.my ? -1f : 1f;
        float[] q = new float[8];
        float[] bx = {-m.w / 2, m.w / 2, m.w / 2, -m.w / 2};
        float[] by = {-m.h / 2, -m.h / 2, m.h / 2, m.h / 2};
        for (int i = 0; i < 4; i++) {
            q[i * 2] = m.cx + sx * (bx[i] + m.off[i * 2] * m.w);
            q[i * 2 + 1] = m.cy + sy * (by[i] + m.off[i * 2 + 1] * m.h);
        }
        return q;
    }

    static float quadDist(float[] a, float[] b) {
        float worst = 0f;
        for (int i = 0; i < 8; i++) worst = Math.max(worst, Math.abs(a[i] - b[i]));
        return worst;
    }

    static void applyBake(Model m, PinNormalize f) {
        m.mx = f.mirrorX;
        m.my = f.mirrorY;
        m.rot += f.rotDeltaDeg;
        m.w = f.newW;
        m.h = f.newH;
        // Sequence runs at rotation 0, so the pose-frame shift lands on the centre as-is
        // (the host adds R(rot).t in general).
        m.cx += f.tx;
        m.cy += f.ty;
        System.arraycopy(f.residual, 0, m.off, 0, 8);
    }

    static void flipSim(Model m, boolean horizontal, String n) {
        if (horizontal) {
            m.mx = !m.mx;
            for (int c = 0; c < 4; c++) m.off[c * 2] = -m.off[c * 2];
        } else {
            m.my = !m.my;
            for (int c = 0; c < 4; c++) m.off[c * 2 + 1] = -m.off[c * 2 + 1];
        }
        float[] before = compose(m);
        PinNormalize f = TransformQuad.normalizePin(m.w, m.h, m.off, m.mx, m.my);
        check(f.valid, n + ": flip bakes (never refuses)");
        applyBake(m, f);
        check(quadDist(before, compose(m)) < 1e-3f, n + ": bake moves no corner");
    }

    static void foldSim(Model m, int edge, String n) {
        // Exactly what the host sees: the view folds the PRESENTED (mirrored) quad, and
        // writeQuad un-mirrors it back into the pin frame (pin lives unmirrored, always).
        float[] q = compose(m);
        TransformQuad.foldOverEdge(q, edge);
        float sx = m.mx ? -1f : 1f, sy = m.my ? -1f : 1f;
        float[] bx = {-m.w / 2, m.w / 2, m.w / 2, -m.w / 2};
        float[] by = {-m.h / 2, -m.h / 2, m.h / 2, m.h / 2};
        float[] off = new float[8];
        for (int i = 0; i < 4; i++) {
            off[i * 2] = ((q[i * 2] - m.cx) * sx - bx[i]) / m.w;
            off[i * 2 + 1] = ((q[i * 2 + 1] - m.cy) * sy - by[i]) / m.h;
        }
        // The fold must fit the tracks or the old code refused it.
        check(maxAbs(off) <= 2f + 1e-4f, n + ": fold fits the corner-pin reach");
        float[] gestured = compose(m);
        // Rebuild the gestured quad from the re-expressed pin, for the bake check below.
        float[] checkQ = new float[8];
        for (int i = 0; i < 4; i++) {
            float[] bx2 = {-m.w / 2, m.w / 2, m.w / 2, -m.w / 2};
            float[] by2 = {-m.h / 2, -m.h / 2, m.h / 2, m.h / 2};
            checkQ[i * 2] = m.cx + sx * (bx2[i] + off[i * 2] * m.w);
            checkQ[i * 2 + 1] = m.cy + sy * (by2[i] + off[i * 2 + 1] * m.h);
        }
        check(quadDist(q, checkQ) < 1e-3f, n + ": pin re-expression holds the folded quad");
        PinNormalize f = TransformQuad.normalizePin(m.w, m.h, off, m.mx, m.my);
        check(f.valid, n + ": fold bakes (never refuses)");
        // Bake FIRST, then the absolute mirror the fit found replaces the working one:
        // the fold already carried the old mirror inside its reflected quad.
        m.off = off;
        applyBake(m, f);
        check(quadDist(checkQ, compose(m)) < 1e-3f, n + ": bake moves no corner");
    }

    static void acceptanceSequence() {
        Model m = new Model();
        flipSim(m, true, "seq flip1");
        flipSim(m, true, "seq flip2");
        foldSim(m, TransformQuad.TOP, "seq fold1");
        foldSim(m, TransformQuad.TOP, "seq fold2");
        foldSim(m, TransformQuad.LEFT, "seq fold3");
        flipSim(m, false, "seq flip3");
        check(maxAbs(m.off) < 1e-3f,
                "flip,flip,fold,fold,fold,flip: pin still at/near zero (got " + maxAbs(m.off) + ")");
    }

    static void foldFoldRestores() {
        Model m = new Model();
        foldSim(m, TransformQuad.TOP, "ff fold1");
        check(m.my && !m.mx, "one fold over top: mirrorY set");
        float rotAfterOne = m.rot;
        foldSim(m, TransformQuad.TOP, "ff fold2");
        check(!m.mx && !m.my, "two folds over top: mirror cleared again");
        check(!m.mx && !m.my, "two folds over top: mirror cleared again");
        checkNear(0f, m.rot, 0.05f, "two folds over top: rotation restored (got " + m.rot
                + " after " + rotAfterOne + ")");
        check(maxAbs(m.off) < 1e-3f, "two folds over top: pin back at zero");
    }

    static void degenerateRefused() {
        check(!TransformQuad.normalizePin(0f, 300f, new float[8], false, false).valid, "zero-width box: leave alone");
        check(!TransformQuad.normalizePin(400f, 300f, null, false, false).valid, "null pin: leave alone");
        float[] nan = new float[8];
        nan[3] = Float.NaN;
        check(!TransformQuad.normalizePin(400f, 300f, nan, false, false).valid, "NaN pin: leave alone");
        // Every corner pulled to the centre: a point, not a picture.
        float[] dot = {0.5f, 0.5f, -0.5f, 0.5f, -0.5f, -0.5f, 0.5f, -0.5f};
        check(!TransformQuad.normalizePin(400f, 300f, dot, false, false).valid, "collapsed quad: leave alone");
    }

    static void fuzzRoundTrip() {
        java.util.Random r = new java.util.Random(20260906L);
        float worst = 0f;
        for (int k = 0; k < 300; k++) {
            float w = 100f + r.nextFloat() * 900f;
            float h = 100f + r.nextFloat() * 900f;
            boolean mx = r.nextBoolean(), my = r.nextBoolean();
            float[] off = new float[8];
            int kind = k % 3;
            for (int i = 0; i < 8; i++) {
                if (kind == 0) off[i] = 0f;                            // exact parallelogram
                else if (kind == 1) off[i] = (r.nextFloat() - 0.5f) * 0.1f;   // nudged
                else off[i] = (r.nextFloat() - 0.5f) * 1.2f;            // hard distortion
            }
            if (kind == 0) {
                // Random affine (rotation + anisotropic scale) written as pin.
                double a = r.nextFloat() * Math.PI * 2;
                float sx = 0.5f + r.nextFloat() * 2f, sy = 0.5f + r.nextFloat() * 2f;
                float cs = (float) Math.cos(a), sn = (float) Math.sin(a);
                float[] bx = {-w / 2, w / 2, w / 2, -w / 2};
                float[] by = {-h / 2, -h / 2, h / 2, h / 2};
                for (int i = 0; i < 4; i++) {
                    float rx = cs * sx * bx[i] - sn * sy * by[i];
                    float ry = sn * sx * bx[i] + cs * sy * by[i];
                    off[i * 2] = (rx - bx[i]) / w;
                    off[i * 2 + 1] = (ry - by[i]) / h;
                }
            }
            PinNormalize f = TransformQuad.normalizePin(w, h, off, mx, my);
            if (!f.valid) continue;   // degenerate draws are allowed to stay unbaked
            float err = recomposeErr(w, h, off, mx, my, f);
            worst = Math.max(worst, err);
        }
        check(worst < 1e-2f, "fuzz: 300 quads (mirrored too) recompose within 0.01px (worst " + worst + ")");
    }

    public static void main(String[] a) {
        complaintArithmetic();
        identity();
        flipBakes();
        foldBakes();
        diagonalFoldBakes();
        rotateAndScaleBake();
        nudgeStaysSmall();
        realTrapezoidSurvives();
        acceptanceSequence();
        foldFoldRestores();
        degenerateRefused();
        fuzzRoundTrip();
        checkNear(30f, TransformQuad.norm180(750f), 1e-4f, "norm180 folds 750 to 30");
        checkNear(180f, TransformQuad.norm180(-180f), 1e-4f, "norm180 is deterministic at -180");
        System.out.println(fails == 0 ? "ALL GREEN" : (fails + " FAILED"));
        if (fails != 0) System.exit(1);
    }
}
