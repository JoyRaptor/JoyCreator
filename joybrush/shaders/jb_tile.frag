#version 300 es
// jb_tile.frag — draws one layer tile on screen (JB-0.07). Premultiplied, blended with
// (ONE, ONE_MINUS_SRC_ALPHA) over the paper and the layers below.
precision highp float;

uniform sampler2D u_layer;
uniform float u_layerOpacity;

in vec2 v_uv;
out vec4 o_color;

void main() {
    o_color = texture(u_layer, v_uv) * u_layerOpacity;
}
