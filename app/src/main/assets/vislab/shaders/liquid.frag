// LIQUID: glassy metaballs. Each blob listens to a band and grows with it; where they meet
// they melt together. Shaded with a fake normal: soft body, bright rim, oil-slick colour.
in vec2 vUv;
in vec2 vPos;
out vec4 o;

#define SPEEDF   uP[0].x
#define COUNT    uP[0].y
#define BRIGHTN  uP[0].z
#define PALSHIFT uP[0].w
#define SIZE     uP[1].x
#define REACT    uP[1].y
#define SPREADB  uP[1].z
#define RIM      uP[1].w
#define IRID     uP[2].x
#define HUESPIN  uP[2].y
#define FILL     uP[2].z
#define CEN      uP[3].xy

float field(vec2 p) {
    float f = 0.0;
    int n = int(clamp(COUNT, 1.0, 10.0));
    float t = TIME * SPEEDF * 0.3;
    for (int i = 0; i < 10; i++) {
        if (i >= n) break;
        float fi = float(i);
        vec2 c = CEN + vec2(sin(t * (1.0 + fi * 0.21) + fi * 1.7), cos(t * (0.8 + fi * 0.17) + fi * 2.9)) * SPREADB * 0.55;
        float band = i % 5 == 0 ? BASS : i % 5 == 1 ? LOW : i % 5 == 2 ? MID : i % 5 == 3 ? HIGH : AIR;
        float r = SIZE * (0.55 + band * REACT * 0.8 + (i == 0 ? KICK * 0.25 : 0.0));
        vec2 d = p - c;
        f += r * r / (dot(d, d) + 1e-4);
    }
    return f;
}

void main() {
    vec2 p = vPos;
    float f = field(p);
    float e = 0.004;
    vec2 g = vec2(field(p + vec2(e, 0.0)) - field(p - vec2(e, 0.0)), field(p + vec2(0.0, e)) - field(p - vec2(0.0, e))) / (2.0 * e);
    // Surface is where the field reaches 1; distance estimate = (f - 1) / |grad f|.
    float d = (1.0 - f) / max(length(g), 1e-3);
    float inside = coreOf(d);
    // Chrome: a dark body that reflects a bright "sky" near its rim and the palette as an
    // oil film. The normal comes from the field slope, steepest at the edge.
    vec3 n = normalize(vec3(-g * 0.03, 1.0));
    float fres = pow(1.0 - n.z, 1.5);
    vec3 body = palw(0.6 + PALSHIFT + TIME * HUESPIN) * 0.06;
    vec3 oil = palw(dot(n.xy, vec2(0.7, 0.4)) * IRID + f * 0.04 + PALSHIFT + TIME * HUESPIN + 0.3);
    float sky = smoothstep(0.1, 0.9, n.y * 0.5 + 0.5);                 // light from above
    vec3 col = (body * FILL + oil * fres * 1.3 * RIM + vec3(0.9) * sky * fres * fres * 0.6) * inside;
    col += oil * exp(-max(d, 0.0) / 0.015) * 0.35 * RIM;        // halo just outside the surface
    float spec_ = pow(max(dot(n, normalize(vec3(-0.4, 0.6, 0.7))), 0.0), 30.0) * inside;
    col += vec3(1.0) * spec_ * 0.9;
    o = lightOut(col * BRIGHTN, inside * FILL);
}
