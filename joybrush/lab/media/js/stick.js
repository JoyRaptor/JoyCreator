// stick.js — dry media (graphite, charcoal, pastel): stick geometry, the force balance that decides how
// deep the stick sinks into the paper, and the stroke → dab builder. CPU twin of shaders/media/jb_stick.glsl.
//
// Force balance: the paper's tooth is a field of tiny springs (Hertzian: force ∝ penetration^1.5). The
// stick sinks until the springs push back with the pen's force. A small point under full force goes deep
// (dark, filled, grain pressed flat); the same force spread along a lead lying on its side barely gets
// past the tooth tops (light, grainy). Tilt changes the shape, so tilt mostly sets SIZE; pressure sets
// how deep, so pressure mostly sets DARKNESS and how much of the grain fills in.

const INF = 1e6;
const D2R = Math.PI / 180;

// Grades. soft: 0 (9H) .. 1 (9B). rInf: darkest the material can look (its reflectance when thick).
export const STICKS = {
  // Proto: a soft woodless stick (all graphite, ~7 mm, lacquered) with a blunt worn cone: the owner's
  // Infinite Painter "Proko" reference. Laid on its side it can touch along about an inch.
  // facets: worn flats [angle to the paper when worn (°), how far back along the side (mm)], steepest first.
  'Proto': { leadD: 7.0, coneDeg: 20, tipR: 0.28, exposedMm: 24, soft: 0.75, rInf: 0.006, facets: [[12, 1.5], [5, 3.0], [1.5, 6.0]], facetRound: 0.05 },
  '2H': { leadD: 2.0, coneDeg: 11, tipR: 0.12, exposedMm: 7, soft: 0.15, rInf: 0.26, facets: [[14, 0.5], [6, 1.1]], facetRound: 0.02 },
  'HB': { leadD: 2.0, coneDeg: 12, tipR: 0.15, exposedMm: 7, soft: 0.35, rInf: 0.14, facets: [[14, 0.6], [6, 1.3]], facetRound: 0.02 },
  '2B': { leadD: 2.0, coneDeg: 13, tipR: 0.18, exposedMm: 7, soft: 0.5, rInf: 0.095, facets: [[13, 0.7], [5, 1.5]], facetRound: 0.03 },
  '6B': { leadD: 2.2, coneDeg: 15, tipR: 0.22, exposedMm: 8, soft: 0.8, rInf: 0.06, facets: [[12, 0.8], [5, 1.8]], facetRound: 0.03 },
  'Woodless 8B': { leadD: 7.0, coneDeg: 18, tipR: 0.25, exposedMm: 24, soft: 0.95, rInf: 0.06, facets: [[10, 2.0], [4, 4.0], [1.2, 9.0]], facetRound: 0.06 },
};

// Tuning hook (lab only): numbers set here override the derived material (see main.js ?mat=).
export const MAT_OVERRIDE = {};

// Engine numbers derived from a stick + paper. Everything the shaders need per stroke.
export function stickMaterial(stick) {
  const s = stick.soft;
  const capMm = 0.004;                     // deposit volume the tooth holds (mm)
  // Optics: graphite is opaque grey FLAKES covering the paper by area (render: cover = 1 − e^(−V/0.4cap)),
  // not a see-through film. A film model went near-black from a trace of graphite: the owner's "100% black
  // dots" (2026-10-06). flakeR is how dark the stick's own flakes are (soft grades darker).
  return {
    capMm,
    flakeR: stick.rInf,
    abrasion: 400 * (0.25 + 0.75 * s * s),  // 1/mm²: how readily the stick sheds onto the tooth (calibrated on drawing_tooth, 2026-10-06)
    // Graphite shears off a tooth tip steeply with how hard that tip is pressed: a feather touch leaves a
    // thin film (soft grey grain that builds over passes), a firm one a thick one. Work ∝ (δ/T)^n · T.
    transferExp: 2.2,
    // A soft lead gives a little where it meets a tip, so contact spreads gradually over neighbouring
    // heights instead of switching on (× tooth depth): soft, not pin-sharp, grain.
    leadSoft: 0.08 + 0.22 * s,
    plateau: 2.2,                          // depth below the tooth top = (1 − h)^plateau (see jb_dry_dab.frag)
    // Worn flats for the shader (up to three; u = 0 means none).
    facetBeta: [0, 1, 2].map(i => (stick.facets && stick.facets[i] ? stick.facets[i][0] * D2R : 0)),
    facetU: [0, 1, 2].map(i => (stick.facets && stick.facets[i] ? stick.facets[i][1] : 0)),
    facetRound: stick.facetRound || 0.03,
    crushRate: 26 * (1.15 - s),            // hard leads dent and burnish the tooth more
    crushStart: 0.45,                      // × tooth depth
    crushMax: 0.6,
    smear: 0.08 + 0.25 * s * s,            // soft graphite drags and softens what is already down (per mm rubbed)
    smearMm: 0.12 + 0.25 * s,
    dustRate: 0.0006 + 0.0024 * s,         // dusting: mm of graphite per mm slid at full contact (soft grades shed more)
    clump: 0.6,                            // pressed graphite piles on the coarse hills
    dirStrength: 0.9,
    conform: 0.8,                          // the sheet bends onto a broad stick: big hills don't decide contact
    sheen: 0.03 + 0.10 * s,
    ...MAT_OVERRIDE,
  };
}

// Geometry of the stick's underside for a given tilt (rad from vertical).
// How the pen's tilt lays the stick down. t = pen tilt as a fraction of "lying on its side" (0 upright,
// 1 = the device's flattest). The angle of the lead's lower side to the paper is β = (90° − cone)·(1 − t)^q:
// a hand drawing a little off vertical still draws with the point (t 0.3 → β ≈ 29°: point only); the side
// opens up gradually over the last part of the range (t 0.7 → 3.5°: a short side with a strong gradient;
// t 0.9 → 0.2°: lying flat, a wide even swath). q is the S-curve's shape (the owner wants it adjustable).
export const TILT_CURVE = { q: 3.0 };

export function stickGeometry(stick, tilt, pressure = 1) {
  const alpha = stick.coneDeg * D2R;
  const t = Math.min(1, Math.max(0, tilt / (Math.PI / 2)));
  const theta = (Math.PI / 2 - alpha) * (1 - Math.pow(1 - t, TILT_CURVE.q));
  // A tilted pencil held LIGHTLY settles onto its side under its own weight; pressing drives the force into
  // the tip and lifts the far end. So in the side range, the lighter the touch the flatter the lead lies:
  // the faintest strokes are the widest (the owner's soft-shading sweep that fans out as it fades,
  // 2026-10-06). Upright strokes are untouched.
  const P = Math.max(0, Math.min(1, pressure));
  // Laid on its side, a lightly held stick settles almost parallel to the paper and touches along its
  // whole length: wide, even and soft. Pressing tips the force toward the point and lifts the far end a
  // little: a harder edge at the tip and a shorter side (the owner: "if it is an even pressure or the
  // tilt is close to flat, then you won't have the hard edge at the tip").
  // Only when nearly flat: a hand holding a pencil at a middle angle keeps that angle however lightly it draws.
  const side = smoothstep(0.65, 0.9, t);
  const settle = 1 - side * (1 - P);
  const beta = Math.max(0.005 * D2R, (Math.PI / 2 - theta - alpha) * settle);
  const betaF = Math.min(Math.PI / 2 - 1e-3, Math.PI / 2 + theta - alpha);
  return {
    tanB: Math.tan(beta),
    tanBf: Math.tan(betaF),
    tipR: stick.tipR,
    tanA: Math.tan(alpha) / Math.cos(beta),
    rhoMax: stick.leadD / 2,
    xLimit: stick.exposedMm * Math.cos(beta),
    facet: 0,
    facets: (stick.facets || []).map(([deg, u]) => [deg * D2R, u]),
    facetRound: stick.facetRound || 0.03,
  };
}

function smoothstep(a, b, x) { const t = Math.min(1, Math.max(0, (x - a) / (b - a))); return t * t * (3 - 2 * t); }
function circ(y, rho) { const q = rho * rho - y * y; return q > 0 ? rho - Math.sqrt(q) : INF; }
// Twins of jb_smax / jb_wear (worn flats, see jb_stick.glsl).
function smax(a, b, r) { const h = Math.max(r - Math.abs(a - b), 0) / Math.max(r, 1e-6); return Math.max(a, b) + h * h * r * 0.25; }
function wear(x, z, g) {
  const beta = Math.atan(g.tanB);
  const w = 1 - smoothstep(0.31, 0.49, beta);
  if (w <= 0 || !g.facets) return z;
  const sb = Math.sin(beta), cb = Math.cos(beta);
  for (const [bj, uj] of g.facets) {
    const zf = uj * sb + (x - uj * cb) * Math.tan(beta - bj);
    z = smax(z, zf - (1 - w) * 10, g.facetRound);
  }
  return z;
}
// Twin of jb_conform: the sheet's give takes up the first ~eps mm of the stick's rise.
export function conform(z, eps) { return eps > 0 ? z - eps * (1 - Math.exp(-z / eps)) : z; }
// Twin of jb_stickZ.
export function stickZ(x, y, g) {
  const r2 = x * x + y * y;
  const zTip = r2 < g.tipR * g.tipR ? g.tipR - Math.sqrt(g.tipR * g.tipR - r2) : INF;
  const ax = Math.abs(x);
  const rho = Math.min(g.rhoMax, g.tipR + ax * g.tanA);
  let across = circ(y, rho);
  let along;
  if (x >= 0) {
    if (x > g.xLimit) return zTip;
    along = x * g.tanB;
  } else {
    along = ax * g.tanBf;
  }
  const z = wear(x, Math.min(zTip, along + across), g);
  const top = along + rho + Math.sqrt(Math.max(rho * rho - y * y, 0));
  return z > top ? INF : z;
}

// Footprint extents (mm, stick frame) where the underside is below depth dz (in stick-z units).
function extents(g, dz) {
  const xBack = g.tanB > 1e-4 ? Math.min(g.xLimit, Math.max(g.tipR, (dz + g.facet) / g.tanB + g.tipR)) : g.xLimit;
  const xFront = -Math.min(g.tipR, Math.sqrt(Math.max(0, 2 * g.tipR * dz))) - dz / Math.max(g.tanBf, 0.05);
  const yMax = Math.min(g.rhoMax, Math.sqrt(2 * g.rhoMax * (dz + g.facet)) + 0.02);
  return { xMin: xFront, xMax: xBack, yMax };
}

// Hertzian contact table: Ψ(a) = E[max(0, a − u)^1.5], u = depth below tooth tops (tooth units).
export function makePsiTable(paper) {
  if (paper.psi) return paper.psi;
  const phi = paper.phi;   // only for meanU; rebuild from the histogram-free route: sample Φ' numerically
  const N = 256, t = new Float64Array(N + 1);
  // d/da Φ(a) = P(u < a) = CDF of u. Recover the CDF from Φ by finite differences, then integrate.
  const cdf = new Float64Array(N + 1);
  for (let i = 0; i <= N; i++) {
    const a0 = Math.max(0, i - 0.5) / N, a1 = Math.min(N, i + 0.5) / N;
    cdf[i] = Math.min(1, Math.max(0, (phiLin(phi, a1) - phiLin(phi, a0)) / Math.max(a1 - a0, 1e-9)));
  }
  for (let i = 0; i <= N; i++) {
    const a = i / N;
    let e = 0;
    // E[(a-u)^1.5 ; u<a] = ∫_0^a 1.5 (a-v)^0.5 CDF(v) dv
    const steps = Math.max(1, i);
    for (let k = 0; k < steps; k++) {
      const v = (k + 0.5) / steps * a;
      e += 1.5 * Math.sqrt(a - v) * cdf[Math.min(N, Math.round(v * N))] * (a / steps);
    }
    t[i] = e;
  }
  paper.psi = t;
  return t;
}
function phiLin(table, a) {
  const N = table.length - 1;
  if (a <= 0) return 0;
  if (a >= 1) return table[N] + (a - 1);
  const f = a * N, i = Math.floor(f), w = f - i;
  return table[i] * (1 - w) + table[i + 1] * w;
}
function psiAt(t, a) {
  const N = t.length - 1;
  if (a <= 0) return 0;
  if (a >= 1) { // beyond the deepest pit every point is in contact: (a-u)^1.5 ≈ grows like a^1.5
    return t[N] * Math.pow(a, 1.5);
  }
  const f = a * N, i = Math.floor(f), w = f - i;
  return t[i] * (1 - w) + t[i + 1] * w;
}

export const PRESS = {
  forceScale: 0.085,     // pen force at full pressure (pad-stiffness units: mm of pad squeeze × mm²)
  gamma: 2.6,           // pressure curve: a long, gentle light end (owner: "slowly build up value")
  toothStiffness: 9,    // tooth vs pad stiffness: higher = the tooth resists, so light strokes stay grainy
  broad: 0,             // force grows with contact area^broad (0: pressure means the same force on the side)
  area0: 0.05,          // mm²: below this the point counts as a point
  conformMm: 0.02,      // the sheet's give (× paper compliance): how much stick rise the paper takes up
  padSoft: 2,           // how soft the pad under the sheet is (× paper compliance): pillowy = long side contact
  lutMaxMm: 2.0,        // deepest squeeze tabulated
};

// ---- Two-layer paper: a stiff, rough tooth on a soft pad (springs in series) ----
// c = how far the stick has pushed the paper down at a point (mm below the undeformed tooth tops).
// The tooth takes c_t of it and the pad the rest; both carry the same pressure:
//   pad:   p = (c − c_t) / compliance          (a Winkler foundation: the sheet over its backing)
//   tooth: p = k · T · Ψ(c_t / T)               (Hertzian asperities; Ψ from the paper's own heights)
// A feather touch only squeezes the tooth tips. Firm pressure flattens the tooth and sinks the pad, so the
// sheet wraps the stick and the mark widens (the owner's "pillowy paper"). Along a tilted lead c falls
// from the tip to zero: a hard edge at the tip and a grainy fade along the side, with no special case.
export const LUT_N = 255;
export function contactLut(paper, press = PRESS) {
  const key = press.toothStiffness + ':' + press.lutMaxMm + ':' + paper.compliance + ':' + press.padSoft;
  if (paper.lut && paper.lutKey === key) return paper.lut;
  const T = paper.toothMm, psi = makePsiTable(paper);
  const kPad = 1 / Math.max(0.05, paper.compliance * press.padSoft);
  const a = new Float32Array(LUT_N + 1), p = new Float64Array(LUT_N + 1);
  // Square-root spacing: entry i is at c = (i/N)² · max, so light contacts (thousandths of a mm) get as
  // many steps as heavy ones; a uniform table put a whole light side stroke inside one or two steps.
  for (let i = 0; i <= LUT_N; i++) {
    const c = (i / LUT_N) * (i / LUT_N) * press.lutMaxMm;
    let lo = 0, hi = c;                       // pad pressure falls, tooth pressure rises with c_t
    for (let k = 0; k < 40; k++) {
      const ct = 0.5 * (lo + hi);
      if (kPad * (c - ct) > press.toothStiffness * T * psiAt(psi, ct / T)) lo = ct; else hi = ct;
    }
    const ct = 0.5 * (lo + hi);
    a[i] = ct / T;
    p[i] = kPad * (c - ct);
  }
  paper.lut = { a, p, maxMm: press.lutMaxMm };
  paper.lutKey = key;
  return paper.lut;
}
function lutAt(arr, maxMm, c) {
  if (c <= 0) return 0;
  const f = Math.min(LUT_N, Math.sqrt(c / maxMm) * LUT_N), i = Math.min(LUT_N - 1, Math.floor(f)), w = f - i;
  return arr[i] * (1 - w) + arr[i + 1] * w;
}

// Solve the depth D (mm below the undeformed tooth tops) the stick's lowest point reaches for the pen's force.
export function solveContact(g, paper, pressure, press = PRESS) {
  const P = Math.max(0, Math.min(1, pressure));
  const target = press.forceScale * Math.pow(P, press.gamma);
  if (target <= 0) return null;
  const lut = contactLut(paper, press);
  const makeGrid = D => {
    const ex = extents(g, D + (g.eps || 0));
    const nx = 40, ny = 14, grid = [];
    const dx = (ex.xMax - ex.xMin) / nx, dy = ex.yMax / ny;
    for (let i = 0; i < nx; i++) {
      const x = ex.xMin + (i + 0.5) * dx;
      for (let j = 0; j < ny; j++) {
        const z = stickZ(x, (j + 0.5) * dy, g);
        if (z < INF * 0.5) grid.push({ z: conform(z, g.eps || 0), dA: 2 * dx * dy });
      }
    }
    return grid;
  };
  // The pen's pressure is how hard the hand presses; a hand shading with the side of a stick bears down
  // over the whole length it feels, so the force it applies grows with the contact (area^broad).
  const excess = (D, grid) => {
    let f = 0, area = 0;
    for (const c of grid) if (D > c.z) { f += lutAt(lut.p, lut.maxMm, D - c.z) * c.dA; area += c.dA; }
    return f - target * Math.pow(Math.max(area, press.area0), press.broad);
  };
  let dHi = 0.05, grid = makeGrid(dHi);
  for (let k = 0; k < 30 && excess(dHi, grid) < 0; k++) { dHi *= 1.5; grid = makeGrid(dHi); }
  // The grid was sized for dHi; refine it to the contact actually reached so long side contacts keep detail.
  let lo = 0, hi = dHi;
  for (let k = 0; k < 24; k++) {
    const mid = 0.5 * (lo + hi);
    if (excess(mid, grid) < 0) lo = mid; else hi = mid;
  }
  const D = Math.min(0.5 * (lo + hi), lut.maxMm);
  return { d: D, ...extents(g, D + (g.eps || 0)) };
}

// One stroke of a dry stick. Samples in → instanced dabs out (20 floats each, see jb_dry_dab.vert).
export class DryStroke {
  constructor(stick, paper, pxPerMm, press = PRESS) {
    this.stick = stick;
    this.paper = paper;
    this.pxPerMm = pxPerMm;
    this.press = press;
    this.mat = stickMaterial(stick);
    this.last = null;
    this.pending = [];
    this.dirty = null;
    this.travel = [1, 0];
    this.carry = 0;           // distance (px) since the last dab
  }

  solve(s) {
    const g = stickGeometry(this.stick, s.tilt, s.p);
    g.eps = this.press.conformMm * this.paper.compliance;
    const c = solveContact(g, this.paper, s.p, this.press);
    return { s, g, c, lean: [Math.cos(s.az), Math.sin(s.az)] };
  }

  add(sample) {
    const cur = this.solve(sample);
    const prev = this.last;
    this.last = cur;
    if (!prev) return;
    const dx = cur.s.x - prev.s.x, dy = cur.s.y - prev.s.y;
    const len = Math.hypot(dx, dy);
    if (len > 1e-6) {
      const k = 0.35;   // light low-pass on the travel direction (pointer jitter)
      const tx = this.travel[0] * (1 - k) + dx / len * k, ty = this.travel[1] * (1 - k) + dy / len * k;
      const tl = Math.hypot(tx, ty) || 1;
      this.travel = [tx / tl, ty / tl];
    }
    if (!prev.c && !cur.c) return;
    // Spacing: a fraction of the narrowest contact so the slide integral is smooth.
    const width = c => (c ? Math.max(0.05, Math.min(c.xMax - c.xMin, 2 * c.yMax)) : 0.05);
    const spacingPx = Math.min(2.5, Math.max(0.35, 0.18 * Math.min(width(prev.c), width(cur.c)) * this.pxPerMm));
    const dwellMm = Math.max(0, (cur.s.t - prev.s.t)) * 0.00012;   // a held pencil still leaves a trace
    let pos = spacingPx - this.carry;
    if (len < 1e-6) {
      this.emit(cur, cur, 1, dwellMm);
      return;
    }
    while (pos <= len) {
      const w = pos / len;
      this.emit(prev, cur, w, spacingPx / this.pxPerMm + dwellMm * spacingPx / len);
      pos += spacingPx;
    }
    this.carry = len - (pos - spacingPx);
  }

  emit(a, b, w, slideMm) {
    const ca = a.c, cb = b.c;
    if (!ca && !cb) return;
    const L = (x, y) => x + (y - x) * w;
    const pick = (k) => (ca && cb ? L(ca[k], cb[k]) : (ca || cb)[k]);
    const ga = a.g, gb = b.g;
    const gx = k => L(ga[k], gb[k]);
    let lx = L(a.lean[0], b.lean[0]), ly = L(a.lean[1], b.lean[1]);
    const ll = Math.hypot(lx, ly) || 1; lx /= ll; ly /= ll;
    const cx = L(a.s.x, b.s.x), cy = L(a.s.y, b.s.y);
    const margin = 0.06;
    const inst = [
      cx, cy, lx, ly,
      pick('xMin'), pick('xMax'), pick('yMax'), (ca ? ca.d : 0) * (1 - w) + (cb ? cb.d : 0) * w,
      gx('tanB'), gx('tanBf'), gx('tipR'), gx('eps'),
      gx('tanA'), gx('rhoMax'), gx('xLimit'), slideMm,
      this.travel[0], this.travel[1], gx('facet'), L(a.s.p, b.s.p),
    ];
    this.pending.push(inst);
    const r = Math.max(Math.abs(inst[4]), Math.abs(inst[5]), inst[6]) + 0.06;
    const rp = r * this.pxPerMm + 2;
    const box = [cx - rp, cy - rp, cx + rp, cy + rp];
    this.dirty = this.dirty ? [Math.min(this.dirty[0], box[0]), Math.min(this.dirty[1], box[1]),
      Math.max(this.dirty[2], box[2]), Math.max(this.dirty[3], box[3])] : box;
  }

  take() {
    const out = { dabs: new Float32Array(this.pending.flat()), count: this.pending.length, dirty: this.dirty, travel: this.travel };
    this.pending = [];
    this.dirty = null;
    return out;
  }
}
