package com.fadcam.ui.faditor.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * THE single authority for chroma keying — the GLSL that does it, the uniform packing that
 * feeds it, and a Java reference implementation of the same arithmetic.
 *
 * <p><b>Why this class exists at all.</b> The key ran export-only for months, and the binding
 * user decision (2026-07-28) is that a tolerance slider tuned against an unkeyed preview is
 * tuning blind. Making the preview key means a SECOND renderer, and this project has paid
 * repeatedly for the same quantity being derived twice — the mask feather note on
 * {@link CompositingSpec#featherRadiusPx} says so in as many words, and the §3a matte bug was
 * exactly two lists that were supposed to agree. So the shader SOURCE lives here as one string
 * and both renderers compile that string. They cannot disagree about the key, because there is
 * only one key.</p>
 *
 * <p><b>The Java reference is not a duplicate — it is the instrument.</b> GLSL cannot run in
 * the JVM harness, so without {@link #keepFactor} the key's arithmetic would be verifiable only
 * by looking at exported pixels on a phone. {@link #keepFactor} mirrors {@link #GLSL_KEY_FN}
 * line for line so the harness can pin the edge cases (tolerance 0, fuzziness 0, offset at both
 * rails) off-device. It is a MIRROR, not a second implementation: nothing in the app renders
 * through it, so it can never silently become the thing that ships. If you edit one, edit both,
 * and the harness will tell you if the two stop agreeing at the sampled points.</p>
 *
 * <p><b>Colour space, stated because it is the classic key bug.</b> The distance is measured on
 * STRAIGHT (un-premultiplied) RGB. A premultiplied sample of a semi-transparent green pixel is
 * darker than the green it is, so it lands further from the key colour and survives — keying
 * would then eat opaque green and keep translucent green, which reads as noise around the
 * edges. Both call sites un-premultiply before calling in.</p>
 */
public final class ChromaKey {

    private ChromaKey() {}

    /** Guards the {@code smoothstep} edge pair against edge0 == edge1, which is undefined. */
    public static final float EDGE_EPSILON = 0.0001f;

    /**
     * The key, as a GLSL 1.00 (ES 2.0) function — ES2 rather than ES3 because the preview tier
     * targets minSdk 24 and the export effect compiles {@code #version 100} too.
     *
     * <p>Takes STRAIGHT rgb and the current alpha, returns the keyed alpha. Callers concatenate
     * this into their own fragment shader and call {@code fadKeyAlpha(...)}; the name is
     * prefixed so it cannot collide with a media3 built-in.</p>
     *
     * <p>{@code params} is packed by {@link #packParams}: x=enabled, y=tolerance, z=fuzziness,
     * w=offset.</p>
     */
    public static final String GLSL_KEY_FN =
            "float fadKeyAlpha(vec3 straightRgb, float alpha, vec3 keyColor, vec4 params) {\n"
            + "  if (params.x < 0.5) return alpha;\n"
            + "  float d = distance(straightRgb, keyColor);\n"
            + "  float keep = smoothstep(params.y, params.y + params.z + "
            + EDGE_EPSILON + ", d);\n"
            + "  return alpha * clamp(keep + params.w, 0.0, 1.0);\n"
            + "}\n";

    /**
     * Java mirror of {@link #GLSL_KEY_FN}. See the class note: this exists so the harness can
     * reach the arithmetic, and is deliberately not on any render path.
     *
     * @param straight un-premultiplied source colour, components 0..1
     * @param key      the key colour, components 0..1
     * @return the multiplier to apply to source alpha, already clamped to 0..1
     */
    public static float keepFactor(@NonNull float[] straight, @NonNull float[] key,
                                   float tolerance, float fuzziness, float offset) {
        double dr = straight[0] - key[0];
        double dg = straight[1] - key[1];
        double db = straight[2] - key[2];
        float d = (float) Math.sqrt(dr * dr + dg * dg + db * db);
        float keep = smoothstep(tolerance, tolerance + fuzziness + EDGE_EPSILON, d);
        return clamp01(keep + offset);
    }

    /** GLSL {@code smoothstep}, to the letter — including its clamp and Hermite curve. */
    public static float smoothstep(float edge0, float edge1, float x) {
        if (edge1 <= edge0) return x < edge0 ? 0f : 1f;   // degenerate pair: GLSL is undefined here
        float t = clamp01((x - edge0) / (edge1 - edge0));
        return t * t * (3f - 2f * t);
    }

    /**
     * Uniform packing shared by both renderers: x=enabled(0/1), y=tolerance, z=fuzziness,
     * w=offset. A disabled or absent spec packs x=0, which the shader short-circuits on, so
     * neither renderer needs a second "is keying on" branch of its own to keep in step.
     */
    @NonNull
    public static float[] packParams(@Nullable CompositingSpec spec) {
        float[] p = new float[4];
        if (spec == null || !spec.keyEnabled) return p;   // all zeros => disabled
        p[0] = 1f;
        p[1] = clamp01(spec.keyTolerance);
        p[2] = clamp01(spec.keyFuzziness);
        p[3] = Math.max(-1f, Math.min(1f, spec.keyOffset));
        return p;
    }

    /** The key colour as GL-ready 0..1 RGB. Alpha in {@code keyColor} is ignored by design. */
    @NonNull
    public static float[] packColor(@Nullable CompositingSpec spec) {
        int rgb = spec != null ? spec.keyColor : 0x00FF00;
        return new float[]{
                ((rgb >> 16) & 0xFF) / 255f,
                ((rgb >> 8) & 0xFF) / 255f,
                (rgb & 0xFF) / 255f,
        };
    }

    /**
     * Whether keying would change ANY pixel. The preview tier uses this to decide between the
     * cheap plain-TextureView path and the GL path, so a project that never touched the key
     * pays exactly nothing — the same "feather 0 takes the identical old path" discipline.
     *
     * <p>Note the offset rail: at offset >= 1 every pixel is kept, so the key is a no-op even
     * with {@code keyEnabled} true. Treating that as "on" would light up the GL tier for a
     * setting that cannot be seen.</p>
     */
    public static boolean isActive(@Nullable CompositingSpec spec) {
        return spec != null && spec.keyEnabled && spec.keyOffset < 1f;
    }

    private static float clamp01(float v) {
        if (Float.isNaN(v)) return 0f;
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
