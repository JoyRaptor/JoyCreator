// MESH: a calm, premium gradient: soft pools of the palette drifting and breathing, each
// one listening to its own band. The brand-friendly look for talk shows and channel intros.
in vec2 vUv;
in vec2 vPos;
out vec4 o;

#define SPEEDF   uP[0].x
#define SOFT     uP[0].y
#define BRIGHTN  uP[0].z
#define PALSHIFT uP[0].w
#define WARP     uP[1].x
#define REACT    uP[1].y
#define GRAINF   uP[1].z
#define HUESPIN  uP[1].w
#define RIPPLES  uP[2].x

void main() {
    vec2 p = vPos;
    float t = TIME * SPEEDF * 0.15;
    if (WARP > 0.0) p += WARP * 0.25 * vec2(snoise(p * 0.9 + t), snoise(p * 0.9 - t + 7.3));
    if (RIPPLES > 0.0) p += RIPPLES * 0.02 * sin(length(p) * 18.0 - TIME * 3.0) * (0.3 + BASS);
    vec3 acc = vec3(0.0);
    float wsum = 0.0;
    for (int i = 0; i < 6; i++) {
        float fi = float(i);
        vec2 c = vec2(sin(t * (0.7 + fi * 0.13) + fi * 2.1), cos(t * (0.5 + fi * 0.11) + fi * 1.3)) * EXT * 0.75;
        float band = i == 0 ? BASS : i == 1 ? LOW : i == 2 ? MID : i == 3 ? HIGH : i == 4 ? AIR : VOICE;
        float r = 0.5 + band * REACT * 0.5;
        float d = length(p - c) / r;
        float w = 1.0 / pow(d * d + 0.05, 1.0 + 2.0 * (1.0 - SOFT));
        acc += palw(fi / 6.0 + PALSHIFT + TIME * HUESPIN) * w;
        wsum += w;
    }
    vec3 col = acc / wsum;
    col *= 0.85 + LEVEL * REACT * 0.25;
    if (GRAINF > 0.0) col += GRAINF * 0.04 * (hash12(gl_FragCoord.xy + floor(TIME * 24.0)) - 0.5);
    o = vec4(col * BRIGHTN, 1.0 - uAdd) * uOpacity;
}
