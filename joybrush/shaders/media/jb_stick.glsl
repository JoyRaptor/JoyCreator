// jb_stick.glsl — where a drawing stick (graphite lead, charcoal, pastel) presses into the paper.
// Functions only: NO #version, NO main. Include after jb_media_common.glsl.
// CPU twin: lab/media/js/stick.js stickSqueeze() — keep the two identical.
//
// Three overlapping zones, all anchored at the pen tip (see stick.js): the rounded point, the worn usage
// side (a strong gradient), and the whole side (feathering). q = (x toward the barrel along the lean,
// y across), mm, from the pen tip. Returns the squeeze in mm (how far the paper is pressed here).


float jb_zoneRamp(float x, float L, float g, float even) {
    if (x < 0.0) return 1.0;
    float u = x / max(L, 1e-3);
    if (u >= 1.0) return 0.0;
    return mix(pow(1.0 - u, g), 1.0 - smoothstep(0.45, 1.0, u), even);
}

float jb_zoneAcross(float x, float y, float L, float R0, float H, float blend, float acrossSoft) {
    float Hw = mix(R0, H, smoothstep(0.0, 0.3 * L, max(x, 0.0)));
    float e = max(0.0, 1.0 - (y * y) / (Hw * Hw));
    return mix(sqrt(e), pow(e, acrossSoft), blend);
}
// zones: R0 (point radius), L1, L2 (side lengths), H (side face half-width); w = zone weights; even 0..1;
// blend 0 = the three zones as drawn since v9, 1 = one footprint (the owner, 2026-10-14: "two brushes overlaid").
// shape = (bell, acrossSoft, tipYield, tail) of the blended footprint; grads = (grad1, grad2), the side zones' fades.
// Both come from stick.js SHAPE / ZONES (lab, live in the pencil tuner) and core Stick.kt (app).
float jb_stickSqueeze(vec2 q, float D, vec4 zones, vec3 w, float even, float blend, vec4 shape, vec2 grads) {
    float R0 = zones.x, L1 = zones.y, L2 = zones.z, H = zones.w;
    float x = q.x, y = q.y;
    float R2 = R0 * R0;
    float sideDown = min(1.0, (w.y + w.z) * 2.0);
    float along = x < 0.0 ? exp(-shape.x * x * x / R2) : mix(exp(-shape.x * x * x / R2), exp(-x / (shape.w * R0)), sideDown);
    float point = mix(max(0.0, 1.0 - dot(q, q) / R2), exp(-shape.x * y * y / R2) * along, blend);
    float front = x < 0.0 ? mix(max(0.0, 1.0 - (x * x) / R2), exp(-shape.x * x * x / R2), blend) : 1.0;
    float g2 = mix(grads.y, 0.55, even);
    float side = (w.y * jb_zoneRamp(x, L1, grads.x, 0.0) * jb_zoneAcross(x, y, L1, R0, H, blend, shape.y)
               + w.z * jb_zoneRamp(x, L2, g2, even) * jb_zoneAcross(x, y, L2, R0, H, blend, shape.y)) * front;
    float pointShare = w.x * (1.0 - shape.z * blend * sideDown);
    return D * (pointShare * point + side);
}
