#!/usr/bin/env node
// Ordered recorded-line/slab composition with the actual production shader, WebGL2 + SwiftShader.
// NODE_PATH=<bundled node_modules> <bundled node.exe> joybrush/tools/recorded_compose_check.js
// CHROME=<browser executable> overrides Edge. Optional --out <report.json> saves numeric evidence.
// No Android engine or device claim: this proves shader arithmetic, target precision and clipping.
const fs = require('fs'), path = require('path'), crypto = require('crypto');
const { chromium } = require('playwright-core');
const root = path.join(__dirname, '..');
const source = name => fs.readFileSync(path.join(root, 'shaders', name), 'utf8')
  .replace(/^#include "([^"]+)"/gm, (_, include) => source(include));
const option = name => { const at = process.argv.indexOf(name); return at < 0 ? null : process.argv[at + 1]; };

(async () => {
  const vertex = source('jb_tile.vert'), fragment = source('jb_recorded_compose.frag');
  const browser = await chromium.launch({
    executablePath: process.env.CHROME || 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
    args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader'],
  });
  try {
    const page = await browser.newPage();
    const report = await page.evaluate(({ vertex, fragment }) => {
      const N = 32, channels = N * N * 4, results = {}, failures = [];
      const gl = document.createElement('canvas').getContext('webgl2', { antialias: false });
      if (!gl) throw Error('UNSUPPORTED PRECISION: WebGL2 is unavailable');
      if (!gl.getExtension('EXT_color_buffer_float')) throw Error('UNSUPPORTED PRECISION: EXT_color_buffer_float is required; RGBA8 fallback is forbidden');
      const precision = gl.getShaderPrecisionFormat(gl.FRAGMENT_SHADER, gl.HIGH_FLOAT);
      if (!precision || precision.precision < 23) throw Error('UNSUPPORTED PRECISION: fragment highp needs at least 23 mantissa bits');
      const check = (name, good, detail) => { results[name] = { pass: good, ...detail }; if (!good) failures.push(name); };
      const compile = (type, text) => {
        const shader = gl.createShader(type); gl.shaderSource(shader, text); gl.compileShader(shader);
        if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) throw Error(gl.getShaderInfoLog(shader));
        return shader;
      };
      function program(text) {
        const p = gl.createProgram(), vs = compile(gl.VERTEX_SHADER, vertex), fs = compile(gl.FRAGMENT_SHADER, text);
        gl.attachShader(p, vs); gl.attachShader(p, fs); gl.linkProgram(p); gl.deleteShader(vs); gl.deleteShader(fs);
        if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw Error(gl.getProgramInfoLog(p));
        return p;
      }
      const actual = program(fragment);
      const behindBranch = 'if (u_operator == 2) { o_color = d + s * (1.0 - d.a); return; }';
      if (!fragment.includes(behindBranch)) throw Error('Behind mutant did not match the production shader');
      const noBehind = program(fragment.replace(behindBranch, 'if (u_operator == 2) { o_color = d; return; }'));
      const vao = gl.createVertexArray(), vbo = gl.createBuffer(); gl.bindVertexArray(vao);
      gl.bindBuffer(gl.ARRAY_BUFFER, vbo); gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([0, 0, 1, 0, 0, 1, 1, 1]), gl.STATIC_DRAW);
      gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
      const fbo = gl.createFramebuffer();
      let drawCount = 0, feedbackChecks = 0, sequenceCount = 0;
      function texture(bytes, floating) {
        const t = gl.createTexture(); gl.activeTexture(gl.TEXTURE2); gl.bindTexture(gl.TEXTURE_2D, t);
        gl.texImage2D(gl.TEXTURE_2D, 0, floating ? gl.RGBA32F : gl.RGBA8, N, N, 0, gl.RGBA,
          floating ? gl.FLOAT : gl.UNSIGNED_BYTE, bytes);
        for (const [key, value] of [[gl.TEXTURE_MIN_FILTER, gl.NEAREST], [gl.TEXTURE_MAG_FILTER, gl.NEAREST],
          [gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE], [gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE]]) gl.texParameteri(gl.TEXTURE_2D, key, value);
        return t;
      }
      const transparent = texture(new Uint8Array(channels), false);
      function attach(target) {
        gl.bindFramebuffer(gl.FRAMEBUFFER, fbo);
        gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, target, 0);
        if (gl.checkFramebufferStatus(gl.FRAMEBUFFER) !== gl.FRAMEBUFFER_COMPLETE)
          throw Error('UNSUPPORTED PRECISION: requested composition target is not framebuffer-complete');
      }
      function draw(p, destination, item, target, operator, origin, clip) {
        if (destination === target || item === target) throw Error('Texture feedback in composition');
        feedbackChecks++;
        attach(target); gl.useProgram(p); gl.viewport(0, 0, N, N); gl.disable(gl.BLEND);
        gl.bindVertexArray(vao);
        const loc = name => gl.getUniformLocation(p, name);
        gl.uniform1i(loc('u_destination'), 0); gl.uniform1i(loc('u_source'), 1); gl.uniform1i(loc('u_operator'), operator);
        gl.uniform1f(loc('u_tileSize'), N); gl.uniform2f(loc('u_tileOrigin'), ...origin);
        gl.uniformMatrix3fv(loc('u_docToClip'), false,
          new Float32Array([2 / N, 0, 0, 0, 2 / N, 0, -origin[0] * 2 / N - 1, -origin[1] * 2 / N - 1, 1]));
        for (const [unit, tex] of [[0, destination], [1, item]]) {
          gl.activeTexture(gl.TEXTURE0 + unit); gl.bindTexture(gl.TEXTURE_2D, tex);
        }
        if (clip) { gl.enable(gl.SCISSOR_TEST); gl.scissor(...clip); } else gl.disable(gl.SCISSOR_TEST);
        gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4); drawCount++;
        gl.disable(gl.SCISSOR_TEST);
      }
      function render(items, { p = actual, perItem8 = false, reverse = false, origin = [0, 0] } = {}) {
        const owned = [], make = (bytes, floating) => { const t = texture(bytes, floating); owned.push(t); return t; };
        try {
          const a = make(perItem8 ? new Uint8Array(channels) : new Float32Array(channels), !perItem8);
          const b = make(null, !perItem8), look = make(null, false);
          let destination = a, target = b;
          const ordered = items.slice().sort((a, b) => a.seq - b.seq);
          if (reverse) ordered.reverse();
          for (const item of ordered) {
            const src = make(item.bytes, false);
            if (item.clip) draw(p, destination, transparent, target, 0, origin); // Preserve the entire prior tile.
            draw(p, destination, src, target, item.op, origin, item.clip);
            [destination, target] = [target, destination];
          }
          let floats = null;
          attach(destination);
          if (!perItem8) { floats = new Float32Array(channels); gl.readPixels(0, 0, N, N, gl.RGBA, gl.FLOAT, floats); }
          draw(actual, destination, transparent, look, 0, origin); // The only RGBA8 look quantization.
          const bytes = new Uint8Array(channels); gl.readPixels(0, 0, N, N, gl.RGBA, gl.UNSIGNED_BYTE, bytes);
          const error = gl.getError(); if (error !== gl.NO_ERROR) throw Error('GL error during sequence: ' + error);
          sequenceCount++; return { floats, bytes };
        } finally {
          gl.bindFramebuffer(gl.FRAMEBUFFER, null); gl.activeTexture(gl.TEXTURE0);
          owned.forEach(t => gl.deleteTexture(t));
        }
      }
      const f = Math.fround, add = (a, b) => f(a + b), mul = (a, b) => f(a * b), sub = (a, b) => f(a - b);
      function reference(items) {
        const out = new Float32Array(channels);
        for (const item of items.slice().sort((a, b) => a.seq - b.seq)) for (let y = 0; y < N; y++) for (let x = 0; x < N; x++) {
          if (item.clip && (x < item.clip[0] || y < item.clip[1] || x >= item.clip[0] + item.clip[2] || y >= item.clip[1] + item.clip[3])) continue;
          const at = (y * N + x) * 4, s = Array.from(item.bytes.slice(at, at + 4), v => f(v / 255)), d = Array.from(out.slice(at, at + 4));
          for (let c = 0; c < 4; c++) {
            if (item.op === 1) out[at + c] = mul(d[c], sub(1, s[3]));
            else if (item.op === 2) out[at + c] = add(d[c], mul(s[c], sub(1, d[3])));
            else if (c === 3 || item.op === 0) out[at + c] = add(s[c], mul(d[c], sub(1, s[3])));
            else if (item.op === 3) out[at + c] = add(add(mul(s[c], d[c]), mul(s[c], sub(1, d[3]))), mul(d[c], sub(1, s[3])));
            else if (item.op === 4) out[at + c] = add(s[c], mul(d[c], sub(1, s[c])));
            else throw Error('Unknown reference operator');
          }
        }
        return { floats: out, bytes: Uint8Array.from(out, v => Math.max(0, Math.min(255, Math.floor(add(mul(v, 255), .5))))) };
      }
      function difference(a, b) {
        let mismatches = 0, maxError = 0;
        for (let i = 0; i < a.length; i++) {
          const error = Math.abs(a[i] - b[i]); if (!Number.isFinite(error)) throw Error('Non-finite result');
          if (error !== 0) mismatches++; maxError = Math.max(maxError, error);
        }
        return { mismatches, maxError, channels: a.length };
      }
      let seed = 20261013;
      const random = () => { seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0; return seed / 4294967296; };
      function pixels(low = false) {
        const out = new Uint8Array(channels);
        for (let i = 0; i < N * N; i++) {
          const alpha = i % 16 === 0 ? 0 : i % 16 === 1 ? 255 : low ? 1 + Math.floor(random() * 7) : 1 + Math.floor(random() * 254);
          out[i * 4 + 3] = alpha;
          for (let c = 0; c < 3; c++) out[i * 4 + c] = Math.floor(random() * (alpha + 1));
        }
        return out;
      }
      const slab = { seq: 2, kind: 'slab', op: 0, bytes: pixels() };
      for (const [op, name] of ['normal', 'erase', 'behind', 'multiply', 'screen'].entries()) {
        const items = [slab, { seq: 7, kind: 'line', op, bytes: pixels() }];
        const got = render(items), want = reference(items), float = difference(got.floats, want.floats), bytes = difference(got.bytes, want.bytes);
        check('operator_' + name, float.maxError <= 2e-6 && bytes.mismatches === 0, { float, bytes });
      }
      // Deliberately unsorted source units, all five operators, and multiple low-alpha interleaves.
      const interleaved = [slab, ...[0, 3, 2, 4, 1, 0, 2, 3, 4].map((op, i) => ({ seq: 11 + i * 3, kind: i % 2 ? 'slab' : 'line', op, bytes: pixels(true) }))];
      const scrambled = interleaved.slice().reverse(), want = reference(interleaved);
      const good = render(scrambled), float = difference(good.floats, want.floats), bytes = difference(good.bytes, want.bytes);
      check('ordered_seq_interleaves', float.maxError <= 2e-6 && bytes.mismatches === 0, { units: interleaved.length, float, bytes });
      for (const origin of [[-256, -512], [-1048576, 1048320]]) {
        const moved = render(scrambled, { origin }), diff = difference(moved.bytes, good.bytes);
        check('coordinate_' + origin.join('_'), diff.mismatches === 0, diff);
      }
      const baked = scrambled.map(item => ({ ...item, kind: 'slab', bytes: item.bytes.slice() }));
      const bakedOutput = render(baked), bakeFloat = difference(bakedOutput.floats, good.floats), bakeBytes = difference(bakedOutput.bytes, good.bytes);
      check('bake_same_source_op_seq', bakeFloat.mismatches === 0 && bakeBytes.mismatches === 0, { units: baked.length, float: bakeFloat, bytes: bakeBytes });
      const wrongOrder = difference(render(scrambled, { reverse: true }).bytes, good.bytes);
      check('broken_wrong_order_detected', wrongOrder.mismatches > 0, wrongOrder);
      const droppedBehind = difference(render(scrambled, { p: noBehind }).bytes, good.bytes);
      check('broken_dropped_behind_detected', droppedBehind.mismatches > 0, droppedBehind);

      // One-byte deposition rounded after every item becomes 32, while one final rounding becomes 30.
      const tiny = new Uint8Array(channels); for (let at = 0; at < channels; at += 4) tiny.set([1, 0, 0, 1], at);
      const adversarial = Array.from({ length: 32 }, (_, i) => ({ seq: 1 + i, kind: i % 2 ? 'slab' : 'line', op: 0, bytes: tiny }));
      const once = render(adversarial), expected = reference(adversarial), quantized = render(adversarial, { perItem8: true });
      const correct = difference(once.bytes, expected.bytes), broken = difference(quantized.bytes, once.bytes);
      check('low_alpha_quantize_once', correct.mismatches === 0, { units: adversarial.length, ...correct, alpha: once.bytes[3] });
      check('broken_per_item_rgba8_detected', broken.mismatches > 0, { ...broken, correctAlpha: once.bytes[3], brokenAlpha: quantized.bytes[3] });

      const clip = [5, 7, 19, 13], clipped = [slab, { seq: 9, kind: 'line', op: 3, bytes: pixels(), clip }];
      const clippedOutput = render(clipped, { origin: [-256, -256] }), clippedWant = reference(clipped), clippedDiff = difference(clippedOutput.bytes, clippedWant.bytes);
      let outside = 0, outsideMismatches = 0, insideChanges = 0;
      for (let y = 0; y < N; y++) for (let x = 0; x < N; x++) {
        const inside = x >= clip[0] && y >= clip[1] && x < clip[0] + clip[2] && y < clip[1] + clip[3];
        for (let c = 0; c < 4; c++) {
          const at = (y * N + x) * 4 + c;
          if (!inside) { outside++; if (clippedOutput.bytes[at] !== slab.bytes[at]) outsideMismatches++; }
          else if (clippedOutput.bytes[at] !== slab.bytes[at]) insideChanges++;
        }
      }
      check('scissor_preserves_slab_outside_line', clippedDiff.mismatches === 0 && outsideMismatches === 0 && insideChanges > 0,
        { clip, bytes: clippedDiff, outsideChannels: outside, outsideMismatches, insideChangedChannels: insideChanges });
      const glError = gl.getError(); check('gl_error', glError === gl.NO_ERROR, { value: glError });
      gl.deleteTexture(transparent); gl.deleteFramebuffer(fbo); gl.deleteBuffer(vbo); gl.deleteVertexArray(vao);
      gl.deleteProgram(actual); gl.deleteProgram(noBehind);
      return { precision: { target: 'RGBA32F', source: 'RGBA8', look: 'RGBA8', mantissaBits: precision.precision,
        exponentRange: [precision.rangeMin, precision.rangeMax] }, sequenceCount, drawCount, feedbackChecks,
        positiveGates: Object.keys(results).filter(name => !name.startsWith('broken_')).length,
        brokenControls: Object.keys(results).filter(name => name.startsWith('broken_')).length, results, failures, glError };
    }, { vertex, fragment });
    report.sourceSha256 = crypto.createHash('sha256').update(fragment).digest('hex');
    report.vertexSha256 = crypto.createHash('sha256').update(vertex).digest('hex');
    if (option('--out')) fs.writeFileSync(option('--out'), JSON.stringify(report, null, 2));
    console.log(JSON.stringify(report));
    if (report.failures.length || report.glError !== 0) process.exitCode = 1;
  } finally { await browser.close(); }
})().catch(error => { console.error(JSON.stringify({ status: 'failed', error: String(error) })); process.exitCode = 1; });
