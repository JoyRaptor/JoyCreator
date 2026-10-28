// NEON RIDGE: Outrun's sound mountains on their own (roadmap R2): the spectrum as a ridgeline
// standing on the horizon, filled dark with a neon edge. Valley lowers the middle so a sun shows
// through; Rough mixes rock into the sound.
in vec2 vUv;
in vec2 vPos;
out vec4 o;

#define HEIGHT   uP[0].x
#define HORIZON  uP[0].y
#define BRIGHTN  uP[0].z
#define PALSHIFT uP[0].w
#define LINEGLOW uP[1].x
#define HUESPIN  uP[1].y
#define VALLEY   uP[1].z
#define ROUGH    uP[1].w

vec3 C(float t) { return palw(t * 0.5 + PALSHIFT + TIME * HUESPIN); }

void main() {
    vec2 p = vPos;
    float hz = HORIZON;
    float above = step(hz, p.y);
    float u = abs(p.x / EXT.x);
    float s = spec(u * 0.7) * (1.0 - ROUGH) + ROUGH * (0.5 + 0.5 * snoise(vec2(p.x * 3.0, 1.0)));
    float valley = mix(1.0, smoothstep(0.05, 0.6, u), VALLEY);
    float ridge = hz + HEIGHT * (0.05 + s * 0.22) * valley;
    float inside = smoothstep(ridge + uPx, ridge - uPx, p.y) * above;
    vec3 col = C(0.05) * 0.08 * inside;
    col += C(0.3) * exp(-abs(p.y - ridge) / (0.004 + LINEGLOW * 0.01)) * 1.2 * above;
    o = vec4(col * BRIGHTN, inside * (1.0 - uAdd)) * uOpacity;
}
