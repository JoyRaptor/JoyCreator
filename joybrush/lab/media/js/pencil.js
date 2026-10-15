// pencil.js — the pencil tuner (owner, 2026-10-15: "every knob and dial … a thing I can copy at the bottom … a test I can
// toggle with a slew of test strokes that live update … the pressure tilt boxes like in the app with bezier handles").
//
// It drives the app's own pencil engine (stick.js + the shared shaders, the same numbers core Stick.kt carries), so a
// block copied from here becomes a Stick, Zones, Press and material in the app with nothing re-tuned. Test strokes are
// fixed pen readings sent through the same pipeline a real pen is: the pressure and tilt curves, then the tilt reach.
import { MediaEngine } from './engine.js';
import { loadCatalogue, loadSurface, DOC_PX_PER_MM } from './paper.js';
import { STICKS, DryStroke, stickMaterial, PRESS, ZONES, SHAPE, curveEval, LINEAR_CURVE } from './stick.js';
import { SplineFeeder } from './spline.js';

const qs = new URLSearchParams(location.search);
const SHADERS = qs.get('shaders') || '../../shaders/';
const ASSETS = qs.get('assets') || '../../assets/paper/';
const MM = DOC_PX_PER_MM;
const SHEET = [1600, 1900];             // doc px: 80 × 95 mm
const STORE = 'jb.pencilTuner.v1';
const LINEAR = LINEAR_CURVE;
const STICK_CURVES = ['sizeCurve', 'darkCurve', 'sideCurve', 'pointCurve'];
// Whole tuner settings saved as a starting point (the owner's own, pasted back).
const PRESETS = {
  'Proto 3': { base: 'Proto 2', settings: {
    stick: { soft: 0.53 },
    press: { depth: 1.5, sideEase: 0.46 },
    mat: { capMm: 0.006, plateau: 2.3, crushStart: 0.58, crushMax: 0.45, clump: 0.46, pileLo: 0.11, pileHi: 0.44, dirStrength: 1.31,
           abrasion: 1.39, leadSoft: 1.98, dustRate: 2, smear: 1.5, smearMm: 1.6, crushRate: 0.56, sheen: 1.12 },
    response: { pressure: [0.754175, 0.240418, 0.551931, 1], tilt: [0.627869, 0.222495, 0.555964, 0.950523] },
    pen: { tiltReach: 61 },
    paper: 'cardboard',
  } },
};
// Android reports the S Pen's lean turned half a turn (the owner, 2026-10-15: "x and y of the tilt are flipped …
// a graphite spray airbrush"). A desktop pen tablet reports it the right way round.
const ANDROID = /Android/i.test(navigator.userAgent);

const canvas = document.getElementById('c');
const gl = canvas.getContext('webgl2', { antialias: false, preserveDrawingBuffer: true });
const labels = document.getElementById('labels');
const lctx = labels.getContext('2d');
const ui = id => document.getElementById(id);

// What the engine starts with, before anything is tuned: the "base" a reset goes back to.
const PRESS0 = { ...PRESS }, ZONES0 = { ...ZONES }, SHAPE0 = { ...SHAPE };
// Material numbers that do not depend on the grade (stickMaterial's constants), tuned as absolutes.
const MAT_ABS = ['capMm', 'transferExp', 'plateau', 'crushStart', 'crushMax', 'clump', 'pileLo', 'pileHi', 'dirStrength', 'conform'];
// Material numbers the grade's softness derives: tuned as multipliers on that, so softness keeps meaning something.
const MAT_MUL = ['abrasion', 'leadSoft', 'dustRate', 'smear', 'smearMm', 'crushRate', 'sheen'];

function defaultsFor(base) {
  if (PRESETS[base]) { const p = PRESETS[base]; const d = defaultsFor(p.base); d.base = base; return merge(d, structuredClone(p.settings)); }
  const st = STICKS[base];
  const m = stickMaterial({ ...st, build: 1 });
  const mat = {};
  for (const k of MAT_ABS) mat[k] = k === 'pileLo' ? m.pileRange[0] : k === 'pileHi' ? m.pileRange[1] : m[k];
  for (const k of MAT_MUL) mat[k] = 1;
  return {
    base, size: 1,
    stick: { tipR: st.tipR, tipMax: st.tipMax, side1: st.side1, side2: st.side2, face: st.face, soft: st.soft, rInf: st.rInf,
             blend: st.blend ?? 0, build: st.build ?? 1,
             sizeCurve: [...(st.sizeCurve || LINEAR)], darkCurve: [...(st.darkCurve || LINEAR)],
             sideCurve: [...(st.sideCurve || LINEAR)], pointCurve: [...(st.pointCurve || LINEAR)] },
    shape: { ...SHAPE0 },
    zones: { ...ZONES0 },
    press: { depth: PRESS0.depth, gamma: PRESS0.gamma, sideEase: PRESS0.sideEase, toothStiffness: PRESS0.toothStiffness, padSoft: PRESS0.padSoft },
    mat,
    response: { pressure: [...LINEAR], tilt: [...LINEAR] },
    pen: { tiltReach: 68, mousePressure: 0.5, mouseTilt: 0, leanFlip: ANDROID ? 1 : 0 },
    paper: 'drawing_tooth',
  };
}

// ---- every knob: [path, label, what it does, min, max, step, unit] ----
const R_LIGHT = 0.3, R_DARK = 0.002;   // as the app's Darkness slider (BrushKnobs): rInf runs the other way
const GROUPS = [
  ['Size', true, [
    ['size', 'Overall size', 'Scales every length of the pencil at once, like the app’s size slider.', 0.3, 3, 0.01, '×'],
  ]],
  ['Point (pen upright)', true, [
    ['stick.tipR', 'Point at a light touch', 'Radius of the point at the lightest pressure.', 0.03, 1, 0.005, 'mm'],
    ['stick.tipMax', 'Point pressed hard', 'Radius of the point at full pressure.', 0.05, 3, 0.005, 'mm'],
    ['shape.bell', 'Point softness', 'Higher: a tight, crisp point. Lower: a soft, fuzzy point (blended footprint only).', 0.5, 6, 0.05, ''],
    ['shape.bellReach', 'Point fringe', 'How far the soft point’s fringe reaches past its radius (blended footprint only).', 0, 2, 0.02, '×R'],
  ]],
  ['Tilted: side of the lead', true, [
    ['stick.side1', 'Worn face length', 'How long the worn face is when the pencil is tilted halfway.', 0.3, 15, 0.05, 'mm'],
    ['stick.side2', 'Whole side length', 'How far the side reaches when the pencil lies nearly flat.', 0.5, 30, 0.1, 'mm'],
    ['stick.face', 'Side width', 'Half-width of the side of the lead that touches.', 0.1, 4, 0.02, 'mm'],
    ['stick.blend', 'Edge blend', '0: a crisp point on a separate shade (the old look). 1: one soft mark from the dark edge into the shade.', 0, 1, 0.01, ''],
    ['shape.tail', 'Dark edge fade length', 'How far the dark edge carries into the shading, in point radii. Shorter: thinner dark core.', 0.3, 8, 0.05, '×R'],
    ['shape.tipYield', 'Edge darkness when tilted', 'How much the point gives way to the side when tilted. Higher: a lighter edge.', 0, 1, 0.01, ''],
    ['shape.acrossSoft', 'Side edge softness', 'How softly the shading fades out at its sides. Higher: softer, narrower.', 0.5, 4, 0.05, ''],
    ['zones.grad1', 'Worn face fade', 'How quickly the worn face fades from the tip toward the barrel. Higher: darker near the tip.', 0.3, 4, 0.05, ''],
    ['zones.grad2', 'Whole side fade', 'How quickly the whole side fades toward the barrel.', 0.3, 4, 0.05, ''],
  ]],
  ['When tilt kicks in', false, [
    ['zones.side1From', 'Worn face starts at', 'How tilted (0 upright … 1 flat) before the worn face starts to touch.', 0, 1, 0.01, ''],
    ['zones.side1Full', 'Worn face full at', 'How tilted before the worn face is fully down.', 0, 1, 0.01, ''],
    ['zones.side2From', 'Whole side starts at', 'How tilted before the whole side starts to touch.', 0, 1, 0.01, ''],
    ['zones.side2Full', 'Whole side full at', 'How tilted before the whole side is fully down.', 0, 1, 0.01, ''],
    ['zones.evenFrom', 'Even shading from', 'Near flat and light, the side lays down evenly from this tilt on.', 0, 1, 0.01, ''],
  ]],
  ['Pressure', true, [
    ['press.depth', 'Press depth', 'How deep full pressure pushes into the paper’s grain. Higher: fills the grain, darker.', 0.3, 4, 0.02, ''],
    ['press.gamma', 'Pressure ramp', 'Higher: a longer gentle light end, so light pressure stays light.', 0.5, 4, 0.02, ''],
    ['press.sideEase', 'Side pressure spread', 'The same hand force spread along the side presses less. Higher: lighter tilted shading.', 0, 2, 0.01, ''],
    ['press.toothStiffness', 'Paper tooth stiffness', 'Higher: the paper’s tooth resists, so light strokes stay grainy.', 1, 30, 0.1, ''],
    ['press.padSoft', 'Paper cushion', 'How soft the pad under the sheet is. Higher: the paper gives, marks widen.', 0.2, 4, 0.02, ''],
  ]],
  ['Graphite', true, [
    ['stick.rInf', 'Darkness', 'How dark the lead gets where it is laid on thick.', 0, 1, 0.005, '%'],
    ['stick.build', 'Build-up speed', 'How quickly the lead lays graphite. Lower: more passes to reach black.', 0.1, 2, 0.01, '×'],
    ['stick.soft', 'Softness (grade)', 'Soft leads lay more and crumble into the paper; hard leads stay pale and clean. Moves several numbers below.', 0, 1, 0.01, ''],
    ['mat.abrasion', 'Shedding', 'How readily the lead sheds onto the paper (× what softness gives).', 0.1, 3, 0.01, '×'],
    ['mat.dustRate', 'Light-touch dust', 'How much loose dust a light touch leaves (× softness).', 0, 4, 0.02, '×'],
    ['mat.pileLo', 'Pressing starts piling at', 'Pressure where dark, clumped graphite starts to take over from soft dust.', 0, 1, 0.01, ''],
    ['mat.pileHi', 'Piling full at', 'Pressure where the dark piling has fully taken over.', 0, 1, 0.01, ''],
    ['mat.transferExp', 'Pressure → darkness steepness', 'Higher: light pressure lays much less than firm pressure.', 1, 4, 0.02, ''],
    ['mat.clump', 'Clumping', 'Pressed graphite piles on the paper’s little hills.', 0, 1, 0.01, ''],
    ['mat.dirStrength', 'Direction catch', 'Tooth faces that meet the stroke’s direction catch more graphite.', 0, 2, 0.01, ''],
    ['mat.leadSoft', 'Grain softness', 'A soft lead gives where it meets the tooth: softer grain (× softness).', 0.1, 3, 0.01, '×'],
    ['mat.conform', 'Paper wraps the lead', 'The sheet bends onto a broad stick, so only fine tooth decides contact.', 0, 1, 0.01, ''],
    ['mat.plateau', 'Tooth shape', 'Paper tooth as plateaus and pits. Higher: flatter tops, narrower pits.', 0.5, 5, 0.05, ''],
    ['mat.capMm', 'Graphite the tooth holds', 'How much graphite a patch of paper can hold before it is full.', 0.001, 0.02, 0.0005, 'mm'],
    ['mat.smear', 'Smear when going over', 'Going over graphite drags it along and softens it (× softness).', 0, 3, 0.02, '×'],
    ['mat.smearMm', 'Smear reach', 'How far the smear drags (× softness).', 0.1, 3, 0.02, '×'],
    ['mat.crushRate', 'Burnish', 'How fast hard pressure flattens the tooth (× softness).', 0, 3, 0.02, '×'],
    ['mat.crushStart', 'Burnish starts at', 'How deep the lead must press before the tooth flattens.', 0, 1.5, 0.01, ''],
    ['mat.crushMax', 'Burnish most', 'How flat the tooth can be crushed.', 0, 1, 0.01, ''],
    ['mat.sheen', 'Graphite shine', 'The silvery shine of heavy graphite (× softness).', 0, 3, 0.02, '×'],
  ]],
];
const box = (id, title, label, hint) =>
  `<div class="curve"><canvas id="curve${id}" title="${hint}"></canvas><div class="cap"><span>${label}</span><span id="curve${id}read"></span></div></div>`;
const CURVE_BOXES = {
  'Point (pen upright)': `<p class="note">Upright, how the point grows and how dark it gets as you press, each on its own curve. A sharp point cuts into the tooth and darkens early while it stays small: lift the darkness curve and keep the size curve low. Double-tap a box to make it straight.</p>
    <div class="curves">${box('S', 'size', 'Size with pressure', 'Across: pressure. Up: how far the point has grown. Drag the handles; double-tap to reset')}${box('D', 'dark', 'Darkness with pressure', 'Across: pressure. Up: how hard the point presses into the tooth. Drag the handles; double-tap to reset')}</div>`,
  'When tilt kicks in': `<p class="note">As you tilt, the soft side comes in and the hard point gives way. Bend the point curve below the line and the point holds on longer while the side comes in: more overlap, a longer ramp into the tilt.</p>
    <div class="curves">${box('I', 'side', 'Side comes in', 'Across: tilt. Up: how far the side has come down. Drag the handles; double-tap to reset')}${box('O', 'point', 'Point gives way', 'Across: tilt. Up: how far the hard point has given way. Drag the handles; double-tap to reset')}</div>`,
};

const PEN = [
  ['pen.tiltReach', 'Pen tilt that counts as flat', 'The S Pen tops out near 70°: that much tilt counts as lying on its side.', 30, 90, 1, '°'],
  ['pen.mousePressure', 'Mouse pressure', 'For a mouse or a pen without pressure.', 0.01, 1, 0.01, ''],
  ['pen.mouseTilt', 'Mouse / no-tilt pen tilt', 'For a mouse or a pen that reports no tilt.', 0, 90, 1, '°'],
];

const get = (o, path) => path.split('.').reduce((a, k) => a[k], o);
function set(o, path, v) { const ks = path.split('.'); const last = ks.pop(); ks.reduce((a, k) => a[k], o)[last] = v; }

// The slider shows Darkness 0..1; the stick keeps rInf.
const toSlider = (path, v) => path === 'stick.rInf' ? (R_LIGHT - v) / (R_LIGHT - R_DARK) : v;
const fromSlider = (path, v) => path === 'stick.rInf' ? R_LIGHT - (R_LIGHT - R_DARK) * v : v;
function fmt(path, v, unit) {
  if (path === 'stick.rInf') return Math.round(toSlider(path, v) * 100) + '%';
  if (unit === '°') return Math.round(v) + '°';
  const a = Math.abs(v);
  const s = a >= 100 ? v.toFixed(0) : a >= 10 ? v.toFixed(1) : a >= 1 ? v.toFixed(2) : v.toFixed(3);
  return s + (unit ? (unit === '%' || unit === '°' ? unit : ' ' + unit) : '');
}

// ---- state ----
let cfg, baseCfg, engine, paper, catalogue;
const state = { tests: true, view: { pan: [0, 0], zoom: 1 }, stroke: null, dirty: true, raw: null, rebuildMs: 0, rebuildTimer: 0,
  look: { impasto: 1.4, pxPerMm: MM, paperColor: [0.96, 0.95, 0.93], lamp: norm([-0.55, 0.55, 0.62]), relief: 0.3, sheen: 0.06, capMm: 0.004, mode: 0 },
  penTiltSeen: false };
const rows = new Map();   // path → { row, input, val }

function norm(v) { const l = Math.hypot(...v); return v.map(x => x / l); }

// ---- the settings the engine draws with ----
function applyGlobals() {
  Object.assign(PRESS, cfg.press);
  Object.assign(ZONES, cfg.zones);
  Object.assign(SHAPE, cfg.shape);
}
function liveStick() {
  const k = cfg.size, s = cfg.stick;
  return { ...s, tipR: s.tipR * k, tipMax: s.tipMax * k, side1: s.side1 * k, side2: s.side2 * k, face: s.face * k };
}
function liveMaterial(stick) {
  const m = stickMaterial(stick);
  for (const key of MAT_MUL) m[key] *= cfg.mat[key];
  for (const key of MAT_ABS) if (key !== 'pileLo' && key !== 'pileHi') m[key] = cfg.mat[key];
  m.pileRange = [cfg.mat.pileLo, cfg.mat.pileHi];
  return m;
}

// ---- the pen pipeline: what a real pen goes through, and the test strokes too ----
/** A pen reading (pressure 0..1, tilt in degrees from upright) to what the pencil feels: curves, then the tilt reach. */
function felt(p, tiltDeg) {
  const pp = curveEval(cfg.response.pressure, p);
  const tc = curveEval(cfg.response.tilt, Math.min(1, tiltDeg / 90)) * 90;    // the app's curve: 0 upright … 1 flat
  const reach = Math.max(20, cfg.pen.tiltReach);
  return { p: pp, tilt: Math.min(1, tc / reach) * (Math.PI / 2) };
}

// ---- test strokes: pen readings, labelled, laid out top to bottom (doc y up) ----
const AZ = -60 * Math.PI / 180;   // a right-handed lean
function testStrokes() {
  const reach = cfg.pen.tiltReach;
  // Each row's band (doc px tall): tilted rows leave room below for the side, which leans toward the lower right.
  const BANDS = [150, 190, 330, 330, 380, 360, 200];
  const tops = []; let y = SHEET[1] - 30;
  for (const b of BANDS) { tops.push(y); y -= b; }
  // A row's marks start 70 px under its label; rowY is that line.
  const rowY = i => tops[i] - 70;
  const out = [];
  const add = (label, pts, row) => out.push({ label, pts, labelY: label ? tops[row] - 22 : 0 });
  const line = (x0, y0, x1, y1, f, n = 160) => Array.from({ length: n + 1 }, (_, i) => { const u = i / n; return { x: x0 + (x1 - x0) * u, y: y0 + (y1 - y0) * u, ...f(u) }; });
  const zig = (x0, y0, w, h, n, f) => {
    const pts = [];
    for (let i = 0; i <= n; i++) {
      const xa = x0 + (w * i) / n, ya = y0 + (i % 2 ? h : 0);
      const prev = pts.length ? pts[pts.length - 1] : null;
      if (prev) for (let k = 1; k <= 24; k++) { const u = k / 24; pts.push({ x: prev.x + (xa - prev.x) * u, y: prev.y + (ya - prev.y) * u, ...f((i - 1 + u) / n) }); }
      else pts.push({ x: xa, y: ya, ...f(0) });
    }
    return pts;
  };
  // 1. pressure ramp, upright
  add('1  Pressure 0 → 100%, pen upright', line(80, rowY(0), 1520, rowY(0), u => ({ p: 0.02 + 0.98 * u, tilt: 6 })), 0);
  // 2. pressure ladder
  [0.1, 0.25, 0.4, 0.6, 0.8, 1.0].forEach((p, i) => add(i ? null : '2  Pressure 10 · 25 · 40 · 60 · 80 · 100%, upright',
    line(140 + i * 240, rowY(1) + 10, 230 + i * 240, rowY(1) - 80, () => ({ p, tilt: 6 }), 60), 1));
  // 3. tilt sweep
  add('3  Tilt: upright → flat → upright, half pressure', line(80, rowY(2), 1520, rowY(2), u => ({ p: 0.5, tilt: reach * Math.sin(Math.PI * u) })), 2);
  // 4. fixed tilts
  [0.35, 0.55, 0.75, 1].forEach((f, i) => add(i ? null : `4  Tilt ${[0.35, 0.55, 0.75].map(v => Math.round(v * reach) + '°').join(' · ')} · flat, half pressure`,
    line(100 + i * 370, rowY(3) - 10, 400 + i * 370, rowY(3) + 10, () => ({ p: 0.5, tilt: f * reach }), 90), 3));
  // 5. shading at full tilt
  add('5  Shading at full tilt: light 30% · firm 80%', zig(90, rowY(4) - 110, 620, 110, 14, () => ({ p: 0.3, tilt: reach })), 4);
  add(null, zig(860, rowY(4) - 110, 620, 110, 14, () => ({ p: 0.8, tilt: reach })), 4);
  // 6. build-up: light passes over one patch
  [1, 2, 4, 8].forEach((n, i) => {
    for (let k = 0; k < n; k++) add(i || k ? null : '6  Build-up: 1 · 2 · 4 · 8 light passes (35%, mostly tilted)',
      zig(110 + i * 370, rowY(5) - 100, 280, 100, 8, () => ({ p: 0.35, tilt: 0.85 * reach })), 5);
  });
  // 7. lines at a writing tilt, light to firm (drawing, not shading)
  [0.2, 0.45, 0.7, 0.95].forEach((p, i) => add(i ? null : `7  Lines at a writing tilt (${Math.min(35, reach)}°): 20 · 45 · 70 · 95%`,
    line(100 + i * 370, rowY(6) - 20, 420 + i * 370, rowY(6) + 20, () => ({ p, tilt: Math.min(35, reach) }), 90), 6));
  return out;
}

function runTests() {
  const t0 = performance.now();
  applyGlobals();
  engine.clearAll();
  const stick = liveStick(), mat = liveMaterial(stick);
  let ts = 0;
  for (const st of testStrokes()) {
    const ds = new DryStroke(stick, paper, MM);
    const feeder = new SplineFeeder(s => ds.add(s));
    st.pts.forEach((r, i) => {
      const f = felt(r.p, r.tilt);
      feeder.add({ x: r.x, y: r.y, p: f.p, tilt: f.tilt, az: AZ, t: (ts += 4) });
      if (i === st.pts.length - 1) feeder.finish();
      if (i % 4 === 3 || i === st.pts.length - 1) engine.dryFrame(ds.take(), paper, mat, MM);
    });
  }
  state.rebuildMs = performance.now() - t0;
  state.dirty = true;
  readout();
}

function scheduleRebuild() {
  save();
  writeOut();
  if (!state.tests) return;
  clearTimeout(state.rebuildTimer);
  state.rebuildTimer = setTimeout(runTests, 60);
}

// ---- drawing ----
function draw() {
  engine.render(state.view, paper, state.look, canvas.width, canvas.height);
  drawLabels();
}
function drawLabels() {
  lctx.clearRect(0, 0, labels.width, labels.height);
  if (!state.tests) return;
  const v = state.view, dpr = devicePixelRatio;
  lctx.font = `${12 * dpr}px system-ui, sans-serif`;
  lctx.fillStyle = 'rgba(40,40,46,0.85)';
  for (const st of testStrokes()) {
    if (!st.label) continue;
    const sx = 80 * v.zoom + v.pan[0], sy = labels.height - (st.labelY * v.zoom + v.pan[1]);
    lctx.fillText(st.label, sx, sy);
  }
}
function loop() {
  if (state.stroke) { state.stroke.flush(); state.dirty = true; }
  if (state.dirty) { draw(); state.dirty = false; }
  requestAnimationFrame(loop);
}

function fitCanvas() {
  const dpr = devicePixelRatio;
  for (const c of [canvas, labels]) {
    c.width = Math.round(innerWidth * dpr); c.height = Math.round(innerHeight * dpr);
    c.style.width = innerWidth + 'px'; c.style.height = innerHeight + 'px';
  }
}
/** The screen area (device px, from the top-left) the sheet may use: beside the settings panel, or above it on a phone. */
function freeArea() {
  const dpr = devicePixelRatio, bar = ui('bar').getBoundingClientRect().bottom + 6;
  let left = 8, top = bar, right = innerWidth - 8, bottom = innerHeight - 8;
  if (!document.body.classList.contains('collapsed')) {
    const r = ui('panel').getBoundingClientRect();
    if (r.top > 1) bottom = Math.min(bottom, r.top - 44);   // a sheet along the bottom (phone), with its toggle above
    else left = Math.max(left, r.right + 8);
  }
  return [left * dpr, top * dpr, right * dpr, bottom * dpr];
}
function fit() {
  const [l, t, r, b] = freeArea();
  const w = Math.max(10, r - l), h = Math.max(10, b - t);
  const z = Math.max(0.02, Math.min(w / SHEET[0], h / SHEET[1]));
  state.view.zoom = z;
  // pan is in GL px from the canvas's BOTTOM-left; the area's top t is from the top.
  state.view.pan = [l + (w - SHEET[0] * z) / 2, canvas.height - b + (h - SHEET[1] * z) / 2];
  state.dirty = true;
}
function realZoom() { const cssMm = matchMedia('(pointer: coarse)').matches ? 0.16 : 0.26; return 0.05 / (cssMm / devicePixelRatio); }
function realSize() {
  const c = [canvas.width / 2, canvas.height / 2], v = state.view, z = realZoom(), k = z / v.zoom;
  v.pan = [c[0] - (c[0] - v.pan[0]) * k, c[1] - (c[1] - v.pan[1]) * k];
  v.zoom = z;
  state.dirty = true;
}
function zoomAt(cx, cy, f) {
  const dpr = devicePixelRatio, sx = cx * dpr, sy = canvas.height - cy * dpr, v = state.view;
  const z = Math.min(40, Math.max(0.05, v.zoom * f)), k = z / v.zoom;
  v.pan = [sx - (sx - v.pan[0]) * k, sy - (sy - v.pan[1]) * k];
  v.zoom = z;
  state.dirty = true;
}

// ---- pen input (only when the test strokes are off) ----
const touches = new Map();
function hookInput() {
  canvas.addEventListener('pointerdown', e => {
    try { canvas.setPointerCapture(e.pointerId); } catch { /* draw without capture */ }
    if (e.pointerType === 'touch' || e.button === 1) { touches.set(e.pointerId, [e.clientX, e.clientY]); return; }
    if (state.tests) return;
    applyGlobals();
    engine.beginStroke();
    const stick = liveStick(), mat = liveMaterial(stick), ds = new DryStroke(stick, paper, MM);
    const feeder = new SplineFeeder(s => ds.add(s));
    state.stroke = { id: e.pointerId, feeder, flush() { const b = ds.take(); if (b.count) engine.dryFrame(b, paper, mat, MM); } };
    feed(e);
  });
  canvas.addEventListener('pointermove', e => {
    if (touches.has(e.pointerId)) { moveTouch(e); return; }
    if (!state.stroke || state.stroke.id !== e.pointerId) { hover(e); return; }
    const list = e.getCoalescedEvents ? e.getCoalescedEvents() : [e];
    for (const ev of (list.length ? list : [e])) feed(ev);
  });
  const end = e => {
    touches.delete(e.pointerId);
    if (state.stroke && state.stroke.id === e.pointerId) {
      state.stroke.feeder.finish();
      state.stroke.flush();
      engine.endStroke();
      state.stroke = null;
      state.dirty = true;
    }
  };
  canvas.addEventListener('pointerup', end);
  canvas.addEventListener('pointercancel', end);
  window.addEventListener('pointerup', end);
  window.addEventListener('pointercancel', end);
  canvas.addEventListener('wheel', e => { e.preventDefault(); zoomAt(e.clientX, e.clientY, Math.exp(-e.deltaY * 0.0015)); }, { passive: false });
  window.addEventListener('resize', () => { fitCanvas(); fit(); });
}
function moveTouch(e) {
  const prev = touches.get(e.pointerId), pts = [...touches.entries()];
  if (pts.length === 1) {
    const dpr = devicePixelRatio;
    state.view.pan[0] += (e.clientX - prev[0]) * dpr;
    state.view.pan[1] -= (e.clientY - prev[1]) * dpr;
  } else if (pts.length >= 2) {
    const other = pts.find(([id]) => id !== e.pointerId)[1];
    const d0 = Math.hypot(prev[0] - other[0], prev[1] - other[1]), d1 = Math.hypot(e.clientX - other[0], e.clientY - other[1]);
    if (d0 > 1) zoomAt((e.clientX + other[0]) / 2, (e.clientY + other[1]) / 2, d1 / d0);
  }
  touches.set(e.pointerId, [e.clientX, e.clientY]);
  state.dirty = true;
}
/** What the pen reads now: pressure and tilt from upright (degrees), and the lean (radians, doc y up). */
function penReading(e) {
  const hasTilt = (e.tiltX || 0) !== 0 || (e.tiltY || 0) !== 0 || (typeof e.altitudeAngle === 'number' && Math.abs(e.altitudeAngle - Math.PI / 2) > 1e-6);
  if (hasTilt) state.penTiltSeen = true;
  if (e.pointerType === 'pen') {
    const p = e.pressure;
    if (!state.penTiltSeen) return { p, tiltDeg: cfg.pen.mouseTilt, az: AZ, noTilt: true };
    let tilt, az;
    if (typeof e.altitudeAngle === 'number') { tilt = Math.PI / 2 - e.altitudeAngle; az = -e.azimuthAngle; }
    else {
      const tx = Math.tan((e.tiltX || 0) * Math.PI / 180), ty = Math.tan((e.tiltY || 0) * Math.PI / 180);
      tilt = Math.atan(Math.hypot(tx, ty)); az = Math.atan2(-ty, tx);
    }
    if (cfg.pen.leanFlip) az += Math.PI;
    return { p, tiltDeg: tilt * 180 / Math.PI, az };
  }
  return { p: cfg.pen.mousePressure, tiltDeg: cfg.pen.mouseTilt, az: AZ, mouse: true };
}
function feed(e) {
  const dpr = devicePixelRatio, r = canvas.getBoundingClientRect();
  const sx = (e.clientX - r.left) * dpr, sy = canvas.height - (e.clientY - r.top) * dpr;
  const x = (sx - state.view.pan[0]) / state.view.zoom, y = (sy - state.view.pan[1]) / state.view.zoom;
  const pr = penReading(e), f = felt(pr.p, pr.tiltDeg);
  state.raw = { ...pr, felt: f };
  state.stroke.feeder.add({ x, y, p: f.p, tilt: f.tilt, az: pr.az, t: e.timeStamp });
  readout();
  markCurves();
  drawCompass();
}
function hover(e) {
  if (e.pointerType !== 'pen') return;
  const pr = penReading(e);
  state.raw = { ...pr, felt: felt(pr.p, pr.tiltDeg) };
  readout();
  markCurves();
  drawCompass();
}
/** Where the tuner thinks the pen's barrel points, seen from above the screen: it should point the way the pen leans. */
function drawCompass() {
  const c = ui('compass'), dpr = devicePixelRatio, w = Math.round(c.clientWidth * dpr);
  if (!w) return;
  if (c.width !== w) { c.width = w; c.height = w; }
  const g = c.getContext('2d'), r = w / 2;
  g.clearRect(0, 0, w, w);
  g.fillStyle = 'rgba(24,24,27,0.86)'; g.beginPath(); g.arc(r, r, r - 1, 0, Math.PI * 2); g.fill();
  const raw = state.raw;
  if (!raw || raw.mouse || raw.noTilt) { g.fillStyle = '#a1a1aa'; g.beginPath(); g.arc(r, r, 3 * dpr, 0, Math.PI * 2); g.fill(); return; }
  const k = Math.min(1, raw.tiltDeg / Math.max(20, cfg.pen.tiltReach));
  const dx = Math.cos(raw.az) * (r - 5 * dpr) * k, dy = -Math.sin(raw.az) * (r - 5 * dpr) * k;   // doc y up → screen y down
  g.strokeStyle = '#22d3ee'; g.lineWidth = 3 * dpr; g.lineCap = 'round';
  g.beginPath(); g.moveTo(r, r); g.lineTo(r + dx, r + dy); g.stroke();
  g.fillStyle = '#e8e6e3'; g.beginPath(); g.arc(r, r, 3 * dpr, 0, Math.PI * 2); g.fill();
}
function readout() {
  const el = ui('readout');
  if (state.tests) { el.textContent = `test strokes redrawn in ${state.rebuildMs.toFixed(0)} ms`; return; }
  const r = state.raw;
  if (!r) { el.textContent = 'draw with your pen'; return; }
  const feltTilt = r.felt.tilt / (Math.PI / 2) * 100;
  el.textContent = r.mouse ? `mouse · pressure ${(r.felt.p * 100).toFixed(0)}% · tilt ${feltTilt.toFixed(0)}% of flat`
    : `pressure ${(r.p * 100).toFixed(0)}% → ${(r.felt.p * 100).toFixed(0)}% · tilt ${r.tiltDeg.toFixed(0)}° → ${feltTilt.toFixed(0)}% of flat` +
      (r.noTilt ? ' (this pen reports no tilt: using the tilt setting)' : '');
}

// ---- the panel ----
function makeRow(host, [path, label, hint, min, max, step, unit]) {
  const row = document.createElement('div');
  row.className = 'row';
  row.title = hint;
  const id = 'k_' + path.replace(/\./g, '_');
  row.innerHTML = `<label for="${id}">${label}</label><span class="val"></span><input id="${id}" type="range" min="${min}" max="${max}" step="${step}">`;
  const input = row.querySelector('input'), val = row.querySelector('.val');
  input.addEventListener('input', () => {
    set(cfg, path, fromSlider(path, Number(input.value)));
    showRow(path);
    if (path.startsWith('pen.')) { readout(); if (path === 'pen.tiltReach') scheduleRebuild(); else { save(); writeOut(); } return; }
    scheduleRebuild();
  });
  // Double-click a row's name to put that one setting back.
  row.querySelector('label').addEventListener('dblclick', () => { set(cfg, path, get(baseCfg, path)); showRow(path); scheduleRebuild(); });
  host.append(row);
  rows.set(path, { row, input, val, unit });
}
function showRow(path) {
  const r = rows.get(path);
  if (!r) return;
  const v = get(cfg, path);
  r.input.value = toSlider(path, v);
  r.val.textContent = fmt(path, v, r.unit);
  r.row.classList.toggle('changed', Math.abs(v - get(baseCfg, path)) > 1e-9);
}
function showAll() {
  for (const p of rows.keys()) showRow(p);
  for (const k of Object.keys(CURVES)) drawCurve(k);
  ui('paper').value = cfg.paper; ui('base').value = cfg.base;
  if (ui('leanflip')) ui('leanflip').checked = !!cfg.pen.leanFlip;
}

function buildPanel() {
  const groups = ui('groups');
  for (const [title, open, knobs] of GROUPS) {
    const d = document.createElement('details');
    d.open = open;
    d.innerHTML = `<summary>${title}</summary>`;
    for (const k of knobs) makeRow(d, k);
    if (CURVE_BOXES[title]) d.insertAdjacentHTML('beforeend', CURVE_BOXES[title]);
    groups.append(d);
  }
  for (const k of PEN) makeRow(ui('pengroup'), k);
  const base = ui('base');
  for (const k of Object.keys(PRESETS)) base.add(new Option(k + ' (your settings)', k));
  for (const k of Object.keys(STICKS)) base.add(new Option(k, k));
  base.onchange = () => {
    const keepPaper = cfg.paper;
    baseCfg = defaultsFor(base.value);
    cfg = structuredClone(baseCfg);
    cfg.paper = keepPaper;
    showAll();
    scheduleRebuild();
  };
  const paperSel = ui('paper');
  for (const s of catalogue.surfaces) paperSel.add(new Option(s.name, s.id));
  paperSel.onchange = async () => { cfg.paper = paperSel.value; await usePaper(); scheduleRebuild(); };
  ui('toggle').onclick = () => {
    document.body.classList.toggle('collapsed');
    const phone = matchMedia('(max-width: 700px)').matches, shut = document.body.classList.contains('collapsed');
    ui('toggle').textContent = phone ? (shut ? '▲ Settings' : '▼ Settings') : (shut ? '▶ Settings' : '◀ Settings');
    fit();
  };
  ui('tests').onclick = () => {
    state.tests = !state.tests;
    const b = ui('tests');
    b.classList.toggle('on', state.tests);
    b.textContent = 'Test strokes: ' + (state.tests ? 'on' : 'off');
    ui('undo').hidden = ui('clear').hidden = state.tests;
    engine.clearAll();
    if (state.tests) runTests(); else { state.raw = null; readout(); state.dirty = true; }
  };
  ui('clear').onclick = () => { engine.clearAll(); state.dirty = true; };
  ui('undo').onclick = () => { if (engine.undo()) state.dirty = true; };
  ui('fit').onclick = fit;
  ui('real').onclick = realSize;
  ui('copy').onclick = async () => {
    const t = ui('out');
    try { await navigator.clipboard.writeText(t.value); ui('copy').textContent = 'Copied ✓'; }
    catch { t.focus(); t.select(); document.execCommand && document.execCommand('copy'); ui('copy').textContent = 'Selected: copy it'; }
    setTimeout(() => (ui('copy').textContent = 'Copy settings'), 1800);
  };
  ui('out').addEventListener('focus', e => e.target.select());
  ui('resetall').onclick = () => { const p = cfg.paper; cfg = structuredClone(baseCfg); cfg.paper = p; showAll(); scheduleRebuild(); };
  ui('load').onclick = async () => {
    const msg = ui('loadmsg');
    try {
      const got = JSON.parse(ui('in').value);
      if (got.format !== 'JoyBrush pencil tuner settings') throw new Error('this is not a pencil tuner settings block');
      if (!STICKS[got.base] && !PRESETS[got.base]) throw new Error(`no pencil called "${got.base}" here`);
      baseCfg = defaultsFor(got.base);
      cfg = merge(structuredClone(baseCfg), got.settings || {});
      showAll();
      await usePaper();
      scheduleRebuild();
      msg.textContent = 'Loaded.';
    } catch (err) { msg.textContent = 'Could not load: ' + err.message; }
  };
  for (const k of Object.keys(CURVES)) hookCurve(k);
  ui('leanflip').checked = !!cfg.pen.leanFlip;
  ui('leanflip').onchange = () => { cfg.pen.leanFlip = ui('leanflip').checked ? 1 : 0; save(); writeOut(); drawCompass(); };
}

/** Only the keys the defaults have, with numbers that are numbers: a hand-edited block cannot plant anything else. */
function merge(into, from) {
  for (const [k, v] of Object.entries(into)) {
    if (!(k in from)) continue;
    const f = from[k];
    if (Array.isArray(v)) { if (Array.isArray(f) && f.length === v.length && f.every(Number.isFinite)) into[k] = f.map(Number); }
    else if (v && typeof v === 'object') { if (f && typeof f === 'object') merge(v, f); }
    else if (typeof v === 'number') { if (Number.isFinite(f)) into[k] = f; }
    else if (typeof v === 'string' && typeof f === 'string') into[k] = f;
  }
  return into;
}

// ---- the settings block ----
function changed(a, b, prefix = '', out = {}) {
  for (const [k, v] of Object.entries(a)) {
    const key = prefix ? prefix + '.' + k : k, w = b[k];
    if (Array.isArray(v)) { if (v.some((x, i) => Math.abs(x - w[i]) > 1e-9)) out[key] = v.map(r6); }
    else if (v && typeof v === 'object') changed(v, w, key, out);
    else if (typeof v === 'number') { if (Math.abs(v - w) > 1e-9) out[key] = r6(v); }
    else if (v !== w) out[key] = v;
  }
  return out;
}
function r6(x) { return Math.round(x * 1e6) / 1e6; }
function rounded(o) { return JSON.parse(JSON.stringify(o, (k, v) => typeof v === 'number' ? r6(v) : v)); }
function writeOut() {
  const block = {
    format: 'JoyBrush pencil tuner settings',
    version: 1,
    base: cfg.base,
    changedFromBase: changed(cfg, baseCfg),
    settings: rounded(cfg),
  };
  ui('out').value = JSON.stringify(block, null, 1);
}
function save() { try { localStorage.setItem(STORE, JSON.stringify(cfg)); } catch { /* private window: nothing kept, nothing lost */ } }
function restore() {
  try {
    const got = JSON.parse(localStorage.getItem(STORE) || 'null');
    if (got && (STICKS[got.base] || PRESETS[got.base]) && !qs.has('fresh')) { baseCfg = defaultsFor(got.base); cfg = merge(structuredClone(baseCfg), got); return; }
  } catch { /* fall through to the default */ }
  baseCfg = defaultsFor('Proto 3');
  cfg = structuredClone(baseCfg);
}

// ---- the curve boxes (the app's ResponseSpec: [x1, y1, x2, y2], a cubic from (0,0) to (1,1)) ----
// Every curve box: which canvas, which setting, and where the live pen sits on it (x in, y out).
const CURVES = {
  P: { path: 'response.pressure', mark: r => r.p },
  T: { path: 'response.tilt', mark: r => Math.min(1, r.tiltDeg / 90) },
  S: { path: 'stick.sizeCurve', mark: r => r.felt.p },
  D: { path: 'stick.darkCurve', mark: r => r.felt.p },
  I: { path: 'stick.sideCurve', mark: r => r.felt.tilt / (Math.PI / 2) },
  O: { path: 'stick.pointCurve', mark: r => r.felt.tilt / (Math.PI / 2) },
};
const curveMark = {};
function markCurves() {
  const r = state.raw;
  for (const [k, c] of Object.entries(CURVES)) {
    const x = r ? c.mark(r) : null;
    curveMark[k] = x === null ? null : [x, curveEval(get(cfg, c.path), x)];
    drawCurve(k);
  }
}
function curveCanvas(which) { return ui('curve' + which); }
function drawCurve(which) {
  const c = curveCanvas(which);
  if (!c) return;
  const dpr = devicePixelRatio, w = Math.round(c.clientWidth * dpr) || 140 * dpr;
  if (c.width !== w) { c.width = w; c.height = w; }
  const g = c.getContext('2d'), pad = 12 * dpr, s = w - 2 * pad;
  const X = x => pad + x * s, Y = y => w - pad - y * s;
  g.clearRect(0, 0, w, w);
  g.strokeStyle = 'rgba(255,255,255,0.08)'; g.lineWidth = dpr;
  for (let i = 0; i <= 4; i++) { g.beginPath(); g.moveTo(X(i / 4), Y(0)); g.lineTo(X(i / 4), Y(1)); g.moveTo(X(0), Y(i / 4)); g.lineTo(X(1), Y(i / 4)); g.stroke(); }
  g.strokeStyle = 'rgba(255,255,255,0.18)'; g.setLineDash([4 * dpr, 4 * dpr]);
  g.beginPath(); g.moveTo(X(0), Y(0)); g.lineTo(X(1), Y(1)); g.stroke(); g.setLineDash([]);
  const h = get(cfg, CURVES[which].path);
  g.strokeStyle = 'rgba(34,211,238,0.5)'; g.lineWidth = 1.2 * dpr;
  g.beginPath(); g.moveTo(X(0), Y(0)); g.lineTo(X(h[0]), Y(h[1])); g.moveTo(X(1), Y(1)); g.lineTo(X(h[2]), Y(h[3])); g.stroke();
  g.strokeStyle = '#e8e6e3'; g.lineWidth = 2 * dpr;
  g.beginPath(); g.moveTo(X(0), Y(0)); g.bezierCurveTo(X(h[0]), Y(h[1]), X(h[2]), Y(h[3]), X(1), Y(1)); g.stroke();
  g.fillStyle = '#22d3ee';
  for (const [hx, hy] of [[h[0], h[1]], [h[2], h[3]]]) { g.beginPath(); g.arc(X(hx), Y(hy), 6 * dpr, 0, Math.PI * 2); g.fill(); }
  const m = curveMark[which];
  if (m) { g.fillStyle = '#fca5a5'; g.beginPath(); g.arc(X(m[0]), Y(m[1]), 4 * dpr, 0, Math.PI * 2); g.fill(); }
  ui('curve' + which + 'read').textContent = h.map(v => v.toFixed(2)).join(' ');
}
function hookCurve(which) {
  const c = curveCanvas(which), path = CURVES[which].path;
  let drag = -1, lastTap = 0;
  const at = e => {
    const r = c.getBoundingClientRect(), pad = 12, s = r.width - 2 * pad;
    return [Math.min(1, Math.max(0, (e.clientX - r.left - pad) / s)), Math.min(1, Math.max(0, 1 - (e.clientY - r.top - pad) / s))];
  };
  c.addEventListener('pointerdown', e => {
    e.preventDefault();
    try { c.setPointerCapture(e.pointerId); } catch { /* fine */ }
    if (e.timeStamp - lastTap < 320) { set(cfg, path, [...LINEAR]); drawCurve(which); scheduleRebuild(); lastTap = 0; return; }
    lastTap = e.timeStamp;
    const [x, y] = at(e), h = get(cfg, path);
    const d0 = Math.hypot(x - h[0], y - h[1]), d1 = Math.hypot(x - h[2], y - h[3]);
    drag = d0 <= d1 ? 0 : 2;
  });
  c.addEventListener('pointermove', e => {
    if (drag < 0) return;
    const [x, y] = at(e), h = get(cfg, path);
    h[drag] = x; h[drag + 1] = y;
    drawCurve(which);
    scheduleRebuild();
  });
  const up = () => { drag = -1; };
  c.addEventListener('pointerup', up);
  c.addEventListener('pointercancel', up);
}

// ---- paper ----
async function usePaper() {
  const id = catalogue.surfaces.some(s => s.id === cfg.paper) ? cfg.paper : catalogue.surfaces[0].id;
  cfg.paper = id;
  paper = await loadSurface(gl, catalogue, id);
  const look = (catalogue.looks || []).find(l => l.defaultSurface === id && l.base);
  state.look.paperColor = look ? [1, 3, 5].map(i => parseInt(look.base.slice(i, i + 2), 16) / 255) : [0.96, 0.95, 0.93];
  ui('paper').value = id;
}

async function main() {
  restore();
  fitCanvas();
  catalogue = await loadCatalogue([[ASSETS, 'catalogue.json']]);
  await usePaper();
  engine = await MediaEngine.create(gl, SHADERS, SHEET[0], SHEET[1]);
  buildPanel();
  if (matchMedia('(max-width: 700px)').matches) ui('toggle').textContent = '▼ Settings';
  showAll();
  writeOut();
  fit();
  hookInput();
  runTests();
  requestAnimationFrame(loop);
  window.__tuner = { cfg: () => cfg, runTests, felt };
  window.__done = true;
}

main().catch(err => {
  document.body.insertAdjacentHTML('beforeend', `<pre class="err">${err.stack || err}</pre>`);
  window.__error = String(err.stack || err); window.__done = true;
});
