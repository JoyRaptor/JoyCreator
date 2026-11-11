// Dual-filter bloom, the way back up: a tent of eight taps, added to the level above.
in vec2 vUv;
out vec4 o;
uniform sampler2D uSrc;
uniform vec2 uHalf;        // half a SOURCE texel, in uv
void main() {
    vec3 s = texture(uSrc, vUv + vec2(-uHalf.x * 2.0, 0.0)).rgb;
    s += texture(uSrc, vUv + vec2(uHalf.x * 2.0, 0.0)).rgb;
    s += texture(uSrc, vUv + vec2(0.0, -uHalf.y * 2.0)).rgb;
    s += texture(uSrc, vUv + vec2(0.0, uHalf.y * 2.0)).rgb;
    s += texture(uSrc, vUv + vec2(-uHalf.x, uHalf.y)).rgb * 2.0;
    s += texture(uSrc, vUv + vec2(uHalf.x, uHalf.y)).rgb * 2.0;
    s += texture(uSrc, vUv + vec2(-uHalf.x, -uHalf.y)).rgb * 2.0;
    s += texture(uSrc, vUv + vec2(uHalf.x, -uHalf.y)).rgb * 2.0;
    o = vec4(s / 12.0, 1.0);
}
