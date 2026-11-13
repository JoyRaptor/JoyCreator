// TextLab: the letters themselves. Reads the field, finds what this pixel sees (the top of a
// letter, its side, or the floor behind), and paints it in the look's material.
//
// The solid: the letter's outline pushed back `uDepth` em, with a bevel rising `uBevelHeight` em
// from its edge, and (if asked) a raised FRAME round a face that sits lower. The eye may lean
// (uExtrude, perspective toward a vanishing point, the phone's tilt), which is what shows the
// sides; the pixel walks its ray down through the field until it lands.
//
// What makes it read as a made object and not a drawing: the room it mirrors (the material
// sphere, blurred for satin), the shadow the frame casts on its own face, a catch-light along
// each crest, and a little wear.
//
// Variants (#define, picked by the look so the phone compiles only what it draws):
//   MARCH_PASS / MARCH_READ  the engine's split of a marching look into two passes (it sets these, not the look)
//   FLAT_FACE  the engine's build of a look seen straight on: no march code at all
//   TUBE     glass tubes that light up (neon), no solid
//   REFRACT  see-through: bends what is behind (glass, water, ice)
//   DETAIL   relief on the face (hammered, brushed, ripples, plate, engine-turned, gem-set)
//   PATTERN  a printed pattern in the fill (dots, stripes, checks, grain)
//   EMBER    the body glows from cracks and takes light from the fire
in vec2 vUv;
out vec4 o;

uniform sampler2D uFieldTex;
uniform sampler2D uMatcap;   // the material sphere (with blurred copies in its mip levels)
uniform sampler2D uBack;     // what is behind the words, same rectangle as the field
uniform sampler2D uHeat;     // the fire, when the look has one
uniform float uHasBack;
#ifdef MARCH_READ
uniform sampler2D uMarch;    // the march pass's answer for each pixel: offset to the hit point, its height, hit * 2 + cover
#endif

// depth
uniform float uBevel;        // bevel width, em
uniform float uBevelHeight;  // em
uniform float uBevelShape;   // -1 scooped .. 0 chisel .. 1 round
uniform float uRim;          // a raised frame this wide inside the edge, em (0 = none)
uniform float uInset;        // how far the face sits below the frame, em
uniform float uDepth;        // how far the letter is pushed back, em
uniform vec2  uExtrude;      // which way the depth trails on screen, em per em of depth
uniform float uPersp;        // depth shrinking toward uVanish (negative = toward it, as a camera sees)
uniform vec2  uVanish;       // vanishing point, 0..1 of the field
uniform float uParallax;     // how much the tilt leans the eye
uniform float uAO;           // darkening in creases
uniform float uCast;         // shadow the frame and bevel cast on the letter's own face

// coats
uniform float uStroke;       // outer stroke width, em (part of the solid)
uniform vec3  uStrokeCol;    // the TRIM: the outer stroke and the raised frame
uniform float uStrokeMetal;
uniform float uInline;       // a drawn line inside the edge: width em
uniform float uInlineOff;    // its distance from the edge, em
uniform vec3  uInlineCol;
uniform vec2  uHover;        // the drawn line as its own element: moved this far off the letter (em), so it hovers over it
uniform vec3  uFillA;        // baseline
uniform vec3  uFillB;        // middle
uniform vec3  uFillC;        // cap height
uniform float uFillMid;      // where the middle sits, 0..1
uniform float uFillSharp;    // 0 = blended, 1 = hard bands

// material
uniform float uMetal;        // 0 = paint or plastic, 1 = mirror
uniform float uRough;        // 0 = mirror sharp .. 1 = satin (the room goes soft)
uniform float uGloss;        // the clear coat on paint and plastic
uniform float uAmbient;
uniform float uShine;        // highlight tightness
uniform float uSpec;         // highlight strength
uniform float uUnlit;        // 1 = flat poster colour, no lighting
uniform vec3  uEnvCurve;     // sphere lookup: sweep across the word, sweep up the letter, how hard the bevel turns it
uniform float uEnvTurn;      // turns the room, radians
uniform float uEnvTilt;      // how far the tilt slides the room
uniform vec2  uEnvJag;       // far mountains on the room's horizon: height, peaks per em
uniform vec2  uSweep;        // a bar of light crossing the word: strength, seconds between passes
uniform vec3  uLight;        // toward the lamp: x, y and its height
uniform vec4  uWear;         // catch-light on crests, scratches, grime in the creases, brushed grain
uniform float uMottle;       // the colour is not one colour: patches lighter and darker, as in stone or old paint

// sides
uniform vec3  uSideA;        // at the face
uniform vec3  uSideB;        // at the back
uniform float uSideBands;    // 0 = smooth, n = that many flat bands
uniform float uSideMetal;

// floor
uniform float uShadow;
uniform float uShadowSoft;   // em
uniform vec2  uShadowOff;    // em
uniform vec3  uShadowCol;
uniform float uGlow;
uniform float uGlowSize;     // em
uniform vec3  uGlowCol;

uniform float uOpacity;
uniform vec2  uReveal;       // how a letter arrives: x = fades in, y = fills up from its baseline

#ifdef TUBE
uniform vec4  uTube;         // centre line this far inside the outline (em), radius (em), core sharpness, 1 = the letter itself is the tube
uniform vec3  uTubeA;
uniform vec3  uTubeB;
uniform vec4  uTubeLight;    // gain, colour split across the word (0 blended .. 1 hard), flicker, buzz speed
uniform vec4  uGas;          // the gas is not even: how blotchy, blotches per em, how far the bright thread wanders, bands running along the tube
uniform vec2  uTie;          // tie wires that hold the tube: how many (0..1), spacing em
uniform float uTubeHand;     // blown and bent by hand: how much the tube's width wavers along its length
uniform float uTubePaint;    // what the field's a channel says: 1 = where the glass is painted black (tube letters); 2 = the distance to this loop's break (outline tubes)
#endif
#ifdef REFRACT
uniform vec4  uGlass;        // bend, colour fringing, tint strength, dark edge
uniform vec3  uTint;
uniform vec2  uCaustic;      // dancing light inside: strength, cells per em
uniform float uGather;       // light gathered on the far side from the lamp, as in a drop
uniform vec4  uIce;          // ice is not glass: milk and bubbles (low and deep), crystals and fissures, frosted patches, snow lying on top
uniform vec2  uChip;         // the crystals: how deep a chip bites the outline (em), crystals per em
uniform vec4  uIceB;         // more of what ice does: a fracture seen as a veil going into the block, light coming through its thin parts from behind, prism sparkles in its crystals, a wet skin
#endif
#ifdef DETAIL
uniform vec4  uDetail;       // kind, bumps per em, strength, drift speed
uniform float uDetailShade;  // the hollows of the relief are darker (dirt and shade collect there)
#endif
#ifdef PATTERN
uniform vec4  uPattern;      // kind, cells per em, amount, dots shrink toward the top
uniform vec3  uPatternCol;
uniform float uPatternTurn;
#endif
#ifdef EMBER
uniform vec4  uEmber;        // heat in the cracks, cracks per em, heat along the edge, light taken from the fire
uniform vec3  uFireA;
uniform vec3  uFireB;
uniform vec3  uFireC;
uniform float uFireToon;
#endif

#ifdef FUR
uniform vec4  uFur;          // fur: strand length past the edge (em), strands per em, 0 = stands straight out .. 1 = hangs down, sway
uniform vec3  uFurTip;       // strand tips (the roots take the fill)
#endif
#ifdef CELLS
uniform vec4  uCells;        // a board of lamps: lamps per em, 0 round .. 1 square, lamp size 0..1, chase speed
uniform float uBoard;        // the sign board the lamps sit in: this far in from the picture's edge (em); 0 = no board
#endif
#ifdef ARCS
uniform vec4  uArc;          // lightning round the outline: strength, how far it strays (em), jaggedness, strikes
uniform vec3  uArcCol;
#endif
#ifdef PAPER
uniform vec4  uPaper;        // cut paper: sheets, how much smaller each is (em), the shade each throws, paper grain
#endif
#ifdef BURN
uniform float uBurnGlow;     // burning away (as the FRONT passes): how hot the burning edge glows
#endif
#ifdef VINE
uniform vec4  uVine;         // a climbing vine: stems, their thickness (em), how far they stray from the outline (em), turns wound round each stroke per em
uniform vec3  uVineCol;
#endif
#ifdef STITCH
uniform vec4  uStitch;       // satin stitch: threads per em, how round each thread stands, stitch length em (0 = right across), ragged ends
#endif
#ifdef BALLOON
uniform vec4  uBalloon;      // a foil balloon: crinkle strength, crinkles per em along the seam, seam width (em), how far a crinkle runs in (em)
#endif
#ifdef SAND
uniform vec4  uSand;         // sand: grains per em, how far the edge crumbles (em), glassy grains that flash, loose grains lying round the foot
uniform vec4  uSandB;        // the stream of grains the FRONT sets loose: wind speed (x the front's own), how far it carries them (em), how far they lift (em), how the eddies shake them (em)
#endif
#ifdef BITS
uniform vec4  uBits;         // made of small things: pieces per em, kind (0 sequin, 1 bead, 2 sprinkle, 3 coin), size 0..1, how unevenly they tilt
uniform vec4  uBitsB;        // how far out of their rows, shimmer speed, colour variety, the shade each throws on the next
uniform vec3  uBitsBase;     // what they are sewn or scattered on
#endif
#ifdef FOG
uniform vec4  uFog;          // a steamed pane: how soft the world is behind it (0..7), how thick the mist, tiny drops per em, the bead of water along a wiped edge
uniform vec3  uFogCol;       // the colour of the mist
#endif
#ifdef FLAG
uniform vec4  uFlag;         // the word on a flying cloth: wave height (em), waves per em, speed, how far the cloth's edge is inside the picture (em)
uniform vec3  uFlagCol;      // the cloth
#endif
#ifdef STAINED
uniform vec4  uPane;         // stained glass: panes per em, lead width (em), the light behind, its light lying on the floor
#ifndef HOLO
uniform vec4  uFloor;        // .y = the floor line
#endif
#endif
#ifdef RUST
uniform vec4  uRust;         // paint over rusting steel: how much of the coat has failed, patch size, edges go first, rust carried down by rain
uniform vec4  uRustB;        // plates per em (0 = one piece), how much of the older coat shows round the bare steel, how strongly coats and rust stand in relief, patches of grey scale in the rust
uniform vec3  uRustUnder;    // the older coat under the paint
uniform vec3  uRustC;        // how much bright bare steel shows before rust begins, the paint's sheen, the bare steel's shine
#endif
#ifdef JELLY
uniform vec4  uJelly;        // jelly: how deep its colour is in the thick middle, light gathered on the far side, how it bends what is behind, how see-through
#endif
#ifdef INK
uniform vec4  uInk;          // ink on wet paper: how far it bleeds (em), feathering along the fibres, the dark line where it dried, granulation
uniform vec4  uInkB;         // a lighter dye runs this much further, backruns (pale blooms), wet shine, uneven brush load
uniform vec3  uInkHalo;      // the dye that runs furthest
#endif
#ifdef REFRACT
uniform vec4  uTank;         // a vessel: how full 0..1 (0 = empty glass), how much the liquid leans with the tilt, waves, bubbles
uniform vec3  uTankCol;
#endif
#ifdef STONE
uniform vec4  uStone;        // what makes stone look carved and of one block: chisel marks, fresh pale chips on broken edges, veins and strata, mineral grain
uniform vec2  uStoneB;       // what settles on it: dust on ledges that face up, moss in the deepest hollows
#endif
#ifdef HOLO
uniform vec4  uHolo;         // a projection of light, not a solid: strength, scan lines per em, scan speed, how often it slips
uniform vec4  uFloor;        // .y = the floor line: the projector's beam starts there
#endif

float fieldD(vec2 q) { return texture(uFieldTex, q / uField.xy).r + uStroke; }

float bevelProfile(float t) {
    float s = uBevelShape;
    if (s >= 0.0) { float a = 1.0 + 3.0 * s; return pow(1.0 - pow(1.0 - t, a), 1.0 / a); }
    float a = 1.0 - 3.0 * s;
    return 1.0 - pow(1.0 - pow(t, a), 1.0 / a);
}
float insetWidth() { return max(uBevel * 0.5, 1.5 / uField.z); }
float heightAt(float d) {
    float h = uBevelHeight * bevelProfile(sat(d / max(uBevel, 1e-4)));
    if (uRim > 0.0) {
        float t = sat((d - uRim) / insetWidth());
        h -= uInset * t * t * (3.0 - 2.0 * t);
    }
    return h;
}

vec2 fieldGrad(vec2 q) {
    float e = max(1.0 / uField.z, 0.006);   // never finer than a letter's own distance picture
    return vec2(fieldD(q + vec2(e, 0.0)) - fieldD(q - vec2(e, 0.0)),
                fieldD(q + vec2(0.0, e)) - fieldD(q - vec2(0.0, e))) / (2.0 * e);
}

// What the ray lands on: 0 nothing, 1 the top, 2 a side. `cover` softens the outline.
int march(vec2 p0, vec2 lean, out vec2 q, out float z, out float cover) {
    float aaw = 1.0 / uField.z;
    float L = length(lean);
    float top = uBevelHeight, bottom = -uDepth;
    cover = 1.0;
#ifndef FLAT_FACE
    if (L < 1e-4 || top - bottom < 1e-4)
#endif
    {
        // seen straight on (the engine builds a look with no slant as FLAT_FACE: only this, no march at all)
        q = p0;
        float d = fieldD(q);
        z = heightAt(max(d, 0.0));
        cover = sat(d / aaw + 0.5);
        return cover > 0.0 ? 1 : 0;
    }
#ifndef FLAT_FACE
    float minStep = (top - bottom) / 48.0;
    float best = -1e9; vec2 bestQ = p0; float bestZ = 0.0;
    bool inside = true, last = false;
    float zPrev = top;
    z = top;
    for (int i = 0; i < 36; i++) {
        q = p0 + lean * z;
        float d = fieldD(q);
        if (d > 0.0) {
            if (!inside) {
                // just came through the outline from outside: find where
                float za = zPrev, zb = z;
                for (int k = 0; k < 6; k++) {
                    float zm = 0.5 * (za + zb);
                    if (fieldD(p0 + lean * zm) > 0.0) zb = zm; else za = zm;
                }
                z = zb; q = p0 + lean * z;
                if (z <= 0.0) return 2;
                d = max(fieldD(q), 0.0);
                inside = true;
            }
            float h = heightAt(d);
            if (z <= h + 1e-4) { z = h; return 1; }
            zPrev = z;
            z -= max((z - h) * 0.6, minStep);
        } else {
            if (d > best) { best = d; bestQ = q; bestZ = min(z, 0.0); }
            inside = false;
            zPrev = z;
            z -= max(-d / L, minStep);
        }
        if (z < bottom) {
            if (last) break;
            z = bottom; last = true;
        }
    }
    cover = sat(best / aaw + 1.0);
    if (cover > 0.0) { q = bestQ; z = bestZ; return 2; }
    return 0;
#endif
}

vec3 fillAt(float ly) {
    float t = sat(ly);
    float m = clamp(uFillMid, 0.02, 0.98);
    float w = mix(1.0, 0.03, uFillSharp);
    float lo = smoothstep(0.5 - 0.5 * w, 0.5 + 0.5 * w, sat(t / m));
    float hi = smoothstep(0.5 - 0.5 * w, 0.5 + 0.5 * w, sat((t - m) / (1.0 - m)));
    return mix(mix(uFillA, uFillB, lo), uFillC, hi);
}

// The room, seen in a surface facing n. `rough` 0..1 reads a blurred copy of the sphere.
vec3 envAt(vec3 n, vec2 c, vec2 q, float rough) {
    vec2 m = n.xy * uEnvCurve.z + c * uEnvCurve.xy + uTilt * uEnvTilt;
    if (uEnvJag.x > 0.0) {
        float j = vnoise(vec2(q.x * uEnvJag.y, 3.7)) * 0.65 + vnoise(vec2(q.x * uEnvJag.y * 2.7, 9.1)) * 0.35 - 0.5;
        m.y += j * uEnvJag.x * exp(-sq(m.y / 0.3));
    }
    m = rot(uEnvTurn) * m;
    float l = length(m);
    if (l > 0.97) m *= 0.97 / l;
    return textureLod(uMatcap, m * 0.5 + 0.5, rough * 5.5).rgb;
}

// Is this point of the face in the shadow of something taller toward the lamp?
float castShadow(vec2 q, float h0) {
    vec2 Ld = normalize(uLight.xy + vec2(1e-4, 0.0));
    float rise = uLight.z / max(length(uLight.xy), 1e-3);
    float sh = 0.0;
    for (int i = 1; i <= 4; i++) {
        float s = 0.006 * float(i) * float(i);
        float hh = heightAt(max(fieldD(q + Ld * s), 0.0));
        sh = max(sh, sat((hh - h0 - s * rise) / (0.003 + s * 0.3)));
    }
    return sh;
}

#ifdef PATTERN
float pattern(vec2 q, float ly) {
    vec2 g = rot(uPatternTurn) * q * uPattern.y;
    float aw = uPattern.y / uField.z;
    int kind = int(uPattern.x + 0.5);
    if (kind == 1) { float r = 0.36 * mix(1.0, sat(1.0 - ly), uPattern.w); return smoothstep(r + aw, r - aw, length(fract(g) - 0.5)); }
    if (kind == 2) return smoothstep(0.25 + aw, 0.25 - aw, abs(fract(g.y) - 0.5));
    if (kind == 3) { vec2 k = step(0.5, fract(g * 0.5)); return abs(k.x - k.y); }
    return vnoise(g * 6.0);
}
#endif

#ifdef DETAIL
float detail(vec2 q) {
#ifdef DETAIL_KIND
    const int kind = DETAIL_KIND;                 // the engine may build a look for its one kind
#else
    int kind = int(uDetail.x + 0.5);
#endif
    vec2 g = q * uDetail.y;
    if (kind == 1) return fbm(g);                                              // cast, rough
    if (kind == 2) { vec3 v = voronoi(g); return v.x * v.x; }                  // hammered
    if (kind == 3) return vnoise(vec2(g.x * 0.03, g.y * 3.0));                 // brushed
    if (kind == 4) return fbm2(g + vec2(0.0, -uTime * uDetail.w))              // ripples
                        + 0.5 * fbm2(g * 2.3 + vec2(uTime * uDetail.w * 0.7, 4.0));
    if (kind == 5) { vec3 v = voronoi(g); return smoothstep(0.0, 0.14, v.y - v.x); }   // plates and grooves
    if (kind == 6) {                                                           // tread plate
        vec2 cell = floor(g), l = fract(g) - 0.5;
        float flip = mod(cell.x + cell.y, 2.0) * 2.0 - 1.0;
        l = rot(flip * 0.785) * l;
        return 1.0 - smoothstep(0.55, 1.0, length(l * vec2(2.0, 6.5)));
    }
    if (kind == 7) {                                                           // engine-turned: rings cut from every cell centre, overlapping
        vec2 cell = floor(g), l = fract(g);
        float h = 0.0;
        for (int y = -1; y <= 1; y++) for (int x = -1; x <= 1; x++) {
            float r = length(l - vec2(float(x), float(y)) - 0.5);
            h = max(h, (0.5 + 0.5 * cos(r * 34.0)) * (1.0 - smoothstep(0.55, 0.95, r)));
        }
        return h * 0.35;
    }
    if (kind == 8) {                                                           // gem-set: every cell a little cut stone
        vec3 v = voronoi(g);
        return (1.0 - smoothstep(0.0, 0.5, v.x)) * 0.6 + v.z * 0.4 * step(v.x, 0.42);
    }
    if (kind == 10) {                                                          // cut stones: each a brilliant, a flat table ringed by eight slanted facets
        vec2 ci = floor(g), cf = fract(g);
        vec2 v = vec2(8.0);
        float id = 0.0;
        for (int y = -1; y <= 1; y++) for (int x = -1; x <= 1; x++) {
            vec2 o = vec2(float(x), float(y));
            vec2 c = cf - o - 0.5 - (hash22(ci + o) - 0.5) * 0.22;             // nearly a grid: stones are set in rows
            if (dot(c, c) < dot(v, v)) { v = c; id = hash21(ci + o); }
        }
        float rad = length(v);
        if (rad > 0.46) return 0.0;                                            // the metal between the stones
        if (rad < 0.19) return 0.42;                                           // the table
        float turn = id * TAU / 8.0;
        float sector = floor((atan(v.y, v.x) + PI + turn) / TAU * 8.0);
        float ang = (sector + 0.5) / 8.0 * TAU - PI - turn;
        return 0.42 - (dot(v, vec2(cos(ang), sin(ang))) - 0.19) * 1.5 + (hash11(sector + id * 31.0) - 0.5) * 0.07;
    }
    if (kind == 9) {                                                           // living rock: worn lumps, pits, a few deep cracks
        // (rock is no mirror, so the cheap texture noise is fine here: three times faster on the phone)
        float lumps = tfbm(g * 0.35) * 0.9 + tfbm(g * 1.3 + 7.0) * 0.35;
        float pits = smoothstep(0.62, 0.9, tnoise(g * 3.1 + 2.0)) * 0.25;
        vec3 v = voronoi(g * 0.22 + tfbm2(g * 0.5) * 0.6);
        float crack = smoothstep(0.0, 0.035, v.y - v.x);                       // 0 down in a crack
        return (lumps - pits) * 0.5 * (0.3 + 0.7 * crack);
    }
    return 0.0;
}
#endif

void main() {
    vec2 p0 = vUv * uField.xy;
    float aw = 1.0 / uField.z;
    vec3 col = vec3(0.0), add = vec3(0.0);
    float a = 0.0;
#ifdef HOLO
    // a projector slips now and then: a band of the picture jumps sideways for a moment
    float slipBand = floor(p0.y * 9.0 + uTime * 2.0);
    p0.x += step(0.9, hash21(vec2(slipBand, floor(uTime * 8.0)))) * (hash21(vec2(slipBand, 7.0)) - 0.5) * 0.14 * uHolo.w;
#endif

#ifdef TUBE
    // NEON. A round rod of bent glass with gas glowing inside it; not an outline with a glow.
    vec4 f = texture(uFieldTex, vUv);
    float seed = hash11(f.b * 91.7);
    float R = max(uTube.y, 1e-4);
    // glass is blown, then bent hot over a flame: no tube keeps one exact width along its length
    float fr = f.r + uTubeHand * R * 0.22 * (tfbm2(p0 * 4.5 + seed * 7.0) - 0.5);
    float away = uTube.w > 0.5 ? max(R - fr, 0.0) : abs(fr - uTube.x);     // distance from the tube's centre line
    if (uTubePaint > 1.5 && uTube.w < 0.5) {
        // An outline tube is never an endless ring. Each loop has a break where its two ends turn
        // back into the wall, and from the front each end is round.
        float toEnd = R * 2.1;                                    // from the break to the middle of each round end
        float along = sqrt(max(f.a * f.a - away * away, 0.0));    // how far along the tube from the break
        if (along < toEnd) away = length(vec2(toEnd - along, away));
    }
    float td = uTube.w > 0.5 ? -fr : away - R;                              // below 0 = inside the glass
    float rr = sat(away / R);                                               // 0 on the centre line .. 1 at the glass
    float inT = smoothstep(-aw, aw, -td);
    vec2 gdir = normalize(fieldGrad(p0) + vec2(1e-5, 0.0));
    vec2 outward = gdir * ((uTube.w < 0.5 && fr > uTube.x) ? 1.0 : -1.0);   // from the centre line toward the glass
    vec2 tang = vec2(-gdir.y, gdir.x);                                      // along the tube
    vec3 n = normalize(vec3(outward * rr, sqrt(max(1.0 - rr * rr, 0.0)) + 1e-3));   // which way the rod's surface faces

    // the gas: it gathers and thins along the tube and drifts, and a brighter thread wanders in it
    float blot = tfbm(p0 * uGas.y + vec2(uTime * 0.045, -uTime * 0.07) + seed * 17.0);
    float fine = tnoise(p0 * uGas.y * 3.3 + vec2(-uTime * 0.19, uTime * 0.12));
    float gas = max(1.0 + uGas.x * ((blot - 0.5) * 1.7 + (fine - 0.5) * 0.5), 0.2);
    float side = dot(outward, vec2(0.6, 0.8)) * rr;                         // -1..1 across the tube
    float wander = (tnoise(p0 * 1.7 + vec2(uTime * 0.23, seed * 9.0)) - 0.5) * 1.4 * uGas.z;
    float thread = exp(-sq((side - wander) / 0.45));

    // switching on: the discharge catches in patches and stutters before it holds; afterwards an
    // unlucky tube buzzes now and then, and faint bands run along every tube
    float tick = floor(uTime * 24.0);
    float strike = f.g;
    float where = tfbm(p0 * 2.3 + seed * 31.0);
    float caught = smoothstep(where - 0.12, where + 0.1, strike * 1.3 - 0.1);
    float stutter = step(hash21(vec2(tick, seed * 77.0)), 0.2 + strike * strike);
    float lit = caught * mix(stutter, 1.0, smoothstep(0.8, 1.0, strike)) * step(0.02, strike);
    float buzz = step(0.93, vnoise(vec2(uTime * uTubeLight.w + seed * 40.0, seed * 9.0))) * step(seed, 0.4);
    lit *= 1.0 - uTubeLight.z * buzz * (0.4 + 0.6 * hash21(vec2(tick, seed)));
    lit *= 1.0 - uGas.w * 0.25 * smoothstep(0.5, 0.9, tnoise(vec2(dot(p0, tang) * 2.2 - uTime * 1.3, seed * 3.0 + dot(p0, gdir) * 0.3)));

    // painted glass lets no light out: the ends, where the tube turns back to its electrode
    float bare = (uTubePaint > 0.5 && uTubePaint < 1.5) ? smoothstep(0.35, 0.65, f.a) : 1.0;
    float lit0 = lit;      // the tube's light before the paint: what its glow and the wall still get from the bare glass beside it
    lit *= bare;
    float cx = sat(f.b);
    vec3 tc = mix(uTubeA, uTubeB, smoothstep(0.5 - 0.5 * (1.0 - uTubeLight.y), 0.5 + 0.5 * (1.0 - uTubeLight.y) + 1e-3, cx));
    // light through a round rod of glowing gas: most where the eye looks through the most of it,
    // the gas's own deep colour at the wall, washed toward white down the middle
    float chord = sqrt(max(1.0 - rr * rr, 0.0));
    float bore = smoothstep(1.0, 0.84, rr);
    vec3 hotc = mix(tc, vec3(1.0, 0.86, 0.7), 0.17);
    vec3 glowc = mix(tc * 0.5, hotc * 1.4, pow(chord, uTube.z) * (0.4 + 0.6 * thread));
    vec3 hot = glowc * gas * uTubeLight.x * (0.3 + 0.7 * bore);
    // the glass: a hard window-light along the lamp side, a softer one below, a darker limb
    float spec = pow(max(dot(n, normalize(vec3(-0.45, 0.75, 1.6))), 0.0), 110.0);
    float spec2 = pow(max(dot(n, normalize(vec3(0.5, -0.7, 1.5))), 0.0), 34.0) * 0.22;
    float limb = pow(max(1.0 - n.z, 0.0), 2.5);
    vec3 glass = vec3(0.012, 0.013, 0.016) * (0.4 + chord) + tc * 0.012 + vec3(0.75, 0.82, 0.9) * (spec * 1.3 + spec2 + limb * 0.07);
    glass = mix(vec3(0.004, 0.004, 0.005) + vec3(0.5, 0.52, 0.55) * spec * 0.5, glass, bare);   // black paint, a dull shine
    col = mix(glass, hot * (1.0 - 0.5 * limb) + vec3(1.0, 0.97, 0.94) * (spec * 1.3 + spec2 * 0.4), lit);

    // tie wires: thin twists of wire across the tube where it is held to its stand-offs
    vec2 cellId = floor(p0 / max(uTie.y, 1e-3));
    vec2 c0 = (cellId + 0.2 + 0.6 * hash22(cellId + 4.7)) * uTie.y;
    vec2 dv = p0 - c0;
    float nearTube = 1.0 - smoothstep(R * 0.12, R * 0.3, td);
    float wire = step(hash21(cellId + seed * 13.0), uTie.x) * (1.0 - smoothstep(0.0035, 0.007, abs(dot(dv, tang))))
               * (1.0 - smoothstep(R * 1.05, R * 1.25, length(dv))) * nearTube;
    col = mix(col, vec3(0.025, 0.025, 0.03) + vec3(0.3) * spec, wire * 0.93);
    a = max(inT, wire);

    float halo = exp(-max(td, 0.0) / max(uGlowSize, 1e-4));
    add = tc * uGlow * halo * halo * lit0 * gas * (1.0 - a);
    // the tubes stand off the wall, so they shade it (less where they are lit: their own light fills it)
    float under = 0.0;
    if (uShadow > 0.0) {
        float fs = texture(uFieldTex, vUv - uShadowOff / uField.xy).r;
        float aways = uTube.w > 0.5 ? R - fs : abs(fs - uTube.x);
        under = (1.0 - smoothstep(0.0, max(uShadowSoft, aw), aways - R)) * uShadow * (1.0 - 0.45 * lit0);
    }
    a *= uOpacity;
    add *= uOpacity;
    under *= uOpacity * (1.0 - a);
    o = vec4(col * a + uShadowCol * under + add, a + under);
    return;
#else

#ifdef CELLS
    {
        // A BOARD OF LAMPS. The words exist only as rows of bulbs, each lit or not by whether its middle
        // falls in a letter. A bulb is glass in a socket: lit, a white-hot middle inside coloured glass;
        // unlit, dark glass that still catches the room. A wave of brightness chases along the rows.
        float N = uCells.x, R = uCells.z;
        vec2 cell = floor(p0 * N), mid = (cell + 0.5) / N;
        vec4 fc = texture(uFieldTex, mid / uField.xy);
        float on = smoothstep(-0.15 / N, 0.15 / N, fc.r) * fc.g;
        vec2 l = (p0 - mid) * N * 2.0;                                       // -1..1 across the lamp's square
        float shape = mix(length(l), max(abs(l.x), abs(l.y)), uCells.y);
        float px = 2.0 * N / uField.z;                                       // one picture pixel, in lamp units
        float bulb = smoothstep(R, R - max(0.1, px), shape);
        float socket = smoothstep(R + 0.2, R + 0.2 - max(0.06, px), shape) * (1.0 - bulb);
        float chase = mix(1.0, 0.5 + 0.5 * sin((cell.x + cell.y * 0.5) * 0.9 - uTime * uCells.w * 6.0), step(0.001, uCells.w) * 0.55);
        float tw = 0.93 + 0.07 * hash21(cell + floor(uTime * 12.0));
        vec3 lampC = fillAt(fc.a);
        float rr = sat(shape / R);
        vec3 litC = mix(mix(lampC, vec3(1.0, 0.96, 0.88), 0.6) * 2.4, lampC * 1.5, smoothstep(0.0, 0.6, rr)) * (1.0 - 0.5 * rr * rr);
        vec3 offC = lampC * 0.045 * (1.0 - 0.6 * rr) + 0.012;
        vec2 g = l - vec2(-0.3, 0.34) * R;
        float glint = exp(-dot(g, g) / (0.035 * R * R));
        vec3 glass = mix(offC, litC * chase * tw, on) + glint * mix(0.2, 0.7, on);
        vec2 inset = min(p0, uField.xy - p0) - uBoard;
        float edge = min(inset.x, inset.y);
        float board = uBoard > 0.0 ? smoothstep(0.0, 1.5 / uField.z, edge) : smoothstep(-0.24, -0.1, fc.r);
        vec3 boardC = vec3(0.02, 0.018, 0.016) * (0.7 + 0.6 * tnoise(p0 * 60.0));
        vec3 sock = vec3(0.1, 0.09, 0.08) * (0.35 + 0.9 * sat(dot(normalize(l + 1e-4), vec2(-0.6, 0.8)) * 0.5 + 0.5));
        col = mix(mix(boardC, sock, socket), glass, bulb);
        col += lampC * on * chase * 0.4 * exp(-max(shape - R, 0.0) * 3.0) * (1.0 - bulb);        // its light on the board
        if (uBoard > 0.0) col = mix(col, vec3(0.14, 0.12, 0.1) * (0.6 + 0.8 * sat(inset.y - inset.x + 0.5)), smoothstep(0.035, 0.03, edge)); // a metal frame
        a = board * uOpacity;
        o = vec4(col * a, a);
        return;
    }
#endif
#ifdef FOG
    {
        // A STEAMED WINDOW. The pane covers the whole picture. Behind the mist the world is soft and pale, and
        // the mist itself is thousands of tiny drops; where a finger wrote the glass is clear and the world
        // sharp, a little of the damp left in streaks, a bead of water pushed up along the stroke's edge.
        // (Runs trickling down from the letters are the look's own drips: true shapes in the field.)
        vec4 ff = texture(uFieldTex, vUv);
        float d = ff.r + uStroke + (tnoise(p0 * 38.0) - 0.5) * 0.014;            // a fingertip's edge is not clean
        float come = smoothstep(0.0, 0.6, ff.g);
#ifdef PEN
        come = step((clamp(ff.a - 1.0, 0.0, 1.0)) * 0.9, ff.g);                  // the finger writes it: clear only where it has been
#endif
        float wiped = smoothstep(-0.005, 0.005, d) * come;
        vec3 sharp = texture(uBack, vUv).rgb;
        vec3 soft = textureLod(uBack, vUv, uFog.x).rgb;
        vec3 misted = mix(soft, uFogCol, sat(uFog.y * (0.8 + 0.5 * (tfbm(p0 * 1.2 + 4.0) - 0.5))));
        vec3 dv = voronoi(p0 * uFog.z);
        float drop = smoothstep(0.3, 0.2, dv.x) * step(0.4, dv.z);                // every drop is a little window of its own
        misted = mix(misted, mix(soft, textureLod(uBack, vUv, 2.0).rgb, 0.6) * 1.08, drop * 0.55);
        misted *= 1.0 - 0.16 * smoothstep(0.2, 0.28, dv.x) * smoothstep(0.34, 0.3, dv.x) * step(0.4, dv.z);      // each has a thin dark rim
        misted += vec3(0.5) * smoothstep(0.09, 0.02, dv.x) * drop * 0.1;                                         // and a point of light
        float smear = tnoise(vec2(p0.x * 4.0, p0.y * 70.0));
        vec3 clearC = mix(sharp, soft, 0.18 * smear) * (0.9 + 0.1 * smear);
        col = mix(misted, clearC, wiped);
        col += vec3(0.85, 0.9, 1.0) * exp(-sq(d / 0.007)) * come * (0.5 + 0.5 * step(0.5, tnoise(p0 * 70.0))) * uFog.w * 0.22;
        a = uOpacity;
        o = vec4(col * a, a);
        return;
    }
#endif
#ifdef FLAG
    {
        // A FLAG. The word is printed on a cloth that flies from its left edge: the wave is small at the hoist
        // and grows toward the free end; a fold facing the lamp is bright and its far side dark; it is hemmed.
        float inset = uFlag.w, wide = max(uField.x - 2.0 * inset, 0.1);
        vec2 pw = p0;
        float slope = 0.0;
        for (int i = 0; i < 2; i++) {                                              // where on the flat cloth this point of the picture comes from
            float ph = pw.x * uFlag.y * TAU - uTime * uFlag.z + pw.y * 0.9;
            float amp = uFlag.x * (0.12 + 0.88 * sat((pw.x - inset) / wide));
            pw = p0 - vec2(0.0, amp * (sin(ph) + 0.35 * sin(ph * 2.3 + 1.7)));
            slope = amp * uFlag.y * TAU * (cos(ph) + 0.8 * cos(ph * 2.3 + 1.7));
        }
        vec2 toEdge = min(pw - inset, uField.xy - inset - pw);
        float nearest = min(toEdge.x, toEdge.y);
        vec4 fd = texture(uFieldTex, pw / uField.xy);
        float ink = smoothstep(-aw, aw, fd.r + uStroke) * fd.g;
        float weave = 0.9 + 0.1 * sin(pw.x * 520.0) * sin(pw.y * 520.0) + (tnoise(pw * 60.0) - 0.5) * 0.08;
        vec3 cloth = mix(uFlagCol, fillAt(fd.a), ink) * weave;
        float hem = smoothstep(0.05, 0.04, nearest);
        cloth *= 1.0 - 0.22 * hem + 0.14 * hem * step(0.5, fract((toEdge.x < toEdge.y ? pw.y : pw.x) * 26.0));     // the hem and its stitches
        col = cloth * clamp(1.0 - slope * 1.5, 0.3, 1.7) + pow(sat(1.0 - abs(slope + 0.3) * 1.6), 6.0) * 0.2;
        a = smoothstep(0.0, 1.5 * aw, nearest) * uOpacity;
        o = vec4(col * a, a);
        return;
    }
#endif
#ifdef INK
    {
        // INK ON WET PAPER. The letter is where the brush touched, and stays the deepest part. From its edge
        // the ink creeps outward: far where the paper was wetter, and in fine feathers, each running straight
        // out from the edge to its own length, as ink wicks along fibres. It thins smoothly as it goes and
        // leaves a faint darker line where it stopped and dried. A lighter dye runs on beyond it. It takes
        // light away from whatever is behind it (paper, or a video): thin ink is pale and clear, thick is deep.
        vec4 fi = texture(uFieldTex, vUv);
        float tooth = tfbm(p0 * 22.0);                                           // the paper's own hills and valleys
        float d = fi.r + uStroke;
        float out_ = max(-d, 0.0);                                               // how far outside the brush mark
#ifdef PEN
        // WRITTEN BY HAND: the field also says how far along the pen's path each point is. The pen spends the
        // first part of a letter's arrival writing it; a point begins to exist only when the pen reaches it.
        float arcAt = clamp(fi.a - 1.0, 0.0, 1.0);
        float since = sat((fi.g - arcAt * 0.62) / 0.38);
        float nib = exp(-sq((fi.g - arcAt * 0.62) / 0.014)) * step(fi.g, 0.999);    // where the pen is at this moment
#else
        float since = sat(fi.g);                                                 // how long this letter has been on the paper
#endif
        float crept = sqrt(since);                                               // ink runs fast at first, then slows
        vec2 g = fieldGrad(p0);
        vec2 foot = p0 - g / max(length(g), 1e-4) * d;                           // the point of the edge this ink came from
        float wetter = smoothstep(0.25, 0.75, tfbm(p0 * 3.2 + 3.0));
        float ray = mix(tfbm2(foot * 9.0), tnoise(foot * 40.0 + 9.0), 0.35);     // one number per ray out of the edge
        ray = mix(0.5, ray, smoothstep(0.45, 0.9, length(g)));                   // (where two edges' ink meets, neither ray rules)
        float lead = mix(1.0, (0.3 + 1.3 * wetter) * (0.4 + 1.2 * ray), uInk.y);
        float reach = clamp(uInk.x * crept * lead, 1e-4, 0.14);
        float t = out_ / reach;                                                  // 0 at the mark .. about 1 where the ink gives out
        float inMark = smoothstep(-0.004, 0.004, d);
        float window = smoothstep(0.23, 0.15, out_);                             // the field knows nothing further out than this
        float bled = 0.62 * exp(-t * t * 2.3) * window;
        bled += uInk.z * 0.2 * exp(-sq((bled - 0.16) / 0.06)) * smoothstep(0.0, 0.3, crept) * window;     // the tide line
        // in the mark: an uneven brush load, pigment drawn to its edge, pale backruns with a crinkled rim
        float run = tfbm(p0 * 2.3 + 31.0);
        float mark = (1.0 + uInkB.w * (tfbm(p0 * 1.6 + 11.0) - 0.5) * 0.8) * (0.88 + 0.2 * exp(-max(d, 0.0) / 0.012))
                   * (1.0 - 0.4 * smoothstep(0.5, 0.62, run) * uInkB.y) + 0.14 * exp(-sq((run - 0.56) / 0.02)) * uInkB.y;
        float body = mix(bled, mark, inMark);
        body *= 1.0 + uInk.w * ((0.5 - tooth) * 0.45 + (tnoise(p0 * 240.0) - 0.5) * 0.08);     // pigment lies in the paper's valleys
        // the lighter dye: thinner, further, and seen only where the ink itself has given out
        float t2 = out_ / clamp(reach * (1.0 + uInkB.x * 1.4) + uInkB.x * 0.02 * crept, 1e-4, 0.2);
        float halo = exp(-t2 * t2 * 2.0) * (0.5 + 0.3 * exp(-sq((t2 - 0.8) / 0.12))) * (1.0 - inMark) * window
                   * sat(1.0 - bled * 1.5) * step(1e-4, uInkB.x);
        float show = smoothstep(0.0, 0.06, since);
#ifdef PEN
        body += nib * 0.7 * inMark;                                              // wet ink under the nib
        vec3 inkCol = fillAt(arcAt);
#else
        vec3 inkCol = fillAt(fi.a);
#endif
        vec3 through = exp(-(max(body, 0.0) * (1.0 - inkCol) * 3.0 + halo * (1.0 - uInkHalo) * 1.5) * show);
        vec3 paper = mix(vec3(0.86, 0.83, 0.76), texture(uBack, vUv).rgb, uHasBack);
        float wetShine = uInkB.z * (1.0 - since) * pow(tnoise(p0 * 26.0 + vec2(uTime * 0.05, 0.0)), 5.0) * sat(body) * 0.6;
        a = smoothstep(0.0, 0.02, 1.0 - min(through.r, min(through.g, through.b))) * uOpacity;
        o = vec4((paper * through + wetShine) * a, a);
        return;
    }
#endif
    vec2 lean = uExtrude + (vUv - uVanish) * uField.xy * uPersp + uTilt * uParallax;
    vec2 q; float z; float cover;
#ifdef MARCH_PASS
    // the first of two passes: only where this pixel's ray meets the letter (32-bit, so the second pass gets the
    // very numbers it would have worked out itself)
    {
        int hitM = march(p0, lean, q, z, cover);
        o = vec4(q - p0, z, float(hitM) * 2.0 + cover);
        return;
    }
#endif
#ifdef MARCH_READ
    vec4 mr = texture(uMarch, vUv);
    q = p0 + mr.xy; z = mr.z;
    int hit = int(floor(mr.w * 0.5));
    cover = mr.w - float(hit) * 2.0;
#else
    int hit = march(p0, lean, q, z, cover);
#endif
#ifdef RUST
    // Rust tips its surface by how its heights change from pixel to pixel (dFdx / dFdy below). A GPU works that out
    // across each 2x2 block of pixels, and it is only defined where the pixels it compares all reach that line: at
    // the letter's outline one of them has missed the letter, and the Note 9 gave a different answer each draw
    // there (2026-11-13). So the change is taken only along rows and columns where both pixels landed on the face.
    // (The pair compared need not include this pixel, so the test is weighted by position: the change across the
    // pair is exactly 1 where both are on the face, 0 where neither is, 8 or more where only one is.)
    float onTop = hit == 1 ? 1.0 : 0.0;
    vec2 rustBoth = abs(vec2(dFdx(onTop * (gl_FragCoord.x + 8.0)), dFdy(onTop * (gl_FragCoord.y + 8.0))));
    bvec2 rustSlopeOk = lessThan(abs(rustBoth - 1.0), vec2(0.01));     // worked out here, where every pixel is running
#endif

    if (hit != 0) {
        vec4 f = texture(uFieldTex, q / uField.xy);
        float dRaw = f.r;
        float d = dRaw + uStroke;
        vec2 g = fieldGrad(q);
        float gl = length(g);
        vec2 gn = gl > 1e-5 ? g / gl : vec2(0.0, 1.0);
        vec2 c = vec2(q.x / uField.x * 2.0 - 1.0, clamp(f.a, -0.25, 1.25) * 2.0 - 1.0);
        vec3 L = normalize(uLight);
        vec2 Ld = normalize(uLight.xy + vec2(1e-4, 0.0));
        vec3 n;
        if (hit == 1) {
            float wi = insetWidth();
            float onFace = uRim > 0.0 ? smoothstep(-aw, aw, d - uRim - wi * 0.5) : 1.0;   // inside the frame
            float trim = 1.0 - smoothstep(-aw, aw, dRaw) * onFace;                        // the stroke and the frame
            float slope = (heightAt(d + aw) - heightAt(d - aw)) / (2.0 * aw);
            n = normalize(vec3(-slope * gn * min(gl, 1.5), 1.0));
            float relief = 1.0;
#ifdef DETAIL
            float h0 = detail(q);
            relief = h0;
            vec2 dg = vec2(detail(q + vec2(aw, 0.0)) - h0, detail(q + vec2(0.0, aw)) - h0) / aw;
            n = normalize(vec3(n.xy - dg * uDetail.z * (1.0 - trim), n.z));
#endif
#ifdef STONE
            if (uStone.x > 0.0) {
                // chisel marks: short parallel cuts, all one way across a face and along the edge on a bevel
                float onBevel = 1.0 - smoothstep(0.0, max(uBevel, aw), d);
                vec2 along = normalize(mix(vec2(0.86, 0.5), vec2(-gn.y, gn.x), onBevel));
                vec2 across = vec2(-along.y, along.x);
                float mark = tnoise(vec2(dot(q, across) * 90.0, dot(q, along) * 7.0 + tnoise(q * 3.0) * 2.0));
                n = normalize(vec3(n.xy + across * (mark - 0.5) * 0.9 * uStone.x * (0.35 + 0.65 * tnoise(q * 2.0 + 5.0)), n.z));
            }
#endif
            // brushed grain: fine streaks that tip the surface a hair, so highlights smear along it
            float grain = 0.0;
            if (uWear.w > 0.0) {
                grain = tnoise(vec2(q.x * 9.0, q.y * 520.0)) - 0.5;
                n = normalize(vec3(n.x, n.y + grain * 0.22 * uWear.w * (1.0 - trim * 0.6), n.z));
            }
#ifdef STITCH
            // SATIN STITCH: threads laid side by side straight across each stroke, turning at a corner with
            // a mitre. Each is a twisted strand standing round off the cloth, with its own gleam; its ends
            // go down into the cloth a little short or long of the drawn line.
            bool sideways = abs(gn.x) > abs(gn.y) * 0.8;                              // across an upright stroke they run left to right
            float sAlong = sideways ? q.x : q.y;
            float sTh = (sideways ? q.y : q.x) * uStitch.x, sId = floor(sTh), sC = fract(sTh) - 0.5;   // which thread; -0.5..0.5 across it
            float sPly = sin((sAlong * uStitch.x * 0.85 + sC * 1.25) * TAU + hash11(sId) * TAU);       // the twist of the strand
            float sSeg = uStitch.z > 0.0 ? fract(sAlong / uStitch.z + hash11(sId + 5.0)) : 0.5;        // a long stitch is sewn down in lengths
            float sDip = uStitch.z > 0.0 ? exp(-sq(min(sSeg, 1.0 - sSeg) * uStitch.z / 0.006)) : 0.0;  // where the needle went through
            n = normalize(vec3(n.xy * 0.7 + (sideways ? vec2(0.0, 1.0) : vec2(1.0, 0.0)) * sC * 2.2 * uStitch.y, n.z));
            float stitchShade = (0.88 + 0.12 * sPly) * (0.86 + 0.28 * hash11(sId * 1.7 + 2.0)) * (1.0 - 0.75 * sC * sC * uStitch.y) * (1.0 - 0.6 * sDip);
            float stitchCut = smoothstep(0.0, aw, d - hash11(sId * 2.3 + 1.0) * 0.011 * uStitch.w);
#endif
#ifdef BALLOON
            {
                // A FOIL BALLOON: two sheets welded along a flat seam and blown tight. The foil crinkles where
                // it leaves the weld: sharp little ridges that run inward from the seam and die out, each to
                // its own length. The weld itself lies flat.
                vec2 foot = q - gn * d, tng = vec2(-gn.y, gn.x);                    // the nearest point of the seam; along the seam
                float F = uBalloon.y, e = 0.35 / F;
                float c1 = 1.0 - abs(2.0 * tnoise((foot + tng * e) * F) - 1.0), c2 = 1.0 - abs(2.0 * tnoise((foot - tng * e) * F) - 1.0);
                float runIn = uBalloon.w * (0.35 + 1.3 * tnoise(foot * F * 0.37 + 9.0));
                float fade = exp(-max(d - uBalloon.z, 0.0) / max(runIn, 1e-3));
                float weld = 1.0 - smoothstep(uBalloon.z * 0.8, uBalloon.z * 1.25 + aw, d);
                n = normalize(vec3(n.xy - tng * (c1 - c2) * 1.6 * uBalloon.x * fade * (1.0 - weld)
                                   + (vec2(vnoise(q * 4.0), vnoise(q * 4.0 + 19.0)) - 0.5) * 0.1, n.z));
                n = normalize(mix(n, vec3(0.0, 0.0, 1.0), weld * 0.92));
            }
#endif

            vec3 alb = fillAt(f.a);
#ifdef PATTERN
            alb = mix(alb, uPatternCol, pattern(q, f.a) * uPattern.z);
#endif
            if (uMottle > 0.0) alb *= 1.0 + uMottle * ((tfbm(q * 2.6 + 11.0) - 0.5) * 1.1 + (tnoise(q * 31.0) - 0.5) * 0.25);
#ifdef RUST
            // PAINTED STEEL LEFT OUT FOR YEARS, from the owner's photographs (2026-11-12).
            //  - The letter is PLATES, each painted its own colour at some time, with a seam between them.
            //  - Paint is a COAT with a thickness. It lets go a chip at a time, so its edge is angular and stands
            //    proud: lit on the lamp's side, shade under it. An older coat shows in a ring before bare steel.
            //    Before it goes it blisters; old paint is crazed with hair cracks.
            //  - The steel beneath is neither one colour nor flat: deep red-brown, orange, ochre blooms, black
            //    pits, patches of grey scale, all of it rough.
            //  - Edges, seams and the raised frame go first; rain carries the rust down the paint below.
            vec3 coat = alb;
            float seam = 9.0, plateRnd = 0.0;
            if (uRustB.x > 0.0) {
                vec2 pg = q * uRustB.x;
                pg.x += hash11(floor(pg.y) * 3.7 + 1.0) * 0.8;                              // each course of plates is offset from the next
                vec2 pf = fract(pg);
                plateRnd = hash21(floor(pg) + 3.0);
                seam = min(min(pf.x, 1.0 - pf.x), min(pf.y, 1.0 - pf.y)) / uRustB.x;      // em to the plate's edge
                coat = plateRnd < 0.34 ? uFillA : plateRnd < 0.67 ? uFillB : uFillC;
            }
            vec3 chipV = voronoi(q * uRust.y * 5.0 + 3.0);                                  // the chips paint breaks into
            float worn = tfbm(q * uRust.y + plateRnd * 17.0 + 21.0) + (chipV.z - 0.5) * 0.4 + (tnoise(q * uRust.y * 9.0) - 0.5) * 0.08
                       + (1.0 - smoothstep(0.0, 0.07, min(d, seam))) * 0.3 * uRust.z + trim * 0.35;
            float thrTop = mix(0.95, 0.3, uRust.x), thrUnder = thrTop + 0.02 + 0.1 * uRustB.y;
            float topGone = smoothstep(thrTop - 0.007, thrTop + 0.007, worn);
            float bareAll = smoothstep(thrUnder - 0.007, thrUnder + 0.007, worn);           // no paint left at all
            // where the paint has only lately gone the steel is still bright; further in it has rusted. How much
            // bright steel shows is not the same everywhere.
            float metalBand = uRustC.x * 0.07 * (0.15 + 1.6 * tnoise(q * 3.3 + 50.0));
            float rustBare = smoothstep(thrUnder + metalBand - 0.006, thrUnder + metalBand + 0.006, worn);
            // bare steel was brushed or ground when it was made: each plate one way
            float brushA = uRustB.x > 0.0 ? step(0.5, fract(plateRnd * 7.0)) * 1.5708 + (plateRnd - 0.5) * 0.3 : 0.12;
            vec2 brushT = vec2(cos(brushA), sin(brushA));
            float soon = smoothstep(thrTop - 0.14, thrTop, worn) * (1.0 - topGone);         // paint that is about to go
            float crazed = (1.0 - smoothstep(0.0, 0.035, chipV.y - chipV.x)) * (0.3 + 0.7 * soon);
            coat = mix(coat, vec3(luma(coat)) * 1.25 + 0.03, 0.35 * tfbm2(q * 2.1 + 5.0)) * (1.0 - 0.45 * crazed);     // chalked by the sun, cracked
            coat *= 0.62 + 0.5 * tfbm(q * 4.0 + 61.0) - 0.18 * smoothstep(0.55, 0.85, tnoise(q * 13.0 + 5.0));        // years of dirt, stains
            vec3 under = uRustUnder * (0.65 + 0.4 * tnoise(q * 30.0));
            float r1 = tfbm(q * 6.0 + 9.0), r2 = tnoise(q * 37.0 + 2.0), r3 = tnoise(q * 110.0);
            vec3 steel = mix(vec3(0.085, 0.026, 0.01), vec3(0.3, 0.085, 0.022), smoothstep(0.25, 0.75, r1));     // deep red-brown
            steel = mix(steel, vec3(0.5, 0.17, 0.035), smoothstep(0.66, 0.86, r2) * 0.75);                         // orange where it is fresh
            steel = mix(steel, vec3(0.48, 0.3, 0.07), smoothstep(0.8, 0.95, tnoise(q * 19.0 + 7.0)) * 0.6);       // an ochre bloom here and there
            steel = mix(steel, vec3(0.025, 0.015, 0.01), smoothstep(0.66, 0.86, r3) * 0.85);                       // black pits
            steel = mix(steel, vec3(0.075, 0.07, 0.068) * (0.6 + 0.8 * r2), smoothstep(0.6, 0.72, tfbm2(q * 3.0 + 40.0)) * uRustB.w);     // dark iron scale
            float rustRuns = smoothstep(thrUnder - 0.05, thrUnder + 0.05, tfbm(vec2(q.x, q.y + 0.11) * uRust.y + plateRnd * 17.0 + 21.0))
                           * (0.35 + 0.65 * tnoise(vec2(q.x * 46.0, q.y * 2.5))) * uRust.w;
            coat = mix(coat, coat * vec3(0.72, 0.45, 0.26), rustRuns * 0.75);
            vec3 metalAlb = vec3(0.6, 0.6, 0.6) * (0.85 + 0.3 * tnoise(q * 9.0 + 3.0));
            alb = mix(mix(mix(coat, under, topGone), metalAlb, bareAll), steel, rustBare);
            alb *= 1.0 - 0.4 * topGone * (1.0 - smoothstep(thrTop + 0.004, thrTop + 0.03, worn));       // shade lying under the coat's broken edge
            alb *= 1.0 - 0.85 * (1.0 - smoothstep(0.0015, 0.005, seam));                    // the seam between two plates
            // Heights: each coat has a thickness, a blister swells, rust is rough. Where the height changes the
            // surface tips, and the lamp does the rest.
            float tall = (1.0 - topGone) * 0.55 + (1.0 - bareAll) * 0.45
                       + soon * max(1.0 - chipV.x * 2.4, 0.0) * 0.5
                       + rustBare * (r2 * 0.25 + r3 * 0.2 - smoothstep(0.66, 0.86, r3) * 0.45 + (tnoise(q * 260.0) - 0.5) * 0.3)
                       + (1.0 - bareAll) * (tnoise(q * 150.0) - 0.5) * 0.06;
            vec2 tallSlope = vec2(dFdx(tall), dFdy(tall));
            tallSlope = vec2(rustSlopeOk.x ? tallSlope.x : 0.0, rustSlopeOk.y ? tallSlope.y : 0.0);
            n = normalize(vec3(n.xy - tallSlope * uRustB.z * 1.6, n.z));
#endif
#ifdef STITCH
            alb *= stitchShade;
#endif
#ifdef SAND
            // SAND: a heap of grains. Each is its own lighter or darker speck and faces its own way.
            vec2 sandCell = floor(q * uSand.x);
            float sandA = hash21(sandCell), sandB = hash21(sandCell + 31.0), sandC = hash21(sandCell + 77.0);
            alb *= 0.7 + 0.55 * sandA + (tfbm(q * 3.0 + 5.0) - 0.5) * 0.3;            // grain to grain, and damper patches
            n = normalize(vec3(n.xy + (vec2(sandA, sandB) - 0.5) * 1.1, n.z));
#endif
#ifdef PAPER
            {
                // CUT PAPER: sheets stacked one inside the next, each a step smaller. Each is cut by hand
                // (its edge wanders a little, differently from its neighbours) and throws a shadow on the
                // sheet beneath, away from the lamp.
                float top = uPaper.x - 1.0;
                float k0 = min(floor(d / uPaper.y), top);
                float cut = (tnoise(q * 6.0 + k0 * 7.3) - 0.5) * 0.007 * uPaper.w;
                float sheet = min(floor(max(d + cut, 0.0) / uPaper.y), top);
                float k = sheet / max(top, 1.0);
                alb = mix(mix(uFillA, uFillB, sat(k * 2.0)), uFillC, sat(k * 2.0 - 1.0));
                float lit = fieldD(q + Ld * 0.03) + cut;                            // the same sheet, a little toward the lamp
                float shaded = smoothstep(-0.004, 0.02, lit - (sheet + 1.0) * uPaper.y) * step(sheet, top - 0.5);
                alb *= 1.0 - uPaper.z * 0.6 * shaded;
                // paper has a thickness: its cut edge is lit on the lamp's side and dark on the other
                float into = d + cut - sheet * uPaper.y, facing = dot(-gn, Ld);
                float rim = smoothstep(0.0055, 0.001, into) * step(0.5, sheet + step(0.0, d));
                alb *= 1.0 + rim * (facing > 0.0 ? 0.35 * facing : 0.3 * facing) * uPaper.z;
                alb *= 1.0 + ((tnoise(q * 260.0 + sheet * 5.0) - 0.5) * 0.12 + (tfbm(q * 30.0 + sheet * 9.0) - 0.5) * 0.12) * uPaper.w;
            }
#endif
#ifdef DETAIL
            alb *= mix(1.0, 0.4 + 0.85 * sat(relief * 1.7), uDetailShade * (1.0 - trim));
#endif
#ifdef STONE
            {
                // strata: faint bands through the whole word at one angle, so it is cut from one block; and a vein or two
                if (uStone.z > 0.0) {
                    float strata = (tnoise(vec2(dot(q, vec2(0.26, 0.97)) * 3.2 + tfbm2(q * 1.3) * 0.9, 0.5)) - 0.5) * 2.0;
                    float veinD = abs(fract(dot(q, vec2(0.93, -0.36)) * 0.9 + tfbm(q * 1.1) * 0.7) - 0.5);
                    float vein = (1.0 - smoothstep(0.0, 0.035, veinD)) * smoothstep(0.4, 0.7, tnoise(q * 0.8 + 2.0));
                    alb *= 1.0 + uStone.z * strata * 0.22;
                    alb = mix(alb, alb * 1.7 + 0.04, vein * uStone.z * 0.6);
                }
                if (uStone.w > 0.0) {
                    // mineral grain: a fine speckle of lighter and darker crystals
                    float g1 = tnoise(q * 230.0), g2 = tnoise(q * 140.0 + 9.0);
                    alb *= 1.0 + uStone.w * ((g1 - 0.5) * 0.5 + smoothstep(0.8, 0.95, g2) * 0.6 - smoothstep(0.85, 0.97, g1) * 0.4);
                }
                if (uStone.y > 0.0) {
                    // fresh chips: where the edge has broken, the stone is newly bared: paler and cleaner
                    float broken = (1.0 - smoothstep(0.0, max(uBevel, aw) * 1.6, d)) * smoothstep(0.45, 0.7, tfbm(q * 6.0 + 4.0));
                    alb = mix(alb, alb * 1.55 + 0.05, broken * uStone.y);
                }
                // what settles: dust lying where a ledge faces up, moss in the deepest hollows
                if (uStoneB.x > 0.0) {
                    float ledge = smoothstep(0.25, 0.8, -gn.y) * (1.0 - smoothstep(0.0, max(uBevel, aw) * 2.2, d));
                    alb = mix(alb, vec3(0.62, 0.55, 0.44), ledge * uStoneB.x * (0.5 + 0.5 * tnoise(q * 40.0)));
                }
                if (uStoneB.y > 0.0) {
                    float hollow = (1.0 - smoothstep(0.08, 0.3, relief)) * smoothstep(0.4, 0.65, tfbm(q * 2.0 + 13.0));
                    alb = mix(alb, vec3(0.12, 0.2, 0.07) * (0.7 + 0.6 * tnoise(q * 90.0)), hollow * uStoneB.y);
                }
            }
#endif
            float metal = mix(uMetal, uStrokeMetal, trim);
            float rough = uRough * mix(1.0, 0.3, trim);
#ifdef RUST
            metal *= 1.0 - rustBare; rough = mix(rough, 1.0, rustBare);
#endif
#ifndef RUST
            alb = mix(alb, uStrokeCol, trim);
#endif
            float ndl = max(dot(n, L), 0.0);
            float fres = pow(1.0 - sat(n.z), 3.0);
            vec3 E = envAt(n, c, q, rough);
            float sp = pow(max(dot(n, normalize(L + vec3(0.0, 0.0, 1.0))), 0.0), uShine) * uSpec;
            vec3 paint = alb * (uAmbient + (1.0 - uAmbient) * ndl) + E * uGloss * (0.04 + 0.96 * fres);
            col = mix(paint, E * alb, metal);
            col *= 1.0 + grain * 0.5 * uWear.w * metal;
            col = mix(col, alb, uUnlit) + sp;
#ifdef RUST
            {
                // THREE SURFACES, THREE KINDS OF SHINE (owner, 2026-11-12). Bare steel is bright and BRUSHED: fine
                // grooves run along the brush, so the lamp makes a soft bright band ACROSS them that slides as the
                // phone tilts, and what it mirrors is smeared the same way. Paint has a medium, satin sheen. Rust has
                // no shine at all: it is porous, and its pits hold shadow.
                vec3 H3 = normalize(L + vec3(0.0, 0.0, 1.0));
                float spP = pow(max(dot(n, H3), 0.0), 12.0) * 0.32 * uRustC.y;
                vec3 paintC = alb * (0.24 + 0.88 * ndl) + E * (0.07 + 0.3 * fres) * uRustC.y + spP * vec3(1.0, 0.98, 0.95);
                vec2 bB = vec2(-brushT.y, brushT.x);
                float groove = tnoise(vec2(dot(q, brushT) * 5.0, dot(q, bB) * 230.0));
                vec3 nm = normalize(vec3(n.xy + bB * (groove - 0.5) * 0.4, n.z));
                vec3 Em = envAt(nm, c, q, 0.3);                                        // (slightly blurred: the grooves smear what it mirrors)
                float along = dot(q, brushT) - dot(uTilt, brushT) * 0.7 - (plateRnd - 0.5) * 1.3;
                float band = exp(-sq((fract(along / 1.1) - 0.5) * 1.1 / 0.17));         // the lamp's streak, once per 1.1 em of plate
                float spM = band * (0.55 + 0.9 * groove) * 1.9 * uRustC.z;
                vec3 metalC = metalAlb * (0.3 + 0.45 * ndl) * (0.8 + 0.4 * groove) + Em * metalAlb * 1.2 * uRustC.z
                            + vec3(1.0, 0.97, 0.92) * spM;
                vec3 rustC = alb * (0.07 + 1.2 * ndl) * (0.82 + 0.18 * tnoise(q * 320.0));
                col = mix(mix(paintC, metalC, bareAll), rustC, rustBare);
            }
#endif
#ifdef SAND
            // a few grains are glass: they flash, and which ones changes as the light or the phone moves
            col += vec3(1.0, 0.93, 0.8) * uSand.z * step(0.97, sandB) * pow(sat(sin(sandA * 50.0 + dot(uTilt, vec2(9.0, 7.0)) + uTime * 0.6)), 12.0) * 2.5;
#endif

            // shade: creases, the well beside the frame, and the shadow the frame throws
            float lit = 1.0 - uAO * (1.0 - smoothstep(0.0, max(uBevel, aw), d));
            if (uRim > 0.0) {
                lit *= 1.0 - uAO * onFace * exp(-max(d - uRim - wi, 0.0) / max(uInset * 1.6, 0.004));
                lit *= 1.0 - uCast * onFace * castShadow(q, heightAt(d));
            }
            // wear: grime gathers low and in corners; scratches catch the light
            if (uWear.z > 0.0) {
                float dirt = smoothstep(0.35, 0.8, tfbm(q * 5.0 + 3.0));
                lit *= 1.0 - uWear.z * (0.45 * dirt + 0.4 * (1.0 - smoothstep(0.0, max(uBevel, aw) * 2.0, d)));
            }
            col *= mix(1.0, lit, 1.0 - uUnlit);
#ifdef BALLOON
            col *= 1.0 - 0.5 * exp(-sq((d - uBalloon.z) / (1.6 * aw))) * step(1e-4, uBalloon.z);     // the crease where the weld ends
#endif
#ifdef RUST
#endif
#ifdef JELLY
            {
                // JELLY: light goes INTO it. The thin rim passes the light and glows with the colour; the thick
                // middle is deeper; light gathers and comes out on the side away from the lamp; what is behind
                // shows through it, bent; the room shines on its wet skin.
                float thickJ = sat(heightAt(d) / max(uBevelHeight, 1e-4));                  // 0 at the rim .. 1 in the middle
                vec3 bodyJ = mix(alb * 1.5 + 0.06, alb * alb * 0.55, pow(thickJ, 0.8) * uJelly.x);
                vec3 behindJ = texture(uBack, vUv - n.xy * uJelly.z * 0.06).rgb;
                bodyJ = mix(bodyJ, bodyJ * (0.5 + behindJ * 2.0), uJelly.w * 0.5);
                float awayJ = sat(dot(n.xy / max(length(n.xy), 1e-4), -Ld));
                bodyJ += alb * uJelly.y * pow(1.0 - sat(n.z), 1.5) * awayJ * 1.6;
                col = bodyJ * (0.55 + 0.45 * ndl) + E * (0.04 + 0.96 * fres) * uGloss + sp;
            }
#endif
#ifdef STAINED
            {
                // STAINED GLASS: panes of coloured glass, each its own shade, held in lead. The light comes
                // THROUGH it, unevenly (old glass is rippled and seeded with bubbles) and it shifts as you move.
                vec3 pv = voronoi(q * uPane.x + 7.0);
                float toLead = min((pv.y - pv.x) * 0.5 / uPane.x, d);                       // em to the nearest came, or to the letter's own edge
                float leadM = 1.0 - smoothstep(uPane.y * 0.5 - aw, uPane.y * 0.5 + aw, toLead);
                vec3 paneC = (pv.z < 0.34 ? uFillA : pv.z < 0.67 ? uFillB : uFillC) * (0.65 + 0.7 * fract(pv.z * 7.31));
                float through = uPane.z * (0.6 + 0.6 * tfbm2(q * 2.5 + pv.z * 9.0)) * (0.88 + 0.24 * tnoise(q * 46.0))
                              * (1.0 + 0.35 * dot(uTilt, (q - 0.5 * uField.xy) / uField.x * 3.0));
                float bubbles = smoothstep(0.93, 0.98, tnoise(q * 120.0 + pv.z * 30.0));
                vec3 glassC = paneC * through * (1.0 + 0.6 * bubbles) * (1.0 - 0.5 * exp(-toLead / 0.012));
                float acrossL = sat(toLead / max(uPane.y * 0.5, 1e-4));                     // across the came: 0 on its crest .. 1 at its edge
                vec3 leadC = (vec3(0.03, 0.032, 0.036) + E * 0.3 * (1.0 - acrossL * acrossL) + sp) * lit;
                col = mix(glassC, leadC, leadM);
            }
#endif
            if (uWear.y > 0.0) {
                float scr = smoothstep(0.80, 0.97, tnoise(rot(0.5) * q * vec2(310.0, 5.0)))
                          + smoothstep(0.84, 0.98, tnoise(rot(-0.9) * q * vec2(220.0, 7.0)));
                col += (E * 0.6 + 0.04) * scr * uWear.y * (0.3 + 0.7 * metal);
            }
            // the catch-light: a hair-thin bright line where a slope meets a flat, on the lamp's side
            float crest = exp(-sq((d - uBevel) / (1.3 * aw)));
            if (uRim > 0.0) crest = max(crest, exp(-sq((d - uRim) / (1.3 * aw))) * 0.8);
            col += vec3(1.0, 0.98, 0.95) * crest * uWear.x * sat(0.15 + 0.85 * dot(-gn, Ld)) * step(1e-4, uBevelHeight);

            if (uSweep.x > 0.0) {
                float xs = mix(-1.5, uField.x + 1.5, fract(uTime / max(uSweep.y, 0.1)));
                float bar = uSweep.x * exp(-sq((q.x + (q.y - 0.5 * uField.y) * 0.45 - xs) / 0.14));
                col = col * (1.0 + 1.6 * bar) + bar * 0.06;   // brighter, but still its own colour
            }
#ifdef REFRACT
            float thick = z + uDepth * 0.5 + 0.15;
            // ICE is made of crystals: each one bends the light on its own slant
            vec3 cr = vec3(1.0, 1.0, 0.5);
            vec2 slant = vec2(0.0);
            if (uIce.y > 0.0) {
                cr = voronoi(q * uChip.y);
                slant = (hash22(vec2(cr.z * 91.0, cr.z * 17.0)) - 0.5) * 0.55 * uIce.y;
            }
            vec2 off = (n.xy + slant) * uGlass.x * thick / uField.xy;
            vec3 bg = vec3(texture(uBack, vUv - off * (1.0 + uGlass.y)).r,
                           texture(uBack, vUv - off).g,
                           texture(uBack, vUv - off * (1.0 - uGlass.y)).b);
            bg = mix(bg, bg * uTint * 1.4 + uTint * 0.03, sat(uGlass.z * (0.4 + 2.0 * thick)));
            if (uCaustic.x > 0.0) {
                vec3 v = voronoi(q * uCaustic.y + vec2(uTime * 0.21, uTime * 0.13) + n.xy * 0.8);
                bg += uTint * uCaustic.x * pow(sat(1.0 - (v.y - v.x) * 1.6), 4.0);
            }
            float F = 0.04 + 0.96 * fres;
            // a drop under a lamp: shaded just inside the edge that faces the lamp (that light has
            // gone on through), and bright where it gathers on the far side
            float rimw = 1.0 - sat(n.z);
            float side = dot(n.xy / max(length(n.xy), 1e-4), Ld);
            vec3 glassCol = bg * (1.0 - uGlass.w * rimw * smoothstep(-0.3, 0.9, side));
            glassCol += (uTint * 0.6 + 0.4) * uGather * pow(rimw, 1.4) * smoothstep(0.2, -0.9, side);
            glassCol = glassCol * (1.0 - F * uGloss * 0.5) + E * F * uGloss + sp;
            if (uTank.x > 0.0) {
                // A VESSEL: the letter is glass, part full. The liquid finds its level, leans as the phone
                // tilts and never sits quite still; bubbles of every size rise through it and gather as
                // a head of froth on top.
                float wave = (sin(q.x * 9.0 + uTime * 2.1) * 0.6 + sin(q.x * 17.0 - uTime * 3.3) * 0.4) * uTank.z * 0.03;
                float level = uTank.x + uTilt.x * uTank.y * c.x * 0.35 + wave;
                float under = smoothstep(0.012, -0.012, f.a - level);
                vec3 liquid = glassCol * mix(vec3(1.0), uTankCol * 1.3, 0.85) + uTankCol * 0.07;
                liquid *= 0.75 + 0.5 * sat(f.a / max(level, 0.05));                              // deeper = darker
                if (under > 0.001) for (int i = 0; i < 2; i++) {
                    float sc = i == 0 ? 11.0 : 23.0, sp = i == 0 ? 0.9 : 1.6;
                    vec3 bb = voronoi(vec2(q.x * sc + sin(q.y * 7.0 + uTime * 1.3) * 0.25, q.y * sc - uTime * sp * sc * 0.12) + float(i) * 17.0);
                    float rad = 0.14 + 0.2 * fract(bb.z * 7.7);
                    float ring = smoothstep(rad, rad - 0.05, bb.x) * (0.3 + 0.7 * smoothstep(rad - 0.14, rad - 0.02, bb.x));
                    liquid += (vec3(1.0, 0.97, 0.9) * 0.55 + uTankCol * 0.5) * ring * step(1.0 - 0.4 * sat(uTank.w), bb.z) * uTank.w;
                }
                float head = sat(uTank.w) * 0.07;
                float froth = smoothstep(level + head, level + head * 0.6, f.a) * (1.0 - under);
                glassCol = mix(glassCol, liquid, under) + vec3(1.0) * exp(-abs(f.a - level) / 0.01) * 0.3 * (0.4 + 0.6 * under);
                if (froth > 0.001) {
                    vec3 fv = voronoi(q * 46.0 + vec2(0.0, uTime * 0.15));
                    vec3 frothC = mix(vec3(1.0, 0.96, 0.9), uTankCol, 0.12) * (0.55 + 0.45 * smoothstep(0.02, 0.2, fv.y - fv.x));
                    glassCol = mix(glassCol, frothC, froth * 0.9);
                }
            }
            if (uIce.x + uIce.y + uIce.z + uIce.w > 0.0) {
                // Fissures run along the crystals' walls (the same walls the outline chips along, so a
                // chip leads into a crack). Low down and deep inside the ice is milky and full of
                // bubbles; it clears toward the top and the outside. Patches of its skin are frosted.
                float wallD = cr.y - cr.x;
                float up = sat(f.a);
                float inner = smoothstep(0.0, max(uBevel, aw) * 3.0, d);
                // (not every wall has split: fissures gather in some parts of the block and leave others whole)
                float split = smoothstep(0.42, 0.62, tfbm(q * 1.2 + 20.0)) * uIce.y;
                float fis = (1.0 - smoothstep(0.0, 0.006 + 0.02 * tnoise(q * 5.0), wallD)) * split;
                float sideLit = smoothstep(0.02, 0.1, wallD) * (1.0 - smoothstep(0.1, 0.28, wallD)) * (cr.z - 0.5) * split;
                glassCol *= 1.0 + min(sideLit, 0.0) * 0.9;                                   // one side of a fissure is in shade
                glassCol += vec3(0.75, 0.9, 1.0) * (fis * (0.4 + 0.6 * tnoise(q * 14.0)) + max(sideLit, 0.0) * 0.7);
                float low = (1.0 - up) * 0.8 + 0.2;
                float milk = uIce.x * inner * low * smoothstep(0.45, 0.8, tfbm(q * 2.4 + 3.0) + (1.0 - up) * 0.3);
                vec3 bub = voronoi(q * 24.0 + 5.0);
                float ring = smoothstep(0.17, 0.12, bub.x) * (0.35 + 0.65 * smoothstep(0.04, 0.13, bub.x));
                float bubbles = uIce.x * inner * low * low * ring * step(0.7, bub.z);
                float frostP = uIce.z * (smoothstep(0.5, 0.72, tfbm(q * 1.3 + 9.0)) * 0.8
                             + (1.0 - smoothstep(0.0, max(uBevel, aw) * 1.2, d)) * 0.45) * (0.6 + 0.4 * tnoise(q * 70.0));
                vec3 white = vec3(0.78, 0.88, 0.97) * (0.45 + 0.7 * ndl);
                glassCol = mix(glassCol, white, sat(milk * 0.7 + frostP));
                glassCol += vec3(0.9, 0.97, 1.0) * bubbles * 0.75;
                // a fracture is a surface inside the block: beside its bright thread you see it as a pale veil
                float veil = smoothstep(0.17, 0.0, wallD) * step(0.5, cr.z) * split * uIceB.x;
                glassCol = mix(glassCol, vec3(0.8, 0.9, 1.0) * (0.45 + 0.55 * ndl), veil * 0.4);
                // lit from behind, ice glows where it is thin
                glassCol += uTint * uIceB.y * 0.55 * (1.0 - inner) * (0.5 + 0.5 * tfbm2(q * 3.0 + 1.0));
                // a crystal now and then splits the light: small coloured sparks that move as you tilt
                float spark = pow(sat(sin(dot(slant, uTilt * 6.0 + vec2(1.3, 0.7)) * 9.0 + cr.z * 40.0 + uTime * 0.5)), 26.0)
                            * smoothstep(0.07, 0.015, cr.x) * uIceB.z;
                glassCol += hsv2rgb(vec3(fract(cr.z * 3.0 + uTime * 0.03), 0.45, 1.0)) * spark * 3.0;
                // a film of meltwater: slow sliding gleams on the skin
                float wet = pow(sat(tfbm(q * 7.0 + vec2(uTime * 0.03, uTime * 0.11)) * 1.6 - 0.62), 2.0) * uIceB.w;
                glassCol += vec3(0.9, 0.96, 1.0) * wet * (0.3 + 0.9 * ndl);
                float capDeep = 0.05 * (0.5 + 1.0 * tfbm2(vec2(q.x * 7.0, 2.0)));
                float snow = uIce.w * smoothstep(0.3, 0.8, -gn.y) * (1.0 - smoothstep(capDeep * 0.6, capDeep, d));
                glassCol = mix(glassCol, vec3(0.93, 0.96, 1.0) * (0.55 + 0.6 * ndl), snow);
            }
            float seeThrough = mix(sat(F * uGloss + sp + uGlass.z * 0.35), 1.0, uHasBack);
            col = mix(glassCol, col, trim);
            a = mix(seeThrough, 1.0, trim);
#else
            a = 1.0;
#endif
#ifdef STITCH
            a *= stitchCut;
#endif
#ifdef SAND
            // its edge is grains, not a line; and so is the front that carries it away
            a *= step(sandC * uSand.y, d) * step((sandC - 0.7) * 0.14, frontAt(q));
#endif
#ifdef HOLO
            // light hanging in air: almost nothing in the body, scan lines drifting through it, a bright rim
            float scan = 0.5 + 0.5 * sin((q.y * uHolo.y - uTime * uHolo.z * 6.0) * TAU);
            float rimH = 1.0 - smoothstep(0.0, max(uBevel, aw) * 1.6, d);
            float waver = 0.82 + 0.18 * tnoise(vec2(uTime * 13.0, f.b * 9.0));
            vec3 hc = fillAt(f.a);
            float a0 = 0.14 + 0.2 * scan;
            a = a0 * presence(q);
            col = hc * 0.05;
            add += hc * (0.1 + 0.35 * scan * scan + 1.6 * rimH) * uHolo.x * waver / a0;
#endif
            if (uInline > 0.0 && dot(uHover, uHover) == 0.0) {
                float il = smoothstep(-aw, aw, uInline * 0.5 - abs(dRaw - uInlineOff - uInline * 0.5));
                col = mix(col, uInlineCol, il);
            }
#ifdef EMBER
            vec3 v = voronoi(q * uEmber.y);
            float crack = 1.0 - smoothstep(0.0, 0.11, v.y - v.x);
            float pulse = 0.65 + 0.35 * sin(uTime * 1.3 + v.z * TAU);
            float T = uEmber.x * (crack * pulse + 0.18 * fbm2(q * 7.0 + uTime * 0.15))
                    + uEmber.z * (1.0 - smoothstep(0.0, max(uBevel, aw) * 1.2, d));
            float fireHere = texture(uHeat, vUv).r;
            add += fireRamp(T * f.g, uFireA, uFireB, uFireC, uFireToon);
            col += (alb + 0.08) * uFireB * sat(fireHere) * uEmber.w * 1.5;
#endif
        } else {
            n = normalize(vec3(-gn, 0.3));
            float dz = sat(-z / max(uDepth, 1e-4));
            float k = uSideBands > 0.5 ? min(floor(dz * uSideBands), uSideBands - 1.0) / max(uSideBands - 1.0, 1.0) : dz;
            vec3 sc = mix(uSideA, uSideB, k);
            float ndl = 0.3 + 0.7 * max(dot(n, L), 0.0);
            float grain = tnoise(vec2(dot(q, vec2(-gn.y, gn.x)) * 9.0, z * 520.0)) - 0.5;
            vec3 E = envAt(n, c, q, uRough * 0.8 + 0.1);
            col = mix(mix(sc * ndl, sc, uUnlit), E * sc * (1.0 + grain * 0.5 * uWear.w), uSideMetal);
            col *= 1.0 - uAO * 0.6 * smoothstep(0.0, 0.25, dz) * (1.0 - uUnlit);
            a = 1.0;
        }
#ifdef BURN
        {
            // BURNING AWAY: the FRONT eats across the word. Ahead of it the letter chars black; the front
            // itself is a hair of live ember; behind it there is nothing, not even a shadow.
            float m = frontAt(q);
            float left = smoothstep(0.0, 0.006, m);
            col *= mix(0.04, 1.0, smoothstep(0.015, 0.11, m));
            add += (vec3(1.0, 0.75, 0.35) * exp(-max(m, 0.0) / 0.006) * 1.6 + vec3(1.0, 0.22, 0.02) * exp(-max(m, 0.0) / 0.028) * 0.5
                    * (0.6 + 0.4 * tnoise(q * 30.0 + uTime * 2.0))) * uBurnGlow * left;
            a *= left;
        }
#endif
        a *= cover;
        a *= mix(1.0, f.g, uReveal.x);
        if (uReveal.y > 0.0) a *= mix(1.0, smoothstep(0.0, 0.06, f.g * 1.5 - 0.25 - f.a), uReveal.y);
        add *= a;
    }

    // the floor: a shadow under the back of the letters, and their glow
    float under = 0.0;
    if (uShadow > 0.0) {
        float sd = fieldD(p0 - lean * uDepth - uShadowOff);
        under = smoothstep(-max(uShadowSoft, aw), 0.3 * max(uShadowSoft, aw), sd) * uShadow;
#if defined(BURN) || defined(SAND)
        under *= smoothstep(-0.05, 0.05, frontAt(p0 - lean * uDepth - uShadowOff));
#endif
    }
    if (uGlow > 0.0) {
        vec4 f0 = texture(uFieldTex, vUv);
        float gd = min(f0.r + uStroke, 0.0);
        add += uGlowCol * uGlow * exp(gd / max(uGlowSize, 1e-4)) * (1.0 - a) * mix(1.0, f0.g, max(uReveal.x, uReveal.y));
    }
#ifdef FUR
    {
        // FUR. Every strand is drawn: it is rooted in the letter, leans the way its tuft is combed,
        // tapers to its tip, and may reach past the outline. Eighteen slices from tip to root; the strand
        // nearest the eye hides those beneath it, which sit in its shade.
        vec2 tuft = vec2(tnoise(p0 * 3.2 + 3.0), tnoise(p0 * 3.2 + 41.0)) - 0.5;
        float sway = (sin(p0.x * 2.3 + uTime * uFur.w) * 0.14 + sin(p0.y * 3.1 - uTime * uFur.w * 0.7) * 0.07) * step(0.001, uFur.w);
        vec2 comb = normalize(mix(tuft * 2.6, vec2(0.0, -1.0), uFur.z) + vec2(sway, 1e-4));
        float longer = 0.55 + 0.9 * tnoise(p0 * 1.7 + 9.0);
        float sheen = 0.85 + 0.3 * dot(comb, normalize(vec2(-0.5, 0.75)));
        vec4 acc = vec4(0.0);
        for (int i = 17; i >= 0; i--) {
            float h = float(i) / 17.0;
            vec2 root = p0 - comb * uFur.x * longer * h * (0.55 + 0.45 * h);          // a strand curves as it falls
            vec4 fr = texture(uFieldTex, root / uField.xy);
            vec2 cp = mat2(0.8, 0.6, -0.6, 0.8) * root * uFur.y, id = floor(cp);      // roots are not sown in rows
            vec2 rnd = vec2(hash21(id), hash21(id + 57.0));
            float tall = 0.3 + 0.7 * rnd.x;                                           // every strand its own length
            float wide = 0.44 * (1.0 - 0.8 * h / tall);
            float cov = smoothstep(-0.004, 0.004, fr.r + uStroke) * fr.g * step(h, tall)
                      * smoothstep(wide, wide - 0.2, length(fract(cp) - 0.5 - (rnd - 0.5) * 0.3));
            vec3 base = fillAt(fr.a);
            vec3 sc = mix(base * 0.28, mix(base, uFurTip, h * h), h) * (0.7 + 0.6 * rnd.y) * sheen
                    * (0.72 + 0.28 * smoothstep(0.0, 0.07, fr.r));
            acc.rgb += (1.0 - acc.a) * cov * sc;
            acc.a += (1.0 - acc.a) * cov;
        }
        vec3 skin = fillAt(texture(uFieldTex, vUv).a) * 0.14;
        float oa = acc.a + (1.0 - acc.a) * a;
        col = (acc.rgb + (1.0 - acc.a) * a * skin) / max(oa, 1e-4);
        a = oa;
    }
#endif
#ifdef ARCS
    {
        // LIGHTNING on the metal itself: a hair of light that crawls along the edge and re-strikes many
        // times a second, and the flash it throws on the letter. (The bolts that leap off are particles.)
        vec4 fa = texture(uFieldTex, vUv);
        float dd = fa.r + uStroke;
        float strike = floor(uTime * (8.0 + 16.0 * uArc.w));
        float near = smoothstep(-0.2, -0.08, dd), arcs = 0.0;
        if (near > 0.0) for (int i = 0; i < 2; i++) {
            float fi = float(i);
            float wander = (tfbm(p0 * (5.0 + 4.0 * fi) + vec2(strike * 7.13, fi * 31.0)) - 0.5) * uArc.y * 2.0
                         + (tnoise(p0 * 42.0 + strike * 3.1 + fi * 9.0) - 0.5) * 0.022 * uArc.z;
            float gate = smoothstep(0.6, 0.68, tnoise(p0 * 1.1 + vec2(strike * 3.7 + fi * 5.0, strike)));
            float off = abs(dd - wander);
            arcs += gate * (exp(-off / 0.0028) + 0.12 * exp(-off / 0.02));
        }
        float flash = 0.35 + 0.65 * hash11(strike);
        add += uArcCol * arcs * uArc.x * fa.g * near * flash;
        col += uArcCol * 0.05 * uArc.x * flash * exp(-max(dd, 0.0) / 0.035) * a;      // the edge of the metal catches it
    }
#endif
#ifdef SAND
    {
        // grains that have rolled off: a thin scatter round the foot of every edge
        vec4 fs = texture(uFieldTex, vUv);
        float e = -(fs.r + uStroke);
        if (e > 0.0 && e < 0.07 && uSand.w > 0.0) {
            vec2 gc = floor(p0 * uSand.x);
            float gh = hash21(gc + 77.0);
            float lying = step(1.0 - 0.45 * uSand.w * exp(-e / 0.016), gh) * fs.g * step((gh - 0.7) * 0.14, frontAt(p0)) * (1.0 - a);
            col = mix(col, fillAt(fs.a) * (0.45 + 0.5 * hash21(gc)), lying);
            a = max(a, lying);
        }
        // THE STREAM. Every grain the front has set loose is still drawn, further along the wind. A point ahead
        // of the line is reached, at this instant, only by grains from one place upwind (they left when the
        // line was there), so the stream is the letter's own grains carried off: thick where they have only
        // just gone, thinning, lifting and shaken by the wind's eddies as they travel.
        if (uSandB.y > 0.0) {
            vec2 wdir = vec2(cos(uFront.w * TAU), sin(uFront.w * TAU));
            float perEm = wdir.x * wdir.x / uField.x + wdir.y * wdir.y / uField.y;       // how much nearer the line gets, per em along the wind
            float R = max(uSandB.x, 1.2);
            float upwind = frontAt(p0) / perEm * R / (R - 1.0);                          // em back to where these grains lay
            float age = upwind / uSandB.y;                                               // 0 only just gone .. 1 carried out of sight
            if (age > 0.0 && age < 1.0) {
                vec2 eddy = vec2(tnoise(p0 * 2.3 + vec2(-uTime * 0.7, 3.0)), tnoise(p0 * 2.3 + vec2(-uTime * 0.7, 40.0))) - 0.5;
                vec2 apart = (hash22(floor(p0 * uSand.x) + 13.0) - 0.5) * 0.4 * age;          // grains part company as they fly
                vec2 src = p0 - wdir * upwind - vec2(0.0, uSandB.z * age * age) + eddy * uSandB.w * age + apart;
                vec4 fsrc = texture(uFieldTex, src / uField.xy);
                vec2 gc = floor(src * uSand.x);
                float gh = hash21(gc + 77.0);
                float thin = exp(-age * 4.5) * (1.0 - age);
                float grain = step(gh * uSand.y, fsrc.r + uStroke) * step(gh, thin * 0.85) * fsrc.g;
                col = mix(col, fillAt(fsrc.a) * (0.5 + 0.6 * hash21(gc)) * (0.7 + 0.3 * thin), grain);
                a = max(a, grain);
            }
        }
    }
#endif
#ifdef VINE
    {
        // A VINE climbs the letter. Woody stems follow its outline, wandering on and off the face and
        // crossing one another; turns of it are wound round each stroke; thin tendrils curl off. All of it
        // appears as the FRONT passes (its leaves and blossoms are particles rooted on the same outline).
        // (Worked out only in the narrow band where a stem can be, and only once it has begun to grow.)
        vec4 fv = texture(uFieldTex, vUv);
        float dd = fv.r + uStroke;
        float grown = dd > -0.09 ? smoothstep(0.03, -0.05, frontAt(p0)) * fv.g : 0.0;
        if (grown > 0.0) {
            float speck = 0.8 + 0.4 * tnoise(p0 * 60.0);
            if (dd < 0.1) {
                vec2 g0 = fieldGrad(p0);
                float gside = dot(normalize(g0 + vec2(1e-5, 0.0)), normalize(uLight.xy + vec2(1e-4, 0.0)));
                float thick = 0.75 + 0.5 * tnoise(p0 * 2.6);
                for (int i = 0; i < 4; i++) {
                    float fi = float(i), off, w;
                    if (i < 3) {
                        if (fi >= uVine.x) continue;
                        off = (tnoise(p0 * (1.9 + 0.8 * fi) + fi * 23.0) - 0.5) * 2.4 * uVine.z + 0.014 * (fi - 0.7);
                        w = uVine.y * (1.0 - 0.22 * fi) * thick;
                    } else {
                        if (uVine.x < 0.5 || tnoise(p0 * 1.9 + 60.0) < 0.5) continue;      // tendrils, here and there
                        off = (tnoise(p0 * 8.0 + 40.0) - 0.5) * 0.09 - 0.01;
                        w = uVine.y * 0.3;
                    }
                    float e = dd - off;
                    float m = smoothstep(w, w - 1.5 * aw, abs(e)) * grown;
                    float across = clamp(e / w, -1.0, 1.0);
                    float lit = 0.5 + 0.5 * sqrt(max(1.0 - across * across, 0.0)) + 0.3 * across * gside;
                    vec3 sc = uVineCol * (i == 3 ? vec3(0.9, 1.6, 0.7) : vec3(1.0)) * lit * speck;
                    float shadeM = smoothstep(w * 1.9, w * 0.4, abs(e + dot(g0, vec2(-0.01, 0.012)))) * grown * 0.5;
                    col = mix(col * (1.0 - shadeM * (1.0 - m)), sc, m);
                    a = max(a, m);
                }
            }
            if (uVine.w > 0.0) {
                // turns wound round the stroke: seen crossing its face on a slant, then gone behind it
                float ph = dot(p0, vec2(0.5, 0.866)) * uVine.w + (tnoise(p0 * 1.6 + 7.0) - 0.5) * 1.3;
                float w = uVine.y * 0.85;
                float band = (fract(ph) - 0.5) / uVine.w;                           // em from the middle of the nearest turn
                float onLetter = smoothstep(-w, -w + 2.0 * aw, dd);                 // it stands a stem's thickness proud of the edge
                float m = smoothstep(w, w - 1.5 * aw, abs(band)) * onLetter * grown;
                float across = clamp(band / w, -1.0, 1.0);
                float lit = (0.5 + 0.5 * sqrt(max(1.0 - across * across, 0.0)) + 0.25 * across) * (0.5 + 0.5 * smoothstep(-w, 0.02, dd));
                float shadeM = smoothstep(w * 2.2, w * 0.5, abs(fract(ph + 0.016 * uVine.w) - 0.5) / uVine.w) * grown * 0.5 * onLetter;
                col = mix(col * (1.0 - shadeM * (1.0 - m)), uVineCol * lit * speck, m);
                a = max(a, m);
            }
        }
    }
#endif
#ifdef BITS
    {
        // MADE OF SMALL THINGS: sequins, beads, sprinkles or coins, wherever the letter is. Each is its own
        // object: its own tilt (so each catches the room at a different moment), its own shade of the
        // colour, lying over the next like a roof tile and shading it.
        float N = uBits.x, R = uBits.z;
        int kind = int(uBits.y + 0.5);
        vec2 gp = p0 * N;
        float row0 = floor(gp.y);
        float px = N / uField.z * 1.3;                                            // one picture pixel, in pieces
        vec3 L3 = normalize(uLight), H3 = normalize(L3 + vec3(0.0, 0.0, 1.0));
        vec4 acc = vec4(0.0);
        float shadeOn = 0.0;                                                      // the shade of the pieces lying over this spot
        float reachP = R * 1.45 + 0.71 * uBitsB.x;                                 // no piece further off than this can touch the pixel
        bool anyNear = texture(uFieldTex, vUv).r + uStroke > -(reachP + 0.3) / N; // (and none at all, out in the open)
        if (anyNear) for (int j = 1; j >= -1; j--) {                              // an upper row lies over the row below it
            float row = row0 + float(j);
            float shift = mod(row, 2.0) * 0.5;
            float c0 = floor(gp.x - shift);
            for (int i = -1; i <= 1; i++) {
                vec2 id = vec2(c0 + float(i), row);
                vec2 l0 = gp - id - vec2(0.5 + shift, 0.5);
                if (dot(l0, l0) > reachP * reachP) continue;
                vec2 h = hash22(id * 1.31 + 7.0);
                vec2 ctr = (id + vec2(0.5 + shift, 0.5) + (h - 0.5) * uBitsB.x) / N;
                vec2 l = (p0 - ctr) * N;
                if (dot(l, l) > R * R * 2.1) continue;
                vec4 fc = texture(uFieldTex, ctr / uField.xy);
                if (fc.r + uStroke < -0.25 * R / N || fc.g * 1.05 < h.x) continue;  // no letter under it, or not arrived yet
                vec2 h2 = hash22(id * 0.77 + 19.0);
                vec2 tip = (h2 - 0.5) * 1.5 * uBits.w;
                if (uBitsB.y > 0.0) tip += 0.3 * sin(uTime * uBitsB.y * (0.7 + 1.6 * h.x) + h.y * TAU) * vec2(cos(h2.x * TAU), sin(h2.x * TAU));
                float len = length(l), dist;                                      // dist below 0 = inside the piece
                vec3 nrm;
                if (kind == 2) {
                    mat2 turn = rot(h2.x * PI);
                    vec2 lr = turn * l;
                    float rad = R * 0.3;
                    vec2 cq = vec2(lr.x - clamp(lr.x, -R * 0.7, R * 0.7), lr.y);
                    dist = length(cq) - rad;
                    nrm = normalize(vec3((cq / rad) * turn, sqrt(max(1.0 - dot(cq, cq) / (rad * rad), 0.0)) + 0.05));
                } else if (kind == 1) {
                    dist = len - R;
                    nrm = normalize(vec3(l / R, sqrt(max(1.0 - len * len / (R * R), 0.0)) + 0.05) + vec3(tip * 0.15, 0.0));
                } else if (kind == 3) {
                    dist = len - R;
                    float rr = len / R;
                    float slope = smoothstep(0.7, 0.76, rr) * (1.0 - smoothstep(0.8, 0.84, rr)) - smoothstep(0.93, 1.0, rr)
                                + smoothstep(0.4, 0.44, rr) * (1.0 - smoothstep(0.46, 0.5, rr)) * 0.6;
                    nrm = normalize(vec3(tip * 0.25 - l / max(len, 1e-3) * slope * 0.8, 1.0));
                } else {
                    dist = len - R;
                    nrm = normalize(vec3(tip + l / R * 0.45, 1.0));                // a sequin is a shallow cup
                }
                float cov = smoothstep(0.0, -px, dist);
                vec3 base = fillAt(fc.a);
                vec3 alb = mix(base * (1.0 + (h.y - 0.5) * 0.7 * uBitsB.z), hsv2rgb(vec3(h2.y, 0.7, 0.85)), kind == 2 ? uBitsB.z : 0.0);
                vec2 cc = vec2(ctr.x / uField.x * 2.0 - 1.0, clamp(fc.a, -0.25, 1.25) * 2.0 - 1.0);
                vec3 E = envAt(nrm, cc, ctr, uRough);
                float ndl = max(dot(nrm, L3), 0.0);
                vec3 pc = mix(alb * (uAmbient + (1.0 - uAmbient) * ndl) + E * uGloss * 0.25, E * alb, uMetal)
                        + pow(max(dot(nrm, H3), 0.0), uShine) * uSpec;
                if (kind == 0) {
                    float rr = len / R;
                    pc *= 1.0 + 0.6 * smoothstep(0.8, 0.97, rr);                    // its cut edge catches the light
                    pc = mix(pc, vec3(0.01), smoothstep(0.17, 0.12, rr));           // the hole it is sewn through
                }
                pc *= 1.0 - shadeOn;
                acc.rgb += (1.0 - acc.a) * cov * pc;
                acc.a += (1.0 - acc.a) * cov;
                // the shade this one throws on whatever lies under it, away from the lamp
                shadeOn = max(shadeOn, smoothstep(R * 1.3, R * 0.8, length(l - vec2(0.1, -0.14))) * uBitsB.w * 0.65 * (1.0 - cov));
            }
        }
        float oa = acc.a + (1.0 - acc.a) * a;
        col = (acc.rgb + (1.0 - acc.a) * a * uBitsBase * (1.0 - shadeOn)) / max(oa, 1e-4);
        a = oa;
    }
#endif
#ifdef STAINED
    {
        // the light that came through the glass lies on the floor in front of it, in the panes' own colours
        float floorY = uFloor.y * uField.y, below = floorY - p0.y;
        if (below > 0.0 && uPane.w > 0.0) {
            vec2 src = vec2(p0.x - below * 0.35, floorY + below * 1.6) + (vec2(tnoise(p0 * 7.0), tnoise(p0 * 7.0 + 31.0)) - 0.5) * (0.05 + 0.25 * below);   // light spreads as it falls
            vec4 fp = texture(uFieldTex, src / uField.xy);
            vec3 pv = voronoi(src * uPane.x + 7.0);
            float glassHere = smoothstep(-0.02, 0.02, fp.r + uStroke) * fp.g
                            * smoothstep(uPane.y * 0.5, uPane.y * 0.5 + 0.02, min((pv.y - pv.x) * 0.5 / uPane.x, fp.r + uStroke));
            vec3 paneC = (pv.z < 0.34 ? uFillA : pv.z < 0.67 ? uFillB : uFillC) * (0.65 + 0.7 * fract(pv.z * 7.31));
            add += paneC * glassHere * uPane.w * 0.5 * exp(-below * 1.8) * (1.0 - a);
        }
    }
#endif
    if (uInline > 0.0 && dot(uHover, uHover) > 0.0) {
        // the drawn line is its own element: the letter's outline again, lifted a little off it
        float dh = texture(uFieldTex, (p0 - uHover) / uField.xy).r;
        float line = smoothstep(-aw, aw, uInline * 0.5 - abs(dh - uInlineOff - uInline * 0.5));
        col = mix(col, uInlineCol, line);
        a = max(a, line);
    }
#ifdef HOLO
    {
        // A projection is three colours that never quite line up, a fainter copy hanging behind it,
        // the beam it rides up on from the floor, and a little static.
        vec3 hc0 = fillAt(0.5);
        float dx = 0.012 * (0.6 + 0.4 * sin(uTime * 1.7));
        float mR = smoothstep(-aw, aw, fieldD(p0 + vec2(dx, 0.0)));
        float mB = smoothstep(-aw, aw, fieldD(p0 - vec2(dx, 0.0)));
        float ghost = smoothstep(-aw, aw, fieldD(p0 + vec2(0.02, 0.025) + uTilt * 0.03));
        add += (vec3(1.0, 0.15, 0.3) * mR + vec3(0.2, 0.4, 1.0) * mB) * 0.22 * uHolo.x * (1.0 - a) * presence(p0);
        add += hc0 * ghost * 0.12 * uHolo.x * (1.0 - a);
        vec2 apex = vec2(uField.x * 0.5, uFloor.y * uField.y);
        float rise = (p0.y - apex.y) / max(uField.y * 0.5 - apex.y, 0.05);
        float halfW = (uField.x * 0.5 - 0.6) * sat(rise) + 0.03;
        float across = abs(p0.x - apex.x) / max(halfW, 1e-3);
        float cone = (1.0 - smoothstep(0.75, 1.0, across)) * smoothstep(0.0, 0.08, rise) * (1.0 - smoothstep(0.9, 1.7, rise));
        float rays = 0.55 + 0.45 * tnoise(vec2((p0.x - apex.x) / max(halfW, 1e-3) * 14.0, uTime * 0.7));
        add += hc0 * cone * rays * 0.055 * uHolo.x * (1.0 - a);
        add += hc0 * (tnoise(p0 * 190.0 + uTime * 61.0) - 0.5) * 0.3 * a * uHolo.x;
    }
#endif
    a *= uOpacity;
    under *= uOpacity * (1.0 - a);
    o = vec4(col * a + uShadowCol * under + add * uOpacity, a + under);
#endif
}
