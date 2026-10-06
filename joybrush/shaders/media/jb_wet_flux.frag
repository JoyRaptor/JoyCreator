#version 300 es
// jb_wet_flux.frag — surface water, step 1 of 2: how much water leaves each cell toward each neighbour.
// A virtual-pipe shallow-water model (NOT lattice Boltzmann: MEDIA_ENGINE_PLAN §1.2 patent guard).
// The water surface is paper relief + water depth, so water runs downhill along the paper's own trenches
// and pools in its valleys. Viscosity is how much of last step's flow survives.
//
// Pinning (surface tension at a wet front): water does not advance into DRY paper unless it is pushed
// harder than the front can hold. Damp paper (capillary water s) barely holds it, so a wash creeps into
// damp paper (wet-in-wet, blooms) and stops crisp on dry paper. The hold varies with the paper, so fronts
// are ragged like a real edge.
precision highp float;
precision highp int;

#include "media/jb_media_common.glsl"

uniform sampler2D u_w0;       // w (mm), s (0..1 capillary saturation), wet age, -
uniform sampler2D u_flux;     // outflow L, R, D, U (mm per second, per cell)
uniform sampler2D u_paperBake;// .b = height 0..1, .a = E[h²]
uniform sampler2D u_fluidBake;// absorbency, fibre direction (signed), capacity
uniform sampler2D u_waterBake;// R = paper height smoothed to fibre scale
uniform float u_filmMm;       // below this depth a film clings: viscous drag ∝ 1/depth², so thin water barely moves
uniform float u_dt;
uniform float u_toothMm;
uniform float u_flow;         // how hard a height difference pushes water (1/s)
uniform float u_keep;         // fraction of flow kept from the last step (viscosity: watery → near 1)
uniform float u_pinMm;        // head (mm) needed to wet dry paper
uniform float u_dampS;        // saturation at which paper counts as damp (no pinning)
uniform float u_minMm;        // thinner than this is "dry"
uniform vec2 u_tilt;          // the paper tilted: gravity's drop per cell (mm), pointing DOWNHILL. 0 = flat.
                              // On the phone this comes from the gravity sensor; in the lab from the tilt pad.

out vec4 o_flux;

float H(ivec2 q, out float w, out float s) {
    vec4 a = texelFetch(u_w0, q, 0);
    w = a.r; s = a.g;
    return texelFetch(u_waterBake, q, 0).r * u_toothMm + w;
}

float pinHold(ivec2 q, float s) {
    // Ragged fronts follow the paper: sizing is never uniform (the fluid map's absorbency), and the
    // water surface already includes the tooth, so a front stalls against bumps and runs along trenches.
    // On damp paper the hold mostly breaks, but not evenly: the front fingers along thirstier fibres,
    // which is what draws a bloom's cauliflower edge.
    float absorb = texelFetch(u_fluidBake, q, 0).r;
    float dry = 1.0 - smoothstep(0.0, u_dampS, s);
    float dampHold = 0.35 * (1.0 - absorb) * (1.0 - absorb);
    return u_pinMm * (dry * (1.4 - 0.8 * absorb) + (1.0 - dry) * dampHold);
}

void main() {
    ivec2 c = ivec2(gl_FragCoord.xy);
    ivec2 size = textureSize(u_w0, 0);
    float w, s;
    float h0 = H(c, w, s);
    if (w <= 0.0) { o_flux = vec4(0.0); return; }
    vec4 prev = texelFetch(u_flux, c, 0);
    ivec2 nb[4] = ivec2[4](ivec2(-1, 0), ivec2(1, 0), ivec2(0, -1), ivec2(0, 1));
    vec4 f;
    for (int i = 0; i < 4; i++) {
        ivec2 q = clamp(c + nb[i], ivec2(0), size - 1);
        float wn, sn;
        float hn = H(q, wn, sn);
        // Tilt adds a slope under everything: water runs downhill, gathers on the low edge, and once it is
        // heavy enough it breaks through a dry front as a run.
        float dh = h0 - hn + dot(u_tilt, vec2(nb[i]));
        // Lubrication: a film's mobility grows with depth², so deep puddles level fast while the thin film
        // left on the tooth stays put instead of draining into the valleys.
        // The DONOR's depth decides: deep water feeds a thin edge (the outward flow that draws the dark
        // rim), while a thin film on the tooth stays put instead of draining into the valleys.
        float depth = w;
        float mobility = min(1.0, (depth / u_filmMm) * (depth / u_filmMm));
        float fi = max(0.0, prev[i] * u_keep + u_dt * u_flow * dh * mobility);
        if (wn < u_minMm) {
            float hold = pinHold(q, sn);
            fi *= smoothstep(hold, hold * 1.6 + 1.0e-4, dh);
        }
        if (q == c) fi = 0.0;
        f[i] = fi;
    }
    float out_ = (f.x + f.y + f.z + f.w) * u_dt;
    if (out_ > w) f *= w / out_;          // never move more water than the cell holds
    o_flux = f;
}
