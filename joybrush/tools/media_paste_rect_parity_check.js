// joybrush/tools/media_paste_rect_parity_check.js — the paste step's canvas rectangle: old square vs tight oriented box.
//
// WHY. MediaLayerEngine.pasteStep draws the dab pass and copies state[1] -> state[0] over a rectangle. The old rectangle was
// a square of half-size hypot(halfW, lenMax) + 0.3 mm (a blade: bladeLen + bladeHalf + 1.5 mm); core/.../PasteFootprint.kt
// now gives a tight oriented box. That is only correct if the dab shader returns its input untouched outside the box
// (jb_paste_dab.frag lines 40/47/83), i.e. it is BIT-EXACT. This checker proves it on the real shaders:
//
//   * the ACTUAL jb_media_quad.vert, jb_paste_dab.frag, jb_paste_brush.frag, jb_media_copy.frag (with their ACTUAL #includes)
//     are run in headless WebGL2 (Edge + SwiftShader) over a seeded RGBA32F canvas, the same two-pass ping-pong the engine does
//     (brush side -> dab over the rect into state[1] -> copyRect state[1] -> state[0] -> swap brush cells);
//   * four contexts take the SAME sequence of steps: OLD (legacy square), NEW (what the engine does: the SAME square quad,
//     scissored to the PasteFootprint box, and only the box copied back), CONTROL (NEW with the box shrunk by 30 px on every
//     side: MUST be detected, otherwise the comparison proves nothing), and SMALLQUAD (informational: drawing a smaller quad
//     instead of scissoring; it is NOT bit-exact because v_docPx/v_layerPx interpolate differently, which is why the engine scissors);
//   * after every CHECK_EVERY steps and at the end the FULL float readback of p0 and p1 (bit patterns, so -0/NaN count) is
//     compared OLD vs NEW (must be 0 pixels different) and OLD vs CONTROL (must be > 0);
//   * at EVERY step the pixels the OLD run changed are read back (inside the old square) and must all lie inside the NEW
//     rectangle; the smallest margin to the rectangle's edge is reported.
//
// The rectangle maths is a JS port of PasteFootprint.kt (this file cannot call Kotlin). The port is pinned to the Kotlin
// by ANCHORS: literal steps whose expected bounds are asserted here AND in PasteFootprintTest.kt (anchorsMatchTheGpuCheckerPort).
// Brushes are parsed from core/.../Paste.kt (PasteBrushes.ALL), so the brush table cannot drift from the app.
//
// Run: NODE_PATH=<dir with playwright-core> node joybrush/tools/media_paste_rect_parity_check.js [--chrome <exe>] [--size 640] [--steps 70]
// (playwright-core: `npm i --no-save playwright-core` in joybrush/tools, as shader_check.js says; CHROME env overrides Edge.)
// Prints one JSON line; exit 0 only on pass. SwiftShader is software GL: this proves the shader maths, not Adreno timing.
const fs = require('fs');
const path = require('path');
let chromium;
try { chromium = require('playwright-core').chromium; } catch (e) {
  console.log(JSON.stringify({ pass: false, error: 'missing dependency playwright-core: ' + e.message })); process.exit(1);
}
const ROOT = path.join(__dirname, '..');
const SHADER_DIR = path.join(ROOT, 'shaders');
const PASTE_KT = path.join(ROOT, 'core', 'src', 'commonMain', 'kotlin', 'cc', 'joycreator', 'joybrush', 'core', 'media', 'Paste.kt');
function argVal(name, dflt) { const i = process.argv.indexOf('--' + name); return i >= 0 && i + 1 < process.argv.length ? process.argv[i + 1] : dflt; }
const SIZE = parseInt(argVal('size', '640'), 10);
const STEPS = parseInt(argVal('steps', '70'), 10);
const CHECK_EVERY = 10;
const PPM = 20.0;            // DOC_PX_PER_MM
const TOOTH_MM = 0.25;       // a typical paper tooth; any fixed value works (it only feeds both runs identically)
const INSET_PX = 30;         // the deliberately broken control

// ---- shaders (same include rule as the Android loader / the other checkers) ----
const INCLUDE_RE = /^#include\s+"([^"]+)"$/;
function expandFile(rel, seen = new Set()) {
  if (seen.has(rel)) return '';
  seen.add(rel);
  const text = fs.readFileSync(path.join(SHADER_DIR, rel), 'utf8').replace(/\r\n/g, '\n');
  return text.split('\n').map(l => { const m = l.trim().match(INCLUDE_RE); return m ? expandFile(m[1], seen) : l; }).join('\n');
}
const SRC = {
  vert: expandFile('media/jb_media_quad.vert'), dab: expandFile('media/jb_paste_dab.frag'),
  brush: expandFile('media/jb_paste_brush.frag'), copy: expandFile('media/jb_media_copy.frag'),
};

// ---- PasteBrushes.ALL, parsed from Paste.kt ----
function parseBrushes() {
  const src = fs.readFileSync(PASTE_KT, 'utf8');
  const defaults = { shape: 0, widthMm: 0, lenMm: 0, thickMm: 0, scrape: 0, hairDepth: 0, ridge: 0, rate: 1, mix: 0, swap: 0.6, loadLenMm: 1, wick: 0, bow: 0, lump: 0, rigid: 0,
    allround: false, tipHalfMm: 0, thinMm: 0, fingers: null, rag: null, arc: null, sparse: null, trowel: false, fillDips: null, blade: false, orient: 'pen', press: null, face: null,
    edgeHalfMm: null, edgeMinMm: 0, acrossMaxMm: 0, riseMaxMm: null, bladeLenMm: 0, bladeHalfMm: 0, bladeHmax: 0, bead: 0, bladeCap: 0, film: null, clean: false, pressIn: null,
    ends: null };
  const out = {};
  const re = /"([^"]+)"\s+to\s+PasteBrush\(([^)]*)\)/g;
  let m;
  while ((m = re.exec(src))) {
    const b = { ...defaults };
    for (const kv of m[2].replace(/\/\/[^\n]*/g, '').split(',')) {
      const p = kv.split('=').map(s => s.trim());
      if (p.length !== 2 || !p[0]) continue;
      let v = p[1];
      if (v === 'true') v = true; else if (v === 'false') v = false;
      else if (/^-?[\d.]+$/.test(v)) v = parseFloat(v);
      else if (/^(EdgeMode|PressMode|FaceMode|EndsMode)\./.test(v)) v = v.split('.')[1].toLowerCase();
      else throw new Error('Paste.kt brush ' + m[1] + ': cannot parse ' + kv);
      if (!(p[0] in defaults)) throw new Error('Paste.kt brush ' + m[1] + ': unknown field ' + p[0]);
      b[p[0]] = v;
    }
    out[m[1]] = b;
  }
  if (Object.keys(out).length < 7) throw new Error('expected the 7 shipped paste brushes, parsed ' + Object.keys(out).length);
  return out;
}
const BRUSHES = parseBrushes();
const nz = (v, d) => (v === null || v === undefined || v === 0) ? d : v;

// ---- JS port of PasteFootprint.kt (pinned to Kotlin by the anchors below) ----
const SAFETY_PAD_PX = 2.0;
// PasteFootprint.landWiden: a loaded hair brush's landing square widens with its splay and its more ragged edge (1 otherwise).
function landWiden(st, b) {
  if (!loadedEnds(b) || b.shape > 1 || !(st.land > 0)) return 1;
  const rag0 = Math.abs(b.rag ?? 1.0) * (0.10 * 0.5 + 0.06 * 0.5);
  return (1 + LAND_SPLAY * st.land) * (1 - rag0) / Math.max(1 - rag0 * (1 + LAND_RAG * st.land), 0.5);
}
function legacyBounds(st, b) {
  const r = (b.blade ? (st.bladeLen ?? b.bladeLenMm) + b.bladeHalfMm + 1.5 : Math.hypot(st.halfW * landWiden(st, b), st.lenMax) + 0.3) * PPM;
  return [st.x - r, st.y - r, st.x + r, st.y + r];
}
function unitFrame(ux, uy, vx, vy) {
  return Math.abs(Math.hypot(ux, uy) - 1) < 1e-3 && Math.abs(Math.hypot(vx, vy) - 1) < 1e-3 && Math.abs(ux * vx + uy * vy) < 1e-3;
}
// PasteBrush.loadedEnds: a hair brush or the trowel paints a loaded brush's ends unless it says "cut"; a blade never does.
const LAND_RAG = 2.0, LAND_SPLAY = 0.14;
function loadedEnds(b) { return !b.blade && b.ends !== 'cut'; }
function supportBounds(st, b, layerScale) {
  const px = 1 / (PPM * layerScale);
  let U, V, uMin, uMax, vMin, vMax;
  if (b.blade) {
    const bladeLen = st.bladeLen ?? nz(b.bladeLenMm, 1.0), half = st.bladeHalf ?? nz(b.bladeHalfMm, 1.0);
    if (!unitFrame(st.bladeDirX, st.bladeDirY, -st.bladeDirY, st.bladeDirX)) return null;
    const aaS = Math.max(px, 1.2 * st.slideMm);
    U = [st.bladeDirX, st.bladeDirY]; V = [-st.bladeDirY, st.bladeDirX];
    uMin = -0.3 * 0.35 * half - aaS; uMax = bladeLen + aaS; vMin = -(half + aaS); vMax = half + aaS;
  } else {
    if (!unitFrame(st.wDirX, st.wDirY, st.lDirX, st.lDirY)) return null;
    const landed = loadedEnds(b) ? 1 + LAND_RAG * (st.land ?? 0) : 1;   // PasteFootprint.LAND_RAG
    const ragMax = b.shape > 1 ? 0.14 * 0.5 + 0.05 * 0.5 : landed * Math.abs(b.rag ?? 1.0) * (0.10 * 0.5 + 0.06 * 0.5);
    if (ragMax > 0.5) return null;
    const across = (Math.max(st.halfW * (loadedEnds(b) && b.shape <= 1 ? 1 + LAND_SPLAY * (st.land ?? 0) : 1), 1e-3) + px) / (1 - ragMax);   // PasteFootprint.LAND_SPLAY
    U = [st.wDirX, st.wDirY]; V = [st.lDirX, st.lDirY];
    uMin = -across; uMax = across; vMin = -px; vMax = st.len + px;
  }
  let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
  for (const u of [uMin, uMax]) for (const v of [vMin, vMax]) {
    const x = st.x + PPM * (u * U[0] + v * V[0]), y = st.y + PPM * (u * U[1] + v * V[1]);
    x0 = Math.min(x0, x); y0 = Math.min(y0, y); x1 = Math.max(x1, x); y1 = Math.max(y1, y);
  }
  const pad = SAFETY_PAD_PX / layerScale;
  return [x0 - pad, y0 - pad, x1 + pad, y1 + pad];
}
function docBounds(st, b, layerScale) {
  const old = legacyBounds(st, b), s = supportBounds(st, b, layerScale);
  if (!s) return old;
  return [Math.max(old[0], s[0]), Math.max(old[1], s[1]), Math.min(old[2], s[2]), Math.min(old[3], s[3])];
}
// MediaLayerEngine.layerRect (padDoc 2.0)
function layerRect(r, ox, oy, scale, w, h, padDoc = 2.0) {
  const pad = padDoc * scale;
  const x0 = Math.max(0, Math.floor((r[0] - ox) * scale - pad)), y0 = Math.max(0, Math.floor((r[1] - oy) * scale - pad));
  const x1 = Math.min(w, Math.ceil((r[2] - ox) * scale + pad)), y1 = Math.min(h, Math.ceil((r[3] - oy) * scale + pad));
  return x1 > x0 && y1 > y0 ? [x0, y0, x1, y1] : null;
}
const f32 = (x) => Math.fround(x);
const layerRect32 = (...a) => layerRect(...a);   // engine does the math in Double then stores Float32: ints this small are exact in float32

// ---- anchors: literal steps, expected doc-px bounds (also asserted by PasteFootprintTest.anchorsMatchTheGpuCheckerPort) ----
const ANCHORS = [
  { name: 'Oil round', scale: 1, st: { x: 500, y: 500, wDirX: 0.6, wDirY: 0.8, lDirX: -0.8, lDirY: 0.6, halfW: 1.95, len: 4.0, lenMax: 9.8, slideMm: 0.3 } },
  { name: 'Oil flat', scale: 1, st: { x: 400, y: 300, wDirX: 0, wDirY: 1, lDirX: -1, lDirY: 0, halfW: 3.4, len: 4.2, lenMax: 8.4, slideMm: 0.3 } },
  { name: 'Fan blender', scale: 2, st: { x: 250, y: 260, wDirX: -0.28, wDirY: 0.96, lDirX: -0.96, lDirY: -0.28, halfW: 6.8, len: 1.8, lenMax: 3.5, slideMm: 0.3 } },
  { name: 'Palette knife 1', scale: 1, st: { x: 120, y: 880, wDirX: 0.6, wDirY: -0.8, lDirX: 0.8, lDirY: 0.6, halfW: 4.4, len: 10.0, lenMax: 18.2, slideMm: 0.3 } },
  { name: 'Palette knife 2', scale: 1, st: { x: 700, y: 640, wDirX: 0, wDirY: 1, lDirX: -1, lDirY: 0, halfW: 0.6, len: 12.0, lenMax: 12.0, slideMm: 0.126, bladeDirX: 0.8, bladeDirY: 0.6, bladeLen: 12.0, bladeHalf: 0.6 } },
  { name: 'Scraper', scale: 0.5, st: { x: 333, y: 444, wDirX: 0, wDirY: 1, lDirX: -1, lDirY: 0, halfW: 0.35, len: 9.0, lenMax: 9.0, slideMm: 0.0735, bladeDirX: -0.6, bladeDirY: 0.8, bladeLen: 9.0, bladeHalf: 0.35 } },
];
function anchorTable() {
  return ANCHORS.map(a => ({ name: a.name, scale: a.scale, bounds: docBounds(a.st, BRUSHES[a.name], a.scale).map(v => Math.round(v * 1e4) / 1e4) }));
}

// ---- step generation: a port of PasteStroke.push (hair/trowel/blade) so the steps are the ones the app makes ----
function smoothstep(a, b, x) { const t = Math.min(1, Math.max(0, (x - a) / (b - a))); return t * t * (3 - 2 * t); }
function makeStep(b, edge, press, pos, dir, p, tilt, az, travelled, slideMm) {
  p = Math.max(0, Math.min(1, p));
  const tiltFrac = Math.min(1, tilt / (60 * Math.PI / 180));
  const dX = dir[0], dY = dir[1], lX = -dX, lY = -dY, wX = -lY, wY = lX;
  const x = pos[0], y = pos[1];
  const st = { x, y, wDirX: wX, wDirY: wY, lDirX: lX, lDirY: lY, pressure: p, slideMm };
  if (b.trowel) return Object.assign(st, { halfW: b.widthMm / 2 * (0.9 + 0.1 * p), len: b.lenMm * (0.6 + 0.4 * p), lenMax: b.lenMm * 1.4, fingers: 0 });
  if (b.blade) {
    const face = b.edgeHalfMm !== null ? (b.face ?? 'edge') : 'flat';
    const half = (face === 'edge' && b.edgeHalfMm !== null) ? b.edgeHalfMm : b.bladeHalfMm;
    const t = Math.min(1, Math.max(0, tilt / (Math.PI / 2)));
    const load = press === 'load';
    const h0 = b.bladeHmax * (load ? p : 1 - p), pressK = load ? 0 : p;
    if (edge === 'across') {
      const l = b.edgeMinMm + (b.acrossMaxMm - b.edgeMinMm) * t, aX = -dY, aY = dX, k = 0.5 * l * PPM;
      return Object.assign(st, { x: x - aX * k, y: y - aY * k, halfW: half, len: l, lenMax: l, bladeDirX: aX, bladeDirY: aY, bladeLen: l, bladeHalf: half,
        travelX: dX, travelY: dY, bladeH0: h0, bladeTan: 0, pressK, loadMode: load });
    }
    const l = b.edgeMinMm + (b.bladeLenMm - b.edgeMinMm) * t;
    const rise = (b.riseMaxMm ?? 0.8) * (1 - t) / Math.max(l, 0.5);
    const bX = edge === 'along' ? -dX : Math.cos(az), bY = edge === 'along' ? -dY : Math.sin(az);
    return Object.assign(st, { halfW: half, len: l, lenMax: l, bladeDirX: bX, bladeDirY: bY, bladeLen: l, bladeHalf: half,
      travelX: dX, travelY: dY, bladeH0: h0, bladeTan: rise, pressK, loadMode: load });
  }
  const fingers = (b.fingers ?? 1.0) * smoothstep(2.0, 6.0, travelled);
  let halfW, lenFull, thick = null;
  if (b.allround) {
    const k = Math.pow(p, 1.6);
    halfW = (b.tipHalfMm + (b.widthMm / 2 - b.tipHalfMm) * k) * (1 + 0.35 * tiltFrac);
    lenFull = (0.35 + (b.lenMm - 0.35) * k) * (1 + 0.4 * tiltFrac);
    thick = b.thinMm + (b.thickMm - b.thinMm) * k;
  } else {
    halfW = b.widthMm / 2 * (b.shape === 0 ? 0.55 + 0.45 * Math.sqrt(p) : 0.9 + 0.1 * p) * (1 + 0.2 * tiltFrac);
    lenFull = b.lenMm * (0.25 + 0.75 * Math.pow(p, 0.8)) * (1 + 0.4 * tiltFrac);
  }
  const minLen = b.shape === 0 ? 2 * halfW : 0.5 * halfW;
  const lenMax = Math.max(minLen, b.lenMm * 1.4);
  const len = Math.min(lenMax, Math.max(minLen, Math.min(lenFull, minLen + 0.8 * travelled)));
  return Object.assign(st, { halfW, len, lenMax, fingers, thick });
}

// A stroke: curvy path, pressure/tilt varying, direction following the path. Origin/scale = the layer window.
function buildSequence(cfg) {
  const b = BRUSHES[cfg.brush];
  const press = b.clean ? 'depth' : (b.press ?? 'load');
  const stepMm = b.blade ? Math.min(0.3, 0.6 * (b.edgeHalfMm !== null && (b.face ?? 'edge') === 'edge' ? b.edgeHalfMm : b.bladeHalfMm) * 0.35) : 0.3;
  const stepPx = Math.max(1 / cfg.scale, stepMm * PPM / cfg.scale);
  const slideMm = stepPx / PPM;
  const steps = [];
  let s = 0, travelled = 0, peak = 0;
  for (let i = 0; i < STEPS; i++) {
    const t = i / (STEPS - 1);
    const ang = cfg.angle + cfg.curve * Math.sin(2 * Math.PI * t * cfg.waves);
    // advance along the heading
    const dir = [Math.cos(ang), Math.sin(ang)];
    if (i === 0) { cfg._p = [cfg.start[0], cfg.start[1]]; } else { cfg._p = [cfg._p[0] + dir[0] * stepPx, cfg._p[1] + dir[1] * stepPx]; travelled += stepPx / PPM; }
    const p = cfg.p0 + (cfg.p1 - cfg.p0) * t + 0.15 * Math.sin(7 * t);
    const tilt = cfg.tilt0 + (cfg.tilt1 - cfg.tilt0) * t;
    const st = makeStep(b, cfg.edge, press, cfg._p, dir, p, tilt, cfg.az + 0.8 * t, travelled, slideMm);
    // PasteStroke's lift and landing (the strokes here ease off and press again, so both run through their range).
    const pc = Math.max(0, Math.min(1, p));
    peak = Math.max(pc, pc + (peak - pc) * Math.exp(-slideMm / 12));
    if (!b.blade) Object.assign(st, { lift: smoothstep(0.03, 0.95, 1 - pc / Math.max(peak, 1e-3)), land: 1 - smoothstep(0, 0.6 * (b.widthMm || 1), travelled) });
    steps.push(st);
  }
  const rects = steps.map(st => {
    const old = layerRect32(legacyBounds(st, b), cfg.origin[0], cfg.origin[1], cfg.scale, SIZE, SIZE);
    const neu = layerRect32(docBounds(st, b, cfg.scale), cfg.origin[0], cfg.origin[1], cfg.scale, SIZE, SIZE);
    let ctl = neu && (neu[2] - neu[0] > 2 * INSET_PX && neu[3] - neu[1] > 2 * INSET_PX) ? [neu[0] + INSET_PX, neu[1] + INSET_PX, neu[2] - INSET_PX, neu[3] - INSET_PX] : null;
    return { old, neu, ctl };
  });
  return { cfg, steps, rects, brush: b };
}

// Layer px of the start so the stroke stays inside the SIZE x SIZE layer for the given scale/origin.
function startFor(scale, origin, fx, fy) { return [origin[0] + fx * SIZE / scale, origin[1] + fy * SIZE / scale]; }
const SEQS = (() => {
  const o0 = [0, 0], o2 = [13.5, -7.25];
  const mk = (brush, extra) => Object.assign({ brush, scale: 1, origin: o0, angle: 0.3, curve: 0.8, waves: 1.5, p0: 0.2, p1: 0.95, tilt0: 0.1, tilt1: 0.9, az: 0.7, edge: null, start: startFor(1, o0, 0.18, 0.5) }, extra);
  return [
    mk('Oil round', {}),
    mk('Oil round', { angle: 2.4, curve: 1.4, waves: 2.5, p0: 0.05, p1: 0.6, start: startFor(1, o0, 0.85, 0.2), seedTag: 'b' }),
    mk('Oil round', { scale: 2, origin: o2, start: startFor(2, o2, 0.12, 0.45), angle: -0.4, seedTag: 'zoom' }),
    mk('All-round', { angle: 1.1, curve: 0.5, p0: 0.0, p1: 1.0 }),
    mk('Oil flat', { angle: 0.0, curve: 0.0, waves: 1 }),
    mk('Oil flat', { angle: 0.785, curve: 0.9, waves: 2 }),
    mk('Fan blender', { angle: -0.6, curve: 0.6, start: startFor(1, o0, 0.15, 0.7) }),
    mk('Palette knife 1', { angle: 0.5, curve: 0.5, p0: 0.3, p1: 1.0 }),
    mk('Palette knife 2', { edge: 'pen', angle: 0.2, curve: 0.5, tilt0: 0.2, tilt1: 1.2 }),
    mk('Palette knife 2', { edge: 'across', angle: -0.3, curve: 0.7, tilt0: 0.3, tilt1: 1.4 }),
    mk('Palette knife 2', { edge: 'along', angle: 1.9, curve: 0.6, tilt0: 0.5, tilt1: 0.9, start: startFor(1, o0, 0.8, 0.15) }),
    mk('Scraper', { edge: 'pen', angle: 0.1, curve: 0.4, tilt0: 0.6, tilt1: 1.3 }),
    mk('Scraper', { edge: 'across', angle: 0.9, curve: 0.5, tilt0: 0.4, tilt1: 1.5 }),
  ].map(buildSequence);
})();

// ---- uniforms exactly as MediaLayerEngine.pasteStep builds them ----
function uniformsFor(st, b, scale, origin) {
  const t = TOOTH_MM;
  const cap = b.blade ? b.bladeCap : b.thickMm * b.rate * b.loadLenMm / b.lenMm;
  return {
    u_center: [st.x, st.y], u_wDir: [st.wDirX, st.wDirY], u_lDir: [st.lDirX, st.lDirY],
    u_halfW: st.halfW, u_len: st.len, u_lenMax: st.lenMax, u_shape: b.shape, u_pressure: st.pressure,
    u_thick: st.thick ?? b.thickMm, u_scrape: b.scrape, u_fingers: st.fingers ?? b.fingers ?? 1.0,
    u_rag: b.rag ?? 1.0, u_arc: b.arc ?? 0.18, u_sparse: b.sparse ?? 0.0,
    u_ends: loadedEnds(b) ? 1 : 0, u_lift: st.lift ?? 0, u_land: st.land ?? 0, u_cover: 0, u_carry: 0,
    u_blade: b.blade ? 1 : 0, u_fillDips: b.fillDips ?? 1.0, u_bladeLoad: st.loadMode ? 1 : 0,
    u_bladeDir: [st.bladeDirX ?? 1, st.bladeDirY ?? 0], u_travel: [st.travelX ?? 1, st.travelY ?? 0],
    u_bladeLen: st.bladeLen ?? nz(b.bladeLenMm, 1.0), u_bladeHalfW: st.bladeHalf ?? nz(b.bladeHalfMm, 1.0),
    u_bladeH0: st.bladeH0 ?? 0, u_bladeTan: st.bladeTan ?? 0, u_bladeBead: b.bead,
    u_bladePressMm: (b.pressIn ?? 0.6) * t * (st.pressK ?? st.pressure * st.pressure), u_bladeFilm: b.film ?? 0.004,
    u_hairDepth: b.hairDepth, u_ridge: b.ridge, u_rate: b.rate, u_mix: b.mix, u_swap: b.swap,
    u_bow: b.bow, u_lump: b.lump, u_rigid: b.rigid, u_slideMm: st.slideMm, u_cellCap: cap,
    u_pxPerMm: PPM, u_toothMm: t, u_layerOrigin: origin, u_layerScale: scale, u_wick: b.wick,
  };
}

(async () => {
  const exe = (() => {
    const c = argVal('chrome', process.env.CHROME || null);
    if (c && fs.existsSync(c)) return c;
    const edge = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';
    return fs.existsSync(edge) ? edge : null;
  })();
  if (!exe) { console.log(JSON.stringify({ pass: false, error: 'no browser: pass --chrome <exe> or set CHROME' })); process.exit(1); }
  if (process.argv.includes('--anchors')) { console.log(JSON.stringify(anchorTable())); process.exit(0); }   // no browser: print the pinned bounds
  const browser = await chromium.launch({ executablePath: exe, args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'] });
  let out;
  try {
    const page = await browser.newPage();
    const payload = {
      SRC, SIZE, CHECK_EVERY,
      seqs: SEQS.map(s => ({
        name: s.cfg.brush + (s.cfg.edge ? ' [' + s.cfg.edge + ']' : '') + (s.cfg.seedTag ? ' (' + s.cfg.seedTag + ')' : '') + (s.cfg.scale !== 1 ? ' @x' + s.cfg.scale : ''),
        brushName: s.cfg.brush, blade: s.brush.blade, clean: s.brush.clean, cellCap: s.brush.blade ? s.brush.bladeCap : s.brush.thickMm * s.brush.rate * s.brush.loadLenMm / s.brush.lenMm,
        scale: s.cfg.scale, origin: s.cfg.origin,
        steps: s.steps.map((st, i) => ({ u: uniformsFor(st, s.brush, s.cfg.scale, s.cfg.origin), seed: 7, rect: s.rects[i] })),
      })),
    };
    out = await page.evaluate(async (P) => {
      const fail = (error, extra = {}) => ({ error, ...extra });
      const W = P.SIZE, H = P.SIZE, LANES = 32, DEPTH = 8;
      const c = document.createElement('canvas'); c.width = 16; c.height = 16;
      const gl = c.getContext('webgl2', { antialias: false, alpha: false });
      if (!gl) return fail('no webgl2');
      if (!gl.getExtension('EXT_color_buffer_float')) return fail('no EXT_color_buffer_float');
      const comp = (t, s) => { const sh = gl.createShader(t); gl.shaderSource(sh, s); gl.compileShader(sh); if (!gl.getShaderParameter(sh, gl.COMPILE_STATUS)) throw new Error((t === gl.VERTEX_SHADER ? 'vs: ' : 'fs: ') + gl.getShaderInfoLog(sh)); return sh; };
      const link = (vs, fs) => { const p = gl.createProgram(); gl.attachShader(p, comp(gl.VERTEX_SHADER, vs)); gl.attachShader(p, comp(gl.FRAGMENT_SHADER, fs)); gl.bindAttribLocation(p, 0, 'a_corner'); gl.linkProgram(p); if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error('link: ' + gl.getProgramInfoLog(p)); return p; };
      let progDab, progBrush, progCopy;
      try { progDab = link(P.SRC.vert, P.SRC.dab); progBrush = link(P.SRC.vert, P.SRC.brush); progCopy = link(P.SRC.vert, P.SRC.copy); }
      catch (e) { return fail('compile/link: ' + e.message); }
      const uinfo = new Map();
      const infoOf = (p) => { if (!uinfo.has(p)) { const m = {}; const n = gl.getProgramParameter(p, gl.ACTIVE_UNIFORMS); for (let i = 0; i < n; i++) { const a = gl.getActiveUniform(p, i); m[a.name.replace(/\[0\]$/, '')] = { type: a.type, loc: gl.getUniformLocation(p, a.name) }; } uinfo.set(p, m); } return uinfo.get(p); };
      const missing = new Set();
      // Set every uniform the program declares from the map (numbers coerced to the declared type, like the engine's MediaProgram).
      function use(prog, vals) {
        gl.useProgram(prog);
        const info = infoOf(prog); let unit = 0;
        for (const [name, u] of Object.entries(info)) {
          const v = vals[name];
          if (v === undefined) { missing.add(name); continue; }
          if (u.type === gl.SAMPLER_2D) { gl.activeTexture(gl.TEXTURE0 + unit); gl.bindTexture(gl.TEXTURE_2D, v.tex); gl.uniform1i(u.loc, unit); unit++; }
          else if (u.type === gl.FLOAT) gl.uniform1f(u.loc, +v);
          else if (u.type === gl.INT) gl.uniform1i(u.loc, v | 0);
          else if (u.type === gl.FLOAT_VEC2) gl.uniform2fv(u.loc, v);
          else if (u.type === gl.FLOAT_VEC4) gl.uniform4fv(u.loc, v);
          else throw new Error('unhandled uniform type for ' + name);
        }
      }
      const quad = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, quad); gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([0, 0, 1, 0, 0, 1, 1, 1]), gl.STATIC_DRAW);
      const vao = gl.createVertexArray(); gl.bindVertexArray(vao); gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
      gl.disable(gl.BLEND); gl.disable(gl.SCISSOR_TEST); gl.disable(gl.DEPTH_TEST);
      const ftex = (w, h, data) => {
        const t = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, t);
        gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA32F, w, h, 0, gl.RGBA, gl.FLOAT, data || null);
        for (const [k, v] of [[gl.TEXTURE_MIN_FILTER, gl.NEAREST], [gl.TEXTURE_MAG_FILTER, gl.NEAREST], [gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE], [gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE]]) gl.texParameteri(gl.TEXTURE_2D, k, v);
        return t;
      };
      const fbo2 = (a, b) => {
        const f = gl.createFramebuffer(); gl.bindFramebuffer(gl.FRAMEBUFFER, f);
        gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, a, 0); gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT1, gl.TEXTURE_2D, b, 0);
        gl.drawBuffers([gl.COLOR_ATTACHMENT0, gl.COLOR_ATTACHMENT1]);
        if (gl.checkFramebufferStatus(gl.FRAMEBUFFER) !== gl.FRAMEBUFFER_COMPLETE) throw new Error('fbo incomplete');
        return f;
      };
      const rng32 = (a) => () => { a |= 0; a = a + 0x6D2B79F5 | 0; let t = Math.imul(a ^ a >>> 15, 1 | a); t = t + Math.imul(t ^ t >>> 7, 61 | t) ^ t; return ((t ^ t >>> 14) >>> 0) / 4294967296; };
      // Smooth value noise on a grid (for believable canvases).
      function noiseField(seed, cell) {
        const r = rng32(seed), gw = Math.ceil(W / cell) + 2, g = new Float32Array(gw * gw);
        for (let i = 0; i < g.length; i++) g[i] = r();
        return (x, y) => { const fx = x / cell, fy = y / cell, ix = Math.floor(fx), iy = Math.floor(fy), tx = fx - ix, ty = fy - iy; const sx = tx * tx * (3 - 2 * tx), sy = ty * ty * (3 - 2 * ty);
          const a = g[iy * gw + ix], b = g[iy * gw + ix + 1], c2 = g[(iy + 1) * gw + ix], d = g[(iy + 1) * gw + ix + 1]; return (a + (b - a) * sx) * (1 - sy) + (c2 + (d - c2) * sx) * sy; };
      }
      // A seeded oil canvas: patches of opaque paint (K, S per mm scaled by thickness), wet and set, gaps; paper tooth in the bake.
      function canvas(seed) {
        const p0 = new Float32Array(W * H * 4), p1 = new Float32Array(W * H * 4), bk = new Float32Array(W * H * 4);
        const nT = noiseField(seed, 61), nC = noiseField(seed + 1, 97), nO = noiseField(seed + 2, 43), nF = noiseField(seed + 3, 5), nH = noiseField(seed + 4, 3);
        const r = rng32(seed + 9);
        for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
          const o = (y * W + x) * 4;
          const th = Math.max(0, nT(x, y) * 1.6 - 0.45) * (0.5 + 0.5 * nF(x, y));   // 0 .. ~0.6 mm, with empty gaps
          const hue = nC(x, y);
          const K = [2 + 18 * hue, 1 + 12 * (1 - hue), 0.5 + 9 * Math.abs(hue - 0.5) * 2];
          p0[o] = K[0] * th; p0[o + 1] = K[1] * th; p0[o + 2] = K[2] * th; p0[o + 3] = th;
          const S = 30; p1[o] = S * th; p1[o + 1] = S * th; p1[o + 2] = S * th; p1[o + 3] = th > 0 ? Math.min(1, 0.2 + 0.9 * nO(x, y)) : 0;
          bk[o + 2] = Math.min(1, 0.15 + 0.7 * nH(x, y) + 0.15 * r());
        }
        return { p0, p1, bk };
      }
      function brushCells(seq, seed) {
        const a = new Float32Array(LANES * DEPTH * 4), b = new Float32Array(LANES * DEPTH * 4);
        if (seq.clean) return { a, b };
        const r = rng32(seed);
        for (let j = 0; j < DEPTH; j++) for (let i = 0; i < LANES; i++) {
          const o = (j * LANES + i) * 4, amt = seq.cellCap * (0.35 + 0.65 * r());
          const k = [1 + 8 * r(), 1 + 6 * r(), 0.5 + 4 * r()];
          a[o] = k[0] * amt; a[o + 1] = k[1] * amt; a[o + 2] = k[2] * amt; a[o + 3] = amt; b[o] = 30 * amt;
        }
        return { a, b };
      }
      const NONE = (() => { const t = ftex(1, 1, new Float32Array(4)); return t; })();
      // A context = the full engine state for one run of a sequence.
      function makeCtx(seq, cv, bc) {
        const st = [0, 1].map(() => { const p0 = ftex(W, H, null), p1 = ftex(W, H, null); return { p0, p1, fbo: fbo2(p0, p1) }; });
        const bkTex = ftex(W, H, cv.bk);
        // seed state[0]
        gl.bindTexture(gl.TEXTURE_2D, st[0].p0); gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA32F, W, H, 0, gl.RGBA, gl.FLOAT, cv.p0);
        gl.bindTexture(gl.TEXTURE_2D, st[0].p1); gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA32F, W, H, 0, gl.RGBA, gl.FLOAT, cv.p1);
        // state[1] starts as an exact copy too (stale data there is irrelevant: only the rect is copied back)
        const br = [0, 1].map(() => { const b0 = ftex(LANES, DEPTH, bc.a), b1 = ftex(LANES, DEPTH, bc.b); return { b0, b1, fbo: fbo2(b0, b1) }; });
        return { st, bkTex, br, bcur: 0 };
      }
      function destroyCtx(x) {
        for (const s of x.st) { gl.deleteFramebuffer(s.fbo); gl.deleteTexture(s.p0); gl.deleteTexture(s.p1); }
        for (const b of x.br) { gl.deleteFramebuffer(b.fbo); gl.deleteTexture(b.b0); gl.deleteTexture(b.b1); }
        gl.deleteTexture(x.bkTex);
      }
      function draw() { gl.bindVertexArray(vao); gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4); }
      // quad = the rect the dab quad covers (u_rect); tight = the rect shaded (scissored when it differs from quad) and copied back.
      function stepCtx(x, seq, s, quad, tight) {
        const u = s.u, cur = x.br[x.bcur], nxt = x.br[1 - x.bcur];
        const common = { ...u, u_layerSize: [W, H], u_paperBake: { tex: x.bkTex }, u_p0: { tex: x.st[0].p0 }, u_p1: { tex: x.st[0].p1 }, u_seed: s.seed };
        // brush side (reads the canvas before the step) into the other brush buffer
        gl.bindFramebuffer(gl.FRAMEBUFFER, nxt.fbo); gl.viewport(0, 0, LANES, DEPTH);
        use(progBrush, { ...common, u_brush0: { tex: cur.b0 }, u_brush1: { tex: cur.b1 }, u_active: 1, u_rect: [0, 0, LANES, DEPTH], u_targetSize: [LANES, DEPTH] });
        draw();
        if (quad && tight) {
          gl.bindFramebuffer(gl.FRAMEBUFFER, x.st[1].fbo); gl.viewport(0, 0, W, H);
          if (tight !== quad) { gl.enable(gl.SCISSOR_TEST); gl.scissor(tight[0], tight[1], tight[2] - tight[0], tight[3] - tight[1]); }
          use(progDab, { ...common, u_brush0: { tex: cur.b0 }, u_brush1: { tex: cur.b1 }, u_targetSize: [W, H], u_rect: quad });
          draw();
          gl.disable(gl.SCISSOR_TEST);
          gl.bindFramebuffer(gl.FRAMEBUFFER, x.st[0].fbo); gl.viewport(0, 0, W, H);
          use(progCopy, { u_a: { tex: x.st[1].p0 }, u_b: { tex: x.st[1].p1 }, u_c: { tex: NONE }, u_targetSize: [W, H], u_rect: tight, u_layerOrigin: u.u_layerOrigin, u_layerScale: u.u_layerScale });
          draw();
        }
        x.bcur = 1 - x.bcur;
        const e = gl.getError(); if (e !== gl.NO_ERROR) throw new Error('GL error 0x' + e.toString(16) + ' in ' + seq.name);
      }
      // Readback of the two state textures of state[0] over a layer rect (default: whole layer), as raw u32 bit patterns.
      function readBits(x, rect) {
        const [x0, y0, x1, y1] = rect || [0, 0, W, H], w = x1 - x0, h = y1 - y0;
        gl.bindFramebuffer(gl.FRAMEBUFFER, x.st[0].fbo);
        const a = new Float32Array(w * h * 4), b = new Float32Array(w * h * 4);
        gl.readBuffer(gl.COLOR_ATTACHMENT0); gl.readPixels(x0, y0, w, h, gl.RGBA, gl.FLOAT, a);
        gl.readBuffer(gl.COLOR_ATTACHMENT1); gl.readPixels(x0, y0, w, h, gl.RGBA, gl.FLOAT, b);
        return { a: new Uint32Array(a.buffer), b: new Uint32Array(b.buffer), w, h };
      }
      function diffPixels(p, q) {   // pixels where any of p0/p1's 8 floats differ in bit pattern
        let n = 0; const N = p.w * p.h;
        for (let i = 0; i < N; i++) { const o = i * 4; if (p.a[o] !== q.a[o] || p.a[o + 1] !== q.a[o + 1] || p.a[o + 2] !== q.a[o + 2] || p.a[o + 3] !== q.a[o + 3] || p.b[o] !== q.b[o] || p.b[o + 1] !== q.b[o + 1] || p.b[o + 2] !== q.b[o + 2] || p.b[o + 3] !== q.b[o + 3]) n++; }
        return n;
      }
      // For the informational 'small quad' variant: how big are the differences (they are ulp noise, not paint)?
      function diffStats(p, q) {
        const fa = new Float32Array(p.a.buffer), fb = new Float32Array(p.b.buffer), ga = new Float32Array(q.a.buffer), gb = new Float32Array(q.b.buffer);
        let maxAbs = 0, over = 0; const N = p.w * p.h;
        for (let i = 0; i < N; i++) {
          let m = 0;
          for (let c = 0; c < 4; c++) { const o = i * 4 + c; m = Math.max(m, Math.abs(fa[o] - ga[o]), Math.abs(fb[o] - gb[o])); }
          if (m > maxAbs) maxAbs = m; if (m > 1e-4) over++;
        }
        return { maxAbs, over };
      }
      const results = []; let allPass = true;
      let canvasesTried = 0;
      for (const seq of P.seqs) {
        const seedN = 1000 + canvasesTried * 17; canvasesTried++;
        const cv = canvas(seedN), bc = brushCells(seq, seedN + 5);
        const A = makeCtx(seq, cv, bc), B = makeCtx(seq, cv, bc), C = makeCtx(seq, cv, bc), D = makeCtx(seq, cv, bc);
        const init = readBits(A);
        const r = { name: seq.name, steps: seq.steps.length, compared: 0, mismatchNew: 0, mismatchControl: 0, checkpoints: 0, changedPixels: 0, oldArea: 0, newArea: 0,
          changedOutsideNewRect: 0, minMarginPx: Infinity, stepsWithChange: 0, skippedControlSteps: 0, smallQuadMismatch: 0, smallQuadMaxAbs: 0, smallQuadOver1e4: 0 };
        try {
          for (let i = 0; i < seq.steps.length; i++) {
            const s = seq.steps[i], rc = s.rect;
            // what the OLD run changes this step, read inside the old square only (nothing outside it can change)
            const before = rc.old ? readBits(A, rc.old) : null;
            stepCtx(A, seq, s, rc.old, rc.old);          // legacy: whole square
            stepCtx(B, seq, s, rc.old, rc.neu);          // engine: same square quad, scissored to the tight box
            if (!rc.ctl) r.skippedControlSteps++;
            stepCtx(C, seq, s, rc.old, rc.ctl);          // broken control: box shrunk by INSET_PX
            stepCtx(D, seq, s, rc.neu, rc.neu);          // informational: a smaller QUAD (not used by the engine)
            if (rc.old) r.oldArea += (rc.old[2] - rc.old[0]) * (rc.old[3] - rc.old[1]);
            if (rc.neu) r.newArea += (rc.neu[2] - rc.neu[0]) * (rc.neu[3] - rc.neu[1]);
            if (before) {
              const after = readBits(A, rc.old), ox = rc.old[0], oy = rc.old[1], nr = rc.neu;
              let changed = 0, stepOut = 0;
              for (let k = 0; k < after.w * after.h; k++) {
                const o = k * 4;
                if (after.a[o] === before.a[o] && after.a[o + 1] === before.a[o + 1] && after.a[o + 2] === before.a[o + 2] && after.a[o + 3] === before.a[o + 3] &&
                    after.b[o] === before.b[o] && after.b[o + 1] === before.b[o + 1] && after.b[o + 2] === before.b[o + 2] && after.b[o + 3] === before.b[o + 3]) continue;
                changed++;
                const px = ox + (k % after.w), py = oy + Math.floor(k / after.w);
                if (!nr || px < nr[0] || px >= nr[2] || py < nr[1] || py >= nr[3]) stepOut++;
                else r.minMarginPx = Math.min(r.minMarginPx, px - nr[0], nr[2] - 1 - px, py - nr[1], nr[3] - 1 - py);
              }
              r.changedPixels += changed; r.changedOutsideNewRect += stepOut; if (changed) r.stepsWithChange++;
            }
            if ((i + 1) % P.CHECK_EVERY === 0 || i === seq.steps.length - 1) {
              const a = readBits(A), b = readBits(B), cc = readBits(C), d = readBits(D);
              r.mismatchNew += diffPixels(a, b); r.mismatchControl += diffPixels(a, cc); r.compared += W * H; r.checkpoints++;
              r.smallQuadMismatch += diffPixels(a, d); const ds = diffStats(a, d); r.smallQuadMaxAbs = Math.max(r.smallQuadMaxAbs, ds.maxAbs); r.smallQuadOver1e4 += ds.over;
            }
          }
        } catch (e) { r.error = String(e.message || e); }
        const fin = readBits(A); r.finalChangedFromStart = diffPixels(init, fin);
        r.pass = !r.error && r.mismatchNew === 0 && r.changedOutsideNewRect === 0 && r.mismatchControl > 0 && r.finalChangedFromStart > 500 && r.changedPixels > 500;
        if (r.minMarginPx === Infinity) r.minMarginPx = null;
        if (!r.pass) allPass = false;
        results.push(r);
        destroyCtx(A); destroyCtx(B); destroyCtx(C); destroyCtx(D);
      }
      return { results, allPass, missingUniforms: [...missing].sort() };
    }, payload);
  } finally {
    await browser.close();
  }
  if (out.error) { console.log(JSON.stringify({ pass: false, ...out })); process.exit(1); }
  const tot = out.results.reduce((t, r) => ({
    compared: t.compared + r.compared, mismatchNew: t.mismatchNew + r.mismatchNew, mismatchControl: t.mismatchControl + r.mismatchControl,
    changedPixels: t.changedPixels + r.changedPixels, smallQuadMismatch: t.smallQuadMismatch + r.smallQuadMismatch, smallQuadMaxAbs: Math.max(t.smallQuadMaxAbs, r.smallQuadMaxAbs), smallQuadOver1e4: t.smallQuadOver1e4 + r.smallQuadOver1e4, changedOutsideNewRect: t.changedOutsideNewRect + r.changedOutsideNewRect, oldArea: t.oldArea + r.oldArea, newArea: t.newArea + r.newArea,
  }), { compared: 0, mismatchNew: 0, mismatchControl: 0, smallQuadMismatch: 0, smallQuadMaxAbs: 0, smallQuadOver1e4: 0, changedPixels: 0, changedOutsideNewRect: 0, oldArea: 0, newArea: 0 });
  const pass = out.allPass && tot.mismatchNew === 0 && tot.mismatchControl > 0;
  console.log(JSON.stringify({ pass, totals: { ...tot, areaRatioNewOverOld: +(tot.newArea / tot.oldArea).toFixed(4) }, perSequence: out.results.map(r => ({ ...r, areaRatio: r.oldArea ? +(r.newArea / r.oldArea).toFixed(4) : null })),
    anchors: anchorTable(), unsetUniforms: out.missingUniforms, size: SIZE, stepsPerSequence: STEPS }, null, 1));
  process.exit(pass ? 0 : 1);
})().catch(e => { console.log(JSON.stringify({ pass: false, error: String(e && e.stack || e) })); process.exit(1); });
