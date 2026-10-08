// Tile paper GPU parity: the ACTUAL jb_paper.glsl + jb_tile_paper.glsl (real #include
// expansion) against the CPU fixture from TilePaperGpuFixtureTest
// (`core/build/tile-paper-gpu-fixture.json`).
//
// Run: cd joybrush/tools && node tile_paper_gpu_check.js   (CHROME=/path/to/chrome to
// override; playwright-core must resolve — the coordinator provides it, no install here.)
// Pass = "pass":true: every active probe within tolerance, every seam pair tight, the
// disabled wrapper bit-identical to the direct canonical read in the same shader, the
// canonical read within tolerance of the CPU canonical, and both mutations discriminated.
//
// Derivative contract: the test fragment shader maps document pixels AFFINELY,
//   docPx = u_probe + (gl_FragCoord.xy - 0.5) * u_step, with u_step = (texelPx, texelPx).
// Hence dFdx(docPx) is exactly the step everywhere, the shader differentiates RAW texel
// coordinates before wrapping (frozen jb_tile_paper.glsl lines 10-11), and
// |dFdx(docPx / texelPx)| = 1, so the shader mip level log2(derivativeScale) equals the
// CPU log2(footprint) with footprint = derivativeScale numerically. Fragment (0,0) of a
// 4x4 viewport sits exactly on the probe, so neighbours exist and derivatives are defined.
// The texture is uploaded RGBA8 16x16 with generated mips, LINEAR_MIPMAP_LINEAR / LINEAR /
// REPEAT — the production filtering path. NEAREST is never selected: it would hide mip bugs.
//
// Slope units: CPU slopes are per texel, GPU slopes per document px (jb_paper.glsl
// divides by u_paperTexelPx), so the script divides CPU slopes by texelPx before comparing.
//
// Tolerance contract (shared verbatim with TilePaperGpuFixtureTest): one quantum is 1 LSB
// — 1/255 for height/moment, slopeRange/127 for slopes. Fine probes (footprint 1, no mips)
// allow 2 quanta (UNORM decode plus one bilinear in fp32); coarse probes (footprint 8,
// trilinear over generated mips) allow 4 quanta (plus one mip byte of box-filter rounding
// between the CPU area average and gl.generateMipmap). Seam/translation pairs reuse the
// same arithmetic on mathematically wrapped inputs, so they must agree to 1e-4 — much
// tighter than parity, with room for float32 mod reassociation through smooth paper.
//
// Expectation contract: active GPU == CPU fixture (parity); canonical GPU == CPU canonical
// (same tolerance); seam pairs equal (tight); disabled wrapper === direct canonical read
// bit-for-bit in the same program (same function, same inputs, same op order).
//
// Perturbation contracts (mutations must be DETECTED where the fixture supports them — a
// supported probe that fails to discriminate fails the run, and zero discriminated total
// fails the run; skips are counted, never silent):
//   A. "wrap disabled": the rect periods are forced to 0. Where the CPU active and CPU
//      canonical answers differ (supportsDisabled), the GPU disabled answer must differ
//      from the GPU active answer by more than the case's parity tolerance.
//   B. "wrapped gradients fed to the read": modes 4/5 call the REAL jb_paperReadGrad at the
//      wrapped position qw, once with correct raw gradients and once with gradients
//      differentiated AFTER wrapping (the bug). At seam-straddling probes the mod
//      discontinuity spikes the wrapped derivative, selecting a far coarser mip. Where the
//      CPU x16-footprint answer moves (supportsMip), bad must differ from good by more
//      than the coarse tolerance. This proves the fixture discriminates the bug class at
//      the exact primitive the tile path uses (the four-read blend would only amplify it).
const fs = require('fs');
const path = require('path');
let chromium;
try {
  chromium = require('playwright-core').chromium;
} catch (e) {
  console.log(JSON.stringify({ error: 'missing dependency playwright-core: ' + e.message }));
  process.exit(1);
}
const root = path.join(__dirname, '..');
const dir = path.join(root, 'shaders');
function src(name, seen = new Set()) {
  return fs.readFileSync(path.join(dir, name), 'utf8').split('\n').map(l => {
    const m = l.trim().match(/^#include\s+"([^"]+)"/);
    return m ? src(m[1], seen) : l;
  }).join('\n');
}
const fixturePath = path.join(root, 'core', 'build', 'tile-paper-gpu-fixture.json');
if (!fs.existsSync(fixturePath)) {
  console.log(JSON.stringify({ error: 'missing fixture: run :core:jvmTest TilePaperGpuFixtureTest first', fixture: fixturePath }));
  process.exit(1);
}
const F = JSON.parse(fs.readFileSync(fixturePath, 'utf8'));
if (F.format !== 'tile-paper-gpu-fixture/1' || !Array.isArray(F.cases) || F.cases.length === 0 ||
    !Array.isArray(F.textureRgba) || F.textureRgba.length !== F.textureSize * F.textureSize * 4) {
  console.log(JSON.stringify({ error: 'fixture shape', fixture: fixturePath }));
  process.exit(1);
}
function findExe() {
  if (process.env.CHROME && fs.existsSync(process.env.CHROME)) return process.env.CHROME;
  const edge = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';
  if (fs.existsSync(edge)) return edge;
  try {
    const ds = fs.readdirSync('/opt/pw-browsers').filter(d => d.startsWith('chromium-'));
    if (ds.length) return path.join('/opt/pw-browsers', ds[0], 'chrome-linux/chrome');
  } catch (e) { /* no fallback browser dir */ }
  return null;
}

const tolSlope = (sr, coarse) => (coarse ? 4 : 2) * sr / 127;
const tolHeight = coarse => (coarse ? 4 : 2) / 255;

(async () => {
  const exe = findExe();
  if (!exe) {
    console.log(JSON.stringify({ error: 'no browser: set CHROME' }));
    process.exit(1);
  }
  const browser = await chromium.launch({
    executablePath: exe,
    args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'],
  });
  try {
    const page = await browser.newPage();
    const out = await page.evaluate(async ({ paperSrc, tileSrc, F }) => {
      const c = document.createElement('canvas');
      const gl = c.getContext('webgl2');
      if (!gl) return { error: 'no webgl2' };
      if (!gl.getExtension('EXT_color_buffer_float')) return { error: 'no EXT_color_buffer_float' };
      const compile = (t, s) => {
        const sh = gl.createShader(t); gl.shaderSource(sh, s); gl.compileShader(sh);
        if (!gl.getShaderParameter(sh, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(sh));
        return sh;
      };
      const vs = '#version 300 es\nlayout(location=0) in vec2 a; void main(){gl_Position=vec4(a,0,1);}';
      // Modes: 0 active tile, 1 disabled tile (rect zw=0 via uniform, same call),
      // 2 direct canonical, 4 wrapped-gradient bug emulation, 5 its correct-gradient twin.
      const frag = '#version 300 es\n' + paperSrc + '\n' + tileSrc +
        '\nuniform vec2 u_probe; uniform vec2 u_step; uniform int u_seed; uniform int u_mode;' +
        ' uniform float u_derivScale; out vec4 o;\n' +
        'void main(){\n' +
        ' vec2 docPx = u_probe + (gl_FragCoord.xy - vec2(0.5)) * u_step;\n' +
        ' float ds = u_derivScale;\n' +
        ' vec4 r;\n' +
        ' if (u_mode == 2) { r = jb_paperRead(docPx, u_seed, true, ds); }\n' +
        ' else if (u_mode == 4 || u_mode == 5) {\n' +
        '  vec2 period = u_tilePaperRect.zw;\n' +
        '  vec2 qw = u_tilePaperRect.xy + mod(docPx - u_tilePaperRect.xy, period);\n' +
        '  vec2 raw = docPx / u_paperTexelPx;\n' +
        '  vec4 good = jb_paperReadGrad(qw, u_seed, true, dFdx(raw) * ds, dFdy(raw) * ds);\n' +
        '  vec4 bad = jb_paperReadGrad(qw, u_seed, true,' +
        ' dFdx(qw / u_paperTexelPx) * ds, dFdy(qw / u_paperTexelPx) * ds);\n' +
        '  r = (u_mode == 4) ? bad : good;\n' +
        ' } else { r = jb_tilePaperRead(docPx, u_seed, true, ds); }\n' +
        ' o = r;\n}';
      let prog;
      try {
        prog = gl.createProgram();
        gl.attachShader(prog, compile(gl.VERTEX_SHADER, vs));
        gl.attachShader(prog, compile(gl.FRAGMENT_SHADER, frag));
        gl.linkProgram(prog);
        if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(prog));
      } catch (e) { return { error: 'compile: ' + e.message }; }
      const u = n => gl.getUniformLocation(prog, n);
      // Fixture texture: RGBA8, real mips, production filtering. Never NEAREST.
      const tex = gl.createTexture();
      gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D, tex);
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, F.textureSize, F.textureSize, 0,
        gl.RGBA, gl.UNSIGNED_BYTE, new Uint8Array(F.textureRgba));
      gl.generateMipmap(gl.TEXTURE_2D);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR_MIPMAP_LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.REPEAT);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.REPEAT);
      // RGBA32F probe target: exact readback, no 8-bit rounding in the question.
      const target = gl.createTexture();
      gl.activeTexture(gl.TEXTURE1); gl.bindTexture(gl.TEXTURE_2D, target);
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA32F, 4, 4, 0, gl.RGBA, gl.FLOAT, null);
      const fbo = gl.createFramebuffer(); gl.bindFramebuffer(gl.FRAMEBUFFER, fbo);
      gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, target, 0);
      if (gl.checkFramebufferStatus(gl.FRAMEBUFFER) !== gl.FRAMEBUFFER_COMPLETE) {
        return { error: 'float target incomplete' };
      }
      gl.viewport(0, 0, 4, 4); gl.disable(gl.BLEND);
      const tri = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, tri);
      gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 3, -1, -1, 3]), gl.STATIC_DRAW);
      const vao = gl.createVertexArray(); gl.bindVertexArray(vao);
      gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
      gl.useProgram(prog);
      gl.uniform1i(u('u_paperSurface'), 0);
      gl.uniform1f(u('u_paperSize'), F.textureSize);
      const reads = [];
      F.cases.forEach((cs, ci) => {
        gl.uniform1f(u('u_paperTexelPx'), cs.texelPx);
        gl.uniform1f(u('u_paperHexTexels'), cs.hexTexels);
        gl.uniform1f(u('u_paperSlopeRange'), cs.slopeRange);
        gl.uniform1i(u('u_paperRotatable'), cs.rotatable ? 1 : 0);
        gl.uniform1f(u('u_paperHeightMean'), cs.heightMean);
        gl.uniform1i(u('u_seed'), cs.seed);
        gl.uniform2f(u('u_step'), cs.texelPx, cs.texelPx);
        gl.uniform1f(u('u_derivScale'), cs.footprint);
        cs.probes.forEach((p, pi) => {
          // Modes 4/5 only at the two straddle probes; every probe gets 0/1/2.
          const modes = (p.kind === 'straddle-x' || p.kind === 'straddle-y') ? [0, 1, 2, 4, 5] : [0, 1, 2];
          for (const mode of modes) {
            gl.uniform2f(u('u_probe'), p.doc[0], p.doc[1]);
            gl.uniform1i(u('u_mode'), mode);
            if (mode === 1) gl.uniform4f(u('u_tilePaperRect'), cs.docRect[0], cs.docRect[1], 0, 0);
            else gl.uniform4f(u('u_tilePaperRect'), cs.docRect[0], cs.docRect[1], cs.docRect[2], cs.docRect[3]);
            gl.drawArrays(gl.TRIANGLES, 0, 3);
            const px = new Float32Array(4);
            gl.readPixels(0, 0, 1, 1, gl.RGBA, gl.FLOAT, px);
            reads.push({ ci, pi, mode, v: Array.from(px) });
          }
        });
      });
      return { reads, glError: gl.getError() };
    }, { paperSrc: src('jb_paper.glsl'), tileSrc: src('jb_tile_paper.glsl'), F }).catch(e => ({ error: String(e) }));
    if (out.error) {
      console.log(JSON.stringify(out));
      process.exitCode = 1;
      return;
    }
    const byMode = (ci, pi, mode) => out.reads.find(r => r.ci === ci && r.pi === pi && r.mode === mode).v;
    const res = {
      fixture: fixturePath, cases: F.cases.length, probes: 0,
      activeChecked: 0, activeMaxFine: 0, activeMaxCoarse: 0,
      canonicalChecked: 0, canonicalMax: 0,
      seamPairs: 0, seamMax: 0,
      disabledMismatches: 0,
      mutationDisabled: { supported: 0, skipped: 0, discriminated: 0, failures: [] },
      mutationMip: { supported: 0, skipped: 0, discriminated: 0, failures: [] },
      failures: [], glError: out.glError,
    };
    F.cases.forEach((cs, ci) => {
      const coarse = cs.footprint > 1.0;
      const ts = tolSlope(cs.slopeRange, coarse), th = tolHeight(coarse);
      const cts = tolSlope(cs.slopeRange, true), cth = tolHeight(true);
      res.probes += cs.probes.length;
      cs.probes.forEach((p, pi) => {
        const want = [p.expected[0] / cs.texelPx, p.expected[1] / cs.texelPx, p.expected[2], p.expected[3]];
        const got = byMode(ci, pi, 0);
        for (let ch = 0; ch < 4; ch++) {
          res.activeChecked++;
          const tol = ch < 2 ? ts : th;
          const err = Math.abs(got[ch] - want[ch]);
          if (coarse) res.activeMaxCoarse = Math.max(res.activeMaxCoarse, err);
          else res.activeMaxFine = Math.max(res.activeMaxFine, err);
          if (!(err <= tol)) res.failures.push({ case: ci, probe: pi, kind: p.kind, channel: ch, check: 'active', got: got[ch], want: want[ch], tol });
        }
        const cwant = [p.canonical[0] / cs.texelPx, p.canonical[1] / cs.texelPx, p.canonical[2], p.canonical[3]];
        const cgot = byMode(ci, pi, 2);
        for (let ch = 0; ch < 4; ch++) {
          res.canonicalChecked++;
          const tol = ch < 2 ? ts : th;
          const err = Math.abs(cgot[ch] - cwant[ch]);
          res.canonicalMax = Math.max(res.canonicalMax, err);
          if (!(err <= tol)) res.failures.push({ case: ci, probe: pi, kind: p.kind, channel: ch, check: 'canonical', got: cgot[ch], want: cwant[ch], tol });
        }
        // Disabled wrapper must be BIT-IDENTICAL to the direct canonical read (same shader).
        const dis = byMode(ci, pi, 1);
        for (let ch = 0; ch < 4; ch++) {
          if (!Object.is(dis[ch], cgot[ch])) {
            res.disabledMismatches++;
            res.failures.push({ case: ci, probe: pi, kind: p.kind, channel: ch, check: 'disabled-bit-identical', got: dis[ch], want: cgot[ch] });
          }
        }
        if (p.supportsDisabled) {
          res.mutationDisabled.supported++;
          let moved = 0;
          for (let ch = 0; ch < 4; ch++) {
            const tol = ch < 2 ? ts : th;
            moved = Math.max(moved, Math.abs(got[ch] - dis[ch]) / tol);
          }
          if (moved > 1) res.mutationDisabled.discriminated++;
          else {
            res.mutationDisabled.failures.push({ case: ci, probe: pi, kind: p.kind, moved });
            res.failures.push({ case: ci, probe: pi, kind: p.kind, check: 'mutation-disabled-undetected', moved });
          }
        } else {
          res.mutationDisabled.skipped++;
        }
        if (p.kind === 'straddle-x' || p.kind === 'straddle-y') {
          if (!p.supportsMip) res.mutationMip.skipped++;
        }
        if (p.supportsMip && (p.kind === 'straddle-x' || p.kind === 'straddle-y')) {
          res.mutationMip.supported++;
          const bad = byMode(ci, pi, 4), good = byMode(ci, pi, 5);
          let moved = 0;
          for (let ch = 0; ch < 4; ch++) {
            const tol = ch < 2 ? cts : cth;
            moved = Math.max(moved, Math.abs(bad[ch] - good[ch]) / tol);
          }
          if (moved > 1) res.mutationMip.discriminated++;
          else {
            res.mutationMip.failures.push({ case: ci, probe: pi, kind: p.kind, moved });
            res.failures.push({ case: ci, probe: pi, kind: p.kind, check: 'mutation-mip-undetected', moved });
          }
        }
      });
      cs.pairs.forEach(([i, j]) => {
        res.seamPairs++;
        const a = byMode(ci, i, 0), b = byMode(ci, j, 0);
        for (let ch = 0; ch < 4; ch++) {
          const err = Math.abs(a[ch] - b[ch]);
          res.seamMax = Math.max(res.seamMax, err);
          if (!(err <= 1e-4)) res.failures.push({ case: ci, check: 'seam', pair: [i, j], channel: ch, err });
        }
      });
    });
    res.pass = res.failures.length === 0 && res.glError === 0 &&
      res.mutationDisabled.discriminated > 0 && res.mutationMip.discriminated > 0;
    if (!res.mutationDisabled.discriminated || !res.mutationMip.discriminated) {
      res.failures.push({ check: 'mutation-never-discriminated' });
      res.pass = false;
    }
    console.log(JSON.stringify(res));
    if (!res.pass) process.exitCode = 1;
  } finally {
    await browser.close().catch(() => {});
  }
})().catch(e => { console.error(e); process.exit(1); });
