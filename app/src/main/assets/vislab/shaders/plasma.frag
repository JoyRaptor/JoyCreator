// PLASMA: interfering waves through the palette, the oldest trick in the demoscene book,
// here with contour lines and a bass-driven pressure so it never looks like a screensaver.
in vec2 vUv;
in vec2 vPos;
out vec4 o;

#define SPEEDF   uP[0].x
#define SCALE    uP[0].y
#define BRIGHTN  uP[0].z
#define PALSHIFT uP[0].w
#define COMPLEX  uP[1].x
#define REACT    uP[1].y
#define HUESPIN  uP[1].z
#define CONTOUR  uP[1].w

void main() {
    vec2 p = vPos * SCALE * 3.0;
    float t = TIME * SPEEDF + BASSACC * REACT * 0.5;
    float v = sin(p.x + t) + sin(p.y * 1.3 - t * 0.7) + sin((p.x + p.y) * 0.7 + t * 0.9);
    vec2 c = p + vec2(sin(t * 0.33), cos(t * 0.5)) * 2.0;
    v += sin(length(c) * (1.0 + COMPLEX) - t);
    v += COMPLEX * fbm2(p * 0.4 + t * 0.1, 3) * 2.0;
    v *= 0.25 * (1.0 + BASS * REACT * 0.5);
    vec3 col = palw(v * 0.5 + PALSHIFT + TIME * HUESPIN);
    if (CONTOUR > 0.0) {
        float k = abs(fract(v * 6.0) - 0.5);
        col *= mix(1.0, 0.3 + 1.4 * smoothstep(0.05, 0.0, k - 0.45), CONTOUR);
    }
    o = vec4(col * BRIGHTN * (0.8 + LEVEL * REACT * 0.4), 1.0 - uAdd) * uOpacity;
}
