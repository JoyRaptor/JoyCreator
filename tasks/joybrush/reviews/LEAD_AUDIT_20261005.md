# JoyBrush Lead audit — 2026-10-05

## Conclusion and scope
Claude's locked board design remains the target: JB-3.00a sections K and K10, plus G7a. No redesign is warranted. Much useful work exists, but isolated components have repeatedly been mistaken for a finished app. Highest board bottleneck is the live region/document/GPU transaction adapter, followed by attaching the specialist's exact chrome. Painting also has confirmed unfinished tools.

Source review of primary working files, shared origin, newer integration and board specialist; two independent scoped helpers checked board and painting lanes. No tests rerun, phone inspected, build or install performed in this audit. Existing reports/XML are historical evidence, not verification of today's final source. Not an exhaustive line-by-line correctness certification of every subsystem.

## Three sources must not be confused
- Primary C:/+Projects/Screenrecorder/FadCam: HEAD8d334538 plus extensive dirty files; older schema4/frame foundation, no locked board chrome. Its watcher targets this folder. Do not reset or blanket-stage; preserve unrelated work.
- Shared origin/joy-creator: e5cfa815 when fetched. Schema7, paper UI/material/filter/export work, region CPU foundation, importer and eyedropper repairs. No board chrome attached.
- Integration C:/Temp/jb-region-routing: HEAD1b4f73d5 before this report,13 commits beyond shared origin; published backup origin/codex/region-routing. Includes rescued brush contact03c918fe, ORA follow-up efbe7da8, OOM8d7ab19f and blank-tile/recovery1b4f73d5. These are review/publication gaps, not wholly missing features.
- Board specialist C:/Users/JoyRaptor/AppData/Local/Temp/jb-board-chrome: c5e00346; isolated visual implementation, not integrated into any of the above production screens.

Integration XML present: core1547 tests/0 failures/4 corpus skips; androidkit246/0; Paper UI5/0. Files are from October2. An integration APK is present from October2 at19:33; neither its mere existence nor the primary watcher proves which source is installed now. Recent save/crash fixes still need acceptance at their exact final revision.

## Boards: requirement-to-code map
| Locked requirement | Existing work | Missing to deliver it |
|---|---|---|
| K2/K10: every board icon-only at rest, approach brackets, non-first animation number | Isolated BoardChromeLayout160; contrast/hover view | Live board scenes, camera transform, pen proximity and input routing |
| K3:34dp satellite pill, title, corner size, outside selection ring, lock/nail | Isolated layout172–205 and native view/fonts/export glyph | Real create/select/rename/resize/confirmed move transactions; multi-board save/reopen |
| K4:12% fade120ms/back300ms, folded ruler | BoardChromePenFade and isolated layout | Live pen/lifecycle integration; phone performance/visual proof |
| G2/B3: per-board saved current frame, shared outside art, pixel-exact edge, one crossed-edge stroke undo | DocModel Board.currentFrameId/RegionFrames; RegionDocumentOps; RegionPaintPlan; CPU RegionRenderer | GPU reads/writes per region/cel, bounded copy execution, history ownership, metadata/save adapters. Latest JbCanvasView1275 refuses multiple boards and1279 non-CANVAS; CanvasSnapshot also static-canvas only |
| K5: fused pegs, outline strip, held widths, blank/duplicate/link/delete, wheel/hold-play FPS | Isolated layout208–251, callbacks and tested core timing/operations | Actual board operations, preview-only hover playback, onion source, range/timing/UI hooks. Edge-duration drag and continuous strip scrub are absent even in the isolated view |
| G7a/K9: selected-board bounds/current-frame thumbnails and animate/held markers | BoardLayerPreview scope helper and marker layout | Activity thumbnail bounds/readback adapter; held-toggle operation on real pixels/history. Passive restores whole-page previews |
| K6: one Studio export icon and shared236dp glass sheet, all/range/current | Isolated ExportChoiceSheet/BoardExportLayout and real glyph | Shared Studio adoption plus actual dispatch/range validation/memory budget/Send-to-Studio. Current Activity is PNG-only |
| K7/K10: full-strength whole-screen tile view, one docked exit switch, wrap every stroke, persisted tiled identity after first wrapped paint | Isolated tile-only presentation; model tiled flag | Repeated pixel rendering, wrapped stroke writes/undo, actual switch state and first-painted transition. Chrome alone paints no tiles |
| K8: sprite shelf/count/size/subgrid, all-layer swaps, sequence preview | Core sprite grid/roll/packing and isolated visual states | Real canvas pixel-swap transaction, one-gesture undo, playback preview and SpriteLab import/open handoff |

Do not attach the historical Activity-wiring.patch blindly: it predates the operational region API. Do not restore whole-layer animation as a shortcut. Primary's temporary bottom frame bar is not Claude's attached outline-strip design. Specialist's earlier full-window software rendering concern is partly addressed with bounded shadow cache/coalesced scenes; do not repeat it as an unfixed current defect.

Concrete isolated interaction defects: BoardChromeView495–504 treats horizontal strip motion as scroll/reorder; no hold-edge target/callback despite BoardChromeLayout334 promising it. Strip drag does not continuously select successive cells for scrubbing. Fix these within the specialist component, then prove integrated behavior.

## Painting and other remaining work
1. Fill is genuinely unfinished: shipped engine=fill preset has no fill branch in integration JbCanvasView985–993; ordinary dab stroke is used. FillPen/InkRaster/FloodFill mathematics do not make the advertised shaped-fill pen work. Finish JB-2.06b instead of tuning its line preset.
2. Owner rejected pencil/soft-air feel, reported ineffective smudge and Bristle too similar to Sable (df786ae1 and BRUSH_DEFECTS_20261002.md). Smudge DOES have production dispatch; reproduce its failed behavior before deciding its root cause. Preserve Sable. Tests of contact dynamics do not supersede owner verdict. Pencil curves/tooth, air stationary deposition, Bristle structural separation and grain-slider semantics remain quality jobs.
3. Paper: catalogue/sheet/None/undo, mip filtering and pre-layer textured export now exist. Do not reopen those as missing implementations. Phone comparison of blended textured PNG and paper feel remains acceptance work; directional dry/wet deposit JB-9.08 is still unbuilt despite stored sliders9.09.
4. Import: ABR parsing and16bit repair exist, but Activity259 loads builtIn only. No end-to-end import/user library/image-tip drawing UI. JB-1.05d requires sibling-file storage, Android decode, real tip sampler and CPU twin; do not treat slope conversion as that feature.
5. PNG is connected. ORA/animation exporters are libraries without corresponding live UI dispatch. ORA atomic-output fix exists on integration, not shared main; ORA's legacy scalar-cel gate still needs region support before board exports.
6. Ink/vector editing and eraser mathematics exist, but latest canvas rejects stroke-record documents. Capture/render/select/rebrush/context eraser need operational integration. Wet simulation, puppet/character boards and Send-to-Studio remain later scope.
7. Recent OOM/recovery/empty-tile fixes deserve targeted regression and phone open/paint/save/reopen checks. They are essential painting reliability work, not a reason to add more defensive migration of disposable test scratches.

## Recommended order and allocation
A. Establish one reviewed integration source and build from that exact revision. Audit13 unpublished-on-main commits; fresh appropriate tests and Note9 smoke checks. Do not resolve primary conflicts or erase its dirty work.
B. Repair functionally broken Fill/Smudge and painting reliability. Brush specialist owns feel/quality; frontier review owns shared engine changes. Keep board specialist productive independently on hold-edge/scrub and exact visual states.
C. Frontier Lead builds board session + GPU region reads/writes + bounded copy/history/save adapter. This is the load-bearing job; assign to a frontier model, not random independent agents editing hot files.
D. Wire multi-board creation/Image selection first; then Animation current frame, exact crossed-edge painting, held layers and scoped previews. Connect the EXISTING locked chrome, not a new mock UI. Finish operation list (reorder/delete/hold/link/FPS/onion/hover/stop) against stable IDs.
E. Shared export dispatch and SpriteLab/Studio handoff; then real sprite rearrangement and tile wrap/full-screen preview. Test board switches, edge-straddling tiles, rapid undo, interrupted playback and lifecycle/save behavior.
F. Screenshot/gesture acceptance against every K/K10/G7a clause on Note9 at548dp. A rendered demo or pure test is not acceptance.

Free agents are useful for bounded components, fixtures and independent audits. The observed failure pattern is unfinished integration and unproven owner-facing behavior; no evidence justifies throwing away all their work. Frontier-owned boundaries plus precise completion gates are the correction.
