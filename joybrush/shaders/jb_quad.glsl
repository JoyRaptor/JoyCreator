// Four-handle contour. The CPU cleans duplicates/backtracks once per dab before upload.
// meta.w: 0 legacy, 1 empty, 3 triangle, 4 quad, 5 exact circular square fillet.
float jb_cross(vec2 a, vec2 b) { return a.x * b.y - a.y * b.x; }
float jb_segmentDistance(vec2 p, vec2 a, vec2 b) {
    vec2 v = b - a;
    float len2 = dot(v, v);
    return length(p - a - v * (len2 > 0.0 ? clamp(dot(p - a, v) / len2, 0.0, 1.0) : 0.0));
}
bool jb_inTriangle(vec2 p, vec2 a, vec2 b, vec2 c) {
    if (jb_cross(b - a, c - a) == 0.0) return false;
    // Use the contour's same half-open ray convention. Inclusive triangle edges would
    // toggle a polygon corner that the outer contour classified as outside.
    vec2 v[3]; v[0] = a; v[1] = b; v[2] = c;
    bool inside = false;
    for (int i = 0; i < 3; ++i) {
        vec2 u = v[i], w = v[(i + 1) % 3];
        if ((u.y > p.y) != (w.y > p.y)) {
            if (p.x < u.x + (p.y - u.y) * (w.x - u.x) / (w.y - u.y)) inside = !inside;
        }
    }
    return inside;
}
struct JbQuadCorner { vec2 p; vec2 q; vec2 unitP; vec2 unitQ; float radius; float turn; };
JbQuadCorner jb_quadCorner(vec2 before, vec2 v, vec2 after, float roundness) {
    JbQuadCorner c;
    c.p = v; c.q = v; c.unitP = vec2(0); c.unitQ = vec2(0); c.radius = 0.0; c.turn = 0.0;
    vec2 a = before - v, b = after - v;
    float la = length(a), lb = length(b);
    if (roundness <= 0.0 || la <= 0.0 || lb <= 0.0) return c;
    a /= la; b /= lb;
    float co = clamp(dot(a, b), -1.0, 1.0), crossAB = jb_cross(a, b);
    if (abs(crossAB) <= 1e-7) return c;
    float td = roundness * 0.5 * min(la, lb);
    vec2 p = v + a * td, q = v + b * td;
    if (abs(jb_cross(p - v, q - v)) <= 1e-14) return c;
    c.p = p; c.q = q;
    c.radius = co >= 0.0 ? td * abs(crossAB) / (1.0 + co) : td * (1.0 - co) / abs(crossAB);
    c.turn = -sign(crossAB);
    c.unitP = c.turn * vec2(-a.y, a.x);
    c.unitQ = -c.turn * vec2(-b.y, b.x);
    return c;
}
float jb_quadSignedDistance(vec2 p, vec4 xy01, vec4 xy23, vec4 meta) {
    vec2 v[4]; v[0] = xy01.xy; v[1] = xy01.zw; v[2] = xy23.xy; v[3] = xy23.zw;
    if (meta.w > 4.5) {
        vec2 center = (v[0] + v[1] + v[2] + v[3]) * 0.25;
        return length(p - center) - length(v[1] - v[0]) * 0.5;
    }
    int n = int(meta.w);
    JbQuadCorner c[4];
    for (int i = 0; i < 4; ++i) {
        if (i >= n) break;
        c[i] = jb_quadCorner(v[(i + n - 1) % n], v[i], v[(i + 1) % n], meta.x);
    }
    bool inside = false;
    float distance = 1e6;
    for (int i = 0; i < 4; ++i) {
        if (i >= n) break;
        int j = (i + 1) % n;
        vec2 a = v[i], b = v[j];
        if ((a.y > p.y) != (b.y > p.y)) {
            if (p.x < a.x + (p.y - a.y) * (b.x - a.x) / (b.y - a.y)) inside = !inside;
        }
        distance = min(distance, jb_segmentDistance(p, c[i].q, c[j].p));
        if (c[i].radius > 0.0) {
            vec2 fromP = p - c[i].p, fromQ = p - c[i].q;
            // Rationalized radial distance avoids subtracting two huge nearly equal radii.
            float circleDelta = (dot(fromP, fromP) + 2.0 * c[i].radius * dot(fromP, c[i].unitP)) /
                                (length(fromP + c[i].radius * c[i].unitP) + c[i].radius);
            float arc = min(length(fromP), length(fromQ));
            if (jb_cross(c[i].unitP, fromP) * c[i].turn >= 0.0 &&
                jb_cross(fromQ, c[i].unitQ) * c[i].turn >= 0.0) arc = abs(circleDelta);
            distance = min(distance, arc);
            if (jb_inTriangle(p, c[i].p, v[i], c[i].q) && circleDelta > 0.0) inside = !inside;
        }
    }
    return inside ? -distance : distance;
}
