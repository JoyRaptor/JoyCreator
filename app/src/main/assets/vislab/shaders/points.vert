// POINTS: a 3D cloud of glowing points whose shape is a pure function of time and sound,
// so it scrubs perfectly. Sphere, torus, galaxy, terrain, helix, lattice, vinyl, tunnel.
out vec2 vQ;
out vec3 vCol;
out float vA;
out float vBlur;

#define FORM     uP[0].x   // 0 sphere, 1 torus, 2 galaxy, 3 terrain, 4 helix, 5 lattice, 6 vinyl, 7 tunnel
#define COUNT    uP[0].y
#define RADIUS   uP[0].z
#define SPINR    uP[0].w   // turns per second
#define CEN      uP[1].xy
#define TILT     uP[1].z   // turns
#define PERSP    uP[1].w
#define DISPLACE uP[2].x
#define WOBBLE   uP[2].y
#define KICKPUMP uP[2].z
#define ZOOM     uP[2].w
#define SIZE     uP[3].x
#define BRIGHTN  uP[3].y
#define DOF      uP[3].z
#define DEPTHFADE uP[3].w
#define CMODE    uP[4].x   // 0 frequency, 1 depth, 2 random, 3 loudness
#define PALSHIFT uP[4].y
#define TWINKLE  uP[4].z
#define FOCUS    uP[4].w
#define GAIN     uP[5].x
#define FLO      uP[5].y
#define FHI      uP[5].z
#define SPINBASS uP[5].w   // extra spin that only moves while the bass plays
#define HUESPIN  uP[6].x
#define ARMS     uP[6].y

// Rest position of point i (about unit size) and the frequency it listens to.
vec3 formPoint(float i, float n, out float f, out float shade) {
    float h = hash11(i * 0.3171 + uSeed);
    float fm = FORM;
    shade = h;
    if (fm < 0.5) {
        float y = 1.0 - 2.0 * (i + 0.5) / n;
        float rr = sqrt(max(0.0, 1.0 - y * y));
        float phi = i * 2.39996323;
        f = fract(i * 0.6180339);   // frequencies wind round the sphere in a spiral
        return vec3(cos(phi) * rr, y, sin(phi) * rr);
    }
    if (fm < 1.5) {
        float ring = floor(sqrt(n));
        float u = floor(i / ring) / ring * TAU, v = mod(i, ring) / ring * TAU;
        f = abs(sin(u * 0.5));
        float R = 0.7, r = 0.3;
        return vec3((R + r * cos(v)) * cos(u), r * sin(v), (R + r * cos(v)) * sin(u));
    }
    if (fm < 2.5) {
        float arms = max(1.0, floor(ARMS));
        float rad = pow(h, 0.55);
        float arm = floor(hash11(i * 1.91) * arms);
        float ang = arm * TAU / arms + rad * 4.2 + (hash11(i * 7.7) - 0.5) * 0.6 * (1.0 - rad * 0.5);
        f = rad;
        float thick = (hash11(i * 3.3) - 0.5) * 0.12 * (1.0 - rad);
        return vec3(cos(ang) * rad, thick, sin(ang) * rad);
    }
    if (fm < 3.5) {
        float cols = floor(sqrt(n * 2.0));
        float rows = floor(n / cols);
        float cx = mod(i, cols) / (cols - 1.0);
        float rz = floor(i / cols) / max(rows - 1.0, 1.0);
        f = abs(cx * 2.0 - 1.0);
        shade = rz;
        return vec3((cx * 2.0 - 1.0) * 1.6, 0.0, (rz * 2.0 - 1.0) * 1.6);
    }
    if (fm < 4.5) {
        float t = i / n;
        float strand = mod(i, 2.0);
        float a = t * TAU * 4.0 + strand * PI;
        f = t;
        return vec3(cos(a) * 0.45, (t * 2.0 - 1.0) * 1.1, sin(a) * 0.45);
    }
    if (fm < 5.5) {
        float side = floor(pow(n, 1.0 / 3.0) + 0.5);
        vec3 g = vec3(mod(i, side), mod(floor(i / side), side), floor(i / (side * side))) / max(side - 1.0, 1.0);
        vec3 pp = g * 2.0 - 1.0;
        f = clamp(length(pp) / 1.732, 0.0, 1.0);
        return pp * 0.75;
    }
    if (fm < 6.5) {
        float rings = 48.0;
        float ring = floor(h * rings);
        float a = hash11(i * 5.1) * TAU;
        float rad = 0.25 + 0.75 * ring / rings;
        f = ring / rings;
        return vec3(cos(a) * rad, 0.0, sin(a) * rad);
    }
    // Tunnel: rings of points running toward the camera forever.
    float ringsN = 40.0;
    float per = max(1.0, floor(n / ringsN));
    float ring = floor(i / per);
    float a = mod(i, per) / per * TAU;
    float z = fract(ring / ringsN + TIME * 0.12 + BASSACC * 0.05);
    f = mod(i, per) / per;
    shade = z;
    return vec3(cos(a), sin(a), (z * 2.0 - 1.0) * 3.0);
}

void main() {
    float n = max(1.0, floor(COUNT));
    float i = float(gl_InstanceID);
    int vtx = gl_VertexID % 6;
    vec2 c = (vtx == 0) ? vec2(-1, -1) : (vtx == 1) ? vec2(1, -1) : (vtx == 2) ? vec2(-1, 1)
           : (vtx == 3) ? vec2(-1, 1) : (vtx == 4) ? vec2(1, -1) : vec2(1, 1);
    float f, shade;
    vec3 p = formPoint(i, n, f, shade);
    float freq = mix(FLO, FHI, f);
    float e = spec(freq) * GAIN;
    float fm = FORM;

    // Sound pushes the form: outward for closed shapes, upward for flat ones.
    if (fm > 2.5 && fm < 3.5) {
        float hv = hist(freq, shade) * GAIN;
        p.y += hv * DISPLACE * 0.9 - 0.35;
        e = hv;
    } else if (fm > 5.5 && fm < 6.5) {
        p.y += e * DISPLACE * 0.5 * (0.6 + 0.4 * sin(atan(p.z, p.x) * 6.0 + TIME * 2.0));
    } else if (fm > 6.5) {
        p.xy *= 1.0 + e * DISPLACE * 0.4;
    } else {
        bool flat_ = fm > 1.5 && fm < 2.5;
        vec3 dir = flat_ ? vec3(0.0, 1.0, 0.0) : normalize(p + 1e-4);
        p += dir * (flat_ ? e * DISPLACE * 0.25 : e * DISPLACE * 0.5);
    }
    if (WOBBLE > 0.0) {
        p += WOBBLE * 0.08 * vec3(snoise(p * 2.0 + TIME * 0.4),
                                  snoise(p * 2.0 + 13.1 + TIME * 0.4),
                                  snoise(p * 2.0 + 27.7 + TIME * 0.4));
    }
    p *= 1.0 + KICK * KICKPUMP * 0.25;

    float yaw = (SPINR * TIME + SPINBASS * BASSACC) * TAU;
    p.xz = rot2(yaw) * p.xz;
    p.yz = rot2(TILT * TAU) * p.yz;
    p *= RADIUS * ZOOM;

    float d = 2.4;
    float s = mix(1.0, d / max(d - p.z, 0.08), PERSP);
    vec2 sp = CEN + p.xy * s;
    float depth = clamp((p.z / max(RADIUS * ZOOM, 1e-3)) * 0.5 + 0.5, 0.0, 1.0);

    float blur = DOF * abs(depth - FOCUS) * 3.0;
    float size = SIZE * s * (0.7 + e * 0.8) * (1.0 + blur);
    float a = (0.35 + e * 1.3) / ((1.0 + blur) * (1.0 + blur));
    a *= mix(1.0, 0.25 + 0.75 * depth, DEPTHFADE);
    if (TWINKLE > 0.0) a *= 1.0 - TWINKLE * (0.5 + 0.5 * sin(TIME * 9.0 + i * 1.7));
    float minS = uPx * 1.2;
    if (size < minS) { a *= (size * size) / (minS * minS); size = minS; }

    float ct = CMODE < 0.5 ? f : CMODE < 1.5 ? depth : CMODE < 2.5 ? shade : clamp(e, 0.0, 1.0);
    vCol = palw(ct * 0.5 + PALSHIFT + TIME * HUESPIN);
    vA = a * BRIGHTN;
    vBlur = clamp(blur, 0.0, 1.0);
    vQ = c;
    gl_Position = vec4(placeOut(sp + c * size) / EXT, 0.0, 1.0);
}
