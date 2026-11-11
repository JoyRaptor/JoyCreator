// Smooths the stair-steps that are left where two lit faces meet (FXAA, after Timothy Lottes).
// Runs on the finished screen picture; the outline itself is already soft from the field.
in vec2 vUv;
out vec4 o;
uniform sampler2D uSrc;
uniform vec2 uPx;   // one pixel, in uv
void main() {
    vec3 nw = texture(uSrc, vUv + vec2(-1.0, -1.0) * uPx).rgb, ne = texture(uSrc, vUv + vec2(1.0, -1.0) * uPx).rgb;
    vec3 sw = texture(uSrc, vUv + vec2(-1.0, 1.0) * uPx).rgb, se = texture(uSrc, vUv + vec2(1.0, 1.0) * uPx).rgb;
    vec3 mid = texture(uSrc, vUv).rgb;
    float lnw = luma(nw), lne = luma(ne), lsw = luma(sw), lse = luma(se), lm = luma(mid);
    float lo = min(lm, min(min(lnw, lne), min(lsw, lse))), hi = max(lm, max(max(lnw, lne), max(lsw, lse)));
    vec2 dir = vec2(-((lnw + lne) - (lsw + lse)), (lnw + lsw) - (lne + lse));
    float reduce = max((lnw + lne + lsw + lse) * 0.03125, 1.0 / 128.0);
    dir = clamp(dir / (min(abs(dir.x), abs(dir.y)) + reduce), vec2(-8.0), vec2(8.0)) * uPx;
    vec3 a = 0.5 * (texture(uSrc, vUv + dir * (1.0 / 3.0 - 0.5)).rgb + texture(uSrc, vUv + dir * (2.0 / 3.0 - 0.5)).rgb);
    vec3 b = a * 0.5 + 0.25 * (texture(uSrc, vUv - dir * 0.5).rgb + texture(uSrc, vUv + dir * 0.5).rgb);
    float lb = luma(b);
    o = vec4((lb < lo || lb > hi) ? a : b, 1.0);
}
