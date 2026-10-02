#version 300 es
// Stamp deposition: R = cap * coverage, A = coverage; fixed-function over blending.
precision highp float;

#include "jb_contact.glsl"

out vec4 o_color;

void main() {
    float d = jb_contactCoverage();
    o_color = vec4(v_cap * d, 0.0, 0.0, d);
}
