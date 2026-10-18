// NEBULA: deep space. Three ingredients, the way the real ones photograph:
//   gas        large soft glowing clouds, mostly black space between them
//   filaments  thin bright strands where the gas is compressed (ridged noise)
//   dust       dark lanes that eat into the gas
// plus a lit core and a star field that twinkles on the hi-hats. The bass swells the gas.
in vec2 vUv;
in vec2 vPos;
out vec4 o;

#define SPEEDF   uP[0].x
#define SCALE    uP[0].y
#define BRIGHTN  uP[0].z
#define PALSHIFT uP[0].w
#define DENSITY  uP[1].x
#define CONTRAST uP[1].y
#define STARS    uP[1].z
#define TWINK    uP[1].w
#define SWELL    uP[2].x
#define WARP     uP[2].y
#define HUESPIN  uP[2].z
#define DUST     uP[2].w
#define CORE     uP[3].xy
#define COREGLOW uP[3].z

void main() {
    vec2 q = vPos * SCALE * 0.6;
    float t = TIME * SPEEDF * 0.02 + BASSACC * SPEEDF * 0.01;
    vec2 w = vec2(fbm2(q * 0.8 + vec2(t, 3.1), 3), fbm2(q * 0.8 + vec2(7.4, -t), 3));
    vec2 qw = q + w * WARP * 0.9;
    // Gas: low frequency, soft.
    float gas = smoothstep(-0.05, 0.65, fbm2(qw * 0.9 + vec2(1.3, t * 0.5), 4) * DENSITY);
    gas = pow(gas, CONTRAST);
    // Filaments: the ridges of a second field, kept only inside the gas.
    float r = 1.0 - abs(fbm2(qw * 1.7 + vec2(4.2, -t * 0.7), 5));
    float fil = pow(clamp(r, 0.0, 1.0), 9.0) * smoothstep(0.05, 0.6, gas);
    // Dust lanes.
    float dust = smoothstep(0.1, 0.55, fbm2(qw * 2.3 + 9.7, 4)) * DUST;
    float hue = clamp(0.5 + 0.7 * w.x, 0.0, 1.0);
    vec3 cGas = palw(hue * 0.35 + PALSHIFT + TIME * HUESPIN);
    vec3 cFil = palw(hue * 0.35 + PALSHIFT + 0.22 + TIME * HUESPIN);
    float core = exp(-length(vPos - CORE) * 1.5) * COREGLOW;
    float swell = 1.0 + (BASS * 0.6 + KICK * 0.3) * SWELL;
    vec3 col = (cGas * gas * 0.45 + cFil * fil * 1.6) * (1.0 - dust) * (0.6 + core) * swell;
    col += cFil * core * core * 0.3 * swell * (1.0 - dust * 0.5);

    if (STARS > 0.0) {
        for (int layer = 0; layer < 2; layer++) {
            float sc = layer == 0 ? 90.0 : 40.0;
            vec2 cell = floor(vPos * sc);
            vec2 f = fract(vPos * sc) - 0.5;
            float h = hash12(cell + float(layer) * 17.0);
            if (h > 1.0 - STARS * (layer == 0 ? 0.05 : 0.02)) {
                vec2 off = (hash22(cell) - 0.5) * 0.6;
                float d = length(f - off);
                float tw = 1.0 - TWINK * (0.5 + 0.5 * sin(TIME * (3.0 + h * 9.0) + h * 60.0)) + HAT * TWINK * 0.6;
                float s = exp(-d * d * (layer == 0 ? 120.0 : 60.0)) * max(tw, 0.0) * (layer == 0 ? 0.7 : 1.3);
                col += mix(vec3(1.0), palw(h + PALSHIFT), 0.3) * s;
            }
        }
    }
    o = lightOut(col * BRIGHTN, 0.0);
}
