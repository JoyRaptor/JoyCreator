// joybrush/tools/media_atlas_parity_check.js — Media Atlas v2, slice B1 (tasks/joybrush/specs/MEDIA_ATLAS_V2_20261016.md):
// every media pass through the page-table address functions (jb_media_atlas.glsl), drawn one instance per slot, with the
// IDENTITY layout (the atlas IS today's window), against today's window engine.
//
// Four engines are driven through the same seeded state and the same strokes, every medium, many frames:
//   old    = the shaders of <base> (git show), today's window pass sequence (JS port of MediaLayerEngine's passes);
//   new    = this tree's shaders WITHOUT JB_ATLAS (the window and lab build), the same sequence;
//   atlas  = this tree's shaders WITH JB_ATLAS, every layer-shaped pass drawn as one instance per slot (its part of the
//            pass rect), every dab drawn once per slot it touches, page table = identity;
//   broken = atlas with the page table off by one tile (tile x reads slot x+1): the control that MUST be detected.
// After every frame the full float state (raw bits, all channels) and the RGBA8 look are compared:
//   atlas vs new: 0 differing pixels required (bit-exact);  broken vs new: > 0 required;
//   new vs old: reported (the window build's one-time change: fragment-derived cell centres and the bead's hand bilinear).
// The window is SIZE² (default 512 = 2×2 slots of 256, so every stroke, pass rect and neighbour read crosses a slot seam).
//
// Run: NODE_PATH=<dir with playwright-core> node joybrush/tools/media_atlas_parity_check.js [--base <ref>] [--size 512]
//        [--chrome <exe>] [--quick]
// Prints one JSON document; exit 0 only on pass. SwiftShader is software GL: this proves the addressing, not Adreno timing.
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');
let chromium;
try { chromium = require('playwright-core').chromium; } catch (e) {
  console.log(JSON.stringify({ pass: false, error: 'missing dependency playwright-core: ' + e.message })); process.exit(1);
}
const ROOT = path.join(__dirname, '..');
const SHADER_DIR = path.join(ROOT, 'shaders');
function argVal(name, dflt) { const i = process.argv.indexOf('--' + name); return i >= 0 && i + 1 < process.argv.length ? process.argv[i + 1] : dflt; }
const git = (...a) => execFileSync('git', ['-C', ROOT, ...a], { encoding: 'utf8', maxBuffer: 1 << 26 }).trim();
const BASE = argVal('base', (() => { try { return git('merge-base', 'HEAD', 'origin/lead/claude-main'); } catch (e) { return 'HEAD'; } })());
const SIZE = parseInt(argVal('size', '512'), 10);
const QUICK = process.argv.includes('--quick');
if (SIZE % 256 !== 0 || SIZE < 512) { console.log(JSON.stringify({ pass: false, error: '--size must be a multiple of 256, at least 512' })); process.exit(1); }
const PPM = 12;            // doc px per mm (layer scale 1)
const K = SIZE / 256;      // scenario scale (the strokes were laid out for 256)

// ---- shaders: base from git, tree from disk; the Android loader's include rule; JB_ATLAS injected after #version ----
const INCLUDE_RE = /^#include\s+"([^"]+)"$/;
function expand(rel, read, seen = new Set()) {
  if (seen.has(rel)) return '';
  seen.add(rel);
  const text = read(rel).replace(/\r\n/g, '\n');
  return text.split('\n').map(l => { const m = l.trim().match(INCLUDE_RE); return m ? expand(m[1], read, seen) : l; }).join('\n');
}
const readNew = (rel) => fs.readFileSync(path.join(SHADER_DIR, rel), 'utf8');
const readOld = (rel) => git('show', BASE + ':joybrush/shaders/' + rel) + '\n';
const define = (src, ...names) => src.replace(/^(#version[^\n]*\n)/, (v) => v + names.map(n => '#define ' + n + '\n').join(''));
const FILES = { quad: 'media/jb_media_quad.vert', flux: 'media/jb_wet_flux.frag', update: 'media/jb_wet_update.frag', copy: 'media/jb_media_copy.frag',
  bead: 'media/jb_wet_bead.frag', wetV: 'media/jb_wet_dab.vert', wetF: 'media/jb_wet_dab.frag', bake: 'media/jb_media_bake.frag',
  screen: 'media/jb_media_screen.vert', render: 'media/jb_media_render.frag', dryV: 'media/jb_dry_dab.vert', dryF: 'media/jb_dry_dab.frag',
  apply: 'media/jb_media_apply.frag', eraseV: 'media/jb_media_erase_dab.vert', eraseF: 'media/jb_media_erase_dab.frag',
  eraseApply: 'media/jb_media_erase_apply.frag', pasteDab: 'media/jb_paste_dab.frag', pasteBrush: 'media/jb_paste_brush.frag' };
function srcSet(read, ...defs) {
  const o = {}; for (const [k, f] of Object.entries(FILES)) o[k] = defs.length ? define(expand(f, read), ...defs) : expand(f, read);
  o.quadPlain = defs.length ? define(expand(FILES.quad, read), ...defs, 'JB_PLAIN_TARGET') : o.quad;   // the paste brush's cells
  return o;
}
const SRC = { old: srcSet(readOld), new: srcSet(readNew), atlas: srcSet(readNew, 'JB_ATLAS') };
if (!/JB_ATLAS/.test(SRC.new.flux)) { console.log(JSON.stringify({ pass: false, error: 'tree shaders do not use jb_media_atlas.glsl' })); process.exit(1); }

// ---- wet constants (WetConstants defaults, wetUniforms for a mid paper) ----
function wetUniformMap(wc) {
  const sizing = 0.5, absorbency = 1, capacity = 1, wickSpeed = 1, tooth = 0.09, heightMean = 0.5;
  return {
    u_dt: wc.dt, u_flow: wc.flow, u_keep: wc.keep, u_pinMm: wc.pinMm * (0.4 + sizing), u_dampS: wc.dampS, u_minMm: wc.minMm, u_evap: wc.evap, u_edgeEvap: wc.edgeEvap,
    u_absorb: wc.absorb * absorbency * 2 * (1 - 0.95 * sizing), u_capMm: wc.capMm * capacity * 2, u_wick: Math.min(0.24 / wc.dt, wc.wick * wickSpeed * 2),
    u_sDry: wc.sDry, u_settle: wc.settle, u_lift: wc.lift, u_stainCarry: wc.stainCarry, u_toothMm: tooth,
    u_runMmPerS: wc.runMmPerS, u_runMm: wc.runMm, u_runHoldMm: wc.runHold * tooth, u_runFilmMm: wc.runFilm, u_runPin: wc.runPin, u_runSizing: wc.runSizing,
    u_dripGapMm: wc.dripGap, u_quietMm: wc.quiet, u_runHoldK: wc.runHoldK, u_runCoherent: wc.runCoherent, u_runCohere: wc.runCohere,
    u_steerScale: wc.steerScale, u_runSteer: wc.runSteer, u_runAlong: wc.runAlong, u_heightMean: heightMean, u_fullMm: wc.fullMm, u_filmMm: wc.filmMm,
    u_edgeDep: wc.edgeDep, u_mingle: Math.min(0.24 / wc.dt, wc.mingle),
  };
}
const WC = { dt: 1 / 120, substeps: 4, flow: 30, keep: 0.9, pinMm: 0.22, dampS: 0.35, minMm: 0.002, evap: 0.003, edgeEvap: 4, absorb: 0.25, capMm: 0.08, wick: 5,
  sDry: 0.03, settle: 0.08, lift: 0.04, stainCarry: 0.12, filmMm: 0.12, edgeDep: 5, mingle: 12, fullMm: 0.4, runMmPerS: 8, runMm: 0.3, runHold: 0.5, runFilm: 0.012,
  runCohere: 0.3, runCoherent: 1, steerScale: 1, runHoldK: 1, quiet: 0.01, dripGap: 4, runSizing: 0.45, runPin: 0.5, runSteer: 4, runAlong: 0.25, beadMm: 0.6 };

// ---- rectangles (MediaLayerEngine.layerRect, WetSpread.grow) ----
// Every pass rectangle stays MARGIN px inside the window, for all four engines alike. At the window's own edge the window
// build clamps a read to its edge texel and the atlas reads a non-resident tile as zero: a deliberate difference (B2 removes
// the window edge), not what B1 measures. 96 px is more than any pass's read reach (bead blur 12 half cells past a
// 27-cell margin, drips 48 px, look shadow 53 px).
const MARGIN = 96;
function layerRect(r, padDoc) {
  const x0 = Math.max(MARGIN, Math.floor(r[0] - padDoc)), y0 = Math.max(MARGIN, Math.floor(r[1] - padDoc));
  const x1 = Math.min(SIZE - MARGIN, Math.ceil(r[2] + padDoc)), y1 = Math.min(SIZE - MARGIN, Math.ceil(r[3] + padDoc));
  return x1 > x0 && y1 > y0 ? [x0, y0, x1, y1] : null;
}
const union = (a, b) => a ? [Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.max(a[2], b[2]), Math.max(a[3], b[3])] : b.slice();
function grow(rect, paint, sx, sy, steps) {
  if (!paint || Math.hypot(sx, sy) <= 1e-4) return rect.slice();
  const cellMm = 1 / PPM, g = Math.ceil(steps * 0.45) + 2, far = Math.ceil(40 / cellMm), near = Math.ceil(10 / cellMm);
  const reach = (d) => d > 1e-4 ? far : near;
  return [Math.min(rect[0], Math.max(MARGIN, Math.max(paint[0] - reach(-sx), rect[0] - g))), Math.min(rect[1], Math.max(MARGIN, Math.max(paint[1] - reach(-sy), rect[1] - g))),
    Math.max(rect[2], Math.min(SIZE - MARGIN, Math.min(paint[2] + reach(sx), rect[2] + g))), Math.max(rect[3], Math.min(SIZE - MARGIN, Math.min(paint[3] + reach(sy), rect[3] + g)))];
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
const S = (v) => v * K;   // a 256-layout coordinate at this window size

// ---- wet scenarios ----
const BRUSHES = {
  Round: { along: 3.0, half: 2.0, wpm: 0.09, wet: 0.95, K: [5.0, 2.0, 0.6], S: 0.2, dry: 0.0, sol: 0.7 },
  Wash: { along: 4.5, half: 4.0, wpm: 0.1, wet: 1.0, K: [1.2, 3.5, 4.5], S: 0.15, dry: 0.0, sol: 0.8 },
  Thirsty: { along: 3.0, half: 2.0, wpm: 0.05, wet: 0.08, K: [0, 0, 0], S: 0, dry: 0.9, sol: 0.0 },
};
function wetDabs(brush, pts, slideMm, seedNo) {
  const b = BRUSHES[brush], out = []; let dirty = null;
  for (const p of pts) {
    out.push([p.x, p.y, p.dx, p.dy, b.along, b.half, b.wpm, b.wet, b.K[0], b.K[1], b.K[2], b.S, slideMm, b.dry, b.sol, seedNo, 0, 0, 0, 0]);
    const r = (b.along + 0.1) * PPM + 2, box = [p.x - r, p.y - r, p.x + r, p.y + r];
    dirty = union(dirty, box);
  }
  return { dabs: out, dirty, reach: (Math.hypot(b.along, b.half) + 0.08) * PPM + 1 };
}
function buildWet(sc) {
  const frames = []; let wetRect = null, paintRect = null;
  for (let f = 0; f < sc.frames; f++) {
    const steps = sc.steps ? sc.steps(f) : WC.substeps;
    const fr = { steps, dabs: null, reach: 0, evap: null, dryNow: false, rect: null };
    let batch = null;
    if (f < sc.strokeFrames) {
      const t0 = f / sc.strokeFrames, t1 = (f + 1) / sc.strokeFrames;
      batch = wetDabs(sc.brush, pathPts(S(sc.from[0]), S(sc.from[1]), S(sc.to[0]), S(sc.to[1]), S(sc.bend), t0, t1, 3), 0.55 * (f === 0 ? 3 : 1), 3);
    } else if (sc.thirsty && f >= sc.thirsty[0] && f < sc.thirsty[1]) {
      const k = f - sc.thirsty[0], m = sc.thirsty[1] - sc.thirsty[0];
      batch = wetDabs('Thirsty', pathPts(S(sc.thirstyFrom[0]), S(sc.thirstyFrom[1]), S(sc.thirstyTo[0]), S(sc.thirstyTo[1]), 0, k / m, (k + 1) / m, 3), 0.6, 9);
    }
    if (batch) {
      fr.dabs = batch.dabs; fr.reach = batch.reach;
      const r = layerRect(batch.dirty, 48);
      if (r) { wetRect = union(wetRect, r); paintRect = union(paintRect, r); }
    }
    if (f === sc.dryNowAt) { fr.dryNow = true; fr.steps = 1; fr.evap = 1e4; }
    fr.rect = wetRect ? grow(wetRect, paintRect, sc.slope[0], sc.slope[1], fr.steps) : null;
    if (fr.rect) wetRect = fr.rect;
    if (fr.dryNow) { wetRect = null; paintRect = null; }
    frames.push(fr);
  }
  return { kind: 'wet', name: sc.name, slope: sc.slope, gran: sc.gran || 0, seed: sc.seed, frames };
}

// ---- dry stick strokes (stick-frame zones as jb_stick.glsl reads them; MediaLayerEngine.dryFrame's passes) ----
function buildDry(sc) {
  const frames = [];
  for (let f = 0; f < sc.frames; f++) {
    const pts = pathPts(S(sc.from[0]), S(sc.from[1]), S(sc.to[0]), S(sc.to[1]), S(sc.bend), f / sc.frames, (f + 1) / sc.frames, 6);
    const dabs = []; let dirty = null;
    pts.forEach((p, i) => {
      const P = 0.25 + 0.65 * ((f * 6 + i) % 11) / 10, lean = [Math.cos(sc.lean), Math.sin(sc.lean)];
      const xMin = -0.6, xMax = sc.side ? 4.0 : 1.2, yMax = sc.side ? 1.1 : 0.7, D = 0.03 + 0.07 * P;
      dabs.push([p.x, p.y, lean[0], lean[1], xMin, xMax, yMax, D, 0.45, 1.6, 3.4, 0.8, 1, 0.6, 0.35, 0.12, p.dx, p.dy, 0.4, P]);
      const v = [-lean[1], lean[0]], cs = [];
      for (const [qx, qy] of [[xMin - 0.06, -yMax - 0.06], [xMax + 0.06, -yMax - 0.06], [xMin - 0.06, yMax + 0.06], [xMax + 0.06, yMax + 0.06]])
        cs.push([p.x + (qx * lean[0] + qy * v[0]) * PPM, p.y + (qx * lean[1] + qy * v[1]) * PPM]);
      const box = [Math.min(...cs.map(c => c[0])), Math.min(...cs.map(c => c[1])), Math.max(...cs.map(c => c[0])), Math.max(...cs.map(c => c[1]))];
      dirty = union(dirty, box);
    });
    const travel = [pts[pts.length - 1].dx, pts[pts.length - 1].dy];
    frames.push({ dabs, travel, rect: layerRect(dirty, sc.smearMm * PPM + 2) });
  }
  return { kind: 'dry', name: sc.name, seed: sc.seed, smearMm: sc.smearMm, frames };
}

// ---- eraser on a media layer (MediaLayerEngine's erase passes: cover, state, water) ----
function buildErase(sc) {
  const frames = [];
  for (let f = 0; f < sc.frames; f++) {
    const pts = pathPts(S(sc.from[0]), S(sc.from[1]), S(sc.to[0]), S(sc.to[1]), S(sc.bend), f / sc.frames, (f + 1) / sc.frames, 5);
    const r = sc.radius * K, dabs = pts.map((p, i) => [p.x, p.y, r, 0.4 + 0.6 * ((f + i) % 4) / 3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]);
    let dirty = null; for (const p of pts) dirty = union(dirty, [p.x - r - 3, p.y - r - 3, p.x + r + 3, p.y + r + 3]);
    frames.push({ dabs, rect: layerRect(dirty, 0) });
  }
  return { kind: 'erase', name: sc.name, seed: sc.seed, frames };
}

// ---- oil paste: the brush-cell pass then the canvas pass (MediaLayerEngine's paste step) ----
function buildPaste(sc) {
  const frames = [];
  const halfW = sc.halfW, lenMax = sc.lenMax;
  for (let f = 0; f < sc.frames; f++) {
    const pts = pathPts(S(sc.from[0]), S(sc.from[1]), S(sc.to[0]), S(sc.to[1]), S(sc.bend), f / sc.frames, (f + 1) / sc.frames, 2);
    const steps = pts.map((p, i) => {
      const t = [p.dx, p.dy], w = [-p.dy, p.dx], pressure = 0.35 + 0.5 * ((f * 2 + i) % 7) / 6;
      const half = (Math.hypot(halfW, lenMax) + 0.3) * PPM + 2;
      return { center: [p.x, p.y], wDir: w, lDir: [-t[0], -t[1]], travel: t, bladeDir: w, pressure, len: lenMax * (0.4 + 0.6 * pressure),
        rect: layerRect([p.x - half, p.y - half, p.x + half, p.y + half], 0) };
    });
    frames.push({ steps });
  }
  return { kind: 'paste', name: sc.name, seed: sc.seed, shape: sc.shape, blade: sc.blade, halfW, lenMax, frames };
}

const FLAT = [0, 0], F = (n) => QUICK ? Math.max(4, n >> 2) : n;
const SCENARIOS = [
  buildWet({ name: 'wet Round flat (crosses both seams)', brush: 'Round', from: [40, 60], to: [210, 190], bend: 24, frames: F(22), strokeFrames: F(12), slope: FLAT, seed: 11, thirsty: [F(8), F(11)], thirstyFrom: [100, 140], thirstyTo: [160, 90] }),
  buildWet({ name: 'wet Wash tilted (bead, flux steer, ring)', brush: 'Wash', from: [60, 50], to: [200, 140], bend: -14, frames: F(22), strokeFrames: F(10), slope: [0.25, 0.45], seed: 13, gran: 1 }),
  buildWet({ name: 'wet Round reversed tilt + odd substeps + dryNow', brush: 'Round', from: [70, 200], to: [190, 70], bend: 18, frames: F(20), strokeFrames: F(9), slope: [-0.4, -0.2], seed: 15,
    steps: (f) => (f % 5 === 3 ? 3 : f % 7 === 5 ? 1 : 4), dryNowAt: F(16) }),
  buildDry({ name: 'dry point stick across the seam', from: [50, 120], to: [215, 140], bend: 20, frames: F(16), seed: 21, lean: 0.6, side: false, smearMm: 0.4 }),
  buildDry({ name: 'dry side stick, smear, corner', from: [200, 50], to: [60, 210], bend: -25, frames: F(16), seed: 22, lean: 2.3, side: true, smearMm: 0.9 }),
  buildErase({ name: 'eraser across both seams', from: [60, 80], to: [200, 190], bend: 12, frames: F(10), seed: 31, radius: 9 }),
  buildPaste({ name: 'oil round brush across the seam', from: [60, 100], to: [200, 160], bend: 15, frames: F(14), seed: 41, shape: 0, blade: 0, halfW: 2.2, lenMax: 7 }),
  buildPaste({ name: 'palette knife (blade) across the corner', from: [190, 70], to: [70, 190], bend: -10, frames: F(12), seed: 42, shape: 2, blade: 1, halfW: 3.0, lenMax: 5 }),
];

(async () => {
  const exe = (() => {
    const c = argVal('chrome', process.env.CHROME || null);
    if (c && fs.existsSync(c)) return c;
    const edge = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';
    return fs.existsSync(edge) ? edge : null;
  })();
  if (!exe) { console.log(JSON.stringify({ pass: false, error: 'no browser: pass --chrome <exe> or set CHROME' })); process.exit(1); }
  const browser = await chromium.launch({ executablePath: exe, args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'] });
  let out;
  try {
    const page = await browser.newPage();
    page.on('console', (m) => { if (m.type() === 'error') console.error('[page]', m.text()); });
    out = await page.evaluate(PAGE, { W: SIZE, PPM, SRC, wu: wetUniformMap(WC), WC, scenarios: SCENARIOS });
  } finally {
    await browser.close();
  }
  if (out.error) { console.log(JSON.stringify({ pass: false, base: BASE, ...out })); process.exit(1); }
  let atlasBad = 0, brokenSeen = 0, errors = 0, compared = 0;
  const brokenByScenario = {};
  for (const r of out.results) {
    if (r.error) errors++;
    atlasBad += r.mismatch.atlas.total; compared += r.pixelsCompared;
    brokenSeen += r.mismatch.broken.total; brokenByScenario[r.name] = r.mismatch.broken.total;
  }
  const nonvacuous = out.results.every(r => r.changed > 200 && r.lookDistinct > 20);
  const pass = errors === 0 && atlasBad === 0 && brokenSeen > 0 && nonvacuous;
  console.log(JSON.stringify({ pass, base: BASE, size: SIZE, slots: (SIZE / 256) ** 2,
    totals: { pixelFieldsCompared: compared, atlasVsWindowMismatchedPixels: atlasBad, brokenTableDetectedPixels: brokenSeen },
    nonvacuous, perScenario: out.results, unsetUniforms: out.missingUniforms }, null, 1));
  process.exit(pass ? 0 : 1);
})().catch(e => { console.log(JSON.stringify({ pass: false, error: String(e && e.stack || e) })); process.exit(1); });

// =====================================================================================================================
// The page: WebGL2 engines (one per kind), the same passes as MediaLayerEngine, compared every frame.
// =====================================================================================================================
async function PAGE(P) {
  const fail = (error, extra = {}) => ({ error, ...extra });
  const W = P.W, H = P.W, N = W / 256;
  const c = document.createElement('canvas'); c.width = 16; c.height = 16;
  const gl = c.getContext('webgl2', { antialias: false, alpha: false });
  if (!gl) return fail('no webgl2');
  if (!gl.getExtension('EXT_color_buffer_float')) return fail('no EXT_color_buffer_float');
  // ---------- programs ----------
  const comp = (t, s, what) => { const sh = gl.createShader(t); gl.shaderSource(sh, s); gl.compileShader(sh); if (!gl.getShaderParameter(sh, gl.COMPILE_STATUS)) throw new Error(what + ': ' + gl.getShaderInfoLog(sh)); return sh; };
  const link = (vs, fs, what) => { const p = gl.createProgram(); gl.attachShader(p, comp(gl.VERTEX_SHADER, vs, what + ' vs')); gl.attachShader(p, comp(gl.FRAGMENT_SHADER, fs, what + ' fs')); gl.linkProgram(p); if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error(what + ' link: ' + gl.getProgramInfoLog(p)); return p; };
  const progSet = (S, tag) => ({
    flux: link(S.quad, S.flux, tag + ' flux'), update: link(S.quad, S.update, tag + ' update'), copy: link(S.quad, S.copy, tag + ' copy'), bead: link(S.quad, S.bead, tag + ' bead'),
    wet: link(S.wetV, S.wetF, tag + ' wet dab'), bake: link(S.quad, S.bake, tag + ' bake'), render: link(S.screen, S.render, tag + ' render'),
    dry: link(S.dryV, S.dryF, tag + ' dry dab'), apply: link(S.quad, S.apply, tag + ' apply'), erase: link(S.eraseV, S.eraseF, tag + ' erase dab'),
    eraseApply: link(S.quad, S.eraseApply, tag + ' erase apply'), pasteDab: link(S.quad, S.pasteDab, tag + ' paste dab'), pasteBrush: link(S.quadPlain, S.pasteBrush, tag + ' paste brush') });
  let PROG;
  try { PROG = { old: progSet(P.SRC.old, 'old'), new: progSet(P.SRC.new, 'new'), atlas: progSet(P.SRC.atlas, 'atlas') }; } catch (e) { return fail('compile/link: ' + e.message); }
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
      if (v === undefined) { missing.add(name); continue; }
      if (u.type === gl.FLOAT) gl.uniform1f(u.loc, +v);
      else if (u.type === gl.INT || u.type === gl.BOOL) gl.uniform1i(u.loc, v | 0);
      else if (u.type === gl.FLOAT_VEC2) gl.uniform2fv(u.loc, v);
      else if (u.type === gl.FLOAT_VEC3) gl.uniform3fv(u.loc, v);
      else if (u.type === gl.FLOAT_VEC4) gl.uniform4fv(u.loc, v);
      else if (u.type === gl.INT_VEC4) gl.uniform4iv(u.loc, v);
      else throw new Error('unhandled uniform type for ' + name);
    }
    gl.activeTexture(gl.TEXTURE0);
  }
  // ---------- geometry ----------
  const quad = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, quad); gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([0, 0, 1, 0, 0, 1, 1, 1]), gl.STATIC_DRAW);
  const quadVao = gl.createVertexArray(); gl.bindVertexArray(quadVao); gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
  // The atlas quad: the unit quad plus per-instance slot (loc 1) and clip rect (loc 2).
  const slotBuf = gl.createBuffer();
  const atlasQuadVao = gl.createVertexArray(); gl.bindVertexArray(atlasQuadVao); gl.bindBuffer(gl.ARRAY_BUFFER, quad); gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
  gl.bindBuffer(gl.ARRAY_BUFFER, slotBuf);
  for (let i = 0; i < 2; i++) { gl.enableVertexAttribArray(1 + i); gl.vertexAttribPointer(1 + i, 4, gl.FLOAT, false, 32, i * 16); gl.vertexAttribDivisor(1 + i, 1); }
  // Dabs: window = the app's 80-byte layout (locs 1-5); atlas = 96 bytes, loc 6 = the slot this copy draws into.
  const dabBuf = gl.createBuffer();
  const dabVao = gl.createVertexArray(); gl.bindVertexArray(dabVao); gl.bindBuffer(gl.ARRAY_BUFFER, quad); gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
  gl.bindBuffer(gl.ARRAY_BUFFER, dabBuf);
  for (let i = 0; i < 5; i++) { gl.enableVertexAttribArray(1 + i); gl.vertexAttribPointer(1 + i, 4, gl.FLOAT, false, 80, i * 16); gl.vertexAttribDivisor(1 + i, 1); }
  const atlasDabVao = gl.createVertexArray(); gl.bindVertexArray(atlasDabVao); gl.bindBuffer(gl.ARRAY_BUFFER, quad); gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
  gl.bindBuffer(gl.ARRAY_BUFFER, dabBuf);
  for (let i = 0; i < 6; i++) { gl.enableVertexAttribArray(1 + i); gl.vertexAttribPointer(1 + i, 4, gl.FLOAT, false, 96, i * 16); gl.vertexAttribDivisor(1 + i, 1); }
  const emptyVao = gl.createVertexArray();
  gl.bindVertexArray(null);
  gl.disable(gl.BLEND); gl.disable(gl.SCISSOR_TEST); gl.disable(gl.DEPTH_TEST);
  // ---------- textures ----------
  const FMT = {
    rgba32f: [gl.RGBA32F, gl.RGBA, gl.FLOAT, gl.NEAREST], rgba16f: [gl.RGBA16F, gl.RGBA, gl.HALF_FLOAT, gl.LINEAR], rg16f: [gl.RG16F, gl.RG, gl.HALF_FLOAT, gl.LINEAR],
    r16f: [gl.R16F, gl.RED, gl.HALF_FLOAT, gl.LINEAR], rgba8: [gl.RGBA8, gl.RGBA, gl.UNSIGNED_BYTE, gl.NEAREST], r8: [gl.R8, gl.RED, gl.UNSIGNED_BYTE, gl.NEAREST],
    r32i: [gl.R32I, gl.RED_INTEGER, gl.INT, gl.NEAREST],
  };
  let OWNED = null;   // the textures and framebuffers of the current scenario's engines, released after it
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
    const st = gl.checkFramebufferStatus(gl.FRAMEBUFFER);
    if (st !== gl.FRAMEBUFFER_COMPLETE) throw new Error('fbo incomplete 0x' + st.toString(16));
    return f;
  }
  const clearFbo = (f, w = W, h = H) => { gl.bindFramebuffer(gl.FRAMEBUFFER, f); gl.viewport(0, 0, w, h); gl.disable(gl.SCISSOR_TEST); gl.clearColor(0, 0, 0, 0); gl.clear(gl.COLOR_BUFFER_BIT); };
  const readFbo = gl.createFramebuffer(), drawFbo = gl.createFramebuffer();
  function blit(src, x0, y0, x1, y1, dst) {
    gl.bindFramebuffer(gl.READ_FRAMEBUFFER, readFbo); gl.framebufferTexture2D(gl.READ_FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, src, 0);
    gl.bindFramebuffer(gl.DRAW_FRAMEBUFFER, drawFbo); gl.framebufferTexture2D(gl.DRAW_FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, dst, 0);
    gl.drawBuffers([gl.COLOR_ATTACHMENT0]);
    gl.blitFramebuffer(x0, y0, x1, y1, x0, y0, x1, y1, gl.COLOR_BUFFER_BIT, gl.NEAREST);
    gl.bindFramebuffer(gl.READ_FRAMEBUFFER, null); gl.bindFramebuffer(gl.DRAW_FRAMEBUFFER, null);
  }
  function scissorTo(r) {
    gl.enable(gl.SCISSOR_TEST);
    const x0 = Math.max(0, Math.floor(r[0])), y0 = Math.max(0, Math.floor(r[1])), x1 = Math.min(W, Math.ceil(r[2])), y1 = Math.min(H, Math.ceil(r[3]));
    gl.scissor(x0, y0, Math.max(0, x1 - x0), Math.max(0, y1 - y0));
  }
  // ---------- the atlas driver (the per-slot instancing B1 adds; identity placement, page table per engine) ----------
  // Slot s of a pass at resolution side = 1 << shift sits at ((s % N) << shift, floor(s / N) << shift); identity: tile (x, y) -> slot x + y·N.
  const tableIdentity = new Int32Array(N * N), tableBroken = new Int32Array(N * N);
  for (let y = 0; y < N; y++) for (let x = 0; x < N; x++) { tableIdentity[y * N + x] = x + y * N; tableBroken[y * N + x] = (x + 1) % N + y * N; }
  const atlasU = (E, shift) => E.atlas ? { u_atlasTable: { tex: E.table }, u_atlasGrid: [0, 0, N, N], u_atlasSlotsX: N, u_atlasShift: shift, u_atlasWrap: [0, 0, 0, 0] } : {};
  // A layer-shaped pass over rect r (layer px at the pass's resolution). Window: one quad. Atlas: one instance per slot.
  function drawRect(E, r, shift = 8) {
    if (!E.atlas) { gl.bindVertexArray(quadVao); gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4); return; }
    const side = 1 << shift, inst = [];
    for (let ty = 0; ty < N; ty++) for (let tx = 0; tx < N; tx++) {
      const x0 = Math.max(r[0], tx * side), y0 = Math.max(r[1], ty * side), x1 = Math.min(r[2], (tx + 1) * side), y1 = Math.min(r[3], (ty + 1) * side);
      if (x1 <= x0 || y1 <= y0) continue;
      inst.push(tx * side, ty * side, tx, ty, x0, y0, x1, y1);   // identity: slot origin == tile origin
    }
    if (!inst.length) return;
    gl.bindBuffer(gl.ARRAY_BUFFER, slotBuf); gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(inst), gl.STREAM_DRAW);
    gl.bindVertexArray(atlasQuadVao); gl.drawArraysInstanced(gl.TRIANGLE_STRIP, 0, 4, inst.length / 8);
  }
  // Instanced dabs; [box] gives each dab's layer-px bounding box. Atlas: each dab once per slot its box touches, in order.
  function drawDabs(E, dabs, box) {
    if (!dabs.length) return;
    if (!E.atlas) {
      gl.bindBuffer(gl.ARRAY_BUFFER, dabBuf); gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(dabs.flat()), gl.STREAM_DRAW);
      gl.bindVertexArray(dabVao); gl.drawArraysInstanced(gl.TRIANGLE_STRIP, 0, 4, dabs.length); return;
    }
    const inst = [];
    for (const d of dabs) {
      const b = box(d);
      const tx0 = Math.max(0, Math.floor(b[0] / 256)), ty0 = Math.max(0, Math.floor(b[1] / 256)), tx1 = Math.min(N - 1, Math.floor(b[2] / 256)), ty1 = Math.min(N - 1, Math.floor(b[3] / 256));
      for (let ty = ty0; ty <= ty1; ty++) for (let tx = tx0; tx <= tx1; tx++) inst.push(...d, tx * 256, ty * 256, tx, ty);
    }
    gl.bindBuffer(gl.ARRAY_BUFFER, dabBuf); gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(inst), gl.STREAM_DRAW);
    gl.bindVertexArray(atlasDabVao); gl.drawArraysInstanced(gl.TRIANGLE_STRIP, 0, 4, inst.length / 24);
  }
  // ---------- seeded state ----------
  const rngf = (a) => () => { a = (a + 0x6D2B79F5) >>> 0; let t = Math.imul(a ^ a >>> 15, 1 | a); t = (t + Math.imul(t ^ t >>> 7, 61 | t)) ^ t; return ((t ^ t >>> 14) >>> 0) / 4294967296; };
  function noiseField(seed, cell) {
    const r = rngf(seed), gw = Math.ceil(W / cell) + 3, g = new Float32Array(gw * gw);
    for (let i = 0; i < g.length; i++) g[i] = r();
    return (x, y) => { const fx = x / cell, fy = y / cell, ix = Math.floor(fx), iy = Math.floor(fy), tx = fx - ix, ty = fy - iy; const sx = tx * tx * (3 - 2 * tx), sy = ty * ty * (3 - 2 * ty);
      const a = g[iy * gw + ix], b = g[iy * gw + ix + 1], c2 = g[(iy + 1) * gw + ix], d = g[(iy + 1) * gw + ix + 1]; return (a + (b - a) * sx) * (1 - sy) + (c2 + (d - c2) * sx) * sy; };
  }
  // p0 K rgb + body, p1 S rgb + openness, paper state (crush, dry volume, flake Σ), a damp pool, stale garbage in state[1].
  function seedState(seed) {
    const n = W * H * 4, p0 = new Float32Array(n), p1 = new Float32Array(n), ps = new Float32Array(n), w0 = new Float32Array(n), w1 = new Float32Array(n), g0 = new Float32Array(n), g1 = new Float32Array(n), g2 = new Float32Array(n);
    const nA = noiseField(seed, 37), nB = noiseField(seed + 1, 23), nC = noiseField(seed + 2, 61), nD = noiseField(seed + 3, 9), r = rngf(seed + 9);
    for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
      const o = (y * W + x) * 4, body = Math.max(0, nA(x, y) * 1.5 - 0.6) * 0.03;
      p0[o] = 3 * body * (0.5 + nB(x, y)); p0[o + 1] = 2 * body * nC(x, y); p0[o + 2] = 1.5 * body; p0[o + 3] = body + 0.002 * nD(x, y);
      p1[o] = p1[o + 1] = p1[o + 2] = 20 * body; p1[o + 3] = body > 0 ? Math.min(1, 0.2 + 0.8 * nC(x, y)) : 0;
      const gr = Math.max(0, nB(x, y) - 0.45) * 0.006;
      ps[o] = 0.3 * Math.max(0, nD(x, y) - 0.5); ps[o + 1] = gr; ps[o + 2] = gr * 0.12; ps[o + 3] = 0;
      const dx = (x - 0.35 * W) / (0.16 * W), dy = (y - 0.55 * H) / (0.12 * H), pool = Math.exp(-(dx * dx + dy * dy));
      const wd = pool > 0.12 ? 0.25 * pool * (0.7 + 0.6 * nB(x, y)) : 0;
      w0[o] = wd; w0[o + 1] = Math.min(1, 0.05 + 0.9 * pool + 0.15 * nC(x, y)); w0[o + 2] = wd > 0 ? 30 * r() : 0.5 * r(); w0[o + 3] = r() * 0.1;
      if (wd > 0) { w1[o] = 4 * wd * nA(x, y); w1[o + 1] = 2 * wd; w1[o + 2] = 0.5 * wd * nD(x, y); w1[o + 3] = 0.3 * wd; }
      else if (r() < 0.04) { w1[o] = 0.001 * r(); w1[o + 3] = 0.0005 * r(); }
      for (let k = 0; k < 4; k++) { g0[o + k] = 3 * r(); g1[o + k] = 3 * r(); g2[o + k] = 3 * r(); }
    }
    for (let i = 0; i < 40; i++) { const o = ((10 + 5 * i) % (W * H)) * 4; w0[o] = 0; w1[o] = 0; }
    return { p0, p1, ps, w0, w1, g0, g1, g2 };
  }
  // the paper (jb_paper.glsl) and the contact table (stick.js contactLut, a smooth tooth-level curve)
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
  const lutBytes = new Uint8Array(256 * 4); for (let i = 0; i < 256; i++) { const v = Math.round(255 * Math.min(1, Math.pow(i / 255, 0.7) * 1.1)); lutBytes[i * 4] = v; lutBytes[i * 4 + 3] = 255; }
  const lutTex = tex(256, 1, 'rgba8', lutBytes); gl.bindTexture(gl.TEXTURE_2D, lutTex); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
  const groundBytes = (() => { const r = rngf(77), b = new Uint8Array(W * H * 4); for (let i = 0; i < W * H; i++) { const a = Math.floor(r() * 256); b[i * 4] = Math.floor(a * r()); b[i * 4 + 1] = Math.floor(a * r()); b[i * 4 + 2] = Math.floor(a * r()); b[i * 4 + 3] = a; } return b; })();
  const paperU = { u_paperSurface: { tex: paperTex }, u_paperTexelPx: 2, u_paperSize: PS, u_paperHexTexels: 32, u_paperSlopeRange: SLOPE, u_paperRotatable: 1, u_paperHeightMean: 0.5,
    u_paperHBot: 0.2, u_paperHTop: 0.8, u_paperFluid: { tex: fluidTex }, u_paperFluidTexelPx: 0, u_paperFluidSize: 4, u_paperFluidHexTexels: 1 };
  const COMMON = { u_layerOrigin: [0, 0], u_layerScale: 1, u_targetSize: [W, H] };
  const KINDS = ['old', 'new', 'atlas', 'broken'];
  // ---------- an engine ----------
  function makeEngine(kind, seed) {
    const atlas = kind === 'atlas' || kind === 'broken', Pg = PROG[atlas ? 'atlas' : kind];
    const sd = seedState(seed);
    const st = [0, 1].map((i) => ({ p0: tex(W, H, 'rgba32f', i ? sd.g0 : sd.p0), p1: tex(W, H, 'rgba32f', i ? sd.g1 : sd.p1), paper: tex(W, H, 'rgba32f', i ? sd.g2 : sd.ps) }));
    const ws = [0, 1].map(() => ({ w0: tex(W, H, 'rgba32f', sd.w0), w1: tex(W, H, 'rgba32f', sd.w1), flux: tex(W, H, 'rgba16f') }));
    const run = tex(W, H, 'rg16f'), bw = W >> 1, bh = H >> 1, bead = [tex(bw, bh, 'rgba16f'), tex(bw, bh, 'rgba16f')];
    const in0 = tex(W, H, 'rgba16f'), in1 = tex(W, H, 'rgba16f');
    const bakeTex = tex(W, H, 'rgba16f'), fluidBake = tex(W, H, 'r8'), waterBake = tex(W, H, 'r16f');
    const E = { kind, atlas, P: Pg, st, ws, run, bead, in0, in1, bakeTex, fluidBake, waterBake, wc: 0, bw, bh };
    E.table = tex(N, N, 'r32i', kind === 'broken' ? tableBroken : tableIdentity);
    E.bakeFbo = fbo(bakeTex, fluidBake, waterBake);
    E.beadFbo = bead.map((b) => fbo(b));
    E.fluxFbo = [0, 1].map((i) => fbo(ws[i].flux, run));
    E.updFbo = [0, 1, 2, 3].map((i) => fbo(ws[i >> 1].w0, ws[i >> 1].w1, st[i & 1].p0, st[i & 1].p1));
    E.stFbo = st.map((s) => fbo(s.p0, s.p1, s.paper));
    E.pFbo = st.map((s) => fbo(s.p0, s.p1));
    E.wetCopy = [fbo(ws[0].w0, ws[0].w1, ws[0].flux, run), fbo(ws[1].w0, ws[1].w1, ws[1].flux)];
    E.wFbo = ws.map((w) => fbo(w.w0, w.w1));
    E.inFbo = fbo(in0, in1);
    E.delta = tex(W, H, 'rgba16f'); E.deltaFbo = fbo(E.delta); clearFbo(E.deltaFbo);
    E.cover = tex(W, H, 'rgba16f'); E.coverFbo = fbo(E.cover); clearFbo(E.coverFbo);
    E.lookTex = tex(W, H, 'rgba8'); E.lookFbo = fbo(E.lookTex);
    E.ground = tex(W, H, 'rgba8', groundBytes);
    const r = rngf(seed + 5), cells = (k) => { const a = new Float32Array(32 * 8 * 4); for (let i = 0; i < 32 * 8; i++) { a[i * 4] = k ? 0.3 * r() : 2 * r(); a[i * 4 + 1] = k ? 0 : 1.5 * r(); a[i * 4 + 2] = k ? 0 : r(); a[i * 4 + 3] = k ? 0 : 0.4 * r(); } return a; };
    E.brush = [0, 1].map(() => [tex(32, 8, 'rgba32f', cells(0)), tex(32, 8, 'rgba32f', cells(1))]);
    E.brushFbo = E.brush.map((b) => fbo(b[0], b[1])); E.bc = 0;
    gl.bindFramebuffer(gl.FRAMEBUFFER, E.bakeFbo); gl.viewport(0, 0, W, H);
    use(E.P.bake, { ...COMMON, ...atlasU(E, 8), u_rect: [0, 0, W, H], ...paperU }); drawRect(E, [0, 0, W, H]);
    clearFbo(E.inFbo);
    return E;
  }
  function copyRect(E, src, dstFbo, rect) {
    gl.bindFramebuffer(gl.FRAMEBUFFER, dstFbo); gl.viewport(0, 0, W, H); gl.disable(gl.BLEND);
    use(E.P.copy, { ...COMMON, ...atlasU(E, 8), u_a: { tex: src.p0 }, u_b: { tex: src.p1 }, u_c: { tex: src.paper }, u_rect: rect }); drawRect(E, rect);
  }
  // ---------- wet (MediaLayerEngine.wetFrame) ----------
  function beadPasses(E, w0, rect, beadCells) {
    const sigma = Math.min(4.8, Math.max(1.0, 0.25 * beadCells)), r = Math.min(12, Math.ceil(2.5 * sigma)), m = 3 + 2 * r;
    const hr = [Math.max(0, Math.floor(rect[0] / 2) - m), Math.max(0, Math.floor(rect[1] / 2) - m), Math.min(E.bw, Math.ceil(rect[2] / 2) + m), Math.min(E.bh, Math.ceil(rect[3] / 2) + m)];
    const pass = (f, pn, src, ax, ay) => {
      gl.bindFramebuffer(gl.FRAMEBUFFER, f); gl.viewport(0, 0, E.bw, E.bh);
      use(E.P.bead, { ...COMMON, ...atlasU(E, 7), u_rect: hr, u_targetSize: [E.bw, E.bh], u_sigma: sigma, u_pass: pn, u_w0: { tex: w0 }, u_src: { tex: src }, u_axis: [ax, ay] }); drawRect(E, hr, 7);
    };
    pass(E.beadFbo[0], 0, E.bead[1], 1, 0); pass(E.beadFbo[1], 1, E.bead[0], 1, 0); pass(E.beadFbo[0], 2, E.bead[1], 0, 1);
  }
  function wetFrame(E, sc, Fr) {
    const rect = Fr.rect, steps = Fr.steps, useInput = !!Fr.dabs;
    if (useInput) {
      gl.bindFramebuffer(gl.FRAMEBUFFER, E.inFbo); gl.viewport(0, 0, W, H);
      if (rect) scissorTo(rect);
      gl.enable(gl.BLEND); gl.blendFunc(gl.ONE, gl.ONE); gl.blendEquation(gl.FUNC_ADD);
      use(E.P.wet, { ...COMMON, ...atlasU(E, 8), u_w0: { tex: E.ws[E.wc].w0 }, u_paperBake: { tex: E.bakeTex }, u_pxPerMm: P.PPM, u_fullMm: P.WC.fullMm });
      drawDabs(E, Fr.dabs, (d) => [d[0] - Fr.reach, d[1] - Fr.reach, d[0] + Fr.reach, d[1] + Fr.reach]);
      gl.disable(gl.BLEND); gl.disable(gl.SCISSOR_TEST);
    }
    if (!rect) return;
    const beadCells = P.WC.beadMm * P.PPM, tilted = Math.hypot(sc.slope[0], sc.slope[1]) > 1e-4;
    const U = { ...P.wu, u_slope: sc.slope, u_cellMm: 1 / P.PPM, u_beadCells: beadCells, u_gran: sc.gran, ...COMMON, ...atlasU(E, 8), u_rect: rect };
    if (Fr.evap !== null) U.u_evap = Fr.evap;
    gl.disable(gl.BLEND);
    if (steps > 1) {
      const ring = Math.max(4, Math.ceil(P.WC.steerScale * beadCells) + 3);
      const rx0 = Math.max(0, Math.floor(rect[0])), ry0 = Math.max(0, Math.floor(rect[1])), rx1 = Math.min(W, Math.ceil(rect[2])), ry1 = Math.min(H, Math.ceil(rect[3]));
      const ox0 = Math.max(0, rx0 - ring), oy0 = Math.max(0, ry0 - ring), ox1 = Math.min(W, rx1 + ring), oy1 = Math.min(H, ry1 + ring);
      const strip = (x0, y0, x1, y1) => { if (x1 > x0 && y1 > y0) blit(E.st[0].p0, x0, y0, x1, y1, E.st[1].p0); };
      strip(ox0, oy0, ox1, ry0); strip(ox0, ry1, ox1, oy1); strip(ox0, ry0, rx0, ry1); strip(rx1, ry0, ox1, ry1);
    }
    for (let k = 0; k < steps; k++) {
      const a = E.ws[E.wc], bi = 1 - E.wc, b = E.ws[bi], src = E.st[k & 1];
      if (tilted) beadPasses(E, a.w0, rect, beadCells);
      gl.bindFramebuffer(gl.FRAMEBUFFER, E.fluxFbo[bi]); gl.viewport(0, 0, W, H);
      use(E.P.flux, { ...U, u_bead: { tex: E.bead[0] }, u_p0: { tex: src.p0 }, u_w0: { tex: a.w0 }, u_flux: { tex: a.flux }, u_paperBake: { tex: E.bakeTex }, u_fluidBake: { tex: E.fluidBake }, u_waterBake: { tex: E.waterBake } });
      drawRect(E, rect);
      gl.bindFramebuffer(gl.FRAMEBUFFER, E.updFbo[bi * 2 + 1 - (k & 1)]); gl.viewport(0, 0, W, H);
      use(E.P.update, { ...U, u_w0: { tex: a.w0 }, u_w1: { tex: a.w1 }, u_flux: { tex: b.flux }, u_run: { tex: E.run }, u_p0: { tex: src.p0 }, u_p1: { tex: src.p1 },
        u_paperBake: { tex: E.bakeTex }, u_waterBake: { tex: E.waterBake }, u_fluidBake: { tex: E.fluidBake }, u_in0: { tex: E.in0 }, u_in1: { tex: E.in1 }, u_useInput: (k === 0 && useInput) ? 1 : 0 });
      drawRect(E, rect);
      E.wc = bi;
    }
    if ((steps & 1) === 1) copyRect(E, E.st[1], E.pFbo[0], rect);   // p0, p1 only: the paper plane is not the wet frame's
    if (useInput) { gl.bindFramebuffer(gl.FRAMEBUFFER, E.inFbo); gl.viewport(0, 0, W, H); scissorTo(rect); gl.clearColor(0, 0, 0, 0); gl.clear(gl.COLOR_BUFFER_BIT); gl.disable(gl.SCISSOR_TEST); }
    if (Fr.dryNow) { for (const f of E.wetCopy) clearFbo(f); }
  }
  // ---------- dry (MediaLayerEngine.dryFrame: dabs into the delta, apply, copy back, clear the delta) ----------
  const DRY = { u_lutMaxMm: 0.2, u_pxPerMm: P.PPM, u_toothMm: 0.09, u_dirStrength: 0.8, u_dustRate: 0.004, u_clump: 0.6, u_pileRange: [0.3, 0.8], u_crushStart: 0.02,
    u_conform: 0.5, u_transferExp: 1.6, u_leadSoft: 0.25, u_plateau: 1.8 };
  function dryFrame(E, sc, Fr) {
    gl.bindFramebuffer(gl.FRAMEBUFFER, E.deltaFbo); gl.viewport(0, 0, W, H);
    gl.enable(gl.BLEND); gl.blendFunc(gl.ONE, gl.ONE); gl.blendEquation(gl.FUNC_ADD);
    use(E.P.dry, { ...COMMON, ...paperU, ...DRY, ...atlasU(E, 8), u_crush: { tex: E.st[0].paper }, u_contactLut: { tex: lutTex } });
    drawDabs(E, Fr.dabs, (d) => { const ext = (Math.hypot(Math.max(Math.abs(d[4]), Math.abs(d[5])), d[6]) + 0.1) * P.PPM + 2; return [d[0] - ext, d[1] - ext, d[0] + ext, d[1] + ext]; });
    gl.disable(gl.BLEND);
    const smearPx = sc.smearMm * P.PPM, rect = Fr.rect;
    if (!rect) return;
    gl.bindFramebuffer(gl.FRAMEBUFFER, E.stFbo[1]); gl.viewport(0, 0, W, H);
    use(E.P.apply, { ...COMMON, ...atlasU(E, 8), u_p0: { tex: E.st[0].p0 }, u_p1: { tex: E.st[0].p1 }, u_paperState: { tex: E.st[0].paper }, u_delta: { tex: E.delta },
      u_capMm: 0.012, u_abrasion: 900, u_flakeR: 0.08, u_crushRate: 40, u_crushMax: 0.6, u_smear: 6, u_smearPx: [Fr.travel[0] * smearPx, Fr.travel[1] * smearPx], u_rect: rect });
    drawRect(E, rect);
    copyRect(E, E.st[1], E.stFbo[0], rect);
    gl.bindFramebuffer(gl.FRAMEBUFFER, E.deltaFbo); scissorTo(rect); gl.clearColor(0, 0, 0, 0); gl.clear(gl.COLOR_BUFFER_BIT); gl.disable(gl.SCISSOR_TEST);
  }
  // ---------- erase (cover with MAX, then the state pass and the water pass) ----------
  function eraseFrame(E, sc, Fr) {
    gl.bindFramebuffer(gl.FRAMEBUFFER, E.coverFbo); gl.viewport(0, 0, W, H);
    gl.enable(gl.BLEND); gl.blendFunc(gl.ONE, gl.ONE); gl.blendEquation(gl.MAX);
    use(E.P.erase, { ...COMMON, ...atlasU(E, 8) });
    drawDabs(E, Fr.dabs, (d) => { const e = d[2] + 2.5; return [d[0] - e, d[1] - e, d[0] + e, d[1] + e]; });
    gl.blendEquation(gl.FUNC_ADD); gl.disable(gl.BLEND);
    const rect = Fr.rect;
    if (!rect) return;
    gl.bindFramebuffer(gl.FRAMEBUFFER, E.stFbo[1]); gl.viewport(0, 0, W, H);
    use(E.P.eraseApply, { ...COMMON, ...atlasU(E, 8), u_a: { tex: E.st[0].p0 }, u_b: { tex: E.st[0].p1 }, u_c: { tex: E.st[0].paper }, u_cover: { tex: E.cover }, u_water: 0, u_rect: rect });
    drawRect(E, rect);
    copyRect(E, E.st[1], E.stFbo[0], rect);
    const a = E.ws[E.wc], bi = 1 - E.wc;
    gl.bindFramebuffer(gl.FRAMEBUFFER, E.wFbo[bi]); gl.viewport(0, 0, W, H);
    use(E.P.eraseApply, { ...COMMON, ...atlasU(E, 8), u_a: { tex: a.w0 }, u_b: { tex: a.w1 }, u_c: { tex: E.st[0].paper }, u_cover: { tex: E.cover }, u_water: 1, u_rect: rect });
    drawRect(E, rect);
    const x0 = rect[0], y0 = rect[1], x1 = rect[2], y1 = rect[3];
    blit(E.ws[bi].w0, x0, y0, x1, y1, a.w0); blit(E.ws[bi].w1, x0, y0, x1, y1, a.w1);
    gl.bindFramebuffer(gl.FRAMEBUFFER, E.coverFbo); scissorTo(rect); gl.clearColor(0, 0, 0, 0); gl.clear(gl.COLOR_BUFFER_BIT); gl.disable(gl.SCISSOR_TEST);
  }
  // ---------- paste (brush cells, then the canvas under the footprint, then copy the box back) ----------
  const PASTE = { u_pxPerMm: P.PPM, u_toothMm: 0.09, u_thick: 0.45, u_scrape: 0.35, u_hairDepth: 0.3, u_ridge: 0.18, u_rate: 0.5, u_mix: 0.35, u_swap: 0.2,
    u_cellCap: 0.4, u_bow: 0.2, u_lump: 0.1, u_rigid: 0, u_fillDips: 0.5, u_fingers: 0.35, u_rag: 0.3, u_arc: 0.2, u_sparse: 0.1, u_wick: 0.08, u_active: 1 };
  function pasteFrame(E, sc, Fr) {
    for (const s of Fr.steps) {
      if (!s.rect) continue;
      const U = { ...COMMON, ...PASTE, u_halfW: sc.halfW, u_lenMax: sc.lenMax, u_len: s.len, u_shape: sc.shape, u_blade: sc.blade, u_rigid: sc.blade, u_pressure: s.pressure,
        u_center: s.center, u_wDir: s.wDir, u_lDir: s.lDir, u_travel: s.travel, u_bladeDir: s.bladeDir, u_slideMm: 0.5, u_paperBake: { tex: E.bakeTex }, u_layerSize: [W, H],
        u_bladeLen: sc.lenMax, u_bladeHalfW: sc.halfW, u_bladeH0: 0.02 + 0.06 * (1 - s.pressure), u_bladeTan: 0.15, u_bladeBead: 0.3, u_bladePressMm: 0.03 * s.pressure,
        u_bladeFilm: 0.008, u_bladeLoad: 0.6, u_seed: sc.seed };
      const nb = 1 - E.bc;
      gl.bindFramebuffer(gl.FRAMEBUFFER, E.brushFbo[nb]); gl.viewport(0, 0, 32, 8);
      use(E.P.pasteBrush, { ...U, ...atlasU(E, 8), u_targetSize: [32, 8], u_rect: [0, 0, 32, 8], u_p0: { tex: E.st[0].p0 }, u_p1: { tex: E.st[0].p1 },
        u_brush0: { tex: E.brush[E.bc][0] }, u_brush1: { tex: E.brush[E.bc][1] } });
      gl.bindVertexArray(quadVao); gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);   // not layer-shaped: one quad in every build
      E.bc = nb;
      gl.bindFramebuffer(gl.FRAMEBUFFER, E.pFbo[1]); gl.viewport(0, 0, W, H);
      use(E.P.pasteDab, { ...U, ...atlasU(E, 8), u_p0: { tex: E.st[0].p0 }, u_p1: { tex: E.st[0].p1 }, u_brush0: { tex: E.brush[E.bc][0] }, u_brush1: { tex: E.brush[E.bc][1] }, u_rect: s.rect });
      if (E.atlas) drawRect(E, s.rect); else { scissorTo(s.rect); drawRect(E, s.rect); gl.disable(gl.SCISSOR_TEST); }
      copyRect(E, E.st[1], E.pFbo[0], s.rect);
    }
  }
  const LOOK_M = 96;   // the look is rendered (and compared) inside the same margin, in every engine
  function renderLook(E) {
    gl.bindFramebuffer(gl.FRAMEBUFFER, E.lookFbo); gl.viewport(0, 0, W, H); gl.disable(gl.BLEND);
    gl.enable(gl.SCISSOR_TEST); gl.scissor(LOOK_M, LOOK_M, W - 2 * LOOK_M, H - 2 * LOOK_M);
    const w = E.ws[E.wc], lamp = [-0.55, 0.55, 0.62], ln = Math.hypot(...lamp);
    use(E.P.render, { ...COMMON, ...paperU, ...atlasU(E, 8), u_p0: { tex: E.st[0].p0 }, u_p1: { tex: E.st[0].p1 }, u_paperState: { tex: E.st[0].paper }, u_ground: { tex: E.ground }, u_paperBake: { tex: E.bakeTex },
      u_w0: { tex: w.w0 }, u_w1: { tex: w.w1 }, u_pan: [0, 0], u_zoom: 1, u_pxPerMm: P.PPM, u_toothMm: 0.09, u_paperColor: [0.95, 0.93, 0.88], u_lamp: lamp.map(v => v / ln),
      u_relief: 0, u_sheen: 0.06, u_capMm: 0.004, u_mode: 0, u_impasto: 1.4, u_look: 1 });
    gl.bindVertexArray(emptyVao); gl.drawArrays(gl.TRIANGLES, 0, 3);
    gl.disable(gl.SCISSOR_TEST);
  }
  // ---------- readback and comparison ----------
  const rf = gl.createFramebuffer();
  function readF(texId) { gl.bindFramebuffer(gl.FRAMEBUFFER, rf); gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, texId, 0); const a = new Float32Array(W * H * 4); gl.readPixels(0, 0, W, H, gl.RGBA, gl.FLOAT, a); return a; }
  function readB(texId) { gl.bindFramebuffer(gl.FRAMEBUFFER, rf); gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, texId, 0); const a = new Uint8Array(W * H * 4); gl.readPixels(0, 0, W, H, gl.RGBA, gl.UNSIGNED_BYTE, a); return a; }
  function snapshot(E) {
    const w = E.ws[E.wc];
    return { w0: readF(w.w0), w1: readF(w.w1), p0: readF(E.st[0].p0), p1: readF(E.st[0].p1), paper: readF(E.st[0].paper), look: readB(E.lookTex) };
  }
  const FIELDS = ['w0', 'w1', 'p0', 'p1', 'paper', 'look'];
  function diff(a, b, isFloat) {
    const ua = isFloat ? new Uint32Array(a.buffer) : a, ub = isFloat ? new Uint32Array(b.buffer) : b;
    let px = 0, max = 0;
    for (let i = 0; i < ua.length; i += 4) {
      let d = false;
      for (let k = 0; k < 4; k++) if (ua[i + k] !== ub[i + k]) { d = true; const e = Math.abs(a[i + k] - b[i + k]); if (!(e <= max)) max = Number.isFinite(e) ? e : Infinity; }
      if (d) px++;
    }
    return { px, max };
  }
  function compare(A, B) {
    const r = { total: 0, maxAbs: {} };
    for (const f of FIELDS) { const d = diff(A[f], B[f], f !== 'look'); r[f] = d.px; r.total += d.px; if (d.px) r.maxAbs[f] = d.max; }
    return r;
  }
  // ---------- the run ----------
  const results = [];
  for (const sc of P.scenarios) {
    OWNED = { tex: [], fbo: [] };
    const E = {}; for (const k of KINDS) E[k] = makeEngine(k, sc.seed * 101 + 7);
    const r = { name: sc.name, medium: sc.kind, frames: 0, pixelsCompared: 0,
      mismatch: Object.fromEntries(KINDS.slice(1).map(k => [k, { total: 0, framesDetected: 0, maxAbs: {} }])), changed: 0, lookDistinct: 0 };
    let first = null;
    try {
      for (let fi = 0; fi < sc.frames.length; fi++) {
        const Fr = sc.frames[fi];
        for (const k of KINDS) {
          const e = E[k];
          if (sc.kind === 'wet') wetFrame(e, sc, Fr); else if (sc.kind === 'dry') dryFrame(e, sc, Fr); else if (sc.kind === 'erase') eraseFrame(e, sc, Fr); else pasteFrame(e, sc, Fr);
          renderLook(e);
        }
        const snaps = {}; for (const k of KINDS) snaps[k] = snapshot(E[k]);
        if (!first) first = snaps.new;
        for (const k of KINDS.slice(1)) {
          const ref = k === 'new' ? snaps.old : snaps.new, d = compare(ref, snaps[k]), m = r.mismatch[k];
          for (const f of FIELDS) m[f] = (m[f] || 0) + d[f];
          m.total += d.total; if (d.total > 0) m.framesDetected++;
          for (const [f, v] of Object.entries(d.maxAbs)) m.maxAbs[f] = Math.max(m.maxAbs[f] || 0, v);
        }
        r.frames++; r.pixelsCompared += W * H * FIELDS.length;
        const ge = gl.getError(); if (ge !== gl.NO_ERROR) throw new Error('GL error 0x' + ge.toString(16) + ' at frame ' + fi);
      }
      const last = snapshot(E.new);
      r.changed = FIELDS.slice(0, 5).reduce((s, f) => s + diff(first[f], last[f], true).px, 0) + diff(first.look, last.look, false).px;
      const seen = new Set(); for (let i = 0; i < last.look.length; i += 4) seen.add(last.look[i] | last.look[i + 1] << 8 | last.look[i + 2] << 16 | last.look[i + 3] << 24); r.lookDistinct = seen.size;
    } catch (e) { r.error = String(e.message || e); }
    gl.bindFramebuffer(gl.FRAMEBUFFER, null);
    for (const t of OWNED.tex) gl.deleteTexture(t);
    for (const f of OWNED.fbo) gl.deleteFramebuffer(f);
    OWNED = null;
    results.push(r);
  }
  return { results, missingUniforms: [...missing].sort() };
}
