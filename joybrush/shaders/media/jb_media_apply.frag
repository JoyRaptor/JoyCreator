#version 300 es
// jb_media_apply.frag — once per frame over the dirty rectangle: turn the dry stroke delta into deposit,
// paper crush and smear. Reads the current state, writes the next (ping-pong, three targets).
//
//   deposit:  dV/dW = k · (cap − V)   ⇒   V' = cap − (cap − V)·exp(−k·W)   (the tooth fills up; heavy
//             graphite stops taking more, which is why it goes smooth and shiny)
//   flakes:   dry media are opaque flakes, not a film: the paper state keeps the deposit volume V and
//             Σ V·(flake reflectance), and the display covers paper with flakes by area (see render)
//   crush:    the tooth is flattened by hard pressure and stays flattened (paper state, shared)
//   smear:    soft sticks drag a little of what is already down along the stroke
precision highp float;

#include "media/jb_media_common.glsl"

uniform sampler2D u_p0;          // K·X rgb, deposit volume V (mm)
uniform sampler2D u_p1;          // S·X rgb, openness
uniform sampler2D u_paperState;  // R crush (tooth units), G dry volume V (mm), B Σ V·flake reflectance
uniform sampler2D u_delta;       // from jb_dry_dab.frag
uniform vec2 u_targetSize;
uniform float u_capMm;           // how much dry deposit the tooth holds (mm)
uniform float u_abrasion;        // k, 1/mm²
uniform float u_flakeR;          // linear reflectance of this stick's flakes (graphite ~0.01–0.25 by grade)
uniform float u_crushRate;       // tooth units per mm² of crush work
uniform float u_crushMax;
uniform float u_smear;           // fraction moved per mm slid in contact
uniform vec2 u_smearPx;          // how far loose deposit is dragged (LAYER px, along travel)

in vec2 v_docPx;
in vec2 v_layerPx;
layout(location = 0) out vec4 o_p0;
layout(location = 1) out vec4 o_p1;
layout(location = 2) out vec4 o_paper;

void main() {
    vec2 uv = v_layerPx / u_targetSize;
    vec4 p0 = texture(u_p0, uv);
    vec4 p1 = texture(u_p1, uv);
    vec4 ps = texture(u_paperState, uv);
    vec4 dl = texture(u_delta, uv);
    vec4 keepS = ps;
    // A bad delta (NaN/inf from any dab) must never reach the layer: drop it for this pixel.
    if (any(isnan(dl)) || any(isinf(dl))) dl = vec4(0.0);

    // Going back over graphite pushes it (the owner, 2026-10-06): the rubbing lead drags loose graphite a
    // little along the stroke and softens it into its neighbours. That is half of why repeated light passes
    // read as a soft dusting rather than as dots. Rate ∝ how much the lead rubbed this pixel this frame.
    float sw = clamp(dl.a * u_smear, 0.0, 0.3);
    if (sw > 0.0) {
        vec2 up = jb_bilinear(u_paperState, v_layerPx - u_smearPx).gb;
        vec2 nb = 0.25 * (jb_bilinear(u_paperState, v_layerPx + vec2(1.5, 0.0)).gb + jb_bilinear(u_paperState, v_layerPx - vec2(1.5, 0.0)).gb
                        + jb_bilinear(u_paperState, v_layerPx + vec2(0.0, 1.5)).gb + jb_bilinear(u_paperState, v_layerPx - vec2(0.0, 1.5)).gb);
        ps.gb = mix(ps.gb, mix(nb, up, 0.6), sw);
    }

    // Pile saturates as the tooth fills; dust settles freely until the tooth is full.
    float room = max(u_capMm - ps.g, 0.0);
    float pile = room * (1.0 - exp(-u_abrasion * dl.r));
    float add = pile + min(dl.b, room - pile);
    ps.g += add;
    ps.b += add * u_flakeR;
    ps.r = min(u_crushMax, ps.r + u_crushRate * dl.g);

    if (any(isnan(ps))) ps = keepS;
    o_p0 = p0;
    o_p1 = p1;
    o_paper = ps;
}
