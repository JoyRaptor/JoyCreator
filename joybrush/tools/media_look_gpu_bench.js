// joybrush/tools/media_look_gpu_bench.js — the media look pass's GPU cost and compile time, window build vs atlas build,
// on a REAL GPU: the PC's (--gpu, local Edge) or a phone's through its Chrome (--cdp <port>, after
// `adb forward tcp:<port> localabstract:chrome_devtools_remote` with Chrome open on the phone). WebGL2 over ANGLE reaches
// the same GPU and driver compiler as the app (Note 9: Adreno 630), so a shader's cost shows here without an APK.
//
// Variants (same textures, same 1024² area = 16 tiles of 256, look as the app draws it):
//   window      the window build, one draw;
//   atlasTable  the atlas build drawn slot by slot with no slot handed to the fragment: every read through the page table
//               (what shipped before 2026-10-18's u_screenSlot);
//   atlasNbr    the look's atlas build (JB_ATLAS_NBR): slot by slot, each draw handed its 3×3 neighbour slots (no table).
// Reports per variant: compile+link ms (first use) and the median of [--reps] timed draws (gl.finish via a 1 px read).
//
// Run: NODE_PATH=<dir with playwright-core> node joybrush/tools/media_look_gpu_bench.js [--cdp 9333 | --gpu] [--reps 5]
const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright-core');
const SHADER_DIR = path.join(__dirname, '..', 'shaders');
function argVal(name, dflt) { const i = process.argv.indexOf('--' + name); return i >= 0 && i + 1 < process.argv.length ? process.argv[i + 1] : dflt; }
const INCLUDE_RE = /^#include\s+"([^"]+)"$/;
function expand(rel, seen = new Set()) {
  if (seen.has(rel)) return '';
  seen.add(rel);
  const text = fs.readFileSync(path.join(SHADER_DIR, rel), 'utf8').replace(/\r\n/g, '\n');
  return text.split('\n').map(l => { const m = l.trim().match(INCLUDE_RE); return m ? expand(m[1], seen) : l; }).join('\n');
}
const define = (src, ...names) => src.replace(/^(#version[^\n]*\n)/, (v) => v + names.map(n => '#define ' + n + '\n').join(''));
// A comment with a fresh number makes every run's source new, so a driver's shader cache cannot hide the compile.
const fresh = (src) => src.replace(/^(#version[^\n]*\n)/, (v) => v + '// run ' + Date.now() + Math.random() + '\n');
const SRC = {
  vert: expand('media/jb_media_screen.vert'), frag: expand('media/jb_media_render.frag'),
  vertA: define(expand('media/jb_media_screen.vert'), 'JB_ATLAS'), fragA: define(expand('media/jb_media_render.frag'), 'JB_ATLAS'),
  fragN: define(expand('media/jb_media_render.frag'), 'JB_ATLAS', 'JB_ATLAS_NBR'),
};
for (const k of Object.keys(SRC)) SRC[k] = fresh(SRC[k]);

async function PAGE(P) {
  const out = { variants: {} };
  const c = document.createElement('canvas'); c.width = c.height = 16; document.body.appendChild(c);
  const gl = c.getContext('webgl2');
  if (!gl) return { error: 'no WebGL2' };
  const dbg = gl.getExtension('WEBGL_debug_renderer_info');
  out.renderer = dbg ? gl.getParameter(dbg.UNMASKED_RENDERER_WEBGL) : gl.getParameter(gl.RENDERER);
  const S = 1536, N = S / 256, AREA = 1024;
  function prog(vs, fs) {
    const t0 = performance.now();
    const p = gl.createProgram();
    for (const [type, src] of [[gl.VERTEX_SHADER, vs], [gl.FRAGMENT_SHADER, fs]]) {
      const s = gl.createShader(type); gl.shaderSource(s, src); gl.compileShader(s);
      if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(s));
      gl.attachShader(p, s);
    }
    gl.linkProgram(p);
    if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(p));
    return { p, ms: performance.now() - t0 };
  }
  // Seeded state: paint with body (the shadow march does real work), water, dry flakes, a paper bake.
  let seed = 12345; const rnd = () => ((seed = (seed * 1103515245 + 12345) >>> 0) / 4294967296);
  function texF(fill) {
    const a = new Float32Array(S * S * 4);
    for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) { const i = (y * S + x) * 4; fill(a, i, x, y); }
    const t = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, t);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA32F, S, S, 0, gl.RGBA, gl.FLOAT, a);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST);
    return t;
  }
  const blob = (x, y) => Math.max(0, Math.sin(x * 0.013) * Math.cos(y * 0.011));
  const T = {
    p0: texF((a, i, x, y) => { const b = blob(x, y); a[i] = 0.3 * b; a[i + 1] = 0.2 * b; a[i + 2] = 0.1 * b; a[i + 3] = 0.05 * b + 0.002 * rnd(); }),
    p1: texF((a, i, x, y) => { const b = blob(x, y); a[i] = 0.4 * b; a[i + 1] = 0.4 * b; a[i + 2] = 0.4 * b; a[i + 3] = 0.5 * b; }),
    paper: texF((a, i) => { a[i] = 0.1 * rnd(); a[i + 1] = 0.001 * rnd(); a[i + 2] = 0.0005 * rnd(); }),
    w0: texF((a, i, x, y) => { const b = blob(y, x); a[i] = 0.05 * b; a[i + 1] = 0.5 * b; }),
    w1: texF((a, i, x, y) => { const b = blob(y, x); a[i] = 0.02 * b; a[i + 3] = 0.01 * b; }),
    bake: texF((a, i) => { a[i + 2] = rnd(); }),
  };
  const ground = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, ground);
  gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, S, S, 0, gl.RGBA, gl.UNSIGNED_BYTE, null);
  // The page table: identity (tile x, y → slot y·N + x), as a scattered atlas costs the same per read.
  const table = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, table);
  const tb = new Int32Array(N * N); for (let i = 0; i < N * N; i++) tb[i] = i;
  gl.texImage2D(gl.TEXTURE_2D, 0, gl.R32I, N, N, 0, gl.RED_INTEGER, gl.INT, tb);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST);
  const look = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, look);
  gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, S, S, 0, gl.RGBA, gl.UNSIGNED_BYTE, null);
  const fbo = gl.createFramebuffer(); gl.bindFramebuffer(gl.FRAMEBUFFER, fbo);
  gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, look, 0);
  const vao = gl.createVertexArray();
  const lamp = [-0.55, 0.55, 0.62], ln = Math.hypot(...lamp);
  function setUniforms(p, atlas) {
    gl.useProgram(p);
    const U = (n) => gl.getUniformLocation(p, n);
    let unit = 0;
    const tex = (n, t) => { const l = U(n); if (l === null) return; gl.activeTexture(gl.TEXTURE0 + unit); gl.bindTexture(gl.TEXTURE_2D, t); gl.uniform1i(l, unit++); };
    tex('u_p0', T.p0); tex('u_p1', T.p1); tex('u_paperState', T.paper); tex('u_w0', T.w0); tex('u_w1', T.w1); tex('u_paperBake', T.bake); tex('u_ground', ground);
    if (atlas) tex('u_atlasTable', table);
    gl.uniform2f(U('u_targetSize'), S, S); gl.uniform2f(U('u_layerOrigin'), 0, 0); gl.uniform1f(U('u_layerScale'), 1);
    gl.uniform2f(U('u_pan'), 0, 0); gl.uniform1f(U('u_zoom'), 1); gl.uniform1f(U('u_pxPerMm'), 12); gl.uniform1f(U('u_toothMm'), 0.09);
    gl.uniform3f(U('u_paperColor'), 0.95, 0.93, 0.88); gl.uniform3f(U('u_lamp'), lamp[0] / ln, lamp[1] / ln, lamp[2] / ln);
    gl.uniform1f(U('u_relief'), 0); gl.uniform1f(U('u_sheen'), 0.06); gl.uniform1f(U('u_capMm'), 0.004); gl.uniform1i(U('u_mode'), 0);
    gl.uniform1f(U('u_impasto'), 1.4); gl.uniform1f(U('u_look'), 1);
    if (atlas) {
      gl.uniform4i(U('u_atlasGrid'), 0, 0, N, N); gl.uniform1i(U('u_atlasSlotsX'), N); gl.uniform1i(U('u_atlasShift'), 8);
      gl.uniform4i(U('u_atlasWrap'), 0, 0, 0, 0); gl.uniform4i(U('u_atlasWalls'), 0, 0, 0, 0);
    }
  }
  const px = new Uint8Array(4);
  const finish = () => gl.readPixels(0, 0, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, px);
  function draw(v) {
    gl.bindFramebuffer(gl.FRAMEBUFFER, fbo); gl.viewport(0, 0, S, S); gl.bindVertexArray(vao);
    gl.enable(gl.SCISSOR_TEST);
    if (v.name === 'window') { gl.scissor(0, 0, AREA, AREA); gl.drawArrays(gl.TRIANGLES, 0, 3); }
    else for (let ty = 0; ty < AREA / 256; ty++) for (let tx = 0; tx < AREA / 256; tx++) {
      gl.scissor(tx * 256, ty * 256, 256, 256);
      if (v.name === 'atlasNbr') {
        const nb = new Int32Array(18);
        for (let dy = -1; dy <= 1; dy++) for (let dx = -1; dx <= 1; dx++) {
          const x = tx + dx, y = ty + dy, i = ((dy + 1) * 3 + dx + 1) * 2, here = x >= 0 && y >= 0 && x < N && y < N;
          nb[i] = here ? x * 256 : -1; nb[i + 1] = here ? y * 256 : -1;
        }
        gl.uniform2i(gl.getUniformLocation(v.p, 'u_nbrTile'), tx, ty);
        gl.uniform2iv(gl.getUniformLocation(v.p, 'u_nbrSlot'), nb);
      }
      gl.drawArrays(gl.TRIANGLES, 0, 3);
    }
    gl.disable(gl.SCISSOR_TEST);
  }
  const only = P.only ? P.only.split(',') : null;
  const variants = [{ name: 'window', vs: P.vert, fs: P.frag }, { name: 'atlasNbr', vs: P.vertA, fs: P.fragN }, { name: 'atlasTable', vs: P.vertA, fs: P.fragA }]
    .filter(v => !only || only.includes(v.name));
  for (const v of variants) {
    const r = {};
    try {
      finish(); const c = prog(v.vs, v.fs); v.p = c.p; r.compileMs = Math.round(c.ms);
      setUniforms(v.p, v.name !== 'window');
      finish();
      const t0 = performance.now(); draw(v); finish(); r.firstDrawMs = +(performance.now() - t0).toFixed(1);
      const ts = [];
      for (let i = 0; i < P.reps; i++) { const t = performance.now(); draw(v); finish(); ts.push(performance.now() - t); }
      ts.sort((a, b) => a - b);
      r.drawMs = ts.map(x => +x.toFixed(1)); r.medianMs = +ts[ts.length >> 1].toFixed(1);
      const e = gl.getError(); if (e) r.glError = e;
      r.contextLost = gl.isContextLost();
    } catch (e) { r.error = String(e.message || e).slice(0, 400); }
    out.variants[v.name] = r;
  }
  return out;
}

(async () => {
  const cdp = argVal('cdp', null);
  let browser, page;
  if (cdp) {
    browser = await chromium.connectOverCDP('http://127.0.0.1:' + cdp);
    const ctx = browser.contexts()[0];
    page = await ctx.newPage();
  } else {
    const exe = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';
    browser = await chromium.launch({ executablePath: exe, args: process.argv.includes('--gpu') ? ['--use-angle=d3d11', '--enable-gpu', '--ignore-gpu-blocklist'] : ['--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
    page = await browser.newPage();
  }
  page.setDefaultTimeout(600000);
  await page.setContent('<!doctype html><title>look bench</title><body></body>');
  const r = await page.evaluate(PAGE, { ...SRC, reps: parseInt(argVal('reps', '5'), 10), only: argVal('only', null) });
  console.log(JSON.stringify(r, null, 1));
  await page.close();
  if (!cdp) await browser.close(); else await browser.close().catch(() => {});
})().catch(e => { console.log(JSON.stringify({ error: String(e.message || e) })); process.exit(1); });
