// paste.js — paste media (oil, acrylic, palette knife): brushes, opaque paint, and the stroke → step
// builder. The paint exchange runs on the GPU (jb_paste_dab.frag on the canvas, jb_paste_brush.frag on
// the brush's 32×8 cells), from the same rules on both sides.

export const PASTE_LANES = 32;
export const PASTE_DEPTH = 8;

// shape: 0 round, 1 flat, 2 knife. Lengths in mm. thick = layer a full brush leaves. loadLen = how far a
// full load lays a full layer before the brush starts to run dry. wick = paint creep between cells per step.
export const PASTE_BRUSHES = {
  // The all-rounder (owner, 2026-10-06): one round that goes from a hairline tip to a full belly on pressure
  // and tilt, like the pencil; thin paint for detail, thick for impasto.
  'All-round': { shape: 0, allround: true, tipHalfMm: 0.08, widthMm: 6, lenMm: 8, thickMm: 0.45, thinMm: 0.04, scrape: 0.4, hairDepth: 0.2, ridge: 0.25, rate: 2.4, mix: 0.12, swap: 0.5, loadLenMm: 160, wick: 0.003, bow: 0.35, lump: 0.08, rigid: 0, fingers: 0, rag: 0.35 },
  'Oil flat': { shape: 1, widthMm: 7, lenMm: 6, thickMm: 0.6, scrape: 0.5, hairDepth: 0.4, ridge: 0.5, rate: 2.5, mix: 0.12, swap: 0.7, loadLenMm: 140, wick: 0.003, bow: 0.6, lump: 0.2, rigid: 0, fingers: 1 },
  'Oil round': { shape: 0, widthMm: 4.5, lenMm: 7, thickMm: 0.5, scrape: 0.5, hairDepth: 0.35, ridge: 0.35, rate: 2.2, mix: 0.12, swap: 0.6, loadLenMm: 120, wick: 0.003, bow: 0.5, lump: 0.12, rigid: 0, fingers: 0.4 },
  // A fan: hair tips on an arc, spread apart; it feathers and blends rather than lays paint.
  'Fan blender': { shape: 1, widthMm: 14, lenMm: 2.5, thickMm: 0.1, scrape: 0.3, hairDepth: 0.95, ridge: 0.0, rate: 1.2, mix: 0.45, swap: 0.3, loadLenMm: 60, wick: 0.002, bow: 0.1, lump: 0.0, rigid: 0, fingers: 0.5, arc: 0.55, sparse: 0.6 },
  // The trowel knife of v6/v7, back by the owner's request (2026-10-07: "the old one had excellent texture" and
  // was easier to control). It follows the stroke, skates over the dips (fillDips 0) and keeps its own geometry.
  'Palette knife 1': { shape: 2, trowel: true, widthMm: 9, lenMm: 13, thickMm: 1.1, scrape: 0.99, hairDepth: 0.0, ridge: 1.0, rate: 4.0, mix: 0.2, swap: 0.5, loadLenMm: 70, wick: 0.0, bow: 0.9, lump: 0.25, rigid: 1, fillDips: 1 },
  // Rigid blades, held like the pen: the edge lies along the pen's lean. Pressure lowers the blade (light
  // shaves the peaks, full reaches the canvas); tilt lays more of the edge down (upright = the point).
  'Palette knife 2': { shape: 2, blade: 1, orient: 'pen', edgeMinMm: 3, acrossMaxMm: 16, bladeLenMm: 22, bladeHalfMm: 4.5, bladeHmax: 0.9, bead: 0.35, bladeCap: 8, thickMm: 1.1, rate: 4.0, loadLenMm: 70, mix: 0.2, swap: 0, scrape: 0, hairDepth: 0, ridge: 0, wick: 0, bow: 0, lump: 0.25, rigid: 1 },
  // A thin cutting blade. Which way its edge lies is the owner's to choose (EDGE_MODES); held at the pen's angle
  // by default. Pressure sets the depth (light skims the peaks, full reaches the canvas).
  'Scraper': { shape: 3, blade: 1, orient: 'pen', edgeMinMm: 1.5, acrossMaxMm: 10, bladeLenMm: 14, bladeHalfMm: 0.35, bladeHmax: 1.6, bead: 0.5, bladeCap: 3, film: 0.002, thickMm: 0, rate: 5.0, loadLenMm: 40, mix: 0, swap: 0, scrape: 0, hairDepth: 0, ridge: 0, wick: 0, bow: 0, lump: 0, rigid: 1, clean: true },
};

// Opaque paint: the colour it covers with when thick. Strong scattering; K from the KM masstone.
export function opaquePaint(rgb) {
  const lin = rgb.map(c => (c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4)));
  const S = 30;                                      // per mm: 0.1 mm of paint already hides most of what is under it
  // Real paints never reflect under ~6% in any channel; lower makes tinting strength absurd.
  const K = lin.map(r => { r = Math.min(0.95, Math.max(0.06, r)); return S * (1 - r) * (1 - r) / (2 * r); });
  return { K, S };
}

export function cellCap(brush) {
  if (brush.blade) return brush.bladeCap;
  return brush.thickMm * brush.rate * brush.loadLenMm / brush.lenMm;
}

// Blade angle to the paper from the pen's tilt fraction t (same S-curve idea as the pencil): near upright
// only the point reaches the paint; the edge lies down over the last part of the tilt range.
// How a blade's edge lies, tapped through on the canvas bar (owner, 2026-10-07):
//   pen     along the pen's lean, whatever the stroke does (v8/v9.1); tilt lays the edge down from the point
//   across  a squeegee: across the stroke, turning with it, centred on the pen; lean widens it
//   along   along the stroke, trailing the pen point: a groove that follows the line
export const EDGE_MODES = ['pen', 'across', 'along'];
export const EDGE_LABELS = { pen: 'pen angle', across: 'across stroke', along: 'along stroke' };

export function bladeTan(t) { return Math.tan(75 * Math.PI / 180 * Math.pow(1 - Math.min(1, Math.max(0, t)), 3)); }

export class PasteStroke {
  // layerScale: layer px per doc px of the layer this stroke paints into (a zoomed vector view is > 1);
  // steps stay finer than one LAYER pixel's worth of paint so no step shows as an edge at any zoom.
  constructor(brush, pxPerMm, layerScale = 1, opts = {}) {
    this.brush = brush;
    this.edge = opts.edge || brush.orient || 'pen';
    this.pxPerMm = pxPerMm;
    this.layerScale = layerScale;
    this.steps = [];
    this.last = null;
    this.dir = null;
    this.carry = 0;
    this.travelled = 0;       // mm since touchdown: the hairs can only trail over the path already drawn
    this.lastStepT = null;
  }

  add(s) {
    const prev = this.last;
    this.last = s;
    const b = this.brush;
    if (!prev) { this.lastStepT = s.t ?? 0; return; }
    const dx = s.x - prev.x, dy = s.y - prev.y, len = Math.hypot(dx, dy);
    if (b.trowel) {
      // The trowel knife steers as it did in v6/v7: it turns with each report and only lays paint as it moves.
      if (len < 1e-6) return;
      const t = [dx / len, dy / len];
      this.dir = this.dir ? norm2([this.dir[0] * 0.6 + t[0] * 0.4, this.dir[1] * 0.6 + t[1] * 0.4]) : t;
    } else if (len > 1e-6) {
      // The hairs swing round to follow the drag over about a millimetre and a half of travel, not per pen
      // report: a dab with a little jitter no longer spins the footprint into several straight lines.
      const t = [dx / len, dy / len];
      const k = this.dir ? 1 - Math.exp(-(len / this.pxPerMm) / 1.5) : 1;
      this.dir = this.dir ? norm2([this.dir[0] * (1 - k) + t[0] * k, this.dir[1] * (1 - k) + t[1] * k]) : t;
    }
    // Small hops: a paste brush is a continuous sweep, so no step may show as an edge. A thin blade edge
    // crossing its own width needs hops finer than that width (else it leaves a zip of ribs).
    const stepMm = b.blade ? Math.min(0.3, 0.6 * b.bladeHalfMm * 0.35) : 0.3;
    const stepPx = Math.max(1 / this.layerScale, stepMm * this.pxPerMm / this.layerScale);
    const interp = w => ({ x: prev.x + dx * w, y: prev.y + dy * w, p: prev.p + (s.p - prev.p) * w, tilt: prev.tilt + (s.tilt - prev.tilt) * w,
      az: (prev.az ?? 0) + angleDelta(prev.az ?? 0, s.az ?? 0) * w, t: (prev.t ?? 0) + ((s.t ?? 0) - (prev.t ?? 0)) * w });
    let pos = stepPx - this.carry;
    let stepped = false;
    while (pos <= len) {
      const w = pos / len;
      this.travelled += stepPx / this.pxPerMm;
      this.push(interp(w), stepPx / this.pxPerMm);
      stepped = true;
      pos += stepPx;
    }
    this.carry = len - (pos - stepPx);
    // A brush held still (a dab, or the start of a stroke) keeps laying paint where it is.
    const now = s.t ?? 0;
    if (!stepped && !b.trowel && now - this.lastStepT >= 16) {
      this.push({ ...s }, 0.04 * Math.min(4, (now - this.lastStepT) / 16));
    }
    if (stepped || now - this.lastStepT >= 16) this.lastStepT = now;
  }

  push(s, slideMm) {
    const b = this.brush;
    const P = Math.max(0, Math.min(1, s.p));
    const tiltFrac = Math.min(1, s.tilt / (60 * Math.PI / 180));
    // Before the brush has moved it has no direction: lean it along the pen's lean until the drag takes over.
    const lean = [-Math.cos(s.az ?? 0), -Math.sin(s.az ?? 0)];
    const dir = this.dir || lean;
    const lDir = [-dir[0], -dir[1]];          // the tips trail the handle
    const wDir = [-lDir[1], lDir[0]];
    const tr = this.travelled;
    if (b.trowel) {
      // v6/v7 trowel geometry: width barely changes with pressure, length grows with it; tilt does nothing.
      const halfW = b.widthMm / 2 * (0.9 + 0.1 * P), len = b.lenMm * (0.6 + 0.4 * P);
      this.steps.push({ x: s.x, y: s.y, wDir, lDir, halfW, len, lenMax: b.lenMm * 1.4, pressure: P, slideMm, fingers: 0 });
      return;
    }
    if (b.blade && this.edge === 'across') {
      // Squeegee: the edge lies across the travel, centred on the pen; lean lays more of it down.
      const t = Math.min(1, Math.max(0, s.tilt / (Math.PI / 2)));
      const L = b.edgeMinMm + (b.acrossMaxMm - b.edgeMinMm) * Math.pow(t, 1.5);
      const across = [-dir[1], dir[0]];
      const k = 0.5 * L * this.pxPerMm;
      this.steps.push({
        x: s.x - across[0] * k, y: s.y - across[1] * k, wDir, lDir, halfW: b.bladeHalfMm, len: L, lenMax: L, pressure: P, slideMm,
        bladeDir: across, bladeLen: L, travel: [...dir], bladeH0: b.bladeHmax * Math.pow(1 - P, 1.5), bladeTan: 0,
      });
      return;
    }
    if (b.blade) {
      // Rigid blade from the pen point: its edge lies along the pen's lean ('pen'), or trails along the path
      // ('along': a groove that follows the line; before the first move it leans with the pen).
      const t = Math.min(1, Math.max(0, s.tilt / (Math.PI / 2)));
      const bladeDir = this.edge === 'along' ? [-dir[0], -dir[1]] : [Math.cos(s.az ?? 0), Math.sin(s.az ?? 0)];
      this.steps.push({
        x: s.x, y: s.y, wDir, lDir, halfW: b.bladeHalfMm, len: b.bladeLenMm, lenMax: b.bladeLenMm, pressure: P, slideMm,
        bladeDir, travel: [...dir],
        // Full pressure rides the canvas peaks (and the weave comes through); a light touch only shaves tops.
        bladeH0: b.bladeHmax * Math.pow(1 - P, 1.5), bladeTan: bladeTan(t),
      });
      return;
    }
    // Splayed hairs come in only after the brush has travelled a little (they are further out: owner,
    // 2026-10-07), never on touchdown.
    const fingers = (b.fingers ?? 1) * smooth(2, 6, tr);
    let halfW, lenFull, thick;
    if (b.allround) {
      // From a hairline tip to the full belly: pressure gathers the hairs down, tilt lays the belly over.
      const k = Math.pow(P, 1.6);
      halfW = (b.tipHalfMm + (b.widthMm / 2 - b.tipHalfMm) * k) * (1 + 0.35 * tiltFrac);
      lenFull = (0.35 + (b.lenMm - 0.35) * k) * (1 + 0.4 * tiltFrac);
      thick = b.thinMm + (b.thickMm - b.thinMm) * k;
    } else {
      halfW = b.widthMm / 2 * (b.shape === 0 ? 0.55 + 0.45 * Math.sqrt(P) : 0.9 + 0.1 * P) * (1 + 0.2 * tiltFrac);
      lenFull = b.lenMm * (0.25 + 0.75 * Math.pow(P, 0.8)) * (1 + 0.4 * tiltFrac);
    }
    // On touchdown a round is a dot and a flat its pressed chisel; the hairs trail out behind only as far as
    // the brush has actually travelled.
    const minLen = b.shape === 0 ? 2 * halfW : 0.5 * halfW;
    const lenMax = Math.max(minLen, b.lenMm * 1.4);
    const len = Math.min(lenMax, Math.max(minLen, Math.min(lenFull, minLen + 0.8 * tr)));
    this.steps.push({ x: s.x, y: s.y, wDir, lDir, halfW, len, lenMax, pressure: P, slideMm, fingers, ...(thick !== undefined ? { thick } : {}) });
  }

  take() { const out = this.steps; this.steps = []; return out; }
}

const smooth = (a, b, x) => { const t = Math.min(1, Math.max(0, (x - a) / (b - a))); return t * t * (3 - 2 * t); };

const norm2 = v => { const l = Math.hypot(v[0], v[1]) || 1; return [v[0] / l, v[1] / l]; };
function angleDelta(a, b) { let d = b - a; while (d > Math.PI) d -= 2 * Math.PI; while (d < -Math.PI) d += 2 * Math.PI; return d; }
