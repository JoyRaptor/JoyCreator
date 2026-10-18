// SLIME, show: the scent map through the palette. Thin veins glow, crowded hubs burn white.
in vec2 vUv;
in vec2 vPos;
out vec4 o;
uniform sampler2D uTrail;

#define BRIGHTN  uP[2].x
#define PALSHIFT uP[2].y
#define HUESPIN  uP[2].z
#define CONTRAST uP[2].w
#define SPECIES  uP[3].w

void main() {
    vec4 t = texture(uTrail, vUv);
    vec3 col;
    if (SPECIES < 1.5) {
        float v = 1.0 - exp(-t.r * CONTRAST * 0.15);
        col = palw(v * 0.5 + PALSHIFT + TIME * HUESPIN) * v * 1.4 + vec3(1.0) * pow(v, 6.0) * 0.6;
    } else {
        vec3 v = 1.0 - exp(-t.rgb * CONTRAST * 0.15);
        col = palw(0.0 + PALSHIFT + TIME * HUESPIN) * v.r + palw(0.33 + PALSHIFT + TIME * HUESPIN) * v.g
            + palw(0.66 + PALSHIFT + TIME * HUESPIN) * v.b;
        col *= 1.3;
    }
    o = lightOut(col * BRIGHTN, 0.0);
}
