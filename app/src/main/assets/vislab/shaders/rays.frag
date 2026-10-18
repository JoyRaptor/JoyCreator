// RAYS: a starburst of god rays from one point, long when the music is loud, flaring on
// the kick. Behind a logo it reads as "the drop"; low and slow it reads as dawn.
in vec2 vUv;
in vec2 vPos;
out vec4 o;

#define SPEEDF   uP[0].x
#define COUNT    uP[0].y
#define BRIGHTN  uP[0].z
#define PALSHIFT uP[0].w
#define CEN      uP[1].xy
#define LEN      uP[1].z
#define SHARP    uP[1].w
#define FLASHK   uP[2].x
#define SPIN     uP[2].y
#define HUESPIN  uP[2].z
#define CORE     uP[2].w

void main() {
    vec2 q = vPos - CEN;
    float r = length(q);
    float a = atan(q.y, q.x) + SPIN * TIME * TAU;
    vec2 ring = vec2(cos(a), sin(a)) * COUNT * 0.16;
    float t = TIME * SPEEDF * 0.3;
    float beams = 0.5 + 0.5 * snoise(vec3(ring, t));
    beams = pow(beams, SHARP) + 0.5 * pow(0.5 + 0.5 * snoise(vec3(ring * 2.3, t * 1.7 + 9.0)), SHARP * 1.5);
    float reach = LEN * (0.4 + LEVEL * 0.8 + KICK * FLASHK * 0.6);
    float fall = exp(-r / max(reach, 0.02));
    float core = exp(-r * 9.0) * CORE * (1.0 + KICK * FLASHK);
    vec3 c = palw(clamp(r / max(reach * 2.0, 0.05), 0.0, 1.0) * 0.5 + PALSHIFT + TIME * HUESPIN);
    vec3 col = c * beams * fall * (0.6 + KICK * FLASHK) + palw(0.95 + PALSHIFT) * core;
    o = lightOut(col * BRIGHTN, 0.0);
}
