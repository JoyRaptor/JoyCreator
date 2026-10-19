// Dual-filter bloom (Bjorge, ARM, SIGGRAPH 2015): built for phone GPUs.
// uPrefilter > 0 on the first pass only: keeps just the light above the threshold, soft knee.
in vec2 vUv;
in vec2 vPos;
out vec4 o;
uniform sampler2D uSrc;
uniform vec2 uHalf;        // half a SOURCE texel, in uv
uniform vec4 uBloomCfg;    // threshold, knee, prefilter (0/1), unused
void main() {
    vec3 s = texture(uSrc, vUv).rgb * 4.0;
    s += texture(uSrc, vUv - uHalf).rgb;
    s += texture(uSrc, vUv + uHalf).rgb;
    s += texture(uSrc, vUv + vec2(uHalf.x, -uHalf.y)).rgb;
    s += texture(uSrc, vUv - vec2(uHalf.x, -uHalf.y)).rgb;
    s *= 0.125;
    if (uBloomCfg.z > 0.5) {
        float br = max3(s);
        float th = uBloomCfg.x;
        float kn = max(uBloomCfg.y, 1e-4);
        float soft = clamp(br - th + kn, 0.0, 2.0 * kn);
        soft = soft * soft / (4.0 * kn);
        float w = max(soft, br - th) / max(br, 1e-4);
        s *= max(w, 0.0);
        s = min(s, vec3(64.0)); // a single hot pixel must not flood the frame
    }
    o = vec4(s, 1.0);
}
