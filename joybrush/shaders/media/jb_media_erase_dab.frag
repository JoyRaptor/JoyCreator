#version 300 es
// jb_media_erase_dab.frag — see jb_media_erase_dab.vert.
precision highp float;
in vec2 v_unit;
flat in float v_strength;
flat in float v_feather;
out vec4 o_cover;
void main() {
    float d = length(v_unit);
    float a = (1.0 - smoothstep(1.0 - v_feather, 1.0, d)) * v_strength;
    o_cover = vec4(a);
}
