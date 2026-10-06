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
  // Proto: a soft woodless stick with a blunt, worn cone, the owner's Infinite Painter reference.
  'Proto': { leadD: 5.0, coneDeg: 20, tipR: 0.14, exposedMm: 9, facetMm: 0.22, soft: 0.75, rInf: 0.04 },
  '2H': { leadD: 2.0, coneDeg: 11, tipR: 0.12, exposedMm: 7, facetMm: 0.05, soft: 0.15, rInf: 0.26 },
  'HB': { leadD: 2.0, coneDeg: 12, tipR: 0.15, exposedMm: 7, facetMm: 0.06, soft: 0.35, rInf: 0.14 },
  '2B': { leadD: 2.0, coneDeg: 13, tipR: 0.18, exposedMm: 7, facetMm: 0.08, soft: 0.5, rInf: 0.095 },
  '6B': { leadD: 2.2, coneDeg: 15, tipR: 0.22, exposedMm: 8, facetMm: 0.1, soft: 0.8, rInf: 0.06 },
  'Woodless 8B': { leadD: 7.0, coneDeg: 18, tipR: 0.25, exposedMm: 22, facetMm: 0.15, soft: 0.95, rInf: 0.045 },
};

// Engine numbers derived from a stick + paper. Everything the shaders need per stroke.
export function stickMaterial(stick) {
  const s = stick.soft;
  const rInf = [stick.rInf, stick.rInf, Math.min(0.9, stick.rInf * 1.08)]; // graphite is faintly cool
  const capMm = 0.004;                     // deposit volume the tooth holds (mm); optics scale with it
  // Optics of a FULL tooth: graphite is a dark, weakly scattering film. Spreading its density over the
  // whole deposit range keeps light strokes light; the floor rInf is only reached when the tooth is full.
  const sAtCap = 0.32;
  const K = rInf.map(r => ((1 - r) * (1 - r)) / (2 * r) * sAtCap / capMm);
  const S = rInf.map(() => sAtCap / capMm);
  return {
    capMm,
    abrasion: 20 + 160 * s * s,              // 1/mm²: how readily the stick sheds onto the tooth
    pigK: K, pigS: S,
    crushRate: 26 * (1.15 - s),            // hard leads dent and burnish the tooth more
    crushStart: 0.45,                      // × tooth depth
    crushMax: 0.6,
    smear: 0.004 + 0.03 * s * s,           // soft graphite drags a little of what is already down (per mm)
    smearMm: 0.12 + 0.25 * s,
    dustRate: 0.00004 + 0.00022 * s,       // mm of deposit per mm slid, into unreached pits
    dirStrength: 1.6,
    conform: 0.8,                          // the sheet bends onto a broad stick: big hills don't decide contact
    sheen: 0.03 + 0.10 * s,
  };
}

// Geometry of the stick's underside for a given tilt (rad from vertical).
export function stickGeometry(stick, tilt, pressure = 1) {
  const alpha = stick.coneDeg * D2R;
  const theta = Math.min(Math.max(tilt, 0), Math.PI / 2 - alpha);   // flatter than its own side is impossible
  // A tilted pencil held LIGHTLY settles onto its side under its own weight; pressing drives the force into
  // the tip and lifts the far end. So in the side range, the lighter the touch the flatter the lead lies:
  // the faintest strokes are the widest (the owner's soft-shading sweep that fans out as it fades,
  // 2026-10-06). Upright strokes are untouched.
  const side = smoothstep(0.45, 0.9, theta / (Math.PI / 2 - alpha));
  const settle = 1 - side * (1 - Math.pow(Math.max(0, Math.min(1, pressure)), 0.75));
  // No hand holds a lead perfectly flat: the flattest it gets still rises ~0.3° along its length.
  const beta = Math.max(0.3 * D2R, (Math.PI / 2 - theta - alpha) * settle);
  const betaF = Math.min(Math.PI / 2 - 1e-3, Math.PI / 2 + theta - alpha);
  return {
    tanB: Math.tan(beta),
    tanBf: Math.tan(betaF),
    tipR: stick.tipR,
    tanA: Math.tan(alpha) / Math.cos(beta),
    rhoMax: stick.leadD / 2,
    xLimit: stick.exposedMm * Math.cos(beta),
    facet: stick.facetMm * (1 - Math.min(1, Math.tan(beta) / 0.35)),   // the worn flat only meets the paper near flat
  };
}

function smoothstep(a, b, x) { const t = Math.min(1, Math.max(0, (x - a) / (b - a))); return t * t * (3 - 2 * t); }
function circ(y, rho) { const q = rho * rho - y * y; return q > 0 ? rho - Math.sqrt(q) : INF; }
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
    across = Math.max(0, across - g.facet * Math.min(1, Math.max(0, 1 - along / Math.max(g.facet * 4, 1e-3))));
  } else {
    along = ax * g.tanBf;
  }
  return Math.min(zTip, along + across);
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
  gamma: 1.6,           // pressure curve
  toothStiffness: 9,    // tooth vs pad stiffness: higher = the tooth resists, so light strokes stay grainy
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
  const key = press.toothStiffness + ':' + press.lutMaxMm + ':' + paper.compliance;
  if (paper.lut && paper.lutKey === key) return paper.lut;
  const T = paper.toothMm, psi = makePsiTable(paper);
  const kPad = 1 / Math.max(0.05, paper.compliance);
  const a = new Float32Array(LUT_N + 1), p = new Float64Array(LUT_N + 1);
  for (let i = 0; i <= LUT_N; i++) {
    const c = i / LUT_N * press.lutMaxMm;
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
  const f = Math.min(LUT_N, c / maxMm * LUT_N), i = Math.min(LUT_N - 1, Math.floor(f)), w = f - i;
  return arr[i] * (1 - w) + arr[i + 1] * w;
}

// Solve the depth D (mm below the undeformed tooth tops) the stick's lowest point reaches for the pen's force.
export function solveContact(g, paper, pressure, press = PRESS) {
  const P = Math.max(0, Math.min(1, pressure));
  const target = press.forceScale * Math.pow(P, press.gamma);
  if (target <= 0) return null;
  const lut = contactLut(paper, press);
  const makeGrid = D => {
    const ex = extents(g, D);
    const nx = 40, ny = 14, grid = [];
    const dx = (ex.xMax - ex.xMin) / nx, dy = ex.yMax / ny;
    for (let i = 0; i < nx; i++) {
      const x = ex.xMin + (i + 0.5) * dx;
      for (let j = 0; j < ny; j++) {
        const z = stickZ(x, (j + 0.5) * dy, g);
        if (z < INF * 0.5) grid.push({ z, dA: 2 * dx * dy });
      }
    }
    return grid;
  };
  const force = (D, grid) => {
    let f = 0;
    for (const c of grid) if (D > c.z) f += lutAt(lut.p, lut.maxMm, D - c.z) * c.dA;
    return f;
  };
  let dHi = 0.05, grid = makeGrid(dHi);
  for (let k = 0; k < 30 && force(dHi, grid) < target; k++) { dHi *= 1.5; grid = makeGrid(dHi); }
  let lo = 0, hi = dHi;
  for (let k = 0; k < 24; k++) {
    const mid = 0.5 * (lo + hi);
    if (force(mid, grid) < target) lo = mid; else hi = mid;
  }
  const D = Math.min(0.5 * (lo + hi), lut.maxMm);
  return { d: D, ...extents(g, D) };
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
      gx('tanB'), gx('tanBf'), gx('tipR'), 0,
      gx('tanA'), gx('rhoMax'), gx('xLimit'), slideMm,
      this.travel[0], this.travel[1], gx('facet'), margin,
    ];
    this.pending.push(inst);
    const r = Math.max(Math.abs(inst[4]), Math.abs(inst[5]), inst[6]) + margin;
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
