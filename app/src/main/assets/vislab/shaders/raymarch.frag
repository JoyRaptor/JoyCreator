// RAYMARCH: real 3D, traced per pixel. Liquid blobs that merge, an endless gyroid cave
// you fly through, a folding fractal, a ring tower. The glow is gathered along each ray,
// which is the neon-in-fog look; the bass drives the shapes.
in vec2 vUv;
in vec2 vPos;
out vec4 o;

#define MODE     uP[0].x   // 0 blobs, 1 gyroid cave, 2 fractal, 3 rings
#define SPEEDF   uP[0].y
#define BRIGHTN  uP[0].z
#define PALSHIFT uP[0].w
#define STEPS    uP[1].x
#define ZOOM     uP[1].y
#define REACT    uP[1].z
#define GLOW     uP[1].w
#define HUESPIN  uP[2].x
#define FOG      uP[2].y
#define SPINR    uP[2].z
#define SURFACE  uP[2].w   // how much of the solid surface shows (vs only glow)

float smin(float a, float b, float k) {
    float h = clamp(0.5 + 0.5 * (b - a) / k, 0.0, 1.0);
    return mix(b, a, h) - k * h * (1.0 - h);
}

float mapScene(vec3 p, out float tag) {
    float m = MODE;
    float t = TIME * SPEEDF;
    tag = 0.0;
    if (m < 0.5) {
        float d = 1e5;
        for (int i = 0; i < 6; i++) {
            float fi = float(i);
            float band = i == 0 ? BASS : i == 1 ? LOW : i == 2 ? MID : i == 3 ? HIGH : i == 4 ? AIR : LEVEL;
            vec3 c = vec3(sin(t * 0.7 + fi * 1.9), cos(t * 0.6 + fi * 2.3), sin(t * 0.5 + fi * 3.1)) * 0.75;
            float r = 0.32 + band * REACT * 0.35 + (i == 0 ? KICK * 0.12 : 0.0);
            float s = length(p - c) - r;
            if (s < d) tag = fi / 6.0;
            d = smin(d, s, 0.35);
        }
        return d;
    }
    if (m < 1.5) {
        vec3 q = p * 2.2;
        float g = abs(dot(sin(q), cos(q.yzx))) / 2.2;
        float thick = 0.06 + BASS * REACT * 0.05;
        tag = fract(p.z * 0.1);
        return g - thick;
    }
    if (m < 2.5) {
        vec3 q = p;
        float s = 1.0;
        float rot = t * 0.2 + BASSACC * 0.1;
        for (int i = 0; i < 6; i++) {
            q = abs(q) - vec3(0.55 + BASS * REACT * 0.1, 0.35, 0.25);
            q.xy = rot2(rot) * q.xy;
            q.yz = rot2(rot * 0.7 + 0.4) * q.yz;
            q *= 1.45;
            s *= 1.45;
        }
        tag = fract(length(q) * 0.05);
        return (length(q) - 1.2) / s;
    }
    // Rings: a stack of tori, each spinning with its own band.
    float d = 1e5;
    for (int i = 0; i < 5; i++) {
        float fi = float(i);
        vec3 q = p - vec3(0.0, (fi - 2.0) * 0.38, 0.0);
        q.xz = rot2(t * (0.3 + fi * 0.15)) * q.xz;
        q.xy = rot2(0.3 * sin(t + fi)) * q.xy;
        float R = 0.55 + spec(fi / 5.0) * REACT * 0.3;
        float tor = length(vec2(length(q.xz) - R, q.y)) - 0.045;
        if (tor < d) tag = fi / 5.0;
        d = min(d, tor);
    }
    return d;
}

void main() {
    vec2 uv = vPos / ZOOM;
    float t = TIME * SPEEDF;
    vec3 ro, rd;
    if (MODE > 0.5 && MODE < 1.5) {
        float z = t * 0.6 + BASSACC * 0.6;
        ro = vec3(0.3 * sin(t * 0.3), 0.3 * cos(t * 0.25), z);
        rd = normalize(vec3(uv, 1.2));
        rd.xy = rot2(t * 0.1) * rd.xy;
    } else {
        float yaw = SPINR * t;
        ro = vec3(sin(yaw) * 3.2, 0.4, cos(yaw) * 3.2);
        vec3 fw = normalize(-ro);
        vec3 rt = normalize(cross(vec3(0.0, 1.0, 0.0), fw));
        vec3 up = cross(fw, rt);
        rd = normalize(fw * 1.6 + rt * uv.x + up * uv.y);
    }
    int steps = int(clamp(STEPS, 16.0, 96.0));
    float dist = 0.0;
    float glow = 0.0;
    float tag = 0.0;
    bool hit = false;
    vec3 p = ro;
    for (int i = 0; i < 96; i++) {
        if (i >= steps) break;
        p = ro + rd * dist;
        float d = mapScene(p, tag);
        glow += exp(-max(d, 0.0) * 12.0) * 0.04;
        if (d < 0.001 * (1.0 + dist)) { hit = true; break; }
        dist += d * 0.85;
        if (dist > 12.0) break;
    }
    vec3 col = palw(tag * 0.5 + PALSHIFT + TIME * HUESPIN) * glow * GLOW * (0.6 + LEVEL * 0.8);
    float cover = 0.0;
    if (hit) {
        float tg;
        vec2 e = vec2(0.0015, 0.0);
        vec3 n = normalize(vec3(mapScene(p + e.xyy, tg) - mapScene(p - e.xyy, tg),
                                mapScene(p + e.yxy, tg) - mapScene(p - e.yxy, tg),
                                mapScene(p + e.yyx, tg) - mapScene(p - e.yyx, tg)));
        float fres = pow(1.0 - abs(dot(n, -rd)), 3.0);
        float dif = max(dot(n, normalize(vec3(0.4, 0.8, -0.3))), 0.0);
        vec3 base = palw(tag * 0.5 + PALSHIFT + TIME * HUESPIN + dot(n, vec3(0.2, 0.3, 0.1)));
        vec3 surf = base * (0.15 + dif * 0.6) + palw(0.95 + PALSHIFT) * fres * 1.4;
        float fog = exp(-dist * FOG * 0.15);
        col += surf * SURFACE * fog;
        cover = SURFACE * fog;
    }
    o = lightOut(col * BRIGHTN, cover);
}
