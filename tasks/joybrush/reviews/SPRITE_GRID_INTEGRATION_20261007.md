# Sprite usability and branch reconciliation — 2026-10-07

Owner reported that unlocked corner resizing refused with “Sprite board size must be a whole grid of cells.” The handles had changed only the rectangle, leaving the cell dimensions unchanged. The owner explicitly asked for easier exact sizing, live board/cell readouts, editable numbers and locking to separate resizing from cell interactions.

## Implemented behavior

- One atomic model resize changes rectangle and grid together, retaining columns/rows. Cell dimensions snap to whole pixels using existing SpriteGridMath, with 1–4096 cell-edge bounds. Left/top drags retain the opposite edge; typed board sizes retain top-left. Paint/vector addresses and existing artwork stay put.
- Drag previews update the ring, board size, cell size and layer-preview scope without history. Release commits once; Cancel restores the saved geometry. Stale board/drawing changes cancel/refuse the captured operation.
- Board dimensions remain tappable above the board. A permanent tappable cell-size readout sits below the grid shelf; its form edits both dimensions. Pixel inputs and steppers resize cells and the whole grid while retaining counts. Forms label numeric fields and select their current value for replacement.
- Unlocked fingers and stylus/eraser pass through cells to painting. Locked finger taps build a transient CellRoll in tap order; the preview uses bounded composited cell thumbnails and the shared PlaybackClock. Play/pause preserves the paused entry; clear resets the roll. No preview writes a saved cursor or creates Undo.
- The separate rearrange switch arms locked finger swaps. Lift follows the pointer, target ring follows the target cell, wiggle honors reduced motion. Release replaces both cells across all layers and masks as one Undo; cancel/same-cell/outside drops create no content transaction.
- Generic RegionTransfer plans split at both cells’ saved Animation ownership boundaries; hidden/held/linked layers preserve their physical-cel semantics. Raster executor splits both tile grids, reads immutable before-state sources, replaces transparent pixels and white-default masks, stages copy-on-write textures, then publishes once. Failure rolls back. Future INK uses the common plan; unsupported raster backends refuse before changing anything.
- Swaps use a conservative bounded allocation budget (128 destination tile textures across layers/masks); oversized painted swaps refuse atomically. No brush, paper or shader algorithms were modified by board work.
- Sprite thumbnails invalidate on neighboring Animation saved-frame changes and pause/resume. Only the current entry and up to 16 look-ahead entries are requested, avoiding cache churn on long sequences. Failed readback refuses rather than returning stale reusable bytes.

## Verification and delivery

Final combined suites pass: core 1642 tests (four optional corpus skips), Androidkit 275, native Android 68; zero failures/errors. Regression includes nearest-cell snapping, anchors/limits, live preview without history, one commit, typed cell growth, pointer dispatch, transient sequence/clear, swap planning, exact CPU swapback/blank erasure/masks, both ownership/tile boundaries, GPU preflight rollback, stale callbacks, pause/resume and a 400-cell sequence cache budget.

Note9 is disconnected during this pass. The previous installed APK remains the October 6 acceptance build; these new resize/preview/swap controls have NOT been phone accepted or installed. Required phone pass: unlocked corner/edge resize and live readouts, typed board/cell dimensions, Undo/Redo/Cancel, pen painting while locked, ordered preview/play/pause/clear, painted/blank/all-layer/mask swaps, negative/tile-boundary cells, swapback and one-step Undo. No new artwork migration is required.

Watcher APK build succeeded in 39 seconds (276 tasks, seven executed) from source f282cf11. APK: `C:/Temp/jb-region-routing/app/build/outputs/apk/default/debug/app-default-arm64-v8a-debug.apk`, SHA-256 `B9D793656E3D6DD8F8670967EB47F7962988E096EE0A6CA842F467888B4E73E8`. Source f282cf11 was pushed to both `origin/codex/region-routing` and `origin/joy-creator`; final later commits only record this delivery. Install only after checking the actual APK fingerprint; no new phone acceptance claim.

## Main-folder repair

Main was seven divergent commits ahead and 58 behind origin, rather than seven unsent source files. Six local coordination documents were separately backed up with staged/unstaged patches, then checkpointed as 969d8126. An abandoned empty October 2 index lock was removed only after no live Git process and exclusive access were confirmed. Three incoming untracked handoff documents were hash-verified/backed up; unique brush closeout content was preserved in 16e12ee6. All other untracked files remain untouched.

Claude’s published origin updates through 0b79b040 were merged into current boards (dbecf7d8). Main ancestry was reconciled in 19d1b366 while retaining the current region architecture; obsolete whole-layer CelProjection/FrameProjection additions were excluded, locked design status and historical decisions preserved, coordination conflict hunks kept, and the latest verified roadmap status retained. Main fast-forwarded to the combined descendant. Normalized-blob-identical stat/CRLF noise was refreshed without rewriting source content.

Claude media checkout still has nine live uncommitted files outside origin. They were left untouched; this pass does not claim those unfinished files were integrated. Shared source is ready for further paper/brush commits without resurrecting the old single-board projection model.

## Still open

Phone acceptance of this pass; Studio/SpriteLab project receiver bridges; Sprite export sheet metadata should label cell count rather than one static frame. Broader boards still need seamless tile preview/wrapped painting, onion skins, bounded Animation geometry/duplicate/remove/move-all transactions, final forms/contrast and fill pen. This checkpoint does not declare all boards complete.
