#version 300 es
// Light: a radial glow (thruster, landing flash, floor glow), a spark, or a four-point glint.
// Laid on additively; the finish pass gives it the alpha a light needs on Android.
precision highp float;
in vec2 vLocal;
in vec2 vHalf;
in vec4 vC1;     // centre colour, straight alpha
in vec4 vC2;     // colour at the middle stop, straight alpha
in vec4 vP;      // x: middle stop (0..1); y: kind (0 glow, 1 spark, 2 glint)
uniform float uPx;
out vec4 o;
void main() {
    vec2 q = vLocal / max(vHalf, vec2(0.01));
    float kind = vP.y;
    vec4 c;
    if (kind < 0.5) {
        float r = length(q);
        float s = max(vP.x, 0.001);
        c = r < s ? mix(vC1, vC2, r / s) : mix(vC2, vec4(vC2.rgb, 0.0), clamp((r - s) / (1.0 - s), 0.0, 1.0));
    } else if (kind < 1.5) {
        float r = length(vLocal);
        float rad = max(vHalf.x - uPx, 0.2);
        c = vec4(vC1.rgb, vC1.a * (1.0 - smoothstep(rad - uPx, rad + uPx * 0.5, r)));
    } else {
        // two thin diamonds crossed: |x|/0.1375 + |y| <= 1, and the same turned
        float a = abs(q.x) / 0.1375 + abs(q.y), b = abs(q.y) / 0.1375 + abs(q.x);
        float d = min(a, b);
        float soft = uPx / max(vHalf.x, 0.01) * 4.0;
        c = vec4(vC1.rgb, vC1.a * (1.0 - smoothstep(1.0 - soft, 1.0, d)));
    }
    o = vec4(c.rgb * c.a, c.a);
}
