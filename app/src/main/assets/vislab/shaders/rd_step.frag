// REACTION-DIFFUSION (the classic two-chemical model): two chemicals, one feeding on the other. Small changes
// to feed and kill grow coral, mazes, spots or mitosis. The bass nudges the feed, the kick
// drops new seeds, so the pattern grows WITH the song. One call = one 1/60 s tick.
in vec2 vUv;
in vec2 vPos;
out vec4 o;
uniform sampler2D uState;
uniform vec4 uSim;    // unused, init (1 = reset), tick seed, unused

#define FEED     uP[0].x
#define KILL     uP[0].y
#define ITERS    uP[0].z
#define KICKSEED uP[2].x
#define BANDFEED uP[2].y
#define SEEDSIZE uP[2].z
#define RINGSEED uP[2].w

void main() {
    ivec2 ij = ivec2(gl_FragCoord.xy);
    ivec2 sz = textureSize(uState, 0);
    vec2 uv = (vec2(ij) + 0.5) / vec2(sz);
    vec2 p = (uv * 2.0 - 1.0) * EXT;
    if (uSim.y > 0.5) {
        float b = 0.0;
        for (int i = 0; i < 12; i++) {
            vec2 c = (hash21(float(i) * 7.13 + uSeed) * 2.0 - 1.0) * EXT * 0.8;
            b += step(length(p - c), 0.05);
        }
        o = vec4(1.0, clamp(b, 0.0, 1.0), 0.0, 1.0);
        return;
    }
    vec2 s = texelFetch(uState, ij, 0).rg;
    vec2 lap = -s;
    lap += 0.2 * (texelFetch(uState, (ij + ivec2(1, 0) + sz) % sz, 0).rg
                + texelFetch(uState, (ij + ivec2(-1, 0) + sz) % sz, 0).rg
                + texelFetch(uState, (ij + ivec2(0, 1) + sz) % sz, 0).rg
                + texelFetch(uState, (ij + ivec2(0, -1) + sz) % sz, 0).rg);
    lap += 0.05 * (texelFetch(uState, (ij + ivec2(1, 1) + sz) % sz, 0).rg
                 + texelFetch(uState, (ij + ivec2(-1, 1) + sz) % sz, 0).rg
                 + texelFetch(uState, (ij + ivec2(1, -1) + sz) % sz, 0).rg
                 + texelFetch(uState, (ij + ivec2(-1, -1) + sz) % sz, 0).rg);
    float A = s.r, B = s.g;
    float f = FEED + BASS * BANDFEED * 0.008;
    float k = KILL;
    float abb = A * B * B;
    A += 1.0 * lap.r - abb + f * (1.0 - A);
    B += 0.5 * lap.g + abb - (k + f) * B;
    // Kick: a new seed somewhere fresh. Ring: the spectrum paints seeds on a circle.
    if (PUNCH > 0.0 && KICKSEED > 0.0) {
        vec2 c = (hash21(uSim.z * 0.37) * 2.0 - 1.0) * EXT * 0.8;
        B = max(B, step(length(p - c), SEEDSIZE * (0.5 + PUNCH)) * KICKSEED);
    }
    if (RINGSEED > 0.0) {
        float r = length(p);
        float u = fract(atan(p.x, p.y) / TAU);
        float e = spec(abs(u * 2.0 - 1.0));
        B = max(B, step(abs(r - 0.6), 0.008) * step(0.55, e) * RINGSEED * 0.5);
    }
    o = vec4(clamp(A, 0.0, 1.0), clamp(B, 0.0, 1.0), 0.0, 1.0);
}
