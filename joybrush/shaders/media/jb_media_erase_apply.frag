#version 300 es
// jb_media_erase_apply.frag — the eraser on a media layer (the Lead, step-4 check 1): what the coverage says is taken
// out of the layer's STATE, so the next look render agrees with it. Paint (both pigment planes), graphite flakes and
// the water with what it carries all go in proportion; the paper's crush stays (a pressed groove survives a rubber).
// u_water = 0: the state pass (p0, p1, paper); 1: the water pass (w0, w1 written to o_p0, o_p1).
precision highp float;
in vec2 v_layerPx;
uniform vec2 u_targetSize;
uniform sampler2D u_a;       // p0, or w0 in the water pass
uniform sampler2D u_b;       // p1, or w1
uniform sampler2D u_c;       // paper state (state pass only)
uniform sampler2D u_cover;   // jb_media_erase_dab.frag
uniform int u_water;
layout(location = 0) out vec4 o_a;
layout(location = 1) out vec4 o_b;
layout(location = 2) out vec4 o_c;
void main() {
    vec2 uv = v_layerPx / u_targetSize;
    float keep = 1.0 - clamp(texture(u_cover, uv).r, 0.0, 1.0);
    vec4 a = texture(u_a, uv);
    vec4 b = texture(u_b, uv);
    if (u_water == 1) {
        o_a = vec4(a.x * keep, a.y * keep, a.z, a.w * keep);   // water depth, saturation, age kept, outflow
        o_b = b * keep;                                          // pigment still in the water
        o_c = vec4(0.0);
        return;
    }
    vec4 ps = texture(u_c, uv);
    o_a = a * keep;                                              // K·X and the paint volume
    o_b = vec4(b.rgb * keep, b.a);                               // S·X; openness is the surface's, kept
    o_c = vec4(ps.r, ps.g * keep, ps.b * keep, ps.a);            // crush stays; graphite volume and reflectance go
}
