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
#endif
#ifdef REFRACT
uniform vec4  uGlass;        // bend, colour fringing, tint strength, dark edge
uniform vec3  uTint;
uniform vec2  uCaustic;      // dancing light inside: strength, cells per em
uniform float uGather;       // light gathered on the far side from the lamp, as in a drop
#endif
#ifdef DETAIL
uniform vec4  uDetail;       // kind, bumps per em, strength, drift speed
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
    if (L < 1e-4 || top - bottom < 1e-4) {
        q = p0;
        float d = fieldD(q);
        z = heightAt(max(d, 0.0));
        cover = sat(d / aaw + 0.5);
        return cover > 0.0 ? 1 : 0;
    }
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
    int kind = int(uDetail.x + 0.5);
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
    return 0.0;
}
#endif

void main() {
    vec2 p0 = vUv * uField.xy;
    float aw = 1.0 / uField.z;
    vec3 col = vec3(0.0), add = vec3(0.0);
    float a = 0.0;

#ifdef TUBE
    vec4 f = texture(uFieldTex, vUv);
    float seed = hash11(f.b * 91.7);
    float away = uTube.w > 0.5 ? -f.r + uTube.y : abs(f.r - uTube.x);   // distance from the tube's centre line
    float td = away - uTube.y;                                          // below 0 = inside the glass
    float x = sat(1.0 - away / max(uTube.y, 1e-4));                     // 0 at the glass, 1 on the centre line
    float inT = smoothstep(-aw, aw, -td);
    // switching on: a letter stutters before it holds; afterwards an unlucky one buzzes now and then
    float tick = floor(uTime * 24.0);
    float lit = step(hash21(vec2(tick, seed * 77.0)), f.g * f.g * 1.15) * step(0.02, f.g);
    float buzz = step(0.93, vnoise(vec2(uTime * uTubeLight.w + seed * 40.0, seed * 9.0))) * step(seed, 0.4);
    lit *= 1.0 - uTubeLight.z * buzz * (0.4 + 0.6 * hash21(vec2(tick, seed)));
    lit *= 0.9 + 0.1 * vnoise(p0 * 7.0 + seed * 30.0);                  // gas never burns quite evenly
    float cx = sat(f.b);
    vec3 tc = mix(uTubeA, uTubeB, smoothstep(0.5 - 0.5 * (1.0 - uTubeLight.y), 0.5 + 0.5 * (1.0 - uTubeLight.y) + 1e-3, cx));
    // a lit tube: colour at the glass, a white-hot thread down the middle
    float core = pow(x, uTube.z);
    vec3 hot = (tc * (0.35 + 0.9 * x) + vec3(1.0) * core * 1.4) * uTubeLight.x;
    // the glass itself: dark, with a thin window-light along the side that faces the lamp
    vec2 g = fieldGrad(p0);
    vec2 outward = normalize(g + vec2(1e-5, 0.0)) * (f.r > uTube.x ? 1.0 : -1.0) * (uTube.w > 0.5 ? -1.0 : 1.0);
    float facing = dot(outward, normalize(vec2(-0.45, 0.89)));
    float sheen = smoothstep(0.35, 0.6, 1.0 - x) * smoothstep(0.95, 0.7, 1.0 - x) * smoothstep(0.2, 0.9, facing);
    vec3 glass = vec3(0.02, 0.021, 0.026) * (0.5 + 1.5 * x * x) + tc * 0.035 + vec3(0.5, 0.55, 0.6) * sheen * 0.35;
    col = mix(glass, hot + vec3(1.0) * sheen * 0.25, lit);
    a = inT;
    float halo = exp(-max(td, 0.0) / max(uGlowSize, 1e-4));
    add = tc * uGlow * halo * halo * lit * (1.0 - inT);
    // the tubes stand off the wall, so they shade it (less where they are lit: their own light fills it)
    float under = 0.0;
    if (uShadow > 0.0) {
        float fs = texture(uFieldTex, vUv - uShadowOff / uField.xy).r;
        float aways = uTube.w > 0.5 ? -fs + uTube.y : abs(fs - uTube.x);
        under = (1.0 - smoothstep(0.0, max(uShadowSoft, aw), aways - uTube.y)) * uShadow * (1.0 - 0.45 * lit);
    }
    a *= uOpacity;
    add *= uOpacity;
    under *= uOpacity * (1.0 - a);
    o = vec4(col * a + uShadowCol * under + add, a + under);
    return;
#else

    vec2 lean = uExtrude + (vUv - uVanish) * uField.xy * uPersp + uTilt * uParallax;
    vec2 q; float z; float cover;
    int hit = march(p0, lean, q, z, cover);

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
#ifdef DETAIL
            float h0 = detail(q);
            vec2 dg = vec2(detail(q + vec2(aw, 0.0)) - h0, detail(q + vec2(0.0, aw)) - h0) / aw;
            n = normalize(vec3(n.xy - dg * uDetail.z * (1.0 - trim), n.z));
#endif
            // brushed grain: fine streaks that tip the surface a hair, so highlights smear along it
            float grain = vnoise(vec2(q.x * 9.0, q.y * 520.0)) - 0.5;
            n = normalize(vec3(n.x, n.y + grain * 0.22 * uWear.w * (1.0 - trim * 0.6), n.z));

            vec3 alb = fillAt(f.a);
#ifdef PATTERN
            alb = mix(alb, uPatternCol, pattern(q, f.a) * uPattern.z);
#endif
            float metal = mix(uMetal, uStrokeMetal, trim);
            float rough = uRough * mix(1.0, 0.3, trim);
            alb = mix(alb, uStrokeCol, trim);
            float ndl = max(dot(n, L), 0.0);
            float fres = pow(1.0 - sat(n.z), 3.0);
            vec3 E = envAt(n, c, q, rough);
            float sp = pow(max(dot(n, normalize(L + vec3(0.0, 0.0, 1.0))), 0.0), uShine) * uSpec;
            vec3 paint = alb * (uAmbient + (1.0 - uAmbient) * ndl) + E * uGloss * (0.04 + 0.96 * fres);
            col = mix(paint, E * alb, metal);
            col *= 1.0 + grain * 0.5 * uWear.w * metal;
            col = mix(col, alb, uUnlit) + sp;

            // shade: creases, the well beside the frame, and the shadow the frame throws
            float lit = 1.0 - uAO * (1.0 - smoothstep(0.0, max(uBevel, aw), d));
            if (uRim > 0.0) {
                lit *= 1.0 - uAO * onFace * exp(-max(d - uRim - wi, 0.0) / max(uInset * 1.6, 0.004));
                lit *= 1.0 - uCast * onFace * castShadow(q, heightAt(d));
            }
            // wear: grime gathers low and in corners; scratches catch the light
            float dirt = smoothstep(0.35, 0.8, fbm(q * 5.0 + 3.0));
            lit *= 1.0 - uWear.z * (0.45 * dirt + 0.4 * (1.0 - smoothstep(0.0, max(uBevel, aw) * 2.0, d)));
            col *= mix(1.0, lit, 1.0 - uUnlit);
            float scr = smoothstep(0.80, 0.97, vnoise(rot(0.5) * q * vec2(310.0, 5.0)))
                      + smoothstep(0.84, 0.98, vnoise(rot(-0.9) * q * vec2(220.0, 7.0)));
            col += (E * 0.6 + 0.04) * scr * uWear.y * (0.3 + 0.7 * metal);
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
            vec2 off = n.xy * uGlass.x * thick / uField.xy;
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
            float seeThrough = mix(sat(F * uGloss + sp + uGlass.z * 0.35), 1.0, uHasBack);
            col = mix(glassCol, col, trim);
            a = mix(seeThrough, 1.0, trim);
#else
            a = 1.0;
#endif
            if (uInline > 0.0) {
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
            float grain = vnoise(vec2(dot(q, vec2(-gn.y, gn.x)) * 9.0, z * 520.0)) - 0.5;
            vec3 E = envAt(n, c, q, uRough * 0.8 + 0.1);
            col = mix(mix(sc * ndl, sc, uUnlit), E * sc * (1.0 + grain * 0.5 * uWear.w), uSideMetal);
            col *= 1.0 - uAO * 0.6 * smoothstep(0.0, 0.25, dz) * (1.0 - uUnlit);
            a = 1.0;
        }
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
    }
    if (uGlow > 0.0) {
        vec4 f0 = texture(uFieldTex, vUv);
        float gd = min(f0.r + uStroke, 0.0);
        add += uGlowCol * uGlow * exp(gd / max(uGlowSize, 1e-4)) * (1.0 - a) * mix(1.0, f0.g, max(uReveal.x, uReveal.y));
    }
    a *= uOpacity;
    under *= uOpacity * (1.0 - a);
    o = vec4(col * a + uShadowCol * under + add * uOpacity, a + under);
#endif
}
