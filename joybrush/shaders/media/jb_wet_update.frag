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
const float JB_DAMP_S = 0.01;   // below this, paper with no standing water is dry (MediaAtlasWater.DAMP_S)
uniform float u_ageScale;       // real seconds per simulated second this step (a slow phone runs the water slower than the
                                // clock): wet age counts real time, so "older water dries faster" means older on the clock
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
uniform float u_fullMm;         // a puddle this deep counts as "as wet as a full brush" (jb_wet_dab.frag)
uniform float u_inputCap;       // 1: a frame's dabs together wet the paper no further than dab after dab would (the wet sheet)
uniform float u_capSlack;       // how far past that a frame's dabs may wet it, as a share, on average (0 = a perfectly even wash)
uniform float u_capSeed;        // moves the slack's pattern each frame
uniform float u_rimCells;       // > 0: water flowing into this many cells along the texture's edge thins out and is gone at the
                                //      edge itself (the wet sheet's rim: see main); 0 = the edge is a wall, as always
uniform float u_edgeDep;        // pigment drops out faster at the drying edge (contact-line deposition)
uniform float u_mingle;         // pigment spreading through connected water (cells²/s): wet-in-wet colour mixing
uniform sampler2D u_waterBake;  // R = paper height smoothed to fibre scale; (fibre build) G = how far the cell lies on a fibre
uniform sampler2D u_fluidBake;  // absorbency, fibre direction, capacity
#ifdef JB_WET_FIBRES
// The paper's fibres and pores (PaperFibres, owner 2026-11-17). Built in only while that switch is on: without the define
// this file is the water as it always was, to the byte.
uniform float u_pore;           // 0..1: how far each spot soaks up what its own pores hold (the fluid map's capacity)
                                // rather than one capacity for the whole sheet
uniform float u_bleed;          // the paper's capillary pull (jb_wet_flux.frag); 0 on a sheet sized shut: nothing is ever marked
uniform float u_bleedFilmMm;    // water the paper drew in is film while it is thinner than this (the bleed, jb_wet_flux.frag)
uniform float u_bleedThinMm;    // any water thinner than this is film: thirsty paper takes it into its surface at once
uniform float u_bleedSieve;     // of the pigment in a film's water that the paper draws on, this share goes with it at each cell
uniform float u_bleedEvap;      // the film dries at this share of the pace of water standing on the paper
uniform float u_bleedMingle;    // colour spreads through the film along a fibre this many times as readily as through a wash
uniform float u_bleedSoak;      // how fast the paper under the bleed's film drinks it, as a share of standing water's pace
uniform float u_bleedCarry;     // how readily that water keeps its pigment as it travels: the film's settling is this
                                // share of standing water's (1 = as standing water)
#endif
uniform float u_noBody;         // owner A/B (2026-10-18): 1 = watercolour leaves no paint body (no relief); unset 0 = as always

layout(location = 0) out vec4 o_w0;
layout(location = 1) out vec4 o_w1;
#ifdef JB_WET_COARSE
// Coarse water (tasks/joybrush/specs/COARSE_WATER_20261021.md): this pass runs per 2 x 2 pixel cell and writes the water;
// the paint is changed by jb_wet_paint.frag from the water as it stood mid-step, written here for it (jb_wet_coarse.glsl).
#include "media/jb_wet_coarse.glsl"
layout(location = 2) out vec4 o_mid0;
layout(location = 3) out vec4 o_mid1;
const highp int JB_W_SHIFT = 7;
#else
layout(location = 2) out vec4 o_p0;
layout(location = 3) out vec4 o_p1;
const highp int JB_W_SHIFT = 8;
#endif
// The water's fields at the water's resolution (every one of them below; the paint and paper are read where they live).
vec4 wf(sampler2D t, highp ivec2 q) { return jb_fetchS(t, q, JB_W_SHIFT); }

#ifndef JB_WET_COARSE
// Smooth noise 0..1 over the cells, one bump about every 40: where the cap's slack is given and where it is not.
float jb_capHash(vec2 q) { return fract(sin(dot(q, vec2(127.1, 311.7))) * 43758.5453); }
float jb_capNoise(vec2 p) {
    vec2 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(jb_capHash(i), jb_capHash(i + vec2(1.0, 0.0)), f.x),
               mix(jb_capHash(i + vec2(0.0, 1.0)), jb_capHash(i + vec2(1.0, 1.0)), f.x), f.y);
}
#endif

#ifdef JB_WET_FIBRES
// How far a cell's water (its W0) is film: water IN the paper's surface rather than standing on it, which follows the
// fibres' rules below. Two kinds are: water the paper drew in beyond a wet edge (MARKED by a negative wet age: see "the
// mark" in main) while it is thinner than u_bleedFilmMm; and any water thinner than u_bleedThinMm, such as the faint rim
// a brush leaves round its stroke, which thirsty paper takes in at once. Water standing deeper is a wash, and behaves as
// it always did. On a sheet sized shut (u_bleed 0) nothing is film.
float jb_filmOf(float w, bool marked) {
    float cap = marked ? u_bleedFilmMm : u_bleedThinMm;
    return u_bleed > 0.0 ? 1.0 - smoothstep(0.5 * cap, cap, w) : 0.0;
}
float jb_film(vec4 st) { return jb_filmOf(st.r, st.b < 0.0); }
#endif

void main() {
    jb_atlasBegin();
    ivec2 c = jb_cell();
    ivec2 size = textureSize(u_w0, 0);
    vec4 a = wf(u_w0, c);
    vec4 pig = wf(u_w1, c);
#ifndef JB_WET_COARSE
    vec4 p0 = jb_fetch(u_p0, c);
    vec4 p1 = jb_fetch(u_p1, c);
#endif
    float w = a.r, s = a.g, age = a.b;

    // --- move: out through own fluxes, in through neighbours' fluxes pointing here
    vec4 fo = wf(u_flux, c);
    vec2 ro = wf(u_run, c).xy;
    float outW = (fo.x + fo.y + fo.z + fo.w + abs(ro.x) + abs(ro.y)) * u_dt;
    float keep = w > 0.0 ? max(0.0, 1.0 - outW / w) : 1.0;
#ifdef JB_WET_FIBRES
    // The fibres sieve the pigment out of water that travels through them: of the pigment in a film's water that the
    // paper draws on (o_run: the bleed), only u_bleedSieve goes with it at each cell, so the colour thins with distance
    // and does not arrive whole at the bleed's far edge to dry there as a line. Deep water (the wash itself, and water
    // running down a tilted sheet) keeps all of its pigment as it moves.
    float sieve = mix(1.0, u_bleedSieve, jb_film(a));
    float inRun = 0.0;
    float keepP = w > 0.0 ? max(0.0, 1.0 - ((fo.x + fo.y + fo.z + fo.w) + sieve * (abs(ro.x) + abs(ro.y))) * u_dt / w) : 1.0;
#else
#define keepP keep
#endif
    float inW = 0.0;
    vec4 inP = vec4(0.0);
    ivec2 nb[4] = ivec2[4](ivec2(-1, 0), ivec2(1, 0), ivec2(0, -1), ivec2(0, 1));
    int toMe[4] = int[4](1, 0, 3, 2);     // the neighbour's flux component that points at this cell
    for (int i = 0; i < 4; i++) {
        ivec2 q = c + nb[i];
        if (!jb_inside(q, size)) continue;
        // The neighbour's levelling flow toward this cell, plus its running water if it runs this way.
        float rq = max(0.0, -dot(wf(u_run, q).xy, vec2(nb[i])));
        float fq = (wf(u_flux, q)[toMe[i]] + rq) * u_dt;
        if (fq <= 0.0) continue;
#ifdef JB_WET_FIBRES
        vec4 aq = wf(u_w0, q);
        float wq = aq.r;
        inW += fq;
        inRun += rq * u_dt;
        fq -= (1.0 - mix(1.0, u_bleedSieve, jb_film(aq))) * rq * u_dt;
#else
        float wq = wf(u_w0, q).r;
        inW += fq;
#endif
        inP += wf(u_w1, q) * min(1.0, fq / max(wq, 1.0e-6));
    }
    // --- mingle: pigment drifts from rich water to lean water wherever the water connects (never into dry
    // paper). Pairwise and symmetric, so pigment is conserved. This is what turns two touching washes into
    // one soft, organic gradient instead of two flat shapes.
    vec4 mingle = vec4(0.0);
    if (a.r > u_minMm) {
        vec4 c0 = wf(u_w1, c) / a.r;
#ifdef JB_WET_FIBRES
        // Through the bleed's film colour spreads only along a fibre, both cells on it (never faster than the step allows):
        // the fine dark threads a stroke's edge sends out along the fibres that cross it.
        float along = jb_fetch(u_waterBake, c).g * min(u_bleedMingle, 0.24 / max(u_mingle * u_dt, 1.0e-6));
        float filmHere = jb_film(a);
#endif
        for (int i = 0; i < 4; i++) {
            ivec2 q = jb_clampCell(c + nb[i], size);
#ifdef JB_WET_FIBRES
            vec4 aq = wf(u_w0, q);
            float wq = aq.r;
            if (wq > u_minMm) mingle += (wf(u_w1, q) / wq - c0) * min(a.r, wq)
                * mix(1.0, along * jb_fetch(u_waterBake, q).g, max(filmHere, jb_film(aq)));
#else
            float wq = wf(u_w0, q).r;
            if (wq > u_minMm) mingle += (wf(u_w1, q) / wq - c0) * min(a.r, wq);
#endif
        }
    }
#ifndef JB_ATLAS
    // The wet sheet's rim (WetSheet): the sheet ends a little past the screen, and water that ran or spread that far stood
    // against its edge in a straight line (Note 9 film carry3, 2026-11-11). Water FLOWING INTO the rim's cells arrives
    // thinner the nearer the edge, and none reaches the edge: a drip that runs off the sheet tapers out as one running dry
    // does. Water that stands there (a wash carried over from another view, a brush's own dab) is left alone.
    if (u_rimCells > 0.0) {
        float d = float(min(min(c.x, c.y), min(size.x - 1 - c.x, size.y - 1 - c.y)));
        float rim = clamp(d / u_rimCells, 0.0, 1.0);
        inW *= rim; inP *= rim; mingle *= rim;
    }
#endif
    w = w - outW + inW;
    pig = max(pig * keepP + inP + u_mingle * u_dt * mingle, vec4(0.0));
#ifdef JB_WET_FIBRES
    // The mark: paper that was dry, or held only a film, and is wetted mostly by what the paper itself drew in (o_run, not
    // the levelling's flow) holds the bleed's water from here on, said by a wet age that counts DOWN (nothing else reads
    // the age's sign: it is this pass's own). A brush that wets it makes it painted water again (age 0, below); so does
    // drying out.
    if (age >= 0.0 && a.r < 0.5 * u_bleedThinMm && inRun > 0.0 && inRun >= 0.5 * inW) age = -max(age, 1.0e-4);
#endif

    // --- brush (signed: a thirsty brush lifts water and the pigment in it)
    vec4 i0 = vec4(0.0), i1 = vec4(0.0);
    if (u_useInput > 0.5) {
        i0 = wf(u_in0, c);
        i1 = wf(u_in1, c);
        if (i0.r < 0.0) {
            float take = min(1.0, -i0.r / max(w, 1.0e-6));
            pig *= 1.0 - take;
            w = max(0.0, w + i0.r);
        } else if (i0.r > 0.0) {
#ifndef JB_WET_COARSE
            // The dabs drawn in one frame all met the paper as it was before any of them, so where they overlap their water
            // added up as it never would dab after dab: each later dab meets wetter paper and gives less, and the paper ends
            // as wet as the brush (its wetness of a full puddle). A quick stroke was laid in scallops, one a frame, their
            // middles three times as wet as their joins. With u_inputCap a frame's dabs wet the paper no further than that
            // same end: the dabs' own wetness (i1.b / i0.r, weighted by what each gave) of a full puddle; what is cut is
            // cut from the pigment that came with it.
            // u_capSlack lets a little of that through again (the owner, 2026-11-11: a faint scallop makes the paper look
            // a little wavy; the even wash was too even). Not the same everywhere, or it is a rhythm: a patch here takes
            // up to three times the share, most of the paper next to none, and the patches move every frame.
            if (u_inputCap > 0.5 && i1.b > 0.0) {
                float slack = 0.0;
                if (u_capSlack > 0.0) {
                    float n = jb_capNoise(vec2(c) / 40.0 + vec2(u_capSeed, u_capSeed * 1.618));
                    slack = u_capSlack * 3.0 * n * n;
                }
                float k = min(1.0, max(i1.b / i0.r * u_fullMm * (1.0 + slack) - w, 0.0) / i0.r);
                i0 *= k; i1 *= k;
            }
#endif
            w += i0.r;
            if (w > u_minMm) age = 0.0;
        }
        pig += vec4(max(i0.gba, 0.0), max(i1.r, 0.0));
#ifndef JB_WET_COARSE
        // Remember how soluble the newest pigment is (graphite: 0, watercolour: ~1). (Coarse: each pixel, jb_wetChild.)
        float kNew = dot(max(i0.gba, 0.0), vec3(1.0));
        float kOld = dot(p0.rgb, vec3(1.0)) + dot(pig.rgb, vec3(1.0));
        if (kNew > 0.0) p1.a = mix(p1.a, clamp(i1.g / kNew, 0.0, 1.0), kNew / max(kNew + kOld, 1.0e-6));
#endif
    }

    bool wet = w > u_minMm;
#ifdef JB_WET_FIBRES
    bool bled = age < 0.0;
    if (wet) age += (bled ? -1.0 : 1.0) * u_dt * max(u_ageScale, 1.0);
    float film = jb_filmOf(w, bled);
#else
    if (wet) age += u_dt * max(u_ageScale, 1.0);
#endif

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
        float wq = wf(u_w0, q).r;
        wetN += 0.125 * smoothstep(u_minMm, u_minMm * 6.0, wq);
        wMax = max(wMax, wq);
    }
    }
    // A rim needs water to flow to the edge. Paint laid almost dry (dry brush) dries where it touches.
    float rimWater = smoothstep(0.03, 0.12, wMax);
    // Uneven sizing and air: some spots dry a little sooner, so pigment migrates toward the slower spots,
    // the soft mottling of a real wash (from the paper's own absorbency map).
    float sizingVar = 1.0;
#ifdef JB_WET_COARSE
    if (!idle) sizingVar = 0.65 + 0.7 * 0.25 * (jb_fetch(u_fluidBake, c * 2).r + jb_fetch(u_fluidBake, c * 2 + ivec2(1, 0)).r
                                               + jb_fetch(u_fluidBake, c * 2 + ivec2(0, 1)).r + jb_fetch(u_fluidBake, c * 2 + ivec2(1, 1)).r);
#else
    if (!idle) sizingVar = 0.65 + 0.7 * jb_fetch(u_fluidBake, c).r;
#endif
    float evap = u_evap * u_dt * sizingVar * (1.0 + rimWater * u_edgeEvap * (1.0 - wetN));
#ifdef JB_WET_FIBRES
    if (u_dryBoost > 0.0) evap *= 1.0 + u_dryBoost * smoothstep(u_dryAfterS, u_dryAfterS + max(u_dryRampS, 1.0e-3), abs(age));
#else
    if (u_dryBoost > 0.0) evap *= 1.0 + u_dryBoost * smoothstep(u_dryAfterS, u_dryAfterS + max(u_dryRampS, 1.0e-3), age);
#endif
#ifdef JB_WET_FIBRES
    // The bleed's film is water in the paper's surface, out of the air's reach: it dries at u_bleedEvap of the pace of
    // water standing on it (whose thin edge dries five times as fast: that alone dried a fibre's thread of water within
    // four cells of the edge, Note 9, 2026-11-17).
    evap *= mix(1.0, u_bleedEvap, film);
#endif
    float before = w;
    w = max(0.0, w - evap);

    // --- soak and wick
    // The paper's own fibres and pores (owner, 2026-11-17; every photo paper's fluid map carries them: G = 0.5 + 0.5 x
    // cos(2 x fibre angle) x coherence, B the same with sin, A = pore capacity, 0.5 = the sheet's own). Dampness spreads
    // faster along the fibres and slower across, by as much: the spread is a diffusion whose two rates are 1 + k and 1 - k
    // along and across the fibre (k = the paper's anisotropy x the map's coherence), so in the grid's own axes it is
    // (1 + k cos 2a) along x, (1 - k cos 2a) along y, and a cross term k sin 2a over the four diagonal neighbours, which is
    // what lets a fibre lying on a diagonal carry the damp along it. A spot with roomy pores takes more water before it
    // is full. Both off (unset uniforms): the arithmetic below is the old one.
#ifdef JB_WET_FIBRES
    // The paper's pores (the fluid map's A: 0.5 = the sheet's own capacity): a spot with roomy pores takes more water
    // before it is full, and takes it faster. A paper with no fluid map bakes G = B = 0 and A = 1: left as the sheet's.
    float capMm = u_capMm;
    {
        vec4 fl = jb_fetch(u_fluidBake, c);
        if (!(fl.g == 0.0 && fl.b == 0.0)) capMm = u_capMm * mix(1.0, clamp(fl.a * 2.0, 0.4, 1.8), u_pore);
    }
#else
    float capMm = u_capMm;
#endif
    float soak = min(w, u_absorb * u_dt * (1.0 - s) * capMm);
#ifdef JB_WET_FIBRES
    // The bleed's film is water already in the paper's surface: it sinks further in slowly (u_bleedSoak of standing
    // water's pace), or the paper under it would drink it dry within a few cells of the edge.
    soak *= mix(1.0, u_bleedSoak, film);
#endif
    w -= soak;
    vec4 stain = pig * min(1.0, soak / max(before, 1.0e-6)) * u_stainCarry;
#ifdef JB_WET_COARSE
    float stainFrac = min(1.0, soak / max(before, 1.0e-6)) * u_stainCarry;   // its pixels put this share of it down (jb_wetChild)
#endif
    pig -= stain;
    float sL = wf(u_w0, jb_clampCell(c + ivec2(-1, 0), size)).g;
    float sR = wf(u_w0, jb_clampCell(c + ivec2(1, 0), size)).g;
    float sD = wf(u_w0, jb_clampCell(c + ivec2(0, -1), size)).g;
    float sU = wf(u_w0, jb_clampCell(c + ivec2(0, 1), size)).g;
    s = s + soak / capMm + u_wick * u_dt * (sL + sR + sD + sU - 4.0 * s);
    // Damp paper dries on the clock (2026-11-10): like the wet age, the drying of dampness takes real time, so a phone that runs
    // the water at a twentieth of real time no longer keeps paper damp twenty times as long (on the owner's Note 9 the damp
    // spread until one damp pool covered the drawing, overflowed the atlas and froze in tile-shaped squares). Dampness this
    // faint darkens the paper by under 0.1 % (jb_media_render.frag): it is let go, so the paper really dries.
    s = clamp(s - u_sDry * u_dt * max(u_ageScale, 1.0) * s * (wet ? 0.15 : 1.0), 0.0, 1.0);
    if (!wet && s < JB_DAMP_S) s = 0.0;

#ifdef JB_WET_COARSE
    // --- settle, lift, dry out: each of the cell's four pixels by its own paper and paint (jb_wetChild, the very function
    // jb_wet_paint.frag applies to them), the water changed by their mean.
    float edge = 1.0 + rimWater * (u_edgeDep * (1.0 - wetN) + u_edgeDep * 0.5 * smoothstep(0.06, 0.01, w));
    vec4 mid0 = pig;
    vec4 mid1 = vec4(w, edge, stainFrac, u_useInput > 0.5 ? 1.0 : 0.0);
    vec4 dep = vec4(0.0), lifted = vec4(0.0);
    if (w > 0.0 || pig != vec4(0.0) || mid1.w > 0.5) {
        for (int j = 0; j < 2; j++) for (int i = 0; i < 2; i++) {
            ivec2 px = c * 2 + ivec2(i, j);
            JbWetChild r = jb_wetChild(mid0, mid1, i0, i1, jb_fetch(u_p0, px), jb_fetch(u_p1, px), jb_fetch(u_paperBake, px).b,
                jb_fetch(u_waterBake, px).r, u_minMm, u_dt, u_settle, u_gran, u_lift);
            dep += r.dep; lifted += r.lift;
        }
    }
    // What settled left the water (the stain already did, above); what lifted joined it. Dried out: none is left.
    if (w <= u_minMm) { pig = vec4(0.0); w = 0.0; }
    else pig = pig - (0.25 * dep - stain) + 0.25 * lifted;
#else
    // --- settle (into the valleys first) or dry out
    vec4 dep = stain;
#ifdef JB_WET_FIBRES
    // A trace of water that the paper is still drawing in is not the last of the water: it gathers until it is a film that
    // draws on in its turn. (Dried out at every step, as below, the bleed stopped where a step brought less than u_minMm:
    // a dozen cells along the thirstiest fibre and none across the felt, Note 9, 2026-11-17.) It dries by evaporating.
    if (w <= u_minMm && inRun <= 0.0) {
#else
    if (w <= u_minMm) {
#endif
        dep += pig;                      // the last of the water leaves its pigment where it is
        pig = vec4(0.0);
#ifdef JB_WET_FIBRES
        // The bleed's last trace is water in the paper: it leaves the paper damp, and the paper is no longer marked.
        if (bled) { s = min(1.0, s + w / capMm); age = 0.0; }
#endif
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
#ifdef JB_WET_FIBRES
        // Water drawn into the paper's surface carries its pigment along the fibres before the fibres filter it out: the
        // film's settling is u_bleedCarry of what standing water of its depth would have.
        float k = 1.0 - exp(-u_settle * g * edge * u_dt * min(20.0, 0.15 / max(w, 0.004))
                            * mix(1.0, u_bleedCarry, film));
#else
        float k = 1.0 - exp(-u_settle * g * edge * u_dt * min(20.0, 0.15 / max(w, 0.004)));
#endif
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

#endif
    o_w0 = vec4(w, s, age, outW / max(u_dt, 1.0e-6));
    o_w1 = max(pig, vec4(0.0));
#ifdef JB_WET_COARSE
    o_mid0 = mid0;
    o_mid1 = mid1;
#else
    o_p0 = p0;
    o_p1 = p1;
#endif
}
