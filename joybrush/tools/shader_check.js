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
for (const n of ['jb_dab.vert', 'jb_dab.frag', 'jb_tile.vert', 'jb_commit.frag', 'jb_tile.frag']) S[n] = src(n);

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
    out.glError = gl.getError();
    return out;
  }, S);
  console.log(JSON.stringify(res));
  await browser.close();
})().catch(e => { console.error(e); process.exit(1); });
