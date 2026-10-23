#version 300 es
// jb_media_copy.frag — copy the three state textures over a rectangle (the second half of a ping-pong
// update that only touched the dirty region).
precision highp float;
precision highp int;
#include "media/jb_media_common.glsl"
#include "media/jb_media_atlas.glsl"
uniform sampler2D u_a;
uniform sampler2D u_b;
uniform sampler2D u_c;
uniform vec2 u_targetSize;
in vec2 v_docPx;
in vec2 v_layerPx;
layout(location = 0) out vec4 o_a;
layout(location = 1) out vec4 o_b;
layout(location = 2) out vec4 o_c;
void main() {
    jb_atlasBegin();
    // A texel-centre read of the same cell (what texture(uv) at v_layerPx / size was), through the address function.
    ivec2 c = jb_cell();
    o_a = jb_fetch(u_a, c);
    o_b = jb_fetch(u_b, c);
    o_c = jb_fetch(u_c, c);
}
