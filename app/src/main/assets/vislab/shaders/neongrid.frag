// NEON GRID: Outrun's perspective floor on its own (roadmap R2): a grid racing toward you that
// flashes on the kick, with a haze along the horizon. Above the horizon it draws nothing, so a
// sun, a ridge or any other layer can sit behind it. Fill darkens the floor between the lines.
in vec2 vUv;
in vec2 vPos;
out vec4 o;

#define SPEEDF   uP[0].x
#define HORIZON  uP[0].y
#define BRIGHTN  uP[0].z
#define PALSHIFT uP[0].w
#define DENSE    uP[1].x
#define LINEGLOW uP[1].y
#define FLASHK   uP[1].z
#define HUESPIN  uP[1].w
#define CURVE    uP[2].x
#define FILL     uP[2].y
#define HAZE     uP[2].z

vec3 C(float t) { return palw(t * 0.5 + PALSHIFT + TIME * HUESPIN); }

void main() {
    vec2 p = vPos;
    float hz = HORIZON;
    float below = 1.0 - step(hz, p.y);
    float travel = TIME * SPEEDF + BASSACC * SPEEDF * 0.6;
    float depth = max(hz - p.y, 1e-3);
    float z = 0.35 / depth;
    float x = p.x * z;
    x += CURVE * sin(z * 0.4 + TIME * 0.5) * 0.5;
    vec2 g = vec2(x * DENSE, z * DENSE + travel * DENSE * 0.6);
    vec2 gd = abs(fract(g) - 0.5);
    vec2 fw = fwidth(g);
    float lineX = gd.x / max(fw.x, 1e-4);
    float lineZ = gd.y / max(fw.y, 1e-4);
    float core = clamp(1.6 - min(lineX, lineZ), 0.0, 1.0);
    float glow = exp(-min(lineX, lineZ) * (0.6 / max(LINEGLOW, 0.05)));
    float fade = exp(-z * 0.08) * smoothstep(0.0, 0.03, depth);
    float flash = 1.0 + KICK * FLASHK * 2.0;
    vec3 col = C(0.35) * (core + glow * 0.5) * fade * flash;
    col += C(0.75) * exp(-depth * 8.0) * 0.6 * HAZE;   // horizon haze
    o = vec4(col * BRIGHTN * below, FILL * below * (1.0 - uAdd)) * uOpacity;
}
