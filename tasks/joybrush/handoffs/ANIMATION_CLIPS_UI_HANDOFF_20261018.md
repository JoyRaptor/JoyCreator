# Animation clips UI implementation and integration — 2026-10-19

Source: `lead/anim-clips`, `C:/Temp/jb-clips`, based on `d232a2b17` (including earlier C1/C2).
Spec: `tasks/joybrush/specs/ANIMATION_CLIPS_20261018.md`.

The requested scope includes the engine and U1–U8; the UI work is implemented in this branch.

## UI contracts

- `BoardChromeLayout` supplies four saved end chips below “When it ends”: Loop, Ping-pong, Hold and Blank. `BoardChromeView` renders the selected chip solid cyan with cut-out text/icon in an isolated layer. Each chip has a descriptive hover/accessibility label. Narrow viewports wrap the transport and clamp the chip block into view.
- The transport exposes labeled Play all and Stop all. `BoardRuntimeController.playAll()` owns one scheduler and submits one changed preview map. Single Play and hover stop Play all; Stop restores saved frames and onion settings. Hold and Blank previews persist until Stop.
- Both overlapping clip outlines remain visible; selection brings the chosen outline above the others. Repeated finger taps in the overlap cycle clips in stable order. Stylus/eraser events continue to painting. Actual controls and edge drags retain priority.
- Clip menus offer “Combine with <name>” for touching/overlapping clips. Different speeds produce the retiming sentence. One Undo restores both clips. Selection moves to the combined clip.
- New clips animate only the active layer. The first creation shows “Only <layer> animates here. Tap the runner on a layer to add it.” Combine does not show that creation hint.
- A rejected overlap names the layer and both clips once per gesture. Transient movement can recover to a valid position before release.
- Unlocked edge drags move every animated drawing with a transient preview, then commit one Undo step. Cancel restores the saved projection. Shared still pixels and masks remain stationary. The lock blocks direct movement.
- Layer runners use “Animates in <clip name>” / “Still in <clip name>”, refreshed after rename.

## Engine and persistence contracts

- `Board.end` persists loop/pingpong/hold/blank; unknown values read as loop. Preview cursors and blank projection are never saved.
- `AnimationBoardOps.combine` returns a transactional `RegionChange`. Apply through `GlPaintEngine.applyBoardChange`; do not separately mutate board metadata and pixels.
- Combined touching clips may use component drawing references. Document v12 introduced saved end behavior; v13 introduces acyclic `Cel.parts`. Cloning remaps the graph, pruning follows transitive references, and paint/render/capture resolve disjoint physical fragments. Repeated drawings retain edit identity across differing composite pairs.
- Flatten samples the original scene before publishing all frames in one Undo. Merge Down isolates only the current frame of linked/component drawings. Different overlapping clips offer Combine first.
- `BoardExportCoordinator.showScene` offers Longest / Full loop and GIF / PNG frames. Requests survive recreation and verify the captured scene signature. GIF uses up to 50 fps with cumulative centisecond timing. Actual encoder cap warnings join the export completion message.
- `BoardChromePenFade` initializes from the supplied resting pen state, so startup cannot invent a recent lift and leave the controls folded.

## Verification

Final test counts, Note9 measurements, archive/export proof, and screenshots are recorded in `tasks/joybrush/reviews/ANIMATION_CLIPS_20261018.md`.

Debug cases: `jb_bench=animationClips` runs disposable GPU pixel/history/export/archive checks; `jb_bench=playAll` drives the real controller with overlapping 256/512/1024 px clips at 25/50/12 fps. Both use the existing debug bench activity and never restore or save the owner's working drawing.