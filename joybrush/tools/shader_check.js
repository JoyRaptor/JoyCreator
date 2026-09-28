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
    out.glError = gl.getError();
    return out;
  }, S);
  console.log(JSON.stringify(res));
  await browser.close();
})().catch(e => { console.error(e); process.exit(1); });
