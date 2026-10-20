// D4: actual production stamp/MRT and commit shader contracts, headless WebGL2.
// Node + the existing bundled playwright-core; CHROME can override Edge.
// RGBA8 comparison permits 2/255 for cumulative UNORM rounding. Mutated production
// shaders are required to fail, and MRT vs sequential attachment draws are exact.
const fs = require('fs');
const path = require('path');
const os = require('os');
const shaderDir = path.join(__dirname, '..', 'shaders');
function source(name, stack = []) {
  if (stack.includes(name)) throw Error('Shader include cycle: ' + [...stack, name].join(' -> '));
  return fs.readFileSync(path.join(shaderDir, name), 'utf8').replace(
    /^\s*#include\s+"([^"]+)"[^\r\n]*$/gm, (_, child) => source(child, [...stack, name]));
}
function replaceOnce(text, from, to, label) {
  if (text.split(from).length !== 2) throw Error(label + ': expected exactly one production witness');
  return text.replace(from, to);
}
function playwright() {
  const bundled = path.join(os.homedir(), '.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright-core');
  for (const candidate of ['playwright-core', bundled]) {
    try { return require(candidate); } catch (error) { if (error.code !== 'MODULE_NOT_FOUND') throw error; }
  }
  throw Error('playwright-core unavailable; no package installation attempted');
}
async function main() {
  const shaders = { vert: source('jb_dab.vert'), stamp: source('jb_dab.frag'),
    tileVert: source('jb_tile.vert'), commit: source('jb_commit.frag') };
  shaders.wrongStamp = replaceOnce(shaders.stamp,
    'o_color = vec4(capCoverage, 0.0, 0.0, d);',
    'o_color = vec4(capCoverage, 0.0, 0.0, capCoverage);', 'wrong mask blend alpha');
  shaders.wrongStamp = replaceOnce(shaders.wrongStamp,
    'o_colorMoment = vec4(ink * capCoverage, d);',
    'o_colorMoment = vec4(ink * capCoverage, capCoverage);', 'wrong moment blend alpha');
  // Only the new branch is mutated; the identical legacy alpha expression remains intact.
  const branchStart = shaders.commit.indexOf('if (u_coloredStroke == 1)');
  const branchEnd = shaders.commit.indexOf('if(u_rgbaStroke == 1)', branchStart);
  if (branchStart < 0 || branchEnd < branchStart) throw Error('Colored commit branch witness missing');
  shaders.wrongCommit = shaders.commit.slice(0, branchStart) + replaceOnce(
    shaders.commit.slice(branchStart, branchEnd), 'texture(u_stroke, v_uv).r',
    'texture(u_colorMoments, v_uv).a', 'wrong commit alpha') + shaders.commit.slice(branchEnd);
  const { chromium } = playwright();
  const browser = await chromium.launch({
    executablePath: process.env.CHROME || 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
    headless: true, args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'],
  });
  try {
    const page = await browser.newPage();
    const result = await page.evaluate(({ shaders }) => {
      const side = 4, tolerance = 2 / 255;
      const canvas = document.createElement('canvas'); canvas.width = canvas.height = side;
      const gl = canvas.getContext('webgl2', { antialias: false });
      if (!gl) throw Error('WebGL2 unavailable');
      function program(vertex, fragment, label) {
        const p = gl.createProgram();
        for (const [type, text] of [[gl.VERTEX_SHADER, vertex], [gl.FRAGMENT_SHADER, fragment]]) {
          const s = gl.createShader(type); gl.shaderSource(s, text); gl.compileShader(s);
          if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw Error(label + ' compile: ' + gl.getShaderInfoLog(s));
          gl.attachShader(p, s);
        }
        gl.linkProgram(p);
        if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw Error(label + ' link: ' + gl.getProgramInfoLog(p));
        return p;
      }
      const stamp = program(shaders.vert, shaders.stamp, 'production stamp');
      const wrongStamp = program(shaders.vert, shaders.wrongStamp, 'wrong blend alpha control');
      const commit = program(shaders.tileVert, shaders.commit, 'production commit');
      const wrongCommit = program(shaders.tileVert, shaders.wrongCommit, 'wrong commit alpha control');
      const loc = (p, name) => gl.getUniformLocation(p, name);
      const requireUniform = (p, name) => {
        const value = loc(p, name); if (value === null) throw Error('Production uniform missing: ' + name); return value;
      };
      requireUniform(stamp, 'u_stampBaseColor'); requireUniform(commit, 'u_coloredStroke');
      requireUniform(commit, 'u_colorMoments');
      function texture(bytes, width = side, height = side) {
        const t = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, t);
        gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, width, height, 0, gl.RGBA, gl.UNSIGNED_BYTE, bytes);
        for (const [name, value] of [[gl.TEXTURE_MIN_FILTER, gl.NEAREST], [gl.TEXTURE_MAG_FILTER, gl.NEAREST],
          [gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE], [gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE]]) gl.texParameteri(gl.TEXTURE_2D, name, value);
        return t;
      }
      gl.activeTexture(gl.TEXTURE0);
      const white = texture(new Uint8Array([255, 255, 255, 255]), 1, 1);
      const fbo = gl.createFramebuffer(); gl.bindFramebuffer(gl.FRAMEBUFFER, fbo);
      const mask = texture(null), moments = texture(null);
      const both = [gl.COLOR_ATTACHMENT0, gl.COLOR_ATTACHMENT1];
      gl.framebufferTexture2D(gl.FRAMEBUFFER, both[0], gl.TEXTURE_2D, mask, 0);
      gl.framebufferTexture2D(gl.FRAMEBUFFER, both[1], gl.TEXTURE_2D, moments, 0);
      gl.drawBuffers(both);
      if (gl.checkFramebufferStatus(gl.FRAMEBUFFER) !== gl.FRAMEBUFFER_COMPLETE) throw Error('MRT framebuffer incomplete');
      const stampVao = gl.createVertexArray(); gl.bindVertexArray(stampVao);
      const corners = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, corners);
      gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW);
      gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 8, 0);
      const instances = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, instances);
      for (const [attribute, count, offset] of [[1, 4, 0], [2, 2, 4], [4, 2, 6], [5, 4, 8], [6, 4, 12], [7, 1, 16]]) {
        gl.enableVertexAttribArray(attribute); gl.vertexAttribPointer(attribute, count, gl.FLOAT, false, 68, offset * 4);
        gl.vertexAttribDivisor(attribute, 1);
      }
      // Attribute 12 is a generic constant in these tests; production's 17-float prefix stays intact.
      for (const attribute of [3, 8, 9, 10, 11, 12]) {
        gl.disableVertexAttribArray(attribute); gl.vertexAttrib4f(attribute, 0, 0, 0, 0);
      }
      const tileVao = gl.createVertexArray(); gl.bindVertexArray(tileVao);
      const tileCorners = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, tileCorners);
      gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([0, 0, 1, 0, 0, 1, 1, 1]), gl.STATIC_DRAW);
      gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 8, 0);
      const read = attachment => {
        gl.readBuffer(attachment); const bytes = new Uint8Array(side * side * 4);
        gl.readPixels(0, 0, side, side, gl.RGBA, gl.UNSIGNED_BYTE, bytes); return [...bytes];
      };
      const difference = (a, b) => a.flat().filter((value, i) => value !== b.flat()[i]).length;
      function checkError(label) { const error = gl.getError(); if (error !== gl.NO_ERROR) throw Error(label + ' GL error ' + error); }
      gl.viewport(0, 0, side, side); gl.disable(gl.DITHER); gl.disable(gl.SCISSOR_TEST);
      const dabs = [[1, 0, 0, .2, .8], [0, 0, 1, .7, .4], [0, 1, 0, .35, .65]];
      const base = [.2, .4, .6];
      function renderStamp(p, { sequential = false, flat = false, fallback = false, full = false } = {}) {
        gl.bindFramebuffer(gl.FRAMEBUFFER, fbo); gl.drawBuffers(both);
        gl.clearColor(0, 0, 0, 0); gl.clear(gl.COLOR_BUFFER_BIT);
        gl.useProgram(p); gl.bindVertexArray(stampVao);
        gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D, white);
        gl.uniform1f(loc(p, 'u_tileSize'), side); gl.uniform2f(loc(p, 'u_tileOrigin'), 0, 0);
        gl.uniform1f(loc(p, 'u_scale'), 1); gl.uniform1f(loc(p, 'u_corner'), 2);
        gl.uniform1f(loc(p, 'u_minPx'), 1); gl.uniform3fv(loc(p, 'u_stampBaseColor'), base);
        gl.enable(gl.BLEND); gl.blendEquation(gl.FUNC_ADD); gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA);
        const groups = sequential ? [[gl.COLOR_ATTACHMENT0, gl.NONE], [gl.NONE, gl.COLOR_ATTACHMENT1]] : [both];
        for (const group of groups) {
          gl.drawBuffers(group);
          for (const [r, g, b, cap, flow] of full ? [[1, 1, 1, 1, 1]] : dabs) {
            const prefix = new Float32Array([2, 2, 64, 0, flow, cap, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 1]);
            gl.bindBuffer(gl.ARRAY_BUFFER, instances); gl.bufferData(gl.ARRAY_BUFFER, prefix, gl.STREAM_DRAW);
            gl.vertexAttrib4f(12, flat ? 1 : r, flat ? 0 : g, flat ? 0 : b, fallback ? 0 : 1);
            gl.drawArraysInstanced(gl.TRIANGLE_STRIP, 0, 4, 1);
          }
        }
        const output = [read(gl.COLOR_ATTACHMENT0), read(gl.COLOR_ATTACHMENT1)]; checkError('stamp'); return output;
      }
      const full = renderStamp(stamp, { full: true });
      const actual = renderStamp(stamp), sequential = renderStamp(stamp, { sequential: true });
      const badBlend = renderStamp(wrongStamp), flat = renderStamp(stamp, { flat: true });
      const fallback = renderStamp(stamp, { fallback: true });
      let expectedMask = 0, expectedRgb = [0, 0, 0];
      for (const [r, g, b, cap, coverage] of dabs) {
        expectedMask = cap * coverage + expectedMask * (1 - coverage);
        expectedRgb = expectedRgb.map((value, i) => [r, g, b][i] * cap * coverage + value * (1 - coverage));
      }
      let maxAccumulationError = 0, maxFallbackError = 0, flatDifferentBytes = 0;
      for (let at = 0; at < side * side * 4; at += 4) {
        maxAccumulationError = Math.max(maxAccumulationError, Math.abs(actual[0][at] / 255 - expectedMask));
        for (let c = 0; c < 3; c++) {
          maxAccumulationError = Math.max(maxAccumulationError, Math.abs(actual[1][at + c] / 255 - expectedRgb[c]));
          maxFallbackError = Math.max(maxFallbackError, Math.abs(fallback[1][at + c] / 255 - base[c] * expectedMask));
        }
        if (flat[0][at] !== flat[1][at]) flatDifferentBytes++;
      }
      // Exercise the production transport too: 17n legacy prefix, 2n fade tail,
      // 12n quad tail and 4n colour tail. Disabled shape metadata keeps this fixture
      // identical to the generic-attribute oracle above while checking real offsets.
      function renderColorTail(individual, wrongOffset = false) {
        gl.bindFramebuffer(gl.FRAMEBUFFER, fbo); gl.drawBuffers(both);
        gl.clearColor(0, 0, 0, 0); gl.clear(gl.COLOR_BUFFER_BIT);
        gl.useProgram(stamp); gl.bindVertexArray(stampVao);
        gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D, white);
        gl.enable(gl.BLEND); gl.blendEquation(gl.FUNC_ADD); gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA);
        const groups = individual ? dabs.map(dab => [dab]) : [dabs];
        for (const group of groups) {
          const n = group.length, fadeStart = n * 17, quadStart = n * 19, colorStart = n * 31;
          const data = new Float32Array(n * 35);
          group.forEach(([r, g, b, cap, flow], i) => {
            data.set([2, 2, 64, 0, flow, cap, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 1], i * 17);
            data.set([0, 0], fadeStart + i * 2);
            data.set([0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0], quadStart + i * 12);
            data.set([r, g, b, 1], colorStart + i * 4);
          });
          gl.bindBuffer(gl.ARRAY_BUFFER, instances); gl.bufferData(gl.ARRAY_BUFFER, data, gl.STREAM_DRAW);
          for (const [attribute, count, stride, offset] of [
            [8, 2, 8, fadeStart * 4], [9, 4, 48, quadStart * 4],
            [10, 4, 48, quadStart * 4 + 16], [11, 4, 48, quadStart * 4 + 32],
            [12, 4, 16, colorStart * 4 - (wrongOffset ? 4 : 0)],
          ]) {
            gl.enableVertexAttribArray(attribute); gl.vertexAttribPointer(attribute, count, gl.FLOAT, false, stride, offset);
            gl.vertexAttribDivisor(attribute, 1);
          }
          gl.drawArraysInstanced(gl.TRIANGLE_STRIP, 0, 4, n);
        }
        const output = [read(gl.COLOR_ATTACHMENT0), read(gl.COLOR_ATTACHMENT1)];
        for (const attribute of [8, 9, 10, 11, 12]) {
          gl.disableVertexAttribArray(attribute); gl.vertexAttribDivisor(attribute, 0); gl.vertexAttrib4f(attribute, 0, 0, 0, 0);
        }
        checkError('colour instance tail'); return output;
      }
      const transported = renderColorTail(false), transportedIndividual = renderColorTail(true);
      const wrongColorOffset = renderColorTail(false, true);
      const transport = {
        batchedVersusIndividualBytes: difference(transported, transportedIndividual),
        genericOracleDifferentBytes: difference(transported, actual),
        wrongOffsetDifferentBytes: difference(transported, wrongColorOffset),
        colorStrideBytes: 16, colorOffset: 'n * (68 + 8 + 48)',
        maskDifferentBytes: difference([transported[0]], [transportedIndividual[0]]),
        momentDifferentBytes: difference([transported[1]], [transportedIndividual[1]]),
      };
      // Restore the correct actual production accumulation as the commit's inputs.
      renderStamp(stamp);
      const commitFbo = gl.createFramebuffer(); gl.bindFramebuffer(gl.FRAMEBUFFER, commitFbo);
      const target = texture(null); gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, target, 0);
      gl.drawBuffers([gl.COLOR_ATTACHMENT0]);
      if (gl.checkFramebufferStatus(gl.FRAMEBUFFER) !== gl.FRAMEBUFFER_COMPLETE) throw Error('Commit framebuffer incomplete');
      function renderCommit(p, backdrop, k, blend, layerOpacity, colored = 1) {
        gl.bindFramebuffer(gl.FRAMEBUFFER, commitFbo); gl.drawBuffers([gl.COLOR_ATTACHMENT0]);
        gl.disable(gl.BLEND); gl.useProgram(p); gl.bindVertexArray(tileVao);
        gl.activeTexture(gl.TEXTURE0); const background = texture(new Uint8Array(backdrop), 1, 1);
        gl.uniform1i(loc(p, 'u_layer'), 0);
        gl.activeTexture(gl.TEXTURE1); gl.bindTexture(gl.TEXTURE_2D, mask); gl.uniform1i(loc(p, 'u_stroke'), 1);
        gl.activeTexture(gl.TEXTURE2); gl.bindTexture(gl.TEXTURE_2D, moments); gl.uniform1i(loc(p, 'u_colorMoments'), 2);
        gl.uniform1i(loc(p, 'u_coloredStroke'), colored); gl.uniform1i(loc(p, 'u_erase'), blend === 'erase' ? 1 : 0);
        gl.uniform1i(loc(p, 'u_blend'), blend === 'multiply' ? 1 : blend === 'screen' ? 2 : 0);
        gl.uniform3fv(loc(p, 'u_color'), base); gl.uniform1f(loc(p, 'u_strokeScale'), k);
        gl.uniform1f(loc(p, 'u_layerOpacity'), layerOpacity); gl.uniform1f(loc(p, 'u_tileSize'), side);
        gl.uniform2f(loc(p, 'u_tileOrigin'), 0, 0);
        gl.uniformMatrix3fv(loc(p, 'u_docToClip'), false, new Float32Array([2 / side, 0, 0, 0, 2 / side, 0, -1, -1, 1]));
        gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
        const bytes = read(gl.COLOR_ATTACHMENT0); gl.deleteTexture(background); checkError('commit'); return bytes;
      }
      let commitCases = 0, maxCommitError = 0, wrongMomentAlphaDetected = 0, wrongSelectorDetected = 0;
      const commitFailures = [];
      for (const backdrop of [[0, 0, 0, 0], [51, 102, 153, 255]]) for (const k of [1, .4]) {
        for (const blend of ['normal', 'multiply', 'screen', 'erase']) for (const layerOpacity of [1, .65]) {
          const output = renderCommit(commit, backdrop, k, blend, layerOpacity);
          const wrongA = renderCommit(wrongCommit, backdrop, k, blend, layerOpacity);
          const wrongSelector = renderCommit(commit, backdrop, k, blend, layerOpacity, 0);
          let error = 0;
          for (let at = 0; at < output.length; at += 4) {
            const alpha = actual[0][at] / 255 * k;
            const src = actual[1].slice(at, at + 3).map(value => value / 255 * k);
            const dst = backdrop.map(value => value / 255);
            const expected = src.map((value, c) => blend === 'erase' ? dst[c] * (1 - alpha) :
              blend === 'multiply' ? value * dst[c] + value * (1 - dst[3]) + dst[c] * (1 - alpha) :
              blend === 'screen' ? value + dst[c] * (1 - value) : value + dst[c] * (1 - alpha));
            expected.push(blend === 'erase' ? dst[3] * (1 - alpha) : alpha + dst[3] * (1 - alpha));
            for (let c = 0; c < 4; c++) error = Math.max(error, Math.abs(output[at + c] / 255 - expected[c] * layerOpacity));
          }
          commitCases++; maxCommitError = Math.max(maxCommitError, error);
          if (error > tolerance) commitFailures.push({ backdrop, k, blend, layerOpacity, error });
          if (difference(output, wrongA) > 0) wrongMomentAlphaDetected++;
          if (difference(output, wrongSelector) > 0) wrongSelectorDetected++;
        }
      }
      const fullCoverage = full[0].every((value, i) => value === (i % 4 === 0 || i % 4 === 3 ? 255 : 0));
      const splitDifferentBytes = difference(actual, sequential), wrongBlendDifferentBytes = difference(actual, badBlend);
      const pass = fullCoverage && maxAccumulationError <= tolerance && maxFallbackError <= tolerance &&
        splitDifferentBytes === 0 && flatDifferentBytes === 0 && wrongBlendDifferentBytes > 0 &&
        transport.batchedVersusIndividualBytes === 0 && transport.genericOracleDifferentBytes === 0 &&
        transport.wrongOffsetDifferentBytes > 0 && commitFailures.length === 0 &&
        wrongMomentAlphaDetected >= 16 && wrongSelectorDetected >= 16;
      return { pass, productionShaders: ['jb_dab.vert + jb_dab.frag', 'jb_tile.vert + jb_commit.frag'],
        format: 'RGBA8 MRT; ONE/ONE_MINUS_SRC_ALPHA', prefixFloats: 17, colorAttribute: 12, fullCoverage,
        maxAccumulationError, maxFallbackError, splitDifferentBytes, flatDifferentBytes, transport, commitCases, maxCommitError,
        controls: { wrongBlendDifferentBytes, wrongMomentAlphaDetected, wrongSelectorDetected,
          guardedProductionMutations: 3 }, commitFailures, drawBuffers: gl.getParameter(gl.MAX_DRAW_BUFFERS), glError: gl.getError() };
    }, { shaders });
    console.log(JSON.stringify(result, null, 2));
    if (!result.pass) process.exitCode = 1;
  } finally { await browser.close(); }
}
main().catch(error => { console.error(error.stack); process.exitCode = 1; });
