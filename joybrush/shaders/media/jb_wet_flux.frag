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
uniform sampler2D u_p0;       // the layer's paint: .a = body thickness (mm), terrain the water runs over
uniform float u_filmMm;       // below this depth a film clings: viscous drag ∝ 1/depth², so thin water barely moves
uniform float u_dt;
uniform float u_toothMm;
uniform float u_flow;         // how hard a height difference pushes water (1/s)
uniform float u_keep;         // fraction of flow kept from the last step (viscosity: watery → near 1)
uniform float u_pinMm;        // head (mm) needed to wet dry paper
uniform float u_dampS;        // saturation at which paper counts as damp (no pinning)
uniform float u_minMm;        // thinner than this is "dry"
uniform vec2 u_slope;         // the paper tilted: sin(angle), pointing DOWNHILL in layer space. 0 = flat.
                              // On the phone this comes from the gravity sensor; in the lab from the tilt pad.
uniform float u_cellMm;       // one cell (mm)
uniform float u_runMmPerS;    // how fast a full bead runs down an upright paper
uniform float u_runMm;        // a bead this deep ABOVE the tooth runs freely; thinner water clings (speed ∝ depth²)
uniform float u_runHoldMm;    // water the tooth holds before any runs
uniform float u_runFilmMm;    // the film running water leaves behind on what it crosses
uniform float u_runPin;       // how much a bead's own weight helps it over a dry edge on a slope
uniform float u_runHoldK;     // a dry edge holds a running bead this much harder than still water
uniform float u_runCoherent;  // 1: whether a bead breaks an edge is decided by the whole bead, 0: cell by cell
uniform float u_steerScale;   // the paper relief that steers running water, in bead sizes
uniform float u_runSteer;     // how strongly the paper's valleys steer running water sideways
uniform float u_runAlong;     // how much of that the paper's bumps slow it along the slope (0..1)
uniform float u_beadCells;    // a running bead's size (cells)
uniform vec4 u_rect;          // the simulated rectangle: no water leaves it
uniform sampler2D u_bead;     // the bead field (jb_wet_bead.frag), valid while the paper is tilted
uniform float u_runCohere;    // how strongly a bead's own slope moves its water (drains a bead into its drips)

uniform float u_layerScale;   // layer px per doc px
uniform float u_runSizing;    // how uneven the paper's sizing is at drip scale (0 = even)
uniform float u_dripGapMm;    // a bead lets go only where it stands highest within about this along its edge
uniform float u_quietMm;      // a neighbour pouring this much more (mm of bead) keeps this part of the edge shut
in vec2 v_docPx;

layout(location = 0) out vec4 o_flux;   // the slow levelling flow (kept from step to step: viscosity)
layout(location = 1) out vec4 o_run;    // running water this step (mm/s), signed along x and y

float H(ivec2 q, out float w, out float s) {
    vec4 a = texelFetch(u_w0, q, 0);
    w = a.r; s = a.g;
    // The water's surface: paper, plus any paint body under it (water pools between impasto ridges and runs
    // round blobs), plus the water itself.
    return texelFetch(u_waterBake, q, 0).r * u_toothMm + texelFetch(u_p0, q, 0).a + w;
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

// Running water's bead (jb_wet_bead.frag): the water's depth Gaussian-blurred at half resolution, read
// bilinearly at a cell. Its slope (mm per mm) is taken across four cells.
float beadAt(vec2 cell) {
    return texture(u_bead, (cell + 0.5) * 0.5 / vec2(textureSize(u_bead, 0))).r;
}
vec2 beadSlope(vec2 cell) {
    return vec2(beadAt(cell + vec2(2.0, 0.0)) - beadAt(cell - vec2(2.0, 0.0)),
                beadAt(cell + vec2(0.0, 2.0)) - beadAt(cell - vec2(0.0, 2.0))) / (4.0 * u_cellMm);
}

// The paper's own slope at fibre scale (mm per mm): running water is steered into the valleys. Static, so it
// cannot feed back on the water.
vec2 paperSlope(ivec2 c, ivec2 size) {
    int d = max(2, int(u_steerScale * u_beadCells));
    ivec2 ql = clamp(c - ivec2(d, 0), ivec2(0), size - 1), qr = clamp(c + ivec2(d, 0), ivec2(0), size - 1);
    ivec2 qb = clamp(c - ivec2(0, d), ivec2(0), size - 1), qt = clamp(c + ivec2(0, d), ivec2(0), size - 1);
    // Paper relief and paint body both steer: a drip runs round a blob of paint.
    float l = texelFetch(u_waterBake, ql, 0).r * u_toothMm + texelFetch(u_p0, ql, 0).a;
    float r = texelFetch(u_waterBake, qr, 0).r * u_toothMm + texelFetch(u_p0, qr, 0).a;
    float b = texelFetch(u_waterBake, qb, 0).r * u_toothMm + texelFetch(u_p0, qb, 0).a;
    float t = texelFetch(u_waterBake, qt, 0).r * u_toothMm + texelFetch(u_p0, qt, 0).a;
    return vec2(r - l, t - b) / (2.0 * float(d) * u_cellMm);
}

// Sizing is never even across a sheet: patches a few millimetres across hold a bead harder than others, so
// drips break away at irregular places instead of as evenly spaced icicles. In document space (the same on
// every layer and at every zoom).
float runSizingAt(vec2 docPx) {
    vec2 mm = docPx * u_cellMm * u_layerScale;
    float n = 0.65 * jb_vnoise2(mm / 2.4, 4099u) + 0.35 * jb_vnoise2(mm / 0.9, 4111u);
    return 1.0 + u_runSizing * (2.0 * n - 1.0);
}

bool outside(ivec2 q) {
    return float(q.x) < u_rect.x || float(q.y) < u_rect.y || float(q.x) >= u_rect.z || float(q.y) >= u_rect.w;
}

void main() {
    ivec2 c = ivec2(gl_FragCoord.xy);
    ivec2 size = textureSize(u_w0, 0);
    float w, s;
    float h0 = H(c, w, s);
    if (w <= 0.0) { o_flux = vec4(0.0); o_run = vec4(0.0); return; }
    vec4 prev = texelFetch(u_flux, c, 0);
    ivec2 nb[4] = ivec2[4](ivec2(-1, 0), ivec2(1, 0), ivec2(0, -1), ivec2(0, 1));
    float sl = length(u_slope);
    float tilted = smoothstep(0.0, 0.15, sl);
    vec2 sd = sl > 1.0e-5 ? u_slope / sl : vec2(0.0);
    // On a tilted paper: the bead here, how hard the paper holds it, and whether this part of the edge may let go.
    float wb = w, holdK = 1.0, breakable = 1.0, edge = 0.0;
    vec2 gb = vec2(0.0), nOut = vec2(0.0);
    if (sl > 1.0e-4) {
        wb = beadAt(vec2(c));
        gb = beadSlope(vec2(c));
        float sz = runSizingAt(v_docPx);
        holdK = u_runHoldK * sz;
        float gl = length(gb);
        if (gl > 1.0e-4) {
            nOut = -gb / gl;                      // where the bead falls away: out through its edge
            edge = smoothstep(0.08, 0.25, gl);
            // Fingers: a bead lets go only where it stands highest along its own edge, for how hard the paper
            // holds there. That point breaks into a drip, the drip drains the bead beside it, and the rest of
            // the edge holds. Without this a deep bead let go everywhere and slid down as one sheet with
            // ruler-straight sides (v9's first live try).
            // And while a neighbour is already pouring out (water a millimetre beyond its edge), this part holds
            // and the bead drains into that drip, as real water does; else each drained root made its neighbour
            // the new highest point and the edge unzipped into a wide curtain.
            vec2 t = vec2(-nOut.y, nOut.x), out1 = nOut / u_cellMm;
            float L = 0.5 * u_dripGapMm / u_cellMm;
            float e0 = wb / sz, emax = 0.0, r0 = beadAt(vec2(c) + out1), rmax = 0.0;
            for (int k = -2; k <= 2; k++) {
                if (k == 0) continue;
                vec2 o = t * (L * float(k));
                emax = max(emax, beadAt(vec2(c) + o) / runSizingAt(v_docPx + o / u_layerScale));
                rmax = max(rmax, beadAt(vec2(c) + o + out1));
            }
            float peak = smoothstep(-0.02, 0.02, e0 - emax);
            float pouring = smoothstep(0.01, 0.03, r0);
            float quiet = 1.0 - smoothstep(u_quietMm, 3.0 * u_quietMm + 1.0e-4, rmax - r0);
            breakable = mix(1.0, max(peak, pouring) * quiet, edge);
        }
    }
    vec4 f;
    for (int i = 0; i < 4; i++) {
        ivec2 q = clamp(c + nb[i], ivec2(0), size - 1);
        float wn, sn;
        float hn = H(q, wn, sn);
        // A tilt adds a gentle slope under the slow levelling too.
        float dh = h0 - hn + dot(u_slope, vec2(nb[i])) * u_cellMm;
        // Lubrication: a film's mobility grows with depth², so deep puddles level fast while the thin film
        // left on the tooth stays put instead of draining into the valleys.
        // The DONOR's depth decides: deep water feeds a thin edge (the outward flow that draws the dark
        // rim), while a thin film on the tooth stays put instead of draining into the valleys.
        float depth = w;
        float mobility = min(1.0, (depth / u_filmMm) * (depth / u_filmMm));
        float fi = max(0.0, prev[i] * u_keep + u_dt * u_flow * dh * mobility);
        if (wn < u_minMm) {
            // On a tilted paper the gathering bead is held by the same edge, judged as a whole bead (the slow
            // levelling must not seep it over cell by cell ahead of the running water's own test).
            float hold = pinHold(q, sn) * mix(1.0, holdK, tilted);
            float head = mix(dh, wb + dot(u_slope, vec2(nb[i])) * u_cellMm, tilted * u_runCoherent);
            // Only an edge facing downhill ever lets go on a slope; a stream's sides and its upper edge hold.
            float faces = smoothstep(0.25, 0.6, dot(sd, vec2(nb[i])));
            fi *= smoothstep(hold, hold * 1.6 + 1.0e-4, head) * mix(1.0, breakable * faces, tilted);
        }
        if (q == c || outside(c + nb[i])) fi = 0.0;
        f[i] = fi;
    }

    // Running water: on a tilted paper gravity carries water downhill as a kinematic wave (no momentum, so
    // no roll waves). Its speed grows with the bead's depth² (lubrication): deep water outruns the thin film
    // it leaves on the tooth, gathers into a bead at its front, and breaks through a dry edge only where the
    // bead is heavy and the paper lets go: drips. On a flat paper nothing here runs.
    vec2 run = vec2(0.0);
    if (sl > 1.0e-4) {
        // Water first fills the paper's tooth, which holds it; only the water standing above runs. So rough
        // paper holds a wash a smooth one lets go.
        float k = clamp((wb - u_runHoldMm) / u_runMm, 0.0, 1.0);
        // The paper steers running water sideways into its valleys (a drip wanders along the grain). Its bumps
        // only slow the water a little along the slope: a real valley is never deep enough to trap a running
        // bead on a tilted sheet, and letting it would sort the wash into spots.
        vec2 ps = paperSlope(c, size);
        float along = dot(ps, sd);
        // Only a deep, running bead is steered; a wash's thin sheet just slides (else it sorts into lace).
        float steer = u_runSteer * smoothstep(0.3 * u_runMm, u_runMm, wb - u_runHoldMm);
        // Surface tension levels a bead along itself: water runs from where the bead stands high to where it
        // has drained, so a drip that breaks away is fed from the bead beside it and the rest of the edge holds.
        vec2 drive = u_slope - steer * sl * (ps - (1.0 - u_runAlong) * along * sd) - u_runCohere * tilted * gb;
        // A held edge turns the water along itself: near a bead's edge (where the bead falls away), the part of
        // the flow pushing out through an edge that holds is taken away, and what is left slides along the
        // edge. So a slanted edge drains to its lowest point and drips THERE, instead of giving way all along
        // its length as a straight-sided curtain.
        if (edge > 0.0) {
            float down = max(0.0, dot(sd, nOut));
            float hold = holdK * pinHold(c, 0.0) / (1.0 + u_runPin * sl * down);
            float lets = smoothstep(hold, hold * 1.6 + 1.0e-4, wb + down * sl * u_cellMm) * breakable * smoothstep(0.25, 0.6, down);
            drive -= edge * (1.0 - lets) * max(0.0, dot(drive, nOut)) * nOut;
        }
        vec2 v = drive * (u_runMmPerS / u_cellMm) * k * k;          // cells per second
        float vm = length(v), vMax = 0.45 / u_dt;
        if (vm > vMax) v *= vMax / vm;
        // Running water wets what it crosses: a thin film stays behind on the paper, so a drip leaves a wet
        // trail and a sliding wash never drains a spot bare.
        run = max(0.0, w - u_runFilmMm) * v;                        // mm/s, signed along x and y
        vec2 downDir = sd;
        for (int axis = 0; axis < 2; axis++) {
            float fa = run[axis];
            if (abs(fa) < 1.0e-9) continue;
            ivec2 step_ = axis == 0 ? ivec2(fa > 0.0 ? 1 : -1, 0) : ivec2(0, fa > 0.0 ? 1 : -1);
            ivec2 q = c + step_;
            if (q.x < 0 || q.y < 0 || q.x >= size.x || q.y >= size.y) continue;   // runs off the paper's edge
            if (outside(q)) { run[axis] = 0.0; continue; }
            float wn, sn;
            float hn = H(q, wn, sn);
            // A dry edge holds the bead back; on a slope the bead's weight helps it over (downhill only). The
            // hold fades as the cell ahead wets, not at the first trace (the slow levelling seeps a trace ahead,
            // and treating that as wet let a whole bead edge slide at once).
            float dryAhead = 1.0 - smoothstep(0.0, 2.0 * u_runFilmMm, wn);
            if (dryAhead > 0.0) {
                float down = max(0.0, dot(downDir, vec2(step_)));
                float hold = dryAhead * holdK * pinHold(q, sn) / (1.0 + u_runPin * sl * down);
                // The bead as a whole breaks the edge (its smoothed depth), so drips come away bead-wide rather
                // than one cell at a time.
                float head = mix(max(h0 - hn, wb), wb, u_runCoherent) + dot(u_slope, vec2(step_)) * u_cellMm;
                run[axis] *= smoothstep(hold, hold * 1.6 + 1.0e-4, head) * breakable * smoothstep(0.25, 0.6, down);
            }
        }
    }
    float out_ = (f.x + f.y + f.z + f.w + abs(run.x) + abs(run.y)) * u_dt;
    if (out_ > w) { f *= w / out_; run *= w / out_; }          // never move more water than the cell holds
    o_flux = f;
    o_run = vec4(run, 0.0, 0.0);
}
