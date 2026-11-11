// The finished picture: the lit words over what is behind them, their glow, heat shimmer,
// a wet floor, then film response.
//   uOut 0: for a screen (tone mapped, sRGB)          the look book and the room's preview
//   uOut 1: for a compositor (linear, premultiplied)  what Studio, VisLab and JoyBrush take
in vec2 vUv;
out vec4 o;
uniform sampler2D uScene;    // the words: linear, premultiplied, may exceed 1
uniform sampler2D uBloom;
uniform sampler2D uBack;
uniform sampler2D uHeat;
uniform float uHasBack;
uniform float uOut;
uniform vec4  uPost;         // bloom strength, light spilled onto what is behind, heat shimmer, exposure
uniform vec4  uFloor;        // mirror strength, floor line (0..1 up the field), ripple, how fast it fades with distance
uniform vec2  uGrade;        // contrast, saturation

vec3 film(vec3 x) {          // ACES-like curve (Narkowicz)
    return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
}
vec3 toSrgb(vec3 c) { return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, c)); }

void main() {
    vec2 uv = vUv;
    vec2 p = uv * uField.xy;
    if (uPost.z > 0.0) {
        // hot air bends light: slide the lookup a few pixels along the heat's slope, with a fine shiver
        vec2 st = 0.03 / uField.xy;
        float hx = texture(uHeat, uv + vec2(st.x, 0.0)).r - texture(uHeat, uv - vec2(st.x, 0.0)).r;
        float hy = texture(uHeat, uv + vec2(0.0, st.y)).r - texture(uHeat, uv - vec2(0.0, st.y)).r;
        float h = texture(uHeat, uv).r + texture(uHeat, uv - vec2(0.0, 0.25 / uField.y)).r * 0.6;
        vec2 shiver = vec2(vnoise(p * 9.0 + vec2(0.0, -uTime * 5.0)), vnoise(p * 9.0 + vec2(7.0, -uTime * 4.3))) - 0.5;
        uv += (clamp(vec2(hx, hy), -1.0, 1.0) * 0.006 + shiver * sat(h) * 0.01) * uPost.z / uField.xy;
    }
    vec4 sc = texture(uScene, uv);
    vec3 bl = texture(uBloom, uv).rgb * uPost.x;

    if (uFloor.x > 0.0 && vUv.y < uFloor.y) {
        // a wet floor: the picture above the line, upside down, rippled, fading as it gets deeper
        float depth = (uFloor.y - vUv.y) * uField.y;
        vec2 rp = vec2(p.x * 3.0, depth * 14.0);
        float rip = (fbm2(rp + vec2(uTime * 0.25, 0.0)) - 0.5) * uFloor.z * (0.4 + depth);
        vec2 muv = vec2(uv.x + rip * 0.06 / uField.x, 2.0 * uFloor.y - vUv.y + rip * 0.05 / uField.y);
        float fadeOut = exp(-depth * uFloor.w) * uFloor.x;
        vec4 m = texture(uScene, muv);
        vec3 mb = texture(uBloom, muv).rgb * uPost.x;
        sc.rgb += (m.rgb + mb) * fadeOut * (1.0 - sc.a);
        sc.a = max(sc.a, m.a * fadeOut * 0.5);
    }

    if (uOut > 0.5) {
        float glowA = sat(max3(bl));
        o = vec4(sc.rgb + bl, sat(sc.a + glowA * (1.0 - sc.a)));
        return;
    }
    vec3 bg = uHasBack > 0.5 ? texture(uBack, uv).rgb : vec3(0.0);
    // the words' light falls on what is behind them, and takes its colour and texture
    vec3 lit = bg * (1.0 + bl * uPost.y * 6.0);
    vec3 c = lit * (1.0 - sc.a) + sc.rgb + bl;
    c *= uPost.w;
    c = film(c);
    float l = luma(c);
    c = mix(vec3(l), c, uGrade.y);
    c = (c - 0.5) * uGrade.x + 0.5;
    c = toSrgb(clamp(c, 0.0, 1.0));
    c += (hash21(gl_FragCoord.xy + fract(uTime) * 61.0) - 0.5) / 255.0;
    o = vec4(c, 1.0);
}
