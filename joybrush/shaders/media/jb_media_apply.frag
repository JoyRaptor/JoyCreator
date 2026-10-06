#version 300 es
// jb_media_apply.frag — once per frame over the dirty rectangle: turn the dry stroke delta into deposit,
// paper crush and smear. Reads the current state, writes the next (ping-pong, three targets).
//
//   deposit:  dV/dW = k · (cap − V)   ⇒   V' = cap − (cap − V)·exp(−k·W)   (the tooth fills up; heavy
//             graphite stops taking more, which is why it goes smooth and shiny)
//   pigment:  every mm of deposit adds K·X and S·X of the stick's pigment (Kubelka–Munk optics)
//   crush:    the tooth is flattened by hard pressure and stays flattened (paper state, shared)
//   smear:    soft sticks drag a little of what is already down along the stroke
precision highp float;

#include "media/jb_media_common.glsl"

uniform sampler2D u_p0;          // K·X rgb, deposit volume V (mm)
uniform sampler2D u_p1;          // S·X rgb, openness
uniform sampler2D u_paperState;  // R crush (tooth units)
uniform sampler2D u_delta;       // from jb_dry_dab.frag
uniform vec2 u_targetSize;
uniform float u_capMm;           // how much dry deposit the tooth holds (mm)
uniform float u_abrasion;        // k, 1/mm²
uniform vec3 u_pigK;             // per mm of deposit
uniform vec3 u_pigS;
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
    vec4 keep0 = p0, keep1 = p1, keepS = ps;
    // A bad delta (NaN/inf from any dab) must never reach the layer: drop it for this pixel.
    if (any(isnan(dl)) || any(isinf(dl))) dl = vec4(0.0);

    float sw = clamp(dl.a * u_smear, 0.0, 0.06);   // subtle: a few percent per pass
    if (sw > 0.0) {
        p0 = mix(p0, jb_bilinear(u_p0, v_layerPx - u_smearPx), sw);
        p1.rgb = mix(p1.rgb, jb_bilinear(u_p1, v_layerPx - u_smearPx).rgb, sw);
    }

    float add = max(u_capMm - p0.a, 0.0) * (1.0 - exp(-u_abrasion * dl.r)) + dl.b;
    p0.rgb += u_pigK * add;
    p0.a += add;
    p1.rgb += u_pigS * add;
    ps.r = min(u_crushMax, ps.r + u_crushRate * dl.g);

    if (any(isnan(p0)) || any(isnan(p1)) || any(isnan(ps))) { p0 = keep0; p1 = keep1; ps = keepS; }
    o_p0 = p0;
    o_p1 = p1;
    o_paper = ps;
}
