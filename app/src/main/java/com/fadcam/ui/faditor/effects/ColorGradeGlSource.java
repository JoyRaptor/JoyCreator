package com.fadcam.ui.faditor.effects;

import androidx.annotation.NonNull;

/**
 * The clip colour grade as GLSL — for EXPORT and for the LIVE GL PREVIEW.
 *
 * <p><b>Why the preview cannot simply reuse the export's effects.</b> Export builds the grade as a
 * CHAIN of media3 effects ({@link EffectStack#toEffects}): {@code Brightness}, {@code Contrast},
 * {@code HslAdjustment}, {@code RgbAdjustment}, then {@link ColorGradeShaderProgram}. Handing that
 * same list to {@code ExoPlayer.setVideoEffects} was tried and does not render in this app's
 * preview path — verified on device, saturation 0 left the picture fully saturated — which is
 * what {@code FaditorEditorActivity.applyPreviewColorGrade}'s javadoc always said. So the preview
 * has to run the grade itself.</p>
 *
 * <p><b>What keeps it honest anyway.</b> Nothing here is a re-derivation. The three matrix stages
 * are applied exactly as media3 applies them ({@code fragment_shader_transformation_es2.glsl}:
 * {@code uRgbMatrix * vec4(rgb, 1)}, alpha preserved) and the MATRICES THEMSELVES are obtained at
 * runtime from media3's own {@code Brightness} / {@code Contrast} / {@code RgbAdjustment}
 * implementations rather than recomputed. The saturation stage is media3's
 * {@code fragment_shader_hsl_es2.glsl} verbatim, Hocevar's branchless conversion included. The
 * last stage is {@link ColorGradeShaderProgram}'s own body, which now lives here and is called by
 * both. The one thing preview and export can still disagree about is a LUT — see below.</p>
 *
 * <p><b>ORDER IS THE SEMANTIC.</b> Exposure and contrast, then saturation, then temperature and
 * tint, then the shader-only parameters. That is {@code toEffects}' order, and it is not
 * interchangeable: saturation sits BETWEEN two matrix stages, which is exactly why they cannot be
 * collapsed into one multiply.</p>
 *
 * <p><b>NOT COVERED: the 3D LUT.</b> {@code ColorLut} needs a 3D texture, and GLSL ES 1.00 has
 * none. A LUT still exports correctly; it is the one grade control whose preview is honest about
 * being absent rather than wrong. {@link #lutIsPreviewable} is the single place that knows.</p>
 */
public final class ColorGradeGlSource {

    private ColorGradeGlSource() {}

    /**
     * Whether the grade a stack describes can be shown in full, or whether a LUT is silently
     * missing from it. The UI asks this so it can say so; nothing else should assume either way.
     */
    public static boolean lutIsPreviewable(@NonNull EffectStack s) {
        return !(s.isLutEnabled() && s.getLutId() != null && s.getLutIntensity() > 0.001f);
    }

    /**
     * {@link ColorGradeShaderProgram}'s grade, as a callable function.
     *
     * <p>Deliberately parameterised on {@code uv} rather than reading a fixed varying: export
     * samples through {@code vTexSamplingCoord} and the preview through {@code vFxUv}, and that
     * is the ONLY difference between the two call sites. Extracting the body is what stops the
     * next edit from landing in one of them.</p>
     *
     * <p>No exposure, temperature or tint here, and their absence is load-bearing: media3's
     * {@code Brightness} and {@code RgbAdjustment} sit earlier in the same chain and have already
     * applied them. Adding them again — additive here, multiplicative there — was a real
     * double-apply that fired on every graded project with a vignette or grain.</p>
     */
    public static final String GRADE_FN =
            "float fadGradeRand(vec2 co) {\n"
            + "  return fract(sin(dot(co, vec2(12.9898, 78.233))) * 43758.5453);\n"
            + "}\n"
            + "vec3 fadColorGrade(vec3 color, vec2 uv, float uHighlights, float uShadows,\n"
            + "                   float uFade, float uVignette, float uGrain) {\n"
            // The luma the masks key off is measured on the ALREADY-EXPOSED colour, which is what
            // the masks were always meant to see: brightening a shot should move which pixels
            // count as highlights.
            + "  float luma = dot(color, vec3(0.299, 0.587, 0.114));\n"
            + "  float highlightMask = step(0.5, luma) * clamp((luma - 0.5) * 2.0, 0.0, 1.0);\n"
            + "  float shadowMask = step(luma, 0.5) * clamp((0.5 - luma) * 2.0, 0.0, 1.0);\n"
            + "  color = mix(color, vec3(luma), uHighlights * highlightMask);\n"
            + "  color = mix(color, vec3(luma), -uShadows * shadowMask);\n"
            + "  float fadeUp = max(0.0, uFade);\n"
            + "  float fadeDown = max(0.0, -uFade);\n"
            + "  color = mix(color, vec3(0.0), fadeUp);\n"
            + "  color = mix(color, vec3(1.0), fadeDown);\n"
            + "  float d = distance(uv, vec2(0.5));\n"
            + "  float vignette = smoothstep(0.72, 0.28, d) * uVignette;\n"
            + "  color *= 1.0 - vignette;\n"
            + "  float noise = fadGradeRand(uv + fract(uGrain * 1000.0)) - 0.5;\n"
            + "  color += noise * uGrain * 0.08;\n"
            + "  return clamp(color, 0.0, 1.0);\n"
            + "}\n";

    /**
     * media3's HSL conversion, verbatim from {@code fragment_shader_hsl_es2.glsl}, reduced to the
     * saturation adjustment this project actually uses.
     *
     * <p>Hocevar's branchless RGB↔HSL. Copied rather than paraphrased on purpose — a "tidier"
     * saturation (a lerp toward luma, say) is a DIFFERENT curve, and it would look close enough
     * in the editor to be trusted and wrong on export.</p>
     */
    public static final String HSL_FN =
            "vec3 fadRgbToHcv(vec3 rgb) {\n"
            + "  vec4 p = (rgb.g < rgb.b) ? vec4(rgb.bg, -1.0, 2.0 / 3.0)\n"
            + "                           : vec4(rgb.gb, 0.0, -1.0 / 3.0);\n"
            + "  vec4 q = (rgb.r < p.x) ? vec4(p.xyw, rgb.r) : vec4(rgb.r, p.yzx);\n"
            + "  float c = q.x - min(q.w, q.y);\n"
            + "  float h = abs((q.w - q.y) / (6.0 * c + 1e-10) + q.z);\n"
            + "  return vec3(h, c, q.x);\n"
            + "}\n"
            + "vec3 fadHueToRgb(float hue) {\n"
            + "  float r = abs(hue * 6.0 - 3.0) - 1.0;\n"
            + "  float g = 2.0 - abs(hue * 6.0 - 2.0);\n"
            + "  float b = 2.0 - abs(hue * 6.0 - 4.0);\n"
            + "  return clamp(vec3(r, g, b), 0.0, 1.0);\n"
            + "}\n"
            + "vec3 fadSaturate(vec3 rgb, float adj) {\n"
            + "  vec3 hcv = fadRgbToHcv(rgb);\n"
            + "  float l = hcv.z - hcv.y * 0.5;\n"
            + "  float s = hcv.y / (1.0 - abs(l * 2.0 - 1.0) + 1e-10);\n"
            + "  s = clamp(s + adj, 0.0, 1.0);\n"
            + "  vec3 base = fadHueToRgb(hcv.x);\n"
            + "  float c = (1.0 - abs(2.0 * l - 1.0)) * s;\n"
            + "  return (base - 0.5) * c + l;\n"
            + "}\n";

    /**
     * The preview's whole grade, in one pass.
     *
     * <p>Each stage is gated by an {@code On} flag rather than by a neutral value, because
     * {@code toEffects} SKIPS an inactive effect entirely and an HSL round trip is not a perfect
     * identity — running it at saturation 1.0 would drift the picture where export would not
     * touch it at all. The flags reproduce {@code toEffects}' own thresholds.</p>
     */
    public static final String PREVIEW_FRAGMENT =
            "#version 100\n"
            + "precision highp float;\n"
            + "varying vec2 vFxUv;\n"
            + "uniform sampler2D uTexSampler;\n"
            + "uniform mat4 uGradeMatA;\n"      // Brightness then Contrast
            + "uniform mat4 uGradeMatB;\n"      // RgbAdjustment (temperature / tint)
            + "uniform float uGradeMatAOn;\n"
            + "uniform float uGradeMatBOn;\n"
            + "uniform float uSatOn;\n"
            + "uniform float uSatAdj;\n"
            + "uniform float uShaderOn;\n"
            + "uniform float uHighlights;\n"
            + "uniform float uShadows;\n"
            + "uniform float uFade;\n"
            + "uniform float uVignette;\n"
            + "uniform float uGrain;\n"
            + HSL_FN
            + GRADE_FN
            + "void main() {\n"
            + "  vec4 src = texture2D(uTexSampler, vFxUv);\n"
            + "  vec3 c = src.rgb;\n"
            // media3 writes each matrix stage into an 8-bit target, which clamps between stages.
            // Clamping here reproduces that; without it a bright exposure would come back down
            // under a later negative stage instead of having been crushed, as it is on export.
            + "  if (uGradeMatAOn > 0.5) c = clamp((uGradeMatA * vec4(c, 1.0)).rgb, 0.0, 1.0);\n"
            + "  if (uSatOn > 0.5) c = clamp(fadSaturate(c, uSatAdj), 0.0, 1.0);\n"
            + "  if (uGradeMatBOn > 0.5) c = clamp((uGradeMatB * vec4(c, 1.0)).rgb, 0.0, 1.0);\n"
            + "  if (uShaderOn > 0.5) {\n"
            + "    c = fadColorGrade(c, vFxUv, uHighlights, uShadows, uFade, uVignette, uGrain);\n"
            + "  }\n"
            + "  gl_FragColor = vec4(c, src.a);\n"
            + "}\n";
}
