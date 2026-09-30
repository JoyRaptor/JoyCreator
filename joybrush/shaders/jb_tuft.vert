#version 300 es
// jb_tuft.vert — the tuft engine's footprints (R9 §3B), one instanced quad each, into ONE stroke-buffer tile.
// A footprint is a teardrop: belly circle at A (radius ra) joined to tip circle at B (radius rb). The quad is the
// teardrop's bounds plus an antialiasing margin (must match TuftMath.bounds / MARGIN_PX in the core).
precision highp float;

layout(location = 0) in vec2 a_corner;   // unit quad corner, -1..1
layout(location = 1) in vec4 a_ab;       // A.x, A.y, B.x, B.y (document px)
layout(location = 2) in vec4 a_r;        // ra, rb, flow, cap
layout(location = 3) in vec4 a_look;     // dry, bias, splay, arc
layout(location = 4) in vec4 a_kind;     // kind (0 footprint, 1 plain), unused ×3

uniform vec2 u_tileOrigin;
uniform float u_tileSize;

out vec2 v_pos;                          // this fragment in document px
flat out vec4 v_ab;
flat out vec4 v_r;
flat out vec4 v_look;
flat out float v_kind;

void main() {
    vec2 a = a_ab.xy;
    vec2 b = a_ab.zw;
    vec2 lo = min(a - vec2(a_r.x), b - vec2(a_r.y)) - vec2(2.0);
    vec2 hi = max(a + vec2(a_r.x), b + vec2(a_r.y)) + vec2(2.0);
    vec2 p = mix(lo, hi, a_corner * 0.5 + 0.5);
    gl_Position = vec4((p - u_tileOrigin) / u_tileSize * 2.0 - 1.0, 0.0, 1.0);
    v_pos = p;
    v_ab = a_ab;
    v_r = a_r;
    v_look = a_look;
    v_kind = a_kind.x;
}
