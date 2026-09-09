import com.fadcam.ui.faditor.transform.mesh.MeshPlacement;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * SPEC T — the FX/keyed/blended preview must put a picture where its own helper frame is.
 *
 * <p><b>The same bug SPEC Q found, in a second place.</b> {@code TextOverlayLayer.fxPipFor} folds
 * the rotation pivot into the Pip centre. It turned the centre-minus-pivot vector with a bare
 * {@code cos/sin} pair — but that vector's two components are fractions of DIFFERENT lengths: x of
 * the video content rect's width, y of its height. A rotation applied to non-square units is a
 * shear, not a turn. The error carries a factor of {@code sin(rot)}: exactly zero at 0 and 180
 * degrees, largest at 90.
 *
 * <p>Every flat surface this must agree with folds in PIXELS, and pixels are square —
 * {@code TextOverlayLayer.position} hands the turn to the View, which rotates about
 * {@code setPivotX/Y} in view pixels, and {@code foldRotationPivotIntoBox} turns a pixel rect. So
 * the reference below is written independently IN PIXELS from that definition and never calls
 * {@code MeshPlacement}. Agreement between the two is the whole test.
 *
 * <p>It bites only an image that carries FX, a chroma key or a blend mode (so it takes the GL Pip
 * path) AND a non-centre rotation pivot — at a centre pivot the whole block is skipped, which is
 * why nobody reported it.
 *
 * <p>{@code TextOverlayLayer} needs Android to load, so the arithmetic itself is measured through
 * the shared definition it now calls, parameterised exactly as {@code fxPipFor} parameterises it
 * (mirror-signed pivot offsets, content-rect fractions, content-rect aspect). The last check is a
 * SOURCE lint proving the host really delegates and no longer carries its own bare fold — that is
 * the check that was RED before the fix.
 */
public class SpecTFxPipFoldTest {

    private static int passed = 0, failed = 0;

    /** Half a pixel on a 1080-wide frame — below anything an eye or a screenshot can resolve. */
    private static final double TOL_PX = 0.05;

    private static final String HOST =
            "app/src/main/java/com/fadcam/ui/faditor/overlay/TextOverlayLayer.java";

    public static void main(String[] args) {
        fxPipFoldMatchesTheFlatDrawAcrossTheSweep();
        theOldFxPipFoldFailsTheSameSweep();
        centrePivotIsBitIdentical();
        squareContentRectWasNeverAffected();
        degenerateContentRectFallsBackNotCrashes();
        theHostDelegatesRatherThanTranscribing();

        System.out.println(failed == 0
                ? "ALL GREEN (" + passed + "/" + (passed + failed) + ")"
                : "FAILURES: " + failed + " (passed " + passed + ")");
        if (failed > 0) System.exit(1);
    }

    // ── The reference: the flat draw, in square pixels, no MeshPlacement anywhere ──────────

    /**
     * Where the flat/View surfaces put the object's centre, in CONTENT-RECT PIXELS. This is the
     * composition {@code TextOverlayLayer.position} gets from the View (scale about the pivot,
     * rotate about the pivot, translate) and the export's Canvas gets from
     * translate-rotate-scale — written out in pixels, which are square.
     */
    static double[] flatCentrePx(Pose p) {
        double cxPx = p.cx * p.rw, cyPx = p.cy * p.rh;
        double fxPx = cxPx, fyPx = cyPx;
        if (p.applyPivot) {
            double pvx = cxPx + p.pivOffX * p.rw;
            double pvy = cyPx + p.pivOffY * p.rh;
            double scx = pvx + p.scaleX * (cxPx - pvx);
            double scy = pvy + p.scaleY * (cyPx - pvy);
            double rad = Math.toRadians(p.rotDeg);
            double c = Math.cos(rad), s = Math.sin(rad);
            double vx = scx - pvx, vy = scy - pvy;
            fxPx = pvx + vx * c - vy * s;
            fyPx = pvy + vx * s + vy * c;
        }
        return new double[]{fxPx + p.dxPx, fyPx + p.dyPx};
    }

    /**
     * The same centre through the shared definition {@code fxPipFor} now calls, with exactly the
     * arguments the host hands it, converted back to content-rect pixels.
     */
    static double[] pipCentrePx(Pose p) {
        float[] f4 = new float[4];
        MeshPlacement.fold((float) p.cx, (float) p.cy, (float) p.wNorm, (float) p.hNorm,
                (float) p.pivOffX, (float) p.pivOffY, p.applyPivot, (float) p.rotDeg,
                (float) p.scaleX, (float) p.scaleY,
                (float) (p.dxPx / p.rw), (float) (p.dyPx / p.rh),
                (float) (p.rw / p.rh), f4);
        return new double[]{f4[0] * p.rw, f4[1] * p.rh};
    }

    /** How far the Pip centre lands from the flat draw's, in content-rect pixels. */
    static double centreErrorPx(Pose p) {
        double[] a = flatCentrePx(p);
        double[] b = pipCentrePx(p);
        return Math.hypot(a[0] - b[0], a[1] - b[1]);
    }

    // ── 1. The sweep SPEC T asked for ─────────────────────────────────

    /**
     * Rotation 0 to 360 in 15-degree steps, all nine pivot anchors, both mirror states, portrait
     * and landscape content rects (plus the Note 9's own), with and without a corner pin and with
     * and without an animation preset. The Pip quad rotates about its own centre, so a centre that
     * lands right IS a picture that lands right.
     */
    static void fxPipFoldMatchesTheFlatDrawAcrossTheSweep() {
        double worst = 0;
        int cases = 0;
        for (double[] rect : RECTS) {
            for (double mirrorX : new double[]{1, -1}) {
                for (double mirrorY : new double[]{1, -1}) {
                    for (float[] pins : new float[][]{null, SCOTT_PINS}) {
                        for (double[] preset : PRESETS) {
                            for (double pvu : new double[]{0, 0.5, 1}) {
                                for (double pvv : new double[]{0, 0.5, 1}) {
                                    for (int rot = 0; rot <= 360; rot += 15) {
                                        Pose p = Pose.of(rect[0], rect[1], rot, pvu, pvv, pins,
                                                mirrorX, mirrorY, preset);
                                        worst = Math.max(worst, centreErrorPx(p));
                                        cases++;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        check("the FX Pip fold matches the flat draw across the whole sweep ("
                + cases + " poses, worst " + fmt(worst) + " px)", worst <= TOL_PX);
    }

    // ── 2. The bug itself, pinned so it cannot come back ──────────────

    /**
     * The OLD fxPipFor fold, transcribed here as the two lines it was, must FAIL the same sweep,
     * and fail in the shape the diagnosis predicts: nothing upright, everything at 90 degrees.
     * Without this the check above could pass against a reference that had quietly inherited the
     * same mistake.
     */
    static void theOldFxPipFoldFailsTheSameSweep() {
        double at0 = oldFoldErrorPx(0);
        double at45 = oldFoldErrorPx(45);
        double at90 = oldFoldErrorPx(90);
        double at180 = oldFoldErrorPx(180);
        check("the old fxPipFor fold was exact upright (" + fmt(at0) + " px at 0, "
                + fmt(at180) + " px at 180)", at0 <= TOL_PX && at180 <= TOL_PX);
        check("the old fxPipFor fold was worst at 90 (" + fmt(at90) + " px) and part-way there at"
                + " 45 (" + fmt(at45) + " px)", at90 > 100 && at45 > 0.5 * at90 && at45 < at90);

        double worst = 0;
        for (double[] rect : RECTS) {
            for (int rot = 0; rot <= 360; rot += 15) {
                worst = Math.max(worst, oldFoldErrorPx(rect[0], rect[1], rot));
            }
        }
        check("the old fxPipFor fold failed the sweep outright (worst " + fmt(worst) + " px)",
                worst > TOL_PX);
    }

    static double oldFoldErrorPx(int rot) {
        return oldFoldErrorPx(1080, 1920, rot);
    }

    /** How far the pre-SPEC-T fold put the Pip centre from the flat draw's, in pixels. */
    static double oldFoldErrorPx(double rw, double rh, double rot) {
        Pose p = Pose.of(rw, rh, rot, 0, 0, SCOTT_PINS, 1, 1, PRESETS[0]);
        double rad = Math.toRadians(rot);
        double c = Math.cos(rad), s = Math.sin(rad);
        double pvx = p.cx + p.pivOffX, pvy = p.cy + p.pivOffY;
        double scx = pvx + p.scaleX * (p.cx - pvx);
        double scy = pvy + p.scaleY * (p.cy - pvy);
        double vx = scx - pvx, vy = scy - pvy;
        double oldX = pvx + vx * c - vy * s + p.dxPx / p.rw;
        double oldY = pvy + vx * s + vy * c + p.dyPx / p.rh;
        double[] good = flatCentrePx(p);
        return Math.hypot(oldX * p.rw - good[0], oldY * p.rh - good[1]);
    }

    // ── 3. What the fix must NOT have changed ─────────────────────────

    /**
     * A centre pivot skips the fold entirely, so no project that never moved the pivot picker can
     * have shifted by so much as a float ulp — measured against the old arithmetic rather than
     * asserted. This is the "bit-identical for everyone else" guarantee.
     */
    static void centrePivotIsBitIdentical() {
        boolean identical = true;
        double worst = 0;
        for (double[] rect : RECTS) {
            for (double[] preset : PRESETS) {
                for (int rot = 0; rot <= 360; rot += 15) {
                    Pose p = Pose.of(rect[0], rect[1], rot, 0.5, 0.5, null, 1, 1, preset);
                    if (p.applyPivot) { identical = false; break; }
                    // What the old code produced at a skipped pivot: the raw centre plus preset.
                    float oldX = (float) p.cx + (float) (p.dxPx / p.rw);
                    float oldY = (float) p.cy + (float) (p.dyPx / p.rh);
                    float[] f4 = new float[4];
                    MeshPlacement.fold((float) p.cx, (float) p.cy, (float) p.wNorm,
                            (float) p.hNorm, (float) p.pivOffX, (float) p.pivOffY, false,
                            (float) p.rotDeg, (float) p.scaleX, (float) p.scaleY,
                            (float) (p.dxPx / p.rw), (float) (p.dyPx / p.rh),
                            (float) (p.rw / p.rh), f4);
                    if (f4[0] != oldX || f4[1] != oldY) identical = false;
                    worst = Math.max(worst, centreErrorPx(p));
                }
            }
        }
        check("a centre pivot skips the block and is bit-identical to the old code", identical);
        check("a centre pivot still matches the flat draw exactly (worst " + fmt(worst) + " px)",
                worst <= TOL_PX);
    }

    /**
     * On a square content rect the two arithmetics ARE the same arithmetic — a sanity check on the
     * reference, and the reason this never showed up in a square-canvas test.
     */
    static void squareContentRectWasNeverAffected() {
        double worst = 0;
        for (int rot = 0; rot <= 360; rot += 15) {
            worst = Math.max(worst, oldFoldErrorPx(1080, 1080, rot));
        }
        check("a square content rect agrees either way (worst " + fmt(worst) + " px)",
                worst <= TOL_PX);
    }

    static void degenerateContentRectFallsBackNotCrashes() {
        boolean ok = true;
        for (float bad : new float[]{0f, -2f, Float.NaN, Float.POSITIVE_INFINITY}) {
            float[] out = new float[4];
            MeshPlacement.fold(0.5f, 0.5f, 0.3f, 0.2f, -0.15f, -0.1f, true, 37f,
                    1f, 1f, 0f, 0f, bad, out);
            for (float v : out) {
                if (Float.isNaN(v) || Float.isInfinite(v)) ok = false;
            }
        }
        check("a degenerate content rect falls back to square units rather than poisoning the Pip",
                ok);
    }

    // ── 4. The host really calls the shared definition ────────────────

    /**
     * The rule this project keeps relearning: the mesh copy of this fold drifted from the flat one
     * BECAUSE it was a transcription. So the host must CALL {@code MeshPlacement.fold}, not carry
     * a third copy. This check is the one that is red before the fix — it reads the shipped source
     * of {@code fxPipFor} and refuses both the old bare fold and any new transcription of the
     * aspect-corrected one.
     */
    static void theHostDelegatesRatherThanTranscribing() {
        String body;
        try {
            String src = new String(Files.readAllBytes(Paths.get(HOST)), StandardCharsets.UTF_8);
            body = methodBody(src, "public com.fadcam.ui.faditor.compositor.FxPreviewTextureView"
                    + ".Pip fxPipFor(");
        } catch (Exception e) {
            check("fxPipFor source is readable (" + e + ")", false);
            return;
        }
        if (body == null) {
            check("fxPipFor was found in " + HOST, false);
            return;
        }
        String flat = body.replaceAll("\\s+", "");
        check("fxPipFor calls the shared MeshPlacement.fold",
                flat.contains("MeshPlacement.fold(") || flat.contains("mesh.MeshPlacement.fold("));
        check("fxPipFor no longer carries the anisotropic bare fold",
                !flat.contains("cx=pvx+vx*c-vy*s") && !flat.contains("cy=pvy+vx*s+vy*c"));
        check("fxPipFor did not transcribe the aspect-corrected fold either",
                !flat.contains("cx=pvx+vx*c-(vy*s)/") && !flat.contains("cy=pvy+(vx*"));
    }

    /** The text between the brace that opens {@code signature}'s body and its matching close. */
    static String methodBody(String src, String signature) {
        int at = src.indexOf(signature);
        if (at < 0) return null;
        int open = src.indexOf('{', at);
        if (open < 0) return null;
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char ch = src.charAt(i);
            if (ch == '{') depth++;
            else if (ch == '}') {
                depth--;
                if (depth == 0) return src.substring(open + 1, i);
            }
        }
        return null;
    }

    // ── Pose fixture ─────────────────────────────────────────────────

    /** Portrait, landscape, the Note 9's own frame — the content rects a Pip is built against. */
    static final double[][] RECTS = {{1080, 1920}, {1920, 1080}, {1440, 2960}};

    /** {scaleX, scaleY, dxPx, dyPx} — no preset, and a real CaptionAnimator one. */
    static final double[][] PRESETS = {{1, 1, 0, 0}, {1.15, 0.9, 12, -20}};

    /**
     * JoyRaptor's own pins, read off the device 2026-09-08 (project BundlingFontTest) — a real
     * trapezoid, not a symmetric one, so the bilinear pivot anchor is genuinely exercised.
     */
    static final float[] SCOTT_PINS = {
            -0.21399821f, 0.054251324f,
            -0.10985908f, -0.033737343f,
            0.109859206f, 0.0337373f,
            0.21399803f, -0.054251287f,
    };

    static final class Pose {
        double rw, rh, cx, cy, wNorm, hNorm, pivOffX, pivOffY;
        boolean applyPivot;
        double rotDeg, scaleX, scaleY, dxPx, dyPx;

        /**
         * JoyRaptor's real pose at a chosen content rect, pivot anchor, pin, mirror and preset. The
         * normalized width/height are {@code wPx / r.width()} and {@code hPx / r.height()} — the
         * same fractions {@code fxPipFor} forms — and the pivot offset is computed the way the
         * MODEL computes it ({@code TextOverlayItem.pivotOffsetFromCentre*}): bilinear on the
         * pinned quad, or {@code (pivot - 0.5) * size} when flat. Restated here only because that
         * class needs Android to load.
         */
        static Pose of(double rw, double rh, double rot, double pvu, double pvv,
                       float[] pins, double mirrorX, double mirrorY, double[] preset) {
            Pose p = new Pose();
            p.rw = rw; p.rh = rh;
            p.cx = 0.84436804; p.cy = 0.3024034;
            double sizeFrac = 0.43814212, imageAspect = 1.3333333;
            // hPx = sizeFrac * r.height(); wPx = hPx * imageAspect. Divided by the rect, that is:
            p.hNorm = sizeFrac;
            p.wNorm = sizeFrac * imageAspect * rh / rw;
            p.applyPivot = !(pvu == 0.5 && pvv == 0.5);
            p.pivOffX = mirrorX * pivotOffX(p.wNorm, pins, pvu, pvv);
            p.pivOffY = mirrorY * pivotOffY(p.hNorm, pins, pvu, pvv);
            p.rotDeg = rot;
            p.scaleX = preset[0]; p.scaleY = preset[1];
            p.dxPx = preset[2]; p.dyPx = preset[3];
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
