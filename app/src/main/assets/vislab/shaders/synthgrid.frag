// OUTRUN: a sky gradient, a striped sun, a spectrum mountain range on the horizon and a
// perspective grid floor racing toward you. The grid flashes on the kick.
in vec2 vUv;
in vec2 vPos;
out vec4 o;

#define SPEEDF   uP[0].x
#define HORIZON  uP[0].y
#define BRIGHTN  uP[0].z
#define PALSHIFT uP[0].w
#define DENSE    uP[1].x
#define SUNSIZE  uP[1].y
#define SUNY     uP[1].z
#define MOUNT    uP[1].w
#define LINEGLOW uP[2].x
#define FLASHK   uP[2].y
#define HUESPIN  uP[2].z
#define SKY      uP[2].w
#define CURVE    uP[3].x

vec3 C(float t) { return palw(t * 0.5 + PALSHIFT + TIME * HUESPIN); }

void main() {
    vec2 p = vPos;
    float hz = HORIZON;
    vec3 col = vec3(0.0);
    float cover = 0.0;
    float travel = TIME * SPEEDF + BASSACC * SPEEDF * 0.6;

    if (p.y >= hz) {
        float k = clamp((p.y - hz) / (EXT.y - hz + 1e-3), 0.0, 1.0);
        col += mix(C(0.75), C(0.0) * 0.3, pow(k, 0.6)) * SKY * 0.6;
        // Sun with the classic cut stripes, which scroll down slowly.
        vec2 sp = p - vec2(0.0, SUNY);
        float r = length(sp);
        float sun = smoothstep(SUNSIZE + uPx, SUNSIZE - uPx, r);
        float yy = (sp.y + SUNSIZE) / (2.0 * SUNSIZE);           // 0 bottom .. 1 top
        float gapW = clamp((0.55 - yy) / 0.55, 0.0, 1.0) * 0.6;   // gaps widen toward the bottom
        sun *= step(gapW, fract(yy * 9.0 + TIME * 0.25));
        vec3 sunC = mix(C(0.95), C(0.6), clamp(1.0 - yy, 0.0, 1.0));
        col += sunC * sun * (1.4 + BASS * 0.6);
        col += C(0.85) * exp(-max(r - SUNSIZE, 0.0) * 5.0) * 0.35 * (1.0 + KICK * 0.5);
        // Mountains: the spectrum as a ridgeline, dark fill with a neon edge.
        if (MOUNT > 0.0) {
            float u = abs(p.x / EXT.x);
            float s = spec(u * 0.7) * 0.6 + 0.4 * (0.5 + 0.5 * snoise(vec2(p.x * 3.0, 1.0)));
            float ridge = hz + MOUNT * (0.05 + s * 0.22) * smoothstep(0.05, 0.6, u);
            float inside = smoothstep(ridge + uPx, ridge - uPx, p.y);
            col = mix(col, C(0.05) * 0.08, inside);
            cover = max(cover, inside);
            col += C(0.3) * exp(-abs(p.y - ridge) / (0.004 + LINEGLOW * 0.01)) * 1.2;
        }
    } else {
        // Floor: perspective grid.
        float depth = (hz - p.y);
        float z = 0.35 / max(depth, 1e-3);
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
        col += C(0.35) * (core + glow * 0.5) * fade * flash;
        col += C(0.75) * exp(-depth * 8.0) * 0.6;  // horizon haze
        cover = 1.0 * SKY;
    }
    o = vec4(col * BRIGHTN, cover * (1.0 - uAdd)) * uOpacity;
}
