// PARTICLES, draw: one quad per particle, read straight from the state texture.
// Sparks are stretched along their screen velocity, which is real motion blur for free.
uniform sampler2D uState0;
uniform sampler2D uState1;
out vec2 vQ;
out vec3 vCol;
out float vA;
out float vLook;

#define EPOS     uP[0].yz
#define SPEED    uP[1].x
#define SIZE     uP[6].x
#define SIZEVAR  uP[6].y
#define SIZELIFE uP[6].z
#define STRETCH  uP[6].w
#define LOOK     uP[7].x
#define BRIGHTN  uP[7].y
#define TWINKLE  uP[7].z
#define CMODE    uP[7].w
#define PERSP    uP[8].x
#define DEPTHFADE uP[8].y
#define FADEIN   uP[8].z
#define FADEOUT  uP[8].w
#define PALSHIFT uP[9].x
#define HUESPIN  uP[9].y
#define YAW      uP[11].x
#define PITCH    uP[11].y
#define VIEWD    uP[11].z

vec3 view(vec3 p) {
    vec3 q = p - vec3(EPOS, 0.0);
    float yaw = YAW * TIME * TAU;
    q.xz = rot2(yaw) * q.xz;
    q.yz = rot2(PITCH * TAU) * q.yz;
    return q;
}

vec2 project(vec3 q, out float s) {
    float d = max(VIEWD, 0.2);
    s = mix(1.0, d / max(d - q.z, 0.05), PERSP);
    return vec2(EPOS) + q.xy * s;
}

void main() {
    int id = gl_InstanceID;
    int W = textureSize(uState0, 0).x;
    ivec2 ij = ivec2(id % W, id / W);
    vec4 s0 = texelFetch(uState0, ij, 0);
    vec4 s1 = texelFetch(uState1, ij, 0);
    float lf = s0.w;
    if (lf >= 1.0 || lf < 0.0) { gl_Position = vec4(2.0, 2.0, 2.0, 1.0); return; }

    int v = gl_VertexID % 6;
    vec2 c = (v == 0) ? vec2(-1, -1) : (v == 1) ? vec2(1, -1) : (v == 2) ? vec2(-1, 1)
           : (v == 3) ? vec2(-1, 1) : (v == 4) ? vec2(1, -1) : vec2(1, 1);

    float h = hash11(float(id) * 0.7131 + uSeed);
    float s;
    vec3 q = view(s0.xyz);
    vec2 sp = project(q, s);
    float size = SIZE * (1.0 + SIZEVAR * (h * 2.0 - 1.0));
    size *= SIZELIFE >= 0.0 ? (1.0 - SIZELIFE * lf) : (1.0 - SIZELIFE * lf * 2.0);
    size *= s;
    float a = smoothstep(0.0, max(FADEIN, 1e-3), lf) * (1.0 - smoothstep(1.0 - max(FADEOUT, 1e-3), 1.0, lf));
    a *= mix(1.0, clamp(s * 0.6, 0.0, 1.0), DEPTHFADE);
    if (TWINKLE > 0.0) a *= 1.0 - TWINKLE * (0.5 + 0.5 * sin(TIME * (6.0 + h * 14.0) + h * 40.0));
    if (LOOK > 5.5 && LOOK < 6.5) a *= 0.65 + 0.35 * sin(TIME * (17.0 + h * 9.0) + h * 50.0);
    // Never smaller than ~1.5 px: shrink the energy instead, so tiny sparks don't shimmer.
    float minS = uPx * 1.5;
    if (size < minS) { a *= (size * size) / (minS * minS); size = minS; }

    float speed = length(s1.xyz);
    float ct = CMODE < 0.5 ? lf : CMODE < 1.5 ? clamp(speed / (abs(SPEED) * 2.0 + 0.05), 0.0, 1.0)
             : CMODE < 2.5 ? h : CMODE < 3.5 ? clamp(sp.x / EXT.x * 0.5 + 0.5, 0.0, 1.0) : clamp(s1.w, 0.0, 1.0);
    vec3 col = palw(ct * 0.5 + PALSHIFT + TIME * HUESPIN);
    if (LOOK > 5.5 && LOOK < 6.5) col = mix(col, vec3(1.0, 0.75, 0.4), 0.25 * s1.w);

    vec2 pos;
    float isStreak = (LOOK > 0.5 && LOOK < 1.5) || LOOK > 7.5 ? 1.0 : 0.0;
    if (isStreak > 0.5 && STRETCH > 0.0) {
        float st;
        vec2 tail = project(view(s0.xyz - s1.xyz * STRETCH * 0.06), st);
        vec2 d = sp - tail;
        float len = length(d);
        vec2 dir = len > 1e-5 ? d / len : vec2(0.0, 1.0);
        vec2 nrm = vec2(-dir.y, dir.x);
        vec2 mid = (sp + tail) * 0.5;
        float half_ = len * 0.5 + size;
        pos = mid + dir * c.x * half_ + nrm * c.y * size;
        // Energy spreads along the streak: long streaks are dimmer per pixel.
        a *= clamp(size * 2.0 / (len + size * 2.0) * 2.0, 0.15, 1.0);
    } else {
        pos = sp + c * size;
    }
    vQ = c;
    vCol = col;
    vA = a * BRIGHTN;
    vLook = LOOK;
    gl_Position = vec4(placeOut(pos) / EXT, 0.0, 1.0);
}
