// jb_stick.glsl — where a drawing stick (graphite lead, charcoal, pastel) presses into the paper.
// Functions only: NO #version, NO main. Include after jb_media_common.glsl.
// CPU twin: lab/media/js/stick.js stickSqueeze() — keep the two identical.
//
// Three overlapping zones, all anchored at the pen tip (see stick.js): the rounded point, the worn usage
// side (a strong gradient), and the whole side (feathering). q = (x toward the barrel along the lean,
// y across), mm, from the pen tip. Returns the squeeze in mm (how far the paper is pressed here).

const float JB_GRAD1 = 1.2;      // ZONES.grad1
const float JB_GRAD2 = 0.9;      // ZONES.grad2

float jb_zoneRamp(float x, float L, float g, float even) {
    if (x < 0.0) return 1.0;
    float u = x / max(L, 1e-3);
    if (u >= 1.0) return 0.0;
    return mix(pow(1.0 - u, g), 1.0 - smoothstep(0.45, 1.0, u), even);
}
float jb_zoneAcross(float x, float y, float L, float R0, float H) {
    float Hw = mix(R0, H, smoothstep(0.0, 0.3 * L, max(x, 0.0)));
    return sqrt(max(0.0, 1.0 - (y * y) / (Hw * Hw)));
}
// zones: R0 (point radius), L1, L2 (side lengths), H (side face half-width); w = zone weights; even 0..1
float jb_stickSqueeze(vec2 q, float D, vec4 zones, vec3 w, float even) {
    float R0 = zones.x, L1 = zones.y, L2 = zones.z, H = zones.w;
    float x = q.x, y = q.y;
    float point = max(0.0, 1.0 - dot(q, q) / (R0 * R0));
    float front = x < 0.0 ? max(0.0, 1.0 - (x * x) / (R0 * R0)) : 1.0;
    float g2 = mix(JB_GRAD2, 0.55, even);
    float side = w.y * jb_zoneRamp(x, L1, JB_GRAD1, 0.0) * jb_zoneAcross(x, y, L1, R0, H)
               + w.z * jb_zoneRamp(x, L2, g2, even) * jb_zoneAcross(x, y, L2, R0, H);
    return D * (w.x * point + side * front);
}
