// TextLab shader library: shared header.
//
// THE ASSEMBLY CONTRACT (identical in the web look book and in the app, do not diverge):
//   "#version 300 es\n" + PRECISION + <#define lines> + common.glsl + "\n#line 1\n" + <body>
// Every vertex and fragment shader gets this file. Nothing else is injected, so a shader
// that compiles in the browser compiles on the phone.
//
// Space: "em". One em is the type size. The FIELD is the rectangle the words live in, with
// room around them for glow, fire and depth; x runs right, y runs UP, (0,0) is its bottom left.
// Colour: LINEAR light, premultiplied alpha, values above 1 are real (they bloom).
//
// The field texture (one texel per field pixel, half float) is the whole shape language:
//   r  distance to the letter's edge in em, POSITIVE INSIDE. Any shape can be a "letter":
//      type, a pen drawing, a sprite's outline.
//   g  reveal 0..1: how far this letter has arrived (the look decides what arriving means)
//   b  which letter, (index + 0.5) / count, so it is an order and a random seed in one
//   a  height inside its own line: 0 on the baseline, 1 at cap height

const float PI  = 3.14159265359;
const float TAU = 6.28318530718;

uniform float uTime;   // seconds on the look's own clock
uniform vec4  uField;  // field width em, field height em, pixels per em, spread em (the field stores no distance beyond it)
uniform vec2  uTilt;   // the phone's tilt or the pointer, -1 .. 1

float sat(float x) { return clamp(x, 0.0, 1.0); }
float sq(float x) { return x * x; }
float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
float max3(vec3 c) { return max(c.r, max(c.g, c.b)); }
mat2 rot(float a) { float c = cos(a), s = sin(a); return mat2(c, s, -s, c); }

// ---- Hashes without sin (they must match on every GPU) ----
float hash11(float p) { p = fract(p * 0.1031); p *= p + 33.33; p *= p + p; return fract(p); }
float hash21(vec2 p) { vec3 p3 = fract(vec3(p.xyx) * 0.1031); p3 += dot(p3, p3.yzx + 33.33); return fract((p3.x + p3.y) * p3.z); }
vec2 hash22(vec2 p) { vec3 p3 = fract(vec3(p.xyx) * vec3(0.1031, 0.1030, 0.0973)); p3 += dot(p3, p3.yzx + 33.33); return fract((p3.xx + p3.yz) * p3.zy); }
vec4 hash41(float p) { vec4 p4 = fract(vec4(p) * vec4(0.1031, 0.1030, 0.0973, 0.1099)); p4 += dot(p4, p4.wzxy + 33.33); return fract((p4.xxyz + p4.yzzw) * p4.zywx); }

// ---- Noise, 0..1 ----
float vnoise(vec2 p) {
    vec2 i = floor(p), f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash21(i), hash21(i + vec2(1.0, 0.0)), u.x),
               mix(hash21(i + vec2(0.0, 1.0)), hash21(i + vec2(1.0, 1.0)), u.x), u.y);
}
float fbm(vec2 p) {
    float a = 0.5, s = 0.0;
    for (int i = 0; i < 4; i++) { s += a * vnoise(p); p = p * 2.03 + vec2(17.1, 9.2); a *= 0.5; }
    return s / 0.9375;
}
float fbm2(vec2 p) { return (vnoise(p) * 2.0 + vnoise(p * 2.03 + vec2(17.1, 9.2))) / 3.0; }

// A swirl with no sources or sinks: what smoke and flame drift on.
vec2 curl(vec2 p) {
    const float e = 0.07;
    float a = fbm2(p + vec2(0.0, e)), b = fbm2(p - vec2(0.0, e));
    float c = fbm2(p + vec2(e, 0.0)), d = fbm2(p - vec2(e, 0.0));
    return vec2(a - b, d - c) / (2.0 * e);
}

// ---- The same noise read from a texture: one tap where vnoise does four hashes. ----
// uNoise is 256 x 256 random bytes, repeating (the host makes it from a fixed recipe, so it is
// the same picture on every device). Use these for anything that moves and need not be exact:
// fire, smoke, drift. Keep vnoise for surface bumps: a texture's 8-bit blend shows as steps
// in a mirror.
uniform sampler2D uNoise;
float tnoise(vec2 p) {
    vec2 i = floor(p), f = fract(p);
    return textureLod(uNoise, (i + f * f * (3.0 - 2.0 * f) + 0.5) / 256.0, 0.0).r;
}
float tfbm(vec2 p) {
    float a = 0.5, s = 0.0;
    for (int i = 0; i < 4; i++) { s += a * tnoise(p); p = p * 2.03 + vec2(17.1, 9.2); a *= 0.5; }
    return s / 0.9375;
}
float tfbm2(vec2 p) { return (tnoise(p) * 2.0 + tnoise(p * 2.03 + vec2(17.1, 9.2))) / 3.0; }
vec2 tcurl(vec2 p) {
    const float e = 0.07;
    float a = tfbm2(p + vec2(0.0, e)), b = tfbm2(p - vec2(0.0, e));
    float c = tfbm2(p + vec2(e, 0.0)), d = tfbm2(p - vec2(e, 0.0));
    return vec2(a - b, d - c) / (2.0 * e);
}

// Presence: a slow drifting mask for things that are only half there. 1 = this part is here,
// 0 = it has faded out of existence for now; it comes and goes.
uniform vec2 uPresence;   // how much of the thing fades (0 = always whole), how fast it comes and goes
float presence(vec2 p) {
    if (uPresence.x <= 0.0) return 1.0;
    float n = tfbm(p * 0.9 + vec2(uTime * uPresence.y * 0.11, -uTime * uPresence.y * 0.07));
    return mix(1.0, smoothstep(0.34, 0.6, n), uPresence.x);
}

// Cells: x = distance to the nearest seed, y = to the next, z = the cell's own random number.
vec3 voronoi(vec2 p) {
    vec2 i = floor(p), f = fract(p);
    float f1 = 8.0, f2 = 8.0, id = 0.0;
    for (int y = -1; y <= 1; y++) for (int x = -1; x <= 1; x++) {
        vec2 g = vec2(float(x), float(y));
        vec2 r = g + hash22(i + g) - f;
        float d = dot(r, r);
        if (d < f1) { f2 = f1; f1 = d; id = hash21(i + g); } else if (d < f2) { f2 = d; }
    }
    return vec3(sqrt(f1), sqrt(f2), id);
}

vec3 hsv2rgb(vec3 c) {
    vec3 p = abs(fract(c.xxx + vec3(1.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0);
    return c.z * mix(vec3(1.0), clamp(p - 1.0, 0.0, 1.0), c.y);
}

// Heat to light: a = the cool fringe, b = the body, c = the core. `toon` 0 = smooth, 1 = flat bands.
vec3 fireRamp(float t, vec3 a, vec3 b, vec3 c, float toon) {
    t = max(t, 0.0);
    float w = mix(1.0, 0.06, toon);
    float k1 = smoothstep(0.20 - 0.17 * w, 0.20 + 0.17 * w, t);
    float k2 = smoothstep(0.47 - 0.25 * w, 0.47 + 0.25 * w, t);
    float k0 = smoothstep(0.07 - 0.06 * w, 0.07 + 0.06 * w, t);
    vec3 col = mix(a * 0.75, b * 1.35, k1);
    col = mix(col, c * 2.1, k2);
    return col * k0;
}
