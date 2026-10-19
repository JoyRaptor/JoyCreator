// PATTERN: hard-edged rave geometry. Moire rings, a hex floor that lights cell by cell,
// Truchet arcs that read as a pulsing maze, a checkerboard tunnel, hypnotic spiral stripes.
in vec2 vUv;
in vec2 vPos;
out vec4 o;

#define MODE     uP[0].x   // 0 moire, 1 hex, 2 truchet, 3 checker tunnel, 4 spiral
#define SCALE    uP[0].y
#define BRIGHTN  uP[0].z
#define PALSHIFT uP[0].w
#define SPEEDF   uP[1].x
#define LINEW    uP[1].y
#define BEATR    uP[1].z
#define HUESPIN  uP[1].w
#define GLOW     uP[2].x
#define CEN      uP[2].yz
#define FILLP    uP[2].w

vec3 C(float t) { return palw(t * 0.5 + PALSHIFT + TIME * HUESPIN); }

float hexDist(vec2 p) { p = abs(p); return max(dot(p, normalize(vec2(1.0, 1.7320508))), p.x); }

void main() {
    vec2 p = vPos - CEN;
    float t = TIME * SPEEDF;
    vec3 col = vec3(0.0);
    float cover = 0.0;
    float m = MODE;
    float gw = GLOW * 0.05 + 1e-4;
    if (m < 0.5) {
        vec2 c1 = vec2(sin(t * 0.3), cos(t * 0.23)) * 0.25 * (1.0 + BASS * BEATR);
        vec2 c2 = -c1;
        float r1 = length(p - c1) * SCALE * 10.0, r2 = length(p - c2) * SCALE * 10.0;
        float a = 0.5 + 0.5 * cos(r1 * PI * 2.0 - t * 2.0);
        float b = 0.5 + 0.5 * cos(r2 * PI * 2.0 - t * 2.0);
        float moire = abs(a - b);
        moire = smoothstep(0.5 - LINEW, 0.5 + LINEW, moire);
        col = C(moire * 0.5 + length(p) * 0.3) * moire * (0.6 + LEVEL);
    } else if (m < 1.5) {
        vec2 s = vec2(1.0, 1.7320508);
        vec2 q = p * SCALE * 6.0;
        vec2 ha = mod(q, s) - s * 0.5;
        vec2 hb = mod(q - s * 0.5, s) - s * 0.5;
        vec2 gv = dot(ha, ha) < dot(hb, hb) ? ha : hb;
        vec2 id = q - gv;
        float e = spec(hash12(floor(id * 10.0)) );
        float ring = exp(-abs(length(id) / (SCALE * 6.0) - shockAge(0) * 1.5) * 8.0) * step(shockAge(0), 2.5);
        float lit = e * BEATR + ring;
        float d = (0.5 - hexDist(gv)) / (SCALE * 6.0) - LINEW * 0.05;
        col = C(hash12(id) * 0.4 + lit * 0.4) * (coreOf(-d + LINEW * 0.1) * 0.0 + glowOf(abs(d), gw) * GLOW * 0.5 + coreOf(abs(d) - LINEW * 0.02) );
        col += C(0.5 + lit * 0.5) * smoothstep(0.0, 0.02, d) * lit * FILLP;
    } else if (m < 2.5) {
        vec2 q = p * SCALE * 8.0;
        vec2 id = floor(q);
        vec2 f = fract(q) - 0.5;
        if (hash12(id + floor(t * 0.25)) > 0.5) f.x = -f.x;
        float dd = abs(abs(f.x + f.y) - 0.5) * 0.7071;
        vec2 cA = f - vec2(0.5, 0.5) * sign(f.x + f.y + 1e-4);
        float arc = abs(length(cA) - 0.5);
        float d = arc / (SCALE * 8.0) - LINEW * 0.01;
        float flow = 0.5 + 0.5 * sin((id.x + id.y) * 0.8 - t * 3.0 + atan(cA.y, cA.x) * 2.0);
        col = C(flow * 0.5 + length(p) * 0.2) * (coreOf(d) + glowOf(d, gw) * GLOW) * (0.4 + flow * (0.6 + LEVEL));
    } else if (m < 3.5) {
        float r = length(p) + 1e-4;
        float a = atan(p.y, p.x) / TAU;
        float z = 1.0 / r * 0.3 + t * 0.5 + BASSACC * 0.3;
        vec2 g = vec2(a * 16.0 * SCALE + z * 0.5, z * 4.0 * SCALE);
        float chk = mod(floor(g.x) + floor(g.y), 2.0);
        float fog = smoothstep(0.0, 0.5, r);
        col = C(fract(z * 0.1)) * chk * fog * (0.5 + KICK * BEATR);
        cover = chk * fog * FILLP;
    } else {
        float r = length(p);
        float a = atan(p.y, p.x);
        float s = fract(a / TAU * 6.0 + log(r + 1e-3) * 2.0 * SCALE - t * 0.5);
        float band = smoothstep(0.5 - LINEW, 0.5, s) * smoothstep(0.5 + LINEW + 0.02, 0.5 + 0.02, s);
        col = C(s * 0.5 + r * 0.2) * band * (0.5 + LEVEL + KICK * BEATR) * smoothstep(0.0, 0.1, r);
    }
    o = lightOut(col * BRIGHTN, cover);
}
