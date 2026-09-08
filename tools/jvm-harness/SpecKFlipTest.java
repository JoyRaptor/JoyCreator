import com.fadcam.ui.faditor.transform.TransformQuad;
import com.fadcam.ui.faditor.transform.TransformQuad.PinNormalize;

/**
 * SPEC K follow-up (JoyRaptor 2026-09-07): a corner flip must mirror about the CENTRAL
 * axis with handles == picture, and the commit bake must preserve it.
 *
 * <p>Production architecture under test (all three must move together):
 * <ol>
 *   <li>flip = flag toggle + stored rotation negated ({@code M.R(d) = R(-d).M} — the
 *       mirror of a tilt visibly tilts the other way), pins untouched;</li>
 *   <li>every pivot fold/anchor is mirror-aware (the stored offset, mirrored by the
 *       flags) — read, write, View, GL, export, bake and verify;</li>
 *   <li>both preview paths assemble mirror.pin.base, the export's order.</li>
 * </ol>
 * With all three, a flip is the EXACT central mirror at every pivot, rotation,
 * distortion and mirror state, and flipping twice restores bit-for-bit. The old
 * flag-plus-negate variant was off by twice the DISTORTION (300/300 fuzz failures,
 * up to ~400% of picture size); flag-only without rotation negation is off by up
 * to twice the PICTURE (the mirrored tilt cannot be drawn at the old angle).
 */
public class SpecKFlipTest {
    static int fails = 0;
    static void check(boolean c, String n) {
        System.out.println((c ? "PASS  " : "FAIL  ") + n);
        if (!c) fails++;
    }

    static boolean isFlat(float[] o) {
        for (float v : o) if (Math.abs(v) > 1e-5f) return false;
        return true;
    }

    static class Pose {
        float cx, cy, w, h, th, pivx = 0.5f, pivy = 0.5f;
        boolean mx, my;
        float[] off = new float[8];
    }

    static Pose copy(Pose p) {
        Pose q = new Pose();
        q.cx = p.cx; q.cy = p.cy; q.w = p.w; q.h = p.h; q.th = p.th;
        q.pivx = p.pivx; q.pivy = p.pivy; q.mx = p.mx; q.my = p.my;
        q.off = p.off.clone();
        return q;
    }

    static float[] deltaOf(Pose p, float w, float h, float[] off) {
        float u = p.pivx, v = p.pivy;
        if (isFlat(off)) return new float[]{(u - 0.5f) * w, (v - 0.5f) * h};
        float[] wx = new float[4];
        TransformQuad.pivotWeights(u, v, wx);
        float[] bx = {-w / 2f, w / 2f, w / 2f, -w / 2f};
        float[] by = {-h / 2f, -h / 2f, h / 2f, h / 2f};
        float dx = 0f, dy = 0f;
        for (int i = 0; i < 4; i++) {
            dx += wx[i] * (bx[i] + off[i * 2] * w);
            dy += wx[i] * (by[i] + off[i * 2 + 1] * h);
        }
        return new float[]{dx, dy};
    }

    /** Presented destination quad, pose units relative to pose centre.
     * Mirror-aware fold: Q = (I-R).M.d + R.M.(b+o.s); centre pivots never fold. */
    static float[] destQuad(Pose p) {
        double rad = Math.toRadians(p.th);
        float c = (float) Math.cos(rad), s = (float) Math.sin(rad);
        float[] d = deltaOf(p, p.w, p.h, p.off);
        float smx = p.mx ? -1f : 1f, smy = p.my ? -1f : 1f;
        boolean centre = p.pivx == 0.5f && p.pivy == 0.5f;
        float mdx = centre ? 0f : smx * d[0], mdy = centre ? 0f : smy * d[1];
        float[] bx = {-p.w / 2f, p.w / 2f, p.w / 2f, -p.w / 2f};
        float[] by = {-p.h / 2f, -p.h / 2f, p.h / 2f, p.h / 2f};
        float[] q = new float[8];
        for (int i = 0; i < 4; i++) {
            float lx = smx * (bx[i] + p.off[i * 2] * p.w);
            float ly = smy * (by[i] + p.off[i * 2 + 1] * p.h);
            q[i * 2] = (mdx - (c * mdx - s * mdy)) + (c * lx - s * ly);
            q[i * 2 + 1] = (mdy - (s * mdx + c * mdy)) + (s * lx + c * ly);
        }
        return q;
    }

    static float[] mirrorX(float[] q) {
        float[] m = new float[8];
        for (int i = 0; i < 4; i++) { m[i * 2] = -q[i * 2]; m[i * 2 + 1] = q[i * 2 + 1]; }
        return m;
    }

    static float[] mirrorY(float[] q) {
        float[] m = new float[8];
        for (int i = 0; i < 4; i++) { m[i * 2] = q[i * 2]; m[i * 2 + 1] = -q[i * 2 + 1]; }
        return m;
    }

    static float dist(float[] a, float[] b) {
        float m = 0f;
        for (int i = 0; i < 8; i++) m = Math.max(m, Math.abs(a[i] - b[i]));
        return m;
    }

    static float maxAbs(float[] x) {
        float m = 0f;
        for (float v : x) m = Math.max(m, Math.abs(v));
        return m;
    }

    /** Host flip, unarmed path (production): toggle flag, negate rotation, pins
     * untouched. Skipped at exactly 0 like the host. */
    static void hostFlipH(Pose p) {
        p.mx = !p.mx;
        if (p.th != 0f) p.th = -p.th;
    }

    static void hostFlipV(Pose p) {
        p.my = !p.my;
        if (p.th != 0f) p.th = -p.th;
    }
    /** Host tryNormalizeOnCommit replica (static pose, mirror-aware anchors).
     * Production contract: bakes preserve the ABSOLUTE picture (teleports fail
     * here); recentrings beyond 3 picture sizes walk away instead. */
    static boolean hostBake(Pose p) {
        float w = p.w, h = p.h;
        float[] pins0 = p.off.clone();
        PinNormalize fit = TransformQuad.normalizePin(w, h, pins0, p.mx, p.my);
        if (fit == null || !fit.valid || !fit.baked) return false;
        float rot0 = p.th;
        float newRot = rot0 + fit.rotDeltaDeg;
        double r0 = Math.toRadians(rot0);
        float c0 = (float) Math.cos(r0), s0 = (float) Math.sin(r0);
        float ncx = p.cx + c0 * fit.tx - s0 * fit.ty;
        float ncy = p.cy + s0 * fit.tx + c0 * fit.ty;
        float mb0x = p.mx ? -1f : 1f, mb0y = p.my ? -1f : 1f;
        boolean wasCentre = p.pivx == 0.5f && p.pivy == 0.5f;
        float[] o0 = deltaOf(p, w, h, pins0);
        o0[0] = wasCentre ? 0f : o0[0] * mb0x; o0[1] = wasCentre ? 0f : o0[1] * mb0y;
        Pose tmp = copy(p);
        tmp.off = fit.residual.clone();
        float sm1x = fit.mirrorX ? -1f : 1f, sm1y = fit.mirrorY ? -1f : 1f;
        float[] o1 = deltaOf(tmp, fit.newW, fit.newH, fit.residual);
        o1[0] = wasCentre ? 0f : o1[0] * sm1x; o1[1] = wasCentre ? 0f : o1[1] * sm1y;
        double r1 = Math.toRadians(newRot);
        float c1 = (float) Math.cos(r1), s1 = (float) Math.sin(r1);
        ncx += (o0[0] - (c0 * o0[0] - s0 * o0[1])) - (o1[0] - (c1 * o1[0] - s1 * o1[1]));
        ncy += (o0[1] - (s0 * o0[0] + c0 * o0[1])) - (o1[1] - (s1 * o1[0] + c1 * o1[1]));
        if (Math.hypot(ncx - p.cx, ncy - p.cy) > 3f * Math.max(w, h)) return false;
        p.cx = ncx; p.cy = ncy;
        p.th = newRot;
        p.w = fit.newW; p.h = fit.newH;
        p.mx = fit.mirrorX; p.my = fit.mirrorY;
        p.off = fit.residual.clone();
        return true;
    }

    /** Host pose-centre recovery replica (stable inside a distort gesture). */
    static float[] recoverCentre(Pose p) {
        float[] cf = foldedCentreOf(p);
        if (isNeutralP(p) || p.th == 0f) return new float[]{cf[0], cf[1]};
        double radU = Math.toRadians(-p.th);
        float uc = (float) Math.cos(radU), us = (float) Math.sin(radU);
        double radP = Math.toRadians(p.th);
        float pc = (float) Math.cos(radP), ps = (float) Math.sin(radP);
        float smx = p.mx ? -1f : 1f, smy = p.my ? -1f : 1f;
        float[] d = deltaOf(p, p.w, p.h, p.off);
        float pvx = cf[0] + pc * smx * d[0] - ps * smy * d[1];
        float pvy = cf[1] + ps * smx * d[0] + pc * smy * d[1];
        float dx = cf[0] - pvx, dy = cf[1] - pvy;
        return new float[]{pvx + uc * dx - us * dy, pvy + us * dx + uc * dy};
    }

    static float[] foldedCentreOf(Pose p) {
        if (isNeutralP(p) || p.th == 0f) return new float[]{p.cx, p.cy};
        double rad = Math.toRadians(p.th);
        float c = (float) Math.cos(rad), s = (float) Math.sin(rad);
        float smx = p.mx ? -1f : 1f, smy = p.my ? -1f : 1f;
        float[] d = deltaOf(p, p.w, p.h, p.off);
        float pvx = p.cx + smx * d[0], pvy = p.cy + smy * d[1];
        float vx = p.cx - pvx, vy = p.cy - pvy;
        return new float[]{pvx + vx * c - vy * s, pvy + vx * s + vy * c};
    }

    static boolean isNeutralP(Pose p) {
        // Production: centre pivot never folds, however pinned (SPEC K catapult fix).
        return p.pivx == 0.5f && p.pivy == 0.5f;
    }

    /** WRITE half = the real production solve. Null = refused (model untouched).
     * Mirrors the production range gate exactly: over-range pins pass only when they
     * bake to flags with a flat residual (folds at rotation). */
    static float[] writeQuad(Pose p, float[] quad8) {
        float[] qc = recoverCentre(p);
        float[] next = new float[8];
        boolean ok = TransformQuad.solvePinForQuad(quad8, qc[0], qc[1], p.w, p.h, p.th,
                p.mx ? -1f : 1f, p.my ? -1f : 1f, p.pivx, p.pivy, next);
        if (!ok) return null;
        boolean range = true;
        for (float v : next) {
            if (Float.isNaN(v) || Float.isInfinite(v) || Math.abs(v) > 8f + 1e-4f) range = false;
        }
        if (!range) {
            PinNormalize fit = TransformQuad.normalizePin(p.w, p.h, next, p.mx, p.my);
            boolean clears = fit != null && fit.valid && fit.baked && isFlat(fit.residual);
            if (!clears) return null;
        }
        return next;
    }

    /** Absolute presented quad (pose centre + relative destination). */
    static float[] absQuad(Pose p) {
        float[] rel = destQuad(p), out = new float[8];
        for (int i = 0; i < 4; i++) { out[i * 2] = p.cx + rel[i * 2]; out[i * 2 + 1] = p.cy + rel[i * 2 + 1]; }
        return out;
    }

    public static void main(String[] a) {
        // 1. JoyRaptor's live item, both flip axes: EXACT central mirror + clean bake.
        {
            for (int axis = 0; axis < 2; axis++) {
                Pose p = new Pose();
                p.cx = 0f; p.cy = 0f; p.w = 300f; p.h = 200f; p.th = 365.2412f;
                p.pivx = 0.5f; p.pivy = 0.0f;
                p.off = new float[]{0.01633418f, 0.023442272f, -0.0027677903f, 0.023442322f,
                        -0.0027679296f, -0.1386609f, 0.17178443f, 0.0300554f};
                float[] before = destQuad(p);
                // SPEC L: the bake below now runs on this distorted pin (it used to walk
                // away from any non-parallelogram), and a bake may RECENTRE the pose. So the
                // double-flip restore has to be judged in ABSOLUTE pixels — the relative
                // quad is measured from a centre the bake is entitled to move. The picture
                // is what must come back, and it does.
                float[] beforeAbs = absQuad(p);
                if (axis == 0) hostFlipH(p); else hostFlipV(p);
                float[] want = axis == 0 ? mirrorX(before) : mirrorY(before);
                float d = dist(destQuad(p), want);
                check(d < 0.5f, "joyraptor bible flip-" + (axis == 0 ? "H" : "V")
                        + ": exact central mirror (drift " + d + "px)");
                // PRODUCTION never bakes on a flip commit — CornerPinTransformHost
                // .commitGesture returns early for "Flip" — so the double-flip identity is
                // measured on this untouched branch. The bake is exercised separately below
                // (it must preserve the picture, and after SPEC L it always runs).
                Pose q = copy(p);
                float[] pre = absQuad(p);
                boolean baked = hostBake(p);
                float db = dist(pre, absQuad(p));
                check(!baked || db < 1f, "joyraptor bible flip-" + (axis == 0 ? "H" : "V")
                        + ": bake preserves (baked=" + baked + ", drift " + db + "px)");
                // SPEC L — the recentring a legacy pin causes is ONE-TIME: the residual's
                // corner mean is exactly zero by construction, so re-baking the baked pose
                // finds no translation left to move. (This is why flips stay exact from the
                // first bake onward, and why the pin cannot accumulate translation again.)
                float[] preRe = absQuad(p);
                float cxRe = p.cx, cyRe = p.cy;
                hostBake(p);
                check(dist(preRe, absQuad(p)) < 0.5f
                                && Math.hypot(p.cx - cxRe, p.cy - cyRe) < 0.5f,
                        "joyraptor bible flip-" + (axis == 0 ? "H" : "V")
                                + ": re-baking a baked pose moves nothing (the migration is one-time)");
                float thAfterOne = q.th;
                boolean mAfterOne = axis == 0 ? q.mx : q.my;
                float[] pinsAfterOne = q.off.clone();
                if (axis == 0) hostFlipH(q); else hostFlipV(q);
                check((axis == 0 ? q.mx : q.my) != mAfterOne, "second flip toggles back");
                check(q.th == -thAfterOne, "second flip restores the angle");
                float dd = dist(absQuad(q), beforeAbs);
                check(dd < 0.5f, "double flip restores the picture (drift " + dd + "px)");
                check(java.util.Arrays.equals(pinsAfterOne, q.off), "flips never touch the pins");
            }
        }
        // 2. Fuzz, both axes: EVERY pivot (corners included), angle, distortion and
        // incoming mirror state must mirror exactly and bake-preserve.
        {
            java.util.Random r = new java.util.Random(20260907L);
            int bad = 0;
            float worst = 0f;
            for (int k = 0; k < 400; k++) {
                boolean horiz = r.nextBoolean();
                Pose p = new Pose();
                p.w = 100f + r.nextFloat() * 400f; p.h = 100f + r.nextFloat() * 400f;
                p.th = r.nextFloat() * 720f - 360f;
                float[] pivs = {0f, 0.5f, 1f};
                p.pivx = pivs[r.nextInt(3)]; p.pivy = pivs[r.nextInt(3)];
                if (r.nextBoolean()) p.mx = true;
                if (r.nextBoolean()) p.my = true;
                for (int i = 0; i < 8; i++) p.off[i] = (r.nextFloat() - 0.5f) * 0.8f;
                float[] before = destQuad(p);
                if (horiz) hostFlipH(p); else hostFlipV(p);
                float[] want = horiz ? mirrorX(before) : mirrorY(before);
                float d = dist(destQuad(p), want);
                worst = Math.max(worst, d);
                if (d > 0.5f) {
                    bad++;
                    if (bad < 4) System.out.println("    flip drift " + d
                            + (horiz ? " H" : " V") + " th=" + p.th
                            + " piv=(" + p.pivx + "," + p.pivy + ") mx=" + p.mx + " my=" + p.my);
                }
                float[] pre = absQuad(p);
                boolean baked = hostBake(p);
                if (baked && dist(pre, absQuad(p)) >= 1f) {
                    bad++;
                    if (bad < 6) System.out.println("    bake moved after flip th=" + p.th);
                }
            }
            check(bad == 0, "fuzz 400 H/V flips exact + bakes preserve (bad " + bad + ", worst " + worst + "px)");
        }
        // 3. Fold chains never corrupt: flat ping-pongs clear through the bake forever;
        // wall-level distorted folds either store bounded pins or refuse with the model
        // untouched (the "folding stopped" report must always be safe, never garbage).
        {
            int bad = 0;
            // 3a. Flat chains at awkward pivots/angles: 12 alternating folds, zero refusals.
            float[][] starts = {{0.5f, 0.5f, 30f}, {0.5f, 0.0f, 365.2412f}, {0.0f, 0.0f, -45f}};
            for (float[] st : starts) {
                Pose p = new Pose();
                p.cx = 500f; p.cy = 500f; p.w = 300f; p.h = 200f;
                p.pivx = st[0]; p.pivy = st[1]; p.th = st[2];
                for (int f = 0; f < 12; f++) {
                    int e = f % 4;
                    float[] q = absQuad(p);
                    if (!TransformQuad.foldOverEdge(q, e)) { bad++; continue; }
                    float[] pins = writeQuad(p, q);
                    if (pins == null) {
                        bad++;
                        System.out.println("    flat fold refused edge " + e + " round " + f);
                        continue;
                    }
                    p.off = pins;
                    float[] preBake = absQuad(p);
                    hostBake(p);
                    if (dist(preBake, absQuad(p)) >= 1f) {
                        bad++;
                        System.out.println("    flat fold bake moved edge " + e + " round " + f);
                    }
                    // Post-bake state must be back inside the pin budget (folds clear
                    // to flags; only genuine distortion may remain, and it fits).
                    for (float v : p.off) {
                        if (!Float.isFinite(v) || Math.abs(v) > 8f + 1e-4f) {
                            bad++;
                            System.out.println("    post-bake pin out of range " + v);
                            break;
                        }
                    }
                }
            }
            check(bad == 0, "flat fold chains: 36 folds, zero refusals, bakes preserve (bad " + bad + ")");
            // 3b. JoyRaptor's live wall-level distortion: folds store bounded pins or refuse
            // cleanly — the model is never polluted.
            {
                Pose p = new Pose();
                p.cx = 500f; p.cy = 500f; p.w = 300f; p.h = 200f;
                p.pivx = 0.5f; p.pivy = 0.0f; p.th = 180f;
                p.off = new float[]{0.15306601f, -0.48712817f, 0.0004953141f, 0.00038562485f,
                        0.54717404f, -1.1084915f, 1.0808861f, -1.9525954f};
                int refused = 0, stored = 0;
                for (int e = 0; e < 4; e++) {
                    float[] before = p.off.clone();
                    float[] q = absQuad(p);
                    if (!TransformQuad.foldOverEdge(q, e)) continue;
                    float[] pins = writeQuad(p, q);
                    if (pins == null) {
                        refused++;
                        if (dist(before, p.off) != 0f) {
                            bad++;
                            System.out.println("    refusal polluted the model edge " + e);
                        }
                        continue;
                    }
                    stored++;
                    for (float v : pins) {
                        if (!Float.isFinite(v) || Math.abs(v) > 8f + 1e-4f) {
                            bad++;
                            System.out.println("    garbage pin " + v + " edge " + e);
                        }
                    }
                    p.off = pins;
                }
                System.out.println("    wall folds: stored=" + stored + " refused=" + refused);
                check(bad == 0, "wall folds never pollute (bad " + bad + ")");
            }
            // 3c. Deep perspective (3x past the old ±2 wall): folds and bakes still
            // behave — the budget now has headroom for real work.
            {
                Pose p = new Pose();
                p.cx = 500f; p.cy = 500f; p.w = 300f; p.h = 200f;
                p.pivx = 0.5f; p.pivy = 0.5f; p.th = 10f;
                p.off = new float[]{0.9f, 0.15f, -0.9f, 0.15f, -0.45f, 0f, 0.45f, 0f};
                int badDeep = 0, storedDeep = 0, refusedDeep = 0;
                for (int e = 0; e < 4; e++) {
                    float[] before = p.off.clone();
                    float[] q = absQuad(p);
                    if (!TransformQuad.foldOverEdge(q, e)) continue;
                    float[] pins = writeQuad(p, q);
                    if (pins == null) {
                        refusedDeep++;
                        if (dist(before, p.off) != 0f) { badDeep++; }
                        continue;
                    }
                    storedDeep++;
                    for (float v : pins) {
                        if (!Float.isFinite(v) || Math.abs(v) > 8f + 1e-4f) { badDeep++; break; }
                    }
                    p.off = pins;
                    float[] preBake = absQuad(p);
                    hostBake(p);
                    if (dist(preBake, absQuad(p)) >= 1f) { badDeep++; }
                }
                System.out.println("    deep folds: stored=" + storedDeep + " refused=" + refusedDeep);
                check(badDeep == 0, "deep folds safe (bad " + badDeep + ")");
                check(storedDeep == 4, "deep folds all store inside the headroom");
            }
        }
        // 4. Preview assembly order: final must be mirror.pin.base (export order).
        // Pure-Java transcription of the android.graphics.Matrix op sequence
        // (preConcat: M'=M.other [source side]; postConcat: M'=other.M [dest side] —
        // pinned-unmirrored pictures have always rendered correctly through postConcat,
        // which pins that convention down). Old view code: preConcat(mirror) then
        // postConcat(pin) = pin.base.mirror. Fixed code: postConcat(pin) then
        // postConcat(mirror) = mirror.pin.base.
        {
            // base: uniform 2x + translate (stand-in rectToRect); pin: skew-ish; mirror: x-flip.
            float[][] base = {{2f, 0f, 10f}, {0f, 2f, 20f}, {0f, 0f, 1f}};
            float[][] pin = {{1f, 0.3f, 5f}, {0.1f, 1f, -3f}, {0.001f, 0.0005f, 1f}};
            float[][] mir = {{-1f, 0f, 100f}, {0f, 1f, 0f}, {0f, 0f, 1f}};
            float[][] want = mul(mir, mul(pin, base));
            float[][] oldCode = mul(pin, mul(base, mir));   // preConcat(mirror)+postConcat(pin)
            float[][] newCode = mul(mir, mul(pin, base));   // postConcat(pin)+postConcat(mirror)
            check(!eq(oldCode, want), "old assembly != export order (reproduces the shove)");
            check(eq(newCode, want), "fixed assembly == export order mirror.pin.base");
            // Flat+mirror through the fixed assembly == mirror.base exactly.
            float[][] eye = {{1f, 0f, 0f}, {0f, 1f, 0f}, {0f, 0f, 1f}};
            check(eq(mul(mir, base), mul(mir, mul(eye, base))), "flat mirror == mirror.base");
        }
        System.out.println(fails == 0 ? "ALL GREEN" : (fails + " FAILED"));
        if (fails != 0) System.exit(1);
    }

    // ── tiny 3x3 ──────────────────────────────────────────────────────
    static float[][] mul(float[][] x, float[][] y) {
        float[][] o = new float[3][3];
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                o[i][j] = x[i][0] * y[0][j] + x[i][1] * y[1][j] + x[i][2] * y[2][j];
        return o;
    }

    static boolean eq(float[][] x, float[][] y) {
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++)
                if (Math.abs(x[i][j] - y[i][j]) > 1e-4f) return false;
        return true;
    }
}
