// The finished picture: the lit words over what is behind them, their glow, heat shimmer,
// a wet floor, then the film: a response curve that KEEPS COLOUR, a grade, a vignette and grain.
//   uOut 0: for a screen (film curve, sRGB)           the look book and the room's preview
//   uOut 1: for a compositor (linear, premultiplied)  a GL compositor that applies the film finish itself
//   uOut 2: for an 8-bit compositor (sRGB, premultiplied, the look's own finish on the words alone):
//           Studio's overlays. Over black it is exactly uOut 0 over a black ground, less vignette and grain.
in vec2 vUv;
out vec4 o;
uniform sampler2D uScene;    // the words: linear, premultiplied, may exceed 1
uniform sampler2D uBloom;
uniform sampler2D uBloomWide; // a small, far more blurred level of the bloom: what the lens streak is drawn from
uniform sampler2D uBack;
uniform sampler2D uHeat;
uniform float uHasBack;
uniform float uOut;
uniform vec4  uPost;         // bloom strength, light spilled onto what is behind, heat shimmer, exposure
uniform vec4  uFloor;        // mirror strength, floor line (0..1 up the field), ripple, how fast it fades with distance
uniform vec2  uGrade;        // contrast, saturation
uniform vec2  uFringe;       // colours that do not quite line up: x = a lens's (nothing in the middle, growing toward the screen's edges), y = a printer's (the plates out of register, the same everywhere)
uniform vec4  uFinish;       // vignette, grain, lens streak (a thin sideways flare off the brightest light), streak tint 0 white .. 1 blue

// Film response on the BRIGHTEST channel only, the others scaled with it: an orange twice as
// bright as white stays orange. (A curve run on each channel alone bleaches every strong colour
// toward white, which is where a cheap, washed-out look comes from.) Only light far beyond the
// film's range is let go to white, as it would on a negative.
vec3 film(vec3 c) {
    float peak = max3(c);
    if (peak < 1e-5) return vec3(0.0);
    const float knee = 0.6;
    float m = peak < knee ? peak : knee + (1.0 - knee) * tanh((peak - knee) / (1.0 - knee));
    vec3 kept = c * (m / peak);
    return mix(kept, vec3(m), smoothstep(1.6, 9.0, peak) * 0.8);
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
        vec2 shiver = vec2(tnoise(p * 9.0 + vec2(0.0, -uTime * 5.0)), tnoise(p * 9.0 + vec2(7.0, -uTime * 4.3))) - 0.5;
        uv += (clamp(vec2(hx, hy), -1.0, 1.0) * 0.006 + shiver * sat(h) * 0.01) * uPost.z / uField.xy;
    }
    vec4 sc = texture(uScene, uv);
    vec3 bl = texture(uBloom, uv).rgb * uPost.x;
    vec3 a3 = vec3(sc.a);
    if (uFringe.x + uFringe.y > 0.0) {
        // red and blue are read from a little to either side of green
        vec2 fromMid = vUv - 0.5;
        vec2 oR = fromMid * dot(fromMid, fromMid) * 0.05 * uFringe.x + vec2(0.0038, -0.0022) * uFringe.y * vec2(uField.y / uField.x, 1.0);
        vec2 oB = -fromMid * dot(fromMid, fromMid) * 0.05 * uFringe.x + vec2(-0.003, 0.0028) * uFringe.y * vec2(uField.y / uField.x, 1.0);
        vec4 sR = texture(uScene, uv + oR), sB = texture(uScene, uv + oB);
        sc.r = sR.r; sc.b = sB.b;
        a3 = vec3(sR.a, sc.a, sB.a);
        bl.r = texture(uBloom, uv + oR).r * uPost.x;
        bl.b = texture(uBloom, uv + oB).b * uPost.x;
    }

    if (uFinish.z > 0.0) {
        // lens streak: the bloom smeared sideways, thin, as an anamorphic lens does to a hard light
        vec3 st = vec3(0.0);
        float wsum = 0.0;
        for (int i = -14; i <= 14; i++) {
            float fi = float(i);
            float w = exp(-fi * fi / 60.0);
            st += texture(uBloomWide, uv + vec2(fi * 0.011, 0.0)).rgb * w;
            wsum += w;
        }
        st /= wsum;
        float hot = smoothstep(0.05, 0.6, max3(st));
        bl += st * hot * uFinish.z * 1.5 * mix(vec3(1.0), vec3(0.45, 0.7, 1.0), uFinish.w);
    }

    if (uFloor.x > 0.0 && vUv.y < uFloor.y) {
        // a wet floor: the picture above the line, upside down, rippled, fading as it gets deeper
        float depth = (uFloor.y - vUv.y) * uField.y;
        vec2 rp = vec2(p.x * 3.0, depth * 14.0);
        float rip = (tfbm2(rp + vec2(uTime * 0.25, 0.0)) - 0.5) * uFloor.z * (0.4 + depth);
        vec2 muv = vec2(uv.x + rip * 0.06 / uField.x, 2.0 * uFloor.y - vUv.y + rip * 0.05 / uField.y);
        float fadeOut = exp(-depth * uFloor.w) * uFloor.x;
        vec4 m = texture(uScene, muv);
        vec3 mb = texture(uBloom, muv).rgb * uPost.x;
        sc.rgb += (m.rgb + mb) * fadeOut * (1.0 - sc.a);
        sc.a = max(sc.a, m.a * fadeOut * 0.5);
    }

    if (uOut > 1.5) {
        // the finish on the words and their glow alone. Glow is light, not paint: its cover is its own
        // brightness, so laid over black it adds exactly what the screen picture shows
        float glowA = sat(max3(bl));
        float a = sat(sc.a + glowA * (1.0 - sc.a));
        vec3 w = film((sc.rgb + bl) * uPost.w);
        w = mix(vec3(luma(w)), w, uGrade.y);
        w = toSrgb(clamp(w, 0.0, 1.0));
        w = clamp((w - 0.5) * uGrade.x + 0.5, 0.0, 1.0);
        o = vec4(w, max(a, max3(w)));
        return;
    }
    if (uOut > 0.5) {
        float glowA = sat(max3(bl));
        o = vec4(sc.rgb + bl, sat(sc.a + glowA * (1.0 - sc.a)));
        return;
    }
    vec3 bg = uHasBack > 0.5 ? texture(uBack, uv).rgb : vec3(0.0);
    // the words' light falls on what is behind them, and takes its colour and texture
    vec3 lit = bg * (1.0 + bl * uPost.y * 6.0);
    vec3 c = lit * (1.0 - a3) + sc.rgb + bl;
    c *= uPost.w;
    c = film(c);
    c = mix(vec3(luma(c)), c, uGrade.y);
    c = toSrgb(clamp(c, 0.0, 1.0));
    c = clamp((c - 0.5) * uGrade.x + 0.5, 0.0, 1.0);
    vec2 cn = (vUv * 2.0 - 1.0) * vec2(1.0, uField.y / uField.x * 1.2);
    c *= 1.0 - uFinish.x * smoothstep(0.35, 1.25, length(cn));
    float gr = hash21(gl_FragCoord.xy + fract(uTime) * 61.0) - 0.5;
    c += gr * (uFinish.y * 0.07 * (1.0 - 0.6 * luma(c)) + 1.0 / 255.0);
    o = vec4(c, 1.0);
}
