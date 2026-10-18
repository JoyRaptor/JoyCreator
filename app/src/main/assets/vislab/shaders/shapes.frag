// SHAPES: geometric light. A halo ring that wobbles with the spectrum, polygons and stars,
// kick shockwaves, an endless polygon tunnel, sonar pulses, a breathing orb, laser-cut
// concentric target rings. All distance fields: crisp at any size, glow included.
in vec2 vUv;
in vec2 vPos;
out vec4 o;

#define MODE     uP[0].x   // 0 halo, 1 polygon/star, 2 shockwaves, 3 tunnel, 4 sonar, 5 orb, 6 target
#define SIDES    uP[0].y
#define CEN      uP[0].zw
#define RADIUS   uP[1].x
#define THICK    uP[1].y
#define GLOW     uP[1].z
#define BRIGHTN  uP[1].w
#define ROT      uP[2].x   // turns
#define SPIN     uP[2].y   // turns per second
#define WOBBLE   uP[2].z   // spectrum push on the outline
#define COUNT    uP[2].w
#define ZSPEED   uP[3].x
#define SHARP    uP[3].y   // star sharpness
#define CMODE    uP[3].z
#define PALSHIFT uP[3].w
#define GAIN     uP[4].x
#define KICKSCALE uP[4].y
#define FILL     uP[4].z
#define DASH     uP[4].w   // 0 solid, >0 number of dashes around
#define HUESPIN  uP[5].x
#define SHOCKSPEED uP[5].y
#define SHOCKLIFE uP[5].z

vec3 col(float t) { return palw(t * 0.5 + PALSHIFT + TIME * HUESPIN); }

// Outline distance for one shape at radius R; u = angle 0..1 for colour and wobble.
float outline(vec2 q, float R, out float u) {
    float ang = atan(q.x, q.y);
    u = fract(ang / TAU);
    float w = 0.0;
    if (WOBBLE > 0.0) w = spec(abs(u * 2.0 - 1.0)) * GAIN * WOBBLE * 0.25;
    float d;
    if (SIDES >= 3.0) d = sdPolyStar(q, R + w, floor(SIDES), SHARP);
    else d = length(q) - (R + w);
    d = abs(d) - THICK * 0.5;
    if (DASH > 0.0) {
        float dd = (abs(fract(u * DASH) - 0.5) - 0.25) * TAU * R / DASH;
        d = max(d, dd);
    }
    return d;
}

void main() {
    vec2 q0 = vPos - CEN;
    float pump = 1.0 + KICK * KICKSCALE * 0.2 + BASS * KICKSCALE * 0.08;
    vec2 q = rot2(-(ROT + SPIN * TIME) * TAU) * q0 / pump;
    float gw = GLOW * 0.035 + 1e-4;
    vec3 acc = vec3(0.0);
    float cover = 0.0;
    float u;
    float m = MODE;

    if (m < 1.5) {
        float d = outline(q, RADIUS, u);
        float ct = CMODE < 0.5 ? abs(u * 2.0 - 1.0) : 0.5;
        acc += col(ct) * (coreOf(d) + glowOf(d, gw) * GLOW);
        cover = coreOf(d);
        if (FILL > 0.0) {
            float inside = SIDES >= 3.0 ? sdPolyStar(q, RADIUS, floor(SIDES), SHARP) : length(q) - RADIUS;
            acc += col(0.0) * coreOf(inside) * FILL * 0.35;
        }
    } else if (m < 2.5) {
        // Shockwaves: one ring per recent kick, racing outward and thinning as it fades.
        for (int i = 0; i < 8; i++) {
            float age = shockAge(i);
            if (age > SHOCKLIFE) continue;
            float k = age / max(SHOCKLIFE, 1e-3);
            float R = RADIUS + age * SHOCKSPEED;
            float d = abs(length(q0) - R) - THICK * (1.0 - k * 0.7) * 0.5;
            float fade = (1.0 - k) * (1.0 - k) * (1.0 - k);
            acc += col(k) * (coreOf(d) + glowOf(d, gw * (1.0 + k)) * GLOW) * fade;
        }
    } else if (m < 3.5) {
        // Tunnel: nested outlines on an exponential scale, flowing toward the viewer.
        float n = max(2.0, COUNT);
        float travel = TIME * ZSPEED + BASSACC * ZSPEED * 0.5;
        float r = length(q);
        float L = log(max(r, 1e-4) / RADIUS) / log(1.6) - fract(travel);
        float k0 = floor(L);
        for (int j = 0; j < 2; j++) {
            float k = k0 + float(j);
            float R = RADIUS * pow(1.6, k + fract(travel));
            vec2 qq = rot2(k * 0.15 + (ROT + SPIN * TIME) * TAU) * q0;
            float d = outline(qq, R, u);
            float depth = clamp((k + fract(travel)) / n + 0.5, 0.0, 1.0);
            float fade = smoothstep(-n * 0.5, -n * 0.5 + 1.5, k) * smoothstep(n * 0.5, n * 0.5 - 1.0, k);
            float flash = 1.0 + KICK * 1.5 * step(fract(k * 0.25 + floor(travel) * 0.25), 0.3);
            acc += col(depth) * (coreOf(d) + glowOf(d, gw) * GLOW) * fade * flash;
        }
    } else if (m < 4.5) {
        // Sonar: steady pulses on the beat, slower and quieter than shockwaves.
        float r = length(q0);
        for (int i = 0; i < 4; i++) {
            float ph = fract(BEATPH * 0.5 + float(i) * 0.25);
            float R = RADIUS * (0.2 + ph * 2.2);
            float d = abs(r - R) - THICK * 0.5;
            acc += col(ph) * (coreOf(d) + glowOf(d, gw) * GLOW) * (1.0 - ph) * (1.0 - ph);
        }
    } else if (m < 5.5) {
        // Orb: a glowing body with a bright limb, breathing with the bass.
        float r = length(q) / RADIUS;
        float body = smoothstep(1.0, 0.0, r);
        float limb = exp(-abs(r - 1.0) * 10.0 / max(GLOW + 0.2, 0.2));
        float swirl = 0.5 + 0.5 * fbm2(q * 3.0 / RADIUS + vec2(TIME * 0.3, -TIME * 0.2), 3);
        acc += col(swirl) * (body * body * (0.5 + swirl) * 0.8 + limb * 1.2);
        cover = smoothstep(1.02, 0.98, r) * FILL;
    } else {
        // Target: fine concentric rings, each one brightening with its own band.
        float r = length(q0);
        float n = max(2.0, COUNT);
        float k = floor(r / RADIUS * n);
        if (k < n) {
            float rr = (k + 0.5) / n * RADIUS;
            float e = spec(k / n) * GAIN;
            float d = abs(r - rr) - THICK * 0.5 * (0.5 + e);
            acc += col(k / n) * (coreOf(d) + glowOf(d, gw) * GLOW) * (0.25 + e * 1.4);
        }
    }
    o = lightOut(acc * BRIGHTN, cover);
}
