// jb_media_common.glsl — shared helpers for the Joy Brush media engine (MEDIA_ENGINE_PLAN.md).
// GLSL ES 3.00 functions only: NO #version, NO main. Include after the #version line.
//
// Units: lengths in MILLIMETRES unless a name ends in Px (document px). Paper height h is 0 (deepest pit)
// .. 1 (tooth top); physical relief = h · toothMm.

const float JB_PI = 3.14159265358979;
const float JB_INF = 1.0e6;

// Standard normal pdf / cdf (cdf: Abramowitz–Stegun 26.2.17 style tanh fit, |err| < 2e-4).
float jb_phi(float z) { z = clamp(z, -6.0, 6.0); return 0.3989422804 * exp(-0.5 * z * z); }
// z is clamped first: tanh of a huge argument is NaN on some GPUs (seen on ANGLE/D3D11, 2026-10-06), and a
// NaN pixel spreads through every later pass.
float jb_Phi(float z) { z = clamp(z, -6.0, 6.0); return 0.5 * (1.0 + tanh(0.7978845608 * (z + 0.044715 * z * z * z))); }

// E[max(0, X)] for X ~ Normal(mu, sigma²): the expected penetration of a tool into a pixel whose
// paper heights spread with sigma. sigma → 0 gives max(mu, 0). This prefilter is what keeps
// zoomed-out deposit from shimmering, and lets vector strokes re-render correctly at any zoom.
float jb_expectPos(float mu, float sigma) {
    if (sigma < 1.0e-6) return max(mu, 0.0);
    float z = mu / sigma;
    return mu * jb_Phi(z) + sigma * jb_phi(z);
}
// P(X > 0): the fraction of the pixel's paper the tool touches.
float jb_probPos(float mu, float sigma) {
    if (sigma < 1.0e-6) return mu > 0.0 ? 1.0 : 0.0;
    return jb_Phi(mu / sigma);
}

// Kubelka–Munk, one channel. K, S are TOTAL absorption / scattering of the layer (coefficient × amount).
// Rg = reflectance of what lies under the layer. S → 0 gives the transparent glaze Rg·exp(-2K).
float jb_kmChannel(float K, float S, float Rg) {
    K = max(K, 0.0); S = max(S, 0.0);
    if (S < 1.0e-5) return Rg * exp(-2.0 * K);
    float r = K / S;
    if (r < 1.0e-4) return (S * (1.0 - Rg) + Rg) / (1.0 + S * (1.0 - Rg));
    float a = 1.0 + r;
    float b = sqrt(a * a - 1.0);
    float e = exp(-2.0 * min(b * S, 20.0));
    float cth = (1.0 + e) / max(1.0 - e, 1.0e-6);
    return clamp((1.0 - Rg * (a - b * cth)) / (a - Rg + b * cth), 0.0, 1.0);
}
vec3 jb_km(vec3 K, vec3 S, vec3 Rg) {
    return vec3(jb_kmChannel(K.r, S.r, Rg.r), jb_kmChannel(K.g, S.g, Rg.g), jb_kmChannel(K.b, S.b, Rg.b));
}

// Integer hash → [0,1). Deterministic across runs (replay and export must match the screen).
float jb_mHash(uvec3 v) {
    v = v * 1664525u + 1013904223u;
    v.x += v.y * v.z; v.y += v.z * v.x; v.z += v.x * v.y;
    v ^= v >> 16u;
    v.x += v.y * v.z; v.y += v.z * v.x; v.z += v.x * v.y;
    return float(v.x >> 8u) / 16777216.0;
}

// Bilinear read of a NEAREST (full-float) texture at doc px: 4 texel fetches.
vec4 jb_bilinear(sampler2D t, vec2 docPx) {
    ivec2 size = textureSize(t, 0);
    vec2 p = docPx - 0.5;
    ivec2 i = ivec2(floor(p));
    vec2 f = p - vec2(i);
    ivec2 a = clamp(i, ivec2(0), size - 1), b = clamp(i + 1, ivec2(0), size - 1);
    vec4 v00 = texelFetch(t, a, 0), v10 = texelFetch(t, ivec2(b.x, a.y), 0);
    vec4 v01 = texelFetch(t, ivec2(a.x, b.y), 0), v11 = texelFetch(t, b, 0);
    return mix(mix(v00, v10, f.x), mix(v01, v11, f.x), f.y);
}

// Smooth value noise (deterministic): 1D across a brush's hairs, 2D in document space.
float jb_vnoise1(float x, uint seed) {
    float i = floor(x), f = x - i;
    float a = jb_mHash(uvec3(uint(int(i) + 65536), seed, 11u)), b = jb_mHash(uvec3(uint(int(i) + 65537), seed, 11u));
    return mix(a, b, f * f * (3.0 - 2.0 * f));
}
float jb_vnoise2(vec2 p, uint seed) {
    vec2 i = floor(p), f = p - i;
    f = f * f * (3.0 - 2.0 * f);
    uvec2 u = uvec2(ivec2(i) + 1048576);
    float a = jb_mHash(uvec3(u, seed)), b = jb_mHash(uvec3(u + uvec2(1, 0), seed));
    float c = jb_mHash(uvec3(u + uvec2(0, 1), seed)), d = jb_mHash(uvec3(u + uvec2(1, 1), seed));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

vec3 jb_srgbToLinear(vec3 c) { return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(0.04045, c)); }
vec3 jb_linearToSrgb(vec3 c) {
    c = clamp(c, 0.0, 1.0);
    return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, c));
}
