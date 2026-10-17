// SLIME (physarum), agent step. Each agent smells the trail ahead, left and right, turns
// toward the strongest, and moves on. Up to three species: each likes its own trail and
// avoids the others, which is what splits the web into rival coloured veins.
// State: x, y (scene units), heading (radians), species.
in vec2 vUv;
in vec2 vPos;
out vec4 o;
uniform sampler2D uState;
uniform sampler2D uTrail;
uniform vec4 uSim;    // count, init (1 = place everyone), tick seed, unused

#define COUNT    uP[0].x
#define SANGLE   uP[0].y   // sensor angle, turns
#define SDIST    uP[0].z   // sensor distance, scene units
#define TURN     uP[0].w   // turn per tick, turns
#define SPEED    uP[1].x   // scene units per second
#define SPECIES  uP[3].w
#define SCATTER  uP[3].x   // kick scatters the headings
#define BASSRUN  uP[3].y
#define MIDSENSE uP[3].z
#define SPAWN    uP[4].x   // 0 everywhere, 1 disc, 2 ring
#define CEN      uP[4].yz
#define EDGES    uP[4].w   // 0 wrap around, 1 bounce

vec4 trailAt(vec2 p) { return texture(uTrail, p / EXT * 0.5 + 0.5); }

float smell(vec2 p, float sp) {
    vec4 t = trailAt(p);
    if (SPECIES < 1.5) return t.r;
    // Own scent attracts, rival scent repels (at half strength, so territories touch).
    vec3 own = sp < 0.5 ? vec3(1.0, -0.5, -0.5) : sp < 1.5 ? vec3(-0.5, 1.0, -0.5) : vec3(-0.5, -0.5, 1.0);
    return dot(t.rgb, own);
}

void main() {
    ivec2 ij = ivec2(gl_FragCoord.xy);
    int W = textureSize(uState, 0).x;
    float id = float(ij.y * W + ij.x);
    if (id >= COUNT) { o = vec4(0.0); return; }
    vec4 s = texelFetch(uState, ij, 0);
    float nsp = max(1.0, floor(SPECIES));
    if (uSim.y > 0.5) {
        vec4 h = hash41(id * 0.731 + uSeed * 11.0);
        vec2 p;
        if (SPAWN < 0.5) p = (h.xy * 2.0 - 1.0) * EXT;
        else if (SPAWN < 1.5) { float a = h.x * TAU; p = CEN + vec2(cos(a), sin(a)) * sqrt(h.y) * 0.6; }
        else { float a = h.x * TAU; p = CEN + vec2(cos(a), sin(a)) * (0.55 + h.y * 0.05); }
        float head = SPAWN > 1.5 ? atan(CEN.y - p.y, CEN.x - p.x) + (h.z - 0.5) : h.z * TAU;
        o = vec4(p, head, mod(id, nsp));
        return;
    }
    vec2 p = s.xy;
    float head = s.z;
    float sp = s.w;
    float sa = (SANGLE + MID * MIDSENSE * 0.05) * TAU;
    float sd = SDIST;
    float F = smell(p + vec2(cos(head), sin(head)) * sd, sp);
    float L = smell(p + vec2(cos(head + sa), sin(head + sa)) * sd, sp);
    float R = smell(p + vec2(cos(head - sa), sin(head - sa)) * sd, sp);
    float rnd = hash12(vec2(id, uSim.z));
    float turn = TURN * TAU;
    if (F > L && F > R) { }
    else if (F < L && F < R) head += (rnd - 0.5) * 2.0 * turn;
    else if (L > R) head += turn * (0.5 + rnd * 0.5);
    else if (R > L) head -= turn * (0.5 + rnd * 0.5);
    if (PUNCH > 0.0 && SCATTER > 0.0) head += (hash12(vec2(uSim.z, id * 1.3)) - 0.5) * TAU * SCATTER * PUNCH;
    float speed = SPEED * (1.0 + BASS * BASSRUN);
    p += vec2(cos(head), sin(head)) * speed * (1.0 / 60.0);
    if (EDGES < 0.5) {
        p = mod(p + EXT, 2.0 * EXT) - EXT;
    } else {
        if (abs(p.x) > EXT.x) { p.x = sign(p.x) * EXT.x; head = PI - head; }
        if (abs(p.y) > EXT.y) { p.y = sign(p.y) * EXT.y; head = -head; }
    }
    o = vec4(p, mod(head, TAU), sp);
}
