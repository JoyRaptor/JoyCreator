#version 300 es
// jb_smudge_dab.frag — one smudge dab into the stroke buffer (JB-1.06, LEAD_RULINGS R47). SHARED by the phone and the PC Brush Lab.
//
// A smudge dab writes  lerp(canvas, carried, t)  with t = tip coverage x flow. As "over" that is a dab of the CARRIED colour
// with weight t, and the stroke buffer already accumulates dabs in order with fixed-function blending (ONE, ONE_MINUS_SRC_ALPHA),
// so this shader only says what one dab adds:   (carried.rgb * t, carried.a * t)   — premultiplied, with the ONE carried colour
// arriving per dab as an attribute (decided on the CPU by core/brush/SmudgeStroke, from the layer as it was when the stroke
// began). The commit (jb_commit.frag, u_smudge = 1) then lays the accumulated paint over the layer where the layer has paint.
// Must match Smudge.dab in the core: for an opaque carried colour the two are the same numbers.
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
in vec4 v_carried;

out vec4 o_color;

void main() {
    JbTip t = JbTip(v_radius, v_angle, u_aspect, u_corner, u_taper, u_hardness, u_minPx);
    float w = jb_tipCoverage(v_offset, t) * v_flow;
    o_color = v_carried * w;
}
