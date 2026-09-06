import com.fadcam.ui.faditor.fx.FxCompiler;
import com.fadcam.ui.faditor.fx.FxCost;
import com.fadcam.ui.faditor.fx.FxEffectDef;
import com.fadcam.ui.faditor.fx.FxInstance;
import com.fadcam.ui.faditor.fx.FxParam;
import com.fadcam.ui.faditor.fx.FxRegistry;
import com.fadcam.ui.faditor.fx.FxStack;
import com.fadcam.ui.faditor.fx.FxUniforms;
import com.fadcam.ui.faditor.fx.GradientCurve;
import com.fadcam.ui.faditor.fx.GradientRamp;
import com.fadcam.ui.faditor.keyframe.Easing;
import com.fadcam.ui.faditor.keyframe.KeyframeSet;

import java.util.List;

/**
 * JVM harness for the FX compiler — pass planning, both emits, and the stack model.
 *
 * <p><b>The GOLDEN STRINGS are the point of this file.</b> Every effect is authored once in GLSL
 * and translated to AGSL for the preview; nothing at runtime compares the two, so a careless
 * edit to the rewrite table would show up only as a preview that quietly disagrees with the
 * exported file. Pinning both emits makes that edit fail here instead. The friction is
 * deliberate — if these tests are annoying to update, they are working.</p>
 *
 * <p>The other load-bearing group is FUSION. Pointwise cards must collapse into one pass or the
 * cost model is a fiction, and a SAMPLER must open its own or it would read a half-built image.</p>
 *
 * <p>Run: {@code bash tools/jvm-harness/run-fx.sh}</p>
 */
public class FxCompilerTest {

    private static int passed = 0, failed = 0;

    public static void main(String[] args) {
        registryIsValid();
        everyEffectCompilesToBothBackends();
        dialectLeaksNothing();
        fusionCounts();
        uniformsAreCollisionFree();
        goldenStrings();
        gradientRampModel();
        gradientEffectCompiles();
        curvePathModel();
        curveEffectCompiles();
        stackIdentityAndKeys();
        slotsSurviveReorder();
        serializationRoundTrip();
        costModel();
        spatialUnitsAreResolutionIndependent();

        System.out.println(failed == 0 ? "ALL GREEN (" + passed + "/" + (passed + failed) + ")"
                : "FAILURES: " + failed + " (passed " + passed + ")");
        if (failed > 0) System.exit(1);
    }

    private static final int K = 8;   // kernel half-width used throughout

    // ── Registry ────────────────────────────────────────────────────────────

    static void registryIsValid() {
        List<String> problems = FxRegistry.validate();
        check("the registry self-check passes: " + problems, problems.isEmpty());
        check("all 17 effects are present (12 v1 + Solid Color + Gradient + Brightness + the legacy "
                        + "grade's two halves, color_grade and film)",
                FxRegistry.all().size() == 17);
    }

    static void everyEffectCompilesToBothBackends() {
        boolean allGlsl = true, allAgsl = true;
        String bad = "";
        for (FxEffectDef def : FxRegistry.all()) {
            FxStack s = new FxStack();
            s.add(def.id);
            FxCompiler.Plan plan = FxCompiler.plan(s);
            if (plan.passes.isEmpty()) { allGlsl = false; bad = def.id + " planned 0 passes"; continue; }
            for (FxCompiler.Pass p : plan.passes) {
                String g = FxCompiler.emitGlsl(p, K);
                String a = FxCompiler.emitAgsl(p, K);
                if (g.isEmpty() || !g.contains("void main")) { allGlsl = false; bad = def.id; }
                if (a.isEmpty() || !a.contains("half4 main")) { allAgsl = false; bad = def.id; }
                // No macro may survive into a driver's hands.
                if (g.contains("FX_") || a.contains("FX_")) {
                    allGlsl = false; bad = def.id + " left a macro unexpanded";
                }
            }
        }
        check("every registry effect emits GLSL" + (allGlsl ? "" : " — " + bad), allGlsl);
        check("every registry effect emits AGSL" + (allAgsl ? "" : " — " + bad), allAgsl);
    }

    static void dialectLeaksNothing() {
        FxStack s = new FxStack();
        s.add("gaussian_blur");
        s.add("levels");
        FxCompiler.Plan plan = FxCompiler.plan(s);
        StringBuilder allGlsl = new StringBuilder(), allAgsl = new StringBuilder();
        for (FxCompiler.Pass p : plan.passes) {
            allGlsl.append(FxCompiler.emitGlsl(p, K));
            allAgsl.append(FxCompiler.emitAgsl(p, K));
        }
        String g = allGlsl.toString(), a = allAgsl.toString();

        check("AGSL contains no vec2/vec3/vec4",
                !a.contains("vec2") && !a.contains("vec3") && !a.contains("vec4"));
        check("AGSL contains no texture2D", !a.contains("texture2D"));
        check("AGSL contains no preprocessor directive", !a.contains("#"));
        check("GLSL contains no float2/half4", !g.contains("float2") && !g.contains("half4"));
        check("GLSL declares the sampler", g.contains("uniform sampler2D uTexSampler"));
        check("AGSL declares the input shader", a.contains("uniform shader inputShader"));

        // The prologue is the whole reason the shipping preview vignette was a no-op.
        check("AGSL normalises the coordinate before any body sees it",
                a.contains("float2 uv = (co - uOrigin) / uSize"));
        check("...and the kernel bound is a LITERAL, not a uniform",
                g.contains("i <= 8") && a.contains("i <= 8"));
    }

    // ── Fusion ──────────────────────────────────────────────────────────────

    static void fusionCounts() {
        // TWO different counts, deliberately. stagesOf() is the STRUCTURE — how many times the
        // image must be finished and re-read. passesOf() is the COST — a separable blur is one
        // stage but two render passes. Conflating them is how a cost model becomes a fiction.
        check("4 pointwise cards fuse into ONE stage",
                stagesOf("invert", "posterize", "threshold", "duotone") == 1);
        check("...costing one render pass",
                passesOf("invert", "posterize", "threshold", "duotone") == 1);
        check("pointwise + blur + pointwise = 3 stages",
                stagesOf("invert", "gaussian_blur", "posterize") == 3);
        check("...costing 4 render passes, because the blur is separable",
                passesOf("invert", "gaussian_blur", "posterize") == 4);
        check("a lone pointwise card is 1 stage", stagesOf("invert") == 1);
        check("a separable blur is ONE stage but TWO render passes",
                stagesOf("gaussian_blur") == 1 && passesOf("gaussian_blur") == 2);

        // UV_REMAP opens NO pass — it folds into whatever samples next.
        check("remap + blur is still just the blur's one stage",
                stagesOf("pixelate", "gaussian_blur") == 1);
        check("remap + pointwise = 1 pointwise stage + 1 resample",
                stagesOf("pixelate", "invert") == 2);

        FxStack s = new FxStack();
        s.add("pixelate");
        s.add("gaussian_blur");
        FxCompiler.Plan p = FxCompiler.plan(s);
        check("the remap is folded INTO the sampler's pass, not given its own",
                p.passes.size() == 1 && p.passes.get(0).remaps.size() == 1);

        // A disabled card must cost nothing at all — that is what makes bypass the cheap escape.
        FxStack off = new FxStack();
        off.add("invert");
        FxInstance blur = off.add("gaussian_blur");
        blur.enabled = false;
        check("a disabled card contributes no pass",
                FxCompiler.plan(off).passCount() == 1);
    }

    /** Render passes, counting a separable kernel's two halves separately. */
    static int passesOf(String... ids) {
        return FxCompiler.plan(stackOf(ids)).passCount();
    }

    /** Pass ENTRIES — how many times the image must be finished and re-read. */
    static int stagesOf(String... ids) {
        return FxCompiler.plan(stackOf(ids)).passes.size();
    }

    // ── Uniforms ────────────────────────────────────────────────────────────

    static void uniformsAreCollisionFree() {
        // The SAME effect twice in ONE fused pass is the case namespacing exists for.
        FxStack s = new FxStack();
        s.add("levels");
        s.add("levels");
        FxCompiler.Plan plan = FxCompiler.plan(s);
        check("two identical cards still fuse", plan.passes.size() == 1);

        String g = FxCompiler.emitGlsl(plan.passes.get(0), K);
        check("the first card's uniforms are slot-0 namespaced", g.contains("u0_inBlack"));
        check("the second's are slot-1 namespaced", g.contains("u1_inBlack"));

        List<FxUniforms.Value> vals = FxUniforms.forPass(plan.passes.get(0));
        int u0 = 0, u1 = 0;
        for (FxUniforms.Value v : vals) {
            if (v.name.startsWith("u0_")) u0++;
            if (v.name.startsWith("u1_")) u1++;
        }
        check("both cards pack their own uniforms", u0 > 0 && u1 > 0 && u0 == u1);

        boolean anyDup = false;
        for (int i = 0; i < vals.size(); i++) {
            for (int j = i + 1; j < vals.size(); j++) {
                if (vals.get(i).name.equals(vals.get(j).name)) anyDup = true;
            }
        }
        check("no uniform name is emitted twice", !anyDup);

        // The fold controls travel as floats — ES2 int uniforms are patchy on old drivers.
        boolean foldFloats = true;
        for (FxUniforms.Value v : vals) {
            if (v.name.endsWith("_blend") && v.components() != 1) foldFloats = false;
        }
        check("the blend mode packs as a single float", foldFloats);
    }

    // ── Golden strings ──────────────────────────────────────────────────────

    static void goldenStrings() {
        // A fixed three-effect stack. If either of these assertions fails, an emit CHANGED —
        // read the diff and decide whether it was meant, then update the golden.
        FxStack s = new FxStack();
        s.add("invert");
        s.add("gaussian_blur");
        s.add("posterize");
        FxCompiler.Plan plan = FxCompiler.plan(s);
        check("the fixed stack plans 3 stages / 4 render passes",
                plan.passes.size() == 3 && plan.passCount() == 4);

        String g0 = FxCompiler.emitGlsl(plan.passes.get(0), K);
        String a0 = FxCompiler.emitAgsl(plan.passes.get(0), K);

        check("GOLDEN glsl pass0 folds card 0 exactly once",
                countOf(g0, "c = fxBlendOver(c, fx0(uv, c), u0_opacity, u0_blend);") == 1);
        check("GOLDEN agsl pass0 folds identically",
                countOf(a0, "c = fxBlendOver(c, fx0(uv, c), u0_opacity, u0_blend);") == 1);
        check("GOLDEN glsl pass0 declares fx0 as vec4",
                g0.contains("vec4 fx0(vec2 uv, vec4 src) {"));
        check("GOLDEN agsl pass0 declares fx0 as half4",
                a0.contains("half4 fx0(float2 uv, half4 src) {"));
        // A POINTWISE pass reads its incoming pixel directly — no fxRemap, because a remap
        // attaches to the SAMPLER that consumes it, and applying one here would move the wrong
        // image.
        check("GOLDEN glsl pass0 reads its source through fxClamp only",
                g0.contains("texture2D(uTexSampler, fxClamp(uv))"));
        check("GOLDEN agsl pass0 reads its source through the prologue's uv",
                a0.contains("half4 c = inputShader.eval(uOrigin + fxClamp(fxRemap(uv)) * uSize)"));
        check("GOLDEN the two emits have the SAME fold line count",
                countOf(g0, "fxBlendOver") == countOf(a0, "fxBlendOver"));

        // The blur pass carries the blend function too — one authority, both passes.
        String g1 = FxCompiler.emitGlsl(plan.passes.get(1), K);
        check("GOLDEN the blend fn is present in the sampler pass",
                g1.contains("fxBlendOver"));
        check("GOLDEN the sampler pass declares uDir for the separable axis",
                g1.contains("uniform vec2 uDir"));
    }

    // ── Gradient ────────────────────────────────────────────────────────────

    static void gradientRampModel() {
        GradientRamp r = GradientRamp.defaultRamp();
        check("default ramp starts black->white", r.colorStops.size() == 2
                && r.colorStops.get(0).color == 0xFF000000
                && r.colorStops.get(1).color == 0xFFFFFFFF);
        check("default ramp is fully opaque throughout",
                Math.abs(r.sampleAlpha(0f) - 1f) < 0.001f
                        && Math.abs(r.sampleAlpha(0.5f) - 1f) < 0.001f
                        && Math.abs(r.sampleAlpha(1f) - 1f) < 0.001f);
        check("midpoint of black->white samples mid-grey",
                Math.abs(((r.sampleColor(0.5f) >> 16) & 0xFF) - 127.5) < 2.0);

        int added = 0;
        for (int i = 0; i < GradientRamp.CAP; i++) {
            if (r.addColorStop(0.5f, 0xFF123456)) added++;
        }
        check("stops beyond CAP are refused, not silently dropped later",
                r.colorStops.size() == GradientRamp.CAP && !r.addColorStop(0.9f, 0xFF000000));
        check("...and the successful adds actually landed", added == GradientRamp.CAP - 2);

        GradientRamp bare = GradientRamp.defaultRamp();
        check("removing below two stops is refused",
                !bare.removeColorStop(0) && bare.colorStops.size() == 2);

        // Pack/unpack round trip, including the sentinel-drops-silently contract.
        GradientRamp packed = GradientRamp.defaultRamp();
        packed.addColorStop(0.5f, 0xFF7F7F7F);
        packed.mirror = true;
        packed.solidBands = true;
        float[] a = packed.toFloatArray();
        check("packed array is the declared length", a.length == GradientRamp.PACKED_LENGTH);
        GradientRamp back = GradientRamp.fromFloatArray(a);
        check("float round trip keeps stop count", back.colorStops.size() == 3);
        check("float round trip keeps flags", back.mirror && back.solidBands && !back.flip);

        // Rich JSON round trip.
        GradientRamp j = GradientRamp.fromJson(packed.toJson());
        check("json round trip keeps stop count and flags",
                j.colorStops.size() == 3 && j.mirror && j.solidBands);

        // Banding: a solid ramp must jump, not blend, at the segment midpoint.
        GradientRamp solid = GradientRamp.defaultRamp();
        solid.solidBands = true;
        check("solid band holds the left colour just before the midpoint",
                solid.sampleColor(0.49f) == 0xFF000000);
        check("solid band snaps to the right colour just after the midpoint",
                solid.sampleColor(0.51f) == 0xFFFFFFFF);
    }

    static void gradientEffectCompiles() {
        FxStack s = new FxStack();
        FxInstance g = s.add("gradient_fill");
        FxCompiler.Plan plan = FxCompiler.plan(s);
        check("a lone gradient card is one fused stage", plan.passes.size() == 1);
        String glsl = FxCompiler.emitGlsl(plan.passes.get(0), K);
        String agsl = FxCompiler.emitAgsl(plan.passes.get(0), K);
        check("gradient GLSL declares the flags uniform", glsl.contains("uniform vec3 u0_ramp_flags"));
        check("gradient GLSL declares all 8 colour stops",
                glsl.contains("u0_ramp_c7") && !glsl.contains("u0_ramp_c8"));
        check("gradient GLSL declares all 8 opacity stops",
                glsl.contains("u0_ramp_o7") && !glsl.contains("u0_ramp_o8"));
        check("gradient AGSL uses the same slot naming", agsl.contains("u0_ramp_flags"));
        check("no FX_ macro survives expansion in either backend",
                !glsl.contains("FX_") && !agsl.contains("FX_"));
        check("no \"Curve (soon)\" stub label survives",
                !glsl.contains("Curve (soon)"));

        // The Linear/Reflected extent the preview's start/end handles drag. Its DEFAULT is the
        // load-bearing part: 1.0 must reproduce the old fixed extent exactly, or every gradient
        // saved before the param existed would shift the first time it is reopened.
        check("the gradient declares a length uniform", glsl.contains("uniform float u0_length"));
        check("Linear divides by it", glsl.contains("t = rd.x / len + 0.5;"));
        check("Reflected divides by it", glsl.contains("t = abs(rd.x) * 2.0 / len;"));
        check("...and it is floored so a zero length cannot divide by zero",
                glsl.contains("float len = max(u0_length, 0.01);"));
        check("its default is the old fixed extent, so saved gradients do not move",
                FxRegistry.require("gradient_fill").param("length").defaultScalar() == 1f);
        check("the ramp evaluator is declared exactly once in GLSL",
                countOf(glsl, "vec3 fxGradColor(") == 1);
        check("the ramp evaluator is declared exactly once in AGSL",
                countOf(agsl, "float3 fxGradColor(") == 1);

        List<FxUniforms.Value> vals = FxUniforms.forPass(plan.passes.get(0));
        boolean sawFlags = false;
        for (FxUniforms.Value v : vals) if (v.name.equals("u0_ramp_flags")) sawFlags = true;
        check("FxUniforms packs the gradient's flags value", sawFlags);

        // A stack with an ordinary effect ahead of it must NOT pull the gradient prelude in.
        FxStack plain = new FxStack();
        plain.add("invert");
        String plainGlsl = FxCompiler.emitGlsl(FxCompiler.plan(plain).passes.get(0), K);
        check("a stack with no gradient card carries no gradient evaluator",
                !plainGlsl.contains("fxGradColor"));
        check("...and no curve evaluator either", !plainGlsl.contains("fxCurveT"));
    }

    // ── The Curve shape ─────────────────────────────────────────────────────
    //
    // The gradient runs along an editable bezier path instead of a straight line, parameterised
    // by distance ALONG the path. The shader can only do that because the CPU has already
    // resampled the path at equal arc length — so BOTH halves are pinned here: the model's
    // resampling arithmetic, and the emitted GLSL/AGSL that consumes it.

    static void curvePathModel() {
        GradientCurve c = GradientCurve.defaultCurve();
        check("a fresh path is a straight line with no vertices", c.vertices.isEmpty());
        check("...from the left edge", c.start.x == 0f && c.start.y == 0.5f);
        check("...to the right edge", c.end.x == 1f && c.end.y == 0.5f);
        check("...with no bend in it", c.start.hx == 0f && c.end.hy == 0f);

        // An unbent path must sample to a straight, EVENLY SPACED run of points — that is the
        // whole "Curve == Linear until it is bent" promise, checked rather than assumed.
        float[] pts = c.samplePoints(GradientCurve.SAMPLE_POINTS);
        check("samplePoints returns x/y per sample",
                pts.length == GradientCurve.SAMPLE_POINTS * 2);
        boolean flat = true, even = true;
        for (int i = 0; i < GradientCurve.SAMPLE_POINTS; i++) {
            float want = i / (GradientCurve.SAMPLE_POINTS - 1f);
            if (Math.abs(pts[i * 2] - want) > 1e-3f) even = false;
            if (Math.abs(pts[i * 2 + 1] - 0.5f) > 1e-5f) flat = false;
        }
        check("an unbent path samples flat", flat);
        check("...and at even spacing, so Curve renders exactly like Linear", even);

        c.setVertexCount(GradientCurve.VERTEX_CAP + 5);
        check("asking for more vertices than the cap stops at the cap",
                c.vertices.size() == GradientCurve.VERTEX_CAP);
        check("a vertex past the cap is refused, not silently dropped", !c.addVertex(0.5f, 0.5f));
        check("new vertices land ON the line, not at the origin",
                Math.abs(c.vertices.get(0).y - 0.5f) < 1e-5f
                        && c.vertices.get(0).x > 0f && c.vertices.get(0).x < 1f);
        c.setVertexCount(1);
        check("shrinking keeps the vertices the user placed first", c.vertices.size() == 1);

        // EQUAL ARC LENGTH is the property the shader's compile-time segment literals depend on.
        // A bent path is the case where parameter-spacing and arc-spacing come apart, so bend it
        // hard and check the chords are still the same length as each other.
        GradientCurve bent = GradientCurve.defaultCurve();
        bent.start.hy = -0.6f;
        bent.end.hy = 0.6f;
        float[] bp = bent.samplePoints(GradientCurve.SAMPLE_POINTS);
        double lo = Double.MAX_VALUE, hi = 0;
        for (int i = 0; i + 1 < GradientCurve.SAMPLE_POINTS; i++) {
            double d = Math.hypot(bp[i * 2 + 2] - bp[i * 2], bp[i * 2 + 3] - bp[i * 2 + 1]);
            lo = Math.min(lo, d);
            hi = Math.max(hi, d);
        }
        check("a bent path's chords are equal length to within 2% — the arc-length resample "
                + "is what lets the shader use compile-time segment bounds", hi / lo < 1.02);

        // Degenerate: every anchor on one spot. Must degrade to a point, not divide by zero.
        GradientCurve dot = GradientCurve.defaultCurve();
        dot.end.x = dot.start.x;
        dot.end.y = dot.start.y;
        float[] dp = dot.samplePoints(GradientCurve.SAMPLE_POINTS);
        boolean finite = true;
        for (float v : dp) if (Float.isNaN(v) || Float.isInfinite(v)) finite = false;
        check("a zero-length path samples finite, not NaN", finite);

        GradientCurve back = GradientCurve.fromFloatArray(bent.toFloatArray());
        check("the packed form round-trips the anchors",
                Math.abs(back.start.hy - (-0.6f)) < 1e-6f && back.end.x == 1f);
        check("packed length is the declared constant",
                bent.toFloatArray().length == GradientCurve.PACKED_LENGTH);
        check("a garbled array falls back to the default rather than throwing",
                GradientCurve.fromFloatArray(new float[]{1f, 2f}).vertices.isEmpty());
    }

    static void curveEffectCompiles() {
        FxStack s = new FxStack();
        s.add("gradient_fill");
        FxCompiler.Pass pass = FxCompiler.plan(s).passes.get(0);
        String glsl = FxCompiler.emitGlsl(pass, K);
        String agsl = FxCompiler.emitAgsl(pass, K);

        check("the curve's sample uniforms are declared, slot-namespaced",
                glsl.contains("uniform vec4 u0_path_s0;")
                        && glsl.contains("uniform vec4 u0_path_s"
                                + (GradientCurve.SAMPLE_VECS - 1) + ";"));
        check("...and no slot past the cap", !glsl.contains(
                "uniform vec4 u0_path_s" + GradientCurve.SAMPLE_VECS + ";"));
        check("the AGSL emit uses the same slot naming",
                agsl.contains("u0_path_s0") && agsl.contains("half4 u0_path_s0"));

        // THE PARITY RULE. A helper declared twice fails the whole program, the exception is
        // caught, and the effect silently turns off — so "exactly once" is pinned, in both
        // backends, for both curve helpers.
        check("the curve evaluator is declared exactly once in GLSL",
                countOf(glsl, "float fxCurveT(") == 1);
        check("the curve evaluator is declared exactly once in AGSL",
                countOf(agsl, "float fxCurveT(") == 1);
        check("the per-chord helper is declared exactly once in GLSL",
                countOf(glsl, "vec2 fxCurveSeg(") == 1);
        check("the per-chord helper is declared exactly once in AGSL",
                countOf(agsl, "float2 fxCurveSeg(") == 1);

        // DECLARATION BEFORE USE — GLSL ES 1.00 has no forward declarations here, and getting
        // this order wrong is what broke every remap stack once already.
        check("fxCurveSeg is declared before fxCurveT calls it",
                glsl.indexOf("vec2 fxCurveSeg(") < glsl.indexOf("float fxCurveT("));
        check("...and fxCurveT before the card body that calls it",
                glsl.indexOf("float fxCurveT(") < glsl.indexOf("vec4 fx0("));

        check("every chord is tested, and no more than every chord",
                countOf(glsl, "acc = fxCurveSeg(acc, q, p") == GradientCurve.SAMPLE_SEGMENTS);
        check("the chord's share of the ramp is a compile-time literal, not a uniform",
                glsl.contains("* " + (1f / GradientCurve.SAMPLE_SEGMENTS) + ")"));
        check("FX_CURVE_T expanded away in both backends",
                !glsl.contains("FX_CURVE_T") && !agsl.contains("FX_CURVE_T"));
        check("the Curve branch feeds the evaluator aspect-corrected coordinates",
                glsl.contains("fxCurveT(vec2(uv.x * uAspect, uv.y), uAspect, u0_path_s0"));

        // The values must be the RESAMPLED polyline, not the stored anchors — they come from a
        // different code path than the gradient's straight slice, so pin that they arrive at all
        // and that they are vec4s.
        int seen = 0;
        for (FxUniforms.Value v : FxUniforms.forPass(pass)) {
            if (v.name.startsWith("u0_path_s")) {
                seen++;
                if (v.components() != 4) check("curve uniforms are vec4", false);
            }
        }
        check("FxUniforms packs one vec4 per sample pair",
                seen == GradientCurve.SAMPLE_VECS);
    }

    static int countOf(String hay, String needle) {
        int n = 0, i = 0;
        while ((i = hay.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }

    // ── Stack model ─────────────────────────────────────────────────────────

    static void stackIdentityAndKeys() {
        FxStack s = new FxStack();
        FxInstance c = s.add("levels");
        // IDENTITY, not equality: an unanimated stack must not allocate per frame.
        check("resolveAt returns THIS when nothing is keyed", s.resolveAt(1234) == s);

        FxEffectDef def = c.def();
        FxParam p = def.params.get(0);
        s.keys = new KeyframeSet();
        s.keys.getOrCreate(c.track(p)).put(0, 0f, Easing.LINEAR);
        s.keys.getOrCreate(c.track(p)).put(1000, 1f, Easing.LINEAR);

        check("an animated stack resolves to a COPY", s.resolveAt(0) != s);
        FxStack mid = s.resolveAt(500);
        check("the keyed parameter interpolates",
                Math.abs(mid.cards().get(0).getScalar(p) - 0.5f) < 0.01f);
        check("the ORIGINAL is never mutated", s.cards().get(0).getScalar(p) == c.getScalar(p));
        check("the resolved copy carries no keys, so re-resolving is a no-op",
                mid.resolveAt(0) == mid);
        check("the track name is the one the UI will use", c.track(p).equals("fx0." + p.name));
    }

    static void slotsSurviveReorder() {
        FxStack s = new FxStack();
        FxInstance a = s.add("invert");
        FxInstance b = s.add("levels");
        FxInstance c = s.add("posterize");
        check("slots are handed out in order", a.slot == 0 && b.slot == 1 && c.slot == 2);

        // Key card 1, then drag it to the bottom. Its keys must follow it.
        FxParam p = b.def().params.get(0);
        s.keys = new KeyframeSet();
        s.keys.getOrCreate(b.track(p)).put(0, 0.25f, Easing.LINEAR);
        String trackBefore = b.track(p);
        s.move(1, 0);
        check("the reordered card is now first", s.cards().get(0) == b);
        check("...and its SLOT did not change", b.slot == 1);
        check("...so its keyframe track name is untouched", b.track(p).equals(trackBefore));
        check("the key is still readable after the move",
                Math.abs(s.keys.valueAt(b.track(p), 0, -1f) - 0.25f) < 0.001f);

        // Delete the middle card: its keys go, everyone else's stay, and the slot is retired.
        FxInstance readded = s.add("levels");
        check("a new card never reuses a live slot", readded.slot == 3);
        s.remove(s.cards().indexOf(b));
        check("the deleted card's track is gone", !s.keys.hasProperty(trackBefore));
        FxInstance after = s.add("invert");
        check("...and its slot is never handed out again", after.slot == 4);
    }

    static void serializationRoundTrip() {
        FxStack s = new FxStack();
        FxInstance a = s.add("levels");
        FxInstance b = s.add("gaussian_blur");
        FxParam p = a.def().params.get(0);
        a.set(p, 0.33f);
        b.opacity = 0.5f;
        b.blendMode = com.fadcam.ui.faditor.model.BlendModes.SCREEN;
        b.collapsed = true;
        s.keys = new KeyframeSet();
        s.keys.getOrCreate(b.track(b.def().params.get(0))).put(250, 7f, Easing.LINEAR);
        s.keys.getOrCreate(b.track(b.def().params.get(0))).put(750, 2f, Easing.LINEAR);

        FxStack r = FxStack.fromJson(s.toJson());
        check("round-trip keeps the card count", r.size() == 2);
        check("round-trip keeps an authored value",
                Math.abs(r.cards().get(0).getScalar(p) - 0.33f) < 0.001f);
        check("round-trip keeps opacity, blend and collapsed",
                r.cards().get(1).opacity == 0.5f
                        && com.fadcam.ui.faditor.model.BlendModes.SCREEN.equals(
                                r.cards().get(1).blendMode)
                        && r.cards().get(1).collapsed);
        check("round-trip keeps slots", r.cards().get(1).slot == b.slot);
        check("round-trip keeps the keyframes", r.isAnimated());
        check("a second trip is stable", r.toJson().toString().equals(s.toJson().toString()));

        // An untouched card is a tiny object — the additive-schema rule.
        FxStack plain = new FxStack();
        plain.add("invert");
        String j = plain.toJson().toString();
        check("an untouched card writes no flags and no values",
                !j.contains("\"off\"") && !j.contains("\"opacity\"")
                        && !j.contains("\"blend\"") && !j.contains("\"v\""));

        // A SINGLE key is a static value, not an animation, so isAnimated() is false for it.
        // Gating the save on isAnimated() therefore dropped that key silently — the value you
        // dialled in at a keyframe simply vanished on reload.
        FxStack one = new FxStack();
        FxInstance oc = one.add("levels");
        FxParam op = oc.def().params.get(0);
        one.keys = new KeyframeSet();
        one.keys.getOrCreate(oc.track(op)).put(400, 0.8f, Easing.LINEAR);
        check("a single keyframe is NOT treated as animation", !one.isAnimated());
        FxStack oneBack = FxStack.fromJson(one.toJson());
        check("...but it still survives the round trip",
                oneBack.keys != null
                        && Math.abs(oneBack.keys.valueAt(oc.track(op), 400, -1f) - 0.8f) < 0.001f);

        // A card naming an effect this build lacks is DROPPED, not kept as a silent no-op.
        check("an unknown effect id is dropped on load",
                FxInstance.fromJson(com.google.gson.JsonParser
                        .parseString("{\"id\":\"no_such_effect\",\"slot\":0}")
                        .getAsJsonObject()) == null);
    }

    static void costModel() {
        FxCost.Estimate light = FxCost.estimate(stackOf("invert"));
        check("one pointwise card is LIGHT", light.level == FxCost.Level.LIGHT);
        check("...and reports one pass", light.passes == 1);
        check("the header label reads sensibly: " + light.label(),
                light.label().equals("1 pass · light"));

        FxCost.Estimate blur = FxCost.estimate(stackOf("gaussian_blur"));
        check("a blur is at least MODERATE", blur.level != FxCost.Level.LIGHT);
        check("...and counts both halves of the separable kernel", blur.passes == 2);

        FxCost.Estimate fused = FxCost.estimate(
                stackOf("invert", "posterize", "threshold", "duotone", "levels", "gradient_map"));
        // rgb_shift is deliberately NOT in that list: it uses FX_SAMPLE, so it is a SAMPLER and
        // would break the fuse — which is the rule working, not a gap in it.
        check("six fused pointwise cards are still ONE pass", fused.passes == 1);
        check("...and stay within the preview budget", !fused.exceedsPreviewBudget());

        FxCost.Estimate heavy = FxCost.estimate(
                stackOf("gaussian_blur", "directional_blur", "gaussian_blur"));
        check("three sampler cards are HEAVY", heavy.level == FxCost.Level.HEAVY);
        check("...and exceed the preview pass budget", heavy.exceedsPreviewBudget());
    }

    // ── Spatial units ───────────────────────────────────────────────────────

    /**
     * NO SPATIAL EFFECT MAY STEP BY {@code uTexel}.
     *
     * <p>A texel is a unit of the RENDER TARGET, and the two renderers do not share one: the
     * preview runs the chain at the decoded video's size, the export at the canvas size the user
     * picked from a dropdown. An offset quoted in texels therefore meant a different-sized blur
     * in the editor than in the file — and, the half that needs no preview to be wrong at all, a
     * different-sized blur in a 720p export than in a 1080p one.</p>
     *
     * <p>Run against the un-fixed bodies every one of these fails: gaussian_blur,
     * directional_blur, pixelate and rgb_shift all emitted {@code uTexel}. The last check is what
     * makes the change safe rather than merely different — at
     * {@link FxCompiler#REFERENCE_HEIGHT} the new step IS the old texel, so every radius anyone
     * has ever dialled in against the editor still means what it meant.</p>
     */
    static void spatialUnitsAreResolutionIndependent() {
        String[] spatial = {"gaussian_blur", "directional_blur", "pixelate", "rgb_shift"};
        for (String id : spatial) {
            String glsl = FxCompiler.emitGlsl(FxCompiler.plan(stackOf(id)).passes.get(0), K);
            // The DECLARATION is emitted for every pass and is not the subject — uTexel is still
            // a legitimate uniform, and other bodies read it deliberately. What must not survive
            // is a USE of it inside a spatial offset, so the declaration line is dropped first.
            StringBuilder body = new StringBuilder();
            for (String line : glsl.split("\n")) {
                if (line.startsWith("uniform ")) continue;
                body.append(line).append('\n');
            }
            check(id + " does not step by uTexel", !body.toString().contains(FxCompiler.U_TEXEL));
            check(id + " steps against the reference height",
                    glsl.contains("1.0 / (1080.0 * " + FxCompiler.U_ASPECT + ")"));
        }
        // The arithmetic the whole change rests on: at a target 1080 tall, one step in y is
        // 1/1080 — bit-identical to what uTexel.y would have been — and one step in x is
        // 1/(1080*aspect), which for a 1080-tall frame is 1/width, i.e. uTexel.x. Same picture
        // distance on both axes, and the same numbers the old code produced at that height.
        float step = 1f / FxCompiler.REFERENCE_HEIGHT;
        float aspect = 16f / 9f;
        check("one step in y equals a 1080-tall target's texel", Math.abs(step - 1f / 1080f) < 1e-9);
        check("one step in x equals that target's texel too",
                Math.abs(step / aspect - 1f / 1920f) < 1e-9);
    }

    static FxStack stackOf(String... ids) {
        FxStack s = new FxStack();
        for (String id : ids) s.add(id);
        return s;
    }

    // ── plumbing ────────────────────────────────────────────────────────────

    static void check(String what, boolean ok) {
        if (ok) { passed++; System.out.println("  ok   " + what); }
        else { failed++; System.out.println("  FAIL " + what); }
    }
}
