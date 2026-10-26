# Unibrush U8 guard source — 2026-10-26

Status: SOURCE ONLY / tests UNRUN / not connected to a runner / shipping unchanged.
Base f6287b921, branch codex/unibrush-u8-guards-20261026, isolated worktree
C:/Temp/jb-unibrush-u8-guards-20261026. Read current roadmap/STATE before preparing this leaf.
No build, compiler, test execution, phone job, activation or integration. UX retains next slot.
No original preserved file, runtime hot file, native-line publisher or current A10 hook changed.

## Recovered facts and exact findings

[Recovery checklist](unibrush-recovery-20261026/README.md),
[precise file/line/fault inputs](unibrush-recovery-20261026/FINDINGS_EXACT.md), and
[inventory/byte fingerprints](unibrush-recovery-20261026/inventory.json) preserve the audit.
Those historical source/XML/APK artifacts are NOT current source/device acceptance. The earlier
outside audit has no commit; this branch publishes only reviewed audit documents and new leaves.
The original C:/Temp/lead-uni@a5e98f5c3 tracked BenchRunner delta and untracked LineParityBench
remain unchanged. No remote lead/unibrush-u1 head was found; Codex6a5bdae1d matches its remote.

U6: original JbCanvasView2119 cancels provisional pixels before replacement begin2122/add2124;
boardStrokeWork556 catches exceptions by cancelling again, not restoring. Engine cancel5231
releases stroke targets. Root owns review/physical failure injection before adoption. No claim
that this failure was executed, or that current coordinator uses that same implementation.
U8: old guarded151 ignores finished; end156 overwrites onNotice; visible130 maps missing reads
to empty maps; numeric diff87–91 does not require nonblank/change/history verdicts. Precise
fault scenarios and expected assertions are in FINDINGS_EXACT. Spec init84 excludes lineParity
from sizeOverride:2000px editable-line parity needs an explicit supported contract too.

## Exclusive source prepared

- UnibrushProbeLifetime.kt: one UI-thread probe lifetime; wrap each delayed draw/read/edit
  callback. Closing blocks queued and reentrant callbacks. Notice lease restores only its own
  exact hook; replacement is preserved and marked. Late wrapper delivery cannot reach either
  probe or old handler. Does not own Activity, replay cancellation or durable preferences.
- UnibrushParityEvidence.kt: a necessary recolour/replay subcase oracle using existing
  MatrixDiff. Requires all four visible-plane readbacks, exact RGBA8 tile lengths, nonnegative
  history/line counts, one additional line, one stroke step and one step per recolour, nonblank
  A/M/B, stroke/middle changes from baseline, changed middle colour, exact final replay.
  Missing/allocated-empty tiles still compare by picture, with malformed inputs rejected first.
- Two JUnit4 source suites:17 tests. Negative controls include empty/missing/truncated output,
  unchanged opaque background, middle clearing/no-op, single-byte/position changes, bad history,
  lost/additional lines, excess tile metadata, stale old-run callbacks, reentrant termination,
  later hook ownership and idempotent restoration. These are uncompiled and UNRUN.

The oracle borrows immutable readback bytes synchronously; it does not copy or retain four
pictures. The future host must fence/read the same run and plane and prevent mutation while
checking them.64 tiles per frame bounds this diagnostic, not the app's drawing/brush capacity;
an over-budget read is inconclusive and needs a separate bounded acceptance strategy. No actual
GL alpha/format, renderer, live undo, screenshot ACK, APK identity or physical pen claim follows.

## Integration and next proof contract

Root first reviews these leaves against current probe/A10/Runner ownership. Do NOT cherry-pick
old BenchRunner/JbCanvasView/GlPaintEngine whole files or old merge commits. After sequencing:

1. Compile and run only these two new test classes under the current shared heavy-job contract;
   bind fresh XML to exact source. No test receipt is supplied by this source-only checkpoint.
2. Connect the helper to a reconciled probe and guard EVERY async boundary, including replay
   scheduling/cancellation and pending GL tasks; close lifetime before abort/restoration. A bool
   cannot cancel an already launched GL command. Actual generation/retirement remains host-owned.
3. Close the notice lease, reject ownerChanged, and reconcile durable editable-mode preferences
   using the current Select-owned preference/runtime APIs; do not silently override them.
4. Add actual baseline readback plus A/M/B, independent captured-line identity/seed/PNG checks,
   agreed output format and expected undo/edit counts. A true result certifies ONLY this subcase.
5. Prove U6 replacement failure safety, then the full U8 matrix on exact APK: all11 modes with
   lines ON/OFF; move/rotate/recolour/rebrush and undo/redo; save/force-stop/reopen library absent,
   first edit/context loss; registered captures and Working/root preservation receipts.
6. U9/U10 performance, lift-to-final and edit budget remain unmeasured; U11 is conditional under
   the recovered finish contract. Activation stays OFF. Owner feel remains separate.

## Publication and documentation

Only four new source/test leaves plus audit/handoff and small ROADMAP/STATE row edits are owned.
Publish this branch without force and compare local HEAD with remote; final handoff gives that
exact SHA. The coordinator reconciles only the two changed rows into its current documents.
No broader runtime merge/activation or claim of major feature completion is proposed. Help
points at the recovered spec; published end-user help is pending. Reuse is limited to these
bench guards/MatrixDiff and the preserved brush/PNG/preview machinery under its own ownership.
