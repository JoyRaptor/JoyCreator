# Joy Brush audit and integration handoff

Started September 30; first fixes completed October 1, 2026.
Owner authorized frontier subagents, engineering fixes and integration without Opus.
Three GPT-6-astra auditors examined persistence, painting and workflow reachability.
Their implementation turns were interrupted by the usage limit. Codex completed and verified
the surviving changes. Existing uncommitted Muse reviews and tuning work were preserved.

## First completed slice

- `CanvasSnapshot.merge` retains the opened document's identity, complete board rectangle,
  board settings and active board (including null), paper options, locked flags, thumbnail,
  and paint/mask cel IDs. Live layer order/settings and pixels replace the corresponding
  projection, with payload addresses remapped together with the cel IDs. Unsupported board
  and animation content remains refused. Wired into `JbCanvasView.readContents`.
- Archive writing now checks declared-to-supplied tile completeness as well as the reverse.
  A missing paint or mask tile is refused before output, preserving an existing healthy file.
- Smudge commit scales every premultiplied channel by stroke opacity. Previously the opacity
  slider was ignored. Shader checks cover transparent and translucent paint and the unchanged
  ordinary coverage branch.
- Save readback failures now belong to their individual request. A timed-out callback cannot
  write later or fail a subsequent request. The readback watchdog stops before disk writing.
- A lost graphics context blocks snapshots and further painting/clearing until a supported
  saved document is reopened. This prevents blank-placeholder autosaves; it does NOT recover
  unsaved marks automatically.
- Layer IDs using the engine's reserved `#mask` suffix are refused before load mutates anything.
  Loaded locked layers refuse new brush strokes and clear. Paper is changed inside the GL load
  transaction, preventing a frame mixing new paper with old pixels.

## Verification

- Standalone androidkit compile and tests: **181 tests, 0 failures/errors**, including 18 new
  snapshot/archive regressions. Archive fixtures are written and read back, not merely inspected.
- Headless Edge/SwiftShader: compiled, GL error 0; smudge, all opacity cases, grain and ordinary
  coverage checks passed. This is a real graphics-driver check, not Note 9 verification.
- Core baseline: **1325 tests, 1 failure**, in the pre-existing UNTRACKED `TuftTuningTest`
  knob enumeration assertion. The newer Sable tuning schema and old tuning work disagree.
  No core files were changed in this slice. Do not call the whole core suite green.
- Running standalone tests while the app watcher rebuilt caused Windows JAR write collisions.
  The isolated androidkit retry passed after the watcher was idle. Do not overlap these builds.
- The app watcher compiled and packaged the first wiring change successfully, but its latest
  rebuild failed with a core JAR write collision and has not refreshed after subsequent saves.
  Final app package is therefore unverified; standalone verification is recorded separately.
- No phone was driven, installed onto, or erased. Device checks remain outstanding.

## Next work, in order

1. **Safe Open transaction.** Read and validate the candidate, wait for the stroke/save queue,
   prevent editing, snapshot and durably preserve the current drawing, THEN replace it. A failed
   preservation must leave the old drawing on screen. The JB-0.08c draft's asynchronous safety
   copy does not gate replacement and cannot meet that promise. Add recent-file access/retention.
2. **Lifecycle recovery.** Startup must finish restore before drawing can start; recover a
   validated `.bak` if the main working file is absent. Add pixel checkpoint recovery on graphics
   restart and lifecycle-aware callback cleanup. Keep the fail-closed snapshot protection.
3. **Runtime document/frame projection.** Boards are export rectangles in ONE unbounded canvas,
   not separate paintings. A board menu alone does not unblock animation. Retain complete
   document/cel payloads, flush the displayed cel before switching, resolve frame-to-cel mapping
   with `DocOps.celFor`, and preserve undo across frame changes.
4. **One complete animation loop.** Create board, animate layer, blank/duplicate/link frames,
   change holds, scrub/play, save/reopen, export GIF; onion skin from the same render source.
5. **Painting reachability.** Fill pen is shipped but currently falls through generic dab drawing;
   selection/transform/flood-fill components have no drawing-screen host. Push is not shipped.
   Wire these deliberately; do not mistake their isolated core tests for a working phone tool.
6. **Studio handoff.** Reuse/extract Studio's existing `createSequenceSheetAndPlace` path and
   pass frame weights. The receiving sequence-placement implementation already exists; the
   Joy Brush sender/receiver contract is missing.

The JB-3.00 draft understates the renderer changes needed and incorrectly suggests separate
per-board art. The JB-0.08c draft merge also loses nonzero board origins and can produce an
invalid active board when the original activeBoardId is null. Do not copy either draft blindly.
Phone priorities: preserve reopened settings, save/reopen painted pixels and masks, verify
Smudge opacity, test background/resume and graphics recreation, then assess non-Sable brush feel.
