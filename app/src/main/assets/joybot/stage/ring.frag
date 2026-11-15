#version 300 es
// A plasma push ring: a thin ellipse with a hot core line and a pink glow around it (owner, 14 Nov).
precision highp float;
in vec2 vLocal;
in vec4 vC1;     // core colour, straight alpha
in vec4 vC2;     // glow colour, straight alpha
in vec4 vP;      // ellipse radii x, y; line width; glow blur (all dp)
uniform float uPx;  // dp per pixel of this target
out vec4 o;
void main() {
    vec2 r = max(vP.xy, vec2(0.05));
    // distance to the ellipse, in dp (Quilez's estimate: good near the line, which is all that shows)
    vec2 q = vLocal / r;
    float k0 = length(q), k1 = length(vLocal / (r * r));
    float d = k1 > 0.0 ? abs(k0 * (k0 - 1.0) / k1) : min(r.x, r.y);
    float aa = uPx * 0.75;
    float core = (1.0 - smoothstep(vP.z * 0.5 - aa, vP.z * 0.5 + aa, d)) * vC1.a;
    float glow = 0.0;
    if (vP.w > 0.0) {
        float g = d / max(vP.w * 0.5, 0.5);
        glow = exp(-g * g) * vC2.a * (1.0 - core);
    }
    o = vec4(vC1.rgb * core + vC2.rgb * glow, core + glow);
}
