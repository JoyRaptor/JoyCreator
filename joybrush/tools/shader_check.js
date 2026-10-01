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
for (const n of ['jb_dab.vert', 'jb_dab.frag', 'jb_tuft.vert', 'jb_tuft.frag', 'jb_paper.glsl', 'jb_paper_bg.vert', 'jb_paper_bg.frag', 'jb_smudge_dab.frag', 'jb_tile.vert', 'jb_commit.frag', 'jb_tile.frag']) S[n] = src(n);
S.paperImage = 'data:image/png;base64,' + fs.readFileSync(path.join(__dirname, '..', 'assets', 'paper', 'surface_pulp_artisan.png')).toString('base64');
// Required CPU-generated fixtures: run :core:jvmTest first; missing parity data is a failure.
S.paperFixtures = ['paper-raster-fixture.json', 'paper-raster-large-fixture.json'].map(name =>
  JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'core', 'build', name), 'utf8')));

(async () => {
  const edge = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';
  const exe = process.env.CHROME || (fs.existsSync(edge) ? edge :
    `/opt/pw-browsers/${fs.readdirSync('/opt/pw-browsers').find(d => d.startsWith('chromium-'))}/chrome-linux/chrome`);
  const browser = await chromium.launch({
    executablePath: exe,
    args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'],
  });
  const page = await browser.newPage();
  const res = await page.evaluate(async (S) => {
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
    prog(S['jb_tuft.vert'], S['jb_tuft.frag']);
    out.tuftCompiled = true;
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
    gl.uniform1i(u(dab, 'u_tipGrain'), 0); gl.uniform1i(u(dab, 'u_paperSurface'), 1);
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
      gl.uniform1i(u(dab, 'u_tipGrain'), 0); gl.uniform1i(u(dab, 'u_paperSurface'), 1);
      paperUniforms(dab, 4);
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
    // JB-9.03: the shipped paper, one full period apart, plus plain tiling as the control.
    function paperUniforms(p, size = 512) {
      gl.uniform1f(u(p, 'u_paperTexelPx'), 2);
      gl.uniform1f(u(p, 'u_paperSize'), size);
      gl.uniform1f(u(p, 'u_paperHexTexels'), 180);
      gl.uniform1f(u(p, 'u_paperSlopeRange'), 0.099);
      gl.uniform1i(u(p, 'u_paperRotatable'), 1);
    }
    const image = new Image(); image.src = S.paperImage; await image.decode();
    gl.activeTexture(gl.TEXTURE1);
    const surface = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, surface);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, gl.RGBA, gl.UNSIGNED_BYTE, image);
    gl.generateMipmap(gl.TEXTURE_2D);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR_MIPMAP_LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.REPEAT);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.REPEAT);
    const W = 2048, H = 256;
    c.width = W; c.height = H;
    gl.activeTexture(gl.TEXTURE3);
    const strip = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, strip);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, W, H, 0, gl.RGBA, gl.UNSIGNED_BYTE, null);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST);
    gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, strip, 0);
    gl.viewport(0, 0, W, H); gl.disable(gl.BLEND);
    const stripV = `#version 300 es\nprecision highp float; layout(location=0) in vec2 a; void main(){gl_Position=vec4(a,0,1);}`;
    const stripF = `#version 300 es\nprecision highp float; ${S['jb_paper.glsl']}\nuniform bool u_control; out vec4 color;
      void main(){float h=u_control ? texture(u_paperSurface,gl_FragCoord.xy/1024.0).b : jb_paperSurface(gl_FragCoord.xy).z; color=vec4(h,h,h,1);}`;
    const stripP = prog(stripV, stripF);
    gl.bindVertexArray(vao); gl.useProgram(stripP);
    gl.uniform1i(u(stripP, 'u_paperSurface'), 1); paperUniforms(stripP);
    function correlation(control, p = stripP) {
      gl.uniform1i(u(p, 'u_control'), control);
      gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
      const pixels = new Uint8Array(W * H * 4); gl.readPixels(0, 0, W, H, gl.RGBA, gl.UNSIGNED_BYTE, pixels);
      let sx=0, sy=0, xx=0, yy=0, xy=0;
      for(let y=0;y<H;y++) for(let x=0;x<W/2;x++) {
        const a=pixels[(y*W+x)*4], b=pixels[(y*W+x+W/2)*4];
        sx+=a;sy+=b;xx+=a*a;yy+=b*b;xy+=a*b;
      }
      const n=W/2*H;
      return (n*xy-sx*sy)/Math.sqrt((n*xx-sx*sx)*(n*yy-sy*sy));
    }
    out.paperSurfaceCorrelation = correlation(0); out.paperSurfaceControlCorrelation = correlation(1);
    // Fill the same strip with a paper-on dab using the production fragment shader.
    const paperDabV = `#version 300 es\nprecision highp float; layout(location=0) in vec2 a;
      out vec2 v_offset; out vec2 v_dabCentre; out float v_radius; out float v_angle; out float v_flow; out float v_cap;
      void main(){gl_Position=vec4(a,0,1);v_offset=a*vec2(1024,128);v_dabCentre=vec2(1024,128);
        v_radius=2048.0;v_angle=0.0;v_flow=1.0;v_cap=1.0;}`;
    function dabCorrelation(control) {
      const fragment = control ? S['jb_dab.frag'].replace('return jb_paperHeight(docPx);',
        'return texture(u_paperSurface, docPx / 1024.0).b;') : S['jb_dab.frag'];
      const p = prog(paperDabV, fragment); gl.useProgram(p); paperUniforms(p);
      gl.uniform1i(u(p,'u_tipGrain'),0);gl.uniform1i(u(p,'u_paperSurface'),1);
      gl.activeTexture(gl.TEXTURE0);gl.bindTexture(gl.TEXTURE_2D,dummy);
      gl.uniform1f(u(p,'u_paperGrainPitchPx'),2);gl.uniform1f(u(p,'u_paperDepth'),0.5);
      gl.uniform1f(u(p,'u_paperEdge'),0.5);gl.uniform1f(u(p,'u_hardness'),1);
      gl.uniform1f(u(p,'u_corner'),2);gl.uniform1f(u(p,'u_minPx'),1);
      // The program's chosen read is the control; u_control is absent in these dab shaders.
      return correlation(control, p);
    }
    // JB-9.03b: exact-zero slopes must survive the GPU's filtered decode.
    const flatBytes = new Uint8Array(4 * 4 * 4);
    for (let i=0;i<flatBytes.length;i+=4) flatBytes.set([127,127,128,64],i);
    gl.activeTexture(gl.TEXTURE1); gl.bindTexture(gl.TEXTURE_2D,surface);
    gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA8,4,4,0,gl.RGBA,gl.UNSIGNED_BYTE,flatBytes);
    gl.generateMipmap(gl.TEXTURE_2D);
    const zeroP = prog(stripV, `#version 300 es\n${S['jb_paper.glsl']}\nout vec4 color;
      void main(){vec2 slope=jb_paperSurface(gl_FragCoord.xy).xy; color=vec4(slope,0,1);}`);
    gl.useProgram(zeroP); paperUniforms(zeroP,4); gl.uniform1i(u(zeroP,'u_paperSurface'),1); gl.uniform1i(u(zeroP,'u_paperRotatable'),1);
    gl.activeTexture(gl.TEXTURE3);
    const zeroTex=gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D,zeroTex);
    gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA32F,1,1,0,gl.RGBA,gl.FLOAT,null);
    gl.framebufferTexture2D(gl.FRAMEBUFFER,gl.COLOR_ATTACHMENT0,gl.TEXTURE_2D,zeroTex,0);
    gl.viewport(0,0,1,1); gl.drawArrays(gl.TRIANGLE_STRIP,0,4);
    const slopes=new Float32Array(4);gl.readPixels(0,0,1,1,gl.RGBA,gl.FLOAT,slopes);
    out.flatSlopes=Array.from(slopes); out.flatSlopeExactZero=slopes[0]===0 && slopes[1]===0;
    gl.activeTexture(gl.TEXTURE1);gl.bindTexture(gl.TEXTURE_2D,surface);
    gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA8,gl.RGBA,gl.UNSIGNED_BYTE,image);gl.generateMipmap(gl.TEXTURE_2D);
    gl.framebufferTexture2D(gl.FRAMEBUFFER,gl.COLOR_ATTACHMENT0,gl.TEXTURE_2D,strip,0);gl.viewport(0,0,W,H);
    out.paperCorrelation=dabCorrelation(false);out.paperControlCorrelation=dabCorrelation(true);
    out.paperNoRepeat = out.paperCorrelation < 0.3 && out.paperControlCorrelation > 0.99 &&
      out.paperSurfaceCorrelation < 0.3 && out.paperSurfaceControlCorrelation > 0.99;
    gl.viewport(0, 0, N, N);
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
    // JB-9.06: production background, CPU bytes at zoom 1, and a smooth look near ±1e7.
    const background = prog(S['jb_paper_bg.vert'], S['jb_paper_bg.frag']);
    out.paperBackgroundCompiled = true;
    function localFrame(origin, pitch, hex, size) {
      const x=origin[0]/pitch, y=origin[1]/pitch;
      const a=(x-y/Math.sqrt(3))/hex, b=2*y/Math.sqrt(3)/hex;
      let i=Math.floor(a), j=Math.floor(b);
      if (a-i+b-j>1) { i++;j++; }
      const cx=hex*(i+j/2), cy=hex*j*Math.sqrt(3)/2;
      const mod=v=>v-Math.floor(v/size)*size;
      return {base:[i,j],local:[x-cx,y-cy],centre:[mod(cx),mod(cy)]};
    }
    function uploadFixtureTexture(entry, unitNumber) {
      gl.activeTexture(gl.TEXTURE0+unitNumber);
      const texture=gl.createTexture();gl.bindTexture(gl.TEXTURE_2D,texture);
      const size=entry?.size || 1, bytes=entry?.rgba || [127,127,127,255];
      gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA8,size,size,0,gl.RGBA,gl.UNSIGNED_BYTE,new Uint8Array(bytes));
      gl.generateMipmap(gl.TEXTURE_2D);
      gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MIN_FILTER,gl.LINEAR_MIPMAP_LINEAR);
      gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MAG_FILTER,gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_S,gl.REPEAT);
      gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_T,gl.REPEAT);
    }
    function backgroundParity(fixture) {
      const {width:w,height:h}=fixture;
      uploadFixtureTexture(fixture.look,0);uploadFixtureTexture(fixture.surface,1);
      gl.activeTexture(gl.TEXTURE3);
      const target=gl.createTexture();gl.bindTexture(gl.TEXTURE_2D,target);
      gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA8,w,h,0,gl.RGBA,gl.UNSIGNED_BYTE,null);
      gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MIN_FILTER,gl.NEAREST);
      gl.bindFramebuffer(gl.FRAMEBUFFER,fbo);
      gl.framebufferTexture2D(gl.FRAMEBUFFER,gl.COLOR_ATTACHMENT0,gl.TEXTURE_2D,target,0);
      if(gl.checkFramebufferStatus(gl.FRAMEBUFFER)!==gl.FRAMEBUFFER_COMPLETE) throw new Error('paper background framebuffer');
      gl.viewport(0,0,w,h);gl.disable(gl.BLEND);gl.bindVertexArray(vao2);gl.useProgram(background);
      const flag=(name,value)=>gl.uniform1i(u(background,name),value?1:0);
      flag('u_hasLook',!!fixture.look);flag('u_hasSurface',!!fixture.surface);
      flag('u_tinted',fixture.tintSet);flag('u_light',fixture.light);
      gl.uniform1i(u(background,'u_look'),0);gl.uniform1i(u(background,'u_surface'),1);
      gl.uniform3fv(u(background,'u_base'),fixture.base.map(v=>v/255));
      gl.uniform3fv(u(background,'u_mean'),(fixture.look?.mean||[255,255,255]).map(v=>v/255));
      const lamp=[-0.45,-0.55,0.70], lampLength=Math.hypot(...lamp);
      gl.uniform3fv(u(background,'u_lamp'),lamp.map(v=>v/lampLength));
      gl.uniform1f(u(background,'u_show'),fixture.show);
      gl.uniform1f(u(background,'u_relief'),fixture.surface?.relief || 0);
      gl.uniform1f(u(background,'u_slopeRange'),fixture.surface?.slopeRange || 0.099);
      gl.uniform1f(u(background,'u_detail'),fixture.detail || 0);
      // readPixels row 0 is the fixture's document top row; avoid a screen-dependent vertical flip.
      gl.uniformMatrix2fv(u(background,'u_docStep'),false,new Float32Array(fixture.docStep || [1,0,0,1]));
      for(let k=0;k<3;k++) {
        const entry=(k===0?fixture.look:fixture.surface);
        const pitch=(entry?.texelPx||2)*fixture.scale/(k===2?8:1);
        const hex=entry?.hexTexels||32, size=entry?.size||64;
        const frame=localFrame(fixture.origin,pitch,hex,size);
        gl.uniform1f(u(background,`u_pitch[${k}]`),pitch);
        gl.uniform1f(u(background,`u_hex[${k}]`),hex);
        gl.uniform1f(u(background,`u_size[${k}]`),size);
        flag(`u_rotate[${k}]`,entry?.rotatable);
        gl.uniform2iv(u(background,`u_hexBase[${k}]`),new Int32Array(frame.base));
        gl.uniform2fv(u(background,`u_localOrigin[${k}]`),frame.local);
        gl.uniform2fv(u(background,`u_baseCentreMod[${k}]`),frame.centre);
      }
      gl.drawArrays(gl.TRIANGLE_STRIP,0,4);
      const actual=new Uint8Array(w*h*4);gl.readPixels(0,0,w,h,gl.RGBA,gl.UNSIGNED_BYTE,actual);
      if(fixture.expected && fixture.expected.length!==actual.length) throw new Error('paper fixture byte count');
      let maxError=0,maxAdjacent=0,checksum=2166136261;
      for(let i=0;i<actual.length;i++) {
        if(fixture.expected) maxError=Math.max(maxError,Math.abs(actual[i]-fixture.expected[i]));
        checksum=Math.imul(checksum^actual[i],16777619)>>>0;
      }
      for(let y=0;y<h;y++) for(let x=0;x<w;x++) for(let c=0;c<3;c++) {
        const i=(y*w+x)*4+c;
        if(x+1<w) maxAdjacent=Math.max(maxAdjacent,Math.abs(actual[i]-actual[i+4]));
        if(y+1<h) maxAdjacent=Math.max(maxAdjacent,Math.abs(actual[i]-actual[i+w*4]));
      }
      return {origin:fixture.origin,width:w,height:h,maxError,maxAdjacent,checksum};
    }
    out.paperBackgroundParity=backgroundParity(S.paperFixtures[0]);
    out.paperLargeOriginParity=backgroundParity(S.paperFixtures[1]);
    const opaqueFlat=(base,w,h)=>Array.from({length:w*h*4},(_,i)=>i%4===3?255:base[i%4]);
    const flatCase={...S.paperFixtures[0],width:64,height:64,origin:[0,0]};
    out.paperBlack=backgroundParity({...flatCase,base:[0,0,0],look:null,surface:null,light:false,
      expected:opaqueFlat([0,0,0],64,64)});
    out.paperShowZero=backgroundParity({...flatCase,show:0,expected:opaqueFlat(flatCase.base,64,64)});
    // At maximum zoom the display-only octave is present; export fixture parity remains detail=0.
    const detailCase={...flatCase,docStep:[1/64,0,0,1/64],expected:null};
    const detailBase=backgroundParity(detailCase), detailFine=backgroundParity({...detailCase,detail:0.35});
    out.paperDetailSmoke={zoom:64,changed:detailBase.checksum!==detailFine.checksum};
    out.paperBackgroundOk=out.paperBackgroundParity.maxError<=3 && out.paperLargeOriginParity.maxError<=3 &&
      out.paperLargeOriginParity.maxAdjacent<3 && out.paperBlack.maxError===0 && out.paperShowZero.maxError===0 &&
      out.paperDetailSmoke.changed;
    out.glError = gl.getError();
    return out;
  }, S);
  const compact={...res};delete compact.smudgeOpacityCases;delete compact.coverageOpacityCases;
  console.log(JSON.stringify(compact));
  await browser.close();
  if (!res.compiled || !res.tuftCompiled || !res.paperBackgroundCompiled || !res.paperBackgroundOk || !res.paperNoRepeat || !res.flatSlopeExactZero || !res.fbo || res.glError !== 0 || !res.grainDarkens || !res.grainAllFinite ||
      !res.smudgeOk || !res.smudgeOpacityOk || !res.coverageOpacityOk ||
      Math.abs(res.strokeCentre - 0.5) > 0.01 || res.strokeOutside !== 0 ||
      !res.committedRGBA?.every((v, i) => Math.abs(v - [127, 0, 0, 127][i]) <= 2)) process.exitCode = 1;
})().catch(e => { console.error(e); process.exit(1); });
