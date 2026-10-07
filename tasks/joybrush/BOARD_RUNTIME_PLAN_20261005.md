# Board runtime implementation — owner priority 2026-10-05

Current owner direction: root finishes boards; Claude owns active paper/brush development. Fill remains a real functional gap; boards first. Spec JB-3.00a K/K10/G7a is unchanged. No test-art migration. October 7 acceptance below supersedes the original implementation checklist.

- [ ] One metadata/session owner, all paint cels retained; multi-board save/load and undo
- [ ] GPU region projection plus exact crossed-edge stroke commit; one undo
- [ ] Validated board creation/selection/move/resize/frame/held-layer operations
- [ ] Exact specialist chrome, stable IDs, strip hold/scrub, scoped thumbnails
- [ ] Board playback/hover/onion timing; transient previews never save cursor
- [ ] Tile full-screen preview/wrapped paint and sprite swap/sequence
- [ ] Board/all-image/range/frame exports and shared export presentation
- [ ] Serial tests, watcher APK and Note9 acceptance against K/K10

Root owns live engine/view/Activity/IO/history; helpers own model ops and specialist strip input. No simultaneous builds; source builds via watcher. No inert controls. A foundation is not final delivery; record exact proven scope per commit.

## Connection pass — 2026-10-06

Owner confirms boundary painting and animation on Note9. Claude owns paper engine and paint brushes; do not edit those engines, shaders, presets or paper UI. Root continues isolated integration, shared LANES announcement published.

- [x] Connect one K6 export presentation to Image, Animation and Sprite boards. Studio/SpriteLab receivers still owed.
- [x] Stable board/frame/range targets through picker recreation; stage complete outputs before writing destinations.
- [x] Connect existing GIF, PNG sequence/timing, sheet/JSON encoders and batch Image PNG.
- [x] Connect K9 per-layer held/animated toggles with bounded content transactions and one undo.
- [ ] Verify targeted tests, serial watcher build, install and exercise connected controls on Note9.
- [ ] Record remaining tiling, onion, Sprite cell interactions and app receiver gaps without declaring completion.
- [x] Connect K8 Sprite count/pixel grid controls and session-only sub-grid; verify whole-cell edits and rapid queued steps. Note9 placement, steppers, pixel sizing, sub-grid, Undo/Redo and sheet/JSON export passed.
- [x] Correct unfinished Sprite cell hit handling: finger/stylus/eraser pass through to painting. Native dispatcher regression and installed Note9 finger stroke/Undo passed; see BOARD_CONNECTIONS_20261006.md for exact APK fingerprints.
- [x] Wire Sprite CellRoll selection/preview and bounded all-layer translated swaps; one swap drag per Undo. October 7 source/tests/build pass; actual phone acceptance pending reconnection.

## Owner Sprite usability correction — 2026-10-06

- [x] Unlocked handle drag scales whole-pixel cell dimensions, fixed grid count, opposite edge anchor; live readouts/preview, one committed Undo on release, Cancel no mutation.
- [x] Editable board size and always-visible cell dimensions. Cell dimensions resize the whole grid while retaining counts; typed whole-board size snaps to the nearest exact cells.
- [x] Locked finger interactions build transient CellRoll/preview; pen continues painting. Separate feature arm enables all-layer exact swap, masks included, one drag/Undo. Source/test checkpoint; Note9 disconnected, phone acceptance owed.
- [ ] Verify model/core/GPU/native regression, watcher APK and Note9 resize/typed/preview/swap/Undo; coordinate Claude main-folder catch-up without overwriting other changes.

## Completion pass — 2026-10-07
Claude owns active brush/paper development. Root owns board integration adapters and phone acceptance. Note9 is connected; two helper drafts recovered after their usage limits.
- [x] Wire and verify Animation resize/move-all/duplicate/remove.
- [x] Complete seamless preview/wrapped painting and transient controls.
- [x] Connect scoped onion preview and app receiver handoffs.
- [x] Run serial regression, watcher build, install and phone acceptance.
- [x] Publish exact completed scope and remaining limitations in reviews/BOARD_COMPLETION_20261007.md.

Functional board runtime accepted on Note9: Sprite live/typed sizing, locked roll/playback, all-layer swap/Undo; Animation geometry guards and all-frame operations; hold taps/drags; bounded tiling and saved first-stroke marker; onion including clipped animated layers over held bases; SpriteLab sheet and Studio timed-canvas handoff, reopened and independently decoded MP4. Earlier boundary painting/GIF/PNG-sequence/held-toggle acceptance remains in BOARD_CONNECTIONS_20261006.md. Do not treat this as exact completion of every visual spec: Frost forms, adaptive drawer ink and app-wide Studio export presentation remain refinement work. Fill pen and tiling read-tool support remain separate; tiling honestly refuses unsupported tools.
