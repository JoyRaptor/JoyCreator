// main.js — the media lab page: draw with a pen (pressure + tilt), or run a fixed test sheet.
import { MediaEngine } from './engine.js';
import { loadCatalogue, loadSurface, DOC_PX_PER_MM } from './paper.js';
import { STICKS, DryStroke, stickMaterial } from './stick.js';
import { SHEETS } from './tests.js';
import { WET, WET_BRUSHES, WetStroke, paintFromColor, tiltUniform } from './wet.js';
import { SplineFeeder } from './spline.js';
import { PASTE_BRUSHES, PasteStroke, opaquePaint } from './paste.js';
import { VectorDoc } from './vector.js';

const qs = new URLSearchParams(location.search);
const SHADERS = qs.get('shaders') || '../../shaders/';
const ASSETS = qs.get('assets') || '../../assets/paper/';

const canvas = document.getElementById('c');
const gl = canvas.getContext('webgl2', { antialias: false, preserveDrawingBuffer: true });
const ui = id => document.getElementById(id);

const state = {
  tool: qs.get('tool') || 'Proto',
  paperId: qs.get('paper') || 'lab_drawing',
  tiltReach: Number(qs.get('reach') || 68),    // device tilt (°) that counts as "lying on its side" (owner's S Pen tops out at 71°)
  mousePressure: 0.5,
  color: [0.22, 0.38, 0.75],
  seed: 100,
  mouseTilt: 0,
  view: { pan: [0, 0], zoom: 1 },
  look: { impasto: 1.4, pxPerMm: DOC_PX_PER_MM, paperColor: [0.96, 0.95, 0.93], lamp: norm([-0.55, 0.55, 0.62]), relief: Number(qs.get('relief') || 0.3), sheen: 0.06, capMm: 0.004, mode: 0 },
  stroke: null,
  dirty: true,
};

let engine, paper, catalogue;

async function main() {
  const test = qs.get('test');
  const size = test && SHEETS[test] ? SHEETS[test].size : [1080, 1920];
  if (test && SHEETS[test] && SHEETS[test].paper && !qs.get('paper')) state.paperId = SHEETS[test].paper;
  fitCanvas(test ? size : null);
  catalogue = await loadCatalogue([['papers/', 'lab_catalogue.json'], [ASSETS, 'catalogue.json']]);
  paper = await loadSurface(gl, catalogue, state.paperId);
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
    if (st.tiltDeg !== undefined) { engine.tilt = tiltUniform(st.tiltDeg, st.tiltDir, DOC_PX_PER_MM); continue; }
    if (st.wait !== undefined) {   // let the water run with no brush on the paper
      const frames = Math.round(st.wait / (WET.dt * WET.substeps));
      for (let f = 0; f < frames && engine.wetActive; f++) engine.wetFrame(null, paper, DOC_PX_PER_MM);
      continue;
    }
    for (const s of st.samples) { s.x += offset[0]; s.y += offset[1]; }
    if (st.kind === 'paste') {
      const brush = PASTE_BRUSHES[st.tool];
      if (!st.dirty) engine.reloadBrush(opaquePaint(st.color), brush, seed, st.belly ? opaquePaint(st.belly) : null);
      const ps = new PasteStroke(brush, DOC_PX_PER_MM);
      const sd = seed++;
      for (const smp of st.samples) { ps.add(smp); for (const step of ps.take()) engine.pasteStep(step, brush, paper, DOC_PX_PER_MM, sd); }
      continue;
    }
    if (st.kind === 'wet') {
      const ws = new WetStroke(WET_BRUSHES[st.tool], paintFromColor(st.color), paper, DOC_PX_PER_MM, seed++);
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
function startStroke(eng, meta) {
  if (meta.kind === 'paste') {
    const brush = PASTE_BRUSHES[meta.tool];
    if (!meta.dirty) eng.reloadBrush(opaquePaint(meta.color), brush, meta.seed, meta.belly ? opaquePaint(meta.belly) : null);
    const ds = new PasteStroke(brush, DOC_PX_PER_MM, eng.scale);
    return { add: s => ds.add(s), flush() { for (const st of ds.take()) eng.pasteStep(st, brush, paper, DOC_PX_PER_MM, meta.seed); }, finish() { this.flush(); } };
  }
  if (meta.kind === 'wet') {
    const ds = new WetStroke(WET_BRUSHES[meta.tool], paintFromColor(meta.color), paper, DOC_PX_PER_MM, meta.seed);
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
  canvas.addEventListener('pointerdown', e => {
    canvas.setPointerCapture(e.pointerId);
    if (e.pointerType === 'touch') { touches.set(e.pointerId, [e.clientX, e.clientY]); return; }
    if (e.button === 1) { touches.set(e.pointerId, [e.clientX, e.clientY]); return; }
    if (!state.vector) engine.beginStroke();
    const kind = PASTE_BRUSHES[state.tool] ? 'paste' : WET_BRUSHES[state.tool] ? 'wet' : 'dry';
    const meta = { kind, tool: state.tool, color: [...state.color], belly: state.belly ? [...state.belly] : null, seed: ++state.seed,
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
    // Input calibration, not physics: the device's reach maps to 'lying on its side'; a mild curve lets
    // the side come in before the very end of the pen's range.
    tilt = Math.min(Math.PI / 2 * 0.99, Math.pow(Math.min(1, tilt / reach), 0.8) * (Math.PI / 2) * 0.92);
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
  for (const k of Object.keys(STICKS)) dryGroup.append(new Option(k, k, false, k === state.tool));
  const wetGroup = document.createElement('optgroup'); wetGroup.label = 'Watercolour';
  for (const k of Object.keys(WET_BRUSHES)) wetGroup.append(new Option(k, k, false, k === state.tool));
  const pasteGroup = document.createElement('optgroup'); pasteGroup.label = 'Oil';
  for (const k of Object.keys(PASTE_BRUSHES)) pasteGroup.append(new Option(k, k, false, k === state.tool));
  toolSel.append(dryGroup, wetGroup, pasteGroup);
  const dirty = ui('dirtybrush');
  if (dirty) dirty.onchange = () => { state.dirtyBrush = dirty.checked; };
  toolSel.onchange = () => { state.tool = toolSel.value; };
  const col = ui('color');
  const setCol = () => { const h = col.value; state.color = [1, 3, 5].map(i => parseInt(h.slice(i, i + 2), 16) / 255); };
  setCol(); col.oninput = setCol;
  const bellyCol = ui('belly'), twoTone = ui('twotone');
  const setBelly = () => { state.belly = twoTone && twoTone.checked ? [1, 3, 5].map(i => parseInt(bellyCol.value.slice(i, i + 2), 16) / 255) : null; };
  if (bellyCol) { bellyCol.oninput = setBelly; twoTone.onchange = setBelly; setBelly(); }
  ui('dry').onclick = () => { engine.dryNow(paper); state.dirty = true; };
  hookTiltPad();
  const paperSel = ui('paper');
  for (const s of catalogue.surfaces) paperSel.add(new Option(s.name, s.id, false, s.id === paper.id));
  paperSel.onchange = async () => { paper = await loadSurface(gl, catalogue, paperSel.value); state.dirty = true; };
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
// Tilt pad: drag the knob to tilt the paper (up to 30°) toward where it points; double-tap to level it.
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
    const deg = 30 * amt;
    const dir = amt > 0.02 ? [dx / (amt * r), -dy / (amt * r)] : [0, 0];   // screen y down → doc y up
    engine.tilt = tiltUniform(deg, dir, DOC_PX_PER_MM);
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
