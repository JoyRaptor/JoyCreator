// VisLab shader library: shared header.
//
// THE ASSEMBLY CONTRACT (identical in the web pilot and in the app, do not diverge):
//   "#version 300 es\n" + PRECISION + common.glsl + "\n#line 1\n" + <body>
// Every vertex and fragment shader gets this file. Nothing else is injected, so a shader
// that compiles in the browser compiles on the phone.
//
// Space: "scene units". The SHORT side of the frame always runs -1 .. +1, so a ring of
// radius 0.8 fits a portrait phone, a landscape video and a square overlay alike. The long
// side runs -EXT .. +EXT (EXT.x or EXT.y is 1, the other is >= 1).
// Colour: LINEAR light, premultiplied alpha. Palette stops arrive already linearised.
// Blend: every layer draws with ONE, ONE_MINUS_SRC_ALPHA. A shader writes alpha 0 for pure
// light (adds) and alpha 1 for paint that hides what is behind; uAdd scales between them.

// ---- Sound, refreshed once per frame for every program (std140, vec4 only) ----
layout(std140) uniform Audio {
    vec4 aBands;   // bass, low, mid, high            (0..~1.2, smoothed)
    vec4 aBands2;  // air, level, voice, bright
    vec4 aHits;    // kick, snare, hat, beat          (1 on the hit, decaying)
    vec4 aTime;    // time s, dt s, beatPhase 0..1, barPhase 0..1
    vec4 aAcc;     // bassTravel, levelTravel, energy (slow), punch (kick strength, this frame only)
    vec4 aRes;     // frame w px, frame h px, half-extent x, half-extent y (scene units)
    vec4 aShock0;  // seconds since each of the last 8 kicks (large = none)
    vec4 aShock1;
};

#define BASS    aBands.x
#define LOW     aBands.y
#define MID     aBands.z
#define HIGH    aBands.w
#define AIR     aBands2.x
#define LEVEL   aBands2.y
#define VOICE   aBands2.z
#define BRIGHT  aBands2.w
#define KICK    aHits.x
#define SNARE   aHits.y
#define HAT     aHits.z
#define BEAT    aHits.w
#define TIME    aTime.x
#define DT      aTime.y
#define BEATPH  aTime.z
#define BARPH   aTime.w
#define BASSACC aAcc.x
#define LEVELACC aAcc.y
#define ENERGY  aAcc.z
#define PUNCH   aAcc.w
#define EXT     aRes.zw

const float PI  = 3.14159265359;
const float TAU = 6.28318530718;

// ---- Per-draw uniforms (the host sets whichever a program actually uses) ----
uniform vec4  uP[12];     // layer parameters, already modulated; see types.json for slots
uniform vec3  uPal[5];    // palette, 5 stops, linear
uniform float uPx;        // scene units per target pixel (antialias width)
uniform float uSeed;      // per-layer random seed
uniform float uAdd;       // 0 = paint (hides), 1 = light (adds)
uniform float uOpacity;   // layer opacity
uniform vec4  uView;      // where this layer sits: move x, y (scene units), log2 size, turn (radians); zeros = as drawn
uniform sampler2D uSpec;  // 128x1: r smooth, g peak-hold, b raw   (x = log frequency 30Hz..16kHz)
uniform sampler2D uWave;  // 512x1: r left/mono, g right            (-1..1, triggered, gain-levelled)
uniform sampler2D uHist;  // 128x64: r = spectrum, row 0 = now, row 63 = ~2 s ago

float spec(float f)     { return texture(uSpec, vec2(clamp(f, 0.0, 1.0), 0.5)).r; }
float specPeak(float f) { return texture(uSpec, vec2(clamp(f, 0.0, 1.0), 0.5)).g; }
float specRaw(float f)  { return texture(uSpec, vec2(clamp(f, 0.0, 1.0), 0.5)).b; }
float waveL(float t)    { return texture(uWave, vec2(clamp(t, 0.0, 1.0), 0.5)).r; }
float waveR(float t)    { return texture(uWave, vec2(clamp(t, 0.0, 1.0), 0.5)).g; }
float hist(float f, float age) { return texture(uHist, vec2(clamp(f, 0.0, 1.0), clamp(age, 0.0, 1.0))).r; }

// Ages of the last kicks, for shockwaves and anything that wants beat history.
float shockAge(int i) {
    if (i < 4) return i == 0 ? aShock0.x : i == 1 ? aShock0.y : i == 2 ? aShock0.z : aShock0.w;
    return i == 4 ? aShock1.x : i == 5 ? aShock1.y : i == 6 ? aShock1.z : aShock1.w;
}

// ---- Palette ----
vec3 pal(float t) {
    t = clamp(t, 0.0, 1.0) * 4.0;
    float i = floor(min(t, 3.999));
    float f = t - i;
    f = f * f * (3.0 - 2.0 * f);
    int k = int(i);
    return mix(uPal[k], uPal[k + 1], f);
}
// Wrapping palette: walks 0 -> 1 -> 0 so a cycling hue never jumps.
vec3 palw(float t) { t = fract(t); return pal(1.0 - abs(2.0 * t - 1.0)); }

float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
float max3(vec3 c) { return max(c.r, max(c.g, c.b)); }

vec3 hueRotate(vec3 c, float a) {
    // Rodrigues rotation about the grey axis: keeps luminance roughly, turns hue.
    const vec3 k = vec3(0.57735);
    float ca = cos(a);
    return c * ca + cross(k, c) * sin(a) + k * dot(k, c) * (1.0 - ca);
}

mat2 rot2(float a) { float c = cos(a), s = sin(a); return mat2(c, s, -s, c); }

// ---- Place: every layer can be moved, sized and turned (types.json "layer"). The layer is MADE
// in its own space, not drawn and then moved like a card: an aurora keeps its curtains to the
// edge of the screen, a particle system flies as one, a pattern runs on. Zeros = as drawn.
// Screen -> layer (a full-screen layer asks "what is here?").
vec2 placeIn(vec2 q) { return rot2(-uView.w) * (q - uView.xy) * exp2(-uView.z); }
// Layer -> screen (points, lines and particles say "draw me here").
vec2 placeOut(vec2 p) { return rot2(uView.w) * p * exp2(uView.z) + uView.xy; }

// ---- Hashes (no sin, stable on mobile) ----
float hash11(float p) { p = fract(p * 0.1031); p *= p + 33.33; p *= p + p; return fract(p); }
float hash12(vec2 p) { vec3 p3 = fract(vec3(p.xyx) * 0.1031); p3 += dot(p3, p3.yzx + 33.33); return fract((p3.x + p3.y) * p3.z); }
vec2 hash21(float p) { vec3 p3 = fract(vec3(p) * vec3(0.1031, 0.1030, 0.0973)); p3 += dot(p3, p3.yzx + 33.33); return fract((p3.xx + p3.yz) * p3.zy); }
vec2 hash22(vec2 p) { vec3 p3 = fract(vec3(p.xyx) * vec3(0.1031, 0.1030, 0.0973)); p3 += dot(p3, p3.yzx + 33.33); return fract((p3.xx + p3.yz) * p3.zy); }
vec3 hash31(float p) { vec3 p3 = fract(vec3(p) * vec3(0.1031, 0.1030, 0.0973)); p3 += dot(p3, p3.yzx + 33.33); return fract((p3.xxy + p3.yzz) * p3.zyx); }
vec4 hash41(float p) { vec4 p4 = fract(vec4(p) * vec4(0.1031, 0.1030, 0.0973, 0.1099)); p4 += dot(p4, p4.wzxy + 33.33); return fract((p4.xxyz + p4.yzzw) * p4.zywx); }

// ---- Simplex noise (Gustavson / Ashima, MIT) ----
vec3 _m289(vec3 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
vec4 _m289(vec4 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
vec2 _m289(vec2 x) { return x - floor(x * (1.0 / 289.0)) * 289.0; }
vec3 _perm(vec3 x) { return _m289(((x * 34.0) + 1.0) * x); }
vec4 _perm(vec4 x) { return _m289(((x * 34.0) + 1.0) * x); }
vec4 _tis(vec4 r) { return 1.79284291400159 - 0.85373472095314 * r; }

float snoise(vec2 v) {
    const vec4 C = vec4(0.211324865405187, 0.366025403784439, -0.577350269189626, 0.024390243902439);
    vec2 i = floor(v + dot(v, C.yy));
    vec2 x0 = v - i + dot(i, C.xx);
    vec2 i1 = (x0.x > x0.y) ? vec2(1.0, 0.0) : vec2(0.0, 1.0);
    vec4 x12 = x0.xyxy + C.xxzz;
    x12.xy -= i1;
    i = _m289(i);
    vec3 p = _perm(_perm(i.y + vec3(0.0, i1.y, 1.0)) + i.x + vec3(0.0, i1.x, 1.0));
    vec3 m = max(0.5 - vec3(dot(x0, x0), dot(x12.xy, x12.xy), dot(x12.zw, x12.zw)), 0.0);
    m = m * m; m = m * m;
    vec3 x = 2.0 * fract(p * C.www) - 1.0;
    vec3 h = abs(x) - 0.5;
    vec3 ox = floor(x + 0.5);
    vec3 a0 = x - ox;
    m *= 1.79284291400159 - 0.85373472095314 * (a0 * a0 + h * h);
    vec3 g;
    g.x = a0.x * x0.x + h.x * x0.y;
    g.yz = a0.yz * x12.xz + h.yz * x12.yw;
    return 130.0 * dot(m, g);
}

float snoise(vec3 v) {
    const vec2 C = vec2(1.0 / 6.0, 1.0 / 3.0);
    const vec4 D = vec4(0.0, 0.5, 1.0, 2.0);
    vec3 i = floor(v + dot(v, C.yyy));
    vec3 x0 = v - i + dot(i, C.xxx);
    vec3 g = step(x0.yzx, x0.xyz);
    vec3 l = 1.0 - g;
    vec3 i1 = min(g.xyz, l.zxy);
    vec3 i2 = max(g.xyz, l.zxy);
    vec3 x1 = x0 - i1 + C.xxx;
    vec3 x2 = x0 - i2 + C.yyy;
    vec3 x3 = x0 - D.yyy;
    i = _m289(i);
    vec4 p = _perm(_perm(_perm(i.z + vec4(0.0, i1.z, i2.z, 1.0)) + i.y + vec4(0.0, i1.y, i2.y, 1.0)) + i.x + vec4(0.0, i1.x, i2.x, 1.0));
    float n_ = 0.142857142857;
    vec3 ns = n_ * D.wyz - D.xzx;
    vec4 j = p - 49.0 * floor(p * ns.z * ns.z);
    vec4 x_ = floor(j * ns.z);
    vec4 y_ = floor(j - 7.0 * x_);
    vec4 x = x_ * ns.x + ns.yyyy;
    vec4 y = y_ * ns.x + ns.yyyy;
    vec4 h = 1.0 - abs(x) - abs(y);
    vec4 b0 = vec4(x.xy, y.xy);
    vec4 b1 = vec4(x.zw, y.zw);
    vec4 s0 = floor(b0) * 2.0 + 1.0;
    vec4 s1 = floor(b1) * 2.0 + 1.0;
    vec4 sh = -step(h, vec4(0.0));
    vec4 a0 = b0.xzyw + s0.xzyw * sh.xxyy;
    vec4 a1 = b1.xzyw + s1.xzyw * sh.zzww;
    vec3 p0 = vec3(a0.xy, h.x);
    vec3 p1 = vec3(a0.zw, h.y);
    vec3 p2 = vec3(a1.xy, h.z);
    vec3 p3 = vec3(a1.zw, h.w);
    vec4 norm = _tis(vec4(dot(p0, p0), dot(p1, p1), dot(p2, p2), dot(p3, p3)));
    p0 *= norm.x; p1 *= norm.y; p2 *= norm.z; p3 *= norm.w;
    vec4 m = max(0.6 - vec4(dot(x0, x0), dot(x1, x1), dot(x2, x2), dot(x3, x3)), 0.0);
    m = m * m;
    return 42.0 * dot(m * m, vec4(dot(p0, x0), dot(p1, x1), dot(p2, x2), dot(p3, x3)));
}

float fbm2(vec2 p, int oct) {
    float a = 0.5, s = 0.0;
    for (int i = 0; i < 6; i++) {
        if (i >= oct) break;
        s += a * snoise(p);
        p = rot2(0.6) * p * 2.03 + 11.7;
        a *= 0.5;
    }
    return s;
}

float fbm3(vec3 p, int oct) {
    float a = 0.5, s = 0.0;
    for (int i = 0; i < 6; i++) {
        if (i >= oct) break;
        s += a * snoise(p);
        p = p * 2.02 + vec3(11.7, 3.1, 7.9);
        a *= 0.5;
    }
    return s;
}

// Divergence-free flow: the curl of a noise potential. Particles in it swirl like smoke
// and never bunch up into blobs.
vec2 curl2(vec3 p) {
    const float e = 0.02;
    float n1 = snoise(p + vec3(0.0, e, 0.0));
    float n2 = snoise(p - vec3(0.0, e, 0.0));
    float n3 = snoise(p + vec3(e, 0.0, 0.0));
    float n4 = snoise(p - vec3(e, 0.0, 0.0));
    return vec2(n1 - n2, n4 - n3) / (2.0 * e);
}

// ---- Shapes ----
float sdBox(vec2 p, vec2 b) { vec2 d = abs(p) - b; return length(max(d, 0.0)) + min(max(d.x, d.y), 0.0); }
float sdRoundBox(vec2 p, vec2 b, float r) { r = min(r, min(b.x, b.y)); vec2 q = abs(p) - b + r; return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r; }
float sdSegment(vec2 p, vec2 a, vec2 b) { vec2 pa = p - a, ba = b - a; float h = clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0); return length(pa - ba * h); }

// Signed distance to a regular polygon or star (after Inigo Quilez). n = points (>= 3),
// sharp 0 = plain polygon .. 1 = spikiest star. Point 0 faces up.
float sdPolyStar(vec2 p, float r, float n, float sharp) {
    n = max(3.0, n);
    float m = mix(n, 2.0, clamp(sharp, 0.0, 1.0));
    float an = PI / n;
    float en = PI / m;
    vec2 acs = vec2(cos(an), sin(an));
    vec2 ecs = vec2(cos(en), sin(en));
    p.x = abs(p.x);
    float bn = mod(atan(p.x, p.y), 2.0 * an) - an;
    p = length(p) * vec2(cos(bn), abs(sin(bn)));
    p -= r * acs;
    p += ecs * clamp(-dot(p, ecs), 0.0, r * acs.y / ecs.y);
    return length(p) * sign(p.x);
}

// Light from a distance field: a crisp core plus a soft halo.
// core: 1 inside, antialiased edge. glow: exp falloff with the given width (scene units).
float coreOf(float d) { return clamp(0.5 - d / max(uPx, 1e-5), 0.0, 1.0); }
float glowOf(float d, float width) { return width > 0.0 ? exp(-max(d, 0.0) / width) : 0.0; }

vec4 lightOut(vec3 rgb, float cover) {
    // rgb is light energy (premultiplied); cover is how much this pixel hides what is behind.
    return vec4(rgb, cover * (1.0 - uAdd)) * uOpacity;
}
