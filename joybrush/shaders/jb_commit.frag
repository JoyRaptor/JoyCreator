#version 300 es
// jb_commit.frag — a layer tile with the stroke applied (JB-0.07). Premultiplied RGBA.
// Used twice with the same maths: to COMMIT a stroke into a new tile (u_layerOpacity = 1,
// blending off), and to PREVIEW the stroke live on screen (u_layerOpacity = the layer's opacity,
// premultiplied-over blending). Must match RefCanvas.endStroke in the core.
precision highp float;

uniform sampler2D u_layer;     // premultiplied RGBA tile (a 1×1 transparent texture if none)
uniform sampler2D u_stroke;    // stroke buffer, R channel
uniform vec3 u_color;          // brush colour, straight (not premultiplied)
uniform float u_strokeScale;   // opacity for BUILD_UP strokes, 1 for WASH
uniform int u_erase;           // 1 = erase
uniform float u_layerOpacity;

in vec2 v_uv;
out vec4 o_color;

void main() {
    vec4 dst = texture(u_layer, v_uv);
    float a = texture(u_stroke, v_uv).r * u_strokeScale;
    vec4 outc = (u_erase == 1) ? dst * (1.0 - a) : vec4(u_color * a, a) + dst * (1.0 - a);
    o_color = outc * u_layerOpacity;
}
