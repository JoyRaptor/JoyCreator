// REACTION-DIFFUSION, show: the pattern lit from one side so it reads as embossed, carved
// relief, then mapped through the palette.
in vec2 vUv;
in vec2 vPos;
out vec4 o;
uniform sampler2D uState;

#define BRIGHTN  uP[1].x
#define PALSHIFT uP[1].y
#define HUESPIN  uP[1].z
#define EMBOSS   uP[1].w
#define PAINT    uP[3].x   // 0 glowing lines, 1 solid relief that hides what is behind

void main() {
    // Moved / sized / turned (placeIn): the grid ends where it ends; nothing beyond it.
    if (any(lessThan(vUv, vec2(0.0))) || any(greaterThan(vUv, vec2(1.0)))) { o = vec4(0.0); return; }
    vec2 px = 1.0 / vec2(textureSize(uState, 0));
    float b = texture(uState, vUv).g;
    float bx = texture(uState, vUv + vec2(px.x, 0.0)).g - texture(uState, vUv - vec2(px.x, 0.0)).g;
    float by = texture(uState, vUv + vec2(0.0, px.y)).g - texture(uState, vUv - vec2(0.0, px.y)).g;
    vec3 n = normalize(vec3(-bx * EMBOSS * 6.0, -by * EMBOSS * 6.0, 1.0));
    float lit = 0.35 + 0.65 * max(dot(n, normalize(vec3(-0.5, 0.6, 0.6))), 0.0);
    float edge = smoothstep(0.12, 0.32, b);
    vec3 c = palw(b * 0.8 + PALSHIFT + TIME * HUESPIN);
    vec3 col = c * edge * lit * 1.3;
    float rim = pow(1.0 - n.z, 1.5) * 2.0;
    col += palw(0.9 + PALSHIFT) * rim * edge;
    o = lightOut(col * BRIGHTN, edge * PAINT);
}
