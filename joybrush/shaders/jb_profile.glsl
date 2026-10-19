// Unibrush D2. Legacy helper/JbTip stay unchanged; zero mode and zero fade use them exactly.
#include "jb_tip.glsl"

uniform int u_tipProfile; // 0 legacy, 1 hard, 2 linear, 3 smooth, 4 gaussian, 5 custom
uniform vec4 u_tipProfileLut[64]; // 256 cached samples, no texture binding

float jb_profileValue(float x) {
    float t = clamp(x, 0.0, 1.0);
    if (u_tipProfile == 2) return 1.0 - t;
    if (u_tipProfile == 3) return 1.0 - t * t * (3.0 - 2.0 * t);
    if (u_tipProfile == 4) {
        float tail = exp(-4.5);
        return clamp((exp(-4.5 * t * t) - tail) / (1.0 - tail), 0.0, 1.0);
    }
    float f = t * 255.0;
    int i = min(int(f), 254);
    float a = u_tipProfileLut[i / 4][i % 4];
    int j = i + 1;
    float b = u_tipProfileLut[j / 4][j % 4];
    return clamp(mix(a, b, f - float(i)), 0.0, 1.0);
}

float jb_unibrushTipCoverage(vec2 offsetPx, JbTip t, float fadeAmount, float fadeAngleLocal) {
    if (u_tipProfile == 0 && fadeAmount <= 0.0) return jb_tipCoverage(offsetPx, t);
    float c = cos(t.angle), s = sin(t.angle);
    vec2 q = vec2(c * offsetPx.x + s * offsetPx.y, -s * offsetPx.x + c * offsetPx.y);
    float a = clamp(t.aspect, -1.0, 1.0);
    float hx = t.radiusPx * (a > 0.0 ? 1.0 - a : 1.0);
    float hy = t.radiusPx * (a < 0.0 ? 1.0 + a : 1.0);
    float halfMin = max(t.minPx, 0.5) * 0.5;
    float razor = min(max(hx, 0.25) / halfMin, 1.0) * min(max(hy, 0.25) / halfMin, 1.0);
    hx = max(hx, halfMin); hy = max(hy, halfMin);
    float along = clamp(q.y / hy * 0.5 + 0.5, 0.0, 1.0);
    float widthScale = max(1.0 - clamp(t.taper, 0.0, 1.0) * along, 1e-3);
    float d = jb_superellipse(q, hx * widthScale, hy, t.corner);
    float aa = max(fwidth(d), 1e-4);
    float inner = min(clamp(t.hardness, 0.0, 1.0), 1.0 - aa);
    float outer = 1.0 + 0.5 * aa;
    float cov;
    if (u_tipProfile == 0 || u_tipProfile == 3) cov = 1.0 - smoothstep(inner, outer, d);
    else if (u_tipProfile == 1) cov = 1.0 - smoothstep(1.0 - 0.5 * aa, outer, d);
    else cov = jb_profileValue((d - inner) / (outer - inner));
    float result = cov * razor;
    if (fadeAmount <= 0.0) return result;
    vec2 v = vec2(cos(fadeAngleLocal), sin(fadeAngleLocal));
    float reach = max(length(vec2(hx, hy) * v), 1e-4);
    float across = clamp(0.5 + 0.5 * dot(q, v) / reach, 0.0, 1.0);
    return result * (1.0 - clamp(fadeAmount, 0.0, 1.0) * across);
}
