// Sampled Unibrush tips share all procedural profile/quad/fade arithmetic when selected.
#include "jb_profile.glsl"

uniform int u_sampleEnabled;
uniform highp sampler2D u_sampleMasks;
uniform vec4 u_sampleRects[16]; // logical image-edge UVs; one transparent pixel surrounds each image
flat in vec4 v_sample; // index+1, native width, native height, minPx

float jb_sampledTipCoverage(vec2 offsetPx, JbTip t, float fadeAmount, float fadeAngleLocal,
                          vec4 quad01, vec4 quad23, vec4 quadMeta) {
    if (u_sampleEnabled == 0 || v_sample.x < 0.5 || v_sample.y <= 0.0 || v_sample.z <= 0.0)
        return jb_unibrushTipCoverage(offsetPx, t, fadeAmount, fadeAngleLocal, quad01, quad23, quadMeta);
    bool quadOn = quadMeta.y > 0.0 && quadMeta.w > 0.5;
    if (quadOn && quadMeta.w == 1.0) return 0.0;
    float c = cos(t.angle), s = sin(t.angle);
    vec2 q = vec2(c * offsetPx.x + s * offsetPx.y, -s * offsetPx.x + c * offsetPx.y);
    float a = clamp(t.aspect, -1.0, 1.0);
    vec2 raw = t.radiusPx * vec2(a > 0.0 ? 1.0-a : 1.0, a < 0.0 ? 1.0+a : 1.0);
    float halfMin = max(t.minPx, 0.5) * 0.5;
    float razor = min(max(raw.x, 0.25) / halfMin, 1.0) * min(max(raw.y, 0.25) / halfMin, 1.0);
    vec2 axes = max(raw, vec2(halfMin));
    float along = clamp(q.y / axes.y * 0.5 + 0.5, 0.0, 1.0);
    float widthScale = max(1.0 - clamp(t.taper, 0.0, 1.0) * along, 1e-3);
    vec2 normalized = q / (axes * vec2(widthScale, 1.0));
    vec2 nativeSize = v_sample.yz;
    vec2 imageUv = (normalized * max(nativeSize.x, nativeSize.y) / nativeSize + 1.0) * 0.5;
    float cov = 1.0;
    if (quadOn || u_tipProfile != 0) {
        float d = max(abs(normalized.x), abs(normalized.y));
        if (quadOn) d = 1.0 + jb_quadSignedDistance(normalized, quad01, quad23, quadMeta) / quadMeta.y;
        float aa = max(fwidth(d), 1e-4);
        float inner = min(clamp(t.hardness, 0.0, 1.0), 1.0-aa);
        float outer = 1.0 + 0.5*aa;
        cov = jb_radialCoverage(false, inner, outer, d, aa);
        if (quadOn && quadMeta.z > 0.0) cov = mix(cov, jb_radialCoverage(true, inner, outer, d, aa), clamp(quadMeta.z, 0.0, 1.0));
    }
    // Derivatives above execute before this fragment-varying support guard. Explicit LOD keeps
    // atlas sampling independent of branch derivatives; no whole-atlas mipmaps can bleed cells.
    vec2 support = 0.5 / nativeSize;
    if (any(lessThanEqual(imageUv, -support)) || any(greaterThanEqual(imageUv, 1.0 + support))) return 0.0;
    vec4 rect = u_sampleRects[clamp(int(v_sample.x + 0.5)-1, 0, 15)];
    float mask = textureLod(u_sampleMasks, mix(rect.xy, rect.zw, imageUv), 0.0).r;
    float result = mask * cov * razor;
    if (fadeAmount <= 0.0) return result;
    vec2 v = vec2(cos(fadeAngleLocal), sin(fadeAngleLocal));
    float reach = max(length(axes * v), 1e-4);
    float across = clamp(0.5 + 0.5 * dot(q, v) / reach, 0.0, 1.0);
    return result * (1.0-clamp(fadeAmount, 0.0, 1.0)*across);
}
