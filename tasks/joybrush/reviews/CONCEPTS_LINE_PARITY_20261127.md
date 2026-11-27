# JoyBrush line work against Concepts: what exists, what is missing, whose it is (2026-11-27)

Owner ruling, 11-27: full parity with Concepts on line work:
- powerful line selection that picks strokes or lines, not only areas;
- line erasing: a stroke eraser and a trim to the intersections;
- moving smoothly between raster and vector lines, as the earlier demo did;
- always knowing what you are painting with: which brush, and whether it makes pixels or lines.

This list is read from `joy-creator` code on 11-27; a read-only inventory was checked by hand at the key points. "Wired" means a person can reach it today. "Core only" means the logic exists and is tested but nothing in the app calls it. **Side** says who builds it:
- **UI**: the Select / chrome lane, outside the paint engine.
- **Engine**: the paint-engine session or Codex. That covers `GlPaintEngine`, `MediaAtlas`, `WetSheet`, `gl/media`, `JbCanvasView` stroke and render internals, and save and recovery.

It replaces the stale October list (`SELECT_LINES_GAPS_20261023.md`): G2, G4, G5, G6, G8 and G10 there are fixed.

## A. Picking lines

| Concepts can | JoyBrush today | Side |
|---|---|---|
| Tap a stroke; tap again for the one under it | Wired (`SelectTool` tap and cycle, count pill) | — |
| Lasso, whole or touching, one layer or all | Wired (`SelectBarView` chips) | — |
| Select everything on a layer | Wired (Select settings, layer menu) | — |
| Add to / take from a selection | Wired (the ＋ chip; tap a selected line to drop it) | — |
| Invert, lock, group | Wired (More menu) | — |
| Select by colour, by brush | **Missing** | UI |
| Paint (pixel) selections that move, scale, flip, fill | **Design only** (`specs/SELECT_PIXELS_20261114.md`, engine review pending). The host's `liftPixels` returns null, so a loop of paint shows ants and nothing else. | Engine |

## B. Editing lines

| Concepts can | JoyBrush today | Side |
|---|---|---|
| Move, scale, rotate, each one undo step | Wired (the Studio box; `SelectBoxCancelTest`, `SelectionSyncTest`) | — |
| Keep or scale line weight on resize | Wired | — |
| Recolour, adjust colour, change brush, change width | Wired. The brush list offers only stamp and watercolour brushes with plain tips, and widening it is engine work. | — / Engine |
| Reshape (nudge, drag a tip) | Wired | — |
| Duplicate, delete, flip | Wired | — |
| Cut, copy, paste | **Cut added 11-27** (one undo step). Copy and paste were wired already. | UI (done) |
| Let go of a selection | **Done chip added 11-27** (tapping empty paper did it already, but nobody finds that) | UI (done) |
| Undo keeps the selection | Wired (D20). **11-27:** a touch after Delete no longer stops Undo re-selecting. | UI (done) |

## C. Erasing lines

| Concepts can | JoyBrush today | Side |
|---|---|---|
| Stroke eraser: touch a stroke, the whole stroke goes | **Core only** (`core/vector/InkErase.WHOLE_STROKE`, `VectorEraser`; no caller in the app) | UI route possible: hit-test like Select's "touching" loop and delete through Select's one-step delete. The canvas eraser route goes through `JbCanvasView` (engine). |
| Slice / trim to intersections | **Core only** (`VectorEraser` `TO_INTERSECTION`, crossing search; spec D24). No Slice preset. | Engine: it replaces one line with pieces through the line commit path |
| Erase part of a line, and it stays a line in pieces | **Not a split.** Erasing over a line today stacks an erase mark on top (`JbCanvasView` 2504-2512). It looks right, but the line underneath stays whole. Unsure whether moving the line later shows the erased part again. | Engine (`InkErase.PARTIAL` exists in core) |

## D. Pixels and lines

| Concepts can | JoyBrush today | Side |
|---|---|---|
| Turn lines into pixels | Wired (Rasterize Down for a selection and for a layer; Freeze; Flatten) | — |
| Choose per brush whether it makes lines | Wired in three places: brush settings, More → "Brush makes lines", and the saved setting. **Gap:** the switch also shows on brushes the canvas then refuses (fill, wire, tiled boards, special tips), because the switch's test (`BrushLinePreferences.canSwitch`) is looser than the canvas's (`LineAdmission`). | UI |
| Turn pixels into lines (trace), or make an earlier paint stroke editable | **Missing** | Engine (a tracer, or the stroke's recording) |

## E. Knowing what you paint with

| Concepts shows | JoyBrush today | Side |
|---|---|---|
| The current brush, and whether it is vector or raster, always in view | **Only inside the brush drawer** (a 12 dp badge per tile, `BrushModeBadge`). The strip's brush button shows no lines-or-pixels mark. | UI |
| Which layers hold lines | **Missing** (a layer row shows opacity and blend only) | UI |

## UI-side work, in the order an artist hits it

1. **A lines-or-pixels mark on the strip's brush button.** Every stroke depends on it.
2. **Show the "Editable lines" switch only where the canvas will honour it.** Build `canSwitch` on `LineAdmission`, so nobody turns it on and is then refused.
3. **A stroke eraser built on Select's hit test and its one-step delete.** No engine work.
4. **Select same colour / same brush,** from the More menu.
5. **A "lines" tag on layer rows.**

Fixes go to `joy-creator` in steps, each with host tests. Progress is logged below.

## Engine-side, for the paint-engine session and Codex

- Paint selections (SELECT_PIXELS P2 onward).
- The Slice / trim eraser.
- A partial erase that really splits a line.
- Tracing pixels into lines; making an earlier stroke editable.
- More brushes as lines (realistic media, picture tips, smudge).
- Until paint selections can clip a brush, a brush stroke ends a paint selection (11-27) instead of painting through the ants. When the clip is wired, flip `PAINT_CLIP_WIRED` in `JoyBrushActivity`.

## Log

- **11-27, step 1 (Select):**
  - Cut is one undo step (`SelectionSync.cutAfterCopy`).
  - A Done chip lets go of the selection.
  - A brush stroke ends a paint selection.
  - Undo after Delete survives a touch.
  - SelectBench has new Cut / Undo / Redo and Done steps, and Undo after a move now fails if the selection is lost.
  - Host tests: SelectionSyncTest +2.
  - Cloud: jb-android 618 and jb-androidkit 1380 passed.
- **11-27, UI step 1, done:** the strip's Brush, Smudge and Eraser buttons each carry the lines-or-pixels mark for the brush that slot holds (`BrushModeBadge.drawMark`, shared with the drawer), and their spoken names say "makes editable lines" or "makes pixels". `ToolStripSelectionTest` +1.
- **11-27, UI step 2, done:** `BrushLinePreferences.canSwitch` now asks `LineAdmission`.
  - The shipped Fill pen and Wire, and any paint-behind brush, now say "makes pixels" and offer no switch. Before, they defaulted to lines that the canvas refused at every pen-down.
  - `BrushLinePreferencesTest` +1.
- **11-27, UI step 4, done:** More, then Same colour or Same brush, widens a line selection to the matching lines on the layer. It records no undo step, because selecting is not an edit. `SelectionSyncTest` +1.
- **UI step 3, the stroke eraser, is not done here.**
  - The person expects it on the Eraser button. That pen-down routing is `JbCanvasView` (the paint-engine session's), so it goes there with this plan: in a "Stroke" eraser mode, hit-test each pen sample against the lines as Select's "touching" loop does (`core/select/LineLasso`, `InkErase.WHOLE_STROKE`), collect the touched addresses, and delete them on lift through Select's one-step `deleteLines`.
  - A Select-side stand-in is possible: loop, Touching, then Delete. That is two actions, and they work today.
- **UI step 5 is not done:** the "lines" tag on layer rows. The row subtitle shows only on wide-screen pulled-out rows, and keeping it current needs a refresh hook on every line edit. It is low value for the cost; listed for the layer-column owner.
