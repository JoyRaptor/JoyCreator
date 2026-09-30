package com.fadcam.ui.faditor.model;

import androidx.annotation.NonNull;

/**
 * THE single authority for layer blend modes — the GLSL that does it, the wire-value → shader-code
 * mapping that selects it, and a Java reference implementation of the same arithmetic.
 *
 * <p><b>Why this class exists.</b> Exactly the {@link ChromaKey} reasoning, applied to the other
 * quantity two renderers were about to derive twice. {@code BlendModeGlEffect} owned
 * {@code blendPix} privately; the FX compiler needs the same equations to fold one effect card over
 * the accumulated colour, and a second hand-written copy of OVERLAY's per-channel branch is how the
 * export and the preview end up disagreeing about what "Overlay" means. So the shader SOURCE lives
 * here as one string and every consumer concatenates that string.</p>
 *
 * <p><b>The extraction was byte-for-byte.</b> {@link #GLSL_BLEND_FN} is the literal that used to sit
 * inside {@code BlendModeGlEffect.Program.FRAGMENT_SHADER}, moved without a character changed — the
 * five original bands are still the original characters. Modes added since (DIFFERENCE and
 * COLOR, 2026-09-04; the remaining Photoshop/W3C set, 2026-09-04) are APPENDED as new bands, so no
 * project that already chose one of the five exports one pixel differently. Editing an EXISTING
 * band changes every export that ever used it.</p>
 *
 * <p><b>The Java mirror is not a duplicate — it is the instrument.</b> GLSL cannot run in the JVM
 * harness, so {@link #blend} mirrors {@link #GLSL_BLEND_FN} line for line to let the harness pin the
 * branch boundaries (b == 0.5 exactly, the ADD clamp, the OVERLAY per-channel select) off-device.
 * Nothing renders through it, so it can never quietly become the thing that ships.</p>
 *
 * <p><b>On DISSOLVE.</b> Photoshop's set includes it; this one deliberately does not. Dissolve is a
 * per-pixel random threshold against alpha, so on a moving picture every frame re-rolls the dice
 * and the layer sizzles. In a still image it reads as grain; in video it reads as a broken
 * encoder. If it is ever wanted, it has to be a per-frame-STABLE hash (seeded by pixel position
 * only, not by time), and that is a different feature, not this one.</p>
 *
 * <p>Android-free (annotations only) so the JVM harness can reach it.</p>
 */
public final class BlendModes {

    private BlendModes() {}

    /** Wire values match {@code Clip#getOverlayBlendMode()} strings. */
    public static final String NORMAL = "NORMAL";
    public static final String MULTIPLY = "MULTIPLY";
    public static final String SCREEN = "SCREEN";
    public static final String OVERLAY = "OVERLAY";
    /** Photoshop calls this LINEAR DODGE (ADD). It is the same equation; there is no second mode. */
    public static final String ADD = "ADD";
    public static final String DIFFERENCE = "DIFFERENCE";
    public static final String COLOR = "COLOR";
    public static final String DARKEN = "DARKEN";
    public static final String LIGHTEN = "LIGHTEN";
    public static final String COLOR_DODGE = "COLOR_DODGE";
    public static final String COLOR_BURN = "COLOR_BURN";
    public static final String LINEAR_BURN = "LINEAR_BURN";
    public static final String HARD_LIGHT = "HARD_LIGHT";
    public static final String SOFT_LIGHT = "SOFT_LIGHT";
    public static final String VIVID_LIGHT = "VIVID_LIGHT";
    public static final String LINEAR_LIGHT = "LINEAR_LIGHT";
    public static final String PIN_LIGHT = "PIN_LIGHT";
    public static final String HARD_MIX = "HARD_MIX";
    public static final String EXCLUSION = "EXCLUSION";
    public static final String SUBTRACT = "SUBTRACT";
    public static final String DIVIDE = "DIVIDE";
    public static final String DARKER_COLOR = "DARKER_COLOR";
    public static final String LIGHTER_COLOR = "LIGHTER_COLOR";
    public static final String HUE = "HUE";
    public static final String SATURATION = "SATURATION";
    public static final String LUMINOSITY = "LUMINOSITY";

    /**
     * Every mode, in the order a picker should list them.
     *
     * <p><b>This array's INDEX IS THE SHADER CODE.</b> {@link #modeName} indexes it directly and
     * {@link #modeCode} must agree, so an entry may only ever be APPENDED — reordering it would
     * re-point every already-saved project at a different equation.</p>
     */
    public static final String[] ALL = {
            NORMAL, MULTIPLY, SCREEN, OVERLAY, ADD, DIFFERENCE, COLOR,
            DARKEN, LIGHTEN, COLOR_DODGE, COLOR_BURN, LINEAR_BURN,
            HARD_LIGHT, SOFT_LIGHT, VIVID_LIGHT, LINEAR_LIGHT, PIN_LIGHT, HARD_MIX,
            EXCLUSION, SUBTRACT, DIVIDE, DARKER_COLOR, LIGHTER_COLOR,
            HUE, SATURATION, LUMINOSITY};

    /**
     * The standard category names, in picker order. Wire-ish keys rather than display strings so
     * this class stays Android-free; the picker maps each to a string resource.
     */
    public static final String[] GROUP_KEYS =
            {"NORMAL", "DARKEN", "LIGHTEN", "CONTRAST", "COMPARATIVE", "COLOR"};

    /**
     * {@link #ALL} arranged into {@link #GROUP_KEYS}, which is how the picker lays it out —
     * twenty-six modes in one flat list is a scroll; in six named groups across three columns it
     * is one screenful.
     *
     * <p>It lives HERE, next to the list it partitions, so the harness can assert the partition is
     * exact (every mode in {@link #ALL} appears exactly once, nothing extra). A grouping kept in
     * the UI layer instead is a mode that ships in the shader and is unreachable in the picker,
     * which is indistinguishable from not having built it.</p>
     *
     * <p>ADD sits under LIGHTEN because that is what Photoshop calls it there: "Linear Dodge
     * (Add)".</p>
     */
    public static final String[][] GROUPED = {
            {NORMAL},
            {DARKEN, MULTIPLY, COLOR_BURN, LINEAR_BURN, DARKER_COLOR},
            {LIGHTEN, SCREEN, COLOR_DODGE, ADD, LIGHTER_COLOR},
            {OVERLAY, SOFT_LIGHT, HARD_LIGHT, VIVID_LIGHT, LINEAR_LIGHT, PIN_LIGHT, HARD_MIX},
            {DIFFERENCE, EXCLUSION, SUBTRACT, DIVIDE},
            {HUE, SATURATION, COLOR, LUMINOSITY},
    };

    /**
     * Wire value → the float the shader branches on. Unknown strings fall back to NORMAL rather
     * than throwing: an unreadable blend mode must cost a project its blend, never its export.
     */
    public static int modeCode(@NonNull String blendMode) {
        switch (blendMode) {
            case "MULTIPLY": return 1;
            case "SCREEN":   return 2;
            case "OVERLAY":  return 3;
            case "ADD":        return 4;
            case "DIFFERENCE": return 5;
            case "COLOR":      return 6;
            case "DARKEN":        return 7;
            case "LIGHTEN":       return 8;
            case "COLOR_DODGE":   return 9;
            case "COLOR_BURN":    return 10;
            case "LINEAR_BURN":   return 11;
            case "HARD_LIGHT":    return 12;
            case "SOFT_LIGHT":    return 13;
            case "VIVID_LIGHT":   return 14;
            case "LINEAR_LIGHT":  return 15;
            case "PIN_LIGHT":     return 16;
            case "HARD_MIX":      return 17;
            case "EXCLUSION":     return 18;
            case "SUBTRACT":      return 19;
            case "DIVIDE":        return 20;
            case "DARKER_COLOR":  return 21;
            case "LIGHTER_COLOR": return 22;
            case "HUE":           return 23;
            case "SATURATION":    return 24;
            case "LUMINOSITY":    return 25;
            default:          return 0; // NORMAL = plain SRC_OVER (mode 0 in the shader)
        }
    }

    /** The inverse of {@link #modeCode}, for round-tripping a picker index back to the wire. */
    @NonNull
    public static String modeName(int code) {
        if (code < 0 || code >= ALL.length) return NORMAL;
        return ALL[code];
    }

    /**
     * The blend equations, as a GLSL 1.00 (ES 2.0) function — ES2 because both the export effect
     * and the preview tier compile {@code #version 100}.
     *
     * <p>Reads the {@code uBlendMode} uniform directly. That is how it was authored inside
     * {@code BlendModeGlEffect}, and it is kept verbatim so the extraction provably changed nothing;
     * a consumer that needs the mode as an argument instead calls {@link #glslBlendFnWithModeParam}
     * rather than keeping a second copy of the equations.</p>
     *
     * <p>NORMAL returns {@code s} unchanged because the caller's mix-by-alpha already IS SRC_OVER.
     * The caller is responsible for clamping the result and for un-premultiplying its inputs.</p>
     *
     * <p><b>No helper functions, deliberately.</b> {@link #glslBlendFnWithModeParam()} derives the
     * FX-fold variant by rewriting this function's SIGNATURE LINE and keeping everything after it,
     * so anything declared ABOVE the signature is dropped from that variant and the fused FX pass
     * fails to link — and GLSL ES 1.00 requires a function to be declared before it is called, so
     * a helper cannot live below either. Every equation is therefore written inline, including the
     * three places SetSat/SetLum/ClipColor arithmetic repeats.</p>
     */
    public static final String GLSL_BLEND_FN =
            "vec3 blendPix(vec3 b, vec3 s) {\n"
            + "  if (uBlendMode < 0.5) return s;\n"
            + "  if (uBlendMode < 1.5) return b * s;\n"
            + "  if (uBlendMode < 2.5) return 1.0 - (1.0 - b) * (1.0 - s);\n"
            + "  if (uBlendMode < 3.5) {\n"
            + "    vec3 lo = 2.0 * b * s;\n"
            + "    vec3 hi = 1.0 - 2.0 * (1.0 - b) * (1.0 - s);\n"
            + "    return vec3(b.r < 0.5 ? lo.r : hi.r,\n"
            + "                b.g < 0.5 ? lo.g : hi.g,\n"
            + "                b.b < 0.5 ? lo.b : hi.b);\n"
            + "  }\n"
            // ADD used to be the FALL-THROUGH (`return min(b+s,1)` for anything >= 3.5). It is an
            // explicit band now, because a fall-through last branch means every mode added after it
            // silently renders as Add -- the quietest possible way for a new blend mode to look
            // "implemented" and be wrong.
            //
            // ADD *is* Photoshop's "Linear Dodge (Add)": b + s clamped at 1. There is deliberately
            // no separate LINEAR_DODGE mode -- it would be a second wire value for one equation,
            // and two names for one thing is how a project round-trips into the "other" one.
            + "  if (uBlendMode < 4.5) return min(b + s, vec3(1.0));\n"
            // DIFFERENCE, the W3C separable definition: |backdrop - source|, per channel.
            + "  if (uBlendMode < 5.5) return abs(b - s);\n"
            // COLOR, the W3C NON-separable definition: the hue and saturation of the SOURCE with
            // the luminosity of the BACKDROP -- SetLum(source, Lum(backdrop)).
            //
            // ClipColor is the second half: shifting luminosity can push a channel outside 0..1,
            // and clamping it there would change the HUE. Scaling the whole colour back toward its
            // own luminosity instead is what keeps the result the source's colour rather than a
            // clipped approximation of it.
            + "  if (uBlendMode < 6.5) {\n"
            + "  vec3 lw = vec3(0.3, 0.59, 0.11);\n"
            + "  vec3 c = s + (dot(b, lw) - dot(s, lw));\n"
            + "  float cl = dot(c, lw);\n"
            + "  float cn = min(min(c.r, c.g), c.b);\n"
            + "  float cx = max(max(c.r, c.g), c.b);\n"
            + "  if (cn < 0.0) c = cl + (c - cl) * cl / max(cl - cn, 0.00001);\n"
            + "  if (cx > 1.0) c = cl + (c - cl) * (1.0 - cl) / max(cx - cl, 0.00001);\n"
            + "  return clamp(c, 0.0, 1.0);\n"
            + "  }\n"
            // ── the rest of the W3C / Photoshop separable set (2026-09-04) ──────────────────
            + "  if (uBlendMode < 7.5) return min(b, s);\n"                       // DARKEN
            + "  if (uBlendMode < 8.5) return max(b, s);\n"                       // LIGHTEN
            // COLOR DODGE. The W3C's three cases (b==0 -> 0; s==1 -> 1; else min(1, b/(1-s)))
            // collapse into this ONE expression: b==0 makes the numerator 0 whatever the epsilon
            // does, and s==1 makes the quotient enormous so the min pins it at 1. Written as the
            // single form on purpose -- a per-channel three-way branch in GLSL would need six more
            // lines and produce the same numbers.
            + "  if (uBlendMode < 9.5) return min(b / max(1.0 - s, 0.00001), vec3(1.0));\n"
            // COLOR BURN, the same collapse of the W3C's three cases.
            + "  if (uBlendMode < 10.5) return 1.0 - min((1.0 - b) / max(s, 0.00001), vec3(1.0));\n"
            + "  if (uBlendMode < 11.5) return max(b + s - 1.0, vec3(0.0));\n"    // LINEAR BURN
            // HARD LIGHT = Overlay with the roles swapped: the SOURCE picks the branch.
            + "  if (uBlendMode < 12.5) {\n"
            + "    vec3 hlo = 2.0 * b * s;\n"
            + "    vec3 hhi = 1.0 - 2.0 * (1.0 - b) * (1.0 - s);\n"
            + "    return vec3(s.r < 0.5 ? hlo.r : hhi.r,\n"
            + "                s.g < 0.5 ? hlo.g : hhi.g,\n"
            + "                s.b < 0.5 ? hlo.b : hhi.b);\n"
            + "  }\n"
            // SOFT LIGHT, the W3C definition including its D(b) piecewise term. step(s, 0.5) is
            // 1.0 where 0.5 >= s, i.e. exactly the "s <= 0.5" half.
            + "  if (uBlendMode < 13.5) {\n"
            + "    vec3 sb = sqrt(max(b, vec3(0.0)));\n"
            + "    vec3 sp = ((16.0 * b - 12.0) * b + 4.0) * b;\n"
            + "    vec3 dd = mix(sb, sp, step(b, vec3(0.25)));\n"
            + "    vec3 slo = b - (1.0 - 2.0 * s) * b * (1.0 - b);\n"
            + "    vec3 shi = b + (2.0 * s - 1.0) * (dd - b);\n"
            + "    return mix(shi, slo, step(s, vec3(0.5)));\n"
            + "  }\n"
            // VIVID LIGHT: colour-burn on the dark half, colour-dodge on the light half, each with
            // the source remapped to 0..1. Continuous at s == 0.5, where both sides give b.
            + "  if (uBlendMode < 14.5) {\n"
            + "    vec3 vb = 1.0 - min((1.0 - b) / max(2.0 * s, 0.00001), vec3(1.0));\n"
            + "    vec3 vd = min(b / max(2.0 - 2.0 * s, 0.00001), vec3(1.0));\n"
            + "    return mix(vd, vb, step(s, vec3(0.5)));\n"
            + "  }\n"
            + "  if (uBlendMode < 15.5) return clamp(b + 2.0 * s - 1.0, 0.0, 1.0);\n" // LINEAR LIGHT
            + "  if (uBlendMode < 16.5) {\n"                                          // PIN LIGHT
            + "    vec3 pl = min(b, 2.0 * s);\n"
            + "    vec3 ph = max(b, 2.0 * s - 1.0);\n"
            + "    return mix(ph, pl, step(s, vec3(0.5)));\n"
            + "  }\n"
            // HARD MIX is Vivid Light thresholded at 0.5, and that threshold reduces algebraically
            // to b + s >= 1 on BOTH halves of vivid's branch -- so this is the exact mode, not an
            // approximation of it.
            + "  if (uBlendMode < 17.5) return step(vec3(1.0), b + s);\n"
            + "  if (uBlendMode < 18.5) return b + s - 2.0 * b * s;\n"            // EXCLUSION
            + "  if (uBlendMode < 19.5) return max(b - s, vec3(0.0));\n"          // SUBTRACT
            + "  if (uBlendMode < 20.5) return min(b / max(s, 0.00001), vec3(1.0));\n" // DIVIDE
            // DARKER / LIGHTER COLOR compare the WHOLE pixel's luminosity and keep one of the two
            // colours intact -- they are not per-channel min/max (that is Darken/Lighten).
            + "  if (uBlendMode < 21.5) {\n"
            + "    vec3 dw = vec3(0.3, 0.59, 0.11);\n"
            + "    return dot(s, dw) < dot(b, dw) ? s : b;\n"
            + "  }\n"
            + "  if (uBlendMode < 22.5) {\n"
            + "    vec3 gw = vec3(0.3, 0.59, 0.11);\n"
            + "    return dot(s, gw) > dot(b, gw) ? s : b;\n"
            + "  }\n"
            // HUE (23) / SATURATION (24) / LUMINOSITY (25) -- the remaining non-separable trio.
            // One band, because all three end in the same SetLum + ClipColor tail that COLOR
            // already needed; only the colour fed into it differs.
            //   HUE        = SetLum(SetSat(source,   Sat(backdrop)), Lum(backdrop))
            //   SATURATION = SetLum(SetSat(backdrop, Sat(source)),   Lum(backdrop))
            //   LUMINOSITY = SetLum(backdrop, Lum(source))
            // SetSat is the vectorised form: slide the colour to 0 and rescale its span to the
            // wanted saturation, which is what the W3C's min/mid/max walk computes.
            + "  if (uBlendMode < 25.5) {\n"
            + "    vec3 nw = vec3(0.3, 0.59, 0.11);\n"
            + "    vec3 nc = b;\n"
            + "    float tl = dot(s, nw);\n"
            + "    if (uBlendMode < 24.5) {\n"
            + "      vec3 hb = uBlendMode < 23.5 ? s : b;\n"
            + "      vec3 hs = uBlendMode < 23.5 ? b : s;\n"
            + "      float sat = max(max(hs.r, hs.g), hs.b) - min(min(hs.r, hs.g), hs.b);\n"
            + "      float hn = min(min(hb.r, hb.g), hb.b);\n"
            + "      float hx = max(max(hb.r, hb.g), hb.b);\n"
            + "      nc = hx > hn ? (hb - hn) * sat / (hx - hn) : vec3(0.0);\n"
            + "      tl = dot(b, nw);\n"
            + "    }\n"
            + "    nc = nc + (tl - dot(nc, nw));\n"
            + "    float nl = dot(nc, nw);\n"
            + "    float nn = min(min(nc.r, nc.g), nc.b);\n"
            + "    float nx = max(max(nc.r, nc.g), nc.b);\n"
            + "    if (nn < 0.0) nc = nl + (nc - nl) * nl / max(nl - nn, 0.00001);\n"
            + "    if (nx > 1.0) nc = nl + (nc - nl) * (1.0 - nl) / max(nx - nl, 0.00001);\n"
            + "    return clamp(nc, 0.0, 1.0);\n"
            + "  }\n"
            // Anything past the last known code is NORMAL, not the mode above it. Same reason ADD
            // stopped being the fall-through: a future mode appended without its own band must
            // look obviously unimplemented, never quietly like Luminosity.
            + "  return s;\n"
            + "}\n";

    /** The signature line of {@link #GLSL_BLEND_FN}, kept as a constant so the rewrite below is a
     *  single exact match rather than a regex that could silently match nothing. */
    private static final String SIGNATURE = "vec3 blendPix(vec3 b, vec3 s) {";

    /** What the signature becomes when the mode arrives as an argument instead of a uniform. */
    private static final String SIGNATURE_PARAM = "vec3 blendPix(vec3 b, vec3 s, float uBlendMode) {";

    /**
     * The same equations with the mode passed in rather than read from a uniform — what the FX
     * compiler needs, because a fused pass folds several cards with DIFFERENT blend modes and
     * therefore cannot have one {@code uBlendMode} uniform.
     *
     * <p><b>Derived, not copied.</b> This rewrites only the signature line of
     * {@link #GLSL_BLEND_FN}; the equations themselves are never restated. An edit to the constant
     * above propagates here for free, which is the entire reason this is a method and not a second
     * string. The parameter deliberately keeps the name {@code uBlendMode} so the body needs no
     * touching at all.</p>
     */
    @NonNull
    public static String glslBlendFnWithModeParam() {
        int at = GLSL_BLEND_FN.indexOf(SIGNATURE);
        if (at < 0) {
            // Unreachable unless GLSL_BLEND_FN was edited without updating SIGNATURE. Fail loudly
            // here rather than emitting a shader that will not compile on a user's driver.
            throw new IllegalStateException("BlendModes.GLSL_BLEND_FN no longer starts with the "
                    + "expected signature; update SIGNATURE alongside it");
        }
        return SIGNATURE_PARAM + GLSL_BLEND_FN.substring(at + SIGNATURE.length());
    }

    /**
     * Java mirror of {@link #GLSL_BLEND_FN}. See the class note: this exists so the harness can
     * reach the arithmetic, and is deliberately not on any render path.
     *
     * @param b straight (un-premultiplied) backdrop rgb, components 0..1
     * @param s straight source rgb, components 0..1
     * @param mode a {@link #modeCode} value
     * @return the blended rgb, UNCLAMPED — exactly like the GLSL, whose callers clamp
     */
    @NonNull
    public static float[] blend(@NonNull float[] b, @NonNull float[] s, int mode) {
        float m = mode;
        if (m < 0.5f) return new float[]{s[0], s[1], s[2]};
        if (m < 1.5f) return new float[]{b[0] * s[0], b[1] * s[1], b[2] * s[2]};
        if (m < 2.5f) {
            return new float[]{
                    1f - (1f - b[0]) * (1f - s[0]),
                    1f - (1f - b[1]) * (1f - s[1]),
                    1f - (1f - b[2]) * (1f - s[2]),
            };
        }
        if (m < 3.5f) {
            float[] out = new float[3];
            for (int i = 0; i < 3; i++) {
                float lo = 2f * b[i] * s[i];
                float hi = 1f - 2f * (1f - b[i]) * (1f - s[i]);
                out[i] = b[i] < 0.5f ? lo : hi;
            }
            return out;
        }
        if (m < 4.5f) {
            return new float[]{
                    Math.min(b[0] + s[0], 1f),
                    Math.min(b[1] + s[1], 1f),
                    Math.min(b[2] + s[2], 1f),
            };
        }
        if (m < 5.5f) {
            return new float[]{
                    Math.abs(b[0] - s[0]),
                    Math.abs(b[1] - s[1]),
                    Math.abs(b[2] - s[2]),
            };
        }
        if (m < 6.5f) {
            // COLOR -- mirrors the GLSL branch line for line, including the ClipColor rescale.
            float bl = 0.3f * b[0] + 0.59f * b[1] + 0.11f * b[2];
            float sl = 0.3f * s[0] + 0.59f * s[1] + 0.11f * s[2];
            float d0 = bl - sl;
            float[] c = {s[0] + d0, s[1] + d0, s[2] + d0};
            return clipColor(c);
        }
        float[] out = new float[3];
        if (m < 7.5f) {                                                        // DARKEN
            for (int i = 0; i < 3; i++) out[i] = Math.min(b[i], s[i]);
            return out;
        }
        if (m < 8.5f) {                                                        // LIGHTEN
            for (int i = 0; i < 3; i++) out[i] = Math.max(b[i], s[i]);
            return out;
        }
        if (m < 9.5f) {                                                        // COLOR DODGE
            for (int i = 0; i < 3; i++) {
                out[i] = Math.min(b[i] / Math.max(1f - s[i], 0.00001f), 1f);
            }
            return out;
        }
        if (m < 10.5f) {                                                       // COLOR BURN
            for (int i = 0; i < 3; i++) {
                out[i] = 1f - Math.min((1f - b[i]) / Math.max(s[i], 0.00001f), 1f);
            }
            return out;
        }
        if (m < 11.5f) {                                                       // LINEAR BURN
            for (int i = 0; i < 3; i++) out[i] = Math.max(b[i] + s[i] - 1f, 0f);
            return out;
        }
        if (m < 12.5f) {                                                       // HARD LIGHT
            for (int i = 0; i < 3; i++) {
                float lo = 2f * b[i] * s[i];
                float hi = 1f - 2f * (1f - b[i]) * (1f - s[i]);
                out[i] = s[i] < 0.5f ? lo : hi;
            }
            return out;
        }
        if (m < 13.5f) {                                                       // SOFT LIGHT
            for (int i = 0; i < 3; i++) {
                float bi = b[i], si = s[i];
                float dd = bi <= 0.25f
                        ? ((16f * bi - 12f) * bi + 4f) * bi
                        : (float) Math.sqrt(Math.max(bi, 0f));
                out[i] = si <= 0.5f
                        ? bi - (1f - 2f * si) * bi * (1f - bi)
                        : bi + (2f * si - 1f) * (dd - bi);
            }
            return out;
        }
        if (m < 14.5f) {                                                       // VIVID LIGHT
            for (int i = 0; i < 3; i++) {
                float burn = 1f - Math.min((1f - b[i]) / Math.max(2f * s[i], 0.00001f), 1f);
                float dodge = Math.min(b[i] / Math.max(2f - 2f * s[i], 0.00001f), 1f);
                out[i] = s[i] <= 0.5f ? burn : dodge;
            }
            return out;
        }
        if (m < 15.5f) {                                                       // LINEAR LIGHT
            for (int i = 0; i < 3; i++) {
                out[i] = Math.max(0f, Math.min(1f, b[i] + 2f * s[i] - 1f));
            }
            return out;
        }
        if (m < 16.5f) {                                                       // PIN LIGHT
            for (int i = 0; i < 3; i++) {
                out[i] = s[i] <= 0.5f ? Math.min(b[i], 2f * s[i])
                                      : Math.max(b[i], 2f * s[i] - 1f);
            }
            return out;
        }
        if (m < 17.5f) {                                                       // HARD MIX
            for (int i = 0; i < 3; i++) out[i] = (b[i] + s[i]) >= 1f ? 1f : 0f;
            return out;
        }
        if (m < 18.5f) {                                                       // EXCLUSION
            for (int i = 0; i < 3; i++) out[i] = b[i] + s[i] - 2f * b[i] * s[i];
            return out;
        }
        if (m < 19.5f) {                                                       // SUBTRACT
            for (int i = 0; i < 3; i++) out[i] = Math.max(b[i] - s[i], 0f);
            return out;
        }
        if (m < 20.5f) {                                                       // DIVIDE
            for (int i = 0; i < 3; i++) {
                out[i] = Math.min(b[i] / Math.max(s[i], 0.00001f), 1f);
            }
            return out;
        }
        if (m < 21.5f) {                                                       // DARKER COLOR
            float[] pick = lum(s) < lum(b) ? s : b;
            return new float[]{pick[0], pick[1], pick[2]};
        }
        if (m < 22.5f) {                                                       // LIGHTER COLOR
            float[] pick = lum(s) > lum(b) ? s : b;
            return new float[]{pick[0], pick[1], pick[2]};
        }
        if (m < 25.5f) {                                                       // HUE/SAT/LUMINOSITY
            float[] nc = {b[0], b[1], b[2]};
            float tl = lum(s);
            if (m < 24.5f) {
                float[] hb = m < 23.5f ? s : b;   // whose HUE survives
                float[] hs = m < 23.5f ? b : s;   // whose SATURATION survives
                float sat = Math.max(Math.max(hs[0], hs[1]), hs[2])
                        - Math.min(Math.min(hs[0], hs[1]), hs[2]);
                float hn = Math.min(Math.min(hb[0], hb[1]), hb[2]);
                float hx = Math.max(Math.max(hb[0], hb[1]), hb[2]);
                if (hx > hn) {
                    for (int i = 0; i < 3; i++) nc[i] = (hb[i] - hn) * sat / (hx - hn);
                } else {
                    nc = new float[]{0f, 0f, 0f};
                }
                tl = lum(b);
            }
            float shift = tl - lum(nc);
            for (int i = 0; i < 3; i++) nc[i] += shift;
            return clipColor(nc);
        }
        return new float[]{s[0], s[1], s[2]};
    }

    private static float lum(@NonNull float[] c) {
        return 0.3f * c[0] + 0.59f * c[1] + 0.11f * c[2];
    }

    /**
     * The W3C ClipColor tail, shared by COLOR and by the HUE/SATURATION/LUMINOSITY band exactly as
     * the GLSL repeats it inline. Kept as one private method HERE (and only here) because this is
     * the Java mirror, which has no shader-linking constraint — the GLSL cannot do the same.
     */
    @NonNull
    private static float[] clipColor(@NonNull float[] c) {
        float cl = lum(c);
        float cn = Math.min(Math.min(c[0], c[1]), c[2]);
        float cx = Math.max(Math.max(c[0], c[1]), c[2]);
        if (cn < 0f) {
            float k = cl / Math.max(cl - cn, 0.00001f);
            for (int i = 0; i < 3; i++) c[i] = cl + (c[i] - cl) * k;
        }
        if (cx > 1f) {
            float k = (1f - cl) / Math.max(cx - cl, 0.00001f);
            for (int i = 0; i < 3; i++) c[i] = cl + (c[i] - cl) * k;
        }
        for (int i = 0; i < 3; i++) c[i] = Math.max(0f, Math.min(1f, c[i]));
        return c;
    }
}
