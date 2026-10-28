// RETRO SUN: Outrun's striped sun on its own (roadmap R2): sliced by gaps that widen toward its
// bottom and scroll slowly down, swelling on the bass, its glow kicking with the drum. It sets
// behind the horizon line; Sky adds Outrun's gradient above that line.
in vec2 vUv;
in vec2 vPos;
out vec4 o;

#define SUNSIZE  uP[0].x
#define SUNY     uP[0].y
#define BRIGHTN  uP[0].z
#define PALSHIFT uP[0].w
#define HORIZON  uP[1].x
#define STRIPES  uP[1].y
#define SCROLL   uP[1].z
#define GLOWK    uP[1].w
#define HUESPIN  uP[2].x
#define SKY      uP[2].y
#define SUNX     uP[2].z

vec3 C(float t) { return palw(t * 0.5 + PALSHIFT + TIME * HUESPIN); }

void main() {
    vec2 p = vPos;
    float above = step(HORIZON, p.y);
    float k = clamp((p.y - HORIZON) / (EXT.y - HORIZON + 1e-3), 0.0, 1.0);
    vec3 col = mix(C(0.75), C(0.0) * 0.3, pow(k, 0.6)) * SKY * 0.6;
    vec2 sp = p - vec2(SUNX, SUNY);
    float r = length(sp);
    float size = max(SUNSIZE, 1e-3);
    float sun = smoothstep(size + uPx, size - uPx, r);
    float yy = (sp.y + size) / (2.0 * size);                  // 0 bottom .. 1 top
    float gapW = clamp((0.55 - yy) / 0.55, 0.0, 1.0) * 0.6;   // gaps widen toward the bottom
    float cut = step(gapW, fract(yy * STRIPES + TIME * 0.25 * SCROLL));
    sun *= mix(1.0, cut, step(0.5, STRIPES));                 // no stripes = a whole disc
    vec3 sunC = mix(C(0.95), C(0.6), clamp(1.0 - yy, 0.0, 1.0));
    col += sunC * sun * (1.4 + BASS * 0.6);
    col += C(0.85) * exp(-max(r - size, 0.0) * 5.0) * 0.35 * (1.0 + KICK * 0.5) * GLOWK;
    o = vec4(col * BRIGHTN * above, sun * above * (1.0 - uAdd)) * uOpacity;
}
