// jb_stick.glsl — the lower surface of a rigid drawing stick (graphite lead, charcoal, pastel).
// Functions only: NO #version, NO main. Include after jb_media_common.glsl.
// CPU twin: lab/media/js/stick.js stickZ() — keep the two identical.
//
// The stick is a rounded cone (tip radius tipR, half-angle α) that becomes a cylinder of radius rhoMax,
// tilted so its axis leans toward +x. q = (x along the lean, toward the barrel; y across), in mm,
// measured on the paper from the lowest point of the tip. Returns the height (mm) of the stick's
// underside above that lowest point. JB_INF = the stick is not over this point (or wood covers it).
//
//   tanB  — slope of the bottom generator (barrel side). 0 = lying flat on its side.
//   tanBf — slope of the front generator (the side facing away from the lean).
//   xLimit — exposed lead length projected on the paper; beyond it is wood (wood never draws).
//   facet — depth (mm) of the flat that wear has ground onto the side; widens a side stroke.
// The sheet gives: real paper is not flat to the micron and a pad under it yields, so the first ~eps mm of
// the stick's rise is taken up by the paper meeting it. Near the contact the underside reads flatter
// (z²/2eps), far from it the same shape shifted down by eps. This is why a side laid nearly flat touches
// along its length even at a feather touch: tilt sets the width, pressure the darkness (owner's rule).
float jb_conform(float z, float eps) {
    return eps > 0.0 ? z - eps * (1.0 - exp(-z / eps)) : z;
}

// Worn flats (the owner, 2026-10-06): a pencil used at its common angles wears a few flats onto its side,
// each slightly rounded into the next. Held at one of those angles, that flat lies even on the paper; between
// them, the neighbouring flats each add their own gradient: the fade is several stacked, not one line.
// Each flat j was worn with the lead at angle beta_j to the paper and reaches u_j mm back along the side.
// The worn underside is the smooth upper envelope of the cone and the flat planes (material below every
// plane is gone). Flats only matter near the side angles; held upright the point is the pristine tip.
uniform vec3 u_facetBeta;       // radians; a component with u = 0 is unused
uniform vec3 u_facetU;          // mm back along the side
uniform float u_facetRound;     // mm: how rounded the edges between flats are

float jb_smax(float a, float b, float r) {
    float h = max(r - abs(a - b), 0.0) / max(r, 1e-6);
    return max(a, b) + h * h * r * 0.25;
}
float jb_wear(float x, float z, float tanB) {
    float beta = atan(tanB);
    float w = 1.0 - smoothstep(0.31, 0.49, beta);          // 18°..28°: upright keeps its point
    if (w <= 0.0) return z;
    float sb = sin(beta), cb = cos(beta);
    for (int j = 0; j < 3; j++) {
        if (u_facetU[j] <= 0.0) continue;
        float zf = u_facetU[j] * sb + (x - u_facetU[j] * cb) * tan(beta - u_facetBeta[j]);
        z = jb_smax(z, zf - (1.0 - w) * 10.0, u_facetRound);
    }
    return z;
}

float jb_circ(float y, float rho) {
    float q = rho * rho - y * y;
    return q > 0.0 ? rho - sqrt(q) : JB_INF;
}
float jb_stickZ(vec2 q, float tanB, float tanBf, float tipR, float tanA, float rhoMax, float xLimit, float facet) {
    float x = q.x, y = q.y;
    float r2 = x * x + y * y;
    float zTip = r2 < tipR * tipR ? tipR - sqrt(tipR * tipR - r2) : JB_INF;
    float ax = abs(x);
    float rho = min(rhoMax, tipR + ax * tanA);
    float across = jb_circ(y, rho);
    float along;
    if (x >= 0.0) {
        if (x > xLimit) return zTip;
        along = x * tanB;
    } else {
        along = ax * tanBf;
    }
    float z = jb_wear(x, min(zTip, along + across), tanB);
    // Material check: a flat may have ground the lead away entirely here (no lead above this point).
    float top = along + rho + sqrt(max(rho * rho - y * y, 0.0));
    return z > top ? JB_INF : z;
}
