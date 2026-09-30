// Draws a pencil-ish line with the REAL dab shader at several grain pitches and saves grain_look.png (look at it!).
// Run: CHROME=<edge/chrome exe> node grain_look.js
const fs = require('fs'), path = require('path');
const { chromium } = require('playwright-core');
const dir = path.join(__dirname, '..', 'shaders');
function src(name) { return fs.readFileSync(path.join(dir, name), 'utf8').split('\n').map(l => { const m = l.trim().match(/^#include\s+"([^"]+)"/); return m ? src(m[1]) : l; }).join('\n'); }
const png = fs.readFileSync(path.join(__dirname, '..', 'assets', 'grain', process.env.GRAIN || 'cloud_fine_256.png')).toString('base64');
const pitches = (process.env.PITCHES || '64,43,32,16,6.4').split(',').map(Number);
const depth = Number(process.env.DEPTH || 0.5);
(async () => {
  const browser = await chromium.launch({ executablePath: process.env.CHROME, args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'] });
  const page = await browser.newPage();
  const b64 = await page.evaluate(async ([vs, fs_, png, pitches, depth]) => {
    const img = new Image(); img.src = 'data:image/png;base64,' + png; await img.decode();
    const W = 256, H = 256;
    const c = document.createElement('canvas'); c.width = W; c.height = H;
    const gl = c.getContext('webgl2', { preserveDrawingBuffer: true }); gl.getExtension('EXT_color_buffer_float');
    const mk = (t, s) => { const sh = gl.createShader(t); gl.shaderSource(sh, s); gl.compileShader(sh); if (!gl.getShaderParameter(sh, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(sh)); return sh; };
    const p = gl.createProgram(); gl.attachShader(p, mk(gl.VERTEX_SHADER, vs)); gl.attachShader(p, mk(gl.FRAGMENT_SHADER, fs_)); gl.linkProgram(p);
    if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(p));
    const tex = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, tex);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, gl.RGBA, gl.UNSIGNED_BYTE, img); gl.generateMipmap(gl.TEXTURE_2D);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR_MIPMAP_LINEAR); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.REPEAT); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.REPEAT);
    const quad = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, quad); gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1,-1,1,-1,-1,1,1,1]), gl.STATIC_DRAW);
    const vao = gl.createVertexArray(); gl.bindVertexArray(vao); gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
    const ib = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, ib);
    gl.enableVertexAttribArray(1); gl.vertexAttribPointer(1, 4, gl.FLOAT, false, 24, 0); gl.vertexAttribDivisor(1, 1);
    gl.enableVertexAttribArray(2); gl.vertexAttribPointer(2, 2, gl.FLOAT, false, 24, 16); gl.vertexAttribDivisor(2, 1);
    gl.useProgram(p); const u = n => gl.getUniformLocation(p, n);
    gl.viewport(0, 0, W, H); gl.clearColor(1, 1, 1, 1); gl.clear(gl.COLOR_BUFFER_BIT);
    gl.uniform2f(u('u_tileOrigin'), 0, 0);
    gl.uniform1f(u('u_aspect'), 0); gl.uniform1f(u('u_corner'), 2); gl.uniform1f(u('u_taper'), 0); gl.uniform1f(u('u_hardness'), 0.8); gl.uniform1f(u('u_minPx'), 1);
    gl.uniform1i(u('u_tipGrain'), 0); gl.uniform1i(u('u_paperGrain'), 1);
    gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D, tex); gl.activeTexture(gl.TEXTURE1); gl.bindTexture(gl.TEXTURE_2D, tex);
    gl.uniform1f(u('u_tipGrainPitchPx'), 0);
    gl.uniform1f(u('u_paperDepth'), depth); gl.uniform1f(u('u_paperEdge'), 0.3); gl.uniform1f(u('u_paperTiltGradient'), 0); gl.uniform1f(u('u_paperRadial'), 0);
    gl.uniform1f(u('u_tiltAmount'), 0); gl.uniform2f(u('u_leanDir'), 0, 0);
    // Output is (cap*d, 0, 0, d): show as black ink on white via blending  dst = dst*(1-a)  (multiply-ish "paint black").
    gl.enable(gl.BLEND); gl.blendFuncSeparate(gl.ZERO, gl.ONE_MINUS_SRC_ALPHA, gl.ZERO, gl.ONE_MINUS_SRC_ALPHA);
    pitches.forEach((pitch, row) => {
      gl.uniform1f(u('u_paperGrainPitchPx'), pitch);
      // The shader maps tile px -> clip with u_tileSize; each row is drawn into the WHOLE canvas height, so offset y by row.
      const inst = []; for (let x = 12; x < W - 12; x += 1.0) inst.push(x, 28 + Math.sin(x / 30) * 6 + row * 48, 3.0, 0, 0.35, 1);
      gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(inst), gl.STREAM_DRAW);
      // tile is W wide, H tall: viewport is non-square, so use a scissor + per-row viewport
      gl.uniform1f(u('u_tileSize'), W);
      gl.drawArraysInstanced(gl.TRIANGLE_STRIP, 0, 4, inst.length / 6);
    });
    const px = new Uint8Array(W * H * 4); gl.readPixels(0, 0, W, H, gl.RGBA, gl.UNSIGNED_BYTE, px);
    const o = document.createElement('canvas'); o.width = W; o.height = H; const ctx = o.getContext('2d'); const id = ctx.createImageData(W, H);
    for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) { const s = ((H - 1 - y) * W + x) * 4, d = (y * W + x) * 4; id.data[d] = px[s]; id.data[d+1] = px[s+1]; id.data[d+2] = px[s+2]; id.data[d+3] = 255; }
    ctx.putImageData(id, 0, 0);
    return o.toDataURL('image/png').split(',')[1];
  }, [src('jb_dab.vert'), src('jb_dab.frag'), png, pitches, depth]);
  fs.writeFileSync(path.join(__dirname, 'grain_look.png'), Buffer.from(b64, 'base64'));
  console.log('wrote grain_look.png for pitches', pitches.join(','));
  await browser.close();
})().catch(e => { console.error(e); process.exit(1); });
