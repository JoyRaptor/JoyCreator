// joybrush/tools/media_wet_exact_parity_check.js — the watercolour frame's exact bandwidth cuts, proved bit for bit.
//
// WHAT CHANGED (lead/wet-exact), and what this proves is EXACT:
//   1. the per-substep copy pass (state[1].p0/p1 -> state[0].p0/p1) is gone: the paint planes ping-pong with the substep
//      parity (substep k reads state[k&1], writes the other); the flux pass's neighbour reads of p0.a beyond the rectangle
//      are served by a one-time ring copy; an odd substep count (the dry-now frame) ends with one copy;
//   2. the brush-input dabs are drawn into, and the input cleared over, the simulated rectangle only (not the whole window);
//   3. fluidBake RGBA8 -> R8, waterBake RGBA16F -> R16F, run RGBA16F -> RG16F (only those channels are ever read);
//   4. jb_wet_update.frag skips the 8-tap drying ring (and two fetches) for a cell whose water is exactly 0.
//
// HOW. Two engines are driven through the SAME wet strokes (a seeded RGBA32F state, dabs from a dab vertex shader, many frames):
//   OLD = the shaders of <base> (default 44379471e~1, the commit before the cuts, via `git show`) with the OLD pass sequence and formats;
//   NEW = the shaders in this working tree with the NEW sequence and formats (a JS port of MediaLayerEngine.wetFrame);
//   plus deliberately BROKEN controls that MUST be detected: noRing (NEW without the p0 ring copy: breaks tilted paper),
//   noClear (NEW that forgets to clear the brush input), badIdle (NEW whose update shader skips the drying ring for
//   thin-but-not-empty cells).
// After EVERY frame the FULL float readback of w0, w1 (current buffer), p0, p1 (state[0]) is compared as raw bit patterns
// (all four channels, so -0/NaN count) and the RGBA8 look (jb_media_render.frag) byte for byte, OLD vs NEW (0 pixels may
// differ) and OLD vs each control (> 0 required). The baked fluid/water/height values are compared too.
//
// Run: NODE_PATH=<dir with playwright-core> node joybrush/tools/media_wet_exact_parity_check.js [--base 44379471e~1]
//        [--chrome <exe>] [--size 256] [--quick]
// Prints one JSON line; exit 0 only on pass. SwiftShader is software GL: this proves the maths, not Adreno timing.
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
// The commit before 44379471e (lead/wet-exact): what the bandwidth cuts are proved against. origin/joy-creator has
// carried those cuts since, so it stopped being a valid default.
const BASE = argVal('base', '44379471e~1');
const SIZE = parseInt(argVal('size', '256'), 10);
const QUICK = process.argv.includes('--quick');
const PPM = 12;            // doc px per mm (layer scale 1)
const SCALE = 1;

// ---- shaders: new from the tree, old from git (same include rule as the Android loader) ----
const INCLUDE_RE = /^#include\s+"([^"]+)"$/;
function expand(rel, read, seen = new Set()) {
  if (seen.has(rel)) return '';
  seen.add(rel);
  const text = read(rel).replace(/\r\n/g, '\n');
  return text.split('\n').map(l => { const m = l.trim().match(INCLUDE_RE); return m ? expand(m[1], read, seen) : l; }).join('\n');
}
// --head <ref>: the NEW shaders from a commit instead of the working tree. Since Media Atlas v2 B1 (2c33263d4) the flux
// reads the bead with a hand bilinear (the Lead's accepted one-time change), so tilted scenarios are no longer bit-exact
// against the pre-cut base; flat ones still are. Reproduce this checker's own proof with --head 735ff55e9.
const HEAD = argVal('head', null);
const readNew = HEAD ? (rel) => execFileSync('git', ['-C', ROOT, 'show', HEAD + ':joybrush/shaders/' + rel], { encoding: 'utf8', maxBuffer: 1 << 26 })
  : (rel) => fs.readFileSync(path.join(SHADER_DIR, rel), 'utf8');
const readOld = (rel) => execFileSync('git', ['-C', ROOT, 'show', BASE + ':joybrush/shaders/' + rel], { encoding: 'utf8', maxBuffer: 1 << 26 });
const FILES = { quad: 'media/jb_media_quad.vert', flux: 'media/jb_wet_flux.frag', update: 'media/jb_wet_update.frag', copy: 'media/jb_media_copy.frag',
  bead: 'media/jb_wet_bead.frag', dabV: 'media/jb_wet_dab.vert', dabF: 'media/jb_wet_dab.frag', bake: 'media/jb_media_bake.frag',
  screen: 'media/jb_media_screen.vert', render: 'media/jb_media_render.frag' };
function srcSet(read) { const o = {}; for (const [k, f] of Object.entries(FILES)) o[k] = expand(f, read); return o; }
const SRC_OLD = srcSet(readOld), SRC_NEW = srcSet(readNew);
// The deliberately broken update shader: skips the drying ring for any cell with w < 0.02 (not only exactly empty ones).
const IDLE_RE = /bool idle = w == 0\.0 &&/;
if (!IDLE_RE.test(SRC_NEW.update)) { console.log(JSON.stringify({ pass: false, error: 'new jb_wet_update.frag has no idle skip to mutate' })); process.exit(1); }
const SRC_BAD = { ...SRC_NEW, update: SRC_NEW.update.replace(IDLE_RE, 'bool idle = w < 0.02 &&') };
const oldHasIdle = IDLE_RE.test(SRC_OLD.update);
if (oldHasIdle) { console.log(JSON.stringify({ pass: false, error: 'base update shader already has the idle skip: wrong --base' })); process.exit(1); }

// ---- constants: WetConstants defaults and wetUniforms for a mid paper (sizing .5, absorbency 1, capacity 1, wickSpeed 1, tooth .09) ----
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

// ---- rectangle logic (ports of MediaLayerEngine.layerRect and WetSpread.grow) ----
const f32 = Math.fround;
function layerRect(r, padDoc) {
  const pad = padDoc * SCALE;
  const x0 = Math.max(0, Math.floor(r[0] * SCALE - pad)), y0 = Math.max(0, Math.floor(r[1] * SCALE - pad));
  const x1 = Math.min(SIZE, Math.ceil(r[2] * SCALE + pad)), y1 = Math.min(SIZE, Math.ceil(r[3] * SCALE + pad));
  return x1 > x0 && y1 > y0 ? [x0, y0, x1, y1] : null;
}
const union = (a, b) => a ? [Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.max(a[2], b[2]), Math.max(a[3], b[3])] : b.slice();
function grow(rect, paint, sx, sy, steps) {
  if (!paint || Math.hypot(sx, sy) <= 1e-4) return rect.slice();
  const cellMm = 1 / (PPM * SCALE), g = Math.ceil(steps * 0.45) + 2, far = Math.ceil(40 / cellMm), near = Math.ceil(10 / cellMm);
  const reach = (d) => d > 1e-4 ? far : near;
  return [Math.max(0, Math.max(paint[0] - reach(-sx), rect[0] - g)), Math.max(0, Math.max(paint[1] - reach(-sy), rect[1] - g)),
    Math.min(SIZE, Math.min(paint[2] + reach(sx), rect[2] + g)), Math.min(SIZE, Math.min(paint[3] + reach(sy), rect[3] + g))];
}

// ---- scenarios: seeded wet strokes ----
const BRUSHES = {   // along/half (mm), waterPerMm, wetness, K rgb, S, dryness, solubility
  Round: { along: 3.0, half: 2.0, wpm: 0.09, wet: 0.95, K: [5.0, 2.0, 0.6], S: 0.2, dry: 0.0, sol: 0.7 },
  Wash: { along: 4.5, half: 4.0, wpm: 0.1, wet: 1.0, K: [1.2, 3.5, 4.5], S: 0.15, dry: 0.0, sol: 0.8 },
  Thirsty: { along: 3.0, half: 2.0, wpm: 0.05, wet: 0.08, K: [0, 0, 0], S: 0, dry: 0.9, sol: 0.0 },
};
function rng(seed) { let a = seed >>> 0; return () => { a = (a + 0x6D2B79F5) >>> 0; let t = Math.imul(a ^ a >>> 15, 1 | a); t = (t + Math.imul(t ^ t >>> 7, 61 | t)) ^ t; return ((t ^ t >>> 14) >>> 0) / 4294967296; }; }
function dabsFor(brush, pts, slideMm, seedNo) {
  const b = BRUSHES[brush], out = new Float32Array(pts.length * 20); let dirty = null;
  pts.forEach((p, i) => {
    const o = i * 20;
    out.set([p.x, p.y, p.dx, p.dy, b.along, b.half, b.wpm, b.wet, b.K[0], b.K[1], b.K[2], b.S, slideMm, b.dry, b.sol, seedNo, 0, 0, 0, 0], o);
    const r = (b.along + 0.1) * PPM + 2, box = [p.x - r, p.y - r, p.x + r, p.y + r];
    dirty = dirty ? [Math.min(dirty[0], box[0]), Math.min(dirty[1], box[1]), Math.max(dirty[2], box[2]), Math.max(dirty[3], box[3])] : box;
  });
  return { dabs: out, n: pts.length, dirty };
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
function buildScenario(sc) {
  const frames = []; let wetRect = null, paintRect = null;
  const total = sc.frames, strokeFrames = sc.strokeFrames;
  for (let f = 0; f < total; f++) {
    const steps = sc.steps ? sc.steps(f) : WC.substeps;
    const fr = { steps, n: 0, dabs: null, evap: null, dryNow: false, rect: null };
    let batch = null;
    if (f < strokeFrames) {
      const t0 = f / strokeFrames, t1 = (f + 1) / strokeFrames;
      batch = dabsFor(sc.brush, pathPts(sc.from[0], sc.from[1], sc.to[0], sc.to[1], sc.bend, t0, t1, 3), 0.55 * (f === 0 ? 3 : 1), 3);
    } else if (sc.thirsty && f >= sc.thirsty[0] && f < sc.thirsty[1]) {
      const k = f - sc.thirsty[0], m = sc.thirsty[1] - sc.thirsty[0];
      batch = dabsFor('Thirsty', pathPts(sc.thirstyFrom[0], sc.thirstyFrom[1], sc.thirstyTo[0], sc.thirstyTo[1], 0, k / m, (k + 1) / m, 3), 0.6, 9);
    }
    if (batch) {
      fr.n = batch.n; fr.dabs = batch.dabs;
      const r = layerRect(batch.dirty, 48);
      if (r) { wetRect = union(wetRect, r); paintRect = union(paintRect, r); }
    }
    if (f === sc.dryNowAt) { fr.dryNow = true; fr.steps = 1; fr.evap = 1e4; }
    fr.rect = wetRect ? grow(wetRect, paintRect, sc.slope[0], sc.slope[1], fr.steps) : null;
    if (fr.rect) wetRect = fr.rect;
    if (fr.dryNow) { wetRect = null; paintRect = null; }
    frames.push(fr);
  }
  return { name: sc.name, slope: sc.slope, gran: sc.gran || 0, seed: sc.seed, frames };
}
const FLAT = [0, 0];
const SCENARIOS = [
  { name: 'wet:Round flat', brush: 'Round', from: [50, 70], to: [200, 170], bend: 24, frames: QUICK ? 10 : 26, strokeFrames: QUICK ? 6 : 14, slope: FLAT, seed: 11, thirsty: [8, 11], thirstyFrom: [90, 140], thirstyTo: [150, 80] },
  { name: 'Wash flat', brush: 'Wash', from: [60, 60], to: [190, 200], bend: -30, frames: QUICK ? 10 : 24, strokeFrames: QUICK ? 5 : 12, slope: FLAT, seed: 12, gran: 1 },
  { name: 'wet:Round tilted', brush: 'Round', from: [60, 60], to: [190, 120], bend: 16, frames: QUICK ? 10 : 26, strokeFrames: QUICK ? 6 : 14, slope: [0.25, 0.45], seed: 13 },
  { name: 'Wash tilted', brush: 'Wash', from: [70, 50], to: [180, 130], bend: -12, frames: QUICK ? 10 : 24, strokeFrames: QUICK ? 5 : 12, slope: [-0.4, 0.15], seed: 14, gran: 1 },
  { name: 'wet:Round flat + dryNow + odd substeps', brush: 'Round', from: [50, 90], to: [200, 160], bend: 20, frames: QUICK ? 10 : 22, strokeFrames: QUICK ? 5 : 10, slope: FLAT, seed: 15,
    steps: (f) => (f % 5 === 3 ? 3 : f % 7 === 5 ? 1 : f % 11 === 9 ? 2 : 4), dryNowAt: QUICK ? 8 : 17 },
  { name: 'Wash tilted + odd substeps + dryNow', brush: 'Wash', from: [60, 80], to: [180, 150], bend: 10, frames: QUICK ? 10 : 20, strokeFrames: QUICK ? 5 : 9, slope: [0.1, -0.5], seed: 16,
    steps: (f) => (f % 4 === 2 ? 3 : 4), dryNowAt: QUICK ? 8 : 16 },
].map(buildScenario);

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
    const payload = {
      W: SIZE, H: SIZE, PPM, SRC: { old: SRC_OLD, new: SRC_NEW, bad: SRC_BAD }, wu: wetUniformMap(WC), WC,
      scenarios: SCENARIOS.map(s => ({ ...s, frames: s.frames.map(f => ({ ...f, dabs: f.dabs ? Array.from(f.dabs) : null })) })),
    };
    out = await page.evaluate(async (P) => {
      const fail = (error, extra = {}) => ({ error, ...extra });
      const W = P.W, H = P.H;
      const c = document.createElement('canvas'); c.width = 16; c.height = 16;
      const gl = c.getContext('webgl2', { antialias: false, alpha: false });
      if (!gl) return fail('no webgl2');
      if (!gl.getExtension('EXT_color_buffer_float')) return fail('no EXT_color_buffer_float');
      // ---------- programs ----------
      const comp = (t, s) => { const sh = gl.createShader(t); gl.shaderSource(sh, s); gl.compileShader(sh); if (!gl.getShaderParameter(sh, gl.COMPILE_STATUS)) throw new Error((t === gl.VERTEX_SHADER ? 'vs: ' : 'fs: ') + gl.getShaderInfoLog(sh)); return sh; };
      const link = (vs, fs) => { const p = gl.createProgram(); gl.attachShader(p, comp(gl.VERTEX_SHADER, vs)); gl.attachShader(p, comp(gl.FRAGMENT_SHADER, fs)); gl.linkProgram(p); if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error('link: ' + gl.getProgramInfoLog(p)); return p; };
      const progSet = (S) => ({ flux: link(S.quad, S.flux), update: link(S.quad, S.update), copy: link(S.quad, S.copy), bead: link(S.quad, S.bead), dab: link(S.dabV, S.dabF),
        bake: link(S.quad, S.bake), render: link(S.screen, S.render) });
      let PROG;
      try { PROG = { old: progSet(P.SRC.old), new: progSet(P.SRC.new), bad: progSet(P.SRC.bad) }; } catch (e) { return fail('compile/link: ' + e.message); }
      const uinfo = new Map(), missing = new Set();
      const infoOf = (p) => { if (!uinfo.has(p)) { const m = {}; const n = gl.getProgramParameter(p, gl.ACTIVE_UNIFORMS); for (let i = 0; i < n; i++) { const a = gl.getActiveUniform(p, i); m[a.name.replace(/\[0\]$/, '')] = { type: a.type, loc: gl.getUniformLocation(p, a.name) }; } uinfo.set(p, m); } return uinfo.get(p); };
      // Writes every declared uniform that the map has (a declared uniform with no value keeps GL's 0, exactly as an engine that never writes it).
      function use(prog, vals) {
        gl.useProgram(prog);
        const info = infoOf(prog); let unit = 0;
        for (const [name, u] of Object.entries(info)) {
          const v = vals[name];
          if (u.type === gl.SAMPLER_2D) {
            if (v === undefined) { missing.add(name); unit++; continue; }
            gl.activeTexture(gl.TEXTURE0 + unit); gl.bindTexture(gl.TEXTURE_2D, v.tex); gl.uniform1i(u.loc, unit); unit++; continue;
          }
          if (v === undefined) { missing.add(name); continue; }
          if (u.type === gl.FLOAT) gl.uniform1f(u.loc, +v);
          else if (u.type === gl.INT || u.type === gl.BOOL) gl.uniform1i(u.loc, v | 0);
          else if (u.type === gl.FLOAT_VEC2) gl.uniform2fv(u.loc, v);
          else if (u.type === gl.FLOAT_VEC3) gl.uniform3fv(u.loc, v);
          else if (u.type === gl.FLOAT_VEC4) gl.uniform4fv(u.loc, v);
          else throw new Error('unhandled uniform type for ' + name);
        }
        gl.activeTexture(gl.TEXTURE0);
      }
      // ---------- geometry ----------
      const quad = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, quad); gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([0, 0, 1, 0, 0, 1, 1, 1]), gl.STATIC_DRAW);
      const quadVao = gl.createVertexArray(); gl.bindVertexArray(quadVao); gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
      const dabBuf = gl.createBuffer();
      const dabVao = gl.createVertexArray(); gl.bindVertexArray(dabVao); gl.bindBuffer(gl.ARRAY_BUFFER, quad); gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
      gl.bindBuffer(gl.ARRAY_BUFFER, dabBuf);
      for (let i = 0; i < 5; i++) { gl.enableVertexAttribArray(1 + i); gl.vertexAttribPointer(1 + i, 4, gl.FLOAT, false, 80, i * 16); gl.vertexAttribDivisor(1 + i, 1); }
      const emptyVao = gl.createVertexArray();
      gl.disable(gl.BLEND); gl.disable(gl.SCISSOR_TEST); gl.disable(gl.DEPTH_TEST);
      // ---------- textures ----------
      const FMT = {
        rgba32f: [gl.RGBA32F, gl.RGBA, gl.FLOAT, gl.NEAREST], rgba16f: [gl.RGBA16F, gl.RGBA, gl.HALF_FLOAT, gl.LINEAR], rg16f: [gl.RG16F, gl.RG, gl.HALF_FLOAT, gl.LINEAR],
        r16f: [gl.R16F, gl.RED, gl.HALF_FLOAT, gl.LINEAR], rgba8: [gl.RGBA8, gl.RGBA, gl.UNSIGNED_BYTE, gl.NEAREST], r8: [gl.R8, gl.RED, gl.UNSIGNED_BYTE, gl.NEAREST],
      };
      function tex(w, h, fmt, data) {
        const [i, f, t, filt] = FMT[fmt], id = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, id);
        gl.texImage2D(gl.TEXTURE_2D, 0, i, w, h, 0, f, t, data || null);
        for (const [k, v] of [[gl.TEXTURE_MIN_FILTER, filt], [gl.TEXTURE_MAG_FILTER, filt], [gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE], [gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE]]) gl.texParameteri(gl.TEXTURE_2D, k, v);
        return id;
      }
      function fbo(...texs) {
        const f = gl.createFramebuffer(); gl.bindFramebuffer(gl.FRAMEBUFFER, f);
        texs.forEach((t, i) => gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0 + i, gl.TEXTURE_2D, t, 0));
        gl.drawBuffers(texs.map((_, i) => gl.COLOR_ATTACHMENT0 + i));
        const st = gl.checkFramebufferStatus(gl.FRAMEBUFFER);
        if (st !== gl.FRAMEBUFFER_COMPLETE) throw new Error('fbo incomplete 0x' + st.toString(16));
        return f;
      }
      const clearFbo = (f) => { gl.bindFramebuffer(gl.FRAMEBUFFER, f); gl.viewport(0, 0, W, H); gl.clearColor(0, 0, 0, 0); gl.clear(gl.COLOR_BUFFER_BIT); };
      const draw = () => { gl.bindVertexArray(quadVao); gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4); };
      const readFbo = gl.createFramebuffer(), drawFbo = gl.createFramebuffer();
      function blit(src, x0, y0, x1, y1, dst) {
        gl.bindFramebuffer(gl.READ_FRAMEBUFFER, readFbo); gl.framebufferTexture2D(gl.READ_FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, src, 0);
        gl.bindFramebuffer(gl.DRAW_FRAMEBUFFER, drawFbo); gl.framebufferTexture2D(gl.DRAW_FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, dst, 0);
        gl.drawBuffers([gl.COLOR_ATTACHMENT0]);
        gl.blitFramebuffer(x0, y0, x1, y1, x0, y0, x1, y1, gl.COLOR_BUFFER_BIT, gl.NEAREST);
        gl.bindFramebuffer(gl.READ_FRAMEBUFFER, null); gl.bindFramebuffer(gl.DRAW_FRAMEBUFFER, null);
      }
      // ---------- seeded state ----------
      const rngf = (a) => () => { a = (a + 0x6D2B79F5) >>> 0; let t = Math.imul(a ^ a >>> 15, 1 | a); t = (t + Math.imul(t ^ t >>> 7, 61 | t)) ^ t; return ((t ^ t >>> 14) >>> 0) / 4294967296; };
      function noiseField(seed, cell) {
        const r = rngf(seed), gw = Math.ceil(W / cell) + 3, g = new Float32Array(gw * gw);
        for (let i = 0; i < g.length; i++) g[i] = r();
        return (x, y) => { const fx = x / cell, fy = y / cell, ix = Math.floor(fx), iy = Math.floor(fy), tx = fx - ix, ty = fy - iy; const sx = tx * tx * (3 - 2 * tx), sy = ty * ty * (3 - 2 * ty);
          const a = g[iy * gw + ix], b = g[iy * gw + ix + 1], c2 = g[(iy + 1) * gw + ix], d = g[(iy + 1) * gw + ix + 1]; return (a + (b - a) * sx) * (1 - sy) + (c2 + (d - c2) * sx) * sy; };
      }
      // p0: K rgb + body thickness (a), p1: S rgb + solubility; a damp pool with pigment; leftover pigment in dry cells; state[1] = stale garbage.
      function seedState(seed) {
        const n = W * H * 4, p0 = new Float32Array(n), p1 = new Float32Array(n), w0 = new Float32Array(n), w1 = new Float32Array(n), g0 = new Float32Array(n), g1 = new Float32Array(n);
        const nA = noiseField(seed, 37), nB = noiseField(seed + 1, 23), nC = noiseField(seed + 2, 61), nD = noiseField(seed + 3, 9), r = rngf(seed + 9);
        for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
          const o = (y * W + x) * 4, body = Math.max(0, nA(x, y) * 1.5 - 0.6) * 0.03;
          p0[o] = 3 * body * (0.5 + nB(x, y)); p0[o + 1] = 2 * body * nC(x, y); p0[o + 2] = 1.5 * body; p0[o + 3] = body + 0.002 * nD(x, y);
          p1[o] = p1[o + 1] = p1[o + 2] = 20 * body; p1[o + 3] = body > 0 ? Math.min(1, 0.2 + 0.8 * nC(x, y)) : 0;
          const dx = (x - 0.3 * W) / (0.14 * W), dy = (y - 0.55 * H) / (0.11 * H), pool = Math.exp(-(dx * dx + dy * dy));
          const wd = pool > 0.12 ? 0.25 * pool * (0.7 + 0.6 * nB(x, y)) : 0;
          w0[o] = wd; w0[o + 1] = Math.min(1, 0.05 + 0.9 * pool + 0.15 * nC(x, y)); w0[o + 2] = wd > 0 ? 30 * r() : 0.5 * r(); w0[o + 3] = r() * 0.1;
          if (wd > 0) { w1[o] = 4 * wd * nA(x, y); w1[o + 1] = 2 * wd; w1[o + 2] = 0.5 * wd * nD(x, y); w1[o + 3] = 0.3 * wd; }
          else if (r() < 0.04) { w1[o] = 0.001 * r(); w1[o + 3] = 0.0005 * r(); }          // pigment stranded in a dry cell
          for (let k = 0; k < 4; k++) { g0[o + k] = 3 * r(); g1[o + k] = 3 * r(); }          // stale garbage in state[1]
        }
        // a cell or two of exact -0 / tiny denormal-ish water, to meet the odd cases of the idle test
        for (let i = 0; i < 40; i++) { const o = ((10 + 5 * i) % (W * H)) * 4; w0[o] = 0; w1[o] = 0; }
        return { p0, p1, w0, w1, g0, g1 };
      }
      // the paper (as jb_media_render.frag / the bake read it)
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
      const groundBytes = (() => { const r = rngf(77), b = new Uint8Array(W * H * 4); for (let i = 0; i < W * H; i++) { const a = Math.floor(r() * 256); b[i * 4] = Math.floor(a * r()); b[i * 4 + 1] = Math.floor(a * r()); b[i * 4 + 2] = Math.floor(a * r()); b[i * 4 + 3] = a; } return b; })();
      const paperU = { u_paperSurface: { tex: paperTex }, u_paperTexelPx: 2, u_paperSize: PS, u_paperHexTexels: 32, u_paperSlopeRange: SLOPE, u_paperRotatable: 1, u_paperHeightMean: 0.5,
        u_paperHBot: 0.2, u_paperHTop: 0.8, u_paperFluid: { tex: fluidTex }, u_paperFluidTexelPx: 0, u_paperFluidSize: 4, u_paperFluidHexTexels: 1 };
      const COMMON = { u_layerOrigin: [0, 0], u_layerScale: 1, u_targetSize: [W, H] };
      // ---------- an engine instance ----------
      // kind: 'old' (origin shaders + passes + formats) | 'new' | 'noRing' | 'noClear' | 'badIdle' (the last three are NEW with one thing broken)
      function makeEngine(kind, seed) {
        const isOld = kind === 'old', P = PROG[kind === 'old' ? 'old' : kind === 'badIdle' ? 'bad' : 'new'];
        const sd = seedState(seed), zero = new Float32Array(W * H * 4);
        const st = [0, 1].map((i) => ({ p0: tex(W, H, 'rgba32f', i ? sd.g0 : sd.p0), p1: tex(W, H, 'rgba32f', i ? sd.g1 : sd.p1), paper: tex(W, H, 'rgba32f', zero) }));
        const ws = [0, 1].map(() => ({ w0: tex(W, H, 'rgba32f', sd.w0), w1: tex(W, H, 'rgba32f', sd.w1), flux: tex(W, H, 'rgba16f') }));
        const run = tex(W, H, isOld ? 'rgba16f' : 'rg16f');
        const bw = (W + 1) >> 1, bh = (H + 1) >> 1, bead = [tex(bw, bh, 'rgba16f'), tex(bw, bh, 'rgba16f')];
        const in0 = tex(W, H, 'rgba16f'), in1 = tex(W, H, 'rgba16f');
        const bakeTex = tex(W, H, 'rgba16f'), fluidBake = tex(W, H, isOld ? 'rgba8' : 'r8'), waterBake = tex(W, H, isOld ? 'rgba16f' : 'r16f');
        const E = { kind, isOld, P, st, ws, run, bead, in0, in1, bakeTex, fluidBake, waterBake, wc: 0, bw, bh };
        E.bakeFbo = fbo(bakeTex, fluidBake, waterBake);
        E.beadFbo = bead.map((b) => fbo(b));
        E.fluxFbo = [0, 1].map((i) => fbo(ws[i].flux, run));
        E.updOld = [0, 1].map((i) => fbo(ws[i].w0, ws[i].w1, st[1].p0, st[1].p1));
        E.updNew = [0, 1, 2, 3].map((i) => fbo(ws[i >> 1].w0, ws[i >> 1].w1, st[i & 1].p0, st[i & 1].p1));
        E.pFbo0 = fbo(st[0].p0, st[0].p1);
        E.wetCopy = [fbo(ws[0].w0, ws[0].w1, ws[0].flux, run), fbo(ws[1].w0, ws[1].w1, ws[1].flux)];
        E.inFbo = fbo(in0, in1);
        E.lookTex = tex(W, H, 'rgba8'); E.lookFbo = fbo(E.lookTex);
        E.ground = tex(W, H, 'rgba8', groundBytes);
        // bake the paper (the engine's bakePaper)
        gl.bindFramebuffer(gl.FRAMEBUFFER, E.bakeFbo); gl.viewport(0, 0, W, H);
        use(P.bake, { ...COMMON, u_rect: [0, 0, W, H], ...paperU }); draw();
        clearFbo(E.inFbo);
        return E;
      }
      function bindCopy(E, src, rect) { use(E.P.copy, { ...COMMON, u_a: { tex: src.p0 }, u_b: { tex: src.p1 }, u_c: { tex: src.paper }, u_rect: rect }); }
      function copyRect(E, src, dstFbo, rect) {
        gl.bindFramebuffer(gl.FRAMEBUFFER, dstFbo); gl.viewport(0, 0, W, H); gl.disable(gl.BLEND); bindCopy(E, src, rect); draw();
      }
      function scissorTo(r) {
        gl.enable(gl.SCISSOR_TEST);
        const x0 = Math.max(0, Math.floor(r[0])), y0 = Math.max(0, Math.floor(r[1])), x1 = Math.min(W, Math.ceil(r[2])), y1 = Math.min(H, Math.ceil(r[3]));
        gl.scissor(x0, y0, Math.max(0, x1 - x0), Math.max(0, y1 - y0));
      }
      function beadPasses(E, w0, rect, beadCells) {
        const sigma = Math.min(4.8, Math.max(1.0, 0.25 * beadCells)), r = Math.min(12, Math.ceil(2.5 * sigma)), m = 3 + 2 * r;
        const hr = [Math.max(0, Math.floor(rect[0] / 2) - m), Math.max(0, Math.floor(rect[1] / 2) - m), Math.min(E.bw, Math.ceil(rect[2] / 2) + m), Math.min(E.bh, Math.ceil(rect[3] / 2) + m)];
        const pass = (f, pn, src, ax, ay) => {
          gl.bindFramebuffer(gl.FRAMEBUFFER, f); gl.viewport(0, 0, E.bw, E.bh);
          use(E.P.bead, { ...COMMON, u_rect: hr, u_targetSize: [E.bw, E.bh], u_sigma: sigma, u_pass: pn, u_w0: { tex: w0 }, u_src: { tex: src }, u_axis: [ax, ay] }); draw();
        };
        pass(E.beadFbo[0], 0, E.bead[1], 1, 0); pass(E.beadFbo[1], 1, E.bead[0], 1, 0); pass(E.beadFbo[0], 2, E.bead[1], 0, 1);
      }
      // One wet frame. Old: dab (whole window) -> per substep flux, update (-> state[1]), copy state[1] -> state[0]; clear the whole input.
      // New: dab scissored to the rectangle; ring copy; substeps ping-pong the paint planes; one copy if odd; scissored clear.
      function wetFrame(E, sc, F) {
        const isNew = !E.isOld, rect = F.rect, steps = F.steps, useInput = F.n > 0;
        if (useInput) {
          gl.bindFramebuffer(gl.FRAMEBUFFER, E.inFbo); gl.viewport(0, 0, W, H);
          if (isNew && rect) scissorTo(rect);
          gl.enable(gl.BLEND); gl.blendFunc(gl.ONE, gl.ONE); gl.blendEquation(gl.FUNC_ADD);
          use(E.P.dab, { ...COMMON, u_w0: { tex: E.ws[E.wc].w0 }, u_paperBake: { tex: E.bakeTex }, u_pxPerMm: P.PPM, u_fullMm: P.WC.fullMm });
          gl.bindVertexArray(dabVao); gl.bindBuffer(gl.ARRAY_BUFFER, dabBuf); gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(F.dabs), gl.STREAM_DRAW);
          gl.drawArraysInstanced(gl.TRIANGLE_STRIP, 0, 4, F.n);
          gl.disable(gl.BLEND); gl.disable(gl.SCISSOR_TEST);
        }
        if (!rect) return;
        const beadCells = P.WC.beadMm * P.PPM * 1, tilted = Math.hypot(sc.slope[0], sc.slope[1]) > 1e-4;
        const U = { ...P.wu, u_slope: sc.slope, u_cellMm: 1 / (P.PPM * 1), u_beadCells: beadCells, u_gran: sc.gran, ...COMMON, u_rect: rect };
        if (F.evap !== null) U.u_evap = F.evap;
        gl.disable(gl.BLEND);
        if (isNew && steps > 1 && E.kind !== 'noRing') {
          const ring = Math.max(4, Math.ceil(P.WC.steerScale * beadCells) + 3);
          const rx0 = Math.max(0, Math.floor(rect[0])), ry0 = Math.max(0, Math.floor(rect[1])), rx1 = Math.min(W, Math.ceil(rect[2])), ry1 = Math.min(H, Math.ceil(rect[3]));
          const ox0 = Math.max(0, rx0 - ring), oy0 = Math.max(0, ry0 - ring), ox1 = Math.min(W, rx1 + ring), oy1 = Math.min(H, ry1 + ring);
          const strip = (x0, y0, x1, y1) => { if (x1 > x0 && y1 > y0) blit(E.st[0].p0, x0, y0, x1, y1, E.st[1].p0); };
          strip(ox0, oy0, ox1, ry0); strip(ox0, ry1, ox1, oy1); strip(ox0, ry0, rx0, ry1); strip(rx1, ry0, ox1, ry1);
        }
        for (let k = 0; k < steps; k++) {
          const a = E.ws[E.wc], bi = 1 - E.wc, b = E.ws[bi], src = isNew ? E.st[k & 1] : E.st[0];
          if (tilted) beadPasses(E, a.w0, rect, beadCells);
          gl.bindFramebuffer(gl.FRAMEBUFFER, E.fluxFbo[bi]); gl.viewport(0, 0, W, H);
          use(E.P.flux, { ...U, u_bead: { tex: E.bead[0] }, u_p0: { tex: src.p0 }, u_w0: { tex: a.w0 }, u_flux: { tex: a.flux }, u_paperBake: { tex: E.bakeTex }, u_fluidBake: { tex: E.fluidBake }, u_waterBake: { tex: E.waterBake } });
          draw();
          gl.bindFramebuffer(gl.FRAMEBUFFER, isNew ? E.updNew[bi * 2 + 1 - (k & 1)] : E.updOld[bi]); gl.viewport(0, 0, W, H);
          use(E.P.update, { ...U, u_w0: { tex: a.w0 }, u_w1: { tex: a.w1 }, u_flux: { tex: b.flux }, u_run: { tex: E.run }, u_p0: { tex: src.p0 }, u_p1: { tex: src.p1 },
            u_paperBake: { tex: E.bakeTex }, u_waterBake: { tex: E.waterBake }, u_fluidBake: { tex: E.fluidBake }, u_in0: { tex: E.in0 }, u_in1: { tex: E.in1 }, u_useInput: (k === 0 && useInput) ? 1 : 0 });
          draw();
          if (!isNew) copyRect(E, E.st[1], E.pFbo0, rect);
          E.wc = bi;
        }
        if (isNew && (steps & 1) === 1) copyRect(E, E.st[1], E.pFbo0, rect);
        if (useInput) {
          if (!isNew) clearFbo(E.inFbo);
          else if (E.kind !== 'noClear') { gl.bindFramebuffer(gl.FRAMEBUFFER, E.inFbo); gl.viewport(0, 0, W, H); scissorTo(rect); gl.clearColor(0, 0, 0, 0); gl.clear(gl.COLOR_BUFFER_BIT); gl.disable(gl.SCISSOR_TEST); }
        }
        if (F.dryNow) { for (const f of E.wetCopy) clearFbo(f); }
      }
      function renderLook(E) {
        gl.bindFramebuffer(gl.FRAMEBUFFER, E.lookFbo); gl.viewport(0, 0, W, H); gl.disable(gl.BLEND);
        const w = E.ws[E.wc];
        const lamp = [-0.55, 0.55, 0.62], ln = Math.hypot(...lamp);
        use(E.P.render, { ...COMMON, ...paperU, u_p0: { tex: E.st[0].p0 }, u_p1: { tex: E.st[0].p1 }, u_paperState: { tex: E.st[0].paper }, u_ground: { tex: E.ground }, u_paperBake: { tex: E.bakeTex },
          u_w0: { tex: w.w0 }, u_w1: { tex: w.w1 }, u_pan: [0, 0], u_zoom: 1, u_pxPerMm: P.PPM, u_toothMm: 0.09, u_paperColor: [0.95, 0.93, 0.88], u_lamp: lamp.map(v => v / ln),
          u_relief: 0, u_sheen: 0.06, u_capMm: 0.004, u_mode: 0, u_impasto: 1.4, u_look: 1 });
        gl.bindVertexArray(emptyVao); gl.drawArrays(gl.TRIANGLES, 0, 3);
      }
      // ---------- readback ----------
      const rf = gl.createFramebuffer();
      function readF(texId) { gl.bindFramebuffer(gl.FRAMEBUFFER, rf); gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, texId, 0); const a = new Float32Array(W * H * 4); gl.readPixels(0, 0, W, H, gl.RGBA, gl.FLOAT, a); return new Uint32Array(a.buffer); }
      function readB(texId) { gl.bindFramebuffer(gl.FRAMEBUFFER, rf); gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, texId, 0); const a = new Uint8Array(W * H * 4); gl.readPixels(0, 0, W, H, gl.RGBA, gl.UNSIGNED_BYTE, a); return a; }
      function snapshot(E) {
        const w = E.ws[E.wc];
        return { w0: readF(w.w0), w1: readF(w.w1), p0: readF(E.st[0].p0), p1: readF(E.st[0].p1), look: readB(E.lookTex) };
      }
      const diffPx = (a, b, stride, mask) => { let n = 0; for (let i = 0; i < a.length; i += stride) { for (let k = 0; k < stride; k++) if ((mask ? mask[k] : 1) && a[i + k] !== b[i + k]) { n++; break; } } return n; };
      const FIELDS = ['w0', 'w1', 'p0', 'p1'];
      function compare(A, B) {
        const r = { w0: diffPx(A.w0, B.w0, 4), w1: diffPx(A.w1, B.w1, 4), p0: diffPx(A.p0, B.p0, 4), p1: diffPx(A.p1, B.p1, 4), look: diffPx(A.look, B.look, 4) };
        r.total = r.w0 + r.w1 + r.p0 + r.p1 + r.look; return r;
      }
      // ---------- the run ----------
      const KINDS = ['old', 'new', 'noRing', 'noClear', 'badIdle'];
      const results = []; let bakeInfo = null;
      for (const sc of P.scenarios) {
        const E = {}; for (const k of KINDS) E[k] = makeEngine(k, sc.seed * 101 + 7);
        if (!bakeInfo) {   // baked values: old RGBA formats vs new narrow ones (.b of the height, .r of fluid and water)
          const ho = readF(E.old.bakeTex), hn = readF(E.new.bakeTex), fo = readB(E.old.fluidBake), fn = readB(E.new.fluidBake);
          const wo = readF(E.old.waterBake), wn = readF(E.new.waterBake);
          let mh = 0, mf = 0, mw = 0, varied = new Set();
          for (let i = 0; i < W * H; i++) { if (ho[i * 4 + 2] !== hn[i * 4 + 2]) mh++; if (fo[i * 4] !== fn[i * 4]) mf++; if (wo[i * 4] !== wn[i * 4]) mw++; varied.add(fo[i * 4]); }
          bakeInfo = { pixels: W * H, heightMismatch: mh, fluidMismatch: mf, waterMismatch: mw, fluidDistinctValues: varied.size };
        }
        const r = { name: sc.name, frames: 0, frameSubsteps: sc.frames.reduce((s, f) => s + (f.rect ? f.steps : 0), 0), pixelsCompared: 0,
          mismatch: Object.fromEntries(KINDS.slice(1).map(k => [k, { w0: 0, w1: 0, p0: 0, p1: 0, look: 0, total: 0, framesDetected: 0 }])), wetPixelsMax: 0, p0Changed: 0, lookDistinct: 0 };
        let first = null;
        try {
          for (let fi = 0; fi < sc.frames.length; fi++) {
            const F = sc.frames[fi];
            for (const k of KINDS) { wetFrame(E[k], sc, F); renderLook(E[k]); }
            const snaps = {}; for (const k of KINDS) snaps[k] = snapshot(E[k]);
            if (!first) first = snaps.old;
            for (const k of KINDS.slice(1)) {
              const d = compare(snaps.old, snaps[k]);
              for (const f of [...FIELDS, 'look', 'total']) r.mismatch[k][f] += d[f];
              if (d.total > 0) r.mismatch[k].framesDetected++;
            }
            r.frames++; r.pixelsCompared += W * H * 5;
            let wp = 0; const f0 = new Float32Array(snaps.old.w0.buffer); for (let i = 0; i < W * H; i++) if (f0[i * 4] > 0.002) wp++;
            r.wetPixelsMax = Math.max(r.wetPixelsMax, wp);
            const ge = gl.getError(); if (ge !== gl.NO_ERROR) throw new Error('GL error 0x' + ge.toString(16) + ' at frame ' + fi);
          }
          const last = snapshot(E.old);
          r.p0Changed = diffPx(first.p0, last.p0, 4) + diffPx(first.p1, last.p1, 4);
          const seen = new Set(); for (let i = 0; i < last.look.length; i += 4) seen.add(last.look[i] | last.look[i + 1] << 8 | last.look[i + 2] << 16 | last.look[i + 3] << 24); r.lookDistinct = seen.size;
        } catch (e) { r.error = String(e.message || e); }
        results.push(r);
      }
      return { results, bakeInfo, missingUniforms: [...missing].sort() };
    }, payload);
  } finally {
    await browser.close();
  }
  if (out.error) { console.log(JSON.stringify({ pass: false, ...out })); process.exit(1); }
  const tilted = (name) => /tilted/.test(name);
  let newBad = 0, compared = 0, ringDetect = 0, ringFlat = 0, clearDetect = 0, idleDetect = 0, errors = 0;
  for (const r of out.results) {
    if (r.error) errors++;
    newBad += r.mismatch.new.total; compared += r.pixelsCompared;
    if (tilted(r.name)) ringDetect += r.mismatch.noRing.total; else ringFlat += r.mismatch.noRing.total;
    clearDetect += r.mismatch.noClear.total; idleDetect += r.mismatch.badIdle.total;
  }
  const bake = out.bakeInfo;
  const bakeOk = bake.heightMismatch === 0 && bake.fluidMismatch === 0 && bake.waterMismatch === 0 && bake.fluidDistinctValues > 8;
  const nonvacuous = out.results.every(r => r.wetPixelsMax > 500 && r.p0Changed > 100 && r.lookDistinct > 50);
  const pass = errors === 0 && newBad === 0 && bakeOk && ringDetect > 0 && clearDetect > 0 && idleDetect > 0 && nonvacuous;
  console.log(JSON.stringify({ pass, base: BASE, size: SIZE, totals: { pixelFieldsCompared: compared, newMismatchedPixels: newBad, controlRingOnTilted: ringDetect, controlRingOnFlat_informational: ringFlat,
    controlNoClear: clearDetect, controlBadIdle: idleDetect }, bake, nonvacuous, perScenario: out.results, unsetUniforms: out.missingUniforms }, null, 1));
  process.exit(pass ? 0 : 1);
})().catch(e => { console.log(JSON.stringify({ pass: false, error: String(e && e.stack || e) })); process.exit(1); });
