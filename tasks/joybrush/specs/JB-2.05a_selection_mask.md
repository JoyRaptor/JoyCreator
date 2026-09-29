# JB-2.05a — Selection masks: lasso, rectangle, ellipse, from-fill, and the boolean ops

| | |
|---|---|
| **Tier** | T2 |
| **Status** | see ROADMAP.md |
| **Depends on** | JB-0.07 (`Tiles`), JB-2.06a (`FloodFill`) — both Built |
| **Owner area** | NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/select/SelectionMask.kt`, NEW `.../select/Lasso.kt`, NEW `.../commonTest/.../select/SelectionMaskTest.kt` |
| **Estimated size** | ~260 lines + ~220 lines of tests |

## Goal
Selection "done right" (blueprint §3.5) starts here: the pure maths of WHICH pixels are selected, and
how much (soft edges). The transform box is JB-2.05b; the gesture and GL are the Lead's JB-2.05.

## Contract
```kotlin
package cc.joycreator.joybrush.core.select

import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.shape.Pt

/**
 * Coverage 0..255 per document pixel, stored sparsely in Tiles.SIZE² byte tiles keyed by Tiles.key.
 * An absent tile is 0 everywhere. Immutable: every operation returns a new mask.
 */
class SelectionMask internal constructor(val tiles: Map<Long, ByteArray>) {
    fun coverage(x: Int, y: Int): Int              // 0..255
    fun bounds(): RectPx?                          // tight box of coverage > 0, null when empty
    val isEmpty: Boolean
    fun add(o: SelectionMask): SelectionMask       // per pixel max(a, b)
    fun subtract(o: SelectionMask): SelectionMask  // a * (255 - b) / 255, rounded to nearest
    fun intersect(o: SelectionMask): SelectionMask // a * b / 255, rounded to nearest
    fun invert(within: RectPx): SelectionMask      // 255 - a inside `within`, 0 outside
    companion object {
        val EMPTY: SelectionMask
        fun polygon(points: List<Pt>): SelectionMask            // Lasso.kt does the work
        fun rect(r: RectPx): SelectionMask
        fun ellipse(cx: Double, cy: Double, rx: Double, ry: Double, rotation: Double): SelectionMask
        /** A FloodFill result (w×h bytes, 255/0) placed with its top-left at (originX, originY). */
        fun fromMask(w: Int, h: Int, mask: ByteArray, originX: Int, originY: Int): SelectionMask
    }
}
```

## Decisions
1. **Coordinates:** document px; pixel (x, y) covers the square [x, x+1) × [y, y+1). Negative
   coordinates are legal (the canvas is infinite) — use `Tiles.key` / `floorDiv`, never `/`.
2. **Lasso fill rule: NON-ZERO winding**, not even-odd. A lasso that wiggles back over itself, or
   goes round twice, must not punch a hole (that is the usual complaint about even-odd lassos).
   The polygon is closed automatically (last point joins the first).
3. **Anti-aliasing:** 4 × 4 supersampling. Sample positions inside pixel (x, y) are
   `x + (i + 0.5) / 4`, `y + (j + 0.5) / 4`, i, j in 0..3. Coverage = `round(inside * 255 / 16)`.
   So an axis-aligned rectangle with integer corners is exactly 255 inside and 0 outside.
4. **Rasteriser:** scanline over the 4 sub-rows of each pixel row: collect edge crossings with their
   winding direction, sort by x, fill spans where the running winding ≠ 0. Iterative, no recursion.
   Only tiles that receive coverage are created; a tile that ends up all zero is dropped.
5. `rect` = `polygon` of its 4 corners. `ellipse` = `polygon` of 360 points on it.
6. **Full tiles** may share one read-only `FULL` array (all 255) to save memory; any operation that
   would change a shared array copies it first. Tests must not be able to tell the difference.
7. Garbage in: a polygon with < 3 points, or any non-finite coordinate, gives `EMPTY` (no throw).
   Size guard: a polygon whose bounding box exceeds 16384 × 16384 px gives `EMPTY` (the caller
   shows "selection too large"; the budget is the same spirit as JB-2.13a's pixel budget).

## Tests
1. Square (10,10)-(20,20): 100 pixels at 255, every neighbour 0, `bounds() == RectPx(10,10,10,10)`.
2. Same square at (-300, -5): proves negative coordinates and a tile seam.
3. Triangle (0,0),(40,0),(0,40): pixel (19, 20), whose centre lies ON the hypotenuse, is between 96 and 160 (6 samples inside, 4 exactly on the edge, 6 outside); pixels well inside are 255.
4. **Figure-8** (self-crossing) lasso: both lobes filled. **Double loop** (same circle drawn twice):
   the inside is 255, not 0 (proves non-zero, not even-odd).
5. Lasso crossing a tile boundary at x = 256: the coverage on both sides of the seam matches a
   single-tile render of the same shape shifted (no seam artefacts).
6. Ops: add / subtract / intersect / invert on two overlapping squares — exact expected counts;
   `a.subtract(a).isEmpty`; `a.intersect(EMPTY).isEmpty`; `a.add(EMPTY)` equals `a` pixel for pixel.
7. `fromMask` of a FloodFill result at origin (-7, 300) round-trips every pixel.
8. Ellipse r = 50 area: sum(coverage) / 255 within 1% of π·50².
9. Garbage: 2 points, NaN point, 20000-px-wide polygon → `EMPTY`.
10. Performance: a 3000-point lasso around a 2048 × 2048 area in < 600 ms on the JVM (generous
    timeout; the phone budget is measured in JB-0.10).

**Command:** `./gradlew -p joybrush :core:jvmTest` — 0 failures.

## Do not
Touch no existing file. No GL, no Android, no dependencies.

## Definition of done
Tests pass (paste) · commit `JB-2.05a: selection masks` · ROADMAP row → 🟧 Built.

## Questions
