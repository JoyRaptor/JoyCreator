// TUNNEL: fly through an endless tube. Rings, a checker, honeycomb or speed lines on the
// wall; the music sets the speed and the kick lights the ring you are passing.
in vec2 vUv;
in vec2 vPos;
out vec4 o;

#define SPEEDF   uP[0].x
#define TWIST    uP[0].y
#define BRIGHTN  uP[0].z
#define PALSHIFT uP[0].w
#define PATTERN  uP[1].x   // 0 rings, 1 checker, 2 honeycomb, 3 speed lines
#define SEGS     uP[1].y
#define FOG      uP[1].z
#define HUESPIN  uP[1].w
#define CEN      uP[2].xy
#define FLASHK   uP[2].z
#define WOBBLE   uP[2].w

void main() {
    vec2 q = vPos - CEN;
    q += WOBBLE * 0.15 * vec2(sin(TIME * 0.7), cos(TIME * 0.5));
    float r = length(q) + 1e-4;
    float a = atan(q.y, q.x) / TAU + 0.5;
    float travel = TIME * SPEEDF * 0.5 + BASSACC * SPEEDF * 0.5;
    float z = 0.35 / r + travel;
    a += TWIST * 0.1 / r + TIME * 0.02;
    float pat;
    float m = PATTERN;
    float n = max(3.0, floor(SEGS));
    if (m < 0.5) {
        float k = abs(fract(z * 2.0) - 0.5);
        pat = smoothstep(0.08, 0.0, k - 0.38) + 0.15;
    } else if (m < 1.5) {
        pat = mod(floor(a * n) + floor(z * 2.0), 2.0) * 0.9 + 0.1;
    } else if (m < 2.5) {
        vec2 g = vec2(a * n, z * 2.0);
        vec2 s = vec2(1.0, 1.7320508);
        vec2 ha = mod(g, s) - s * 0.5, hb = mod(g - s * 0.5, s) - s * 0.5;
        vec2 gv = dot(ha, ha) < dot(hb, hb) ? ha : hb;
        vec2 ab = abs(gv);
        float hd = max(dot(ab, normalize(vec2(1.0, 1.7320508))), ab.x);
        pat = smoothstep(0.42, 0.5, hd) + 0.1;
    } else {
        float lane = hash11(floor(a * n * 3.0));
        float streak = fract(z * (0.5 + lane) + lane * 13.0);
        pat = smoothstep(0.75, 1.0, streak) * (0.5 + lane);
    }
    float ringId = floor(z * 2.0);
    float flash = 1.0 + FLASHK * KICK * 2.0 * step(0.0, 1.0 - abs(z * 2.0 - (travel * 2.0 + 0.35 / 0.6)));
    vec3 c = palw(fract(z * 0.05) + a * 0.0 + PALSHIFT + TIME * HUESPIN);
    float fog = 1.0 - exp(-r * FOG * 4.0);
    float centre = exp(-r * 6.0);
    vec3 col = c * pat * fog * flash * (0.6 + LEVEL * 0.6) + palw(0.95 + PALSHIFT) * centre * (0.6 + BASS);
    o = lightOut(col * BRIGHTN, 0.0);
}
