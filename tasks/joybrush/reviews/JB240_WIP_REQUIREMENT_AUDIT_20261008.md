# JB-2.40 corrected audit — 2026-10-08, read-only, WIP not approved, gate not landed
Provenance: snapshot `C:\Temp\jb-opencode-media-snapshot`, base `019739ab` + patch `SHA256:665EC6A578CF414CB1B1F7ADF73804C23263E364BDBB5B85C1667EDC74D61405` (coordinator verified patch SHA256 before applying it to this snapshot).
Authority: `JB240_LEAD_INPUT_SPEC.md` (131 lines, Req1-11 + DECIDED `#g`). Draft claim that `tasks/joybrush/specs/JB-2.40_media_on_any_layer.md` (124 lines) is “identical” is false: in-snapshot tasks spec has Req1-10 only, no Req11 Tiling paragraph; omit identity claim.
Method: `Read`/`Glob`/`Grep` only. No build/test/shell/Git/network/device/edit. Executed tests by this audit: none. All tests below are source-asserted, not run here.
Slices per `tasks/todo.md:514-530`: S1 kind-gone/v9/rules/slots/`#g`-floor/funnel-drop/Req5-net/frames-still-refused; S2 per-pixel bake + reload-keeping-water + eraser + whole-tile; S3 per-`(layer,cel,tile)` + lift frame refusals + snapshot cel-with-floats; S4 dry-save file-only + live stays wet.
1 No-kind: implemented. Active-layer paint + INK-only refusal holds (`JbCanvasView:1080-1081`, `BrushRules:28-38`). Zero source hits for `addMediaLayerStep`/`mediaMadeLayer`/`mediaLayerFailed`/`MEDIA_SLOTS`/`slotsFor`/`slotsUsed` outside history/draft/spec text.
2 Per-tile/per-cel + board case: partial. Payload per-layer only (`GlPaintEngine:1791,1813-1814,1817`); per-cel missing. Frame paint refused in words (`JbCanvasView:1085-1090`); open refused (`JbCanvasView:1668-1671`); save throws if `cels.size!=1` (`BoardSnapshot:21-35`, throw `:25`); framing drops payloads (`GlPaintEngine:1858-1870`). Lead board case (wash then Animation board, save + frame-1 ownership) still fails by design.
3 First-touch exactness: code present, CPU pixel proof exists — draft “no test found” is wrong. Copy look→ground on no-payload + keep on first flush (`MediaWindow:180-194,93-128`); `g` RGBA8 (`MediaStores:20-31`). Hand-worked `render(empty,ground)==ground`, 8-bit round-trip, glaze-over-ink pinned (`MediaGroundTest:20-56`). Remaining: GPU `blit` bit-exactness and `u_relief=0` on-device identity not executed here.
4 Plain-over-media: missing per-pixel bake; Slice-1 floor only. `settlePlainWrites` drops whole-tile payload incl `g` in same step (`GlPaintEngine:1829-1851`, pinned `GlMediaStoresTest:129-139`). Funnel callers: `endStroke:1669-1733` (push `:1731`), `replaceTiles:1036-1073` (push `:1072`), `applyBoardChange:1143-1302` (push `:1294-1295`). Bounded correction: look tiles remain — loss is live state/water/editability, not deletion of displayed raster. Pen-across-wash keeps crisp line look but that tile’s wash stops/cannot be re-wet until S2. No `coverage>1/255` per-pixel path found.
5 Safety net: implemented. `agreedLook`/`reconcileAgreements`/`dropStalePayload` (`GlPaintEngine:1872-1918`); called on every window load (`MediaWindow:167-169`); outside-stroke drop is own step, inside-stroke joins stroke (`GlPaintEngine:1915-1916`); warned (`:1914`). Pinned (`GlMediaStoresTest:153-178`).
6 Dry saves: missing/contradicted. Save writes `w0/w1` as-is (`BoardSnapshot:21-35`; `JbArchive:60-82`); wet round-trip asserted (`JbArchiveMediaTest:62-68`; `MediaSaveTest:45-52`). No settle-to-`p0/p1`, no `w0/w1` omission. Live-continuation after save holds trivially (capture is read-only) but dry-file part fails; owner check 4 blocked.
7 No layer cost: implemented. No slot API in `LayerBudget` (`LayerNames:101-153`); plain count (`JoyBrushActivity:1117-1123,1136-1142`); ceiling kept (`LayerNames:150-152` + `GlPaintEngine:1956-1961`).
8 Brush rules: implemented (`BrushRules:28-38`; pinned `MediaLayerDocTest:99-106`).
9 Files: implemented. v9 (`DocModel:8-17,138-146,190-199`); `mediaAsPaint` (`DocJson:76-95`); rule 8b (`DocOps:196-217`); pinned (`MediaLayerDocTest:31-50`; `EnumFreezeTest:47-54,93-105` keeps MEDIA per R3).
10 One Undo: holds for Slice-1 scope only. Each funnel push + `endMediaStroke:1931-1937` is one `Step`; write+drop undo/redo agreement pinned (`GlMediaStoresTest:141-151`). Not proof future per-pixel bake stays one step.
11 Tile-board refusal: implemented for entry (`JbCanvasView:376-380,1082-1084`; smudge/fill parity `:1202-1203`). Media-eraser Tile path not proven here — uncertainty.
Q1b `#g`: partial. Ground/first-touch/lazy stores present; per-pixel `#g`=new-look + zero `p0/p1`/flakes/`w0/w1` + keep `paper.r` crush absent; media-eraser scales `p0/p1/paper/w0/w1` not `g` (`MediaLayerEngine:574-622`; `MediaCanvas:306-310` limits to existing stores); whole-tile bake absent.
Water reload: `lookWritten` (`MediaWindow:148-158` via `MediaCanvas:199-200`, `GlPaintEngine:1825-1826`) clears tile + `loadKey` + `stateRestored`, does not `stopWater` — water elsewhere runs on (`MediaLayerEngine:359-387`). `restored` (undo/redo) stops water + `resumeWater=false` (`MediaWindow:136-142` via `MediaCanvas:196-197`). Slice-2 “reload baked keys while running” mechanism exists but baked-keys content missing.
Compositor contract holds in WIP: `draw:2169-2210`, `drawComposited:2231-2280` read look tiles only; no media branch observed.
Source-asserted (not executed): `MediaLayerDocTest`, `GlMediaStoresTest:122-193`, `MediaBudgetTest:27-39`, `MediaGroundTest`, `EnumFreezeTest`, `JbArchiveMediaTest`, `MediaSaveTest`; wet-asserts contradict Req6; absent: per-pixel bake, dry-save, frame-cel ownership, eraser-ground, whole-tile bake, `blend_gpu_check` run.
Bounded data-loss: Slice-1/frame drops preserve raster look as plain pixels; what is lost is simulatable state. Do not claim source-proven pixel deletion.
Uncertainties: every plain writer reaches funnel vs bypasses to net not exhaustively audited (transform/paste/clear traced by name only); eyedropper parity (`JbCanvasView:951,1004`) unrelated to media correctness; GPU pixel identity needs device run.
## Exact source path registry

- `JB240_LEAD_INPUT_SPEC.md`
- `tasks/todo.md`
- `tasks/joybrush/specs/JB-2.40_media_on_any_layer.md`
- `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/JbCanvasView.kt`
- `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/brush/BrushRules.kt`
- `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/layers/LayerNames.kt`
- `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/doc/DocModel.kt`
- `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/doc/DocJson.kt`
- `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/doc/DocOps.kt`
- `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/GlPaintEngine.kt`
- `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/media/MediaWindow.kt`
- `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/media/MediaStores.kt`
- `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/media/MediaCanvas.kt`
- `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/media/MediaLayerEngine.kt`
- `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/io/BoardSnapshot.kt`
- `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/io/JbArchive.kt`
- `joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/JoyBrushActivity.kt`
- `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/doc/MediaLayerDocTest.kt`
- `joybrush/androidkit/src/test/kotlin/cc/joycreator/joybrush/androidkit/gl/GlMediaStoresTest.kt`
- `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/media/MediaGroundTest.kt`
- `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/media/MediaBudgetTest.kt`
- `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/brush/PushTest.kt`
- `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/doc/EnumFreezeTest.kt`
- `joybrush/androidkit/src/test/kotlin/cc/joycreator/joybrush/androidkit/io/JbArchiveMediaTest.kt`
- `joybrush/androidkit/src/test/kotlin/cc/joycreator/joybrush/androidkit/io/MediaSaveTest.kt`
- `joybrush/tools/blend_gpu_check.js`

Coordinator review: source-checked whole-tile payload drops and snapshot wet-store capture; independent free checker corrected omitted ground CPU proof and stale-spec identity claim. Report concerns captured WIP only; no build or device acceptance.
