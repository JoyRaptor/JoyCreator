// IMAGE: the logo, album art or a Joy Brush drawing, alive: it pumps with the bass, shakes
// on the kick, splits into RGB on the hits and wears a halo in the palette.
in vec2 vUv;
in vec2 vPos;
out vec4 o;
uniform sampler2D uImage;
uniform float uImageAspect;   // width / height of the picture

#define CEN      uP[0].xy
#define SIZE     uP[0].z   // half-height, scene units
#define BRIGHTN  uP[0].w
#define PUMP     uP[1].x
#define SHAKEI   uP[1].y
#define HALO     uP[1].z
#define SPIN     uP[1].w
#define SPLIT    uP[2].x
#define TINT     uP[2].y
#define CIRCLE   uP[2].z
#define PALSHIFT uP[2].w

vec4 img(vec2 uv) {
    vec2 inside = step(vec2(0.0), uv) * step(uv, vec2(1.0));
    vec4 c = texture(uImage, uv) * inside.x * inside.y;
    if (CIRCLE > 0.5) c *= smoothstep(0.5, 0.49, length(uv - 0.5));
    return c;   // premultiplied
}

void main() {
    float s = SIZE * (1.0 + BASS * PUMP * 0.12 + KICK * PUMP * 0.08);
    vec2 shake = SHAKEI * KICK * 0.02 * vec2(snoise(vec2(TIME * 30.0, 1.0)), snoise(vec2(2.0, TIME * 30.0)));
    vec2 q = rot2(-SPIN * TIME * TAU) * (vPos - CEN - shake);
    vec2 uv = vec2(q.x / (s * uImageAspect), q.y / s) * 0.5 + 0.5;
    uv.y = 1.0 - uv.y;
    vec4 c = img(uv);
    float split = SPLIT * (KICK + SNARE * 0.5) * 0.02;
    if (split > 0.0) {
        c.r = img(uv + vec2(split, 0.0)).r;
        c.b = img(uv - vec2(split, 0.0)).b;
    }
    vec3 lin = pow(c.rgb, vec3(2.2));
    lin = mix(lin, pal(luma(lin)) * c.a, TINT);
    vec3 col = lin * BRIGHTN;
    if (HALO > 0.0) {
        float g = 0.0;
        for (int i = 0; i < 12; i++) {
            float a = float(i) / 12.0 * TAU;
            vec2 off = vec2(cos(a), sin(a)) * 0.05 * HALO;
            g += img(uv + off).a + img(uv + off * 2.0).a * 0.5;
        }
        g /= 18.0;
        col += palw(0.5 + PALSHIFT + TIME * 0.05) * g * (1.0 - c.a) * HALO * (0.8 + BASS + KICK);
    }
    o = vec4(col, c.a * (1.0 - uAdd)) * uOpacity;
}
