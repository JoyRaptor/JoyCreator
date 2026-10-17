// oils.js — vector oils, lift vs repaint (owner, 2026-10-15: "freely nudgeable selectable movable live vector lines").
//
// Every oil stroke is its RECORD (brush, colour, seed, the pen's points after the spline) plus its SKIN: the paint
// state it left in its rectangle, captured right after it was painted, with a mask of where it changed anything.
//   Lift     moves a stroke as the paint it laid: the skins are stacked again in order with that one in its new
//            place. Its mix and ridges are kept, the paint under it shows again, nothing is simulated.
//   Re-mix   paints the moved stroke again where it now lies, mixing into what is under it; the strokes after it
//            keep their skins (one stroke's physics, not all of them).
//   Repaint  moves its record and paints every stroke again from its record, in order, with the real physics: the
//            moved stroke mixes into what is now under it, and the strokes painted after it pick up from it.
// All three run on the app's own oil engine (paste.js + the shared shaders), so what this shows is what the app would.
import { MediaEngine } from './engine.js';
import { loadCatalogue, loadSurface, DOC_PX_PER_MM } from './paper.js';
import { SplineFeeder } from './spline.js';
import { PASTE_BRUSHES, PasteStroke, opaquePaint } from './paste.js';
import { makeProgram, use, makeTexture, makeFbo, makeQuad } from './gl.js';

const qs = new URLSearchParams(location.search);
const SHADERS = qs.get('shaders') || '../../shaders/';
const ASSETS = qs.get('assets') || '../../assets/paper/';
const MM = DOC_PX_PER_MM;
const SHEET = [1600, 1100];        // doc px: 80 × 55 mm
const REACH = 68;                  // pen tilt (°) that counts as flat, as the app

const canvas = document.getElementById('c');
const gl = canvas.getContext('webgl2', { antialias: false, preserveDrawingBuffer: true });
const over = document.getElementById('over');
const octx = over.getContext('2d');
const ui = id => document.getElementById(id);

let engine, paper, catalogue;
const state = {
  mode: 'paint', move: 'lift', tool: 'Oil flat', color: [0.17, 0.31, 0.7],
  records: [], sel: -1, seed: 10,
  view: { pan: [0, 0], zoom: 1 }, dirty: true, live: null, drag: null, lastTap: null,
  look: { impasto: 1.4, pxPerMm: MM, paperColor: [0.93, 0.9, 0.84], lamp: norm([-0.55, 0.55, 0.62]), relief: 0.3, sheen: 0.06, capMm: 0.004, mode: 0 },
};
function norm(v) { const l = Math.hypot(...v); return v.map(x => x / l); }

// ---------------- skins: capture and stack ----------------
const VS = `#version 300 es
layout(location = 0) in vec2 a_corner;
uniform vec4 u_rect;   // target px
uniform vec2 u_size;   // target size
void main() { vec2 p = mix(u_rect.xy, u_rect.zw, a_corner); gl_Position = vec4(p / u_size * 2.0 - 1.0, 0.0, 1.0); }`;
// The skin: the state in the stroke's rectangle after it was painted, and where it differs from before.
const CAPTURE_FS = `#version 300 es
precision highp float;
uniform sampler2D u_p0; uniform sampler2D u_p1; uniform sampler2D u_pre0; uniform sampler2D u_pre1;
uniform vec2 u_src;    // the rectangle's corner in layer px
layout(location = 0) out vec4 o0;
layout(location = 1) out vec4 o1;
layout(location = 2) out vec4 oMask;
void main() {
  ivec2 s = ivec2(gl_FragCoord.xy + u_src);
  vec4 a0 = texelFetch(u_p0, s, 0), a1 = texelFetch(u_p1, s, 0);
  vec4 b0 = texelFetch(u_pre0, s, 0), b1 = texelFetch(u_pre1, s, 0);
  // Paint body (mm), pigment (K·X) and cover (S·X) the stroke laid or took up here.
  vec4 e0 = abs(a0 - b0), e1 = abs(a1 - b1);
  float d = max(max(max(e0.x, e0.y), max(e0.z, e0.w)), max(max(e1.x, e1.y), max(e1.z, e1.w)));
  // Exact: wherever anything changed (a skin is the state there, so restacking it reproduces the painting bit for bit).
  o0 = a0; o1 = a1; oMask = vec4(d > 1e-7 ? 1.0 : 0.0);
}`;
// A skin laid over the stack where its mask says the stroke painted.
const STACK_FS = `#version 300 es
precision highp float;
uniform sampler2D u_s0; uniform sampler2D u_s1; uniform sampler2D u_sp;
uniform sampler2D u_f0; uniform sampler2D u_f1; uniform sampler2D u_mask;
uniform vec2 u_dst;    // the skin's corner in layer px, where it lies now
uniform vec2 u_fsize;
layout(location = 0) out vec4 o0;
layout(location = 1) out vec4 o1;
layout(location = 2) out vec4 o2;
void main() {
  ivec2 L = ivec2(gl_FragCoord.xy);
  vec4 s0 = texelFetch(u_s0, L, 0), s1 = texelFetch(u_s1, L, 0);
  o2 = texelFetch(u_sp, L, 0);
  ivec2 f = L - ivec2(u_dst);
  if (f.x < 0 || f.y < 0 || f.x >= int(u_fsize.x) || f.y >= int(u_fsize.y)) { o0 = s0; o1 = s1; return; }
  float m = texelFetch(u_mask, f, 0).r;
  o0 = mix(s0, texelFetch(u_f0, f, 0), m);
  o1 = mix(s1, texelFetch(u_f1, f, 0), m);
}`;
let progCapture, progStack, quadVao, pre0, pre1;

function initSkins() {
  progCapture = makeProgram(gl, VS, CAPTURE_FS, 'skin capture');
  progStack = makeProgram(gl, VS, STACK_FS, 'skin stack');
  quadVao = gl.createVertexArray();
  gl.bindVertexArray(quadVao);
  gl.bindBuffer(gl.ARRAY_BUFFER, makeQuad(gl));
  gl.enableVertexAttribArray(0);
  gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
  gl.bindVertexArray(null);
  const full = { internal: gl.RGBA32F, type: gl.FLOAT, filter: gl.NEAREST };
  pre0 = makeTexture(gl, engine.w, engine.h, full);
  pre1 = makeTexture(gl, engine.w, engine.h, full);
}
function drawRect(fbo, w, h, rect) {
  gl.bindFramebuffer(gl.FRAMEBUFFER, fbo);
  gl.viewport(0, 0, w, h);
  gl.disable(gl.BLEND);
  gl.enable(gl.SCISSOR_TEST);
  gl.scissor(rect[0], rect[1], rect[2] - rect[0], rect[3] - rect[1]);
  gl.bindVertexArray(quadVao);
  gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
  gl.disable(gl.SCISSOR_TEST);
  gl.bindVertexArray(null);
  gl.bindFramebuffer(gl.FRAMEBUFFER, null);
}
/** Copy the state of [rect] (layer px) aside: what was there before a stroke. */
function snapshotBefore(rect) {
  const s = engine.state[0];
  engine.blit(s.p0, rect, pre0, rect);
  engine.blit(s.p1, rect, pre1, rect);
}
/** The stroke's skin from its rectangle, now that it is painted (compared with [snapshotBefore]). */
function captureSkin(rec, rect) {
  freeSkin(rec);
  const w = rect[2] - rect[0], h = rect[3] - rect[1];
  if (w <= 0 || h <= 0) return;
  const full = { internal: gl.RGBA32F, type: gl.FLOAT, filter: gl.NEAREST };
  const f0 = makeTexture(gl, w, h, full), f1 = makeTexture(gl, w, h, full);
  const mask = makeTexture(gl, w, h, { internal: gl.R8, format: gl.RED, type: gl.UNSIGNED_BYTE, filter: gl.NEAREST });
  const fbo = makeFbo(gl, [f0, f1, mask]);
  const s = engine.state[0];
  use(progCapture, { u_p0: s.p0, u_p1: s.p1, u_pre0: pre0, u_pre1: pre1, u_src: [rect[0], rect[1]], u_rect: [0, 0, w, h], u_size: [w, h] });
  drawRect(fbo, w, h, [0, 0, w, h]);
  gl.deleteFramebuffer(fbo);
  rec.skin = { f0, f1, mask, rect: [...rect], at: [...rec.offset], w, h };
}
function freeSkin(rec) {
  if (!rec.skin) return;
  gl.deleteTexture(rec.skin.f0); gl.deleteTexture(rec.skin.f1); gl.deleteTexture(rec.skin.mask);
  rec.skin = null;
}
/** Lay one skin on the stack, at its record's current place. */
function stackSkin(rec) {
  const k = rec.skin;
  if (!k) return;
  const dx = Math.round(rec.offset[0] - k.at[0]), dy = Math.round(rec.offset[1] - k.at[1]);
  const r = clampRect([k.rect[0] + dx, k.rect[1] + dy, k.rect[2] + dx, k.rect[3] + dy]);
  if (!r) return;
  const a = engine.state[0], b = engine.state[1];
  use(progStack, { u_s0: a.p0, u_s1: a.p1, u_sp: a.paper, u_f0: k.f0, u_f1: k.f1, u_mask: k.mask,
    u_dst: [k.rect[0] + dx, k.rect[1] + dy], u_fsize: [k.w, k.h], u_rect: r, u_size: [engine.w, engine.h] });
  drawRect(b.fbo, engine.w, engine.h, r);
  engine.blit(b.p0, r, a.p0, r);
  engine.blit(b.p1, r, a.p1, r);
}

// ---------------- records ----------------
function brushReachPx(brush) { return (Math.max(brush.widthMm || 0, brush.bladeLenMm || 0, brush.acrossMaxMm || 0, brush.lenMm || 0) * 0.75 + 2) * MM; }
function clampRect(r) {
  const x0 = Math.max(0, Math.floor(r[0])), y0 = Math.max(0, Math.floor(r[1]));
  const x1 = Math.min(engine.w, Math.ceil(r[2])), y1 = Math.min(engine.h, Math.ceil(r[3]));
  return x1 > x0 && y1 > y0 ? [x0, y0, x1, y1] : null;
}
/** Where a record can have painted, at its current place (layer px = doc px here). */
function recRect(rec) {
  const pad = brushReachPx(PASTE_BRUSHES[rec.tool]);
  let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
  for (const s of rec.samples) { x0 = Math.min(x0, s.x); y0 = Math.min(y0, s.y); x1 = Math.max(x1, s.x); y1 = Math.max(y1, s.y); }
  const [dx, dy] = rec.offset;
  return clampRect([x0 + dx - pad, y0 + dy - pad, x1 + dx + pad, y1 + dy + pad]);
}
/**
 * The 16-px cells a record's brush can have reached at its current place: its path, widened by the brush. Whether
 * two strokes touch is asked of these, not of their rectangles (a long diagonal stroke's rectangle covers half the
 * sheet it never touched).
 */
const CELL = 16;
function recCells(rec) {
  const half = brushReachPx(PASTE_BRUSHES[rec.tool]) + CELL, [dx, dy] = rec.offset, cells = new Set();
  const cols = Math.ceil(SHEET[0] / CELL);
  let prev = null;
  for (const s of rec.samples) {
    const x = s.x + dx, y = s.y + dy;
    if (prev && Math.hypot(x - prev[0], y - prev[1]) < CELL / 2) continue;
    prev = [x, y];
    const r = Math.ceil(half / CELL), cx = Math.floor(x / CELL), cy = Math.floor(y / CELL);
    for (let j = -r; j <= r; j++) for (let i = -r; i <= r; i++) {
      if ((i * i + j * j) * CELL * CELL > (half + CELL) * (half + CELL)) continue;
      const X = cx + i, Y = cy + j;
      if (X >= 0 && Y >= 0 && X < cols && Y * CELL < SHEET[1]) cells.add(Y * cols + X);
    }
  }
  return cells;
}

/** Paint one record with the real physics at its current place (a clean brush, freshly loaded). */
function paintRecord(rec) {
  const brush = PASTE_BRUSHES[rec.tool];
  engine.reloadBrush(opaquePaint(rec.color), brush, rec.seed, null);
  const ps = new PasteStroke(brush, MM, engine.scale, {});
  const [dx, dy] = rec.offset;
  for (const s of rec.samples) {
    ps.add({ ...s, x: s.x + dx, y: s.y + dy });
    for (const st of ps.take()) engine.pasteStep(st, brush, paper, MM, rec.seed);
  }
}
/** Repaint: every record again, in order, with the physics; each one's skin is taken afresh. */
function repaintAll() {
  const t0 = performance.now();
  engine.clearAll();
  for (const rec of state.records) {
    const r = recRect(rec);
    if (!r) continue;
    snapshotBefore(r);
    paintRecord(rec);
    captureSkin(rec, r);
  }
  gl.finish();
  state.dirty = true;
  return performance.now() - t0;
}
/**
 * Repaint only what a change reaches: [from] is the first record that changed (moved, recoloured), [area] where it
 * was before (layer px). Records before it keep their skins, exact (each was captured over exactly what is under it
 * now). From it on, a record is painted again if it touches the area the repainted ones reach, which then grows by
 * its own rectangle; one that touches none of it keeps its skin. Every brush is loaded fresh per stroke, so nothing
 * carries from one stroke to the next except the paint on the canvas. Returns [ms, how many were painted].
 */
function repaintFrom(from, area, onlyThis = false) {
  const t0 = performance.now();
  engine.clearAll();
  // [area] is the cells the changed record covered before the change.
  const reach = new Set(area || []);
  let painted = 0;
  state.records.forEach((rec, i) => {
    const r = recRect(rec);
    if (!r) return;
    const cells = i < from ? null : recCells(rec);
    let touches = i === from;
    // Re-mix paints the changed stroke alone: everything after it keeps its skin, as painted.
    if (!touches && cells && !onlyThis) for (const c of cells) if (reach.has(c)) { touches = true; break; }
    if (!touches) { stackSkin(rec); return; }
    snapshotBefore(r);
    paintRecord(rec);
    captureSkin(rec, r);
    for (const c of cells) reach.add(c);
    painted++;
  });
  gl.finish();
  state.dirty = true;
  return [performance.now() - t0, painted];
}

/** Lift: the skins stacked again in order, each at its record's place. No physics. */
function restack() {
  const t0 = performance.now();
  engine.clearAll();
  for (const rec of state.records) stackSkin(rec);
  gl.finish();
  state.dirty = true;
  return performance.now() - t0;
}

// ---------------- the sample painting ----------------
function samplePainting() {
  const az = -60 * Math.PI / 180, out = [];
  const curve = (pts, p, tilt, n = 140) => {
    const res = [];
    for (let i = 0; i <= n; i++) {
      const u = i / n, k = pts.length - 1, f = u * k, j = Math.min(k - 1, Math.floor(f)), w = f - j;
      const a = pts[j], b = pts[j + 1];
      res.push({ x: a[0] + (b[0] - a[0]) * w, y: a[1] + (b[1] - a[1]) * w, p: p(u), tilt: tilt * Math.PI / 180, az, t: i * 6 });
    }
    return res;
  };
  const swell = (lo, hi) => u => lo + (hi - lo) * Math.sin(Math.PI * Math.min(1, u * 1.2));
  out.push({ tool: 'Oil flat', color: [0.12, 0.22, 0.62], samples: curve([[180, 640], [520, 700], [900, 610], [1380, 680]], swell(0.4, 0.95), 45) });
  out.push({ tool: 'All-round', color: [0.95, 0.78, 0.12], samples: curve([[420, 940], [640, 700], [820, 420], [980, 220]], swell(0.35, 0.9), 40) });
  out.push({ tool: 'Oil round', color: [0.72, 0.1, 0.12], samples: curve([[260, 300], [600, 380], [940, 540], [1260, 860]], swell(0.3, 0.85), 35) });
  out.push({ tool: 'Palette knife 2', color: [0.96, 0.95, 0.92], samples: curve([[1050, 900], [1150, 600], [1220, 330]], swell(0.4, 0.8), 50, 90) });
  out.push({ tool: 'Oil flat', color: [0.18, 0.5, 0.3], samples: curve([[200, 900], [380, 860], [560, 900], [760, 860]], swell(0.3, 0.7), 55) });
  return out;
}
function loadSample() {
  for (const r of state.records) freeSkin(r);
  state.records = samplePainting().map(r => ({ ...r, seed: ++state.seed, offset: [0, 0], skin: null }));
  state.sel = -1;
  const ms = repaintAll();
  say(`sample painting: ${state.records.length} strokes painted in ${ms.toFixed(0)} ms`);
  selectionChanged();
}

// ---------------- pen ----------------
function docAt(e) {
  const dpr = devicePixelRatio, r = canvas.getBoundingClientRect();
  const sx = (e.clientX - r.left) * dpr, sy = canvas.height - (e.clientY - r.top) * dpr;
  return [(sx - state.view.pan[0]) / state.view.zoom, (sy - state.view.pan[1]) / state.view.zoom];
}
function penReading(e) {
  if (e.pointerType !== 'pen') return { p: 0.7, tilt: 40 * Math.PI / 180, az: -60 * Math.PI / 180 };
  let tilt = 0, az = -60 * Math.PI / 180;
  if (typeof e.altitudeAngle === 'number') { tilt = Math.PI / 2 - e.altitudeAngle; az = -e.azimuthAngle; }
  else if (e.tiltX || e.tiltY) {
    const tx = Math.tan((e.tiltX || 0) * Math.PI / 180), ty = Math.tan((e.tiltY || 0) * Math.PI / 180);
    tilt = Math.atan(Math.hypot(tx, ty)); az = Math.atan2(-ty, tx);
  }
  return { p: e.pressure, tilt: Math.min(1, tilt / (REACH * Math.PI / 180)) * (Math.PI / 2), az };
}
function hitTest(x, y) {
  const hits = [];
  for (let i = state.records.length - 1; i >= 0; i--) {
    const rec = state.records[i], half = brushReachPx(PASTE_BRUSHES[rec.tool]) * 0.55, [dx, dy] = rec.offset;
    let best = Infinity;
    for (let k = 1; k < rec.samples.length; k++) {
      const a = rec.samples[k - 1], b = rec.samples[k];
      const ax = a.x + dx, ay = a.y + dy, bx = b.x + dx, by = b.y + dy;
      const vx = bx - ax, vy = by - ay, l2 = vx * vx + vy * vy || 1;
      const t = Math.max(0, Math.min(1, ((x - ax) * vx + (y - ay) * vy) / l2));
      best = Math.min(best, Math.hypot(x - ax - vx * t, y - ay - vy * t));
    }
    if (best < half) hits.push(i);
  }
  return hits;   // topmost first
}

const touches = new Map();
function hookInput() {
  canvas.addEventListener('pointerdown', e => {
    try { canvas.setPointerCapture(e.pointerId); } catch { /* fine */ }
    if (e.pointerType === 'touch' && (state.mode === 'paint' || touches.size > 0 || !state.records.length)) { touches.set(e.pointerId, [e.clientX, e.clientY]); return; }
    const [x, y] = docAt(e);
    if (state.mode === 'paint') { beginPaint(e, x, y); return; }
    // Move: tap picks the topmost stroke under the pen; the same spot again picks the one beneath.
    const hits = hitTest(x, y);
    if (!hits.length) { state.sel = -1; selectionChanged(); return; }
    const last = state.lastTap;
    let pick = hits[0];
    if (last && Math.hypot(last[0] - x, last[1] - y) < 6 && hits.includes(state.sel)) pick = hits[(hits.indexOf(state.sel) + 1) % hits.length];
    state.lastTap = [x, y];
    state.sel = pick;
    selectionChanged();
    state.drag = { id: e.pointerId, from: [x, y], start: [...state.records[pick].offset], was: recCells(state.records[pick]), moved: false };
  });
  canvas.addEventListener('pointermove', e => {
    if (touches.has(e.pointerId)) { moveTouch(e); return; }
    if (state.live && state.live.id === e.pointerId) {
      const list = e.getCoalescedEvents ? e.getCoalescedEvents() : [e];
      for (const ev of (list.length ? list : [e])) feedPaint(ev);
      return;
    }
    const d = state.drag;
    if (d && d.id === e.pointerId) {
      const [x, y] = docAt(e), rec = state.records[state.sel];
      rec.offset = [d.start[0] + x - d.from[0], d.start[1] + y - d.from[1]];
      d.moved = true;
      // While dragging, both styles show the lifted skin moving; Repaint paints it in when you let go.
      state.pendingRestack = true;
      state.dirty = true;
    }
  });
  const end = e => {
    touches.delete(e.pointerId);
    if (state.live && state.live.id === e.pointerId) { endPaint(); return; }
    const d = state.drag;
    if (d && d.id === e.pointerId) { state.drag = null; if (d.moved) settleMove(d.was); }
  };
  canvas.addEventListener('pointerup', end);
  canvas.addEventListener('pointercancel', end);
  window.addEventListener('pointerup', end);
  canvas.addEventListener('wheel', e => { e.preventDefault(); zoomAt(e.clientX, e.clientY, Math.exp(-e.deltaY * 0.0015)); }, { passive: false });
  window.addEventListener('resize', () => { fitCanvas(); fit(); });
}
function beginPaint(e, x, y) {
  const brush = PASTE_BRUSHES[state.tool];
  const rec = { tool: state.tool, color: [...state.color], seed: ++state.seed, offset: [0, 0], samples: [], skin: null };
  engine.reloadBrush(opaquePaint(rec.color), brush, rec.seed, null);
  // What is under the whole sheet now: the new stroke's skin is what it changes.
  snapshotBefore([0, 0, engine.w, engine.h]);
  const ps = new PasteStroke(brush, MM, engine.scale, {});
  const feeder = new SplineFeeder(s => { rec.samples.push({ ...s }); ps.add(s); });
  state.live = { id: e.pointerId, rec, feeder, flush() { for (const st of ps.take()) engine.pasteStep(st, brush, paper, MM, rec.seed); } };
  feedPaint(e);
}
function feedPaint(e) {
  const [x, y] = docAt(e), r = penReading(e);
  state.live.feeder.add({ x, y, p: r.p, tilt: r.tilt, az: r.az, t: e.timeStamp });
}
function endPaint() {
  const live = state.live;
  state.live = null;
  live.feeder.finish();
  live.flush();
  if (live.rec.samples.length < 2) { state.dirty = true; return; }
  state.records.push(live.rec);
  const r = recRect(live.rec);
  if (r) captureSkin(live.rec, r);
  state.dirty = true;
  say(`${state.records.length} strokes`);
}
function settleMove(was) {
  const rec = state.records[state.sel];
  if (!rec) return;
  if (state.move === 'lift') { const ms = restack(); say(`lifted: ${state.records.length} skins stacked in ${ms.toFixed(1)} ms`); return; }
  if (state.move === 'remix') { const [ms] = repaintFrom(state.sel, was, true); say(`re-mixed this stroke in ${ms.toFixed(0)} ms; the strokes after it kept their skins`); return; }
  const [ms, n] = repaintFrom(state.sel, was);
  say(`repainted ${n} of ${state.records.length} strokes in ${ms.toFixed(0)} ms (the rest kept their skins)`);
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

// ---------------- view ----------------
function fitCanvas() {
  const dpr = devicePixelRatio;
  for (const c of [canvas, over]) {
    c.width = Math.round(innerWidth * dpr); c.height = Math.round(innerHeight * dpr);
    c.style.width = innerWidth + 'px'; c.style.height = innerHeight + 'px';
  }
}
function fit() {
  const dpr = devicePixelRatio, top = ui('bar').getBoundingClientRect().bottom + 8, bottom = ui('help').getBoundingClientRect().top - 8;
  const l = 8 * dpr, t = top * dpr, w = canvas.width - 16 * dpr, h = Math.max(50, bottom - top) * dpr;
  const z = Math.max(0.05, Math.min(w / SHEET[0], h / SHEET[1]));
  state.view.zoom = z;
  state.view.pan = [l + (w - SHEET[0] * z) / 2, canvas.height - (t + h) + (h - SHEET[1] * z) / 2];
  state.dirty = true;
}
function zoomAt(cx, cy, f) {
  const dpr = devicePixelRatio, sx = cx * dpr, sy = canvas.height - cy * dpr, v = state.view;
  const z = Math.min(40, Math.max(0.05, v.zoom * f)), k = z / v.zoom;
  v.pan = [sx - (sx - v.pan[0]) * k, sy - (sy - v.pan[1]) * k];
  v.zoom = z;
  state.dirty = true;
}
function loop() {
  if (state.live) { state.live.flush(); state.dirty = true; }
  if (state.pendingRestack) { state.pendingRestack = false; restack(); }
  if (state.dirty) { engine.render(state.view, paper, state.look, canvas.width, canvas.height); drawOverlay(); state.dirty = false; }
  requestAnimationFrame(loop);
}
/** The selected stroke: its path and rectangle, drawn over the paint. */
function drawOverlay() {
  octx.clearRect(0, 0, over.width, over.height);
  const rec = state.records[state.sel];
  if (!rec) return;
  const v = state.view, X = x => x * v.zoom + v.pan[0], Y = y => over.height - (y * v.zoom + v.pan[1]);
  const [dx, dy] = rec.offset, dpr = devicePixelRatio;
  octx.lineWidth = 2 * dpr; octx.setLineDash([6 * dpr, 5 * dpr]); octx.strokeStyle = 'rgba(34,211,238,0.95)';
  octx.beginPath();
  rec.samples.forEach((s, i) => { const px = X(s.x + dx), py = Y(s.y + dy); if (i) octx.lineTo(px, py); else octx.moveTo(px, py); });
  octx.stroke();
  const r = recRect(rec);
  if (r) { octx.setLineDash([]); octx.lineWidth = 1 * dpr; octx.strokeStyle = 'rgba(34,211,238,0.45)'; octx.strokeRect(X(r[0]), Y(r[3]), (r[2] - r[0]) * v.zoom, (r[3] - r[1]) * v.zoom); }
}

// ---------------- the bar ----------------
function say(text) { ui('readout').textContent = text; }
function selectionChanged() {
  const has = state.sel >= 0 && state.sel < state.records.length;
  for (const id of ['recolor', 'del', 'nudgeL', 'nudgeR']) ui(id).disabled = !has;
  state.dirty = true;
}
function buildBar() {
  const tool = ui('tool');
  for (const k of Object.keys(PASTE_BRUSHES)) if (!PASTE_BRUSHES[k].clean) tool.add(new Option(k, k, false, k === state.tool));
  tool.onchange = () => { state.tool = tool.value; };
  const hex = h => [1, 3, 5].map(i => parseInt(h.slice(i, i + 2), 16) / 255);
  ui('color').oninput = () => { state.color = hex(ui('color').value); };
  state.color = hex(ui('color').value);
  const setMode = m => {
    state.mode = m;
    ui('mPaint').classList.toggle('on', m === 'paint'); ui('mMove').classList.toggle('on', m === 'move');
    if (m === 'paint') { state.sel = -1; selectionChanged(); }
  };
  ui('mPaint').onclick = () => setMode('paint');
  ui('mMove').onclick = () => setMode('move');
  const setMove = m => {
    state.move = m;
    for (const [id, v] of [['sLift', 'lift'], ['sRemix', 'remix'], ['sRepaint', 'repaint']]) ui(id).classList.toggle('on', m === v);
  };
  ui('sLift').onclick = () => setMove('lift');
  ui('sRemix').onclick = () => setMove('remix');
  ui('sRepaint').onclick = () => setMove('repaint');
  ui('recolor').onclick = () => {
    const rec = state.records[state.sel]; if (!rec) return;
    rec.color = [...state.color];
    const [ms, n] = repaintFrom(state.sel, recCells(rec));
    say(`recoloured: repainted ${n} of ${state.records.length} strokes in ${ms.toFixed(0)} ms`);
  };
  const nudge = dx => () => { const rec = state.records[state.sel]; if (!rec) return; const was = recCells(rec); rec.offset = [rec.offset[0] + dx, rec.offset[1]]; settleMove(was); state.dirty = true; };
  ui('nudgeL').onclick = nudge(-MM);
  ui('nudgeR').onclick = nudge(MM);
  ui('del').onclick = () => {
    const rec = state.records[state.sel]; if (!rec) return;
    const at = state.sel, was = recCells(rec);
    freeSkin(rec);
    state.records.splice(at, 1);
    state.sel = -1; selectionChanged();
    if (state.move === 'lift') { say(`removed: skins stacked in ${restack().toFixed(1)} ms`); return; }
    const [ms, n] = repaintFrom(at, was);
    say(`removed: repainted ${n} of ${state.records.length} strokes in ${ms.toFixed(0)} ms`);
  };
  ui('repaintAll').onclick = () => { const ms = repaintAll(); say(`repainted: ${state.records.length} strokes in ${ms.toFixed(0)} ms`); };
  ui('sample').onclick = loadSample;
  ui('clear').onclick = () => { for (const r of state.records) freeSkin(r); state.records = []; state.sel = -1; engine.clearAll(); selectionChanged(); say('cleared'); };
}

async function main() {
  fitCanvas();
  catalogue = await loadCatalogue([[ASSETS, 'catalogue.json']]);
  const id = qs.get('paper') || 'canvas_linen';
  paper = await loadSurface(gl, catalogue, catalogue.surfaces.some(s => s.id === id) ? id : catalogue.surfaces[0].id);
  const look = (catalogue.looks || []).find(l => l.defaultSurface === paper.id && l.base);
  if (look) state.look.paperColor = [1, 3, 5].map(i => parseInt(look.base.slice(i, i + 2), 16) / 255);
  engine = await MediaEngine.create(gl, SHADERS, SHEET[0], SHEET[1], [0, 0], 1, { undoBytes: 0 });
  initSkins();
  buildBar();
  fit();
  hookInput();
  loadSample();
  requestAnimationFrame(loop);
  window.__oils = { state, repaintAll, repaintFrom, restack, recRect, recCells, records: () => state.records };
  window.__done = true;
}
main().catch(err => {
  document.body.insertAdjacentHTML('beforeend', `<pre class="err">${err.stack || err}</pre>`);
  window.__error = String(err.stack || err); window.__done = true;
});
