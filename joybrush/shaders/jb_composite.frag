#version 300 es
// jb_composite.frag — one layer tile blended over the stack beneath it, with any of the 27 modes (JB-2.20b).
// SHARED by the phone and the PC Brush Lab. Premultiplied RGBA in and out. Blending is OFF for this draw:
// the result REPLACES the pixel, because the backdrop is read here, from a copy, not by the blend unit.
//
// Must match Blend.apply (core/render/Blend.kt) — the CPU renderer every export goes through:
//     co = sa(1 - da)Cs + sa*da*B(Cb, Cs) + (1 - sa)da*Cb      (premultiplied out)
//     a  = sa + da(1 - sa)
// with Cs / Cb the STRAIGHT source / backdrop colour and B = the Studio's blendPix (jb_blend.glsl,
// GENERATED from BlendModes.java — R23). ERASE_BELOW is destination-out on all four channels.
precision highp float;

#include "jb_blend.glsl"

uniform sampler2D u_layer;      // the layer's tile, premultiplied RGBA8
uniform sampler2D u_backdrop;   // a copy of the stack so far, the size of the target: texel = pixel
uniform float u_layerOpacity;
uniform float u_mode;           // the Studio's mode code 0..25; -1 = ERASE_BELOW (no Studio code)

in vec2 v_uv;
out vec4 o_color;

void main() {
    vec4 s = texture(u_layer, v_uv) * u_layerOpacity;
    vec4 d = texelFetch(u_backdrop, ivec2(gl_FragCoord.xy), 0);
    float sa = s.a;
    float da = d.a;
    // Nothing to add here: leave the backdrop's bits alone rather than round-tripping them through
    // an un-premultiply and a premultiply (which is not exact in 8 bits).
    if (sa <= 0.0) { o_color = d; return; }
    if (u_mode < -0.5) { o_color = d * (1.0 - sa); return; }   // ERASE_BELOW

    // Zero alpha has no colour to divide by (0/0 is NaN): a transparent pixel is a pixel of black.
    vec3 cs = s.rgb / sa;
    vec3 cb = da > 0.0 ? d.rgb / da : vec3(0.0);
    vec3 bl = clamp(blendPix(cb, cs, u_mode), 0.0, 1.0);
    vec3 co = sa * (1.0 - da) * cs + sa * da * bl + (1.0 - sa) * da * cb;
    o_color = vec4(co, sa + da * (1.0 - sa));
}
