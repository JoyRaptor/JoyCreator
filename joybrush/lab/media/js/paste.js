// paste.js — paste media (oil, acrylic, palette knife): brushes, opaque paint, and the stroke → step
// builder. The paint exchange runs on the GPU (jb_paste_dab.frag on the canvas, jb_paste_brush.frag on
// the brush's 32×8 cells), from the same rules on both sides.

export const PASTE_LANES = 32;
export const PASTE_DEPTH = 8;

// shape: 0 round, 1 flat, 2 knife. Lengths in mm. thick = layer a full brush leaves. loadLen = how far a
// full load lays a full layer before the brush starts to run dry. wick = paint creep between cells per step.
export const PASTE_BRUSHES = {
  'Oil flat': { shape: 1, widthMm: 7, lenMm: 6, thickMm: 0.6, scrape: 0.5, hairDepth: 0.4, ridge: 0.5, rate: 2.5, mix: 0.12, swap: 0.7, loadLenMm: 140, wick: 0.003, bow: 0.6, lump: 0.2, rigid: 0 },
  'Oil round': { shape: 0, widthMm: 4.5, lenMm: 7, thickMm: 0.5, scrape: 0.5, hairDepth: 0.35, ridge: 0.35, rate: 2.2, mix: 0.12, swap: 0.6, loadLenMm: 120, wick: 0.003, bow: 0.5, lump: 0.12, rigid: 0 },
  'Fan blender': { shape: 1, widthMm: 14, lenMm: 8, thickMm: 0.1, scrape: 0.3, hairDepth: 0.95, ridge: 0.0, rate: 1.2, mix: 0.45, swap: 0.3, loadLenMm: 60, wick: 0.002, bow: 0.1, lump: 0.0, rigid: 0 },
  'Palette knife': { shape: 2, widthMm: 9, lenMm: 13, thickMm: 1.1, scrape: 0.99, hairDepth: 0.0, ridge: 1.0, rate: 4.0, mix: 0.2, swap: 0.5, loadLenMm: 70, wick: 0.0, bow: 0.9, lump: 0.25, rigid: 1 },
  // A knife edge or a scraper: cuts a line through wet paint down to the canvas, pushing ridges up beside it.
  'Scraper': { shape: 3, widthMm: 1.6, lenMm: 3, thickMm: 0.0, scrape: 1.0, hairDepth: 0.0, ridge: 0.0, rate: 6.0, mix: 0.0, swap: 0.0, loadLenMm: 1, wick: 0.0, bow: 0.0, lump: 0.0, rigid: 1, cut: true },
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
  return brush.thickMm * brush.rate * brush.loadLenMm / brush.lenMm;
}

export class PasteStroke {
  // layerScale: layer px per doc px of the layer this stroke paints into (a zoomed vector view is > 1);
  // steps stay finer than one LAYER pixel's worth of paint so no step shows as an edge at any zoom.
  constructor(brush, pxPerMm, layerScale = 1) {
    this.brush = brush;
    this.pxPerMm = pxPerMm;
    this.layerScale = layerScale;
    this.steps = [];
    this.last = null;
    this.dir = null;
    this.carry = 0;
  }

  add(s) {
    const prev = this.last;
    this.last = s;
    if (!prev) return;
    const dx = s.x - prev.x, dy = s.y - prev.y, len = Math.hypot(dx, dy);
    if (len < 1e-6) return;
    const t = [dx / len, dy / len];
    // The brush turns with the drag, but not instantly (hairs drag behind the handle). It touches down
    // already facing the way the hand first moves, so the start is not a turned stamp.
    this.dir = this.dir ? norm2([this.dir[0] * 0.6 + t[0] * 0.4, this.dir[1] * 0.6 + t[1] * 0.4]) : t;
    const b = this.brush;
    // Small hops: a paste brush is a continuous sweep, so no step may show as an edge.
    const stepPx = Math.max(1 / this.layerScale, 0.3 * this.pxPerMm / this.layerScale);
    let pos = stepPx - this.carry;
    while (pos <= len) {
      const w = pos / len;
      this.push({ x: prev.x + dx * w, y: prev.y + dy * w, p: prev.p + (s.p - prev.p) * w, tilt: prev.tilt + (s.tilt - prev.tilt) * w }, stepPx / this.pxPerMm);
      pos += stepPx;
    }
    this.carry = len - (pos - stepPx);
  }

  push(s, slideMm) {
    const b = this.brush;
    const P = Math.max(0, Math.min(1, s.p));
    const tiltFrac = Math.min(1, s.tilt / (60 * Math.PI / 180));
    const lDir = [-this.dir[0], -this.dir[1]];          // the tips trail the handle
    const wDir = [-lDir[1], lDir[0]];
    const halfW = b.widthMm / 2 * (b.shape === 0 ? 0.55 + 0.45 * Math.sqrt(P) : 0.9 + 0.1 * P) * (b.shape === 2 ? 1 : 1 + 0.2 * tiltFrac);
    const lenMax = b.lenMm * 1.4;
    const len = b.shape === 2 ? b.lenMm * (0.6 + 0.4 * P) : Math.min(lenMax, b.lenMm * (0.25 + 0.75 * Math.pow(P, 0.8)) * (1 + 0.4 * tiltFrac));
    this.steps.push({ x: s.x, y: s.y, wDir, lDir, halfW, len, lenMax, pressure: P, slideMm });
  }

  take() { const out = this.steps; this.steps = []; return out; }
}

const norm2 = v => { const l = Math.hypot(v[0], v[1]) || 1; return [v[0] / l, v[1] / l]; };
