#version 300 es
precision highp float;
precision highp sampler2DArray;
uniform sampler2DArray u_dabs;
in vec2 v_uv;
flat in float v_sample;
flat in vec3 v_bandAlpha;
out vec4 o_color;
void main() {
    float shape=texture(u_dabs,vec3(v_uv,v_sample)).r;
    // ONE, ONE_MINUS_SRC_COLOR: independent source-over for each of the three coverage bands.
    o_color=vec4(v_bandAlpha*shape,0.0);
}
