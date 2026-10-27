# Unibrush recovery audit — 2026-10-26

Scope: read-only source/evidence recovery requested by the completion coordinator. Read current
ROADMAP.md, AGENT_START_20261026.md, STATE.md, AGENTS.md and GIT_HYGIENE.md from coordinator
C:/Temp/jb-select-reconcile-20261024, observed HEAD a9312fc6b. No build, test execution, phone,
integration, hot-file edit, reset or deletion. Only this outside audit directory was written.
Preserved C:/Temp/lead-uni remains at a5e98f5c3 with its original two dirty paths unchanged.

## Recovery and synchronization

- Published Codex source: codex/unibrush at6a5bdae1d, clean; exact remote head matches.
- Preserved integration: lead/unibrush-u1 at a5e98f5c3. Fresh remote head listing contains no
  lead/unibrush-u1 branch. This source is local-only under that name; no publication performed.
- Relative to coordinator a9312fc6b:31 reachable commits unique to preserved branch,136 unique
  to coordinator; common base ccb75b39d. These are divergent lineages, not a merge-ready claim.
- Beyond published Codex checkpoint there are133 reachable commits INCLUDING merged main;
  only11 first-parent checkpoints. Do not blindly replay133 commits or replace hot whole files.
- Dirty tracked BenchRunner.kt adds nullable jb_lines preference override/restoration and
  lineParity dispatch. Untracked LineParityBench.kt adds the preliminary U8 probe. Copies,
  original diff and byte hashes are included here solely for recovery; never treat as tested.
- Full unique commit list, changed-path inventory, XML-set fingerprints, APK metadata/hash and
  dirty SHA256 fingerprints are in inventory.json. Original outputs were not moved or deleted.

## Source found

| Gate | Recovered source / status | Still required |
|---|---|---|
| U1 | afa102cb5 debug bench can select hidden modes | Current-source exact build/device receipt; keep shipping false |
| U2 | 3361f559f adds modes-note9.png; commit describes lines-OFF raster smoke with Taper refused and11–20ms p50 | Raw per-mode reports, APK binding, nonblank/preview parity and Adreno colour-moment proof; old screenshot/timing is not U8/U9 |
| U3 | e6343400d NativeLines frozen PNG ownership/snapshot/admission, budget and changed-byte guards | Current integration and exact device/save/undo receipts |
| U4/U5 | aa967048f save/readback/open assets and replay with frozen pictures; refusals lifted together | Real View save/reopen/library-absent/recovery/context-loss matrix |
| U6 | 38e20044e LiftShape plus provisional stamp at contact and shaped replacement at lift; fallback-to-pixels on limits | Current integration, failure atomicity, real GPU live/replay and one-undo proof |
| U7 | a5e98f5c3 Select asset staging, picture/dual lookup, move/rebrush transport; wire refusal retained | Current Select reconciliation and move/rotate/recolour/rebrush/undo device matrix |
| U8 | BenchRunner dirty + untracked LineParityBench; SOURCE ONLY | Repair probe acceptance/lifetime contracts, compile/tests, then all11-mode Note9 matrix incl save/force-stop/reopen without library and first edit/context loss |
| U9 | Historical raster p50 only; no recovered current60fps ABBA or lift-to-final receipt | Lines ON/OFF pen spiral/scribble, sustained frame metrics/hitches, atomic final completion timing |
| U10 | Existing bounded-circle PC parity proof and size override; no Note9 Soft2000 >=45fps receipt | Exact2000px measurement and LineEditBudget outcome with clear refusal if exceeded |
| U11 | No recovered half-resolution runtime | CONDITIONAL under current finish contract: only if U10 fails; owner chooses size cap vs waiting. Do not carry old unconditional D10 assumptions forward |
| U12 | SHIPPING_ENABLED remains false | Current root integration/reconciliation, U1–U10 acceptance, then separately authorized activation/device sequence; no Note20 action here |

## Evidence actually inspected

Existing XML artifacts independently count core2704/0fail/0error/4skips (262 suites), backend1175/
0/0/0 (134 suites), app370/0/0/0 (43 suites). These agree with U7 commit prose but there is no
recovered exact-source run receipt: they are HISTORICAL ARTIFACT COUNTS, not tests run now or
acceptance of dirty U8/current coordinator code. Commit U6 claims2703/1175/369; commit U7 claims
2704/1175/370. Retain that distinction.

Preserved arm64 APK:171372221bytes, SHA256
07149d7afbbc51a9b4c533621f94ea3d013a041ae9d8f64a2fe875d6b156f7d7.
Metadata says com.fadcam.beta/defaultDebug, version37/4.0.0-beta9. No source/install/run-ID binding
was recovered. No installed-package query was made. build_check4.log points at the PRIMARY
checkout and cannot certify this integration source. The U2 PNG is the only tracked U2 evidence
recovered in its evidence directory; no registered screenshot ACK/current preservation receipts
were found by this scoped audit. This is not a claim that no further evidence exists elsewhere.

## Concrete source risks before a U8 run

1. U6 JbCanvasView finishStroke cancels the provisional engine stroke before beginStroke/addDabs
   for the shaped replacement. LiftShape's allowance fallback happens before cancellation, but
   an allocation/GL/replay failure AFTER cancellation can lose the provisional drawing unless
   the engine supplies a proven recovery/transaction. Existing LiftShapeTest covers replay,
   predicate and allowance null, not this physical failure path. Root must review/fault-test it.
2. U8 guarded() does not reject callbacks after finished. Timeout removes only its own Runnable;
   queued draw/read/edit callbacks can still continue mutating after abort/restoration. Use a
   generation/lifetime contract that ends every delayed callback and releases only owned hooks.
3. U8 end() restores onNotice unconditionally, potentially replacing a later callback owner.
   BenchRunner's new editableLines restoration also needs durable pref/lifecycle review against
   current coordinator A10 ownership. Do not overwrite current scoped callback safeguards.
4. U8 reports numeric pixelsDiff/undoSteps but has no strict nonempty output gate, exact expected
   undo-count verdict, changed-middle-colour gate or complete acceptance status. visible() falls
   back to empty maps, so zero-diff alone can be vacuous. It performs two recolours, not the full
   move/rotate/rebrush/undo/save/reopen/context-loss matrix. It is a diagnostic seed, not U8 done.

## Corrections to earlier handoffs

The October23 finish contract identifies NativeLines/endStroke as the live publisher and
RecordedScene as debug-only on that source. The October21 claim that an actual RecordedScene
publisher was universally prerequisite is obsolete for this lane. Review current coordinator
publication ownership rather than reinstating that old blanket blocker. U6 physical replacement
failure safety remains a separate real requirement. The finish contract's U1–U7 table still says
missing/partial despite later commits: source recovery supersedes those stale table cells only,
not the unproved device gates. Conditional U11 likewise supersedes the old unconditional claim.

## Ready / blocked checklist and next bounded assignments

- READY: preserve and publish an isolated coherent source-recovery checkpoint, with current
  roadmap/STATE proposals; root sequences exact exclusive paths. Original dirty work stays intact.
- READY: root review of U3–U7 deltas against current NativeLines/save/Select/engine source; reconcile
  narrowly rather than cherry-picking old merge commits or whole hot files.
- READY AFTER CONTRACT: isolated U8 probe repair/tests, with no engine edits; root grants BenchRunner
  sequencing and defines true final-publication/output/pref-restoration contracts first.
- BLOCKED: current implementation verification on exact reconciled source; no shared slot used here.
- BLOCKED: U8/U9/U10 acceptance on exact APK with run reports, nonblank controls, screenshot ACKs,
  Working/root preservation hashes, ON/OFF matrices, cold reopen/library absent and final receipt.
- BLOCKED: switch-on and promotional availability until those gates pass. Owner creative feel is
  separate. No current implementer or device reservation is asserted by this audit.

Small ROADMAP/STATE replacement-row proposals are in ROW_PROPOSALS.md. They are not applied to
canonical documents. Granular evidence stays here; no claim of whole-family completion is made.
