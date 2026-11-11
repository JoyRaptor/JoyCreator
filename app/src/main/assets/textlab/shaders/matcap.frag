// Bakes a MATERIAL SPHERE: a picture of a ball lit by a whole room. A letter's surface looks
// up its colour here by which way it faces, so a new chrome or gold is only a new sphere.
// (A photograph of a shiny ball can be dropped in instead of this bake.)
//
// The room is a photographer's studio, painted: a floor-to-ceiling ramp read up the ball, a
// horizon, SOFTBOXES (the long clean rectangles of light that make metal look expensive), round
// lamps, upright strips, a rim, an oil-film rainbow, and a little unevenness so it is not
// computer-perfect. Brightness above 1 is real: it is what glints.
in vec2 vUv;
out vec4 o;
uniform vec3  uRamp[8];     // colours from the ball's bottom to its top
uniform float uRampPos[8];  // where each sits, 0 (bottom) .. 1 (top), rising
uniform vec4  uJag;         // horizon: height 0..1, mountain height, mountain count, how far the jag reaches
uniform vec4  uBox[4];      // softbox: centre x, y on the ball (-1..1), half width, half height
uniform vec4  uBoxB[4];     // its turn (radians), edge softness, strength, how much brighter its middle is
uniform vec3  uBoxCol[4];
uniform vec4  uLamp[4];     // round lamp: x, y, size, strength
uniform vec3  uLampCol[4];
uniform vec4  uStrips;      // studio strips: strength, count, lowest height 0..1, softness
uniform vec3  uRimCol;      // light at the grazing edge of the ball
uniform vec2  uIrid;        // rainbow: amount, turns across the ball
uniform float uUneven;      // blotchy fall-off across the room, 0..1
uniform float uGain;

vec3 ramp(float t) {
    vec3 c = uRamp[0];
    for (int i = 1; i < 8; i++) {
        c = mix(c, uRamp[i], smoothstep(uRampPos[i - 1], max(uRampPos[i], uRampPos[i - 1] + 1e-4), t));
    }
    return c;
}

void main() {
    vec2 s = vUv * 2.0 - 1.0;
    float r = min(length(s), 1.0);
    float t = s.y * 0.5 + 0.5;
    // far mountains: push the ramp up and down near the horizon only
    float m = (vnoise(vec2(s.x * uJag.z, 3.7)) * 0.65 + vnoise(vec2(s.x * uJag.z * 2.7, 9.1)) * 0.35) - 0.5;
    t += m * uJag.y * exp(-sq((t - uJag.x) / max(uJag.w, 1e-3)));
    vec3 col = ramp(sat(t));
    col *= 1.0 - uUneven * 0.6 * fbm(s * 2.2 + 7.0);
    // studio strips: bright upright bars hanging in the upper half
    float bars = smoothstep(1.0 - uStrips.w, 1.0, sin(s.x * uStrips.y * PI) * 0.5 + 0.5);
    col += bars * uStrips.x * smoothstep(uStrips.z, uStrips.z + 0.15, t) * (1.0 - smoothstep(0.86, 0.98, t));
    for (int i = 0; i < 4; i++) {
        // a softbox: a rounded rectangle of light, a touch brighter in its middle
        vec2 l = rot(uBoxB[i].x) * (s - uBox[i].xy);
        vec2 e = abs(l) - uBox[i].zw;
        float dist = length(max(e, 0.0)) + min(max(e.x, e.y), 0.0);
        float soft = max(uBoxB[i].y, 1e-3);
        float inside = 1.0 - smoothstep(-soft, soft, dist);
        float mid = 1.0 + uBoxB[i].w * exp(-dot(l / max(uBox[i].zw, vec2(1e-3)), l / max(uBox[i].zw, vec2(1e-3))) * 1.5);
        col += uBoxCol[i] * uBoxB[i].z * inside * mid;

        vec2 dl = (s - uLamp[i].xy) / max(uLamp[i].z, 1e-3);
        col += uLampCol[i] * uLamp[i].w * exp(-dot(dl, dl));
    }
    col += uRimCol * pow(r, 6.0);
    if (uIrid.x > 0.0) {
        vec3 rb = hsv2rgb(vec3(fract(r * uIrid.y + atan(s.y, s.x) / TAU * 2.0 + t * 0.5), 0.75, 1.0));
        col = mix(col, col * rb * 1.8 + rb * 0.12, uIrid.x);
    }
    o = vec4(col * uGain, 1.0);
}
