// PLEXUS: drifting nodes joined by lines when they come close. Every node position is a
// pure function of its id and time, so the whole network scrubs exactly. The host draws
// COUNT*COUNT instances: the first COUNT are nodes, the next COUNT*(COUNT-1)/2 are pairs.
out vec2 vQ;
out vec3 vCol;
out float vA;
out float vKind;   // 0 node, 1 link

#define COUNT    uP[0].x
#define LINKDIST uP[0].y
#define SPEEDN   uP[0].z
#define SPREADN  uP[0].w
#define CEN      uP[1].xy
#define NODESIZE uP[1].z
#define LINEW    uP[1].w
#define GLOW     uP[2].x
#define BRIGHTN  uP[2].y
#define KICKPUSH uP[2].z
#define PULSE    uP[2].w
#define CMODE    uP[3].x
#define PALSHIFT uP[3].y
#define GAIN     uP[3].z
#define DEPTH3D  uP[3].w
#define FLO      uP[4].x
#define FHI      uP[4].y
#define HUESPIN  uP[4].z
#define LINKAMP  uP[4].w

vec3 nodePos(float i, out float f) {
    vec3 h = hash31(i * 1.731 + uSeed);
    f = h.z;
    float t = TIME * SPEEDN * 0.12;
    vec2 base = (h.xy * 2.0 - 1.0) * EXT * SPREADN;
    vec2 wander = vec2(snoise(vec2(i * 3.1, t)), snoise(vec2(t, i * 5.7))) * 0.35 * SPREADN;
    vec2 p = base + wander;
    vec2 dir = normalize(p + 1e-4);
    p += dir * KICK * KICKPUSH * 0.12 * (0.5 + h.x);
    float z = (snoise(vec2(i * 0.77, t * 0.7)) * 0.5 + 0.5) * DEPTH3D;
    return vec3(CEN + p, z);
}

void main() {
    float n = max(2.0, floor(COUNT));
    float id = float(gl_InstanceID);
    int vtx = gl_VertexID % 6;
    vec2 c = (vtx == 0) ? vec2(-1, -1) : (vtx == 1) ? vec2(1, -1) : (vtx == 2) ? vec2(-1, 1)
           : (vtx == 3) ? vec2(-1, 1) : (vtx == 4) ? vec2(1, -1) : vec2(1, 1);
    vQ = c;
    if (id < n) {
        float f;
        vec3 p = nodePos(id, f);
        float e = spec(mix(FLO, FHI, f)) * GAIN;
        float size = NODESIZE * (0.6 + e * PULSE * 1.6) * (1.0 - p.z * 0.5);
        float ct = CMODE < 0.5 ? f : CMODE < 1.5 ? p.z : clamp(e, 0.0, 1.0);
        vCol = palw(ct * 0.5 + PALSHIFT + TIME * HUESPIN);
        vA = (0.5 + e) * BRIGHTN * (1.0 - p.z * 0.6);
        vKind = 0.0;
        gl_Position = vec4(placeOut(p.xy + c * size * (1.0 + GLOW * 2.0)) / EXT, 0.0, 1.0);
        return;
    }
    float l = id - n;
    float pairs = n * (n - 1.0) * 0.5;
    if (l >= pairs) { gl_Position = vec4(2.0, 2.0, 2.0, 1.0); return; }
    float a = floor((sqrt(8.0 * l + 1.0) - 1.0) * 0.5) + 1.0;
    float b = l - a * (a - 1.0) * 0.5;
    if (b >= a) { a += 1.0; b = l - a * (a - 1.0) * 0.5; }
    if (b < 0.0) { a -= 1.0; b = l - a * (a - 1.0) * 0.5; }
    float fa, fb;
    vec3 A = nodePos(a, fa);
    vec3 B = nodePos(b, fb);
    float dist = length(A.xy - B.xy);
    float near = 1.0 - smoothstep(LINKDIST * 0.45, LINKDIST, dist);
    if (near <= 0.001) { gl_Position = vec4(2.0, 2.0, 2.0, 1.0); return; }
    float e = 0.5 * (spec(mix(FLO, FHI, fa)) + spec(mix(FLO, FHI, fb))) * GAIN;
    vec2 d = B.xy - A.xy;
    vec2 dir = d / max(dist, 1e-5);
    vec2 nrm = vec2(-dir.y, dir.x);
    float hw = LINEW * 0.5 + GLOW * 0.012 + uPx;
    vec2 pos = mix(A.xy, B.xy, c.x * 0.5 + 0.5) + nrm * c.y * hw;
    vQ = vec2(c.x, c.y * hw);
    float ct = CMODE < 0.5 ? 0.5 * (fa + fb) : CMODE < 1.5 ? 0.5 * (A.z + B.z) : clamp(e, 0.0, 1.0);
    vCol = palw(ct * 0.5 + PALSHIFT + TIME * HUESPIN);
    vA = near * (0.25 + e * LINKAMP + KICK * 0.6) * BRIGHTN * (1.0 - 0.3 * (A.z + B.z));
    vKind = 1.0;
    gl_Position = vec4(placeOut(pos) / EXT, 0.0, 1.0);
}
