# R51 Board Export Agreement Audit (revised)
Base `e44d86c35f4ffbf9dcc6835ddc20b73e4e440682`. Read-only; no execution, no runtime or parity claim. Owns only `tasks/joybrush/reviews/R51_BOARD_EXPORT_AGREEMENT_AUDIT.md`.

## Source registry (complete paths)
- [S1] `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/io/BoardSnapshot.kt`
- [S2] `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/io/CanvasSnapshot.kt`
- [S3] `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/render/RegionRenderer.kt`
- [S4] `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/render/RegionTileSource.kt`
- [S5] `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/doc/RegionPaintPlan.kt`
- [S6] `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/io/CanvasPng.kt`
- [S7] `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/io/OraExport.kt`
- [S8] `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/io/AnimExport.kt`
- [S9] `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/export/AnimExportPlan.kt`
- [S10] `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/io/BoardExport.kt`
- [S11] `joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/board/BoardExportCoordinator.kt`
- [S12] `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/vector/CelComposer.kt`
- [S13] `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/vector/InkTiles.kt`
- [S14] `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/vector/InkReplay.kt`
- [S15] `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/GlPaintEngine.kt`
- [S16] `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/doc/SpriteCellOps.kt`
- [S17] `joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/board/BoardRuntimeController.kt`
- [S18] `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/doc/RegionDocumentOps.kt`
- [S19] `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/doc/DocModel.kt`

## Entry points and data flow (observed)
- Snapshot: [S1]:12-18 requires `kind.hasPixels && animatedIn==null`; [S2]:24-26,94-98 additionally requires `strokes.isEmpty()`, one canvas board. Non-pixel layers and obsolete whole-layer animation are refused; pixel region-frame physical snapshots are supported.
- Paint-time tile wrap: [S15]:1473-1476 `TilePainting.dabs`, [S15]:1517-1519 `TilePainting.stamps`, [S15]:1680-1738 commit through `strokePlan.tileSlices` plus `TilePainting.clip/intersect` and `markWrappedStroke`. Export reads committed wrapped pixels; no `TilePainting` call in export is expected because ghost repeats must not export.
- Paint-time crossing split: [S5]:24-32,53-80 one `RegionPaintPlan` per layer, `planeAt`/`tileSlices` pixel-exact, never rounded to tiles. [S4]:19-34 projects `tileSlices` into export tiles via `copyRgbaSlice`; single-plane fast path at [S4]:25. This is built pixel splitting.
- Render: [S3]:249-266,297-312 `render`/`renderPremultiplied`; paper as floor in bounded blocks [S3]:315-332,561-592; INK reroute [S3]:340-357 via [S13]; default `onInkRefusal` throws `RegionException` [S3]:258; visible INK without `brushLookup` refused [S3]:465-469. Region `renderCel` plus per-board cursor rule documented [S3]:164-166, enforced [S4]:12-16.
- PNG: [S6]:16-24 `encodeBoard` via `selectFrame`; [S6]:34-36 refuses `!hasPixels || animatedIn!=null`; region guard [S6]:60-66.
- ORA: lookup `brushLookup` [S7]:189-190, entries inject `brushLookup/strokeSource/onInkRefusal` [S7]:228,243, per-layer render [S7]:345-357, merged render [S7]:287-290. Mask/clip baked [S7]:348-357. Nine blends refused, not approximated [S7]:552-578.
- Animation plan: [S9]:65-89 `plan`/`planRange` swap+clamp; delays from `AnimOps.frameStartsMs/totalDurationMs` [S9]:189-234; holds folded as repeats [S9]:160-177,271-278. Runner renders `plan.rect,frameId` [S8]:154-157,225-228 without `brushLookup`/`strokeSource`.
- Stage/coordinate: [S10]:45-132 validate then `ALL_IMAGES` [S10]:67-78, `ANIMATION` [S10]:79-103, `CANVAS/PNG` [S10]:105-106, `SPRITE/SHEET` [S10]:107-123 via `SpriteGridMath.cellRect` [S10]:117 and `SpritePacker.pack` [S10]:119-122. Coordinator kind/scopes/formats/paper/budget [S11]:38-57,98-126.
- Sprite swap is shipped: [S17]:587-590 `SpriteCellOps.swap(live,id,index,target)` on committed drop; [S16]:14-69 swaps every layer plus masks, preserves held/linked addresses. Export consumes the committed document.
- One-layer composer exists but is unwired into export: [S12]:28-116 `Slab(op)/Line(seq)`, `look` one float pass; `bake` whole-line at own seq/op [S12]:169-183; D2 rule [S12]:126-151. [S3] imports only `InkTiles`, not `CelComposer`.

## Agreement table
| Area | Requirement | Observed | Verdict |
|---|---|---|---|
| Image/Board/Sprite PNG rect-exact | JB-3.00a B9,B14 rectangle only | [S6]+[S3] tile walk supports negative origin; sprite cells same path [S10]:117 | Supported for committed pixels |
| Tile-wrapped art export | JB-3.00a B22 paint wraps; B21 repeats preview-only | Wrapped pixels committed [S15]; export reads rect without re-wrapping | Supported as committed art |
| Crossing pixel split | JB-3.00a B10 clip-exact | [S5]+[S4] shared/frame slice per pixel; GL commit same plan [S15]:1692-1717 | Supported for pixels |
| Animation plan/range/holds | JB-3.06b one plan, three encoders | [S9]+[S8] plan/range/sheetClip/timing.txt; GIF loop | Supported for pixels |
| Held layers, cursors, masks/paper | JB-3.00a B11,G2,G7a | `currentFrameId` [S19]:112; `selectFrame/setHeld` [S18]:78-82,160-177; masks/clip [S3]:382-419; paper floor | Supported for pixels |
| Sprite swap export | JB-3.00a B16/F1-Q4 swap all layers | [S17]+[S16] committed swap; [S10] exports placed grid | Source-supported |
| ORA legacy procedural INK | JB-2.14b layers+merged agree | [S7]:190,228,243,287-290,356-357 injects lookup+strokes | Supported within refusals |
| ORA nine blends | Exact SVG only | [S7]:552-578 `else->IllegalArgumentException` | Explicit unsupported/refusal boundary |
| Unsupported engines/blends | JB-5.20 D6; InkReplay rule | [S14]:106-119; [S13]:32-38 image-tip/unsupported-blend refusals; export default throws | Explicit unsupported/refusal boundary |
| Editable-line split/seq/slabs in export | JB-5.20 D1-D3,D8,D8a,D12; slice 5.20f | No region addresses for lines; [S3]:355 per-cel `InkTiles.render`, no `seq`/slab/`look` | Real gap (5.20f) |
| Animation/sprite INK lookup | Same renderer contract | [S8] and [S10]:117 omit `brushLookup`/`strokeSource`, unlike [S7] | Real gap |
| MP4/WebP/scale/sheet picker | R35 out | `AnimFormat` GIF/PNG_SEQ/SHEET only [S8]:43-47; `sheetCols` no picker [S9]:139-144 | Planned gate, absent by ruling |

## Existing tests (complete paths, cited without execution claim)
- `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/doc/RegionPaintPlanTest.kt`: `edges are exact and half open`, `negative coordinates straddle four tiles without snapping`, `two regions share a tile without sharing outside paint`, `all tile pixels partition exactly once`, `held layer has shared pixels everywhere`, `changing frame leaves outside plane untouched`, `slices reassemble all original paint`.
- `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/render/RegionTileSourceTest.kt`: `savedCursorAndExportOverrideChangeOnlyInsideBoard`, `blankFrameDoesNotLeakSharedPixelsAndHeldUsesShared`, `twoBoardsInSameTileKeepIndependentCursors`, `masksAndClipBaseUseTheirOwnLayerRegionPixels`, `unknownFramesAndMalformedTilesAreRefused`.
- `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/doc/SpriteCellOpsTest.kt`: `same cel swap snapshots both sides including transparent pixels and reverses exactly`, `both animation boundaries preserve hidden shared art other frames held and linked addresses`.
- `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/export/AnimExportPlanTest.kt`: starts-to-delays, tie, range-vs-clock, holds-as-repeats.
- `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/vector/CelComposerTest.kt`: nine seq/slab/bake tests.
- `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/vector/InkTilesTest.kt`: `behindEraseAndMultiplyHaveExplicitPixelMeaning`, `unsupportedBlendAndImageAndGrainAreRefusedInsteadOfInvented`, seam tests.
- `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/render/RegionRendererTest.kt`; `joybrush/androidkit/src/test/kotlin/cc/joycreator/joybrush/androidkit/io/AnimExportTest.kt`; `joybrush/androidkit/src/test/kotlin/cc/joycreator/joybrush/androidkit/io/BoardExportTest.kt`; `joybrush/androidkit/src/test/kotlin/cc/joycreator/joybrush/androidkit/io/OraExportTest.kt`; `joybrush/androidkit/src/test/kotlin/cc/joycreator/joybrush/androidkit/io/BoardSnapshotTest.kt`; `joybrush/androidkit/src/test/kotlin/cc/joycreator/joybrush/androidkit/io/CanvasPngTest.kt`.

## Unknowns
- Whether `CanvasPng.encodeBoard`'s `selectFrame` copy covers region INK once lookup exists; current gate refuses INK first.
- Normative wording for ORA class KDoc still saying INK skipped while code renders procedural INK.
- Coordinator `SPRITE_LAB`/`STUDIO` availability versus `BoardExport.stage` sheet-only branch.

## Coordinator-review corrections applied
- Tile export no longer called absent; wrap is paint-time, export reads committed pixels.
- Pixel crossing split separated from future editable-line split; cited [S5]/[S4] plus independent partition tests.
- Sprite swap described as shipped via [S17]:589 and [S16], not future.
- ORA procedural INK distinguished from animation/sprite missing lookup; refusals kept separate from 5.20f.
- No `fails today`/`passes` language; anticipated oracles only; full test paths used.

## Proposed regression-test-only candidates (for Lead; no edits authorized)
1. Path `joybrush/androidkit/src/test/kotlin/cc/joycreator/joybrush/androidkit/io/SpriteSwapExportTest.kt`. Acceptance from source: after `SpriteCellOps.swap`, `BoardExport` sheet cells equal pre-swap opposite cells using `SpriteGridMath.cellRect` oracle; outside-cell pixels unchanged.
2. Path `joybrush/androidkit/src/test/kotlin/cc/joycreator/joybrush/androidkit/io/AnimInkLookupBoundaryTest.kt`. Acceptance from source: procedural-INK animation document without lookup is refused in words via [S3]:465-469/[S13] refusal path, while `OraExport` with `BrushLibrary` lookup plus `contents.strokes` renders the same cel; pins missing animation/sprite lookup boundary versus ORA capability.
