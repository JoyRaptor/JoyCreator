// CRYSTAL: Voronoi cells with glowing seams. Every cell owns a frequency and lights with it,
// so the whole wall shimmers with the music; the kick sends a wave across from the centre.
in vec2 vUv;
in vec2 vPos;
out vec4 o;

#define SPEEDF   uP[0].x
#define SCALE    uP[0].y
#define BRIGHTN  uP[0].z
#define PALSHIFT uP[0].w
#define EDGEW    uP[1].x
#define EDGEGLOW uP[1].y
#define CELLFILL uP[1].z
#define PULSE    uP[1].w
#define HUESPIN  uP[2].x
#define WAVE     uP[2].y

void main() {
    vec2 p = vPos * SCALE;
    float t = TIME * SPEEDF * 0.25;
    vec2 ip = floor(p), fp = fract(p);
    float d1 = 8.0, d2 = 8.0;
    vec2 id1 = vec2(0.0);
    vec2 mr = vec2(0.0);
    vec2 mg = vec2(0.0);
    for (int j = -1; j <= 1; j++)
    for (int i = -1; i <= 1; i++) {
        vec2 g = vec2(float(i), float(j));
        vec2 h = hash22(ip + g);
        vec2 off = 0.5 + 0.42 * sin(t + TAU * h);
        vec2 r = g + off - fp;
        float d = dot(r, r);
        if (d < d1) { d2 = d1; d1 = d; id1 = ip + g; mr = r; mg = g; }
        else if (d < d2) { d2 = d; }
    }
    // Second pass for a true distance to the cell border (Quilez), over the 3x3 cells around
    // the nearest one: the same picture as the full 5x5 search at half the work (a phone ran
    // this look at 30 fps with the 5x5).
    float border = 8.0;
    for (int j = -1; j <= 1; j++)
    for (int i = -1; i <= 1; i++) {
        vec2 g = mg + vec2(float(i), float(j));
        vec2 h = hash22(ip + g);
        vec2 off = 0.5 + 0.42 * sin(t + TAU * h);
        vec2 r = g + off - fp;
        if (dot(mr - r, mr - r) > 1e-5) border = min(border, dot(0.5 * (mr + r), normalize(r - mr)));
    }
    float hc = hash12(id1);
    float e = spec(hc) ;
    float wave = WAVE * exp(-abs(length(vPos) - shockAge(0) * 1.2) * 6.0) * step(shockAge(0), 2.0);
    float lit = CELLFILL * (0.15 + e * PULSE) + wave;
    float edge = border / SCALE;
    float d = edge - EDGEW * 0.5;
    vec3 c = palw(hc * 0.5 + PALSHIFT + TIME * HUESPIN);
    vec3 col = c * lit * smoothstep(0.0, 0.03, edge) + c * (coreOf(d) + glowOf(d, EDGEGLOW * 0.03 + 1e-4) * EDGEGLOW) * (0.5 + e);
    o = lightOut(col * BRIGHTN, 0.0);
}
