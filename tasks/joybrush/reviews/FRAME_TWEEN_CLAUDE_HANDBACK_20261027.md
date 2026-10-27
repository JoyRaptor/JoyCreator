# Frame/tween source handback — October 27, 2026

Bounded source deliverables are complete and published; product/runtime acceptance remains open.
No source changes or heavy/phone jobs were made on the combined native candidate by this lane.

## Exact source and adoption

- Frame probe base `717f6c326`, original source `7b23ce906`, adversarial V2 `11ca8c132`.
  Own branch `codex/frame-duplicate-probe-20261026` in
  `C:/Temp/jb-frame-duplicate-probe-20261026`. Pre-handback published tip `f3f364c42`.
- Lead combined candidate `c971c8c0cc7628dee7ca89efa301741c208eedcd` already adopts V2.
  Comparison against V2 shows only diagnostic comments and removal of dispatch/device from
  the post-success remaining-gates list. Do NOT blindly reapply the old zero-context patch.
- Frozen-brush tween source `2ab6cd4c4`; preserved branch
  `codex/tween-wash-fidelity-20261026`, published tip `80ebe10335003e5f43261807ad98b3db15cfc7c2`,
  checkout `C:/Temp/jb-tween-wash-fidelity-20261026`.
  That branch's `tasks/joybrush/handoffs/FROZEN_TWEEN_FOCUSED_GATE_20261026.md` and adjacent JSON pin
  the two source blobs and seven test methods. No generic matrix interpolation is introduced.

## Actionable read-only findings on c971

1. **P1 — partial initialization cannot be reclaimed by release.** GlPaintEngine.initWith
   (around620) sets ready=false, creates GL objects, then sets ready=true only on success.
   createGlObjects assigns programs, buffers, VAOs, framebuffers and textures incrementally.
   If later creation throws, release (around810) returns immediately on !ready, so the new
   frame/watercolor dispatch finally blocks cannot reclaim already-created same-context objects.
   Add exception-safe initialization ownership/rollback using the actual physical epoch before
   claiming initialization-failure cleanup. Never delete handles from a lost/replaced context.
2. **P2 — watercolor cleanup failure loses the completion callback.** JbCanvasView's recorded
   probe finally (around3026) calls release without catching cleanup exceptions. A throw there
   escapes before post{ready(report)}, even when a failure report was already constructed, so
   BenchRunner cannot receive that outcome or continue. Catch cleanup failure into passed=false
   and guarantee the failure callback. The frame dispatch's cleanup around3053 already has that
   reporting pattern. Initialization rollback above still needs its own fix.

These are source findings, not reproduced device failures. No additional actionable V2 copy or
false-pass defect was found by inspection. Unexecuted code is not proven correct by this review.
Root retains all engine/View/BenchRunner edits. Shared candidate peer work was preserved.

Lead subsequently granted a separate narrow engine-init/GlProgram cleanup implementation on
`codex/native-init-cleanup-20261027`, from`c8e116205`, leaving View/app cleanup with root.
See that branch's `reviews/NATIVE_INIT_CLEANUP_20261027.md` and focused-gate manifest.
This supersedes engine-init ownership only; the combined candidate itself remained read-only.
Candidate c8e116205 differs from c971 only in Select files; audited engine/View source is unchanged.

Proposed focused fault cases: fail a later shader after a successful vertex/program; fail fragment
compilation/status/log retrieval, program attach/link and resource initialization after glGen;
require exact-once current-attempt cleanup and readiness=false. Switch context/epoch before or
during cleanup and require no stale-name deletion. Make one cleanup action throw and require
original failure plus suppressed cleanup while other names still retire. For root's View fix,
inject release failure on both success/failure paths and require one passed=false completion
callback with cleanup detail, no lost BenchRunner continuation. These cases are not device proof.

## Evidence and remaining gates

- This lane ran no Gradle compilation/tests or GPU/phone action in this final push. It performed
  read-only source/diff checks and a Luna inventory. Owned source/test names and pinned tween
  blobs match the gate manifest. Whitespace/patch checks are mechanical checks only.
- The lead's earlier exact717f6c326 focused26 pass covers core duplicate6, board memory11,
  initialization4 and prepared copy5, with Kotlin compilation. It does not cover V2 or c971.
- Frame CPU oracle4 and frozen tween metadata7 remain UNRUN by this lane. Obtain fresh XML
  and exact candidate hashes under root admission; preserve historical results separately.
- Frame GPU oracle must actually execute on a disposable engine with nonempty pigment,
  patterned negative/odd edges and wholly outside bases, strict RGBA8 union/halo, independent
  outputs, unchanged source/shared canvas, target Delete and exact one-step Undo/redo.
- Low-budget refusal, injected publication failure, actual resource retirement, archive/save/
  reopen, watercolor frame duplication, captured/joint support, live tween rendering/playback/
  export and clip reconciliation remain open. Native wash/grain quality and owner acceptance
  remain separate. Retain all unsupported guards.

Next action: root resolves/reconciles the two cleanup findings, reviews candidate scope, then
admits the focused test gates and disposable debug dispatch in the shared queue. This handback
requires no owner action. STATE feature activation is unchanged; root owns its canonical edit.

## Workspace/resource handoff

Both owned checkouts were clean before these documentation-only handback edits; exact final
published HEAD/remote matches are supplied in the coordinator message. No jobs, subprocess
workers, resource locks or phone claim are held by this lane. Existing shared candidate staged
Select files were not touched. Preserve all worktrees/evidence; no cleanup deletion is authorized
while the product gates above remain unfinished.

Narrow init cleanup source is now `41dbdb38a`, published with selective code/roadmap patches
at `422caee76` on `codex/native-init-cleanup-20261027`. Source-only focused gate is20 checks:
new ledger5, new constructor-flow7, existing context-loss8. No compilation/test execution or
GL proof was performed here. Root may selectively integrate and run after queue admission.
