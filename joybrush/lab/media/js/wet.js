// wet.js — fluid media (watercolour, ink wash; gouache later as settings): the brush, its stroke → dab
// builder, and the simulation constants. The water itself is simulated on the GPU (jb_wet_*.frag).
//
// The brush holds water and pigment and EXCHANGES them with the paper by wetness: loaded brush on dry
// paper gives; a drier brush pressed into a puddle takes water (and pigment) back. One state per brush
// (no pickup-vs-reservoir buffers: patent guard, MEDIA_ENGINE_PLAN §1.2).

const D2R = Math.PI / 180;

// Simulation constants (sim seconds). Paper numbers scale them per paper (see wetUniforms).
export const WET = {
  dt: 1 / 120,          // sim seconds per substep
  substeps: 4,          // per frame
  flow: 30,             // how hard a height difference pushes water (1/s)
  keep: 0.9,            // viscosity: fraction of flow kept each substep (watery → near 1)
  pinMm: 0.22,          // head needed to wet DRY paper (surface tension on sized paper)
  dampS: 0.35,          // capillary saturation at which paper counts as damp
  minMm: 0.002,         // thinner than this is dry
  evap: 0.003,          // mm/s
  edgeEvap: 4.0,        // thin edges dry this much faster: the dark watercolour edge
  absorb: 0.25,         // 1/s before sizing (hard-sized watercolour paper keeps water on top for minutes)
  capMm: 0.08,          // water the paper holds when saturated
  wick: 5,              // capillary spread, cells²/s (stable while wick·dt ≤ 0.25)
  sDry: 0.03,           // 1/s
  settle: 0.08,         // 1/s (pigment stays afloat long enough to be carried to the drying edge)
  lift: 0.04,           // 1/s
  stainCarry: 0.12,
  filmMm: 0.12,         // thinner water clings to the paper (viscous drag) instead of flowing
  edgeDep: 5.0,         // pigment drops out this much faster at the drying edge
  mingle: 12,           // pigment spreading through connected water (cells²/s; stable while ·dt ≤ 0.25)
  fullMm: 0.4,          // a puddle this deep is "as wet as a full brush" (deeper than the tooth: washes lie level)
};

export const WET_BRUSHES = {
  'Round': { bellyMm: 3.0, tipMm: 0.3, waterPerMm: 0.09, capacityMm3: 120, load: 0.95, gran: 1.0, stain: 0.3, beadMm: 2.5, liftMm: 3, dwellMmPerS: 1.5 },
  'Wash': { bellyMm: 6.0, tipMm: 2.0, waterPerMm: 0.1, capacityMm3: 260, load: 1.0, gran: 1.0, stain: 0.3, beadMm: 3, liftMm: 4, dwellMmPerS: 2 },
  'Dry brush': { bellyMm: 3.5, tipMm: 0.8, waterPerMm: 0.05, capacityMm3: 90, load: 0.22, gran: 1.0, stain: 0.3, beadMm: 0, liftMm: 0, dwellMmPerS: 0.3 },
  'Water': { bellyMm: 4.0, tipMm: 0.6, waterPerMm: 0.1, capacityMm3: 120, load: 1.0, gran: 1.0, stain: 0, clear: true, beadMm: 2.5, liftMm: 3, dwellMmPerS: 1.5 },
  'Thirsty': { bellyMm: 3.0, tipMm: 0.6, waterPerMm: 0.05, capacityMm3: 90, load: 0.08, gran: 1.0, stain: 0, clear: true, beadMm: 0, liftMm: 0, dwellMmPerS: 0.5 },
};

// A paint = the colour a full-strength wash dries to on white. Transparent pigment: K from Beer–Lambert,
// a little scattering. Concentration is per mm of water, so a full brush's puddle dries to that colour.
export function paintFromColor(rgb, strength = 1) {
  const lin = rgb.map(c => (c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4)));
  const K = lin.map(r => -Math.log(Math.max(r, 0.015)) / 2);
  const refMm = 0.4;
  const meanK = (K[0] + K[1] + K[2]) / 3;
  return { K: K.map(k => k * strength / refMm), S: 0.04 * meanK * strength / refMm };
}

// Paper tilt → per-cell gravity drop. tiltDeg along dir (unit, doc space, pointing downhill).
export function tiltUniform(tiltDeg, dir, pxPerMm, gain = 0.6) {
  const s = Math.tan(Math.min(60, Math.max(0, tiltDeg)) * Math.PI / 180) * gain / pxPerMm;
  return [dir[0] * s, dir[1] * s];
}

export function wetUniforms(paper, w = WET) {
  const ph = paper.physical || {};
  const sizing = ph.sizing ?? 0.6, absorbency = ph.absorbency ?? 0.5, capacity = ph.capacity ?? 0.5, wick = ph.wickSpeed ?? 0.5;
  return {
    u_dt: w.dt, u_flow: w.flow, u_keep: w.keep, u_pinMm: w.pinMm * (0.4 + sizing), u_dampS: w.dampS, u_minMm: w.minMm,
    u_evap: w.evap, u_edgeEvap: w.edgeEvap, u_absorb: w.absorb * absorbency * 2 * (1 - 0.95 * sizing),
    u_capMm: w.capMm * capacity * 2, u_wick: Math.min(0.24 / w.dt, w.wick * wick * 2), u_sDry: w.sDry,
    u_settle: w.settle, u_lift: w.lift, u_stainCarry: w.stainCarry, u_toothMm: paper.toothMm,
    u_heightMean: paper.uniforms.u_paperHeightMean, u_fullMm: w.fullMm, u_filmMm: w.filmMm, u_edgeDep: w.edgeDep, u_mingle: Math.min(0.24 / w.dt, w.mingle),
  };
}

export class WetStroke {
  constructor(brush, paint, paper, pxPerMm, seed = 1) {
    this.brush = brush;
    this.paint = brush.clear ? { K: [0, 0, 0], S: 0 } : paint;
    this.paper = paper;
    this.pxPerMm = pxPerMm;
    this.water = brush.load;          // 0..1 of capacity
    this.pending = [];
    this.dirty = null;
    this.last = null;
    this.dir = null;
    this.carry = 0;
    this.seed = seed;
  }

  add(s) {
    const prev = this.last;
    this.last = s;
    if (!prev) return;
    const dx = s.x - prev.x, dy = s.y - prev.y, len = Math.hypot(dx, dy);
    if (len > 1e-6) {
      const t = [dx / len, dy / len];
      this.dir = this.dir ? norm2(lerp2(this.dir, t, 0.4)) : t;
    }
    if (!this.dir) this.dir = [Math.cos(s.az), Math.sin(s.az)];
    const b0 = this.halfWidth(prev), b1 = this.halfWidth(s);
    const spacingPx = Math.max(0.6, 0.22 * Math.min(b0, b1) * this.pxPerMm);
    let pos = spacingPx - this.carry;
    if (!this.started && s.p > 0.02) this.started = true;
    if (len < 1e-6) {
      // A brush resting in place keeps bleeding water into the paper.
      this.emit(s, s, 0, this.brush.dwellMmPerS * Math.max(0, (s.t - prev.t) / 1000));
      return;
    }
    while (pos <= len) {
      this.emit(prev, s, pos / len, spacingPx / this.pxPerMm);
      pos += spacingPx;
    }
    this.carry = len - (pos - spacingPx);
  }

  // Lift: what is left hanging on the tip drops where the stroke ends; a slow lift leaves more.
  finish() {
    const s = this.last;
    if (!s || !this.started) return;
    this.emit(s, s, 0, this.brush.liftMm * this.water * this.water);
  }

  halfWidth(s) {
    const b = this.brush;
    const p = Math.max(0, Math.min(1, s.p));
    // The tip touches first; pressing lays the belly down. Tilt lays more of the belly flat.
    const t = Math.min(1, s.tilt / (60 * D2R));
    return (b.tipMm + (b.bellyMm - b.tipMm) * Math.pow(p, 0.75)) * (1 + 0.35 * t);
  }

  emit(a, b, w, slideMm) {
    // The bead hanging at a loaded tip comes off over the first few millimetres: the stroke starts in a
    // pool that melts into the rest of it (not a stamped blob).
    this.travelled = (this.travelled || 0) + slideMm;
    const beadLen = 3;
    slideMm *= 1 + (this.brush.beadMm / beadLen) * this.water * Math.exp(-this.travelled / beadLen);
    const L = (x, y) => x + (y - x) * w;
    const s = { x: L(a.x, b.x), y: L(a.y, b.y), p: L(a.p, b.p), tilt: L(a.tilt, b.tilt) };
    if (s.p <= 0.002) return;
    const half = this.halfWidth(s);
    const along = half * (1.25 + 0.6 * Math.min(1, s.tilt / (60 * D2R)));
    const dryness = 1 - smooth(0.05, 0.45, this.water);
    const wet = this.water;
    this.pending.push([
      s.x, s.y, this.dir[0], this.dir[1],
      along, half, this.brush.waterPerMm, wet,
      this.paint.K[0], this.paint.K[1], this.paint.K[2], this.paint.S,
      slideMm, dryness, 1 - this.brush.stain, this.seed,
      0, 0, 0, 0,
    ]);
    // Water leaves the brush roughly in proportion to what it lays down on dry paper.
    const area = Math.PI * along * half * 0.6;
    const given = this.brush.waterPerMm * slideMm * wet * area * (1 - 0.5 * dryness);
    this.water = Math.max(0, this.water - given / this.brush.capacityMm3);
    const r = (along + 0.1) * this.pxPerMm + 2;
    const box = [s.x - r, s.y - r, s.x + r, s.y + r];
    this.dirty = this.dirty ? [Math.min(this.dirty[0], box[0]), Math.min(this.dirty[1], box[1]),
      Math.max(this.dirty[2], box[2]), Math.max(this.dirty[3], box[3])] : box;
  }

  take() {
    const out = { dabs: new Float32Array(this.pending.flat()), count: this.pending.length, dirty: this.dirty };
    this.pending = [];
    this.dirty = null;
    return out;
  }
}

const smooth = (a, b, x) => { const t = Math.min(1, Math.max(0, (x - a) / (b - a))); return t * t * (3 - 2 * t); };
const lerp2 = (a, b, t) => [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t];
const norm2 = v => { const l = Math.hypot(v[0], v[1]) || 1; return [v[0] / l, v[1] / l]; };
