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
| Stroke eraser: touch a stroke, the whole stroke goes | **Built 11-27:** the Whole lines eraser in the Erasers shelf, one undo step per stroke (log below) | UI (done) |
| Slice / trim to intersections | **Built 11-27:** the Trim to crossing eraser. A middle cut that would leave two pieces is not yet possible (log below). | UI and core, plus the one engine edit kind |
| Erase part of a line, and it stays a line in pieces | **Built 11-27 for ends and whole short lines:** the Part of a line eraser. A middle cut is not yet possible (log below). The old note follows: **Not a split.** Erasing over a line today stacks an erase mark on top (`JbCanvasView` 2504-2512). It looks right, but the line underneath stays whole. Unsure whether moving the line later shows the erased part again. | Engine (`InkErase.PARTIAL` exists in core) |

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
| Which layers hold lines | **Built 11-27:** a layer that holds editable lines wears the strip's lines mark in the layer column and says "lines" in its pulled-out row (`LayerColumnLinesTagTest`). Phone look owed. | UI (done) |

## UI-side work, in the order an artist hits it

1. **A lines-or-pixels mark on the strip's brush button.** Every stroke depends on it.
2. **Show the "Editable lines" switch only where the canvas will honour it.** Build `canSwitch` on `LineAdmission`, so nobody turns it on and is then refused.
3. **A stroke eraser built on Select's hit test and its one-step delete.** No engine work.
4. **Select same colour / same brush,** from the More menu.
5. **A "lines" tag on layer rows.** Done 11-27.

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
- **11-27, UI step 3, done (orchestrator gave this lane `JbCanvasView`'s eraser pen-down routing for it):**
  - **The tool:** the **Whole lines** eraser is a new brush in the Erasers shelf (`brushes/lineeraser`, `joybrush.eraser.lines`). With it in the hand, `androidkit/select/StrokeEraserTool` stands in front of Select's tool on the canvas.
  - **What a stroke does:** every editable line the stroke touches on the active layer vanishes as the eraser crosses it (lifted and shown empty) and is deleted on lift through Select's one-step delete. One stroke is one undo step.
  - **What it leaves alone:** locked lines stay; a cancelled stroke takes nothing; one finger pans once a pen has been seen. The pen button's lasso, hover and every other tool pass straight on to Select.
  - **How a touch is decided:** `core/select/StrokeEraseHit` sweeps a disc the eraser's size (never under 4 screen px) along the path and tests it against the lines as ribbons.
  - **`JbCanvasView` change:** only one read-only getter (`fingersDraw`).
  - **Tests:** `StrokeEraseHitTest` 6, `StrokeEraserToolTest` 6; the shipped-brush lists now include the new folder.
  - **Paint is untouched by this eraser;** the ordinary eraser rubs out paint. Erasing part of a line and keeping the pieces as lines is still engine work.
  - **Phone check owed:** on the Note 9, draw three crossing lines, pick Eraser, then Whole lines, and stroke across two of them. Both should go at once and one Undo should bring both back. SelectBench runs only on the phone, so it has not been run.
- **UI step 5 is not done:** the "lines" tag on layer rows. The row subtitle shows only on wide-screen pulled-out rows, and keeping it current needs a refresh hook on every line edit. It is low value for the cost; listed for the layer-column owner.

## Slice eraser and partial erase: read 11-27, engine-side (one small piece)

**What exists:**
- `core/vector/VectorEraser.erase(lines, eraserPath, mode)` finds the touched spans:
  - `PARTIAL`: the span under the eraser;
  - `TO_INTERSECTION`: out to the nearest crossing on each side;
  - `WHOLE_STROKE`.
- `core/vector/InkErase.run(...)` turns the surviving pieces into contiguous sublists of the line's own samples, so a piece stays a re-brushable line (LEAD_RULINGS R20). It refuses captured records in words.
- The crossing search, the box pre-filter and the piece maths are written and tested.
- The tool side would be the Whole lines eraser again: another mode of `StrokeEraserTool`, chosen by an eraser brush in the Erasers shelf.

**Why it cannot land as one undo step today:**
- Erasing the middle of a line leaves two pieces. One gesture must therefore remove one line and add two, and the owner's rule makes that one undo step.
- Line edits are applied inside `GlPaintEngine.editLines` (androidkit `gl/`, around line 3606). It knows only Replace (same address), Add, Delete and Bake.
- Replace for one piece plus Add for the rest is two edits, so two presses to undo.
- Only an erase that trims a line's end, leaving one piece, fits in one Replace.

**The engine piece needed (small):**
- A new `LineEdit.Split(removed: Set<LineAddress>, added: List<AddressedStroke>, brushes, assets)`.
- `editLines` applies it as its Delete loop followed by its Add loop in the same pass. The pass already gathers every tile, slab and line change of one edit into ONE `UndoLog` entry, so undo and redo stay one step.
- `LineEdit.addresses()` returns both sets.
- `CanvasSelectionHost`'s refusal and history mapping (around line 251) treats it as a delete plus an add.
- About 30 lines in `GlPaintEngine.kt` and `LineTypes.kt`. The rest (the tool modes, the two eraser brushes, the tests with crossing lines) is UI and core.

**Built 11-27** (the orchestrator approved the one engine change):
- **The edit kind.** `LineEdit.Split` shortens lines IN PLACE (same address, same seq) and removes lines erased whole, in one edit, so one undo step.
  - It never adds lines. A cel's seqs are one per line (LineRules D1), so a line cut in its middle (two pieces) would need a second seq.
  - That case is left whole, and the person is told once: "Cutting a line in two isn't possible yet...".
  - Giving pieces their own seqs needs a format decision: renumber everything above them (pixel slabs included), or a sub-order within a seq. That is for the engine owner or the owner.
- **The engine change.** `GlPaintEngine.editLines` gained only the Split branch, 3 lines. Its list change is in the pure `lines/LineSplitApply`, which is JVM-tested; the other edit kinds' engine code is untouched.
- **Two new erasers in the Erasers shelf.** Both cut with core's `InkErase`, so what is left is the line's own samples:
  - **Trim to crossing** (`brushes/trimeraser`, Concepts' slice): cuts an overshoot back to the line it crosses.
  - **Part of a line** (`brushes/parteraser`): rubs out the part under the eraser.
- **Tests:** `LineSplitApplyTest` 3 (in place, seq kept, untouched lines identical, a gone line refused, every edit kind's addresses as before); `StrokeEraserToolTest` +3 with crossing lines (trim, end erase plus whole short line, middle cut left whole).
- **Cloud:** jb-core 3026, jb-androidkit 1383, jb-android 637 and the app build all passed.
- **Owed:**
  - A phone check that one press of Undo puts a trimmed line back whole. That is one engine edit, so one undo entry, but it is not provable on the JVM, where the engine's tile work needs GL.
  - These two erasers have no live preview while dragging: the cut shows on lift.
