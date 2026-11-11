// Particles thrown off a shape: embers, sparks, glints, drops, dust.
//
// There is no stored state. Particle number i works out where it is from the clock alone: where
// on the shape it was born (a row of the EMITTER texture), which way it left, how old it is. So
// the same moment always draws the same picture: scrubbing and export agree with the preview.
//
// The emitter texture is a list of points on a shape (any shape: letters, a drawing, a sprite):
//   xy = the point, em     zw = the outward direction of the outline there
// rows 0..63 are points on the OUTLINE, rows 64..127 are points INSIDE the shape.
//
// Six vertices per particle, no buffers.
uniform sampler2D uEmit;
uniform sampler2D uFieldTex;
uniform sampler2D uMatcap;
uniform vec4  uPBirth;   // 0 = outline / 1 = inside, born this far out along the outline's direction (em), only where the outline faces up (0 off .. 1), share alive 0..1
uniform vec4  uPLife;    // seconds, size em, size at the end (x its start), stretch along its path
uniform vec4  uPMove;    // speed outward, rise, sideways drift, random speed   (em/s)
uniform vec4  uPForce;   // gravity (em/s2), swirl, swirl size, spin of the sparkle
uniform vec4  uPKind;    // shape: 0 soft dot, 1 four-point star, 2 round disc, 3 streak; twinkle 0..1; glint (1 = brightness follows the material sphere); seed
uniform vec3  uEnvCurve;
uniform float uEnvTurn;
uniform float uEnvTilt;
out vec2 vQ;        // -1..1 across the particle
out float vAge;     // 0 born .. 1 gone
out float vBright;
out float vRand;

void main() {
    int id = gl_VertexID / 6, c = gl_VertexID % 6;
    vec2 corner = vec2((c == 1 || c == 2 || c == 4) ? 1.0 : -1.0, (c == 2 || c == 4 || c == 5) ? 1.0 : -1.0);
    float fi = float(id) + uPKind.w * 131.0;
    vec4 r0 = hash41(fi * 1.37 + 0.5);
    float life = uPLife.x * (0.55 + 0.9 * r0.x);
    float clock = uTime / life + r0.y;
    float cycle = floor(clock);
    float age = fract(clock);
    vec4 r = hash41(fi * 0.731 + cycle * 17.3 + 3.1);

    int row = int(r.x * 63.999) + (uPBirth.x > 0.5 ? 64 : 0);
    int colm = int(r.y * 63.999);
    vec4 e = texelFetch(uEmit, ivec2(colm, row), 0);
    vec2 born = e.xy + e.zw * uPBirth.y * (0.3 + r.z);
    float reveal = textureLod(uFieldTex, e.xy / uField.xy, 0.0).g;

    float t = age * life;
    vec2 vel = e.zw * uPMove.x * (0.4 + r.w) + vec2((r.z - 0.5) * 2.0 * uPMove.z, uPMove.y * (0.5 + r.w))
             + (r0.zw - 0.5) * 2.0 * uPMove.w;
    vec2 sw = curl(born * uPForce.z + vec2(fi * 0.37, t * 0.35)) * uPForce.y;
    vec2 pos = born + vel * t + vec2(0.0, -0.5 * uPForce.x * t * t) + sw * t;
    vec2 velNow = vel + vec2(0.0, -uPForce.x * t) + sw;

    // alive: its share of the crowd, its letter has arrived, and (if asked) its edge faces the sky
    float alive = step(hash11(fi * 3.31 + 7.0), uPBirth.w) * step(0.5, reveal);
    alive *= mix(1.0, smoothstep(0.0, 0.4, e.w), uPBirth.z);

    float bright = 1.0;
    if (uPKind.z > 0.5) {
        // a glint lives where the material sphere is bright at this spot on the bevel
        vec2 cc = vec2(e.x / uField.x * 2.0 - 1.0, 0.0);
        vec2 m = rot(uEnvTurn) * (e.zw * 0.7 * uEnvCurve.z + cc * uEnvCurve.xy + uTilt * uEnvTilt);
        float l = length(m); if (l > 0.98) m *= 0.98 / l;
        float lum = luma(textureLod(uMatcap, m * 0.5 + 0.5, 0.0).rgb);
        bright = smoothstep(0.9, 2.2, lum);
    }
    float tw = mix(1.0, 0.5 + 0.5 * sin(uTime * (2.0 + 5.0 * r0.z) + fi), uPKind.y);
    bright *= tw * tw;

    float size = uPLife.y * (0.45 + 1.1 * r0.z) * mix(1.0, uPLife.z, age);
    vec2 dir = normalize(velNow + vec2(1e-5, 0.0));
    float stretch = 1.0 + uPLife.w * length(velNow);
    vec2 ax = int(uPKind.x + 0.5) == 3 ? dir : rot(uPForce.w * (uTime * (r0.x - 0.5) * 2.0 + fi)) * vec2(1.0, 0.0);
    vec2 ay = vec2(-ax.y, ax.x);
    vec2 off = ax * corner.x * size * stretch + ay * corner.y * size;
    vec2 P = pos + off * alive * step(1e-4, bright);

    vQ = corner; vAge = age; vBright = bright * alive; vRand = r0.z;
    gl_Position = vec4(P / uField.xy * 2.0 - 1.0, 0.0, 1.0);
}
