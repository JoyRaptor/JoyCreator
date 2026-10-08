# R51 Save/Undo Integration Audit — Assignment A

Base: `e44d86c35f4ffbf9dcc6835ddc20b73e4e440682`. Owns only `tasks/joybrush/reviews/R51_SAVE_UNDO_INTEGRATION_AUDIT.md` (absent in checkout; this report is the content).
Read-only source review. No runtime, no device, no builds.

## Path registry (complete exact paths)

- [P1] `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/vector/CelComposer.kt`
- [P2] `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/vector/InkEditSession.kt`
- [P3] `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/paint/UndoLog.kt`
- [P4] `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/doc/DocModel.kt`
- [P5] `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/doc/DocOps.kt`
- [P6] `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/doc/DocJson.kt`
- [P7] `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/stroke/StrokeCodec.kt`
- [P8] `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/stroke/StrokeRecord.kt`
- [P9] `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/io/JbArchive.kt`
- [P10] `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/io/BoardSnapshot.kt`
- [P11] `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/JbCanvasView.kt`
- [P12] `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/GlPaintEngine.kt`
- [P13] `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/doc/RegionDocumentOps.kt`
- [P14] `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/doc/AnimOps.kt`
- [P15] `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/render/RegionRenderer.kt`

Requirements: `tasks/joybrush/LEAD_RULINGS.md:750-778` (R51), `tasks/joybrush/specs/JB-5.20_lines_in_the_one_layer.md:40-140` (D1–D15, slices 5.20a–f), `tasks/joybrush/specs/JB-2.40_media_on_any_layer.md:1-131` (§Q1 decided (b)), `tasks/joybrush/design/ONE_LAYER_MODEL_BRIEF_20261007.md:§4-§8`.

## High-confidence findings

1. **One-history primitives exist in core but engine save/undo path does not consume them.** [P3]: `LineChange:37-45`, `Step.lines:63-64`, `heldBytes:75-77`, `mergeNewest` lines fold `129-140`, `extendNewest` preserves lines `153-163`, `replaceInNewest` treats lines as content `183-190`. [P1]: `applyLines:214-224`. But [P12]: `undoStep:1752-1766` and `redoStep:1768-1782` restore only `changes/stack/paper/document`; no reference to `s.lines` or `applyLines`. Targeted searches in `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl` find `UndoLog.Step(` at multiple sites, none passes `lines=`.
2. **`InkEditSession` still owns a separate 50-step stack; no emission into `UndoLog`.** [P2]: `steps:101`, `commit:418-422`, `MAX_UNDO_STEPS:459`, header `12-20` states engine undo "never a StrokeRecord, so an ink edit needs an undo of its own". This does not yet satisfy the planned JB-5.20 D9 / R51.6 integration; it is not a newly regressed wired feature. Spec itself marks half-two ("emits instead of own stack") as landing with 5.20d (`JB-5.20:148-149`).
3. **Document/archive model has no slab/seq/look/brush-copy fields.** [P4]: `DOC_VERSION:14` is 8; `Cel:187-195` has only `tiles/strokesFile/floatTiles`; `LayerKind:135-143` still `PAINT,INK,MEDIA`. [P7]: `VERSION:44` is 2, `OLDEST:47` is 1; no `seq` (D14 requires Codec v3 + cel slab list + `brushes/`). [P5]: rule 8 `197-204` forbids PAINT+strokes / INK+tiles; rule 8b `206-211` forbids `floatTiles` on non-MEDIA.
4. **The current live canvas/snapshot path does not capture editable lines; the archive supports legacy stroke payloads separately.** [P10]: `capture:12-49` requires `hasPixels && animatedIn==null:18`, returns `strokes emptyMap:48`, and throws for MEDIA-on-frames `23`. [P11]: `refusalFor:1683-1728` refuses non-empty strokes `1685-1687`, non-pixel kind `1695-1697`, MEDIA-on-frames `1698-1700`; `readContents` non-board branch builds `strokes emptyMap:1830` and refuses media-only save `1755-1757`. [P9]: `write:199-260` and `read:472-516` enforce `strokesFile<->strokes` and `floatTiles<->media` both directions, but only for the old split model.
5. **Composer slab/operator rules are built and tested in isolation; render/save do not use them.** [P1]: `writableTop:126-130`, `paint:137-151`, `eachSlab:158-162`, `bake:169-183`, `compact:189-207`, `look:97-116` single-float-pass. [P15] grep: no `CelComposer`, no `slab`, no `seq`; only legacy `InkTiles.prepare/render:338-355` behind optional `strokeSource:257,305`.
6. **Explicit JB-2.40 media gate, not an omission.** `JB-2.40:8-9` owner area is `core/doc, BrushRules, LayerBudget, core/media, GlPaintEngine media paths, JbCanvasView media paths, JbArchive, BoardSnapshot`; "Do not touch" lists `core/vector`, `core/stroke`. R51 phases (`LEAD_RULINGS:775-778`, `JB-5.20:7,146-151`) order 2.40 before 5.20a. Media stores remain per-layer in engine (`GlPaintEngine:1791-1799` comment: "per layer, never per cel"), which `JB-5.20:164-165` explicitly flags slice 3 must change for frames.

## Requirement-to-callsite table

| Requirement | Built primitive | Actual caller / wiring | Verdict |
|---|---|---|---|
| R51.6 + D9 one history; `LineChange` with seqs | [P3]:37-45,63-64; [P1]:214-224 | [P12]:1752-1782 ignores `lines`; [P11]:755-766 delegates to those; no `InkEditSession→UndoLog` conversion found | Built, not wired |
| D1 seq time-order; D2 per-tile slab rule; D8a op-carrying slab; D8 ignore/consume; D12 Rasterize | [P1]:20-27,32-38,43-64,126-207 | No caller in [P12]/[P11]/[P15]; only tests | Built, not wired |
| D3 look cache saved, not replayed on open | No `look` field in [P4]; no look entries in [P9] | [P15] has no look path | Absent (5.20a/5.20d/5.20f) |
| D5 brush copies; D14 DOC_VERSION+Codec v3+slabs | Absent per §3 above | — | Absent (5.20a) |
| D4 GPU replay; 5.01b capture at `feed` after snap before `feedOne` | Notes at `JB-5.20:155-163` | Grep: no `StrokeRecord` in [P11]/[P12] main; [P11]:1295 placer only | Absent (5.20d) |
| D7 eraser both kinds as one step; D10 fill-as-shape; D11 select; D12 Flatten | `VectorEraser/InkErase` exist (per `JB-5.20:30-36`) | No engine eraser→`bake`+pixel call; no save wiring | Out of audit slice / absent wiring |
| R51.3 dry save; R51.4 plain-over-media per-pixel; JB-2.40 ground `#g` | Spec `JB-2.40:56-104` decided (b) | [P10]:21-33 media-only path; [P11]:1736-1757 legacy branch | Gated to media row; not present |
| Boards: lines in frame cels; D15 duplicate keeps line id for tweens | [P13]:85-106 LINK shares cel `95,97`; DUPLICATE new cel+`RegionCopy:96`; [P14]:263-283 same for legacy | [P10]:18 blocks vector; [P11]:1866-1903 frame select is metadata-only | Doc identity supports LINK sharing; line payload absent |

## Pixel/line sequence trace (observed source behavior)

- Edit: [P2] gestures mutate `records:314-317` and push `InkEditStep:419`; [P12] paint/media strokes push tile-only `Step`s (`960,1079,1832`, board `701-800,1301,1738`). No joint pixel+line step is constructed in engine code.
- Undo/redo: core `mergeNewest/extendNewest/replaceInNewest` handle lines ([P3]:129-190); engine `undoStep/redoStep` do not apply line changes ([P12]:1752-1782). `JbCanvasView.undo/redo` ([P11]:755-766) inherit this missing future integration.
- Save/reload: `snapshot:1596-1616 → readContents:1736-1750` (board) or `:1753-1830` (legacy, strokes dropped). These canvas capture paths do not produce the planned mixed line/slab representation. JbArchive legacy INK read/write capability is separate.
- Frame switches: `selectBoardFrame:1866-1878` is explicitly "absent from Undo" with `contentChanged=false:1875`; `previewBoardFrames:1881-1889` ephemeral; `editBoards:1892-1903` via `applyBoardChange`. Stable cel identity: LINK reuses same cel id ([P13]:95, [P14]:268); DUPLICATE/BLANK mint fresh `Cel(id)` + `CopyCel` work ([P14]:275-282, [P13]:96); delete drops unreferenced cel ([P13]:109-132, [P14]:320-349). D15 "same line id across duplicated frames" has no line store to apply to — unknown, not contradicted.

## Source-supported failure scenarios / unknowns (not runtime claims)

1. Silent line loss on undo after future line edits land if engine path unchanged — supported by [P12]:1760,1776 restoring only `changes`.
2. Current canvas opening refuses stroke contents, and raster snapshot rejects non-pixel layers; JbArchive itself validates legacy stroke payloads. Future one-layer wiring without D14 would risk bypassing these safe refusals — [P11]:1685-1687 vs [P9]:254-260.
3. Interleave order (D2) cannot be verified in save/export until slabs persist — [P1]:126-151 vs [P4]:187-195.
4. Wet-media + lines + frame save interaction unknown: [P10]:23 refuses MEDIA-multicel; R51.4 ground rule has no code to inspect in named paths.
5. Budget accounting for lines exists ([P3]:75-77, `SAMPLE_BYTES:42-43`) but eviction `trim:203-207` releases only tile `before`s; line memory pressure behavior under real budget unknown.

## Existing tests (cite, no execution claim)

- `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/vector/CelComposerTest.kt` (D2/D8/D8a/compact; e.g. bake bit-identity `:110-146`).
- `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/paint/LineUndoTest.kt` (fold/empty-step/extend/budget/apply order `:26-86`).
- `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/vector/InkEditSessionTest.kt` (gestures, 50-step cap `:710-711`).
- `joybrush/androidkit/src/test/kotlin/cc/joycreator/joybrush/androidkit/io/JbArchiveTest.kt`, `joybrush/androidkit/src/test/kotlin/cc/joycreator/joybrush/androidkit/io/JbArchiveMediaTest.kt`, `joybrush/androidkit/src/test/kotlin/cc/joycreator/joybrush/androidkit/io/JbArchiveCompletenessTest.kt` (split-model round-trips).
- `joybrush/androidkit/src/test/kotlin/cc/joycreator/joybrush/androidkit/io/BoardSnapshotTest.kt` (every-frame capture `:14-24`, LINK single payload `:25-32`, fail-closed `:33-35`).

Proposed discriminating regression cases (for 5.20a/d/f, not built here):

1. Paint-line-paint on one tile → save/reload → looks byte-identical and line remains editable (D2+D3+D14).
2. Consume-smudge → undo → line returns at same `seq` and look matches pre-smudge (D8+D9).
3. Rasterize Down → undo → line editable again; Flatten Lower → undo restores layers (D12, one step).
4. Duplicate frame → move line in frame 2 → frame 1 unchanged except LINK-shared case; save/reload preserves cel sharing and per-cel seqs (D15 + stable identity).
5. Pure-pixel cel save byte-identical to today (JB-5.20 regression gate).

## Coordinator checklist (short, named paths only)

- [ ] Confirm 5.20a owner/slice before touching [P4]/[P7]/[P9]/[P10]; JB-2.40 must land first per spec.
- [ ] Require engine `undoStep/redoStep` to apply `s.lines` via `CelComposer.applyLines` + tile re-render; reject any new second history.
- [ ] Require record capture point per `JB-5.20:155-163` (snap→record→feedOne, no predicted samples, seed/zoom/colour rules).
- [ ] Require `BoardSnapshot` + `JbArchive` to carry slabs/lines/looks/brushes with both-directions validation; keep refusal-first behavior.
- [ ] Keep media `#g`/dry-save work inside JB-2.40 lane; do not duplicate FilmStrip UI, grain, or media engine work.
- [ ] Gate on: pure-pixel parity, one-press-one-step, Note 9 open/edit budgets (`JB-5.20:169-173`, owner checks `:177-184`).

SUBTASK_REQUEST: none. Budget kept to named paths + direct callers (`JbCanvasView`, `GlPaintEngine`, `RegionRenderer`, `AnimOps`, `RegionDocumentOps`).

Coordinator review: verified core LineChange accounting, engine undo/redo, raster snapshot refusal, live canvas refusal, document fields and codec version. Removed the implication that archive round-trips silently lose legacy lines; mixed line/slab integration is a gated future path. No runtime reproduction claimed.
