#!/usr/bin/env node
// Real watercolour shaders, WebGL2/SwiftShader. No Android build or phone access.
// NODE_PATH=<playwright-core runtime> node joybrush/tools/watercolour_check.js
// --poc <readonly index.html> renders W1 golden paths in the actual POC for DeltaE comparison.
// --diagnostic --out <dir> also saves reference/actual PNGs for shape and unpooled isolation.
const fs = require('fs'), path = require('path'), zlib = require('zlib'), crypto = require('crypto');
const { chromium } = require('playwright-core');
const root = path.join(__dirname, '..');
const source = name => fs.readFileSync(path.join(root, 'shaders', name), 'utf8')
  .replace(/^#include "([^"]+)"/gm, (_, include) => source(include));
const option = name => { const i = process.argv.indexOf(name); return i < 0 ? null : process.argv[i + 1]; };

// Decode scalar asset bytes without browser premultiplication or colour management.
function png(file) {
  const buf = fs.readFileSync(file), chunks = []; let at = 8, width, height, type, depth;
  if (buf.readUInt32BE(0) !== 0x89504e47) throw Error('Not PNG: ' + file);
  while (at < buf.length) {
    const n = buf.readUInt32BE(at), tag = buf.toString('ascii', at + 4, at + 8), data = buf.subarray(at + 8, at + 8 + n);
    if (tag === 'IHDR') { width = data.readUInt32BE(0); height = data.readUInt32BE(4); depth = data[8]; type = data[9]; if (data[12]) throw Error('Interlaced PNG'); }
    if (tag === 'IDAT') chunks.push(data);
    at += n + 12;
  }
  const channels = { 0: 1, 2: 3, 4: 2, 6: 4 }[type];
  if (depth !== 8 || !channels) throw Error('Need 8-bit PNG: ' + file);
  const row = width * channels, raw = zlib.inflateSync(Buffer.concat(chunks)), pixels = new Uint8Array(row * height);
  for (let y = 0; y < height; y++) for (let x = 0; x < row; x++) {
    const a = x >= channels ? pixels[y * row + x - channels] : 0, b = y ? pixels[(y - 1) * row + x] : 0,
      c = y && x >= channels ? pixels[(y - 1) * row + x - channels] : 0, f = raw[y * (row + 1)];
    let prediction = 0;
    if (f === 1) prediction = a; else if (f === 2) prediction = b; else if (f === 3) prediction = (a + b) >> 1;
    else if (f === 4) { const p = a + b - c, da = Math.abs(p - a), db = Math.abs(p - b), dc = Math.abs(p - c); prediction = da <= db && da <= dc ? a : db <= dc ? b : c; }
    else if (f !== 0) throw Error('Bad PNG filter');
    pixels[y * row + x] = (raw[y * (row + 1) + x + 1] + prediction) & 255;
  }
  const red = Array.from({ length: width * height }, (_, i) => pixels[i * channels]);
  const rgba=new Uint8Array(width*height*4);for(let i=0;i<width*height;i++){const at=i*channels;rgba[i*4]=pixels[at];rgba[i*4+1]=channels>=3?pixels[at+1]:pixels[at];rgba[i*4+2]=channels>=3?pixels[at+2]:pixels[at];rgba[i*4+3]=type===4?pixels[at+1]:type===6?pixels[at+3]:255;}
  return { width, height, red, rgba };
}

async function gpuChecks({ vert, frag, finishVert, finishFrag, mixFrag, assets, perceptual }) {
  const canvas = document.createElement('canvas'), gl = canvas.getContext('webgl2', { antialias: false });
  if (!gl || !gl.getExtension('EXT_color_buffer_float')) throw Error('Float WebGL2 required');
  const failures = [], results = {};
  const check = (name, condition, detail) => { results[name] = detail; if (!condition) failures.push(name); };
  const compile = (type, code) => { const s = gl.createShader(type); gl.shaderSource(s, code); gl.compileShader(s); if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw Error(gl.getShaderInfoLog(s)); return s; };
  const program = (vs, fs) => { const p = gl.createProgram(); gl.attachShader(p, compile(gl.VERTEX_SHADER, vs)); gl.attachShader(p, compile(gl.FRAGMENT_SHADER, fs)); gl.linkProgram(p); if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw Error(gl.getProgramInfoLog(p)); return p; };
  const dab = program(vert, frag), finish = program(finishVert, finishFrag);
  const fade = program(finishVert, mixFrag);
  const fadeWrongAmount = program(finishVert, mixFrag.replace('clamp(u_amount,0.0,1.0)', '1.0'));
  const fadeWrongAlpha = program(finishVert, mixFrag.replace('clamp(u_amount,0.0,1.0));', 'clamp(u_amount,0.0,1.0)); o_color.a=1.0-(1.0-texture(u_plain,uv).a)*(1.0-texture(u_pooled,uv).a);'));
  const mutantAlpha = program(finishVert, finishFrag.replace('1.0+4.0*u_edgeAlpha*edge', '1.0'));
  const mutantTint = program(finishVert, finishFrag.replace('vec3(concentration)', 'vec3(1.0)'));
  const mutantNeighbour = program(finishVert, finishFrag.replace('ivec2 q=p-tile*u_bandTileSize;', 'ivec2 q=p-tile*u_bandTileSize; if(any(notEqual(tile,ivec2(0)))) return vec3(0.0);'));
  const mutantGrain = program(finishVert, finishFrag.replace('u_grain*0.8*texture(u_fineGrain,v_docPx/256.0).r', '0.0'));
  const mutantPaperCut = program(finishVert, finishFrag.replace('bands*=mix(vec3(1.0),cut,u_paperCut);', 'bands*=vec3(1.0);'));
  function texture(w, h, values, floating = false) {
    const t = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, t);
    gl.texImage2D(gl.TEXTURE_2D, 0, floating ? gl.RGBA16F : gl.RGBA8, w, h, 0, gl.RGBA, floating ? gl.FLOAT : gl.UNSIGNED_BYTE, values);
    for (const [k, v] of [[gl.TEXTURE_MIN_FILTER, gl.NEAREST], [gl.TEXTURE_MAG_FILTER, gl.NEAREST], [gl.TEXTURE_WRAP_S, gl.REPEAT], [gl.TEXTURE_WRAP_T, gl.REPEAT]]) gl.texParameteri(gl.TEXTURE_2D, k, v);
    return t;
  }
  const black = texture(1, 1, new Uint8Array([0, 0, 0, 255]));
  const paperByte = 96, height = paperByte / 255;
  const paper = texture(1, 1, new Uint8Array([127, 127, paperByte, Math.ceil(height * height * 255)]));
  const bind = (p, name, unit, tex) => { gl.activeTexture(gl.TEXTURE0 + unit); gl.bindTexture(gl.TEXTURE_2D, tex); gl.uniform1i(gl.getUniformLocation(p, name), unit); };
  function target(n, floating) {
    const t = texture(n, n, null, floating), fb = gl.createFramebuffer(); gl.bindFramebuffer(gl.FRAMEBUFFER, fb); gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, t, 0);
    if (gl.checkFramebufferStatus(gl.FRAMEBUFFER) !== gl.FRAMEBUFFER_COMPLETE) throw Error('Incomplete framebuffer');
    gl.viewport(0, 0, n, n); gl.clearColor(0, 0, 0, 0); gl.clear(gl.COLOR_BUFFER_BIT); return { t, fb };
  }
  const vao = gl.createVertexArray(); gl.bindVertexArray(vao);
  const quad = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, quad); gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([0,0, 1,0, 0,1, 1,1]), gl.STATIC_DRAW); gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
  let tint = [.31, .68, .88]; const decode = x => x <= .04045 ? x / 12.92 : ((x + .055) / 1.055) ** 2.4, encode = x => x <= .0031308 ? 12.92 * x : 1.055 * x ** (1 / 2.4) - .055;
  const config = { edgeWidth: 3, edgePool: .65, edgeAlpha: .72, gravityWidth: 36, gravityPool: .57, paperCut: .5, pooling: 1 };
  function finished(p, origin, n, neighbours, tileSize, s = config, scale = 1) {
    const out = target(n, false); gl.disable(gl.BLEND); gl.useProgram(p); gl.bindVertexArray(vao);
    const u = name => gl.getUniformLocation(p, name), f = (name, v) => gl.uniform1f(u(name), v);
    gl.uniform2f(u('u_tileOrigin'), ...origin); f('u_outputDocSize', n / scale); f('u_scale', scale); gl.uniform1i(u('u_bandTileSize'), tileSize);
    neighbours.forEach((tex, i) => bind(p, 'u_band' + (i % 3) + Math.floor(i / 3), i, tex));
    bind(p, 'u_backdrop', 9, s.backdrop || black); bind(p, 'u_fineGrain', 10, s.fineGrain || black); bind(p, 'u_paperSurface', 11, s.paperTexture || paper);
    const ps=s.paperSpec;f('u_paperTexelPx', ps?ps.texelPx:1); f('u_paperSize', ps?ps.size:1); f('u_paperHexTexels', ps?ps.hexTexels:16); f('u_paperSlopeRange', ps?ps.slopeRange:.1); f('u_paperHeightMean', ps?(ps.heightMean??.5):height);
    gl.uniform1i(u('u_paperRotatable'), ps&&ps.rotatable?1:0); gl.uniform4f(u('u_tilePaperRect'), 0, 0, 0, 0); gl.uniform1i(u('u_hasPaper'), 1);
    gl.uniform3f(u('u_tintSrgb'), ...tint); gl.uniform3f(u('u_tintLinear'), ...tint.map(decode)); gl.uniform2f(u('u_gravityDirection'), 0, 1);
    for (const name of ['edgeWidth', 'edgePool', 'edgeAlpha', 'gravityWidth', 'gravityPool', 'paperCut', 'pooling']) f('u_' + name, s[name]);
    f('u_backdropWorld', s.backdropWorld || 1254); gl.uniform2f(u('u_backdropOffset'), ...(s.backdropOffset || [113,287])); f('u_textureStrength', s.textureStrength || 0); f('u_textureContrast', s.textureContrast || 1); gl.uniform1i(u('u_textureInvert'), s.textureInvert || 0); f('u_grain', s.grain || 0);
    // Only skip pixels outside conservative stamp geometry; both paths are exactly transparent
    // there (pooling is an interior effect). Metrics still include every full-frame pixel.
    if(s.scissor){gl.enable(gl.SCISSOR_TEST);gl.scissor(...s.scissor);}
    gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);gl.disable(gl.SCISSOR_TEST);
    const bytes = new Uint8Array(n * n * 4); gl.readPixels(0, 0, n, n, gl.RGBA, gl.UNSIGNED_BYTE, bytes); gl.deleteFramebuffer(out.fb); gl.deleteTexture(out.t); return bytes;
  }
  // Float band injection isolates finish optics from dab rasterization and uses canonical paper.
  const N = 64, raw = new Float32Array(N * N * 4);
  for (let y = 0; y < N; y++) for (let x = 0; x < N; x++) if (x >= 13 && x < 51 && y >= 10 && y < 45) {
    const at = (y * N + x) * 4; raw[at] = .5; raw[at + 1] = .25; raw[at + 2] = .125;
  }
  const bands = texture(N, N, raw, true), empty = texture(N, N, new Float32Array(N * N * 4), true), near = Array(9).fill(empty); near[4] = bands;
  const coverage = (x, y, s) => {
    if (x < 13 || x >= 51 || y < 10 || y >= 45) return 0;
    const clamp = x => Math.max(0, Math.min(1, x)), cut = [1, clamp((height - .425) * 8 + .5), clamp((height - .85) * 8 + .5)];
    return 1 - [.5, .25, .125].reduce((a, b, i) => a * (1 - b * (1 - s.paperCut * (1 - cut[i]))), 1);
  };
  function expected(s) {
    const out = new Uint8Array(N * N * 4);
    for (let y = 0; y < N; y++) for (let x = 0; x < N; x++) {
      const a = coverage(x, y, s), inner = Math.min(...[[s.edgeWidth,0],[-s.edgeWidth,0],[0,s.edgeWidth],[0,-s.edgeWidth]].map(([dx,dy]) => coverage(Math.floor(x + .5 + dx), Math.floor(y + .5 + dy), s))), edge = Math.max(0, a - inner) / Math.max(a, 1e-8);
      let gravity = 0; for (let k = 1; k <= 4; k++) gravity += Math.max(0, a - coverage(x, Math.floor(y + .5 + s.gravityWidth * k / 4), s)) / Math.max(a, 1e-8) / 4;
      const concentration = 1 + 2 * Math.max(0, Math.min(1, edge * s.edgePool + gravity * s.gravityPool)), ap = 1 - (1 - a) ** (1 + 4 * s.edgeAlpha * edge);
      for (let c = 0; c < 3; c++) out[(y * N + x) * 4 + c] = Math.round(255 * (tint[c] * a * (1 - s.pooling) + encode(decode(tint[c]) ** concentration) * ap * s.pooling));
      out[(y * N + x) * 4 + 3] = Math.round(255 * (a * (1 - s.pooling) + ap * s.pooling));
    } return out;
  }
  const difference = (a, b) => { let count = 0, max = 0; for (let i = 0; i < a.length; i++) { const d = Math.abs(a[i] - b[i]); if (d) count++; max = Math.max(max, d); } return { mismatches: count, maxByteError: max }; };
  for (const pooling of [0, .37, 1]) {
    const s = { ...config, pooling }, diff = difference(finished(finish, [0,0], N, near, N, s), expected(s));
    check('finish_cpu_pooling_' + pooling, diff.maxByteError <= 1, diff);
  }
  for (const [name, mutant] of [['alpha', mutantAlpha], ['tint', mutantTint]]) {
    const diff = difference(finished(mutant, [0,0], N, near, N), expected(config)); check('mutation_' + name + '_red', diff.maxByteError > 8, diff);
  }
  // Exercise the ACTUAL cached-image fade program, independent of finish's optical fade.
  // Channels are already premultiplied; different alpha/colour and transparent/opaque
  // endpoints expose accidental source-over, straight-colour interpolation or alpha buildup.
  const plainBytes=new Uint8Array(N*N*4),pooledBytes=new Uint8Array(N*N*4);
  for(let i=0;i<N*N;i++){
    const a=i%N===0?0:i%N===1?255:(i*73+19)%256,b=i%N===0?255:i%N===1?0:(i*41+127)%256;
    for(let c=0;c<3;c++){plainBytes[i*4+c]=Math.floor(a*((i*11+c*79+13)%256)/255);pooledBytes[i*4+c]=Math.floor(b*((i*17+c*31+91)%256)/255);}plainBytes[i*4+3]=a;pooledBytes[i*4+3]=b;
  }
  const plainTexture=texture(N,N,plainBytes),pooledTexture=texture(N,N,pooledBytes);
  function fadeResult(p,amount){
    const out=target(N,false);gl.disable(gl.BLEND);gl.useProgram(p);gl.bindVertexArray(vao);
    // Shared finish/dab quad storage must always be restored before finish/mix draws.
    gl.bindBuffer(gl.ARRAY_BUFFER,quad);gl.bufferData(gl.ARRAY_BUFFER,new Float32Array([0,0,1,0,0,1,1,1]),gl.STATIC_DRAW);
    bind(p,'u_plain',0,plainTexture);bind(p,'u_pooled',1,pooledTexture);gl.uniform1f(gl.getUniformLocation(p,'u_amount'),amount);gl.uniform2f(gl.getUniformLocation(p,'u_tileOrigin'),-128,-64);gl.uniform1f(gl.getUniformLocation(p,'u_outputDocSize'),N);gl.drawArrays(gl.TRIANGLE_STRIP,0,4);
    const pixels=new Uint8Array(N*N*4);gl.readPixels(0,0,N,N,gl.RGBA,gl.UNSIGNED_BYTE,pixels);gl.deleteFramebuffer(out.fb);gl.deleteTexture(out.t);return pixels;
  }
  const fadeExpected=amount=>{const t=Math.max(0,Math.min(1,amount));return Uint8Array.from(plainBytes,(v,i)=>Math.round(v*(1-t)+pooledBytes[i]*t));};
  for(const amount of [0,.37,1,-.25,1.25]){const diff=difference(fadeResult(fade,amount),fadeExpected(amount));check('cached_mix_amount_'+amount,diff.maxByteError<=1,diff);}
  for(const [name,p] of [['amount',fadeWrongAmount],['alpha',fadeWrongAlpha]]){const diff=difference(fadeResult(p,.37),fadeExpected(.37));check('mutation_cached_mix_'+name+'_red',diff.maxByteError>8,diff);}
  gl.deleteTexture(plainTexture);gl.deleteTexture(pooledTexture);
  // Exact 3-channel source-over. White scalar shape removes filter/geometry ambiguity.
  const array = gl.createTexture(); gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D_ARRAY, array);
  gl.texImage3D(gl.TEXTURE_2D_ARRAY, 0, gl.R8, 1, 1, 1, 0, gl.RED, gl.UNSIGNED_BYTE, new Uint8Array([255])); gl.texParameteri(gl.TEXTURE_2D_ARRAY, gl.TEXTURE_MIN_FILTER, gl.NEAREST); gl.texParameteri(gl.TEXTURE_2D_ARRAY, gl.TEXTURE_MAG_FILTER, gl.NEAREST);
  const dabVao = gl.createVertexArray(); gl.bindVertexArray(dabVao); gl.bindBuffer(gl.ARRAY_BUFFER, quad); gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1,-1,1,-1,-1,1,1,1]), gl.STATIC_DRAW); gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
  const instances = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, instances);
  for (const [loc, offset] of [[1,0],[2,16]]) { gl.enableVertexAttribArray(loc); gl.vertexAttribPointer(loc,4,gl.FLOAT,false,32,offset); gl.vertexAttribDivisor(loc,1); }
  function deposited(origin, n, list, precision = 'half', scale = 1) {
    const floating=precision==='half',out = target(n, floating); gl.useProgram(dab); gl.bindVertexArray(dabVao); gl.bindBuffer(gl.ARRAY_BUFFER, instances); gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(list.flat()), gl.STATIC_DRAW);
    gl.uniform2f(gl.getUniformLocation(dab,'u_tileOrigin'), ...origin); gl.uniform1f(gl.getUniformLocation(dab,'u_tileSize'), n); gl.uniform1f(gl.getUniformLocation(dab,'u_scale'),scale); gl.uniform1i(gl.getUniformLocation(dab,'u_dabs'),0); gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D_ARRAY,array);
    gl.enable(gl.BLEND); gl.blendEquation(gl.FUNC_ADD); gl.blendFunc(gl.ONE,gl.ONE_MINUS_SRC_COLOR); gl.drawArraysInstanced(gl.TRIANGLE_STRIP,0,4,list.length); gl.disable(gl.BLEND);
    const pixels = floating?new Float32Array(n*n*4):new Uint8Array(n*n*4); gl.readPixels(0,0,n,n,gl.RGBA,floating?gl.FLOAT:gl.UNSIGNED_BYTE,pixels); gl.deleteFramebuffer(out.fb); return { texture:out.t, pixels };
  }
  const deposit = deposited([0,0],N,[[32,32,28,0,0,.5,.25,.125],[32,32,28,0,0,.25,.5,.25]]), sample = Array.from(deposit.pixels.slice((32*N+32)*4,(32*N+32)*4+4)), wanted = [.625,.625,.34375,0];
  check('independent_band_source_over',sample.every((v,i) => Math.abs(v-wanted[i]) < 1e-4), { sample, wanted });
  // Restore finish quad: the two VAOs share this buffer.
  gl.bindBuffer(gl.ARRAY_BUFFER, quad); gl.bufferData(gl.ARRAY_BUFFER,new Float32Array([0,0,1,0,0,1,1,1]),gl.STATIC_DRAW);
  const bytes = b64 => Uint8Array.from(atob(b64), c => c.charCodeAt(0));
  const realPaper=texture(assets.paper.width,assets.paper.height,bytes(assets.paper.b64));gl.bindTexture(gl.TEXTURE_2D,realPaper);gl.generateMipmap(gl.TEXTURE_2D);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MIN_FILTER,gl.LINEAR_MIPMAP_LINEAR);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MAG_FILTER,gl.LINEAR);
  // Exact production Mulberry32/R8 bytes, not the POC's procedural placeholder.
  const grainBytes = new Uint8Array(128 * 128 * 4); let grainSeed = 521932;
  for (let i = 0; i < 128 * 128; i++) {
    grainSeed = (grainSeed + 0x6D2B79F5) | 0;
    let t = Math.imul(grainSeed ^ (grainSeed >>> 15), grainSeed | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    const value = Math.floor(((t ^ (t >>> 14)) >>> 0) / 4294967296 * 255 + .5);
    grainBytes[i * 4] = grainBytes[i * 4 + 1] = grainBytes[i * 4 + 2] = value;
    grainBytes[i * 4 + 3] = 255;
  }
  const fineGrain = texture(128, 128, grainBytes);
  gl.bindTexture(gl.TEXTURE_2D, fineGrain); gl.generateMipmap(gl.TEXTURE_2D);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR_MIPMAP_LINEAR);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
  const realConfig = { ...config, paperTexture: realPaper, paperSpec: assets.paper.spec, fineGrain, grain: .4 };
  const renderKnob = (s, p = finish) => finished(p, [0, 0], N, near, N, s);
  const alphaStats = data => {
    let sum = 0, count = 0, min = 255, max = 0;
    for (let i = 3; i < data.length; i += 4) {
      sum += data[i]; if (data[i]) { count++; min = Math.min(min, data[i]); max = Math.max(max, data[i]); }
    }
    return { sum, count, range: max - min };
  };
  const channelDiff = (a, b, alpha) => {
    let max = 0; for (let i = 0; i < a.length; i++) if ((i % 4 === 3) === alpha) max = Math.max(max, Math.abs(a[i] - b[i]));
    return max;
  };
  for (const key of ['edgePool', 'gravityPool']) {
    const low = renderKnob({ ...realConfig, [key]: 0 }), high = renderKnob({ ...realConfig, [key]: 1 });
    const detail = { maxAlphaChange: channelDiff(low, high, true), maxColorChange: channelDiff(low, high, false) };
    check('knob_' + key + '_color_only_real_paper_grain', detail.maxAlphaChange === 0 && detail.maxColorChange > 2, detail);
  }
  const edgeLow = renderKnob({ ...realConfig, edgeAlpha: 0 }), edgeHigh = renderKnob({ ...realConfig, edgeAlpha: 1 });
  let decreased = 0; for (let i = 3; i < edgeLow.length; i += 4) if (edgeHigh[i] < edgeLow[i]) decreased++;
  check('knob_edgeAlpha_independent_buildup', decreased === 0 && alphaStats(edgeHigh).sum > alphaStats(edgeLow).sum,
    { decreased, low: alphaStats(edgeLow), high: alphaStats(edgeHigh) });
  const grainLow = renderKnob({ ...realConfig, grain: 0 }), grainHigh = renderKnob({ ...realConfig, grain: 1 });
  let increased = 0; for (let i = 3; i < grainLow.length; i += 4) if (grainHigh[i] > grainLow[i]) increased++;
  check('knob_actual_grain_breakup', increased === 0 && alphaStats(grainHigh).sum < alphaStats(grainLow).sum * .9,
    { increased, low: alphaStats(grainLow), high: alphaStats(grainHigh), fixture: 'Mulberry32 seed521932 R8 128x128 / world256px' });
  const paperLow = renderKnob({ ...realConfig, paperCut: 0, grain: 0, pooling: 0 });
  const paperHighConfig = { ...realConfig, paperCut: 1, grain: 0, pooling: 0 };
  const paperHigh = renderKnob(paperHighConfig);
  check('knob_actual_paper_height_breakup', alphaStats(paperHigh).sum < alphaStats(paperLow).sum && alphaStats(paperHigh).range > alphaStats(paperLow).range,
    { low: alphaStats(paperLow), high: alphaStats(paperHigh), paper: 'pulp_artisan canonical height' });
  for (const [key, p, s, reference] of [
    ['grain', mutantGrain, { ...realConfig, grain: 1 }, grainHigh],
    ['paper_cut', mutantPaperCut, paperHighConfig, paperHigh],
  ]) {
    const diff = difference(renderKnob(s, p), reference);
    check('mutation_actual_' + key + '_red', diff.maxByteError > 2, diff);
  }
  gl.deleteTexture(fineGrain);
  function loadDabs(){gl.activeTexture(gl.TEXTURE0);gl.bindTexture(gl.TEXTURE_2D_ARRAY,array);gl.pixelStorei(gl.UNPACK_ALIGNMENT,1);gl.texImage3D(gl.TEXTURE_2D_ARRAY,0,gl.R8,assets.dabs.width,assets.dabs.height,assets.dabs.count,0,gl.RED,gl.UNSIGNED_BYTE,bytes(assets.dabs.b64));gl.generateMipmap(gl.TEXTURE_2D_ARRAY);gl.texParameteri(gl.TEXTURE_2D_ARRAY,gl.TEXTURE_MIN_FILTER,assets.dabFilter==='linear'?gl.LINEAR:gl.LINEAR_MIPMAP_LINEAR);gl.texParameteri(gl.TEXTURE_2D_ARRAY,gl.TEXTURE_MAG_FILTER,gl.LINEAR);gl.texParameteri(gl.TEXTURE_2D_ARRAY,gl.TEXTURE_WRAP_S,gl.CLAMP_TO_EDGE);gl.texParameteri(gl.TEXTURE_2D_ARRAY,gl.TEXTURE_WRAP_T,gl.CLAMP_TO_EDGE);}
  loadDabs();
  for (const origin of [[-64,-64],[-128,-128]]) for(const kind of ['constant','real_paper']) for(const scale of [1,2]) {
    const tilePixels=64*scale,bigPixels=192*scale,seamEmpty=texture(tilePixels,tilePixels,new Float32Array(tilePixels*tilePixels*4),true);
    const seamConfig=kind==='real_paper'?{...config,paperTexture:realPaper,paperSpec:assets.paper.spec}:config;
    // Tiles and monolithic target use the same real dab shader, including diagonal/corner overlap.
    const centre=[origin[0]+64,origin[1]+64], stamps=[[centre[0]-16,centre[1]-16,32,0,0,.5,.25,.125],[centre[0]+16,centre[1]+16,32,0,0,.25,.5,.25]];
    gl.bindBuffer(gl.ARRAY_BUFFER,quad); gl.bufferData(gl.ARRAY_BUFFER,new Float32Array([-1,-1,1,-1,-1,1,1,1]),gl.STATIC_DRAW);
    const big=deposited(origin,bigPixels,stamps,'half',scale), tiles=[]; for(let y=0;y<3;y++)for(let x=0;x<3;x++)tiles.push(deposited([origin[0]+x*64,origin[1]+y*64],tilePixels,stamps,'half',scale).texture);
    gl.bindBuffer(gl.ARRAY_BUFFER,quad); gl.bufferData(gl.ARRAY_BUFFER,new Float32Array([0,0,1,0,0,1,1,1]),gl.STATIC_DRAW);
    const bigEmpty=texture(bigPixels,bigPixels,new Float32Array(bigPixels*bigPixels*4),true), bigNeighbours=Array(9).fill(bigEmpty);bigNeighbours[4]=big.texture;
    const reference=finished(finish,origin,bigPixels,bigNeighbours,bigPixels,seamConfig,scale), result=new Uint8Array(reference.length), bad=new Uint8Array(reference.length);
    for(let y=0;y<3;y++)for(let x=0;x<3;x++) {
      const neighbours=[];for(let dy=-1;dy<=1;dy++)for(let dx=-1;dx<=1;dx++)neighbours.push(x+dx>=0&&x+dx<3&&y+dy>=0&&y+dy<3?tiles[(y+dy)*3+x+dx]:seamEmpty);
      for(const [p,dst] of [[finish,result],[mutantNeighbour,bad]]) { const part=finished(p,[origin[0]+x*64,origin[1]+y*64],tilePixels,neighbours,tilePixels,seamConfig,scale);for(let row=0;row<tilePixels;row++)dst.set(part.subarray(row*tilePixels*4,(row+1)*tilePixels*4),((y*tilePixels+row)*bigPixels+x*tilePixels)*4); }
    }
    const goodDiff=difference(reference,result),badDiff=difference(reference,bad);check('seam_'+kind+'_scale'+scale+'_'+origin.join('_'),goodDiff.mismatches===0,goodDiff);check('mutation_neighbour_red_'+kind+'_scale'+scale+'_'+origin.join('_'),badDiff.mismatches>0,badDiff);
    for(const tex of [...tiles,big.texture,bigEmpty,seamEmpty])gl.deleteTexture(tex);
  }
  if (perceptual) {
    const rgba = img => { const data=bytes(img.b64),out=new Uint8Array(img.width*img.height*4);for(let i=0;i<data.length;i++){out[i*4]=out[i*4+1]=out[i*4+2]=data[i];out[i*4+3]=255;}return out; };
    const wash=texture(assets.backdrop.width,assets.backdrop.height,rgba(assets.backdrop));
    gl.bindTexture(gl.TEXTURE_2D,wash);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MIN_FILTER,gl.LINEAR);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MAG_FILTER,gl.LINEAR);
    loadDabs();
    // CIE76 in D65 Lab; both painted-only and all-frame scores are reported, never masked by white.
    function lab(rgb) { const [r,g,b]=rgb.map(decode),f=t=>t>.008856?Math.cbrt(t):7.787*t+16/116,x=f((.4124564*r+.3575761*g+.1804375*b)/.95047),y=f(.2126729*r+.7151522*g+.072175*b),z=f((.0193339*r+.119192*g+.9503041*b)/1.08883);return [116*y-16,500*(x-y),200*(y-z)]; }
    const s=perceptual.settings;
    tint=perceptual.color.match(/\w\w/g).map(v=>parseInt(v,16)/255);
    const base={edgeWidth:s.edgeWidth,edgePool:s.edgePool,edgeAlpha:s.edgeAlpha,gravityWidth:s.gravityWidth,gravityPool:s.gravityPool,paperCut:s.paperCut,pooling:1,backdrop:wash,backdropWorld:1254*s.textureScale,textureStrength:s.texture,textureContrast:s.textureContrast,textureInvert:perceptual.invert?1:0,grain:0};
    results.perceptual_note='Full-resolution shader pooling vs POC half-resolution/LUT pooling; source R8 wash vs POC downsampled512 mask. Approved includes procedural paper/grain; engine test uses canonical constant-height paper and no placeholder grain. Isolated disables paper cut/grain in both. All deltas are reported without relaxing threshold3.';
    const variants=perceptual.diagnostic?['approved','isolated','full_resolution_reference','unpooled','shape']:['approved','isolated'];
    const filters=assets.dabFilter==='both'?['mipmap','linear']:[assets.dabFilter],precisions=assets.bandPrecision==='both'?['half','byte']:[assets.bandPrecision];
    for(const filter of filters) for(const precision of precisions) for(const variant of variants) for(let index=0;index<perceptual.paths.length;index++) {
      assets.dabFilter=filter;loadDabs();
      const record=perceptual.paths[index],image=new Image();image.src=perceptual.images[variant][index];await image.decode();const reference=document.createElement('canvas');reference.width=1400;reference.height=900;const rg=reference.getContext('2d');rg.drawImage(image,0,0);const ref=rg.getImageData(0,0,1400,900).data;
      gl.bindBuffer(gl.ARRAY_BUFFER,quad);gl.bufferData(gl.ARRAY_BUFFER,new Float32Array([-1,-1,1,-1,-1,1,1,1]),gl.STATIC_DRAW);
      const depositedStroke=deposited([0,0],1400,record.dabs.map(d=>[d[1],d[2],d[3]/2,d[4],d[0],d[9],d[10],d[11]]),precision);
      gl.bindBuffer(gl.ARRAY_BUFFER,quad);gl.bufferData(gl.ARRAY_BUFFER,new Float32Array([0,0,1,0,0,1,1,1]),gl.STATIC_DRAW);
      const blank=texture(1400,1400,new Float32Array(1400*1400*4),true),neighbours=Array(9).fill(blank);neighbours[4]=depositedStroke.texture;
      const bb=record.dabs.reduce((b,d)=>{const r=d[3]*Math.SQRT1_2+4;return [Math.min(b[0],d[1]-r),Math.min(b[1],d[2]-r),Math.max(b[2],d[1]+r),Math.max(b[3],d[2]+r)];},[1400,1400,0,0]),x0=Math.max(0,Math.floor(bb[0])),y0=Math.max(0,Math.floor(bb[1])),x1=Math.min(1400,Math.ceil(bb[2])),y1=Math.min(1400,Math.ceil(bb[3]));
      const cfg={...base,scissor:[x0,y0,x1-x0,y1-y0],paperCut:variant==='approved'?base.paperCut:0,backdropOffset:record.backdropOffset.map(v=>-v*base.backdropWorld),...(['unpooled','shape'].includes(variant)?{edgePool:0,edgeAlpha:0,gravityPool:0}:{}),...(variant==='shape'?{textureStrength:0}:{})},actual=finished(finish,[0,0],1400,neighbours,1400,cfg);
      let all=0,painted=0,count=0,max=0;
      for(let i=0;i<1400*900;i++){const p=i*4,a=ref[p+3]/255,b=actual[p+3]/255;if(a===0&&b===0)continue;const ra=[0,1,2].map(c=>ref[p+c]/255*a+1-a),rb=[0,1,2].map(c=>actual[p+c]/255+1-b),la=lab(ra),lb=lab(rb),delta=Math.hypot(...la.map((v,c)=>v-lb[c]));all+=delta;max=Math.max(max,delta);if(Math.max(a,b)>.02){painted+=delta;count++;}}
      const metric={fullFrameMeanDeltaE:all/(1400*900),paintedMeanDeltaE:painted/Math.max(1,count),paintedPixels:count,maxDeltaE:max};
      const key='poc_'+filter+'_'+precision+'_'+variant+'_seed_'+record.seed;
      check(key,count>0&&metric.paintedMeanDeltaE<3&&metric.fullFrameMeanDeltaE<3,metric);
      if(perceptual.saveImages){const toPng=(data,premultiplied)=>{const c=document.createElement('canvas');c.width=1400;c.height=900;const g=c.getContext('2d'),pixels=g.createImageData(1400,900);for(let i=0;i<1400*900;i++){const a=data[i*4+3]/255;for(let k=0;k<3;k++)pixels.data[i*4+k]=Math.round((premultiplied?data[i*4+k]:data[i*4+k]*a)+255*(1-a));pixels.data[i*4+3]=255;}g.putImageData(pixels,0,0);return c.toDataURL();};results[key].referencePng=toPng(ref,false);results[key].actualPng=toPng(actual,true);}
      gl.deleteTexture(depositedStroke.texture);gl.deleteTexture(blank);
    }
  }
  const error=gl.getError();check('gl_error', error === 0, error);
  return { results, failures, perceptual: perceptual ? 'See approved + isolated painted/full-frame gates' : 'NOT RUN: use --poc <index.html> for screenshot parity; optical/seam gates alone do not prove look parity' };
}

(async () => {
  const assetsDir = path.join(root,'assets/watercolour/poc25'), manifest=JSON.parse(fs.readFileSync(path.join(assetsDir,'manifest.json'),'utf8')), backdrop=png(path.join(assetsDir,'backdrop.png'));
  const paperSpec=JSON.parse(fs.readFileSync(path.join(root,'assets/paper/catalogue.json'),'utf8')).surfaces.find(p=>p.id==='pulp_artisan'),paperImage=png(path.join(root,'assets/paper',paperSpec.file));
  const dabFilter=option('--dab-filter')||'linear';if(!['mipmap','linear','both'].includes(dabFilter))throw Error('--dab-filter must be mipmap, linear or both');
  const bandPrecision=option('--band-precision')||'half';if(!['half','byte','both'].includes(bandPrecision))throw Error('--band-precision must be half, byte or both');
  const assets={dabFilter,bandPrecision,paper:{width:paperImage.width,height:paperImage.height,b64:Buffer.from(paperImage.rgba).toString('base64'),spec:paperSpec},backdrop:{width:backdrop.width,height:backdrop.height,b64:Buffer.from(backdrop.red).toString('base64')},dabs:{width:manifest.width,height:manifest.height,count:manifest.count,b64:Buffer.concat(manifest.dabs.map(file=>Buffer.from(png(path.join(assetsDir,file)).red))).toString('base64')}};
  const browser = await chromium.launch({ executablePath:process.env.CHROME || 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', args:['--use-angle=swiftshader','--enable-unsafe-swiftshader'] });
  try {
    let perceptual=null;
    if(option('--poc')) {
      const referencePage=await browser.newPage(),fixture=JSON.parse(fs.readFileSync(path.join(root,'core/src/jvmTest/resources/watercolour/poc-golden.json'),'utf8')),approved=JSON.parse(fs.readFileSync(path.join(path.dirname(option('--poc')),'approved-watercolor-settings.json'),'utf8'));
      const injection=`__renderReference(paths,approved,diagnostic){for(const [id,control] of Object.entries(approved.controls))if($(id))$(id).value=control.value;$('color').value=approved.color;curves.pressure=approved.pressureBezier;curves.tilt=approved.tiltBezier;Object.assign(dynamics.scatter,approved.scatterInputs);Object.assign(dynamics.spacing,approved.spacingInputs);$('compensate').checked=approved.spacingCompensation;$('invert').checked=approved.backdropInverted;$('quality').value='1';resizeRenderer(false);const original=settings(),images={};for(const variant of diagnostic?['approved','isolated','full_resolution_reference','unpooled','shape']:['approved','isolated']){images[variant]=paths.map(path=>{const s={...original,...(variant!=='approved'?{paperCut:0,grain:0}:{}),...(variant==='full_resolution_reference'?{__checkPoolingScale:1}:{}),...(['unpooled','shape'].includes(variant)?{edgePool:0,edgeAlpha:0,gravityPool:0}:{}),...(variant==='shape'?{texture:0}:{})};begin(path.points[0],s,path.seed);for(const p of path.points.slice(1))append(p);const image=strokeImage(true).toDataURL();active=null;return image;});}return {images,color:original.color,invert:original.invert};},`;
      const referenceSource=fs.readFileSync(option('--poc'),'utf8');
      const hash=crypto.createHash('sha256').update(referenceSource).digest('hex');if(hash!==fixture.sourceSha256)throw Error('POC source changed since W1 golden: regenerate fixture before comparing screenshots');
      // Preserve the original .5 reference for every approved/isolation gate. Only the explicitly
      // named diagnostic changes this in-memory copy; it is not source/production POC parity.
      if(!referenceSource.includes('const scale=.5,w='))throw Error('POC pooling instrumentation anchor changed');
      const html=referenceSource.replace('const scale=.5,w=','const scale=a.s.__checkPoolingScale??.5,w=').replace('window.watercolorPOC={','window.watercolorPOC={'+injection);
      await referencePage.setContent(html,{waitUntil:'load'});await referencePage.waitForFunction(()=>window.watercolorPOC?.ready);
      const diagnostic=process.argv.includes('--diagnostic'),ref=await referencePage.evaluate(({paths,approved,diagnostic})=>watercolorPOC.__renderReference(paths,approved,diagnostic),{paths:fixture.paths,approved,diagnostic});
      perceptual={...ref,paths:fixture.paths,settings:fixture.settings,diagnostic,saveImages:!!option('--out')};await referencePage.close();
    }
    const page=await browser.newPage(); const report=await page.evaluate(gpuChecks,{vert:source('lines/jb_wc_dab.vert'),frag:source('lines/jb_wc_dab.frag'),finishVert:source('lines/jb_wc_finish.vert'),finishFrag:source('lines/jb_wc_finish.frag'),mixFrag:source('lines/jb_wc_mix.frag'),assets,perceptual});
    if(option('--out')){fs.mkdirSync(option('--out'),{recursive:true});for(const [key,result] of Object.entries(report.results))for(const name of ['referencePng','actualPng'])if(result[name]){fs.writeFileSync(path.join(option('--out'),key+'_'+name+'.png'),Buffer.from(result[name].split(',')[1],'base64'));delete result[name];}fs.writeFileSync(path.join(option('--out'),'report.json'),JSON.stringify(report,null,2));}
    console.log(JSON.stringify(report,null,2)); if(report.failures.length) process.exitCode=1;
  } finally { await browser.close(); }
})().catch(error=>{console.error(error);process.exitCode=1;});
