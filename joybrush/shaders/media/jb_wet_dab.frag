#version 300 es
// jb_wet_dab.frag — what one wet dab exchanges with the paper, added (signed) into the brush-input
// buffers that jb_wet_update.frag consumes. Blend ONE, ONE: dabs batch with no read-back.
//
// Exchange follows wetness: a loaded brush on dry paper gives water; a drier brush pressed into a wet
// wash takes water (and the pigment in it) back, which is how a thirsty brush lifts. A nearly dry brush
// only touches the tooth tops (dry brush), and the bristles leave faint streaks along the drag.
precision highp float;
precision highp int;

#include "media/jb_media_common.glsl"

uniform sampler2D u_w0;         // current water (w, s)
uniform sampler2D u_paperBake;  // .b = height
uniform vec2 u_targetSize;
uniform float u_fullMm;         // a puddle this deep counts as "as wet as a full brush"

in vec2 v_q;
in vec2 v_docPx;
in vec2 v_layerPx;
flat in vec4 v_i1;
flat in vec4 v_i2;
flat in vec4 v_i3;

layout(location = 0) out vec4 o_in0;   // Δw, ΔK rgb
layout(location = 1) out vec4 o_in1;   // ΔS, solubility weight, -, -

void main() {
    vec2 r = v_i1.xy;
    vec2 e = v_q / r;
    // Hair edges are never a perfect ellipse: the outline wanders a little, fixed in document space so
    // overlapping dabs agree and the stroke's side is one ragged line, not a row of bumps.
    float rag = jb_vnoise2(v_docPx / 11.0, 5u) * 0.65 + jb_vnoise2(v_docPx / 4.0, 6u) * 0.35;
    float d = length(e) + 0.09 * (rag - 0.5) * smoothstep(0.6, 1.0, length(e));
    if (d > 1.0) discard;
    // A loaded brush leaves a puddle with a definite edge (surface tension), not a soft airbrush ramp.
    float press = 1.0 - smoothstep(0.9, 1.0, d);
    // Hairs across the brush (fixed per brush): coarse clumps plus single hairs, smooth across the width.
    float across = v_q.y / max(r.y, 1e-3);
    uint seed = uint(v_i3.w) * 7u + 1u;
    float hair = 0.65 * jb_vnoise1(across * 3.5, seed) + 0.35 * jb_vnoise1(across * 11.0, seed + 3u);
    // Loaded: faint streaks of more or less paint along the stroke. Drying: separate runs of hair.
    float streak = mix(0.9 + 0.2 * hair, 0.45 + 0.55 * hair, v_i3.y);
    ivec2 cell = ivec2(v_layerPx);
    float h = texelFetch(u_paperBake, cell, 0).b;
    // Dry brush: the less water, the more only the tooth tops are reached, and each run of hair reaches
    // its own depth, so the paper shows through in streaks along the drag rather than in dots.
    float reach = mix(1.15, 0.38, v_i3.y);
    float tooth = smoothstep(1.0 - reach - 0.07, 1.0 - reach + 0.07, h + (hair - 0.5) * 1.1 * v_i3.y);
    float contact = press * streak * tooth;

    vec4 water = texelFetch(u_w0, cell, 0);
    float paperWet = clamp(water.r / u_fullMm, 0.0, 1.5);
    float give = v_i1.z * v_i3.x * contact * (v_i1.w - paperWet);   // signed exchange
    vec3 dK = give > 0.0 ? v_i2.rgb * give : vec3(0.0);
    float dS = give > 0.0 ? v_i2.a * give : 0.0;
    o_in0 = vec4(give, dK);
    o_in1 = vec4(dS, v_i3.z * dot(dK, vec3(1.0)), 0.0, 0.0);
}
