// Draws the fire picture as light and smoke over the letters (premultiplied; flame adds).
// The heat picture is small and soft. What makes it fire is drawn here: every streamline of the
// flow carries its own brightness all the way up (FIBRES), tongues are carved out of the soft
// heat, each tongue has a lit rim, and the colour runs from a deep red tip to a yellow heart.
// The same fire can be drawn real or as flat cartoon bands (uFireToon).
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
    float real = 1.0 - uFireToon;
    float age = h.b;
    float from = p.x + h.a;      // where along the shape this bit of flame started

    // tongues: tall noise that rides up at the flame's own pace, carved OUT of the soft heat
    vec2 np = vec2(p.x - uTime * uWind.x * 0.8, p.y - uTime * (uFire.x * 0.8 + uWind.y));
    float n = tfbm(np * vec2(s * 2.6, s * 0.9) + vec2(uTime * 0.13, 0.0)) * 0.65
            + tfbm2(np * vec2(s * 6.5, s * 2.2) + vec2(4.0, uTime * 0.21)) * 0.35;
    // (young flame, still at the shape, is left soft: the carving and the rim come in as it climbs)
    float grown = 0.3 + 0.7 * smoothstep(0.03, 0.35, age);
    T -= uFireLook.x * (1.0 - n) * 0.26 * grown * smoothstep(0.0, 0.12, T) * (1.0 - smoothstep(0.55, 1.0, T));
    // fibres: brightness that belongs to the streamline, so it is combed along the flow and
    // thins the flame more the further it has climbed
    float fib = tfbm(vec2(from * s * 5.0, age * 1.5 - uTime * 0.22));
    float fib2 = tnoise(vec2(from * s * 14.0 + 3.0, age * 2.6 + uTime * 0.1));
    float climb = smoothstep(0.03, 0.35, age);
    T *= 1.0 - real * uFireLook.x * climb * (0.42 * (1.0 - fib) + 0.16 * (1.0 - fib2));

    float inside = smoothstep(0.0, 0.03, texture(uFieldTex, vUv).r);
    float show = 1.0 - inside * (1.0 - uFireFront);
    vec3 flame = fireRamp(T, uFireA, uFireB, uFireC, uFireToon) * uFireLook.y * show;
    // a flame is a thin sheet of light: brighter round the rim of each tongue, streaked inside
    float limb = smoothstep(0.06, 0.15, T) * (1.0 - smoothstep(0.15, 0.4, T));
    flame *= mix(1.0, 0.7 + 0.65 * fib2, real * smoothstep(0.15, 0.5, T));
    flame += mix(uFireA, uFireB, 0.6) * limb * 0.5 * grown * real * uFireLook.y * show;

    float hide = smoothstep(0.12, 0.7, T) * uFireLook.w * show;
    float smoke = sat(h.g * uFireLook.z) * (1.0 - smoothstep(0.1, 0.45, T));
    smoke *= 0.5 + 0.5 * tfbm2(vec2(from * s * 1.6, age * 0.7) + vec2(0.0, uTime * 0.05));
    vec3 smokeCol = vec3(0.02, 0.017, 0.015) + uFireB * 0.06 * sat(T * 4.0 + (1.0 - climb) * 0.3);
    float a = sat(hide + smoke);
    o = vec4(flame + smokeCol * smoke, a);
}
