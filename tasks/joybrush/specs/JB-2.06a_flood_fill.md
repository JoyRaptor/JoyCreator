# JB-2.06a — Fill maths: flood fill with tolerance and gap closing

| | |
|---|---|
| **Tier** | T2 |
| **Status** | 🟧 Built — see ROADMAP.md |
| **Depends on** | none |
| **Owner area** | NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/fill/FloodFill.kt`, NEW `.../commonTest/.../fill/FloodFillTest.kt` |
| **Estimated size** | ~220 lines + ~200 lines of tests |

## Goal
"Easy fills" (owner). Tap inside line art and the region fills — even when the lines have small gaps
(the #1 frustration with fill tools), and with no pale fringe along anti-aliased lines.

## Contract
```kotlin
package cc.joycreator.joybrush.core.fill
data class FillOptions(
    val tolerance: Float = 0.1f,     // 0..1: max colour distance from the seed colour (RGBA, premultiplied, Euclidean / 2)
    val gapClosePx: Int = 0,         // 0..8: lines this far apart count as closed
    val grow: Int = 1,               // 0..4: grow the result this many px under the line to kill the fringe
)
object FloodFill {
    /**
     * @param w,h size of the reference image; rgba premultiplied RGBA8, row 0 = top (the REFERENCE: what
     *   bounds the fill — usually the composite of the line-art layer or all layers).
     * @return a mask of w×h bytes: 255 = fill, 0 = not.
     */
    fun fill(w: Int, h: Int, rgba: ByteArray, seedX: Int, seedY: Int, options: FillOptions): ByteArray
}
```

## Decisions
1. **Region:** 4-connected scanline flood from the seed over pixels whose colour distance to the seed
   pixel ≤ tolerance.
2. **Gap closing (only if gapClosePx > 0):** build a "wall" mask = pixels NOT within tolerance;
   dilate it by gapClosePx (square structuring element); flood the non-wall area from the seed;
   then dilate the flooded region by gapClosePx again but only into pixels that were within
   tolerance originally (so the fill reaches the real line, not stopping short by the dilation).
   If the seed itself lies inside the dilated wall, fall back to the plain flood.
3. **Grow:** finally dilate the mask by `grow` px (square), unrestricted — the fill tucks under the
   anti-aliased edge of the line so no halo shows.
4. Seed outside the image → all-zero mask. Iterative (explicit stack/queue), never recursive.
5. Performance: 2048×2048 plain flood of an empty image in < 1.5 s on the JVM (test with a generous
   timeout; the phone budget is measured in JB-0.10).

## Tests
1. Closed circle outline, seed inside → inside filled, outside not.
2. Circle with a 3 px gap: gapClosePx 0 leaks outside; gapClosePx 2 does not leak AND the fill
   touches the line all around (no 2-px moat).
3. Tolerance: a soft gradient region fills up to where distance exceeds tolerance.
4. grow 1 covers the one-pixel anti-aliased ring under the line.
5. Seed out of bounds → empty. 6. Big empty image within the time limit.

**Command:** `./gradlew -p joybrush :core:jvmTest` — 0 failures.

## Definition of done
Tests pass (paste) · commit `JB-2.06a: flood fill` · ROADMAP row → 🟧 Built.

## Rulings from the orchestrator (PROVISIONAL — Claude to confirm)

Three tests asserted numbers the geometry does not support. The **implementation was right in all
three cases**; the expectations were the bug, which is the same failure mode this file's own null
test warns about, three paragraphs up. Each correction is written into the test with its derivation
so the next reader can check it rather than trust it:

1. **`gapClosingDoesNotCrossAThinLine`** asserted 480 (15 columns) for `gapClosePx = 1` on a 1 px
   line. Decision 2's push-back step puts the fill back over the moat onto pixels that were within
   tolerance, and x = 15 is white, so the answer is **512** (16 columns) — the same as with no
   closing, which is the correct shape: closing decides whether a region can get *out*, and the
   push-back restores the edge. It also agrees with
   `threePixelGapLeaksWithoutClosingAndIsSealedByTwo`, whose 196 depends on that same no-moat rule.
   The test's own property (the far side of the line is untouched) is still asserted, at x = 17
   as well as x = 20.
2. **`floodIsFourConnectedAndGrowIsSquare`** asserted 25 for a 5 x 5 whose main diagonal is black.
   A square dilation of the x > y triangle does not reach the far triangle — the Chebyshev radius
   runs out. The expectation is now **derived in the test** (minimum Chebyshev distance to the
   triangle, per pixel, compared pixel by pixel) and the derived count is 19. The 25 was a guess.
3. **`growIsClampedToFour`** asserted 73 white pixels for a 3 x 3 box outline on 9 x 9. 81 - 8 black
   = 73 white, but the box is a **closed ring**: the single white pixel inside it, (5,5), is not
   4-connected to the outside, so the region is **72**. The count was of the white pixels, not of
   the region — i.e. the assertion did not actually test 4-connectivity. The enclosed pixel is now
   asserted explicitly, which makes the test prove the property it was named for.

## Questions
