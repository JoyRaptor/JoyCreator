# Adversarial review — JB-0.08b Autosave, reopen, Save a copy, Open

- Reviewer: muse-spark (cross-reviewer; builder was an unrelated subagent — different family; orchestrator applied R11).
- Task status: 🟧 Built. Commit reviewed: `f8f50c62` (HEAD's `JbCanvasView` save/open section identical; only R10 snapshot + R13 `handOver` hunks differ — out of scope).
- Spec reviewed: `tasks/joybrush/specs/JB-0.08b_save_open_wiring.md` (decisions 1–5, verification, Do-not + builder Questions 1–8).
- §5b checks: diff touches only the spec's owner area (`JbCanvasView` snapshot/load/refusal + `JoyBrushActivity` save/open wiring), board row, spec questions — engine/archive untouched per "Do not" (the `strokeInProgress` engine read relies on the Lead's `f87f1dbb` one-liner, not this diff). No JVM tests exist for this task (verification is watcher-green + sandbox device, no adb here); `:androidkit:test` green in a clean HEAD worktree (BUILD SUCCESSFUL), and full `:core:jvmTest` there is 584/3 with the only failures being JB-3.08a's two documented WIP reds (🟨 Claimed, not Built) and JB-5.10's known flaky timing bound — none in this task's path.
- Severity scale per §5b: BLOCKER / MAJOR / MINOR.

## Finding 1 (MAJOR): an explicit "Save a copy…" tapped during any autosave is silently discarded
Proof: `JoyBrushActivity.kt:388-392` — `saveAsync` starts `if (!saving.compareAndSet(false, true)) { repost idleSave; return }`, dropping its `write/failurePrefix/done` arguments. `saveCopyTo` (`:489-506`) goes through `saveAsync`. So a copy tapped while `saving==true` never writes its Uri, shows no toast, and the 30 s retry writes the *working file*, not the copy. Same path drops a pause autosave (`:201` skips when saving, retry only via `idleSave` — which cannot complete while GL is paused, so kill-before-resume loses work). An explicit user save action must never vanish without a word; at minimum the copy needs its own gate/queue, not the shared one.

## Finding 2 (MAJOR): the R11 save-owed flag is consumed by any history event and can be dropped mid-stroke
Proof: `saveOwed` is set in `saveWorkingFile` (`:427`) when `strokeInProgress`, but consumed in `onStrokeFinished` (`:448-452`), which is called only from `onHistoryChanged` (`:161-165`) — i.e. undo/redo/clear as well as stroke end. Sequence: pause sets `saveOwed=true` mid-stroke → user taps Undo mid-stroke (overlay pills are separate views, tappable mid-stroke) → `saveOwed=false`, `strokeInProgress` still true → return without writing. The owed save is gone; stroke end later sees `saveOwed==false`. Conversely `cancelStroke` never calls `reportHistory`, so a `saveOwed` set before a *cancelled* stroke lingers until the next history event (redundant late write, or never). R11's promise ("writes the moment the stroke ends") holds only when nothing else touches history first.

## Finding 3 (MAJOR): "Save a copy…" bypasses the R11 guard and reports success on a pre-stroke snapshot
Proof: `saveWorkingFile` checks `strokeInProgress` (`:427`); `saveCopyTo → saveAsync` (`:398`) does not. The live stroke lives in the engine's stroke buffer, not tiles (`GlPaintEngine` stroke-layer discipline), so `readContents` captures pre-stroke tiles while the success toast (`:492` "Copy saved") claims the drawing. A copy taken with the pen down is missing exactly the stroke the person sees. Same one-line guard as the working file closes it.

## Finding 4 (MINOR): cross-thread `strokeInProgress` race underlies all of the above
Proof: UI reads `JbCanvasView:140 → GlPaintEngine:174 strokeLayer != null`; `strokeLayer` (`GlPaintEngine:71`) is a plain `var` written only on the GL thread, no volatile/lock. A stale read either autosaves mid-stroke (incomplete file, no owed flag) or sets a spurious `saveOwed`. Narrow window, self-heals on the next event — but Findings 1–3's guards all depend on this read.

## Finding 5 (MINOR): no `onDestroy` cleanup — dead-screen callbacks and a 30 s leak
Proof: the file has `onCreate/onResume/onPause/onActivityResult` only; `fileIo` (shared top-level executor, `:83`) is never shut down by design (`:76-82`); `idleSave` (`:136`, 30 s delayed, captures `this`) and `ui.post` continuations (`:470-473, :521`, `saveAsync:411`) have no `isFinishing/isDestroyed` guard, so they can call `canvas.snapshot` on a dead `GLSurfaceView`. `GlPaintEngine.release()` is never called (no `onDetachedFromWindow` in the view either) — rotation/destroy orphans GL textures (pause is covered by `preserveEGLContextOnPause`, destroy is not).

## Finding 6 (MINOR): resave silently drops metadata the refusal logic lets through
Proof: `readContents` (`JbCanvasView:570-579`) copies only colour/visible/opacity/tiles; `load` restores only those + name. `textureScale`, `includeInExport`, layer `locked/blend/name`, board `name/clipToBoard`, and all ids (reset to constants) churn — e.g. a plain-colour doc with `textureScale=2` passes `refusalFor` (only `textureId` checked) and `validate`, opens, and resaves as scale 1. Same for thumbnails: `thumbnailPng=null` always (`:580`), so opening a file *with* a thumbnail then autosaving strips it silently (spec Q7 left the write side open; the strip side is not mentioned anywhere).

## Finding 7 (MINOR): `load` applies paper/name after queueing GL work — one-frame wrong-paper flash
Proof: `JbCanvasView:472-484` queues reset/write on GL, then sets `paperArgb`/`documentName` on the caller thread (`:485-486`, paper setter fires `requestRender`). A frame can draw with the new paper over old/empty tiles before the queued writes run. Narrow ordering issue, visible in code.

## Verified (proof)
- R11 working-file path as committed: atomic via `JbArchive.save` (tmp+fsync+rename+`.bak`); SAF copy builds the whole archive in `cacheDir` first then streams (spec Q1 option (b), implemented without being ruled — the safe choice); refused files toast with the exception message and keep the current drawing; multi-layer/ink/animated/multi-cel/multi-board/non-PAINT/non-CANVAS/paper-texture/tile-mismatch refusals all synchronous before any GL work (`refusalFor`, `:493-540`), one `JbArchiveException` catch.
- Spec Q8's id-ordering guard present (`:566-568` factory-order check); paper colour carried both ways (`#RRGGBB`↔ARGB, alpha forced opaque with the unreachable-white rationale); board sized to view (spec Q5's open question — code picks view size, `1×1` pre-layout edge acknowledged in spec, not invented here).

## Recommendation
Fix Findings 1–3 (all three are "explicit save intent lost or misreported" — the one thing this task must not do); 4–7 are MINOR. Device check (draw → Home → force-stop → reopen) still owed to the owner.

## Addendum 2026-09-29 — reworked by JB-2.15/SaveQueue (R26: `b920689b` + `7bee36ad`), verified
- Finding 1 (MAJOR) FIXED in its primary shape: no gate, EXPLICIT never merges, own destination preserved. Finding 2 (MAJOR) FIXED including the converse (no flag; history cannot release; cancel releases). Finding 3 (MAJOR) HALF-FIXED: the queue hold works but the spec-ordered `perform`-side re-check is missing — pen-down between gate (UI) and snapshot (GL) reproduces this finding's shape; see JB-2.15 Finding 1.
- Finding 4 (MINOR) BYPASSED at the view (UI-owned flag; engine race off the save path). Findings 5–7 still open as referred (no `onDestroy`; metadata churn — explicitly out-of-scope per 2.15 Decision 9; paper/name ordering).
- New issues introduced by the rework (watchdog single-flight break, snapshot-fail stranding, load-completion debt wipe, spec-contract drift) are filed under JB-2.15, not here. This file's original verdicts stand as the record of what was wrong and why the rework exists.
