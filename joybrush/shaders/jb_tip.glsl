// jb_tip.glsl — Joy Brush tip shape (JB-1.01). SHARED: included verbatim by the phone (OpenGL ES 3.0)
// and the PC Brush Lab (WebGL2). GLSL ES 3.00 functions only: no #version, no uniforms, no main.
//
// The owner's one-dialogue tip (blueprint §1, idea 1), with the research corrections:
//   corner  — superellipse exponent: 2 = circle/ellipse, 1 = diamond, 4 ≈ rounded square, 16+ ≈ square.
//             (A superellipse, not "rounded corners": only this matches Photoshop's roundness exactly.)
//   taper   — 0 = none … 1 = the top end closes to a point (square → trapezoid → triangle;
//             circle → egg → teardrop).
//   aspect  — -1 … +1: 0 = as designed, → +1 tall and thin, → -1 short and wide; ±1 is a razor.
//   angle   — rotation in radians (initial rotation + any dynamics, already summed by the caller).
//   minPx   — thinnest the tip may render. Thinner tips are drawn at minPx but FADED by the ratio,
//             so a razor tip thins out smoothly instead of flickering or vanishing.
//   hardness— 0 = soft all the way from the centre, 1 = hard edge (still antialiased).

struct JbTip {
    float radiusPx;  // half the nominal tip size, in target pixels
    float angle;
    float aspect;
    float corner;
    float taper;
    float hardness;
    float minPx;
};

// Normalised superellipse "radius" of local point q inside half-extents (hx, hy) with exponent n.
// < 1 inside, 1 on the edge.
float jb_superellipse(vec2 q, float hx, float hy, float n) {
    vec2 u = abs(q / vec2(hx, hy));
    // pow(0, n) is fine; guard n to a sane range so pow never sees a negative or zero exponent.
    float e = clamp(n, 0.5, 64.0);
    return pow(pow(u.x, e) + pow(u.y, e), 1.0 / e);
}

// Coverage (0..1) of the tip at offsetPx = (pixel centre − dab centre), in target pixels.
float jb_tipCoverage(vec2 offsetPx, JbTip t) {
    // Into the tip's own frame (undo the rotation).
    float c = cos(t.angle), s = sin(t.angle);
    vec2 q = vec2(c * offsetPx.x + s * offsetPx.y, -s * offsetPx.x + c * offsetPx.y);

    // Aspect → half-extents. +aspect squeezes x (tall & thin), -aspect squeezes y (short & wide).
    float a = clamp(t.aspect, -1.0, 1.0);
    float hx = t.radiusPx * (a > 0.0 ? 1.0 - a : 1.0);
    float hy = t.radiusPx * (a < 0.0 ? 1.0 + a : 1.0);

    // Razor safety: never thinner than minPx; fade by how much thinner it "should" be.
    // At exactly ±1 the true width is zero; a quarter-pixel floor keeps a faint hairline visible.
    float halfMin = max(t.minPx, 0.5) * 0.5;
    float fade = min(max(hx, 0.25) / halfMin, 1.0) * min(max(hy, 0.25) / halfMin, 1.0);
    hx = max(hx, halfMin);
    hy = max(hy, halfMin);

    // Taper: width shrinks linearly from the bottom (y = -hy, full width) to the top (y = +hy).
    float along = clamp(q.y / hy * 0.5 + 0.5, 0.0, 1.0);
    float widthScale = max(1.0 - clamp(t.taper, 0.0, 1.0) * along, 1e-3);

    float d = jb_superellipse(q, hx * widthScale, hy, t.corner);

    // Soft edge from `hardness` to the rim, plus a one-pixel antialiasing band from fwidth.
    float aa = max(fwidth(d), 1e-4);
    float inner = min(clamp(t.hardness, 0.0, 1.0), 1.0 - aa);
    float cov = 1.0 - smoothstep(inner, 1.0 + 0.5 * aa, d);
    return cov * fade;
}
