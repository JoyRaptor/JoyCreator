#version 300 es
// jb_dry_dab.frag — what one dab of a dry stick does to each pixel, accumulated ADDITIVELY into the
// stroke's delta buffer (blend ONE, ONE; no read-back, so dabs batch). jb_media_apply.frag turns the
// delta into deposit once per frame. Saturation is closed-form in the accumulated work, so the result
// does not depend on how dabs are batched.
//
// Physics (MEDIA_ENGINE_PLAN.md §1.2–1.3):
//   squeeze      c = D − z_stick                             (mm the paper is pushed down here)
//   tooth level  a = LUT(c)   two-layer paper: the tooth takes part of c, the soft pad the rest
//   penetration  δ = (a − (1 − h))·tooth                      (mm; > 0 where this pixel's tooth is touched)
//   abrasion work = δ^1.5 / √tooth · slide · directional       (Archard wear ∝ pressure × distance, and a
//                   Hertzian asperity's pressure ∝ δ^1.5: light contact sheds little, the tip's hard edge a lot)
//   crush work    = E[max(0, δ − crushStart)] · slide          (hard pressure flattens the tooth)
//   dust          = the pits the stick passes over but cannot reach get a little loose dust
precision highp float;
precision highp int;

#include "media/jb_media_common.glsl"
#include "media/jb_stick.glsl"
#include "jb_paper.glsl"

uniform sampler2D u_crush;      // R = crush, in tooth units (0 = untouched)
uniform sampler2D u_contactLut; // R = tooth level a for squeeze c (jb lab: stick.js contactLut)
uniform float u_lutMaxMm;
uniform vec2 u_targetSize;
uniform float u_pxPerMm;
uniform float u_toothMm;
uniform float u_dirStrength;    // directional deposit: faces that meet the stroke catch more
uniform float u_dustRate;       // dust into unreached pits, per mm slid
uniform float u_crushStart;     // penetration (mm) past which the tooth starts to flatten
uniform float u_conform;        // 0..1: the sheet bends onto the stick, so only the fine tooth decides contact

in vec2 v_q;
in vec2 v_docPx;
in vec2 v_layerPx;
flat in vec4 v_i1;
flat in vec4 v_i2;
flat in vec4 v_i3;
flat in vec4 v_i4;
flat in vec2 v_lean;

out vec4 o_delta;               // R abrasion work (mm²), G crush work (mm²), B dust (mm), A contact·slide (mm)

void main() {
    float D = v_i1.w;
    float z = jb_stickZ(v_q, v_i2.x, v_i2.y, v_i2.z, v_i3.x, v_i3.y, v_i3.z, v_i4.z);
    if (z >= JB_INF * 0.5) discard;
    float c = D - z;
    if (c <= 0.0) discard;
    const float N = 255.0;
    float a = texture(u_contactLut, vec2((min(c / u_lutMaxMm, 1.0) * N + 0.5) / (N + 1.0), 0.5)).r;
    vec4 s = jb_paperSurface(v_docPx);
    float crush = jb_bilinear(u_crush, v_layerPx).r;
    float T = u_toothMm;
    float h = s.z - crush - u_conform * (jb_paperCoarseHeight(v_docPx) - u_paperHeightMean);
    float sigma = sqrt(max(s.w - s.z * s.z, 0.0)) * T;
    float mu = (a - (1.0 - h)) * T;
    float slide = v_i3.w;

    float e = jb_expectPos(mu, sigma);
    vec2 grad = s.xy * u_pxPerMm * T;            // physical slope of the tooth (mm per mm)
    float dirW = clamp(1.0 + u_dirStrength * dot(grad, v_i4.xy), 0.0, 3.0);
    float work = e * sqrt(e / T) * slide * dirW;
    float crushWork = jb_expectPos(mu - u_crushStart, sigma) * slide;

    // Over the footprint (stick below the tooth tops) but not touching: loose dust settles in the pit.
    float over = smoothstep(0.0, 0.25 * T, c);
    float reach = 1.0 - jb_probPos(mu, sigma);
    float dust = u_dustRate * slide * over * reach * exp(min(mu, 0.0) / (0.4 * T));

    float contact = jb_probPos(mu, sigma);
    o_delta = vec4(work, crushWork, dust, contact * slide);
}
