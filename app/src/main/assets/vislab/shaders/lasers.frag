// LASERS: rave beams fanning from one or two points, sweeping on the beat through haze.
// The haze is noise the beams light up, which is what makes a laser show read as 3D.
in vec2 vUv;
in vec2 vPos;
out vec4 o;

#define COUNT    uP[0].x
#define SWEEP    uP[0].y
#define BRIGHTN  uP[0].z
#define PALSHIFT uP[0].w
#define ORIGIN   uP[1].xy
#define FAN      uP[1].z   // turns
#define WIDTH    uP[1].w
#define HAZE     uP[2].x
#define STROBE   uP[2].y
#define HUESPIN  uP[2].z
#define MIRRORL  uP[2].w
#define AIM      uP[3].x   // turns, 0 = up
#define CMODE    uP[3].y

vec3 fan(vec2 p, vec2 origin, float side) {
    vec3 acc = vec3(0.0);
    int n = int(clamp(COUNT, 1.0, 24.0));
    vec2 q = p - origin;
    float r = length(q);
    // Haze: the beams only show where smoke catches them, which is what reads as 3D.
    float smoke = 0.5 + 0.5 * fbm2(p * 2.2 + vec2(TIME * 0.15, -TIME * 0.05), 4);
    float hz = mix(1.0, 0.15 + 1.6 * smoke * smoke, clamp(HAZE * 0.6, 0.0, 1.0));
    for (int i = 0; i < 24; i++) {
        if (i >= n) break;
        float fi = float(i);
        float k = n > 1 ? fi / float(n - 1) - 0.5 : 0.0;
        float sweep = SWEEP * sin(TIME * 1.3 + BEATPH * TAU * 0.5 + fi * 0.4) * 0.08;
        float a = (AIM + k * FAN + sweep) * TAU * side;
        vec2 dir = vec2(sin(a), cos(a));
        float along = dot(q, dir);
        if (along < 0.0) continue;
        float perp = abs(dot(q, vec2(-dir.y, dir.x)));
        float w = WIDTH * (1.0 + along * 0.6);
        float beam = exp(-perp / w) + exp(-perp / (w * 7.0)) * 0.07;
        float ct = CMODE < 0.5 ? fi / max(float(n - 1), 1.0) : 0.5 + 0.5 * sin(TIME + fi);
        vec3 c = palw(ct * 0.5 + PALSHIFT + TIME * HUESPIN);
        acc += c * beam * hz * exp(-along * 0.35);
    }
    acc += palw(0.9 + PALSHIFT) * exp(-r * 30.0) * 2.0;
    return acc;
}

void main() {
    vec2 p = vPos;
    float gate = 1.0;
    if (STROBE > 0.0) gate = mix(1.0, step(0.5, fract(BEATPH * 2.0 + 0.25)) * (0.4 + KICK), STROBE);
    vec3 col = fan(p, ORIGIN, 1.0);
    if (MIRRORL > 0.5) col += fan(p, vec2(-ORIGIN.x, ORIGIN.y), -1.0);
    col *= gate * (0.6 + LEVEL * 0.6 + KICK * 0.5);
    o = lightOut(col * BRIGHTN, 0.0);
}
