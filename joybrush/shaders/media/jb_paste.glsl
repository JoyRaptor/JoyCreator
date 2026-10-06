// jb_paste.glsl — paste media (oil, acrylic, gouache body, palette knife): the brush footprint and the
// paint exchange shared by the canvas pass (jb_paste_dab.frag) and the brush pass (jb_paste_brush.frag).
// Functions only: NO #version, NO main. Include after jb_media_common.glsl.
//
// The brush is a small fixed grid of paint cells: LANES across its width (runs of hair) × DEPTH from tip
// to belly. Each cell holds ONE paint (K rgb, S, amount). Patent guard (MEDIA_ENGINE_PLAN §1.2): one state
// per cell (no pickup-vs-reservoir split, no "pickup first" rule), a coarse grid never at canvas
// resolution, and a 2D footprint (no 3D bristle mesh).
//
// Brush frame: across a ∈ [-1, 1] (wDir), along b ∈ [0, len] mm (lDir, pointing to the trailing tips).
// Depth from the tip: δ = (len − b) / lenMax, so a light touch uses only the tips and pressure brings the
// belly down, and its paint shows.

uniform sampler2D u_brush0;     // brush cells: K rgb (total), amount (mm of paint the cell can lay)
uniform sampler2D u_brush1;     // brush cells: S (total), -, -, -
uniform vec2 u_center;          // pen point, doc px
uniform vec2 u_wDir;            // across the brush (unit, doc space)
uniform vec2 u_lDir;            // toward the trailing tips (unit)
uniform float u_halfW;          // mm
uniform float u_len;            // contact length now, mm
uniform float u_lenMax;         // full hair length that can touch, mm
uniform float u_shape;          // 0 round, 1 flat, 2 knife (rigid), 3 cutting edge (scraper, sgraffito)
uniform float u_pressure;       // 0..1
uniform float u_thick;          // thickest layer a full brush leaves (mm)
uniform float u_scrape;         // how much pressure scrapes the layer thinner
uniform float u_hairDepth;      // groove depth of the hair runs (fraction of the layer)
uniform float u_ridge;          // paint squeezed up at the brush's sides (fraction of the layer)
uniform float u_rate;           // how fast a pass reaches its target (per contact length slid)
uniform float u_mix;            // how strongly wet paint is stirred together
uniform float u_swap;           // hair by hair, brush paint goes down while canvas paint comes up (broken colour)
uniform float u_slideMm;        // brush travel this step
uniform float u_cellCap;        // a full cell's amount (mm)
uniform float u_bow;            // paint piling ahead of the brush (fraction of the layer), left as a ridge where it lifts
uniform float u_lump;           // thick paint is never smooth: lumps in the layer (fraction)
uniform float u_rigid;          // 1 = a blade rides the canvas peaks; 0 = hair follows the weave down
float u_hairLen = 1.0;          // set per fragment before jb_pasteCover (how long this run of hair is)
uniform float u_seed;

const float JB_LANES = 32.0;
const float JB_DEPTH = 8.0;

// How far the hair splays past (or falls short of) the nominal side here: paint squeezed out, stray
// hairs. Document space, so the side of a stroke is one ragged line. A knife's edge is straight.
float jb_pasteRag(vec2 docPx) {
    uint s = uint(u_seed);
    if (u_shape > 1.5)   // a blade's edge is straight, but the paint squeezed out past it is not
        return 0.14 * (jb_vnoise2(docPx / 26.0, s + 23u) - 0.5) + 0.05 * (jb_vnoise2(docPx / 8.0, s + 24u) - 0.5);
    return 0.10 * (jb_vnoise2(docPx / 9.0, s + 21u) - 0.5) + 0.06 * (jb_vnoise2(docPx / 3.5, s + 22u) - 0.5);
}

// Footprint coverage at brush coords (a, b): 1 inside, soft 1-px edge, shaped by the brush.
float jb_pasteCover(float a, float b, float aaA, float aaB) {
    if (b < -aaB || b > u_len + aaB) return 0.0;
    float t = clamp(b / max(u_len, 1e-3), 0.0, 1.0);
    float half_ = 1.0;
    if (u_shape < 0.5) {
        // Round: a teardrop — full width at the belly, closing to the tip.
        half_ = sqrt(max(0.0, 1.0 - t * t * t));
    } else if (u_shape < 1.5) {
        // Flat: square shoulders, the trailing corners rounded off a little.
        half_ = 1.0 - 0.35 * smoothstep(0.75, 1.0, t);
    } else {
        // Knife: a trowel, its rounded tip trailing. A cutting edge: a narrow round point.
        half_ = u_shape > 2.5 ? sqrt(max(0.0, 1.0 - t * t)) : 1.0 - 0.45 * smoothstep(0.6, 1.0, t) * smoothstep(0.6, 1.0, t);
    }
    float side = smoothstep(half_ + aaA, half_ - aaA, abs(a));
    // Hairs are not all one length: as the brush lifts, the shorter ones leave the canvas first, so a light
    // or lifting stroke breaks into separate fingers of paint.
    if (u_shape < 1.5) {
        float thr = (1.0 - u_pressure) * 0.85 - 0.12;
        side *= smoothstep(thr - 0.06, thr + 0.06, u_hairLen);
    }
    // The ends ramp over at least one step of travel: each step then blends into the next and the stroke
    // never shows a line per step.
    float aaE = max(aaB, 1.2 * u_slideMm);
    float ends = smoothstep(-aaE, aaE, b) * smoothstep(u_len + aaE, u_len - aaE, b);
    return side * ends;
}

// Hair runs across the brush: coarse clumps plus single hairs. They are not rails: clumps wander a little
// sideways and come and go along the stroke (read in document space, so overlapping steps agree).
// Rigid knife: none.
float jb_pasteHair(float a, vec2 docPx) {
    if (u_shape > 1.5) return 0.5;
    uint s = uint(u_seed);
    float drift = 1.6 * (jb_vnoise2(docPx / 55.0, s + 9u) - 0.5);
    float clump = jb_vnoise1((a + 1.0) * 6.0 + drift, s);
    float hairs = jb_vnoise1((a + 1.0) * 19.0 + drift * 2.5, s + 5u);
    float come = 0.55 + 0.9 * jb_vnoise2(docPx / 30.0, s + 13u);
    return clamp(0.5 + (0.6 * clump + 0.4 * hairs - 0.5) * come, 0.0, 1.0);
}

// The paint this part of the brush leaves (mm, measured from the paper/canvas surface), given how loaded
// that cell is, where it is in the footprint (b mm from the front), and the canvas under it (valley =
// 1 − h, × tooth mm). The brush's underside sits a layer's thickness above the canvas PEAKS, so paint
// fills the weave's valleys first: scrape hard and the peaks come bare and the canvas takes over; lay it
// on thick and the weave disappears under it.
float jb_pasteTarget(float a, float load, float hair, float b, float valleyMm, vec2 docPx) {
    float fill = clamp(load / max(u_cellCap, 1e-6), 0.0, 1.0);
    float t = u_thick * fill * (1.0 - u_scrape * pow(u_pressure, 0.6));
    t *= 1.0 + u_hairDepth * (hair - 0.5) * 2.0;
    t *= 1.0 + u_ridge * smoothstep(0.7, 1.0, abs(a));
    t *= 1.0 + u_bow * fill * smoothstep(0.3 * u_len, 0.0, b);
    // Thick paint drags: lumps stretched along the stroke (fine across it, long along it).
    vec2 sp = vec2(dot(docPx, u_wDir) / 6.0, dot(docPx, u_lDir) / 70.0);
    t *= 1.0 + u_lump * 2.0 * (0.7 * jb_vnoise2(sp, uint(u_seed) + 31u) + 0.3 * jb_vnoise2(sp * 2.7, uint(u_seed) + 32u) - 0.5) * fill;
    return max(t, 0.0) + valleyMm * mix(0.5, 1.0, u_rigid);
}

// The brush's paint at brush coords, read SMOOTHLY between cells (no block edges in the stroke): bilinear
// across the hair runs and along the depth.
// Depth into the hair (0 tip .. 1 belly) at brush coords. Along the drag the tips trail; on a pressed
// ROUND brush the hairs splayed out to the sides are belly hairs too, so the stroke's edges show the belly.
float jb_pasteDepth(float a, float b) {
    float along = (u_len - b) / max(u_lenMax, 1e-3);
    if (u_shape < 0.5) along = max(along, abs(a) * u_len / max(u_lenMax, 1e-3));
    return along;
}

void jb_pasteRead(float a, float b, out vec4 c0, out vec4 c1) {
    float lane = clamp((a + 1.0) * 0.5 * JB_LANES - 0.5, 0.0, JB_LANES - 1.0);
    float depth = clamp(jb_pasteDepth(a, b) * JB_DEPTH - 0.5, 0.0, JB_DEPTH - 1.0);
    ivec2 i = ivec2(floor(vec2(lane, depth)));
    vec2 f = vec2(lane, depth) - vec2(i);
    ivec2 j = min(i + 1, ivec2(int(JB_LANES) - 1, int(JB_DEPTH) - 1));
    c0 = mix(mix(texelFetch(u_brush0, i, 0), texelFetch(u_brush0, ivec2(j.x, i.y), 0), f.x),
             mix(texelFetch(u_brush0, ivec2(i.x, j.y), 0), texelFetch(u_brush0, j, 0), f.x), f.y);
    c1 = mix(mix(texelFetch(u_brush1, i, 0), texelFetch(u_brush1, ivec2(j.x, i.y), 0), f.x),
             mix(texelFetch(u_brush1, ivec2(i.x, j.y), 0), texelFetch(u_brush1, j, 0), f.x), f.y);
}

// How much a run of hair trades paint (rather than stirring it): uneven across the brush, so wet-in-wet
// strokes come out in streaks of separate colours, not one averaged mud.
float jb_pasteSwapRate(float hair) { return u_swap * smoothstep(0.3, 0.75, hair); }

ivec2 jb_pasteCell(float a, float b) {
    float lane = clamp((a + 1.0) * 0.5 * JB_LANES, 0.0, JB_LANES - 1.0);
    float depth = clamp(jb_pasteDepth(a, b) * JB_DEPTH, 0.0, JB_DEPTH - 1.0);
    return ivec2(int(lane), int(depth));
}
