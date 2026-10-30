#version 300 es
// Stamp deposition: R = cap * coverage, A = coverage; fixed-function over blending.
precision highp float;

#include "jb_contact.glsl"

layout(location = 0) out vec4 o_color;
layout(location = 1) out vec4 o_colorMoment;
flat in vec4 v_color;
uniform vec3 u_stampBaseColor;

void main() {
    float d = jb_contactCoverage();
    float capCoverage = v_cap * d;
    o_color = vec4(capCoverage, 0.0, 0.0, d);
    vec3 ink = v_color.a > 0.5 ? v_color.rgb : u_stampBaseColor;
    // Both outputs blend with UN-CAPPED coverage d; original R remains the final opacity.
    o_colorMoment = vec4(ink * capCoverage, d);
}
