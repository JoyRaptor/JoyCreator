// joybrush/tools/media_dry_apply_parity_check.js — dry apply/copy parity for the JB_PAPER_ONLY optimisation.
//
// Compares THREE pipelines using ACTUAL shader files loaded from two roots at runtime:
//   A) baseline (original 3c81) apply+copy default 3 attachments,
//   B) candidate apply+copy default 3 attachments,
//   C) candidate apply+copy PAPER_ONLY (paper location 0, single attachment, existing paper textures, p0/p1 untouched).
// ALL actual paper raw Float32 bits AND p0/p1 raw Float32 bits after apply+copy must equal baseline (exact 0 mismatch).
// Candidate p0/p1 must also equal initial physical float state. Whole textures compared including outside partial rect
// (next-state scratch p0/p1 allowed stale for PAPER_ONLY since it skips writes; final ACTIVE cur compared fully,
// next PAPER compared as appropriate).
//
// coverage='apply-copy-only synthetic-delta' — SYNTHETIC deltas (physically valid fixture values, nonzero
// pigment/deposit/crush/smear, never zero-activity except empty) drive ACTUAL apply+copy passes. NEVER claims
// dab/window/Undo/lifecycle proven. Full jb_dry_dab.vert/frag packed-20-float path NOT exercised here.
//
// Real path: ACTUAL jb_media_quad.vert (TRIANGLE_STRIP 0,4, same rect/origin/scale as MediaLayerEngine) +
// actual expanded apply/copy frags with MediaProgram.withDefines injection replicated exactly.
// RGBA32F state NEAREST; delta RGBA16F with correct IEEE half bits uploaded as HALF_FLOAT (actual GL conversion,
// not fake half). Distinct seeded cur/nxt (especially scratch outside), two alias-free passes, same quad/origin/
// scale/common uniforms/rect, no buffer swaps. Write/read textures asserted disjoint before draw.
// Raw float readPixels RGBA/FLOAT via Float32Array then Uint32 bitview; -0/+0 distinguished (bit-exact);
// finite physical nonnegative inputs only (positive p0/p1 fixtures + explicit zero empty); no NaN/inf.
// Exact=0 mismatch, counts + first coordinates/values/bits reported.
//
// Render oracle: FINAL active state rendered through ACTUAL media_render.frag/screen.vert with ACTUAL canonical
// paper (seeded nonflat, existing render-checker pattern), both u_look 0+1, R G B AND ALPHA exact RGBA8.
// Supported modes 0/1/8, relief 0/nonzero sufficient pairs. Float proof never collapsed into RGBA8.
// Render chkRender mirrors media_render_parity_check.js actual need/types/bindings (required samplers
// p0/p1/paperState/w0/w1/paperBake/paperSurface + look/mode/relief/pan/zoom/layerOrigin/layerScale/
// paperRotatable BOOL etc; fluid/percentile optionals only where actual source leaves them optimizable;
// runtime mode/relief must stay active); bindings 0..7 verified per draw, null locations fail (no
// uniform(null) silent wrong-both-pass). ALL look0 alphas must be 255 (both-wrong-transparent fails);
// relief0-vs-nonzero (mode0) + mode0-vs-1/8 activity plus mode0-only look-pair nonvacuity required;
// look1 mode0 varied-transparency + broken mode0 colour detection retained. Minimal varied
// origin/scale/pan/zoom views prove render transform wiring. Provenance carries raw+expanded
// render/screen/common/paper hashes per root + active uniform/binding evidence; default/paper-only
// proof counts retained. Actual uniform-missing/type errors can yield symmetric wrong renders without
// GLError, so exact equality alone is insufficient without these gates.
//
// Deliberately BROKEN paper-variant control mutates actual paper output by exactly ONE guarded replacement
// (add halved); ordinary candidate unmodified must PASS and broken variant must FAIL raw paper (and colour
// where expected) on actual nonzero supported fixtures. Zero supported/detected fails.
// Separate original/candidate-default/paper-variant/broken compilations. Broken derived in-memory only;
// no mutation in accepted candidate or production files. Strong oracle selfchecks guard exact-one mutation.
// Unavailable float FBO/readback is ERROR not pass. No timing/speed claims; no measurements (prefer none).
// Deterministically seeded, bounded 32x32 grid. Cleanup browser finally. Concise JSON, nonzero exit on any
// error/mismatch/control failure; async errors cannot exit 0. Browser GL errors fatal; provenance/renderer/version captured.
//
// Run (coordinator executes; bundled playwright-core + Edge/SwiftShader, no npm install):
//   node joybrush/tools/media_dry_apply_parity_check.js --baseline-dir <originalJoybrushRoot> --candidate-dir <candidateJoybrushRoot> [--browser <exe>] [--out <json>]
// Root variants (documented): each --*-dir accepts either the REPO root (containing joybrush/shaders/...) or the
// JOYBRUSH root itself (containing shaders/...). Aliases: --baseline-root/--candidate-root/--baseline/--candidate/
// --orig-dir/--base-dir (baseline) and --cand-dir (candidate). --browser alias --chrome. Env: BASELINE_DIR,
// CANDIDATE_DIR, BROWSER, CHROME. --help prints options. Unknown args fail. Coordinator supplies baseline dir
// and runs script+browser afterwards. Local clean WIP, source-only, no execution claim here.
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

function argVal(names) {
  for (const n of names) {
    const i = process.argv.indexOf('--' + n);
    if (i >= 0 && i + 1 < process.argv.length) return process.argv[i + 1];
  }
  return null;
}
if (process.argv.includes('--help') || process.argv.includes('-h')) {
  console.log(JSON.stringify({ usage: 'node media_dry_apply_parity_check.js --baseline-dir <originalJoybrushRoot|repoRoot> --candidate-dir <candidateJoybrushRoot|repoRoot> [--browser <exe>] [--out <json>]', aliases: { baseline: ['baseline-dir', 'baseline-root', 'baseline', 'orig-dir', 'base-dir'], candidate: ['candidate-dir', 'candidate-root', 'candidate', 'cand-dir'], browser: ['browser', 'chrome'], out: ['out'] }, env: ['BASELINE_DIR', 'CANDIDATE_DIR', 'BROWSER', 'CHROME'], coverage: 'apply-copy-only synthetic-delta (never dab/window/Undo/lifecycle)' }));
  process.exit(0);
}
const KNOWN = new Set(['baseline-dir', 'baseline-root', 'baseline', 'orig-dir', 'base-dir', 'candidate-dir', 'candidate-root', 'candidate', 'cand-dir', 'browser', 'chrome', 'out', 'help', 'h']);
for (const a of process.argv.slice(2)) {
  if (a.startsWith('--')) {
    const k = a.slice(2);
    if (!KNOWN.has(k)) {
      console.log(JSON.stringify({ error: 'unknown arg ' + a, known: [...KNOWN] }));
      process.exit(1);
    }
  }
}
function failEarly(reason, extra) {
  console.log(JSON.stringify(Object.assign({ pass: false, error: reason }, extra || {})));
  process.exit(1);
}
let baselineDirRaw = argVal(['baseline-dir', 'baseline-root', 'baseline', 'orig-dir', 'base-dir']) || process.env.BASELINE_DIR || null;
let candidateDirRaw = argVal(['candidate-dir', 'candidate-root', 'candidate', 'cand-dir']) || process.env.CANDIDATE_DIR || null;
const browserArg = argVal(['browser', 'chrome']) || process.env.BROWSER || process.env.CHROME || null;
const outPath = argVal(['out']) || null;
if (!baselineDirRaw) failEarly('missing --baseline-dir <original joybrush root> (or repo root containing joybrush/)');
if (!candidateDirRaw) failEarly('missing --candidate-dir <candidate joybrush root> (or repo root containing joybrush/)');
function resolveJoybrushRoot(raw, tag) {
  const abs = path.resolve(raw);
  if (!fs.existsSync(abs)) failEarly('missing ' + tag + ' dir', { dir: abs });
  const asRepo = path.join(abs, 'joybrush', 'shaders', 'media', 'jb_media_apply.frag');
  const asJb = path.join(abs, 'shaders', 'media', 'jb_media_apply.frag');
  if (fs.existsSync(asRepo)) return path.join(abs, 'joybrush');
  if (fs.existsSync(asJb)) return abs;
  failEarly(tag + ' must be repo root (joybrush/shaders/...) or joybrush root (shaders/...)', { dir: abs });
  return abs;
}
const BASE_JB = resolveJoybrushRoot(baselineDirRaw, 'baseline');
const CAND_JB = resolveJoybrushRoot(candidateDirRaw, 'candidate');
function reqFile(jbRoot, rel) {
  const p = path.join(jbRoot, rel);
  if (!fs.existsSync(p)) failEarly('missing file', { file: p, rel });
  return p;
}
const RELS = ['shaders/media/jb_media_apply.frag', 'shaders/media/jb_media_copy.frag', 'shaders/media/jb_media_quad.vert', 'shaders/media/jb_media_render.frag', 'shaders/media/jb_media_screen.vert', 'shaders/media/jb_media_common.glsl', 'shaders/jb_paper.glsl'];
const basePaths = {}, candPaths = {};
for (const r of RELS) { basePaths[r] = reqFile(BASE_JB, r); candPaths[r] = reqFile(CAND_JB, r); }

function sha256(s) { return crypto.createHash('sha256').update(s, 'utf8').digest('hex'); }
const STRICT_INCLUDE_RE = /^#include\s+"([^"]+)"$/;
function strictIncludeOf(t) { const m = t.match(STRICT_INCLUDE_RE); return m ? m[1] : null; }
function includesOf(t) { return t.split('\n').map(l => l.trim()).filter(l => STRICT_INCLUDE_RE.test(l)); }
function malformedIncludes(t) { return t.split('\n').map(l => l.trim()).filter(l => l.startsWith('#include') && !STRICT_INCLUDE_RE.test(l)); }
function expandFor(shaderDir, rel, seen) {
  if (seen.has(rel)) throw new Error('shader include cycle at ' + rel);
  seen.add(rel);
  const raw = fs.readFileSync(path.join(shaderDir, rel), 'utf8');
  const text = raw.replace(/\r\n/g, '\n').replace(/\r/g, '\n');
  const out = text.split('\n').map(l => {
    const inc = strictIncludeOf(l.trim());
    return inc ? expandFor(shaderDir, inc, seen) : l;
  }).join('\n');
  seen.delete(rel);
  return out;
}
function collectIncludes(shaderDir, rel, seen, acc) {
  if (seen.has(rel)) throw new Error('shader include cycle at ' + rel);
  seen.add(rel);
  const raw = fs.readFileSync(path.join(shaderDir, rel), 'utf8').replace(/\r\n/g, '\n').replace(/\r/g, '\n');
  for (const l of raw.split('\n')) {
    const inc = strictIncludeOf(l.trim());
    if (inc) { acc.push(inc); collectIncludes(shaderDir, inc, seen, acc); }
  }
  seen.delete(rel);
}
// EXACT replica of MediaProgram.withDefines (MediaGl.kt): empty unchanged; else validate single JB_PAPER_ONLY,
// require exactly one #version line, insert sorted "#define X 1" after it.
function withDefines(src, defines, label) {
  if (defines.size === 0) return src;
  const NAME = /^[A-Za-z_][A-Za-z0-9_]*$/;
  for (const d of defines) {
    if (!NAME.test(d)) throw new Error(label + ': bad define: ' + d);
    if (d !== 'JB_PAPER_ONLY') throw new Error(label + ': unsupported define: ' + d);
  }
  const lines = src.split('\n');
  const idx = lines.map((l, i) => (l.trimStart().startsWith('#version') ? i : -1)).filter(i => i >= 0);
  if (idx.length !== 1) throw new Error(label + ': expected exactly one #version line, found ' + idx.length);
  const insert = [...defines].sort().map(d => '#define ' + d + ' 1').join('\n');
  const out = lines.slice();
  out.splice(idx[0] + 1, 0, insert);
  return out.join('\n');
}
function readNorm(p) {
  const raw = fs.readFileSync(p, 'utf8');
  const hadCR = raw.includes('\r');
  const norm = raw.replace(/\r\n/g, '\n').replace(/\r/g, '\n');
  return { raw, hadCR, norm };
}
const bApply = readNorm(basePaths['shaders/media/jb_media_apply.frag']);
const cApply = readNorm(candPaths['shaders/media/jb_media_apply.frag']);
const bCopy = readNorm(basePaths['shaders/media/jb_media_copy.frag']);
const cCopy = readNorm(candPaths['shaders/media/jb_media_copy.frag']);
const bQuad = readNorm(basePaths['shaders/media/jb_media_quad.vert']);
const cQuad = readNorm(candPaths['shaders/media/jb_media_quad.vert']);
const bRender = readNorm(basePaths['shaders/media/jb_media_render.frag']);
const cRender = readNorm(candPaths['shaders/media/jb_media_render.frag']);
const bScreen = readNorm(basePaths['shaders/media/jb_media_screen.vert']);
const cScreen = readNorm(candPaths['shaders/media/jb_media_screen.vert']);
const bCommon = readNorm(basePaths['shaders/media/jb_media_common.glsl']);
const cCommon = readNorm(candPaths['shaders/media/jb_media_common.glsl']);
const bPaperInc = readNorm(basePaths['shaders/jb_paper.glsl']);
const cPaperInc = readNorm(candPaths['shaders/jb_paper.glsl']);
const eolNormalized = [bApply, cApply, bCopy, cCopy, bQuad, cQuad, bRender, cRender, bScreen, cScreen, bCommon, cCommon, bPaperInc, cPaperInc].some(o => o.norm !== o.raw);
function guardFail(reason, extra) {
  console.log(JSON.stringify(Object.assign({ pass: false, guard: 'fail', reason }, extra || {})));
  process.exit(1);
}
// ---- deposit-add spans (shared actual physics; candidate helper extracted from baseline inline) ----
const ORIG_ADD_SRC = 'float\\s+add\\s*=\\s*pile\\s*\\+\\s*min\\s*\\(\\s*dl\\.b\\s*,\\s*room\\s*-\\s*pile\\s*\\)\\s*;';
const BROKEN_ADD_SRC = 'float\\s+add\\s*=\\s*\\(\\s*pile\\s*\\+\\s*min\\s*\\(\\s*dl\\.b\\s*,\\s*room\\s*-\\s*pile\\s*\\)\\s*\\)\\s*\\*\\s*0\\.5\\s*;';
const origAddRe = new RegExp(ORIG_ADD_SRC, 'g');
const brokenAddRe = new RegExp(BROKEN_ADD_SRC, 'g');
const origAddSingle = new RegExp(ORIG_ADD_SRC);
const brokenAddSingle = new RegExp(BROKEN_ADD_SRC);
// ---- separated baseline-vs-candidate structural guards ----
// Baseline (original 3c81) has physics INLINE in main; helper ONLY candidate introduced.
// Do NOT assert candidate helper on baseline. No exact SHA pin hardcoded here (worker reads
// no external); coordinator may pin exact normalized SHA256 for baseline apply/copy from 3c81
// when available. Safer baseline guard checks actual original expected complete body.
function baselineApplyOK(t) {
  if (typeof t !== 'string') return false;
  if (t.includes('JB_PAPER_ONLY')) return false;
  if (t.includes('jb_media_apply_paper')) return false;
  if (malformedIncludes(t).length) return false;
  const need = ['void main', 'u_paperState', 'u_delta', 'u_targetSize', 'u_capMm', 'u_abrasion', 'u_flakeR', 'u_crushRate', 'u_crushMax', 'u_smear', 'u_smearPx', 'texture(u_p0', 'texture(u_p1', 'texture(u_paperState', 'texture(u_delta', 'out vec4 o_p0', 'out vec4 o_p1', 'out vec4 o_paper', 'o_p0', 'o_p1', 'o_paper', 'exp(', 'jb_bilinear'];
  for (const s of need) if (!t.includes(s)) return false;
  const oA = t.match(origAddRe) || [];
  const oB = t.match(brokenAddRe) || [];
  if (oA.length !== 1 || oB.length !== 0) return false;
  if (!origAddSingle.test(t)) return false;
  return true;
}
function candidateApplyOK(t) {
  if (typeof t !== 'string') return false;
  if (!t.includes('JB_PAPER_ONLY')) return false;
  if (!t.includes('jb_media_apply_paper')) return false;
  if (!t.includes('vec4 jb_media_apply_paper(')) return false;
  if (!t.includes('#ifdef JB_PAPER_ONLY')) return false;
  if (!t.includes('#ifndef JB_PAPER_ONLY')) return false;
  if (malformedIncludes(t).length) return false;
  const need = ['void main', 'u_paperState', 'u_delta', 'u_targetSize', 'u_capMm', 'u_abrasion', 'u_flakeR', 'u_crushRate', 'u_crushMax', 'u_smear', 'u_smearPx', 'texture(u_paperState', 'texture(u_delta', 'out vec4 o_p0', 'out vec4 o_paper', 'o_p0', 'o_p1', 'o_paper', 'u_p0', 'u_p1', 'exp(', 'jb_bilinear'];
  for (const s of need) if (!t.includes(s)) return false;
  const oA = t.match(origAddRe) || [];
  const oB = t.match(brokenAddRe) || [];
  if (oA.length !== 1 || oB.length !== 0) return false;
  if ((t.match(/jb_media_apply_paper/g) || []).length < 2) return false;
  return true;
}
function baselineCopyOK(t) {
  if (typeof t !== 'string') return false;
  if (t.includes('JB_PAPER_ONLY')) return false;
  if (malformedIncludes(t).length) return false;
  const need = ['void main', 'uniform sampler2D u_a', 'uniform sampler2D u_b', 'uniform sampler2D u_c', 'out vec4 o_a', 'out vec4 o_b', 'out vec4 o_c', 'texture(u_a', 'texture(u_b', 'texture(u_c', 'u_targetSize'];
  for (const s of need) if (!t.includes(s)) return false;
  return true;
}
function candidateCopyOK(t) {
  if (typeof t !== 'string') return false;
  if (!t.includes('JB_PAPER_ONLY')) return false;
  if (!t.includes('#ifdef JB_PAPER_ONLY')) return false;
  if (!t.includes('#ifndef JB_PAPER_ONLY')) return false;
  if (malformedIncludes(t).length) return false;
  const need = ['void main', 'uniform sampler2D u_c', 'out vec4 o_c', 'texture(u_c', 'u_targetSize', 'u_a', 'u_b', 'o_a', 'o_b'];
  for (const s of need) if (!t.includes(s)) return false;
  return true;
}
// ---- unexpected-source guards: baseline pre-optimisation inline, candidate carries optimisation ----
(function validateRoots() {
  for (const [tag, o] of [['baseline-apply', bApply], ['baseline-copy', bCopy]]) {
    if (malformedIncludes(o.norm).length) guardFail('malformed #include in ' + tag, { bad: malformedIncludes(o.norm) });
    if (o.norm.includes('JB_PAPER_ONLY')) guardFail('baseline must be original without JB_PAPER_ONLY: ' + tag);
  }
  for (const [tag, o] of [['candidate-apply', cApply], ['candidate-copy', cCopy]]) {
    if (malformedIncludes(o.norm).length) guardFail('malformed #include in ' + tag, { bad: malformedIncludes(o.norm) });
    if (!o.norm.includes('JB_PAPER_ONLY')) guardFail('candidate must carry JB_PAPER_ONLY optimisation: ' + tag);
  }
  if (!baselineApplyOK(bApply.norm)) guardFail('baseline apply must be original inline-paper body/main/passthrough without helper (no mock apply; helper ONLY candidate)');
  if (!candidateApplyOK(cApply.norm)) guardFail('candidate apply must carry shared jb_media_apply_paper + JB_PAPER_ONLY conditional outputs with actual physics (no mock apply)');
  if (!baselineCopyOK(bCopy.norm)) guardFail('baseline copy must be original passthrough without JB_PAPER_ONLY (no mock copy)');
  if (!candidateCopyOK(cCopy.norm)) guardFail('candidate copy must carry JB_PAPER_ONLY conditional copy (no mock copy)');
  if (!bApply.norm.includes('u_delta') || !cApply.norm.includes('u_delta')) guardFail('apply must sample u_delta (no mock delta path)');
  if (!bCopy.norm.includes('u_c') || !cCopy.norm.includes('u_c')) guardFail('copy must sample u_c (no mock copy)');
  if (!bQuad.norm.includes('u_rect') || !cQuad.norm.includes('u_rect')) guardFail('quad must use u_rect (actual quad conventions required)');
  if (!bRender.norm.includes('jb_paperSurface') || !cRender.norm.includes('jb_paperSurface')) guardFail('render must use actual paper (no mock paper)');
  if (malformedIncludes(bQuad.norm).length || malformedIncludes(cQuad.norm).length || malformedIncludes(bRender.norm).length || malformedIncludes(cRender.norm).length) guardFail('malformed #include in quad/render');
  if (malformedIncludes(bCommon.norm).length || malformedIncludes(cCommon.norm).length || malformedIncludes(bPaperInc.norm).length || malformedIncludes(cPaperInc.norm).length) guardFail('malformed #include in common/paper');
  if (!bCommon.norm.includes('jb_bilinear') || !cCommon.norm.includes('jb_bilinear')) guardFail('common must stay actual (jb_bilinear required)');
  if (!bPaperInc.norm.includes('jb_paperSurface') || !cPaperInc.norm.includes('jb_paperSurface')) guardFail('paper include must stay actual (jb_paperSurface required)');
  const bInc = includesOf(bApply.norm), cInc = includesOf(cApply.norm);
  if (!bInc.includes('#include "media/jb_media_common.glsl"') || !cInc.includes('#include "media/jb_media_common.glsl"')) guardFail('apply includes must stay actual media_common', { bInc, cInc });
})();
(function runSourceGuardSelfChecks() {
  if (baselineApplyOK(cApply.norm)) guardFail('source-guard selfcheck failed: candidate apply must be refused as baseline');
  if (!candidateApplyOK(cApply.norm)) guardFail('source-guard selfcheck failed: candidate apply must be accepted as candidate');
  if (baselineCopyOK(cCopy.norm)) guardFail('source-guard selfcheck failed: candidate copy must be refused as baseline');
  if (!candidateCopyOK(cCopy.norm)) guardFail('source-guard selfcheck failed: candidate copy must be accepted as candidate');
  const mockApply = '#version 300 es\nvoid main(){ gl_FragColor = vec4(0.0); }\n';
  if (baselineApplyOK(mockApply)) guardFail('source-guard selfcheck failed: mocked baseline apply accepted');
  if (candidateApplyOK(mockApply)) guardFail('source-guard selfcheck failed: mocked candidate apply accepted');
  const mockCopy = '#version 300 es\nvoid main(){ }\n';
  if (baselineCopyOK(mockCopy)) guardFail('source-guard selfcheck failed: mocked baseline copy accepted');
  if (candidateCopyOK(mockCopy)) guardFail('source-guard selfcheck failed: mocked candidate copy accepted');
  const synthBaselineApply = ['#version 300 es', 'uniform sampler2D u_p0; uniform sampler2D u_p1; uniform sampler2D u_paperState; uniform sampler2D u_delta;', 'uniform vec2 u_targetSize; uniform float u_capMm; uniform float u_abrasion; uniform float u_flakeR; uniform float u_crushRate; uniform float u_crushMax; uniform float u_smear; uniform vec2 u_smearPx;', '#include "media/jb_media_common.glsl"', 'layout(location = 0) out vec4 o_p0; layout(location = 1) out vec4 o_p1; layout(location = 2) out vec4 o_paper;', 'void main(){ vec4 p0 = texture(u_p0, vec2(0.0)); vec4 p1 = texture(u_p1, vec2(0.0)); vec4 ps = texture(u_paperState, vec2(0.0)); vec4 dl = texture(u_delta, vec2(0.0));', 'float sw = clamp(dl.a * u_smear, 0.0, 0.3); vec2 sb = jb_bilinear(u_paperState, vec2(0.0)).gb;', 'float room = max(u_capMm - ps.g, 0.0); float pile = room * (1.0 - exp(-u_abrasion * dl.r)); float add = pile + min(dl.b, room - pile); ps.g += add; ps.b += add * u_flakeR; ps.r = min(u_crushMax, ps.r + u_crushRate * dl.g);', 'o_p0 = p0; o_p1 = p1; o_paper = ps; }'].join('\n');
  if (!baselineApplyOK(synthBaselineApply)) guardFail('source-guard selfcheck failed: synthetic baseline apply must be accepted');
  if (candidateApplyOK(synthBaselineApply)) guardFail('source-guard selfcheck failed: synthetic baseline must be refused as candidate (missing JB_PAPER_ONLY/helper)');
  if (baselineApplyOK(synthBaselineApply + '\nvec4 jb_media_apply_paper(vec4 x){ return x; }\n')) guardFail('source-guard selfcheck failed: helper-injected baseline must be refused as baseline');
  if (baselineApplyOK('#define JB_PAPER_ONLY 1\n' + synthBaselineApply)) guardFail('source-guard selfcheck failed: PAPER_ONLY-injected baseline must be refused as baseline');
  const noAddBase = synthBaselineApply.replace(origAddSingle, 'float add = 0.0;');
  if (baselineApplyOK(noAddBase)) guardFail('source-guard selfcheck failed: physics-removed baseline must be refused');
  const candNoPhys = cApply.norm.replace(origAddSingle, 'float add = 0.0;');
  if (candidateApplyOK(candNoPhys)) guardFail('source-guard selfcheck failed: physics-removed candidate must be refused');
  if (baselineApplyOK(candNoPhys)) guardFail('source-guard selfcheck failed: physics-removed candidate must also be refused as baseline');
  const synthBaselineCopy = '#version 300 es\nuniform sampler2D u_a; uniform sampler2D u_b; uniform sampler2D u_c; uniform vec2 u_targetSize; layout(location = 0) out vec4 o_a; layout(location = 1) out vec4 o_b; layout(location = 2) out vec4 o_c; void main(){ o_a = texture(u_a, vec2(0.0)); o_b = texture(u_b, vec2(0.0)); o_c = texture(u_c, vec2(0.0)); }\n';
  if (!baselineCopyOK(synthBaselineCopy)) guardFail('source-guard selfcheck failed: synthetic baseline copy must be accepted');
  if (candidateCopyOK(synthBaselineCopy)) guardFail('source-guard selfcheck failed: synthetic baseline copy must be refused as candidate');
})();
function validateBrokenGuard(origNorm, brokenNorm) {
  if (malformedIncludes(origNorm).length || malformedIncludes(brokenNorm).length) return { ok: false, reason: 'malformed #include in broken guard' };
  if (includesOf(origNorm).join('|') !== includesOf(brokenNorm).join('|')) return { ok: false, reason: 'broken includes must stay actual' };
  const oA = origNorm.match(origAddRe) || [], oB = origNorm.match(brokenAddRe) || [];
  if (oA.length !== 1 || oB.length !== 0) return { ok: false, reason: 'orig must hold exactly one deposit-add (no 0/multiple)', oA: oA.length, oB: oB.length };
  const bA = brokenNorm.match(origAddRe) || [], bB = brokenNorm.match(brokenAddRe) || [];
  if (bA.length !== 0 || bB.length !== 1) return { ok: false, reason: 'broken must hold only halved add (exactly one expected replacement)', origAdds: bA.length, brokenAdds: bB.length };
  const oM = origAddSingle.exec(origNorm), bM = brokenAddSingle.exec(brokenNorm);
  if (!oM || !bM) return { ok: false, reason: 'guard span not located' };
  const oIdx = oM.index, bIdx = bM.index;
  if (origNorm.slice(0, oIdx) !== brokenNorm.slice(0, bIdx) || origNorm.slice(oIdx + oM[0].length) !== brokenNorm.slice(bIdx + bM[0].length)) return { ok: false, reason: 'unrelated bytes outside single substitution (byte-exact elsewhere required)' };
  return { ok: true };
}
(function runBrokenSelfChecks() {
  const mk = (block) => {
    const m = origAddSingle.exec(cApply.norm);
    if (!m) guardFail('selfcheck span missing');
    return cApply.norm.slice(0, m.index) + block + cApply.norm.slice(m.index + m[0].length);
  };
  const good = 'float add = (pile + min(dl.b, room - pile)) * 0.5;';
  const cases = [
    { n: 'accept-exact', c: mk(good), w: true },
    { n: 'reject-zero-orig', c: cApply.norm, w: false },
    { n: 'reject-multiple', c: mk(good).replace(brokenAddSingle, (m) => m + '\n    ' + good), w: false },
    { n: 'reject-unrelated-trailing', c: mk(good) + '\n// trailing\n', w: false },
    { n: 'reject-unrelated-prefix', c: '// prefix\n' + mk(good), w: false },
    { n: 'reject-gap-code', c: mk(good).replace(brokenAddSingle, (m) => m + '\n    float __probe = 1.0;'), w: false },
  ];
  for (const t of cases) {
    const r = validateBrokenGuard(cApply.norm, t.c);
    if (r.ok !== t.w) guardFail('broken guard selfcheck failed: ' + t.n + ' expected ' + t.w + ' got ' + r.ok + ' (' + (r.reason || 'ok') + ')');
  }
})();
const brokenApplyNorm = cApply.norm.replace(origAddSingle, 'float add = (pile + min(dl.b, room - pile)) * 0.5;');
{
  const r = validateBrokenGuard(cApply.norm, brokenApplyNorm);
  if (!r.ok) guardFail(r.reason);
  if (brokenApplyNorm === cApply.norm) guardFail('broken derivation empty');
}
let expanded = {};
try {
  expanded.bApply = expandFor(path.join(BASE_JB, 'shaders'), 'media/jb_media_apply.frag', new Set());
  expanded.cApply = expandFor(path.join(CAND_JB, 'shaders'), 'media/jb_media_apply.frag', new Set());
  expanded.xApply = expandFor(path.join(CAND_JB, 'shaders'), 'media/jb_media_apply.frag', new Set());
  expanded.bCopy = expandFor(path.join(BASE_JB, 'shaders'), 'media/jb_media_copy.frag', new Set());
  expanded.cCopy = expandFor(path.join(CAND_JB, 'shaders'), 'media/jb_media_copy.frag', new Set());
  expanded.bQuad = expandFor(path.join(BASE_JB, 'shaders'), 'media/jb_media_quad.vert', new Set());
  expanded.cQuad = expandFor(path.join(CAND_JB, 'shaders'), 'media/jb_media_quad.vert', new Set());
  expanded.bRender = expandFor(path.join(BASE_JB, 'shaders'), 'media/jb_media_render.frag', new Set());
  expanded.cRender = expandFor(path.join(CAND_JB, 'shaders'), 'media/jb_media_render.frag', new Set());
  expanded.bScreen = expandFor(path.join(BASE_JB, 'shaders'), 'media/jb_media_screen.vert', new Set());
  expanded.cScreen = expandFor(path.join(CAND_JB, 'shaders'), 'media/jb_media_screen.vert', new Set());
} catch (e) { guardFail('include expansion failed (actual includes required): ' + e.message); }
// broken expanded: same as candidate expanded but with single halved-add applied post-expansion (same span, still exactly one)
let brokenExpanded;
{
  const m = origAddSingle.exec(expanded.cApply);
  if (!m) guardFail('broken span missing in expanded candidate apply');
  const ms = [...expanded.cApply.matchAll(origAddRe)];
  if (ms.length !== 1) guardFail('expanded candidate apply must hold exactly one deposit-add', { count: ms.length });
  brokenExpanded = expanded.cApply.replace(origAddSingle, 'float add = (pile + min(dl.b, room - pile)) * 0.5;');
  const r = validateBrokenGuard(expanded.cApply, brokenExpanded);
  if (!r.ok) guardFail('broken expanded guard: ' + r.reason);
  if (brokenExpanded === expanded.cApply) guardFail('broken expanded identical (not mutated)');
}
let injected = {};
try {
  const E = new Set(), P = new Set(['JB_PAPER_ONLY']);
  injected.bApplyDef = withDefines(expanded.bApply, E, 'baseline apply default');
  injected.cApplyDef = withDefines(expanded.cApply, E, 'candidate apply default');
  injected.cApplyPaper = withDefines(expanded.cApply, P, 'candidate apply paper');
  injected.xApplyPaper = withDefines(brokenExpanded, P, 'broken apply paper');
  injected.xApplyDef = withDefines(brokenExpanded, E, 'broken apply default');
  injected.bCopyDef = withDefines(expanded.bCopy, E, 'baseline copy default');
  injected.cCopyDef = withDefines(expanded.cCopy, E, 'candidate copy default');
  injected.cCopyPaper = withDefines(expanded.cCopy, P, 'candidate copy paper');
  injected.bQuadDef = withDefines(expanded.bQuad, E, 'baseline quad default');
  injected.cQuadDef = withDefines(expanded.cQuad, E, 'candidate quad default');
  injected.cQuadPaper = withDefines(expanded.cQuad, P, 'candidate quad paper');
  injected.xQuadPaper = withDefines(expanded.cQuad, P, 'broken quad paper');
  injected.bRender = withDefines(expanded.bRender, E, 'render');
  injected.cRender = withDefines(expanded.cRender, E, 'candidate render');
  injected.bScreen = withDefines(expanded.bScreen, E, 'screen');
  injected.cScreen = withDefines(expanded.cScreen, E, 'candidate screen');
  // withDefines selfcheck: empty unchanged, PAPER_ONLY inserted after exactly one #version
  {
    const s = '#version 300 es\nvoid main(){}\n';
    if (withDefines(s, E, 'self') !== s) guardFail('withDefines empty must leave unchanged');
    const got = withDefines(s, P, 'self');
    if (got !== '#version 300 es\n#define JB_PAPER_ONLY 1\nvoid main(){}\n') guardFail('withDefines PAPER_ONLY injection mismatch', { got: got.slice(0, 120) });
    let threw = false;
    try { withDefines(s, new Set(['EVIL']), 'self'); } catch (e) { threw = true; }
    if (!threw) guardFail('withDefines must reject unsupported define');
  }
} catch (e) { guardFail('define injection failed (must EXACT match MediaProgram.withDefines): ' + e.message); }
const incAcc = {};
for (const [tag, jb, rel] of [['bApply', BASE_JB, 'media/jb_media_apply.frag'], ['cApply', CAND_JB, 'media/jb_media_apply.frag'], ['bCopy', BASE_JB, 'media/jb_media_copy.frag'], ['cCopy', CAND_JB, 'media/jb_media_copy.frag'], ['bQuad', BASE_JB, 'media/jb_media_quad.vert'], ['cQuad', CAND_JB, 'media/jb_media_quad.vert'], ['bRender', BASE_JB, 'media/jb_media_render.frag'], ['cRender', CAND_JB, 'media/jb_media_render.frag'], ['bScreen', BASE_JB, 'media/jb_media_screen.vert'], ['cScreen', CAND_JB, 'media/jb_media_screen.vert']]) {
  const a = []; collectIncludes(path.join(jb, 'shaders'), rel, new Set(), a); incAcc[tag] = a;
}
const prov = {
  coverage: 'apply-copy-only synthetic-delta',
  baselineDir: BASE_JB, candidateDir: CAND_JB,
  baselineApply: basePaths['shaders/media/jb_media_apply.frag'], candidateApply: candPaths['shaders/media/jb_media_apply.frag'],
  baselineRender: basePaths['shaders/media/jb_media_render.frag'], candidateRender: candPaths['shaders/media/jb_media_render.frag'],
  baselineScreen: basePaths['shaders/media/jb_media_screen.vert'], candidateScreen: candPaths['shaders/media/jb_media_screen.vert'],
  baselineCommon: basePaths['shaders/media/jb_media_common.glsl'], candidateCommon: candPaths['shaders/media/jb_media_common.glsl'],
  baselinePaperInc: basePaths['shaders/jb_paper.glsl'], candidatePaperInc: candPaths['shaders/jb_paper.glsl'],
  eolNormalized,
  baselineApplySHA256: sha256(bApply.raw), candidateApplySHA256: sha256(cApply.raw),
  baselineCopySHA256: sha256(bCopy.raw), candidateCopySHA256: sha256(cCopy.raw),
  baselineQuadSHA256: sha256(bQuad.raw), candidateQuadSHA256: sha256(cQuad.raw),
  baselineRenderSHA256: sha256(bRender.raw), candidateRenderSHA256: sha256(cRender.raw),
  baselineScreenSHA256: sha256(bScreen.raw), candidateScreenSHA256: sha256(cScreen.raw),
  baselineCommonSHA256: sha256(bCommon.raw), candidateCommonSHA256: sha256(cCommon.raw),
  baselinePaperIncSHA256: sha256(bPaperInc.raw), candidatePaperIncSHA256: sha256(cPaperInc.raw),
  baselineApplyNormSHA256: sha256(bApply.norm), candidateApplyNormSHA256: sha256(cApply.norm),
  baselineCopyNormSHA256: sha256(bCopy.norm), candidateCopyNormSHA256: sha256(cCopy.norm),
  baselineQuadNormSHA256: sha256(bQuad.norm), candidateQuadNormSHA256: sha256(cQuad.norm),
  baselineRenderNormSHA256: sha256(bRender.norm), candidateRenderNormSHA256: sha256(cRender.norm),
  baselineScreenNormSHA256: sha256(bScreen.norm), candidateScreenNormSHA256: sha256(cScreen.norm),
  baselineCommonNormSHA256: sha256(bCommon.norm), candidateCommonNormSHA256: sha256(cCommon.norm),
  baselinePaperIncNormSHA256: sha256(bPaperInc.norm), candidatePaperIncNormSHA256: sha256(cPaperInc.norm),
  brokenApplyNormSHA256: sha256(brokenApplyNorm),
  baselineApplyExpandedSHA256: sha256(expanded.bApply), candidateApplyExpandedSHA256: sha256(expanded.cApply),
  brokenApplyExpandedSHA256: sha256(brokenExpanded),
  baselineCopyExpandedSHA256: sha256(expanded.bCopy), candidateCopyExpandedSHA256: sha256(expanded.cCopy),
  baselineQuadExpandedSHA256: sha256(expanded.bQuad), candidateQuadExpandedSHA256: sha256(expanded.cQuad),
  baselineRenderExpandedSHA256: sha256(expanded.bRender), candidateRenderExpandedSHA256: sha256(expanded.cRender),
  baselineScreenExpandedSHA256: sha256(expanded.bScreen), candidateScreenExpandedSHA256: sha256(expanded.cScreen),
  injectedSHA256: { bApplyDef: sha256(injected.bApplyDef), cApplyDef: sha256(injected.cApplyDef), cApplyPaper: sha256(injected.cApplyPaper), xApplyPaper: sha256(injected.xApplyPaper), xApplyDef: sha256(injected.xApplyDef), bCopyDef: sha256(injected.bCopyDef), cCopyDef: sha256(injected.cCopyDef), cCopyPaper: sha256(injected.cCopyPaper), bQuadDef: sha256(injected.bQuadDef), cQuadDef: sha256(injected.cQuadDef), cQuadPaper: sha256(injected.cQuadPaper), xQuadPaper: sha256(injected.xQuadPaper), bRender: sha256(injected.bRender), cRender: sha256(injected.cRender), bScreen: sha256(injected.bScreen), cScreen: sha256(injected.cScreen) },
  includes: incAcc,
  note: 'synthetic-delta only; dab/window/Undo/lifecycle NOT proven',
};
function findExe() {
  if (browserArg && fs.existsSync(browserArg)) return browserArg;
  const edge = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';
  if (fs.existsSync(edge)) return edge;
  try {
    const ds = fs.readdirSync('/opt/pw-browsers').filter(d => d.startsWith('chromium-'));
    if (ds.length) return path.join('/opt/pw-browsers', ds[0], 'chrome-linux/chrome');
  } catch (e) { /* no fallback */ }
  return null;
}
(async () => {
  const exe = findExe();
  if (!exe) { console.log(JSON.stringify(Object.assign({ pass: false }, prov, { error: 'no browser: pass --browser <Edge/Chromium exe> or set BROWSER/CHROME' }))); process.exit(1); }
  const browser = await chromium.launch({ executablePath: exe, args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'] });
  let browserVersion = '';
  try { browserVersion = await browser.version(); } catch (e) { browserVersion = 'unknown:' + e.message; }
  try {
    const page = await browser.newPage();
    const errors = [];
    page.on('console', m => { if (m.type() === 'error') errors.push(m.text().slice(0, 300)); });
    page.on('pageerror', e => errors.push(String(e).slice(0, 300)));
    const out = await page.evaluate((P) => {
      const W = 32, H = 32, N = W * H;
      const fail = (error, extra) => Object.assign({ error }, extra || {});
      const c = document.createElement('canvas'); c.width = W; c.height = H;
      const gl = c.getContext('webgl2', { antialias: false, alpha: false });
      if (!gl) return fail('no webgl2');
      if (!gl.getExtension('EXT_color_buffer_float')) return fail('no EXT_color_buffer_float (float FBO/readback unavailable: ERROR not pass)');
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
      let pBApp, pCAppD, pCAppP, pXAppP, pXAppD, pBCopy, pCCopyD, pCCopyP, pXCopyP, pRend;
      try {
        pBApp = link(P.bQuadDef, P.bApplyDef);
        pCAppD = link(P.cQuadDef, P.cApplyDef);
        pCAppP = link(P.cQuadPaper, P.cApplyPaper);
        pXAppP = link(P.xQuadPaper, P.xApplyPaper);
        pXAppD = link(P.cQuadDef, P.xApplyDef);
        pBCopy = link(P.bQuadDef, P.bCopyDef);
        pCCopyD = link(P.cQuadDef, P.cCopyDef);
        pCCopyP = link(P.cQuadPaper, P.cCopyPaper);
        pXCopyP = link(P.xQuadPaper, P.cCopyPaper);
        pRend = link(P.screen, P.render);
      } catch (e) { return fail('compile/link: ' + e.message); }
      const active = (p) => {
        const m = {}; const n = gl.getProgramParameter(p, gl.ACTIVE_UNIFORMS);
        for (let i = 0; i < n; i++) { const inf = gl.getActiveUniform(p, i); m[inf.name.replace(/\[0\]$/, '')] = inf.type; }
        return m;
      };
      // Required uniforms for apply/copy; paper-only must drop u_p0/u_p1 (apply) and u_a/u_b (copy).
      const chkApply = (tag, am, paperOnly) => {
        const errs = [];
        const needF = { u_paperState: gl.SAMPLER_2D, u_delta: gl.SAMPLER_2D, u_targetSize: gl.FLOAT_VEC2, u_capMm: gl.FLOAT, u_abrasion: gl.FLOAT, u_flakeR: gl.FLOAT, u_crushRate: gl.FLOAT, u_crushMax: gl.FLOAT, u_smear: gl.FLOAT, u_smearPx: gl.FLOAT_VEC2, u_rect: gl.FLOAT_VEC4, u_layerOrigin: gl.FLOAT_VEC2, u_layerScale: gl.FLOAT };
        for (const k of ['u_p0', 'u_p1']) {
          if (!paperOnly) { if (am[k] === undefined) errs.push(tag + ' missing ' + k); else if (am[k] !== gl.SAMPLER_2D) errs.push(tag + ' type ' + k); }
          else if (am[k] !== undefined) errs.push(tag + ' paper-only must drop ' + k);
        }
        for (const k of Object.keys(needF)) { if (am[k] === undefined) errs.push(tag + ' missing ' + k); else if (am[k] !== needF[k]) errs.push(tag + ' type ' + k); }
        return errs;
      };
      const chkCopy = (tag, am, paperOnly) => {
        const errs = [];
        for (const k of ['u_a', 'u_b']) {
          if (!paperOnly) { if (am[k] === undefined) errs.push(tag + ' missing ' + k); else if (am[k] !== gl.SAMPLER_2D) errs.push(tag + ' type ' + k); }
          else if (am[k] !== undefined) errs.push(tag + ' paper-only must drop ' + k);
        }
        if (am.u_c === undefined || am.u_c !== gl.SAMPLER_2D) errs.push(tag + ' u_c sampler');
        if (am.u_targetSize === undefined || am.u_targetSize !== gl.FLOAT_VEC2) errs.push(tag + ' missing/type u_targetSize');
        if (am.u_rect === undefined || am.u_rect !== gl.FLOAT_VEC4) errs.push(tag + ' missing/type u_rect');
        if (am.u_layerOrigin === undefined || am.u_layerOrigin !== gl.FLOAT_VEC2) errs.push(tag + ' missing/type u_layerOrigin');
        if (am.u_layerScale === undefined || am.u_layerScale !== gl.FLOAT) errs.push(tag + ' missing/type u_layerScale');
        return errs;
      };
      // Render oracle MUST mirror media_render_parity_check.js actual need/types/bindings.
      // Actual jb_media_render.frag (+jb_paper.glsl) expected-active reference: required samplers
      // p0/p1/paperState/w0/w1/paperBake/paperSurface + required look/mode/relief/pan/zoom/
      // layerOrigin/layerScale/paperRotatable(BOOL) etc per actual source. u_paperFluid + paper
      // optionals (u_paperHBot/u_paperHTop/u_paperFluid*) may be optimized out when statically
      // unused (actual conditional `u_relief != 0.0 || u_mode == 1` keeps paperSurface active, but
      // never forces fluid/percentiles active); where still active their types must match. Runtime
      // uniforms u_mode/u_relief MUST stay active (symmetric wrong renders without GLError otherwise).
      const chkRender = (tag, am) => {
        const errs = [];
        const need = { u_p0: gl.SAMPLER_2D, u_p1: gl.SAMPLER_2D, u_paperState: gl.SAMPLER_2D, u_w0: gl.SAMPLER_2D, u_w1: gl.SAMPLER_2D, u_paperBake: gl.SAMPLER_2D, u_paperSurface: gl.SAMPLER_2D, u_targetSize: gl.FLOAT_VEC2, u_layerOrigin: gl.FLOAT_VEC2, u_layerScale: gl.FLOAT, u_pan: gl.FLOAT_VEC2, u_zoom: gl.FLOAT, u_pxPerMm: gl.FLOAT, u_toothMm: gl.FLOAT, u_paperColor: gl.FLOAT_VEC3, u_lamp: gl.FLOAT_VEC3, u_relief: gl.FLOAT, u_sheen: gl.FLOAT, u_capMm: gl.FLOAT, u_mode: gl.INT, u_impasto: gl.FLOAT, u_look: gl.FLOAT, u_paperTexelPx: gl.FLOAT, u_paperSize: gl.FLOAT, u_paperHexTexels: gl.FLOAT, u_paperSlopeRange: gl.FLOAT, u_paperHeightMean: gl.FLOAT };
        for (const k of Object.keys(need)) { if (am[k] === undefined) errs.push(tag + ' missing ' + k); else if (am[k] !== need[k]) errs.push(tag + ' type ' + k); }
        if (am.u_paperRotatable === undefined) errs.push(tag + ' missing u_paperRotatable');
        else if (am.u_paperRotatable !== gl.BOOL) errs.push(tag + ' type u_paperRotatable (want BOOL)');
        for (const k of ['u_paperHBot', 'u_paperHTop', 'u_paperFluidTexelPx', 'u_paperFluidSize', 'u_paperFluidHexTexels']) { if (am[k] !== undefined && am[k] !== gl.FLOAT) errs.push(tag + ' type ' + k); }
        if (am.u_paperFluid !== undefined && am.u_paperFluid !== gl.SAMPLER_2D) errs.push(tag + ' type u_paperFluid');
        if (am.u_mode === undefined) errs.push(tag + ' runtime u_mode must stay active');
        if (am.u_relief === undefined) errs.push(tag + ' runtime u_relief must stay active');
        return errs;
      };
      // Required ACTIVE sampler locations must exist (null = optimized out or misspelled = fail, never silent).
      const chkRenderLocations = (tag, prog, am) => {
        const errs = [];
        for (const nm of ['u_p0', 'u_p1', 'u_paperState', 'u_w0', 'u_w1', 'u_paperBake', 'u_paperSurface', 'u_targetSize', 'u_layerOrigin', 'u_layerScale', 'u_pan', 'u_zoom', 'u_pxPerMm', 'u_toothMm', 'u_paperColor', 'u_lamp', 'u_relief', 'u_sheen', 'u_capMm', 'u_mode', 'u_impasto', 'u_look', 'u_paperTexelPx', 'u_paperSize', 'u_paperHexTexels', 'u_paperSlopeRange', 'u_paperHeightMean', 'u_paperRotatable']) {
          if (am[nm] !== undefined && !gl.getUniformLocation(prog, nm)) errs.push(tag + ' missing location ' + nm);
        }
        for (const nm of ['u_paperHBot', 'u_paperHTop', 'u_paperFluidTexelPx', 'u_paperFluidSize', 'u_paperFluidHexTexels', 'u_paperFluid']) {
          if (am[nm] !== undefined && !gl.getUniformLocation(prog, nm)) errs.push(tag + ' missing location ' + nm + ' (active yet null)');
        }
        return errs;
      };
      let activeRenderEvidence = null, renderBindingEvidence = null;
      {
        const errs = [];
        errs.push(...chkApply('baseApply', active(pBApp), false));
        errs.push(...chkApply('candApplyDef', active(pCAppD), false));
        errs.push(...chkApply('candApplyPaper', active(pCAppP), true));
        errs.push(...chkApply('brokenApplyPaper', active(pXAppP), true));
        errs.push(...chkCopy('baseCopy', active(pBCopy), false));
        errs.push(...chkCopy('candCopyDef', active(pCCopyD), false));
        errs.push(...chkCopy('candCopyPaper', active(pCCopyP), true));
        const aRend = active(pRend);
        errs.push(...chkRender('render', aRend));
        errs.push(...chkRenderLocations('render', pRend, aRend));
        if (errs.length) return fail('uniforms: ' + errs.slice(0, 6).join('; '), { uniforms: errs });
        activeRenderEvidence = aRend;
        // Render active sampler bindings 0..7 evidence (locations must exist; units verified per-draw below).
        renderBindingEvidence = {};
        for (const nm of ['u_p0', 'u_p1', 'u_paperState', 'u_w0', 'u_w1', 'u_paperBake', 'u_paperSurface', 'u_paperFluid']) {
          const loc = gl.getUniformLocation(pRend, nm);
          renderBindingEvidence[nm] = { active: aRend[nm] !== undefined, hasLocation: !!loc };
        }
      }
      // Layout locations: default 0/1/2, paper-only paper at 0.
      try {
        const loc = (p, n) => gl.getFragDataLocation(p, n);
        if (loc(pBApp, 'o_p0') !== 0 || loc(pBApp, 'o_p1') !== 1 || loc(pBApp, 'o_paper') !== 2) return fail('baseline default apply locations must be 0/1/2');
        if (loc(pCAppD, 'o_p0') !== 0 || loc(pCAppD, 'o_p1') !== 1 || loc(pCAppD, 'o_paper') !== 2) return fail('candidate default apply locations must be 0/1/2');
        if (loc(pCAppP, 'o_paper') !== 0) return fail('candidate PAPER_ONLY apply paper must be location 0');
        if (loc(pXAppP, 'o_paper') !== 0) return fail('broken PAPER_ONLY apply paper must be location 0');
        if (loc(pBCopy, 'o_a') !== 0 || loc(pBCopy, 'o_b') !== 1 || loc(pBCopy, 'o_c') !== 2) return fail('baseline copy locations must be 0/1/2');
        if (loc(pCCopyP, 'o_c') !== 0) return fail('candidate PAPER_ONLY copy paper must be location 0');
      } catch (e) { return fail('frag data location query: ' + e.message); }
      if (gl.getError() !== gl.NO_ERROR) return fail('GL error after program setup');
      const rng32 = (a) => () => { a |= 0; a = a + 0x6D2B79F5 | 0; let t = Math.imul(a ^ a >>> 15, 1 | a); t = t + Math.imul(t ^ t >>> 7, 61 | t) ^ t; return ((t ^ t >>> 14) >>> 0) / 4294967296; };
      // Correct IEEE float->half (round-to-nearest-even, actual GL conversion, not fake half).
      function f2h(v) {
        const f = new Float32Array([v])[0];
        const u = new Uint32Array(new Float32Array([f]).buffer)[0];
        const s = (u >> 16) & 0x8000;
        const e = (u >> 23) & 0xff, m = u & 0x7fffff;
        if (e === 0xff) return s | (m ? 0x7e00 : 0x7c00);
        const ke = e - 112;
        if (ke >= 31) return s | 0x7c00;
        if (ke <= 0) {
          if (ke < -10) return s;
          const mm = m | 0x800000;
          let h = mm >> (1 - ke);
          if ((mm >> (0 - ke)) & 1) h++;
          // round to even handled via increment above for these magnitudes; keep simple exact path
          return s | (h >> 13);
        }
        let h = ((e - 112) << 10) | (m >> 13);
        // round to nearest even
        const roundBit = (m >> 12) & 1, sticky = m & 0xfff;
        if (roundBit && (sticky || (h & 1))) h++;
        return s | (h & 0xffff);
      }
      function genState(kind, seed) {
        const r = rng32(seed);
        const p0 = new Float32Array(N * 4), p1 = new Float32Array(N * 4), ps = new Float32Array(N * 4);
        for (let i = 0; i < N; i++) {
          const o = i * 4, q = r();
          let K = [0, 0, 0], vol = 0, S = [0, 0, 0], open = 0;
          if (kind === 'empty') { /* all zero */ }
          else if (kind === 'dry' || (kind === 'mixed' && q < 0.4)) {
            K = [0.2 + r() * 1.3, 0.1 + r() * 1.0, r() * 0.9]; vol = 0.008 + r() * 0.03; S = [r() * 0.3, r() * 0.3, r() * 0.3]; open = r() * 0.4;
          } else if (kind === 'oil' || (kind === 'mixed' && q < 0.7)) {
            if (r() < 0.3) { vol = 0; }
            else { K = [1 + r() * 3, 0.5 + r() * 2, r() * 1.5]; vol = 0.05 + r() * 0.08; S = [0.5 + r() * 1.2, 0.5 + r() * 1.0, 0.3 + r() * 0.8]; open = 0.5 + r() * 0.5; }
          } else {
            K = [r() * 0.3, r() * 0.3, r() * 0.3]; vol = 0.004 + r() * 0.01; S = [r() * 0.2, r() * 0.2, r() * 0.2]; open = r() * 0.2;
          }
          p0[o] = K[0]; p0[o + 1] = K[1]; p0[o + 2] = K[2]; p0[o + 3] = vol;
          p1[o] = S[0]; p1[o + 1] = S[1]; p1[o + 2] = S[2]; p1[o + 3] = open;
          if (kind === 'empty') { ps[o] = 0; ps[o + 1] = 0; ps[o + 2] = 0; ps[o + 3] = 0; }
          else {
            const crush = r() * 0.28, vv = 0.015 + r() * 0.06, fl = 0.05 + r() * 0.15;
            ps[o] = crush; ps[o + 1] = vv; ps[o + 2] = vv * fl; ps[o + 3] = 0;
          }
        }
        return { p0, p1, ps };
      }
      function genDeltaFloat(kind, seed) {
        const r = rng32(seed);
        const d = new Float32Array(N * 4);
        if (kind === 'empty') return d;
        for (let i = 0; i < N; i++) {
          const o = i * 4;
          d[o] = 0.006 + r() * 0.04; d[o + 1] = 0.005 + r() * 0.025; d[o + 2] = 0.003 + r() * 0.016; d[o + 3] = 0.15 + r() * 0.85;
        }
        return d;
      }
      // NONFLAT canonical paper (render-checker pattern, 64px, sinusoidal+noise, packed R/G slope bytes B height A h^2)
      const PS = 64, SLOPE = 0.099;
      function genPaper(seed) {
        const r = rng32(seed), h = new Float32Array(PS * PS);
        for (let y = 0; y < PS; y++) for (let x = 0; x < PS; x++) h[y * PS + x] = 0.5 + 0.25 * Math.sin(2 * Math.PI * x / 16) + 0.15 * Math.cos(2 * Math.PI * y / 12) + 0.2 * (r() - 0.5);
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
      const paper = genPaper(0x9a9a);
      if (!((paper.mx - paper.mn) > 0.15 && (paper.sMx - paper.sMn) > 8)) return fail('paper must be NONFLAT canonical');
      // Fixture finiteness/nonnegativity spot-check (positive + explicit zero, no NaN/inf)
      for (const k of ['dry', 'wet', 'oil', 'mixed', 'empty']) {
        const s = genState(k, 1234);
        for (const arr of [s.p0, s.p1, s.ps]) for (let i = 0; i < arr.length; i++) {
          if (!Number.isFinite(arr[i]) || arr[i] < 0) return fail('fixtures must be finite nonnegative physical', { k });
          if (Number.isNaN(arr[i]) || !Number.isFinite(arr[i])) return fail('NaN/inf physical state unsupported');
        }
      }
      function ftexFull(data) {
        const t = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, t);
        gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA32F, W, H, 0, gl.RGBA, gl.FLOAT, data);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
        return t;
      }
      function ftexHalf(half) {
        const t = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, t);
        gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA16F, W, H, 0, gl.RGBA, gl.HALF_FLOAT, half);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
        return t;
      }
      // Per-pipeline state textures (alias-free, no sharing across pipelines)
      function makePipe() {
        return { curP0: ftexFull(null), curP1: ftexFull(null), curPaper: ftexFull(null), nxtP0: ftexFull(null), nxtP1: ftexFull(null), nxtPaper: ftexFull(null) };
      }
      const T = { base: makePipe(), cdef: makePipe(), cpap: makePipe(), brok: makePipe() };
      const deltaTex = ftexHalf(new Uint16Array(N * 4));
      if (gl.getError() !== gl.NO_ERROR) return fail('GL error after texture creation (check RGBA32F/NEAREST + RGBA16F delta)');
      function fboN(texs) {
        const f = gl.createFramebuffer(); gl.bindFramebuffer(gl.FRAMEBUFFER, f);
        texs.forEach((t, i) => gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0 + i, gl.TEXTURE_2D, t, 0));
        gl.drawBuffers(texs.map((_, i) => gl.COLOR_ATTACHMENT0 + i));
        if (gl.checkFramebufferStatus(gl.FRAMEBUFFER) !== gl.FRAMEBUFFER_COMPLETE) throw new Error('float FBO incomplete (ERROR not pass)');
        return f;
      }
      let fBApp, fCAppD, fCAppP, fXAppP, fBCopy, fCCopyD, fCCopyP, fXCopyP;
      try {
        fBApp = fboN([T.base.nxtP0, T.base.nxtP1, T.base.nxtPaper]);
        fCAppD = fboN([T.cdef.nxtP0, T.cdef.nxtP1, T.cdef.nxtPaper]);
        fCAppP = fboN([T.cpap.nxtPaper]);
        fXAppP = fboN([T.brok.nxtPaper]);
        fBCopy = fboN([T.base.curP0, T.base.curP1, T.base.curPaper]);
        fCCopyD = fboN([T.cdef.curP0, T.cdef.curP1, T.cdef.curPaper]);
        fCCopyP = fboN([T.cpap.curPaper]);
        fXCopyP = fboN([T.brok.curPaper]);
      } catch (e) { return fail('FBO: ' + e.message); }
      const readFbo = gl.createFramebuffer();
      const quadBuf = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, quadBuf);
      gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([0, 0, 1, 0, 0, 1, 1, 1]), gl.STATIC_DRAW);
      const quadVao = gl.createVertexArray(); gl.bindVertexArray(quadVao);
      gl.bindBuffer(gl.ARRAY_BUFFER, quadBuf); gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
      gl.bindVertexArray(null);
      const emptyVao = gl.createVertexArray();
      gl.viewport(0, 0, W, H); gl.disable(gl.BLEND); gl.disable(gl.SCISSOR_TEST);
      function uploadFull(tex, data) {
        gl.bindTexture(gl.TEXTURE_2D, tex);
        gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA32F, W, H, 0, gl.RGBA, gl.FLOAT, data);
      }
      function uploadHalf(tex, half) {
        gl.bindTexture(gl.TEXTURE_2D, tex);
        gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA16F, W, H, 0, gl.RGBA, gl.HALF_FLOAT, half);
      }
      function disjoint(readIds, writeIds) {
        for (const r of readIds) for (const w of writeIds) if (r === w) return false;
        return true;
      }
      function setApply(prog, tx, cs) {
        gl.useProgram(prog);
        const F1 = (n, v) => gl.uniform1f(gl.getUniformLocation(prog, n), v);
        // Sampler units: default p0=0,p1=1,paper=2,delta=3; paper-only paper=0,delta=1.
        const am = active(prog);
        let unit = 0;
        const bind = (nm, tex) => { const loc = gl.getUniformLocation(prog, nm); if (!loc) return -1; gl.activeTexture(gl.TEXTURE0 + unit); gl.bindTexture(gl.TEXTURE_2D, tex); gl.uniform1i(loc, unit); const got = gl.getUniform(prog, loc); if (got !== unit) throw new Error('binding ' + nm + '=' + got + ' want ' + unit); unit++; return got; };
        if (am.u_p0 !== undefined) bind('u_p0', tx.curP0);
        if (am.u_p1 !== undefined) bind('u_p1', tx.curP1);
        bind('u_paperState', tx.curPaper); bind('u_delta', deltaTex);
        gl.uniform2f(gl.getUniformLocation(prog, 'u_targetSize'), W, H);
        gl.uniform4f(gl.getUniformLocation(prog, 'u_rect'), cs.rect[0], cs.rect[1], cs.rect[2], cs.rect[3]);
        gl.uniform2f(gl.getUniformLocation(prog, 'u_layerOrigin'), cs.origin[0], cs.origin[1]);
        F1('u_layerScale', cs.scale);
        F1('u_capMm', cs.capMm); F1('u_abrasion', cs.abrasion); F1('u_flakeR', cs.flakeR);
        F1('u_crushRate', cs.crushRate); F1('u_crushMax', cs.crushMax); F1('u_smear', cs.smear);
        gl.uniform2f(gl.getUniformLocation(prog, 'u_smearPx'), cs.smearPx[0], cs.smearPx[1]);
        return { unit };
      }
      function setCopy(prog, tx, cs) {
        gl.useProgram(prog);
        const am = active(prog);
        let unit = 0;
        const bind = (nm, tex) => { const loc = gl.getUniformLocation(prog, nm); if (!loc) return -1; gl.activeTexture(gl.TEXTURE0 + unit); gl.bindTexture(gl.TEXTURE_2D, tex); gl.uniform1i(loc, unit); const got = gl.getUniform(prog, loc); if (got !== unit) throw new Error('binding ' + nm + '=' + got + ' want ' + unit); unit++; return got; };
        if (am.u_a !== undefined) bind('u_a', tx.nxtP0);
        if (am.u_b !== undefined) bind('u_b', tx.nxtP1);
        bind('u_c', tx.nxtPaper);
        gl.uniform2f(gl.getUniformLocation(prog, 'u_targetSize'), W, H);
        gl.uniform4f(gl.getUniformLocation(prog, 'u_rect'), cs.rect[0], cs.rect[1], cs.rect[2], cs.rect[3]);
        gl.uniform2f(gl.getUniformLocation(prog, 'u_layerOrigin'), cs.origin[0], cs.origin[1]);
        gl.uniform1f(gl.getUniformLocation(prog, 'u_layerScale'), cs.scale);
      }
      function drawQuad() { gl.bindVertexArray(quadVao); gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4); gl.bindVertexArray(null); }
      function readFloat(tex) {
        gl.bindFramebuffer(gl.FRAMEBUFFER, readFbo);
        gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, tex, 0);
        gl.drawBuffers([gl.COLOR_ATTACHMENT0]);
        if (gl.checkFramebufferStatus(gl.FRAMEBUFFER) !== gl.FRAMEBUFFER_COMPLETE) throw new Error('read FBO incomplete (ERROR not pass)');
        const px = new Float32Array(N * 4);
        gl.readPixels(0, 0, W, H, gl.RGBA, gl.FLOAT, px);
        const err = gl.getError();
        if (err !== gl.NO_ERROR) throw new Error('float readback unsupported (ERROR not pass): 0x' + err.toString(16));
        return px;
      }
      // Cases: 5 fixtures x varied rect/origin/scale/smear/cap/crush + overlapping second update.
      const cases = [
        { f: 'dry', rect: [0, 0, 32, 32], origin: [0, 0], scale: 1, smear: 0, smearPx: [0, 0], capMm: 0.2, abrasion: 30, flakeR: 0.1, crushRate: 0.6, crushMax: 0.5 },
        { f: 'dry', rect: [4, 4, 20, 20], origin: [0, 0], scale: 1, smear: 0.5, smearPx: [1.5, 0], capMm: 0.2, abrasion: 30, flakeR: 0.1, crushRate: 0.6, crushMax: 0.5 },
        { f: 'wet', rect: [4.5, 5.25, 19.75, 22.5], origin: [-3.5, -2.25], scale: 0.5, smear: 0.3, smearPx: [2.25, -1.5], capMm: 0.06, abrasion: 12, flakeR: 0.08, crushRate: 0.4, crushMax: 0.4 },
        { f: 'oil', rect: [-8, -6, 10, 12], origin: [5, 3], scale: 2, smear: 0, smearPx: [0, 0], capMm: 0.02, abrasion: 50, flakeR: 0.2, crushRate: 0.9, crushMax: 0.6 },
        { f: 'mixed', rect: [2, 2, 30, 30], origin: [-12, -6], scale: 1, smear: 0.45, smearPx: [0.75, 1.25], capMm: 0.03, abrasion: 20, flakeR: 0.12, crushRate: 0.5, crushMax: 0.45, rect2: [12, 12, 28, 28] },
        { f: 'empty', rect: [0, 0, 32, 32], origin: [0, 0], scale: 1, smear: 0.5, smearPx: [1, 1], capMm: 0.2, abrasion: 30, flakeR: 0.1, crushRate: 0.6, crushMax: 0.5 },
        { f: 'empty', rect: [6.5, 3.5, 18.25, 24.75], origin: [-4, -5], scale: 0.5, smear: 0, smearPx: [0, 0], capMm: 0.05, abrasion: 15, flakeR: 0.09, crushRate: 0.3, crushMax: 0.3 },
        { f: 'dry', rect: [-4.5, -3.25, 12.75, 14.5], origin: [2, -3], scale: 2, smear: 0.2, smearPx: [-1.75, 0.5], capMm: 0.025, abrasion: 40, flakeR: 0.15, crushRate: 0.7, crushMax: 0.55 },
        { f: 'oil', rect: [8, 8, 24, 24], origin: [0, 0], scale: 1, smear: 0.6, smearPx: [3.5, -2.25], capMm: 0.2, abrasion: 25, flakeR: 0.07, crushRate: 0.5, crushMax: 0.5, rect2: [4, 4, 16, 16] },
        { f: 'wet', rect: [0, 0, 16, 16], origin: [-8, -4], scale: 1, smear: 0, smearPx: [0, 0], capMm: 0.04, abrasion: 18, flakeR: 0.11, crushRate: 0.45, crushMax: 0.4 },
        { f: 'mixed', rect: [10.5, 9.75, 31.5, 30.25], origin: [3, 2], scale: 0.5, smear: 0.35, smearPx: [1.1, -0.9], capMm: 0.08, abrasion: 22, flakeR: 0.1, crushRate: 0.55, crushMax: 0.5 },
        { f: 'dry', rect: [0, 0, 32, 32], origin: [-6, -6], scale: 2, smear: 0.5, smearPx: [0.5, 0.5], capMm: 0.15, abrasion: 28, flakeR: 0.09, crushRate: 0.6, crushMax: 0.5 },
      ];
      // Coverage assertions (fail if inadequate)
      {
        const fs = new Set(cases.map(c => c.f));
        if (!(fs.has('empty') && fs.has('dry') && fs.has('wet') && fs.has('oil') && fs.has('mixed'))) return fail('coverage: empty/dry/wet/oil/mixed required');
        const scales = new Set(cases.map(c => c.scale));
        if (!(scales.has(0.5) && scales.has(1) && scales.has(2))) return fail('coverage: layerScale .5/1/2 required', { scales: [...scales] });
        if (!cases.some(c => c.origin[0] < 0 || c.origin[1] < 0)) return fail('coverage: negative origin required');
        if (!cases.some(c => c.rect[0] === 0 && c.rect[1] === 0 && c.rect[2] === 32 && c.rect[3] === 32)) return fail('coverage: full rect required');
        if (!cases.some(c => !(c.rect[0] === 0 && c.rect[1] === 0 && c.rect[2] === 32 && c.rect[3] === 32))) return fail('coverage: partial rect required');
        if (!cases.some(c => c.rect.some(v => v % 1 !== 0))) return fail('coverage: fractional rect required');
        if (!cases.some(c => c.rect[0] < 0 || c.rect[1] < 0)) return fail('coverage: edge-clipped/negative-origin rect required');
        if (!cases.some(c => c.smear === 0) || !cases.some(c => c.smear !== 0)) return fail('coverage: zero and nonzero smear required');
        if (!cases.some(c => Math.abs(c.smearPx[0] % 1) > 1e-6 || Math.abs(c.smearPx[1] % 1) > 1e-6)) return fail('coverage: fractional smear travel required');
        if (!cases.some(c => c.rect2)) return fail('coverage: overlapping multiple updates required');
        if (!cases.some(c => c.capMm < 0.05) || !cases.some(c => c.capMm > 0.1)) return fail('coverage: cap+saturation pair required');
      }
      const rows = [];
      let totalPaperBits = 0, totalP0Bits = 0, totalP1Bits = 0;
      let controlSupported = 0, controlDetected = 0;
      let allChannelCases = 0;
      const fnv = (b) => { let h = 2166136261; for (let i = 0; i < b.length; i++) { h ^= b[i]; h = Math.imul(h, 16777619); } return (h >>> 0).toString(16).padStart(8, '0'); };
      const hashes = new Set();
      for (let ci = 0; ci < cases.length; ci++) {
        const cs = cases[ci];
        const cur0 = genState(cs.f, 1000 + ci * 17), nxt0 = genState(cs.f, 5000 + ci * 29 + 7);
        const dFloat = genDeltaFloat(cs.f, 9000 + ci * 13);
        const dHalf = new Uint16Array(N * 4);
        for (let i = 0; i < dFloat.length; i++) dHalf[i] = f2h(dFloat[i]);
        // Upload initials to all pipelines + delta
        for (const tx of [T.base, T.cdef, T.cpap, T.brok]) {
          uploadFull(tx.curP0, cur0.p0); uploadFull(tx.curP1, cur0.p1); uploadFull(tx.curPaper, cur0.ps);
          uploadFull(tx.nxtP0, nxt0.p0); uploadFull(tx.nxtP1, nxt0.p1); uploadFull(tx.nxtPaper, nxt0.ps);
        }
        uploadHalf(deltaTex, dHalf);
        if (gl.getError() !== gl.NO_ERROR) return fail('GL error after fixture upload', { case: ci });
        const rects = [cs.rect].concat(cs.rect2 ? [cs.rect2] : []);
        for (const rc of rects) {
          const sub = Object.assign({}, cs, { rect: rc });
          // --- baseline default ---
          if (!disjoint([T.base.curP0, T.base.curP1, T.base.curPaper, deltaTex], [T.base.nxtP0, T.base.nxtP1, T.base.nxtPaper])) return fail('write/read textures must be disjoint (base apply)', { case: ci });
          gl.bindFramebuffer(gl.FRAMEBUFFER, fBApp); gl.viewport(0, 0, W, H); gl.disable(gl.BLEND);
          setApply(pBApp, T.base, sub); drawQuad();
          if (gl.getError() !== gl.NO_ERROR) return fail('GL error drawing baseline apply (actual shader GL error fatal)', { case: ci });
          if (!disjoint([T.base.nxtP0, T.base.nxtP1, T.base.nxtPaper], [T.base.curP0, T.base.curP1, T.base.curPaper])) return fail('write/read textures must be disjoint (base copy)', { case: ci });
          gl.bindFramebuffer(gl.FRAMEBUFFER, fBCopy); gl.viewport(0, 0, W, H);
          setCopy(pBCopy, T.base, sub); drawQuad();
          if (gl.getError() !== gl.NO_ERROR) return fail('GL error drawing baseline copy', { case: ci });
          // --- candidate default ---
          if (!disjoint([T.cdef.curP0, T.cdef.curP1, T.cdef.curPaper, deltaTex], [T.cdef.nxtP0, T.cdef.nxtP1, T.cdef.nxtPaper])) return fail('disjoint (candDef apply)', { case: ci });
          gl.bindFramebuffer(gl.FRAMEBUFFER, fCAppD); gl.viewport(0, 0, W, H);
          setApply(pCAppD, T.cdef, sub); drawQuad();
          if (gl.getError() !== gl.NO_ERROR) return fail('GL error drawing candidate default apply', { case: ci });
          if (!disjoint([T.cdef.nxtP0, T.cdef.nxtP1, T.cdef.nxtPaper], [T.cdef.curP0, T.cdef.curP1, T.cdef.curPaper])) return fail('disjoint (candDef copy)', { case: ci });
          gl.bindFramebuffer(gl.FRAMEBUFFER, fCCopyD); gl.viewport(0, 0, W, H);
          setCopy(pCCopyD, T.cdef, sub); drawQuad();
          if (gl.getError() !== gl.NO_ERROR) return fail('GL error drawing candidate default copy', { case: ci });
          // --- candidate paper-only ---
          if (!disjoint([T.cpap.curPaper, deltaTex], [T.cpap.nxtPaper])) return fail('disjoint (candPaper apply)', { case: ci });
          gl.bindFramebuffer(gl.FRAMEBUFFER, fCAppP); gl.viewport(0, 0, W, H);
          setApply(pCAppP, T.cpap, sub); drawQuad();
          if (gl.getError() !== gl.NO_ERROR) return fail('GL error drawing candidate paper apply', { case: ci });
          if (!disjoint([T.cpap.nxtPaper], [T.cpap.curPaper])) return fail('disjoint (candPaper copy)', { case: ci });
          gl.bindFramebuffer(gl.FRAMEBUFFER, fCCopyP); gl.viewport(0, 0, W, H);
          setCopy(pCCopyP, T.cpap, sub); drawQuad();
          if (gl.getError() !== gl.NO_ERROR) return fail('GL error drawing candidate paper copy', { case: ci });
          // --- broken paper-only ---
          if (!disjoint([T.brok.curPaper, deltaTex], [T.brok.nxtPaper])) return fail('disjoint (broken apply)', { case: ci });
          gl.bindFramebuffer(gl.FRAMEBUFFER, fXAppP); gl.viewport(0, 0, W, H);
          setApply(pXAppP, T.brok, sub); drawQuad();
          if (gl.getError() !== gl.NO_ERROR) return fail('GL error drawing broken apply', { case: ci });
          if (!disjoint([T.brok.nxtPaper], [T.brok.curPaper])) return fail('disjoint (broken copy)', { case: ci });
          gl.bindFramebuffer(gl.FRAMEBUFFER, fXCopyP); gl.viewport(0, 0, W, H);
          setCopy(pXCopyP, T.brok, sub); drawQuad();
          if (gl.getError() !== gl.NO_ERROR) return fail('GL error drawing broken copy', { case: ci });
        }
        let bCurP0, bCurP1, bCurPa, bNxtPa, bNxtP0, bNxtP1, cCurP0, cCurP1, cCurPa, cNxtPa, cNxtP0, cNxtP1, pCurP0, pCurP1, pCurPa, pNxtPa, xCurPa;
        try {
          bCurP0 = readFloat(T.base.curP0); bCurP1 = readFloat(T.base.curP1); bCurPa = readFloat(T.base.curPaper); bNxtPa = readFloat(T.base.nxtPaper); bNxtP0 = readFloat(T.base.nxtP0); bNxtP1 = readFloat(T.base.nxtP1);
          cCurP0 = readFloat(T.cdef.curP0); cCurP1 = readFloat(T.cdef.curP1); cCurPa = readFloat(T.cdef.curPaper); cNxtPa = readFloat(T.cdef.nxtPaper); cNxtP0 = readFloat(T.cdef.nxtP0); cNxtP1 = readFloat(T.cdef.nxtP1);
          pCurP0 = readFloat(T.cpap.curP0); pCurP1 = readFloat(T.cpap.curP1); pCurPa = readFloat(T.cpap.curPaper); pNxtPa = readFloat(T.cpap.nxtPaper);
          xCurPa = readFloat(T.brok.curPaper);
        } catch (e) { return fail('readback: ' + e.message, { case: ci }); }
        const toBits = (f) => new Uint32Array(f.buffer.slice(0));
        const bB0 = toBits(bCurP0), bB1 = toBits(bCurP1), bBP = toBits(bCurPa), bNP = toBits(bNxtPa), bN0 = toBits(bNxtP0), bN1 = toBits(bNxtP1);
        const cB0 = toBits(cCurP0), cB1 = toBits(cCurP1), cBP = toBits(cCurPa), cNP = toBits(cNxtPa), cN0 = toBits(cNxtP0), cN1 = toBits(cNxtP1);
        const pB0 = toBits(pCurP0), pB1 = toBits(pCurP1), pBP = toBits(pCurPa), pNP = toBits(pNxtPa);
        const xBP = toBits(xCurPa);
        const initP0B = toBits(cur0.p0), initP1B = toBits(cur0.p1), initPaB = toBits(cur0.ps);
        const initNxtP0B = toBits(nxt0.p0), initNxtP1B = toBits(nxt0.p1), initNxtPaB = toBits(nxt0.ps);
        function cmpBits(a, b) {
          let n = 0, first = null;
          for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) {
            n++;
            if (!first) {
              const px = Math.floor((i / 4)) % W, py = Math.floor((i / 4) / W), ch = i % 4;
              first = { index: i, x: px, y: py, ch, aBits: ('00000000' + a[i].toString(16)).slice(-8), bBits: ('00000000' + b[i].toString(16)).slice(-8), aVal: new Float32Array(new Uint32Array([a[i]]).buffer)[0], bVal: new Float32Array(new Uint32Array([b[i]]).buffer)[0] };
            }
          }
          return { n, first };
        }
        const dDefP0 = cmpBits(bB0, cB0), dDefP1 = cmpBits(bB1, cB1), dDefPa = cmpBits(bBP, cBP), dDefNxtPa = cmpBits(bNP, cNP);
        const dDefNxtP0 = cmpBits(bN0, cN0), dDefNxtP1 = cmpBits(bN1, cN1);
        const dPapPa = cmpBits(bBP, pBP), dPapNxtPa = cmpBits(bNP, pNP);
        const dPapP0Init = cmpBits(pB0, initP0B), dPapP1Init = cmpBits(pB1, initP1B);
        const dDefP0Init = cmpBits(bB0, initP0B), dDefP1Init = cmpBits(bB1, initP1B);
        const dBrokPa = cmpBits(bBP, xBP);
        totalPaperBits += dDefPa.n + dPapPa.n; totalP0Bits += dDefP0.n; totalP1Bits += dDefP1.n;
        // p0/p1 after default must equal baseline (cur AND nxt, whole textures); paper-only p0/p1 must equal initial (untouched).
        // Default cur p0/p1 must also equal initial cur (passthrough, no corruption).
        const candDefOk = (dDefP0.n === 0 && dDefP1.n === 0 && dDefPa.n === 0 && dDefNxtPa.n === 0 && dDefNxtP0.n === 0 && dDefNxtP1.n === 0 && dDefP0Init.n === 0 && dDefP1Init.n === 0);
        const candPapOk = (dPapPa.n === 0 && dPapNxtPa.n === 0 && dPapP0Init.n === 0 && dPapP1Init.n === 0);
        // Activity: non-empty paper must change vs initial (inside clearly-inside region); empty must not.
        const rc0 = cs.rect2 ? [Math.min(cs.rect[0], cs.rect2[0]), Math.min(cs.rect[1], cs.rect2[1]), Math.max(cs.rect[2], cs.rect2[2]), Math.max(cs.rect[3], cs.rect2[3])] : cs.rect;
        const x0i = Math.ceil(Math.max(0, rc0[0])), x1i = Math.floor(Math.min(W, rc0[2])), y0i = Math.ceil(Math.max(0, rc0[1])), y1i = Math.floor(Math.min(H, rc0[3]));
        let insideChanged = 0, insideChecked = 0, chChanged = [0, 0, 0, 0];
        for (let y = y0i; y < y1i; y++) for (let x = x0i; x < x1i; x++) {
          for (let ch = 0; ch < 4; ch++) {
            const idx = (y * W + x) * 4 + ch;
            insideChecked++;
            if (bBP[idx] !== initPaB[idx]) { insideChanged++; chChanged[ch]++; }
          }
        }
        let outsideHeld = true;
        for (let y = 0; y < H && outsideHeld; y++) for (let x = 0; x < W && outsideHeld; x++) {
          const clearlyOutside = (x + 1 <= Math.floor(rc0[0]) || x >= Math.ceil(rc0[2]) || y + 1 <= Math.floor(rc0[1]) || y >= Math.ceil(rc0[3]));
          if (!clearlyOutside) continue;
          // Whole-texture outside: cur paper outside must equal initial cur; nxt paper outside must equal
          // initial nxt (distinct scratch proves no out-of-bounds writes); same for paper-only.
          for (let ch = 0; ch < 4; ch++) {
            const idx = (y * W + x) * 4 + ch;
            if (bBP[idx] !== initPaB[idx] || pBP[idx] !== initPaB[idx]) { outsideHeld = false; break; }
            if (bNP[idx] !== initNxtPaB[idx] || pNP[idx] !== initNxtPaB[idx]) { outsideHeld = false; break; }
            if (bB0[idx] !== initP0B[idx] || bB1[idx] !== initP1B[idx]) { outsideHeld = false; break; }
          }
        }
        if (cs.f !== 'empty') {
          if (!(insideChanged > 0)) return fail('activity: non-empty paper never changed (synthetic delta vacuous?)', { case: ci, fixture: cs.f });
          if (!outsideHeld) return fail('outside pixels must hold (whole-texture compare incl. outside partial rect)', { case: ci });
          if (chChanged[0] > 0 && chChanged[1] > 0 && chChanged[2] > 0) allChannelCases++;
        } else {
          if (!(insideChanged === 0)) return fail('empty must leave paper bit-exact (zero delta)', { case: ci, insideChanged });
        }
        // Control support/detection on nonzero fixtures (non-empty with actual change)
        if (cs.f !== 'empty' && insideChanged > 0) {
          controlSupported++;
          if (dBrokPa.n > 0) controlDetected++;
        }
        hashes.add(fnv(new Uint8Array(bBP.buffer))); hashes.add(fnv(new Uint8Array(cBP.buffer)));
        rows.push({ case: ci, fixture: cs.f, rect: cs.rect, origin: cs.origin, scale: cs.scale, smear: cs.smear, expectSame: true, defP0Bits: dDefP0.n, defP1Bits: dDefP1.n, defPaperBits: dDefPa.n, defNxtPaperBits: dDefNxtPa.n, defNxtP0Bits: dDefNxtP0.n, defNxtP1Bits: dDefNxtP1.n, defP0InitBits: dDefP0Init.n, defP1InitBits: dDefP1Init.n, papPaperBits: dPapPa.n, papNxtPaperBits: dPapNxtPa.n, papP0InitBits: dPapP0Init.n, papP1InitBits: dPapP1Init.n, brokenPaperBits: dBrokPa.n, insideChanged, outsideHeld, chChanged, firstDef: dDefPa.first, firstPap: dPapPa.first, firstBroken: dBrokPa.first, okDef: candDefOk, okPap: candPapOk });
        if (!candDefOk) return { error: null, failAt: ci, rows, note: 'candidate-default mismatch vs baseline (exact 0 required)', defP0: dDefP0, defP1: dDefP1, defPa: dDefPa };
        if (!candPapOk) return { error: null, failAt: ci, rows, note: 'candidate PAPER_ONLY mismatch vs baseline or p0/p1 touched (exact 0 required)', papPa: dPapPa, p0init: dPapP0Init };
      }
      if (hashes.size < 4) return fail('outputs vacuous (all cases equal)');
      if (controlSupported === 0) return fail('positive control has no supported nonzero fixture');
      if (controlDetected === 0) return fail('positive control never detected (broken paper must fail raw paper on nonzero fixtures)');
      if (allChannelCases === 0) return fail('no case changed all paper R/G/B channels (need crush+deposit coverage)');
      // ---- render FINAL active state through actual media_render.frag/screen.vert ----
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
      const zeroF = new Float32Array(N * 4);
      const w0Tex = ftexFull(zeroF), w1Tex = ftexFull(zeroF);
      const bakeArr = new Float32Array(N * 4);
      { const r = rng32(77); for (let i = 0; i < N; i++) { bakeArr[i * 4] = 0; bakeArr[i * 4 + 1] = 0; bakeArr[i * 4 + 2] = 0.3 + r() * 0.4; bakeArr[i * 4 + 3] = 0; } }
      const bakeTex = ftexFull(bakeArr);
      const outTex = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, outTex);
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, W, H, 0, gl.RGBA, gl.UNSIGNED_BYTE, null);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST);
      const renderFbo = gl.createFramebuffer(); gl.bindFramebuffer(gl.FRAMEBUFFER, renderFbo);
      gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, outTex, 0);
      gl.drawBuffers([gl.COLOR_ATTACHMENT0]);
      if (gl.checkFramebufferStatus(gl.FRAMEBUFFER) !== gl.FRAMEBUFFER_COMPLETE) return fail('render RGBA8 FBO incomplete');
      if (gl.getError() !== gl.NO_ERROR) return fail('GL error after render texture setup');
      // Re-run one representative update per fixture to get final ACTIVE cur for render (reuse T.base/cpap/brok cur)
      const renderFixtures = ['dry', 'wet', 'oil', 'mixed', 'empty'];
      const repCase = { rect: [4, 4, 24, 24], origin: [0, 0], scale: 1, smear: 0.4, smearPx: [1.5, -1], capMm: 0.06, abrasion: 25, flakeR: 0.1, crushRate: 0.55, crushMax: 0.5 };
      // Minimal varied render views prove u_layerOrigin/u_layerScale/u_pan/u_zoom wiring (null would
      // otherwise stay silent with symmetric wrong renders). Negative origins + zooms 0.5/1/2 covered.
      const renderViews = {
        dry: { origin: [0, 0], scale: 1, pan: [0, 0], zoom: 1 },
        wet: { origin: [-8, -4], scale: 1, pan: [4, 2], zoom: 0.5 },
        oil: { origin: [5, 3], scale: 1, pan: [0, 0], zoom: 2 },
        mixed: { origin: [-12, -6], scale: 1, pan: [7, 5], zoom: 1 },
        empty: { origin: [0, 0], scale: 1, pan: [0, 0], zoom: 1 },
      };
      const LA = [-0.45, -0.55, 0.70], LB = [0.5, 0.3, 0.8];
      const norm3 = (v) => { const l = Math.hypot(v[0], v[1], v[2]); return [v[0] / l, v[1] / l, v[2] / l]; };
      const renderRows = [];
      let renderSupported = 0, renderDetected = 0, renderProbes = 0, renderMismatchBytes = 0;
      let look1Trans = 0, look1Count = 0, look1AMin = 255, look1AMax = 0;
      const rHashes = new Set();
      const renderHashByKey = {};
      for (const fk of renderFixtures) {
        const cur = genState(fk, 2000 + fk.length * 131), nxt = genState(fk, 7000 + fk.length * 171);
        const df = genDeltaFloat(fk, 3000 + fk.length * 77);
        const dh = new Uint16Array(N * 4); for (let i = 0; i < df.length; i++) dh[i] = f2h(df[i]);
        for (const tx of [T.base, T.cpap, T.brok]) { uploadFull(tx.curP0, cur.p0); uploadFull(tx.curP1, cur.p1); uploadFull(tx.curPaper, cur.ps); uploadFull(tx.nxtP0, nxt.p0); uploadFull(tx.nxtP1, nxt.p1); uploadFull(tx.nxtPaper, nxt.ps); }
        uploadHalf(deltaTex, dh);
        const sub = Object.assign({ f: fk }, repCase);
        // baseline default update
        gl.bindFramebuffer(gl.FRAMEBUFFER, fBApp); gl.viewport(0, 0, W, H); setApply(pBApp, T.base, sub); drawQuad();
        gl.bindFramebuffer(gl.FRAMEBUFFER, fBCopy); setCopy(pBCopy, T.base, sub); drawQuad();
        // candidate paper-only update
        gl.bindFramebuffer(gl.FRAMEBUFFER, fCAppP); setApply(pCAppP, T.cpap, sub); drawQuad();
        gl.bindFramebuffer(gl.FRAMEBUFFER, fCCopyP); setCopy(pCCopyP, T.cpap, sub); drawQuad();
        // broken paper-only update
        gl.bindFramebuffer(gl.FRAMEBUFFER, fXAppP); setApply(pXAppP, T.brok, sub); drawQuad();
        gl.bindFramebuffer(gl.FRAMEBUFFER, fXCopyP); setCopy(pXCopyP, T.brok, sub); drawQuad();
        if (gl.getError() !== gl.NO_ERROR) return fail('GL error in render-fixture update', { fixture: fk });
        const bP0 = readFloat(T.base.curP0), bP1 = readFloat(T.base.curP1), bPa = readFloat(T.base.curPaper);
        const pP0 = readFloat(T.cpap.curP0), pP1 = readFloat(T.cpap.curP1), pPa = readFloat(T.cpap.curPaper);
        const xPa = readFloat(T.brok.curPaper);
        // Upload final ACTIVE as render inputs (alias-free temp textures)
        const rB0 = ftexFull(bP0), rB1 = ftexFull(bP1), rBP = ftexFull(bPa);
        const rP0 = ftexFull(pP0), rP1 = ftexFull(pP1), rPP = ftexFull(pPa);
        const rX0 = ftexFull(bP0), rX1 = ftexFull(bP1), rXP = ftexFull(xPa);
        const drawRender = (tx0, tx1, txP, mode, relief, look, lamp, view) => {
          gl.bindFramebuffer(gl.FRAMEBUFFER, renderFbo); gl.viewport(0, 0, W, H);
          gl.useProgram(pRend);
          // Fail-closed: required uniform null locations throw (WebGL silently ignores null, which
          // would let symmetric wrong renders pass). Sampler bindings 0..7 verified via getUniform.
          const req = (n) => { const l = gl.getUniformLocation(pRend, n); if (!l) throw new Error('render missing uniform ' + n + ' (null location cannot stay silent)'); return l; };
          const Ub = (nm, tex, unit) => {
            gl.activeTexture(gl.TEXTURE0 + unit); gl.bindTexture(gl.TEXTURE_2D, tex);
            const loc = req(nm); gl.uniform1i(loc, unit);
            const got = gl.getUniform(pRend, loc); if (got !== unit) throw new Error('render binding ' + nm + '=' + got + ' want ' + unit);
          };
          Ub('u_p0', tx0, 0); Ub('u_p1', tx1, 1); Ub('u_paperState', txP, 2);
          Ub('u_w0', w0Tex, 3); Ub('u_w1', w1Tex, 4); Ub('u_paperBake', bakeTex, 5); Ub('u_paperSurface', paperTex, 6);
          // u_paperFluid is optional in actual source (jb_paperFluid unused by this render path; may be
          // optimized out). If active, its binding 7 is required and verified; if inactive, still bind.
          gl.activeTexture(gl.TEXTURE7); gl.bindTexture(gl.TEXTURE_2D, fluidTex);
          {
            const fl = gl.getUniformLocation(pRend, 'u_paperFluid');
            if (activeRenderEvidence && activeRenderEvidence.u_paperFluid !== undefined) {
              if (!fl) throw new Error('render missing uniform u_paperFluid (active yet null location)');
              gl.uniform1i(fl, 7);
              const got = gl.getUniform(pRend, fl); if (got !== 7) throw new Error('render binding u_paperFluid=' + got + ' want 7');
            } else if (fl) { gl.uniform1i(fl, 7); const got = gl.getUniform(pRend, fl); if (got !== 7) throw new Error('render binding u_paperFluid=' + got + ' want 7'); }
          }
          gl.uniform2f(req('u_targetSize'), W, H);
          gl.uniform2f(req('u_layerOrigin'), view.origin[0], view.origin[1]); gl.uniform1f(req('u_layerScale'), view.scale);
          gl.uniform2f(req('u_pan'), view.pan[0], view.pan[1]); gl.uniform1f(req('u_zoom'), view.zoom);
          gl.uniform1f(req('u_pxPerMm'), 20); gl.uniform1f(req('u_toothMm'), 0.09);
          gl.uniform3f(req('u_paperColor'), 0.95, 0.93, 0.88);
          const lp = norm3(lamp); gl.uniform3f(req('u_lamp'), lp[0], lp[1], lp[2]);
          gl.uniform1f(req('u_relief'), relief); gl.uniform1f(req('u_sheen'), 0.3);
          gl.uniform1f(req('u_capMm'), 0.2); gl.uniform1i(req('u_mode'), mode);
          gl.uniform1f(req('u_impasto'), 1); gl.uniform1f(req('u_look'), look);
          gl.uniform1f(req('u_paperTexelPx'), 2); gl.uniform1f(req('u_paperSize'), PS);
          gl.uniform1f(req('u_paperHexTexels'), 32); gl.uniform1f(req('u_paperSlopeRange'), SLOPE);
          gl.uniform1i(req('u_paperRotatable'), 1); gl.uniform1f(req('u_paperHeightMean'), paper.mean);
          if (gl.getUniformLocation(pRend, 'u_paperHBot')) gl.uniform1f(gl.getUniformLocation(pRend, 'u_paperHBot'), 0.2);
          if (gl.getUniformLocation(pRend, 'u_paperHTop')) gl.uniform1f(gl.getUniformLocation(pRend, 'u_paperHTop'), 0.8);
          if (gl.getUniformLocation(pRend, 'u_paperFluidTexelPx')) gl.uniform1f(gl.getUniformLocation(pRend, 'u_paperFluidTexelPx'), 0);
          if (gl.getUniformLocation(pRend, 'u_paperFluidSize')) gl.uniform1f(gl.getUniformLocation(pRend, 'u_paperFluidSize'), 4);
          if (gl.getUniformLocation(pRend, 'u_paperFluidHexTexels')) gl.uniform1f(gl.getUniformLocation(pRend, 'u_paperFluidHexTexels'), 1);
          gl.bindVertexArray(emptyVao); gl.drawArrays(gl.TRIANGLES, 0, 3); gl.bindVertexArray(null);
          if (gl.getError() !== gl.NO_ERROR) throw new Error('render draw GL error');
          const b = new Uint8Array(N * 4); gl.readPixels(0, 0, W, H, gl.RGBA, gl.UNSIGNED_BYTE, b);
          if (gl.getError() !== gl.NO_ERROR) throw new Error('render readback GL error');
          return b;
        };
        for (const mode of [0, 1, 8]) for (const relief of [0, 1]) for (const look of [0, 1]) {
          const lamp = ((mode + relief + look) % 2 === 0) ? LA : LB;
          const view0 = renderViews[fk];
          let bO, bC, bX;
          try { bO = drawRender(rB0, rB1, rBP, mode, relief, look, lamp, view0); bC = drawRender(rP0, rP1, rPP, mode, relief, look, lamp, view0); bX = drawRender(rX0, rX1, rXP, mode, relief, look, lamp, view0); }
          catch (e) { return fail('render: ' + e.message, { fixture: fk, mode, relief, look }); }
          let mm = 0; for (let i = 0; i < bO.length; i++) if (bO[i] !== bC[i]) mm++;
          let xm = 0; for (let i = 0; i < bO.length; i++) if (bO[i] !== bX[i]) xm++;
          renderProbes += N; renderMismatchBytes += mm;
          rHashes.add(fnv(bO)); rHashes.add(fnv(bC));
          renderHashByKey[[fk, mode, relief, look].join('|')] = fnv(bO);
          // ALL look0 outputs must be opaque (exact bytes already compared, but both-wrong-transparent
          // would pass without this fail-closed alpha gate).
          if (look === 0) {
            let aBadO = 0, aBadC = 0;
            for (let i = 3; i < bO.length; i += 4) { if (bO[i] !== 255) aBadO++; if (bC[i] !== 255) aBadC++; }
            if (aBadO !== 0 || aBadC !== 0) return fail('render alpha must stay opaque for u_look=0 (exact alpha bytes, both-wrong-transparent fails)', { fixture: fk, mode, relief, look, aBadO, aBadC });
          }
          if (look === 1 && mode === 0) { for (let i = 3; i < bO.length; i += 4) { look1Count++; const a = bO[i]; look1AMin = Math.min(look1AMin, a); look1AMax = Math.max(look1AMax, a); if (a < 255) look1Trans++; } }
          const supportedColour = (fk !== 'empty' && mode === 0);
          if (supportedColour) renderSupported++;
          if (supportedColour && xm > 0) renderDetected++;
          const ok = (mm === 0);
          renderRows.push({ fixture: fk, mode, relief, look, origin: view0.origin.slice(), layerScale: view0.scale, pan: view0.pan.slice(), zoom: view0.zoom, bytesDiffer: mm, brokenBytesDiffer: xm, ok });
          if (!ok) return { error: null, failAt: 'render-' + fk + '-' + mode + '-' + relief + '-' + look, rows, renderRows, note: 'render colour mismatch baseline vs PAPER_ONLY (exact RGBA8 incl. alpha required)' };
        }
        for (const t of [rB0, rB1, rBP, rP0, rP1, rPP, rX0, rX1, rXP]) gl.deleteTexture(t);
      }
      if (rHashes.size < 4) return fail('render outputs vacuous');
      if (renderSupported === 0) return fail('render control has no supported colour case (need non-empty mode0)');
      if (renderDetected === 0) return fail('render control never detected (broken paper must fail colour where expected: non-empty mode0)');
      if (!(look1Count > 0 && look1Trans > 0 && (look1AMax - look1AMin) > 10)) return fail('render nonvacuity: look1 mode0 must show varied transparency (exact alpha proven)', { look1Count, look1Trans, look1AMin, look1AMax });
      // Meaningful mode+relief matrix activity on nonempty representative fixtures (improve the
      // physical fixture if this fails; never weaken the assert). Debug modes early-return before
      // the look branch, so look-difference is scoped to mode0 only.
      {
        const nonEmpty = renderFixtures.filter(f => f !== 'empty');
        const H = (f, m, r, l) => renderHashByKey[[f, m, r, l].join('|')];
        let reliefDiff = 0;
        for (const f of nonEmpty) { if (H(f, 0, 0, 0) !== H(f, 0, 1, 0)) reliefDiff++; }
        if (!(reliefDiff > 0)) return fail('render nonvacuity: relief0 vs nonzero identical in mode0 look0 for every nonempty fixture (need at least one differing hash)', { renderHashByKey });
        let mode1Diff = 0, mode8Diff = 0;
        for (const f of nonEmpty) for (const r of [0, 1]) for (const l of [0, 1]) {
          if (H(f, 0, r, l) !== H(f, 1, r, l)) mode1Diff++;
          if (H(f, 0, r, l) !== H(f, 8, r, l)) mode8Diff++;
        }
        if (!(mode1Diff > 0)) return fail('render nonvacuity: mode0 vs mode1 identical everywhere nonempty (need at least one difference)', { mode1Diff });
        if (!(mode8Diff > 0)) return fail('render nonvacuity: mode0 vs mode8 identical everywhere nonempty (need at least one difference)', { mode8Diff });
        // Controlled mode0 look pairs only: same fixture/relief, look differs (mode1/8 ignore look).
        let lookPairs = 0;
        for (const f of renderFixtures) for (const r of [0, 1]) {
          const h0 = H(f, 0, r, 0), h1 = H(f, 0, r, 1);
          if (h0 === undefined || h1 === undefined) return fail('render look-pair coverage missing (mode0 look0/look1)', { fixture: f, relief: r });
          lookPairs++;
          if (h0 === h1) return fail('render look path vacuous (mode0 look0 vs look1 identical for same inputs)', { fixture: f, relief: r });
        }
        if (lookPairs < 10) return fail('render look-pair coverage missing (need 5 fixtures x 2 reliefs = 10 mode0 pairs)', { lookPairs });
      }
      return { rows, renderRows, probes: N * cases.length, renderProbes, controlSupported, controlDetected, renderSupported, renderDetected, distinctHashes: hashes.size, renderHashes: rHashes.size, paperRawHash: fnv(paper.bytes), look1AMin, look1AMax, look1Trans, activeRender: activeRenderEvidence, renderBindings: renderBindingEvidence, renderViews, glError: gl.getError(), renderer: gl.getParameter(gl.RENDERER), version: gl.getParameter(gl.VERSION), shading: gl.getParameter(gl.SHADING_LANGUAGE_VERSION) };
    }, { bApplyDef: injected.bApplyDef, cApplyDef: injected.cApplyDef, cApplyPaper: injected.cApplyPaper, xApplyPaper: injected.xApplyPaper, xApplyDef: injected.xApplyDef, bCopyDef: injected.bCopyDef, cCopyDef: injected.cCopyDef, cCopyPaper: injected.cCopyPaper, bQuadDef: injected.bQuadDef, cQuadDef: injected.cQuadDef, cQuadPaper: injected.cQuadPaper, xQuadPaper: injected.xQuadPaper, render: injected.bRender, screen: injected.bScreen }).catch(e => ({ error: String(e && e.stack || e) }));
    if (out.error) {
      console.log(JSON.stringify(Object.assign({ pass: false }, prov, { browser: browserVersion, consoleErrors: errors, error: out.error })));
      process.exitCode = 1;
      return;
    }
    if (out.failAt !== undefined) {
      console.log(JSON.stringify(Object.assign({ pass: false }, prov, { browser: browserVersion, consoleErrors: errors, failAt: out.failAt, note: out.note, rows: out.rows, renderRows: out.renderRows })));
      process.exitCode = 1;
      return;
    }
    const pass = out.glError === 0 && out.rows.every(r => r.okDef && r.okPap) && out.renderRows.every(r => r.ok) && out.controlDetected > 0 && out.renderDetected > 0 && !!out.activeRender && !!out.renderBindings;
    const res = Object.assign({ pass }, prov, { browser: browserVersion, consoleErrors: errors, cases: out.rows.length, probes: out.probes, renderProbes: out.renderProbes, controlSupported: out.controlSupported, controlDetected: out.controlDetected, renderSupported: out.renderSupported, renderDetected: out.renderDetected, distinctHashes: out.distinctHashes, renderHashes: out.renderHashes, paperRawHash: out.paperRawHash, look1: { aMin: out.look1AMin, aMax: out.look1AMax, transparent: out.look1Trans }, activeRender: out.activeRender, renderBindings: out.renderBindings, renderViews: out.renderViews, glError: out.glError, renderer: out.renderer, version: out.version, shading: out.shading, rows: out.rows.map(r => ({ case: r.case, fixture: r.fixture, okDef: r.okDef, okPap: r.okPap, brokenPaperBits: r.brokenPaperBits })), renderRows: out.renderRows.map(r => ({ fixture: r.fixture, mode: r.mode, relief: r.relief, look: r.look, origin: r.origin, layerScale: r.layerScale, pan: r.pan, zoom: r.zoom, bytesDiffer: r.bytesDiffer, brokenBytesDiffer: r.brokenBytesDiffer })) });
    console.log(JSON.stringify(res));
    if (outPath) fs.writeFileSync(path.resolve(outPath), JSON.stringify(res, null, 2), 'utf8');
    if (!pass) process.exitCode = 1;
    if (errors.some(t => /shader.*error|compile.*error|link.*error|GL error/i.test(t))) { console.log(JSON.stringify(Object.assign({ pass: false }, prov, { error: 'browser log contained actual shader GL error', consoleErrors: errors }))); process.exitCode = 1; }
  } finally {
    await browser.close().catch(() => {});
  }
})().catch(e => { console.error(String(e && e.stack || e)); process.exit(1); });
