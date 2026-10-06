# Board runtime implementation — owner priority 2026-10-05

Paper, pencil, watercolor and advanced brush development paused. Preserve existing painting/paper. Fill remains a real functional gap; boards first. Spec JB-3.00a K/K10/G7a is unchanged. No test-art migration.

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
- [ ] Wire Sprite CellRoll selection/preview, then bounded all-layer translated swaps; no inert cell controls and one swap drag per Undo.
