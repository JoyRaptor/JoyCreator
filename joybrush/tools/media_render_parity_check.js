// joybrush/tools/media_render_parity_check.js — render-parity for the paper-relief optimisation.
//
// Compares the ACTUAL jb_media_render.frag (root HEAD, with its ACTUAL #includes) against the
// exact candidate file supplied at runtime (coordinator copies the UNCOMMITTED candidate into a
// disposable verification tree; this checker never writes shaders and never commits).
//
// Valid candidate MUST be byte-exact for ALL modes/relief (no tolerated mismatch): the candidate
// reloads the canonical surface when (u_relief != 0.0 || u_mode == 1) and is otherwise equivalent
// because the surface value is unused there. EXACT approved replacement for the single original
// span `vec4 s = jb_paperSurface(docPx);` is:
//   // A flat look does not use the paper's surface vector. Keep the real
//   // surface for relief and the height debug view.
//   vec4 s = vec4(0.0);
//   if (u_relief != 0.0 || u_mode == 1) s = jb_paperSurface(docPx);
// in this order, whitespace/CRLF-tolerant only (RAW hashes still recorded with eolNormalized
// provenance). The guard rejects 0/multiple/reordered parts, any non-whitespace gap between
// zero-init and conditional (no extra ops), vec4(0)/!=0 without .0, unknown comments/code
// elsewhere, and malformed #include forms (strict #include "path" only, expansion and guard agree).
// Includes must stay actual. Guard selfchecks (no browser) assert accept-exact + reject
// zero/multiple/reordered/extra-gap/unrelated.
//
// Separate positive control (candidate is NEVER a positive control): a third genuinely MUTATED
// actual shader derived here by exactly ONE substitution
//   original: vec4 s = jb_paperSurface(docPx);
//   control:  vec4 s = vec4(0.0);   (no reload — forced 0 even where needed)
// with its own exact-one guard. Original vs control MUST differ (changed RGBA) for supported
// NONFLAT paper + (mode==1 || relief!=0) cases; original vs candidate MUST match ALWAYS.
//
// Real path: ACTUAL jb_media_screen.vert (fullscreen triangle via gl_VertexID, empty VAO,
// TRIANGLES 0,3 — the same display path MediaEngine.render uses) + actual expanded fragments.
// Draws ALL programs over the SAME seeded FINITE RGBA32F state (dry/wet/oil/mixed/empty) and the
// SAME NONFLAT canonical paper (raw bytes hashed as reference) to an RGBA8 FBO, then
// exact-compares EVERY byte including alpha.
//
// Coverage (full Cartesian 5x3x2x2=60 minimum, independently asserted + legacy extras): fixtures
// dry/wet/oil/mixed/empty x modes 0/1/8 x relief 0/nonzero x look 0/1, varied lamp/impasto/sheen,
// negative origins, nonzero zooms (0.5/1/2), seeded same finite inputs, controlled mode0 look pairs
// (same inputs except look). Missing Cartesian combos fail. Both u_look 0 and 1 covered; mixed alpha
// and exact alpha bytes compared; look0 requires opaque + varied RGB except empty+mode1 (paper
// height may be flat — still requires non-black + opaque); look1 mode0 requires transparency
// present + varied alpha (no forced opaque assert for look1; empty mode0 look1 may be all
// transparent). Look-pair nonvacuity guards mode0 pairs only: actual shader returns for mode1/8
// before the u_look branch, so requiring look0!=look1 there is WRONG. Candidate vs original MUST
// exact-match EVERY RGBA8 byte ALWAYS (including empty/flat edges).
//
// Textures / uniform types / required active bindings / FBO / GL errors / NONFLAT paper / raw
// canonical reference all fail if violated. Pass requires zero mismatches + detected separate
// positive control + nonvacuity (varied hashes + look transparency). EXT_disjoint_timer_query_webgl2
// via WebGL2 core API only (gl.createQuery/beginQuery/endQuery/getQueryParameter — NOT WebGL1
// EXT createQueryEXT etc.), disjoint-valid only, honestly skipped if unsupported; CPU submit time
// excludes readPixels/GPU wait if measured, otherwise omitted. No speedup / phone claims.
// Local clean WIP checkpoint UNVERIFIED, no run claims.
//
// Run (coordinator executes; bundled playwright-core + Edge/SwiftShader, no npm install):
//   node joybrush/tools/media_render_parity_check.js --candidate <candidate.frag> [--source <orig.frag>] [--chrome <exe>]
// Env: CANDIDATE_FRAG, SOURCE_FRAG, CHROME. --help prints options.
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
let chromium;
try {
  chromium = require('playwright-core').chromium;
} catch (e) {
  console.log(JSON.stringify({ error: 'missing dependency playwright-core (coordinator provides it, no install here): ' + e.message }));
  process.exit(1);
}
const ROOT = path.join(__dirname, '..');
const SHADER_DIR = path.join(ROOT, 'shaders');
const DEFAULT_SOURCE = path.join(SHADER_DIR, 'media', 'jb_media_render.frag');
const DEFAULT_VERT = path.join(SHADER_DIR, 'media', 'jb_media_screen.vert');

function argVal(name) {
  const i = process.argv.indexOf('--' + name);
  if (i >= 0 && i + 1 < process.argv.length) return process.argv[i + 1];
  return null;
}
if (process.argv.includes('--help') || process.argv.includes('-h')) {
  console.log(JSON.stringify({ usage: 'node media_render_parity_check.js --candidate <frag> [--source <frag>] [--chrome <exe>]', env: ['CANDIDATE_FRAG', 'SOURCE_FRAG', 'CHROME'] }));
  process.exit(0);
}
const sourcePath = path.resolve(argVal('source') || process.env.SOURCE_FRAG || DEFAULT_SOURCE);
const candidatePath = argVal('candidate') || process.env.CANDIDATE_FRAG || null;
const chromeEnv = argVal('chrome') || process.env.CHROME || null;

function sha256(s) { return crypto.createHash('sha256').update(s, 'utf8').digest('hex'); }
const STRICT_INCLUDE_RE = /^#include\s+"([^"]+)"$/;
function strictIncludeOf(trimmedLine) {
  const m = trimmedLine.match(STRICT_INCLUDE_RE);
  return m ? m[1] : null;
}
// Expand #include against the ACTUAL shaders root (same rule as the Android loader / gl.js).
// Strict: trimmed line must be exactly #include "path" with no trailing tokens. Malformed
// #include forms are rejected by the guard below, never silently misread.
function expandFile(rel, seen = new Set()) {
  if (seen.has(rel)) return '';
  seen.add(rel);
  const raw = fs.readFileSync(path.join(SHADER_DIR, rel), 'utf8');
  const text = raw.replace(/\r\n/g, '\n').replace(/\r/g, '\n');
  return expandText(text, seen);
}
function expandText(text, seen = new Set()) {
  return text.split('\n').map(l => {
    const inc = strictIncludeOf(l.trim());
    return inc ? expandFile(inc, seen) : l;
  }).join('\n');
}
function includesOf(text) {
  return text.split('\n').map(l => l.trim()).filter(l => STRICT_INCLUDE_RE.test(l));
}
function malformedIncludes(text) {
  return text.split('\n').map(l => l.trim()).filter(l => l.startsWith('#include') && !STRICT_INCLUDE_RE.test(l));
}

if (!fs.existsSync(sourcePath)) {
  console.log(JSON.stringify({ error: 'missing source frag', source: sourcePath }));
  process.exit(1);
}
if (!candidatePath) {
  console.log(JSON.stringify({ error: 'missing candidate: pass --candidate <exact root candidate file> (coordinator copies UNCOMMITTED candidate to a disposable tree)', source: sourcePath }));
  process.exit(1);
}
const candAbs = path.resolve(candidatePath);
if (!fs.existsSync(candAbs)) {
  console.log(JSON.stringify({ error: 'candidate not found', candidate: candAbs }));
  process.exit(1);
}
const origRaw = fs.readFileSync(sourcePath, 'utf8');
const candRaw = fs.readFileSync(candAbs, 'utf8');
const vertRaw = fs.readFileSync(DEFAULT_VERT, 'utf8');

// RAW bytes are the reference (hashes below); guard comparisons use LF-normalized text with
// explicit provenance (eolNormalized + normalized hashes). Normalization is NOT semantic
// weakening: prefix/suffix must still be byte-exact after normalization, and any CR is reported.
const origHadCR = origRaw.includes('\r');
const candHadCR = candRaw.includes('\r');
const vertHadCR = vertRaw.includes('\r');
const normEOL = (s) => s.replace(/\r\n/g, '\n').replace(/\r/g, '\n');
const origN = normEOL(origRaw);
const candN = normEOL(candRaw);
const vertN = normEOL(vertRaw);
const eolNormalized = (origN !== origRaw || candN !== candRaw || vertN !== vertRaw);

// ---- substitution guards: candidate exact-one + control exact-one, byte-exact elsewhere ----
function guardFail(reason, extra = {}) {
  console.log(JSON.stringify({ pass: false, guard: 'fail', reason, source: sourcePath, candidate: candAbs, ...extra }));
  process.exit(1);
}
// Approved ROOT candidate replacement (EXACT known variant). Original span is ONLY
//   vec4 s = jb_paperSurface(docPx);
// Expected replacement is exactly these 2 comment lines + zero-init (vec4(0.0)) + conditional
// reload (u_relief != 0.0 || u_mode == 1), in this order, with only whitespace between parts.
// Whitespace (spaces/tabs/indentation/newlines) may vary; CRLF is normalized above. Any other
// added code/comments elsewhere — including non-whitespace gap content, reordered parts,
// vec4(0) without .0, u_relief != 0 without .0, or unknown comments — is rejected.
const EXPECTED_C1 = '// A flat look does not use the paper\'s surface vector. Keep the real';
const EXPECTED_C2 = '// surface for relief and the height debug view.';
function escRe(s) { return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'); }
const ORIG_INIT_SINGLE_SRC = 'vec4\\s+s\\s*=\\s*jb_paperSurface\\s*\\(\\s*docPx\\s*\\)\\s*;';
const ZERO_INIT_STRICT_SRC = 'vec4\\s+s\\s*=\\s*vec4\\s*\\(\\s*0\\.0\\s*\\)\\s*;';
const COND_STRICT_SRC = 'if\\s*\\(\\s*u_relief\\s*!=\\s*0\\.0\\s*\\|\\|\\s*u_mode\\s*==\\s*1\\s*\\)\\s*s\\s*=\\s*jb_paperSurface\\s*\\(\\s*docPx\\s*\\)\\s*;';
const origInitRe = new RegExp(ORIG_INIT_SINGLE_SRC, 'g');
const zeroInitRe = new RegExp(ZERO_INIT_STRICT_SRC, 'g');
const condRe = new RegExp(COND_STRICT_SRC, 'g');
const surfCallRe = /jb_paperSurface\s*\(\s*docPx\s*\)/g;
const origInitSingleRe = new RegExp(ORIG_INIT_SINGLE_SRC);
const zeroInitSingleRe = new RegExp(ZERO_INIT_STRICT_SRC);
const condSingleRe = new RegExp(COND_STRICT_SRC);
const fullReplStrictRe = new RegExp('^' + escRe(EXPECTED_C1) + '\\s+' + escRe(EXPECTED_C2) + '\\s+' + ZERO_INIT_STRICT_SRC + '\\s+' + COND_STRICT_SRC + '$');

function validateCandidateGuard(origNorm, candNorm) {
  const badO = malformedIncludes(origNorm), badC = malformedIncludes(candNorm);
  if (badO.length || badC.length) return { ok: false, reason: 'malformed #include form (must be exactly #include "path" with no trailing tokens)', badO, badC };
  const incO = includesOf(origNorm), incC = includesOf(candNorm);
  if (incO.length !== 2 || incC.length !== 2 || incO.join('|') !== incC.join('|')) {
    return { ok: false, reason: 'includes must stay the actual two sources with no add/remove', sourceIncludes: incO, candidateIncludes: incC };
  }
  if (!incO.includes('#include "media/jb_media_common.glsl"') || !incO.includes('#include "jb_paper.glsl"')) {
    return { ok: false, reason: 'source includes are not the actual media/paper pair', sourceIncludes: incO };
  }
  const oInits = origNorm.match(origInitRe) || [];
  const oCalls = origNorm.match(surfCallRe) || [];
  if (oInits.length !== 1 || oCalls.length !== 1) return { ok: false, reason: 'source must contain exactly one paper-surface init', inits: oInits.length, calls: oCalls.length };
  const cInits = candNorm.match(origInitRe) || [];
  const cZero = candNorm.match(zeroInitRe) || [];
  const cCond = candNorm.match(condRe) || [];
  const cCalls = candNorm.match(surfCallRe) || [];
  if (cInits.length !== 0 || cZero.length !== 1 || cCond.length !== 1 || cCalls.length !== 1) {
    return { ok: false, reason: 'candidate must hold only zero-init (vec4(0.0)) + one conditional reload (u_relief != 0.0 || u_mode == 1), no 0/multiple/unrelated', directInits: cInits.length, zeroInits: cZero.length, condReloads: cCond.length, calls: cCalls.length };
  }
  const c1Count = candNorm.split(EXPECTED_C1).length - 1;
  const c2Count = candNorm.split(EXPECTED_C2).length - 1;
  if (c1Count !== 1 || c2Count !== 1) return { ok: false, reason: 'candidate must contain exactly the two approved comment lines (no missing/duplicate/unknown comments)', c1Count, c2Count };
  const oIdx = origNorm.search(origInitSingleRe);
  const oMatch = origInitSingleRe.exec(origNorm);
  if (oIdx < 0 || !oMatch) return { ok: false, reason: 'guard span not located (orig)' };
  const oEnd = oIdx + oMatch[0].length;
  const replStart = candNorm.indexOf(EXPECTED_C1);
  const c2Idx = candNorm.indexOf(EXPECTED_C2);
  const zIdx = candNorm.search(zeroInitSingleRe);
  const zMatch = zeroInitSingleRe.exec(candNorm);
  const cMatch = condSingleRe.exec(candNorm);
  if (replStart < 0 || c2Idx < 0 || zIdx < 0 || !zMatch || !cMatch) return { ok: false, reason: 'guard span not located (candidate)' };
  const c1End = replStart + EXPECTED_C1.length;
  const c2End = c2Idx + EXPECTED_C2.length;
  const zEnd = zIdx + zMatch[0].length;
  const cIdx = cMatch.index;
  const cEnd = cIdx + cMatch[0].length;
  if (!(replStart < c2Idx && c2Idx < zIdx && zIdx < cIdx)) return { ok: false, reason: 'reordered replacement parts (expected C1, C2, zero-init, cond in order)', replStart, c2Idx, zIdx, cIdx };
  const gap1 = candNorm.slice(c1End, c2Idx);
  const gap2 = candNorm.slice(c2End, zIdx);
  const gap3 = candNorm.slice(zEnd, cIdx);
  if (!/^\s*$/.test(gap1) || !/^\s*$/.test(gap2) || !/^\s*$/.test(gap3)) {
    return { ok: false, reason: 'non-whitespace gap between replacement parts (no extra operations allowed)', gap1: gap1.slice(0, 80), gap2: gap2.slice(0, 80), gap3: gap3.slice(0, 80) };
  }
  const replSpan = candNorm.slice(replStart, cEnd);
  if (!fullReplStrictRe.test(replSpan)) {
    return { ok: false, reason: 'replacement span is not exactly the approved comment+zero+cond block', spanHead: replSpan.slice(0, 160) };
  }
  if (origNorm.slice(0, oIdx) !== candNorm.slice(0, replStart) || origNorm.slice(oEnd) !== candNorm.slice(cEnd)) {
    return { ok: false, reason: 'unrelated bytes outside the single substitution span (byte-exact elsewhere required)' };
  }
  return { ok: true, oIdx, oEnd, replStart, cEnd };
}

const guardRes = validateCandidateGuard(origN, candN);
if (!guardRes.ok) guardFail(guardRes.reason, guardRes);
const oIdx = guardRes.oIdx;
const oEnd = guardRes.oEnd;
// Third genuinely MUTATED actual-shader control: exactly ONE substitution, forced zero, no reload.
// Derived from the ORIGINAL (normalized) only, same strict numeric form vec4(0.0).
const ctrlNsingle = origN.replace(origInitSingleRe, 'vec4 s = vec4(0.0);');
if (ctrlNsingle === origN) guardFail('control derivation found no substitution');
{
  const xInits = ctrlNsingle.match(origInitRe) || [];
  const xZero = ctrlNsingle.match(zeroInitRe) || [];
  const xCond = ctrlNsingle.match(condRe) || [];
  const xCalls = ctrlNsingle.match(surfCallRe) || [];
  if (xInits.length !== 0 || xZero.length !== 1 || xCond.length !== 0 || xCalls.length !== 0) {
    guardFail('control must be exactly one forced-zero with no reload (genuinely mutated)', { directInits: xInits.length, zeroInits: xZero.length, condReloads: xCond.length, calls: xCalls.length });
  }
  const xZeroIdx = ctrlNsingle.search(zeroInitSingleRe);
  const xZeroMatch = zeroInitSingleRe.exec(ctrlNsingle);
  if (xZeroIdx < 0 || !xZeroMatch) guardFail('control guard span not located');
  const xEnd = xZeroIdx + xZeroMatch[0].length;
  if (origN.slice(0, oIdx) !== ctrlNsingle.slice(0, xZeroIdx) || origN.slice(oEnd) !== ctrlNsingle.slice(xEnd)) {
    guardFail('control changed bytes outside the single substitution span (byte-exact elsewhere required)');
  }
  if (malformedIncludes(ctrlNsingle).length) guardFail('control has malformed #include', { controlBad: malformedIncludes(ctrlNsingle) });
  if (includesOf(ctrlNsingle).join('|') !== includesOf(origN).join('|')) guardFail('control includes must stay actual', { controlIncludes: includesOf(ctrlNsingle) });
}
const ctrlRaw = ctrlNsingle;

// Guard selfchecks (no browser needed): the guard must accept the actual exact candidate and
// reject zero/multiple/reordered/extra-code-in-gap/unrelated changes. Failure here is a guard bug.
function runGuardSelfChecks(origNorm) {
  const mkCand = (block) => {
    const oi = origNorm.search(origInitSingleRe);
    const om = origInitSingleRe.exec(origNorm);
    return origNorm.slice(0, oi) + block + origNorm.slice(oi + om[0].length);
  };
  const goodBlock = EXPECTED_C1 + '\n    ' + EXPECTED_C2 + '\n    vec4 s = vec4(0.0);\n    if (u_relief != 0.0 || u_mode == 1) s = jb_paperSurface(docPx);';
  const goodCRLF = goodBlock.replace(/\n/g, '\r\n');
  const casesSelf = [
    { name: 'accept-exact', cand: mkCand(goodBlock), want: true },
    { name: 'accept-CRLF-normalized', cand: normEOL(mkCand(goodCRLF)), want: true },
    { name: 'reject-zero-orig', cand: origNorm, want: false },
    { name: 'reject-multiple', cand: mkCand(goodBlock).replace(condSingleRe, (m) => m + '\n    if (u_relief != 0.0 || u_mode == 1) s = jb_paperSurface(docPx);'), want: false },
    { name: 'reject-reordered', cand: mkCand('if (u_relief != 0.0 || u_mode == 1) s = jb_paperSurface(docPx);\n    vec4 s = vec4(0.0);\n    ' + EXPECTED_C1 + '\n    ' + EXPECTED_C2), want: false },
    { name: 'reject-extra-code-in-gap', cand: mkCand(EXPECTED_C1 + '\n    ' + EXPECTED_C2 + '\n    vec4 s = vec4(0.0);\n    float __guard_probe = 1.0;\n    if (u_relief != 0.0 || u_mode == 1) s = jb_paperSurface(docPx);'), want: false },
    { name: 'reject-extra-comment-in-gap', cand: mkCand(EXPECTED_C1 + '\n    ' + EXPECTED_C2 + '\n    vec4 s = vec4(0.0);\n    // extra comment\n    if (u_relief != 0.0 || u_mode == 1) s = jb_paperSurface(docPx);'), want: false },
    { name: 'reject-unrelated-trailing', cand: mkCand(goodBlock) + '\n// trailing unrelated\n', want: false },
    { name: 'reject-unrelated-prefix', cand: '// prefix unrelated\n' + mkCand(goodBlock), want: false },
    { name: 'reject-zero-0-without-dot', cand: mkCand(EXPECTED_C1 + '\n    ' + EXPECTED_C2 + '\n    vec4 s = vec4(0);\n    if (u_relief != 0.0 || u_mode == 1) s = jb_paperSurface(docPx);'), want: false },
    { name: 'reject-cond-0-without-dot', cand: mkCand(EXPECTED_C1 + '\n    ' + EXPECTED_C2 + '\n    vec4 s = vec4(0.0);\n    if (u_relief != 0 || u_mode == 1) s = jb_paperSurface(docPx);'), want: false },
    { name: 'reject-unknown-comment', cand: mkCand('// unknown comment\n    ' + goodBlock), want: false },
  ];
  for (const t of casesSelf) {
    const r = validateCandidateGuard(origNorm, t.cand);
    if (r.ok !== t.want) return { ok: false, reason: 'guard selfcheck failed: ' + t.name + ' expected ok=' + t.want + ' got ok=' + r.ok + ' (' + (r.reason || 'ok') + ')' };
  }
  return { ok: true };
}
{
  const self = runGuardSelfChecks(origN);
  if (!self.ok) guardFail(self.reason);
}

const origExpanded = expandText(origN);
const candExpanded = expandText(candN);
const ctrlExpanded = expandText(ctrlRaw);
if (ctrlExpanded === origExpanded) guardFail('control expanded shader identical to original (not mutated)');
const prov = {
  source: sourcePath, candidate: candAbs, vert: DEFAULT_VERT,
  sourceSHA256: sha256(origRaw), candidateSHA256: sha256(candRaw), controlSHA256: sha256(ctrlRaw), vertSHA256: sha256(vertRaw),
  sourceHadCR: origHadCR, candidateHadCR: candHadCR, vertHadCR: vertHadCR, eolNormalized,
  sourceNormalizedSHA256: sha256(origN), candidateNormalizedSHA256: sha256(candN), vertNormalizedSHA256: sha256(vertN),
  controlNormalizedSHA256: sha256(ctrlRaw),
  sourceExpandedSHA256: sha256(origExpanded), candidateExpandedSHA256: sha256(candExpanded), controlExpandedSHA256: sha256(ctrlExpanded),
};

function findExe() {
  if (chromeEnv && fs.existsSync(chromeEnv)) return chromeEnv;
  const edge = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';
  if (fs.existsSync(edge)) return edge;
  try {
    const ds = fs.readdirSync('/opt/pw-browsers').filter(d => d.startsWith('chromium-'));
    if (ds.length) return path.join('/opt/pw-browsers', ds[0], 'chrome-linux/chrome');
  } catch (e) { /* no fallback dir */ }
  return null;
}

(async () => {
  const exe = findExe();
  if (!exe) {
    console.log(JSON.stringify({ pass: false, error: 'no browser: pass --chrome <Edge/Chromium exe> or set CHROME', ...prov }));
    process.exit(1);
  }
  const browser = await chromium.launch({ executablePath: exe, args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'] });
  try {
    const page = await browser.newPage();
    const out = await page.evaluate(async (P) => {
      const W = 64, H = 32, N = W * H;
      const fail = (error, extra = {}) => ({ error, ...extra });
      const c = document.createElement('canvas');
      c.width = W; c.height = H;
      const gl = c.getContext('webgl2', { antialias: false, alpha: false });
      if (!gl) return fail('no webgl2');
      const comp = (t, s) => {
        const sh = gl.createShader(t); gl.shaderSource(sh, s); gl.compileShader(sh);
        if (!gl.getShaderParameter(sh, gl.COMPILE_STATUS)) throw new Error((t === gl.VERTEX_SHADER ? 'vs: ' : 'fs: ') + gl.getShaderInfoLog(sh));
        return sh;
      };
      const link = (vs, fs) => {
        const p = gl.createProgram();
        gl.attachShader(p, comp(gl.VERTEX_SHADER, vs)); gl.attachShader(p, comp(gl.FRAGMENT_SHADER, fs));
        gl.linkProgram(p);
        if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error('link: ' + gl.getProgramInfoLog(p));
        return p;
      };
      let pO, pC, pX;
      try { pO = link(P.vert, P.orig); pC = link(P.vert, P.cand); pX = link(P.vert, P.ctrl); }
      catch (e) { return fail('compile/link: ' + e.message); }
      const active = (p) => {
        const m = {}; const n = gl.getProgramParameter(p, gl.ACTIVE_UNIFORMS);
        for (let i = 0; i < n; i++) { const info = gl.getActiveUniform(p, i); m[info.name.replace(/\[0\]$/, '')] = info.type; }
        return m;
      };
      const aO = active(pO), aC = active(pC), aX = active(pX);
      // Required display-path uniforms and their GL types (orig + valid candidate must match exactly).
      const need = { u_p0: gl.SAMPLER_2D, u_p1: gl.SAMPLER_2D, u_paperState: gl.SAMPLER_2D, u_w0: gl.SAMPLER_2D, u_w1: gl.SAMPLER_2D, u_paperBake: gl.SAMPLER_2D, u_paperSurface: gl.SAMPLER_2D, u_targetSize: gl.FLOAT_VEC2, u_layerOrigin: gl.FLOAT_VEC2, u_layerScale: gl.FLOAT, u_pan: gl.FLOAT_VEC2, u_zoom: gl.FLOAT, u_pxPerMm: gl.FLOAT, u_toothMm: gl.FLOAT, u_paperColor: gl.FLOAT_VEC3, u_lamp: gl.FLOAT_VEC3, u_relief: gl.FLOAT, u_sheen: gl.FLOAT, u_capMm: gl.FLOAT, u_mode: gl.INT, u_impasto: gl.FLOAT, u_look: gl.FLOAT, u_paperTexelPx: gl.FLOAT, u_paperSize: gl.FLOAT, u_paperHexTexels: gl.FLOAT, u_paperSlopeRange: gl.FLOAT, u_paperHeightMean: gl.FLOAT };
      const optFloat = ['u_paperHBot', 'u_paperHTop', 'u_paperFluidTexelPx', 'u_paperFluidSize', 'u_paperFluidHexTexels'];
      const uniErr = [];
      for (const [k, t] of Object.entries(need)) {
        if (aO[k] === undefined) uniErr.push('orig missing ' + k);
        else if (aO[k] !== t) uniErr.push('orig type ' + k);
        if (aC[k] === undefined) uniErr.push('cand missing ' + k);
        else if (aC[k] !== t) uniErr.push('cand type ' + k);
      }
      if (aO.u_paperRotatable === undefined || aC.u_paperRotatable === undefined) uniErr.push('missing u_paperRotatable');
      else if (aO.u_paperRotatable !== gl.BOOL || aC.u_paperRotatable !== gl.BOOL) uniErr.push('type u_paperRotatable (want BOOL)');
      for (const k of optFloat) for (const [tag, m] of [['orig', aO], ['cand', aC]]) {
        if (m[k] !== undefined && m[k] !== gl.FLOAT) uniErr.push(tag + ' type ' + k);
      }
      // Control is genuinely mutated (paper call removed) so its paper samplers/uniforms may be
      // optimized out; where still active their types must match, but missing paper uniforms are allowed.
      const paperKeys = ['u_paperSurface', 'u_paperTexelPx', 'u_paperSize', 'u_paperHexTexels', 'u_paperSlopeRange', 'u_paperHeightMean', 'u_paperRotatable'];
      for (const [k, t] of Object.entries(need)) {
        if (aX[k] !== undefined && aX[k] !== t) uniErr.push('ctrl type ' + k);
        else if (aX[k] === undefined && !paperKeys.includes(k)) uniErr.push('ctrl missing non-paper ' + k);
      }
      if (uniErr.length) return fail('uniforms: ' + uniErr.slice(0, 8).join('; '), { uniforms: uniErr });
      if (gl.getError() !== gl.NO_ERROR) return fail('GL error after program setup');

      const rng32 = (a) => () => { a |= 0; a = a + 0x6D2B79F5 | 0; let t = Math.imul(a ^ a >>> 15, 1 | a); t = t + Math.imul(t ^ t >>> 7, 61 | t) ^ t; return ((t ^ t >>> 14) >>> 0) / 4294967296; };
      // Seeded FINITE RGBA32F layer state. Oil keeps ~35% gaps so relief!=0 still moves the frame.
      function genFixture(kind, seed) {
        const r = rng32(seed);
        const p0 = new Float32Array(N * 4), p1 = new Float32Array(N * 4), ps = new Float32Array(N * 4);
        const w0 = new Float32Array(N * 4), w1 = new Float32Array(N * 4), bk = new Float32Array(N * 4);
        for (let i = 0; i < N; i++) {
          const o = i * 4, q = r();
          let K = [0, 0, 0], vol = 0, S = [0, 0, 0], open = 0, crush = 0, V = 0, fl = 0, wr = 0, sat = 0, wK = [0, 0, 0], wS = 0;
          if (kind === 'dry' || (kind === 'mixed' && q < 0.4)) {
            K = [r() * 1.5, r() * 1.2, r()]; vol = 0.01 + r() * 0.04; S = [r() * 0.3, r() * 0.3, r() * 0.3]; open = r() * 0.4;
            crush = 0.1 + r() * 0.2; V = 0.1 + r() * 0.4; fl = 0.2 + r() * 0.3;
          } else if (kind === 'oil' || (kind === 'mixed' && q < 0.7)) {
            if (r() < 0.35) { vol = 0; }
            else { K = [1 + r() * 4, 0.5 + r() * 3, r() * 2]; vol = 0.06 + r() * 0.09; S = [0.5 + r() * 1.5, 0.5 + r() * 1.2, 0.3 + r()]; open = 0.5 + r() * 0.5; }
          } else if (kind === 'wet' || kind === 'mixed') {
            K = [r() * 0.3, r() * 0.3, r() * 0.3]; vol = 0.005 + r() * 0.01; S = [r() * 0.2, r() * 0.2, r() * 0.2]; open = r() * 0.2;
            V = r() * 0.05; fl = 0.3; wr = 0.05 + r() * 0.15; sat = 0.3 + r() * 0.5; wK = [0.2 + r() * 0.6, 0.2 + r() * 0.5, 0.2 + r() * 0.4]; wS = 0.2 + r() * 0.4;
          }
          p0[o] = K[0]; p0[o + 1] = K[1]; p0[o + 2] = K[2]; p0[o + 3] = vol;
          p1[o] = S[0]; p1[o + 1] = S[1]; p1[o + 2] = S[2]; p1[o + 3] = open;
          ps[o] = crush; ps[o + 1] = V; ps[o + 2] = V * fl; ps[o + 3] = 0;
          w0[o] = wr; w0[o + 1] = sat; w0[o + 2] = 0; w0[o + 3] = 0;
          w1[o] = wK[0]; w1[o + 1] = wK[1]; w1[o + 2] = wK[2]; w1[o + 3] = wS;
          bk[o] = 0; bk[o + 1] = 0; bk[o + 2] = 0.2 + r() * 0.6; bk[o + 3] = 0;
        }
        return { p0, p1, ps, w0, w1, bk };
      }
      // NONFLAT canonical paper: sinusoidal + seeded noise heights, central-difference slopes,
      // packed exactly as the production surface (R/G slope bytes, B height, A height^2).
      const PS = 64, SLOPE = 0.099;
      function genPaper(seed) {
        const r = rng32(seed), h = new Float32Array(PS * PS);
        for (let y = 0; y < PS; y++) for (let x = 0; x < PS; x++) {
          h[y * PS + x] = 0.5 + 0.25 * Math.sin(2 * Math.PI * x / 16) + 0.15 * Math.cos(2 * Math.PI * y / 12) + 0.2 * (r() - 0.5);
        }
        const bytes = new Uint8Array(PS * PS * 4);
        let mean = 0, mn = 1, mx = 0, sMn = 255, sMx = 0;
        for (let y = 0; y < PS; y++) for (let x = 0; x < PS; x++) {
          const at = (xx, yy) => h[((yy + PS) % PS) * PS + ((xx + PS) % PS)];
          const dx = (at(x + 1, y) - at(x - 1, y)) / 2, dy = (at(x, y + 1) - at(x, y - 1)) / 2;
          const v = Math.min(1, Math.max(0, h[y * PS + x]));
          const ex = Math.round(127 + 127 * Math.min(1, Math.max(-1, dx / SLOPE)));
          const ey = Math.round(127 + 127 * Math.min(1, Math.max(-1, dy / SLOPE)));
          const o = (y * PS + x) * 4;
          bytes[o] = ex; bytes[o + 1] = ey; bytes[o + 2] = Math.round(v * 255); bytes[o + 3] = Math.round(v * v * 255);
          mean += v; mn = Math.min(mn, v); mx = Math.max(mx, v); sMn = Math.min(sMn, ex, ey); sMx = Math.max(sMx, ex, ey);
        }
        return { bytes, mean: mean / (PS * PS), mn, mx, sMn, sMx };
      }
      const fixtures = { dry: genFixture('dry', 0xd271), wet: genFixture('wet', 0x9e77), oil: genFixture('oil', 0x0115), mixed: genFixture('mixed', 0x71ed), empty: genFixture('empty', 0xe1c0) };
      const paper = genPaper(0x9a9a);
      // Fixture guards: FINITE everywhere, nonempty + alpha-varied sources overall, empty truly empty.
      let finite = true, absSum = 0, aMin = 1, aMax = 0;
      for (const [k, f] of Object.entries(fixtures)) for (const arr of [f.p0, f.p1, f.ps, f.w0, f.w1, f.bk]) {
        for (let i = 0; i < arr.length; i++) { if (!Number.isFinite(arr[i])) finite = false; absSum += Math.abs(arr[i]); }
      }
      for (const k of ['dry', 'wet', 'oil', 'mixed']) { const a = fixtures[k].p0; for (let i = 3; i < a.length; i += 4) { aMin = Math.min(aMin, a[i]); aMax = Math.max(aMax, a[i]); } }
      let emptyMax = 0;
      for (const arr of [fixtures.empty.p0, fixtures.empty.p1, fixtures.empty.ps, fixtures.empty.w0, fixtures.empty.w1]) {
        for (let i = 0; i < arr.length; i++) emptyMax = Math.max(emptyMax, Math.abs(arr[i]));
      }
      if (!finite) return fail('fixtures non-finite');
      if (!(absSum > 1 && (aMax - aMin) > 0.01)) return fail('source fixtures vacuous (empty/unvaried)', { absSum, aMin, aMax });
      if (!(emptyMax === 0)) return fail('empty fixture must be all zero state', { emptyMax });
      if (!((paper.mx - paper.mn) > 0.15 && (paper.sMx - paper.sMn) > 8)) return fail('paper must be NONFLAT canonical', { hRange: paper.mx - paper.mn, sRange: paper.sMx - paper.sMn });

      function ftex(data) {
        const t = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, t);
        gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA32F, W, H, 0, gl.RGBA, gl.FLOAT, data);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
        return t;
      }
      const gpuFix = {};
      for (const [k, f] of Object.entries(fixtures)) {
        gpuFix[k] = { p0: ftex(f.p0), p1: ftex(f.p1), ps: ftex(f.ps), w0: ftex(f.w0), w1: ftex(f.w1), bk: ftex(f.bk) };
      }
      const paperTex = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, paperTex);
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, PS, PS, 0, gl.RGBA, gl.UNSIGNED_BYTE, paper.bytes);
      gl.generateMipmap(gl.TEXTURE_2D);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR_MIPMAP_LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.REPEAT); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.REPEAT);
      const fluidTex = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, fluidTex);
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, 4, 4, 0, gl.RGBA, gl.UNSIGNED_BYTE, new Uint8Array(64).fill(128));
      gl.generateMipmap(gl.TEXTURE_2D);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR_MIPMAP_LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.REPEAT); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.REPEAT);
      if (!paperTex || !fluidTex) return fail('paper/fluid texture creation failed');
      for (const k of Object.keys(gpuFix)) for (const t of Object.values(gpuFix[k])) if (!t) return fail('layer texture creation failed', { fixture: k });
      if (gl.getError() !== gl.NO_ERROR) return fail('GL error after texture upload (check RGBA32F/NEAREST + paper mips/REPEAT)');
      const outTex = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, outTex);
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, W, H, 0, gl.RGBA, gl.UNSIGNED_BYTE, null);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST);
      if (!outTex) return fail('output texture creation failed');
      const fbo = gl.createFramebuffer(); gl.bindFramebuffer(gl.FRAMEBUFFER, fbo);
      gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, outTex, 0);
      if (gl.checkFramebufferStatus(gl.FRAMEBUFFER) !== gl.FRAMEBUFFER_COMPLETE) return fail('output RGBA8 framebuffer incomplete');
      const vao = gl.createVertexArray(); gl.bindVertexArray(vao);
      gl.viewport(0, 0, W, H); gl.disable(gl.BLEND); gl.disable(gl.SCISSOR_TEST);
      const LA = [-0.45, -0.55, 0.70], LB = [0.5, 0.3, 0.8];
      const norm = (v) => { const l = Math.hypot(...v); return v.map(x => x / l); };
      // Full Cartesian coverage: dry/wet/oil/mixed/empty x mode 0/1/8 x relief 0/nonzero x look 0/1 = 60
      // minimum, varied lamp/impasto/sheen, negative origins, nonzero zooms (0.5/1/2), seeded same
      // inputs, controlled mode0 look pairs (same other params, look differs). Legacy extras kept.
      const legacyCases = [
        { f: 'dry', o: [-8, -4], z: 1, pan: [0, 0], mode: 0, relief: 1, look: 0, lamp: LA, imp: 1, sheen: 0.6 },
        { f: 'dry', o: [-8, -4], z: 1, pan: [0, 0], mode: 0, relief: 1, look: 1, lamp: LA, imp: 1, sheen: 0.6 },
        { f: 'dry', o: [0, 0], z: 1, pan: [0, 0], mode: 0, relief: 0, look: 0, lamp: LA, imp: 1, sheen: 0.6 },
        { f: 'dry', o: [0, 0], z: 1, pan: [0, 0], mode: 0, relief: 0, look: 1, lamp: LA, imp: 1, sheen: 0.6 },
        { f: 'wet', o: [-8, -4], z: 0.5, pan: [4, 2], mode: 0, relief: 1, look: 0, lamp: LB, imp: 1, sheen: 0 },
        { f: 'wet', o: [-8, -4], z: 0.5, pan: [4, 2], mode: 0, relief: 1, look: 1, lamp: LB, imp: 1, sheen: 0 },
        { f: 'oil', o: [5, 3], z: 1, pan: [0, 0], mode: 0, relief: 1, look: 0, lamp: LA, imp: 1, sheen: 0 },
        { f: 'oil', o: [5, 3], z: 1, pan: [0, 0], mode: 0, relief: 1, look: 1, lamp: LA, imp: 1, sheen: 0 },
        { f: 'mixed', o: [-12, -6], z: 0.5, pan: [0, 0], mode: 0, relief: 1, look: 0, lamp: LB, imp: 0, sheen: 0.6 },
        { f: 'mixed', o: [-12, -6], z: 0.5, pan: [0, 0], mode: 0, relief: 1, look: 1, lamp: LB, imp: 0, sheen: 0.6 },
        { f: 'empty', o: [0, 0], z: 1, pan: [0, 0], mode: 0, relief: 1, look: 0, lamp: LA, imp: 1, sheen: 0 },
        { f: 'empty', o: [0, 0], z: 1, pan: [0, 0], mode: 0, relief: 1, look: 1, lamp: LA, imp: 1, sheen: 0 },
        { f: 'dry', o: [0, 0], z: 2, pan: [4, 2], mode: 1, relief: 0, look: 0, lamp: LA, imp: 1, sheen: 0.6 },
        { f: 'oil', o: [0, 0], z: 2, pan: [4, 2], mode: 1, relief: 0, look: 1, lamp: LA, imp: 1, sheen: 0.6 },
        { f: 'wet', o: [0, 0], z: 1, pan: [0, 0], mode: 1, relief: 1, look: 0, lamp: LA, imp: 0, sheen: 0.6 },
        { f: 'mixed', o: [0, 0], z: 1, pan: [0, 0], mode: 8, relief: 0, look: 0, lamp: LA, imp: 0, sheen: 0.6 },
        { f: 'oil', o: [-8, -4], z: 1, pan: [7, 5], mode: 8, relief: 1, look: 0, lamp: LB, imp: 1, sheen: 0 },
        { f: 'dry', o: [5, 3], z: 0.5, pan: [7, 5], mode: 8, relief: 1, look: 1, lamp: LA, imp: 0, sheen: 0.6 },
        { f: 'empty', o: [0, 0], z: 1, pan: [0, 0], mode: 0, relief: 0, look: 0, lamp: LA, imp: 1, sheen: 0 },
        { f: 'empty', o: [-8, -4], z: 2, pan: [0, 0], mode: 1, relief: 0, look: 0, lamp: LB, imp: 0, sheen: 0 },
      ];
      const CART_F = ['dry', 'wet', 'oil', 'mixed', 'empty'];
      const CART_M = [0, 1, 8];
      const CART_R = [0, 1];
      const CART_LK = [0, 1];
      const CART_ORIGINS = [[-8, -4], [0, 0], [5, 3], [-12, -6]];
      const CART_ZOOMS = [0.5, 1, 2];
      const CART_PANS = [[0, 0], [4, 2], [7, 5]];
      function cartParams(f, mode, relief) {
        const fi = CART_F.indexOf(f), mi = CART_M.indexOf(mode), ri = CART_R.indexOf(relief);
        const k = fi * 6 + mi * 2 + ri;
        return {
          o: CART_ORIGINS[k % CART_ORIGINS.length],
          z: CART_ZOOMS[k % CART_ZOOMS.length],
          pan: CART_PANS[k % CART_PANS.length],
          lamp: (k % 2 === 0) ? LA : LB,
          imp: ((fi + ri) % 2 === 0) ? 1 : 0,
          sheen: ((fi + mi + ri) % 2 === 0) ? 0.6 : 0,
        };
      }
      const cartCases = [];
      for (const f of CART_F) for (const mode of CART_M) for (const relief of CART_R) for (const look of CART_LK) {
        const p = cartParams(f, mode, relief);
        cartCases.push({ f, o: p.o.slice(), z: p.z, pan: p.pan.slice(), mode, relief, look, lamp: p.lamp.slice(), imp: p.imp, sheen: p.sheen });
      }
      const cases = [...legacyCases, ...cartCases];
      // Independent coverage assertions: full Cartesian 5x3x2x2=60 must be present; fail otherwise.
      {
        const need = new Set();
        for (const f of CART_F) for (const m of CART_M) for (const r of CART_R) for (const lk of CART_LK) need.add([f, m, r, lk].join('|'));
        const have = new Set(cases.map(c2 => [c2.f, c2.mode, c2.relief !== 0 ? 1 : 0, c2.look].join('|')));
        const missing = [...need].filter(k => !have.has(k));
        if (missing.length) return fail('coverage: full Cartesian 5x3x2x2=60 required, missing ' + missing.length, { missing: missing.slice(0, 8) });
        if (cases.length < 60) return fail('coverage: at least 60 cases required', { count: cases.length });
        const seenF = new Set(cases.map(c2 => c2.f));
        const seenM = new Set(cases.map(c2 => c2.mode));
        const seenLk = new Set(cases.map(c2 => c2.look));
        const hasR0 = cases.some(c2 => c2.relief === 0), hasR1 = cases.some(c2 => c2.relief !== 0);
        const hasLk1M0 = cases.some(c2 => c2.look === 1 && c2.mode === 0);
        const hasNeg = cases.some(c2 => c2.o[0] < 0 || c2.o[1] < 0);
        const seenZ = new Set(cases.map(c2 => c2.z));
        const seenLamp = new Set(cases.map(c2 => c2.lamp.join(',')));
        const hasImp0 = cases.some(c2 => c2.imp === 0), hasImp1 = cases.some(c2 => c2.imp === 1);
        const hasSh0 = cases.some(c2 => c2.sheen === 0), hasShP = cases.some(c2 => c2.sheen > 0);
        if (!(seenF.has('dry') && seenF.has('wet') && seenF.has('oil') && seenF.has('mixed') && seenF.has('empty'))) return fail('coverage: fixtures dry/wet/oil/mixed/empty required', { fixtures: [...seenF] });
        if (!(seenM.has(0) && seenM.has(1) && seenM.has(8))) return fail('coverage: modes 0/1/8 required', { modes: [...seenM] });
        if (!hasR0 || !hasR1) return fail('coverage: relief 0 and nonzero required');
        if (!(seenLk.has(0) && seenLk.has(1) && hasLk1M0)) return fail('coverage: look 0 and 1 (with look1+mode0) required', { looks: [...seenLk] });
        if (!hasNeg) return fail('coverage: negative origin required');
        if (!(seenZ.size >= 2 && seenZ.has(1))) return fail('coverage: varied nonzero zoom required', { zooms: [...seenZ] });
        if (!(seenLamp.size >= 2)) return fail('coverage: varied lamp required');
        if (!hasImp0 || !hasImp1) return fail('coverage: impasto 0 and 1 required');
        if (!hasSh0 || !hasShP) return fail('coverage: sheen 0 and >0 required');
        // Controlled mode0 look pairs: same f/o/z/pan/mode/relief/lamp/imp/sheen, look differs.
        {
          const byCtrl = new Map();
          for (const c2 of cases) {
            if (c2.mode !== 0) continue;
            const k = [c2.f, c2.o.join(','), c2.z, c2.pan.join(','), c2.mode, c2.relief !== 0 ? 1 : 0, c2.lamp.join(','), c2.imp, c2.sheen].join('|');
            if (!byCtrl.has(k)) byCtrl.set(k, new Set());
            byCtrl.get(k).add(c2.look);
          }
          let controlledPairs = 0;
          for (const s of byCtrl.values()) if (s.has(0) && s.has(1)) controlledPairs++;
          // 5 fixtures x 2 reliefs = 10 controlled pairs minimum (Cartesian provides exactly this).
          if (controlledPairs < 10) return fail('coverage: controlled mode0 look pairs required (same inputs except look)', { controlledPairs });
        }
      }
      const locs = (p) => { const g = (n) => gl.getUniformLocation(p, n); return { g }; };
      const L0 = locs(pO), L1 = locs(pC), LX = locs(pX);
      function setAll(L, prog, cs, F) {
        gl.useProgram(prog);
        const U = (n, v) => gl.uniform1i(L.g(n), v);
        const F1 = (n, v) => gl.uniform1f(L.g(n), v);
        gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D, F.p0); U('u_p0', 0);
        gl.activeTexture(gl.TEXTURE1); gl.bindTexture(gl.TEXTURE_2D, F.p1); U('u_p1', 1);
        gl.activeTexture(gl.TEXTURE2); gl.bindTexture(gl.TEXTURE_2D, F.ps); U('u_paperState', 2);
        gl.activeTexture(gl.TEXTURE3); gl.bindTexture(gl.TEXTURE_2D, F.w0); U('u_w0', 3);
        gl.activeTexture(gl.TEXTURE4); gl.bindTexture(gl.TEXTURE_2D, F.w1); U('u_w1', 4);
        gl.activeTexture(gl.TEXTURE5); gl.bindTexture(gl.TEXTURE_2D, F.bk); U('u_paperBake', 5);
        gl.activeTexture(gl.TEXTURE6); gl.bindTexture(gl.TEXTURE_2D, paperTex); U('u_paperSurface', 6);
        gl.activeTexture(gl.TEXTURE7); gl.bindTexture(gl.TEXTURE_2D, fluidTex); if (L.g('u_paperFluid')) U('u_paperFluid', 7);
        gl.uniform2f(L.g('u_targetSize'), W, H);
        gl.uniform2f(L.g('u_layerOrigin'), cs.o[0], cs.o[1]); F1('u_layerScale', 1);
        gl.uniform2f(L.g('u_pan'), cs.pan[0], cs.pan[1]); F1('u_zoom', cs.z);
        F1('u_pxPerMm', 20); F1('u_toothMm', 0.09);
        gl.uniform3f(L.g('u_paperColor'), 0.95, 0.93, 0.88);
        const lp = norm(cs.lamp); gl.uniform3f(L.g('u_lamp'), lp[0], lp[1], lp[2]);
        F1('u_relief', cs.relief); F1('u_sheen', cs.sheen); F1('u_capMm', 0.2);
        gl.uniform1i(L.g('u_mode'), cs.mode); F1('u_impasto', cs.imp); F1('u_look', cs.look);
        F1('u_paperTexelPx', 2); F1('u_paperSize', PS); F1('u_paperHexTexels', 32); F1('u_paperSlopeRange', SLOPE);
        gl.uniform1i(L.g('u_paperRotatable'), 1); F1('u_paperHeightMean', paper.mean);
        if (L.g('u_paperHBot')) F1('u_paperHBot', 0.2);
        if (L.g('u_paperHTop')) F1('u_paperHTop', 0.8);
        if (L.g('u_paperFluidTexelPx')) F1('u_paperFluidTexelPx', 0);
        if (L.g('u_paperFluidSize')) F1('u_paperFluidSize', 4);
        if (L.g('u_paperFluidHexTexels')) F1('u_paperFluidHexTexels', 1);
      }
      // Required active bindings: sampler uniforms must read back the bound unit.
      function checkBindings(L, prog, allowMissingPaperSurface) {
        const pairs = [['u_p0', 0], ['u_p1', 1], ['u_paperState', 2], ['u_w0', 3], ['u_w1', 4], ['u_paperBake', 5], ['u_paperSurface', 6]];
        for (const [nm, unit] of pairs) {
          const loc = L.g(nm);
          if (!loc) {
            if (nm === 'u_paperSurface' && allowMissingPaperSurface) continue;
            return 'missing location ' + nm;
          }
          const v = gl.getUniform(prog, loc);
          if (v !== unit) return 'binding ' + nm + '=' + v + ' want ' + unit;
        }
        const fl = L.g('u_paperFluid');
        if (fl) { const v = gl.getUniform(prog, fl); if (v !== 7) return 'binding u_paperFluid=' + v + ' want 7'; }
        return null;
      }
      const fnv = (b) => { let h = 2166136261; for (let i = 0; i < b.length; i++) { h ^= b[i]; h = Math.imul(h, 16777619); } return (h >>> 0).toString(16).padStart(8, '0'); };
      const paperRawHash = fnv(paper.bytes);
      const rows = [];
      const controlRows = [];
      let probes = 0, maxDiff = 0, mismatchBytes = 0, mismatchCases = 0;
      let controlSupported = 0, controlDiffCases = 0;
      let cpuSubmitMs = 0;
      const hashes = new Set();
      let look1TransparentPixels = 0, look1CountedPixels = 0, look1AMin = 255, look1AMax = 0;
      for (let ci = 0; ci < cases.length; ci++) {
        const cs = cases[ci], F = gpuFix[cs.f];
        gl.bindFramebuffer(gl.FRAMEBUFFER, fbo);
        let t0 = performance.now();
        setAll(L0, pO, cs, F);
        let bErr = checkBindings(L0, pO, false);
        if (bErr) return fail('required active binding orig: ' + bErr, { case: ci });
        gl.drawArrays(gl.TRIANGLES, 0, 3);
        cpuSubmitMs += performance.now() - t0;
        if (gl.getError() !== gl.NO_ERROR) return fail('GL error drawing original', { case: ci });
        const bO = new Uint8Array(N * 4); gl.readPixels(0, 0, W, H, gl.RGBA, gl.UNSIGNED_BYTE, bO);
        t0 = performance.now();
        setAll(L1, pC, cs, F);
        bErr = checkBindings(L1, pC, false);
        if (bErr) return fail('required active binding candidate: ' + bErr, { case: ci });
        gl.drawArrays(gl.TRIANGLES, 0, 3);
        cpuSubmitMs += performance.now() - t0;
        if (gl.getError() !== gl.NO_ERROR) return fail('GL error drawing candidate', { case: ci });
        const bC = new Uint8Array(N * 4); gl.readPixels(0, 0, W, H, gl.RGBA, gl.UNSIGNED_BYTE, bC);
        t0 = performance.now();
        setAll(LX, pX, cs, F);
        bErr = checkBindings(LX, pX, true);
        if (bErr) return fail('required active binding control: ' + bErr, { case: ci });
        gl.drawArrays(gl.TRIANGLES, 0, 3);
        cpuSubmitMs += performance.now() - t0;
        if (gl.getError() !== gl.NO_ERROR) return fail('GL error drawing control', { case: ci });
        const bX = new Uint8Array(N * 4); gl.readPixels(0, 0, W, H, gl.RGBA, gl.UNSIGNED_BYTE, bX);
        if (gl.getError() !== gl.NO_ERROR) return fail('GL error after readback', { case: ci });
        // Valid candidate: exact match ALWAYS (every byte including alpha). No tolerated mismatch.
        let md = 0, mm = 0, pxMm = 0;
        for (let i = 0; i < bO.length; i++) { const d = Math.abs(bO[i] - bC[i]); if (d > md) md = d; if (d !== 0) mm++; }
        for (let p = 0; p < N; p++) { if (bO[p * 4] !== bC[p * 4] || bO[p * 4 + 1] !== bC[p * 4 + 1] || bO[p * 4 + 2] !== bC[p * 4 + 2] || bO[p * 4 + 3] !== bC[p * 4 + 3]) pxMm++; }
        // Separate mutated control: changed RGBA where supported (mode==1 || relief!=0, NONFLAT paper).
        let cmd = 0, cmm = 0, cpx = 0;
        for (let i = 0; i < bO.length; i++) { const d = Math.abs(bO[i] - bX[i]); if (d > cmd) cmd = d; if (d !== 0) cmm++; }
        for (let p = 0; p < N; p++) { if (bO[p * 4] !== bX[p * 4] || bO[p * 4 + 1] !== bX[p * 4 + 1] || bO[p * 4 + 2] !== bX[p * 4 + 2] || bO[p * 4 + 3] !== bX[p * 4 + 3]) cpx++; }
        probes += N; maxDiff = Math.max(maxDiff, md); mismatchBytes += mm;
        const candOk = (mm === 0);
        if (!candOk) mismatchCases++;
        const supported = (cs.mode === 1 || cs.relief !== 0);
        if (supported) controlSupported++;
        const ctrlDiff = (cmm > 0);
        if (supported && ctrlDiff) controlDiffCases++;
        const hO = fnv(bO), hC = fnv(bC), hX = fnv(bX);
        hashes.add(hO); hashes.add(hC);
        if (cs.look === 0) {
          // Opaque display path: exact alpha bytes must be 255 on both programs.
          // Valid candidate vs original MUST exact-match EVERY RGBA8 byte ALWAYS (no edge exemption).
          let mx = 0, mn = 255, aBad = 0;
          for (let i = 0; i < bO.length; i += 4) {
            mx = Math.max(mx, bO[i], bO[i + 1], bO[i + 2]); mn = Math.min(mn, bO[i], bO[i + 1], bO[i + 2]);
            if (bO[i + 3] !== 255 || bC[i + 3] !== 255) aBad++;
          }
          if (aBad !== 0) return fail('alpha must stay opaque for u_look=0 (exact alpha bytes)', { case: ci, aBad });
          // Empty+mode1 (paper height) may read flat depending on paper sampling: allow flat there,
          // still require non-black to prove a draw happened. All other look0 cases require varied RGB.
          const isEmptyMode1 = (cs.f === 'empty' && cs.mode === 1);
          if (!isEmptyMode1) {
            if (!(mx > 16 && (mx - mn) > 4)) return fail('output vacuous for look0 (all-black/flat)', { case: ci, mx, mn });
          } else {
            if (!(mx > 16)) return fail('output vacuous for empty mode1 look0 (all-black)', { case: ci, mx, mn });
          }
        } else {
          // u_look=1: NO forced opaque assert; exact alpha bytes already compared via mm above.
          // Collect transparency stats for mode0 (premultiplied look tile); other modes early-return.
          if (cs.mode === 0) {
            for (let i = 3; i < bO.length; i += 4) {
              const a = bO[i];
              look1CountedPixels++;
              look1AMin = Math.min(look1AMin, a); look1AMax = Math.max(look1AMax, a);
              if (a < 255) look1TransparentPixels++;
            }
          }
        }
        rows.push({ case: ci, fixture: cs.f, mode: cs.mode, relief: cs.relief, look: cs.look, zoom: cs.z, origin: cs.o, expectSame: true, bytesDiffer: mm, pixelsDiffer: pxMm, maxByteDiff: md, hashOrig: hO, hashCand: hC, ok: candOk });
        controlRows.push({ case: ci, fixture: cs.f, mode: cs.mode, relief: cs.relief, look: cs.look, supported, bytesDiffer: cmm, pixelsDiffer: cpx, maxByteDiff: cmd, hashCtrl: hX, detected: ctrlDiff });
        if (!candOk) return { error: null, failAt: ci, rows, controlRows, probes, maxDiff, mismatchBytes, mismatchCases, controlSupported, controlDiffCases, note: 'valid-candidate mismatch (no tolerated mismatch): original vs candidate must be exact for ALL modes/relief' };
      }
      cpuSubmitMs = +cpuSubmitMs.toFixed(1);
      if (hashes.size < 4) return fail('outputs vacuous (all cases equal)', { distinctHashes: hashes.size });
      // Look-pair nonvacuity ONLY where look is semantically used. Actual jb_media_render.frag
      // returns for u_mode==1 (paper height, s.z) and u_mode==8 (lit slope) BEFORE the u_look
      // branch, so look is ignored there; requiring look0!=look1 for mode1/8 is WRONG.
      // Guard controlled mode0 pairs only (same inputs except look). Empty mode0 look1 may be
      // all-transparent — that still differs from opaque look0, so the pair check holds.
      {
        const byKey = new Map();
        for (let i = 0; i < cases.length; i++) {
          const cs = cases[i];
          if (cs.mode !== 0) continue;
          const key = [cs.f, cs.o.join(','), cs.z, cs.pan.join(','), cs.mode, cs.relief, cs.lamp.join(','), cs.imp, cs.sheen].join('|');
          if (!byKey.has(key)) byKey.set(key, {});
          byKey.get(key)[cs.look] = rows[i].hashOrig;
        }
        let checked = 0;
        for (const [key, m] of byKey) {
          if (m[0] !== undefined && m[1] !== undefined) {
            checked++;
            if (m[0] === m[1]) return fail('look path vacuous (mode0 look0 vs look1 identical for same inputs)', { key });
          }
        }
        if (checked === 0) return fail('look-pair coverage missing (no controlled mode0 look0/look1 pairs)');
      }
      // Expected look-output transparency: look1 mode0 must show some transparency + varied alpha.
      if (!(look1CountedPixels > 0 && look1TransparentPixels > 0)) return fail('nonvacuity: look1 mode0 outputs lack expected transparency', { counted: look1CountedPixels, transparent: look1TransparentPixels });
      if (!((look1AMax - look1AMin) > 10)) return fail('nonvacuity: look1 mixed alpha never varied (exact alpha bytes unproven)', { aMin: look1AMin, aMax: look1AMax });
      // Separate positive control: must differ for mode1 AND for relief!=0 (NONFLAT paper).
      {
        const mode1diff = controlRows.some((r, i) => cases[i].mode === 1 && r.detected);
        const reliefDiff = controlRows.some((r, i) => cases[i].relief !== 0 && cases[i].mode !== 1 && r.detected);
        if (controlSupported === 0) return fail('positive control has no supported case (need mode1 or relief!=0)');
        if (!mode1diff || !reliefDiff) return fail('positive control missed (separate mutated shader must change RGBA where supported: need mode1 differ + relief!=0 differ; paper flat or fixture too thick?)', { controlSupported, controlDiffCases });
        if (controlDiffCases === 0) return fail('positive control never detected');
      }
      // Optional GPU timer: WebGL2 core API only (NOT WebGL1 EXT API), disjoint-valid only.
      let gpuTimer = { present: false, status: 'skipped: EXT_disjoint_timer_query_webgl2 not present' };
      const ext = gl.getExtension('EXT_disjoint_timer_query_webgl2');
      if (ext) {
        const hasCore = (typeof gl.createQuery === 'function' && typeof gl.beginQuery === 'function' && typeof gl.endQuery === 'function' && typeof gl.getQueryParameter === 'function' && typeof gl.deleteQuery === 'function');
        if (!hasCore) {
          gpuTimer = { present: true, status: 'skipped: WebGL2 core query API missing (WebGL1 EXT API not used in WebGL2)' };
        } else {
          gpuTimer = { present: true, status: 'skipped: present, parity needs no timing' };
          try {
            const q = gl.createQuery();
            gl.beginQuery(ext.TIME_ELAPSED_EXT, q);
            setAll(L0, pO, cases[0], gpuFix[cases[0].f]); gl.drawArrays(gl.TRIANGLES, 0, 3);
            gl.endQuery(ext.TIME_ELAPSED_EXT);
            let spins = 0;
            while (!gl.getQueryParameter(q, gl.QUERY_RESULT_AVAILABLE) && spins++ < 200) await new Promise(r => setTimeout(r, 5));
            if (gl.getQueryParameter(q, gl.QUERY_RESULT_AVAILABLE)) {
              const disjoint = gl.getParameter(ext.GPU_DISJOINT_EXT);
              if (!disjoint) gpuTimer = { present: true, status: 'measured', ns: gl.getQueryParameter(q, gl.QUERY_RESULT) };
              else gpuTimer = { present: true, status: 'skipped: disjoint true (invalid)' };
            } else gpuTimer = { present: true, status: 'skipped: query not available' };
            gl.deleteQuery(q);
          } catch (e) { gpuTimer = { present: true, status: 'skipped: ' + String(e && e.message || e).slice(0, 120) }; }
        }
      }
      return { rows, controlRows, probes, cases: cases.length, maxDiff, mismatchBytes, mismatchCases, controlSupported, controlDiffCases, distinctHashes: hashes.size, paperMean: +paper.mean.toFixed(4), paperHRange: +(paper.mx - paper.mn).toFixed(4), paperRawHash, look1AMin, look1AMax, look1TransparentPixels, glError: gl.getError(), gpuTimer, cpuSubmitMs, cpuSubmitNote: 'excludes readPixels/GPU wait' };
    }, { vert: vertN, orig: origExpanded, cand: candExpanded, ctrl: ctrlExpanded }).catch(e => ({ error: String(e) }));
    if (out.error) {
      console.log(JSON.stringify({ pass: false, ...prov, error: out.error }));
      process.exitCode = 1;
      return;
    }
    if (out.failAt !== undefined) {
      console.log(JSON.stringify({ pass: false, ...prov, probes: out.probes, cases: out.rows.length, maxDiff: out.maxDiff, mismatchBytes: out.mismatchBytes, mismatchCases: out.mismatchCases, controlSupported: out.controlSupported, controlDiffCases: out.controlDiffCases, note: out.note, rows: out.rows, controlRows: out.controlRows }));
      process.exitCode = 1;
      return;
    }
    // Pass requires: zero candidate mismatches + detected separate positive control + nonvacuity.
    const nonvacuity = out.distinctHashes >= 4 && out.look1TransparentPixels > 0 && ((out.look1AMax - out.look1AMin) > 10);
    const controlOk = out.controlSupported > 0 && out.controlDiffCases > 0 && out.controlRows.some(r => r.supported && r.detected);
    const pass = out.glError === 0 && out.mismatchBytes === 0 && out.mismatchCases === 0 && out.rows.every(r => r.ok) && controlOk && nonvacuity;
    console.log(JSON.stringify({ pass, ...prov, cases: out.cases, probes: out.probes, maxDiff: out.maxDiff, mismatchBytes: out.mismatchBytes, mismatchCases: out.mismatchCases, controlSupported: out.controlSupported, controlDiffCases: out.controlDiffCases, distinctHashes: out.distinctHashes, paperMean: out.paperMean, paperHRange: out.paperHRange, paperRawHash: out.paperRawHash, look1AMin: out.look1AMin, look1AMax: out.look1AMax, look1TransparentPixels: out.look1TransparentPixels, glError: out.glError, gpuTimer: out.gpuTimer, cpuSubmitMs: out.cpuSubmitMs, cpuSubmitNote: out.cpuSubmitNote, rows: out.rows.map(r => ({ case: r.case, fixture: r.fixture, mode: r.mode, relief: r.relief, look: r.look, expectSame: r.expectSame, bytesDiffer: r.bytesDiffer, maxByteDiff: r.maxByteDiff, hashOrig: r.hashOrig, hashCand: r.hashCand })), controlRows: out.controlRows.map(r => ({ case: r.case, supported: r.supported, bytesDiffer: r.bytesDiffer, maxByteDiff: r.maxByteDiff, hashCtrl: r.hashCtrl, detected: r.detected })) }));
    if (!pass) process.exitCode = 1;
  } finally {
    await browser.close().catch(() => {});
  }
})().catch(e => { console.error(String(e)); process.exit(1); });
