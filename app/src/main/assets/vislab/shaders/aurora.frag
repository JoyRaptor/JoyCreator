// AURORA: curtains of light that fold and ripple, with fine vertical rays inside each one.
// The mids make them ripple, the highs make the rays shimmer. Optional stars and a dark
// mountain ridge (shaped by the spectrum) along the bottom.
in vec2 vUv;
in vec2 vPos;
out vec4 o;

#define SPEEDF   uP[0].x
#define SCALE    uP[0].y
#define BRIGHTN  uP[0].z
#define PALSHIFT uP[0].w
#define BASEY    uP[1].x
#define WAVY     uP[1].y
#define LAYERS   uP[1].z
#define SHIMMER  uP[1].w
#define STARS    uP[2].x
#define HORIZON  uP[2].y
#define HUESPIN  uP[2].z
#define RIDGE    uP[2].w
#define RAYS     uP[3].x
#define TALL     uP[3].y

void main() {
    vec2 p = vPos;
    float t = TIME * SPEEDF * 0.1;
    vec3 col = vec3(0.0);
    float cover = 0.0;
    int nl = int(clamp(LAYERS, 1.0, 5.0));
    for (int i = 0; i < 5; i++) {
        if (i >= nl) break;
        float fi = float(i);
        float x = p.x * SCALE * (0.6 + fi * 0.15) + fi * 3.7;
        float fold = fbm2(vec2(x * 0.35 + t * (0.4 + fi * 0.1), fi * 7.1 + t * 0.2), 4);
        fold += MID * WAVY * 0.35 * sin(x * 2.0 + TIME * 2.0 + fi);
        float base = BASEY + fi * 0.12 + fold * WAVY * 0.45;
        float h = p.y - base;
        if (h < -0.08) continue;
        float rays = 0.5 + 0.5 * snoise(vec2((x + fold * 2.0) * 18.0 * RAYS, t * 3.0 + fi));
        rays = pow(rays, 3.0) * (1.0 + HIGH * SHIMMER * 2.0) + 0.25;
        float body = smoothstep(-0.08, 0.0, h) * exp(-max(h, 0.0) / (0.25 * TALL + 0.05 * fi));
        float edge = exp(-abs(h) * 30.0) * 0.8;
        float k = clamp(h / (0.6 * TALL), 0.0, 1.0);
        vec3 c = palw(k * 0.45 + fi * 0.06 + PALSHIFT + TIME * HUESPIN);
        col += c * (body * rays + edge) * (0.7 + VOICE * 0.3 + LEVEL * 0.4) / (1.0 + fi * 0.35);
    }
    col += palw(0.15 + PALSHIFT) * HORIZON * exp(-max(p.y - BASEY + 0.3, 0.0) * 3.0) * 0.25;

    if (STARS > 0.0) {
        vec2 cell = floor(p * 110.0);
        float hh = hash12(cell);
        if (hh > 1.0 - STARS * 0.03) {
            float d = length(fract(p * 110.0) - 0.5);
            col += vec3(0.9, 0.95, 1.0) * exp(-d * d * 60.0) * (0.6 + 0.4 * sin(TIME * 4.0 + hh * 50.0));
        }
    }
    if (RIDGE > 0.0) {
        float u = p.x / EXT.x * 0.5 + 0.5;
        float s = spec(abs(u * 2.0 - 1.0) * 0.6);
        float ridgeY = -EXT.y + RIDGE * (0.25 + 0.18 * fbm2(vec2(p.x * 1.3, 2.0), 4) + s * 0.12);
        float m = smoothstep(ridgeY + uPx, ridgeY - uPx, p.y);
        col *= 1.0 - m;
        col += palw(0.05 + PALSHIFT) * exp(-abs(p.y - ridgeY) * 60.0) * 0.4 * (1.0 - m * 0.5);
        cover = m;
    }
    o = vec4(col * BRIGHTN, cover * (1.0 - uAdd)) * uOpacity;
}
