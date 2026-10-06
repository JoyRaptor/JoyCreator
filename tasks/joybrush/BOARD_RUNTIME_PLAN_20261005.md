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
