// Actual production sampled-tip rendering against the JVM TipMath fixture, not a JS oracle.
// Run after :core:jvmTest generates core/build/unibrush-sample-fixture.json.
// Only even/even fragment centres are compared: their forward derivative lanes match TipMath.
// RGBA8 tolerance is strictly <2/255; controls must exceed twice that error in a relevant fixture.
// No package installation. CHROME overrides Edge; NODE_PATH and the bundled runtime are fallbacks.
const fs = require('fs');
const path = require('path');
const os = require('os');
const root = path.join(__dirname, '..');
const shaderDir = path.join(root, 'shaders');
const fixturePath = path.join(root, 'core', 'build', 'unibrush-sample-fixture.json');
const tolerance = 2 / 255;
const controlThreshold = 2 * tolerance;
const sources = new Map();

function source(name, stack = []) {
  if (stack.includes(name)) throw new Error('Shader include cycle: ' + [...stack, name].join(' -> '));
  if (sources.has(name)) return sources.get(name);
  const file = path.resolve(shaderDir, name);
  if (!file.startsWith(path.resolve(shaderDir) + path.sep)) throw new Error('Shader include escapes shader directory');
  const expanded = fs.readFileSync(file, 'utf8').replace(/^\s*#include\s+"([^"]+)"[^\r\n]*$/gm,
    (_, child) => source(child, [...stack, name]));
  sources.set(name, expanded);
  return expanded;
}

function fixture() {
  const data = JSON.parse(fs.readFileSync(fixturePath, 'utf8'));
  for (const key of ['atlasWidth', 'atlasHeight']) {
    if (!Number.isInteger(data[key]) || data[key] < 1 || data[key] > 4096) throw new Error('Invalid ' + key);
  }
  if (!Array.isArray(data.atlasBytes) || data.atlasBytes.length !== data.atlasWidth * data.atlasHeight ||
      data.atlasBytes.some(v => !Number.isInteger(v) || v < 0 || v > 255)) throw new Error('Invalid R8 atlas');
  if (!Array.isArray(data.rects) || data.rects.length < 2 || data.rects.length > 16 || data.rects.some(r =>
      !Array.isArray(r) || r.length !== 4 || r.some(v => !Number.isFinite(v) || v < 0 || v > 1) ||
      r[0] >= r[2] || r[1] >= r[3])) throw new Error('Invalid image rectangles');
  if (!Array.isArray(data.cases) || !data.cases.length) throw new Error('Missing CPU coverage cases');
  for (const c of data.cases) {
    if (typeof c.name !== 'string' || !c.name) throw new Error('Fixture case needs a name');
    for (const k of ['width', 'height', 'imageWidth', 'imageHeight'])
      if (!Number.isInteger(c[k]) || c[k] < 1 || c[k] > 4096) throw new Error(c.name + ': invalid ' + k);
    for (const k of ['radius', 'angle', 'aspect', 'taper', 'anchor', 'hardness', 'minPx', 'fade', 'fadeAngle'])
      if (!Number.isFinite(c[k])) throw new Error(c.name + ': nonfinite/missing ' + k);
    if (!(c.radius > 0 && c.minPx > 0) || !Number.isInteger(c.index) || c.index < 0 || c.index >= data.rects.length ||
        !Number.isInteger(c.profile) || c.profile < 0 || c.profile > 5) throw new Error(c.name + ': invalid image/profile/radius');
    for (const [key, count] of [['lut', 256], ['quad', 8], ['quadMeta', 4], ['coverage', c.width * c.height]])
      if (!Array.isArray(c[key]) || c[key].length !== count || c[key].some(v => !Number.isFinite(v)))
        throw new Error(c.name + ': invalid ' + key);
    if (c.coverage.some(v => v < 0 || v > 1) || c.quadMeta[1] <= 0) throw new Error(c.name + ': invalid coverage/normalization');
  }
  if (!data.cases.some(c => c.imageWidth === 1 && c.imageHeight === 1 && c.radius === 15 && c.width === 64 && c.height === 64))
    throw new Error('Fixture must include the native 1x1 radius15/full64 vertex-clipping regression');
  return data;
}

function playwright() {
  const bundled = path.join(os.homedir(), '.cache', 'codex-runtimes', 'codex-primary-runtime', 'dependencies', 'node', 'node_modules');
  const candidates = ['playwright-core', ...(process.env.NODE_PATH || '').split(path.delimiter).filter(Boolean)
    .map(p => path.join(p, 'playwright-core')), path.join(bundled, 'playwright-core')];
  const tried = [];
  for (const candidate of [...new Set(candidates)]) {
    try { const resolved = require.resolve(candidate); return { chromium: require(resolved).chromium, resolved }; }
    catch (e) { tried.push(candidate + ': ' + e.message.split('\n')[0]); }
  }
  const error = new Error('playwright-core unavailable; no packages were installed');
  error.dependencyFallbacks = tried;
  throw error;
}

function replaceExactlyOnce(text, pattern, replacement, label) {
  const matches = text.match(pattern);
  if (!matches || matches.length !== 1) throw new Error(label + ': expected exactly one production mutation site');
  return text.replace(pattern, replacement);
}

async function main() {
  const data = fixture();
  const vert = source('jb_dab.vert');
  const stamp = source('jb_dab.frag');
  const shaders = {
    vert, stamp, smudge: source('jb_smudge_dab.frag'),
    legacyExtent: replaceExactlyOnce(vert,
      /    if \(a_sample\.x > 0\.5 && a_sample\.y > 0\.0 && a_sample\.z > 0\.0\) \{[\s\S]*?\n    \}/g,
      '    // Broken control: keep the historical procedural quad extent.', 'legacy extent'),
    noGuard: replaceExactlyOnce(stamp,
      /    if \(any\(lessThanEqual\(imageUv, -support\)\) \|\| any\(greaterThanEqual\(imageUv, 1\.0 \+ support\)\)\) return 0\.0;/g,
      '    // Broken control: atlas reads escape their selected image cell.', 'sample support guard'),
  };
  const runtime = playwright();
  const executablePath = process.env.CHROME || 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';
  const browser = await runtime.chromium.launch({ executablePath, headless: true,
    args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'] });
  try {
    const page = await browser.newPage();
    const result = await page.evaluate(({ data, shaders, tolerance, controlThreshold }) => {
      const canvas = document.createElement('canvas'); canvas.width = canvas.height = 64;
      const gl = canvas.getContext('webgl2', { antialias: false });
      if (!gl) throw new Error('WebGL2 context unavailable');
      function program(vs, fs, label) {
        const pair = [[gl.VERTEX_SHADER, vs], [gl.FRAGMENT_SHADER, fs]].map(([type, text]) => {
          const s = gl.createShader(type); gl.shaderSource(s, text); gl.compileShader(s);
          if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(label + ': ' + gl.getShaderInfoLog(s));
          return s;
        });
        const p = gl.createProgram(); pair.forEach(s => gl.attachShader(p, s)); gl.linkProgram(p);
        if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error(label + ': ' + gl.getProgramInfoLog(p));
        pair.forEach(s => { gl.detachShader(p, s); gl.deleteShader(s); });
        return p;
      }
      const production = program(shaders.vert, shaders.stamp, 'actual production stamp');
      gl.deleteProgram(program(shaders.vert, shaders.smudge, 'actual production smudge'));
      const legacyExtent = program(shaders.legacyExtent, shaders.stamp, 'broken legacy extent');
      const noGuard = program(shaders.vert, shaders.noGuard, 'broken sample-cell guard');
      const uniformCache = new Map();
      function loc(p, name) {
        if (!uniformCache.has(p)) uniformCache.set(p, new Map());
        const cache = uniformCache.get(p);
        if (!cache.has(name)) cache.set(name, gl.getUniformLocation(p, name));
        return cache.get(name);
      }
      function texture(unit, internal, width, height, format, bytes, filter) {
        const t = gl.createTexture(); gl.activeTexture(gl.TEXTURE0 + unit); gl.bindTexture(gl.TEXTURE_2D, t);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, filter);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, filter);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
        gl.texImage2D(gl.TEXTURE_2D, 0, internal, width, height, 0, format, gl.UNSIGNED_BYTE, bytes);
        return t;
      }
      gl.pixelStorei(gl.UNPACK_ALIGNMENT, 1);
      const placeholder = texture(0, gl.RGBA8, 1, 1, gl.RGBA, new Uint8Array([255,255,255,255]), gl.NEAREST);
      for (const unit of [1, 2]) { gl.activeTexture(gl.TEXTURE0 + unit); gl.bindTexture(gl.TEXTURE_2D, placeholder); }
      const atlas = texture(3, gl.R8, data.atlasWidth, data.atlasHeight, gl.RED, new Uint8Array(data.atlasBytes), gl.LINEAR);
      const coverage = texture(4, gl.RGBA8, 64, 64, gl.RGBA, null, gl.NEAREST);
      const moments = texture(5, gl.RGBA8, 64, 64, gl.RGBA, null, gl.NEAREST);
      const fbo = gl.createFramebuffer(); gl.bindFramebuffer(gl.FRAMEBUFFER, fbo);
      gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, coverage, 0);
      gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT1, gl.TEXTURE_2D, moments, 0);
      gl.drawBuffers([gl.COLOR_ATTACHMENT0, gl.COLOR_ATTACHMENT1]);
      if (gl.checkFramebufferStatus(gl.FRAMEBUFFER) !== gl.FRAMEBUFFER_COMPLETE) throw new Error('Incomplete sampled MRT framebuffer');
      gl.disable(gl.DITHER); gl.disable(gl.SCISSOR_TEST);
      const vao = gl.createVertexArray(); gl.bindVertexArray(vao);
      const quad = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, quad);
      gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1,-1,1,-1,-1,1,1,1]), gl.STATIC_DRAW);
      gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
      const vbo = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, vbo);
      for (const [a, count, offset] of [[1,4,0],[2,2,16],[4,2,24],[5,4,32],[6,4,48],[7,1,64]]) {
        gl.enableVertexAttribArray(a); gl.vertexAttribPointer(a, count, gl.FLOAT, false, 68, offset); gl.vertexAttribDivisor(a, 1);
      }
      const rects = new Float32Array(64);
      data.rects.forEach((r, i) => rects.set(r, i * 4));
      function uniforms(p, c, mutation) {
        gl.useProgram(p);
        gl.uniform2f(loc(p, 'u_tileOrigin'), 0, 0);
        for (const [name, value] of [['u_tileSize',c.width],['u_scale',1],['u_aspect',c.aspect],['u_corner',2],
          ['u_taper',c.taper],['u_hardness',c.hardness],['u_minPx',c.minPx],['u_tipGrainPitchPx',0],
          ['u_paperGrainPitchPx',0],['u_paperInfluence',0]]) gl.uniform1f(loc(p, name), value);
        for (const [name, unit] of [['u_tipGrain',0],['u_paperSurface',1],['u_paperFluid',2],['u_sampleMasks',3]])
          gl.uniform1i(loc(p, name), unit);
        gl.uniform1i(loc(p, 'u_sampleEnabled'), mutation === 'imageOff' ? 0 : 1);
        gl.uniform1i(loc(p, 'u_tipProfile'), mutation === 'profileOff' ? 0 : c.profile);
        gl.uniform1i(loc(p, 'u_tipProfileB'), 0);
        gl.uniform4fv(loc(p, 'u_tipProfileLut[0]'), new Float32Array(c.lut));
        gl.uniform4fv(loc(p, 'u_tipProfileLutB[0]'), new Float32Array(c.lut));
        gl.uniform3f(loc(p, 'u_stampBaseColor'), .3, .6, .8);
        const r = rects.slice();
        if (mutation === 'wrongRect') data.rects.forEach((_, i) => r.set(data.rects[(i + 1) % data.rects.length], i * 4));
        gl.uniform4fv(loc(p, 'u_sampleRects[0]'), r);
      }
      function dab(c, mutation = null) {
        const native = mutation === 'nativeAspectIgnored' ? Math.max(c.imageWidth, c.imageHeight) : null;
        const meta = c.quadMeta.slice(); if (mutation === 'quadOff') meta[3] = 0;
        return {
          prefix: [c.width/2,c.height/2,c.radius,c.angle,1,1,0,0,c.aspect,c.hardness,0,0,c.anchor,0,0,0,1],
          fade: [mutation === 'fadeOff' ? 0 : c.fade,c.fadeAngle], quad: [...c.quad,...meta], color: [0,0,0,0],
          sample: [(mutation === 'wrongIndex' ? (c.index + 1) % data.rects.length : c.index) + 1,
            native || c.imageWidth,native || c.imageHeight,c.minPx],
        };
      }
      function upload(group, wrong = null) {
        gl.bindVertexArray(vao); gl.bindBuffer(gl.ARRAY_BUFFER, vbo);
        gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([...group.flatMap(d => d.prefix), ...group.flatMap(d => d.fade),
          ...group.flatMap(d => d.quad), ...group.flatMap(d => d.color), ...group.flatMap(d => d.sample)]), gl.STREAM_DRAW);
        const n = group.length;
        for (const [a, count, stride, offset, fault] of [[8,2,8,68*n,'fade'],[9,4,48,76*n,'quad'],
          [10,4,48,76*n+16,'quad'],[11,4,48,76*n+32,'quad'],[12,4,16,124*n,'rgb'],[13,4,16,140*n,'sample']]) {
          gl.enableVertexAttribArray(a);
          gl.vertexAttribPointer(a, count, gl.FLOAT, false, stride, offset - (wrong === fault ? (fault === 'fade' || fault === 'quad' ? 8 : 16) : 0));
          gl.vertexAttribDivisor(a, 1);
        }
      }
      function read(attachment = 0) {
        gl.readBuffer(gl.COLOR_ATTACHMENT0 + attachment);
        const bytes = new Uint8Array(64 * 64 * 4); gl.readPixels(0, 0, 64, 64, gl.RGBA, gl.UNSIGNED_BYTE, bytes);
        const error = gl.getError(); if (error !== gl.NO_ERROR) throw new Error('Sampled draw GL error ' + error);
        return bytes;
      }
      function render(c, mutation = null, p = production) {
        if (c.width !== 64 || c.height !== 64) throw new Error('Production fixture target must be 64x64');
        uniforms(p, c, mutation); gl.viewport(0, 0, 64, 64); gl.disable(gl.BLEND);
        gl.clearColor(0,0,0,0); gl.clear(gl.COLOR_BUFFER_BIT);
        upload([dab(c, mutation)]); gl.drawArraysInstanced(gl.TRIANGLE_STRIP, 0, 4, 1);
        const bytes = read();
        let max = 0, squares = 0, probes = 0, failures = 0, controlPixels = 0, worst = null;
        for (let y = 0; y < 64; y += 2) for (let x = 0; x < 64; x += 2) {
          const i = y * 64 + x, error = Math.abs(bytes[i*4]/255 - c.coverage[i]);
          if (error > max) { max = error; worst = {x,y,gpu:bytes[i*4]/255,cpu:c.coverage[i]}; }
          squares += error*error; probes++;
          if (error >= tolerance) failures++;
          if (error > controlThreshold) controlPixels++;
        }
        return { max, rms:Math.sqrt(squares/probes), probes, failures, controlPixels, worst };
      }
      const controls = Object.fromEntries(['wrongIndex','wrongRect','imageOff','nativeAspectIgnored','legacyExtent',
        'fadeOff','profileOff','quadOff','noOutOfCellGuard'].map(n => [n,{ relevantCases:0,detectedCases:0,max:0,witnesses:[] }]));
      function control(name,c,result) {
        const out = controls[name]; out.relevantCases++; out.max = Math.max(out.max,result.max);
        if (result.controlPixels) { out.detectedCases++; out.witnesses.push({name:c.name,pixels:result.controlPixels,max:result.max}); }
      }
      const cases = [];
      let max = 0, squares = 0, probes = 0, failedCases = 0;
      for (const c of data.cases) {
        const measured = render(c); cases.push({name:c.name,...measured});
        max = Math.max(max,measured.max); squares += measured.rms*measured.rms*measured.probes; probes += measured.probes;
        if (measured.failures) failedCases++;
        for (const name of ['wrongIndex','wrongRect','imageOff','nativeAspectIgnored','fadeOff','profileOff','quadOff']) {
          if (name === 'nativeAspectIgnored' && c.imageWidth === c.imageHeight || name === 'fadeOff' && c.fade === 0 ||
              name === 'profileOff' && c.profile === 0 || name === 'quadOff' && c.quadMeta[3] === 0) continue;
          control(name,c,render(c,name));
        }
        control('legacyExtent',c,render(c,null,legacyExtent));
      }
      // Canary atlas: leave every source image and its one-pixel transparent border unchanged,
      // brighten only unrelated atlas texels. Correct cell guards still match the ACTUAL CPU fixture.
      const canary = Uint8Array.from(data.atlasBytes);
      for (let y = 0; y < data.atlasHeight; y++) for (let x = 0; x < data.atlasWidth; x++) {
        const protectedPixel = data.rects.some(r => x >= Math.round(r[0]*data.atlasWidth)-1 &&
          x <= Math.round(r[2]*data.atlasWidth) && y >= Math.round(r[1]*data.atlasHeight)-1 && y <= Math.round(r[3]*data.atlasHeight));
        if (!protectedPixel) canary[y*data.atlasWidth+x] = 255;
      }
      function uploadAtlas(bytes) {
        gl.activeTexture(gl.TEXTURE3); gl.bindTexture(gl.TEXTURE_2D,atlas);
        gl.texSubImage2D(gl.TEXTURE_2D,0,0,0,data.atlasWidth,data.atlasHeight,gl.RED,gl.UNSIGNED_BYTE,bytes);
      }
      uploadAtlas(canary);
      let canaryFailures = 0;
      for (const c of data.cases) {
        if (render(c).failures) canaryFailures++;
        control('noOutOfCellGuard',c,render(c,null,noGuard));
      }
      uploadAtlas(new Uint8Array(data.atlasBytes));

      function transport() {
        const c = data.cases.find(c => c.profile === 4) || data.cases[0];
        const globals = {...c,profile:4,aspect:0,taper:0};
        const dabs = [[24,26,18,.2,.2,-.4,.8,.2,.3],[34,31,15,-.4,.8,.9,.2,.8,.4],[29,38,20,.7,.5,-1.1,.4,.3,.9]].map((v,i) => {
          const image = data.cases.find(c => c.index === [0,2,4][i]);
          const d = dab(image);
          d.prefix = [v[0],v[1],v[2],v[3],.6,[.45,.8,.6][i],0,0,[-.1,.3,-.5][i],0,0,0,[0,.4,.2][i],0,0,0,1];
          d.fade = [v[4],v[5]]; d.quad = [-1,-1,1,-1,1,1,-1,1,.2+i*.3,.8,0,4];
          d.color = [v[6],v[7],v[8],1];
          return d;
        });
        function groups(groups, wrong = null) {
          uniforms(production,globals,null); gl.viewport(0,0,64,64);
          gl.clearColor(0,0,0,0); gl.clear(gl.COLOR_BUFFER_BIT);
          gl.enable(gl.BLEND); gl.blendFuncSeparate(gl.ONE,gl.ONE_MINUS_SRC_ALPHA,gl.ONE,gl.ONE_MINUS_SRC_ALPHA);
          for (const group of groups) { upload(group,wrong); gl.drawArraysInstanced(gl.TRIANGLE_STRIP,0,4,group.length); }
          return [read(0),read(1)];
        }
        const a = groups([dabs]), b = groups(dabs.map(d => [d]));
        const diff = (a,b) => a.reduce((sum,pixels,k) => sum + pixels.reduce((n,v,i) => n+(v!==b[k][i]?1:0),0),0);
        const differingBytes = diff(a,b);
        const wrongOffsets = Object.fromEntries(['sample','fade','quad','rgb'].map(name => [name,diff(a,groups([dabs],name))]));
        const coveredPixels = a[0].reduce((n,v,i) => n+(i%4===0 && v>0?1:0),0);
        return {pass:differingBytes===0 && coveredPixels>0 && Object.values(wrongOffsets).every(n=>n>0),
          dabs:dabs.length,differingBytes,wrongOffsetDifferingBytes:wrongOffsets,coveredPixels,order:'identical',
          layout:'17n prefix + 2n fade + 12n quad + 4n RGB + 4n sample; attr13 stride16 offset140n bytes; MRT coverage and RGB moments'};
      }
      const transported = transport();
      const tinyNames = new Set(data.cases.filter(c => c.imageWidth===1 && c.imageHeight===1).map(c=>c.name));
      const tinyExtentDetected = controls.legacyExtent.witnesses.some(c=>tinyNames.has(c.name));
      const pass = failedCases===0 && canaryFailures===0 && tinyExtentDetected && transported.pass &&
        Object.values(controls).every(c=>c.detectedCases>0);
      return {pass,compiled:['actual production stamp','actual production smudge','guarded broken extent/cell-control programs'],
        tolerance,controlThreshold,probes:'even/even pixel centres; forward CPU derivatives',
        parity:{cases:cases.length,failedCases,probes,max,rms:Math.sqrt(squares/probes)},cases,controls,
        canary:{guardedFailedCases:canaryFailures,preserves:'source pixels plus their complete transparent border'},
        tinyExtentDetected,transport:transported};
    }, {data,shaders,tolerance,controlThreshold});
    console.log(JSON.stringify({...result,fixture:fixturePath,playwright:runtime.resolved},null,2));
    if (!result.pass) process.exitCode = 1;
  } finally { await browser.close(); }
}
main().catch(error => {
  console.error(JSON.stringify({pass:false,error:error.message,
    ...(error.dependencyFallbacks ? {dependencyFallbacks:error.dependencyFallbacks} : {})},null,2));
  process.exitCode = 1;
});
