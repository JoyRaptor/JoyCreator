// Stages to judge a look against, painted with no pictures: what is BEHIND the words.
// In Studio the real video takes this place; in the room these are the sets to try a look on.
// They are dark on purpose: a title is lit against black, the way a film lights one.
//   0 black            1 dark stage (a pool of haze, a gloss floor)   2 brick wall at night
//   3 sea and sky      4 dusk grid                                    5 far lights, out of focus
//   6 paper            7 smoke
in vec2 vUv;
out vec4 o;
uniform float uScene;
uniform vec3  uStageTint;   // the colour of the room's haze and far light
uniform vec4  uFloor;       // .y = the floor line, 0..1 up the field (the look's wet-floor line)

float brick(vec2 p, out float mortar, out vec2 cell) {
    vec2 g = p * vec2(3.4, 7.6);
    g.x += 0.5 * mod(floor(g.y), 2.0);
    cell = floor(g);
    vec2 f = fract(g);
    vec2 e = min(f, 1.0 - f) * vec2(2.2, 1.0);
    mortar = 1.0 - smoothstep(0.035, 0.085, min(e.x, e.y) + (tnoise(g * 9.0) - 0.5) * 0.03);
    return hash21(cell);
}

void main() {
    vec2 p = vUv * uField.xy;
    vec2 cn = vUv * 2.0 - 1.0;
    float aspect = uField.x / uField.y;
    int s = int(uScene + 0.5);
    vec3 c = vec3(0.0);
    vec2 cc = (vUv - vec2(0.5, 0.56)) * vec2(aspect, 1.0);
    float pool = exp(-dot(cc * vec2(0.5, 1.15), cc * vec2(0.5, 1.15)) * 3.2);
    if (s == 1 || s == 7) {
        float drift = tfbm(p * 0.8 + vec2(uTime * 0.02, 0.0));
        c = vec3(0.0022, 0.0026, 0.0034) + uStageTint * pool * (0.035 + 0.075 * drift);
        if (s == 7) {
            float s1 = tfbm(p * 0.7 + vec2(uTime * 0.03, -uTime * 0.012));
            float s2 = tfbm(p * 1.8 + vec2(-uTime * 0.05, uTime * 0.02) + s1 * 1.6);
            float wisps = pow(s2, 2.4) * 1.9;
            c += uStageTint * wisps * (0.02 + 0.3 * pool);
        }
        // a gloss floor: darker, with the haze lying along its far edge
        float line = uFloor.y;
        float fl = smoothstep(line + 0.004, line - 0.004, vUv.y);
        float into = max(line - vUv.y, 0.0);
        vec3 floorC = c * 0.25 + uStageTint * 0.02 * exp(-into * 11.0) * (0.4 + 0.6 * exp(-cc.x * cc.x * 1.5));
        floorC *= 0.8 + 0.4 * tnoise(vec2(p.x * 2.0, into * 90.0));
        c = mix(c, floorC, fl);
    } else if (s == 2) {
        float mortar; vec2 cell; float id = brick(p, mortar, cell);
        vec3 b = mix(vec3(0.032, 0.013, 0.010), vec3(0.075, 0.030, 0.022), id);
        b = mix(b, vec3(0.05, 0.045, 0.04), step(0.9, hash21(cell + 7.0)) * 0.5);          // the odd pale brick
        b *= 0.55 + 0.9 * tfbm(p * 26.0) * (0.7 + 0.6 * tnoise(p * 3.0));
        b = mix(b, vec3(0.012, 0.011, 0.010), mortar);
        b *= 1.0 - 0.5 * smoothstep(0.45, 0.8, tfbm(vec2(p.x * 2.5, p.y * 0.5)));             // soot runs
        float line = uFloor.y;
        float wall = smoothstep(line - 0.004, line + 0.004, vUv.y);
        float into = max(line - vUv.y, 0.0);
        vec3 floorC = vec3(0.006, 0.006, 0.008) * (0.5 + 1.0 * tfbm(vec2(p.x * 5.0, into * 60.0)));
        c = mix(floorC, b * 0.55, wall);
        c *= 0.3 + 0.7 * sat(1.0 - dot(cn * vec2(0.8, 1.0), cn * vec2(0.8, 1.0)) * 0.6);
    } else if (s == 3) {
        float hz = 0.46;
        float up = sat((vUv.y - hz) / (1.0 - hz));
        vec3 sky = mix(vec3(0.5, 0.68, 0.8), vec3(0.03, 0.14, 0.4), pow(up, 0.6));
        sky += vec3(0.4) * smoothstep(0.55, 0.68, tfbm(vec2(p.x * 0.7 + uTime * 0.01, vUv.y * 9.0))) * up * (1.0 - up) * 1.4;
        float dn = max(hz - vUv.y, 0.0);
        vec2 wp = vec2(p.x * (1.2 + 6.0 * dn), 26.0 * sqrt(dn * 8.0)) + vec2(uTime * 0.06, -uTime * 0.12);
        float w = tfbm(wp);
        vec3 sea = mix(vec3(0.16, 0.42, 0.55), vec3(0.01, 0.07, 0.17), sat(dn / hz * 1.2)) * (0.6 + 0.8 * w);
        sea += vec3(0.75, 0.85, 0.9) * pow(smoothstep(0.62, 0.9, w), 2.0) * exp(-dn * 9.0) * 0.6;
        c = mix(sea, sky, smoothstep(hz - 0.003, hz + 0.003, vUv.y));
        c += vec3(0.35, 0.38, 0.4) * exp(-abs(vUv.y - hz) * 70.0);
    } else if (s == 4) {
        // dusk over a lit grid: thin lines that keep their width as they run to the horizon
        float hz = 0.36;
        float up = sat((vUv.y - hz) / (1.0 - hz));
        vec3 skyC = mix(vec3(0.55, 0.10, 0.22), vec3(0.012, 0.006, 0.05), pow(up, 0.55));
        skyC += vec3(1.0, 0.35, 0.2) * exp(-up * 9.0) * 0.5;
        float gy = max(hz - vUv.y, 1e-3);
        vec2 gp = vec2(cn.x * aspect * 0.35 / gy, 0.6 / gy + uTime * 0.35);
        vec2 gw = fwidth(gp) * 1.2 + 0.012;
        float grid = max(1.0 - smoothstep(0.0, gw.x, abs(fract(gp.x + 0.5) - 0.5)),
                         1.0 - smoothstep(0.0, gw.y, abs(fract(gp.y + 0.5) - 0.5)));
        vec3 ground = vec3(0.006, 0.002, 0.016) + vec3(0.9, 0.12, 0.55) * grid * sat(gy * 5.0) * exp(-gy * 2.0) * 0.8;
        c = vUv.y > hz ? skyC : ground;
        c += vec3(1.0, 0.4, 0.5) * exp(-abs(vUv.y - hz) * 90.0) * 0.5;
    } else if (s == 5) {
        c = vec3(0.003, 0.003, 0.004) + uStageTint * pool * 0.02;
        for (int i = 0; i < 26; i++) {
            vec4 r = hash41(float(i) * 3.7 + 1.0);
            vec2 d = (vUv - vec2(r.x, 0.15 + 0.8 * r.y)) * vec2(aspect, 1.0);
            float rad = 0.035 + 0.11 * r.z * r.z;
            float disc = smoothstep(rad, rad * 0.9, length(d));
            float ring = 0.55 + 0.45 * smoothstep(rad * 0.6, rad * 0.95, length(d));
            vec3 lc = mix(uStageTint, mix(vec3(1.0, 0.62, 0.25), vec3(1.0, 0.9, 0.7), r.w), 0.75);
            c += lc * disc * ring * 0.035 * (0.4 + r.w) * (0.6 + 0.4 * sin(uTime * (0.5 + r.z) + r.x * 30.0));
        }
    } else if (s == 6) {
        c = vec3(0.86, 0.83, 0.76) * (0.92 + 0.12 * tfbm(p * 22.0)) * (0.85 + 0.15 * sat(1.0 - dot(cn, cn) * 0.4));
    }
    o = vec4(c, 1.0);
}
