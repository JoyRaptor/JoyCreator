// Particles thrown off a shape (embers, sparks, glints, drops, dust, sand) and things that stay
// rooted on it (bolts of lightning, leaves, blossoms).
//
// There is no stored state. Particle number i works out where it is from the clock alone: where
// on the shape it was born (a row of the EMITTER texture), which way it left, how old it is. So
// the same moment always draws the same picture: scrubbing and export agree with the preview.
//
// The emitter texture is a list of points on a shape (any shape: letters, a drawing, a sprite):
//   xy = the point, em     zw = the outward direction of the outline there
// rows 0..63 are points on the OUTLINE, rows 64..127 points INSIDE the shape, rows 128..191 its
// CORNERS (where a made object catches a glint).
//
// Six vertices per particle, no buffers.
uniform sampler2D uEmit;
uniform sampler2D uFieldTex;
uniform sampler2D uMatcap;
uniform vec4  uPBirth;   // born: 0 on the outline, 1 inside, 2 anywhere in the picture (dust), 3 on corners; this far out along the outline's direction (em); only where the outline faces up (0 off .. 1); share alive 0..1
uniform vec4  uPLife;    // seconds, size em, size at the end (x its start), stretch along its path
uniform vec4  uPMove;    // speed outward, rise, sideways drift (for those born at the front: the wind, blowing the front's way), random speed   (em/s)
uniform vec4  uPForce;   // gravity (em/s2), swirl (for a leaf: its flutter), swirl size, spin
uniform vec4  uPKind;    // shape: 0 soft dot, 1 lens glint, 2 drop, 3 streak, 4 soft disc, 5 bolt, 6 leaf, 7 blossom; twinkle 0..1; 1 = brightness follows the material sphere; seed
uniform float uPFront;   // 1 = it is born as the FRONT (common.glsl) passes its birthplace
uniform vec3  uEnvCurve;
uniform float uEnvTurn;
uniform float uEnvTilt;
out vec2 vQ;        // -1..1 across the particle
out float vAge;     // 0 born .. 1 gone
out float vBright;
out float vRand;
out float vSeed;
out vec2 vAux;      // a leaf or blossom: where its shadow falls, in vQ units

void main() {
    int id = gl_VertexID / 6, c = gl_VertexID % 6;
    vec2 corner = vec2((c == 1 || c == 2 || c == 4) ? 1.0 : -1.0, (c == 2 || c == 4 || c == 5) ? 1.0 : -1.0);
    int kind = int(uPKind.x + 0.5);
    bool rooted = kind == 6 || kind == 7;       // leaves and blossoms stay where they grew
    bool atFront = uPFront > 0.5;
    float fi = float(id) + uPKind.w * 131.0;
    vec4 r0 = hash41(fi * 1.37 + 0.5);
    float life = uPLife.x * (0.55 + 0.9 * r0.x);
    float clock = uTime / life + r0.y;
    float cycle = (atFront || rooted) ? 0.0 : floor(clock);     // those keep one birthplace
    float age = fract(clock);
    vec4 r = hash41(fi * 0.731 + cycle * 17.3 + 3.1);

    int mode = int(uPBirth.x + 0.5);
    int row = int(r.x * 63.999) + (mode == 1 ? 64 : mode == 3 ? 128 : 0);
    vec4 e = texelFetch(uEmit, ivec2(int(r.y * 63.999), row), 0);
    float reveal = textureLod(uFieldTex, e.xy / uField.xy, 0.0).g;
    if (mode == 2) { e = vec4(r.xy * uField.xy, normalize(r.zw - 0.5 + vec2(1e-3, 0.0))); reveal = 1.0; }
    vec2 born = e.xy + e.zw * uPBirth.y * (0.3 + r.z);

    // alive: its share of the crowd, its letter has arrived, and (if asked) its edge faces the sky
    float alive = step(hash11(fi * 3.31 + 7.0), uPBirth.w) * step(0.5, reveal);
    alive *= mix(1.0, smoothstep(0.0, 0.4, e.w), uPBirth.z);
    // born as the front passes: its age is how far the front has gone on since
    float past = 1.0;
    if (atFront) {
        past = frontGone() - frontBase(e.xy) / 1.4;
        if (!rooted) {
            float per = uFront.y > 0.0 ? uFront.y * 0.128 * life : 0.12;
            age = past / per;
            alive *= step(0.0, age) * step(age, 1.0);
            age = clamp(age, 0.0, 1.0);
        }
    }

    float t = age * life;
    vec2 wind = atFront ? vec2(cos(uFront.w * TAU), sin(uFront.w * TAU)) * uPMove.z * (0.5 + r.z)
                        : vec2((r.z - 0.5) * 2.0 * uPMove.z, 0.0);
    vec2 vel = e.zw * uPMove.x * (0.4 + r.w) + wind + vec2(0.0, uPMove.y * (0.5 + r.w)) + (r0.zw - 0.5) * 2.0 * uPMove.w;
    vec2 sw = tcurl(born * uPForce.z + vec2(fi * 0.37, t * 0.35)) * uPForce.y;
    vec2 pos = born + vel * t + vec2(0.0, -0.5 * uPForce.x * t * t) + sw * t;
    vec2 velNow = vel + vec2(0.0, -uPForce.x * t) + sw;

    float bright = 1.0;
    if (uPKind.z > 0.5) {
        // a glint lives where the material sphere is bright at this spot on the bevel
        vec2 cc = vec2(e.x / uField.x * 2.0 - 1.0, 0.0);
        vec2 m = rot(uEnvTurn) * (e.zw * 0.7 * uEnvCurve.z + cc * uEnvCurve.xy + uTilt * uEnvTilt);
        float l = length(m); if (l > 0.97) m *= 0.97 / l;
        float lum = luma(textureLod(uMatcap, m * 0.5 + 0.5, 0.0).rgb);
        bright = smoothstep(1.1, 3.0, lum);
    }
    float tw = mix(1.0, 0.5 + 0.5 * sin(uTime * (1.5 + 4.0 * r0.z) + fi), uPKind.y);
    bright *= tw * tw;

    float size = uPLife.y * (0.45 + 1.1 * r0.z) * mix(1.0, uPLife.z, age);
    vec2 dir = normalize(velNow + vec2(1e-5, 0.0));
    float stretch = 1.0 + uPLife.w * length(velNow);
    vec2 ax = kind == 3 ? dir : rot(uPForce.w * (uTime * (r0.x - 0.5) * 2.0 + fi)) * vec2(1.0, 0.0);
    vec2 ay = vec2(-ax.y, ax.x);
    vec2 off = ax * corner.x * size * stretch + ay * corner.y * size;
    vec2 P = pos + off * alive * step(1e-4, bright);
    vQ = corner; vAux = vec2(0.0);
    if (kind == 5) {
        // a BOLT does not travel: it stands on the outline where it was born and leaps outward
        // (gravity pulls it toward the ground). Its size is its length.
        vec2 bd = normalize(rot((r.w - 0.5) * 1.7) * e.zw + vec2(1e-4, -uPForce.x * 0.15));
        float len = uPLife.y * (0.45 + 1.1 * r0.z);
        P = e.xy + (bd * (corner.x * 0.5 + 0.5) + vec2(-bd.y, bd.x) * corner.y * 0.4) * len * alive;
    }
    if (rooted) {
        // a LEAF or a BLOSSOM grows where it is rooted and stays: out from the outline, splayed to one
        // side, drooping a little, stirring in the air. It opens once the front has passed it.
        float grow = atFront ? smoothstep(kind == 7 ? 0.08 : 0.0, kind == 7 ? 0.22 : 0.12, past) : 1.0;
        vec2 out0 = (mode == 0 || mode == 3) ? e.zw : normalize(r.zw - 0.5 + vec2(1e-3, 0.0));
        float flutter = sin(uTime * (1.1 + r0.x) + fi) * 0.09 * uPForce.y;
        vec2 bd = normalize(rot((r.w - 0.5) * 2.4 + flutter) * out0 + vec2(1e-4, -0.3));
        vec2 by = vec2(-bd.y, bd.x);
        float len = max(uPLife.y * (0.5 + 1.0 * r0.z) * grow, 1e-4);
        vec2 cq = corner * 1.3;                                  // room round it for its shadow
        P = born + (bd * (kind == 6 ? cq.x * 0.5 + 0.5 : cq.x * 0.5) + by * cq.y * 0.5) * len * alive * step(1e-3, grow);
        vec2 sh = vec2(0.011, -0.015) / len;                      // the shadow lies down and to the right
        vQ = cq; vAux = 2.0 * vec2(dot(sh, bd), dot(sh, by));
    }

    vAge = age; vBright = bright * alive; vRand = r0.z; vSeed = r.x * 37.0 + r.y * 11.0;
    gl_Position = vec4(P / uField.xy * 2.0 - 1.0, 0.0, 1.0);
}
