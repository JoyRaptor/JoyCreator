// stamp_pass_check.js: H1 (VECTOR_RENDER_DESIGN §8) on the GPU, headless (Edge + SwiftShader, as contact_check.js).
//   (a) EXACTNESS: today's jb_dab.vert (--old-vert <file>, e.g. `git show origin/joy-creator:joybrush/shaders/jb_dab.vert`)
//       and the new one with u_scale unset render byte-identical stroke buffers, paper grain on.
//   (b) u_scale = 1 is byte-identical to u_scale unset.
//   (c) S5 split invariance: one instanced draw vs the same list in random chunks, in order, are byte-identical;
//       CONTROL: the same chunks drawn in reverse order must differ (blending is order-dependent, so (c) can fail).
//   (d) S2/S3 scale: the same doc region at scale 2 into a 2x target, box-filtered 2x2, matches scale 1 within the
//       antialiasing band; CONTROL: the 2x target drawn with u_scale unset must miss badly.
// Run: NODE_PATH=<bundled node_modules> node stamp_pass_check.js --old-vert <path>
const fs = require('fs'), path = require('path');
const { chromium } = require('playwright-core');
const root = path.join(__dirname, '..');
const src = n => fs.readFileSync(path.join(root, 'shaders', n), 'utf8').replace(/^#include "([^"]+)"/gm, (_, m) => src(m));
const argOld = process.argv.indexOf('--old-vert');
if (argOld < 0) { console.error('usage: node stamp_pass_check.js --old-vert <old jb_dab.vert>'); process.exit(2); }
const oldVert = fs.readFileSync(process.argv[argOld + 1], 'utf8');
(async () => {
  const browser = await chromium.launch({ executablePath: process.env.CHROME || 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
    args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
  try {
    const page = await browser.newPage();
    const out = await page.evaluate(({ newVert, oldVert, frag }) => {
      const canvas = document.createElement('canvas');
      const gl = canvas.getContext('webgl2');
      if (!gl || !gl.getExtension('EXT_color_buffer_float')) throw Error('Float WebGL2 required');
      const sh = (t, s) => { const x = gl.createShader(t); gl.shaderSource(x, s); gl.compileShader(x); if (!gl.getShaderParameter(x, gl.COMPILE_STATUS)) throw Error(gl.getShaderInfoLog(x)); return x; };
      const prog = vs => { const p = gl.createProgram(); gl.attachShader(p, sh(gl.VERTEX_SHADER, vs)); gl.attachShader(p, sh(gl.FRAGMENT_SHADER, frag)); gl.linkProgram(p); if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw Error(gl.getProgramInfoLog(p)); return p; };
      const progNew = prog(newVert), progOld = prog(oldVert);
      // A deterministic paper surface (RGBA: slope x, slope y, height, h^2) on unit 1, REPEAT, so grain is exercised.
      let seed = 99; const rnd = () => (seed = (seed * 1664525 + 1013904223) >>> 0) / 4294967296;
      const P = 64, paperPx = new Uint8Array(P * P * 4);
      for (let i = 0; i < P * P; i++) { const h = rnd(); paperPx[i * 4] = 128; paperPx[i * 4 + 1] = 128; paperPx[i * 4 + 2] = h * 255; paperPx[i * 4 + 3] = h * h * 255; }
      const paper = gl.createTexture(); gl.activeTexture(gl.TEXTURE1); gl.bindTexture(gl.TEXTURE_2D, paper);
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, P, P, 0, gl.RGBA, gl.UNSIGNED_BYTE, paperPx);
      for (const [k, v] of [[gl.TEXTURE_MIN_FILTER, gl.LINEAR], [gl.TEXTURE_MAG_FILTER, gl.LINEAR], [gl.TEXTURE_WRAP_S, gl.REPEAT], [gl.TEXTURE_WRAP_T, gl.REPEAT]]) gl.texParameteri(gl.TEXTURE_2D, k, v);
      const one = gl.createTexture();
      for (const unit of [0, 2]) { gl.activeTexture(gl.TEXTURE0 + unit); gl.bindTexture(gl.TEXTURE_2D, one); }
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, 1, 1, 0, gl.RGBA, gl.UNSIGNED_BYTE, new Uint8Array([255, 255, 255, 255]));
      const vao = gl.createVertexArray(); gl.bindVertexArray(vao);
      const quad = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, quad);
      gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW);
      gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
      const inst = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, inst);
      for (const [loc, n, off] of [[1, 4, 0], [2, 2, 16], [4, 2, 24], [5, 4, 32], [6, 4, 48], [7, 1, 64]]) { gl.enableVertexAttribArray(loc); gl.vertexAttribPointer(loc, n, gl.FLOAT, false, 68, off); gl.vertexAttribDivisor(loc, 1); }
      // 120 dabs along a wobbly path: mixed sizes, some live contact (anchored, tilted), some legacy.
      const dabs = [];
      for (let i = 0; i < 120; i++) {
        const x = 30 + i * 1.6, y = 128 + 40 * Math.sin(i * 0.07), live = i % 3 !== 0;
        dabs.push([x, y, 3 + 9 * rnd(), rnd() * 6, 0.2 + 0.6 * rnd(), 0.4 + 0.6 * rnd(), 1, 0,
          live ? -0.6 * rnd() : 0, 0.5 + 0.5 * rnd(), 1, 0.3 + 0.7 * rnd(), live ? 0.8 * rnd() : 0, live ? rnd() : 0, Math.cos(i), Math.sin(i), live ? 1 : 0]);
      }
      function setUniforms(p, size, scale) {
        gl.useProgram(p);
        const u = n => gl.getUniformLocation(p, n), f = (n, v) => gl.uniform1f(u(n), v);
        gl.uniform2f(u('u_tileOrigin'), 0, 0); f('u_tileSize', size);
        f('u_scale', scale === null ? 0 : scale); // 0 is what an unset uniform reads (a program keeps the last value written)
        f('u_aspect', 0); f('u_corner', 2); f('u_taper', 0); f('u_hardness', 0.85); f('u_minPx', 1);
        gl.uniform1i(u('u_tipGrain'), 0); gl.uniform1i(u('u_paperSurface'), 1); gl.uniform1i(u('u_paperFluid'), 2);
        f('u_paperGrainPitchPx', 1); f('u_paperInfluence', 1); f('u_paperEdge', 0.2); f('u_paperTiltGradient', 0.6); f('u_paperRadial', 0.05);
        f('u_paperSize', P); f('u_paperTexelPx', 1.5); f('u_paperHexTexels', 16); f('u_paperSlopeRange', 0.1); f('u_paperHeightMean', 0.5);
      }
      function render(p, size, scale, chunks) {
        const t = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, t); gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA16F, size, size, 0, gl.RGBA, gl.HALF_FLOAT, null);
        const fb = gl.createFramebuffer(); gl.bindFramebuffer(gl.FRAMEBUFFER, fb); gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, t, 0);
        gl.viewport(0, 0, size, size); gl.clearColor(0, 0, 0, 0); gl.clear(gl.COLOR_BUFFER_BIT);
        gl.enable(gl.BLEND); gl.blendFuncSeparate(gl.ONE, gl.ONE_MINUS_SRC_ALPHA, gl.ONE, gl.ONE_MINUS_SRC_ALPHA);
        setUniforms(p, size, scale);
        gl.bindVertexArray(vao); gl.bindBuffer(gl.ARRAY_BUFFER, inst);
        for (const c of chunks) { gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(c.flat()), gl.STREAM_DRAW); gl.drawArraysInstanced(gl.TRIANGLE_STRIP, 0, 4, c.length); }
        const px = new Float32Array(size * size * 4); gl.readPixels(0, 0, size, size, gl.RGBA, gl.FLOAT, px);
        gl.deleteFramebuffer(fb); gl.deleteTexture(t); return px;
      }
      const diff = (a, b) => { let n = 0, worst = 0; for (let i = 0; i < a.length; i++) { const d = Math.abs(a[i] - b[i]); if (d !== 0) n++; worst = Math.max(worst, d); } return { differing: n, worst }; };
      const whole = [dabs];
      const res = {};
      const base = render(progOld, 256, null, whole);
      res.a_oldVsNewUnset = diff(base, render(progNew, 256, null, whole));
      res.b_unsetVsScale1 = diff(render(progNew, 256, null, whole), render(progNew, 256, 1, whole));
      const chunks = []; for (let i = 0; i < dabs.length;) { const n = 1 + Math.floor(rnd() * 17); chunks.push(dabs.slice(i, i + n)); i += n; }
      res.c_split = diff(render(progNew, 256, 1, whole), render(progNew, 256, 1, chunks));
      res.c_control_reversed = diff(render(progNew, 256, 1, whole), render(progNew, 256, 1, chunks.slice().reverse()));
      // (d): no grain, so only geometry and the antialiasing band are compared.
      const noGrain = (p) => { gl.useProgram(p); gl.uniform1f(gl.getUniformLocation(p, 'u_paperGrainPitchPx'), 0); };
      const renderNG = (size, scale) => { const s0 = setUniforms; setUniforms = (p, sz, sc) => { s0(p, sz, sc); noGrain(p); }; const r = render(progNew, size, scale, whole); setUniforms = s0; return r; };
      const one1 = renderNG(256, 1), two = renderNG(512, 2), wrong = renderNG(512, null);
      const box = img => { const o = new Float32Array(256 * 256 * 4); for (let y = 0; y < 256; y++) for (let x = 0; x < 256; x++) for (let c = 0; c < 4; c++) {
        let s = 0; for (const [dx, dy] of [[0, 0], [1, 0], [0, 1], [1, 1]]) s += img[((2 * y + dy) * 512 + 2 * x + dx) * 4 + c]; o[(y * 256 + x) * 4 + c] = s / 4; } return o; };
      const meanAbs = (a, b) => { let s = 0; for (let i = 0; i < a.length; i += 4) s += Math.abs(a[i + 3] - b[i + 3]); return s / (a.length / 4); };
      const covered = img => { let n = 0; for (let i = 3; i < img.length; i += 4) if (img[i] > 0.5) n++; return n; };
      const two1 = box(two), wrong1 = box(wrong);
      res.d_scale2 = { meanAbsAlpha: meanAbs(one1, two1), worst: diff(one1, two1).worst, covered1: covered(one1), covered2: covered(two1) };
      res.d_control_unset = { meanAbsAlpha: meanAbs(one1, wrong1), covered: covered(wrong1) };
      res.glError = gl.getError();
      return res;
    }, { newVert: src('jb_dab.vert'), oldVert: oldVert.replace(/^#include "([^"]+)"/gm, (_, m) => src(m)), frag: src('jb_dab.frag') });
    console.log(JSON.stringify(out, null, 1));
    const fails = [];
    if (out.a_oldVsNewUnset.differing !== 0) fails.push('(a) old vs new shader differ');
    if (out.b_unsetVsScale1.differing !== 0) fails.push('(b) u_scale 1 differs from unset');
    if (out.c_split.differing !== 0) fails.push('(c) split draws differ');
    if (out.c_control_reversed.differing === 0) fails.push('(c) CONTROL: reversed order did not differ, so (c) cannot fail');
    const d = out.d_scale2;
    if (!(d.meanAbsAlpha < 0.01 && Math.abs(d.covered1 - d.covered2) <= d.covered1 * 0.02)) fails.push('(d) scale 2 does not match scale 1');
    if (!(out.d_control_unset.meanAbsAlpha > 0.05)) fails.push('(d) CONTROL: unset scale on a 2x target matched, so (d) cannot fail');
    if (out.glError !== 0) fails.push('glError ' + out.glError);
    if (fails.length) { console.error('FAILED: ' + fails.join('; ')); process.exitCode = 1; } else console.log('stamp_pass_check: all pass, both controls fail as they must');
  } finally { await browser.close(); }
})();
