// joybrush/tools/media_atlas_b2_check.js — Media Atlas v2, slice B2 (tasks/joybrush/specs/MEDIA_ATLAS_V2_20261016.md): FREE
// placement. Any layer tile in any slot, residency dilation (every tile within a pass's read reach of what it writes is
// loaded first), per-slot load from and write-back to the layer's stores, 15 resident slots + 1 staging.
//
// The oracle is ONE big window engine (no atlas) over a 1280² layer = 5×5 tiles: strokes in the middle 3×3 block, the
// outer ring of tiles a guard (passes stay inside the block, so the oracle never meets its own edge). Against it run
// atlas engines whose textures are only 1024² = 16 slots, the layer kept in "stores" (one texture per plane over the
// whole layer) and tiles loaded into slots on demand, the page table updated as they come:
//   orderA   = slots handed out in order 0, 1, 2, …;
//   orderB   = slots handed out SCRAMBLED (so neighbouring tiles sit in unrelated slots, every seam a table read);
//   noRing   = orderB WITHOUT the dilation (only the tiles a pass writes are loaded): the control that MUST fail.
// Every engine pair runs the same seeded state and the same strokes (wet flat, wet tilted, dry point, dry side smear,
// eraser, oil round, palette knife); at checkpoints the atlas writes its resident tiles back and its stores are compared,
// raw float bits, with the oracle's state over the WHOLE layer (p0, p1, paper, w0, w1), and the look at the end.
//   orderA vs oracle: 0 pixels;  orderB vs oracle: 0 pixels;  noRing vs oracle: > 0.
//
// Run: NODE_PATH=<dir with playwright-core> node joybrush/tools/media_atlas_b2_check.js [--chrome <exe>] [--quick]
// Not tested here (by design): eviction and write-back under pressure (B3 decides wet residency), dryNow (clears water
// beyond the resident tiles in the window, not in the atlas), the window's own edge (the atlas has none).
const fs = require('fs');
const path = require('path');
let chromium;
try { chromium = require('playwright-core').chromium; } catch (e) {
  console.log(JSON.stringify({ pass: false, error: 'missing dependency playwright-core: ' + e.message })); process.exit(1);
}
const ROOT = path.join(__dirname, '..');
const SHADER_DIR = path.join(ROOT, 'shaders');
function argVal(name, dflt) { const i = process.argv.indexOf('--' + name); return i >= 0 && i + 1 < process.argv.length ? process.argv[i + 1] : dflt; }
const QUICK = process.argv.includes('--quick');
const PPM = 12, LW = 1280, AW = parseInt(argVal('atlas', '1024'), 10);   // --atlas 1536 = 6 x 6 slots (B3 slot scaling)

const INCLUDE_RE = /^#include\s+"([^"]+)"$/;
function expand(rel, seen = new Set()) {
  if (seen.has(rel)) return '';
  seen.add(rel);
  const text = fs.readFileSync(path.join(SHADER_DIR, rel), 'utf8').replace(/\r\n/g, '\n');
  return text.split('\n').map(l => { const m = l.trim().match(INCLUDE_RE); return m ? expand(m[1], seen) : l; }).join('\n');
}
const define = (src, ...names) => src.replace(/^(#version[^\n]*\n)/, (v) => v + names.map(n => '#define ' + n + '\n').join(''));
const FILES = { quad: 'media/jb_media_quad.vert', flux: 'media/jb_wet_flux.frag', update: 'media/jb_wet_update.frag', copy: 'media/jb_media_copy.frag',
  bead: 'media/jb_wet_bead.frag', wetV: 'media/jb_wet_dab.vert', wetF: 'media/jb_wet_dab.frag', bake: 'media/jb_media_bake.frag',
  screen: 'media/jb_media_screen.vert', render: 'media/jb_media_render.frag', dryV: 'media/jb_dry_dab.vert', dryF: 'media/jb_dry_dab.frag',
  apply: 'media/jb_media_apply.frag', eraseV: 'media/jb_media_erase_dab.vert', eraseF: 'media/jb_media_erase_dab.frag',
  eraseApply: 'media/jb_media_erase_apply.frag', pasteDab: 'media/jb_paste_dab.frag', pasteBrush: 'media/jb_paste_brush.frag' };
function srcSet(...defs) {
  const o = {}; for (const [k, f] of Object.entries(FILES)) o[k] = defs.length ? define(expand(f), ...defs) : expand(f);
  o.quadPlain = defs.length ? define(expand(FILES.quad), ...defs, 'JB_PLAIN_TARGET') : o.quad;
  // MediaLayerEngine's atlas build of every pass drawn slot by slot (ATLAS_NBR).
  if (defs.includes('JB_ATLAS')) for (const k of ['apply', 'copy', 'bake', 'flux', 'update', 'pasteDab', 'bead', 'eraseApply', 'render']) o[k] = define(expand(FILES[k]), ...defs, 'JB_ATLAS_NBR');
  return o;
}
const SRC = { window: srcSet(), atlas: srcSet('JB_ATLAS') };

function wetUniformMap(wc) {
  const sizing = 0.5, absorbency = 1, capacity = 1, wickSpeed = 1, tooth = 0.09, heightMean = 0.5;
  return {
    u_dt: wc.dt, u_flow: wc.flow, u_keep: wc.keep, u_pinMm: wc.pinMm * (0.4 + sizing), u_dampS: wc.dampS, u_minMm: wc.minMm, u_evap: wc.evap, u_edgeEvap: wc.edgeEvap,
    u_absorb: wc.absorb * absorbency * 2 * (1 - 0.95 * sizing), u_capMm: wc.capMm * capacity * 2, u_wick: Math.min(0.24 / wc.dt, wc.wick * wickSpeed * 2),
    u_sDry: wc.sDry, u_settle: wc.settle, u_lift: wc.lift, u_stainCarry: wc.stainCarry, u_toothMm: tooth,
    u_runMmPerS: wc.runMmPerS, u_runMm: wc.runMm, u_runHoldMm: wc.runHold * tooth, u_runFilmMm: wc.runFilm, u_runPin: wc.runPin, u_runSizing: wc.runSizing,
    u_dripGapMm: wc.dripGap, u_quietMm: wc.quiet, u_runHoldK: wc.runHoldK, u_runCoherent: wc.runCoherent, u_runCohere: wc.runCohere,
    u_steerScale: wc.steerScale, u_runSteer: wc.runSteer, u_runAlong: wc.runAlong, u_heightMean: heightMean, u_fullMm: wc.fullMm, u_filmMm: wc.filmMm,
    u_edgeDep: wc.edgeDep, u_mingle: Math.min(0.24 / wc.dt, wc.mingle), u_noBody: 0,
  };
}
const WC = { dt: 1 / 120, substeps: 4, flow: 30, keep: 0.9, pinMm: 0.22, dampS: 0.35, minMm: 0.002, evap: 0.003, edgeEvap: 4, absorb: 0.25, capMm: 0.08, wick: 5,
  sDry: 0.03, settle: 0.08, lift: 0.04, stainCarry: 0.12, filmMm: 0.12, edgeDep: 5, mingle: 12, fullMm: 0.4, runMmPerS: 8, runMm: 0.3, runHold: 0.5, runFilm: 0.012,
  runCohere: 0.3, runCoherent: 1, steerScale: 1, runHoldK: 1, quiet: 0.01, dripGap: 4, runSizing: 0.45, runPin: 0.5, runSteer: 4, runAlong: 0.25, beadMm: 0.6 };

function layerRect(r, padDoc) {
  const x0 = Math.max(0, Math.floor(r[0] - padDoc)), y0 = Math.max(0, Math.floor(r[1] - padDoc));
  const x1 = Math.min(LW, Math.ceil(r[2] + padDoc)), y1 = Math.min(LW, Math.ceil(r[3] + padDoc));
  return x1 > x0 && y1 > y0 ? [x0, y0, x1, y1] : null;
}
const union = (a, b) => a ? [Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.max(a[2], b[2]), Math.max(a[3], b[3])] : b.slice();
function grow(rect, paint, sx, sy, steps) {
  if (!paint || Math.hypot(sx, sy) <= 1e-4) return rect.slice();
  const cellMm = 1 / PPM, g = Math.ceil(steps * 0.45) + 2, far = Math.ceil(40 / cellMm), near = Math.ceil(10 / cellMm);
  const reach = (d) => d > 1e-4 ? far : near;
  return [Math.min(rect[0], Math.max(0, Math.max(paint[0] - reach(-sx), rect[0] - g))), Math.min(rect[1], Math.max(0, Math.max(paint[1] - reach(-sy), rect[1] - g))),
    Math.max(rect[2], Math.min(LW, Math.min(paint[2] + reach(sx), rect[2] + g))), Math.max(rect[3], Math.min(LW, Math.min(paint[3] + reach(sy), rect[3] + g)))];
}
function pathPts(x0, y0, x1, y1, bend, t0, t1, count) {
  const pts = [];
  for (let i = 0; i < count; i++) {
    const t = t0 + (t1 - t0) * (count === 1 ? 0 : i / (count - 1));
    const x = x0 + (x1 - x0) * t + bend * Math.sin(Math.PI * t), y = y0 + (y1 - y0) * t - bend * 0.6 * Math.sin(2 * Math.PI * t);
    const t2 = Math.min(1, t + 0.01), xn = x0 + (x1 - x0) * t2 + bend * Math.sin(Math.PI * t2), yn = y0 + (y1 - y0) * t2 - bend * 0.6 * Math.sin(2 * Math.PI * t2);
    const l = Math.hypot(xn - x, yn - y) || 1;
    pts.push({ x, y, dx: (xn - x) / l, dy: (yn - y) / l });
  }
  return pts;
}
const BRUSHES = {
  Round: { along: 3.0, half: 2.0, wpm: 0.09, wet: 0.95, K: [5.0, 2.0, 0.6], S: 0.2, dry: 0.0, sol: 0.7 },
  Wash: { along: 4.5, half: 4.0, wpm: 0.1, wet: 1.0, K: [1.2, 3.5, 4.5], S: 0.15, dry: 0.0, sol: 0.8 },
  Thirsty: { along: 3.0, half: 2.0, wpm: 0.05, wet: 0.08, K: [0, 0, 0], S: 0, dry: 0.9, sol: 0.0 },
};
function wetDabs(brush, pts, slideMm, seedNo) {
  const b = BRUSHES[brush], out = []; let dirty = null;
  for (const p of pts) {
    out.push([p.x, p.y, p.dx, p.dy, b.along, b.half, b.wpm, b.wet, b.K[0], b.K[1], b.K[2], b.S, slideMm, b.dry, b.sol, seedNo, 0, 0, 0, 0]);
    const r = (b.along + 0.1) * PPM + 2; dirty = union(dirty, [p.x - r, p.y - r, p.x + r, p.y + r]);
  }
  return { dabs: out, dirty, reach: (Math.hypot(b.along, b.half) + 0.08) * PPM + 1 };
}
// Residency reach of each medium (layer px): how far beyond a pass's rect its reads (and the bead's writes) go.
const REACH = { wet: 80, dry: 0, paste: 12, erase: 3, look: 56 };   // dry: the apply's smear reach is added per stroke
function buildWet(sc) {
  const frames = []; let wetRect = null, paintRect = null;
  for (let f = 0; f < sc.frames; f++) {
    const fr = { steps: WC.substeps, dabs: null, reach: 0, rect: null };
    let batch = null;
    if (f < sc.strokeFrames) batch = wetDabs(sc.brush, pathPts(...sc.from, ...sc.to, sc.bend, f / sc.strokeFrames, (f + 1) / sc.strokeFrames, 3), 0.55 * (f === 0 ? 3 : 1), 3);
    else if (sc.thirsty && f >= sc.thirsty[0] && f < sc.thirsty[1]) {
      const k = f - sc.thirsty[0], m = sc.thirsty[1] - sc.thirsty[0];
      batch = wetDabs('Thirsty', pathPts(...sc.thirstyFrom, ...sc.thirstyTo, 0, k / m, (k + 1) / m, 3), 0.6, 9);
    }
    if (batch) { fr.dabs = batch.dabs; fr.reach = batch.reach; const r = layerRect(batch.dirty, 48); if (r) { wetRect = union(wetRect, r); paintRect = union(paintRect, r); } }
    fr.rect = wetRect ? grow(wetRect, paintRect, sc.slope[0], sc.slope[1], fr.steps) : null;
    if (fr.rect) wetRect = fr.rect;
    frames.push(fr);
  }
  return { kind: 'wet', name: sc.name, slope: sc.slope, gran: sc.gran || 0, seed: sc.seed, frames };
}
// Proto 2 / Proto Pencil (brush v11, Stick.blend 1): one blended footprint of exp() bells, so the squeeze is never exactly
// 0 and the quad edge cuts a tail. The quad is sized as stickContact sizes it (reach = r0 · (1 + bellReach)).
const STICKS = {
  zones: { blend: 0, shape: [2.2, 1.6, 0.5, 2.5], grads: [1.2, 0.9] },
  'Proto 2': { blend: 1, shape: [2.2, 1.6, 0.5, 2.5], grads: [1.2, 0.9], bellReach: 0.6, tipR: 0.15, tipMax: 0.55, side1: 3.5, side2: 10.0, face: 1.1 },
  'Proto Pencil': { blend: 1, shape: [4.45, 1.6, 0.5, 3.6], grads: [1.2, 2.0], bellReach: 0.6, tipR: 0.15, tipMax: 0.55, side1: 3.5, side2: 9.2, face: 1.1 },
};
function protoDab(st, p, lean, P, tilted) {
  const r0 = st.tipR + (st.tipMax - st.tipR) * Math.pow(P, 0.8), reach = r0 * (1 + st.bellReach * st.blend);
  const l1 = tilted ? r0 + (st.side1 - r0) * 0.7 : r0, l2 = tilted ? l1 + (st.side2 - l1) * 0.45 : r0, H = tilted ? st.face : r0;
  const w = tilted ? [0.5, 0.3, 0.2] : [1, 0, 0];
  const xMin = -reach, xMax = tilted ? Math.max(reach, l2) : reach, yMax = tilted ? Math.max(reach, st.face) : reach, D = 0.02 + 0.05 * P;
  return [p.x, p.y, lean[0], lean[1], xMin, xMax, yMax, D, r0, l1, l2, H, w[0], w[1], w[2], 0.12, p.dx, p.dy, 0.4, P];
}
function buildDry(sc) {
  const frames = [];
  for (let f = 0; f < sc.frames; f++) {
    const pts = pathPts(...sc.from, ...sc.to, sc.bend, f / sc.frames, (f + 1) / sc.frames, 6);
    const dabs = []; let dirty = null;
    pts.forEach((p, i) => {
      const P = 0.25 + 0.65 * ((f * 6 + i) % 11) / 10, lean = [Math.cos(sc.lean), Math.sin(sc.lean)];
      // The quad must cover everywhere the stick can press (stickContact does): past the longest zone (L2 = 3.4 mm) and
      // the face half-width. A quad edge where the stick still presses would make coverage depend on rasterisation rounding.
      const xMin = -0.6, xMax = sc.side ? 4.0 : 3.6, yMax = sc.side ? 1.1 : 1.0, D = 0.03 + 0.07 * P;
      const dab = sc.stick ? protoDab(STICKS[sc.stick], p, lean, P, sc.side) : [p.x, p.y, lean[0], lean[1], xMin, xMax, yMax, D, 0.45, 1.6, 3.4, 0.8, 1, 0.6, 0.35, 0.12, p.dx, p.dy, 0.4, P];
      dabs.push(dab);
      const e = (Math.hypot(Math.max(Math.abs(dab[4]), dab[5]), dab[6]) + 0.1) * PPM + 2;
      dirty = union(dirty, [p.x - e, p.y - e, p.x + e, p.y + e]);
    });
    frames.push({ dabs, travel: [pts[pts.length - 1].dx, pts[pts.length - 1].dy], rect: layerRect(dirty, sc.smearMm * PPM + 2) });
  }
  const st = STICKS[sc.stick || 'zones'];
  return { kind: 'dry', name: sc.name, seed: sc.seed, smearMm: sc.smearMm, stick: { u_stickBlend: st.blend, u_stickShape: st.shape, u_zoneGrad: st.grads }, frames };
}
function buildErase(sc) {
  const frames = [];
  for (let f = 0; f < sc.frames; f++) {
    const pts = pathPts(...sc.from, ...sc.to, sc.bend, f / sc.frames, (f + 1) / sc.frames, 5);
    const r = sc.radius, dabs = pts.map((p, i) => [p.x, p.y, r, 0.4 + 0.6 * ((f + i) % 4) / 3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]);
    let dirty = null; for (const p of pts) dirty = union(dirty, [p.x - r - 3, p.y - r - 3, p.x + r + 3, p.y + r + 3]);
    frames.push({ dabs, rect: layerRect(dirty, 0) });
  }
  return { kind: 'erase', name: sc.name, seed: sc.seed, frames };
}
function buildPaste(sc) {
  const frames = [];
  for (let f = 0; f < sc.frames; f++) {
    const pts = pathPts(...sc.from, ...sc.to, sc.bend, f / sc.frames, (f + 1) / sc.frames, 2);
    frames.push({ steps: pts.map((p, i) => {
      const t = [p.dx, p.dy], w = [-p.dy, p.dx], pressure = 0.35 + 0.5 * ((f * 2 + i) % 7) / 6, half = (Math.hypot(sc.halfW, sc.lenMax) + 0.3) * PPM + 2;
      return { center: [p.x, p.y], wDir: w, lDir: [-t[0], -t[1]], travel: t, bladeDir: w, pressure, len: sc.lenMax * (0.4 + 0.6 * pressure), rect: layerRect([p.x - half, p.y - half, p.x + half, p.y + half], 0) };
    }) });
  }
  return { kind: 'paste', name: sc.name, seed: sc.seed, shape: sc.shape, blade: sc.blade, halfW: sc.halfW, lenMax: sc.lenMax, frames };
}
const FLAT = [0, 0], F = (n) => QUICK ? Math.max(4, n >> 2) : n;
// Block = tiles 1..3 = [256, 1024). Every pass rect PLUS its reach stays inside it.
const SCENARIOS = [
  buildWet({ name: 'wet Round flat, 3×3 tiles', brush: 'Round', from: [440, 470], to: [840, 820], bend: 40, frames: F(18), strokeFrames: F(10), slope: FLAT, seed: 11, thirsty: [F(8), F(11)], thirstyFrom: [560, 700], thirstyTo: [720, 520] }),
  buildWet({ name: 'wet Wash tilted (bead, steer, ring)', brush: 'Wash', from: [520, 520], to: [720, 690], bend: -15, frames: F(10), strokeFrames: F(6), slope: [0.15, 0.25], seed: 13, gran: 1 }),
  buildDry({ name: 'dry point stick, 3×3 tiles', from: [420, 520], to: [880, 760], bend: 30, frames: F(14), seed: 21, lean: 0.6, side: false, smearMm: 0.4 }),
  buildDry({ name: 'dry side stick, smear', from: [860, 420], to: [420, 860], bend: -30, frames: F(14), seed: 22, lean: 2.3, side: true, smearMm: 0.9 }),
  buildDry({ name: 'Proto 2 point, 3×3 tiles', stick: 'Proto 2', from: [420, 480], to: [880, 800], bend: 25, frames: F(14), seed: 23, lean: 0.9, side: false, smearMm: 0.4 }),
  buildDry({ name: 'Proto Pencil tilted, 3×3 tiles', stick: 'Proto Pencil', from: [430, 840], to: [840, 430], bend: -20, frames: F(14), seed: 24, lean: 1.9, side: true, smearMm: 0.9 }),
  buildErase({ name: 'eraser across the block', from: [440, 460], to: [860, 860], bend: 25, frames: F(10), seed: 31, radius: 18 }),
  buildPaste({ name: 'oil round brush', from: [430, 540], to: [880, 720], bend: 25, frames: F(12), seed: 41, shape: 0, blade: 0, halfW: 2.2, lenMax: 7 }),
  buildPaste({ name: 'palette knife (blade)', from: [870, 430], to: [430, 870], bend: -15, frames: F(10), seed: 42, shape: 2, blade: 1, halfW: 3.0, lenMax: 5 }),
];
const KINDS = ['orderA', 'orderB', 'noRing'];

(async () => {
  const exe = (() => {
    const c = argVal('chrome', process.env.CHROME || null);
    if (c && fs.existsSync(c)) return c;
    const edge = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';
    return fs.existsSync(edge) ? edge : null;
  })();
  if (!exe) { console.log(JSON.stringify({ pass: false, error: 'no browser' })); process.exit(1); }
  const browser = await chromium.launch({ executablePath: exe, args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'] });   // SwiftShader only: these compare the atlas with the WINDOW build, two different programs, and a real GPU's compiler may round them apart (Intel d3d11: dry dabs differ by an ulp even at the proven size; the Note 9's Adreno does not)
  let out;
  try {
    const page = await browser.newPage();
    page.on('console', (m) => { if (m.type() === 'error') console.error('[page]', m.text()); });
    out = await page.evaluate(PAGE, { LW, AW, PPM, SRC, wu: wetUniformMap(WC), WC, REACH, KINDS, scenarios: SCENARIOS });
  } finally { await browser.close(); }
  if (out.error) { console.log(JSON.stringify({ pass: false, ...out })); process.exit(1); }
  let exactBad = 0, ringSeen = 0, errors = 0;
  for (const r of out.results) {
    if (r.error) errors++;
    exactBad += (r.mismatch.orderA?.total ?? 0) + (r.mismatch.orderB?.total ?? 0); ringSeen += r.mismatch.noRing?.total ?? 0;
  }
  const nonvacuous = out.results.every(r => r.changed > 200 && r.maxResident >= 4);
  const scattered = out.results.every(r => r.seamReads > 0);
  const pass = errors === 0 && exactBad === 0 && ringSeen > 0 && nonvacuous && scattered;
  console.log(JSON.stringify({ pass, layer: LW, atlas: AW, slots: 16, resident: 15,
    totals: { freePlacementMismatchedPixels: exactBad, missingRingDetectedPixels: ringSeen }, nonvacuous, perScenario: out.results, unsetUniforms: out.missingUniforms }, null, 1));
  process.exit(pass ? 0 : 1);
})().catch(e => { console.log(JSON.stringify({ pass: false, error: String(e && e.stack || e) })); process.exit(1); });

// =====================================================================================================================
async function PAGE(P) {
  const fail = (error, extra = {}) => ({ error, ...extra });
  const LW = P.LW, AW = P.AW, GN = LW / 256, SN = AW / 256, RESIDENT = SN * SN - 1;
  const c = document.createElement('canvas'); c.width = 16; c.height = 16;
  const gl = c.getContext('webgl2', { antialias: false, alpha: false });
  if (!gl || !gl.getExtension('EXT_color_buffer_float')) return fail('no float webgl2');
  const comp = (t, s, what) => { const sh = gl.createShader(t); gl.shaderSource(sh, s); gl.compileShader(sh); if (!gl.getShaderParameter(sh, gl.COMPILE_STATUS)) throw new Error(what + ': ' + gl.getShaderInfoLog(sh)); return sh; };
  const link = (vs, fs, what) => { const p = gl.createProgram(); gl.attachShader(p, comp(gl.VERTEX_SHADER, vs, what + ' vs')); gl.attachShader(p, comp(gl.FRAGMENT_SHADER, fs, what + ' fs')); gl.linkProgram(p); if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error(what + ' link: ' + gl.getProgramInfoLog(p)); return p; };
  const progSet = (S, tag) => ({
    flux: link(S.quad, S.flux, tag), update: link(S.quad, S.update, tag), copy: link(S.quad, S.copy, tag), bead: link(S.quad, S.bead, tag),
    wet: link(S.wetV, S.wetF, tag), bake: link(S.quad, S.bake, tag), render: link(S.screen, S.render, tag), dry: link(S.dryV, S.dryF, tag),
    apply: link(S.quad, S.apply, tag), erase: link(S.eraseV, S.eraseF, tag), eraseApply: link(S.quad, S.eraseApply, tag),
    pasteDab: link(S.quad, S.pasteDab, tag), pasteBrush: link(S.quadPlain, S.pasteBrush, tag) });
  let PROG;
  try { PROG = { window: progSet(P.SRC.window, 'window'), atlas: progSet(P.SRC.atlas, 'atlas') }; } catch (e) { return fail('compile/link: ' + e.message); }
  const uinfo = new Map(), missing = new Set();
  const infoOf = (p) => { if (!uinfo.has(p)) { const m = {}; const n = gl.getProgramParameter(p, gl.ACTIVE_UNIFORMS); for (let i = 0; i < n; i++) { const a = gl.getActiveUniform(p, i); m[a.name.replace(/\[0\]$/, '')] = { type: a.type, loc: gl.getUniformLocation(p, a.name) }; } uinfo.set(p, m); } return uinfo.get(p); };
  function use(prog, vals) {
    gl.useProgram(prog);
    const info = infoOf(prog); let unit = 0;
    for (const [name, u] of Object.entries(info)) {
      const v = vals[name];
      if (u.type === gl.SAMPLER_2D || u.type === gl.INT_SAMPLER_2D) {
        if (v === undefined) { missing.add(name); unit++; continue; }
        gl.activeTexture(gl.TEXTURE0 + unit); gl.bindTexture(gl.TEXTURE_2D, v.tex); gl.uniform1i(u.loc, unit); unit++; continue;
      }
      if (v === undefined) { if (!name.startsWith('u_nbr')) missing.add(name); continue; }   // u_nbr*: set per slot in drawRect
      if (u.type === gl.FLOAT) gl.uniform1f(u.loc, +v);
      else if (u.type === gl.INT || u.type === gl.BOOL) gl.uniform1i(u.loc, v | 0);
      else if (u.type === gl.FLOAT_VEC2) gl.uniform2fv(u.loc, v);
      else if (u.type === gl.FLOAT_VEC3) gl.uniform3fv(u.loc, v);
      else if (u.type === gl.FLOAT_VEC4) gl.uniform4fv(u.loc, v);
      else if (u.type === gl.INT_VEC4) gl.uniform4iv(u.loc, v);
      else if (u.type === gl.INT_VEC2) gl.uniform2iv(u.loc, v);   // the look's u_nbrTile, u_nbrSlot[9]
      else throw new Error('unhandled uniform type for ' + name);
    }
    gl.activeTexture(gl.TEXTURE0);
  }
  const quad = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, quad); gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([0, 0, 1, 0, 0, 1, 1, 1]), gl.STATIC_DRAW);
  const quadVao = gl.createVertexArray(); gl.bindVertexArray(quadVao); gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
  const slotBuf = gl.createBuffer();
  const atlasQuadVao = gl.createVertexArray(); gl.bindVertexArray(atlasQuadVao); gl.bindBuffer(gl.ARRAY_BUFFER, quad); gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
  gl.bindBuffer(gl.ARRAY_BUFFER, slotBuf);
  for (let i = 0; i < 2; i++) { gl.enableVertexAttribArray(1 + i); gl.vertexAttribPointer(1 + i, 4, gl.FLOAT, false, 32, i * 16); gl.vertexAttribDivisor(1 + i, 1); }
  const dabBuf = gl.createBuffer();
  const dabVao = gl.createVertexArray(); gl.bindVertexArray(dabVao); gl.bindBuffer(gl.ARRAY_BUFFER, quad); gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
  gl.bindBuffer(gl.ARRAY_BUFFER, dabBuf);
  for (let i = 0; i < 5; i++) { gl.enableVertexAttribArray(1 + i); gl.vertexAttribPointer(1 + i, 4, gl.FLOAT, false, 80, i * 16); gl.vertexAttribDivisor(1 + i, 1); }
  const atlasDabVao = gl.createVertexArray(); gl.bindVertexArray(atlasDabVao); gl.bindBuffer(gl.ARRAY_BUFFER, quad); gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
  gl.bindBuffer(gl.ARRAY_BUFFER, dabBuf);
  for (let i = 0; i < 6; i++) { gl.enableVertexAttribArray(1 + i); gl.vertexAttribPointer(1 + i, 4, gl.FLOAT, false, 96, i * 16); gl.vertexAttribDivisor(1 + i, 1); }
  const emptyVao = gl.createVertexArray();
  gl.bindVertexArray(null); gl.disable(gl.BLEND); gl.disable(gl.SCISSOR_TEST);
  const FMT = {
    rgba32f: [gl.RGBA32F, gl.RGBA, gl.FLOAT, gl.NEAREST], rgba16f: [gl.RGBA16F, gl.RGBA, gl.HALF_FLOAT, gl.LINEAR], rg16f: [gl.RG16F, gl.RG, gl.HALF_FLOAT, gl.LINEAR],
    r16f: [gl.R16F, gl.RED, gl.HALF_FLOAT, gl.LINEAR], rgba8: [gl.RGBA8, gl.RGBA, gl.UNSIGNED_BYTE, gl.NEAREST], r8: [gl.R8, gl.RED, gl.UNSIGNED_BYTE, gl.NEAREST],
    r32i: [gl.R32I, gl.RED_INTEGER, gl.INT, gl.NEAREST],
  };
  let OWNED = null;
  function tex(w, h, fmt, data) {
    const [i, f, t, filt] = FMT[fmt], id = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, id);
    if (OWNED) OWNED.tex.push(id);
    gl.texImage2D(gl.TEXTURE_2D, 0, i, w, h, 0, f, t, data || null);
    for (const [k, v] of [[gl.TEXTURE_MIN_FILTER, filt], [gl.TEXTURE_MAG_FILTER, filt], [gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE], [gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE]]) gl.texParameteri(gl.TEXTURE_2D, k, v);
    return id;
  }
  function fbo(...texs) {
    const f = gl.createFramebuffer(); gl.bindFramebuffer(gl.FRAMEBUFFER, f);
    if (OWNED) OWNED.fbo.push(f);
    texs.forEach((t, i) => gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0 + i, gl.TEXTURE_2D, t, 0));
    gl.drawBuffers(texs.map((_, i) => gl.COLOR_ATTACHMENT0 + i));
    if (gl.checkFramebufferStatus(gl.FRAMEBUFFER) !== gl.FRAMEBUFFER_COMPLETE) throw new Error('fbo incomplete');
    return f;
  }
  const readFbo = gl.createFramebuffer(), drawFbo = gl.createFramebuffer();
  function blitTex(src, sx0, sy0, sx1, sy1, dst, dx0, dy0) {
    gl.bindFramebuffer(gl.READ_FRAMEBUFFER, readFbo); gl.framebufferTexture2D(gl.READ_FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, src, 0);
    gl.bindFramebuffer(gl.DRAW_FRAMEBUFFER, drawFbo); gl.framebufferTexture2D(gl.DRAW_FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, dst, 0);
    gl.drawBuffers([gl.COLOR_ATTACHMENT0]);
    gl.blitFramebuffer(sx0, sy0, sx1, sy1, dx0, dy0, dx0 + (sx1 - sx0), dy0 + (sy1 - sy0), gl.COLOR_BUFFER_BIT, gl.NEAREST);
    gl.bindFramebuffer(gl.READ_FRAMEBUFFER, null); gl.bindFramebuffer(gl.DRAW_FRAMEBUFFER, null);
  }
  // ---------- seeded state over the whole layer (the oracle's window, the atlas's stores) ----------
  const rngf = (a) => () => { a = (a + 0x6D2B79F5) >>> 0; let t = Math.imul(a ^ a >>> 15, 1 | a); t = (t + Math.imul(t ^ t >>> 7, 61 | t)) ^ t; return ((t ^ t >>> 14) >>> 0) / 4294967296; };
  function noiseField(seed, cell) {
    const r = rngf(seed), gw = Math.ceil(LW / cell) + 3, g = new Float32Array(gw * gw);
    for (let i = 0; i < g.length; i++) g[i] = r();
    return (x, y) => { const fx = x / cell, fy = y / cell, ix = Math.floor(fx), iy = Math.floor(fy), tx = fx - ix, ty = fy - iy; const sx = tx * tx * (3 - 2 * tx), sy = ty * ty * (3 - 2 * ty);
      const a = g[iy * gw + ix], b = g[iy * gw + ix + 1], c2 = g[(iy + 1) * gw + ix], d = g[(iy + 1) * gw + ix + 1]; return (a + (b - a) * sx) * (1 - sy) + (c2 + (d - c2) * sx) * sy; };
  }
  function seedState(seed) {
    const n = LW * LW * 4, o = {}; for (const k of ['p0', 'p1', 'ps', 'w0', 'w1', 'g0', 'g1', 'g2']) o[k] = new Float32Array(n);
    const nA = noiseField(seed, 37), nB = noiseField(seed + 1, 23), nC = noiseField(seed + 2, 61), nD = noiseField(seed + 3, 9), r = rngf(seed + 9);
    for (let y = 0; y < LW; y++) for (let x = 0; x < LW; x++) {
      const i = (y * LW + x) * 4, body = Math.max(0, nA(x, y) * 1.5 - 0.6) * 0.03;
      o.p0[i] = 3 * body * (0.5 + nB(x, y)); o.p0[i + 1] = 2 * body * nC(x, y); o.p0[i + 2] = 1.5 * body; o.p0[i + 3] = body + 0.002 * nD(x, y);
      o.p1[i] = o.p1[i + 1] = o.p1[i + 2] = 20 * body; o.p1[i + 3] = body > 0 ? Math.min(1, 0.2 + 0.8 * nC(x, y)) : 0;
      const gr = Math.max(0, nB(x, y) - 0.45) * 0.006; o.ps[i] = 0.3 * Math.max(0, nD(x, y) - 0.5); o.ps[i + 1] = gr; o.ps[i + 2] = gr * 0.12;
      // A damp pool inside the block (water never reaches the guard ring, where the oracle would differ from unloaded tiles).
      const dx = (x - 610) / 110, dy = (y - 660) / 90, pool = Math.exp(-(dx * dx + dy * dy));
      const wd = pool > 0.12 ? 0.25 * pool * (0.7 + 0.6 * nB(x, y)) : 0;
      o.w0[i] = wd; o.w0[i + 1] = Math.min(1, 0.05 + 0.9 * pool + 0.15 * nC(x, y)); o.w0[i + 2] = wd > 0 ? 30 * r() : 0.5 * r(); o.w0[i + 3] = r() * 0.1;
      if (wd > 0) { o.w1[i] = 4 * wd * nA(x, y); o.w1[i + 1] = 2 * wd; o.w1[i + 2] = 0.5 * wd * nD(x, y); o.w1[i + 3] = 0.3 * wd; }
      for (let k = 0; k < 4; k++) { o.g0[i + k] = 3 * r(); o.g1[i + k] = 3 * r(); o.g2[i + k] = 3 * r(); }
    }
    return o;
  }
  const PS = 64, SLOPE = 0.099;
  const paperBytes = (() => {
    const r = rngf(0x9a9a), h = new Float32Array(PS * PS), b = new Uint8Array(PS * PS * 4);
    for (let y = 0; y < PS; y++) for (let x = 0; x < PS; x++) h[y * PS + x] = 0.5 + 0.25 * Math.sin(2 * Math.PI * x / 16) + 0.15 * Math.cos(2 * Math.PI * y / 12) + 0.2 * (r() - 0.5);
    for (let y = 0; y < PS; y++) for (let x = 0; x < PS; x++) {
      const at = (xx, yy) => h[((yy + PS) % PS) * PS + ((xx + PS) % PS)], dx = (at(x + 1, y) - at(x - 1, y)) / 2, dy = (at(x, y + 1) - at(x, y - 1)) / 2;
      const v = Math.min(1, Math.max(0, h[y * PS + x])), o = (y * PS + x) * 4;
      b[o] = Math.round(127 + 127 * Math.min(1, Math.max(-1, dx / SLOPE))); b[o + 1] = Math.round(127 + 127 * Math.min(1, Math.max(-1, dy / SLOPE)));
      b[o + 2] = Math.round(v * 255); b[o + 3] = Math.round(v * v * 255);
    }
    return b;
  })();
  const mip = (id) => { gl.bindTexture(gl.TEXTURE_2D, id); gl.generateMipmap(gl.TEXTURE_2D); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR_MIPMAP_LINEAR); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.REPEAT); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.REPEAT); };
  const paperTex = tex(PS, PS, 'rgba8', paperBytes); mip(paperTex);
  const fluidTex = tex(4, 4, 'rgba8', new Uint8Array(64).fill(128)); mip(fluidTex);
  const lutBytes = new Uint8Array(256 * 4); for (let i = 0; i < 256; i++) { lutBytes[i * 4] = Math.round(255 * Math.min(1, Math.pow(i / 255, 0.7) * 1.1)); lutBytes[i * 4 + 3] = 255; }
  const lutTex = tex(256, 1, 'rgba8', lutBytes); gl.bindTexture(gl.TEXTURE_2D, lutTex); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
  const groundBytes = (() => { const r = rngf(77), b = new Uint8Array(LW * LW * 4); for (let i = 0; i < LW * LW; i++) { const a = Math.floor(r() * 256); b[i * 4] = Math.floor(a * r()); b[i * 4 + 1] = Math.floor(a * r()); b[i * 4 + 2] = Math.floor(a * r()); b[i * 4 + 3] = a; } return b; })();
  const paperU = { u_paperSurface: { tex: paperTex }, u_paperTexelPx: 2, u_paperSize: PS, u_paperHexTexels: 32, u_paperSlopeRange: SLOPE, u_paperRotatable: 1, u_paperHeightMean: 0.5,
    u_paperHBot: 0.2, u_paperHTop: 0.8, u_paperFluid: { tex: fluidTex }, u_paperFluidTexelPx: 0, u_paperFluidSize: 4, u_paperFluidHexTexels: 1 };

  // =================================================================================================================
  // An engine. 'window' = the oracle: its textures ARE the layer. Otherwise an atlas: textures AW² (SN×SN slots, one kept
  // for staging), the layer in stores (LW² per plane), tiles loaded on demand, the page table over the layer's GN×GN tiles.
  // =================================================================================================================
  function makeEngine(kind, seed) {
    const atlas = kind !== 'window', W = atlas ? AW : LW, Pg = PROG[atlas ? 'atlas' : 'window'];
    const sd = seedState(seed);
    const E = { kind, atlas, P: Pg, W, wc: 0, bw: W >> 1, reachOn: kind !== 'noRing', seamReads: 0, maxResident: 0, loads: 0 };
    const T = (fmt, data) => tex(W, W, fmt, atlas ? null : data);
    E.st = [0, 1].map((i) => ({ p0: T('rgba32f', i ? sd.g0 : sd.p0), p1: T('rgba32f', i ? sd.g1 : sd.p1), paper: T('rgba32f', i ? sd.g2 : sd.ps) }));
    E.ws = [0, 1].map(() => ({ w0: T('rgba32f', sd.w0), w1: T('rgba32f', sd.w1), flux: tex(W, W, 'rgba16f') }));
    E.run = tex(W, W, 'rg16f'); E.bead = [tex(E.bw, E.bw, 'rgba16f'), tex(E.bw, E.bw, 'rgba16f')];
    E.in0 = tex(W, W, 'rgba16f'); E.in1 = tex(W, W, 'rgba16f');
    E.bakeTex = tex(W, W, 'rgba16f'); E.fluidBake = tex(W, W, 'r8'); E.waterBake = tex(W, W, 'r16f');
    E.delta = tex(W, W, 'rgba16f'); E.cover = tex(W, W, 'rgba16f');
    E.lookTex = tex(W, W, 'rgba8'); E.ground = tex(W, W, 'rgba8', atlas ? null : groundBytes);
    E.bakeFbo = fbo(E.bakeTex, E.fluidBake, E.waterBake); E.beadFbo = E.bead.map((b) => fbo(b));
    E.fluxFbo = [0, 1].map((i) => fbo(E.ws[i].flux, E.run));
    E.updFbo = [0, 1, 2, 3].map((i) => fbo(E.ws[i >> 1].w0, E.ws[i >> 1].w1, E.st[i & 1].p0, E.st[i & 1].p1));
    E.stFbo = E.st.map((s) => fbo(s.p0, s.p1, s.paper)); E.pFbo = E.st.map((s) => fbo(s.p0, s.p1)); E.wFbo = E.ws.map((w) => fbo(w.w0, w.w1));
    E.inFbo = fbo(E.in0, E.in1); E.deltaFbo = fbo(E.delta); E.coverFbo = fbo(E.cover); E.lookFbo = fbo(E.lookTex);
    for (const f of [E.inFbo, E.deltaFbo, E.coverFbo, E.fluxFbo[0], E.fluxFbo[1]]) { gl.bindFramebuffer(gl.FRAMEBUFFER, f); gl.viewport(0, 0, W, W); gl.clearColor(0, 0, 0, 0); gl.clear(gl.COLOR_BUFFER_BIT); }
    const r = rngf(seed + 5), cells = (k) => { const a = new Float32Array(32 * 8 * 4); for (let i = 0; i < 32 * 8; i++) { a[i * 4] = k ? 0.3 * r() : 2 * r(); a[i * 4 + 1] = k ? 0 : 1.5 * r(); a[i * 4 + 2] = k ? 0 : r(); a[i * 4 + 3] = k ? 0 : 0.4 * r(); } return a; };
    E.brush = [0, 1].map(() => [tex(32, 8, 'rgba32f', cells(0)), tex(32, 8, 'rgba32f', cells(1))]);
    E.brushFbo = E.brush.map((b) => fbo(b[0], b[1])); E.bc = 0;
    if (atlas) {
      // The stores: the layer's durable planes (what MediaWindow loads and writes back), plus state[1]'s garbage so that
      // even an accidental read of the ping-pong buffer outside a pass rect sees what the oracle sees.
      const S = (data, fmt = 'rgba32f') => tex(LW, LW, fmt, data);
      E.store = { p0: S(sd.p0), p1: S(sd.p1), paper: S(sd.ps), w0: S(sd.w0), w1: S(sd.w1), g0: S(sd.g0), g1: S(sd.g1), g2: S(sd.g2), look: S(null, 'rgba8'), ground: S(groundBytes, 'rgba8') };
      E.res = new Map(); E.slotTile = new Map();
      const order = [...Array(RESIDENT).keys()];
      if (kind !== 'orderA') { const rr = rngf(0xb2b2); for (let i = order.length - 1; i > 0; i--) { const j = Math.floor(rr() * (i + 1)); [order[i], order[j]] = [order[j], order[i]]; } }
      E.free = order;
      E.tableData = new Int32Array(GN * GN).fill(-1); E.table = tex(GN, GN, 'r32i', E.tableData);
    } else {
      gl.bindFramebuffer(gl.FRAMEBUFFER, E.bakeFbo); gl.viewport(0, 0, W, W);
      use(E.P.bake, { u_layerOrigin: [0, 0], u_layerScale: 1, u_targetSize: [W, W], u_rect: [0, 0, W, W], ...paperU }); gl.bindVertexArray(quadVao); gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
    }
    return E;
  }
  const slotXY = (s, shift) => [(s % SN) << shift, Math.floor(s / SN) << shift];
  const atlasU = (E, shift) => E.atlas ? { u_atlasTable: { tex: E.table }, u_atlasGrid: [0, 0, GN, GN], u_atlasSlotsX: SN, u_atlasShift: shift, u_atlasWrap: [0, 0, 0, 0], u_atlasWalls: [E.wallsOn ? 1 : 0, 0, 0, 0] } : {};
  const COMMON = (E) => ({ u_layerOrigin: [0, 0], u_layerScale: 1, u_targetSize: [E.W, E.W] });
  // tiles a layer-px rect touches (inclusive ranges), clamped to the layer
  function tilesOf(r) {
    const out = [];
    const tx0 = Math.max(0, Math.floor(r[0] / 256)), ty0 = Math.max(0, Math.floor(r[1] / 256)), tx1 = Math.min(GN - 1, Math.floor((Math.ceil(r[2]) - 1) / 256)), ty1 = Math.min(GN - 1, Math.floor((Math.ceil(r[3]) - 1) / 256));
    for (let ty = ty0; ty <= ty1; ty++) for (let tx = tx0; tx <= tx1; tx++) out.push(ty * GN + tx);
    return out;
  }
  const dilate = (r, d) => [r[0] - d, r[1] - d, r[2] + d, r[3] + d];
  // ---------- residency: load on demand (the per-slot load MediaAtlas does), never evicting in these strokes ----------
  function load(E, t, slot) {
    const tx = t % GN, ty = Math.floor(t / GN), lx = tx * 256, ly = ty * 256, [ax, ay] = slotXY(slot, 8);
    const planes = [[E.store.p0, E.st[0].p0], [E.store.p1, E.st[0].p1], [E.store.paper, E.st[0].paper], [E.store.g0, E.st[1].p0], [E.store.g1, E.st[1].p1], [E.store.g2, E.st[1].paper],
      [E.store.w0, E.ws[0].w0], [E.store.w1, E.ws[0].w1], [E.store.w0, E.ws[1].w0], [E.store.w1, E.ws[1].w1], [E.store.ground, E.ground]];
    for (const [s, d] of planes) blitTex(s, lx, ly, lx + 256, ly + 256, d, ax, ay);
    // The slot's working targets start from rest, as the oracle's window started (zero), and its paper is baked here.
    gl.enable(gl.SCISSOR_TEST);
    for (const f of [E.inFbo, E.deltaFbo, E.coverFbo, E.fluxFbo[0], E.fluxFbo[1]]) { gl.bindFramebuffer(gl.FRAMEBUFFER, f); gl.scissor(ax, ay, 256, 256); gl.clearColor(0, 0, 0, 0); gl.clear(gl.COLOR_BUFFER_BIT); }
    gl.disable(gl.SCISSOR_TEST);
    E.res.set(t, slot); E.slotTile.set(slot, t); E.tableData[t] = slot; E.loads++;
    gl.bindTexture(gl.TEXTURE_2D, E.table); gl.texSubImage2D(gl.TEXTURE_2D, 0, 0, 0, GN, GN, gl.RED_INTEGER, gl.INT, E.tableData);
    gl.bindFramebuffer(gl.FRAMEBUFFER, E.bakeFbo); gl.viewport(0, 0, E.W, E.W);
    use(E.P.bake, { ...COMMON(E), ...atlasU(E, 8), u_rect: [lx, ly, lx + 256, ly + 256], ...paperU }); drawRect(E, [lx, ly, lx + 256, ly + 256]);
  }
  function ensure(E, rect, reach) {
    if (!E.atlas || !rect) return;
    const tiles = tilesOf(E.reachOn ? dilate(rect, reach) : rect);
    for (const t of tiles) if (!E.res.has(t)) {
      if (!E.free.length) throw new Error('atlas full: these strokes must fit 15 slots (eviction is B3)');
      load(E, t, E.free.shift());
    }
    E.maxResident = Math.max(E.maxResident, E.res.size);
  }
  function writeBack(E) {
    for (const [t, slot] of E.res) {
      const tx = t % GN, ty = Math.floor(t / GN), lx = tx * 256, ly = ty * 256, [ax, ay] = slotXY(slot, 8);
      for (const [d, s] of [[E.store.p0, E.st[0].p0], [E.store.p1, E.st[0].p1], [E.store.paper, E.st[0].paper], [E.store.w0, E.ws[E.wc].w0], [E.store.w1, E.ws[E.wc].w1], [E.store.look, E.lookTex]])
        blitTex(s, ax, ay, ax + 256, ay + 256, d, lx, ly);
    }
  }
  // ---------- the per-slot draws ----------
  function drawRect(E, r, shift = 8) {
    if (!E.atlas) { gl.bindVertexArray(quadVao); gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4); return; }
    const side = 1 << shift, inst = [];
    for (let ty = 0; ty < GN; ty++) for (let tx = 0; tx < GN; tx++) {
      const x0 = Math.max(r[0], tx * side), y0 = Math.max(r[1], ty * side), x1 = Math.min(r[2], (tx + 1) * side), y1 = Math.min(r[3], (ty + 1) * side);
      if (x1 <= x0 || y1 <= y0) continue;
      const s = E.res.get(ty * GN + tx); if (s === undefined) continue;
      const [sx, sy] = slotXY(s, shift); inst.push(sx, sy, tx, ty, x0, y0, x1, y1);
    }
    if (!inst.length) return;
    gl.bindBuffer(gl.ARRAY_BUFFER, slotBuf); gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(inst), gl.STREAM_DRAW);
    gl.bindVertexArray(atlasQuadVao);
    const prog = gl.getParameter(gl.CURRENT_PROGRAM), nbrLoc = gl.getUniformLocation(prog, 'u_nbrSlot');
    if (nbrLoc === null) { gl.drawArraysInstanced(gl.TRIANGLE_STRIP, 0, 4, inst.length / 8); return; }
    // JB_ATLAS_NBR (MediaAtlasGl.drawRect): one slot a draw, handed its 3x3 neighbours (walls marked while they are on).
    const tileLoc = gl.getUniformLocation(prog, 'u_nbrTile');
    for (let i = 0; i < inst.length / 8; i++) {
      const tx = inst[i * 8 + 2], ty = inst[i * 8 + 3];
      gl.uniform2i(tileLoc, tx, ty); gl.uniform2iv(nbrLoc, new Int32Array(nbrOf(E, tx, ty, true)));
      for (let k = 0; k < 2; k++) gl.vertexAttribPointer(1 + k, 4, gl.FLOAT, false, 32, i * 32 + k * 16);
      gl.drawArraysInstanced(gl.TRIANGLE_STRIP, 0, 4, 1);
    }
    for (let k = 0; k < 2; k++) gl.vertexAttribPointer(1 + k, 4, gl.FLOAT, false, 32, k * 16);
  }
  // Dabs. [clip] (layer px) is the window build's scissor; the atlas draws each slot's group under that slot's own scissor.
  function drawDabs(E, dabs, box, clip) {
    if (!dabs.length) return;
    if (!E.atlas) {
      if (clip) scissorWin(E, clip);
      gl.bindBuffer(gl.ARRAY_BUFFER, dabBuf); gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(dabs.flat()), gl.STREAM_DRAW);
      gl.bindVertexArray(dabVao); gl.drawArraysInstanced(gl.TRIANGLE_STRIP, 0, 4, dabs.length); gl.disable(gl.SCISSOR_TEST); return;
    }
    const groups = new Map();   // slot -> its dab copies, dab order kept
    for (const d of dabs) {
      const b = box(d);
      for (const t of tilesOf(b)) { const s = E.res.get(t); if (s === undefined) continue; const [sx, sy] = slotXY(s, 8); if (!groups.has(s)) groups.set(s, []); groups.get(s).push(...d, sx, sy, t % GN, Math.floor(t / GN)); }
    }
    for (const [s, inst] of groups) {
      if (clip) { const t = E.slotTile.get(s); if (!scissorSlot(E, s, t, clip)) continue; }
      gl.bindBuffer(gl.ARRAY_BUFFER, dabBuf); gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(inst), gl.STREAM_DRAW);
      gl.bindVertexArray(atlasDabVao); gl.drawArraysInstanced(gl.TRIANGLE_STRIP, 0, 4, inst.length / 24);
      gl.disable(gl.SCISSOR_TEST);
    }
  }
  function scissorWin(E, r) {
    gl.enable(gl.SCISSOR_TEST);
    const x0 = Math.max(0, Math.floor(r[0])), y0 = Math.max(0, Math.floor(r[1])), x1 = Math.min(E.W, Math.ceil(r[2])), y1 = Math.min(E.W, Math.ceil(r[3]));
    gl.scissor(x0, y0, Math.max(0, x1 - x0), Math.max(0, y1 - y0));
  }
  // The part of layer rect r inside tile t, in slot s's texels; false when empty.
  function scissorSlot(E, s, t, r) {
    const tx = t % GN, ty = Math.floor(t / GN), [ax, ay] = slotXY(s, 8);
    const x0 = Math.max(Math.floor(r[0]), tx * 256), y0 = Math.max(Math.floor(r[1]), ty * 256), x1 = Math.min(Math.ceil(r[2]), tx * 256 + 256), y1 = Math.min(Math.ceil(r[3]), ty * 256 + 256);
    if (x1 <= x0 || y1 <= y0) return false;
    gl.enable(gl.SCISSOR_TEST); gl.scissor(x0 - tx * 256 + ax, y0 - ty * 256 + ay, x1 - x0, y1 - y0); return true;
  }
  function clearRect(E, f, r) {
    gl.bindFramebuffer(gl.FRAMEBUFFER, f); gl.viewport(0, 0, E.W, E.W); gl.clearColor(0, 0, 0, 0);
    if (!E.atlas) { scissorWin(E, r); gl.clear(gl.COLOR_BUFFER_BIT); gl.disable(gl.SCISSOR_TEST); return; }
    for (const [t, s] of E.res) if (scissorSlot(E, s, t, r)) { gl.clear(gl.COLOR_BUFFER_BIT); gl.disable(gl.SCISSOR_TEST); }
  }
  // Same-format, same-layout copy of layer rect [x0, y0, x1, y1) from texture a to texture b of this engine.
  function copyBlit(E, a, b, x0, y0, x1, y1) {
    if (x1 <= x0 || y1 <= y0) return;
    if (!E.atlas) { blitTex(a, x0, y0, x1, y1, b, x0, y0); return; }
    for (const [t, s] of E.res) {
      const tx = t % GN, ty = Math.floor(t / GN), [ax, ay] = slotXY(s, 8);
      const cx0 = Math.max(x0, tx * 256), cy0 = Math.max(y0, ty * 256), cx1 = Math.min(x1, tx * 256 + 256), cy1 = Math.min(y1, ty * 256 + 256);
      if (cx1 <= cx0 || cy1 <= cy0) continue;
      blitTex(a, cx0 - tx * 256 + ax, cy0 - ty * 256 + ay, cx1 - tx * 256 + ax, cy1 - ty * 256 + ay, b, cx0 - tx * 256 + ax, cy0 - ty * 256 + ay);
    }
  }
  function copyRect(E, src, dstFbo, rect) {
    gl.bindFramebuffer(gl.FRAMEBUFFER, dstFbo); gl.viewport(0, 0, E.W, E.W); gl.disable(gl.BLEND);
    use(E.P.copy, { ...COMMON(E), ...atlasU(E, 8), u_a: { tex: src.p0 }, u_b: { tex: src.p1 }, u_c: { tex: src.paper }, u_rect: rect }); drawRect(E, rect);
  }
  // ---------- the media frames (MediaLayerEngine's passes) ----------
  function wetFrame(E, sc, Fr) {
    const rect = Fr.rect, steps = Fr.steps, useInput = !!Fr.dabs;
    ensure(E, rect, P.REACH.wet);
    if (useInput) {
      gl.bindFramebuffer(gl.FRAMEBUFFER, E.inFbo); gl.viewport(0, 0, E.W, E.W);
      gl.enable(gl.BLEND); gl.blendFunc(gl.ONE, gl.ONE); gl.blendEquation(gl.FUNC_ADD);
      use(E.P.wet, { ...COMMON(E), ...atlasU(E, 8), u_w0: { tex: E.ws[E.wc].w0 }, u_paperBake: { tex: E.bakeTex }, u_pxPerMm: P.PPM, u_fullMm: P.WC.fullMm });
      drawDabs(E, Fr.dabs, (d) => [d[0] - Fr.reach, d[1] - Fr.reach, d[0] + Fr.reach, d[1] + Fr.reach], rect);
      gl.disable(gl.BLEND);
    }
    if (!rect) return;
    const beadCells = P.WC.beadMm * P.PPM, tilted = Math.hypot(sc.slope[0], sc.slope[1]) > 1e-4;
    const U = { ...P.wu, u_slope: sc.slope, u_cellMm: 1 / P.PPM, u_beadCells: beadCells, u_gran: sc.gran, ...COMMON(E), ...atlasU(E, 8), u_rect: rect };
    if (steps > 1) {
      const ring = Math.max(4, Math.ceil(P.WC.steerScale * beadCells) + 3), W = LW;
      const rx0 = Math.max(0, Math.floor(rect[0])), ry0 = Math.max(0, Math.floor(rect[1])), rx1 = Math.min(W, Math.ceil(rect[2])), ry1 = Math.min(W, Math.ceil(rect[3]));
      const ox0 = Math.max(0, rx0 - ring), oy0 = Math.max(0, ry0 - ring), ox1 = Math.min(W, rx1 + ring), oy1 = Math.min(W, ry1 + ring);
      copyBlit(E, E.st[0].p0, E.st[1].p0, ox0, oy0, ox1, ry0); copyBlit(E, E.st[0].p0, E.st[1].p0, ox0, ry1, ox1, oy1);
      copyBlit(E, E.st[0].p0, E.st[1].p0, ox0, ry0, rx0, ry1); copyBlit(E, E.st[0].p0, E.st[1].p0, rx1, ry0, ox1, ry1);
    }
    for (let k = 0; k < steps; k++) {
      const a = E.ws[E.wc], bi = 1 - E.wc, b = E.ws[bi], src = E.st[k & 1];
      if (tilted) {
        const sigma = Math.min(4.8, Math.max(1.0, 0.25 * beadCells)), r = Math.min(12, Math.ceil(2.5 * sigma)), m = 3 + 2 * r, hb = LW >> 1;
        const hr = [Math.max(0, Math.floor(rect[0] / 2) - m), Math.max(0, Math.floor(rect[1] / 2) - m), Math.min(hb, Math.ceil(rect[2] / 2) + m), Math.min(hb, Math.ceil(rect[3] / 2) + m)];
        const pass = (f, pn, s, ax, ay) => {
          gl.bindFramebuffer(gl.FRAMEBUFFER, f); gl.viewport(0, 0, E.bw, E.bw);
          use(E.P.bead, { ...COMMON(E), ...atlasU(E, 7), u_rect: hr, u_targetSize: [E.bw, E.bw], u_sigma: sigma, u_pass: pn, u_w0: { tex: a.w0 }, u_src: { tex: s }, u_axis: [ax, ay] }); drawRect(E, hr, 7);
        };
        pass(E.beadFbo[0], 0, E.bead[1], 1, 0); pass(E.beadFbo[1], 1, E.bead[0], 1, 0); pass(E.beadFbo[0], 2, E.bead[1], 0, 1);
      }
      gl.bindFramebuffer(gl.FRAMEBUFFER, E.fluxFbo[bi]); gl.viewport(0, 0, E.W, E.W);
      use(E.P.flux, { ...U, u_bead: { tex: E.bead[0] }, u_p0: { tex: src.p0 }, u_w0: { tex: a.w0 }, u_flux: { tex: a.flux }, u_paperBake: { tex: E.bakeTex }, u_fluidBake: { tex: E.fluidBake }, u_waterBake: { tex: E.waterBake } });
      drawRect(E, rect);
      gl.bindFramebuffer(gl.FRAMEBUFFER, E.updFbo[bi * 2 + 1 - (k & 1)]); gl.viewport(0, 0, E.W, E.W);
      use(E.P.update, { ...U, u_w0: { tex: a.w0 }, u_w1: { tex: a.w1 }, u_flux: { tex: b.flux }, u_run: { tex: E.run }, u_p0: { tex: src.p0 }, u_p1: { tex: src.p1 },
        u_paperBake: { tex: E.bakeTex }, u_waterBake: { tex: E.waterBake }, u_fluidBake: { tex: E.fluidBake }, u_in0: { tex: E.in0 }, u_in1: { tex: E.in1 }, u_useInput: (k === 0 && useInput) ? 1 : 0 });
      drawRect(E, rect);
      E.wc = bi;
    }
    if ((steps & 1) === 1) copyRect(E, E.st[1], E.pFbo[0], rect);
    if (useInput) clearRect(E, E.inFbo, rect);
  }
  const DRY = { u_lutMaxMm: 0.2, u_pxPerMm: P.PPM, u_toothMm: 0.09, u_dirStrength: 0.8, u_dustRate: 0.004, u_clump: 0.6, u_pileRange: [0.3, 0.8], u_crushStart: 0.02,
    u_conform: 0.5, u_transferExp: 1.6, u_leadSoft: 0.25, u_plateau: 1.8 };
  function dryFrame(E, sc, Fr) {
    const rect = Fr.rect; if (!rect) return;
    const smearPx = sc.smearMm * P.PPM;
    ensure(E, rect, Math.ceil(smearPx) + 4);
    gl.bindFramebuffer(gl.FRAMEBUFFER, E.deltaFbo); gl.viewport(0, 0, E.W, E.W);
    gl.enable(gl.BLEND); gl.blendFunc(gl.ONE, gl.ONE); gl.blendEquation(gl.FUNC_ADD);
    use(E.P.dry, { ...COMMON(E), ...paperU, ...DRY, ...sc.stick, ...atlasU(E, 8), u_crush: { tex: E.st[0].paper }, u_contactLut: { tex: lutTex } });
    drawDabs(E, Fr.dabs, (d) => { const ext = (Math.hypot(Math.max(Math.abs(d[4]), Math.abs(d[5])), d[6]) + 0.1) * P.PPM + 2; return [d[0] - ext, d[1] - ext, d[0] + ext, d[1] + ext]; }, null);
    gl.disable(gl.BLEND);
    gl.bindFramebuffer(gl.FRAMEBUFFER, E.stFbo[1]); gl.viewport(0, 0, E.W, E.W);
    use(E.P.apply, { ...COMMON(E), ...atlasU(E, 8), u_p0: { tex: E.st[0].p0 }, u_p1: { tex: E.st[0].p1 }, u_paperState: { tex: E.st[0].paper }, u_delta: { tex: E.delta },
      u_capMm: 0.012, u_abrasion: 900, u_flakeR: 0.08, u_crushRate: 40, u_crushMax: 0.6, u_smear: 6, u_smearPx: [Fr.travel[0] * smearPx, Fr.travel[1] * smearPx], u_rect: rect });
    drawRect(E, rect);
    copyRect(E, E.st[1], E.stFbo[0], rect);
    clearRect(E, E.deltaFbo, rect);
  }
  function eraseFrame(E, sc, Fr) {
    const rect = Fr.rect; if (!rect) return;
    ensure(E, rect, P.REACH.erase);
    gl.bindFramebuffer(gl.FRAMEBUFFER, E.coverFbo); gl.viewport(0, 0, E.W, E.W);
    gl.enable(gl.BLEND); gl.blendFunc(gl.ONE, gl.ONE); gl.blendEquation(gl.MAX);
    use(E.P.erase, { ...COMMON(E), ...atlasU(E, 8) });
    drawDabs(E, Fr.dabs, (d) => { const e = d[2] + 2.5; return [d[0] - e, d[1] - e, d[0] + e, d[1] + e]; }, null);
    gl.blendEquation(gl.FUNC_ADD); gl.disable(gl.BLEND);
    gl.bindFramebuffer(gl.FRAMEBUFFER, E.stFbo[1]); gl.viewport(0, 0, E.W, E.W);
    use(E.P.eraseApply, { ...COMMON(E), ...atlasU(E, 8), u_a: { tex: E.st[0].p0 }, u_b: { tex: E.st[0].p1 }, u_c: { tex: E.st[0].paper }, u_cover: { tex: E.cover }, u_water: 0, u_rect: rect });
    drawRect(E, rect);
    copyRect(E, E.st[1], E.stFbo[0], rect);
    const a = E.ws[E.wc], bi = 1 - E.wc;
    gl.bindFramebuffer(gl.FRAMEBUFFER, E.wFbo[bi]); gl.viewport(0, 0, E.W, E.W);
    use(E.P.eraseApply, { ...COMMON(E), ...atlasU(E, 8), u_a: { tex: a.w0 }, u_b: { tex: a.w1 }, u_c: { tex: E.st[0].paper }, u_cover: { tex: E.cover }, u_water: 1, u_rect: rect });
    drawRect(E, rect);
    copyBlit(E, E.ws[bi].w0, a.w0, rect[0], rect[1], rect[2], rect[3]); copyBlit(E, E.ws[bi].w1, a.w1, rect[0], rect[1], rect[2], rect[3]);
    clearRect(E, E.coverFbo, rect);
  }
  const PASTE = { u_pxPerMm: P.PPM, u_toothMm: 0.09, u_thick: 0.45, u_scrape: 0.35, u_hairDepth: 0.3, u_ridge: 0.18, u_rate: 0.5, u_mix: 0.35, u_swap: 0.2,
    u_cellCap: 0.4, u_bow: 0.2, u_lump: 0.1, u_fillDips: 0.5, u_fingers: 0.35, u_rag: 0.3, u_arc: 0.2, u_sparse: 0.1, u_wick: 0.08, u_active: 1 };
  function pasteFrame(E, sc, Fr) {
    for (const s of Fr.steps) {
      if (!s.rect) continue;
      ensure(E, s.rect, P.REACH.paste);
      const U = { ...COMMON(E), ...PASTE, u_halfW: sc.halfW, u_lenMax: sc.lenMax, u_len: s.len, u_shape: sc.shape, u_blade: sc.blade, u_rigid: sc.blade, u_pressure: s.pressure,
        u_center: s.center, u_wDir: s.wDir, u_lDir: s.lDir, u_travel: s.travel, u_bladeDir: s.bladeDir, u_slideMm: 0.5, u_paperBake: { tex: E.bakeTex }, u_layerSize: [LW, LW],
        u_bladeLen: sc.lenMax, u_bladeHalfW: sc.halfW, u_bladeH0: 0.02 + 0.06 * (1 - s.pressure), u_bladeTan: 0.15, u_bladeBead: 0.3, u_bladePressMm: 0.03 * s.pressure,
        u_bladeFilm: 0.008, u_bladeLoad: 0.6, u_seed: sc.seed };
      const nb = 1 - E.bc;
      gl.bindFramebuffer(gl.FRAMEBUFFER, E.brushFbo[nb]); gl.viewport(0, 0, 32, 8);
      use(E.P.pasteBrush, { ...U, ...atlasU(E, 8), u_targetSize: [32, 8], u_rect: [0, 0, 32, 8], u_p0: { tex: E.st[0].p0 }, u_p1: { tex: E.st[0].p1 },
        u_brush0: { tex: E.brush[E.bc][0] }, u_brush1: { tex: E.brush[E.bc][1] } });
      gl.bindVertexArray(quadVao); gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
      E.bc = nb;
      gl.bindFramebuffer(gl.FRAMEBUFFER, E.pFbo[1]); gl.viewport(0, 0, E.W, E.W);
      use(E.P.pasteDab, { ...U, ...atlasU(E, 8), u_p0: { tex: E.st[0].p0 }, u_p1: { tex: E.st[0].p1 }, u_brush0: { tex: E.brush[E.bc][0] }, u_brush1: { tex: E.brush[E.bc][1] }, u_rect: s.rect });
      if (E.atlas) drawRect(E, s.rect); else { scissorWin(E, s.rect); drawRect(E, s.rect); gl.disable(gl.SCISSOR_TEST); }
      copyRect(E, E.st[1], E.pFbo[0], s.rect);
    }
  }
  const LOOK_RECT = [336, 336, 944, 944];
  function renderLook(E) {
    ensure(E, LOOK_RECT, P.REACH.look);
    gl.bindFramebuffer(gl.FRAMEBUFFER, E.lookFbo); gl.viewport(0, 0, E.W, E.W); gl.disable(gl.BLEND);
    const w = E.ws[E.wc], lamp = [-0.55, 0.55, 0.62], ln = Math.hypot(...lamp);
    const U = { ...COMMON(E), ...paperU, ...atlasU(E, 8), u_p0: { tex: E.st[0].p0 }, u_p1: { tex: E.st[0].p1 }, u_paperState: { tex: E.st[0].paper }, u_ground: { tex: E.ground }, u_paperBake: { tex: E.bakeTex },
      u_w0: { tex: w.w0 }, u_w1: { tex: w.w1 }, u_zoom: 1, u_pxPerMm: P.PPM, u_toothMm: 0.09, u_paperColor: [0.95, 0.93, 0.88], u_lamp: lamp.map(v => v / ln),
      u_relief: 0, u_sheen: 0.06, u_capMm: 0.004, u_mode: 0, u_impasto: 1.4, u_look: 1, u_targetSize: [LW, LW] };
    gl.bindVertexArray(emptyVao);
    if (!E.atlas) { use(E.P.render, { ...U, u_pan: [0, 0] }); scissorWin(E, LOOK_RECT); gl.drawArrays(gl.TRIANGLES, 0, 3); gl.disable(gl.SCISSOR_TEST); return; }
    // Per slot: the slot's texels map back to layer px through u_pan (gl_FragCoord − pan = layer px; exact integers).
    for (const [t, s] of E.res) {
      if (!scissorSlot(E, s, t, LOOK_RECT)) continue;
      const tx = t % GN, ty = Math.floor(t / GN), [ax, ay] = slotXY(s, 8);
      use(E.P.render, { ...U, u_pan: [ax - tx * 256, ay - ty * 256], u_nbrTile: [tx, ty], u_nbrSlot: nbrOf(E, tx, ty) }); gl.drawArrays(gl.TRIANGLES, 0, 3); gl.disable(gl.SCISSOR_TEST);
    }
  }
  // MediaAtlasGl.neighbours: the look's 3x3 neighbour slots (JB_ATLAS_NBR), x = -1 where a tile is not here.
  function nbrOf(E, tx, ty, withWalls = false) {
    const o = [];
    for (let dy = -1; dy <= 1; dy++) for (let dx = -1; dx <= 1; dx++) {
      const x = tx + dx, y = ty + dy, inGrid = x >= 0 && y >= 0 && x < GN && y < GN, s = inGrid ? E.res.get(y * GN + x) : undefined;
      if (withWalls && E.wallsOn && E.walls && inGrid && E.walls.has(y * GN + x)) o.push(-2, -2);
      else if (s === undefined) o.push(-1, -1); else o.push(...slotXY(s, 8));
    }
    return o;
  }
  // ---------- readback ----------
  const rf = gl.createFramebuffer();
  function readF(texId) { gl.bindFramebuffer(gl.FRAMEBUFFER, rf); gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, texId, 0); const a = new Float32Array(LW * LW * 4); gl.readPixels(0, 0, LW, LW, gl.RGBA, gl.FLOAT, a); return new Uint32Array(a.buffer); }
  function readB(texId, x0, y0, x1, y1) { gl.bindFramebuffer(gl.FRAMEBUFFER, rf); gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, texId, 0); const a = new Uint8Array((x1 - x0) * (y1 - y0) * 4); gl.readPixels(x0, y0, x1 - x0, y1 - y0, gl.RGBA, gl.UNSIGNED_BYTE, a); return a; }
  const planesOf = (E) => E.atlas ? { p0: E.store.p0, p1: E.store.p1, paper: E.store.paper, w0: E.store.w0, w1: E.store.w1 }
    : { p0: E.st[0].p0, p1: E.st[0].p1, paper: E.st[0].paper, w0: E.ws[E.wc].w0, w1: E.ws[E.wc].w1 };
  function diffPx(a, b) { let n = 0; for (let i = 0; i < a.length; i += 4) if (a[i] !== b[i] || a[i + 1] !== b[i + 1] || a[i + 2] !== b[i + 2] || a[i + 3] !== b[i + 3]) n++; return n; }
  function compareState(O, A, m) {
    if (A.atlas) writeBack(A);
    const po = planesOf(O), pa = planesOf(A); let total = 0;
    for (const k of Object.keys(po)) {
      const a = readF(po[k]), b = readF(pa[k]), d = diffPx(a, b); m[k] = (m[k] || 0) + d; total += d;
      if (d && !(m.where || []).length) { m.where = []; const fa = new Float32Array(a.buffer), fb = new Float32Array(b.buffer);
        for (let i = 0; i < a.length && m.where.length < 4; i += 4) if (a[i] !== b[i] || a[i + 1] !== b[i + 1] || a[i + 2] !== b[i + 2] || a[i + 3] !== b[i + 3]) {
          const px = i / 4; m.where.push({ plane: k, x: px % LW, y: Math.floor(px / LW), oracle: Array.from(fa.slice(i, i + 4)), atlas: Array.from(fb.slice(i, i + 4)) }); } }
    }
    m.total += total; return total;
  }
  // ---------- the run: each atlas kind against its own oracle (one pair alive at a time) ----------
  const results = [];
  for (const sc of P.scenarios) {
    const r = { name: sc.name, medium: sc.kind, frames: sc.frames.length, mismatch: {}, changed: 0, maxResident: 0, seamReads: 0, loads: {} };
    try {
      for (const kind of P.KINDS) {
        OWNED = { tex: [], fbo: [] };
        const O = makeEngine('window', sc.seed * 101 + 7), A = makeEngine(kind, sc.seed * 101 + 7), m = { total: 0, checkpoints: 0 };
        const first = kind === P.KINDS[0] ? planesOf(O) : null, firstData = first ? Object.fromEntries(Object.entries(first).map(([k, t]) => [k, readF(t)])) : null;
        for (let fi = 0; fi < sc.frames.length; fi++) {
          const Fr = sc.frames[fi];
          for (const e of [O, A]) { if (sc.kind === 'wet') wetFrame(e, sc, Fr); else if (sc.kind === 'dry') dryFrame(e, sc, Fr); else if (sc.kind === 'erase') eraseFrame(e, sc, Fr); else pasteFrame(e, sc, Fr); }
          if (fi % 4 === 3 || fi === sc.frames.length - 1) { compareState(O, A, m); m.checkpoints++; }
          const ge = gl.getError(); if (ge !== gl.NO_ERROR) throw new Error('GL error 0x' + ge.toString(16) + ' at frame ' + fi + ' (' + kind + ')');
        }
        renderLook(O); renderLook(A);
        writeBack(A);
        const lo = readB(O.lookTex, ...LOOK_RECT), la = readB(A.store.look, ...LOOK_RECT), ld = diffPx(lo, la);
        m.look = ld; m.total += ld;
        r.mismatch[kind] = m; r.loads[kind] = A.loads;
        if (kind !== 'noRing') r.maxResident = Math.max(r.maxResident, A.maxResident);
        if (kind === 'orderB') { let s = 0; for (const [t] of A.res) { const tx = t % GN, ty = Math.floor(t / GN), sl = A.res.get(t); for (const [dx, dy] of [[1, 0], [0, 1]]) { const n = A.res.get((ty + dy) * GN + tx + dx); if (n !== undefined && n !== sl + dx + dy * 4) s++; } } r.seamReads = s; }
        if (firstData) { const last = planesOf(O); r.changed = Object.keys(last).reduce((s, k) => s + diffPx(firstData[k], readF(last[k])), 0); }
        gl.bindFramebuffer(gl.FRAMEBUFFER, null);
        for (const t of OWNED.tex) gl.deleteTexture(t);
        for (const f of OWNED.fbo) gl.deleteFramebuffer(f);
        OWNED = null;
      }
    } catch (e) { r.error = String(e.message || e); if (OWNED) { for (const t of OWNED.tex) gl.deleteTexture(t); for (const f of OWNED.fbo) gl.deleteFramebuffer(f); OWNED = null; } }
    results.push(r);
  }
  return { results, missingUniforms: [...missing].sort() };
}
