package com.fadcam.ui.faditor.fx;

import androidx.annotation.NonNull;

import com.fadcam.ui.faditor.model.BlendModes;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns an {@link FxStack} into shader source — GLSL ES 1.00 for the export path, AGSL for the
 * preview.
 *
 * <p><b>Authored once, in GLSL. Export emits the bytes verbatim; the preview goes through a
 * translation table.</b> The asymmetry is deliberate: export is ground truth, so a mistake in
 * the translation produces a wrong PREVIEW, never a wrong file.</p>
 *
 * <p>Three facts about AGSL shape everything here:</p>
 * <ul>
 *   <li><b>No preprocessor.</b> Every macro is expanded in Java, for BOTH backends, so the two
 *       emits stay structurally identical instead of diverging at the one place nobody reads.</li>
 *   <li><b>Loops must be compile-time bounded.</b> Kernels emit LITERAL trip counts — which is
 *       also why this project ships its own Gaussian rather than Skia's.</li>
 *   <li><b>No {@code vecN} type names.</b> A word-boundary rewrite handles it; word-boundary and
 *       not plain replace, or {@code vec2} inside an identifier would be mangled.</li>
 * </ul>
 *
 * <p><b>The normalization prologue is mandatory and no authored body can bypass it.</b> In
 * {@code createRuntimeShaderEffect} the coordinate handed to {@code main} is in LOCAL PIXEL
 * space, not 0..1. That single fact made the shipping preview vignette a total no-op for its
 * whole life — it computed {@code distance(co, float2(0.5))} against a number around 700. Every
 * body here sees {@code FX_UV}, which the prologue has already normalised.</p>
 *
 * <p>Pure string work, android-free, so the JVM harness pins it with golden strings.</p>
 */
public final class FxCompiler {

    private FxCompiler() {}

    /** Uniform names the compiler owns. Bodies reach them through macros, never by name. */
    public static final String U_ORIGIN = "uOrigin";
    public static final String U_SIZE = "uSize";
    public static final String U_TEXEL = "uTexel";
    public static final String U_ASPECT = "uAspect";
    public static final String U_TIME = "uTime";
    public static final String U_DIR = "uDir";

    // ── Pass planning ───────────────────────────────────────────────────────

    /** One compiled pass: the cards folded into it, and how many times it must run. */
    public static final class Pass {
        /** Cards whose colour work happens in this pass, bottom-up. */
        @NonNull public final List<FxInstance> cards = new ArrayList<>();
        /**
         * UV_REMAP cards folded into this pass's sampling coordinate. They cost no pass of their
         * own — that is the entire point of the capability.
         */
        @NonNull public final List<FxInstance> remaps = new ArrayList<>();
        /** True when this pass samples neighbours and therefore had to open its own. */
        public boolean sampler;
        /** Separable kernels declare 2; the compiler emits H and V with {@link #U_DIR}. */
        public int repeats = 1;

        @NonNull
        @Override
        public String toString() {
            return "Pass" + (sampler ? "[sampler x" + repeats + "]" : "") + cards;
        }
    }

    /** The whole plan for a stack. */
    public static final class Plan {
        @NonNull public final List<Pass> passes = new ArrayList<>();
        /** Total render passes, counting a separable blur's two halves separately. */
        public int passCount() {
            int n = 0;
            for (Pass p : passes) n += Math.max(1, p.repeats);
            return n;
        }
        @NonNull
        @Override
        public String toString() { return passes.toString(); }
    }

    /**
     * Walk the stack bottom→top and decide where the pass boundaries fall.
     *
     * <p>POINTWISE and GENERATOR cards fuse: they read only the colour in hand, so any number of
     * them collapse into one pass. UV_REMAP opens no pass and accumulates into the next
     * sampler's coordinate. SAMPLER wants a NEIGHBOUR, which needs a finished image, and that is
     * what forces a boundary.</p>
     */
    @NonNull
    public static Plan plan(@NonNull FxStack stack) {
        Plan plan = new Plan();
        Pass current = null;
        List<FxInstance> pendingRemaps = new ArrayList<>();

        for (FxInstance card : stack.active()) {
            FxEffectDef def = card.def();
            if (def == null) continue;
            if (def.capability == FxEffectDef.Capability.UV_REMAP) {
                pendingRemaps.add(card);
                continue;
            }
            if (def.capability == FxEffectDef.Capability.SAMPLER) {
                Pass p = new Pass();
                p.sampler = true;
                p.repeats = Math.max(1, def.passes);
                p.cards.add(card);
                p.remaps.addAll(pendingRemaps);
                pendingRemaps.clear();
                plan.passes.add(p);
                current = null;             // a sampler closes itself; nothing fuses onto it
                continue;
            }
            if (current == null) {
                current = new Pass();
                plan.passes.add(current);
            }
            current.cards.add(card);
        }

        // A trailing remap run still has to happen, so it costs one 1-tap resample pass. Folding
        // it into a preceding pointwise pass would apply it to the WRONG image — the remap must
        // move pixels that already exist, not the ones being computed.
        if (!pendingRemaps.isEmpty()) {
            Pass p = new Pass();
            p.sampler = true;
            p.remaps.addAll(pendingRemaps);
            plan.passes.add(p);
        }
        return plan;
    }

    // ── Emit ────────────────────────────────────────────────────────────────

    /** GLSL ES 1.00 fragment source for one pass. This is the byte the export path compiles. */
    @NonNull
    public static String emitGlsl(@NonNull Pass pass, int kernelHalf) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("precision highp float;\n")
          .append("varying vec2 vFxUv;\n")
          .append("uniform sampler2D uTexSampler;\n")
          .append("uniform vec2 ").append(U_TEXEL).append(";\n")
          .append("uniform float ").append(U_ASPECT).append(";\n")
          .append("uniform float ").append(U_TIME).append(";\n")
          .append("uniform vec2 ").append(U_DIR).append(";\n");
        emitUniformDecls(sb, pass, false);
        sb.append(PRELUDE_GLSL);
        if (passHasGradient(pass)) sb.append(PRELUDE_GRAD_GLSL);
        sb.append(BlendModes.glslBlendFnWithModeParam()).append(FOLD_FN);
        // fxuvN → fxRemap → fxN. See emitRemapCardFns for why this order is the only one that
        // compiles.
        emitRemapCardFns(sb, pass, false, kernelHalf);
        emitRemapFn(sb, pass, false, kernelHalf);
        emitCardFns(sb, pass, false, kernelHalf);
        sb.append("void main() {\n")
          .append("  vec2 uv = vFxUv;\n")
          .append("  vec4 c = ").append(pass.sampler ? "FX_SAMPLE_SRC" : "texture2D(uTexSampler, fxClamp(uv))")
          .append(";\n");
        emitFold(sb, pass);
        sb.append("  gl_FragColor = c;\n}\n");
        return finish(sb.toString(), pass, false, kernelHalf);
    }

    /** AGSL source for the same pass — the preview's translation of the very same bodies. */
    @NonNull
    public static String emitAgsl(@NonNull Pass pass, int kernelHalf) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("uniform shader inputShader;\n")
          .append("uniform float2 ").append(U_ORIGIN).append(";\n")
          .append("uniform float2 ").append(U_SIZE).append(";\n")
          .append("uniform float2 ").append(U_TEXEL).append(";\n")
          .append("uniform float ").append(U_ASPECT).append(";\n")
          .append("uniform float ").append(U_TIME).append(";\n")
          .append("uniform float2 ").append(U_DIR).append(";\n");
        emitUniformDecls(sb, pass, true);
        sb.append(PRELUDE_AGSL);
        if (passHasGradient(pass)) sb.append(PRELUDE_GRAD_AGSL);
        sb.append(toAgsl(BlendModes.glslBlendFnWithModeParam())).append(toAgsl(FOLD_FN));
        // Same chain as the GLSL path — AGSL is likewise declaration-before-use, and has no
        // prototypes to fall back on.
        emitRemapCardFns(sb, pass, true, kernelHalf);
        emitRemapFn(sb, pass, true, kernelHalf);
        emitCardFns(sb, pass, true, kernelHalf);
        // THE PROLOGUE. co is in local pixel space; every body downstream sees 0..1.
        sb.append("half4 main(float2 co) {\n")
          .append("  float2 uv = (co - ").append(U_ORIGIN).append(") / ").append(U_SIZE).append(";\n")
          .append("  half4 c = FX_SAMPLE_SRC;\n");
        emitFold(sb, pass);
        sb.append("  return c;\n}\n");
        return finish(sb.toString(), pass, true, kernelHalf);
    }

    // ── Pieces ──────────────────────────────────────────────────────────────

    /**
     * The per-card fold, built ON TOP of the shared blend authority rather than beside it.
     *
     * <p>{@code BlendModes.glslBlendFnWithModeParam()} is used, not {@code GLSL_BLEND_FN}: a
     * FUSED pass folds several cards with DIFFERENT blend modes, so there cannot be one
     * {@code uBlendMode} uniform for the pass. That variant rewrites only the signature line, so
     * the equations still exist exactly once in the codebase.</p>
     */
    private static final String FOLD_FN =
            "vec4 fxBlendOver(vec4 base, vec4 src, float amt, float mode) {\n"
            + "  vec3 mixed = blendPix(base.rgb, src.rgb, mode);\n"
            + "  return vec4(clamp(mix(base.rgb, mixed, amt), 0.0, 1.0),\n"
            + "              mix(base.a, src.a, amt));\n"
            + "}\n";


    /**
     * Shared prelude, authored once in GLSL and translated for AGSL like everything else.
     *
     * <p>{@code fxClamp} stops a blur sampling transparent black outside the content rect — the
     * classic RenderEffect edge artifact, and the reason every sample goes through it.</p>
     *
     * <p>{@code fxNoise} is here rather than in a body because more than one generator wants it
     * and because a hash written twice is a hash that drifts: two effects would then produce
     * different noise from the same seed. Value noise with a smoothstep fade — cheap, and
     * identical on both backends because both compile these same lines.</p>
     */
    private static final String PRELUDE_SRC =
            "vec2 fxClamp(vec2 p) { return clamp(p, vec2(0.0), vec2(1.0)); }\n"
            + "float fxHash(vec2 p) {\n"
            + "  return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453123);\n"
            + "}\n"
            + "float fxNoise(vec2 p) {\n"
            + "  vec2 i = floor(p);\n"
            + "  vec2 f = fract(p);\n"
            + "  vec2 u = f * f * (3.0 - 2.0 * f);\n"
            + "  float a = fxHash(i);\n"
            + "  float b = fxHash(i + vec2(1.0, 0.0));\n"
            + "  float c = fxHash(i + vec2(0.0, 1.0));\n"
            + "  float d = fxHash(i + vec2(1.0, 1.0));\n"
            + "  return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);\n"
            + "}\n";

    private static final String PRELUDE_GLSL = PRELUDE_SRC;

    private static final String PRELUDE_AGSL = toAgsl(PRELUDE_SRC);

    /**
     * The gradient ramp's shared evaluator — declared ONCE here, exactly like {@code fxNoise},
     * and called by every gradient card with THAT card's own slot-namespaced uniforms as
     * arguments. Deliberately NOT a uniform array: nothing else in this codebase's GL renderers
     * sets a uniform array (see {@code AdjustmentLayerGlEffect}'s {@code uMaskGeo}, which is a
     * single vec4 even though {@code MaskSdf} supports several shapes), and media3's
     * {@code GlProgram} uniform binding has no exercised path for one. A run of individually
     * named vec4/vec3 uniforms, unrolled by hand, is the same trick {@code uMaskGeo} already
     * relies on — one shape, not eight, because this renderer only ever wires up one.
     *
     * <p>Unused stop SLOTS carry a sentinel position {@code > 1.0} (see {@link
     * GradientRamp#toFloatArray()}) rather than a count uniform: {@code t} never exceeds 1.0, so
     * a sentinel segment can never be the one selected, and the stop count needs no uniform of
     * its own at all.</p>
     */
    private static final String PRELUDE_GRAD_SRC =
            "vec3 fxGradSeg3(vec4 a, vec4 b, float t, float solid) {\n"
            + "  float span = max(b.x - a.x, 0.0001);\n"
            + "  float f = clamp((t - a.x) / span, 0.0, 1.0);\n"
            + "  if (solid > 0.5) f = f < 0.5 ? 0.0 : 1.0;\n"
            + "  return mix(a.yzw, b.yzw, f);\n"
            + "}\n"
            + "vec3 fxGradColor(float t, float solid,\n"
            + "    vec4 s0, vec4 s1, vec4 s2, vec4 s3, vec4 s4, vec4 s5, vec4 s6, vec4 s7) {\n"
            + "  if (t <= s0.x) return s0.yzw;\n"
            + "  if (t <= s1.x) return fxGradSeg3(s0, s1, t, solid);\n"
            + "  if (t <= s2.x) return fxGradSeg3(s1, s2, t, solid);\n"
            + "  if (t <= s3.x) return fxGradSeg3(s2, s3, t, solid);\n"
            + "  if (t <= s4.x) return fxGradSeg3(s3, s4, t, solid);\n"
            + "  if (t <= s5.x) return fxGradSeg3(s4, s5, t, solid);\n"
            + "  if (t <= s6.x) return fxGradSeg3(s5, s6, t, solid);\n"
            + "  if (t <= s7.x) return fxGradSeg3(s6, s7, t, solid);\n"
            + "  return s7.yzw;\n"
            + "}\n"
            // Opacity stops carry a THIRD component, .z — the bias of the diamond between this
            // stop and the next, as a RATIO within the segment. f<=m maps to the lower half of
            // the blend, f>m to the upper half, so the 50% crossover sits at the biased point
            // instead of the geometric midpoint — the diamond the spec asks for.
            + "float fxGradSeg2(vec3 a, vec3 b, float t, float solid) {\n"
            + "  float span = max(b.x - a.x, 0.0001);\n"
            + "  float f = clamp((t - a.x) / span, 0.0, 1.0);\n"
            + "  if (solid > 0.5) {\n"
            + "    f = f < 0.5 ? 0.0 : 1.0;\n"
            + "  } else {\n"
            + "    float m = clamp(a.z, 0.02, 0.98);\n"
            + "    f = f <= m ? 0.5 * f / m : 1.0 - 0.5 * (1.0 - f) / (1.0 - m);\n"
            + "  }\n"
            + "  return mix(a.y, b.y, f);\n"
            + "}\n"
            + "float fxGradAlpha(float t, float solid,\n"
            + "    vec3 s0, vec3 s1, vec3 s2, vec3 s3, vec3 s4, vec3 s5, vec3 s6, vec3 s7) {\n"
            + "  if (t <= s0.x) return s0.y;\n"
            + "  if (t <= s1.x) return fxGradSeg2(s0, s1, t, solid);\n"
            + "  if (t <= s2.x) return fxGradSeg2(s1, s2, t, solid);\n"
            + "  if (t <= s3.x) return fxGradSeg2(s2, s3, t, solid);\n"
            + "  if (t <= s4.x) return fxGradSeg2(s3, s4, t, solid);\n"
            + "  if (t <= s5.x) return fxGradSeg2(s4, s5, t, solid);\n"
            + "  if (t <= s6.x) return fxGradSeg2(s5, s6, t, solid);\n"
            + "  if (t <= s7.x) return fxGradSeg2(s6, s7, t, solid);\n"
            + "  return s7.y;\n"
            + "}\n";

    private static final String PRELUDE_GRAD_GLSL = PRELUDE_GRAD_SRC;
    private static final String PRELUDE_GRAD_AGSL = toAgsl(PRELUDE_GRAD_SRC);

    private static void emitUniformDecls(@NonNull StringBuilder sb, @NonNull Pass pass,
                                         boolean agsl) {
        // Emitted from the descriptors, never authored — the generalisation of
        // GlTransitionShaderLoader.uniformsFor. Namespacing by SLOT is what makes fusion
        // collision-free when the same effect appears twice in one pass.
        List<FxInstance> all = new ArrayList<>(pass.remaps);
        all.addAll(pass.cards);
        boolean anyGradient = false;
        for (FxInstance card : all) {
            FxEffectDef def = card.def();
            if (def == null) continue;
            for (FxParam p : def.params) {
                if (p.kind == FxParam.Kind.GRADIENT) {
                    anyGradient = true;
                    emitGradientUniformDecls(sb, card, p, agsl);
                    continue;
                }
                sb.append("uniform ").append(agsl ? toAgsl(p.glslType()) : p.glslType())
                  .append(' ').append(uniformName(card, p)).append(";\n");
            }
            if (def.foldsColor()) {
                sb.append("uniform float ").append(foldOpacityName(card)).append(";\n")
                  .append("uniform float ").append(foldBlendName(card)).append(";\n");
            }
        }
    }

    /**
     * The gradient's uniforms, unrolled: one {@code vec3} of flags, {@link GradientRamp#CAP}
     * colour-stop {@code vec4}s, {@link GradientRamp#CAP} opacity-stop {@code vec3}s. Order here
     * is the SAME order {@link #gradientUniformValues} slices {@link GradientRamp#toFloatArray()}
     * into — the one thing that must never drift, or a value would land under the wrong name.
     */
    private static void emitGradientUniformDecls(@NonNull StringBuilder sb,
                                                  @NonNull FxInstance card, @NonNull FxParam p,
                                                  boolean agsl) {
        String base = uniformName(card, p);
        StringBuilder decl = new StringBuilder();
        decl.append("uniform vec3 ").append(base).append("_flags;\n");
        for (int i = 0; i < GradientRamp.CAP; i++) {
            decl.append("uniform vec4 ").append(base).append("_c").append(i).append(";\n");
        }
        for (int i = 0; i < GradientRamp.CAP; i++) {
            decl.append("uniform vec3 ").append(base).append("_o").append(i).append(";\n");
        }
        sb.append(agsl ? toAgsl(decl.toString()) : decl.toString());
    }

    /**
     * The values for {@link #emitGradientUniformDecls}' names, sliced out of a card's packed
     * {@code float[]} — {@link FxUniforms#forPass} calls this instead of packing the whole
     * array under one name, because there is no single uniform to pack it under.
     */
    @NonNull
    static List<FxUniforms.Value> gradientUniformValues(@NonNull FxInstance card,
                                                         @NonNull FxParam p) {
        float[] a = card.get(p);
        String base = uniformName(card, p);
        List<FxUniforms.Value> out = new ArrayList<>(1 + GradientRamp.CAP * 2);
        out.add(new FxUniforms.Value(base + "_flags", new float[]{a[0], a[1], a[2]}));
        int cBase = 3;
        for (int i = 0; i < GradientRamp.CAP; i++) {
            int o = cBase + i * 4;
            out.add(new FxUniforms.Value(base + "_c" + i,
                    new float[]{a[o], a[o + 1], a[o + 2], a[o + 3]}));
        }
        int oBase = cBase + GradientRamp.CAP * 4;
        for (int i = 0; i < GradientRamp.CAP; i++) {
            int o = oBase + i * 3;
            out.add(new FxUniforms.Value(base + "_o" + i,
                    new float[]{a[o], a[o + 1], a[o + 2]}));
        }
        return out;
    }

    /** Whether any card in {@code pass} declares a {@link FxParam.Kind#GRADIENT} param — gates
     *  whether the shared gradient evaluator needs splicing in at all. */
    private static boolean passHasGradient(@NonNull Pass pass) {
        List<FxInstance> all = new ArrayList<>(pass.remaps);
        all.addAll(pass.cards);
        for (FxInstance card : all) {
            FxEffectDef def = card.def();
            if (def == null) continue;
            for (FxParam p : def.params) {
                if (p.kind == FxParam.Kind.GRADIENT) return true;
            }
        }
        return false;
    }

    /** {@code u<slot>_<param>} — the one place this name is formed. */
    @NonNull
    public static String uniformName(@NonNull FxInstance card, @NonNull FxParam p) {
        return "u" + card.slot + "_" + p.name;
    }

    @NonNull
    public static String foldOpacityName(@NonNull FxInstance card) {
        return "u" + card.slot + "_" + FxParam.RESERVED_OPACITY;
    }

    @NonNull
    public static String foldBlendName(@NonNull FxInstance card) {
        return "u" + card.slot + "_" + FxParam.RESERVED_BLEND;
    }

    private static void emitRemapFn(@NonNull StringBuilder sb, @NonNull Pass pass, boolean agsl,
                                    int kernelHalf) {
        String v2 = agsl ? "float2" : "vec2";
        sb.append(v2).append(" fxRemap(").append(v2).append(" uv) {\n");
        for (FxInstance card : pass.remaps) {
            FxEffectDef def = card.def();
            if (def == null) continue;
            sb.append("  uv = fxuv").append(card.slot).append("(uv);\n");
        }
        sb.append("  return uv;\n}\n");
    }

    /**
     * The REMAP card bodies, {@code fxuvN}.
     *
     * <p>Split from the effect cards, and emitted before {@link #emitRemapFn}, because the three
     * kinds form a strict chain: {@code fxuvN} → {@code fxRemap} → {@code fxN}. {@code fxRemap}
     * calls every {@code fxuvN}; an effect card that SAMPLES expands {@code FX_SAMPLE_SRC} into a
     * call to {@code fxRemap}. GLSL ES 1.00 requires declaration before use, so any other order
     * fails to compile — and it did: emitting {@code fxRemap} first made every Pixelate or Offset
     * stack fail with "'fxuv2': no matching overloaded function found", on EXPORT as well as
     * preview, where it degraded silently to passthrough. Found by the live preview, which
     * logs the driver's message and the source.</p>
     *
     * <p>A remap body cannot itself sample — {@code FxRegistry} rejects a non-SAMPLER card whose
     * body reads neighbours — so this end of the chain has no back edge and no prototype is
     * needed.</p>
     */
    private static void emitRemapCardFns(@NonNull StringBuilder sb, @NonNull Pass pass,
                                         boolean agsl, int kernelHalf) {
        for (FxInstance card : pass.remaps) {
            FxEffectDef def = card.def();
            if (def == null) continue;
            String v2 = agsl ? "float2" : "vec2";
            sb.append(v2).append(" fxuv").append(card.slot).append('(').append(v2)
              .append(" uv) {\n").append(indent(expand(def.glslBody, card, agsl, kernelHalf)))
              .append("}\n");
        }
    }

    /** The EFFECT card bodies, {@code fxN}. These may call {@code fxRemap}. */
    private static void emitCardFns(@NonNull StringBuilder sb, @NonNull Pass pass, boolean agsl,
                                    int kernelHalf) {
        for (FxInstance card : pass.cards) {
            FxEffectDef def = card.def();
            if (def == null) continue;
            String v4 = agsl ? "half4" : "vec4";
            String v2 = agsl ? "float2" : "vec2";
            sb.append(v4).append(" fx").append(card.slot).append('(').append(v2)
              .append(" uv, ").append(v4).append(" src) {\n")
              .append(indent(expand(def.glslBody, card, agsl, kernelHalf)))
              .append("}\n");
        }
    }

    /**
     * The per-card fold: {@code c = fxBlendOver(c, fxN(uv, c), opacity, blend)}.
     *
     * <p>This is where {@code BlendModes.GLSL_BLEND_FN} earns being extracted — the FX fold and
     * {@code BlendModeGlEffect} now run the SAME blend arithmetic rather than two copies of it.</p>
     */
    private static void emitFold(@NonNull StringBuilder sb, @NonNull Pass pass) {
        for (FxInstance card : pass.cards) {
            sb.append("  c = fxBlendOver(c, fx").append(card.slot).append("(uv, c), ")
              .append(foldOpacityName(card)).append(", ")
              .append(foldBlendName(card)).append(");\n");
        }
    }

    /** Expand the macros. Done in Java for BOTH backends, because AGSL has no preprocessor. */
    @NonNull
    private static String expand(@NonNull String body, @NonNull FxInstance card, boolean agsl,
                                 int kernelHalf) {
        String out = body;
        // FX_P(name) → u<slot>_name. Longest-first is irrelevant here, but the order below is
        // not: FX_SAMPLE must expand before FX_UV, or the uv inside it would be rewritten twice.
        FxEffectDef def = card.def();
        if (def != null) {
            for (FxParam p : def.params) {
                if (p.kind == FxParam.Kind.GRADIENT) {
                    // No FX_P(name) form — a ramp is not a single uniform. FX_GRAD_COLOR(t) and
                    // FX_GRAD_ALPHA(t) call the shared evaluator with THIS card's slot-namespaced
                    // stop uniforms; FX_GRAD_MIRROR/FX_GRAD_FLIP read the flags vec3 directly.
                    String base = uniformName(card, p);
                    StringBuilder colorArgs = new StringBuilder();
                    StringBuilder alphaArgs = new StringBuilder();
                    for (int i = 0; i < GradientRamp.CAP; i++) {
                        colorArgs.append(", ").append(base).append("_c").append(i);
                        alphaArgs.append(", ").append(base).append("_o").append(i);
                    }
                    out = replaceCall(out, "FX_GRAD_COLOR",
                            "fxGradColor(%s, " + base + "_flags.z" + colorArgs + ")");
                    out = replaceCall(out, "FX_GRAD_ALPHA",
                            "fxGradAlpha(%s, " + base + "_flags.z" + alphaArgs + ")");
                    out = out.replace("FX_GRAD_MIRROR", base + "_flags.x");
                    out = out.replace("FX_GRAD_FLIP", base + "_flags.y");
                    continue;
                }
                out = out.replace("FX_P(" + p.name + ")", uniformName(card, p));
            }
        }
        // Before FX_SAMPLE, because the name FX_SAMPLE is not a prefix of FX_NOISE but the
        // ordering rule is worth keeping visible: these are textual, and a macro that contained
        // another would expand wrong in the other order.
        out = replaceCall(out, "FX_NOISE", "fxNoise(%s)");
        out = replaceCall(out, "FX_SAMPLE",
                agsl ? "inputShader.eval(" + U_ORIGIN + " + fxClamp(fxRemap(%s)) * " + U_SIZE + ")"
                     : "texture2D(uTexSampler, fxClamp(fxRemap(%s)))");
        out = out.replace("FX_UV", agsl ? "uv" : "uv");
        out = out.replace("FX_TEXEL", U_TEXEL);
        out = out.replace("FX_ASPECT", U_ASPECT);
        out = out.replace("FX_TIME", U_TIME);
        out = out.replace("FX_DIR", U_DIR);
        // A LITERAL by the time a driver sees it — AGSL requires compile-time-bounded loops.
        out = out.replace("FX_KERNEL_HALF", String.valueOf(kernelHalf));
        return agsl ? toAgsl(out) : out;
    }

    /** {@code NAME(arg)} → {@code template} with {@code %s} = arg. Handles nested parens. */
    @NonNull
    private static String replaceCall(@NonNull String src, @NonNull String name,
                                      @NonNull String template) {
        StringBuilder out = new StringBuilder(src.length() + 64);
        int at = 0;
        while (true) {
            int i = src.indexOf(name + "(", at);
            if (i < 0) { out.append(src, at, src.length()); break; }
            out.append(src, at, i);
            int depth = 0, j = i + name.length();
            int argStart = j + 1;
            for (; j < src.length(); j++) {
                char ch = src.charAt(j);
                if (ch == '(') depth++;
                else if (ch == ')') { depth--; if (depth == 0) break; }
            }
            if (j >= src.length()) { out.append(src, i, src.length()); break; }
            String arg = src.substring(argStart, j);
            out.append(template.replace("%s", arg));
            at = j + 1;
        }
        return out.toString();
    }

    /**
     * GLSL → AGSL, on WORD BOUNDARIES. A plain replace would corrupt any identifier containing
     * a type name, and this table is the single most drift-prone thing in the file — which is
     * why the harness pins both emits with golden strings.
     */
    @NonNull
    static String toAgsl(@NonNull String glsl) {
        String s = glsl;
        s = s.replaceAll("\\bvec2\\b", "float2");
        s = s.replaceAll("\\bvec3\\b", "float3");
        s = s.replaceAll("\\bvec4\\b", "half4");
        s = s.replaceAll("\\bmat2\\b", "float2x2");
        s = s.replaceAll("\\bmat3\\b", "float3x3");
        s = s.replaceAll("\\bmat4\\b", "float4x4");
        return s;
    }

    /** Resolve the source-marker the entry points use for "the image coming in". */
    @NonNull
    private static String finish(@NonNull String src, @NonNull Pass pass, boolean agsl,
                                 int kernelHalf) {
        String sample = agsl
                ? "inputShader.eval(" + U_ORIGIN + " + fxClamp(fxRemap(uv)) * " + U_SIZE + ")"
                : "texture2D(uTexSampler, fxClamp(fxRemap(uv)))";
        return src.replace("FX_SAMPLE_SRC", sample);
    }

    @NonNull
    private static String indent(@NonNull String body) {
        StringBuilder sb = new StringBuilder(body.length() + 32);
        for (String line : body.split("\n", -1)) {
            if (line.isEmpty()) continue;
            sb.append("  ").append(line).append('\n');
        }
        return sb.toString();
    }
}
