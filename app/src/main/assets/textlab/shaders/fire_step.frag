// The fire, one tick (fixed 1/60 s). A small picture of heat and smoke that is carried upward
// on a swirl each tick, cools unevenly so it tears into tongues, and is fed by the shape.
// It feeds from the FIELD, so anything with an outline can burn: letters, a drawing, a sprite.
//   r = heat   g = smoke
//   b = age: seconds since this bit of flame left the shape
//   a = how far it has drifted sideways since then (em). With its place now, that says WHERE on
//       the shape it came from, and that stays the same all the way up a streamline: it is what
//       lets the flame be drawn as fibres combed along its own flow.
in vec2 vUv;
out vec4 o;
uniform sampler2D uPrev;
uniform sampler2D uFieldTex;
uniform float uDt;
uniform vec4  uFire;     // rise (em/s), swirl, cooling, feed
uniform vec4  uFireShape;// swirl size (per em), how deep inside the edge it burns (em), 0 = all edges .. 1 = upper edges only, smoke made
uniform vec2  uWind;     // em/s
uniform vec2  uFireUnder;// the shape stands in the flame's way (0 = flame passes through it, 1 = it pools underneath and wraps round); how far below an edge the pool hangs, em

float source(vec2 uv, vec2 p) {
    vec4 f = texture(uFieldTex, uv);
    float e = 1.5 / uField.z;
    float up = (texture(uFieldTex, uv + vec2(0.0, e / uField.y)).r - texture(uFieldTex, uv - vec2(0.0, e / uField.y)).r) / (2.0 * e);
    // up < 0 on an edge that faces the sky (distance falls as you go up and out of the letter)
    float facesUp = mix(1.0, smoothstep(0.15, -0.5, up), uFireShape.z);
    float band = smoothstep(-0.012, 0.004, f.r) * (1.0 - smoothstep(0.0, max(uFireShape.y, 1e-3), f.r));
    float flick = smoothstep(0.2, 0.75, tfbm2(p * 7.0 + vec2(uTime * 1.7, -uTime * 3.6)));
    // under an edge that faces DOWN the flame hangs a little below it, trapped, before it finds its way out
    // (it thins away downward with no edge of its own: fire is a glowing gas, not a shape)
    float pool = smoothstep(-uFireUnder.y, 0.0, f.r);
    pool = pool * pool * (1.0 - smoothstep(-0.004, 0.004, f.r)) * smoothstep(0.1, 0.6, up) * uFireUnder.x;
    return (band * facesUp + pool * 0.55) * flick * uFire.w * 0.85 * f.g;
}

void main() {
    vec2 p = vUv * uField.xy;
    // how hot it is round here, read from four corners: a single-pixel reading lets alternate
    // rows run at different speeds and the flame combs into stripes
    vec2 tx = 1.5 / vec2(textureSize(uPrev, 0));
    float hpp = texture(uPrev, vUv + tx).r, hmm = texture(uPrev, vUv - tx).r;
    float hpm = texture(uPrev, vUv + vec2(tx.x, -tx.y)).r, hmp = texture(uPrev, vUv - vec2(tx.x, -tx.y)).r;
    float here = 0.25 * (hpp + hmm + hpm + hmp);
    float s = uFireShape.x;
    // the swirl and the cold lanes RIDE UP with the flame (and are taller than wide), so a tongue
    // keeps its own shape as it climbs instead of boiling in place
    vec2 np = vec2(p.x - uTime * uWind.x * 0.8, p.y - uTime * (uFire.x * 0.8 + uWind.y));
    vec2 tall = vec2(s, s * 0.55);
    vec2 sw = tcurl(np * tall + vec2(uTime * 0.11, 0.0)) + 0.5 * tcurl(np * tall * 2.6 + vec2(5.3, uTime * 0.17));
    vec2 v = vec2(0.0, uFire.x * (0.5 + 0.9 * sat(here))) + uWind + sw * uFire.y;
    float wall = 0.0;
    if (uFireUnder.x > 0.0) {
        // THE SHAPE IS IN THE WAY. Flame cannot rise through it: under an edge it pools, creeps
        // sideways toward where there is less of it, and only climbs once it has found the corner.
        float e2 = 2.0 / uField.z;
        vec2 gin = vec2(texture(uFieldTex, vUv + vec2(e2 / uField.x, 0.0)).r - texture(uFieldTex, vUv - vec2(e2 / uField.x, 0.0)).r,
                        texture(uFieldTex, vUv + vec2(0.0, e2 / uField.y)).r - texture(uFieldTex, vUv - vec2(0.0, e2 / uField.y)).r);
        vec2 nin = normalize(gin + vec2(1e-6, 0.0));                                   // toward the inside of the shape
        wall = smoothstep(-uFireUnder.y * 1.3, 0.0, texture(uFieldTex, vUv).r) * uFireUnder.x;   // inside it, or just under its skin
        v -= nin * max(dot(v, nin), 0.0) * wall;
        vec2 slope = vec2((hpp + hpm) - (hmp + hmm), (hpp + hmp) - (hpm + hmm)) / (4.0 * tx * uField.xy);   // heat per em
        v -= clamp(slope * 0.02, -0.6, 0.6) * wall;
    }
    vec4 was = texture(uPrev, vUv - v * uDt / uField.xy);
    was.r = mix(was.r, here, 0.7 * wall);   // trapped flame has nowhere to go, so it spreads and goes soft

    float tear = tfbm(np * tall * 1.9 + vec2(uTime * 0.07, 3.0));
    float heat = was.r * exp(-uDt * uFire.z * (0.25 + 2.2 * tear * tear)) - uDt * 0.05 * uFire.z;
    heat = max(heat, 0.0);
    float lost = max(was.r - heat, 0.0);
    float smoke = was.g * exp(-uDt * 0.55) + lost * uFireShape.w;
    float age = min(was.b + uDt, 8.0);
    float drift = was.a - v.x * uDt;

    // The shape holds its edge at a temperature (it does not pile heat up without end). The flame
    // travels several pixels a tick, so the edge is read at three points along the way it came:
    // read once, a thin edge would be stamped upward as a ladder of stripes.
    vec2 back = v * uDt / uField.xy;
    float feed = max(source(vUv, p), max(source(vUv - back * 0.34, p), source(vUv - back * 0.67, p)));
    float fresh = smoothstep(0.0, 0.06, feed - heat);
    age = mix(age, 0.0, fresh);
    drift = mix(drift, 0.0, fresh);
    heat = max(heat, feed);

    // the edges of the picture must not hold heat, or flame would smear along them
    vec2 b = min(vUv, 1.0 - vUv) * uField.xy;
    float keep = smoothstep(0.0, 0.12, min(b.x, b.y));
    o = vec4(heat * keep, smoke * keep, age, drift);
}
