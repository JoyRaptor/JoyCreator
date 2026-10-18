// SPECTRUM: the frequency picture, drawn as true distance fields so every bar has a crisp
// core and a real glow at any resolution. Straight or round, bars / LED blocks / line /
// filled mountain / orbs, mirrored, with peak caps and a floor reflection.
in vec2 vUv;
in vec2 vPos;
out vec4 o;

#define MODE     uP[0].x   // 0 bars, 1 blocks, 2 line, 3 mountain, 4 orbs
#define COUNT    uP[0].y
#define RADIAL   uP[0].z
#define MIRROR   uP[0].w   // 0 low->high, 1 low at the ends, 2 low in the middle
#define CEN      uP[1].xy
#define SIZE     uP[1].z   // straight: share of frame width; round: radius
#define HEIGHT   uP[1].w
#define FILL     uP[2].x
#define ROUND    uP[2].y
#define GLOW     uP[2].z
#define CORE     uP[2].w
#define ROT      uP[3].x
#define ARC      uP[3].y
#define CMODE    uP[3].z   // 0 along the spectrum, 1 up the bar, 2 both
#define PALSHIFT uP[3].w
#define PEAKS    uP[4].x
#define GAIN     uP[4].y
#define FLO      uP[4].z
#define FHI      uP[4].w
#define THICK    uP[5].x
#define DUAL     uP[5].y   // straight: 0 up, 1 up and down; round: 0 out, 1 both, 2 in
#define BRIGHTN  uP[5].z
#define EXTRA    uP[5].w   // straight: reflection; round: base ring
#define HUESPIN  uP[6].x
#define SPIN     uP[6].y   // turns per second (round)

float freqOf(float u) {
    float f = u;
    if (MIRROR > 1.5) f = abs(u * 2.0 - 1.0);
    else if (MIRROR > 0.5) f = 1.0 - abs(u * 2.0 - 1.0);
    return mix(FLO, FHI, f);
}

vec3 colorAt(float u, float h) {
    float along = MIRROR > 0.5 ? abs(u * 2.0 - 1.0) : u;
    float t = CMODE < 0.5 ? along : (CMODE < 1.5 ? h : 0.5 * (along + h));
    return palw(t * 0.5 + PALSHIFT + TIME * HUESPIN);
}

// Distance from lp (bar-local, x across, y along from the base) to one bar of length L.
float barDist(vec2 lp, float L, float hw) {
    if (MODE > 3.5) return length(lp - vec2(0.0, L)) - hw;                     // orb on the tip
    float d;
    if (DUAL > 0.5 && DUAL < 1.5) d = sdRoundBox(lp, vec2(hw, L), ROUND * hw);
    else if (DUAL > 1.5) d = sdRoundBox(lp + vec2(0.0, L * 0.5), vec2(hw, L * 0.5), ROUND * hw);
    else d = sdRoundBox(lp - vec2(0.0, L * 0.5), vec2(hw, L * 0.5), ROUND * hw);
    if (MODE > 0.5 && MODE < 1.5) {                                              // LED blocks
        float pitch = max(hw * 2.0, uPx * 3.0);
        float yy = lp.y - pitch * 0.5;
        float ds = abs(fract(yy / pitch) - 0.5) * pitch - pitch * 0.5 * FILL;
        d = max(d, ds);
    }
    return d;
}

void addBar(inout vec3 acc, inout float cover, vec2 lp, float u, float hw, float gw) {
    vec3 s = texture(uSpec, vec2(freqOf(u), 0.5)).rgb;
    float L = max(s.r * GAIN * HEIGHT, uPx * 1.5);
    float d = barDist(lp, L, hw);
    float h = clamp(lp.y / max(HEIGHT, 1e-3), 0.0, 1.0);
    vec3 c = colorAt(u, h);
    float core = coreOf(d);
    acc += c * (core * CORE + glowOf(d, gw) * GLOW);
    cover = max(cover, core);
    if (PEAKS > 0.0) {
        float pk = s.g * GAIN * HEIGHT + max(THICK, uPx * 2.0) * 1.5;
        float pd = sdBox(lp - vec2(0.0, pk), vec2(hw, max(THICK, uPx * 1.5) * 0.5));
        acc += mix(c, vec3(1.0), 0.5) * (coreOf(pd) + glowOf(pd, gw) * GLOW * 0.6) * PEAKS;
    }
}

vec4 straightBars(vec2 q) {
    float W = SIZE * 2.0 * EXT.x;
    float n = max(1.0, floor(COUNT));
    float u = q.x / W + 0.5;
    float cellW = W / n;
    float hw = 0.5 * FILL * cellW;
    float gw = GLOW * 0.08 + 1e-4;
    float y = q.y;
    float refl = 1.0;
    if (DUAL < 0.5 && y < 0.0) {
        if (EXTRA <= 0.0) return vec4(0.0);
        refl = EXTRA * exp(y * 7.0);
        y = -y;
    }
    vec3 acc = vec3(0.0);
    float cover = 0.0;
    float i0 = floor(u * n);
    for (int k = -1; k <= 1; k++) {
        float i = i0 + float(k);
        if (i < 0.0 || i >= n) continue;
        float uc = (i + 0.5) / n;
        addBar(acc, cover, vec2(q.x - (uc - 0.5) * W, y), uc, hw, gw);
    }
    return vec4(acc * refl, cover * refl);
}

vec4 roundBars(vec2 q) {
    float R = SIZE;
    float arc = clamp(ARC, 0.02, 1.0);
    float n = max(1.0, floor(COUNT));
    float ang = atan(q.x, q.y);                     // 0 at the top, clockwise
    float u = fract(ang / TAU + arc * 0.5) / arc;   // the arc is centred on the top
    float full = step(0.999, arc);
    float cellW = TAU * R * arc / n;
    float hw = 0.5 * FILL * cellW;
    float gw = GLOW * 0.08 + 1e-4;
    vec3 acc = vec3(0.0);
    float cover = 0.0;
    float i0 = floor(u * n);
    for (int k = -1; k <= 1; k++) {
        float i = i0 + float(k);
        if (full > 0.5) i = mod(i + n, n);
        else if (i < 0.0 || i >= n) continue;
        float uc = (i + 0.5) / n;
        float a = (uc * arc - arc * 0.5) * TAU;
        vec2 dir = vec2(sin(a), cos(a));
        vec2 perp = vec2(cos(a), -sin(a));
        vec2 lp = vec2(dot(q, perp), dot(q, dir) - R);
        addBar(acc, cover, lp, uc, hw, gw);
    }
    if (EXTRA > 0.0) {
        float d = abs(length(q) - R) - max(THICK, uPx) * 0.5;
        vec3 c = colorAt(u, 0.0);
        acc += c * (coreOf(d) + glowOf(d, gw) * GLOW) * EXTRA;
    }
    return vec4(acc, cover);
}

// Smooth curve modes: line and filled mountain.
float curveVal(float u) {
    return texture(uSpec, vec2(freqOf(clamp(u, 0.0, 1.0)), 0.5)).r * GAIN * HEIGHT;
}

vec4 straightCurve(vec2 q) {
    float W = SIZE * 2.0 * EXT.x;
    float u = q.x / W + 0.5;
    if (u < 0.0 || u > 1.0) return vec4(0.0);
    float y = q.y;
    float refl = 1.0;
    if (DUAL < 0.5 && y < 0.0) {
        if (EXTRA <= 0.0) return vec4(0.0);
        refl = EXTRA * exp(y * 7.0);
        y = -y;
    }
    if (DUAL > 0.5) y = abs(y);
    float L = curveVal(u);
    float du = 1.0 / 512.0;
    float slope = (curveVal(u + du) - curveVal(u - du)) / (2.0 * du * W);
    float d = abs(y - L) / sqrt(1.0 + slope * slope) - max(THICK, uPx) * 0.5;
    vec3 c = colorAt(u, clamp(L / max(HEIGHT, 1e-3), 0.0, 1.0));
    float gw = GLOW * 0.08 + 1e-4;
    vec3 acc = c * (coreOf(d) * CORE + glowOf(d, gw) * GLOW);
    float cover = coreOf(d);
    if (MODE > 2.5 && y < L) {
        float body = 0.18 + 0.82 * exp(-(L - y) * 9.0);
        acc += colorAt(u, y / max(HEIGHT, 1e-3)) * body * FILL;
        cover = max(cover, FILL * 0.85);
    }
    return vec4(acc * refl, cover * refl);
}

vec4 roundCurve(vec2 q) {
    float R = SIZE;
    float arc = clamp(ARC, 0.02, 1.0);
    float ang = atan(q.x, q.y);
    float u = fract(ang / TAU + arc * 0.5) / arc;
    if (u > 1.0) return vec4(0.0);
    float r = length(q);
    float L = curveVal(u);
    float sgn = DUAL > 1.5 ? -1.0 : 1.0;
    float target = R + sgn * L;
    float du = 1.0 / 512.0;
    float slope = (curveVal(u + du) - curveVal(u - du)) / (2.0 * du * TAU * max(r, 1e-3) * arc);
    float d = abs(r - target) / sqrt(1.0 + slope * slope) - max(THICK, uPx) * 0.5;
    if (DUAL > 0.5 && DUAL < 1.5) d = min(d, abs(r - (R - L)) - max(THICK, uPx) * 0.5);
    vec3 c = colorAt(u, clamp(L / max(HEIGHT, 1e-3), 0.0, 1.0));
    float gw = GLOW * 0.08 + 1e-4;
    vec3 acc = c * (coreOf(d) * CORE + glowOf(d, gw) * GLOW);
    float cover = coreOf(d);
    if (MODE > 2.5) {
        float inside = sgn > 0.0 ? step(R, r) * step(r, target) : step(target, r) * step(r, R);
        float edge = abs(target - r);
        acc += c * inside * (0.18 + 0.82 * exp(-edge * 9.0)) * FILL;
        cover = max(cover, inside * FILL * 0.85);
    }
    if (EXTRA > 0.0) {
        float dr = abs(r - R) - max(THICK, uPx) * 0.5;
        acc += c * (coreOf(dr) + glowOf(dr, gw) * GLOW) * EXTRA;
    }
    return vec4(acc, cover);
}

void main() {
    vec2 q = vPos - CEN;
    vec4 r;
    bool curve = MODE > 1.5 && MODE < 3.5;
    if (RADIAL > 0.5) {
        q = rot2(-(ROT + SPIN * TIME) * TAU) * q;
        r = curve ? roundCurve(q) : roundBars(q);
    } else {
        r = curve ? straightCurve(q) : straightBars(q);
    }
    o = lightOut(r.rgb * BRIGHTN, r.a);
}
