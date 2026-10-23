// WORDS: the words of a song or a voice as art, never a caption line (owner, 10-17). Three
// parts that mix freely (owner, 10-18):
//   PATH       where the words live: a weaving ribbon, a spiral, a ring, a tunnel wall, signs
//              down a night street, flying at you, the centre, scattered across the sky, rising
//              off an ember.
//   BEHAVIOUR  how each word arrives, is sung and leaves: karaoke, pop, neon flicker, smoke,
//              burst, sparkle.
//   LETTERS    what the letters are made of: glow, neon tube, outline, stars, sparks,
//              constellation.
// The word being sung lights up letter by letter as it goes, whatever the parts.
in vec2 vUv;
in vec2 vPos;
out vec4 o;
uniform sampler2D uWords;   // 48 slots of 2048 x 128 stacked top to bottom; ink in alpha
uniform vec4 uWord[48];     // slot, start - now (s), end - now (s), width / height
uniform vec4 uWordB[48];    // flow - flow now (text heights), order, hash, duration (s)
uniform vec2 uWordWin;      // how far ahead / behind (s) words are kept: where they arrive and leave
uniform float uWordCount;
uniform float uFlowNow;     // the words laid end to end so far (text heights): how far we have travelled

#define PATHK    uP[0].x   // 0 ribbon, 1 spiral, 2 ring, 3 tunnel, 4 street, 5 flight, 6 centre, 7 scatter, 8 rising
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
#define LETTERS  uP[4].x   // 0 glow, 1 neon tube, 2 outline, 3 stars, 4 sparks, 5 constellation
#define FLICKER  uP[4].y   // neon stutter as a word switches on
#define WEAVE    uP[4].z   // how much ribbons and smoke wander
#define ACROSS   uP[4].w   // where the ember / rocket launch sits, left to right
#define UPK      uP[5].x   // ... and up and down
#define TRAVEL   uP[5].y   // ribbon: 0 words coming at you, 1 going away
#define ROLL     uP[5].z   // ribbon: turn the whole ribbon round the screen, degrees
#define LEAN     uP[5].w   // ribbon: lean the banner over, degrees (0 upright, 90 lying flat)
#define RIPPLE   uP[6].x   // ribbon: how much it banks as it weaves
#define REACH    uP[6].y   // ribbon: how far off it reaches, text heights
#define CLOSEST  uP[6].z   // ribbon: how close the word being sung passes
#define SIDEK    uP[6].w   // ribbon: how far to the side of you it runs
#define BEHAVE   uP[7].x   // 0 karaoke, 1 pop, 2 neon flicker, 3 smoke, 4 burst, 5 sparkle
#define FADEK    uP[7].y   // seconds a word takes to fade in where it arrives and out where it leaves (0 = pop)
#define SHIFTK   uP[7].z   // slide the words along the path, text heights

// Hosts build one small program per path, behaviour and letter style (defined ahead of this
// file): one program holding everything ran at 5 fps on the Note 9, because the GPU budgets for
// the heaviest path whichever is showing. Undefined = everything, chosen at run time.
#ifndef WPATH
#define WPATH -1
#endif
#ifdef BEHAVIOUR
#define BH float(BEHAVIOUR)
#else
#define BH BEHAVE
#endif
#ifdef LETTER_STYLE
#define ST float(LETTER_STYLE)
#else
#define ST LETTERS
#endif

const float SLOTS = 48.0;
const float SLOT_W = 16.0;   // a slot is 16 text heights wide

// What the behaviour tells the letters (reset per word by behave()).
float gReveal = 1.0;    // share of the letters' grains already lit (sparkle)
float gLeave = 0.0;     // share already gone
float gScatter = 0.0;   // how far the leaving grains have drifted, text heights
float gMotion = 0.0;    // how fast the word is flying apart (burst): streaks the sparks
float gTwinkle = 0.0;   // 1 = sparks blink out
float gExpand = 1.0;    // how far a burst has opened past full size: its sparks spread apart

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

// Grains for the sparkle behaviour on solid letters: lit one by one, gone one by one.
float grains(int i, vec2 q) {
    if (gReveal >= 1.0 && gLeave <= 0.0) return 1.0;
    vec2 c = floor(q * 22.0);
    float h1 = hash12(c + uWordB[i].y * 3.7), h2 = hash12(c + 9.1);
    return step(h1, gReveal) * step(gLeave, h2) * (0.75 + 0.25 * sin(TIME * 9.0 + h1 * 40.0));
}

// Letters made of points of light: stars, sparks (streaked as the word flies apart) or a
// constellation (stars joined by faint lines). q in text heights from the word's corner.
float points(int i, vec2 q, float lod) {
    vec4 w = uWord[i];
    vec4 b = uWordB[i];
    // Leaving grains drift apart. Only while they leave: the swirl is 4 noise samples, and paid
    // for every word on every pixel it took Written in Stars from 60 to 11-19 fps on the Note 9.
    // Two 2D noises, not the 3D swirl (curl2, 4 noise samples): Written in Stars still ran at
    // 14-23 fps on the Note 9 with every leaving word swirling every pixel round it.
    vec2 qq = gScatter > 0.0 ? q - vec2(snoise(q * 0.7 + b.y), snoise(q * 0.7 + b.y + 17.3)) * gScatter : q;
    if (ST < 4.5) {
        float N = ST < 3.5 ? 14.0 : 12.0;
        vec2 g = qq * N;
        vec2 cell = floor(g);
        vec2 j = (hash22(cell + b.y * 7.0) - 0.5) * 0.5;
        vec2 at = (cell + 0.5 + j) / N;
        float k = ink(i, vec2(at.x / w.w, at.y), lod + 1.0);
        float on = smoothstep(0.35, 0.7, k) * step(hash12(cell + 1.3), gReveal) * step(gLeave, hash12(cell + 9.1));
        float tw = 0.6 + 0.4 * sin(TIME * 6.0 + hash12(cell) * 40.0);
        if (gTwinkle > 0.0) tw *= step(0.35, hash11(floor(TIME * 18.0) + cell.x * 3.1 + cell.y * 7.7));
        vec2 d = (fract(g) - 0.5 - j) * gExpand;      // sparks stay small as the word spreads
        if (ST < 3.5) {
            float r = 0.16 + 0.22 * k;
            return smoothstep(r, r * 0.25, length(d)) * on * tw;
        }
        vec2 radial = normalize(at - vec2(w.w * 0.5, 0.5) + 1e-4);
        float al = dot(d, radial), ac = dot(d, vec2(-radial.y, radial.x));
        float streak = gMotion * 2.5 + 0.12;
        return (exp(-ac * ac * 45.0) * exp(-max(-al, 0.0) / streak * 4.0 - max(al, 0.0) * 9.0)
                + exp(-length(d) * 7.0) * 0.18) * on * tw * 1.2;
    }
    // Constellation: the stars of the 3 x 3 cells around, and lines between lit neighbours.
    const float NC = 9.0;
    vec2 gq = qq * NC;
    vec2 cell = floor(gq);
    vec2 sp[9];
    float on[9];
    for (int k = 0; k < 9; k++) {
        vec2 cc = cell + vec2(float(k - (k / 3) * 3 - 1), float(k / 3 - 1));
        vec2 at = cc + 0.5 + (hash22(cc + b.y * 5.3) - 0.5) * 0.7;
        float inkc = ink(i, vec2(at.x / NC / w.w, at.y / NC), lod + 1.0);
        on[k] = step(0.5, inkc) * step(hash12(cc + b.y * 1.7), gReveal) * step(gLeave, hash12(cc + 9.1));
        sp[k] = at;
    }
    float stars = 0.0, lines = 0.0;
    for (int k = 0; k < 9; k++) {
        if (on[k] <= 0.0) continue;
        vec2 d = gq - sp[k];
        float r = length(d);
        float tw = 0.75 + 0.25 * sin(TIME * 5.0 + float(k) * 7.0 + b.y);
        stars += tw * (exp(-r * r * 26.0) * 1.8 + exp(-r * 4.0) * 0.22
                 + (exp(-abs(d.x) * 30.0 - abs(d.y) * 4.0) + exp(-abs(d.y) * 30.0 - abs(d.x) * 4.0)) * 0.12);
    }
    for (int k = 0; k < 9; k++) {
        int kx = k - (k / 3) * 3, ky = k / 3;
        if (kx < 2 && on[k] * on[k + 1] > 0.0) lines += exp(-sdSegment(gq, sp[k], sp[k + 1]) * 22.0);
        if (ky < 2 && on[k] * on[k + 3] > 0.0) lines += exp(-sdSegment(gq, sp[k], sp[k + 3]) * 22.0);
    }
    return stars + lines * 0.45;
}

// The letters in the chosen style; `glow` is the soft halo round them.
float letters(int i, vec2 uv, float lod, out float glow) {
    glow = ink(i, uv, lod + 3.5) * (1.0 - gLeave);
    vec2 q = vec2(uv.x * uWord[i].w, uv.y);
    if (ST > 2.5) {
        glow *= 0.3 / (gExpand * gExpand);   // points of light carry their own sparkle; a soft haze only
        return points(i, q, lod);
    }
    float a;
    if (ST < 0.5) {
        a = ink(i, uv, lod);
    } else {
        // Neon tube / outline: a line along the letter edges, where the blurred ink crosses half.
        float soft = ink(i, uv, lod + (ST < 1.5 ? 1.8 : 1.0));
        float band = 1.0 - abs(soft - 0.5) * 2.0;
        a = smoothstep(ST < 1.5 ? 0.25 : 0.55, 0.92, band);
    }
    float g = grains(i, q);
    glow *= mix(1.0, g, 0.6);
    return a * g;
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
vec3 lit(int i, vec2 uv, float fade, float sz, float grey, inout float cover) {
    if (fade <= 0.001) return vec3(0.0);
    float glow;
    float a = letters(i, uv, lodFor(sz), glow);
    float sung;
    float on = singing(i, sung);
    bool neon = BH > 1.5 && BH < 2.5;
    // Karaoke inside the art: letters already sung are lit, the rest wait dimmer (a neon sign
    // waits as a dark tube, then stutters on).
    float done = on > 0.5 ? step(uv.x, sung * 1.04) : step(uWord[i].z, 0.0);
    float lift = mix(neon ? 0.3 : 0.55, 1.0, done) * (1.0 + on * (0.35 + KICK * PUMP)) * (neon ? flick(i) : 1.0);
    vec3 c = wordColour(i);
    vec3 core = (ST > 0.5 && ST < 1.5) || ST > 2.5 ? mix(c, vec3(1.0), 0.45) : c;
    vec3 L = (core * a * lift * 1.6 + c * glow * GLOWK * (0.6 + on)) * fade;
    cover = max(cover, min(a, 1.0) * fade);
    return mix(L, vec3(luma(L)) * 0.9, grey);
}

// ---------------------------------------------------------------- behaviour
// How word i lives over time. Moves uv (in the word's own box), tells the letters what to do,
// and returns how visible the word is. `alone`: the word stands on its own (centre, scattered),
// so the behaviour also fades it in and out; on a path the path decides what is in view.
float behave(int i, inout vec2 uv, bool alone, out float grey, out float blur) {
    vec4 w = uWord[i];
    float since = -w.y, gone = -w.z;
    float W = w.w;
    vec2 q = vec2((uv.x - 0.5) * W, uv.y - 0.5);   // text heights from the word's centre
    grey = 0.0;
    blur = 0.0;
    gReveal = 1.0;
    gLeave = 0.0;
    gScatter = 0.0;
    gMotion = 0.0;
    gTwinkle = 0.0;
    gExpand = 1.0;
    float fade = 1.0;
    float bh = BH;
    if (bh < 0.5) {                                 // karaoke
        if (alone) fade = smoothstep(-0.6, -0.2, since) * (1.0 - smoothstep(0.3, 0.9, gone));
    } else if (bh < 1.5) {                          // pop: in on its beat, pumping, then away
        float s = (0.6 + 0.4 * smoothstep(-0.15, 0.05, since)) * (1.0 + KICK * PUMP * 0.3 + BASS * PUMP * 0.1)
                  * (1.0 - 0.35 * smoothstep(0.0, 0.25, gone));
        q = rot2(sin(TIME * 1.3 + uWordB[i].z * 6.0) * SPIN * 0.3) * (q / s);
        if (alone) fade = smoothstep(-0.15, 0.0, since) * (1.0 - smoothstep(0.0, 0.25, gone));
    } else if (bh < 2.5) {                          // neon flicker (the light itself is in lit())
        if (alone) fade = smoothstep(-1.2, -0.8, since) * (1.0 - smoothstep(0.6, 1.2, gone));
    } else if (bh < 3.5) {                          // smoke: condenses out of mist, then rises and comes apart
        float life = 2.6 / max(SMOKEK, 0.2);
        float form = smoothstep(-0.7, 0.0, since);
        float dis = clamp(gone / life, 0.0, 1.0);
        q.y -= dis * dis * 4.0 * SMOKEK;
        q += curl2(vec3(q * 0.45, TIME * 0.35 + uWordB[i].z * 10.0)) * (dis * 1.6 + (1.0 - form) * 0.9) * SMOKEK;
        float ero = fbm2(q * 1.4 + uWordB[i].z * 9.0, 3) * 0.5 + 0.5;
        fade = smoothstep(dis * 1.1 - 0.15, dis * 1.1 + 0.05, ero) * form * (1.0 - dis * 0.7);
        grey = dis * 0.75;
        blur = dis * 4.0;
    } else if (bh < 4.5) {                          // burst: flies out from its centre, hangs, sags, twinkles out
        // Not sung yet: standing alone it is not there (its rocket is climbing); on a path it
        // waits whole and dim along the way (lit() dims it), then bursts as it is sung. Hidden,
        // Ribbon + Burst showed one word at a time.
        if (since < 0.0) {
            if (alone) return 0.0;
            uv = vec2(q.x / W + 0.5, q.y + 0.5);
            return 1.0;
        }
        float k = 1.0 - exp(-since * 7.0);
        q.y += 0.11 * since * since / max(SIZE, 0.02);
        // Like a real shell it keeps opening as it falls (owner, 10-18): the sparks drift apart.
        gExpand = 1.0 + since * 0.3;
        q /= max(k, 0.04) * gExpand;
        gMotion = 1.0 - k;
        gTwinkle = step(1.0, since);
        fade = 1.0 - smoothstep(1.3, 2.8, since);
    } else {                                        // sparkle: lit grain by grain, scattered away
        gReveal = smoothstep(-1.1, -0.05, since);
        float lv = clamp((gone - 0.2) / 2.0, 0.0, 1.0);
        gLeave = lv;
        gScatter = lv * 1.5;
        q.y -= lv * 0.6;
        if (alone) fade = step(-1.2, since) * (1.0 - smoothstep(2.0, 2.4, gone));
    }
    uv = vec2(q.x / W + 0.5, q.y + 0.5);
    return fade;
}

// How long before its start and after its end a word can be showing (point paths skip the rest).
vec2 window() {
    float bh = BH;
    if (bh < 0.5) return vec2(0.6, 0.9);
    if (bh < 1.5) return vec2(0.15, 0.25);
    if (bh < 2.5) return vec2(1.2, 1.2);
    if (bh < 3.5) return vec2(0.7, 2.6 / max(SMOKEK, 0.2));
    if (bh < 4.5) return vec2(0.75, 2.8);
    return vec2(1.2, 2.4);
}

// How far past its box a word's light may reach (smoke curls out, a burst sags): paths look
// this far round each word.
float margin() {
    float bh = BH;
    if (bh > 2.5 && bh < 3.5) return 1.4;
    if (bh > 3.5 && bh < 4.5) return 0.6;
    if (bh > 0.5 && bh < 1.5) return 0.3;
    return 0.12;
}

// Could word i's light reach uv at all, given how far its behaviour moves it (smoke rises and
// curls, a burst opens and sags, sparkle drifts)? A cheap test before the costly drawing: every
// pixel used to work out the smoke for every word, and Cigar Smoke ran at 12-27 fps on the Note 9.
bool inReach(int i, vec2 uv) {
    float W = uWord[i].w;
    vec2 q = vec2((uv.x - 0.5) * W, uv.y - 0.5);   // text heights from the word's centre
    float bh = BH;
    float gx = 0.5 * W + 0.2, up = 0.7, down = 0.7;
    // How far it reaches NOW (its age), not the most it ever could: a word still forming, or not
    // yet sung, stays close to its box. Swirls (curl2) rarely push past 1.2x their amount.
    float since = -uWord[i].y, gone = -uWord[i].z;
    if (bh > 2.5 && bh < 3.5) {
        float dis = clamp(gone * max(SMOKEK, 0.2) / 2.6, 0.0, 1.0);
        float amp = (dis * 1.6 + (1.0 - smoothstep(-0.7, 0.0, since)) * 0.9) * SMOKEK * 1.2;
        gx += amp;
        up += dis * dis * 4.0 * SMOKEK + amp;
        down += amp;
    } else if (bh > 3.5 && bh < 4.5) {
        float sb = max(since, 0.0);
        float e = 1.0 + sb * 0.3;
        gx = 0.5 * W * e + 0.4;
        up = 0.5 * e + 0.4;
        down = 0.5 * e + 0.4 + 0.11 * sb * sb / max(SIZE, 0.02);
    } else if (bh > 0.5 && bh < 1.5) {
        gx = 0.75 * W + 0.3;
        up = down = 0.9;
    } else if (bh > 4.5) {
        float lv = clamp((gone - 0.2) / 2.0, 0.0, 1.0);
        float amp = lv * 1.5 * 1.2;
        gx += amp;
        up += lv * 0.6 + amp;
        down += amp;
    }
    return abs(q.x) < gx && q.y < up && -q.y < down;
}

// Draw word i at uv (its box) with its behaviour and letters.
// Words arrive at the far end of the window (uWordWin.x ahead) and leave at the near end
// (uWordWin.y behind): Fade (FADEK seconds) softens both, so a word does not pop in or out.
float edgeFade(int i) {
    if (FADEK <= 0.001) return 1.0;
    float ahead = uWord[i].y;   // start - now: how far off it still is
    float gone = -uWord[i].z;   // now - end: how long ago it ended
    return clamp((uWordWin.x - ahead) / FADEK, 0.0, 1.0) * clamp((uWordWin.y - gone) / FADEK, 0.0, 1.0);
}

vec3 drawWord(int i, vec2 uv, float sz, float fade, bool alone, inout float cover) {
    float grey, blur;
    float f = behave(i, uv, alone, grey, blur) * fade * edgeFade(i);
    if (f <= 0.002) return vec3(0.0);
    return lit(i, uv, f, sz / exp2(blur), grey, cover);
}

// ---------------------------------------------------------------- paths along a line
// f = position along the path, v = across it (0 = centre), both in text heights. Finds the word
// at f, then draws it once: the drawing stays out of the loop, so the GPU program stays small
// (drawing inside long loops ran at 18 fps on the Note 9). `wrap` > 0 repeats the path every
// `wrap` heights (a ring); `flip` turns each word round (a path read the other way).
vec3 along(float f, float v, float fade, float wrap, float sz, float flip, inout float cover) {
    float m = margin();
    if (abs(v) > 0.5 + m) return vec3(0.0);
    int n = int(uWordCount);
    int hit = -1;
    float best = m;
    float u = 0.0;
    for (int i = 0; i < 48; i++) {
        if (i >= n) break;
        float local = f - (uWordB[i].x + SHIFTK);
        if (wrap > 0.0) local = mod(local, wrap);
        float out_ = max(max(-local, local - uWord[i].w), 0.0);
        if (out_ > best || (hit >= 0 && out_ == best)) continue;
        best = out_;
        hit = i;
        u = local / uWord[i].w;
        if (out_ == 0.0) break;
    }
    if (hit < 0) return vec3(0.0);
    return drawWord(hit, vec2(flip > 0.5 ? 1.0 - u : u, v + 0.5), sz, fade, false, cover);
}

// The banner the words ride on: a faint ribbon with bright edges.
vec3 banner(float v, float f, float fade) {
    float body = smoothstep(0.72, 0.62, abs(v));
    float edge = exp(-abs(abs(v) - 0.68) * 40.0);
    vec3 c = palw(0.15 + PALSHIFT + f * 0.01 + TIME * HUESPIN);
    return c * (body * 0.06 + edge * 0.5 * (0.6 + BASS)) * BANNER * fade;
}

// ---- 0 weaving ribbon
// The words ride an invisible ribbon that weaves off toward the horizon like smoke, the one
// being sung passing close by, big and readable, rippling with depth as the ribbon banks.
//   Coming at you: the ribbon runs down your left; the coming words wait far off toward the
//     centre and fly at you, so the line reads on from the word being sung.
//   Going away: the ribbon runs down your right; the sung words sail off toward the centre, so
//     the line reads up to the word being sung.
// Either way every word reads left to right, never mirrored (owner, 10-18). Camera space: x
// right, y up, z ahead, the screen at z = 1; s = text heights along the ribbon away from you.
float ribbonSide() { return TRAVEL < 0.5 ? -1.0 : 1.0; }

vec3 ribbonAt(float s) {
    float H = SIZE * 0.9;
    float t = TIME * SPEEDK;
    float x = ribbonSide() * SIDEK * 0.85 + WEAVE * 0.11 * (sin(s * 0.21 + t * 0.6) + 0.6 * sin(s * 0.47 - t * 1.1));
    float y = UPK * 0.45 + WEAVE * 0.12 * (sin(s * 0.29 - t * 0.8) + 0.5 * cos(s * 0.63 + t * 0.5));
    return vec3(x, y, CLOSEST + s * H);
}

vec2 ribbonUp(float s) {
    float bank = ribbonSide() * radians(LEAN) + RIPPLE * 0.3 * sin(s * 0.33 + TIME * SPEEDK * 0.9);
    return vec2(-sin(bank), cos(bank));
}

vec3 haze(float v, float f, float fade) {
    float n = fbm2(vec2(f * 0.35 - TIME * 0.4, v * 1.5), 3) * 0.5 + 0.5;
    float body = smoothstep(0.85, 0.0, abs(v)) * n;
    return palw(0.2 + PALSHIFT + f * 0.012 + TIME * HUESPIN) * body * 0.14 * BANNER * fade;
}

vec3 weave(vec2 p, inout float cover) {
    p = rot2(-radians(ROLL)) * p;                  // Rotate: the whole ribbon turns on screen
    float H = SIZE * 0.9;
    bool going = TRAVEL > 0.5;
    float fMin = (0.2 - CLOSEST) / H, fMax = REACH;
    const int N = 64;
    float ds = (fMax - fMin) / float(N);
    float reachV = 0.5 + margin() + 0.35;
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
            if (h >= 0.0 && h < 1.0 && abs(v) < reachV) {
                float sAt = fa + h * ds;
                float z = mix(Pa.z, Pb.z, h);
                float fade = smoothstep(0.18, min(0.7, CLOSEST * 0.8), z) * smoothstep(fMax * H, fMax * H * 0.5, z - CLOSEST);
                if (nh == 3) {
                    hits[0] = hits[1]; flips[0] = flips[1];
                    hits[1] = hits[2]; flips[1] = flips[2];
                    nh = 2;
                }
                hits[nh] = vec4(going ? -sAt : sAt, v, fade, length(W));
                // Only if the ribbon swings right across: keep the letters the right way round.
                flips[nh] = (det < 0.0) != going ? 1.0 : 0.0;
                nh++;
            }
        }
        Pb = Pa;
    }
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

// ---- 1 spiral
vec3 spiral(vec2 p, inout float cover) {
    // Laid on a disc tipped back into the screen, turning slowly (TIME x SPIN: a music link on
    // Spin speeds the turn up smoothly, see "rate" in types.json; it once jumped whole turns).
    float ct = cos(TILT), st = sin(TILT);
    float zz = max(1.0 + p.y * st * 0.9, 0.15);
    vec2 q = vec2(p.x, p.y / max(ct, 0.25)) / zz;
    q = rot2(TIME * SPIN) * q;
    float r = max(length(q), 1e-4);
    float th = -atan(q.y, q.x);                    // clockwise, so text reads left to right
    // A log spiral: the text shrinks with the radius, so every turn holds the same number of
    // words and the song fills the coil to its centre (owner, 10-18: words moving round the
    // spiral, filling it out). The word being sung sits on the rim; the lines already sung wind
    // in behind it, the coming ones arrive from outside.
    float R0 = 0.8 * min(EXT.x, EXT.y);
    float c = SIZE / R0;                           // text height per unit of radius
    float g = min(c * (1.5 + SPREAD), 0.9);        // each turn in is this much smaller
    float k = -log(1.0 - g) / TAU;                 // radius growth per radian
    float turn = floor((log(r / R0) / k - th) / TAU + 0.5);
    float ph = th + TAU * turn;                    // radians along the coil from the rim word
    float rc = R0 * exp(k * ph);                   // the middle of the coil here
    float h = c * rc;                              // text height here
    float v = (r - rc) / h;
    float f = ph * sqrt(1.0 + k * k) / c;          // text heights along the coil from the rim word
    float fade = smoothstep(0.006, 0.02, h) * smoothstep(1.6 * max(EXT.x, EXT.y), 0.9 * max(EXT.x, EXT.y), r);
    return along(f, v, fade, 0.0, h / zz, 0.0, cover) + banner(v, f, fade);
}

// ---- 2 ring
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

// ---- 3 tunnel
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
    float fade = exp(-max(depth - d0, 0.0) * 0.35) * smoothstep(0.0, d0 * 0.6, depth);
    vec3 col = along(F, v, fade, 0.0, r * SIZE * 1.1, 1.0, cover);
    // Guide rails either side of each lane, flowing with the words.
    float rail = exp(-abs(abs(v) - 0.75) * 30.0) * (0.5 + 0.5 * sin(F * 3.0 - TIME * 4.0));
    col += palw(0.3 + PALSHIFT + TIME * HUESPIN) * rail * BANNER * 0.4 * fade;
    return col;
}

// ---------------------------------------------------------------- paths where words stand alone

// ---- 4 street: driving down a night street, each word a sign you pass (on the walls or
// sticking out from them); the drive keeps pace with the words.
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
    float m = margin();
    for (int i = 0; i < 48; i++) {
        if (i >= n) break;
        vec4 w = uWord[i];
        vec4 b = uWordB[i];
        float sgn = mod(b.y, 2.0) < 0.5 ? -1.0 : 1.0;   // which side of the street
        float len = w.w * Hs;
        float z0 = 2.2 + (b.x + SHIFTK) * SP;            // the sign's near end, down the street
        if (z0 + len < 0.25) continue;
        float y0 = 0.5 + hash11(b.z * 17.0) * 0.9;       // its bottom edge, above the road
        vec3 c = wordColour(i);
        float unlit = w.y > 0.0 ? 0.3 : 1.0;             // coming signs wait dark
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
        // A thin neon frame round the sign.
        vec2 box = abs(uv - 0.5) - vec2(0.5 + 0.12 / w.w, 0.72);
        float frame = exp(-abs(max(box.x * w.w, box.y)) * 26.0) * step(max(box.x * w.w, box.y), 0.06);
        col += c * frame * 0.5 * unlit * (BH > 1.5 && BH < 2.5 ? flick(i) : 1.0) * fd;
        float mw = m / w.w;
        if (ns < 2 && uv.x > -0.05 - mw && uv.x < 1.05 + mw && uv.y > -0.3 - m && uv.y < 1.3 + m) {
            signI[ns] = i;
            signA[ns] = vec4(uv, fd, Hs / t);
            ns++;
        }
    }
    for (int k = 0; k < 2; k++) {
        if (k >= ns) break;
        col += drawWord(signI[k], signA[k].xy, signA[k].w, signA[k].z, false, cover);
    }
    return col;
}

// ---- 5 flight: each word far off before its time, near as it is sung, then rushing past.
vec3 flight(vec2 p, inout float cover) {
    vec3 col = vec3(0.0);
    int n = int(uWordCount);
    for (int i = 0; i < 48; i++) {
        if (i >= n) break;
        vec4 w = uWord[i];
        vec4 b = uWordB[i];
        float until = w.y, after = -w.z, dur = max(b.w, 0.05);
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
        if (!inReach(i, uv)) continue;
        float fade = clamp(1.0 - (z - 1.0) / (2.6 * SPEEDK * 2.5 + 0.5), 0.0, 1.0) * smoothstep(0.06, 0.35, z);
        col += drawWord(i, uv, s / z, fade, false, cover);
    }
    return col;
}

// A rocket climbing to where a bursting word will open (burst behaviour, standing alone).
vec3 rocket(vec2 p, int i, vec2 burst) {
    float until = uWord[i].y;
    if (until <= 0.0 || until > 0.7) return vec3(0.0);
    vec2 launch = vec2(burst.x + (hash11(uWordB[i].z * 5.0) - 0.5) * 0.4 + ACROSS * 0.3, -EXT.y * 1.05);
    float k = 1.0 - until / 0.7;
    k = 1.0 - (1.0 - k) * (1.0 - k);
    vec2 r = mix(launch, burst, k);
    vec2 tail = mix(launch, burst, max(k - 0.3, 0.0));
    float tr = exp(-sdSegment(p, tail, r) * 220.0) * smoothstep(0.0, 0.3, dot(p - tail, r - tail) / max(dot(r - tail, r - tail), 1e-5));
    return mix(vec3(1.0, 0.85, 0.6), wordColour(i), 0.3) * (exp(-length(p - r) * 120.0) * 2.5 + tr * 0.6 * (0.6 + 0.4 * hash12(floor(p * 300.0) + TIME)));
}

// ---- 6 centre / 7 scatter: each word on its own, in the middle or somewhere across the sky.
vec3 standalone(vec2 p, bool centre, inout float cover) {
    vec3 col = vec3(0.0);
    vec2 win = window();
    bool burst = BH > 3.5 && BH < 4.5;
    int n = int(uWordCount);
    for (int i = 0; i < 48; i++) {
        if (i >= n) break;
        vec4 w = uWord[i];
        vec4 b = uWordB[i];
        if (w.y > win.x || -w.z > win.y) continue;
        float s = SIZE;
        vec2 home = vec2(0.0);
        if (centre) {
            s = min(SIZE, SPREAD * min(EXT.x, EXT.y) * 1.6 / w.w);
        } else {
            // A bursting word opens to about twice its size: keep that on screen too.
            vec2 half_ = vec2(s * w.w * 0.5, s * 0.5) * (burst ? 1.8 : 1.0);
            home = (hash21(b.z * 37.0 + 1.0) - 0.5) * 2.0 * max(EXT * vec2(0.85, 0.7) * SPREAD - half_, 0.0)
                   + vec2(0.0, UPK * 0.3);
            if (burst) home.y = EXT.y * (0.05 + 0.4 * hash11(b.z * 7.0)) + UPK * 0.3;   // shells open up in the sky
        }
        if (burst && w.y > 0.0) {
            col += rocket(p, i, home);
            continue;
        }
        vec2 q = (p - home) / s;
        vec2 uv = vec2(q.x / w.w + 0.5, q.y + 0.5);
        if (!inReach(i, uv)) continue;
        col += drawWord(i, uv, s, 1.0, true, cover);
        if (burst) col += mix(vec3(1.0, 0.95, 0.8), wordColour(i), 0.3) * exp(-length(p - home) * 26.0) * exp(w.y * 10.0) * 0.45;
    }
    return col;
}

// ---- 8 rising: each word curls up off a glowing ember as it is sung.
vec3 rising(vec2 p, inout float cover) {
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
    for (int i = 0; i < 48; i++) {
        if (i >= n) break;
        vec4 w = uWord[i];
        float hz = uWordB[i].z;
        float age = -w.y;                              // seconds since the word started
        if (age < -0.25 || age > 6.0) continue;
        float rise = max(age, 0.0);
        vec2 c = src + vec2(0.0, 0.16 + rise * 0.2 * SPEEDK + max(-w.z, 0.0) * max(-w.z, 0.0) * 0.03);
        // Wander: two slow sines per word (a noise here cost every pixel 16 noise samples).
        c.x += (sin(rise * 1.6 + hz * 6.3) * 0.06 + (sin(rise * 0.43 + hz * 9.0) * 0.6 + sin(rise * 0.71 + hz * 23.0) * 0.4) * 0.1) * rise * WEAVE;
        float s = SIZE * (0.8 + rise * 0.22) * smoothstep(-0.25, 0.2, age);
        if (s < 1e-3) continue;
        vec2 q = rot2(sin(rise * 0.9 + hz * 4.0) * 0.12 * WEAVE) * (p - c);
        if (!inReach(i, vec2(q.x / (s * w.w) + 0.5, q.y / s + 0.5))) continue;
        // A small wobble (1.5% of the word): sines, not the 4-noise swirl it used to pay for.
        vec2 qs = q / s;
        q += vec2(sin(qs.y * 2.7 + TIME * 1.1 + hz * 7.0), sin(qs.x * 2.1 - TIME * 0.9 + hz * 3.0)) * s * 0.02 * WEAVE;
        vec2 uv = vec2(q.x / (s * w.w) + 0.5, q.y / s + 0.5);
        col += drawWord(i, uv, s, 1.0 - smoothstep(4.0, 6.0, rise), false, cover);
    }
    return col;
}

void main() {
    vec2 p = vPos;
    float cover = 0.0;
    vec3 col;
#if WPATH == 0
    col = weave(p, cover);
#elif WPATH == 1
    col = spiral(p, cover);
#elif WPATH == 2
    col = ring(p, cover);
#elif WPATH == 3
    col = ticker(p, cover);
#elif WPATH == 4
    col = street(p, cover);
#elif WPATH == 5
    col = flight(p, cover);
#elif WPATH == 6
    col = standalone(p, true, cover);
#elif WPATH == 7
    col = standalone(p, false, cover);
#elif WPATH == 8
    col = rising(p, cover);
#else
    float m = PATHK;
    if (m < 0.5) col = weave(p, cover);
    else if (m < 1.5) col = spiral(p, cover);
    else if (m < 2.5) col = ring(p, cover);
    else if (m < 3.5) col = ticker(p, cover);
    else if (m < 4.5) col = street(p, cover);
    else if (m < 5.5) col = flight(p, cover);
    else if (m < 6.5) col = standalone(p, true, cover);
    else if (m < 7.5) col = standalone(p, false, cover);
    else col = rising(p, cover);
#endif
    o = lightOut(col * BRIGHTN, cover);
}
