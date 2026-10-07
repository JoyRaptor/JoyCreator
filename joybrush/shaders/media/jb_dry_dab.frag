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
// Two ways graphite reaches the paper (the owner, 2026-10-06), blended by pressure:
//   dusting  — a light touch sheds loose dust that settles evenly and softly over everything the lead
//              passes, fine-grained and low in opacity; only the pits are shielded. Back-and-forth light
//              passes build it up smoothly.
//   piling   — pressing hard scrapes dark graphite and jams it against the tooth faces that meet the
//              direction of travel: directional, clumped on the coarser hills, very dark.
//   pile work = (δ/tooth)^n · tooth · slide · directional · clump  (Archard wear ∝ pressure × distance)
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
uniform float u_dustRate;       // dusting: graphite dust per mm slid at full contact (mm)
uniform float u_clump;          // pressed graphite piles on the coarse hills (0 = no clumping)
uniform float u_crushStart;     // penetration (mm) past which the tooth starts to flatten
uniform float u_conform;        // 0..1: the sheet bends onto the stick, so only the fine tooth decides contact
uniform float u_transferExp;    // n above
uniform float u_leadSoft;       // a soft lead gives where it meets a tip (× tooth): gradual, not binary, contact
uniform float u_plateau;        // paper tooth is plateaus and narrow pits, not spikes: depth below the top = (1−h)^k

in vec2 v_q;
in vec2 v_docPx;
in vec2 v_layerPx;
flat in vec4 v_i1;
flat in vec4 v_i2;
flat in vec4 v_i3;
flat in vec4 v_i4;
flat in vec2 v_lean;

out vec4 o_delta;               // R pile work (mm²), G crush work (mm²), B dust (mm), A contact·slide (mm)

void main() {
    float D = v_i1.w;
    float z = jb_stickZ(v_q, v_i2.x, v_i2.y, v_i2.z, v_i3.x, v_i3.y, v_i3.z, v_i4.z);
    if (z >= JB_INF * 0.5) discard;
    z = jb_conform(z, v_i2.w);
    float c = D - z;
    if (c <= 0.0) discard;
    const float N = 255.0;
    float a = texture(u_contactLut, vec2((sqrt(min(c / u_lutMaxMm, 1.0)) * N + 0.5) / (N + 1.0), 0.5)).r;   // sqrt-spaced table
    vec4 s = jb_paperSurface(v_docPx);
    float crush = jb_bilinear(u_crush, v_layerPx).r;
    float T = u_toothMm;
    float P = v_i4.w;
    float hc = jb_paperCoarseHeight(v_docPx);
    // Plateaus: most of a sheet's surface lies near the top of the tooth and the pits are narrow, so a light
    // touch already greys much of it and only the pits stay white (the soft grain of real graphite). The
    // paper maps are rank-uniform in height; this reshapes depth below the top as (1 − h)^k.
    float hRaw = clamp(s.z - u_conform * (hc - u_paperHeightMean), 0.0, 1.0);
    float depth = pow(1.0 - hRaw, u_plateau);
    float h = 1.0 - depth - crush;
    float sigmaH = sqrt(max(s.w - s.z * s.z, 0.0)) * u_plateau * pow(max(1.0 - hRaw, 1e-3), u_plateau - 1.0);
    float sigma = sqrt(sigmaH * sigmaH + u_leadSoft * u_leadSoft) * T;
    float mu = (a - (1.0 - h)) * T;
    float slide = v_i3.w;

    // Piling: only where the lead really bites; directional and clumped as the pressure rises.
    float e = jb_expectPos(mu, sigma);
    vec2 grad = s.xy * u_pxPerMm * T;            // physical slope of the tooth (mm per mm)
    float dirW = clamp(1.0 + u_dirStrength * smoothstep(0.15, 0.85, P) * dot(grad, v_i4.xy), 0.0, 3.0);
    float clump = max(0.0, 1.0 + u_clump * smoothstep(0.25, 1.0, P) * (hc - u_paperHeightMean) * 4.0);
    float work = T * pow(e / T, u_transferExp) * slide * dirW * clump;
    float crushWork = jb_expectPos(mu - u_crushStart, sigma) * slide;

    // Dusting: wherever the lead rubs (in proportion to how hard it rubs there), settling evenly on a
    // slightly softened tooth; the pits are shielded.
    float hs = clamp(jb_paperRead(v_docPx, 0, false, 2.0).z - u_conform * (hc - u_paperHeightMean), 0.0, 1.0);
    float dsoft = pow(1.0 - hs, u_plateau) + 0.6 * crush;
    float exposure = 0.35 + 0.65 * (1.0 - smoothstep(0.1, 0.55, dsoft));   // pits are shielded, not sealed: light-grey specks
    float dust = u_dustRate * slide * clamp(a, 0.0, 1.5) * exposure;

    float contact = jb_probPos(mu, sigma);
    o_delta = vec4(work, crushWork, dust, contact * slide);
}
