// Draws the fire picture as light and smoke over the letters (premultiplied; flame adds).
// The heat picture is small and soft; the fine tongues come from breaking it up here, so the
// same fire can be drawn smooth and real or as flat cartoon bands.
in vec2 vUv;
out vec4 o;
uniform sampler2D uHeat;
uniform sampler2D uFieldTex;
uniform float uFireFront;   // 0 = flame stays off the letters' faces, 1 = it licks right over them
uniform vec3  uFireA;       // the cool fringe
uniform vec3  uFireB;       // the body
uniform vec3  uFireC;       // the core
uniform float uFireToon;    // 0 = real, 1 = flat bands
uniform vec4  uFireLook;    // fine detail, gain, smoke darkness, how much flame hides what is behind
uniform vec4  uFireShape;
uniform vec4  uFire;
uniform vec2  uWind;
void main() {
    vec2 p = vUv * uField.xy;
    vec4 h = texture(uHeat, vUv);
    float T = h.r;
    float s = uFireShape.x;
    // fine tongues: tall noise that rides up at the flame's own pace, carved OUT of the soft heat
    vec2 np = vec2(p.x - uTime * uWind.x * 0.8, p.y - uTime * (uFire.x * 0.8 + uWind.y));
    float n = tfbm(np * vec2(s * 2.6, s * 0.9) + vec2(uTime * 0.13, 0.0)) * 0.65
            + tfbm2(np * vec2(s * 6.5, s * 2.2) + vec2(4.0, uTime * 0.21)) * 0.35;
    T -= uFireLook.x * (1.0 - n) * 0.32 * smoothstep(0.0, 0.12, T) * (1.0 - smoothstep(0.55, 1.0, T));
    float inside = smoothstep(0.0, 0.03, texture(uFieldTex, vUv).r);
    float show = 1.0 - inside * (1.0 - uFireFront);
    vec3 flame = fireRamp(T, uFireA, uFireB, uFireC, uFireToon) * uFireLook.y * show;
    // a flame is a thin sheet of light: brighter round the rim of each tongue, streaked inside
    float real = 1.0 - uFireToon;
    float limb = smoothstep(0.07, 0.17, T) * (1.0 - smoothstep(0.17, 0.42, T));
    float streaks = tfbm2(np * vec2(s * 9.0, s * 1.6) + vec2(1.7, uTime * 0.3));
    flame *= mix(1.0, 0.72 + 0.6 * streaks, real * smoothstep(0.15, 0.5, T));
    flame += mix(uFireA, uFireB, 0.6) * limb * 0.55 * real * uFireLook.y * show;
    float hide = smoothstep(0.12, 0.7, T) * uFireLook.w * show;
    float smoke = sat(h.g * uFireLook.z) * (1.0 - smoothstep(0.1, 0.45, T));
    smoke *= 0.55 + 0.45 * tfbm2(np * s * 1.4 + vec2(0.0, uTime * 0.2));
    vec3 smokeCol = vec3(0.018, 0.016, 0.015) + uFireB * 0.05 * sat(T * 4.0);
    float a = sat(hide + smoke);
    o = vec4(flame + smokeCol * smoke, a);
}
