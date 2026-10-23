#version 300 es
// jb_media_erase_apply.frag — the eraser on a media layer (the Lead, step-4 check 1): what the coverage says is taken
// out of the layer's STATE, so the next look render agrees with it. Paint (both pigment planes), graphite flakes and
// the water with what it carries all go in proportion; the paper's crush stays (a pressed groove survives a rubber).
// u_water = 0: the state pass (p0, p1, paper); 1: the water pass (w0, w1 written to o_p0, o_p1).
precision highp float;
precision highp int;
#include "media/jb_media_common.glsl"
#include "media/jb_media_atlas.glsl"
in vec2 v_layerPx;
uniform vec2 u_targetSize;
uniform sampler2D u_a;       // p0, or w0 in the water pass
uniform sampler2D u_b;       // p1, or w1
uniform sampler2D u_c;       // paper state (state pass only)
uniform sampler2D u_cover;   // jb_media_erase_dab.frag
uniform int u_water;
uniform int u_wf;            // the water pass: pixels per water cell side (2: coarse water, a cell per 2 x 2 pixels)
layout(location = 0) out vec4 o_a;
layout(location = 1) out vec4 o_b;
layout(location = 2) out vec4 o_c;
void main() {
    jb_atlasBegin();
    ivec2 c = jb_cell();
    float keep = 1.0 - clamp(jb_fetch(u_cover, c).r, 0.0, 1.0);
    vec4 a = jb_fetch(u_a, c);
    vec4 b = jb_fetch(u_b, c);
    if (u_water == 1) {
        if (u_wf > 1) {
            // Coarse water: c is a water cell; it loses the share of its four pixels the rubber covered.
            float cover = 0.0;
            for (int j = 0; j < 2; j++) for (int i = 0; i < 2; i++) cover += clamp(jb_fetch(u_cover, c * 2 + ivec2(i, j)).r, 0.0, 1.0);
            keep = 1.0 - 0.25 * cover;
            a = jb_fetchS(u_a, c, 7);
            b = jb_fetchS(u_b, c, 7);
        }
        o_a = vec4(a.x * keep, a.y * keep, a.z, a.w * keep);   // water depth, saturation, age kept, outflow
        o_b = b * keep;                                          // pigment still in the water
        o_c = vec4(0.0);
        return;
    }
    vec4 ps = jb_fetch(u_c, c);
    o_a = a * keep;                                              // K·X and the paint volume
    o_b = vec4(b.rgb * keep, b.a);                               // S·X; openness is the surface's, kept
    o_c = vec4(ps.r, ps.g * keep, ps.b * keep, ps.a);            // crush stays; graphite volume and reflectance go
}
