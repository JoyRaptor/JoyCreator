// PARTICLES, step: one texel per particle. Two state textures, written together (MRT):
//   s0 = position xyz, life (0 = born .. 1 = dead)     s1 = velocity xyz, energy at birth
//
// Emission is a ring buffer: this frame the host asks for uEmit.y particles starting at slot
// uEmit.x. Their seeds come from a GLOBAL emission serial (uEmit.w + offset), not the slot,
// so the same moment in a song always births the same particles: scrub, preview and export
// replay identically from the same audio.
in vec2 vUv;
in vec2 vPos;
layout(location = 0) out vec4 o0;
layout(location = 1) out vec4 o1;
uniform sampler2D uState0;
uniform sampler2D uState1;
uniform vec4 uEmit;   // first slot, count, pool size, serial of the first

#define EMITTER  uP[0].x   // 0 point, 1 ring, 2 line, 3 screen, 4 sphere, 5 spectrum line, 6 spectrum ring, 7 frame edge, 8 disc
#define EPOS     uP[0].yz
#define ESIZE    uP[0].w
#define SPEED    uP[1].x
#define SPEEDVAR uP[1].y
#define SPREAD   uP[1].z
#define DIR      uP[1].w
#define LIFE     uP[2].x
#define LIFEVAR  uP[2].y
#define DRAG     uP[2].z
#define GRAVITY  uP[2].w
#define CURL     uP[3].x
#define CSCALE   uP[3].y
#define CSPEED   uP[3].z
#define SWIRL    uP[3].w
#define ATTRACT  uP[4].x
#define PUNCHAMT uP[4].y
#define TURB     uP[4].z
#define ZSPEED   uP[4].w
#define DIRMODE  uP[5].x   // 0 cone, 1 outward, 2 tangent, 3 inward, 4 any way
#define AUDSPEED uP[5].y
#define ATTRMODE uP[9].z   // 0 none, 1 Lorenz, 2 Aizawa, 3 Thomas, 4 Halvorsen
#define ATTRSIZE uP[9].w
#define ORBIT    uP[10].x
#define WIND     uP[10].y
#define FLOOR    uP[10].z  // > 0: bounce on a floor at this height below the emitter... 0 = none
#define ZDEPTH   uP[10].w

float lifeOf(float id) {
    return max(0.05, LIFE * (1.0 + LIFEVAR * (hash11(id * 0.1371 + uSeed) * 2.0 - 1.0)));
}

float specFreqAt(float u) { return spec(abs(u * 2.0 - 1.0) * 0.85); }

// The flow field of a strange attractor, as a velocity in attractor space.
vec3 attractorFlow(vec3 q, float m) {
    if (m < 1.5) {                       // Lorenz
        return vec3(10.0 * (q.y - q.x), q.x * (28.0 - q.z) - q.y, q.x * q.y - 2.6667 * q.z) * 0.05;
    }
    if (m < 2.5) {                       // Aizawa
        float a = 0.95, b = 0.7, c = 0.6, d = 3.5, e = 0.25, f = 0.1;
        return vec3((q.z - b) * q.x - d * q.y,
                    d * q.x + (q.z - b) * q.y,
                    c + a * q.z - q.z * q.z * q.z / 3.0 - (q.x * q.x + q.y * q.y) * (1.0 + e * q.z) + f * q.z * q.x * q.x * q.x);
    }
    if (m < 3.5) {                       // Thomas
        float b = 0.19;
        return vec3(sin(q.y) - b * q.x, sin(q.z) - b * q.y, sin(q.x) - b * q.z) * 2.0;
    }
    float a = 1.89;                      // Halvorsen
    return vec3(-a * q.x - 4.0 * q.y - 4.0 * q.z - q.y * q.y,
                -a * q.y - 4.0 * q.z - 4.0 * q.x - q.z * q.z,
                -a * q.z - 4.0 * q.x - 4.0 * q.y - q.x * q.x) * 0.12;
}

vec3 attractorCentre(float m) {
    if (m < 1.5) return vec3(0.0, 0.0, 25.0);
    if (m < 2.5) return vec3(0.0, 0.0, 0.6);
    return vec3(0.0);
}

float attractorScale(float m) {
    if (m < 1.5) return 0.035;
    if (m < 2.5) return 0.55;
    if (m < 3.5) return 0.22;
    return 0.09;
}

vec3 attractorSpread(float m) {
    if (m < 1.5) return vec3(30.0, 30.0, 30.0);
    if (m < 2.5) return vec3(2.0);
    if (m < 3.5) return vec3(6.0);
    return vec3(10.0);
}

void spawn(float serial, float id, float sub, out vec4 s0, out vec4 s1) {
    vec4 h = hash41(serial * 1.3719 + uSeed * 17.31 + 0.5);
    vec4 g = hash41(serial * 0.7123 + uSeed * 3.17 + 9.1);
    vec2 E = EPOS;
    vec3 p = vec3(E, 0.0);
    float energy = max(LEVEL, KICK * 0.8);
    float live = 1.0;
    float m = EMITTER;
    vec2 outward = vec2(0.0, 1.0);
    vec3 q3 = vec3(0.0);
    if (m < 0.5) {
        float a = h.x * TAU, r = sqrt(h.y) * ESIZE * 0.08;
        p.xy = E + r * vec2(cos(a), sin(a));
        outward = vec2(cos(a), sin(a));
    } else if (m < 1.5) {
        float a = h.x * TAU;
        outward = vec2(sin(a), cos(a));
        p.xy = E + outward * ESIZE;
    } else if (m < 2.5) {
        p.xy = E + vec2((h.x - 0.5) * 2.0 * EXT.x * ESIZE, 0.0);
    } else if (m < 3.5) {
        p.xy = (h.xy * 2.0 - 1.0) * EXT * 1.05;
        outward = normalize(p.xy - E + 1e-4);
    } else if (m < 4.5) {
        float z = h.x * 2.0 - 1.0, a = h.y * TAU, rr = sqrt(1.0 - z * z);
        vec3 u = vec3(rr * cos(a), z, rr * sin(a));
        p = vec3(E, 0.0) + u * ESIZE;
        outward = u.xy;
    } else if (m < 5.5) {
        float x = h.x;
        float e = specFreqAt(x);
        live = step(g.w, e * 1.4 - 0.05);      // loud frequencies spawn, quiet ones do not
        energy = e;
        p.xy = E + vec2((x - 0.5) * 2.0 * EXT.x * ESIZE, 0.0);
    } else if (m < 6.5) {
        float a = h.x * TAU;
        float e = specFreqAt(fract(h.x));
        live = step(g.w, e * 1.4 - 0.05);
        energy = e;
        outward = vec2(sin(a), cos(a));
        p.xy = E + outward * ESIZE * (1.0 + e * 0.25);
    } else if (m < 7.5) {
        float side = floor(h.x * 4.0), t = h.y * 2.0 - 1.0;
        vec2 ext = EXT * 1.02;
        p.xy = side < 1.0 ? vec2(t * ext.x, ext.y) : side < 2.0 ? vec2(t * ext.x, -ext.y)
             : side < 3.0 ? vec2(ext.x, t * ext.y) : vec2(-ext.x, t * ext.y);
        outward = -normalize(p.xy);
    } else {
        float a = h.x * TAU, r = sqrt(h.y) * ESIZE;
        outward = vec2(cos(a), sin(a));
        p.xy = E + r * outward;
    }
    if (ZDEPTH > 0.0) p.z = -h.z * ZDEPTH;

    float dm = DIRMODE;
    vec2 d2;
    float jitter = (h.w - 0.5) * SPREAD * TAU;
    if (dm < 0.5) { float a = DIR * TAU + jitter; d2 = vec2(sin(a), cos(a)); }
    else if (dm < 1.5) d2 = rot2(jitter) * outward;
    else if (dm < 2.5) d2 = rot2(jitter) * vec2(-outward.y, outward.x);
    else if (dm < 3.5) d2 = rot2(jitter) * -outward;
    else { float a = g.x * TAU; d2 = vec2(cos(a), sin(a)); }
    float sp = SPEED * (1.0 + SPEEDVAR * (g.y * 2.0 - 1.0)) * (1.0 + AUDSPEED * energy);
    vec3 v = vec3(d2 * sp, ZSPEED * (0.6 + 0.8 * g.z));
    if (m > 3.5 && m < 4.5 && dm > 3.5) v = normalize(vec3(g.xyz) - 0.5) * sp;

    if (ATTRMODE > 0.5) {
        // Start anywhere near the attractor; the flow pulls it onto the strange shape.
        // Attractor z is "up" on screen, hence the .xzy swaps in and out.
        vec3 c = attractorCentre(ATTRMODE);
        q3 = c + (vec3(h.xyz) - 0.5) * attractorSpread(ATTRMODE);
        p = vec3(E, 0.0) + (q3 - c).xzy * attractorScale(ATTRMODE) * ATTRSIZE;
        v = vec3(0.0);
    }

    // Born part-way through this frame: older ones of the batch have already moved.
    float age = (1.0 - sub) * DT;
    p += v * age;
    float life = live > 0.5 ? age / lifeOf(id) : 1.5;
    s0 = vec4(p, life);
    s1 = vec4(v, energy);
}

void main() {
    ivec2 ij = ivec2(gl_FragCoord.xy);
    int W = textureSize(uState0, 0).x;
    float id = float(ij.y * W + ij.x);
    float N = uEmit.z;
    if (id >= N) { o0 = vec4(0.0, 0.0, 0.0, 2.0); o1 = vec4(0.0); return; }
    float rel = mod(id - uEmit.x + N, N);
    if (rel < uEmit.y) {
        float serial = mod(uEmit.w + rel, 1048576.0);
        spawn(serial, id, rel / max(uEmit.y, 1.0), o0, o1);
        return;
    }
    vec4 s0 = texelFetch(uState0, ij, 0);
    vec4 s1 = texelFetch(uState1, ij, 0);
    if (s0.w >= 1.0) { o0 = s0; o1 = s1; return; }

    float dt = DT;
    vec3 p = s0.xyz, v = s1.xyz;
    vec2 rel2 = p.xy - EPOS;
    float r = max(length(rel2), 1e-3);
    vec2 radial = rel2 / r;
    vec3 f = vec3(0.0, -GRAVITY, 0.0);
    f.x += WIND;
    f.xy += vec2(-radial.y, radial.x) * SWIRL / (0.3 + r);
    f.xy += -radial * ATTRACT;
    f.xy += vec2(-radial.y, radial.x) * ORBIT;
    if (CURL != 0.0) f.xy += curl2(vec3(p.xy * CSCALE, TIME * CSPEED + uSeed * 7.0)) * CURL;
    if (TURB > 0.0) f.xy += (hash22(vec2(id, floor(TIME * 24.0))) - 0.5) * TURB * 8.0;
    // Kick: one radial shove on the frame the kick lands.
    v.xy += radial * PUNCH * PUNCHAMT;
    v += f * dt;
    v *= exp(-DRAG * dt);

    if (ATTRMODE > 0.5) {
        float sc = attractorScale(ATTRMODE) * ATTRSIZE;
        vec3 c = attractorCentre(ATTRMODE);
        vec3 q = ((p - vec3(EPOS, 0.0)) / sc).xzy + c;
        float speed = (1.0 + SPEED) * (1.0 + AUDSPEED * (LEVEL + KICK * 0.5));
        // Midpoint step keeps the orbit on the attractor at phone frame rates.
        vec3 k1 = attractorFlow(q, ATTRMODE);
        vec3 k2 = attractorFlow(q + k1 * dt * speed * 0.5, ATTRMODE);
        q += k2 * dt * speed;
        // A kick throws them off the shape; the flow pulls them back into it.
        p = (q - c).xzy * sc + vec3(EPOS, 0.0) + vec3(radial, 0.0) * PUNCH * PUNCHAMT * 0.03;
        v = k2.xzy * sc * speed;
    } else {
        p += v * dt;
    }
    if (FLOOR != 0.0 && p.y < EPOS.y - FLOOR) { p.y = EPOS.y - FLOOR; v.y = abs(v.y) * 0.45; v.x *= 0.8; }

    float life = s0.w + dt / lifeOf(id);
    if (ZDEPTH > 0.0 && p.z > 0.9) life = 1.5;     // flew past the camera
    o0 = vec4(p, life);
    o1 = vec4(v, s1.w);
}
