# Select lines continuation — October 25, 2026

Worktree: `C:/Temp/jb-codex-select`, branch `codex/select-bench-20261025`,
based on the Coordinator's frozen `0c77938c6`. The original `codex/select-lines`
delivery remains at `50109f2a6`; the newer branch contains clean scoped adoption,
not a replacement of the Coordinator's checkout.

## Built source, verification pending

- Canonical Copy crosses the actual Activity → View → GL metadata boundary. It
  preserves layer order, requires engine-issued source proof, refuses unknown or
  clipped sources, resolves once across timeout/context loss, and drops callbacks
  from an old drawing. Failed Copy preserves the previous session clipboard.
- Copy/Paste memory admission counts conservative decoded-brush allowance. Paste
  rechecks available heap before decoding/transforming; an allocation failure during
  preparation reports refusal and discards only newly staged brush definitions.
  Publication remains outside that catch so a submitted edit is not described as absent.
- Actual View regression fixtures now include accepted Copy → drawing epoch switch →
  fresh empty destination, plus overlapping layer order, unknown source, stale callback,
  timeout/late work, and concurrent/lost-context cases. Two injected Paste-failure
  regressions exercise preparation and brush staging without allocation stress.
- The phone runner binds source receipt, preserved APK, installed SHA-256 before/after,
  Note 9/API identity, fresh run ID, consecutive events, exact step/screenshot manifest,
  temperature readings, and complete PNG validation. Foreign locks are preserved;
  an unresolved launched bench keeps its own claim. No logcat/data clearing or install.
- The Android bench now has 20 cases and 11 captures, including real UI Copy/Paste,
  Group, and Lock with published-record/history checks. The session clipboard is
  restored on ordinary bench completion. Additional A1–A10 phone cases are still owed.
- A10/K25 sheet and drawer source reads the shared `BrushLinePreferences` per brush ID.
  Eligible sheets offer an Editable lines switch; pixel-only sheets show the exact D22
  sentence. Both drawers show the native 12 dp dotted-corner/node-curve glyph in 80%
  drawer ink. The stale sheet callback is guarded by brush ID. Obsolete D2 global
  controls and the temporary priority caption are retired. More/current-brush policy
  hooks use Vector's reviewed `3043aaf14`, not a competing implementation.

## Evidence and limits

- Offline runner tests: **14 passed** with mocked ADB and real temporary Windows
  lock-file operations. Existing offline validator: **29 passed**, independently
  rerun together (43 total). Bash syntax and exact
  20-case manifest/source names checked. These are not Android or device evidence.
- New Copy/Paste, benchmark, and A10 Kotlin source/tests **have not been compiled
  or executed** at this checkpoint. Available RAM remained below the 2200 MiB focused
  build admission floor. No competing Gradle job or phone claim was started here.
- `select_bench.sh C:/Temp/jb-select-acceptance-20261025/runner-preflight` was invoked
  and correctly returned **1**, requiring the new candidate's successful-build receipt.
  Log: `C:/Temp/jb-select-bench-preflight-20261025.log`. This is a preflight refusal,
  not a benchmark run or phone acceptance. Fresh adb inventory contained no devices.
  The A10 checkpoint invocation likewise returned 1 before launch; its separate log is
  `C:/Temp/jb-select-a10-preflight-20261025.log`. Neither invocation held a phone claim.
- Earlier Coordinator evidence stays attached to its exact source: 25 HSB/Sync cases
  passed on `9ace0963c`; backend `ca141cce5` ran 21 cases with three fixture-constructor
  failures (Engine 8 and Reply 5 passed). The fixture fix `1edcfff89` was adopted but
  not rerun here. None of those counts is transferred to this candidate.
- K25 policy `f87402bc4` and fixture fix `ba341098b` are source dependencies only:
  the prior eight-case run had one fixture assertion failure, fixed but not rerun.

## Remaining

- Coordinator's exact-source app/backend/core verification, candidate APK, and real
  Note 9 `select_bench.sh <out>` after each step. A10 sheet on/off/no-switch and both
  paper-tone drawer captures remain owed; any benchmark preference toggles must restore
  the exact prior per-brush key presence/value on completion.
- D23 notices must come from confirmed successful engine publication. `onStrokeEnded`
  currently runs before queued GL publication and cannot prove the first pixel stroke
  happened. Smudge lacks the runtime atomic split/bake success hook. No fake notice
  was added. A9 Slice runtime, C3 display hook, K26 realistic-paint ordering, and Ring
  Bend/Phase B remain their existing dependencies, not claimed complete by this work.
- Preserve worktrees and evidence while unfinished. Only this lane's Python bytecode
  cache was removed; no shared builds, watcher processes, other lanes' files, or logs
  were deleted.

## Coordinator review follow-up

Interrupted Select benches now restore the captured clipboard/canvas mode once via
Activity pause/destroy, cancel their current synthetic contact with CANCEL, discard
queued gestures, and suppress late metadata continuations. Two Android regression
fixtures cover abort idempotence and partial-contact cancellation; execution is owed.
Host collection rejects new matching reports/SHOT requests after terminal evidence.
Windows claim cleanup verifies token and captured file identity under a pinned open
handle, then deletes that object on close. Changed identities/tokens or sharing/access
uncertainty leave the claim intact. Receipt generation from a successful frozen build
remains a separate required gate; the runner cannot independently prove its author's
source-to-APK assertion.

Queued Activity/View Copy results now capture a read-only predicate for the exact
Select benchmark session. Abort and ordinary completion invalidate it before clipboard
restoration; late Ready/refusal callbacks cannot change the clipboard or show a toast.
Normal non-benchmark requests capture no such guard. Two additional actual Activity/View
metadata regressions keep the Activity alive and drawing epoch unchanged while testing
abort/completion, so they do not depend on `isFinishing` or drawing-switch protection.
Both new Kotlin cases remain unexecuted under the same resource gate.
