// SPRITE: a sprite sheet from Sprite Lab playing as a layer. Which frame shows is decided on the
// CPU (its own speed, a frame per beat or per kick, the loudness, or one held; see SpriteClock);
// this draws that frame, alive like a picture: it pumps with the bass, shakes on the kick,
// splits into RGB on the hits and can wear a halo in the palette.
in vec2 vUv;
in vec2 vPos;
out vec4 o;
uniform sampler2D uSheet;
uniform vec4 uCell;          // this frame's place in the sheet: x, y (from the top), w, h in 0..1
uniform float uCellAspect;   // width / height of one frame

#define CEN      uP[0].xy
#define SIZE     uP[0].z   // half-height, scene units
#define BRIGHTN  uP[0].w
#define PUMP     uP[1].x
#define SHAKEI   uP[1].y
#define HALO     uP[1].z
#define SPLIT    uP[2].x
#define TINT     uP[2].y
#define MIRROR   uP[2].z
#define PALSHIFT uP[2].w
// uP[3] (Play, Speed, Frame, Back and forth) is read on the CPU.

vec4 cell(vec2 uv) {
    vec2 inside = step(vec2(0.0), uv) * step(uv, vec2(1.0));
    return texture(uSheet, uCell.xy + clamp(uv, 0.0, 1.0) * uCell.zw) * inside.x * inside.y;   // premultiplied
}

void main() {
    float s = SIZE * (1.0 + BASS * PUMP * 0.12 + KICK * PUMP * 0.08);
    vec2 shake = SHAKEI * KICK * 0.02 * vec2(snoise(vec2(TIME * 30.0, 1.0)), snoise(vec2(2.0, TIME * 30.0)));
    vec2 q = vPos - CEN - shake;
    vec2 uv = vec2(q.x / (s * uCellAspect), q.y / s) * 0.5 + 0.5;
    uv.y = 1.0 - uv.y;
    if (MIRROR > 0.5) uv.x = 1.0 - uv.x;
    vec4 c = cell(uv);
    float split = SPLIT * (KICK + SNARE * 0.5) * 0.02;
    if (split > 0.0) {
        c.r = cell(uv + vec2(split, 0.0)).r;
        c.b = cell(uv - vec2(split, 0.0)).b;
    }
    vec3 lin = pow(c.rgb, vec3(2.2));
    lin = mix(lin, pal(luma(lin)) * c.a, TINT);
    vec3 col = lin * BRIGHTN;
    if (HALO > 0.0) {
        float g = 0.0;
        for (int i = 0; i < 12; i++) {
            float a = float(i) / 12.0 * TAU;
            vec2 off = vec2(cos(a), sin(a)) * 0.05 * HALO;
            g += cell(uv + off).a + cell(uv + off * 2.0).a * 0.5;
        }
        g /= 18.0;
        col += palw(0.5 + PALSHIFT + TIME * 0.05) * g * (1.0 - c.a) * HALO * (0.8 + BASS + KICK);
    }
    o = vec4(col, c.a * (1.0 - uAdd)) * uOpacity;
}
