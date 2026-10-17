#!/usr/bin/env node
// Tile-board watercolour finish and periodic-blend gates, real shaders on WebGL2/SwiftShader. No Android build or phone.
// NODE_PATH=<playwright-core runtime> node joybrush/tools/tile_wc_seam_check.js [--base <git ref>]
//
// 1. PARITY: with no Tile rect the finish is bit-identical to the --base shaders (default HEAD), on rich inputs
//    (noise bands in all nine neighbours, mipmapped wash and grain, a varying paper, every pooling knob on).
// 2. PAPER PARITY: jb_tilePaperRead through the refactored jb_tile_paper.glsl returns the same float bits as --base,
//    armed and unarmed (the 4-corner weights moved into jb_tilePeriodicWeights).
// 3. SEAM: armed, the finished picture is periodic with the board, F(p) == F(p + period), over a window that spans
//    every seam and corner of a board whose edges are not on tile borders, with bands that repeat with the board.
//    Controls that must FAIL: the same render unarmed, and a mutant whose armed wash read is the unarmed one.
const fs = require('fs'), path = require('path'), { execFileSync } = require('child_process');
const { chromium } = require('playwright-core');
const root = path.join(__dirname, '..');
const option = name => { const i = process.argv.indexOf(name); return i < 0 ? null : process.argv[i + 1]; };
const base = option('--base') || 'HEAD';
const fromDisk = name => fs.readFileSync(path.join(root, 'shaders', name), 'utf8');
const fromGit = name => execFileSync('git', ['show', `${base}:joybrush/shaders/${name}`], { cwd: root, encoding: 'utf8', maxBuffer: 1 << 24 });
const expand = read => { const src = name => read(name).replace(/^#include "([^"]+)"/gm, (_, inc) => src(inc)); return src; };
const now = expand(fromDisk), was = expand(fromGit);

async function gpu({ vert, finishNow, finishWas, paperProbeNow, paperProbeWas, probeVert }) {
  const canvas = document.createElement('canvas'), gl = canvas.getContext('webgl2', { antialias: false });
  if (!gl || !gl.getExtension('EXT_color_buffer_float')) throw Error('Float WebGL2 required');
  const results = {}, failures = [];
  const check = (name, ok, detail) => { results[name] = detail; if (!ok) failures.push(name); };
  const compile = (type, code) => { const s = gl.createShader(type); gl.shaderSource(s, code); gl.compileShader(s); if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw Error(gl.getShaderInfoLog(s)); return s; };
  const program = (vs, fs) => { const p = gl.createProgram(); gl.attachShader(p, compile(gl.VERTEX_SHADER, vs)); gl.attachShader(p, compile(gl.FRAGMENT_SHADER, fs)); gl.linkProgram(p); if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw Error(gl.getProgramInfoLog(p)); return p; };
  const armedWash = 'if(tiled) wash=jb_tileTextureR(u_backdrop,v_docPx,u_backdropOffset,u_backdropWorld,0.5);';
  if (!finishNow.includes(armedWash)) throw Error('armed wash anchor changed');
  const armedGrain = 'if(tiled) mask*=1.0-u_grain*0.8*jb_tileTextureR(u_fineGrain,v_docPx,vec2(0.0),256.0,0.5);';
  if (!finishNow.includes(armedGrain)) throw Error('armed grain anchor changed');
  const finishMutant = finishNow.replace(armedWash, 'if(tiled) wash=texture(u_backdrop,(v_docPx+u_backdropOffset)/u_backdropWorld).r;');
  const grainMutant = finishNow.replace(armedGrain, 'if(tiled) mask*=1.0-u_grain*0.8*texture(u_fineGrain,v_docPx/256.0).r;');
  const pNow = program(vert, finishNow), pWas = program(vert, finishWas), pMutant = program(vert, finishMutant), pGrainMutant = program(vert, grainMutant);
  const probeNow = program(probeVert, paperProbeNow), probeWas = program(probeVert, paperProbeWas);

  let seed = 20261016;
  const rand = () => { seed = (seed + 0x6D2B79F5) | 0; let t = Math.imul(seed ^ (seed >>> 15), seed | 1); t ^= t + Math.imul(t ^ (t >>> 7), t | 61); return ((t ^ (t >>> 14)) >>> 0) / 4294967296; };
  function texture(w, h, values, { floating = false, mip = false, filter = 'nearest' } = {}) {
    const t = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, t);
    gl.texImage2D(gl.TEXTURE_2D, 0, floating ? gl.RGBA16F : gl.RGBA8, w, h, 0, gl.RGBA, floating ? gl.FLOAT : gl.UNSIGNED_BYTE, values);
    if (mip) gl.generateMipmap(gl.TEXTURE_2D);
    const lin = filter === 'linear';
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, mip ? gl.LINEAR_MIPMAP_LINEAR : lin ? gl.LINEAR : gl.NEAREST);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, lin || mip ? gl.LINEAR : gl.NEAREST);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.REPEAT); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.REPEAT);
    return t;
  }
  const noise = (w, h) => { const b = new Uint8Array(w * h * 4); for (let i = 0; i < w * h; i++) { const v = Math.floor(rand() * 256); b[i * 4] = b[i * 4 + 1] = b[i * 4 + 2] = v; b[i * 4 + 3] = 255; } return b; };
  const wash = texture(256, 256, noise(256, 256), { mip: true });
  const grain = texture(128, 128, noise(128, 128), { mip: true });
  const paperBytes = new Uint8Array(64 * 64 * 4);
  for (let i = 0; i < 64 * 64; i++) { const h = rand(); paperBytes[i * 4] = Math.floor(rand() * 256); paperBytes[i * 4 + 1] = Math.floor(rand() * 256); paperBytes[i * 4 + 2] = Math.floor(h * 255); paperBytes[i * 4 + 3] = Math.floor(h * h * 255); }
  const paper = texture(64, 64, paperBytes, { mip: true });
  const vao = gl.createVertexArray(); gl.bindVertexArray(vao);
  const quad = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, quad); gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([0,0, 1,0, 0,1, 1,1]), gl.STATIC_DRAW);
  gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
  const bind = (p, name, unit, tex) => { gl.activeTexture(gl.TEXTURE0 + unit); gl.bindTexture(gl.TEXTURE_2D, tex); gl.uniform1i(gl.getUniformLocation(p, name), unit); };
  function paperUniforms(p, rect) {
    const u = n => gl.getUniformLocation(p, n), f = (n, v) => gl.uniform1f(u(n), v);
    bind(p, 'u_paperSurface', 11, paper); bind(p, 'u_paperFluid', 12, paper);
    f('u_paperTexelPx', 1.7); f('u_paperSize', 64); f('u_paperHexTexels', 16); f('u_paperSlopeRange', .1); f('u_paperHeightMean', .47);
    gl.uniform1i(u('u_paperRotatable'), 0); gl.uniform4f(u('u_tilePaperRect'), ...rect);
  }
  function target(n, floating) {
    const t = texture(n, n, null, { floating }), fb = gl.createFramebuffer(); gl.bindFramebuffer(gl.FRAMEBUFFER, fb);
    gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, t, 0);
    if (gl.checkFramebufferStatus(gl.FRAMEBUFFER) !== gl.FRAMEBUFFER_COMPLETE) throw Error('Incomplete framebuffer');
    gl.viewport(0, 0, n, n); gl.clearColor(0, 0, 0, 0); gl.clear(gl.COLOR_BUFFER_BIT); return { t, fb };
  }
  const N = 128;   // doc px per band/output tile in this harness (scale 1)
  const tint = [.31, .68, .88], decode = x => x <= .04045 ? x / 12.92 : ((x + .055) / 1.055) ** 2.4;
  // One output tile of the ACTUAL finish program at tile (kx, ky); bands come from band(kx, ky) for the 3x3 neighbours.
  function finish(p, kx, ky, band, rect) {
    // Make every neighbour texture BEFORE binding any: creating one binds it on the active unit and would evict a band.
    for (let dy = -1; dy <= 1; dy++) for (let dx = -1; dx <= 1; dx++) band(kx + dx, ky + dy);
    const out = target(N, false); gl.disable(gl.BLEND); gl.useProgram(p); gl.bindVertexArray(vao);
    const u = n => gl.getUniformLocation(p, n), f = (n, v) => gl.uniform1f(u(n), v);
    gl.uniform2f(u('u_tileOrigin'), kx * N, ky * N); f('u_outputDocSize', N); f('u_scale', 1); gl.uniform1i(u('u_bandTileSize'), N);
    for (let dy = -1; dy <= 1; dy++) for (let dx = -1; dx <= 1; dx++) bind(p, 'u_band' + (dx + 1) + (dy + 1), (dy + 1) * 3 + dx + 1, band(kx + dx, ky + dy));
    bind(p, 'u_backdrop', 9, wash); bind(p, 'u_fineGrain', 10, grain); paperUniforms(p, rect);
    gl.uniform1i(u('u_hasPaper'), 1); gl.uniform3f(u('u_tintSrgb'), ...tint); gl.uniform3f(u('u_tintLinear'), ...tint.map(decode));
    gl.uniform2f(u('u_gravityDirection'), 0, 1);
    for (const [n, v] of [['edgeWidth', 3], ['edgePool', .65], ['edgeAlpha', .72], ['gravityWidth', 36], ['gravityPool', .57], ['paperCut', .5], ['pooling', 1],
      ['backdropWorld', 301.3], ['textureStrength', 1], ['textureContrast', 1.5], ['grain', .4]]) f('u_' + n, v);
    gl.uniform2f(u('u_backdropOffset'), 113.25, -287.5); gl.uniform1i(u('u_textureInvert'), 0);
    gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
    const bytes = new Uint8Array(N * N * 4); gl.readPixels(0, 0, N, N, gl.RGBA, gl.UNSIGNED_BYTE, bytes);
    gl.deleteFramebuffer(out.fb); gl.deleteTexture(out.t); return bytes;
  }
  const bandCache = new Map();
  const bandTex = (key, fill) => { if (!bandCache.has(key)) bandCache.set(key, texture(N, N, fill(), { floating: true })); return bandCache.get(key); };
  const diff = (a, b) => { let count = 0, max = 0; for (let i = 0; i < a.length; i++) { const d = Math.abs(a[i] - b[i]); if (d) count++; if (d > max) max = d; } return { mismatches: count, max }; };

  // ---- 1. Unarmed parity, bit for bit, against the base shader. Noise bands in all nine neighbours.
  const noiseBand = (kx, ky) => bandTex('noise' + kx + ',' + ky, () => { const v = new Float32Array(N * N * 4); for (let i = 0; i < N * N; i++) { const on = rand() < .7; v[i * 4] = on ? rand() * .7 : 0; v[i * 4 + 1] = on ? rand() * .4 : 0; v[i * 4 + 2] = on ? rand() * .2 : 0; } return v; });
  let total = 0, worst = { mismatches: 0, max: 0 };
  for (const [kx, ky] of [[0, 0], [-3, 2], [5, -4]]) {
    const d = diff(finish(pNow, kx, ky, noiseBand, [0, 0, 0, 0]), finish(pWas, kx, ky, noiseBand, [0, 0, 0, 0]));
    total += d.mismatches; worst = { mismatches: worst.mismatches + d.mismatches, max: Math.max(worst.max, d.max) };
  }
  check('unarmed_finish_bit_identical_to_base', total === 0, { ...worst, tiles: 3, pixels: 3 * N * N });

  // ---- 2. Paper read parity (float bits) through the refactored tile paper include.
  function probe(p, rect) {
    const out = target(N, true); gl.useProgram(p); gl.bindVertexArray(vao); paperUniforms(p, rect);
    gl.uniform2f(gl.getUniformLocation(p, 'u_origin'), -61.5, 37.25); gl.uniform1f(gl.getUniformLocation(p, 'u_span'), 3.3 * N);
    gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
    const v = new Float32Array(N * N * 4); gl.readPixels(0, 0, N, N, gl.RGBA, gl.FLOAT, v); gl.deleteFramebuffer(out.fb); gl.deleteTexture(out.t);
    return new Uint32Array(v.buffer);
  }
  for (const [name, rect] of [['armed', [-37, 23, 150, 110]], ['unarmed', [0, 0, 0, 0]]]) {
    const d = diff(probe(probeNow, rect), probe(probeWas, rect));
    check('paper_read_bits_identical_' + name, d.mismatches === 0, d);
  }

  // ---- 3. Armed seam: bands that repeat with the board; the finish must repeat with it too.
  const rect = [-37, 23, 150, 110];
  const wrap = (v, o, p) => o + (((v - o) % p) + p) % p;
  const blobs = Array.from({ length: 7 }, () => [rect[0] + rand() * rect[2], rect[1] + rand() * rect[3], 9 + rand() * 30, rand()]);
  blobs.push([rect[0] + 2, rect[1] + 50, 28, .8], [rect[0] + 70, rect[1] + rect[3] - 3, 24, .6]);   // two that straddle seams
  const periodicBand = (kx, ky) => bandTex('periodic' + kx + ',' + ky, () => {
    const v = new Float32Array(N * N * 4);
    for (let y = 0; y < N; y++) for (let x = 0; x < N; x++) {
      const qx = wrap(kx * N + x + .5, rect[0], rect[2]), qy = wrap(ky * N + y + .5, rect[1], rect[3]);
      let c = 0;
      for (const [bx, by, r, w] of blobs) for (const ox of [-rect[2], 0, rect[2]]) for (const oy of [-rect[3], 0, rect[3]])
        if (Math.hypot(qx - bx - ox, qy - by - oy) < r) c = Math.max(c, .35 + .5 * w);
      const at = (y * N + x) * 4; v[at] = c; v[at + 1] = c * .5; v[at + 2] = c * .25;
    }
    return v;
  });
  const K = { x0: -2, x1: 2, y0: -1, y1: 2 };   // doc window [-256, 384) x [-128, 384): every seam and corner, with margins
  function picture(p, r) {
    const W = (K.x1 - K.x0 + 1) * N, H = (K.y1 - K.y0 + 1) * N, img = new Uint8Array(W * H * 4);
    for (let ky = K.y0; ky <= K.y1; ky++) for (let kx = K.x0; kx <= K.x1; kx++) {
      const t = finish(p, kx, ky, periodicBand, r);
      for (let y = 0; y < N; y++) img.set(t.subarray(y * N * 4, (y + 1) * N * 4), (((ky - K.y0) * N + y) * W + (kx - K.x0) * N) * 4);
    }
    return { img, W, H, at: (x, y) => ((y - K.y0 * N) * W + (x - K.x0 * N)) * 4 };
  }
  // Compare F(p) with F(p + period) for every p whose translate is in the window and whose 40 px neighbourhood (the
  // finish's reach) also is, so the band data under both is the same repeat.
  function periodicity(pic) {
    let pairs = 0, max = 0, over1 = 0, seamPairs = 0;
    const x0 = K.x0 * N + 40, x1 = (K.x1 + 1) * N - 40, y0 = K.y0 * N + 40, y1 = (K.y1 + 1) * N - 40;
    for (const [sx, sy] of [[rect[2], 0], [0, rect[3]], [rect[2], rect[3]]])
      for (let y = y0; y < y1 - sy; y++) for (let x = x0; x < x1 - sx; x++) {
        const a = pic.at(x, y), b = pic.at(x + sx, y + sy);
        let d = 0; for (let c = 0; c < 4; c++) d = Math.max(d, Math.abs(pic.img[a + c] - pic.img[b + c]));
        pairs++; if (d > max) max = d; if (d > 1) over1++;
        const nearSeam = Math.abs(wrap(x, rect[0], rect[2]) - rect[0]) < 4 || Math.abs(wrap(y, rect[1], rect[3]) - rect[1]) < 4;
        if (nearSeam) seamPairs++;
      }
    return { pairs, seamPairs, maxByteError: max, pairsOver1: over1 };
  }
  const armed = periodicity(picture(pNow, rect));
  check('armed_finish_periodic_with_board', armed.maxByteError <= 1 && armed.seamPairs > 1000, armed);
  let lit = 0; { const pic = picture(pNow, rect); for (let i = 3; i < pic.img.length; i += 4) if (pic.img[i]) lit++; }
  check('armed_fixture_not_empty', lit > 5000, { litPixels: lit });
  const unarmed = periodicity(picture(pNow, [0, 0, 0, 0]));
  check('control_unarmed_is_not_periodic_red', unarmed.maxByteError > 8, unarmed);
  const mutant = periodicity(picture(pMutant, rect));
  check('mutation_armed_wash_unwrapped_red', mutant.maxByteError > 8, mutant);
  const grainMut = periodicity(picture(pGrainMutant, rect));
  check('mutation_armed_grain_unwrapped_red', grainMut.maxByteError > 2, grainMut);
  return { pass: failures.length === 0, failures, results };
}

(async () => {
  const probeVert = `#version 300 es
layout(location=0) in vec2 a_pos; uniform vec2 u_origin; uniform float u_span; out vec2 v_doc;
void main(){ v_doc=u_origin+a_pos*u_span; gl_Position=vec4(a_pos*2.0-1.0,0.0,1.0); }`;
  const probeFrag = include => `#version 300 es
precision highp float;
precision highp int;
${include('jb_paper.glsl')}
${include('jb_tile_paper.glsl')}
in vec2 v_doc; out vec4 o;
void main(){ vec4 a=jb_tilePaperRead(v_doc,0,true); vec4 b=jb_tilePaperRead(v_doc*1.37+vec2(11.0,-5.0),0,false,8.0); o=vec4(a.x+a.y*0.5,a.z,a.w,b.z); }`;
  const browser = await chromium.launch({ executablePath: process.env.CHROME || 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
    args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
  try {
    const page = await browser.newPage();
    page.on('console', m => console.error('[page]', m.text()));
    const report = await page.evaluate(gpu, {
      vert: now('lines/jb_wc_finish.vert'), finishNow: now('lines/jb_wc_finish.frag'), finishWas: was('lines/jb_wc_finish.frag'),
      probeVert, paperProbeNow: probeFrag(now), paperProbeWas: probeFrag(was),
    });
    console.log(JSON.stringify({ base, ...report }, null, 2));
    if (!report.pass) process.exitCode = 1;
  } finally { await browser.close(); }
})().catch(e => { console.error(e); process.exitCode = 1; });
