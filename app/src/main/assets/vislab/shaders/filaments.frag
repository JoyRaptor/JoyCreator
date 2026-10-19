// FILAMENTS: mountains of hair-thin light. Each row is the spectrum a moment further in
// the past, set further back: smaller, higher on screen, softer (out of focus) and dimmer,
// so the sound leaves a range of peaks behind it as it plays. Every thread has its own
// height, which gives the comb-of-spikes skyline.
in vec2 vUv;
in vec2 vPos;
out vec4 o;

#define ROWS     uP[0].x
#define THREADS  uP[0].y   // threads across the full width
#define BASEY    uP[0].z
#define WIDTH    uP[0].w   // share of frame width
#define HEIGHT   uP[1].x
#define DEPTH    uP[1].y   // how far up each row behind sits
#define SHARP    uP[1].z   // 1 = hairline threads, 0 = soft blades
#define DOF      uP[1].w   // how much the back rows blur
#define RIM      uP[2].x
#define FILL     uP[2].y
#define BRIGHTN  uP[2].z
#define PALSHIFT uP[2].w
#define GAIN     uP[3].x
#define FLO      uP[3].y
#define FHI      uP[3].z
#define MIRROR   uP[3].w
#define SMOOTH   uP[4].x   // 0 = every band its own spike, 1 = rolling hills
#define SPIKE    uP[4].y   // per-thread height scatter
#define SWAY     uP[4].z
#define HUESPIN  uP[4].w
#define CMODE    uP[5].x   // 0 across, 1 by row, 2 by height
#define ROWSHIFT uP[5].y
#define DRIFT    uP[5].z
#define AGESTEP  uP[5].w   // history seconds-ish per row (0..1 of the 2 s buffer)
#define CROWN    uP[6].x   // 0 = flat range, 1 = one central summit

float freqOf(float u) {
    float f = u;
    if (MIRROR > 1.5) f = abs(u * 2.0 - 1.0);
    else if (MIRROR > 0.5) f = 1.0 - abs(u * 2.0 - 1.0);
    return mix(FLO, FHI, clamp(f, 0.0, 1.0));
}

float ridge(float u, float age) {
    float w = SMOOTH * 0.06;
    float s = hist(freqOf(u), age) * 0.4;
    s += hist(freqOf(u - w), age) * 0.2 + hist(freqOf(u + w), age) * 0.2;
    s += hist(freqOf(u - w * 2.0), age) * 0.1 + hist(freqOf(u + w * 2.0), age) * 0.1;
    // Edges taper so the range sits in the frame like a landscape, not a cut-off strip.
    float edge = smoothstep(0.0, 0.12, u) * smoothstep(1.0, 0.88, u);
    float crown = mix(1.0, 0.25 + 0.75 * exp(-pow((u - 0.5) * 3.2, 2.0)), CROWN);
    return pow(max(s * GAIN, 0.0), 1.25) * edge * crown;
}

void main() {
    int rows = int(clamp(ROWS, 1.0, 6.0));
    float W = WIDTH * 2.0 * EXT.x;
    vec3 col = vec3(0.0);
    float cover = 0.0;
    for (int kk = 5; kk >= 0; kk--) {
        if (kk >= rows) continue;
        float k = float(kk);
        float scale = 1.0 - k * 0.13;
        float base = BASEY + k * DEPTH;
        float age = k * AGESTEP;
        float soft = clamp(k / 5.0 * DOF, 0.0, 1.0);
        float dim = 1.0 - k * 0.14;
        float x = vPos.x / scale + DRIFT * k * 0.15 * sin(TIME * 0.1 + k);
        float u = x / W + 0.5;
        if (u < -0.02 || u > 1.02) continue;
        float y = (vPos.y - base) / scale;

        float n = THREADS * WIDTH;
        float s = u * n;
        float i0 = floor(s);
        vec3 rowLight = vec3(0.0);
        float rowCover = 0.0;
        float tw = mix(0.32, 0.05, SHARP) + soft * 0.35;    // thread half-width, in thread spacings
        for (int j = -1; j <= 1; j++) {
            float i = i0 + float(j);
            float ui = (i + 0.5) / n;
            if (ui < 0.0 || ui > 1.0) continue;
            float hRand = hash11(i * 7.31 + k * 131.0 + uSeed);
            float sway = SWAY * 0.25 * sin(TIME * (1.0 + hRand) + i * 0.7) * max(y, 0.0);
            float dx = (s - (i + 0.5) - sway) ;
            float top = ridge(ui, age) * HEIGHT * (1.0 + SPIKE * (hRand - 0.5) * 1.6);
            top = max(top, 0.004);
            if (y < 0.0 || y > top + 0.25) continue;
            float lat = exp(-dx * dx / (tw * tw));
            float v = clamp(y / top, 0.0, 1.2);
            float body = step(y, top) * (0.25 + 0.75 * v * v);
            float tip = exp(-abs(y - top) / (0.012 + soft * 0.03)) * 1.6;
            float ct = CMODE < 0.5 ? (MIRROR > 0.5 ? abs(ui * 2.0 - 1.0) : ui)
                     : CMODE < 1.5 ? k / 5.0 : v;
            vec3 c = palw(ct * 0.5 + PALSHIFT + k * ROWSHIFT + TIME * HUESPIN);
            rowLight += c * lat * (body + tip) * mix(1.0, 0.45, soft);
            rowCover = max(rowCover, lat * step(y, top) * FILL);
        }
        // Rim: a soft glowing skyline along the smoothed ridge, the "sheet" between threads.
        float hr = ridge(u, age) * HEIGHT;
        float dr = abs(y - hr);
        vec3 rc = palw((CMODE < 0.5 ? (MIRROR > 0.5 ? abs(u * 2.0 - 1.0) : u) : k / 5.0) * 0.5
                       + PALSHIFT + k * ROWSHIFT + TIME * HUESPIN);
        rowLight += rc * RIM * (exp(-dr / (0.01 + soft * 0.04)) * 0.6 + step(0.0, y) * step(y, hr) * FILL * 0.25);
        rowLight *= dim;
        // Front rows veil the rows behind them a little: translucent, not opaque.
        col = col * (1.0 - rowCover * 0.55) + rowLight;
        cover = max(cover, rowCover * 0.5);
    }
    o = lightOut(col * BRIGHTN, cover);
}
