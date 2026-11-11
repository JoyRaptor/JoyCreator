# JoyBrush ledger: smaller fixes for Opus or Sonnet sessions

Kept by the Atlas / wet-sheet session (Claude Fable 5.2) from 2026-11-10. Each line is a job a mid-tier model can do in
one sitting. **Found by reading the code on the date given, not each tried in the app: check before you fix**
(`tasks/AGENT_START.md`, "Check your sources are current"). Tick a line and add the commit when it lands.

The owner's bar for all of them (2026-11-10): his three-year-old picks any brush and makes a mark. She is never turned away
for the wrong brush or the wrong layer. His rule for how: **an automatic guess keeps a way back** (do the sensible thing,
then offer one tap to change it); never a sentence that explains a rule.

## A. Strokes that are refused today (the "any brush, any layer" list)

Found 2026-11-10 at `e9b1dfb16`. Sentences are in `joybrush/androidkit/.../JbCanvasView.kt` unless said.

| # | Today | What should happen instead |
|---|---|---|
| A1 | Pencil, watercolour, oil, smudge, push or wet paint on an ink layer: "... an ink layer has no pixels for them" (`core/brush/BrushRules.refusalFor`) | Paint on a paint layer: the nearest one above, or a new one made above the ink layer and selected. One-tap way back: undo removes the layer with the stroke |
| A2 | Realistic paint while a layer's mask is selected: "A mask is painted with paint brushes..." (line ~1929) | Paint on the layer itself and leave the mask selection as it was, or switch the selection to the layer |
| A3 | Painting on a hidden layer: "... is hidden. Show it to paint on it." (~2101) | Show the layer and paint (undo hides it again with the stroke) |
| A4 | Symmetry with tuft, watercolour or fill: "Symmetry is not available for this brush." (`core/symmetry/SymmetryLanes.refusalFor`) | Watercolour: works on the wet sheet once it is the default (remove WATERCOLOUR from the refusal then). Tuft and fill: paint the plain stroke, or build the lanes |
| A5 | Fill pen with a mask selected: "Select the paint layer to use the fill pen; masks are not supported yet." (~2150) | Fill the paint layer |
| A6 | Multiply or screen brush on a mask: "... don't paint on masks." (~2224) | Paint on the layer |
| A7 | Realistic paint where editable lines cross: "Realistic paint can't go where editable lines are yet" (`GlPaintEngine.writableMediaTiles`, a `check`, so it surfaces as a stopped stroke) | Larger job (K26 on the JoyBrush roadmap); until then paint on a new layer above |
| A8 | "Screen-width wire brushes are not connected to drawing yet." (~2120) | Hide such brushes from the drawer until they paint |
| A9 | "Turn tiling off to use <tool>" (~647) | Turn tiling off for the stroke, or make the tool work tiled |
| A10 | "This phone has room for N layers..." (~1252) | Keep (a real limit), but check the wording against the no-jargon rule |
| A11 | Symmetry with pencil or oil in the atlas: "Symmetry is not available for pencil, watercolour and oil on this build." (`MediaCanvas.begin`) | The atlas has no edge to pin: build the lanes without a pin, as the wet sheet does (`MediaCanvas.beginOnSheet`) |

## B. Small features

| # | Job | Notes |
|---|---|---|
| B1 | **Zoom readout.** While pinching, show the zoom as a percent in the house style, fading out after; a gentle detent at 100 % | Owner's idea 2026-11-10. 100 % is life size on the Notes (20 px per mm of paper; the screens are about 20.3 px per mm), so say so in its hover label. No readout exists today (grep 2026-11-10). `JbCanvasView.requestRender` already publishes the zoom |
| B2 | A "back to 100 %" gesture or button next to the readout | Check what double-tap already does first |

## C. Wet sheet follow-ups (after it is the default)

Design and limits: `tasks/joybrush/specs/WET_SHEET_20261110.md`. Each item below is self-contained work for Opus or Sonnet.
The big pieces (carrying wet paint over when the view moves, printing with the paper's fine grain, toning down lift) stay
with the session that built the sheet; they are on the job card, not here.

| # | What the person meets today | What to build | Where |
|---|---|---|---|
| C1 | The autosave 30 s after the last stroke dries a wash that is still wet | Save "as if dried" without touching the sheet: for the snapshot only, stamp the sheet's look into temporary copies of the tiles it covers and hand those to `readContents` in place of the layer's own; recycle them after. Then drop the `settleSheet()` call in `JbCanvasView.snapshot`. Leaving the app must still really print (the graphics context can die while paused) | `JbCanvasView.snapshot`, `GlPaintEngine.printOverlay` (a variant that returns textures and pushes no step) |
| C2 | After very many big wet strokes without a pause, Undo stops at a stroke (the sheet's copies passed 128 MB) but the Undo button still looks live | Report "nothing more to undo" from `GlPaintEngine` when `sheetUndoBlocked` says so (`canUndo` as the UI reads it), and say the usual trimmed-history sentence once (`UndoLog.trimmedSentence`) | `GlPaintEngine.undoStep`, `JbCanvasView.reportHistory` |
| C3 | Zoomed far out over fine pencil hatching, a wet wash's colour is computed against a slightly noisy copy of what is under it | Draw the ground through a mip chain or a box filter instead of one tap per cell | `GlPaintEngine.layerLookInto`, `WetSheet.refresh` |
| C4 | A wash on a layer with a blend mode, a mask or a clip is drawn plain over the layer until it dries | In the composited path, draw the overlay through `jb_composite.frag` as the layer's tiles are (its mask and clip textures per tile), or stamp it into temporary tiles as the stroke preview does | `GlPaintEngine.drawComposited`, `renderStrokePreviews` |
| C5 | Every phone gets the same water grid (1280 cells along the screen, 896 on Fast) | Pick it from what the phone manages: the sheet already knows whether it keeps the clock's pace (`WetSheetMath.Pace`). Raise `WetSheet.longCells` for the next sheet when there is room for twice the steps, lower it when a full-screen wash runs under two thirds of the clock. Keep the owner's Detailed / Fast switch as the override | `WetSheet`, `WetSheetMath` |
| C6 | Watercolour on an animation frame, or on a layer that has editable lines, still uses the old water in the atlas (squares possible on big washes) | Give the sheet a plane (the frame's cel) for printing and its ground, and print through the item-safe path on layers with lines | `MediaCanvas.begin`, `GlPaintEngine.printOverlay`, `layerLookInto` |
| C7 | The speed running water may reach is fixed (2.7 mm/s, as approved in the lab) | A "Water speed" row in the ⋯ menu stepping `WetSheet.flowSpeed` through 0.5, 1, 1.5, 2 (the step rule already follows it: `WetSheetMath.maxDt`). Film each on the Note 9; above 1 the water needs more steps a frame | `JoyBrushActivity.moreMenu`, `JbCanvasView` |
| C8 | Nothing shows while the sheet prints a very large wash (a few hundred tiles in one frame) | Measure the print on the Note 9 (`JoyBrushSheet: printed` log line, frame time); if a frame passes about 50 ms, print over several frames (tiles in batches, the overlay clipped to what is still unprinted) | `WetSheet.print`, `GlPaintEngine.printOverlay` |

## D. Paused work passed on by the launch-push session (2026-11-10)

Not this session's, recorded so it is not lost; the BOARD's "Paused work" table is the place of record.

- Studio bug batch (five Studio bugs and Joybot's add-a-marker undo): branch `claude/studio-bugs-20261110-f15370`, not on `joy-creator`.
- The rest of UI-1 (fullscreen camera preview, old Studio project list, Home sideways layout, Records sidebar): branch `claude/ui1-leftovers-20261110-38909d`, not on `joy-creator`.
- Restart sentence: "Continue the 11-10 launch push: check the two claude/*-20261110 branches and the Paused work table in tasks/BOARD.md."
