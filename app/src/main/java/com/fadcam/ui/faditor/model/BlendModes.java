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
 * harness pins that against a pasted copy of the pre-extraction text
 * ({@code BlendModesTest.extractionIsByteIdentical}). If you edit it, you are changing every
 * existing export, and that test is where you will find out.</p>
 *
 * <p><b>The Java mirror is not a duplicate — it is the instrument.</b> GLSL cannot run in the JVM
 * harness, so {@link #blend} mirrors {@link #GLSL_BLEND_FN} line for line to let the harness pin the
 * branch boundaries (b == 0.5 exactly, the ADD clamp, the OVERLAY per-channel select) off-device.
 * Nothing renders through it, so it can never quietly become the thing that ships.</p>
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
    public static final String ADD = "ADD";

    /** Every mode, in the order a picker should list them. */
    public static final String[] ALL = {NORMAL, MULTIPLY, SCREEN, OVERLAY, ADD};

    /**
     * Wire value → the float the shader branches on. Unknown strings fall back to NORMAL rather
     * than throwing: an unreadable blend mode must cost a project its blend, never its export.
     */
    public static int modeCode(@NonNull String blendMode) {
        switch (blendMode) {
            case "MULTIPLY": return 1;
            case "SCREEN":   return 2;
            case "OVERLAY":  return 3;
            case "ADD":      return 4;
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
            + "  return min(b + s, vec3(1.0));\n"
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
        return new float[]{
                Math.min(b[0] + s[0], 1f),
                Math.min(b[1] + s[1], 1f),
                Math.min(b[2] + s[2], 1f),
        };
    }
}
