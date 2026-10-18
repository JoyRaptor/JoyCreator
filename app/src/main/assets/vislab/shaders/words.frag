// WORDS: the words of a song or a voice as art, never a caption line (owner, 10-17). Two
// choices make a look: the SCENE the words live in (flying at you through space, wound on a
// spiral banner, along a tunnel wall, breaking into smoke, beating in the puck, circling a
// ring, riding a weaving ribbon, curling up off a cigar, neon signs down a night street,
// written in stars, bursting as fireworks) and the LETTERS they are made of (glow, neon tube,
// outline, stars). The word being sung lights up letter by letter as it goes.
in vec2 vUv;
in vec2 vPos;
out vec4 o;
uniform sampler2D uWords;   // 16 slots of 2048 x 128 stacked top to bottom; ink in alpha
uniform vec4 uWord[16];     // slot, start - now (s), end - now (s), width / height
uniform vec4 uWordB[16];    // flow - flow now (text heights), order, hash, duration (s)
uniform float uWordCount;
uniform float uFlowNow;     // the words laid end to end so far (text heights): how far we have travelled

#define MODE     uP[0].x   // see main()
#define SIZE     uP[0].y   // text height, scene units
#define BRIGHTN  uP[0].z
#define PALSHIFT uP[0].w
#define SPEEDK   uP[1].x
#define SPIN     uP[1].y
#define GLOWK    uP[1].z
#define SPREAD   uP[1].w
#define TILT     uP[2].x
#define LANES    uP[2].y
#define SMOKEK   uP[2].z
#define PUMP     uP[2].w
#define BANNER   uP[3].x
#define COLBY    uP[3].y
#define HUESPIN  uP[3].w
#define LETTERS  uP[4].x   // 0 glow, 1 neon tube, 2 outline, 3 stars
#define FLICKER  uP[4].y   // neon stutter as a word switches on
#define WEAVE    uP[4].z   // how much ribbons and smoke wander
#define ACROSS   uP[4].w   // where the ribbon / ember / launch sits, left to right
#define UPK      uP[5].x   // ... and up and down

// Hosts build one small program per scene and letter style (SCENE, LETTER_STYLE defined ahead
// of this file): a single program holding every scene ran at 5 fps on the Note 9, because the
// GPU budgets for the heaviest path whichever scene is showing. Undefined = all, chosen at run time.
#ifndef SCENE
#define SCENE -1
#endif
#ifdef LETTER_STYLE
#define ST float(LETTER_STYLE)
#else
#define ST LETTERS
#endif

const float SLOTS = 16.0;
const float SLOT_W = 16.0;   // a slot is 16 text heights wide

// ---------------------------------------------------------------- reading the words

// Ink of word i at uv (0..1 across its box, v = 0 at the bottom), at mip level lod.
float ink(int i, vec2 uv, float lod) {
    if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) return 0.0;
    vec4 w = uWord[i];
    vec2 a = vec2(uv.x * w.w / SLOT_W, (w.x + 1.0 - uv.y) / SLOTS);
    float k = textureLod(uWords, a, lod).a;
    if (lod < 0.75) return k;
    // Blurred samples fade before the edge of the word's box: no hard frame round the glow
    // (seen on the Note 9), and no bleed from the slot above or below.
    float ex = min(uv.x, 1.0 - uv.x) * w.w, ey = min(uv.y, 1.0 - uv.y);
    return k * smoothstep(0.0, 0.22, ex) * smoothstep(0.0, 0.1, ey);
}

// The mip level that keeps letters sharp but never shimmering when a text height covers `sz`
// scene units on screen (128 texels per text height in the atlas). Explicit, so it also holds
// inside loops and branches where the GPU cannot work it out.
float lodFor(float sz) { return max(0.0, log2(128.0 * uPx / max(sz, 1e-5))); }

// The letters in the chosen style; `glow` is the soft halo round them.
float letters(int i, vec2 uv, float lod, out float glow) {
    glow = ink(i, uv, lod + 3.5);
    float st = ST;
    if (st < 0.5) return ink(i, uv, lod);
    if (st < 2.5) {
        // Neon tube / outline: a line along the letter edges, where the blurred ink crosses half.
        float soft = ink(i, uv, lod + (st < 1.5 ? 1.8 : 1.0));
        float band = 1.0 - abs(soft - 0.5) * 2.0;
        return smoothstep(st < 1.5 ? 0.25 : 0.55, 0.92, band);
    }
    // Stars: the letters drawn as twinkling points of light.
    float N = 14.0;
    vec2 q = vec2(uv.x * uWord[i].w, uv.y) * N;
    vec2 cell = floor(q);
    vec2 j = (hash22(cell + uWordB[i].y * 7.0) - 0.5) * 0.5;
    vec2 c = (cell + 0.5 + j) / N;
    float k = ink(i, vec2(c.x / uWord[i].w, c.y), lod + 1.0);
    float tw = 0.6 + 0.4 * sin(TIME * 6.0 + hash12(cell) * 40.0);
    float r = 0.16 + 0.22 * k;
    return smoothstep(r, r * 0.25, length(fract(q) - 0.5 - j)) * smoothstep(0.35, 0.7, k) * tw;
}

vec3 wordColour(int i) {
    float k = COLBY < 0.5 ? uWordB[i].z * 0.8 : uWordB[i].y * 0.11;
    return palw(k + PALSHIFT + TIME * HUESPIN);
}

// 1 while the word is being sung, 0 otherwise; `sung` = how far through it is (0..1).
float singing(int i, out float sung) {
    vec4 w = uWord[i];
    sung = clamp(-w.y / max(w.z - w.y, 1e-3), 0.0, 1.0);
    return step(w.y, 0.0) * step(0.0, w.z);
}

// A neon tube stutters for a moment as it switches on.
float flick(int i) {
    float since = -uWord[i].y;
    if (FLICKER <= 0.0 || since < 0.0 || since > 0.45) return 1.0;
    return mix(1.0, step(0.42, hash11(floor(since * 30.0) + uWordB[i].y * 3.1)), FLICKER);
}

// Light for word i at uv: the letters (brighter once sung), the glow, and how much of the pixel
// the letters cover. sz: the word's text height on screen, scene units.
vec3 lit(int i, vec2 uv, float fade, float sz, inout float cover) {
    if (fade <= 0.001) return vec3(0.0);
    float glow;
    float a = letters(i, uv, lodFor(sz), glow);
    float sung;
    float on = singing(i, sung);
    // Karaoke inside the art: letters already sung are lit, the rest wait a little dimmer.
    float done = on > 0.5 ? step(uv.x, sung * 1.04) : step(uWord[i].z, 0.0);
    float lift = mix(0.55, 1.0, done) * (1.0 + on * (0.35 + KICK * PUMP)) * flick(i);
    vec3 c = wordColour(i);
    vec3 core = ST > 0.5 && ST < 1.5 ? mix(c, vec3(1.0), 0.45) : c;   // a whiter tube
    cover = max(cover, a * fade);
    return (core * a * lift * 1.6 + c * glow * GLOWK * (0.6 + on)) * fade;
}

// ---------------------------------------------------------------- 0 star flight
vec3 flight(vec2 p, inout float cover) {
    vec3 col = vec3(0.0);
    int n = int(uWordCount);
    for (int i = 0; i < 16; i++) {
        if (i >= n) break;
        vec4 w = uWord[i];
        vec4 b = uWordB[i];
        float until = w.y, after = -w.z, dur = max(b.w, 0.05);
        // Far away before its time, at the hero distance as it starts, drifting nearer while it
        // is sung, then rushing past the camera.
        float z;
        if (until > 0.0) z = 1.0 + until * 2.6 * SPEEDK;
        else if (after < 0.0) z = 1.0 - 0.3 * clamp(-until / dur, 0.0, 1.0);
        else z = 0.7 - after * 2.2 * SPEEDK;
        if (z < 0.06) continue;
        vec2 dir = normalize(hash21(b.z * 91.7 + 3.1) - 0.5 + 1e-4);
        float reach = SPREAD * (0.35 + 0.65 * hash11(b.z * 13.1));
        vec2 home = dir * reach * 0.18;
        vec2 off = home + dir * reach * clamp(until * 0.5, 0.0, 1.5);
        if (after > 0.0) off = home + dir * after * 1.2 * SPEEDK;
        float s = SIZE * (1.0 + PUMP * BASS * 0.15);
        vec2 q = (p * z - off) / s;
        vec2 uv = vec2(q.x / w.w + 0.5, q.y + 0.5);
        float fade = clamp(1.0 - (z - 1.0) / (2.6 * SPEEDK * 2.5 + 0.5), 0.0, 1.0) * smoothstep(0.06, 0.35, z);
        col += lit(i, uv, fade, s / z, cover);
    }
    return col;
}

// ---------------------------------------------------------------- shared: text along a path
// f = position along the path, v = across it (0 = centre), both in text heights. Draws the word
// that sits at f. `wrap` > 0 repeats the path every `wrap` heights (a ring); `flip` mirrors each
// word (a path seen from behind) so it still reads.
// Find first, draw once: the word drawing stays out of the loop, so the GPU program stays small
// (drawing inside long loops ran at 18 fps on the Note 9).
vec3 along(float f, float v, float fade, float wrap, float sz, float flip, inout float cover) {
    int n = int(uWordCount);
    int hit = -1;
    float u = 0.0;
    for (int i = 0; i < 16; i++) {
        if (i >= n) break;
        float local = f - uWordB[i].x;
        if (wrap > 0.0) local = mod(local, wrap);
        if (local < 0.0 || local > uWord[i].w) continue;
        hit = i;
        u = local / uWord[i].w;
        break;
    }
    if (hit < 0) return vec3(0.0);
    return lit(hit, vec2(flip > 0.5 ? 1.0 - u : u, v + 0.5), fade, sz, cover);
}

// The banner the words ride on: a faint ribbon with bright edges.
vec3 banner(float v, float f, float fade) {
    float body = smoothstep(0.72, 0.62, abs(v));
    float edge = exp(-abs(abs(v) - 0.68) * 40.0);
    vec3 c = palw(0.15 + PALSHIFT + f * 0.01 + TIME * HUESPIN);
    return c * (body * 0.06 + edge * 0.5 * (0.6 + BASS)) * BANNER * fade;
}

// ---------------------------------------------------------------- 1 spiral banner
vec3 spiral(vec2 p, inout float cover) {
    // Laid on a disc tipped back into the screen, turning.
    float ct = cos(TILT), st = sin(TILT);
    float zz = max(1.0 + p.y * st * 0.9, 0.15);
    vec2 q = vec2(p.x, p.y / max(ct, 0.25)) / zz;
    q = rot2(TIME * SPIN) * q;
    float r = length(q);
    float th = -atan(q.y, q.x);                    // clockwise, so text reads left to right
    float A = SIZE * (1.5 + SPREAD) / TAU;         // radius gained per radian: band plus gap
    float turn = floor((r / A - th) / TAU + 0.5);
    float phi = th + TAU * turn;
    if (phi < 1.0) return vec3(0.0);
    float v = (r - A * phi) / SIZE;
    float R0 = 0.62 * min(EXT.x, EXT.y);           // where the word being sung sits
    float phi0 = R0 / A;
    float f = 0.5 * A * (phi * phi - phi0 * phi0) / SIZE;   // arc length from the anchor
    // Words wind inward and shrink into the centre; the coming ones arrive from outside.
    float fade = smoothstep(1.0, 4.0, phi) * smoothstep(1.6 * max(EXT.x, EXT.y), 0.9 * max(EXT.x, EXT.y), r);
    return along(f, v, fade, 0.0, SIZE / zz, 0.0, cover) + banner(v, f, fade);
}

// ---------------------------------------------------------------- 2 tunnel ticker
vec3 ticker(vec2 p, inout float cover) {
    float r = max(length(p), 1e-3);
    float a = atan(p.y, p.x) - TIME * SPIN;
    float depth = 0.4 / r;                         // into the tunnel
    float d0 = 0.4 / (0.7 * min(EXT.x, EXT.y));    // where the word being sung passes
    float Lz = SIZE * 1.4;                         // text height, in depth
    float lanes = max(1.0, floor(LANES + 0.5));
    float sector = TAU / lanes;
    float aa = mod(a + sector * 0.5, sector) - sector * 0.5;   // angle from the nearest lane
    float v = aa / (SIZE * 1.1);
    // Read on the wall like a corridor sign: far end on the left, near end on the right.
    float F = (depth - d0) / Lz;
    vec3 col = vec3(0.0);
    float fade = exp(-max(depth - d0, 0.0) * 0.35) * smoothstep(0.0, d0 * 0.6, depth);
    float sz = r * SIZE * 1.1;
    int n = int(uWordCount);
    for (int i = 0; i < 16; i++) {
        if (i >= n) break;
        float local = F - uWordB[i].x;
        if (local < 0.0 || local > uWord[i].w) continue;
        col += lit(i, vec2(1.0 - local / uWord[i].w, v + 0.5), fade, sz, cover);
    }
    // Guide rails either side of each lane, flowing with the words.
    float rail = exp(-abs(abs(v) - 0.75) * 30.0) * (0.5 + 0.5 * sin(F * 3.0 - TIME * 4.0));
    col += palw(0.3 + PALSHIFT + TIME * HUESPIN) * rail * BANNER * 0.4 * fade;
    return col;
}

// ---------------------------------------------------------------- 3 smoke
vec3 smoke(vec2 p, inout float cover) {
    vec3 col = vec3(0.0);
    int n = int(uWordCount);
    float life = 2.6 / max(SMOKEK, 0.2);
    for (int i = 0; i < 16; i++) {
        if (i >= n) break;
        vec4 w = uWord[i];
        float until = w.y, after = -w.z;
        if (until > 0.7 || after > life) continue;
        float form = clamp(1.0 - until / 0.7, 0.0, 1.0);        // condenses out of mist
        float dis = clamp(after / life, 0.0, 1.0);             // then lets go
        vec2 home = (hash21(uWordB[i].z * 41.0) - 0.5) * vec2(0.5, 0.35) * SPREAD;
        vec2 q = p - home;
        q.y -= dis * dis * 0.9 * SMOKEK;                        // smoke rises
        vec2 wv = curl2(vec3(q * 2.2, TIME * 0.35 + uWordB[i].z * 10.0));
        q += wv * (dis * 0.35 + (1.0 - form) * 0.2) * SMOKEK;
        float s = SIZE * (1.0 + dis * 0.6);
        vec2 uv = vec2(q.x / (s * w.w) + 0.5, q.y / s + 0.5);
        float ero = fbm2(q * 7.0 + uWordB[i].z * 9.0, 3) * 0.5 + 0.5;
        float keep = smoothstep(dis * 1.1 - 0.15, dis * 1.1 + 0.05, ero) * form;
        float lod = lodFor(s);
        float a = ink(i, uv, lod + dis * 4.0) * keep;
        float g = ink(i, uv, lod + 3.0 + dis * 2.0) * GLOWK;
        vec3 c = mix(wordColour(i), vec3(0.75) + 0.25 * wordColour(i), dis);
        float sung;
        float on = singing(i, sung);
        cover = max(cover, a * (1.0 - dis));
        col += c * (a * (1.2 + on * 0.6 + KICK * PUMP * on) + g * (0.5 + dis)) * (1.0 - dis * 0.7);
    }
    return col;
}

// ---------------------------------------------------------------- 4 puck
vec3 puck(vec2 p, inout float cover) {
    vec3 col = vec3(0.0);
    int n = int(uWordCount);
    for (int i = 0; i < 16; i++) {
        if (i >= n) break;
        vec4 w = uWord[i];
        float until = w.y, after = -w.z;
        if (until > 0.15 || after > 0.25) continue;
        // Pops in on its start, pumps with the kick, shrinks away as the next arrives.
        float inK = smoothstep(0.15, -0.05, until);
        float outK = smoothstep(0.25, 0.0, after);
        float fit = min(SIZE, SPREAD * min(EXT.x, EXT.y) * 1.6 / w.w);
        float s = fit * (0.6 + 0.4 * inK) * (1.0 + KICK * PUMP * 0.3 + BASS * PUMP * 0.1);
        vec2 q = rot2(sin(TIME * 1.3 + uWordB[i].z * 6.0) * SPIN * 0.3) * p;
        vec2 uv = vec2(q.x / (s * w.w) + 0.5, q.y / s + 0.5);
        col += lit(i, uv, inK * outK, s, cover);
    }
    return col;
}

// ---------------------------------------------------------------- 5 ring
vec3 ring(vec2 p, inout float cover) {
    float R = SPREAD * 0.55 * min(EXT.x, EXT.y);
    // Tipped back like a planet's ring (Tilt), swaying, never a full turn: the word being sung
    // stays near the top, the right way up.
    float ct = cos(TILT);
    vec2 q = vec2(p.x, p.y / max(ct, 0.2));
    q = rot2(sin(TIME * 0.5) * SPIN * 3.0) * q;
    float r = length(q);
    float v = (r - R) / SIZE;
    float th = -atan(q.y, q.x) + PI * 0.5;         // clockwise from the top: reads left to right
    float circ = TAU * R / SIZE;                   // ring length in text heights
    float f = th / TAU * circ;
    float back = mix(1.0, 0.45 + 0.55 * smoothstep(0.2, -0.4, q.y / max(R, 1e-3)), step(0.05, TILT));
    float fade = smoothstep(1.2, 0.6, abs(v)) * back;
    return along(f, v, fade, circ, SIZE * mix(1.0, ct, 0.5), 0.0, cover) + banner(v, f, back);
}

// ---------------------------------------------------------------- 6 weaving ribbon
// The words ride an invisible ribbon that weaves off toward the horizon like smoke. You travel
// along it: the coming words wait far off near the centre, the one being sung passes close by,
// big and readable, rippling with depth as the ribbon banks. Camera space: x right, y up, z
// ahead, the screen at z = 1; f = text heights along the ribbon from the word being sung.
vec3 ribbonAt(float f) {
    float H = SIZE * 0.9;
    float t = TIME * SPEEDK;
    // Off to one side (Across), so the banner is seen at a slant: readable, with depth.
    float x = ACROSS * 0.85 + WEAVE * 0.11 * (sin(f * 0.21 + t * 0.6) + 0.6 * sin(f * 0.47 - t * 1.1));
    float y = UPK * 0.45 + WEAVE * 0.12 * (sin(f * 0.29 - t * 0.8) + 0.5 * cos(f * 0.63 + t * 0.5));
    return vec3(x, y, 1.1 + f * H);
}

vec2 ribbonUp(float f) {
    float bank = WEAVE * 0.3 * sin(f * 0.33 + TIME * SPEEDK * 0.9);
    return vec2(-sin(bank), cos(bank));
}

vec3 haze(float v, float f, float fade) {
    float n = fbm2(vec2(f * 0.35 - TIME * 0.4, v * 1.5), 3) * 0.5 + 0.5;
    float body = smoothstep(0.85, 0.0, abs(v)) * n;
    return palw(0.2 + PALSHIFT + f * 0.012 + TIME * HUESPIN) * body * 0.14 * BANNER * fade;
}

vec3 weave(vec2 p, inout float cover) {
    float H = SIZE * 0.9;
    float fMin = (0.25 - 1.1) / H, fMax = 42.0;
    const int N = 64;
    float ds = (fMax - fMin) / float(N);
    // Where this pixel crosses the ribbon, far to near; only the 3 nearest are kept.
    vec4 hits[3];    // f, v, fade, size on screen
    float flips[3];
    int nh = 0;
    vec3 Pb = ribbonAt(fMax);
    for (int k = N - 1; k >= 0; k--) {
        float fa = fMin + float(k) * ds;
        vec3 Pa = ribbonAt(fa);
        vec2 A = Pa.xy / Pa.z, B = Pb.xy / Pb.z;
        vec2 W = ribbonUp(fa) * H / Pa.z;          // one text height, on screen
        vec2 ab = B - A;
        float det = ab.x * W.y - ab.y * W.x;
        if (abs(det) > 1e-9) {
            vec2 d = p - A;
            float h = (d.x * W.y - d.y * W.x) / det;   // along this piece, 0..1
            float v = (ab.x * d.y - ab.y * d.x) / det; // across, text heights
            if (h >= 0.0 && h < 1.0 && abs(v) < 0.85) {
                float f = fa + h * ds;
                float z = mix(Pa.z, Pb.z, h);
                float fade = smoothstep(0.3, 0.75, z) * smoothstep(fMax * H, fMax * H * 0.5, z - 1.1);
                if (nh == 3) {
                    hits[0] = hits[1]; flips[0] = flips[1];
                    hits[1] = hits[2]; flips[1] = flips[2];
                    nh = 2;
                }
                hits[nh] = vec4(f, v, fade, length(W));
                flips[nh] = det < 0.0 ? 1.0 : 0.0;    // seen from behind: mirror so it reads
                nh++;
            }
        }
        Pb = Pa;
    }
    // Each crossing laid over the ones behind it.
    vec3 col = vec3(0.0);
    float cov = 0.0;
    for (int k = 0; k < 3; k++) {
        if (k >= nh) break;
        vec4 h4 = hits[k];
        float c2 = 0.0;
        vec3 L = along(h4.x, h4.y, h4.z, 0.0, h4.w, flips[k], c2) + haze(h4.y, h4.x, h4.z);
        col = col * (1.0 - c2) + L;
        cov = c2 + cov * (1.0 - c2);
    }
    cover = max(cover, cov);
    return col;
}

// ---------------------------------------------------------------- 7 cigar smoke
// Each word curls up off a glowing ember as it is sung, swaying, spreading and greying as it
// rises, until it is only smoke.
vec3 cigar(vec2 p, inout float cover) {
    vec2 src = vec2(ACROSS * EXT.x * 0.7, -EXT.y * 0.82 + UPK * 0.4);
    vec3 col = vec3(0.0);
    float e = length(p - src);
    col += palw(0.9 + PALSHIFT) * (exp(-e * 70.0) * (1.4 + BASS * 1.5) + exp(-e * 14.0) * 0.12);
    // The thread of smoke the words rise out of.
    vec2 tq = p - src;
    if (tq.y > 0.0) {
        float sway = sin(tq.y * 7.0 - TIME * 1.3) * 0.08 * tq.y + snoise(vec2(tq.y * 2.5, TIME * 0.35)) * 0.12 * tq.y * WEAVE;
        float thread = exp(-abs(tq.x - sway) * 90.0 / (1.0 + tq.y * 7.0)) * exp(-tq.y * 1.1);
        col += vec3(0.75, 0.78, 0.85) * thread * 0.4 * BANNER;
    }
    int n = int(uWordCount);
    float life = 4.0 / max(SMOKEK, 0.2);
    for (int i = 0; i < 16; i++) {
        if (i >= n) break;
        vec4 w = uWord[i];
        float hz = uWordB[i].z;
        float age = -w.y;                              // seconds since the word started
        float gone = -w.z;                             // seconds since it ended
        if (age < -0.25 || gone > life) continue;
        // Readable while it is sung; after that the smoke takes it.
        float a01 = clamp(gone / life, 0.0, 1.0);
        float rise = max(age, 0.0);
        vec2 c = src + vec2(0.0, 0.16 + rise * 0.2 * SPEEDK + max(gone, 0.0) * max(gone, 0.0) * 0.03);
        c.x += sin(rise * 1.6 + hz * 6.3) * 0.06 * rise * WEAVE + snoise(vec2(rise * 0.4, hz * 9.0)) * 0.1 * rise * WEAVE;
        float s = SIZE * (0.8 + rise * 0.22) * smoothstep(-0.25, 0.2, age);
        if (s < 1e-3) continue;
        vec2 q = rot2(sin(rise * 0.9 + hz * 4.0) * 0.3 * a01 * WEAVE) * (p - c);
        // The higher it drifts, the more the smoke curls the letters.
        q += curl2(vec3(q / s * 0.45, TIME * 0.3 + hz * 7.0)) * s * (0.015 + a01 * 0.35) * WEAVE;
        vec2 uv = vec2(q.x / (s * w.w) + 0.5, q.y / s + 0.5);
        float ero = fbm2(q / s * 2.2 + hz * 9.0, 3) * 0.5 + 0.5;
        float fade = smoothstep(a01 * 1.15 - 0.2, a01 * 1.15 + 0.05, ero) * (1.0 - a01 * a01);
        float cv = 0.0;
        vec3 L = lit(i, uv, fade, s / (1.0 + a01 * 5.0), cv);   // softer as it rises
        col += mix(L, vec3(luma(L)) * 0.9, a01 * 0.75);
        cover = max(cover, cv * (1.0 - a01));
    }
    return col;
}

// ---------------------------------------------------------------- 8 neon street
// Driving down a night street: buildings either side, the words as neon signs you pass. The
// coming ones are lit further down the street, the one being sung stutters on beside you, and
// the drive keeps pace with the words.
vec3 street(vec2 p, inout float cover) {
    float eye = 0.42 + UPK * 0.3;                  // eye height above the road
    float Wd = 0.9 + SPREAD * 0.6;                 // half the street's width
    float Hs = SIZE * 1.6;                         // a sign's text height
    float SP = Hs * 1.05;                          // street length per text height of words
    float travel = uFlowNow * SP;
    vec3 col = vec3(0.0);
    // What the ray hits first: a wall, the road or the sky.
    float side = p.x < 0.0 ? -1.0 : 1.0;
    float tw = abs(p.x) > 1e-4 ? Wd / abs(p.x) : 1e9;
    float yw = eye + p.y * tw;
    float top = 1.5 + 1.8 * hash11(floor((tw + travel) / 2.4) + side * 13.0);
    bool wallHit = tw < 60.0 && yw > 0.0 && yw < top;
    float fog = exp(-tw * 0.09);
    if (wallHit) {
        // Dark facades with a scatter of lit windows.
        vec2 g = vec2((tw + travel) * 4.5, yw * 6.0);
        vec2 cell = floor(g), fw = fract(g);
        float lamp = step(0.8, hash12(cell + side * 31.0)) * (0.6 + 0.4 * hash12(cell + 5.5));
        float pane = step(0.22, fw.x) * step(fw.x, 0.78) * step(0.3, fw.y) * step(fw.y, 0.78);
        vec3 warm = mix(vec3(1.0, 0.62, 0.3), palw(hash12(cell) + PALSHIFT), 0.25);
        col += (vec3(0.01, 0.01, 0.016) + warm * lamp * pane * 0.1) * BANNER * fog;
    } else if (p.y < 0.0) {
        float tg = eye / -p.y;
        float x = p.x * tg;
        if (abs(x) < Wd) {
            float dash = step(0.55, fract((tg + travel) * 0.9)) * smoothstep(0.035, 0.015, abs(x));
            col += (vec3(0.01) + vec3(0.9, 0.8, 0.5) * dash * 0.25) * BANNER * exp(-tg * 0.09);
        }
    }
    int n = int(uWordCount);
    int signI[2];
    vec4 signA[2];   // uv, fade, size on screen
    int ns = 0;
    for (int i = 0; i < 16; i++) {
        if (i >= n) break;
        vec4 w = uWord[i];
        vec4 b = uWordB[i];
        float sgn = mod(b.y, 2.0) < 0.5 ? -1.0 : 1.0;   // which side of the street
        float len = w.w * Hs;
        float z0 = 2.2 + b.x * SP;                       // the sign's near end, down the street
        if (z0 + len < 0.25) continue;
        float y0 = 0.5 + hash11(b.z * 17.0) * 0.9;       // its bottom edge, above the road
        vec3 c = wordColour(i);
        float unlit = w.y > 0.0 ? 0.3 : 1.0;             // coming words wait as dark tubes
        float t = 0.0;
        vec2 uv = vec2(-1.0);
        if (b.z > 0.62) {
            // A blade sign sticking out from the wall, facing you.
            t = z0;
            if (t < 0.25) continue;
            float xw = p.x * t, ywb = eye + p.y * t;
            float u = sgn < 0.0 ? (xw + Wd) / len : (xw - (Wd - len)) / len;
            uv = vec2(u, (ywb - y0) / Hs);
        } else if (sgn == side && wallHit) {
            // Flat on the wall, running along the street.
            t = tw;
            float u = sgn < 0.0 ? (tw - z0) / len : (z0 + len - tw) / len;
            uv = vec2(u, (yw - y0) / Hs);
            // The lit sign washes the wall round it with its colour.
            vec2 dd = max(vec2(abs(u - 0.5) - 0.5, abs(uv.y - 0.5) - 0.5) * vec2(len, Hs), 0.0);
            col += c * exp(-length(dd) * 5.0) * 0.12 * unlit * fog;
        } else {
            continue;
        }
        float fd = exp(-t * 0.09) * smoothstep(0.25, 0.6, t);
        // A thin neon frame round the sign, then the letters.
        vec2 box = abs(uv - 0.5) - vec2(0.5 + 0.12 / w.w, 0.72);
        float frame = exp(-abs(max(box.x * w.w, box.y)) * 26.0) * step(max(box.x * w.w, box.y), 0.06);
        col += c * frame * 0.5 * unlit * flick(i) * fd;
        if (ns < 2 && uv.x > -0.05 && uv.x < 1.05 && uv.y > -0.3 && uv.y < 1.3) {
            signI[ns] = i;
            signA[ns] = vec4(uv, fd * unlit, Hs / t);
            ns++;
        }
    }
    for (int k = 0; k < 2; k++) {
        if (k >= ns) break;
        col += lit(signI[k], signA[k].xy, signA[k].z, signA[k].w, cover);
    }
    return col;
}

// ---------------------------------------------------------------- 9 constellations
// Each word is written in stars across the night sky: its stars light one by one as the word
// arrives, faint lines join them into a constellation, then they let go and drift away.
vec3 constellations(vec2 p, inout float cover) {
    vec3 col = vec3(0.0);
    // A quiet sky.
    vec2 g = p * 24.0;
    vec2 sc = floor(g);
    vec2 sj = hash22(sc);
    float bg = smoothstep(0.07, 0.0, length(fract(g) - sj)) * step(0.85, hash12(sc + 3.7));
    col += vec3(0.75, 0.8, 1.0) * bg * (0.5 + 0.5 * sin(TIME * 2.0 + hash12(sc) * 30.0)) * 0.3 * BANNER;
    const float N = 9.0;                             // stars per text height
    int n = int(uWordCount);
    for (int i = 0; i < 16; i++) {
        if (i >= n) break;
        vec4 w = uWord[i];
        vec4 b = uWordB[i];
        float since = -w.y, after = -w.z;
        if (since < -1.2 || after > 2.2) continue;
        float s = SIZE;
        vec2 half_ = vec2(s * w.w * 0.5, s * 0.5);
        vec2 home = (hash21(b.z * 37.0 + 1.0) - 0.5) * 2.0 * max(EXT * vec2(0.85, 0.7) * SPREAD - half_, 0.0);
        vec2 q = (p - home - vec2(0.0, max(after, 0.0) * 0.05)) / s;   // text heights, origin centre
        q.x += w.w * 0.5;
        q.y += 0.5;
        if (q.x < -0.3 || q.x > w.w + 0.3 || q.y < -0.3 || q.y > 1.3) continue;
        vec2 gq = q * N;
        vec2 cell = floor(gq);
        float lod = lodFor(s) + 1.0;
        // The stars of the 3 x 3 cells around: where each sits, and whether it is lit.
        vec2 sp[9];
        float on[9];
        for (int k = 0; k < 9; k++) {
            vec2 cc = cell + vec2(float(k - (k / 3) * 3 - 1), float(k / 3 - 1));
            vec2 j = (hash22(cc + b.y * 5.3) - 0.5) * 0.7;
            vec2 at = cc + 0.5 + j;
            float inkc = ink(i, vec2(at.x / N / w.w, at.y / N), lod);
            float hh = hash12(cc + b.y * 1.7);
            float arrive = -1.1 + hh * 1.0;              // each star lights at its own moment
            float leave = 0.2 + hash12(cc + 9.1) * 1.6;
            float lit01 = smoothstep(arrive, arrive + 0.12, since) * (1.0 - smoothstep(leave, leave + 0.4, after));
            on[k] = step(0.5, inkc) * lit01;
            sp[k] = at + (hash22(cc + 2.2) - 0.5) * max(after - leave, 0.0) * 1.5;   // leaving: drift apart
        }
        vec3 c = wordColour(i);
        float sung;
        float singNow = singing(i, sung);
        float stars = 0.0, lines = 0.0;
        for (int k = 0; k < 9; k++) {
            if (on[k] <= 0.0) continue;
            vec2 d = gq - sp[k];
            float r = length(d);
            float tw = 0.75 + 0.25 * sin(TIME * 5.0 + float(k) * 7.0 + b.y);
            stars += on[k] * tw * (exp(-r * r * 26.0) * 1.8 + exp(-r * 4.0) * 0.22
                     + (exp(-abs(d.x) * 30.0 - abs(d.y) * 4.0) + exp(-abs(d.y) * 30.0 - abs(d.x) * 4.0)) * 0.12);
        }
        // Lines between neighbouring lit stars (right and up of each, so each line is drawn once).
        for (int k = 0; k < 9; k++) {
            int kx = k - (k / 3) * 3, ky = k / 3;
            if (kx < 2 && on[k] * on[k + 1] > 0.0) lines += exp(-sdSegment(gq, sp[k], sp[k + 1]) * 22.0) * on[k] * on[k + 1];
            if (ky < 2 && on[k] * on[k + 3] > 0.0) lines += exp(-sdSegment(gq, sp[k], sp[k + 3]) * 22.0) * on[k] * on[k + 3];
        }
        float lift = 1.0 + singNow * (0.5 + KICK * PUMP);
        col += (mix(c, vec3(1.0), 0.55) * stars + c * lines * (0.25 + 0.5 * BANNER)) * lift;
        cover = max(cover, min(stars, 1.0) * 0.5);
    }
    return col;
}

// ---------------------------------------------------------------- 10 fireworks
// A rocket climbs, and on the word's beat it bursts into the word, made of sparks that fly out
// from the burst, hang, sag and twinkle out.
vec3 fireworks(vec2 p, inout float cover) {
    vec3 col = vec3(0.0);
    const float CLIMB = 0.7;
    int n = int(uWordCount);
    for (int i = 0; i < 16; i++) {
        if (i >= n) break;
        vec4 w = uWord[i];
        vec4 b = uWordB[i];
        float until = w.y, since = -w.y;
        if (until > CLIMB || since > 2.8) continue;
        float s = SIZE * 1.3;
        float halfW = s * w.w * 0.5;
        vec2 burst = vec2((hash11(b.z * 13.0) - 0.5) * 2.0 * max(EXT.x * 0.85 * SPREAD - halfW, 0.0),
                          EXT.y * (0.05 + 0.4 * hash11(b.z * 7.0)) + UPK * 0.3);
        vec2 launch = vec2(burst.x + (hash11(b.z * 5.0) - 0.5) * 0.4 + ACROSS * 0.3, -EXT.y * 1.05);
        vec3 c = wordColour(i);
        if (until > 0.0) {
            // The rocket and its sparkling trail.
            float k = 1.0 - until / CLIMB;
            k = 1.0 - (1.0 - k) * (1.0 - k);
            vec2 r = mix(launch, burst, k);
            vec2 tail = mix(launch, burst, max(k - 0.3, 0.0));
            float tr = exp(-sdSegment(p, tail, r) * 220.0) * smoothstep(0.0, 0.3, dot(p - tail, r - tail) / max(dot(r - tail, r - tail), 1e-5));
            col += mix(vec3(1.0, 0.85, 0.6), c, 0.3) * (exp(-length(p - r) * 120.0) * 2.5 + tr * 0.6 * (0.6 + 0.4 * hash12(floor(p * 300.0) + TIME)));
            continue;
        }
        float k = 1.0 - exp(-since * 7.0);              // how far the sparks have flown out
        vec2 sag = vec2(0.0, -0.11 * since * since);
        vec2 q = (p - burst - sag) / max(k, 0.04);
        vec2 uv = vec2(q.x / (s * w.w) + 0.5, q.y / s + 0.5);
        // Sparks: the letters as points, streaked along the line they flew out on.
        float N = 12.0;
        vec2 g = vec2(uv.x * w.w, uv.y) * N;
        vec2 cell = floor(g);
        vec2 j = (hash22(cell + b.y * 3.3) - 0.5) * 0.6;
        vec2 at = (cell + 0.5 + j) / N;
        float inkc = ink(i, vec2(at.x / w.w, at.y), lodFor(s * k) + 1.0);
        vec2 radial = normalize(vec2((at.x - w.w * 0.5), at.y - 0.5) + 1e-4);
        vec2 d = fract(g) - 0.5 - j;
        float along_ = dot(d, radial), across = dot(d, vec2(-radial.y, radial.x));
        float streak = (1.0 - k) * 2.5 + 0.12;
        float spark = exp(-across * across * 45.0) * exp(-max(-along_, 0.0) / streak * 4.0 - max(along_, 0.0) * 9.0)
                    + exp(-length(d) * 7.0) * 0.18;
        float twinkle = since > 1.0 ? step(0.35, hash11(floor(TIME * 18.0) + cell.x * 3.1 + cell.y * 7.7)) : 1.0;
        float fade = 1.0 - smoothstep(1.3, 2.8, since);
        vec3 hot = mix(vec3(1.0, 0.95, 0.8), c, smoothstep(0.0, 0.5, since));
        float sp = spark * smoothstep(0.35, 0.65, inkc) * twinkle * fade;
        col += hot * sp * 2.2;
        col += c * ink(i, uv, lodFor(s * k) + 3.0) * 0.2 * fade * GLOWK * smoothstep(0.3, 0.7, k);   // the word glows through its sparks
        cover = max(cover, min(sp, 1.0) * 0.4);
        // The flash of the burst.
        col += hot * exp(-length(p - burst) * 26.0) * exp(-since * 10.0) * 0.45;
    }
    return col;
}

void main() {
    vec2 p = vPos;
    float cover = 0.0;
    vec3 col;
#if SCENE == 0
    col = flight(p, cover);
#elif SCENE == 1
    col = spiral(p, cover);
#elif SCENE == 2
    col = ticker(p, cover);
#elif SCENE == 3
    col = smoke(p, cover);
#elif SCENE == 4
    col = puck(p, cover);
#elif SCENE == 5
    col = ring(p, cover);
#elif SCENE == 6
    col = weave(p, cover);
#elif SCENE == 7
    col = cigar(p, cover);
#elif SCENE == 8
    col = street(p, cover);
#elif SCENE == 9
    col = constellations(p, cover);
#elif SCENE == 10
    col = fireworks(p, cover);
#else
    float m = MODE;
    if (m < 0.5) col = flight(p, cover);
    else if (m < 1.5) col = spiral(p, cover);
    else if (m < 2.5) col = ticker(p, cover);
    else if (m < 3.5) col = smoke(p, cover);
    else if (m < 4.5) col = puck(p, cover);
    else if (m < 5.5) col = ring(p, cover);
    else if (m < 6.5) col = weave(p, cover);
    else if (m < 7.5) col = cigar(p, cover);
    else if (m < 8.5) col = street(p, cover);
    else if (m < 9.5) col = constellations(p, cover);
    else col = fireworks(p, cover);
#endif
    o = lightOut(col * BRIGHTN, cover);
}
