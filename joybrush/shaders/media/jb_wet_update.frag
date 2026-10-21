#version 300 es
// jb_wet_update.frag — surface water, step 2 of 2, and everything the water does to the paper.
// Writes four targets: water (W0), pigment in the water (W1), pigment on the paper (P0, P1).
//
//   move      water and its pigment travel along last pass's fluxes (conservative upwind transport)
//   brush     the brush adds water + pigment, or a thirsty brush takes them back (signed input)
//   dry       water evaporates; thin edges dry fastest, so water (and pigment) flows outward to replace
//             it and piles pigment at the edge: watercolour's dark edge, with no edge-detection trick
//   soak      water soaks into the paper; staining pigment rides it into the fibres
//   wick      soaked water spreads through the fibres, leaving damp paper that unpins later washes
//   settle    pigment settles out of still water, faster into the paper's valleys (granulation)
//   lift      fresh water re-dissolves soluble pigment already on the paper
//   dry out   when the last water goes, everything still suspended settles where it is
precision highp float;
precision highp int;

#include "media/jb_media_common.glsl"
#include "media/jb_media_atlas.glsl"

uniform sampler2D u_w0;         // w (mm), s (0..1), wet time (s), -
uniform sampler2D u_w1;         // suspended K rgb, S
uniform sampler2D u_flux;       // outflow L, R, D, U (this step)
uniform sampler2D u_run;        // running water's outflow this step (mm/s), signed along x and y
uniform sampler2D u_p0;         // deposited K rgb, volume
uniform sampler2D u_p1;         // deposited S rgb, soluble fraction
uniform sampler2D u_paperBake;  // .b = height, .a = E[h²]
uniform sampler2D u_in0;        // brush: Δw (mm, signed), ΔK rgb
uniform sampler2D u_in1;        // brush: ΔS, solubility of the new pigment
uniform float u_dt;
uniform float u_minMm;
uniform float u_evap;           // mm/s
uniform float u_edgeEvap;       // extra drying at a thin edge (× evap)
uniform float u_dryAfterS;      // older water dries faster: from this wet age (sim s) ...
uniform float u_dryRampS;       // ... ramping in over this long ...
uniform float u_dryBoost;       // ... to evaporation × (1 + this); 0 = off
uniform float u_absorb;         // 1/s
uniform float u_capMm;          // water the paper holds when saturated (mm)
uniform float u_wick;           // capillary spread (cells²/s)
uniform float u_sDry;           // capillary drying (1/s)
uniform float u_settle;         // 1/s
uniform float u_gran;           // granulation
uniform float u_lift;           // 1/s
uniform float u_stainCarry;
uniform float u_heightMean;
uniform float u_useInput;       // 1 on the step that consumes the brush input
uniform float u_edgeDep;        // pigment drops out faster at the drying edge (contact-line deposition)
uniform float u_mingle;         // pigment spreading through connected water (cells²/s): wet-in-wet colour mixing
uniform sampler2D u_waterBake;  // R = paper height smoothed to fibre scale
uniform sampler2D u_fluidBake;  // absorbency, fibre direction, capacity
uniform float u_noBody;         // owner A/B (2026-10-18): 1 = watercolour leaves no paint body (no relief); unset 0 = as always

layout(location = 0) out vec4 o_w0;
layout(location = 1) out vec4 o_w1;
layout(location = 2) out vec4 o_p0;
layout(location = 3) out vec4 o_p1;

void main() {
    jb_atlasBegin();
    ivec2 c = jb_cell();
    ivec2 size = textureSize(u_w0, 0);
    vec4 a = jb_fetch(u_w0, c);
    vec4 pig = jb_fetch(u_w1, c);
    vec4 p0 = jb_fetch(u_p0, c);
    vec4 p1 = jb_fetch(u_p1, c);
    float w = a.r, s = a.g, age = a.b;

    // --- move: out through own fluxes, in through neighbours' fluxes pointing here
    vec4 fo = jb_fetch(u_flux, c);
    vec2 ro = jb_fetch(u_run, c).xy;
    float outW = (fo.x + fo.y + fo.z + fo.w + abs(ro.x) + abs(ro.y)) * u_dt;
    float keep = w > 0.0 ? max(0.0, 1.0 - outW / w) : 1.0;
    float inW = 0.0;
    vec4 inP = vec4(0.0);
    ivec2 nb[4] = ivec2[4](ivec2(-1, 0), ivec2(1, 0), ivec2(0, -1), ivec2(0, 1));
    int toMe[4] = int[4](1, 0, 3, 2);     // the neighbour's flux component that points at this cell
    for (int i = 0; i < 4; i++) {
        ivec2 q = c + nb[i];
        if (!jb_inside(q, size)) continue;
        // The neighbour's levelling flow toward this cell, plus its running water if it runs this way.
        float rq = max(0.0, -dot(jb_fetch(u_run, q).xy, vec2(nb[i])));
        float fq = (jb_fetch(u_flux, q)[toMe[i]] + rq) * u_dt;
        if (fq <= 0.0) continue;
        float wq = jb_fetch(u_w0, q).r;
        inW += fq;
        inP += jb_fetch(u_w1, q) * min(1.0, fq / max(wq, 1.0e-6));
    }
    // --- mingle: pigment drifts from rich water to lean water wherever the water connects (never into dry
    // paper). Pairwise and symmetric, so pigment is conserved. This is what turns two touching washes into
    // one soft, organic gradient instead of two flat shapes.
    vec4 mingle = vec4(0.0);
    if (a.r > u_minMm) {
        vec4 c0 = jb_fetch(u_w1, c) / a.r;
        for (int i = 0; i < 4; i++) {
            ivec2 q = jb_clampCell(c + nb[i], size);
            float wq = jb_fetch(u_w0, q).r;
            if (wq > u_minMm) mingle += (jb_fetch(u_w1, q) / wq - c0) * min(a.r, wq);
        }
    }
    w = w - outW + inW;
    pig = max(pig * keep + inP + u_mingle * u_dt * mingle, vec4(0.0));

    // --- brush (signed: a thirsty brush lifts water and the pigment in it)
    if (u_useInput > 0.5) {
        vec4 i0 = jb_fetch(u_in0, c);
        vec4 i1 = jb_fetch(u_in1, c);
        if (i0.r < 0.0) {
            float take = min(1.0, -i0.r / max(w, 1.0e-6));
            pig *= 1.0 - take;
            w = max(0.0, w + i0.r);
        } else if (i0.r > 0.0) {
            w += i0.r;
            if (w > u_minMm) age = 0.0;
        }
        pig += vec4(max(i0.gba, 0.0), max(i1.r, 0.0));
        // Remember how soluble the newest pigment is (graphite: 0, watercolour: ~1).
        float kNew = dot(max(i0.gba, 0.0), vec3(1.0));
        float kOld = dot(p0.rgb, vec3(1.0)) + dot(pig.rgb, vec3(1.0));
        if (kNew > 0.0) p1.a = mix(p1.a, clamp(i1.g / kNew, 0.0, 1.0), kNew / max(kNew + kOld, 1.0e-6));
    }

    bool wet = w > u_minMm;
    if (wet) age += u_dt;

    // --- dry: faster where the wet area is thin and near its edge
    // A cell with no water left (w == 0 here) is dry whatever its surroundings say: the ring below only feeds the edge
    // drying (evap), and with the edge factor >= 1 the water it would take away is none, so w stays 0 and nothing else
    // uses wetN / wMax / sizingVar on that path (the dry-out branch below). Skipping the ring changes no output bit.
    // The uniform tests are what make "edge factor >= 1, evaporation >= 0" true for every constant set.
    bool idle = w == 0.0 && u_minMm > 0.0 && u_evap >= 0.0 && u_edgeEvap >= 0.0 && u_dt >= 0.0;
    float wetN = 0.0, wMax = w;
    if (!idle) {
    for (int i = 0; i < 8; i++) {
        float ang = float(i) * 0.785398;
        ivec2 q = jb_clampCell(c + ivec2(round(3.0 * vec2(cos(ang), sin(ang)))), size);
        float wq = jb_fetch(u_w0, q).r;
        wetN += 0.125 * smoothstep(u_minMm, u_minMm * 6.0, wq);
        wMax = max(wMax, wq);
    }
    }
    // A rim needs water to flow to the edge. Paint laid almost dry (dry brush) dries where it touches.
    float rimWater = smoothstep(0.03, 0.12, wMax);
    // Uneven sizing and air: some spots dry a little sooner, so pigment migrates toward the slower spots,
    // the soft mottling of a real wash (from the paper's own absorbency map).
    float sizingVar = 1.0;
    if (!idle) sizingVar = 0.65 + 0.7 * jb_fetch(u_fluidBake, c).r;
    float evap = u_evap * u_dt * sizingVar * (1.0 + rimWater * u_edgeEvap * (1.0 - wetN));
    if (u_dryBoost > 0.0) evap *= 1.0 + u_dryBoost * smoothstep(u_dryAfterS, u_dryAfterS + max(u_dryRampS, 1.0e-3), age);
    float before = w;
    w = max(0.0, w - evap);

    // --- soak and wick
    float soak = min(w, u_absorb * u_dt * (1.0 - s) * u_capMm);
    w -= soak;
    vec4 stain = pig * min(1.0, soak / max(before, 1.0e-6)) * u_stainCarry;
    pig -= stain;
    float sL = jb_fetch(u_w0, jb_clampCell(c + ivec2(-1, 0), size)).g;
    float sR = jb_fetch(u_w0, jb_clampCell(c + ivec2(1, 0), size)).g;
    float sD = jb_fetch(u_w0, jb_clampCell(c + ivec2(0, -1), size)).g;
    float sU = jb_fetch(u_w0, jb_clampCell(c + ivec2(0, 1), size)).g;
    s = s + soak / u_capMm + u_wick * u_dt * (sL + sR + sD + sU - 4.0 * s);
    s = clamp(s - u_sDry * u_dt * s * (wet ? 0.15 : 1.0), 0.0, 1.0);

    // --- settle (into the valleys first) or dry out
    vec4 dep = stain;
    if (w <= u_minMm) {
        dep += pig;                      // the last of the water leaves its pigment where it is
        pig = vec4(0.0);
        w = 0.0;
    } else {
        // Granulation: pigment grains lodge in the paper's FINE tooth (height minus its fibre-scale mean),
        // not in the big bumps, which only shape where the water pools.
        float h = jb_fetch(u_paperBake, c).b;   // (fetched only where it is used: dry cells never reach here)
        float hs = jb_fetch(u_waterBake, c).r;
        float g = clamp(1.0 + u_gran * (hs - h) * 5.0, 0.3, 2.5);
        float edge = 1.0 + rimWater * (u_edgeDep * (1.0 - wetN) + u_edgeDep * 0.5 * smoothstep(0.06, 0.01, w));
        // Pigment reaches the fibres faster the thinner the film over them (it has less far to sink), so most
        // of it is down before the last water gathers in pockets; what is left still marks the edge.
        float k = 1.0 - exp(-u_settle * g * edge * u_dt * min(20.0, 0.15 / max(w, 0.004)));
        dep += pig * k;
        pig *= 1.0 - k;
        // --- lift: water re-dissolves soluble pigment lying under it
        float l = u_lift * u_dt * p1.a * smoothstep(u_minMm, u_minMm * 4.0, w);
        vec3 lk = p0.rgb * l;
        vec3 ls = p1.rgb * l;
        p0.rgb -= lk; p1.rgb -= ls;
        pig += vec4(lk, dot(ls, vec3(1.0 / 3.0)));
    }
    p0.rgb += dep.rgb;
    p1.rgb += vec3(dep.a);
    p0.a += dot(dep.rgb, vec3(1.0)) * 0.0004 * (1.0 - u_noBody);   // pigment film volume (tiny; paste media add body)

    o_w0 = vec4(w, s, age, outW / max(u_dt, 1.0e-6));
    o_w1 = max(pig, vec4(0.0));
    o_p0 = p0;
    o_p1 = p1;
}
