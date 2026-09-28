#version 300 es
// jb_dab.frag — one dab into the stroke buffer (JB-0.07).
//
// Output R = cap·d, A = d, where d = tip coverage × flow. With blending set to
// (ONE, ONE_MINUS_SRC_ALPHA) the buffer does  s' = cap·d + s·(1 − d)  — so a WASH stroke
// (cap = opacity) approaches its opacity and never passes it, and a BUILD_UP stroke (cap = 1)
// accumulates freely. Fixed-function blending: dabs batch with no read-back.
// Must match RefCanvas.stamp in the core.
precision highp float;

#include "jb_tip.glsl"

uniform float u_aspect;
uniform float u_corner;
uniform float u_taper;
uniform float u_hardness;
uniform float u_minPx;

in vec2 v_offset;
in float v_radius;
in float v_angle;
in float v_flow;
in float v_cap;

out vec4 o_color;

void main() {
    JbTip t = JbTip(v_radius, v_angle, u_aspect, u_corner, u_taper, u_hardness, u_minPx);
    float d = jb_tipCoverage(v_offset, t) * v_flow;
    o_color = vec4(v_cap * d, 0.0, 0.0, d);
}
