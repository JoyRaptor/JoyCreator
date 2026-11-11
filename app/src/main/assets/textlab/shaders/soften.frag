// Rounds the field: a blur of the distance, one direction per pass (run it across, then up).
// Blurring distances melts neighbours into each other and takes the knife edge off a bevel.
// The first pass may also wobble where it reads from, which makes the outline move like liquid.
in vec2 vUv;
out vec4 o;
uniform sampler2D uSrc;
uniform vec2  uStep;     // one field pixel along this pass's direction, in uv
uniform float uRadius;   // blur radius in field pixels
uniform vec4  uWobble;   // amount em, scale, speed, 1 = this pass wobbles
uniform vec2  uChip;     // crystal chips: how deep a chip bites (em), crystals per em
uniform float uGrow;     // crystal growth: how far a shard stands proud of the outline (em)
void main() {
    vec2 uv = vUv;
    if (uWobble.w > 0.5 && uWobble.x > 0.0) {
        vec2 p = uv * uField.xy;
        vec2 w = vec2(fbm2(p * uWobble.y + vec2(0.0, -uTime * uWobble.z)),
                      fbm2(p * uWobble.y + vec2(31.7, uTime * uWobble.z * 0.8))) - 0.5;
        uv += w * uWobble.x * 2.0 / uField.xy;
    }
    vec4 c = texture(uSrc, uv);
    if (uWobble.w > 0.5 && uChip.x + uGrow > 0.0) {
        // crystal chips: whole angular cells of the shape are bitten back, so the outline breaks in
        // straight facets (the same cells the look draws its fissures along, so a chip leads to a crack).
        // Other crystals have GROWN: they stand a little proud of the outline, as shards do.
        vec3 v = voronoi(uv * uField.xy * uChip.y);
        c.r -= uChip.x * smoothstep(0.5, 0.72, v.z) * (0.35 + 0.65 * v.z);
        c.r += uGrow * smoothstep(0.38, 0.12, v.z) * (1.0 - smoothstep(0.25, 0.6, v.x));
    }
    if (uRadius < 0.25) { o = c; return; }
    float sigma = uRadius * 0.5;
    float sum = c.r, wsum = 1.0;
    for (int i = 1; i <= 8; i++) {
        float x = float(i) * uRadius / 8.0;
        float w = exp(-0.5 * x * x / (sigma * sigma));
        sum += w * (texture(uSrc, uv + uStep * x).r + texture(uSrc, uv - uStep * x).r);
        wsum += 2.0 * w;
    }
    o = vec4(sum / wsum, c.gba);
}
