#version 300 es
// jb_dab.vert — draws brush dabs, one instanced quad each, into ONE stroke-buffer tile (JB-0.07).
// Shared by the phone and the PC Brush Lab. Tile texel (0,0) = the tile's top-left document pixel.
precision highp float;

layout(location = 0) in vec2 a_corner;   // unit quad corner, -1..1
layout(location = 1) in vec4 a_dab;      // x, y, radius (document px), angle (radians)
layout(location = 2) in vec2 a_dab2;     // flow, cap
layout(location = 3) in vec4 a_carried;  // smudge only (JB-1.06): the ONE carried colour this dab paints with, premultiplied. A stamp brush never enables it.

uniform vec2 u_tileOrigin;               // document px of the tile's first texel
uniform float u_tileSize;                // the TARGET's size in texels
// Target texels per document px (VECTOR_RENDER_DESIGN §8, S2): 1 for the live stroke and the document-resolution look,
// 2^k for the sharp levels. Unset reads 0 and means 1, so every caller that never sets it draws exactly as before.
uniform float u_scale;
// Shared uniforms already set by StampPass; no new uniform names or default state.
uniform float u_taper;
uniform float u_minPx;

out vec2 v_offset;                       // this fragment's offset from the dab centre, px
out vec2 v_dabCentre;                   // the dab centre in document px (the paper grain is sampled in canvas space)
out float v_radius;
out float v_angle;
out float v_flow;
out float v_cap;
out vec4 v_carried;
layout(location = 4) in vec2 a_travel;   // document-space unit travel, zero at a tap/dwell
flat out vec2 v_travel;
// Live contact attributes are appended; disabled attributes preserve legacy callers.
layout(location = 5) in vec4 a_contact; // aspect, hardness, tip depth, paper depth
layout(location = 6) in vec4 a_pen;     // anchor, tilt amount, lean x/y
layout(location = 7) in float a_live;
flat out vec4 v_contact;
flat out vec4 v_pen;
flat out float v_live;
layout(location = 8) in vec2 a_fade;
flat out vec2 v_fade;
layout(location = 9) in vec4 a_quad01;
layout(location = 10) in vec4 a_quad23;
layout(location = 11) in vec4 a_quadMeta; // roundness, normalization, profile mix, kind
flat out vec4 v_quad01;
flat out vec4 v_quad23;
flat out vec4 v_quadMeta;
layout(location = 12) in vec4 a_color; // resolved straight sRGB, enabled
flat out vec4 v_color;
layout(location = 13) in vec4 a_sample; // index+1, native width, native height, minPx
flat out vec4 v_sample;

void main() {
    // Half-size that contains the tip at any rotation, plus an antialiasing margin
    // (must match TipMath.extent in the core).
    // The margin is 2 TARGET px (2 / s doc px) at every scale; at s = 1, 2.0 / 1.0 and * 1.0 are exact, so the quad and
    // its position are bit-identical to before. Everything after this (v_offset, the tip, the grain) stays in document px:
    // fwidth then makes the antialiasing band one target px (S3), and the paper is sampled where the paper is (S4).
    float s = u_scale > 0.0 ? u_scale : 1.0;
    float ext = a_dab.z * (1.4143 + (a_live > 0.5 ? abs(a_pen.x) : 0.0)) + 2.0 / s;
    if (a_sample.x > 0.5 && a_sample.y > 0.0 && a_sample.z > 0.0) {
        float a = clamp(a_contact.x, -1.0, 1.0);
        vec2 axes = max(a_dab.z * vec2(a > 0.0 ? 1.0-a : 1.0, a < 0.0 ? 1.0+a : 1.0), vec2(max(a_sample.w, 0.5)*0.5));
        vec2 support = (a_sample.yz + 1.0) / max(a_sample.y, a_sample.z);
        ext = max(a_dab.z * 1.4143, length(axes * support)) + a_dab.z * (a_live > 0.5 ? abs(a_pen.x) : 0.0) + 2.0 / s;
    }
    // D10 CANDIDATE ONLY, NOT GPU-VALIDATED. A canonical modern unit circle has
    // radius r in both axes, so the historical sqrt(2)*r quad shades empty corners.
    // All excluded shapes retain the preceding extent expression without alteration.
    bool canonicalCircle = a_quadMeta.w == 5.0 && a_quadMeta.y == 1.0 &&
        all(equal(a_quad01, vec4(-1.0, -1.0, 1.0, -1.0))) &&
        all(equal(a_quad23, vec4(1.0, 1.0, -1.0, 1.0)));
    bool tightCircle = canonicalCircle && a_live > 0.5 && a_contact.x == 0.0 &&
        a_pen.x == 0.0 && u_taper == 0.0 && a_sample.x <= 0.5 &&
        a_dab.z >= 256.0 && max(u_minPx, 0.5) * 0.5 <= a_dab.z;
    if (tightCircle) {
        // fwidth band needs <=1 target px for a unit circle; retain2 plus rounding.
        // Profile code floors aa at1e-4 even at deep zoom, so do not shrink below
        // the corresponding document-space halo when scale becomes very large.
        ext = a_dab.z + 2.0 / s + a_dab.z * 1e-4;
    }
    vec2 off = a_corner * ext;
    vec2 p = a_dab.xy + off;
    gl_Position = vec4((p - u_tileOrigin) * s / u_tileSize * 2.0 - 1.0, 0.0, 1.0);
    v_offset = off;
    v_dabCentre = a_dab.xy;
    v_radius = a_dab.z;
    v_angle = a_dab.w;
    v_flow = a_dab2.x;
    v_cap = a_dab2.y;
    v_carried = a_carried;
    v_travel = a_travel;
    v_contact = a_contact;
    v_pen = a_pen;
    v_live = a_live;
    v_fade = a_fade;
    v_quad01 = a_quad01;
    v_quad23 = a_quad23;
    v_quadMeta = a_quadMeta;
    v_color = a_color;
    v_sample = a_sample;
}
