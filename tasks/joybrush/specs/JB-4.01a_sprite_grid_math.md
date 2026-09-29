# JB-4.01a — Sprite grid maths: by pixel size or by cell count, sub-grids, dragging an edge

| | |
|---|---|
| **Tier** | T2 |
| **Status** | see ROADMAP.md |
| **Depends on** | JB-0.02 (`Board`, `SpriteGrid`), JB-4.03a (packer reading order) — Built |
| **Owner area** | NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/sprite/SpriteGridMath.kt`, NEW `.../commonTest/.../sprite/SpriteGridMathTest.kt` |
| **Estimated size** | ~150 lines + ~180 lines of tests |

## Goal
The sprite board (blueprint §1 row 9, Phase 4): the owner sets the grid either as "cells of 64 × 64
px" or "8 columns × 4 rows", sees optional sub-grid lines inside each cell for alignment, and can drag
the grid's right or bottom edge to resize every cell at once. This spec is the maths; the board UI is
JB-4.01.

## Contract
```kotlin
package cc.joycreator.joybrush.core.sprite

import cc.joycreator.joybrush.core.doc.Board
import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.doc.SpriteGrid

object SpriteGridMath {
    /** Cells of exactly cellW × cellH; as many whole columns/rows as fit the board rect (at least 1 each). */
    fun bySize(rect: RectPx, cellW: Int, cellH: Int): SpriteGrid
    /** cols × rows cells; cell size = floor(rect.w / cols) × floor(rect.h / rows) (at least 1 px). */
    fun byCount(rect: RectPx, cols: Int, rows: Int): SpriteGrid
    /** The board rect that holds [grid] exactly, keeping [rect]'s top-left. */
    fun fitRect(rect: RectPx, grid: SpriteGrid): RectPx
    /** Cell [index] in READING order (row-major — the order SpritePacker packs), in document px. */
    fun cellRect(board: Board, index: Int): RectPx
    /** The cell index under a document point, or -1 outside the grid. */
    fun cellAt(board: Board, x: Float, y: Float): Int
    enum class Edge { RIGHT, BOTTOM, CORNER }
    /**
     * Dragging the grid's right / bottom edge (or its corner) by (dx, dy) document px: every cell
     * grows by (dx / cols, dy / rows), rounded to whole px, min 1. cols and rows stay the same.
     */
    fun dragEdge(grid: SpriteGrid, edge: Edge, dx: Float, dy: Float): SpriteGrid
    /** Sub-grid lines: [div] × [div] divisions inside every cell, as (x0, y0, x1, y1) segments in doc px. */
    fun subGridLines(board: Board, div: Int): List<FloatArray>
}
```

## Decisions
1. The grid starts at the board rect's top-left. Cells are whole pixels (sprite sheets are pixel
   exact). A grid is `cols × rows` of `cellW × cellH`; the board may be larger than the grid (spare
   area on the right/bottom is simply outside the grid).
2. Inputs are clamped, never thrown on: sizes and counts < 1 become 1; a cell bigger than the rect
   gives 1 column/row. Upper guard: cols × rows ≤ 4096 and cellW, cellH ≤ 4096 (a sheet beyond that
   cannot be packed or opened by SpriteLab) — clamp cols/rows down to satisfy it.
3. `cellAt` uses half-open cells: x in [left, left + cellW). Non-finite point → -1.
4. `dragEdge`: RIGHT changes only cellW, BOTTOM only cellH, CORNER both. Rounding is to nearest.
5. `subGridLines`: `div` 2..8 (else empty list); only interior lines of each cell (the cell borders
   themselves are drawn by the board). These lines are a HELPER: never exported (blueprint §3).
6. `cellRect` / `cellAt` read the board's `grid`; a board with no grid → `cellAt` = -1 and
   `cellRect` throws `IllegalArgumentException` (asking for a cell that does not exist is a bug).

## Tests
1. bySize(RectPx(0,0,512,256), 64, 64) → 8 × 4 of 64 × 64; bySize with 100 × 100 → 5 × 2 (whole cells only).
2. byCount(RectPx(0,0,500,300), 3, 2) → cells 166 × 150; fitRect → 498 × 300.
3. cellRect index 0 / 7 / 8 on an 8-column grid at board origin (100, −40): reading order, row 1
   starts at index 8, negative origin handled.
4. cellAt: each cell's top-left pixel and bottom-right pixel map to that cell; one pixel right of
   the grid → -1; NaN → -1. `cellAt(cellRect(i))` = i for every i.
5. dragEdge RIGHT +80 on 8 cols of 64 → cellW 74 (64 + 10); BOTTOM −1000 → cellH 1; CORNER both.
6. Guards: byCount(…, 0, 0) → 1 × 1; cols × rows over 4096 is clamped.
7. subGridLines div 2 on a 2 × 1 grid of 64 → 2 cells × (1 vertical + 1 horizontal) = 4 segments,
   at the cell midlines; div 1 or 9 → empty.
8. Agreement with the packer: for a 3-column grid, `cellRect(i)` column/row equal `i % 3`, `i / 3`,
   the same formula `SpritePacker.pack` documents.

**Command:** `./gradlew -p joybrush :core:jvmTest` — 0 failures.

## Do not
Touch no existing file; no change to `SpriteGrid` or the document format.

## Definition of done
Tests pass (paste) · commit `JB-4.01a: sprite grid maths` · ROADMAP row → 🟧 Built.

## Questions
