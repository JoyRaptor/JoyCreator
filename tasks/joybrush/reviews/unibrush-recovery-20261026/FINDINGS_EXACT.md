# Exact follow-up handoff — 2026-10-26

Audit source only: C:/Temp/lead-uni@a5e98f5c3e5ad637467115e3b1981872c60cdba9,
branch lead/unibrush-u1 plus the two preserved dirty U8 paths. These locations are NOT
claims about current coordinator f6287b921. Root must compare/reconcile current hot files.
No build/test/device reproduction was performed. Findings below are source-derived fault
sequences and proposed verification inputs, not observed phone failures.

## Artifact / Git / remote status

- README.md: full recovery status, U1–U12 checklist and superseded-contract corrections.
- inventory.json: exact source/ref status,31 unique commits against audit-time coordinator
  a9312fc6b, changed paths, dirty byte hashes, historical XML set fingerprints and APK SHA256.
- ROW_PROPOSALS.md: small broad roadmap row and truthful STATE availability/help/reuse row.
- U8_BenchRunner_preserved_dirty.patch: original tracked dirty delta, not applied anywhere.
- BenchRunner.kt and LineParityBench.kt: byte copies of the original dirty inputs.
- FINDINGS_EXACT.md: this precise source/trigger/expected-proof supplement.

All files above are under C:/Temp/jb-unibrush-recovery-audit-20261026.
This outside report directory has NO Git commit or remote publication. No source commit,
branch change, push or canonical document edit was made in this recovery audit.
Fresh remote listing: codex/unibrush equals local6a5bdae1d617b4fe3100f051a34aa431de4765ed;
lead/unibrush-u1 has NO remote head under that name. Preserved original remains local-only.
Remote/docs metadata reported by coordinator can advance independently; this supplement
makes no comparison/integration claim against f6287b921 or efcb293bb.

## U6-F1 — provisional pixels released before replacement succeeds

Source: joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/JbCanvasView.kt
finishStroke at2057, shaped replacement2115–2131, cancellation2119, replacement begin2122,
addDabs2124, pending line/endStroke2131. boardStrokeWork error wrapper556–565 cancels on
Exception and reports refusal; it does not restore the released provisional stroke.

Engine source: gl/GlPaintEngine.kt cancelStroke5231–5240 calls releaseStrokeTiles5234.
releaseStrokeTiles6550–6560 ends masks/clears colour targets, recycles stroke textures and
clears strokeTiles. beginStrokeBuffer4791 itself also starts by cancelStroke. Keeping an
extra reference to the old map alone is therefore not a proven recovery mechanism.

Input/fault sequence to verify:
1. Lines OFF, modern Unibrush Taper, valid bounded raw samples, nonempty provisional dabs.
   Repeat with lines ON and taper/dash + frozen image/dual cases supported by admission.
2. Pen UP; LiftShape.shaped returns a nonempty result (do NOT use allowance refusal).
3. Inject failure after2119 in replacement beginStroke allocation/mask preparation, then
   separately during addDabs or final commit. Provisional ownership is already released.
4. Observe physical pixels, history depth, pending line/PNG resources and cleanup on refusal.

Expected gate: original/provisional drawing survives, or a separately prepared shaped result
publishes atomically; no lost stroke, partial final pixels, dangling line/assets, double undo
or cleanup failure hiding the primary error. Also prove success equals replay, exactly one
undo, and cancellation/context loss. Existing LiftShapeTest proves dab replay/allowance null,
not this physical failure path. This is a concrete potential loss path, not a claim every
allocation necessarily throws or that it was reproduced on device.

## U8-F1 — queued callbacks can mutate after timeout/abort

Untracked LineParityBench.kt: timeout38–45 calls end then abort; initial delayed drawn52;
GL before callback58–65 and delayed committed64; recolour callbacks85–86/99–105;
guarded151 catches Exception but has NO finished/generation check. end153–157 sets finished
and removes only the timeout Runnable. finish136 checks finished too late to block earlier
queued drawing/edit mutations.

Input: start a jb_lines=true lineParity run; withhold a benchState callback beyond60s so timeout
finishes and parent abort/restoration runs; then deliver that callback. The callback still
enters guarded, can play the stroke at62–64, or a late committed/read callback can issue a
recolour at85/99. Test each delayed boundary, including parent Activity/runner teardown.

Expected gate: a generation/owner token rejects every post-terminal mutation and delivery;
queued replays are cancelled/drained under the actual replay contract, and terminal cleanup
runs once. Merely checking finished inside finish() or cancelling only timeout is insufficient.

## U8-F2 — cleanup overwrites later callback / preference ownership

LineParityBench.kt saves priorNotice34, installs wrapper47, and unconditionally writes priorNotice
back156. Input: after start, another owner replaces canvas.onNotice, then timeout/finish fires.
Current cleanup overwrites that newer owner. Restore only if still this probe's exact callback;
report replacement/inconclusive instead of suppressing it. Colour restoration157 likewise
needs an explicit ownership/lifetime policy if later user/runner changes are allowed.

Dirty BenchRunner.kt captures editableLines36, sets spec.lines106, restores linesBefore48,
parses jb_lines437. These are source-only and must reconcile with CURRENT A10 durable preference,
user changes, cancel/destroy and active-run ownership. No durable restoration test exists in
this dirty patch. Do not replace the coordinator's newer A10/Runner safeguards with this file.

## U8-F3 — zero difference can mean no pixels, and numbers are not acceptance

LineParityBench.kt visible130 returns planes["visible"].orEmpty(). compare109–127 returns(0,0)
for two empty maps. committed87–91 emits diff/undo/recoloured diff without requiring nonempty
nonzero alpha output, changed intermediate colour, exact undo counts, or an explicit PASS verdict.
The no-line branch80–81 also finishes and advances rather than establishing full U8 acceptance.

Input: valid line metadata but absent/empty/zero-alpha visible planes at A/M/B, or no-op replay/
recolour returning identical pixels. Another input: arbitrary undoDepth difference. Current code
can emit pixelsDiff=0/maxByteDiff=0 with no strict failure gate; it does NOT itself claim PASS,
so downstream readers must not interpret that numeric pair as a passing U8 receipt.

Expected gate: require correctly dimensioned nonblank A/B, exact A/B parity within agreed format
oracle, demonstrably changed M for an eligible colour-sensitive fixture, one stroke undo step
and separately one step per edit; no-line/refusal/blank/replaced hook/timeout must be explicit
inconclusive or failed cases. Use meaningful negative controls and actual physical output.

This probe only draws slowC and recolours twice. Move, rotate, rebrush, each undo/redo, save,
force-stop/reopen without library, first edit and context loss remain separate U8 cases.

## U8/U10-F4 — size override excludes lineParity

Dirty BenchRunner.kt Spec init84 accepts sizeOverridePx only with its listed ordinary motion
cases; lineParity is not listed. Input cases=["lineParity"], sizeOverridePx=2000f (via jb_bench_size_px)
throws before running. Thus this probe cannot currently supply Soft2000 editable-line acceptance.
Root should explicitly decide supported fixture/override semantics before adding that case;
this does not invalidate ordinary Soft2000 contact benchmark support.

## Evidence limits / next exclusive contract

Historical XML artifacts independently count2704 core/1175 backend/370 app, no failures/errors,
four optional core skips. No exact-source or current-source execution inferred. Preserved
arm64 APK hash07149d7afbbc51a9b4c533621f94ea3d013a041ae9d8f64a2fe875d6b156f7d7 is NOT an
installed APK/run receipt. No heavy/phone slot requested; UX retains next focused job.

Root owns U6 hot engine/View review and physical transaction contract. A next isolated U8 fix
can own only a new probe/guard/helper/tests after root sequences BenchRunner and current A10
pref/replay completion APIs. Publish reviewed granular evidence plus small ROADMAP/STATE rows;
keep shipping OFF. Conditional U11 follows U10 measurement/current finish contract, not the old
blanket half-resolution prerequisite. NativeLines/endStroke is the preserved source's live
publisher; do not reinstate the obsolete universal RecordedScene prerequisite.
