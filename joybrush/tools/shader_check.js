// Run: cd joybrush/tools && npm i --no-save playwright-core && node shader_check.js   (CHROME=/path/to/chrome to override)
// Pass = "compiled":true, strokeCentre ≈ 0.5 (never above), committedRGBA ≈ [127,0,0,127].
// Compiles the shared Joy Brush shaders in WebGL2 (headless Chromium / SwiftShader) and checks the
// wash cap and commit maths against the CPU reference's expectations.
const fs = require('fs');
const path = require('path');
const { chromium } = require('playwright-core');
const dir = path.join(__dirname, '..', 'shaders');
function src(name, seen = new Set()) {
  return fs.readFileSync(path.join(dir, name), 'utf8').split('\n').map(l => {
    const m = l.trim().match(/^#include\s+"([^"]+)"/);
    return m ? src(m[1], seen) : l;
  }).join('\n');
}
const S = {};
for (const n of ['jb_dab.vert', 'jb_dab.frag', 'jb_smudge_dab.frag', 'jb_tile.vert', 'jb_commit.frag', 'jb_tile.frag']) S[n] = src(n);

(async () => {
  const exe = process.env.CHROME ? null : fs.readdirSync('/opt/pw-browsers').find(d => d.startsWith('chromium-'));
  const browser = await chromium.launch({
    executablePath: process.env.CHROME || `/opt/pw-browsers/${exe}/chrome-linux/chrome`,
    args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'],
  });
  const page = await browser.newPage();
  const res = await page.evaluate((S) => {
    const c = document.createElement('canvas');
    const gl = c.getContext('webgl2');
    if (!gl) return { error: 'no webgl2' };
    const out = { ext: !!gl.getExtension('EXT_color_buffer_float') };
    function prog(v, f) {
      const mk = (t, s) => { const sh = gl.createShader(t); gl.shaderSource(sh, s); gl.compileShader(sh);
        if (!gl.getShaderParameter(sh, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(sh)); return sh; };
      const p = gl.createProgram(); gl.attachShader(p, mk(gl.VERTEX_SHADER, v)); gl.attachShader(p, mk(gl.FRAGMENT_SHADER, f));
      gl.linkProgram(p); if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(p)); return p;
    }
    let dab, commit, tile;
    try { dab = prog(S['jb_dab.vert'], S['jb_dab.frag']); commit = prog(S['jb_tile.vert'], S['jb_commit.frag']); tile = prog(S['jb_tile.vert'], S['jb_tile.frag']); }
    catch (e) { return { error: 'compile: ' + e.message }; }
    out.compiled = true;
    const N = 256;
    function tex(internal, format, type) { const t = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, t);
      gl.texImage2D(gl.TEXTURE_2D, 0, internal, N, N, 0, format, type, null);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST); return t; }
    const fbo = gl.createFramebuffer(); gl.bindFramebuffer(gl.FRAMEBUFFER, fbo);
    const stroke = tex(gl.R16F, gl.RED, gl.HALF_FLOAT);
    gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, stroke, 0);
    out.fbo = gl.checkFramebufferStatus(gl.FRAMEBUFFER) === gl.FRAMEBUFFER_COMPLETE;
    gl.viewport(0, 0, N, N); gl.clearColor(0, 0, 0, 0); gl.clear(gl.COLOR_BUFFER_BIT);
    // Dabs: 40 × (128,128) r=10 flow .3 cap .5 — wash should approach 0.5 and never pass it.
    const quad = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, quad);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1,-1, 1,-1, -1,1, 1,1]), gl.STATIC_DRAW);
    const vao = gl.createVertexArray(); gl.bindVertexArray(vao);
    gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
    const inst = []; for (let i = 0; i < 40; i++) inst.push(128, 128, 10, 0, 0.3, 0.5);
    const ib = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, ib); gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(inst), gl.STATIC_DRAW);
    gl.enableVertexAttribArray(1); gl.vertexAttribPointer(1, 4, gl.FLOAT, false, 24, 0); gl.vertexAttribDivisor(1, 1);
    gl.enableVertexAttribArray(2); gl.vertexAttribPointer(2, 2, gl.FLOAT, false, 24, 16); gl.vertexAttribDivisor(2, 1);
    gl.useProgram(dab);
    const u = (p, n) => gl.getUniformLocation(p, n);
    gl.uniform2f(u(dab, 'u_tileOrigin'), 0, 0); gl.uniform1f(u(dab, 'u_tileSize'), N);
    gl.uniform1f(u(dab, 'u_aspect'), 0); gl.uniform1f(u(dab, 'u_corner'), 2); gl.uniform1f(u(dab, 'u_taper'), 0);
    gl.uniform1f(u(dab, 'u_hardness'), 1); gl.uniform1f(u(dab, 'u_minPx'), 1);
    // The two grain samplers must ALWAYS point at a texture that is not the framebuffer's own attachment (that is
    // a feedback loop: INVALID_OPERATION and the draw is dropped). The engine binds a 1x1 dummy when a grain is off.
    const dummy = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, dummy);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, 1, 1, 0, gl.RGBA, gl.UNSIGNED_BYTE, new Uint8Array([255, 255, 255, 255]));
    gl.activeTexture(gl.TEXTURE1); gl.bindTexture(gl.TEXTURE_2D, dummy); gl.activeTexture(gl.TEXTURE0);
    gl.uniform1i(u(dab, 'u_tipGrain'), 0); gl.uniform1i(u(dab, 'u_paperGrain'), 1);
    gl.enable(gl.BLEND); gl.blendFuncSeparate(gl.ONE, gl.ONE_MINUS_SRC_ALPHA, gl.ONE, gl.ONE_MINUS_SRC_ALPHA);
    gl.drawArraysInstanced(gl.TRIANGLE_STRIP, 0, 4, 40);
    const px = new Float32Array(4); gl.readPixels(128, 128, 1, 1, gl.RGBA, gl.FLOAT, px); out.strokeCentre = px[0];
    gl.readPixels(128 + 30, 128, 1, 1, gl.RGBA, gl.FLOAT, px); out.strokeOutside = px[0];
    // Commit into an RGBA8 tile over transparent: red at alpha = stroke value.
    const layer = tex(gl.RGBA8, gl.RGBA, gl.UNSIGNED_BYTE);
    const after = tex(gl.RGBA8, gl.RGBA, gl.UNSIGNED_BYTE);
    gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, after, 0);
    gl.disable(gl.BLEND); gl.useProgram(commit);
    const unit = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, unit);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([0,0, 1,0, 0,1, 1,1]), gl.STATIC_DRAW);
    const vao2 = gl.createVertexArray(); gl.bindVertexArray(vao2); gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
    const s = 2 / N;
    gl.uniformMatrix3fv(u(commit, 'u_docToClip'), false, new Float32Array([s,0,0, 0,s,0, -1,-1,1]));
    gl.uniform2f(u(commit, 'u_tileOrigin'), 0, 0); gl.uniform1f(u(commit, 'u_tileSize'), N);
    gl.uniform1i(u(commit, 'u_layer'), 0); gl.uniform1i(u(commit, 'u_stroke'), 1);
    gl.uniform3f(u(commit, 'u_color'), 1, 0, 0); gl.uniform1f(u(commit, 'u_strokeScale'), 1);
    gl.uniform1i(u(commit, 'u_erase'), 0); gl.uniform1f(u(commit, 'u_layerOpacity'), 1);
    gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D, layer);
    gl.activeTexture(gl.TEXTURE1); gl.bindTexture(gl.TEXTURE_2D, stroke);
    gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
    const b = new Uint8Array(4); gl.readPixels(128, 128, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, b); out.committedRGBA = Array.from(b);
    // ---- JB-1.05c: the grain path -----------------------------------------------------------------
    // One big dab (r=40, flow 1, cap 1) into a cleared stroke buffer, with a flat paper texture: all-bright
    // (height 1) must paint, all-dark (height 0) must not, and pitch 0 (the default) must be plain tip coverage.
    function flat(v) { const t = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, t);
      const a = new Uint8Array(4 * 4 * 4).fill(v); for (let i = 3; i < a.length; i += 4) a[i] = 255;
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, 4, 4, 0, gl.RGBA, gl.UNSIGNED_BYTE, a);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.REPEAT); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.REPEAT); return t; }
    const bright = flat(255), dark = flat(0);
    const one = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, one);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([128, 128, 40, 0, 1, 1]), gl.STATIC_DRAW);
    function centreWith(paperPitch, paperTex, tiltAmount, lean) {
      gl.bindFramebuffer(gl.FRAMEBUFFER, fbo);
      gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, stroke, 0);
      gl.viewport(0, 0, N, N); gl.disable(gl.SCISSOR_TEST); gl.clearColor(0, 0, 0, 0); gl.clear(gl.COLOR_BUFFER_BIT);
      gl.bindVertexArray(vao); gl.bindBuffer(gl.ARRAY_BUFFER, one);
      gl.vertexAttribPointer(1, 4, gl.FLOAT, false, 24, 0); gl.vertexAttribPointer(2, 2, gl.FLOAT, false, 24, 16);
      gl.useProgram(dab);
      gl.uniform1f(u(dab, 'u_hardness'), 1);
      gl.uniform1i(u(dab, 'u_tipGrain'), 0); gl.uniform1i(u(dab, 'u_paperGrain'), 1);
      gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D, bright);
      gl.activeTexture(gl.TEXTURE1); gl.bindTexture(gl.TEXTURE_2D, paperTex);
      gl.uniform1f(u(dab, 'u_tipGrainPitchPx'), 0); gl.uniform1f(u(dab, 'u_paperGrainPitchPx'), paperPitch);
      gl.uniform1f(u(dab, 'u_paperDepth'), 0.2); gl.uniform1f(u(dab, 'u_paperEdge'), 0.05);
      gl.uniform1f(u(dab, 'u_paperTiltGradient'), 0.6); gl.uniform1f(u(dab, 'u_paperRadial'), 0);
      gl.uniform1f(u(dab, 'u_tiltAmount'), tiltAmount); gl.uniform2f(u(dab, 'u_leanDir'), lean[0], lean[1]);
      gl.enable(gl.BLEND); gl.blendFuncSeparate(gl.ONE, gl.ONE_MINUS_SRC_ALPHA, gl.ONE, gl.ONE_MINUS_SRC_ALPHA);
      gl.drawArraysInstanced(gl.TRIANGLE_STRIP, 0, 4, 1);
      const q = new Float32Array(4); gl.readPixels(128, 128, 1, 1, gl.RGBA, gl.FLOAT, q); return q[0]; // R16F: only the red channel exists (alpha always reads 1); with cap = 1 red = d
    }
    out.grainOffCentre = centreWith(0, dark, 0, [0, 0]);            // default: plain tip coverage, ~1
    out.grainBrightCentre = centreWith(256, bright, 0, [0, 0]);     // height 1, level 0.2: (1-0.8)/0.05+0.5 -> 1
    out.grainDarkCentre = centreWith(256, dark, 0, [0, 0]);         // height 0: (0-0.8)/0.05+0.5 -> 0
    out.grainFingerCentre = centreWith(256, bright, 0, [0, 0]);     // a finger reaches the shader as tilt 0, lean (0,0)
    out.grainPenCentre = centreWith(256, bright, 0.7, [1, 0]);      // a leaning pen: still finite at the centre
    out.grainDarkens = out.grainBrightCentre > 0.9 && out.grainDarkCentre < 0.05 && out.grainOffCentre > 0.9;
    out.grainAllFinite = [out.grainOffCentre, out.grainBrightCentre, out.grainDarkCentre, out.grainFingerCentre, out.grainPenCentre].every(Number.isFinite);
    // ---- JB-1.06: the smudge path ---------------------------------------------------------------------
    // Two dabs of two different carried colours, accumulated in an RGBA float stroke buffer, then committed with u_smudge = 1 over an
    // opaque canvas and over an EMPTY one. Must equal the sequential rule  out = carried*t + canvas*(1 - carriedA*t)  applied twice
    // (core Smudge.dab), and must leave the empty canvas empty.
    try {
      const smudge = prog(S['jb_dab.vert'], S['jb_smudge_dab.frag']);
      const sbuf = gl.createTexture(); gl.activeTexture(gl.TEXTURE2); gl.bindTexture(gl.TEXTURE_2D, sbuf);
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA16F, N, N, 0, gl.RGBA, gl.HALF_FLOAT, null);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST);
      gl.bindFramebuffer(gl.FRAMEBUFFER, fbo);
      gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, sbuf, 0);
      gl.viewport(0, 0, N, N); gl.clearColor(0, 0, 0, 0); gl.clear(gl.COLOR_BUFFER_BIT);
      const svao = gl.createVertexArray(); gl.bindVertexArray(svao);
      gl.bindBuffer(gl.ARRAY_BUFFER, quad); gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
      const sib = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, sib);
      // x, y, r, angle, flow, cap, then the carried colour (premultiplied rgba)
      gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([
        128, 128, 40, 0, 0.5, 1,   1, 0, 0, 1,
        128, 128, 40, 0, 0.5, 1,   0, 0, 1, 1,
      ]), gl.STATIC_DRAW);
      gl.enableVertexAttribArray(1); gl.vertexAttribPointer(1, 4, gl.FLOAT, false, 40, 0); gl.vertexAttribDivisor(1, 1);
      gl.enableVertexAttribArray(2); gl.vertexAttribPointer(2, 2, gl.FLOAT, false, 40, 16); gl.vertexAttribDivisor(2, 1);
      gl.enableVertexAttribArray(3); gl.vertexAttribPointer(3, 4, gl.FLOAT, false, 40, 24); gl.vertexAttribDivisor(3, 1);
      gl.useProgram(smudge);
      gl.uniform2f(u(smudge, 'u_tileOrigin'), 0, 0); gl.uniform1f(u(smudge, 'u_tileSize'), N);
      gl.uniform1f(u(smudge, 'u_aspect'), 0); gl.uniform1f(u(smudge, 'u_corner'), 2); gl.uniform1f(u(smudge, 'u_taper'), 0);
      gl.uniform1f(u(smudge, 'u_hardness'), 1); gl.uniform1f(u(smudge, 'u_minPx'), 1);
      gl.enable(gl.BLEND); gl.blendFuncSeparate(gl.ONE, gl.ONE_MINUS_SRC_ALPHA, gl.ONE, gl.ONE_MINUS_SRC_ALPHA);
      gl.drawArraysInstanced(gl.TRIANGLE_STRIP, 0, 4, 2);
      gl.disable(gl.BLEND);
      function commitOver(canvasRGBA, strokeScale = 1, layerOpacity = 1, smudgeMode = 1) { // premultiplied bytes
        const layerT = gl.createTexture(); gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D, layerT);
        const a = new Uint8Array(4 * 4 * 4); for (let i = 0; i < 16; i++) a.set(canvasRGBA, i * 4);
        gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, 4, 4, 0, gl.RGBA, gl.UNSIGNED_BYTE, a);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST);
        gl.activeTexture(gl.TEXTURE3);   // tex() binds on the ACTIVE unit: keep it away from units 0 and 1, which the commit samples
        const outT = tex(gl.RGBA8, gl.RGBA, gl.UNSIGNED_BYTE);
        gl.activeTexture(gl.TEXTURE1); gl.bindTexture(gl.TEXTURE_2D, sbuf);
        gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, outT, 0);
        gl.useProgram(commit); gl.bindVertexArray(vao2); gl.bindBuffer(gl.ARRAY_BUFFER, unit);
        gl.uniform1i(u(commit, 'u_layer'), 0); gl.uniform1i(u(commit, 'u_stroke'), 1); gl.uniform1i(u(commit, 'u_smudge'), smudgeMode);
        gl.uniform1i(u(commit, 'u_erase'), 0); gl.uniform1f(u(commit, 'u_layerOpacity'), layerOpacity); gl.uniform1f(u(commit, 'u_strokeScale'), strokeScale);
        gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
        const px2 = new Uint8Array(4); gl.readPixels(128, 128, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, px2); return Array.from(px2);
      }
      const canvas = [0.2, 0.4, 0.6];
      const bytes = c => c.map(v => Math.round(v * 255));
      out.smudgeOverPaint = commitOver([...bytes(canvas), 255]);
      out.smudgeOverEmpty = commitOver([0, 0, 0, 0]);
      // The rule, twice: t = 0.5 each (flow 0.5 x coverage 1 at the centre of a hard tip), carried opaque.
      let c = canvas.slice();
      for (const carried of [[1, 0, 0], [0, 0, 1]]) c = c.map((v, i) => carried[i] * 0.5 + v * 0.5);
      out.smudgeWant = bytes(c);
      out.smudgeOk = out.smudgeOverPaint.slice(0, 3).every((v, i) => Math.abs(v - out.smudgeWant[i]) <= 2) && out.smudgeOverPaint[3] === 255
        && out.smudgeOverEmpty.every(v => v === 0);
      // The two half-strength dabs have accumulated [0.25, 0, 0.5, 0.75]. Stroke opacity mixes
      // that completed mark with the original canvas; layer opacity then scales the preview.
      // A translucent backdrop catches scaling RGB without scaling alpha, which opaque-only
      // colour checks can miss. Empty paint must stay empty at every setting.
      out.smudgeOpacityCases = [];
      for (const backdrop of [[51, 102, 153, 255], [26, 51, 77, 128], [0, 0, 0, 0]]) {
        for (const scale of [0, 0.25, 1]) for (const layerOpacity of [1, 0.6]) {
          const actual = commitOver(backdrop, scale, layerOpacity);
          const dst = backdrop.map(v => v / 255);
          const mark = [0.25, 0, 0.5, 0.75];
          const want = dst.map((v, i) => Math.round(255 * layerOpacity *
            (dst[3] === 0 ? v : mark[i] * scale + v * (1 - mark[3] * scale))));
          out.smudgeOpacityCases.push({ backdrop, scale, layerOpacity, actual, want,
            ok: actual.every((v, i) => Math.abs(v - want[i]) <= 1) });
        }
      }
      out.smudgeOpacityOk = out.smudgeOpacityCases.every(c => c.ok);
      // Stamp and tuft both use the non-smudge coverage branch. The same buffer's red channel
      // is exactly 0.25: pin its existing red-over-canvas result at the same opacity settings.
      out.coverageOpacityCases = [0, 0.25, 1].map(scale => {
        const backdrop = [51, 102, 153, 255];
        const actual = commitOver(backdrop, scale, 1, 0);
        const a = 0.25 * scale;
        const want = backdrop.map((v, i) => Math.round(v * (1 - a) + ([255, 0, 0, 255][i]) * a));
        return { scale, actual, want, ok: actual.every((v, i) => Math.abs(v - want[i]) <= 1) };
      });
      out.coverageOpacityOk = out.coverageOpacityCases.every(c => c.ok);
    } catch (e) { out.smudgeError = String(e).slice(0, 300); }
    out.glError = gl.getError();
    return out;
  }, S);
  console.log(JSON.stringify(res));
  await browser.close();
  if (!res.compiled || !res.fbo || res.glError !== 0 || !res.grainDarkens || !res.grainAllFinite ||
      !res.smudgeOk || !res.smudgeOpacityOk || !res.coverageOpacityOk ||
      Math.abs(res.strokeCentre - 0.5) > 0.01 || res.strokeOutside !== 0 ||
      !res.committedRGBA?.every((v, i) => Math.abs(v - [127, 0, 0, 127][i]) <= 2)) process.exitCode = 1;
})().catch(e => { console.error(e); process.exit(1); });
