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
        // Wear grinds a flat along the bottom generator; it only bites where the side is near the paper.
        across = max(0.0, across - facet * clamp(1.0 - along / max(facet * 4.0, 1.0e-3), 0.0, 1.0));
    } else {
        along = ax * tanBf;
    }
    return min(zTip, along + across);
}
