// Dual-filter upsample. Drawn with additive blending into the next larger level.
in vec2 vUv;
in vec2 vPos;
out vec4 o;
uniform sampler2D uSrc;
uniform vec2 uHalf;        // half a SOURCE texel, in uv
uniform float uWeight;
void main() {
    vec2 h = uHalf;
    vec3 s = texture(uSrc, vUv + vec2(-h.x * 2.0, 0.0)).rgb;
    s += texture(uSrc, vUv + vec2(-h.x, h.y)).rgb * 2.0;
    s += texture(uSrc, vUv + vec2(0.0, h.y * 2.0)).rgb;
    s += texture(uSrc, vUv + vec2(h.x, h.y)).rgb * 2.0;
    s += texture(uSrc, vUv + vec2(h.x * 2.0, 0.0)).rgb;
    s += texture(uSrc, vUv + vec2(h.x, -h.y)).rgb * 2.0;
    s += texture(uSrc, vUv + vec2(0.0, -h.y * 2.0)).rgb;
    s += texture(uSrc, vUv + vec2(-h.x, -h.y)).rgb * 2.0;
    o = vec4(s * (uWeight / 12.0), 1.0);
}
