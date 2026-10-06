# Board runtime foundation checkpoint - 2026-10-05

Owner priority: finish boards to JB-3.00a K/K10/G7a; advanced brushes/paper are paused. Future vector brushes share the board/session model. Work is on `codex/region-routing`, not the dirty primary checkout.

## Implemented source

- Live raster engine addresses shared canvas and each board frame separately. Preview/readback project exact current regions; a crossing stroke commits all affected planes in one undo step.
- Multi-board snapshot/save/open retains inactive frames and linked cel identities. Zero mask coverage remains real content; unreadable payload fails the snapshot.
- Layer add/duplicate/delete and mask deletion include document metadata in the same history step. Duplicate copies every physical cel, preserving links within the copy. Pixel replacements and clear route through current region ownership.
- Core board/session, frame edits and cel clone plans accept PAINT and INK metadata. Raster GL/snapshot/CPU rendering explicitly refuse unsupported vector content. OpenRaster retains its existing named vector omissions and renders the raster preview accordingly. This is architecture compatibility, not a vector renderer.
- The canonical locked K/K10/G7a spec and visual reference were restored from primary: the previous integration spec was missing K's body.
- Specialist chrome/shared Studio strip primitives are imported from c5e00346. The staged strip-input improvement adds stable-ID scrub/hold previews with cancellation, not document writes during preview. Import excludes the old Activity patch and old schema.

## Delivery remaining

Activity board controls still need an operational adapter. Animation geometry transactions, selected-board layer previews, playback/hover/onion, fullscreen seamless tile drawing, sprite swaps/sequence and export dispatch remain on BOARD_RUNTIME_PLAN_20261005.md. No phone installation or device acceptance is claimed by this checkpoint.

## Verification

Production commit shader plus GPU blit/scissor ran on Edge WebGL2/SwiftShader against freshly generated Kotlin RegionPaintPlan fixtures: 655,360 pixel comparisons, zero GL errors; includes two boards in one tile and negative-coordinate edges, normal paint and erase. A deliberate one-pixel boundary expansion failed at pixel 6006; restored run passed. This verifies the shader/geometry protocol, not end-to-end native Android engine execution.

Serial integration verification passed: core 1,619 tests / 0 failures / 0 errors / 4 optional corpus skips; androidkit 254 / 0 / 0 / 0; native UI 29 / 0 / 0 / 0 (20 BoardChromeView, 4 export presentation, 5 paper chrome). Initial checks found a projection-cache naming collision, the no-graphics empty-clone path, OpenRaster's raster-preview filtering, and two imported tests hard-coded to document version 5; these were corrected before the passing run. No device run occurred. Full app watcher compilation/packaging passed: `:app:assembleDefaultDebug`, 276 tasks, BUILD SUCCESSFUL. The temporary watcher was stopped after success and its build-slot lock released; no phone install. The first packaging helper attempt hit a Windows log-reader sharing lock; its shared reader was repaired before the successful run.


## Operational adapter notes

Frame navigation must save the board cursor without filling paint undo history with scrub samples. Unrelated layer undo must preserve the live saved cursor where that frame still exists. Hover/play/hold previews must never save cursors or duration; end preview before pen-down so the frozen stroke plan targets saved frame content. Stop interactions before pause, context loss, drawing replacement, and order/identity change. Map board corners through the actual ViewTransform (including rotation); an axis-aligned screen bounding box is not the board's paint boundary. Layer previews use selected bounds/current board frame and return to normal when passive. Animation move must move all linked physical cels once and clear the old shared footprint, including hidden initial-frame content; never migrate current-only art or whole-canvas pictures. See the locked spec before implementing geometry/drop semantics.
