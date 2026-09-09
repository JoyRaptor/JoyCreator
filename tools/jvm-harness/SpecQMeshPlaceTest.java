import com.fadcam.ui.faditor.transform.mesh.MeshPlacement;

/**
 * SPEC Q — an IDENTITY bend must land exactly where the flat draw lands, at every angle.
 *
 * <p><b>The measurement this file exists to keep.</b> JoyRaptor, 2026-09-08: a bent picture rendered
 * about a centimetre down-and-right of its own helper frame at 45 degrees, "almost aligned" when
 * nearly upright, and worst at 90 degrees clockwise. Undoing the bend made the frame hug the
 * picture exactly, so the fault was in the mesh render path alone, with the deformation removed.
 *
 * <p>The cause was the pivot fold. Every flat surface folds the rotation pivot in PIXELS —
 * {@code TextOverlayLayer.position} hands the turn to the View, which rotates about
 * {@code setPivotX/Y} in view pixels, and {@code foldRotationPivotIntoBox} turns a pixel rect.
 * Pixels are square. {@code MeshPlacement.fold} folds in NORMALIZED fractions, where x is a
 * fraction of the frame's width and y a fraction of its height — two different lengths — so the
 * bare {@code cos/sin} sheared the centre-minus-pivot vector instead of turning it. The error
 * carries a factor of {@code sin(rot)}: exactly zero upright, largest at 90 degrees. Which is
 * exactly what JoyRaptor saw.
 *
 * <p><b>The reference below is written independently, in pixels</b>, from the flat surfaces'
 * definition — lay the box out unfolded, turn it about the pivot in pixels, then place its corners
 * — and never calls {@code MeshPlacement}. Agreement between the two is the whole test.
 *
 * <p>Android-free by construction; the script refuses to run if an android import leaks into the
 * mesh package.
 */
public class SpecQMeshPlaceTest {

    private static int passed = 0, failed = 0;

    /** Half a pixel on a 1080-wide frame — below anything an eye or a screenshot can resolve. */
    private static final double TOL_PX = 0.05;

    public static void main(String[] args) {
        identityBendMatchesFlatDrawAcrossTheSweep();
        centrePivotIsUntouchedByTheFix();
        theOldFoldFailsTheSameSweep();
        squareFrameWasNeverAffected();
        degenerateAspectFallsBackNotCrashes();
        buildPlaceRefusesGarbage();

        System.out.println(failed == 0
                ? "ALL GREEN (" + passed + "/" + (passed + failed) + ")"
                : "FAILURES: " + failed + " (passed " + passed + ")");
        if (failed > 0) System.exit(1);
    }

    // ── The reference: the flat draw, in square pixels, no MeshPlacement anywhere ──────────

    /**
     * Where the flat surfaces put object-local corner {@code (u,v)} (top-left origin), as a
     * fraction of the frame, top-left origin. Pixels throughout — that is the point.
     */
    static double[] flatCorner(Pose p, double u, double v) {
        double fw = p.frameW, fh = p.frameH;
        double halfWpx = p.wNorm * p.presetScaleX * 0.5 * fw;
        double halfHpx = p.hNorm * p.presetScaleY * 0.5 * fh;
        double cxPx = p.cx * fw, cyPx = p.cy * fh;
        double fxPx = cxPx, fyPx = cyPx;
        double rad = Math.toRadians(p.rotDeg);
        double c = Math.cos(rad), s = Math.sin(rad);
        if (p.applyPivot) {
            double pvx = cxPx + p.pivOffX * fw;
            double pvy = cyPx + p.pivOffY * fh;
            double scx = pvx + p.presetScaleX * (cxPx - pvx);
            double scy = pvy + p.presetScaleY * (cyPx - pvy);
            double vx = scx - pvx, vy = scy - pvy;
            fxPx = pvx + vx * c - vy * s;
            fyPx = pvy + vx * s + vy * c;
        }
        fxPx += p.dxNorm * fw;
        fyPx += p.dyNorm * fh;
        double ox = (2 * u - 1) * halfWpx, oy = (2 * v - 1) * halfHpx;
        return new double[]{(fxPx + ox * c - oy * s) / fw, (fyPx + ox * s + oy * c) / fh};
    }

    /** The same corner through the mesh stamp's own two calls, converted to the same space. */
    static double[] meshCorner(Pose p, double u, double v) {
        float[] f4 = new float[4];
        MeshPlacement.fold((float) p.cx, (float) p.cy, (float) p.wNorm, (float) p.hNorm,
                (float) p.pivOffX, (float) p.pivOffY, p.applyPivot, (float) p.rotDeg,
                (float) p.presetScaleX, (float) p.presetScaleY,
                (float) p.dxNorm, (float) p.dyNorm, (float) (p.frameW / p.frameH), f4);
        float[] m = new float[9];
        if (!MeshPlacement.buildPlace(f4[0], f4[1], f4[2], f4[3], (float) p.rotDeg,
                (float) (p.frameW / p.frameH), m)) {
            throw new IllegalStateException("buildPlace refused a drawable pose");
        }
        // Column-major: m[0..2] col0, m[3..5] col1, m[6..8] col2.
        double x = m[0] * u + m[3] * v + m[6];
        double y = m[1] * u + m[4] * v + m[7];
        double w = m[2] * u + m[5] * v + m[8];
        x /= w; y /= w;
        // Clip (y-up) back to top-left frame fractions, the space flatCorner reports in.
        return new double[]{x * 0.5 + 0.5, 1.0 - (y * 0.5 + 0.5)};
    }

    /** Worst corner disagreement for one pose, in frame pixels. */
    static double worstCornerPx(Pose p) {
        double worst = 0;
        for (int i = 0; i < 4; i++) {
            double u = i & 1, v = i >> 1;
            double[] a = flatCorner(p, u, v);
            double[] b = meshCorner(p, u, v);
            worst = Math.max(worst, Math.hypot((a[0] - b[0]) * p.frameW,
                    (a[1] - b[1]) * p.frameH));
        }
        return worst;
    }

    // ── 1. The sweep SPEC Q asked for ─────────────────────────────────

    /**
     * 0 to 360 in 15-degree steps, both mirror states, with and without a corner pin, on the
     * portrait frame JoyRaptor shot on and on a landscape one. An identity bend is the unit square,
     * so its corners ARE the picture's corners: pixel agreement here is the pixel-identical
     * render the spec asks for, with the deformation taken out of the picture.
     */
    static void identityBendMatchesFlatDrawAcrossTheSweep() {
        double worst = 0;
        int cases = 0;
        for (int[] frame : new int[][]{{1080, 1920}, {1920, 1080}, {1440, 2960}}) {
            for (double mirrorX : new double[]{1, -1}) {
                for (double mirrorY : new double[]{1, -1}) {
                    for (float[] pins : new float[][]{null, SCOTT_PINS}) {
                        // The nine anchors, so a corner pivot (the one JoyRaptor had) is covered
                        // alongside centre.
                        for (double pvu : new double[]{0, 0.5, 1}) {
                            for (double pvv : new double[]{0, 0.5, 1}) {
                                for (int rot = 0; rot <= 360; rot += 15) {
                                    Pose p = Pose.of(frame[0], frame[1], rot, pvu, pvv, pins,
                                            mirrorX, mirrorY);
                                    worst = Math.max(worst, worstCornerPx(p));
                                    cases++;
                                }
                            }
                        }
                    }
                }
            }
        }
        check("identity bend matches the flat draw across the whole sweep ("
                + cases + " poses, worst " + fmt(worst) + " px)", worst <= TOL_PX);
    }

    // ── 2. What the fix must NOT have changed ─────────────────────────

    /**
     * A centre pivot skips the fold entirely, so no project that never moved the pivot picker can
     * have shifted by so much as a float ulp. This is the "byte-identical for everyone else"
     * guarantee, measured rather than asserted.
     */
    static void centrePivotIsUntouchedByTheFix() {
        double worst = 0;
        for (int rot = 0; rot <= 360; rot += 15) {
            Pose p = Pose.of(1080, 1920, rot, 0.5, 0.5, null, 1, 1);
            float[] a = new float[4];
            float[] b = new float[4];
            MeshPlacement.fold((float) p.cx, (float) p.cy, (float) p.wNorm, (float) p.hNorm,
                    (float) p.pivOffX, (float) p.pivOffY, /* applyPivot= */ false,
                    (float) p.rotDeg, 1f, 1f, 0f, 0f, 0.5625f, a);
            MeshPlacement.fold((float) p.cx, (float) p.cy, (float) p.wNorm, (float) p.hNorm,
                    (float) p.pivOffX, (float) p.pivOffY, /* applyPivot= */ false,
                    (float) p.rotDeg, 1f, 1f, 0f, 0f, 1f, b);
            for (int i = 0; i < 4; i++) worst = Math.max(worst, Math.abs(a[i] - b[i]));
        }
        check("a centre pivot ignores the aspect entirely (worst delta " + fmt(worst) + ")",
                worst == 0.0);
    }

    // ── 3. The bug itself, pinned so it cannot come back ──────────────

    /**
     * The OLD fold, transcribed here as the three lines it was, must FAIL the same sweep — and
     * fail in JoyRaptor's shape: nothing upright, everything at 90 degrees. Without this the test
     * above could pass against a reference that had quietly inherited the same mistake.
     */
    static void theOldFoldFailsTheSameSweep() {
        double at0 = oldFoldErrorPx(0);
        double at45 = oldFoldErrorPx(45);
        double at90 = oldFoldErrorPx(90);
        double at180 = oldFoldErrorPx(180);
        check("the old fold was exact upright (" + fmt(at0) + " px at 0, "
                + fmt(at180) + " px at 180)", at0 <= TOL_PX && at180 <= TOL_PX);
        check("the old fold was worst at 90 (" + fmt(at90) + " px) and half-way there at 45 ("
                + fmt(at45) + " px)", at90 > 100 && at45 > 0.5 * at90 && at45 < at90);
    }

    /** How far the pre-SPEC-Q fold put the centre from the flat draw's, in frame pixels. */
    static double oldFoldErrorPx(int rot) {
        Pose p = Pose.of(1080, 1920, rot, 0, 0, SCOTT_PINS, 1, 1);
        double rad = Math.toRadians(rot);
        double c = Math.cos(rad), s = Math.sin(rad);
        double pvx = p.cx + p.pivOffX, pvy = p.cy + p.pivOffY;
        double vx = p.cx - pvx, vy = p.cy - pvy;
        double oldX = pvx + vx * c - vy * s;
        double oldY = pvy + vx * s + vy * c;
        double[] good = flatCorner(p, 0.5, 0.5);
        return Math.hypot((oldX - good[0]) * p.frameW, (oldY - good[1]) * p.frameH);
    }

    /**
     * On a SQUARE frame the two arithmetics are the same arithmetic — which is both a sanity
     * check on the reference and the reason this went unnoticed in any square-canvas test.
     */
    static void squareFrameWasNeverAffected() {
        double worst = 0;
        for (int rot = 0; rot <= 360; rot += 15) {
            worst = Math.max(worst, worstCornerPx(Pose.of(1080, 1080, rot, 0, 0, null, 1, 1)));
        }
        check("a square frame agrees either way (worst " + fmt(worst) + " px)", worst <= TOL_PX);
    }

    // ── 4. Defensive shape ───────────────────────────────────────────

    static void degenerateAspectFallsBackNotCrashes() {
        boolean ok = true;
        for (float bad : new float[]{0f, -2f, Float.NaN, Float.POSITIVE_INFINITY}) {
            float[] out = new float[4];
            MeshPlacement.fold(0.5f, 0.5f, 0.3f, 0.2f, -0.15f, -0.1f, true, 37f,
                    1f, 1f, 0f, 0f, bad, out);
            for (float v : out) {
                if (Float.isNaN(v) || Float.isInfinite(v)) ok = false;
            }
        }
        check("a degenerate aspect falls back to square units rather than poisoning the pose", ok);
    }

    static void buildPlaceRefusesGarbage() {
        float[] m = new float[9];
        boolean refused = !MeshPlacement.buildPlace(0.5f, 0.5f, 0f, 0.2f, 0f, 0.5625f, m)
                && !MeshPlacement.buildPlace(0.5f, 0.5f, 0.2f, 0.2f, Float.NaN, 0.5625f, m)
                && !MeshPlacement.buildPlace(0.5f, 0.5f, 0.2f, 0.2f, 0f, 0f, m);
        check("buildPlace still refuses a pose it cannot draw", refused);
    }

    // ── Pose fixture ─────────────────────────────────────────────────

    /**
     * JoyRaptor's own pins, read off the device 2026-09-08 (project BundlingFontTest, the bent
     * picture) — a real trapezoid, not a symmetric one, so the bilinear pivot anchor below is
     * genuinely exercised.
     */
    static final float[] SCOTT_PINS = {
            -0.21399821f, 0.054251324f,
            -0.10985908f, -0.033737343f,
            0.109859206f, 0.0337373f,
            0.21399803f, -0.054251287f,
    };

    static final class Pose {
        double frameW, frameH, cx, cy, wNorm, hNorm, pivOffX, pivOffY;
        boolean applyPivot;
        double rotDeg, presetScaleX, presetScaleY, dxNorm, dyNorm;

        /**
         * JoyRaptor's real pose (centre, size, split scale, image aspect) at a chosen frame, pivot
         * anchor, pin and mirror. The pivot offset is computed the way the MODEL computes it
         * ({@code TextOverlayItem.pivotOffsetFromCentre*}) — bilinear on the pinned quad, or
         * {@code (pivot - 0.5) * size} when flat — restated here only because that class needs
         * Android to load.
         */
        static Pose of(int fw, int fh, double rot, double pvu, double pvv,
                       float[] pins, double mirrorX, double mirrorY) {
            Pose p = new Pose();
            p.frameW = fw; p.frameH = fh;
            p.cx = 0.84436804; p.cy = 0.3024034;
            double sizeFrac = 0.43814212, sx = 1.052591, sy = 0.95003664, imageAspect = 1.3333333;
            double a = fw / (double) fh;
            p.wNorm = sizeFrac * imageAspect * sx / a;
            p.hNorm = sizeFrac * sy;
            p.applyPivot = !(pvu == 0.5 && pvv == 0.5);
            p.pivOffX = mirrorX * pivotOffX(p.wNorm, pins, pvu, pvv);
            p.pivOffY = mirrorY * pivotOffY(p.hNorm, pins, pvu, pvv);
            p.rotDeg = rot;
            p.presetScaleX = 1; p.presetScaleY = 1; p.dxNorm = 0; p.dyNorm = 0;
            return p;
        }

        static double pivotOffX(double w, float[] pins, double u, double v) {
            if (pins == null) return (u - 0.5) * w;
            double xTL = -w / 2 + pins[0] * w, xTR = w / 2 + pins[2] * w;
            double xBR = w / 2 + pins[4] * w, xBL = -w / 2 + pins[6] * w;
            return (1 - u) * (1 - v) * xTL + u * (1 - v) * xTR + u * v * xBR + (1 - u) * v * xBL;
        }

        static double pivotOffY(double h, float[] pins, double u, double v) {
            if (pins == null) return (v - 0.5) * h;
            double yTL = -h / 2 + pins[1] * h, yTR = -h / 2 + pins[3] * h;
            double yBR = h / 2 + pins[5] * h, yBL = h / 2 + pins[7] * h;
            return (1 - u) * (1 - v) * yTL + u * (1 - v) * yTR + u * v * yBR + (1 - u) * v * yBL;
        }
    }

    static String fmt(double v) { return String.format(java.util.Locale.US, "%.4f", v); }

    static void check(String what, boolean ok) {
        if (ok) { passed++; System.out.println("  ok   " + what); }
        else { failed++; System.out.println("  FAIL " + what); }
    }
}
