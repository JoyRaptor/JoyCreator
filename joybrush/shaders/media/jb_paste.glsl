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
uniform float u_blade;          // 1 = a rigid blade (Palette knife 2, scraper); 0 = hair or the trowel knife
uniform float u_fillDips;       // how far a loaded brush's paint reaches into dips: 1 fills them (v8), 0 skates over
                                // them catching the high points (the trowel knife's texture)
uniform float u_fingers;        // 0..1: how much a lifting brush breaks into separate fingers of hair
uniform float u_rag;            // 0..1: how ragged the hair's edges are (a detail brush keeps clean edges)
uniform float u_arc;            // flat brushes: how far the middle hairs lead the corners (× half-width); a fan is a deep arc
uniform float u_sparse;         // 0..1: gaps between the hairs (a fan's hairs are spread apart)
// Stroke ends (owner, 2026-10-23: "they start and stop like a razor-cut perfect line"). 0 = the clean-cut ends kept to
// compare; 1 = a loaded brush: it lands with a blob, its hairs are not one length, and lifting off it runs dry and breaks.
uniform float u_ends;
uniform float u_lift;           // 0..1: how far the pen has eased off its press (pressure under its recent peak)
uniform float u_land;           // 1 at touchdown, 0 once the brush has travelled about its own width
// 0..1 (owner, 2026-10-23: "painting a different colour on top takes many dabs"): a loaded brush pressed lightly rides on
// wet paint and lays its own on top, which then shows (an opaque coat hides what is under it). 0 = it sinks to a target
// measured from the canvas and its paint stirs in by volume, as before.
uniform float u_cover;
// 0..1 (owner, 2026-10-23: the dirty brush): the hairs that touch hold little paint and are fed from the belly near the
// ferrule, which holds most of the load, so what they pick up is laid back within a few centimetres, streaky and fading,
// instead of tinting the whole load. 0 = every cell holds a full share and nothing flows but the slow wick, as before.
uniform float u_carry;
// Rigid blade (knife, scraper): held like the pen, so its edge lies along the pen's LEAN, not the path.
uniform vec2 u_bladeDir;        // along the edge, from the point toward the handle (unit, doc space)
uniform vec2 u_travel;          // direction of travel (unit)
uniform float u_bladeLen;       // mm of edge that can reach the paint
uniform float u_bladeHalfW;     // mm: half the width of the blade's underside (knife) or edge (scraper)
uniform float u_bladeH0;        // mm above the canvas peaks at the point (pressure lowers it)
uniform float u_bladeTan;       // the edge's rise along its length (tilt flattens it; 0 = lying flat)
uniform float u_bladeBead;      // how much of what the blade carries rides ahead of it as a bead
uniform float u_bladePressMm;   // a firm blade presses into the weave (the canvas yields): peaks come bare
uniform float u_bladeFilm;      // mm: paint is sticky; a scraped surface keeps a thin stain
uniform float u_bladeLoad;      // 1: pressure is the load (a light touch only catches what stands high)
float u_hairLen = 1.0;          // set per fragment before jb_pasteCover (how long this run of hair is)
uniform float u_seed;

const float JB_LANES = 32.0;
const float JB_DEPTH = 8.0;
const float JB_LAND_RAG = 2.0;   // the landing's extra raggedness (PasteFootprint.LAND_RAG)
const float JB_LAND_SPLAY = 0.14; // a firm landing splays the hairs this much wider (PasteFootprint.LAND_SPLAY)

// How firmly the brush is landing (u_ends): 0 lightly or once it has travelled, 1 planted hard.
float jb_pasteFirmLand() { return u_ends > 0.5 ? u_land * smoothstep(0.4, 0.9, u_pressure) : 0.0; }
// The brush's half-width in play: planted hard, the hairs splay out (both passes map lanes through it).
float jb_pasteHalfW() { return u_ends > 0.5 && u_shape < 1.5 ? u_halfW * (1.0 + JB_LAND_SPLAY * jb_pasteFirmLand()) : u_halfW; }
const float JB_FEED = 0.25;
const float JB_CATCH = 1.0;      // how much the tooth's face rising toward the travel counts against its height (jb_pasteDry)
const float JB_HAIR_SHARE = 0.06;   // u_carry: what the hairs that touch hold, of a full cell
const float JB_BELLY_SHARE = 5.6;   // ... and the belly near the ferrule on top of that (the load's average stays ~0.9)      // per step: how fast the belly refills a cell below it (u_carry), of that cell's shortfall

// The share of a full cell (u_cellCap) a cell holds at this depth (0 tip .. 1 at the ferrule): with u_carry the hairs hold
// 0.3 and the belly near the ferrule most of the load (the loading tray fills to this; PasteLoad.profile). 1 without.
float jb_pasteProf(float depth) { return mix(1.0, JB_HAIR_SHARE + JB_BELLY_SHARE * smoothstep(0.7, 1.0, depth), u_carry); }
// The full amount of the cell being read here (set per fragment: u_cellCap * jb_pasteProf(its depth)).
float jb_cap = 1.0;
// Paint seeping along the hair between neighbouring depth cells (u_carry): plain diffusion of how full each is (its amount
// over what its depth holds), so a cell that has laid paint down draws from the fuller one next to it and an overfull one
// gives back. Signed: > 0 runs from `up` (the cell nearer the ferrule) into `own`. Both cells compute it from the same old
// state (conservative); never more than a quarter of what the giving cell holds. PATENT GUARD (R8, US 8,462,173): this is
// the ONLY flow between depth cells; it knows nothing of where a cell's paint came from, and nothing is laid "picked-up
// first": a cell lays its one mixed paint (tasks/joybrush/specs/OIL_CARRY_20261024.md).
float jb_pasteSeep(float up, float capUp, float own, float capOwn) {
    float f = JB_FEED * u_carry * (up / max(capUp, 1e-6) - own / max(capOwn, 1e-6)) * min(capUp, capOwn);
    return f >= 0.0 ? min(f, 0.25 * up) : max(f, -0.25 * own);
}

// How far the hair splays past (or falls short of) the nominal side here: paint squeezed out, stray
// hairs. Document space, so the side of a stroke is one ragged line. A knife's edge is straight.
float jb_pasteRag(vec2 docPx) {
    uint s = uint(u_seed);
    if (u_shape > 1.5)   // a blade's edge is straight, but the paint squeezed out past it is not
        return 0.14 * (jb_vnoise2(docPx / 26.0, s + 23u) - 0.5) + 0.05 * (jb_vnoise2(docPx / 8.0, s + 24u) - 0.5);
    // A loaded brush pressed down splays its hairs and squeezes paint out unevenly where it lands (u_ends; PasteFootprint
    // widens its box by the same factor).
    float landed = u_ends > 0.5 ? 1.0 + JB_LAND_RAG * u_land : 1.0;
    return landed * u_rag * (0.10 * (jb_vnoise2(docPx / 9.0, s + 21u) - 0.5) + 0.06 * (jb_vnoise2(docPx / 3.5, s + 22u) - 0.5));
}

// A run of hair's own length and strength (0 short and weak .. 1 long and strong): clumps plus single hairs, fixed
// to the brush (lane a), so the same hairs land last, lift last and leave the longest streaks.
float jb_pasteTip(float a) { return 0.6 * u_hairLen + 0.4 * jb_vnoise1((a + 1.0) * 27.0, uint(u_seed) + 43u); }

// Footprint coverage at brush coords (a, b): 1 inside, soft 1-px edge, shaped by the brush.
// No edge of a hair brush is ever a straight line (the owner, 2026-10-07: "a perfectly sharp cutoff when
// you start or stop painting is a dead giveaway"): a round has a round head, a flat a shallow, uneven arc.
float jb_pasteCover(float a, float b, float aaA, float aaB) {
    if (b < -aaB || b > u_len + aaB) return 0.0;
    // A loaded brush's ends (u_ends): every run of hair has its own length, so the front (where the pen stops) and the
    // tips (where it landed) are ragged per run; as the pen eases off, each run's contact slides back toward its tip at
    // its own pace, so the runs end one after another. Only ever INSIDE the footprint (PasteFootprint's box holds).
    float tail = 0.0;
    if (u_ends > 0.5 && u_shape < 2.5) {
        float tip = jb_pasteTip(a);
        float rough = u_shape > 1.5 ? 0.35 : 1.0;   // the trowel's edge is straight; only the paint at it tears
        b -= rough * (0.16 * u_halfW * (1.0 - tip) + u_lift * u_len * 0.85 * (1.0 - tip) * (1.0 - tip));
        tail = rough * min(0.6 * u_len, 0.45 * u_halfW * (1.0 - tip) + 0.15 * u_halfW * jb_vnoise1((a + 1.0) * 61.0, uint(u_seed) + 44u));
        if (b < -aaB) return 0.0;
    }
    float t = clamp(b / max(u_len, 1e-3), 0.0, 1.0);
    float half_ = 1.0;
    if (u_shape < 0.5) {
        // Round: a round head (the pressed belly) and a tail of hairs trailing behind it.
        float r = min(u_halfW, 0.5 * u_len);
        if (b < r) {
            float k = (r - b) / max(r, 1e-3);
            half_ = sqrt(max(0.0, 1.0 - k * k)) * (r / max(u_halfW, 1e-3));
            if (r >= u_halfW * 0.999) half_ = sqrt(max(0.0, 1.0 - k * k));
        } else {
            float tt = clamp((b - r) / max(u_len - r, 1e-3), 0.0, 1.0);
            // Planted hard (u_ends), the tips splay square: the landing edge flattens instead of a round cap.
            half_ = sqrt(max(0.0, 1.0 - (u_ends > 0.5 ? pow(tt, 3.0 + 9.0 * jb_pasteFirmLand()) : tt * tt * tt)));
        }
    } else if (u_shape < 1.5) {
        // Flat: the chisel's front is a shallow arc (the middle hairs lead) with an uneven edge, and the
        // trailing corners round off.
        float lead = (u_arc * a * a + 0.06 * (jb_vnoise1((a + 1.0) * 5.0, uint(u_seed) + 61u) - 0.5)) * u_halfW;
        b -= lead;
        if (b < -aaB) return 0.0;
        t = clamp(b / max(u_len, 1e-3), 0.0, 1.0);
        half_ = 1.0 - 0.45 * smoothstep(0.6, 1.0, t) * smoothstep(0.6, 1.0, t);
    } else {
        // Knife: a trowel, its rounded tip trailing. A cutting edge: a narrow round point.
        half_ = u_shape > 2.5 ? sqrt(max(0.0, 1.0 - t * t)) : 1.0 - 0.45 * smoothstep(0.6, 1.0, t) * smoothstep(0.6, 1.0, t);
    }
    float side = smoothstep(half_ + aaA, half_ - aaA, abs(a));
    // Spread hairs (a fan): only some runs of hair touch; the gaps close a little as pressure squashes them.
    if (u_sparse > 0.0) {
        float hairRun = jb_vnoise1((a + 1.0) * 16.0, uint(u_seed) + 71u);
        side *= smoothstep(u_sparse * (0.75 - 0.35 * u_pressure) - 0.08, u_sparse * (0.75 - 0.35 * u_pressure) + 0.08, hairRun);
    }
    // Hairs are not all one length: as the brush lifts, the shorter ones leave the canvas first, so a light
    // or lifting stroke breaks into separate fingers of paint.
    if (u_shape < 1.5 && u_fingers > 0.0) {
        float thr = ((1.0 - u_pressure) * 0.85 - 0.12) * u_fingers;
        side *= smoothstep(thr - 0.06, thr + 0.06, u_hairLen);
    }
    // Lifting off, the weak and short runs leave the canvas first, one after another (any hair brush, whatever its fingers);
    // landing lightly, only the long ones reach it until the press builds, so a swept landing starts in streaks.
    if (u_ends > 0.5 && u_shape < 1.5) {
        float thr = max(0.3 * u_lift, 0.5 * u_land * (1.0 - smoothstep(0.12, 0.45, u_pressure)));
        if (thr > 0.0) side *= smoothstep(thr - 0.05, thr + 0.05, jb_pasteTip(a));
    }
    // The ends ramp over at least one step of travel: each step then blends into the next and the stroke
    // never shows a line per step.
    float aaE = max(aaB, 1.2 * u_slideMm);
    float ends = smoothstep(-aaE, aaE, b) * smoothstep(u_len - tail + aaE, u_len - tail - aaE, b);
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
    float fill = clamp(load / max(jb_cap, 1e-6), 0.0, 1.0);
    float t = u_thick * fill * (1.0 - u_scrape * pow(u_pressure, 0.6));
    t *= 1.0 + u_hairDepth * (hair - 0.5) * 2.0;
    t *= 1.0 + u_ridge * smoothstep(0.7, 1.0, abs(a));
    t *= 1.0 + u_bow * fill * smoothstep(0.3 * u_len, 0.0, b);
    // Thick paint drags: lumps stretched along the stroke (fine across it, long along it).
    vec2 sp = vec2(dot(docPx, u_wDir) / 6.0, dot(docPx, u_lDir) / 70.0);
    t *= 1.0 + u_lump * 2.0 * (0.7 * jb_vnoise2(sp, uint(u_seed) + 31u) + 0.3 * jb_vnoise2(sp * 2.7, uint(u_seed) + 32u) - 0.5) * fill;
    // A loaded brush set down squashes out a blob of its paint, piled up round the tips where it landed; lifting off,
    // the hairs drag out a thinning film.
    // Planted hard, more of it is squeezed back into a ridge along the landing edge.
    if (u_ends > 0.5) t *= (1.0 + u_land * fill * (0.5 + (0.9 + 1.4 * jb_pasteFirmLand()) * smoothstep(0.55, 1.0, b / max(u_len, 1e-3))))
                       * (1.0 - 0.6 * u_lift);
    // Loaded paint fills the weave (hiding it); a thin film follows the weave down.
    return max(t, 0.0) + valleyMm * mix(mix(0.5, 0.95, smoothstep(0.05, 0.3, t)), 1.0, u_rigid);
}

// How far into the dips a hair brush's paint reaches (mm of relief it bridges): pressure bends the hairs in, a loaded
// brush's paint flows in. Lifting off (u_ends), the brush only grazes the high points: a dry, scratchy end, in streaks.
// Shared by the canvas pass and the brush pass (they must agree on what is touched).
float jb_pasteGive(float fillLoad) {
    float give = u_pressure * mix(0.12, 0.25, u_fillDips) + fillLoad * mix(0.3, 2.0 * u_thick + 0.2, u_fillDips);
    return u_ends > 0.5 && u_shape < 1.5 ? give * (1.0 - 0.6 * u_lift) : give;
}
float jb_pasteGraze(float hair) { return 0.06 * (1.0 - hair); }
// A lifting hair brush's dry end (Lead, 10-25: a middle zone of broken streaks that keep each bristle's line, thinning
// gradually, speckle growing toward the tip): every point has a catch score, mostly its run of hair's strength (the
// streaks), then how high the tooth stands there (relief: the surface here minus around, mm) and whether its face rises
// toward the travel (rise: ahead minus behind). As the pen eases off, the score a point needs climbs with u_lift, so the
// share that takes paint falls steadily: whole streaks, broken streaks, the strongest hairs on the tooth's tops, specks.
// Callers read `rise` only for hair brushes: the two extra surface reads cost a big trowel +55 % (10-24 A/B).
float jb_pasteDry(float tip, float relief, float rise, float toothMm) {
    if (u_ends < 0.5 || u_shape > 1.5 || u_lift <= 0.0) return 1.0;
    float tooth = max(0.5 * toothMm, 1e-3);   // (u_toothMm is declared by the passes, after this file)
    float score = 0.55 * tip + 0.3 * clamp(0.5 + relief / tooth, 0.0, 1.0) + 0.15 * clamp(0.5 + JB_CATCH * rise / tooth, 0.0, 1.0);
    float need = 0.22 + 0.62 * u_lift;
    return smoothstep(need - 0.07, need + 0.07, score);
}
// Riding on wet paint (u_cover): pressed lightly, a loaded brush sits on the wet layer instead of sinking to its target,
// and lays up to a coat above what is there; pressed hard, or on paint already piled thick, it digs in as before. Both
// passes call it with what they read of the canvas, so the brush loses what the canvas gains.
float jb_pasteRide(float target, float t, float open, float fillLoad) {
    if (u_cover <= 0.0) return target;
    float ride = u_cover * open * fillLoad * (1.0 - smoothstep(0.35, 1.0, u_pressure)) * (1.0 - smoothstep(1.5 * u_thick, 3.0 * u_thick, t));
    return max(target, mix(target, t + 0.4 * u_thick * fillLoad, ride));
}
// The share of a pixel's colour that a new coat of paint takes: by volume (stirred in), or, with u_cover, what an opaque
// coat that thick hides of the paint under it (S per mm of the brush's paint).
float jb_pasteCoat(float dep, float t, float Sb) {
    float w = dep / max(t + dep, 1e-6);
    return mix(w, max(w, 1.0 - exp(-Sb * dep)), u_cover);
}

// A lifting brush presses less, so it stops ploughing up the paint it has just laid (a thinner target must not scrape).
float jb_pastePlough() { return u_ends > 0.5 ? 1.0 - u_lift : 1.0; }

// A rigid blade presses its paint straight into the weave; only the layer above the canvas peaks builds up step
// by step. (Filling the valleys at the layer's slow rate left them 10–15 % short, and that shortfall showed as
// a grid of weave under thick knife paint.) Returns the part of a deficit that fills fast, and its rate.
float jb_pasteFast(float target, float t, float valleyMm) { return u_rigid * clamp(valleyMm - t, 0.0, max(target - t, 0.0)); }
float jb_pasteRateFast() { return 1.0 - exp(-8.0 * u_rate * u_slideMm / max(u_len, 0.2)); }

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

// ---- rigid blade ----
// Blade frame: xb along the edge from the point (0) toward the handle, yb across. The underside is a
// trowel: rounded point, widening to the full blade.
float jb_bladeHalf(float xb) {
    return u_bladeHalfW * mix(0.35, 1.0, smoothstep(0.0, 2.5 * u_bladeHalfW + 1.0, xb));
}
float jb_bladeCover(vec2 relMm, float aa, out float xb, out float yb) {
    xb = dot(relMm, u_bladeDir);
    yb = dot(relMm, vec2(-u_bladeDir.y, u_bladeDir.x));
    float half_ = jb_bladeHalf(xb);
    // Edges ramp over at least one step of travel, so a moving blade never shows a line per step.
    float aaS = max(aa, 1.2 * u_slideMm);
    float along = smoothstep(-0.3 * half_ - aaS, -0.3 * half_ + aaS, xb) * smoothstep(u_bladeLen + aaS, u_bladeLen - aaS, xb);
    return along * smoothstep(half_ + aaS, half_ - aaS, abs(yb));
}
// The blade's underside height above the canvas peaks (mm) at xb. A loaded knife chatters a little as it
// drags, so what it leaves is never machine-flat (u_lump, along the travel).
float jb_bladeHeight(float xb) { return u_bladeH0 + max(xb, 0.0) * u_bladeTan; }
float jb_bladeChatter(vec2 docPx) {
    vec2 sp = vec2(dot(docPx, u_travel) / 40.0, dot(docPx, vec2(-u_travel.y, u_travel.x)) / 120.0);
    return u_lump * 0.25 * (jb_vnoise2(sp, uint(u_seed) + 51u) - 0.5) * 2.0;
}
// The blade carries what it scrapes in cells along its edge (lane = position along the edge).
void jb_bladeRead(float xb, out vec4 c0, out vec4 c1) {
    float lane = clamp(xb / max(u_bladeLen, 1e-3), 0.0, 1.0) * (JB_LANES - 1.0);
    int i = int(floor(lane));
    int j = min(i + 1, int(JB_LANES) - 1);
    float f = lane - float(i);
    c0 = mix(texelFetch(u_brush0, ivec2(i, 0), 0), texelFetch(u_brush0, ivec2(j, 0), 0), f);
    c1 = mix(texelFetch(u_brush1, ivec2(i, 0), 0), texelFetch(u_brush1, ivec2(j, 0), 0), f);
}
// Where the blade's paint sits: down to its underside, plus the bead it pushes ahead of it (on the side
// facing the travel) and the paint squeezed out at the ends of the edge.
float jb_bladeTarget(float xb, float yb, float valleyMm, float load) {
    float base = max(jb_bladeHeight(xb) + valleyMm - u_bladePressMm, u_bladeFilm);
    float half_ = jb_bladeHalf(xb);
    vec2 n = vec2(-u_bladeDir.y, u_bladeDir.x);
    float front = dot(u_travel, n) >= 0.0 ? yb : -yb;               // + toward the travel side
    float frontBand = smoothstep(0.35 * half_, 0.95 * half_, front);
    float alongEdge = abs(dot(u_travel, u_bladeDir));                // moving along the edge: the bead is at its end
    float endBand = alongEdge * (dot(u_travel, u_bladeDir) >= 0.0 ? smoothstep(u_bladeLen * 0.8, u_bladeLen, xb)
                                                                    : 1.0 - smoothstep(0.0, 0.2 * u_bladeLen, xb));
    float carried = clamp(load / max(u_cellCap, 1e-6), 0.0, 3.0);
    return base + u_bladeBead * carried * max(frontBand * (1.0 - alongEdge), endBand);
}

// A loaded blade pressed lightly only lets go of paint where the surface stands high: a skim over the canvas
// peaks, broken. Firmer pressure squeezes its paint down into the valleys too (linear in pressure; the whole
// weave is reached by about half pressure). surfAboveMm: the surface's height above the canvas PEAK level (paint − valley depth).
// Measured from the peaks, not from the neighbours: a neighbour test let fresh paint from the same stroke make
// the empty spots beside it count as dips, and they never filled (a checkerboard).
float jb_bladeReach(float surfAboveMm, float toothMm) {
    if (u_bladeLoad < 0.5) return 1.0;
    return smoothstep(-0.015, 0.015, surfAboveMm + u_pressure * 2.2 * toothMm - 0.01);
}

ivec2 jb_pasteCell(float a, float b) {
    float lane = clamp((a + 1.0) * 0.5 * JB_LANES, 0.0, JB_LANES - 1.0);
    float depth = clamp(jb_pasteDepth(a, b) * JB_DEPTH, 0.0, JB_DEPTH - 1.0);
    return ivec2(int(lane), int(depth));
}
