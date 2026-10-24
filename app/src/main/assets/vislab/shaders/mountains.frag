// MOUNTAINS: ranges of peaks one behind another, the far ones paler in the haze, each with a lit
// rim and, if wanted, snow on the tops. Drive-by moves them sideways, near ranges faster than far
// ones (parallax), so it reads as driving past instead of warping in place. Paint by default:
// they hide what is behind them (stars, aurora curtains), which is what makes a skyline.
// The spectrum can shape the nearest range; the bass can lift them all.
in vec2 vUv;
in vec2 vPos;
out vec4 o;

#define LAYERS   uP[0].x
#define HEIGHT   uP[0].y
#define BRIGHTN  uP[0].z
#define PALSHIFT uP[0].w
#define BASEY    uP[1].x
#define ROUGH    uP[1].y
#define SPEEDK   uP[1].z
#define PEAKS    uP[1].w
#define RIM      uP[2].x
#define HAZE     uP[2].y
#define HUESPIN  uP[2].z
#define SOUND    uP[2].w
#define SNOW     uP[3].x
#define BASSUP   uP[3].y

// Ridged noise: sharp crests, soft valleys. ROUGH adds the small crags.
float ridge(float x, float seed) {
    float a = 0.0, w = 0.62, f = 1.0;
    for (int k = 0; k < 4; k++) {
        float n = 1.0 - abs(snoise(vec2(x * f, seed + float(k) * 11.3)));
        a += n * n * w;
        f *= 2.1;
        w *= (k == 0 ? 0.55 : 0.5 * ROUGH + 0.05);
    }
    return a;
}

void main() {
    vec2 p = vPos;
    int n = int(clamp(LAYERS + 0.5, 1.0, 5.0));
    float travel = TIME * SPEEDK;
    vec3 col = vec3(0.0);
    float cover = 0.0;
    // Far to near: each nearer range is painted over the ones behind it.
    for (int j = 0; j < 5; j++) {
        int i = n - 1 - j;           // i = 0 is the nearest
        if (i < 0) break;
        float fi = float(i);
        float depth = fi / max(float(n - 1), 1.0);   // 0 near .. 1 far
        float par = 1.0 / (1.0 + fi * 0.9);          // far ranges move less
        float x = p.x * PEAKS * (0.55 + fi * 0.22) + travel * par + fi * 13.7 + uSeed;
        float r = ridge(x * 0.6, fi * 5.1 + uSeed);
        float amp = HEIGHT * (1.0 - depth * 0.45);
        float top = BASEY + fi * 0.09 * HEIGHT + amp * (0.18 + 0.55 * r);
        top += BASS * BASSUP * 0.08 * (1.0 - depth * 0.6);
        if (i == 0 && SOUND > 0.0) {
            float u = clamp(p.x / EXT.x * 0.5 + 0.5, 0.0, 1.0);
            top += spec(abs(u * 2.0 - 1.0) * 0.7) * SOUND * 0.22;
        }
        float inside = smoothstep(top + uPx, top - uPx, p.y);
        if (inside <= 0.0) continue;
        // Body: dark, lifted toward the palette by the haze the further away it is.
        vec3 tint = palw(0.08 + fi * 0.07 + PALSHIFT + TIME * HUESPIN);
        vec3 body = tint * (0.012 + HAZE * depth * depth * 0.2);
        float below = top - p.y;                      // how far under the crest
        vec3 lit = tint * RIM * exp(-below / (0.012 + 0.01 * fi)) * (0.9 - depth * 0.5);
        float snowLine = smoothstep(0.55, 0.85, r) * exp(-below / (0.06 * HEIGHT + 0.01));
        vec3 snow = vec3(0.85, 0.9, 1.0) * SNOW * snowLine * (1.0 - depth * 0.5);
        vec3 c = (body + (lit + snow) * BRIGHTN);
        col = mix(col, c, inside);
        cover = max(cover, inside);
    }
    o = vec4(col, cover * (1.0 - uAdd)) * uOpacity;
}
