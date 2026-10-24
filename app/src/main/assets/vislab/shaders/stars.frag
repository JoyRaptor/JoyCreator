// STARS: a crisp star field drawn at full resolution, a few bright ones and many faint, in up to
// three layers of depth that drift at different speeds. Twinkle styles: steady, twinkle, pulse to
// the beat, sparkle on the hi-hats. Shooting stars now and then. Light only: it never hides what
// is behind it, so it sits under aurora curtains and behind mountains.
in vec2 vUv;
in vec2 vPos;
out vec4 o;

#define DENSITY  uP[0].x
#define SIZEK    uP[0].y
#define BRIGHTN  uP[0].z
#define PALSHIFT uP[0].w
#define TWMODE   uP[1].x   // 0 steady, 1 twinkle, 2 pulse to the beat, 3 sparkle on hi-hats
#define TWSPEED  uP[1].y
#define DRIFT    uP[1].z
#define DEPTHK   uP[1].w
#define COLOURK  uP[2].x
#define SHOOT    uP[2].y
#define HUESPIN  uP[2].z
#define GLOWK    uP[2].w

vec3 starLayer(vec2 p, float d) {
    float scale = 34.0 + d * 26.0;                  // cells per scene unit: far layers are finer
    vec2 q = p * scale + vec2(TIME * DRIFT * (1.0 - d * 0.3) * scale * 0.08, 0.0) + d * 91.7 + uSeed * 7.0;
    vec2 cell = floor(q);
    float h = hash12(cell + d * 17.0);
    if (h > DENSITY * (0.35 + d * 0.2)) return vec3(0.0);
    vec2 off = (hash22(cell + 3.1 + d) - 0.5) * 0.5;
    vec2 f = fract(q) - 0.5 - off;
    float m = pow(hash12(cell + 9.7), 4.0);         // magnitude: most faint, a few bright
    float rad = (0.35 + 1.4 * m) * SIZEK;           // core radius in pixels
    float dist = length(f) / scale;                 // scene units
    float px = max(uPx, 1e-5);
    float core = clamp(rad - dist / px + 0.5, 0.0, 1.0);
    float glow = GLOWK * m * exp(-dist / (px * (2.0 + 6.0 * m))) * smoothstep(0.24, 0.1, length(f));
    float tw = 1.0;
    int mode = int(TWMODE + 0.5);
    float ph = h * 61.0;
    if (mode == 1) tw = 0.55 + 0.45 * sin(TIME * TWSPEED * 3.0 + ph);
    else if (mode == 2) tw = 0.45 + 0.55 * exp(-BEATPH * 5.0) * step(0.4, fract(ph));
    else if (mode == 3) tw = 0.5 + HAT * 2.5 * step(0.75, hash12(cell + floor(TIME * (4.0 + TWSPEED * 6.0))));
    vec3 tint = mix(vec3(0.92, 0.95, 1.0), palw(h * 0.8 + PALSHIFT + TIME * HUESPIN), COLOURK);
    return tint * (core * (0.35 + 0.9 * m) + glow) * tw * (1.0 - d * 0.25);
}

// A shooting star in time slot k: a bright head and a fading tail, now and then.
vec3 meteor(vec2 p, float k) {
    float slot = floor(TIME / 2.2 + k * 0.5);
    float tin = fract(TIME / 2.2 + k * 0.5) * 2.2;  // seconds into the slot
    vec4 r = hash41(slot * 7.13 + k * 3.7 + uSeed);
    if (r.x > SHOOT || tin > 0.8) return vec3(0.0);
    vec2 a = (r.yz * 2.0 - 1.0) * EXT * vec2(0.9, 0.6) + vec2(0.0, 0.3 * EXT.y);
    vec2 dir = normalize(vec2(r.w > 0.5 ? 1.0 : -1.0, -0.55 - r.w * 0.4));
    float len = 0.25 + 0.3 * r.y;
    vec2 head = a + dir * tin * 1.6;
    vec2 tail = head - dir * len;
    float d = sdSegment(p, tail, head);
    float along = clamp(dot(p - tail, dir) / len, 0.0, 1.0);
    float fade = smoothstep(0.8, 0.5, tin) * smoothstep(0.0, 0.08, tin);
    float line = clamp(1.0 - d / max(uPx * 1.2, 1e-5), 0.0, 1.0) + exp(-d / (uPx * 4.0)) * 0.4;
    return vec3(0.95, 0.97, 1.0) * line * along * along * fade * 1.6;
}

void main() {
    vec2 p = vPos;
    vec3 col = vec3(0.0);
    int layers = int(clamp(DEPTHK + 0.5, 1.0, 3.0));
    for (int i = 0; i < 3; i++) {
        if (i >= layers) break;
        col += starLayer(p, float(i) / 2.0);
    }
    if (SHOOT > 0.0) col += meteor(p, 0.0) + meteor(p, 1.0);
    o = lightOut(col * BRIGHTN, 0.0);
}
