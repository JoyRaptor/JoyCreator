// commit_blend_check.js: jb_commit.frag's stroke-level blends (brush version 9, VECTOR_RENDER_DESIGN H6) against the
// premultiplied W3C formulas RefCanvas.endStroke and render/Blend use. u_blend 0 (never set by existing callers) must be
// the unchanged normal "over"; 1 multiply; 2 screen; u_erase 1 wins over any u_blend.
// Run: NODE_PATH=<bundled node_modules> node commit_blend_check.js   (CHROME= overrides the browser)
const fs = require('fs'), path = require('path');
const { chromium } = require('playwright-core');
const root = path.join(__dirname, '..');
const src = n => fs.readFileSync(path.join(root, 'shaders', n), 'utf8').replace(/^#include "([^"]+)"/gm, (_, m) => src(m));
(async () => {
  const browser = await chromium.launch({ executablePath: process.env.CHROME || 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
    args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
  try {
    const page = await browser.newPage();
    const out = await page.evaluate(({ vert, frag }) => {
      const N = 64;
      const canvas = document.createElement('canvas'); canvas.width = N; canvas.height = N;
      const gl = canvas.getContext('webgl2');
      if (!gl || !gl.getExtension('EXT_color_buffer_float')) throw Error('Float WebGL2 required');
      const sh = (t, s) => { const x = gl.createShader(t); gl.shaderSource(x, s); gl.compileShader(x); if (!gl.getShaderParameter(x, gl.COMPILE_STATUS)) throw Error(gl.getShaderInfoLog(x)); return x; };
      const p = gl.createProgram(); gl.attachShader(p, sh(gl.VERTEX_SHADER, vert)); gl.attachShader(p, sh(gl.FRAGMENT_SHADER, frag)); gl.linkProgram(p);
      if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw Error(gl.getProgramInfoLog(p));
      gl.useProgram(p);
      const u = n => gl.getUniformLocation(p, n);
      // Deterministic pseudo-random premultiplied layer and coverage, covering a = 0 and a = 1 rows.
      let seed = 12345; const rnd = () => (seed = (seed * 1103515245 + 12345) >>> 0) / 4294967296;
      const layer = new Float32Array(N * N * 4), stroke = new Float32Array(N * N * 4);
      for (let i = 0; i < N * N; i++) {
        const a = i < N ? 0 : i < 2 * N ? 1 : rnd();
        layer[i * 4] = rnd() * a; layer[i * 4 + 1] = rnd() * a; layer[i * 4 + 2] = rnd() * a; layer[i * 4 + 3] = a;
        stroke[i * 4] = (i % N) === 0 ? 1 : rnd();
      }
      const tex = (unit, data) => { const t = gl.createTexture(); gl.activeTexture(gl.TEXTURE0 + unit); gl.bindTexture(gl.TEXTURE_2D, t);
        gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA32F, N, N, 0, gl.RGBA, gl.FLOAT, data);
        for (const k of [gl.TEXTURE_MIN_FILTER, gl.TEXTURE_MAG_FILTER]) gl.texParameteri(gl.TEXTURE_2D, k, gl.NEAREST); return t; };
      tex(0, layer); tex(1, stroke);
      gl.uniform1i(u('u_layer'), 0); gl.uniform1i(u('u_stroke'), 1);
      const color = [0.9, 0.45, 0.15];
      gl.uniform3f(u('u_color'), ...color); gl.uniform1f(u('u_strokeScale'), 0.8); gl.uniform1f(u('u_layerOpacity'), 1); gl.uniform1i(u('u_smudge'), 0);
      const target = gl.createTexture(); gl.activeTexture(gl.TEXTURE2); gl.bindTexture(gl.TEXTURE_2D, target);
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA32F, N, N, 0, gl.RGBA, gl.FLOAT, null);
      const fbo = gl.createFramebuffer(); gl.bindFramebuffer(gl.FRAMEBUFFER, fbo); gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, target, 0);
      const vao = gl.createVertexArray(); gl.bindVertexArray(vao);
      const quad = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, quad);
      gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([0, 0, 1, 0, 0, 1, 1, 1]), gl.STATIC_DRAW);
      gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
      gl.uniform2f(u('u_tileOrigin'), 0, 0); gl.uniform1f(u('u_tileSize'), N);
      // Document 0..N onto clip -1..1 (column-major mat3), so texel i lands on pixel i.
      gl.uniformMatrix3fv(u('u_docToClip'), false, new Float32Array([2 / N, 0, 0, 0, 2 / N, 0, -1, -1, 1]));
      gl.viewport(0, 0, N, N); gl.disable(gl.BLEND);
      const run = (erase, blend, setBlend = true) => {
        gl.uniform1i(u('u_erase'), erase); if (setBlend) gl.uniform1i(u('u_blend'), blend);
        gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
        const px = new Float32Array(N * N * 4); gl.readPixels(0, 0, N, N, gl.RGBA, gl.FLOAT, px); return px;
      };
      const want = (mode, i) => {
        const a = stroke[i * 4] * 0.8, d = layer.slice(i * 4, i * 4 + 4), s = color.map(c => c * a);
        if (mode === 'erase') return d.map(v => v * (1 - a));
        const alpha = a + d[3] * (1 - a);
        if (mode === 'normal') return [...s.map((v, c) => v + d[c] * (1 - a)), alpha];
        if (mode === 'multiply') return [...s.map((v, c) => v * d[c] + v * (1 - d[3]) + d[c] * (1 - a)), alpha];
        return [...s.map((v, c) => v + d[c] * (1 - v)), alpha];
      };
      const results = {};
      const cases = [['normal-unset', 0, 0, 'normal', false], ['normal', 0, 0, 'normal', true], ['multiply', 0, 1, 'multiply', true],
        ['screen', 0, 2, 'screen', true], ['erase-wins', 1, 1, 'erase', true]];
      // 'normal-unset' first: a fresh program's u_blend is 0, exactly what every existing caller gets.
      for (const [name, erase, blend, mode, setBlend] of cases) {
        const px = run(erase, blend, setBlend);
        let worst = 0, bad = 0;
        for (let i = 0; i < N * N; i++) { const w = want(mode, i); for (let c = 0; c < 4; c++) { const e = Math.abs(px[i * 4 + c] - w[c]); worst = Math.max(worst, e); if (e > 1e-5) bad++; } }
        results[name] = { worst, mismatches: bad };
      }
      return { results, glError: gl.getError() };
    }, { vert: src('jb_tile.vert'), frag: src('jb_commit.frag') });
    console.log(JSON.stringify(out));
    const failed = Object.entries(out.results).filter(([, r]) => r.mismatches !== 0);
    if (failed.length || out.glError !== 0) { console.error('FAILED: ' + JSON.stringify(failed)); process.exitCode = 1; }
  } finally { await browser.close(); }
})();
