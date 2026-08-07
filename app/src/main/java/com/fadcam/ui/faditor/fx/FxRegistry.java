package com.fadcam.ui.faditor.fx;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * THE static catalog of effects — modelled on {@code GLTransitionCatalog}, with bodies as Java
 * constants rather than assets (see {@link FxEffectDef}'s class note for why that mattered).
 *
 * <p><b>The self-check runs at class load and throws.</b> Every rule in {@link #validate} describes
 * a mistake that produces a shader which either fails to compile on a user's driver or, worse,
 * compiles and does nothing — a declared parameter no body reads is a slider that moves and changes
 * nothing, and there is no symptom to notice. This is the same job
 * {@code GlTransitionShaderLoader.sanitize()} does for external bodies, moved to load time because
 * this catalog is a compile-time constant: if it is wrong, it is wrong on every device, every time,
 * and the harness will say so in seconds.</p>
 *
 * <p><b>Ids are wire values.</b> {@link FxInstance} persists {@code effectId} into the project
 * file. Renaming an id orphans every card that used it. Add, never rename.</p>
 *
 * <p>Android-free so the JVM harness can reach it.</p>
 */
public final class FxRegistry {

    private FxRegistry() {}

    // ── Bodies ──────────────────────────────────────────────────────────────────────────────
    //
    // Authored ONCE, in GLSL ES 1.00, as a statement block. Export emits these bytes verbatim;
    // the preview goes through FxCompiler's translation table. The asymmetry is deliberate — a
    // translation mistake produces a wrong PREVIEW, never a wrong file.
    //
    // The available macros are documented on FxCompiler. Nothing here may declare a uniform, a
    // varying, a precision, a preprocessor line, or an entry point: validate() rejects all of it.

    private static final String BODY_GAUSSIAN_BLUR =
            "float sigma = max(FX_P(radius), 0.0001);\n"
            + "vec2 off = FX_TEXEL * FX_DIR;\n"
            + "vec4 sum = vec4(0.0);\n"
            + "float wsum = 0.0;\n"
            // FX_KERNEL_HALF is a LITERAL by the time a driver sees it. AGSL requires
            // compile-time-bounded loops, and that requirement is the reason we ship our own
            // Gaussian instead of Skia's — see the spec's §5.5.
            + "for (int i = -FX_KERNEL_HALF; i <= FX_KERNEL_HALF; i++) {\n"
            + "  float fi = float(i);\n"
            + "  float w = exp(-0.5 * fi * fi / (sigma * sigma));\n"
            + "  sum += FX_SAMPLE(uv + off * fi) * w;\n"
            + "  wsum += w;\n"
            + "}\n"
            + "return sum / max(wsum, 0.0001);\n";

    private static final String BODY_DIRECTIONAL_BLUR =
            "float sigma = max(FX_P(radius), 0.0001);\n"
            + "float ang = radians(FX_P(angle));\n"
            + "vec2 off = FX_TEXEL * vec2(cos(ang), sin(ang));\n"
            + "vec4 sum = vec4(0.0);\n"
            + "float wsum = 0.0;\n"
            + "for (int i = -FX_KERNEL_HALF; i <= FX_KERNEL_HALF; i++) {\n"
            + "  float fi = float(i);\n"
            + "  float w = exp(-0.5 * fi * fi / (sigma * sigma));\n"
            + "  sum += FX_SAMPLE(uv + off * fi) * w;\n"
            + "  wsum += w;\n"
            + "}\n"
            + "return sum / max(wsum, 0.0001);\n";

    private static final String BODY_PIXELATE =
            // A mosaic is a COORDINATE change, not a colour one, so it costs no pass at all — it
            // folds into whatever samples next. That is the whole point of having UV_REMAP.
            "vec2 cell = FX_TEXEL * max(FX_P(size), 1.0);\n"
            + "return (floor(uv / cell) + vec2(0.5)) * cell;\n";

    private static final String BODY_INVERT =
            "return vec4(mix(src.rgb, vec3(1.0) - src.rgb, FX_P(amount)), src.a);\n";

    private static final String BODY_LEVELS =
            "float lo = FX_P(inBlack);\n"
            + "float hi = FX_P(inWhite);\n"
            + "vec3 c = clamp((src.rgb - vec3(lo)) / max(hi - lo, 0.0001), 0.0, 1.0);\n"
            + "c = pow(c, vec3(1.0 / max(FX_P(gamma), 0.0001)));\n"
            + "c = mix(vec3(FX_P(outBlack)), vec3(FX_P(outWhite)), c);\n"
            + "return vec4(c, src.a);\n";

    private static final String BODY_GRADIENT_MAP =
            "float l = dot(src.rgb, vec3(0.299, 0.587, 0.114));\n"
            + "vec3 g = mix(FX_P(lowColor), FX_P(highColor), l);\n"
            + "return vec4(mix(src.rgb, g, FX_P(amount)), src.a);\n";

    private static final String BODY_THRESHOLD =
            "float l = dot(src.rgb, vec3(0.299, 0.587, 0.114));\n"
            // The epsilon guards smoothstep's edge pair against edge0 == edge1, which GLSL leaves
            // undefined — the identical rail ChromaKey.EDGE_EPSILON exists for.
            + "float s = FX_P(softness) * 0.5 + 0.0001;\n"
            + "float t = smoothstep(FX_P(level) - s, FX_P(level) + s, l);\n"
            + "return vec4(mix(src.rgb, vec3(t), FX_P(amount)), src.a);\n";

    private static final String BODY_POSTERIZE =
            // floor(c*(n-1)+0.5)/(n-1), not floor(c*n)/n: the latter can never emit pure white,
            // so a posterised white sky comes back grey and it reads as a colour bug.
            "float n = max(floor(FX_P(steps)), 2.0) - 1.0;\n"
            + "vec3 c = floor(src.rgb * n + vec3(0.5)) / n;\n"
            + "return vec4(mix(src.rgb, c, FX_P(amount)), src.a);\n";

    private static final String BODY_DUOTONE =
            "float l = dot(src.rgb, vec3(0.299, 0.587, 0.114));\n"
            + "l = clamp((l - FX_P(pivot)) * FX_P(contrast) + 0.5, 0.0, 1.0);\n"
            + "vec3 d = mix(FX_P(shadowColor), FX_P(highlightColor), l);\n"
            + "return vec4(mix(src.rgb, d, FX_P(amount)), src.a);\n";

    private static final String BODY_RGB_SHIFT =
            "float ang = radians(FX_P(angle));\n"
            + "vec2 o = FX_TEXEL * FX_P(amount) * vec2(cos(ang), sin(ang));\n"
            + "vec4 r = FX_SAMPLE(uv + o);\n"
            + "vec4 g = FX_SAMPLE(uv);\n"
            + "vec4 b = FX_SAMPLE(uv - o);\n"
            + "return vec4(r.r, g.g, b.b, g.a);\n";

    private static final String BODY_NOISE =
            // ONE body covers value noise, clouds and difference clouds — three catalog entries'
            // worth of picker for one shader, which is what the spec's §8 row asks for.
            "vec2 p = FX_UV * FX_P(scale) * vec2(FX_ASPECT, 1.0) + vec2(FX_P(seed) * 7.13);\n"
            + "float amp = 0.5;\n"
            + "float f = 0.0;\n"
            + "for (int i = 0; i < 3; i++) {\n"
            + "  f += amp * FX_NOISE(p);\n"
            + "  p = p * 2.0;\n"
            + "  amp = amp * 0.5;\n"
            + "}\n"
            + "f = f / 0.875;\n"
            + "float m = FX_P(mode);\n"
            + "float v = m < 0.5 ? FX_NOISE(p * 0.25) : (m < 1.5 ? f : abs(f * 2.0 - 1.0));\n"
            + "return vec4(mix(src.rgb, vec3(v), FX_P(amount)), src.a);\n";

    private static final String BODY_OFFSET =
            // fract() is the wrap. It makes a tiling generator underneath actually usable, which
            // is the only reason a zero-cost remap earns a catalog slot at all.
            "return fract(uv + vec2(FX_P(x), FX_P(y)) + vec2(1.0));\n";

    // ── The catalog ─────────────────────────────────────────────────────────────────────────

    /**
     * Rules a body must not break. DECLARED BEFORE the static initializer that reads it, and it
     * must stay there: static fields initialise in SOURCE ORDER, so with this further down the
     * file {@link #validate} saw a null table and the whole class failed to load — on every
     * device, the first time anything touched the registry.
     */
    private static final String[][] FORBIDDEN = {
            {"texture2D", "sampling is FX_SAMPLE's job — a raw texture2D cannot translate to AGSL"},
            {"uniform", "uniforms are emitted from FxParam descriptors, never authored"},
            {"varying", "the compiler owns the varyings; AGSL has none at all"},
            {"precision", "the compiler emits the precision qualifier once, per backend"},
            {"#", "AGSL has no preprocessor — every macro is expanded in Java"},
            {"gl_FragColor", "the entry point is the compiler's; AGSL returns a value instead"},
            {"main(", "a body is a STATEMENT BLOCK; the compiler emits the signature"},
    };

    private static final List<FxEffectDef> ALL;
    private static final Map<String, FxEffectDef> BY_ID;

    static {
        List<FxEffectDef> defs = new ArrayList<>();

        defs.add(new FxEffectDef("gaussian_blur", "Gaussian Blur",
                FxEffectDef.Family.BLUR, FxEffectDef.Capability.SAMPLER, 2, 3.0f,
                BODY_GAUSSIAN_BLUR,
                FxParam.flt("radius", "Radius", 0f, 50f, 8f)));

        defs.add(new FxEffectDef("directional_blur", "Directional Blur",
                FxEffectDef.Family.BLUR, FxEffectDef.Capability.SAMPLER, 1, 1.8f,
                BODY_DIRECTIONAL_BLUR,
                FxParam.flt("radius", "Radius", 0f, 50f, 8f),
                FxParam.flt("angle", "Angle", -180f, 180f, 0f)));

        defs.add(new FxEffectDef("pixelate", "Pixelate",
                FxEffectDef.Family.BLUR, FxEffectDef.Capability.UV_REMAP, 1, 0f,
                BODY_PIXELATE,
                FxParam.flt("size", "Cell size", 1f, 256f, 16f)));

        defs.add(new FxEffectDef("invert", "Invert",
                FxEffectDef.Family.COLOR, FxEffectDef.Capability.POINTWISE, 1, 0.2f,
                BODY_INVERT,
                FxParam.flt("amount", "Amount", 0f, 1f, 1f)));

        defs.add(new FxEffectDef("levels", "Levels",
                FxEffectDef.Family.COLOR, FxEffectDef.Capability.POINTWISE, 1, 0.6f,
                BODY_LEVELS,
                FxParam.flt("inBlack", "Input black", 0f, 1f, 0f),
                FxParam.flt("inWhite", "Input white", 0f, 1f, 1f),
                FxParam.flt("gamma", "Gamma", 0.1f, 4f, 1f),
                FxParam.flt("outBlack", "Output black", 0f, 1f, 0f),
                FxParam.flt("outWhite", "Output white", 0f, 1f, 1f)));

        defs.add(new FxEffectDef("gradient_map", "Gradient Map",
                FxEffectDef.Family.COLOR, FxEffectDef.Capability.POINTWISE, 1, 0.4f,
                BODY_GRADIENT_MAP,
                FxParam.color("lowColor", "Shadows", 0x101820),
                FxParam.color("highColor", "Highlights", 0xF2E9E4),
                FxParam.flt("amount", "Amount", 0f, 1f, 1f)));

        defs.add(new FxEffectDef("threshold", "Threshold",
                FxEffectDef.Family.COLOR, FxEffectDef.Capability.POINTWISE, 1, 0.3f,
                BODY_THRESHOLD,
                FxParam.flt("level", "Level", 0f, 1f, 0.5f),
                FxParam.flt("softness", "Softness", 0f, 1f, 0.04f),
                FxParam.flt("amount", "Amount", 0f, 1f, 1f)));

        defs.add(new FxEffectDef("posterize", "Posterize",
                FxEffectDef.Family.COLOR, FxEffectDef.Capability.POINTWISE, 1, 0.3f,
                BODY_POSTERIZE,
                FxParam.flt("steps", "Steps", 2f, 32f, 6f),
                FxParam.flt("amount", "Amount", 0f, 1f, 1f)));

        defs.add(new FxEffectDef("duotone", "Duotone",
                FxEffectDef.Family.COLOR, FxEffectDef.Capability.POINTWISE, 1, 0.5f,
                BODY_DUOTONE,
                FxParam.color("shadowColor", "Shadow tone", 0x1A1A66),
                FxParam.color("highlightColor", "Highlight tone", 0xFFD24D),
                FxParam.flt("pivot", "Pivot", 0f, 1f, 0.5f),
                FxParam.flt("contrast", "Contrast", 0.1f, 4f, 1f),
                FxParam.flt("amount", "Amount", 0f, 1f, 1f)));

        defs.add(new FxEffectDef("rgb_shift", "RGB Shift",
                FxEffectDef.Family.COLOR, FxEffectDef.Capability.SAMPLER, 1, 1.2f,
                BODY_RGB_SHIFT,
                FxParam.flt("amount", "Amount", 0f, 64f, 6f),
                FxParam.flt("angle", "Angle", -180f, 180f, 0f)));

        defs.add(new FxEffectDef("noise", "Noise / Clouds",
                FxEffectDef.Family.GENERATE, FxEffectDef.Capability.GENERATOR, 1, 2.0f,
                BODY_NOISE,
                FxParam.enumOf("mode", "Mode", 1, "Value", "Clouds", "Difference"),
                FxParam.flt("scale", "Scale", 1f, 200f, 8f),
                FxParam.flt("seed", "Seed", 0f, 100f, 0f),
                FxParam.flt("amount", "Amount", 0f, 1f, 1f)));

        defs.add(new FxEffectDef("offset", "Offset",
                FxEffectDef.Family.DISTORT, FxEffectDef.Capability.UV_REMAP, 1, 0f,
                BODY_OFFSET,
                FxParam.flt("x", "Offset X", -1f, 1f, 0f),
                FxParam.flt("y", "Offset Y", -1f, 1f, 0f)));

        ALL = Collections.unmodifiableList(defs);

        Map<String, FxEffectDef> byId = new LinkedHashMap<>();
        for (FxEffectDef d : defs) byId.put(d.id, d);
        BY_ID = Collections.unmodifiableMap(byId);

        // Load-time, not test-time: this catalog is a compile-time constant, so a failure here is
        // a failure on every device. Better a loud IllegalStateException the first harness run
        // catches than a shader that quietly does nothing on a phone.
        List<String> problems = validate();
        if (!problems.isEmpty()) {
            throw new IllegalStateException("FxRegistry is invalid:\n  " + join(problems, "\n  "));
        }
    }

    @NonNull
    public static List<FxEffectDef> all() { return ALL; }

    @Nullable
    public static FxEffectDef get(@NonNull String id) { return BY_ID.get(id); }

    /** Throws rather than returning null — an unknown id in a project file is a bug in the caller,
     *  which should have used {@link #get} and skipped the card. */
    @NonNull
    public static FxEffectDef require(@NonNull String id) {
        FxEffectDef d = BY_ID.get(id);
        if (d == null) throw new IllegalArgumentException("no such effect: " + id);
        return d;
    }

    @NonNull
    public static List<FxEffectDef> byFamily(@NonNull FxEffectDef.Family family) {
        List<FxEffectDef> out = new ArrayList<>();
        for (FxEffectDef d : ALL) {
            if (d.family == family) out.add(d);
        }
        return out;
    }

    // ── The self-check ──────────────────────────────────────────────────────────────────────

    /** Tokens an authored body must never contain, each mapped to whose job it actually is. */

    /**
     * Every rule the catalog must satisfy, as a list of human-readable problems ({@code empty} ==
     * valid). Exposed rather than private so the harness can assert on the WORDING of a failure,
     * not just on the fact of one.
     */
    @NonNull
    public static List<String> validate() {
        List<String> problems = new ArrayList<>();
        Map<String, FxEffectDef> seen = new LinkedHashMap<>();

        for (FxEffectDef d : ALL) {
            if (seen.put(d.id, d) != null) problems.add(d.id + ": duplicate id");

            for (String[] rule : FORBIDDEN) {
                if (d.glslBody.contains(rule[0])) {
                    problems.add(d.id + ": body contains '" + rule[0] + "' — " + rule[1]);
                }
            }

            boolean remap = d.capability == FxEffectDef.Capability.UV_REMAP;
            boolean samples = d.glslBody.contains("FX_SAMPLE");
            if (samples && d.capability != FxEffectDef.Capability.SAMPLER) {
                // A POINTWISE body that samples would read the PASS INPUT, not the colour the
                // cards below it produced — it would silently ignore everything under it.
                problems.add(d.id + ": uses FX_SAMPLE but is not declared SAMPLER");
            }
            if (!samples && d.capability == FxEffectDef.Capability.SAMPLER) {
                problems.add(d.id + ": declared SAMPLER but never calls FX_SAMPLE — it would cost "
                        + "a whole pass for nothing");
            }
            if (remap && !d.glslBody.contains("return")) {
                problems.add(d.id + ": UV_REMAP body never returns a coordinate");
            }
            if (d.passes > 1 && !d.glslBody.contains("FX_DIR")) {
                problems.add(d.id + ": declares " + d.passes + " passes but ignores FX_DIR, so "
                        + "every pass would render the same thing");
            }

            // FX_P references must resolve, and every declared param must be referenced. The
            // second half is the one that catches silently-broken UI.
            List<String> referenced = referencedParams(d.glslBody);
            for (String r : referenced) {
                if (d.param(r) == null) problems.add(d.id + ": FX_P(" + r + ") is not a declared param");
            }
            for (FxParam p : d.params) {
                if (!referenced.contains(p.name)) {
                    problems.add(d.id + ": param '" + p.name + "' is declared but no body reads it "
                            + "— that is a slider that changes nothing");
                }
            }
            if (d.param(FxParam.RESERVED_OPACITY) != null
                    || d.param(FxParam.RESERVED_BLEND) != null) {
                // Every card already has these, emitted by the compiler for the fold. A param of
                // the same name would collide on both the uniform and the keyframe track.
                problems.add(d.id + ": '" + FxParam.RESERVED_OPACITY + "'/'"
                        + FxParam.RESERVED_BLEND + "' are reserved for the per-card fold");
            }
        }
        return problems;
    }

    /** Every {@code name} appearing in an {@code FX_P(name)} reference, in first-seen order. */
    @NonNull
    static List<String> referencedParams(@NonNull String body) {
        List<String> out = new ArrayList<>();
        int at = 0;
        while (true) {
            int i = body.indexOf("FX_P(", at);
            if (i < 0) break;
            int close = body.indexOf(')', i);
            if (close < 0) break;
            String name = body.substring(i + 5, close).trim();
            if (!name.isEmpty() && !out.contains(name)) out.add(name);
            at = close + 1;
        }
        return out;
    }

    @NonNull
    private static String join(@NonNull List<String> parts, @NonNull String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(parts.get(i));
        }
        return sb.toString();
    }
}
