#version 300 es
// jb_tuft.frag — one tuft footprint into the stroke buffer (R9 §3B).
//
// Same output contract as jb_dab.frag: R = cap·d, A = d, blended (ONE, ONE_MINUS_SRC_ALPHA), so the stroke buffer,
// the commit and the live preview are the stamp engine's own and nothing downstream knows a tuft drew it.
//
// The shape is TuftMath.distance's teardrop. On top of it, for a footprint (kind 0):
//   - bristle streaks, laid in STROKE space (lateral position across the brush × distance along the stroke), so every
//     footprint that covers a pixel agrees which streak it is in, and a dry stroke breaks into streaks along its length;
//   - the sweep bias: ink to the +normal side, the other side dries first (O10);
//   - splay: a ragged edge and a few wider splits (O11);
//   - the paper's tooth, anchored to the page, breaking the dry parts further.
// A plain mark (kind 1: spatter, stray hair) is the teardrop alone.
precision highp float;

#include "jb_grain_sample.glsl"

uniform float u_bristles;   // streaks across the full width
uniform float u_streakPx;   // how long a streak runs along the stroke before it changes, document px
uniform float u_tooth;      // 0..1: how much the paper breaks up the dry parts
uniform float u_seed;       // per stroke, so two strokes do not share one streak pattern

in vec2 v_pos;
flat in vec4 v_ab;
flat in vec4 v_r;
flat in vec4 v_look;
flat in float v_kind;

out vec4 o_color;

float jb_hash21(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float jb_vnoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    float a = jb_hash21(i);
    float b = jb_hash21(i + vec2(1.0, 0.0));
    float c = jb_hash21(i + vec2(0.0, 1.0));
    float d = jb_hash21(i + vec2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

// TuftMath.distance, transcribed: the uneven capsule, and the nearer circle when one sits inside the other.
float jb_tuftDistance(vec2 p, vec2 a, vec2 b, float ra, float rb) {
    vec2 d = b - a;
    float h = dot(d, d);
    float k0 = ra - rb;
    if (h <= k0 * k0 + 1e-6) return min(length(p - a) - ra, length(p - b) - rb);
    vec2 q0 = p - a;
    vec2 q = vec2(abs(q0.x * d.y - q0.y * d.x), dot(q0, d)) / h;
    vec2 c = vec2(sqrt(h - k0 * k0), k0);
    float k = c.x * q.y - c.y * q.x;
    float m = dot(c, q);
    float n = dot(q, q);
    if (k < 0.0) return sqrt(h * n) - ra;
    if (k > c.x) return sqrt(h * (n + 1.0 - 2.0 * q.y)) - rb;
    return m - ra;
}

void main() {
    vec2 a = v_ab.xy;
    vec2 b = v_ab.zw;
    float ra = v_r.x;
    float rb = v_r.y;
    float flow = v_r.z;
    float cap = v_r.w;
    float dry = v_look.x;
    float bias = v_look.y;
    float splay = v_look.z;
    float arc = v_look.w;

    // The page's tooth, read in uniform control flow (a texture read needs it for its derivatives).
    float h = 1.0;
    if (u_paperGrainPitchPx > 0.0) h = jb_paperGrainHeight(v_pos);

    float sd = jb_tuftDistance(v_pos, a, b, ra, rb);
    float d;
    if (v_kind > 0.5) {
        d = clamp(0.5 - sd, 0.0, 1.0);
    } else {
        vec2 axis = b - a;
        float len = length(axis);
        vec2 ax = len > 1e-4 ? axis / len : vec2(1.0, 0.0);
        vec2 n = vec2(-ax.y, ax.x);
        vec2 q = v_pos - a;
        float along = dot(q, ax);
        float t = clamp(along / max(len, 1e-3), 0.0, 1.0);
        float halfW = max(mix(ra, rb, t), 0.5);
        float y = clamp(dot(q, n) / halfW, -1.0, 1.0);
        float s = arc - along;                       // where on the stroke this paper point is

        // Splay: the edge goes ragged as the bristles part.
        float rag = jb_vnoise(vec2(s / max(2.0, ra * 0.8) + u_seed, y * 2.0 + 11.0)) - 0.5;
        sd += splay * ra * 0.35 * rag;
        float cov = clamp(0.5 - sd, 0.0, 1.0);

        // Sweep: the inside keeps its ink, the outside runs dry first.
        float side = bias * y;
        float dLocal = clamp(dry + max(-side, 0.0) * 0.9 - max(side, 0.0) * 0.6, 0.0, 1.0);

        // Bristle streaks: each streak has its own ink, and the drier the brush the fewer of them touch.
        float g = jb_vnoise(vec2((y * 0.5 + 0.5) * u_bristles, s / u_streakPx + u_seed * 0.37));
        float bristle = smoothstep(-0.03, 0.03, g - (dLocal * 1.1 - 0.08));

        // Splits: a few wider gaps across the brush while it is spread.
        float split = jb_vnoise(vec2(y * 3.0 + u_seed * 1.3, s / (u_streakPx * 3.0)));
        float gap = splay > 0.01 ? smoothstep(splay * 0.5 - 0.03, splay * 0.5 + 0.03, split) : 1.0;

        // Tooth: only the dry parts feel the paper; a loaded brush fills its valleys.
        float reach = u_tooth * dLocal;
        float tooth = mix(1.0, clamp((h - reach * 0.9) / 0.08 + 0.5, 0.0, 1.0), smoothstep(0.0, 0.15, reach));

        d = cov * bristle * gap * tooth;
    }
    d *= flow;
    o_color = vec4(cap * d, 0.0, 0.0, d);
}
