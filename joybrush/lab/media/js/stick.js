// stick.js — dry media (graphite, charcoal, pastel): where the stick touches the paper and how hard, and
// the stroke → dab builder. CPU half of shaders/media/jb_stick.glsl (jb_stickSqueeze) — keep them in step.
//
// The contact is three overlapping zones, ALL anchored at the pen tip (the owner, 2026-10-07, from
// Infinite Painter's Proko pencil and from how a real pencil wears):
//   point  — the rounded tip, 0.5 → 2 mm across with pressure; always there, the hardest edge;
//   side 1 — the worn "most used" part of the side, 4–6 mm, a strong gradient from the tip; it comes in
//            at middle tilts;
//   side 2 — the whole side of the stick, about half an inch (an inch woodless), for feathering; it comes
//            in near flat.
// Each zone carries part of the squeeze at the tip and fades toward the barrel on its own curve, so a side
// stroke has several stacked grades: darkest at the tip edge, softer and softer toward the far edge. Only
// the flattest, lightest angle evens it out. Pressure sets the squeeze (darkness, how far the grain fills);
// tilt sets which zones are down (size and gradient). Nothing is ever placed away from the pen tip.
// (Version 7 modelled worn flats geometrically; they lifted the tip region, flipped the gradient and made
// the mark jump away from the pen. Replaced 2026-10-07.)

const D2R = Math.PI / 180;

// Grades. soft 0 (9H) .. 1 (9B). rInf: how dark its flakes are. Zone sizes in mm:
//   tipR → tipMax: the point's radius from a feather touch to full pressure
//   side1: the worn usage face's length; side2: the whole side that can touch; face: the face's half-width
export const STICKS = {
  // Proto: a soft woodless stick, the owner's Infinite Painter "Proko" reference.
  'Proto': { tipR: 0.25, tipMax: 1.0, side1: 5.5, side2: 16, face: 1.6, soft: 0.75, rInf: 0.006 },
  '2H': { tipR: 0.12, tipMax: 0.35, side1: 2.2, side2: 5, face: 0.6, soft: 0.15, rInf: 0.22 },
  'HB': { tipR: 0.14, tipMax: 0.45, side1: 2.6, side2: 5.5, face: 0.7, soft: 0.35, rInf: 0.12 },
  '2B': { tipR: 0.17, tipMax: 0.6, side1: 3.0, side2: 6, face: 0.8, soft: 0.5, rInf: 0.07 },
  '6B': { tipR: 0.2, tipMax: 0.7, side1: 3.5, side2: 7, face: 0.9, soft: 0.8, rInf: 0.03 },
  'Woodless 8B': { tipR: 0.3, tipMax: 1.2, side1: 6, side2: 24, face: 2.0, soft: 0.95, rInf: 0.006 },
};

// When each zone comes in, as a fraction t of the pen tilt range (0 upright … 1 the flattest the device
// reports). Below side1From the pencil draws with its point only: people rarely hold a pen exactly upright,
// and a slightly leaning pencil still draws a crisp line. Tunable here and (lab) with ?zones=.
export const ZONES = {
  side1From: 0.5, side1Full: 0.78,
  side2From: 0.7, side2Full: 0.98,
  evenFrom: 0.88,        // from here to flat, a light touch lays the side down evenly
  grad1: 1.2,            // fade curve of side 1 (higher = darker near the tip, quicker fade)
  grad2: 0.9,            // fade curve of side 2 when not lying flat
};

// Tuning hook (lab only): numbers set here override the derived material (see main.js ?mat=).
export const MAT_OVERRIDE = {};

// Engine numbers derived from a stick. Everything the shaders need per stroke.
export function stickMaterial(stick) {
  const s = stick.soft;
  const capMm = 0.004;                     // deposit volume the tooth holds (mm)
  // Optics: graphite is opaque grey FLAKES covering the paper by area, not a see-through film (a film model
  // went near-black from a trace of graphite: the owner's "100% black dots", 2026-10-06).
  return {
    capMm,
    flakeR: stick.rInf,
    abrasion: 400 * (0.25 + 0.75 * s * s),  // 1/mm²: how readily the stick sheds onto the tooth
    // Graphite shears off a tooth tip steeply with how hard that tip is pressed: a feather touch leaves a
    // thin film (soft grey grain that builds over passes), a firm one a thick one. Work ∝ (δ/T)^n · T.
    transferExp: 2.2,
    // A soft lead gives a little where it meets a tip, so contact spreads gradually over neighbouring
    // heights instead of switching on (× tooth depth): soft, not pin-sharp, grain.
    leadSoft: 0.08 + 0.22 * s,
    plateau: 2.2,                          // depth below the tooth top = (1 − h)^plateau (see jb_dry_dab.frag)
    crushRate: 26 * (1.15 - s),            // hard leads dent and burnish the tooth more
    crushStart: 0.45,                      // × tooth depth
    crushMax: 0.6,
    // Going back over graphite drags it along and softens it into its neighbours: a soft dusting rather
    // than dots (the owner asked for more, 2026-10-07).
    smear: 0.18 + 0.6 * s * s,
    smearMm: 0.15 + 0.3 * s,
    dustRate: 0.0006 + 0.0024 * s,         // dusting: mm of graphite per mm slid at full contact (soft grades shed more)
    clump: 0.35,                           // pressed graphite piles on the tooth's small hills
    // A light touch only dusts (soft, even, low-contrast grey); piling (dark, clumped, contrasty) comes in
    // with pressure. Proko's light strokes are a grey dusting, its firm ones dark grain (2026-10-07).
    pileRange: [0.12, 0.75],
    dirStrength: 0.9,
    conform: 0.8,                          // the sheet bends onto a broad stick: big hills don't decide contact
    sheen: 0.03 + 0.10 * s,
    ...MAT_OVERRIDE,
  };
}

function smoothstep(a, b, x) { const t = Math.min(1, Math.max(0, (x - a) / (b - a))); return t * t * (3 - 2 * t); }

// The contact for one pen sample: how hard at the tip (D, mm of squeeze), the zone sizes (mm) and how much
// of the squeeze each zone carries (weights sum to 1), plus the extents of the footprint.
export function stickContact(stick, tilt, pressure, paper, press = PRESS, zones = ZONES) {
  const P = Math.max(0, Math.min(1, pressure));
  if (P <= 0) return null;
  const t = Math.max(0, Math.min(1, tilt / (Math.PI / 2)));
  const s1 = smoothstep(zones.side1From, zones.side1Full, t);
  const s2 = smoothstep(zones.side2From, zones.side2Full, t);
  // Near flat and lightly held, the side lies down evenly and the tip no longer dominates ("if it is an
  // even pressure or the tilt is close to flat, then you won't have the hard edge at the tip").
  const even = smoothstep(zones.evenFrom, 1.0, t) * (1 - 0.6 * P);
  const R0 = stick.tipR + (stick.tipMax - stick.tipR) * Math.pow(P, 0.8);
  const L1 = R0 + (stick.side1 - R0) * s1;
  const L2 = L1 + (stick.side2 - L1) * s2;
  let w0 = 1 - 0.8 * even, w1 = 0.9 * s1 * (1 - 0.35 * s2), w2 = 0.8 * s2;
  const sum = w0 + w1 + w2;
  w0 /= sum; w1 /= sum; w2 /= sum;
  const D = paper.toothMm * press.depth * Math.pow(P, press.gamma) / (1 + press.sideEase * (0.4 * s1 + s2));
  const sideOn = w1 + w2 > 0.005;
  return {
    D, R0, L1, L2, H: sideOn ? stick.face : R0, w0, w1, w2, even,
    xMin: -R0, xMax: sideOn ? Math.max(R0, w2 > 0.005 ? L2 : L1) : R0, yMax: sideOn ? Math.max(R0, stick.face) : R0,
  };
}

// Twin of jb_stickSqueeze (for tests and tools): the squeeze (mm) at a stick-frame point (x toward the
// barrel, y across).
export function stickSqueeze(x, y, c, zones = ZONES) {
  const point = Math.max(0, 1 - (x * x + y * y) / (c.R0 * c.R0));
  const front = x < 0 ? Math.max(0, 1 - (x * x) / (c.R0 * c.R0)) : 1;
  const ramp = (L, g, ev) => {
    if (x < 0) return 1;
    const u = x / Math.max(L, 1e-3);
    if (u >= 1) return 0;
    return (1 - ev) * Math.pow(1 - u, g) + ev * (1 - smoothstep(0.45, 1, u));
  };
  const across = L => {
    const Hw = c.R0 + (c.H - c.R0) * smoothstep(0, 0.3 * L, Math.max(x, 0));
    return Math.sqrt(Math.max(0, 1 - (y * y) / (Hw * Hw)));
  };
  const g2 = zones.grad2 + (0.55 - zones.grad2) * c.even;
  return c.D * (c.w0 * point + (c.w1 * ramp(c.L1, zones.grad1, 0) * across(c.L1) + c.w2 * ramp(c.L2, g2, c.even) * across(c.L2)) * front);
}

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
  depth: 1.9,           // squeeze at the tip at full pressure, in tooth depths (fills the grain)
  gamma: 1.5,           // pressure curve: a long, gentle light end (owner: "slowly build up value")
  sideEase: 0.45,       // the same hand force spread along the side squeezes less: tip depth / (1 + this·side)
  toothStiffness: 9,    // tooth vs pad stiffness: higher = the tooth resists, so light strokes stay grainy
  padSoft: 1,           // how soft the pad under the sheet is (× paper compliance)
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

// One stroke of a dry stick. Samples in → instanced dabs out (20 floats each, see jb_dry_dab.vert).
export class DryStroke {
  constructor(stick, paper, pxPerMm, press = PRESS) {
    this.stick = stick;
    this.paper = paper;
    this.pxPerMm = pxPerMm;
    this.press = press;
    this.mat = stickMaterial(stick);
    contactLut(paper, press);
    this.last = null;
    this.pending = [];
    this.dirty = null;
    this.travel = [1, 0];
    this.carry = 0;           // distance (px) since the last dab
  }

  solve(s) {
    return { s, c: stickContact(this.stick, s.tilt, s.p, this.paper, this.press), lean: [Math.cos(s.az), Math.sin(s.az)] };
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
    const some = ca || cb;
    const zero = { D: 0, R0: some.R0, L1: some.L1, L2: some.L2, H: some.H, w0: 1, w1: 0, w2: 0, even: 0, xMin: 0, xMax: 0, yMax: 0 };
    const A = ca || zero, B = cb || zero;
    const pick = k => L(A[k], B[k]);
    let lx = L(a.lean[0], b.lean[0]), ly = L(a.lean[1], b.lean[1]);
    const ll = Math.hypot(lx, ly) || 1; lx /= ll; ly /= ll;
    const cx = L(a.s.x, b.s.x), cy = L(a.s.y, b.s.y);
    const inst = [
      cx, cy, lx, ly,
      Math.min(A.xMin, B.xMin), Math.max(A.xMax, B.xMax), Math.max(A.yMax, B.yMax), pick('D'),
      pick('R0'), pick('L1'), pick('L2'), pick('H'),
      pick('w0'), pick('w1'), pick('w2'), slideMm,
      this.travel[0], this.travel[1], pick('even'), L(a.s.p, b.s.p),
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
