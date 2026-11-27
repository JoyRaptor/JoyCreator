# JB-2.40 WIP Requirement Audit — 2026-10-08

## Provenance and scope
- Source: isolated snapshot `C:\Temp\jb-opencode-media-snapshot`, base `019739ab` + Lead-captured patch `SHA256:665EC6A5…D61405`, coordinator-verified.
- Authority: `JB240_LEAD_INPUT_SPEC.md` (copied from `e44d86c`); `tasks/joybrush/specs/JB-2.40_media_on_any_layer.md` identical; slices from `tasks/todo.md:514-530`.
- Method: read-only source read (`Read`/`Glob`/`Grep`). No build, test, shell, Git, network, device, or edit. No test was run by this audit.
- This report does not approve WIP and does not land the media gate.

## Implementation slices (todo.md definition)
- Slice 1 (kind goes, v9, rules, slots gone, `#g` floor, funnel drop, Req5 net, frames still refused): **implemented**.
- Slice 2 (exact per-pixel plain-on-media bake, window reload of baked keys with water running, eraser decision, whole-tile ops): **missing**.
- Slice 3 (payloads per `(layer, cel, tile)` via one address helper, lift frame refusals, `BoardSnapshot` cel-with-float-tiles): **missing**.
- Slice 4 (dry saves: settle water to `p0/p1` in file only, omit `w0/w1`, live wash continues): **missing**; source asserts the opposite.

## Requirements
- 1 No-kind, paint on active PAINT, INK refused: **implemented**. `JbCanvasView.kt:1080-1081` paints on active layer; `BrushRules.kt:28-38` refuses only INK; zero hits for `addMediaLayerStep`/`mediaMadeLayer`/`mediaLayerFailed`.
- 2 Payload per tile, per cel + board-frame case: **partial**. Per-tile/per-layer yes; per-cel no. `GlPaintEngine.kt:1786-1795,1813-1814` payload is per-layer; `JbCanvasView.kt:1086-1090` refuses media on framed layers; `JbCanvasView.kt:1668-1671` refuses open; `BoardSnapshot.kt:21-35` throws `…cannot be saved yet` when `cels.size!=1`; `GlPaintEngine.kt:1858-1870` drops payloads when frames arrive. Lead board case (paint wash, then Animation board, save + frame-1 ownership) still fails by design.
- 3 First-touch exactness: **implemented in code, pixel-proof gap**. `MediaWindow.kt:180-194` copies look to ground when no payload; `MediaWindow.kt:114-126` keeps it as `#g` on first flush; `MediaStores.kt:20-31` defines `g` RGBA8. No hand-worked CPU pixel test for Req3 found.
- 4 Plain-over-media per pixel for all writers: **missing**. `GlPaintEngine.kt:1835-1851` `settlePlainWrites` drops the whole tile payload + ground. Covers funnel callers only; not per-pixel bake, not stamp/eraser/smudge/fill/paste/transform/clear individually.
- 5 Safety net: **implemented**. `GlPaintEngine.kt:1872-1898` `agreedLook`/`reconcileAgreements`; `GlPaintEngine.kt:1905-1918` `dropStalePayload`; `MediaWindow.kt:167-169` calls it on load. Tested by `GlMediaStoresTest.kt:153-178`.
- 6 Dry saves, live stays wet, look==saved-look: **missing / contradicted**. `BoardSnapshot.kt:21-35` saves `w0/w1` as-is; `JbArchiveMediaTest.kt:62-68` `aSaveMidFlowReopensWet` asserts water survives; `MediaSaveTest.kt:45-52` `…ReopensWet` asserts `w0` round-trips. No settle-to-`p0/p1`, no `w0/w1` omission.
- 7 No layer cost, plain count, ceiling stays: **implemented**. `LayerNames.kt:101-153` has no `MEDIA_SLOTS`/`slotsFor`/`slotsUsed`/`roomFor`; `JoyBrushActivity.kt:1117-1142` shows plain `column.show(stack,maxLayers)`; `LayerNames.kt:150-152` + `GlPaintEngine.kt:1956-1961` keep `mediaFullMessage`.
- 8 Brush rules lose MEDIA branch, INK stamp/fill only: **implemented**. `BrushRules.kt:28-38`; `MediaLayerDocTest.kt:99-106` pins PAINT-allowed / INK-refused.
- 9 Files v9, v8 MEDIA→PAINT, never write MEDIA, freeze keeps MEDIA: **implemented**. `DocModel.kt:8-17` v9; `DocJson.kt:92-95` `mediaAsPaint`; `DocOps.kt:203-217` rule 8b; `MediaLayerDocTest.kt:40-50`; `EnumFreezeTest.kt:98-100`.
- 10 One Undo per stroke including look+payload: **implemented for Slice-1 scope, pending Slice-2**. `GlPaintEngine.kt:1731` `endStroke`, `:1072` `replaceTiles`, `:1294` `applyBoardChange`, `:1931-1937` `endMediaStroke` each push one `UndoLog.Step`; `GlMediaStoresTest.kt:141-151` pins write+drop undo/redo agreement.
- 11 Refuse media while Tile armed: **implemented**. `JbCanvasView.kt:376-380` `refuseTileTool`; `:1084` media refusal; `:1202-1203` smudge/fill parity; `:951,:1004` eyedropper parity.
- Q1(b) `#g` ground rule: **partial**. Ground store, first-touch copy, `render(state over ground)`, lazy stores present (`MediaStores.kt:20-31`; `MediaWindow.kt:114-126,180-194`). Missing: per-pixel `coverage>1/255` bake (`#g`=new look, zero `p0/p1`/flakes/`w0/w1`, keep crush `paper.r`), media-eraser scaling `#g` (`MediaLayerEngine.kt:574-622` touches `p0/p1/paper/w0/w1`, not ground), whole-tile bake for transform/filter/merge/opacity.

## Actual changed caller chains (observed)
- Plain funnel: `endStroke:1670-1733` / `replaceTiles:1036-1074` / `applyBoardChange:1143-1294` → `settlePlainWrites:1835-1851` → `onLookWritten:1825-1826` → `MediaCanvas.kt:199-200` → `MediaWindow.kt:148-158` reload; undo/redo → `reconcileAgreements:1891` + `reportMediaRestored:2089` → `MediaCanvas.kt:196-197` → `MediaWindow.kt:136-142` stop-water+reload.
- Save path: `JbCanvasView.kt:1708-1721` → `BoardSnapshot.capture:12-35` → `JbArchive` `layers/<id>/<cel>/<key>.<store>.f16` + `<key>.g.rgba` (`JbArchive.kt:69,82,138-139,404-414,624`); wet stores included.
- Compositor contract holds in WIP: `draw:2169-2210` and `drawComposited:2231-2280` read look tiles only; no media branch observed.

## Tests asserted in source (none run here)
- Assert Slice-1: `MediaLayerDocTest`, `GlMediaStoresTest.kt:122-193`, `MediaBudgetTest.kt:30-38`, `PushTest.kt:76-82`, `EnumFreezeTest`.
- Assert wet saves (contradicts Req6): `JbArchiveMediaTest.aSaveMidFlowReopensWet`, `MediaSaveTest.itRoundTripsThroughTheFileAndReopensWet`.
- Absent: per-pixel bake arithmetic, Req3/4 hand-worked pixels, dry-save (`no w0/w1` + deposited `p0/p1`), frame-cel payload ownership, eraser-grounds, whole-tile bake, GPU `blend_gpu_check`.

## Contradictions / data-loss paths (high confidence)
- Dry-save gap is load-bearing: landing current save code keeps washes wet across reopen, violating Req6 and owner check 4.
- Slice-1 drop is whole-tile lossy: a crisp pen line across a wet wash drops the wash for the whole tile, violating owner check 2 until Slice-2.
- Frame path is lossy-or-refused: framing drops live payloads (`dropPayloadsOfFramedLayers`) or refuses save/open; the audited board bug is not yet fixed.
- Per-layer addressing means frame vs shared copies cannot both own media; current refuse/drop avoids wrong render at the cost of loss.

## Uncertainties
- Bit-exactness of `blit` ground copy and `u_relief=0` identity `render(empty,ground)==ground` were read, not executed; needs GPU/CPU pixel proof.
- Whether every plain writer reaches the funnel vs bypasses to the Req5 net was traced by name (`endStroke`, `replaceTiles`, `applyBoardChange`), not exhaustively audited for transform/paste/clear paths.
- No independent checker was run; a bounded free-only recheck of citations, dry-save absence, and frame refusal would still be valuable.
