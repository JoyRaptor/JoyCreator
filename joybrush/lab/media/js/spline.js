// spline.js — fast strokes without facets, and without smoothing.
//
// A pen reports ~100–240 points a second; a quick flick covers millimetres between reports, and joining
// them with straight lines shows the corners (the owner saw ~16 facets in one quick curve, 2026-10-06).
// This passes a centripetal Catmull–Rom curve THROUGH every real pen point: nothing is averaged away, a
// sharp turn the hand made stays sharp (centripetal = no overshoot, no loops at corners), and only the gaps
// between reports are filled. Stabilising is a separate, optional user setting, never this.
// Cost: the curve between two points needs the NEXT point, so the stroke trails the pen by one report.

export class SplineFeeder {
  constructor(sink, stepPx = 0.75) {
    this.sink = sink;      // receives dense samples {x, y, p, tilt, az, t}
    this.stepPx = stepPx;
    this.pts = [];
  }

  add(s) {
    const pts = this.pts;
    const last = pts[pts.length - 1];
    if (last && Math.hypot(s.x - last.x, s.y - last.y) < 1e-3) { pts[pts.length - 1] = s; return; }
    pts.push(s);
    if (pts.length === 1) { this.sink(s); return; }
    if (pts.length >= 3) {
      const n = pts.length;
      this.segment(pts[Math.max(0, n - 4)], pts[n - 3], pts[n - 2], pts[n - 1]);
    }
    if (pts.length > 4) pts.shift();
  }

  // Lift: draw the last stretch (its far end has no next point, so the curve just ends there).
  finish() {
    const pts = this.pts, n = pts.length;
    if (n >= 2) this.segment(pts[Math.max(0, n - 3)], pts[n - 2], pts[n - 1], pts[n - 1]);
    this.pts = [];
  }

  segment(p0, p1, p2, p3) {
    const d = (a, b) => Math.max(1e-4, Math.sqrt(Math.hypot(b.x - a.x, b.y - a.y)));   // centripetal: |Δ|^0.5
    const t0 = 0, t1 = t0 + (p0 === p1 ? d(p1, p2) : d(p0, p1)), t2 = t1 + d(p1, p2), t3 = t2 + (p2 === p3 ? d(p1, p2) : d(p2, p3));
    const len = Math.hypot(p2.x - p1.x, p2.y - p1.y);
    const steps = Math.max(1, Math.ceil(len / this.stepPx));
    // Endpoint duplicates mean "no neighbour": extend straight so the end is not bent.
    const P0 = p0 === p1 ? { x: 2 * p1.x - p2.x, y: 2 * p1.y - p2.y } : p0;
    const P3 = p2 === p3 ? { x: 2 * p2.x - p1.x, y: 2 * p2.y - p1.y } : p3;
    for (let i = 1; i <= steps; i++) {
      const u = i / steps, t = t1 + (t2 - t1) * u;
      const lerp = (a, b, ta, tb) => {
        const w = (t - ta) / (tb - ta);
        return { x: a.x + (b.x - a.x) * w, y: a.y + (b.y - a.y) * w };
      };
      const A1 = lerp(P0, p1, t0, t1), A2 = lerp(p1, p2, t1, t2), A3 = lerp(p2, P3, t2, t3);
      const B1 = lerp(A1, A2, t0, t2), B2 = lerp(A2, A3, t1, t3);
      const C = lerp(B1, B2, t1, t2);
      const az = p1.az + angleDelta(p1.az, p2.az) * u;
      this.sink({
        x: C.x, y: C.y,
        p: p1.p + (p2.p - p1.p) * u,
        tilt: p1.tilt + (p2.tilt - p1.tilt) * u,
        az, t: p1.t + (p2.t - p1.t) * u,
      });
    }
  }
}

function angleDelta(a, b) {
  let d = b - a;
  while (d > Math.PI) d -= 2 * Math.PI;
  while (d < -Math.PI) d += 2 * Math.PI;
  return d;
}
