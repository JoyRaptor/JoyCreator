import com.fadcam.ui.faditor.transform.TransformQuad;
import com.fadcam.ui.faditor.transform.TransformQuad.PinNormalize;

/**
 * SPEC L — stop objects escaping the canvas, at the source.
 *
 * <p>The fixture at the top of this file is not invented. It is the exact stored state of the
 * image {@code abaef109} read out of JoyRaptor's live Note 20 {@code project.json} on 2026-09-07,
 * the picture that had vanished off the canvas. It is the bug; a fix that does not repair it
 * is not a fix.</p>
 *
 * <p>Composition order under test — the one all three renderers implement:
 * {@code X = C + R(rot) . M . (B + off . s)}.</p>
 */
public class SpecLEscapeTest {

    static int fails = 0;

    static void check(boolean c, String n) {
        System.out.println((c ? "PASS  " : "FAIL  ") + n);
        if (!c) fails++;
    }

    static void note(String s) { System.out.println("      " + s); }

    static float maxAbs(float[] a) {
        float m = 0f;
        for (float v : a) m = Math.max(m, Math.abs(v));
        return m;
    }

    // ── JoyRaptor's live broken image, verbatim ──────────────────────────────────

    static final float FX_CENTER_X = -0.624f;
    static final float FX_CENTER_Y = 0.591f;
    static final float FX_ROT = 196.37f;
    static final boolean FX_FLIP_H = true;
    static final boolean FX_FLIP_V = false;
    static final float FX_SIZE = 0.353f;
    static final float[] FX_PIN = {
            -1.019f, 1.145f,    // TL
            -3.416f, 0.675f,    // TR  <- three and a half picture-widths
            -2.397f, -1.243f,   // BR
            -0.659f, -1.406f    // BL
    };

    /** The canvas the numbers were authored on (Note 20 preview, 9:16 fit). */
    static final float CANVAS_W = 1080f, CANVAS_H = 1920f;
    /** Drawn box: sizeFraction of the canvas width, 4:3 picture. */
    static final float BOX_W = FX_SIZE * CANVAS_W;
    static final float BOX_H = BOX_W * 3f / 4f;

    /** A whole pose: exactly the fields the model stores. */
    static class Pose {
        float cxN, cyN;          // centre, normalised to the canvas
        float w, h;              // drawn box, px
        float rot;               // degrees, raw winding
        boolean mx, my;          // mirror flags
        float[] off = new float[8];
    }

    static Pose fixture() {
        Pose p = new Pose();
        p.cxN = FX_CENTER_X;
        p.cyN = FX_CENTER_Y;
        p.w = BOX_W;
        p.h = BOX_H;
        p.rot = FX_ROT;
        p.mx = FX_FLIP_H;
        p.my = FX_FLIP_V;
        p.off = FX_PIN.clone();
        return p;
    }

    /** The drawn quad in canvas pixels — what the eye sees, what every renderer draws. */
    static float[] drawnQuad(Pose p) {
        float cx = p.cxN * CANVAS_W, cy = p.cyN * CANVAS_H;
        float smx = p.mx ? -1f : 1f, smy = p.my ? -1f : 1f;
        double rad = Math.toRadians(p.rot);
        float cs = (float) Math.cos(rad), sn = (float) Math.sin(rad);
        float[] bx = {-p.w / 2f, p.w / 2f, p.w / 2f, -p.w / 2f};
        float[] by = {-p.h / 2f, -p.h / 2f, p.h / 2f, p.h / 2f};
        float[] q = new float[8];
        for (int i = 0; i < 4; i++) {
            float lx = smx * (bx[i] + p.off[i * 2] * p.w);
            float ly = smy * (by[i] + p.off[i * 2 + 1] * p.h);
            q[i * 2] = cx + cs * lx - sn * ly;
            q[i * 2 + 1] = cy + sn * lx + cs * ly;
        }
        return q;
    }

    /**
     * CornerPinTransformHost.tryNormalizeOnCommit, centre-pivot path: the fit's pose-frame
     * translation rotates onto the centre, rotation ADDS, size and mirror are replaced and
     * the residual becomes the new pin.
     */
    static boolean bake(Pose p) {
        PinNormalize f = TransformQuad.normalizePin(p.w, p.h, p.off, p.mx, p.my);
        if (f == null || !f.valid || !f.baked) return false;
        double rad0 = Math.toRadians(p.rot);
        float c0 = (float) Math.cos(rad0), s0 = (float) Math.sin(rad0);
        float ncx = p.cxN * CANVAS_W + c0 * f.tx - s0 * f.ty;
        float ncy = p.cyN * CANVAS_H + s0 * f.tx + c0 * f.ty;
        p.cxN = ncx / CANVAS_W;
        p.cyN = ncy / CANVAS_H;
        p.rot = p.rot + f.rotDeltaDeg;
        p.w = f.newW;
        p.h = f.newH;
        p.mx = f.mirrorX;
        p.my = f.mirrorY;
        p.off = f.residual.clone();
        return true;
    }

    static float quadDist(float[] a, float[] b) {
        float worst = 0f;
        for (int i = 0; i < 4; i++) {
            worst = Math.max(worst, (float) Math.hypot(a[i * 2] - b[i * 2],
                    a[i * 2 + 1] - b[i * 2 + 1]));
        }
        return worst;
    }

    static String bbox(float[] q) {
        float l = q[0], r = q[0], t = q[1], b = q[1];
        for (int i = 1; i < 4; i++) {
            l = Math.min(l, q[i * 2]); r = Math.max(r, q[i * 2]);
            t = Math.min(t, q[i * 2 + 1]); b = Math.max(b, q[i * 2 + 1]);
        }
        return String.format("x %.0f..%.0f  y %.0f..%.0f", l, r, t, b);
    }

    // ── 1. The fixture ───────────────────────────────────────────────────────

    static void scottsFixture() {
        System.out.println("-- JoyRaptor's live broken image (abaef109), 2026-09-07 --");
        Pose p = fixture();
        note("box " + BOX_W + " x " + BOX_H + " px on a " + (int) CANVAS_W + "x"
                + (int) CANVAS_H + " canvas");

        // The spec's own reading: every corner is left of the box centre, in box widths.
        float[] xs = new float[4];
        float[] base = {-0.5f, 0.5f, 0.5f, -0.5f};
        for (int i = 0; i < 4; i++) xs[i] = base[i] + FX_PIN[i * 2];
        note(String.format("corner x in box widths: TL %.2f  TR %.2f  BR %.2f  BL %.2f",
                xs[0], xs[1], xs[2], xs[3]));
        check(xs[0] < 0 && xs[1] < 0 && xs[2] < 0 && xs[3] < 0,
                "fixture: every corner sits LEFT of the box that supposedly holds it");

        float[] before = drawnQuad(p);
        float pinBefore = maxAbs(p.off);
        note("BEFORE  worst pin offset = " + pinBefore);
        note("BEFORE  centre = (" + p.cxN + ", " + p.cyN + ")  drawn bbox " + bbox(before));
        check(pinBefore > 3.4f, "fixture: the stored pin really is 3.4+ picture-widths");

        boolean baked = bake(p);
        check(baked, "SPEC L: the trapezoid BAKES (before this spec it never once did)");

        float[] after = drawnQuad(p);
        float pinAfter = maxAbs(p.off);
        note("AFTER   worst pin offset = " + pinAfter);
        note("AFTER   centre = (" + p.cxN + ", " + p.cyN + ")  drawn bbox " + bbox(after));
        note("AFTER   size " + p.w + " x " + p.h + "  rot " + p.rot
                + "  mirror " + p.mx + "," + p.my);

        // THE point of the whole spec: representation changed, picture did not.
        float moved = quadDist(before, after);
        note("picture moved by " + moved + " px across the bake");
        check(moved < 1f, "the bake does not move the picture by even one pixel");

        check(pinAfter < 0.5f, "residual is under 0.5 (spec target); got " + pinAfter);
        check(pinAfter < pinBefore / 5f, "residual is a small fraction of what was stored");

        // The centre is now WHERE THE PICTURE IS, which is what makes the travel clamp work.
        float qcx = 0f, qcy = 0f;
        for (int i = 0; i < 4; i++) { qcx += after[i * 2] / 4f; qcy += after[i * 2 + 1] / 4f; }
        float centreErr = (float) Math.hypot(qcx - p.cxN * CANVAS_W, qcy - p.cyN * CANVAS_H);
        note("distance from stored centre to the drawn quad's centroid = " + centreErr + " px");
        check(centreErr < 1f,
                "the stored centre now IS the picture's centre — the travel clamp guards the picture again");

        // And before the bake it emphatically was not.
        Pose q = fixture();
        float[] q0 = drawnQuad(q);
        float bcx = 0f, bcy = 0f;
        for (int i = 0; i < 4; i++) { bcx += q0[i * 2] / 4f; bcy += q0[i * 2 + 1] / 4f; }
        float gap = (float) Math.hypot(bcx - q.cxN * CANVAS_W, bcy - q.cyN * CANVAS_H);
        note("...it was " + gap + " px away before the bake ("
                + String.format("%.2f", gap / BOX_W) + " picture-widths)");
        check(gap > BOX_W, "fixture: the pin really had translated the picture off its own box");

        // The bake is representation-only, so the picture is still exactly where it was —
        // off canvas. Part 2 is what brings it home, and it moves the POSE, not the shape.
        float[] fix = new float[2];
        float[] shape = drawnQuad(p);
        check(TransformQuad.quadEscapeFix(shape, 0f, 0f, CANVAS_W, CANVAS_H,
                        TransformQuad.QUAD_MIN_VISIBLE_FRAC, fix),
                "Part 2 sees the fixture is off canvas");
        p.cxN += fix[0] / CANVAS_W;
        p.cyN += fix[1] / CANVAS_H;
        float[] home = drawnQuad(p);
        note("Part 2 shift = " + fix[0] + ", " + fix[1] + "  ->  bbox " + bbox(home));
        check(!TransformQuad.quadEscapeFix(home, 0f, 0f, CANVAS_W, CANVAS_H,
                        TransformQuad.QUAD_MIN_VISIBLE_FRAC, fix),
                "JoyRaptor's picture is back in frame");
        for (int i = 0; i < 4; i++) {
            check(Math.abs((home[i * 2] - shape[i * 2]) - (home[0] - shape[0])) < 1e-2f
                            && Math.abs((home[i * 2 + 1] - shape[i * 2 + 1])
                                    - (home[1] - shape[1])) < 1e-2f,
                    "recovery corner " + i + ": pure translation, the authored shape untouched");
        }
        check(maxAbs(p.off) == pinAfter, "recovery spends no pin budget at all");
    }

    // ── 2. The Part 2 backstop, on its own ───────────────────────────────────

    static void escapeClamp() {
        System.out.println("-- Part 2: the drawn-quad backstop --");
        float[] fix = new float[2];
        float L = 0f, T = 0f, R = 1080f, B = 1920f;

        float[] inside = {100f, 100f, 400f, 100f, 400f, 400f, 100f, 400f};
        check(!TransformQuad.quadEscapeFix(inside, L, T, R, B, 0.15f, fix),
                "a quad fully on canvas needs no fix");

        float[] mostlyOff = {-900f, 100f, -600f, 100f, -600f, 400f, -900f, 400f};
        check(TransformQuad.quadEscapeFix(mostlyOff, L, T, R, B, 0.15f, fix),
                "a quad off the left edge needs a fix");
        note("fix = " + fix[0] + ", " + fix[1]);
        check(fix[1] == 0f, "the fix is per-axis: nothing off vertically, nothing moved vertically");
        float[] moved = mostlyOff.clone();
        for (int i = 0; i < 4; i++) { moved[i * 2] += fix[0]; moved[i * 2 + 1] += fix[1]; }
        check(!TransformQuad.quadEscapeFix(moved, L, T, R, B, 0.15f, fix),
                "one application is enough — the fixed quad satisfies the rule");

        float[] justEnough = {-255f, 100f, 45f, 100f, 45f, 400f, -255f, 400f};   // 45/300 = 15%
        check(!TransformQuad.quadEscapeFix(justEnough, L, T, R, B, 0.15f, fix),
                "exactly 15% visible is enough (the rule is not off by a hair)");

        // A picture larger than the canvas must still be satisfiable.
        float[] huge = {-4000f, -6000f, 4000f, -6000f, 4000f, 6000f, -4000f, 6000f};
        TransformQuad.quadEscapeFix(huge, L, T, R, B, 0.15f, fix);
        note("a picture bigger than the canvas: fix = " + fix[0] + ", " + fix[1]);
        check(fix[0] == 0f && fix[1] == 0f,
                "a picture larger than the canvas covers it and is never shoved");

        // The shape is never touched — only translated.
        float[] before = mostlyOff.clone();
        TransformQuad.quadEscapeFix(mostlyOff, L, T, R, B, 0.15f, fix);
        float dx0 = before[2] - before[0], dy0 = before[3] - before[1];
        float[] after = before.clone();
        for (int i = 0; i < 4; i++) { after[i * 2] += fix[0]; after[i * 2 + 1] += fix[1]; }
        check(after[2] - after[0] == dx0 && after[3] - after[1] == dy0,
                "the fix translates and never reshapes");

        check(!TransformQuad.quadEscapeFix(null, L, T, R, B, 0.15f, fix), "null quad refused");
        float[] nan = inside.clone();
        nan[3] = Float.NaN;
        check(!TransformQuad.quadEscapeFix(nan, L, T, R, B, 0.15f, fix), "NaN quad refused");
        check(!TransformQuad.quadEscapeFix(inside, 0f, 0f, 0f, 0f, 0.15f, fix),
                "degenerate rect refused");
    }

    // ── 3. Forty gestures: no growth, no escape ──────────────────────────────

    /** Re-express a dragged (presented) quad as pins against the current pose. */
    static void readBackPins(Pose p, float[] quad) {
        float cx = p.cxN * CANVAS_W, cy = p.cyN * CANVAS_H;
        float smx = p.mx ? -1f : 1f, smy = p.my ? -1f : 1f;
        double rad = Math.toRadians(-p.rot);
        float cs = (float) Math.cos(rad), sn = (float) Math.sin(rad);
        float[] bx = {-p.w / 2f, p.w / 2f, p.w / 2f, -p.w / 2f};
        float[] by = {-p.h / 2f, -p.h / 2f, p.h / 2f, p.h / 2f};
        for (int i = 0; i < 4; i++) {
            float dx = quad[i * 2] - cx, dy = quad[i * 2 + 1] - cy;
            float ux = cs * dx - sn * dy, uy = sn * dx + cs * dy;
            p.off[i * 2] = (smx * ux - bx[i]) / p.w;
            p.off[i * 2 + 1] = (smy * uy - by[i]) / p.h;
        }
    }

    static void fortyGestures() {
        System.out.println("-- Forty gestures: fold / flip / move / scale / distort --");
        Pose p = new Pose();
        p.cxN = 0.5f; p.cyN = 0.5f;
        p.w = 400f; p.h = 300f;
        // Start from a genuine keystone — the shape that never used to bake.
        p.off = new float[]{0.18f, 0.06f, -0.18f, 0.06f, -0.09f, -0.03f, 0.09f, -0.03f};

        float worstPin = maxAbs(p.off);
        float worstMove = 0f;
        int refusals = 0, clampFires = 0;
        int[] clampByKind = new int[5];
        float worstRescue = 0f;
        java.util.Random r = new java.util.Random(20260908L);
        float[] fix = new float[2];

        for (int k = 0; k < 40; k++) {
            float[] q = drawnQuad(p);
            switch (k % 5) {
                case 0: {   // FOLD over an edge
                    float[] f = q.clone();
                    if (!TransformQuad.foldOverEdge(f, k % 4)) { refusals++; break; }
                    readBackPins(p, f);
                    break;
                }
                case 1: {   // FLIP: the production unarmed path (flag + negated rotation)
                    p.mx = !p.mx;
                    if (p.rot != 0f) p.rot = -p.rot;
                    break;
                }
                case 2: {   // MOVE
                    p.cxN += (r.nextFloat() - 0.5f) * 0.24f;
                    p.cyN += (r.nextFloat() - 0.5f) * 0.24f;
                    break;
                }
                case 3: {   // SCALE about the opposite corner
                    float[] s = q.clone();
                    TransformQuad.scaleCornerApply(s, q, TransformQuad.BR,
                            0.7f + r.nextFloat() * 0.8f, 0.7f + r.nextFloat() * 0.8f);
                    readBackPins(p, s);
                    break;
                }
                default: {  // DISTORT: a real corner nudge, the thing the pin is FOR
                    float[] d = q.clone();
                    int c = r.nextInt(4);
                    d[c * 2] += (r.nextFloat() - 0.5f) * 0.25f * p.w;
                    d[c * 2 + 1] += (r.nextFloat() - 0.5f) * 0.25f * p.h;
                    if (!TransformQuad.isValid(d)) { refusals++; break; }
                    readBackPins(p, d);
                    break;
                }
            }
            // Production commit: bake, then the Part 2 backstop.
            float[] gestured = drawnQuad(p);
            if (bake(p)) worstMove = Math.max(worstMove, quadDist(gestured, drawnQuad(p)));
            float[] drawn = drawnQuad(p);
            if (TransformQuad.quadEscapeFix(drawn, 0f, 0f, CANVAS_W, CANVAS_H,
                    TransformQuad.QUAD_MIN_VISIBLE_FRAC, fix)) {
                clampFires++;
                clampByKind[k % 5]++;
                worstRescue = Math.max(worstRescue, (float) Math.hypot(fix[0], fix[1]));
                p.cxN += fix[0] / CANVAS_W;
                p.cyN += fix[1] / CANVAS_H;
            }
            worstPin = Math.max(worstPin, maxAbs(p.off));
        }

        note("worst pin offset over 40 gestures = " + worstPin + " (wall is 8.0)");
        note("worst picture movement caused by a BAKE = " + worstMove + " px");
        note("gestures the geometry refused = " + refusals);
        note("times the Part 2 backstop fired = " + clampFires
                + "  [fold " + clampByKind[0] + ", flip " + clampByKind[1]
                + ", move " + clampByKind[2] + ", scale " + clampByKind[3]
                + ", distort " + clampByKind[4] + "]");
        note("worst rescue distance = " + worstRescue + " px (picture is ~"
                + (int) p.w + " px across)");
        // Honest reading: a FOLD is a reflection over an edge, so it legitimately relocates
        // the picture by up to its own size, and near the canvas edge that lands off canvas.
        // The backstop catching THAT is the backstop doing its job. What must never happen
        // is a rescue on the scale of the escapes this spec is about — whole frame-widths.
        check(worstRescue < 2f * Math.max(p.w, p.h),
                "the backstop only ever nudges by about a picture size, never a frame-width —"
                        + " it is not papering over a Part 1 failure");
        check(worstPin < 1f, "the pin never grows past 1.0 — nothing is spending budget on translation");
        check(worstMove < 1f, "no bake in the whole run moved the picture by a pixel");

        float[] end = drawnQuad(p);
        check(!TransformQuad.quadEscapeFix(end, 0f, 0f, CANVAS_W, CANVAS_H,
                TransformQuad.QUAD_MIN_VISIBLE_FRAC, fix),
                "after forty gestures the picture is still on the canvas");
    }

    // ── 4. Preview/export parity: a keystone is still a keystone ─────────────

    /**
     * The change is one of REPRESENTATION. Both renderers draw from
     * {@code (centre, size, rotation, mirror, pin)} through the same equation, so the proof
     * that they still agree is that the equation's OUTPUT is unchanged — every corner, to
     * sub-pixel, before and after the bake, for a hundred random keystones.
     */
    static void representationOnly() {
        System.out.println("-- A keystone is still the same keystone (preview == export) --");
        java.util.Random r = new java.util.Random(4242L);
        float worst = 0f;
        int baked = 0, skipped = 0;
        for (int k = 0; k < 200; k++) {
            Pose p = new Pose();
            p.cxN = 0.2f + r.nextFloat() * 0.6f;
            p.cyN = 0.2f + r.nextFloat() * 0.6f;
            p.w = 120f + r.nextFloat() * 600f;
            p.h = 120f + r.nextFloat() * 600f;
            p.rot = (r.nextFloat() - 0.5f) * 720f;
            p.mx = r.nextBoolean();
            p.my = r.nextBoolean();
            // A trapezoid: opposite edges deliberately unequal, i.e. real perspective.
            float t = 0.05f + r.nextFloat() * 0.45f;
            p.off = new float[]{t, 0f, -t, 0f, -t * 0.4f, 0f, t * 0.4f, 0f};
            float[] before = drawnQuad(p);
            if (!bake(p)) { skipped++; continue; }
            baked++;
            worst = Math.max(worst, quadDist(before, drawnQuad(p)));
        }
        note("200 random keystones: " + baked + " baked, " + skipped + " left alone");
        note("worst corner movement across the bake = " + worst + " px");
        check(baked > 190, "essentially every keystone bakes now");
        check(worst < 0.05f, "every corner of every keystone lands where it already was");
    }

    public static void main(String[] a) {
        scottsFixture();
        escapeClamp();
        fortyGestures();
        representationOnly();
        System.out.println(fails == 0 ? "ALL GREEN" : (fails + " FAILED"));
        if (fails != 0) System.exit(1);
    }
}
