#version 300 es
// jb_dab.vert — draws brush dabs, one instanced quad each, into ONE stroke-buffer tile (JB-0.07).
// Shared by the phone and the PC Brush Lab. Tile texel (0,0) = the tile's top-left document pixel.
precision highp float;

layout(location = 0) in vec2 a_corner;   // unit quad corner, -1..1
layout(location = 1) in vec4 a_dab;      // x, y, radius (document px), angle (radians)
layout(location = 2) in vec2 a_dab2;     // flow, cap

uniform vec2 u_tileOrigin;               // document px of the tile's first texel
uniform float u_tileSize;

out vec2 v_offset;                       // this fragment's offset from the dab centre, px
out vec2 v_dabCentre;                   // the dab centre in document px (the paper grain is sampled in canvas space)
out float v_radius;
out float v_angle;
out float v_flow;
out float v_cap;

void main() {
    // Half-size that contains the tip at any rotation, plus an antialiasing margin
    // (must match TipMath.extent in the core).
    float ext = a_dab.z * 1.4143 + 2.0;
    vec2 off = a_corner * ext;
    vec2 p = a_dab.xy + off;
    gl_Position = vec4((p - u_tileOrigin) / u_tileSize * 2.0 - 1.0, 0.0, 1.0);
    v_offset = off;
    v_dabCentre = a_dab.xy;
    v_radius = a_dab.z;
    v_angle = a_dab.w;
    v_flow = a_dab2.x;
    v_cap = a_dab2.y;
}
