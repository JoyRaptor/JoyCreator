// main.js — the media lab page: draw with a pen (pressure + tilt), or run a fixed test sheet.
import { MediaEngine } from './engine.js';
import { loadCatalogue, loadSurface, DOC_PX_PER_MM } from './paper.js';
import { STICKS, DryStroke, stickMaterial, PRESS, MAT_OVERRIDE, ZONES } from './stick.js';
import { SHEETS } from './tests.js';
import { WET, WET_BRUSHES, WETNESS, WetStroke, paintFromColor, tiltSlope } from './wet.js';
import { SplineFeeder } from './spline.js';
import { PASTE_BRUSHES, PasteStroke, opaquePaint, EDGE_MODES, EDGE_LABELS } from './paste.js';
import { VectorDoc } from './vector.js';

const qs = new URLSearchParams(location.search);
const SHADERS = qs.get('shaders') || '../../shaders/';
const ASSETS = qs.get('assets') || '../../assets/paper/';

const canvas = document.getElementById('c');
const gl = canvas.getContext('webgl2', { antialias: false, preserveDrawingBuffer: true });
const ui = id => document.getElementById(id);

const state = {
  tool: qs.get('tool') || 'Proto',
  // Which medium the tool belongs to: names repeat across media (an all-round brush in watercolour and in oil).
  kind: qs.get('kind') || (STICKS[qs.get('tool') || 'Proto'] ? 'dry' : WET_BRUSHES[qs.get('tool')] ? 'wet' : 'paste'),
  paperId: qs.get('paper') || 'drawing_tooth',
  tiltReach: Number(qs.get('reach') || 68),    // device tilt (°) that counts as "lying on its side" (owner's S Pen tops out at 71°)
  mousePressure: 0.5,
  bellyMode: 'off',
  edgeModes: {},
  wetLevel: 3,
  color: [0.22, 0.38, 0.75],
  seed: 100,
  mouseTilt: 0,
  view: { pan: [0, 0], zoom: 1 },
  look: { impasto: Number(qs.get('impasto') || 1.4), pxPerMm: DOC_PX_PER_MM, paperColor: [0.96, 0.95, 0.93], lamp: norm([-0.55, 0.55, 0.62]), relief: Number(qs.get('relief') || 0.3), sheen: 0.06, capMm: 0.004, mode: 0 },
  stroke: null,
  dirty: true,
};

let engine, paper, catalogue;

// Tuning hooks (lab only): ?press=broad:0.3,gamma:1.8  ?mat=abrasion:30  ?proto=rInf:0.04
function applyTuning() {
  const parse = v => Object.fromEntries((v || '').split(',').filter(Boolean).map(kv => { const [k, x] = kv.split(':'); return [k, Number(x)]; }));
  Object.assign(PRESS, parse(qs.get('press')));
  Object.assign(MAT_OVERRIDE, parse(qs.get('mat')));
  Object.assign(STICKS.Proto, parse(qs.get('proto')));
  if (qs.get('texel')) globalThis.__TEXEL_SCALE = Number(qs.get('texel'));
  Object.assign(ZONES, parse(qs.get('zones')));
  Object.assign(WET, parse(qs.get('wet')));
}

async function main() {
  applyTuning();
  const test = qs.get('test');
  const size = test && SHEETS[test] ? SHEETS[test].size : [1080, 1920];
  if (test && SHEETS[test] && SHEETS[test].paper && !qs.get('paper')) state.paperId = SHEETS[test].paper;
  fitCanvas(test ? size : null);
  catalogue = await loadCatalogue([[ASSETS, 'catalogue.json']]);
  paper = await loadSurface(gl, catalogue, state.paperId);
  state.look.paperColor = paperColorFor(paper.id);
  engine = await MediaEngine.create(gl, SHADERS, size[0], size[1]);
  buildUi();
  if (test) {
    state.view = { pan: [0, 0], zoom: Number(qs.get('zoom') || 1) };
    if (qs.get('cx')) {  // centre a doc point (for close-up crops)
      const z = state.view.zoom;
      state.view.pan = [canvas.width / 2 - Number(qs.get('cx')) * z, canvas.height / 2 - Number(qs.get('cy')) * z];
    }
    runSheet(SHEETS[test]);
    state.look.mode = Number(qs.get('mode') || 0);
    if (qs.get('vector')) {
      // Vector check: the same sheet as records, replayed sharp into the zoomed view.
      let sd = 1;
      vdoc.records = SHEETS[test].strokes(DOC_PX_PER_MM).filter(st => st.samples)
        .map(st => ({ kind: st.kind || 'dry', tool: st.tool, color: st.color, belly: st.belly || null, dirty: !!st.dirty, seed: sd++, samples: st.samples }));
      state.vector = true;
      await refreshView();
    }
    draw();
    window.__probe = (x, y) => engine.probe(x, y);
    window.__engine = engine;
    // Debug: the rendered pixel at doc (x, y) in a look mode (8 = the lit slope).
    window.__px = (x, y, mode = 0) => {
      const keep = state.look.mode; state.look.mode = mode; draw();
      const sx = Math.round(x * state.view.zoom + state.view.pan[0]), sy = Math.round(y * state.view.zoom + state.view.pan[1]);
      const px = new Uint8Array(4); gl.readPixels(sx, sy, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, px);
      state.look.mode = keep; draw();
      return [px[0], px[1], px[2]];
    };
    window.__wetSteps = (n, slope) => { engine.slope = slope; for (let f = 0; f < n; f++) engine.wetFrame(null, paper, DOC_PX_PER_MM); };
    // Measurement: mean darkness (0 = paper, 1 = black) of a doc-px box, and a darkness profile along a row.
    window.__dark = (x0, y0, x1, y1) => {
      draw();
      const w = Math.max(1, Math.round(x1 - x0)), h = Math.max(1, Math.round(y1 - y0));
      const px = new Uint8Array(w * h * 4);
      gl.readPixels(Math.round(x0), Math.round(y0), w, h, gl.RGBA, gl.UNSIGNED_BYTE, px);
      const ref = 0.2126 * state.look.paperColor[0] + 0.7152 * state.look.paperColor[1] + 0.0722 * state.look.paperColor[2];
      let sum = 0;
      for (let i = 0; i < w * h; i++) sum += (0.2126 * px[i * 4] + 0.7152 * px[i * 4 + 1] + 0.0722 * px[i * 4 + 2]) / 255;
      return Math.max(0, 1 - sum / (w * h) / ref);
    };
    window.__row = (x0, x1, y, band = 6) => {
      const out = [];
      for (let x = x0; x < x1; x += 2) out.push(+window.__dark(x, y - band, x + 2, y + band).toFixed(3));
      return out;
    };
    window.__done = true;
    return;
  }
  // Open at true size (1 doc px ≈ 0.05 mm ≈ one Note 9 pixel), showing the sample sheet.
  state.view.zoom = Math.min(1, canvas.width / size[0]);
  state.view.pan = [(canvas.width - size[0] * state.view.zoom) / 2, canvas.height - size[1] * state.view.zoom];
  runSheet(SHEETS.proto, [40, 400]);
  state.dirty = true;
  hookInput();
  requestAnimationFrame(loop);
}

function runSheet(sheet, offset = [0, 0]) {
  let seed = 1, n = 0;
  const until = Number(qs.get('until') || 1e9);
  for (const st of sheet.strokes(DOC_PX_PER_MM)) {
    if (n++ >= until) break;
    if (st.tiltDeg !== undefined) { engine.slope = tiltSlope(st.tiltDeg, st.tiltDir); continue; }
    if (st.wait !== undefined) {   // let the water run with no brush on the paper
      const frames = Math.round(st.wait / (WET.dt * WET.substeps));
      for (let f = 0; f < frames && engine.wetActive; f++) engine.wetFrame(null, paper, DOC_PX_PER_MM);
      continue;
    }
    for (const s of st.samples) { s.x += offset[0]; s.y += offset[1]; }
    if (st.kind === 'paste') {
      const brush = PASTE_BRUSHES[st.tool];
      if (!st.dirty) engine.reloadBrush(opaquePaint(st.color), brush, seed, st.belly ? opaquePaint(st.belly) : null);
      const ps = new PasteStroke(brush, DOC_PX_PER_MM, 1, { edge: st.edge || qs.get('edge') || undefined });
      const sd = seed++;
      for (const smp of st.samples) { ps.add(smp); for (const step of ps.take()) engine.pasteStep(step, brush, paper, DOC_PX_PER_MM, sd); }
      continue;
    }
    if (st.kind === 'wet') {
      const ws = new WetStroke(WET_BRUSHES[st.tool], paintFromColor(st.color), paper, DOC_PX_PER_MM, seed++, st.wetness !== undefined ? WETNESS[st.wetness] : null);
      for (let i = 0; i < st.samples.length; i++) {
        ws.add(st.samples[i]);
        if (i === st.samples.length - 1) ws.finish();
        if (i % 4 === 3 || i === st.samples.length - 1) engine.wetFrame(ws.take(), paper, DOC_PX_PER_MM);
      }
      continue;
    }
    const stick = STICKS[st.tool];
    const ds = new DryStroke(stick, paper, DOC_PX_PER_MM);
    const mat = stickMaterial(stick);
    const feeder = new SplineFeeder(s => ds.add(s));
    const put = st.spline === false ? s => ds.add(s) : s => feeder.add(s);
    for (let i = 0; i < st.samples.length; i++) {
      put(st.samples[i]);
      if (i === st.samples.length - 1 && st.spline !== false) feeder.finish();
      if (i % 4 === 3 || i === st.samples.length - 1) engine.dryFrame(ds.take(), paper, mat, DOC_PX_PER_MM);
    }
  }
}

// One live stroke into one engine (the page layer, and when a sharp zoomed view is showing, that too).
// ---- belly colour modes (owner, 2026-10-07: choosing a second colour every stroke gets tedious) ----
const BELLY_MODES = ['off', 'manual', 'darker', 'lighter', 'warmer', 'cooler', 'last colour', 'shift'];
function rgbToHsl([r, g, b]) {
  const mx = Math.max(r, g, b), mn = Math.min(r, g, b), l = (mx + mn) / 2;
  if (mx === mn) return [0, 0, l];
  const d = mx - mn, s = l > 0.5 ? d / (2 - mx - mn) : d / (mx + mn);
  const h = mx === r ? (g - b) / d + (g < b ? 6 : 0) : mx === g ? (b - r) / d + 2 : (r - g) / d + 4;
  return [h / 6, s, l];
}
function hslToRgb([h, s, l]) {
  if (s === 0) return [l, l, l];
  const q = l < 0.5 ? l * (1 + s) : l + s - l * s, p = 2 * l - q;
  const f = t => { t = (t % 1 + 1) % 1; return t < 1 / 6 ? p + (q - p) * 6 * t : t < 0.5 ? q : t < 2 / 3 ? p + (q - p) * (2 / 3 - t) * 6 : p; };
  return [f(h + 1 / 3), f(h), f(h - 1 / 3)];
}
// Toward a hue (0..1) by up to `by` of a turn, the short way round.
function hueToward(h, target, by) { let d = target - h; d -= Math.round(d); return h + Math.sign(d) * Math.min(Math.abs(d), by); }
function bellyFor(mode, color, seed) {
  const [h, s, l] = rgbToHsl(color);
  switch (mode) {
    case 'manual': return state.bellyColor;
    case 'darker': return hslToRgb([hueToward(h, 0.66, 0.03), Math.min(1, s * 1.1), l * 0.45]);
    case 'lighter': return hslToRgb([h, s * 0.8, l + (1 - l) * 0.55]);
    case 'warmer': return hslToRgb([hueToward(h, 0.08, 0.07), Math.min(1, s * 1.05), Math.min(0.95, l * 1.05)]);
    case 'cooler': return hslToRgb([hueToward(h, 0.6, 0.07), s * 0.95, l * 0.92]);
    case 'last colour': return state.prevColor || null;
    case 'shift': {
      let x = (seed * 2654435761) >>> 0;
      const r = () => ((x = (x * 1664525 + 1013904223) >>> 0) / 4294967296 - 0.5);
      return hslToRgb([h + 0.06 * r(), Math.min(1, Math.max(0, s + 0.2 * r())), Math.min(0.95, Math.max(0.05, l + 0.18 * r()))]);
    }
    default: return null;
  }
}

// Each blade tool remembers its own edge mode.
function edgeFor(tool) { return state.edgeModes[tool] || PASTE_BRUSHES[tool].orient || 'pen'; }

function startStroke(eng, meta) {
  if (meta.kind === 'paste') {
    const brush = PASTE_BRUSHES[meta.tool];
    if (!meta.dirty) eng.reloadBrush(opaquePaint(meta.color), brush, meta.seed, meta.belly ? opaquePaint(meta.belly) : null);
    const ds = new PasteStroke(brush, DOC_PX_PER_MM, eng.scale, { edge: meta.edge });
    return { add: s => ds.add(s), flush() { for (const st of ds.take()) eng.pasteStep(st, brush, paper, DOC_PX_PER_MM, meta.seed); }, finish() { this.flush(); } };
  }
  if (meta.kind === 'wet') {
    const ds = new WetStroke(WET_BRUSHES[meta.tool], paintFromColor(meta.color), paper, DOC_PX_PER_MM, meta.seed, meta.wetness);
    return { wet: true, add: s => ds.add(s), take: () => ds.take(), flush() {}, finish() { ds.finish(); } };
  }
  const stick = STICKS[meta.tool], ds = new DryStroke(stick, paper, DOC_PX_PER_MM), mat = stickMaterial(stick);
  return { add: s => ds.add(s), flush() { const b = ds.take(); if (b.count) eng.dryFrame(b, paper, mat, DOC_PX_PER_MM); }, finish() { this.flush(); } };
}

// ---- vector: records replayed at screen resolution when zoomed in (pencil and oil) ----
const vdoc = new VectorDoc();
function viewRegion() {
  const v = state.view;
  return [-v.pan[0] / v.zoom, -v.pan[1] / v.zoom, (canvas.width - v.pan[0]) / v.zoom, (canvas.height - v.pan[1]) / v.zoom];
}
function invalidateView() { state.viewValid = false; state.viewChangedAt = performance.now(); state.dirty = true; }
async function refreshView() {
  // Watercolour is simulated per document cell, so a sharp view is only re-drawn for pencil and oil.
  if (!state.vector || state.view.zoom < 1.3 || vdoc.records.some(r => r.kind === 'wet') || !vdoc.records.length) return;
  state.viewBusy = true;
  const region = viewRegion(), scale = Math.min(8, state.view.zoom);
  if (!state.viewEngine) {
    // Capped at ~2.5 Mpx (a Note 9 screen is 4.3): the sharp view is then ~0.8 layer px per screen px.
    const k = Math.min(1, Math.sqrt(2.5e6 / (canvas.width * canvas.height)));
    state.viewK = k;
    state.viewEngine = await MediaEngine.create(gl, SHADERS, Math.round(canvas.width * k), Math.round(canvas.height * k), [region[0], region[1]], scale * k, { half: true, undoBytes: 0 });
  }
  state.viewEngine.origin = [region[0], region[1]];
  state.viewEngine.scale = scale * state.viewK;
  state.viewEngine.bakedPaper = null;
  vdoc.replay(state.viewEngine, paper, DOC_PX_PER_MM, region);
  state.viewBusy = false;
  state.viewValid = true;
  state.dirty = true;
}

function loop() {
  if (state.stroke) for (const live of state.stroke.lives) if (!live.wet) { live.flush(); state.dirty = true; }
  if (state.vector && !state.viewValid && !state.viewBusy && !state.stroke && performance.now() - (state.viewChangedAt || 0) > 350) refreshView();
  // Water keeps moving whether or not the brush is down; it sleeps when everything has dried.
  const wetLive = state.stroke && state.stroke.lives[0].wet ? state.stroke.lives[0] : null;
  const wetBatch = wetLive ? wetLive.take() : null;
  if (engine.wetActive || (wetBatch && wetBatch.count)) {
    const r = engine.wetRect;
    const big = r && (r[2] - r[0]) * (r[3] - r[1]) > 1.5e6;     // keep the phone responsive on huge washes
    engine.wetFrame(wetBatch, paper, DOC_PX_PER_MM, big ? { substeps: 2 } : {});
    state.dirty = true;
  }
  if (state.dirty) { draw(); state.dirty = false; }
  requestAnimationFrame(loop);
}

function draw() {
  const eng = state.vector && state.viewValid && state.viewEngine ? state.viewEngine : engine;
  eng.render(state.view, paper, state.look, canvas.width, canvas.height);
}

// ---------------- input ----------------
const touches = new Map();
function hookInput() {
  // Anything that breaks while drawing says so in the readout (a silent failure looked like "the pen does nothing").
  window.addEventListener('error', e => { const r = ui('readout'); if (r) { r.textContent = 'Something broke: ' + e.message; r.style.color = 'var(--err)'; } });
  canvas.addEventListener('pointerdown', e => {
    // Capture keeps a stroke going past the canvas edge. Some pens (and synthetic events) refuse it; a refusal
    // must never cost the stroke.
    try { canvas.setPointerCapture(e.pointerId); } catch { /* draw without capture */ }
    if (e.pointerType === 'touch') { touches.set(e.pointerId, [e.clientX, e.clientY]); return; }
    if (e.button === 1) { touches.set(e.pointerId, [e.clientX, e.clientY]); return; }
    if (!state.vector) engine.beginStroke();
    const kind = state.kind;
    const seed = ++state.seed;
    const belly = kind === 'paste' ? bellyFor(state.bellyMode, state.color, seed) : null;
    const wetness = kind === 'wet' && !WET_BRUSHES[state.tool].clear ? WETNESS[state.wetLevel] : kind === 'wet' ? { ...WETNESS[state.wetLevel], load: Math.max(WETNESS[state.wetLevel].load, WET_BRUSHES[state.tool].load) } : null;
    const edge = kind === 'paste' && PASTE_BRUSHES[state.tool].blade ? edgeFor(state.tool) : undefined;
    const meta = { kind, tool: state.tool, color: [...state.color], belly: belly ? [...belly] : null, seed, wetness, edge,
      dirty: kind === 'paste' && state.dirtyBrush && state.lastPaste === state.tool };
    if (kind === 'paste') state.lastPaste = state.tool;
    const lives = [startStroke(engine, meta)];
    if (state.vector && state.viewValid && kind !== 'wet') lives.push(startStroke(state.viewEngine, meta));
    if (state.vector && kind === 'wet') state.viewValid = false;
    const sink = s => { for (const l of lives) l.add(s); };
    state.stroke = { id: e.pointerId, lives, feeder: new SplineFeeder(state.vector ? vdoc.begin(meta, sink) : sink) };
    feed(e);
  });
  canvas.addEventListener('pointermove', e => {
    if (touches.has(e.pointerId)) { moveTouch(e); return; }
    if (!state.stroke || state.stroke.id !== e.pointerId) return;
    const list = e.getCoalescedEvents ? e.getCoalescedEvents() : [e];
    for (const ev of (list.length ? list : [e])) feed(ev);
  });
  const end = e => {
    touches.delete(e.pointerId);
    if (state.stroke && state.stroke.id === e.pointerId) {
      state.stroke.feeder.finish();
      for (const live of state.stroke.lives) live.finish();
      if (state.stroke.lives[0].wet) engine.wetFrame(state.stroke.lives[0].take(), paper, DOC_PX_PER_MM);
      if (state.vector) vdoc.end(); else engine.endStroke();
      state.stroke = null;
      state.dirty = true;
    }
  };
  canvas.addEventListener('pointerup', end);
  canvas.addEventListener('pointercancel', end);
  // Without capture (a pen that refused it) the lift can land on the panel instead: end the stroke anyway.
  window.addEventListener('pointerup', end);
  window.addEventListener('pointercancel', end);
  canvas.addEventListener('wheel', e => {
    e.preventDefault();
    const f = Math.exp(-e.deltaY * 0.0015);
    zoomAt(e.clientX, e.clientY, f);
  }, { passive: false });
  window.addEventListener('resize', () => { fitCanvas(); state.dirty = true; });
}

function moveTouch(e) {
  const prev = touches.get(e.pointerId);
  const pts = [...touches.entries()];
  if (pts.length === 1) {
    const dpr = devicePixelRatio;
    state.view.pan[0] += (e.clientX - prev[0]) * dpr;
    state.view.pan[1] -= (e.clientY - prev[1]) * dpr;
  } else if (pts.length >= 2) {
    const other = pts.find(([id]) => id !== e.pointerId)[1];
    const d0 = Math.hypot(prev[0] - other[0], prev[1] - other[1]);
    const d1 = Math.hypot(e.clientX - other[0], e.clientY - other[1]);
    if (d0 > 1) zoomAt((e.clientX + other[0]) / 2, (e.clientY + other[1]) / 2, d1 / d0);
  }
  touches.set(e.pointerId, [e.clientX, e.clientY]);
  invalidateView();
}

function zoomAt(cx, cy, f) {
  const dpr = devicePixelRatio;
  const sx = cx * dpr, sy = canvas.height - cy * dpr;
  const v = state.view;
  const z = Math.min(32, Math.max(0.1, v.zoom * f));
  const k = z / v.zoom;
  v.pan = [sx - (sx - v.pan[0]) * k, sy - (sy - v.pan[1]) * k];
  v.zoom = z;
  invalidateView();
}

function feed(e) {
  const dpr = devicePixelRatio;
  const r = canvas.getBoundingClientRect();
  const sx = (e.clientX - r.left) * dpr, sy = canvas.height - (e.clientY - r.top) * dpr;
  const x = (sx - state.view.pan[0]) / state.view.zoom, y = (sy - state.view.pan[1]) / state.view.zoom;
  let p, tilt, az;
  if (e.pointerType === 'pen') {
    p = e.pressure;
    if (typeof e.altitudeAngle === 'number') { tilt = Math.PI / 2 - e.altitudeAngle; az = -e.azimuthAngle; }
    else {
      const tx = Math.tan((e.tiltX || 0) * Math.PI / 180), ty = Math.tan((e.tiltY || 0) * Math.PI / 180);
      tilt = Math.atan(Math.hypot(tx, ty)); az = Math.atan2(-ty, tx);
    }
    state.rawTilt = tilt * 180 / Math.PI;
    state.maxTilt = Math.max(state.maxTilt || 0, state.rawTilt);
    const reach = Math.max(20, state.tiltReach) * Math.PI / 180;
    // Input calibration, not physics: the device's reach counts as 'lying on its side' (t = 1). The shape of
    // the response (point → side) is the stick's tilt curve, not this mapping.
    tilt = Math.min(1, tilt / reach) * (Math.PI / 2);
  } else {
    p = state.mousePressure;
    tilt = state.mouseTilt * Math.PI / 180;
    az = -60 * Math.PI / 180;
  }
  state.stroke.feeder.add({ x, y, p, tilt, az, t: e.timeStamp });
  ui('readout').textContent = e.pointerType === 'pen'
    ? `pressure ${p.toFixed(2)} · pen tilt ${state.rawTilt.toFixed(0)}° (most ${state.maxTilt.toFixed(0)}°)`
    : `mouse · pressure ${p.toFixed(2)} · tilt ${(tilt * 180 / Math.PI).toFixed(0)}°`;
}

// ---------------- ui ----------------
function buildUi() {
  const toolSel = ui('tool');
  const dryGroup = document.createElement('optgroup'); dryGroup.label = 'Pencil';
  const opt = (kind, k) => new Option(k, `${kind}:${k}`, false, k === state.tool && kind === state.kind);
  for (const k of Object.keys(STICKS)) dryGroup.append(opt('dry', k));
  const wetGroup = document.createElement('optgroup'); wetGroup.label = 'Watercolour';
  for (const k of Object.keys(WET_BRUSHES)) wetGroup.append(opt('wet', k));
  const pasteGroup = document.createElement('optgroup'); pasteGroup.label = 'Oil';
  for (const k of Object.keys(PASTE_BRUSHES)) pasteGroup.append(opt('paste', k));
  toolSel.append(dryGroup, wetGroup, pasteGroup);
  const dirty = ui('dirtybrush');
  if (dirty) dirty.onchange = () => { state.dirtyBrush = dirty.checked; };
  // Show each brush's own controls only: wetness for watercolour, belly and dirty brush for oil.
  const showFor = () => {
    const isWet = state.kind === 'wet', isPaste = state.kind === 'paste', isHair = isPaste && PASTE_BRUSHES[state.tool].shape < 2;
    ui('wetbox').hidden = !isWet;
    ui('bellybox').hidden = !isHair;
    const isBlade = isPaste && !!PASTE_BRUSHES[state.tool].blade;
    ui('edgebox').hidden = !isBlade;
    if (isBlade) ui('edgemode').textContent = 'Edge: ' + EDGE_LABELS[edgeFor(state.tool)];
    ui('belly').hidden = state.bellyMode !== 'manual';
    ui('dirtybrush').parentElement.hidden = !isPaste;
    // A dry brush means just that: picking it sets the brush nearly dry.
    if (isWet && state.tool === 'Dry brush' && state.lastTool !== 'wet:Dry brush') state.wetLevel = 0;
    state.lastTool = state.kind + ':' + state.tool;
    ui('wetread').textContent = WETNESS[state.wetLevel].name;
  };
  toolSel.onchange = () => { [state.kind, state.tool] = toolSel.value.split(/:(.*)/s); showFor(); };
  const hex = h => [1, 3, 5].map(i => parseInt(h.slice(i, i + 2), 16) / 255);
  const col = ui('color');
  const setCol = () => { state.color = hex(col.value); };
  setCol(); col.oninput = setCol;
  // "Your last colour": the colour you had before the one you just picked.
  let committed = [...state.color];
  col.onchange = () => { state.prevColor = committed; committed = hex(col.value); };
  const bellyCol = ui('belly');
  state.bellyColor = hex(bellyCol.value);
  bellyCol.oninput = () => { state.bellyColor = hex(bellyCol.value); };
  ui('bellymode').onclick = () => {
    state.bellyMode = BELLY_MODES[(BELLY_MODES.indexOf(state.bellyMode) + 1) % BELLY_MODES.length];
    ui('bellymode').textContent = 'Belly: ' + state.bellyMode;
    showFor();
  };
  ui('edgemode').onclick = () => {
    const m = EDGE_MODES[(EDGE_MODES.indexOf(edgeFor(state.tool)) + 1) % EDGE_MODES.length];
    state.edgeModes[state.tool] = m;
    showFor();
  };
  ui('wetter').onclick = () => { state.wetLevel = Math.min(WETNESS.length - 1, state.wetLevel + 1); showFor(); };
  ui('drier').onclick = () => { state.wetLevel = Math.max(0, state.wetLevel - 1); showFor(); };
  showFor();
  ui('dry').onclick = () => { engine.dryNow(paper); state.dirty = true; };
  hookTiltPad();
  hookPhoneTilt();
  const paperSel = ui('paper');
  for (const s of catalogue.surfaces) paperSel.add(new Option(s.name, s.id, false, s.id === paper.id));
  paperSel.onchange = async () => { paper = await loadSurface(gl, catalogue, paperSel.value); state.look.paperColor = paperColorFor(paper.id); invalidateView(); };
  bindRange('reach', v => (state.tiltReach = v));
  bindRange('mp', v => (state.mousePressure = v / 100));
  bindRange('mt', v => (state.mouseTilt = v));
  bindRange('relief', v => { state.look.relief = v / 100; state.dirty = true; });
  ui('undo').onclick = () => {
    if (state.vector) { vdoc.records.pop(); vdoc.replay(engine, paper, DOC_PX_PER_MM); invalidateView(); return; }
    if (engine.undo()) state.dirty = true;
  };
  ui('clear').onclick = () => { engine.beginStroke(); engine.touch([0, 0, engine.w, engine.h]); engine.clearAll(); engine.endStroke(); vdoc.records = []; invalidateView(); };
  const vec = ui('vector');
  vec.onchange = () => { state.vector = vec.checked; invalidateView(); };
  ui('recolor').onclick = () => {
    const last = vdoc.records[vdoc.records.length - 1];
    if (!last) return;
    last.color = [...state.color];
    vdoc.replay(engine, paper, DOC_PX_PER_MM);
    invalidateView();
  };
  ui('sheet').onclick = () => { engine.beginStroke(); runSheet(SHEETS.proto, [40, 400]); engine.endStroke(); state.dirty = true; };
  ui('mode').onchange = e => { state.look.mode = Number(e.target.value); state.dirty = true; };
}
// Tilt pad: drag the knob to tilt the paper (up to upright) toward where it points; double-tap to level it.
// The app will read the phone's gravity sensor instead; this is the same physics on a thumb.
function hookTiltPad() {
  const pad = ui('tiltpad'), knob = ui('tiltknob');
  if (!pad) return;
  const R = () => pad.clientWidth / 2;
  const set = (dx, dy) => {
    const r = R(), l = Math.hypot(dx, dy), k = l > r ? r / l : 1;
    dx *= k; dy *= k;
    knob.style.transform = `translate(${dx}px, ${dy}px)`;
    const amt = Math.hypot(dx, dy) / r;
    // Fine near level, upright at the rim: half way out is about 32°, three quarters about 58°.
    const deg = 90 * Math.pow(amt, 1.5);
    const dir = amt > 0.02 ? [dx / (amt * r), -dy / (amt * r)] : [0, 0];   // screen y down → doc y up
    engine.slope = tiltSlope(deg, dir);
    ui('tiltread').textContent = deg < 0.5 ? 'level' : `tilted ${deg.toFixed(0)}°`;
  };
  let id = null, last = 0;
  pad.addEventListener('pointerdown', e => {
    e.stopPropagation(); pad.setPointerCapture(e.pointerId); id = e.pointerId;
    if (e.timeStamp - last < 300) set(0, 0);
    last = e.timeStamp;
  });
  pad.addEventListener('pointermove', e => {
    if (e.pointerId !== id) return;
    const b = pad.getBoundingClientRect();
    set(e.clientX - b.left - b.width / 2, e.clientY - b.top - b.height / 2);
  });
  pad.addEventListener('pointerup', () => { id = null; });
}

// Phone tilt (owner, 2026-10-06: "nudge and tilt your phone ... pool and roll"): the gravity sensor, taken
// relative to how the phone was held when it was switched on, so drawing at a comfortable angle stays level
// and only tipping the phone runs the water. The app reads the same sensor natively.
function hookPhoneTilt() {
  const btn = ui('phonetilt');
  if (!btn) return;
  let on = false, ref = null, got = false, smooth = [0, 0];
  const onMotion = e => {
    const g = e.accelerationIncludingGravity;
    if (!g || g.x === null || g.y === null) return;
    got = true;
    // The sensor reads the push that holds the phone up, so gravity in the screen's plane is its negative;
    // turned by the screen's rotation into the page's axes (page y points up).
    const a = ((screen.orientation && screen.orientation.angle) ?? window.orientation ?? 0) * Math.PI / 180;
    const dx = -g.x / 9.81, dy = -g.y / 9.81;
    const v = [dx * Math.cos(a) - dy * Math.sin(a), dx * Math.sin(a) + dy * Math.cos(a)];
    if (!ref) ref = v;
    smooth = [smooth[0] + 0.25 * (v[0] - ref[0] - smooth[0]), smooth[1] + 0.25 * (v[1] - ref[1] - smooth[1])];
    const m = Math.hypot(smooth[0], smooth[1]);
    // A steady hand is level (a small dead zone), and the slope never exceeds upright.
    const k = m > 0.04 ? Math.min(1, m - 0.04) / m : 0;
    engine.slope = [smooth[0] * k, smooth[1] * k];
    const deg = Math.asin(Math.min(1, m * k)) * 180 / Math.PI;
    ui('tiltread').textContent = k === 0 ? 'phone: level' : `phone ${deg.toFixed(0)}°`;
  };
  btn.onclick = async () => {
    on = !on;
    if (on && typeof DeviceMotionEvent !== 'undefined' && typeof DeviceMotionEvent.requestPermission === 'function') {
      try { on = (await DeviceMotionEvent.requestPermission()) === 'granted'; } catch { on = false; }
    }
    if (on) {
      ref = null; got = false; smooth = [0, 0];
      window.addEventListener('devicemotion', onMotion);
      setTimeout(() => { if (on && !got) ui('tiltread').textContent = 'no tilt sensor here'; }, 1500);
    } else {
      window.removeEventListener('devicemotion', onMotion);
      engine.slope = [0, 0];
      ui('tiltread').textContent = 'level';
    }
    btn.textContent = 'Phone tilt: ' + (on ? 'on' : 'off');
    ui('tiltpad').hidden = on;
  };
}

// The paper's own colour: the base of the look that uses this surface (the paper session's catalogue).
function paperColorFor(surfaceId) {
  const look = (catalogue.looks || []).find(l => l.defaultSurface === surfaceId && l.base);
  if (!look) return [0.96, 0.95, 0.93];
  const h = look.base;
  return [1, 3, 5].map(i => parseInt(h.slice(i, i + 2), 16) / 255);
}

function bindRange(id, fn) { const el = ui(id); if (!el) return; fn(Number(el.value)); el.oninput = () => fn(Number(el.value)); }

function fitCanvas(fixed) {
  const dpr = devicePixelRatio;
  if (fixed) {
    canvas.width = fixed[0]; canvas.height = fixed[1];
    canvas.style.width = fixed[0] / dpr + 'px'; canvas.style.height = fixed[1] / dpr + 'px';
    return;
  }
  canvas.width = Math.round(innerWidth * dpr); canvas.height = Math.round(innerHeight * dpr);
  canvas.style.width = innerWidth + 'px'; canvas.style.height = innerHeight + 'px';
}
function norm(v) { const l = Math.hypot(...v); return v.map(x => x / l); }

main().catch(err => { document.body.insertAdjacentHTML('beforeend', `<pre class="err">${err.stack || err}</pre>`); window.__error = String(err.stack || err); window.__done = true; });
