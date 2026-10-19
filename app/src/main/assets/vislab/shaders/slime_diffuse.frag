// SLIME, trail: spread a little (3x3 blur) and fade. One tick = 1/60 s, always.
in vec2 vUv;
in vec2 vPos;
out vec4 o;
uniform sampler2D uTrail;

#define DECAY    uP[1].z
#define DIFFUSE  uP[1].w

void main() {
    ivec2 ij = ivec2(gl_FragCoord.xy);
    ivec2 sz = textureSize(uTrail, 0);
    vec4 c = texelFetch(uTrail, ij, 0);
    vec4 sum = vec4(0.0);
    for (int y = -1; y <= 1; y++)
    for (int x = -1; x <= 1; x++) {
        ivec2 q = (ij + ivec2(x, y) + sz) % sz;
        sum += texelFetch(uTrail, q, 0);
    }
    vec4 blurred = sum / 9.0;
    vec4 v = mix(c, blurred, clamp(DIFFUSE, 0.0, 1.0)) * clamp(DECAY, 0.0, 1.0);
    o = min(v, vec4(50.0));
}
