// LIGHTNING: forked electric arcs that strike on the kick (or snare), crackle while they
// live and leave an afterglow. Game-style energy: from the centre out, or sky to ground.
in vec2 vUv;
in vec2 vPos;
out vec4 o;

#define COUNT    uP[0].x
#define FROM     uP[0].y   // 0 centre outward, 1 top to bottom, 2 ring inward
#define BRIGHTN  uP[0].z
#define PALSHIFT uP[0].w
#define JITTER   uP[1].x
#define BRANCH   uP[1].y
#define WIDTH    uP[1].z
#define GLOW     uP[1].w
#define TRIGGER  uP[2].x   // 0 kick, 1 always crackling, 2 snare
#define REACH    uP[2].y
#define HUESPIN  uP[2].z
#define CEN      uP[3].xy
#define RADIUS   uP[3].z

float bolt(vec2 p, vec2 a, vec2 b, float seed, float flick) {
    vec2 ab = b - a;
    float len = length(ab);
    vec2 dir = ab / max(len, 1e-4);
    vec2 nrm = vec2(-dir.y, dir.x);
    float t = clamp(dot(p - a, dir) / len, 0.0, 1.0);
    float env = sin(t * PI);
    float off = fbm2(vec2(t * 6.0, seed + flick), 4) * JITTER * 0.35 * len * env;
    off += snoise(vec2(t * 28.0, seed * 3.0 + flick)) * JITTER * 0.04 * len * env;
    vec2 onBolt = a + dir * t * len + nrm * off;
    float d = length(p - onBolt);
    // A branch splits off part-way along and wanders away.
    float bt = 0.3 + 0.4 * hash11(seed);
    vec2 bp = a + dir * bt * len + nrm * fbm2(vec2(bt * 6.0, seed + flick), 4) * JITTER * 0.35 * len * sin(bt * PI);
    vec2 bdir = normalize(dir + nrm * (hash11(seed + 3.0) - 0.5) * 2.5);
    float bl = len * 0.45 * BRANCH;
    float s = clamp(dot(p - bp, bdir) / max(bl, 1e-4), 0.0, 1.0);
    vec2 bn = vec2(-bdir.y, bdir.x);
    vec2 onB = bp + bdir * s * bl + bn * snoise(vec2(s * 10.0, seed + flick)) * JITTER * 0.1 * bl * (1.0 - s);
    float db = length(p - onB) + s * WIDTH * 2.0;
    return min(d, BRANCH > 0.0 ? db : 1e5);
}

void main() {
    vec2 p = vPos;
    float env;
    float strikeAt;
    if (TRIGGER < 0.5) { env = exp(-shockAge(0) * 7.0); strikeAt = TIME - shockAge(0); }
    else if (TRIGGER < 1.5) { env = 0.6 + 0.4 * LEVEL; strikeAt = floor(TIME * 12.0) / 12.0; }
    else { env = SNARE; strikeAt = floor(TIME * 6.0); }
    if (env < 0.01) { o = vec4(0.0); return; }
    float flick = floor(TIME * 24.0) * 0.37;
    vec3 col = vec3(0.0);
    int n = int(clamp(COUNT, 1.0, 8.0));
    for (int i = 0; i < 8; i++) {
        if (i >= n) break;
        float seed = floor(strikeAt * 100.0) * 0.13 + float(i) * 7.7;
        vec2 h = hash21(seed);
        vec2 a, b;
        if (FROM < 0.5) {
            float ang = h.x * TAU;
            a = CEN + vec2(cos(ang), sin(ang)) * RADIUS;
            b = CEN + vec2(cos(ang), sin(ang)) * (RADIUS + REACH * (0.5 + h.y));
        } else if (FROM < 1.5) {
            a = vec2((h.x - 0.5) * 2.0 * EXT.x * 0.8, EXT.y);
            b = vec2(a.x + (h.y - 0.5) * 0.8, EXT.y - REACH * 2.0);
        } else {
            float ang = h.x * TAU;
            a = CEN + vec2(cos(ang), sin(ang)) * (RADIUS + REACH);
            b = CEN + vec2(cos(ang), sin(ang)) * RADIUS * 0.2;
        }
        float d = bolt(p, a, b, seed, flick) - WIDTH * 0.5;
        vec3 c = palw(h.y * 0.3 + PALSHIFT + TIME * HUESPIN);
        col += mix(c, vec3(1.0), 0.6) * coreOf(d) * 1.5 + c * glowOf(d, GLOW * 0.05 + 1e-4) * GLOW;
    }
    o = lightOut(col * env * BRIGHTN, 0.0);
}
