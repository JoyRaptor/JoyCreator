#!/usr/bin/env node
// render_sheet.js: draws a Line Lab sheet (written by LineLabSheetTest) with Joy Brush's REAL shaders, headless.
//
//   NODE_PATH=<node_modules holding playwright-core> node render_sheet.js <sheet.json> <out.png>
//       [--crop x,y,w,h,scale] [--crop-out crop.png] [--shaders <dir>]
//
// The whole sheet is ONE tile: jb_dab.vert/frag draw every dab into an RGBA16F "stroke buffer", then jb_tile.vert +
// jb_commit.frag commit it into the layer, stroke by stroke, exactly the pair GlPaintEngine uses. The layer is shown over
// white and written as an 8-bit PNG. --crop also writes a nearest-neighbour magnified copy of that region (captioned
// "magnified pixels"), to --crop-out or <out>_crop.png. CHROME=<exe> overrides the browser (default: Edge).
// Throws on: a GL error, a stroke that drew or changed nothing, a NaN anywhere.
const fs = require('fs'), path = require('path'), zlib = require('zlib');
const { chromium } = require('playwright-core');
const root = path.join(__dirname, '..', '..');   // joybrush/
let shaderDir = path.join(root, 'shaders');   // --shaders <dir> swaps in another branch's shader folder
const source = n => fs.readFileSync(path.join(shaderDir, n), 'utf8').replace(/^#include "([^"]+)"/gm, (_, m) => source(m));

// A small PNG reader (8/16-bit gray, gray+alpha, RGB, RGBA, 8-bit palette; not interlaced) that returns STRAIGHT RGBA bytes,
// the way GrainTextures hands them to the GPU (BitmapFactory with inPremultiplied=false): a browser decode could premultiply
// the packed paper's alpha channel (height squared), so the bytes are decoded here and uploaded raw.
function decodePng(file) {
  const b = fs.readFileSync(file);
  if (b.readUInt32BE(0) !== 0x89504e47) throw Error('not a PNG: ' + file);
  let pos = 8, w = 0, h = 0, depth = 0, ctype = 0, interlace = 0, plte = null, trns = null; const idat = [];
  while (pos < b.length) {
    const len = b.readUInt32BE(pos), type = b.toString('latin1', pos + 4, pos + 8), d = b.subarray(pos + 8, pos + 8 + len);
    if (type === 'IHDR') { w = d.readUInt32BE(0); h = d.readUInt32BE(4); depth = d[8]; ctype = d[9]; interlace = d[12]; }
    else if (type === 'PLTE') plte = d; else if (type === 'tRNS') trns = d; else if (type === 'IDAT') idat.push(d); else if (type === 'IEND') break;
    pos += 12 + len;
  }
  const channels = { 0: 1, 2: 3, 3: 1, 4: 2, 6: 4 }[ctype];
  if (interlace || !channels || (depth !== 8 && depth !== 16) || (ctype === 3 && depth !== 8)) throw Error(`unsupported PNG (depth ${depth}, type ${ctype}, interlace ${interlace}): ${file}`);
  const bpp = channels * depth / 8, stride = w * bpp, raw = zlib.inflateSync(Buffer.concat(idat)), px = Buffer.alloc(h * stride);
  for (let y = 0; y < h; y++) {
    const f = raw[y * (stride + 1)], src = y * (stride + 1) + 1, dst = y * stride;
    for (let x = 0; x < stride; x++) {
      const a = x >= bpp ? px[dst + x - bpp] : 0, up = y ? px[dst - stride + x] : 0, c = x >= bpp && y ? px[dst - stride + x - bpp] : 0;
      let v = raw[src + x];
      if (f === 1) v += a; else if (f === 2) v += up; else if (f === 3) v += (a + up) >> 1;
      else if (f === 4) { const p = a + up - c, pa = Math.abs(p - a), pb = Math.abs(p - up), pc = Math.abs(p - c); v += (pa <= pb && pa <= pc) ? a : (pb <= pc ? up : c); }
      else if (f !== 0) throw Error('bad PNG filter ' + f + ': ' + file);
      px[dst + x] = v & 255;
    }
  }
  const rgba = Buffer.alloc(w * h * 4), s = depth / 8;   // s = bytes per sample; the high byte is the 8-bit value
  for (let i = 0; i < w * h; i++) {
    const o = i * 4, q = i * bpp;
    if (ctype === 0) { rgba[o] = rgba[o + 1] = rgba[o + 2] = px[q]; rgba[o + 3] = 255; }
    else if (ctype === 2) { rgba[o] = px[q]; rgba[o + 1] = px[q + s]; rgba[o + 2] = px[q + 2 * s]; rgba[o + 3] = 255; }
    else if (ctype === 3) { const k = px[q]; rgba[o] = plte[k * 3]; rgba[o + 1] = plte[k * 3 + 1]; rgba[o + 2] = plte[k * 3 + 2]; rgba[o + 3] = trns && k < trns.length ? trns[k] : 255; }
    else if (ctype === 4) { rgba[o] = rgba[o + 1] = rgba[o + 2] = px[q]; rgba[o + 3] = px[q + s]; }
    else { rgba[o] = px[q]; rgba[o + 1] = px[q + s]; rgba[o + 2] = px[q + 2 * s]; rgba[o + 3] = px[q + 3 * s]; }
  }
  return { w, h, b64: rgba.toString('base64') };
}

async function main() {
  const args = process.argv.slice(2);
  const opt = n => { const i = args.indexOf(n); return i < 0 ? null : args[i + 1]; };
  const positional = args.filter((a, i) => !a.startsWith('--') && !(i > 0 && args[i - 1].startsWith('--')));
  if (positional.length < 2) { console.error('usage: node render_sheet.js <sheet.json> <out.png> [--crop x,y,w,h,scale] [--crop-out crop.png] [--shaders <dir>]'); process.exit(2); }
  if (opt('--shaders')) { shaderDir = path.resolve(opt('--shaders')); if (!fs.existsSync(path.join(shaderDir, 'jb_dab.frag'))) throw Error('no jb_dab.frag in --shaders ' + shaderDir); }
  const [sheetPath, outPath] = positional;
  let crop = null;
  if (opt('--crop')) {
    const p = opt('--crop').split(',').map(Number);
    if (p.length !== 5 || p.some(v => !Number.isFinite(v)) || p[2] <= 0 || p[3] <= 0 || p[4] < 1) throw Error('--crop needs x,y,w,h,scale');
    crop = { x: p[0], y: p[1], w: p[2], h: p[3], scale: Math.round(p[4]) };
  }
  const t0 = Date.now();
  const sheet = JSON.parse(fs.readFileSync(sheetPath, 'utf8'));
  if (sheet.format !== 'joybrush.linelab.sheet') throw Error('not a line lab sheet: ' + sheetPath);

  // Pictures: paper surface (unit 1), its fluid map (unit 2), and each stroke's tip grain (unit 0).
  const load = rel => { const d = decodePng(path.join(root, rel)); d.rel = rel; return d; };
  const tex = { paper: null, fluid: null, tip: {} };
  if (sheet.paper.surfaceAsset) {
    tex.paper = load(sheet.paper.surfaceAsset);
    if (tex.paper.w !== sheet.paper.size || tex.paper.h !== sheet.paper.size) throw Error(`paper picture is ${tex.paper.w}x${tex.paper.h} but the catalogue says ${sheet.paper.size}`);
  }
  if (sheet.paper.fluid) tex.fluid = load(sheet.paper.fluid.asset);
  for (const s of sheet.strokes) if (s.tipGrain.asset && !tex.tip[s.tipGrain.asset]) tex.tip[s.tipGrain.asset] = load(s.tipGrain.asset);

  const browser = await chromium.launch({ executablePath: process.env.CHROME || 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
    args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
  let result;
  const t1 = Date.now();
  try {
    const page = await browser.newPage();
    page.setDefaultTimeout(0);
    result = await page.evaluate(renderInPage, { vert: source('jb_dab.vert'), frag: source('jb_dab.frag'), tileVert: source('jb_tile.vert'),
      commitFrag: source('jb_commit.frag'), sheet, tex, crop, label: path.basename(sheetPath, '.json') });
  } finally { await browser.close(); }
  const t2 = Date.now();

  const save = (file, dataUrl) => { fs.mkdirSync(path.dirname(path.resolve(file)), { recursive: true }); fs.writeFileSync(file, Buffer.from(dataUrl.split(',')[1], 'base64')); };
  save(outPath, result.png);
  let cropOut = null;
  if (crop) { cropOut = opt('--crop-out') || outPath.replace(/\.png$/i, '') + '_crop.png'; save(cropOut, result.cropPng); }
  for (const s of result.strokes) console.log(`  ${s.name.padEnd(22)} ${String(s.dabs).padStart(6)} dabs  stroke px ${String(s.covered).padStart(7)}  layer px changed ${String(s.changed).padStart(7)}  ${s.ms} ms`);
  if (result.unusedUniforms.length) console.log('  uniforms the shader does not use (optimised out): ' + result.unusedUniforms.join(', '));
  console.log(`shaders: ${shaderDir}`);
  console.log(`${outPath}${cropOut ? ' + ' + cropOut + ' (magnified pixels ' + crop.scale + 'x)' : ''}: ${sheet.strokes.length} strokes, ${result.strokes.reduce((a, s) => a + s.dabs, 0)} dabs; GL ${result.glMs} ms, browser start ${t1 - t0 - 0} ms (incl. pictures), total ${Date.now() - t0} ms (page ${t2 - t1} ms)`);
}

// Runs inside the browser page. Self-contained: it may use nothing from the node side except its argument.
async function renderInPage({ vert, frag, tileVert, commitFrag, sheet, tex, crop, label }) {
  const T = sheet.sheet.tileSize, W = sheet.sheet.width, H = sheet.sheet.height;
  const canvas = document.createElement('canvas'); canvas.width = 16; canvas.height = 16;
  const gl = canvas.getContext('webgl2', { antialias: false, preserveDrawingBuffer: true });
  if (!gl || !gl.getExtension('EXT_color_buffer_float')) throw Error('Float WebGL2 required');
  gl.getExtension('EXT_float_blend');
  const glStart = performance.now();
  const shader = (type, s) => { const sh = gl.createShader(type); gl.shaderSource(sh, s); gl.compileShader(sh); if (!gl.getShaderParameter(sh, gl.COMPILE_STATUS)) throw Error(gl.getShaderInfoLog(sh)); return sh; };
  const program = (v, f) => { const p = gl.createProgram(); gl.attachShader(p, shader(gl.VERTEX_SHADER, v)); gl.attachShader(p, shader(gl.FRAGMENT_SHADER, f)); gl.linkProgram(p); if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw Error(gl.getProgramInfoLog(p)); return p; };
  const unused = new Set();
  const uniforms = p => {
    const cache = {}, loc = n => (n in cache ? cache[n] : (cache[n] = gl.getUniformLocation(p, n)));
    const ok = (n, l) => { if (l === null) unused.add(n); return l !== null; };
    return {
      f: (n, v) => { if (!Number.isFinite(v)) throw Error('non-finite uniform ' + n); const l = loc(n); if (ok(n, l)) gl.uniform1f(l, v); },
      i: (n, v) => { const l = loc(n); if (ok(n, l)) gl.uniform1i(l, v); },
      v2: (n, a, b) => { const l = loc(n); if (ok(n, l)) gl.uniform2f(l, a, b); },
      v3: (n, a, b, c) => { const l = loc(n); if (ok(n, l)) gl.uniform3f(l, a, b, c); },
      v4: (n, a, b, c, d) => { const l = loc(n); if (ok(n, l)) gl.uniform4f(l, a, b, c, d); },
      m3: (n, m) => { const l = loc(n); if (ok(n, l)) gl.uniformMatrix3fv(l, false, m); },
    };
  };
  const dabProg = program(vert, frag), commitProg = program(tileVert, commitFrag);
  const du = uniforms(dabProg), cu = uniforms(commitProg);

  // Pictures: REPEAT, mipmapped, linear -- GrainTextures.textureFor. Straight RGBA bytes, no premultiply, no flip.
  gl.pixelStorei(gl.UNPACK_ALIGNMENT, 1); gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, false); gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, false);
  const picture = (w, h, bytes) => {
    const t = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, t);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, w, h, 0, gl.RGBA, gl.UNSIGNED_BYTE, bytes);
    gl.generateMipmap(gl.TEXTURE_2D);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR_MIPMAP_LINEAR); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.REPEAT); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.REPEAT);
    return t;
  };
  const decode = img => { const s = atob(img.b64), b = new Uint8Array(s.length); for (let i = 0; i < s.length; i++) b[i] = s.charCodeAt(i); return picture(img.w, img.h, b); };
  const placeholder = picture(1, 1, new Uint8Array([255, 255, 255, 255]));   // GrainTextures.placeholder: 1x1 white
  const paperTex = tex.paper ? decode(tex.paper) : placeholder;
  const fluidTex = tex.fluid ? decode(tex.fluid) : placeholder;
  const tipTex = {}; for (const k of Object.keys(tex.tip)) tipTex[k] = decode(tex.tip[k]);

  // Render targets: the stroke buffer and two layer textures (ping-pong), all RGBA16F, nearest.
  const target = () => {
    const t = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, t);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA16F, T, T, 0, gl.RGBA, gl.HALF_FLOAT, null);
    for (const k of [gl.TEXTURE_MIN_FILTER, gl.TEXTURE_MAG_FILTER]) gl.texParameteri(gl.TEXTURE_2D, k, gl.NEAREST);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    return t;
  };
  const strokeTex = target(), layers = [target(), target()];
  const fbo = gl.createFramebuffer();
  const attach = t => { gl.bindFramebuffer(gl.FRAMEBUFFER, fbo); gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, t, 0);
    if (gl.checkFramebufferStatus(gl.FRAMEBUFFER) !== gl.FRAMEBUFFER_COMPLETE) throw Error('framebuffer incomplete'); };
  const clear = t => { attach(t); gl.viewport(0, 0, T, T); gl.disable(gl.SCISSOR_TEST); gl.clearColor(0, 0, 0, 0); gl.clear(gl.COLOR_BUFFER_BIT); };
  const readRegion = (t, x, y, w, h) => { attach(t); const a = new Float32Array(w * h * 4); gl.readPixels(x, y, w, h, gl.RGBA, gl.FLOAT, a); return a; };
  clear(strokeTex); clear(layers[0]); clear(layers[1]);

  // Geometry: the dab quad (corners -1..1, location 0) with the instance attributes, and the commit quad (corners 0..1).
  const dabVao = gl.createVertexArray(); gl.bindVertexArray(dabVao);
  const quad = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, quad);
  gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW);
  gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
  const instances = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, instances);
  for (const [loc, count, off] of [[1, 4, 0], [2, 2, 16], [4, 2, 24], [5, 4, 32], [6, 4, 48], [7, 1, 64]]) {
    gl.enableVertexAttribArray(loc); gl.vertexAttribPointer(loc, count, gl.FLOAT, false, 68, off); gl.vertexAttribDivisor(loc, 1);
  }
  gl.vertexAttrib4f(3, 0, 0, 0, 0);   // a_carried: smudge only, never enabled by a stamp brush
  const tileVao = gl.createVertexArray(); gl.bindVertexArray(tileVao);
  const quad01 = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, quad01);
  gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([0, 0, 1, 0, 0, 1, 1, 1]), gl.STATIC_DRAW);
  gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
  gl.bindVertexArray(null);

  const paper = sheet.paper, strokeStats = [];
  let cur = 0, totalDabs = 0;
  const bind = (unit, t) => { gl.activeTexture(gl.TEXTURE0 + unit); gl.bindTexture(gl.TEXTURE_2D, t); };
  const checkGl = what => { const e = gl.getError(); if (e !== gl.NO_ERROR) throw Error('glError 0x' + e.toString(16) + ' ' + what); };
  for (const st of sheet.strokes) {
    const t0 = performance.now(), n = st.dabCount;
    if (st.dabs.length !== n * 17) throw Error(`${st.name}: ${st.dabs.length} floats for ${n} dabs`);
    const data = new Float32Array(st.dabs);

    // -- the dabs, into a cleared stroke buffer: blend ONE, ONE_MINUS_SRC_ALPHA on colour and alpha --
    clear(strokeTex);
    gl.enable(gl.BLEND); gl.blendFuncSeparate(gl.ONE, gl.ONE_MINUS_SRC_ALPHA, gl.ONE, gl.ONE_MINUS_SRC_ALPHA);
    gl.useProgram(dabProg); gl.bindVertexArray(dabVao); gl.viewport(0, 0, T, T);
    du.v2('u_tileOrigin', 0, 0); du.f('u_tileSize', T);
    du.f('u_aspect', st.tip.aspect); du.f('u_corner', st.tip.corner); du.f('u_taper', st.tip.taper); du.f('u_hardness', st.tip.hardness); du.f('u_minPx', st.tip.minPx);
    const tg = st.tipGrain, pg = st.paperGrain, pr = st.paperResponse;
    bind(0, tg.asset ? tipTex[tg.asset] : placeholder); bind(1, paperTex); bind(2, fluidTex); gl.activeTexture(gl.TEXTURE0);
    du.i('u_tipGrain', 0); du.i('u_paperSurface', 1); du.i('u_paperFluid', 2);
    du.f('u_tipGrainPitchPx', tg.pitchPx); du.f('u_tipDepth', tg.depth); du.f('u_tipEdge', tg.edge); du.f('u_tipTiltGradient', tg.tiltGradient); du.f('u_tipRadial', tg.radial);
    du.f('u_paperGrainPitchPx', pg.pitchPx); du.f('u_paperDepth', pg.depth); du.f('u_paperEdge', pg.edge); du.f('u_paperTiltGradient', pg.tiltGradient); du.f('u_paperRadial', pg.radial);
    du.f('u_paperInfluence', pr.influence); du.f('u_paperDirectional', pr.directional); du.f('u_paperWet', pr.wet);
    du.f('u_tiltAmount', st.legacy.tiltAmount); du.v2('u_leanDir', st.legacy.leanDir[0], st.legacy.leanDir[1]);
    du.v4('u_tilePaperRect', 0, 0, 0, 0);
    du.f('u_paperTexelPx', paper.texelPx); du.f('u_paperSize', paper.size); du.f('u_paperHexTexels', paper.hexTexels);
    du.f('u_paperSlopeRange', paper.slopeRange); du.f('u_paperHeightMean', paper.heightMean); du.i('u_paperRotatable', paper.rotatable ? 1 : 0);
    du.i('u_grainCapacity', st.grainCapacity || 0);   // absent from older shaders: a null location is a no-op
    du.f('u_paperFluidTexelPx', paper.fluid ? paper.fluid.texelPx : 0); du.f('u_paperFluidSize', paper.fluid ? paper.fluid.size : 1); du.f('u_paperFluidHexTexels', paper.fluid ? paper.fluid.hexTexels : 150);
    gl.bindBuffer(gl.ARRAY_BUFFER, instances);
    for (let o = 0; o < n; o += 256) {   // batches only to keep each GPU call short; the order is the stroke order
      const m = Math.min(256, n - o);
      gl.bufferData(gl.ARRAY_BUFFER, data.subarray(o * 17, (o + m) * 17), gl.STREAM_DRAW);
      gl.drawArraysInstanced(gl.TRIANGLE_STRIP, 0, 4, m);
    }
    gl.bindVertexArray(null); gl.disable(gl.BLEND);
    checkGl(st.name + ' (dabs)');

    // the stroke's footprint: every pixel the dabs can reach lies in bbox
    const bx = Math.max(0, Math.floor(st.bbox[0])), by = Math.max(0, Math.floor(st.bbox[1]));
    const bw = Math.min(T, Math.ceil(st.bbox[2])) - bx, bh = Math.min(T, Math.ceil(st.bbox[3])) - by;
    const sbuf = readRegion(strokeTex, bx, by, bw, bh);
    let covered = 0;
    for (let i = 0; i < sbuf.length; i++) if (!Number.isFinite(sbuf[i])) throw Error(`${st.name}: NaN/Inf in the stroke buffer`);
    for (let i = 0; i < bw * bh; i++) if (sbuf[i * 4] > 0) covered++;
    if (covered === 0) throw Error(`${st.name}: the dabs drew nothing`);

    // -- commit: jb_commit.frag, layer[cur] + stroke -> layer[1 - cur], blending OFF --
    const before = readRegion(layers[cur], bx, by, bw, bh), next = 1 - cur;
    attach(layers[next]); gl.viewport(0, 0, T, T); gl.disable(gl.BLEND);
    gl.useProgram(commitProg); gl.bindVertexArray(tileVao);
    cu.v2('u_tileOrigin', 0, 0); cu.f('u_tileSize', T); cu.m3('u_docToClip', new Float32Array([2 / T, 0, 0, 0, 2 / T, 0, -1, -1, 1]));
    bind(0, layers[cur]); bind(1, strokeTex); gl.activeTexture(gl.TEXTURE0);
    cu.i('u_layer', 0); cu.i('u_stroke', 1); cu.v3('u_color', st.color[0], st.color[1], st.color[2]);
    cu.f('u_strokeScale', st.strokeScale); cu.i('u_erase', 0); cu.i('u_blend', st.blend); cu.i('u_smudge', 0); cu.f('u_layerOpacity', 1);
    gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
    gl.bindVertexArray(null);
    checkGl(st.name + ' (commit)');
    const after = readRegion(layers[next], bx, by, bw, bh);
    let changed = 0;
    for (let i = 0; i < after.length; i++) if (!Number.isFinite(after[i])) throw Error(`${st.name}: NaN/Inf in the layer`);
    for (let i = 0; i < bw * bh; i++) { const o = i * 4; if (after[o] !== before[o] || after[o + 1] !== before[o + 1] || after[o + 2] !== before[o + 2] || after[o + 3] !== before[o + 3]) changed++; }
    if (changed === 0) throw Error(`${st.name}: the commit changed no pixel`);
    cur = next; totalDabs += n;
    strokeStats.push({ name: st.name, dabs: n, covered, changed, ms: Math.round(performance.now() - t0) });
  }

  // -- the layer over white (premultiplied: rgb + (1 - a) * 1), to 8 bits --
  attach(layers[cur]);
  const buf = new Float32Array(W * H * 4); gl.readPixels(0, 0, W, H, gl.RGBA, gl.FLOAT, buf);
  checkGl('final read');
  const bg = sheet.sheet.background, out = new Uint8ClampedArray(W * H * 4);
  for (let i = 0; i < W * H; i++) {
    const o = i * 4, a = buf[o + 3];
    for (let c = 0; c < 3; c++) {
      const v = buf[o + c] + (1 - a) * bg[c];
      if (!Number.isFinite(v)) throw Error(`NaN in the final image at pixel ${i % W},${Math.floor(i / W)}`);
      out[o + c] = Math.round(Math.min(1, Math.max(0, v)) * 255);
    }
    out[o + 3] = 255;
  }
  const glMs = Math.round(performance.now() - glStart);
  const toUrl = (w, h, data) => { const c = document.createElement('canvas'); c.width = w; c.height = h; c.getContext('2d').putImageData(new ImageData(data, w, h), 0, 0); return c.toDataURL('image/png'); };
  const result = { png: toUrl(W, H, out), strokes: strokeStats, unusedUniforms: [...unused], glMs };

  if (crop) {
    const cx = Math.max(0, Math.min(W - 1, Math.round(crop.x))), cy = Math.max(0, Math.min(H - 1, Math.round(crop.y)));
    const cw = Math.min(W - cx, Math.round(crop.w)), ch = Math.min(H - cy, Math.round(crop.h));
    const sub = new Uint8ClampedArray(cw * ch * 4);
    for (let y = 0; y < ch; y++) sub.set(out.subarray(((cy + y) * W + cx) * 4, ((cy + y) * W + cx + cw) * 4), y * cw * 4);
    const small = document.createElement('canvas'); small.width = cw; small.height = ch;
    small.getContext('2d').putImageData(new ImageData(sub, cw, ch), 0, 0);
    const band = 26, big = document.createElement('canvas'); big.width = cw * crop.scale; big.height = ch * crop.scale + band;
    const g = big.getContext('2d');
    g.fillStyle = '#26262b'; g.fillRect(0, 0, big.width, band);
    g.fillStyle = '#e8e8ec'; g.font = '14px sans-serif'; g.textBaseline = 'middle';
    g.fillText(`magnified pixels ${crop.scale}x (nearest neighbour) - ${label} - crop x=${cx} y=${cy} w=${cw} h=${ch} of the 1:1 sheet`, 8, band / 2);
    g.imageSmoothingEnabled = false;
    g.drawImage(small, 0, band, cw * crop.scale, ch * crop.scale);
    result.cropPng = big.toDataURL('image/png');
  }
  return result;
}

main().catch(e => { console.error(e && e.stack || e); process.exitCode = 1; });
