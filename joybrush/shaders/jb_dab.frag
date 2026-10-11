#version 300 es
// Stamp deposition: R = cap * coverage, A = coverage; fixed-function over blending.
precision highp float;

#include "jb_contact.glsl"

out vec4 o_color;

// 0 (default, every existing brush): grain decides how much of each dab lands. 1: grain decides how much the paper can
// hold, so overlapping dabs converge to a grey set by the tooth and the pressure (the vector pencil).
uniform int u_grainCapacity;

void main() {
    if (u_grainCapacity == 1) {
        vec2 rt = jb_contactRateAndTooth();
        o_color = vec4(v_cap * rt.y * rt.x, 0.0, 0.0, rt.x);
        return;
    }
    float d = jb_contactCoverage();
    o_color = vec4(v_cap * d, 0.0, 0.0, d);
}
