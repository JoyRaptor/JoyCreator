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
        sb.append(BlendModes.glslBlendFnWithModeParam()).append(FOLD_FN);
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
        sb.append(toAgsl(BlendModes.glslBlendFnWithModeParam())).append(toAgsl(FOLD_FN));
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

    private static void emitUniformDecls(@NonNull StringBuilder sb, @NonNull Pass pass,
                                         boolean agsl) {
        // Emitted from the descriptors, never authored — the generalisation of
        // GlTransitionShaderLoader.uniformsFor. Namespacing by SLOT is what makes fusion
        // collision-free when the same effect appears twice in one pass.
        List<FxInstance> all = new ArrayList<>(pass.remaps);
        all.addAll(pass.cards);
        for (FxInstance card : all) {
            FxEffectDef def = card.def();
            if (def == null) continue;
            for (FxParam p : def.params) {
                sb.append("uniform ").append(agsl ? toAgsl(p.glslType()) : p.glslType())
                  .append(' ').append(uniformName(card, p)).append(";\n");
            }
            if (def.foldsColor()) {
                sb.append("uniform float ").append(foldOpacityName(card)).append(";\n")
                  .append("uniform float ").append(foldBlendName(card)).append(";\n");
            }
        }
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

    private static void emitCardFns(@NonNull StringBuilder sb, @NonNull Pass pass, boolean agsl,
                                    int kernelHalf) {
        for (FxInstance card : pass.remaps) {
            FxEffectDef def = card.def();
            if (def == null) continue;
            String v2 = agsl ? "float2" : "vec2";
            sb.append(v2).append(" fxuv").append(card.slot).append('(').append(v2)
              .append(" uv) {\n").append(indent(expand(def.glslBody, card, agsl, kernelHalf)))
              .append("}\n");
        }
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
