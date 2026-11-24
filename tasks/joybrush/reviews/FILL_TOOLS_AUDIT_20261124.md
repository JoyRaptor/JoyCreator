# FILL-TOOLS audit, 2026-11-24 (read-only)

By Claude Opus 5.5 (Studio/STU-5 lane, taken from the JoyBrush roadmap's unclaimed OPEN AUDIT rows L11 / FILL-TOOLS). Source: `joy-creator` at `5ac4b5754`. Nothing was built, run or installed. A read-only scout read the code; I re-checked the load-bearing claims myself (no main-code caller of `FloodFill.fill` except `BenchCases`, `R51Measurement` and core's own `FillTrace`, which is itself unwired; `GlPaintEngine.replaceTiles` at line 2127 takes the `FillRequest`; the refusal sentences in `FillPenRaster`). Select pixel PhaseB is excluded.

## What a person can do today

| Fill | State | How it is reached | Commit and undo | Proof |
|---|---|---|---|---|
| **Fill pen** (draw a closed shape, it fills) | BUILT, wired | A brush, not a tool (ruling R21): "Fill pen" on the brush drawer's Fill shelf | On pen-up, one `GlPaintEngine.replaceTiles(..., fill=FillRequest)` call = **one** `UndoLog` step; on a layer with editable lines it is stored as a pixel slab in time order | `FillPenDispatchTest` (11), `FillPenRasterTest` (17+), core `FillPenTest`, `MaskPaintTest`; device: the app's own bench matrix 10-17 (`DEVICE_MATRIX_20261017`, four boards, `undoSteps:1, undoExact:true`). **No person has been seen filling a shape on a phone** (11-13 Note 9 check could only draw straight lines by adb, so no area was filled) |
| Fill pen or multiply/screen glaze with a **mask selected** | BUILT (JBX-05/06) | same | fills the layer, the mask stays selected, a notice says so | `FillPenDispatchTest.withTheMaskSelectedTheFillPenFillsTheLayer`; the glaze case has no unit test I could find |
| **Tap-fill (bucket)** | NOT WIRED | nothing: no tool, brush, gesture or engine word; a tap with the fill pen says "That shape is too small to fill." | none | core only: `FloodFill` (+ barrier corpus), `FillTrace` (+ topology corpus), `SelectionMask.fromMask` exist and are tested; no screen calls them |

Refusals the fill pen speaks: locked layer (refused, names the layer), a path over 32,768 samples or px ("Fill stroke is too long; draw a smaller shape"), a speck under 4 screen px ("That shape is too small to fill."), nothing would change ("Nothing to fill there."); over 64 tiles / 4,194,304 px / 128 destination tiles is refused before any allocation (wording not re-read). A hidden layer is shown and filled with a notice; an armed Tile board folds the shape into the repeat; a media layer is filled like any other.

## The 10-07 fill handoff against today

Built as promised: the canvas branch, guide-snapped samples, smoothing and the nonzero polygon, the closed transient preview, one atomic read/replace commit, normal/behind/erase, cancel and after-frame preview clearing, the frozen stroke settings, the budgets, region projection that keeps hidden shared paint and other frames.
Changed on purpose since (the handoff text is out of date, not the code): hidden layers are shown instead of refused, a mask selection fills the layer, armed tiling folds into the repeat, media layers fill, the pen eraser over media takes the ordinary raster eraser, and the fill now passes a `FillRequest` into the engine (editable lines v1.2, R50) where the handoff said no engine change.
Never done: the handoff's own phone acceptance list (draw a closed shape, one Undo and Redo, across an animation boundary, erase mode, second-finger cancel, save and reopen).

## What tap-fill would take (nothing here is built)

1. A trigger (a bucket tool or brush kind) and a tap branch in `JbCanvasView.startStroke`.
2. A reference picture of the layer (or of what is visible) for `FloodFill.fill`.
3. The chain `FloodFill.fill` -> `SelectionMask.fromMask` -> `FillPenRaster.plan` / `MaskPaint` -> `replaceTiles(fill=...)`: every piece exists; reusing `replaceTiles` gives one undo step for free.
4. Tolerance / gap-close / grow controls (`FillOptions`) and plain refusal sentences ("nothing to fill there", "the area leaks out", "too big").
5. A preview of the region, dispatch tests and a one-undo-step test.
Files: `JbCanvasView.kt` (claimed: "atlas solid" for media strokes; the screens lane for the non-media `startStroke` part), possibly `GlPaintEngine.kt` (claimed: "atlas solid"), `JoyBrushActivity.kt` (shared with Codex's save work), new classes under `androidkit/tools/` (unclaimed). **A builder must ask the atlas-solid session, the screens lane and Codex first.**

## Owed on a phone (for the phone lane; a real finger or pen, not adb lines)
Draw a closed shape with the Fill pen on a paint layer: it fills; one Undo removes it and one Redo brings it back; the same across an animation frame boundary; in erase mode; cancel by a second finger mid-shape; save and reopen keeps it.
