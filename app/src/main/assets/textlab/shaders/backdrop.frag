// Scenes to judge a look against, painted with no pictures: what is BEHIND the words.
// In Studio the real video takes this place; in the room these are the stage to try a look on.
//   0 black   1 dark studio   2 brick wall at night   3 sea and sky   4 sunset   5 forest lights   6 paper
in vec2 vUv;
out vec4 o;
uniform float uScene;

float brick(vec2 p, out float mortar) {
    vec2 g = p * vec2(2.4, 5.2);
    g.x += 0.5 * mod(floor(g.y), 2.0);
    vec2 id = floor(g), f = fract(g);
    vec2 e = min(f, 1.0 - f) * vec2(2.2, 1.0);
    mortar = 1.0 - smoothstep(0.03, 0.09, min(e.x, e.y));
    return hash21(id);
}

void main() {
    vec2 p = vUv * uField.xy;
    vec2 cn = vUv * 2.0 - 1.0;
    int s = int(uScene + 0.5);
    vec3 c = vec3(0.0);
    if (s == 1) {
        float v = 1.0 - dot(cn * vec2(0.75, 1.0), cn * vec2(0.75, 1.0));
        c = mix(vec3(0.004, 0.004, 0.006), vec3(0.055, 0.058, 0.07), sat(v));
        c += vec3(0.02, 0.022, 0.03) * smoothstep(0.42, 0.0, vUv.y) * 0.6;
    } else if (s == 2) {
        float mortar; float id = brick(p, mortar);
        vec3 b = mix(vec3(0.05, 0.022, 0.018), vec3(0.11, 0.05, 0.04), id);
        b *= 0.6 + 0.8 * fbm(p * 14.0);
        b = mix(b, vec3(0.03, 0.028, 0.026), mortar);
        float wall = smoothstep(0.26, 0.3, vUv.y);
        vec3 floorC = vec3(0.012, 0.012, 0.016) * (0.6 + 0.8 * fbm(p * vec2(3.0, 18.0)));
        c = mix(floorC, b * 0.5, wall);
        c *= 0.35 + 0.65 * sat(1.0 - dot(cn, cn) * 0.5);
    } else if (s == 3) {
        float hz = 0.42;
        vec3 sky = mix(vec3(0.42, 0.56, 0.68), vec3(0.16, 0.3, 0.5), sat((vUv.y - hz) / (1.0 - hz)));
        float w = fbm(vec2(p.x * 1.2, (hz - vUv.y) * 60.0 / (0.25 + (hz - vUv.y) * 6.0)) + vec2(uTime * 0.05, 0.0));
        vec3 sea = mix(vec3(0.2, 0.36, 0.46), vec3(0.03, 0.1, 0.17), sat((hz - vUv.y) / hz)) * (0.75 + 0.5 * w);
        c = mix(sea, sky, smoothstep(hz - 0.004, hz + 0.004, vUv.y));
        c += vec3(0.3) * exp(-abs(vUv.y - hz) * 60.0);
    } else if (s == 4) {
        vec3 skyC = mix(vec3(0.9, 0.28, 0.12), vec3(0.08, 0.02, 0.2), sat(vUv.y * 1.3 - 0.2));
        float sun = smoothstep(0.52, 0.5, length((cn - vec2(0.0, -0.1)) * vec2(uField.x / uField.y, 1.0)));
        float gy = max(0.36 - vUv.y, 1e-3);
        vec2 gp = vec2(cn.x / gy * 0.5, 1.0 / gy + uTime * 0.4);
        float grid = max(smoothstep(0.46, 0.5, abs(fract(gp.x) - 0.5)), smoothstep(0.44, 0.5, abs(fract(gp.y) - 0.5)));
        vec3 ground = vec3(0.02, 0.0, 0.05) + vec3(0.9, 0.1, 0.6) * grid * sat(gy * 4.0);
        c = vUv.y > 0.36 ? skyC + vec3(1.0, 0.7, 0.2) * sun * 0.8 : ground;
    } else if (s == 5) {
        c = mix(vec3(0.01, 0.03, 0.015), vec3(0.04, 0.1, 0.04), fbm(p * 0.8));
        for (int i = 0; i < 14; i++) {
            vec4 r = hash41(float(i) * 3.7 + 1.0);
            vec2 d = (vUv - r.xy) * uField.xy;
            float rad = 0.12 + 0.3 * r.z;
            c += mix(vec3(0.5, 0.75, 0.2), vec3(0.95, 0.85, 0.4), r.w) * 0.16 * smoothstep(rad, rad * 0.8, length(d));
        }
    } else if (s == 6) {
        c = vec3(0.86, 0.83, 0.76) * (0.92 + 0.12 * fbm(p * 22.0)) * (0.85 + 0.15 * sat(1.0 - dot(cn, cn) * 0.4));
    }
    o = vec4(c, 1.0);
}
