import com.fadcam.ui.faditor.model.CompositingSpec;
import com.fadcam.ui.faditor.model.MaskSdf;

/**
 * JVM harness for the signed-distance mask (SPEC_ADJUSTMENT_LAYERS_FX M4).
 *
 * <p><b>The SIGN is the claim, and it is testable.</b> {@code sdRoundBox} is asserted to be
 * geometrically EXACT against {@code Path.addRoundRect} with equal x/y radii — which is what
 * {@code MaskPathBuilder.shapePath} emits — so hard edges match the Canvas renderers to the
 * pixel. Exactness of a distance FIELD is hard to test directly; exactness of its sign is not,
 * so containment is checked at a grid of points whose answer is known by hand.</p>
 *
 * <p>The feather is deliberately NOT asserted to match {@code BlurMaskFilter}. It is a
 * smoothstep across the field rather than a Gaussian, they differ near a concave join, and the
 * spec says to document that rather than hide it. What matters is that BOTH GL renderers use
 * this same field and therefore agree with each other.</p>
 *
 * <p>Run: {@code bash tools/jvm-harness/run-adjust.sh}</p>
 */
public class MaskSdfTest {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        boxSignIsExact();
        cornerRadiusRounds();
        rotationIsAPixelRotationNotAShear();
        booleanFolds();
        effectCoverageRespectsInvert();
        coverOfMirrorsFxMaskCover();
        glslReadsShapesTopDown();
        featherMatchesTheExportBlur();
        packing();

        System.out.println(failed == 0 ? "ALL GREEN (" + passed + "/" + (passed + failed) + ")"
                : "FAILURES: " + failed + " (passed " + passed + ")");
        if (failed > 0) System.exit(1);
    }

    private static final float W = 1000f, H = 1000f;

    // ── Sign ────────────────────────────────────────────────────────────────

    static void boxSignIsExact() {
        // A hard-cornered box centred at 0.5, half the frame wide and tall: x,y in 0.25..0.75.
        CompositingSpec s = spec(shape(0.5f, 0.5f, 0.5f, 0.5f, 0f, 0f));

        check("dead centre is fully inside", cov(s, 0.5f, 0.5f) > 0.99f);
        check("just inside the left edge is inside", cov(s, 0.26f, 0.5f) > 0.99f);
        check("just outside the left edge is outside", cov(s, 0.24f, 0.5f) < 0.01f);
        check("just inside the top edge is inside", cov(s, 0.5f, 0.26f) > 0.99f);
        check("just outside the bottom edge is outside", cov(s, 0.5f, 0.76f) < 0.01f);
        check("a corner just inside is inside", cov(s, 0.26f, 0.26f) > 0.99f);
        check("a corner just outside is outside", cov(s, 0.24f, 0.24f) < 0.01f);
        check("far outside is zero", cov(s, 0.05f, 0.05f) < 0.001f);

        // Sweep a grid: every point's expected answer is known from the rectangle alone.
        int wrong = 0;
        for (int i = 0; i <= 20; i++) {
            for (int j = 0; j <= 20; j++) {
                float x = i / 20f, y = j / 20f;
                // Skip a 2% band either side of each edge, where antialiasing legitimately
                // gives a partial answer rather than a binary one.
                if (near(x, 0.25f) || near(x, 0.75f) || near(y, 0.25f) || near(y, 0.75f)) continue;
                boolean inside = x > 0.25f && x < 0.75f && y > 0.25f && y < 0.75f;
                float c = cov(s, x, y);
                if (inside != (c > 0.5f)) wrong++;
            }
        }
        check("the SIGN is right at every one of the ~400 sampled points: " + wrong + " wrong",
                wrong == 0);
    }

    static boolean near(float a, float b) { return Math.abs(a - b) < 0.02f; }

    static void cornerRadiusRounds() {
        CompositingSpec hard = spec(shape(0.5f, 0.5f, 0.5f, 0.5f, 0f, 0f));
        CompositingSpec round = spec(shape(0.5f, 0.5f, 0.5f, 0.5f, 0f, 1f));
        // The extreme corner is inside a hard box and OUTSIDE a fully-rounded one — that is the
        // whole visible difference between "Rect" and "Circle".
        check("a hard box contains its extreme corner", cov(hard, 0.27f, 0.27f) > 0.9f);
        check("a fully-rounded one does NOT", cov(round, 0.27f, 0.27f) < 0.1f);
        check("...but both still contain the centre of an edge",
                cov(hard, 0.5f, 0.27f) > 0.9f && cov(round, 0.5f, 0.27f) > 0.9f);
    }

    static void rotationIsAPixelRotationNotAShear() {
        // A WIDE, short box on a SQUARE frame, rotated 90 degrees, must become TALL and narrow.
        // Rotating in normalised coordinates instead would shear it — the error is largest at
        // 45 degrees and zero at 0/180, which is why 90 is the honest check.
        CompositingSpec flat = spec(shape(0.5f, 0.5f, 0.6f, 0.2f, 0f, 0f));
        CompositingSpec turned = spec(shape(0.5f, 0.5f, 0.6f, 0.2f, 90f, 0f));
        check("unrotated: wide, so a point out to the side is inside",
                cov(flat, 0.75f, 0.5f) > 0.9f);
        check("unrotated: short, so a point above is outside", cov(flat, 0.5f, 0.75f) < 0.1f);
        check("rotated 90: the point to the side is now OUTSIDE",
                cov(turned, 0.75f, 0.5f) < 0.1f);
        check("rotated 90: the point above is now INSIDE", cov(turned, 0.5f, 0.75f) > 0.9f);
    }

    // ── Booleans ────────────────────────────────────────────────────────────

    static void booleanFolds() {
        // Two circles side by side, overlapping in the middle.
        CompositingSpec union = spec(shape(0.4f, 0.5f, 0.3f, 0.3f, 0f, 1f),
                shape(0.6f, 0.5f, 0.3f, 0.3f, 0f, 1f));
        check("UNION covers the left lobe", cov(union, 0.32f, 0.5f) > 0.9f);
        check("UNION covers the right lobe", cov(union, 0.68f, 0.5f) > 0.9f);
        check("UNION covers the overlap", cov(union, 0.5f, 0.5f) > 0.9f);

        CompositingSpec sub = spec(shape(0.4f, 0.5f, 0.3f, 0.3f, 0f, 1f),
                shape(0.6f, 0.5f, 0.3f, 0.3f, 0f, 1f));
        sub.masks.get(1).mode = CompositingSpec.MODE_SUBTRACT;
        check("SUBTRACT keeps the left lobe", cov(sub, 0.32f, 0.5f) > 0.9f);
        check("SUBTRACT removes the overlap — the cookie's bite", cov(sub, 0.5f, 0.5f) < 0.1f);
        check("SUBTRACT does not add the right lobe", cov(sub, 0.68f, 0.5f) < 0.1f);

        CompositingSpec inter = spec(shape(0.4f, 0.5f, 0.3f, 0.3f, 0f, 1f),
                shape(0.6f, 0.5f, 0.3f, 0.3f, 0f, 1f));
        inter.masks.get(1).mode = CompositingSpec.MODE_INTERSECT;
        check("INTERSECT keeps ONLY the overlap", cov(inter, 0.5f, 0.5f) > 0.9f);
        check("INTERSECT drops the left-only part", cov(inter, 0.32f, 0.5f) < 0.1f);
        check("INTERSECT drops the right-only part", cov(inter, 0.68f, 0.5f) < 0.1f);

        // A leading SUBTRACT must be SEEDED as a union, or the whole mask would be empty --
        // the same rule MaskFold pins on the Path side, restated on the field side.
        CompositingSpec lead = spec(shape(0.5f, 0.5f, 0.4f, 0.4f, 0f, 0f));
        lead.masks.get(0).mode = CompositingSpec.MODE_SUBTRACT;
        check("a leading SUBTRACT does not erase everything", cov(lead, 0.5f, 0.5f) > 0.9f);
    }

    static void effectCoverageRespectsInvert() {
        CompositingSpec s = spec(shape(0.5f, 0.5f, 0.4f, 0.4f, 0f, 0f));
        // Default reading: a mask cuts a HOLE, so the effect lands OUTSIDE the shape.
        check("by default the effect lands outside the shape",
                MaskSdf.effectCoverage(s, 0.05f, 0.05f, W, H) > 0.9f);
        check("...and not inside it",
                MaskSdf.effectCoverage(s, 0.5f, 0.5f, W, H) < 0.1f);
        s.invertMasks = true;
        check("inverted, the effect lands INSIDE the shape",
                MaskSdf.effectCoverage(s, 0.5f, 0.5f, W, H) > 0.9f);
        check("...and not outside", MaskSdf.effectCoverage(s, 0.05f, 0.05f, W, H) < 0.1f);

        check("no mask at all means the effect lands everywhere",
                MaskSdf.effectCoverage(new CompositingSpec(), 0.1f, 0.9f, W, H) == 1f);
        check("a null spec is the same answer", MaskSdf.effectCoverage(null, 0f, 0f, W, H) == 1f);
    }

    static void coverOfMirrorsFxMaskCover() {
        // The polarity policy lives once in GLSL (fxMaskCover) and once in Java
        // (coverOf). Pin both: the shared string must carry the hole-by-default
        // reading, and the mirror must agree with it point for point.
        String glsl = MaskSdf.GLSL_MASK_FN;
        check("GLSL_MASK_FN carries the single polarity function",
                glsl.contains("float fxMaskCover(float inside, float inv)"));
        check("...with hole-by-default polarity",
                glsl.contains("inv > 0.5 ? inside : 1.0 - inside"));
        check("coverOf: hole by default shows outside",
                Math.abs(MaskSdf.coverOf(0.2f, false) - 0.8f) < 0.001f);
        check("coverOf: window shows inside",
                Math.abs(MaskSdf.coverOf(0.2f, true) - 0.2f) < 0.001f);
        check("coverOf agrees with effectCoverage inside the shape",
                Math.abs(MaskSdf.coverOf(1f, false)
                        - MaskSdf.effectCoverage(
                                spec(shape(0.5f, 0.5f, 0.4f, 0.4f, 0f, 0f)),
                                0.5f, 0.5f, W, H)) < 0.001f);
    }

    static void glslReadsShapesTopDown() {
        // Every GL caller hands fxShapeSd a Y-UP frame coordinate; the shapes are Y-DOWN. The
        // flip must live in the shared function, or each consumer mirrors the mask top-to-bottom
        // against the Canvas export (2026-09-23: a box at Y 6% previewed at the bottom).
        String glsl = MaskSdf.GLSL_MASK_FN;
        check("fxShapeSd flips the GL point into the authored top-down space",
                glsl.contains("vec2 p = (vec2(uv.x, 1.0 - uv.y) - geo.xy) * frame;"));
        check("...and nothing else in the shared string reads raw uv",
                glsl.indexOf("(uv - geo.xy)") < 0);
    }

    static void featherMatchesTheExportBlur() {
        // The Canvas export blurs with BlurMaskFilter(r): Gaussian, sigma = 0.57735 r + 0.5.
        float r = 60f;
        float sigma = 0.57735f * r + 0.5f;
        check("a soft edge is half covered exactly on the edge",
                Math.abs(MaskSdf.coverageOf(0f, r) - 0.5f) < 0.001f);
        // Normal CDF at one sigma: 0.8413 inside, 0.1587 outside.
        check("one sigma inside matches the Gaussian (0.841)",
                Math.abs(MaskSdf.coverageOf(-sigma, r) - 0.8413f) < 0.01f);
        check("one sigma outside matches the Gaussian (0.159)",
                Math.abs(MaskSdf.coverageOf(sigma, r) - 0.1587f) < 0.01f);
        check("two sigma inside matches the Gaussian (0.977)",
                Math.abs(MaskSdf.coverageOf(-2f * sigma, r) - 0.9772f) < 0.01f);
        check("a hard edge keeps its half-pixel antialias band",
                MaskSdf.coverageOf(-0.5f, 0f) > 0.99f && MaskSdf.coverageOf(0.5f, 0f) < 0.01f);
        check("the GLSL states the same curve as the Java mirror",
                MaskSdf.GLSL_MASK_FN.contains("float sigma = 0.57735 * feather + 0.5;")
                && MaskSdf.GLSL_MASK_FN.contains("exp(clamp(1.702 * sd / sigma, -9.0, 9.0))"));

        // Radius off the FRAME's shorter side, like MaskPathBuilder — not the shape's size.
        CompositingSpec small = spec(shape(0.5f, 0.5f, 0.1f, 0.1f, 0f, 0f));
        small.maskFeather = 0.5f;
        float[] g = MaskSdf.packShapes(small, 1080f, 1920f);
        check("a small soft box is blurred by a fraction of the FRAME, as the export does",
                Math.abs(g[7] - CompositingSpec.featherRadiusPx(0.5f, 1080f, 1920f)) < 0.01f);
    }

    // ── Packing ─────────────────────────────────────────────────────────────

    static void packing() {
        CompositingSpec s = spec(shape(0.25f, 0.75f, 0.5f, 0.2f, 90f, 0.5f));
        float[] g = MaskSdf.packShapes(s, W, H);
        check("packs one shape into its slots", g.length == MaskSdf.FLOATS_PER_SHAPE);
        check("centre and size pack straight through",
                g[0] == 0.25f && g[1] == 0.75f && g[2] == 0.5f && g[3] == 0.2f);
        // Pre-resolved so no shader calls cos() per pixel for a value constant across the draw.
        check("rotation packs as cos/sin, not as an angle",
                Math.abs(g[4]) < 0.001f && Math.abs(g[5] - 1f) < 0.001f);
        check("the corner fraction survives", Math.abs(g[6] - 0.5f) < 0.001f);

        check("ops come from the ONE fold authority",
                MaskSdf.packOps(s).length == 1
                        && MaskSdf.packOps(s)[0] == MaskSdf.OP_UNION);

        // A stack larger than the uniform array is truncated, not overflowed.
        CompositingSpec many = new CompositingSpec();
        for (int i = 0; i < MaskSdf.MAX_SHAPES + 4; i++) many.addShape();
        check("more shapes than the uniform array holds are dropped, not overflowed",
                MaskSdf.packShapes(many, W, H).length
                        == MaskSdf.MAX_SHAPES * MaskSdf.FLOATS_PER_SHAPE);
    }

    // ── plumbing ────────────────────────────────────────────────────────────

    static float cov(CompositingSpec s, float x, float y) {
        return MaskSdf.coverage(s, x, y, W, H);
    }

    static CompositingSpec.MaskShape shape(float cx, float cy, float w, float h,
                                           float rot, float corner) {
        CompositingSpec.MaskShape m = new CompositingSpec.MaskShape();
        m.cx = cx; m.cy = cy; m.w = w; m.h = h; m.rotationDeg = rot; m.corner = corner;
        return m;
    }

    static CompositingSpec spec(CompositingSpec.MaskShape... shapes) {
        CompositingSpec s = new CompositingSpec();
        for (CompositingSpec.MaskShape m : shapes) {
            CompositingSpec.MaskShape added = s.addShape();
            added.cx = m.cx; added.cy = m.cy; added.w = m.w; added.h = m.h;
            added.rotationDeg = m.rotationDeg; added.corner = m.corner; added.mode = m.mode;
        }
        return s;
    }

    static void check(String what, boolean ok) {
        if (ok) { passed++; System.out.println("  ok   " + what); }
        else { failed++; System.out.println("  FAIL " + what); }
    }
}
