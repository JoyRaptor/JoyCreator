#version 300 es
// jb_media_copy.frag — copy the three state textures over a rectangle (the second half of a ping-pong
// update that only touched the dirty region).
precision highp float;
#ifndef JB_PAPER_ONLY
uniform sampler2D u_a;
uniform sampler2D u_b;
#endif
uniform sampler2D u_c;
uniform vec2 u_targetSize;
in vec2 v_docPx;
in vec2 v_layerPx;
#ifdef JB_PAPER_ONLY
layout(location = 0) out vec4 o_c;
#else
layout(location = 0) out vec4 o_a;
layout(location = 1) out vec4 o_b;
layout(location = 2) out vec4 o_c;
#endif
void main() {
    vec2 uv = v_layerPx / u_targetSize;
#ifndef JB_PAPER_ONLY
    o_a = texture(u_a, uv);
    o_b = texture(u_b, uv);
#endif
    o_c = texture(u_c, uv);
}
