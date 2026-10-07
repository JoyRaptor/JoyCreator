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
  runMmPerS: 8,         // a full bead running down an upright paper (mm/s; the step caps it at ~0.45 cell/step)
  runMm: 0.3,           // a bead this deep above the tooth runs freely; thinner water clings (speed ∝ depth²)
  runHold: 0.5,         // the tooth holds this fraction of its depth in water before any runs
  runFilm: 0.012,       // the film running water leaves on what it crosses (mm): a drip's wet trail
  runCohere: 0.3,       // how strongly a bead's own slope moves its water (drains the bead into its drips)
  runCoherent: 1.0,     // a bead breaks a dry edge as a whole (bead-wide drips), not cell by cell (a comb)
  steerScale: 1.0,      // the paper relief that steers running water, in bead sizes
  runHoldK: 1.0,        // a dry edge holds a running bead this much harder than still water
  quiet: 0.01,          // a neighbour pouring this much more (mm) keeps this part of a bead's edge shut
  dripGap: 4.0,         // a bead lets go only where it stands highest within about this (mm) along its edge
  runSizing: 0.45,      // how unevenly the sizing holds a bead at drip scale: drips break away irregularly
  runPin: 0.5,          // how much a bead's weight helps it break a dry edge on a slope
  runSteer: 4.0,        // how strongly the paper's valleys steer running water sideways (a drip wanders)
  runAlong: 0.25,       // how much the paper's bumps slow it along the slope (more traps it in spots)
  beadMm: 0.6,          // a running bead's size: it moves as one body over about this
};

// How wet the brush is, tapped up and down like Expresii's water drop and napkin (owner, 2026-10-07).
// load: how full of water the brush is; flow: how much water it lets go per mm.
export const WETNESS = [
  { name: 'dry', load: 0.15, flow: 0.6 },
  { name: 'damp', load: 0.4, flow: 0.8 },
  { name: 'wet', load: 0.8, flow: 1.0 },
  { name: 'loaded', load: 1.0, flow: 1.25 },
  { name: 'runny', load: 1.35, flow: 1.7 },
];

// Thinned paint (owner, 2026-10-06/07: "thick paint with wateriness … impasto pulling pigment into runny
// watercolor drips … two stage, loaded then watery brush"). An oil brush can carry thinner: its stroke lays the
// paint, thinner in body the more it is thinned, and a water stroke rides along the same path carrying the
// paint's colour as a glaze; the fresh paint also gives pigment up to the water lying on it (lift). The water
// then runs, pools and drips like any wash. `water` is the WETNESS level of the riding water stroke.
export const THINNER = [
  { name: 'neat', water: null, body: 1.0 },
  { name: 'thinned', water: 1, body: 0.8 },
  { name: 'watery', water: 2, body: 0.6 },
  { name: 'runny', water: 4, body: 0.4 },
];

// The medium that runs out of thinned paint: as wide as the paint's footprint.
export function thinnerBrush(paste) {
  const half = paste.widthMm / 2;
  return {
    allround: !!paste.allround, bellyMm: half, tipMm: paste.allround ? Math.max(0.1, paste.tipHalfMm) : half * 0.55,
    waterPerMm: 0.09, capacityMm3: 160, load: 0.9, gran: 0.6, stain: 0.25, beadMm: 1.5, liftMm: 2.5, dwellMmPerS: 1.2,
  };
}

// The water stroke riding along a thinned paint stroke (null when the paint is neat).
export function thinnerStroke(paste, color, paper, pxPerMm, seed, level) {
  const t = THINNER[level];
  if (!t || t.water === null) return null;
  return new WetStroke(thinnerBrush(paste), paintFromColor(color, 0.55 + 0.15 * level), paper, pxPerMm, seed + 7919, WETNESS[t.water]);
}

export const WET_BRUSHES = {
  // The all-rounder (owner, 2026-10-07): a pointed round from a hairline to a full belly on pressure and
  // tilt, carrying a wash's worth of water.
  'All-round': { allround: true, bellyMm: 4.5, tipMm: 0.06, waterPerMm: 0.09, capacityMm3: 150, load: 0.95, gran: 1.0, stain: 0.3, beadMm: 2.5, liftMm: 3, dwellMmPerS: 1.5 },
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

// Paper tilt → the slope gravity pulls along: sin(tiltDeg) toward dir (unit, doc space, pointing downhill).
export function tiltSlope(tiltDeg, dir) {
  const s = Math.sin(Math.min(90, Math.max(0, tiltDeg)) * D2R);
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
    u_runMmPerS: w.runMmPerS, u_runMm: w.runMm, u_runHoldMm: w.runHold * paper.toothMm, u_runFilmMm: w.runFilm, u_runPin: w.runPin, u_runSizing: w.runSizing, u_dripGapMm: w.dripGap, u_quietMm: w.quiet, u_runHoldK: w.runHoldK, u_runCoherent: w.runCoherent, u_runCohere: w.runCohere, u_steerScale: w.steerScale, u_runSteer: w.runSteer, u_runAlong: w.runAlong,
    u_heightMean: paper.uniforms.u_paperHeightMean, u_fullMm: w.fullMm, u_filmMm: w.filmMm, u_edgeDep: w.edgeDep, u_mingle: Math.min(0.24 / w.dt, w.mingle),
  };
}

export class WetStroke {
  constructor(brush, paint, paper, pxPerMm, seed = 1, wetness = null) {
    this.brush = brush;
    // Blotting the brush leaves the paint in it strong; dipping it in water thins it (Expresii's napkin and
    // drop). Strength per mm of water goes as (wet ÷ load)^0.6: a dry brush skips richly over the tooth, a
    // runny one floods paler.
    const conc = wetness && !brush.clear ? Math.min(2.6, Math.max(0.7, Math.pow(0.8 / Math.max(0.1, wetness.load), 0.6))) : 1;
    this.paint = brush.clear ? { K: [0, 0, 0], S: 0 } : { K: paint.K.map(k => k * conc), S: paint.S * conc };
    this.paper = paper;
    this.pxPerMm = pxPerMm;
    // A wetness level replaces the brush's own load (the dry brush keeps its own unless asked).
    this.water = wetness ? Math.min(1.5, wetness.load) : brush.load;          // 0..1+ of capacity
    this.flow = wetness ? wetness.flow : 1;
    this.travelled = 0;
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
      // The hairs swing round over about a millimetre and a half of travel, not per pen report, so a dab's
      // jitter does not smear it into several elongated marks.
      const t = [dx / len, dy / len];
      const k = this.dir ? 1 - Math.exp(-(len / this.pxPerMm) / 1.5) : 1;
      this.dir = this.dir ? norm2(lerp2(this.dir, t, k)) : t;
      this.travelled += len / this.pxPerMm;
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
    return (b.tipMm + (b.bellyMm - b.tipMm) * Math.pow(p, b.allround ? 1.6 : 0.75)) * (1 + 0.35 * t);
  }

  emit(a, b, w, slideMm) {
    // The bead hanging at a loaded tip comes off over the first few millimetres: the stroke starts in a
    // pool that melts into the rest of it (not a stamped blob).
    this.beadTravel = (this.beadTravel || 0) + slideMm;
    const beadLen = 3;
    slideMm *= 1 + (this.brush.beadMm / beadLen) * this.water * Math.exp(-this.beadTravel / beadLen);
    const L = (x, y) => x + (y - x) * w;
    const s = { x: L(a.x, b.x), y: L(a.y, b.y), p: L(a.p, b.p), tilt: L(a.tilt, b.tilt) };
    if (s.p <= 0.002) return;
    const half = this.halfWidth(s);
    // A round puddle on touchdown; the footprint only stretches along the drag once the brush is moving.
    const along = half * (1 + (0.25 + 0.6 * Math.min(1, s.tilt / (60 * D2R))) * smooth(0, 3, this.travelled));
    const dryness = 1 - smooth(0.05, 0.45, this.water);
    const wet = this.water;
    this.pending.push([
      s.x, s.y, this.dir[0], this.dir[1],
      along, half, this.brush.waterPerMm * this.flow, wet,
      this.paint.K[0], this.paint.K[1], this.paint.K[2], this.paint.S,
      slideMm, dryness, 1 - this.brush.stain, this.seed,
      0, 0, 0, 0,
    ]);
    // Water leaves the brush roughly in proportion to what it lays down on dry paper.
    const area = Math.PI * along * half * 0.6;
    const given = this.brush.waterPerMm * this.flow * slideMm * wet * area * (1 - 0.5 * dryness);
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
