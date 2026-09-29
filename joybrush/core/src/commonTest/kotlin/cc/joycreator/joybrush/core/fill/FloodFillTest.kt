package cc.joycreator.joybrush.core.fill

import cc.joycreator.joybrush.core.paint.Tiles
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * Flood fill, checked against values worked out by hand.
 *
 * The order of these tests is the order the feature fails in. A fill that is slightly wrong at a
 * seam is invisible; a fill that leaks through a one-pixel gap in an outline, or that fringes along
 * a soft edge, is the thing a person stops using the tool over. So most of what follows is about
 * BOUNDARIES: the tolerance boundary (exact, both sides), the gap-closing boundary (a gap of 2n
 * seals, 2n+1 does not), the tile boundary, the edge of the reference, and the largest input the
 * cap permits.
 */
class FloodFillTest {

    /**
     * THE NULL TEST, and the one that should have been written first. Every other test in this file
     * is a perturbation of it: a uniform image has no wall, no gap, no gradient and no fringe, so
     * the region of a flood fill on it is the whole image and nothing else can be true.
     *
     * It exists because the first version of this file had no such test, and got away with it. The
     * flood seeded its neighbouring rows with a pixel offset where a row index was wanted, so both
     * neighbour scans hit their bounds check and returned: every fill on a multi-row image produced
     * exactly ONE scanline, and growing it by 1 produced three. Sixteen of the suite's tests failed
     * and not one of them was the test that would have said "a fill on a flat colour fills the flat
     * colour".
     *
     * The lesson is not "add more tests", it is that hand-derivation checks ARITHMETIC and cannot
     * check INDEXING. Every expected value in the old suite was verified against the same mental
     * model that produced the bug, so the model agreed with itself beautifully and was wrong. A
     * null case has no arithmetic to get wrong, which is exactly why it is worth having first.
     *
     * The sizes are chosen to catch unit confusion from both sides: non-square (5 x 3, 3 x 5, 17 x 9)
     * so that a row/column swap cannot hide, 1 x 1 and 2 x 2 for the degenerate arithmetic, and 64 x
     * 64 for something big enough that "one row" is obviously wrong. The seeds are the four corners
     * and the middle, because a fill that only works from the top row is still broken.
     */
    @Test
    fun aUniformRegionFillsCompletelyFromAnySeed() {
        for ((w, h) in listOf(1 to 1, 2 to 2, 5 to 3, 3 to 5, 8 to 8, 17 to 9, 64 to 64)) {
            val buf = image(w, h)
            for (seed in listOf(0 to 0, w - 1 to 0, 0 to h - 1, w - 1 to h - 1, w / 2 to h / 2)) {
                val mask = FloodFill.fill(w, h, buf, seed.first, seed.second, exact())
                assertEquals(w * h, count(mask), "${w}x$h filled from $seed")
                assertEquals(w * h, mask.size, "${w}x$h mask size from $seed")
            }
        }
    }

    /**
     * The same null test with the other two stages running, because a stage that is only ever
     * exercised on images with lines in them can be wrong in a way nothing notices.
     *
     * Growing a full mask is a no-op — the dilation cannot reach outside an image it already fills
     * — and closing gaps on an image with NO wall must not eat anything: there is nothing to seal
     * and nothing to grow over. Before the fix both of these returned one scanline, which is the
     * same three rows and the same one row respectively.
     */
    @Test
    fun aUniformRegionSurvivesGrowingAndGapClosing() {
        val w = 32
        val h = 32
        val buf = image(w, h)
        for (grow in 0..4) {
            assertEquals(
                w * h,
                count(FloodFill.fill(w, h, buf, 16, 16, exact(grow = grow))),
                "grow $grow on a full mask",
            )
        }
        for (gap in 0..4) {
            assertEquals(
                w * h,
                count(FloodFill.fill(w, h, buf, 16, 16, exact(gap = gap))),
                "gap $gap with no wall to close",
            )
        }
        // And a uniform region that is NOT square, through the gap path, in case the wall stage
        // reintroduces the same unit confusion somewhere the plain flood does not.
        val tall = image(5, 40)
        assertEquals(200, count(FloodFill.fill(5, 40, tall, 2, 20, exact(gap = 3))))
        val wide = image(40, 5)
        assertEquals(200, count(FloodFill.fill(40, 5, wide, 20, 2, exact(gap = 3))))
    }

    // ── image helpers ───────────────────────────────────────────────────────────

    private fun image(w: Int, h: Int, r: Int = 255, g: Int = 255, b: Int = 255, a: Int = 255): ByteArray {
        val buf = ByteArray(w * h * 4)
        for (i in buf.indices step 4) {
            buf[i] = r.toByte()
            buf[i + 1] = g.toByte()
            buf[i + 2] = b.toByte()
            buf[i + 3] = a.toByte()
        }
        return buf
    }

    private fun put(buf: ByteArray, w: Int, x: Int, y: Int, r: Int, g: Int, b: Int, a: Int) {
        val i = (y * w + x) * 4
        buf[i] = r.toByte()
        buf[i + 1] = g.toByte()
        buf[i + 2] = b.toByte()
        buf[i + 3] = a.toByte()
    }

    /** A 1 px black rectangle outline: the "line art" every geometry test here is drawn with. */
    private fun box(buf: ByteArray, w: Int, x0: Int, y0: Int, x1: Int, y1: Int) {
        for (x in x0..x1) {
            put(buf, w, x, y0, 0, 0, 0, 255)
            put(buf, w, x, y1, 0, 0, 0, 255)
        }
        for (y in y0..y1) {
            put(buf, w, x0, y, 0, 0, 0, 255)
            put(buf, w, x1, y, 0, 0, 0, 255)
        }
    }

    private fun frame(buf: ByteArray, w: Int, h: Int) = box(buf, w, 0, 0, w - 1, h - 1)

    /** Draw a box outline, then erase [gx0]..[gx1] of its top edge: a broken line. */
    private fun boxWithGapInTop(buf: ByteArray, w: Int, x0: Int, y0: Int, x1: Int, y1: Int, gx0: Int, gx1: Int) {
        box(buf, w, x0, y0, x1, y1)
        for (x in gx0..gx1) put(buf, w, x, y0, 255, 255, 255, 255)
    }

    private fun count(mask: ByteArray): Int = mask.count { it != 0.toByte() }

    private fun at(mask: ByteArray, w: Int, x: Int, y: Int): Int = mask[y * w + x].toInt() and 0xFF

    private fun exact(tolerance: Float = 0f, gap: Int = 0, grow: Int = 0) =
        FillOptions(tolerance = tolerance, gapClosePx = gap, grow = grow)

    /**
     * Deterministic noise. NOT a legal premultiplied image — the alphas and colours are unrelated —
     * and that is the point: the distance test must not assume a premultiplied buffer, because the
     * cost of assuming one is a fill that quietly misbehaves on somebody else's compositing bug.
     */
    private fun noise(w: Int, h: Int): ByteArray {
        val buf = ByteArray(w * h * 4)
        var s = 0x2545F491L
        for (i in buf.indices) {
            s = s * 6364136223846793005L + 1442695040888963407L
            buf[i] = ((s ushr 33).toInt() and 0x7F).toByte()
        }
        return buf
    }

    // ── 1. the plain region ─────────────────────────────────────────────────────

    /**
     * A closed outline fills its inside and nothing else.
     *
     * 16 x 16 with a 1 px frame: the frame is 4 * 16 - 4 = 60 px, the inside is 14 x 14 = 196, and
     * 60 + 196 = 256 = 16 * 16. Nothing outside the frame exists in this image, which is itself
     * the first thing to notice — the reference is the whole world, so "outside" needs an image
     * with an outside.
     */
    @Test
    fun closedOutlineFillsTheInsideAndOnlyTheInside() {
        val w = 16
        val buf = image(w, w)
        frame(buf, w, w)
        val mask = FloodFill.fill(w, w, buf, 8, 8, exact())
        assertEquals(w * w, mask.size)
        assertEquals(196, count(mask), "the 14 x 14 inside")
        assertEquals(255, at(mask, w, 8, 8), "the seed")
        assertEquals(255, at(mask, w, 1, 1), "the inside corner")
        assertEquals(0, at(mask, w, 0, 0), "the line itself")
        assertEquals(0, at(mask, w, 0, 8), "the middle of the left line")
    }

    /**
     * The same thing on a circle, because a rectangle has corners and an outline in line art does
     * not. The ring is 784 <= dx^2 + dy^2 < 841, i.e. radius 28 to 29, so the assertions are whole
     * sets rather than a count nobody can check by eye: every pixel inside radius 20 is filled,
     * every pixel outside radius 30 is not, the ring itself is not, and the mask has the eight-fold
     * symmetry of the drawing it came from. A count is not asserted because counting the lattice
     * points inside a circle of radius 28 by hand is not something a test should require of a
     * reader; the symmetry is the stronger claim anyway, and it is what a hand-drawn outline has.
     */
    @Test
    fun closedCircleFillsTheDiscAndNotTheSky() {
        val w = 61
        val buf = image(w, w)
        for (y in 0 until w) for (x in 0 until w) {
            val dx = x - 30
            val dy = y - 30
            val d2 = dx * dx + dy * dy
            if (d2 in 784..840) put(buf, w, x, y, 0, 0, 0, 255)
        }
        val mask = FloodFill.fill(w, w, buf, 30, 30, exact())
        assertEquals(255, at(mask, w, 30, 30), "the centre")
        for (y in 0 until w) for (x in 0 until w) {
            val sqx = (x - 30) * (x - 30)
            val sqy = (y - 30) * (y - 30)
            if (sqx + sqy < 400) assertEquals(255, at(mask, w, x, y), "inside radius 20 at $x,$y")
            if (sqx + sqy > 900) assertEquals(0, at(mask, w, x, y), "outside radius 30 at $x,$y")
        }
        // Eight-fold symmetry about the centre: four quarter turns and the two diagonals.
        for (y in 0 until w) for (x in 0 until w) {
            val dx = x - 30
            val dy = y - 30
            val v = at(mask, w, x, y)
            assertEquals(v, at(mask, w, 30 + dy, 30 - dx), "quarter turn at $x,$y")
            assertEquals(v, at(mask, w, 30 - dx, 30 - dy), "half turn at $x,$y")
            assertEquals(v, at(mask, w, 30 + dx, 30 - dy), "mirror at $x,$y")
        }
    }

    /**
     * A tap that misses the reference gives no region, and does not throw: a tap off the board is
     * something a person does with a finger, not a bug in the program.
     */
    @Test
    fun seedOutsideTheReferenceIsAnEmptyMask() {
        val w = 4
        val buf = image(w, w)
        for (seed in listOf(-1 to 0, 0 to -1, 4 to 0, 0 to 4, 99 to 99, -99 to -99)) {
            val mask = FloodFill.fill(w, w, buf, seed.first, seed.second, FillOptions())
            assertEquals(16, mask.size, "mask is still w x h for seed $seed")
            assertEquals(0, count(mask), "no region for seed $seed")
        }
    }

    /**
     * An entirely transparent reference. The seed is transparent, every pixel is at distance 0 from
     * it, so the region is the whole reference — and that is the intended answer, not a bug: the
     * background of a drawing IS one region, and a bucket tool that filled nothing on an empty
     * board would look broken. It is also the shape of the "this cel has no tiles" case, where the
     * caller has handed over a window of nothing.
     */
    @Test
    fun aFullyTransparentReferenceIsAllOneRegion() {
        val w = 8
        val buf = image(w, w, 0, 0, 0, 0)
        assertEquals(64, count(FloodFill.fill(w, w, buf, 4, 4, exact(tolerance = 0.1f))))
        assertEquals(64, count(FloodFill.fill(w, w, buf, 4, 4, exact(tolerance = 1f))))
        assertEquals(64, count(FloodFill.fill(w, w, buf, 4, 4, exact(tolerance = 0.1f, grow = 1))))
    }

    // ── 2. gap closing ──────────────────────────────────────────────────────────

    /**
     * The spec's headline case: a 3 px break in a closed box leaks with no gap closing and does not
     * leak with gapClosePx 2, and the closed fill reaches the line instead of stopping short.
     *
     * The box is (8,8)..(23,23) in a 32 x 32 image, so there is real artwork-side space to leak
     * INTO — the reason every leak test here keeps the outline away from the image edge. Perimeter
     * 4 * 16 - 4 = 60, less the 3 gap pixels, is 57 wall pixels, so 1024 - 57 = 967.
     *
     * gapClosePx 0:  everything white is one region through the break. 967.
     * gapClosePx 2:  the wall grows 2 px each way, which seals 4 px of gap, so the 3 px break is
     *                shut and the region is the inside only. The inside is 14 x 14 = 196. The fill
     *                is not 196 minus a moat because the area is pushed back out over the wall's
     *                own dilation, restricted to pixels that were within tolerance — so (9,9), the
     *                inside corner that hugs the line, IS filled, and the line itself is not.
     */
    @Test
    fun threePixelGapLeaksWithoutClosingAndIsSealedByTwo() {
        val w = 32
        val buf = image(w, w)
        boxWithGapInTop(buf, w, 8, 8, 23, 23, 15, 17)

        val open = FloodFill.fill(w, w, buf, 16, 16, exact())
        assertEquals(967, count(open), "with no gap closing the whole image floods")
        assertEquals(255, at(open, w, 7, 7), "including well outside the box")

        val closed = FloodFill.fill(w, w, buf, 16, 16, exact(gap = 2))
        assertEquals(196, count(closed), "just the inside")
        assertEquals(0, at(closed, w, 7, 7), "nothing outside the box")
        assertEquals(255, at(closed, w, 9, 9), "the inside corner: no moat around the line")
        assertEquals(255, at(closed, w, 16, 9), "the inside of the closed part of the top line")
        assertEquals(0, at(closed, w, 16, 8), "the line itself is never filled")
    }

    /**
     * The same, one pixel narrower, and the smallest gap closing that does anything. A 1 px break
     * is sealed by gapClosePx 1 because the wall grows 1 px from each side: 2 * 1 = 2 px of gap.
     *
     * Perimeter 60 less 1 gap pixel is 59, so the leaking count is 1024 - 59 = 965. Closed, the
     * region is the 14 x 14 = 196 inside again.
     */
    @Test
    fun onePixelGapNeedsOnlyOnePixelOfClosing() {
        val w = 32
        val buf = image(w, w)
        boxWithGapInTop(buf, w, 8, 8, 23, 23, 16, 16)

        val open = FloodFill.fill(w, w, buf, 16, 16, exact())
        assertEquals(965, count(open), "one pixel is enough to flood the whole image")
        assertEquals(255, at(open, w, 7, 7), "including outside the box")

        val closed = FloodFill.fill(w, w, buf, 16, 16, exact(gap = 1))
        assertEquals(196, count(closed), "just the inside")
        assertEquals(0, at(closed, w, 7, 7), "nothing outside the box")
        assertEquals(255, at(closed, w, 9, 9), "the inside corner")
    }

    /**
     * Gap closing is for GAPS, not for thin lines. A 1 px black line down the middle of a white
     * image, wall grown by 1, is 3 px of wall: the fill stops on its own side and never appears on
     * the other. The two numbers side by side are the whole point — the same options leak through a
     * 1 px gap and do not cross a 1 px line, and nothing about the fill distinguishes them except
     * the arithmetic.
     *
     * No closing: the line is 1 px, so the left side is 16 columns = 512. Closed: the wall dilates
     * to 3 px (x = 15, 16, 17), which pushes the flood back to 15 columns — and then Decision 2's
     * push-back step runs, taking the fill over the moat onto x = 15, which is white and therefore
     * within tolerance of the seed. So the answer is 16 columns = 512, the SAME as with no closing,
     * and x = 20 is untouched.
     *
     * That the two numbers are equal is the point, and the equality is not a coincidence: gap closing
     * is about whether a region can get OUT, and the push-back puts the edge back where it was. A
     * previous version of this test asserted 480 here, which is what you get if you forget the
     * push-back — and which contradicts `threePixelGapLeaksWithoutClosingAndIsSealedByTwo` above,
     * where the spec's own no-moat rule puts the fill right up against the line.
     */
    @Test
    fun gapClosingDoesNotCrossAThinLine() {
        val w = 32
        val buf = image(w, w)
        for (y in 0 until w) put(buf, w, 16, y, 0, 0, 0, 255)

        val open = FloodFill.fill(w, w, buf, 4, 16, exact())
        assertEquals(16 * 32, count(open), "up to the line and no further")

        val closed = FloodFill.fill(w, w, buf, 4, 16, exact(gap = 1))
        assertEquals(16 * 32, count(closed), "still 16 columns: the push-back undoes the dilation")
        assertEquals(0, at(closed, w, 20, 16), "the far side of the line is not filled")
        assertEquals(0, at(closed, w, 17, 16), "nor is the far side of the dilated wall")
        assertEquals(255, at(closed, w, 15, 16), "the fill hugs the line, with no moat")
    }

    /**
     * The exact width rule, which is the one number in this file most likely to be got wrong by
     * half. The wall is dilated on BOTH sides, so gapClosePx n seals a gap of 2n and not 2n+1.
     *
     * A 24 x 24 box at (8,8)..(31,31) in a 40 x 40 image, with 16 px of its top edge missing. That
     * is a perimeter of 4 * 24 - 4 = 92, less 16 gap pixels = 76 wall pixels, so a leak fills
     * 1600 - 76 = 1524. The inside is 22 x 22 = 484.
     *
     *   gap 7: 2 * 7 = 14 < 16, so two pixels of the break survive and it leaks. 1524.
     *   gap 8: 2 * 8 = 16, the break is shut and the inside is filled to the line. 484.
     */
    @Test
    fun gapClosingSealsTwiceTheDistanceAndNotOnePixelMore() {
        val w = 40
        val buf = image(w, w)
        boxWithGapInTop(buf, w, 8, 8, 31, 31, 12, 27)

        val seven = FloodFill.fill(w, w, buf, 20, 20, exact(gap = 7))
        assertEquals(1524, count(seven), "14 px of closing leaves 2 px of break")
        assertEquals(255, at(seven, w, 0, 0), "so the outside floods")

        val eight = FloodFill.fill(w, w, buf, 20, 20, exact(gap = 8))
        assertEquals(484, count(eight), "16 px of closing shuts the break")
        assertEquals(0, at(eight, w, 0, 0), "and the outside is untouched")
        assertEquals(255, at(eight, w, 9, 9), "the fill still reaches the line")
    }

    /**
     * A seed the closing has swallowed. With a black pixel diagonally off the seed and gapClosePx
     * 1, the dilated wall covers the seed, so there is no region to flood at all. Returning an
     * empty mask there would look like a dead tap, so the plain flood runs instead and the person
     * gets their fill. Here that is the 24 white pixels of a 5 x 5 with one black pixel in it.
     */
    @Test
    fun seedInsideTheDilatedWallFallsBackToThePlainFlood() {
        val w = 5
        val buf = image(w, w)
        put(buf, w, 1, 1, 0, 0, 0, 255)
        val mask = FloodFill.fill(w, w, buf, 0, 0, exact(gap = 1))
        assertEquals(24, count(mask), "every white pixel, not nothing and not the whole 25")
        assertEquals(255, at(mask, w, 0, 0), "the seed")
        assertEquals(0, at(mask, w, 1, 1), "the line")
    }

    // ── 3. tolerance ────────────────────────────────────────────────────────────

    /**
     * Tolerance is measured from the SEED, and the boundary is inclusive, exactly.
     *
     * A white seed, then pure black (a difference of 255 in red and nothing else, so a squared
     * distance of 255 * 255 = 65025, which is distance 255 / 510 = 0.5), then a colour one step off
     * black (255 * 255 + 1 * 1 = 65026, which is a hair more than 0.5). At tolerance 0.5 the second
     * pixel is inside and the third is outside, and neither is a rounding accident: 0.5 is exact in
     * binary, the bound is 260100 * 0.25 = 65025.0 exactly, and the sum is an Int, so the test is
     * one exact comparison. A strict `<` here would fail, which is the point of the test.
     */
    @Test
    fun toleranceBoundaryIsExactAndInclusive() {
        val w = 3
        val buf = image(w, 1)
        put(buf, w, 1, 0, 0, 255, 255, 255)     // exactly 0.5 from the white seed
        put(buf, w, 2, 0, 0, 254, 255, 255)     // a hair over 0.5

        val onBoundary = FloodFill.fill(w, 1, buf, 0, 0, exact(tolerance = 0.5f))
        assertEquals(2, count(onBoundary), "the exactly-0.5 pixel is inside, the one beyond is not")
        assertEquals(255, at(onBoundary, w, 1, 0), "65025 <= 65025")
        assertEquals(0, at(onBoundary, w, 2, 0), "65026 > 65025")

        // 0.49 gives a bound of 260100 * 0.2401 = 62450.01, so 65025 is now well outside it.
        val justUnder = FloodFill.fill(w, 1, buf, 0, 0, exact(tolerance = 0.49f))
        assertEquals(1, count(justUnder), "only the seed")
    }

    /**
     * A soft gradient fills up to where the distance from the SEED exceeds the tolerance, and no
     * further — which is not the same as walking the ramp. Each step here is 40 / 510 = 0.078, under
     * the 0.1 tolerance, so a fill that compared each pixel with its NEIGHBOUR would run the whole
     * row and return 5. Comparing with the seed, which is what the spec says and what makes the
     * region well defined, stops at the first step over the bound: 40^2 = 1600 is inside
     * 260100 * 0.01 = 2601, and 80^2 = 6400 is not.
     */
    @Test
    fun gradientStopsWhereTheDistanceFromTheSeedExceedsTolerance() {
        val w = 5
        val buf = image(w, 1, 0, 0, 0, 255)   // the seed is black, so a step of 40 is 40, not 215
        for (x in 1 until w) put(buf, w, x, 0, x * 40, 0, 0, 255)
        val mask = FloodFill.fill(w, 1, buf, 0, 0, exact(tolerance = 0.1f))
        assertEquals(2, count(mask), "the seed and one step; a neighbour-relative fill would give 5")
        assertEquals(255, at(mask, w, 1, 0), "40 / 510 = 0.078 is inside 0.1")
        assertEquals(0, at(mask, w, 2, 0), "80 / 510 = 0.157 is not")
        assertEquals(0, at(mask, w, 4, 0), "and nothing further along the ramp")
    }

    /**
     * The trap this predicate is written to avoid: a nearly transparent pixel is NOT the transparent
     * one. The two differ only in alpha, by 1 / 255 as bytes, so the distance is 1 / 510 = 0.00196 —
     * inside a tolerance of 0.003. A per-channel test at the same tolerance would have seen a
     * difference of 1 / 255 = 0.0039 and rejected it, which on a soft edge means the fill silently
     * changes shape depending on which side of the ramp the tap landed.
     */
    @Test
    fun nearlyTransparentIsNotTheSameColourAsTransparent() {
        val w = 3
        val buf = image(w, 1, 0, 0, 0, 0)
        put(buf, w, 1, 0, 0, 0, 0, 1)
        val mask = FloodFill.fill(w, 1, buf, 0, 0, exact(tolerance = 0.003f))
        assertEquals(3, count(mask), "alpha is in the distance, so the faint pixel is in the region")
    }

    /**
     * Tolerance 0 and tolerance 1, the two ends of the slider, hand-checked as exact squares.
     *
     * White seed, then black, then transparent. Black is 3 * 255 * 255 = 195075 squared, which is
     * 0.866 on the 0..1 scale; transparent is 4 * 255 * 255 = 260100, which is exactly 1.0, the
     * most distant pair there is. At 0 both are out and only the seed is in: 1. At 1 the bound is
     * exactly 260100, so even the transparent pixel is in: 3.
     */
    @Test
    fun zeroToleranceMatchesOnlyAndFullToleranceMatchesEverything() {
        val w = 3
        val buf = image(w, 1)
        put(buf, w, 1, 0, 0, 0, 0, 255)
        put(buf, w, 2, 0, 0, 0, 0, 0)
        assertEquals(1, count(FloodFill.fill(w, 1, buf, 0, 0, exact(tolerance = 0f))))
        assertEquals(3, count(FloodFill.fill(w, 1, buf, 0, 0, exact(tolerance = 1f))))
    }

    // ── 4. grow, and the fringe it exists to kill ────────────────────────────────

    /**
     * Grow is what covers the one-pixel anti-aliased ring under a line. One black pixel in a 7 x 7
     * white field, seed in the corner: without growing, 48 pixels and the line still shows through;
     * with grow 1 the line is covered, and growing further cannot invent anything outside the image
     * so 2 and 4 are also 49.
     */
    @Test
    fun growCoversTheRingUnderTheLineAndStopsAtTheImageEdge() {
        val w = 7
        val buf = image(w, w)
        put(buf, w, 3, 3, 0, 0, 0, 255)

        val none = FloodFill.fill(w, w, buf, 0, 0, exact(grow = 0))
        assertEquals(48, count(none), "the ring is not covered")
        assertEquals(0, at(none, w, 3, 3), "the line shows through")

        assertEquals(49, count(FloodFill.fill(w, w, buf, 0, 0, exact(grow = 1))), "grow 1 covers it")
        assertEquals(49, count(FloodFill.fill(w, w, buf, 0, 0, exact(grow = 2))), "grow 2 is clipped")
        assertEquals(49, count(FloodFill.fill(w, w, buf, 0, 0, exact(grow = 4))), "grow 4 is clipped")
    }

    /**
     * The flood is 4-connected and grow is a square dilation, so grow 1 steps over a one-pixel
     * diagonal line that the fill itself would never cross. That is a real difference between the
     * two and it is asserted here rather than left to be discovered: a 5 x 5 whose main diagonal is
     * black splits into two triangles that share only corners.
     *
     * Without grow, the seed at (4,0) reaches the x > y triangle and nothing else: 4 + 3 + 2 + 1 = 10
     * pixels, and (0,4) is in the other one. Under 8-connectivity the whole square would fill, so
     * this is also the test that the fill is not 8-connected.
     *
     * With grow 1 the answer is NOT 25, which is what a previous version of this test asserted. A
     * square dilation of a triangle does not reach the other triangle: growing by 1 covers a pixel
     * iff some region pixel is within CHEBYSHEV distance 1, and on the far side of the diagonal
     * that radius simply runs out — (4,3) is the only region pixel within 1 of (4,4) and (3,4), but
     * (1,3) and (0,3) are 2 away from everything. So the expectation is DERIVED here rather than
     * picked: the test computes, for every pixel, the minimum Chebyshev distance to the triangle
     * and asserts the mask equals `distance <= 1`, which is 19 pixels. A hand-written 25 was a
     * guess that the geometry does not support, and 19 is the number the geometry gives.
     */
    @Test
    fun floodIsFourConnectedAndGrowIsSquare() {
        val w = 5
        val buf = image(w, w)
        for (i in 0 until w) put(buf, w, i, i, 0, 0, 0, 255)

        val mask = FloodFill.fill(w, w, buf, 4, 0, exact(grow = 0))
        assertEquals(10, count(mask), "one triangle only")
        assertEquals(0, at(mask, w, 0, 4), "the other triangle is not 4-connected to it")

        val grown = FloodFill.fill(w, w, buf, 4, 0, exact(grow = 1))
        var expected = 0
        for (y in 0 until w) for (x in 0 until w) {
            var best = Int.MAX_VALUE
            for (ry in 0 until w) for (rx in 0 until w) {
                if (rx <= ry) continue          // the flood's own region: x > y
                val d = maxOf(abs(rx - x), abs(ry - y))   // Chebyshev, i.e. a square element
                if (d < best) best = d
            }
            val want = if (best <= 1) 255 else 0
            if (want != 0) expected++
            assertEquals(want, at(grown, w, x, y), "grown mask at $x,$y (chebyshev distance $best)")
        }
        assertEquals(19, expected, "the derived count, which the geometry gives and not a guess")
    }

    // ── 5. the reference bounds everything ───────────────────────────────────────

    /**
     * A region larger than one tile, and then the same region as a window: filling a 300 x 300
     * reference and filling a 100 x 100 crop of it must agree exactly where they overlap.
     *
     * That agreement is the property a caller needs to work in windows, and it is the answer to
     * "what if the fill walks into a tile the cel does not have" — it cannot. The fill has no
     * document coordinates, no tile keys and no cel; the reference it is handed IS the universe, so
     * a window taken from tile -1 and the same window taken from tile 511 are the same array of
     * bytes and produce the same mask. 300 x 300 spans four tiles; the frame is 4 * 300 - 4 = 1196
     * pixels and the inside is 298 x 298 = 88804.
     */
    @Test
    fun aWindowAgreesWithTheWholeAndTheFillStaysInsideIt() {
        val big = 300
        val buf = image(big, big)
        frame(buf, big, big)
        val whole = FloodFill.fill(big, big, buf, 150, 150, exact())
        assertEquals(298 * 298, count(whole), "a region larger than one tile")

        val wx = 100
        val wy = 100
        val ox = 100
        val oy = 100
        val window = ByteArray(wx * wy * 4)
        for (y in 0 until wy) for (x in 0 until wx) {
            val s = ((oy + y) * big + (ox + x)) * 4
            val d = (y * wx + x) * 4
            for (k in 0 until 4) window[d + k] = buf[s + k]
        }
        val cropped = FloodFill.fill(wx, wy, window, 50, 50, exact())
        assertEquals(wx * wy, count(cropped), "the window is all one region, and all of it")
        for (y in 0 until wy) for (x in 0 until wx) {
            assertEquals(
                at(whole, big, ox + x, oy + y),
                at(cropped, wx, x, y),
                "the two must agree at $x,$y",
            )
        }
    }

    /**
     * A region that runs across a tile boundary is one region. A black line down x = Tiles.SIZE
     * splits a 512 x 64 reference at exactly the tile edge: with no growing, the fill is the 256
     * columns to the left of the line, 256 * 64 = 16384, and it stops dead on the boundary. With
     * grow 1 it takes the line with it, 257 * 64 = 16448, which is the fringe step crossing the tile
     * edge and is why a fill applied tile by tile must not grow the mask per tile.
     */
    @Test
    fun aRegionCrossingATileEdgeIsOneRegion() {
        val w = 512
        val h = 64
        val edge = Tiles.SIZE
        val buf = image(w, h)
        for (y in 0 until h) put(buf, w, edge, y, 0, 0, 0, 255)

        val mask = FloodFill.fill(w, h, buf, 10, 32, exact())
        assertEquals(edge * h, count(mask), "the 256 columns left of the tile edge")
        assertEquals(255, at(mask, w, edge - 1, 32), "the last column of the first tile")
        assertEquals(0, at(mask, w, edge, 32), "the line, which is the first column of the next")

        val grown = FloodFill.fill(w, h, buf, 10, 32, exact(grow = 1))
        assertEquals((edge + 1) * h, count(grown), "grow takes the tile edge with it")
        assertEquals(255, at(grown, w, edge, 32), "the line pixel is covered")
    }

    /**
     * The degenerate sizes, where the row arithmetic is most likely to step off the image: one
     * pixel, one row, one column, and a grow far larger than the image. A fill that wrapped or
     * clamped wrong shows up here as a wrong count rather than as a crash in the field.
     */
    @Test
    fun degenerateSizesStayInsideTheReference() {
        val one = FloodFill.fill(1, 1, image(1, 1), 0, 0, exact(grow = 4))
        assertEquals(1, one.size)
        assertEquals(1, count(one))

        assertEquals(8, count(FloodFill.fill(8, 1, image(8, 1), 0, 0, exact())), "one row")
        assertEquals(8, count(FloodFill.fill(1, 8, image(1, 8), 0, 0, exact())), "one column")
        assertEquals(8, count(FloodFill.fill(8, 1, image(8, 1), 7, 0, exact())), "one row, far seed")
        assertEquals(8, count(FloodFill.fill(1, 8, image(1, 8), 0, 7, exact())), "one column, far seed")

        val tiny = FloodFill.fill(4, 4, image(4, 4), 0, 0, exact(grow = 4))
        assertEquals(16, count(tiny), "grow 4 on a 4 x 4 image is the whole image")
    }

    // ── 6. what the mask is allowed to be ────────────────────────────────────────

    /**
     * The mask is 255 or 0 and nothing else, whatever the options are, and the same input gives the
     * same bytes twice. The noise is deliberately not a legal premultiplied image; the fill has no
     * business caring, and a mask holding a stray 1 would be the first place it showed.
     */
    @Test
    fun maskIsAlwaysZeroOrFullAndTheFillIsDeterministic() {
        val w = 32
        val buf = noise(w, w)
        for (options in listOf(
            exact(),
            exact(tolerance = 0.2f, gap = 2, grow = 3),
            exact(tolerance = 1f, gap = 0, grow = 4),
        )) {
            val mask = FloodFill.fill(w, w, buf, 16, 16, options)
            assertEquals(w * w, mask.size, "mask is w x h for $options")
            for (i in mask.indices) {
                val v = mask[i].toInt() and 0xFF
                assertTrue(v == 0 || v == 255, "byte $i is $v, not 0 or 255, for $options")
            }
            assertTrue(count(mask) >= 1, "the seed is always in its own region")
            assertContentEquals(mask, FloodFill.fill(w, w, buf, 16, 16, options), "same input, same mask")
        }
    }

    /**
     * Grow reaches exactly `grow` pixels, and no further, from a region that is one pixel.
     *
     * This replaces a version of itself that used the noise image and asserted a count between 1
     * and 49. That was luck dressed up as a test: it only held because the LCG happened not to put
     * two identically-coloured pixels side by side, and "the fill is one pixel plus its grow" is a
     * statement about the dilation, not about a random number generator. Here the region really is
     * one pixel — every neighbour is black against a white seed, which is 0.866 away at tolerance 0
     * — so the expected areas are 1, 9, 25, 49, 81 for grow 0..4, and they check the radius
     * arithmetic of the dilation rather than a bound.
     */
    @Test
    fun aGrowReachesExactlyGrowPixelsAroundASinglePixel() {
        val w = 32
        val buf = image(w, w, 0, 0, 0, 255)                      // black everywhere
        put(buf, w, 16, 16, 255, 255, 255, 255)                  // one white pixel: the whole region
        for (grow in 0..4) {
            val side = 2 * grow + 1
            assertEquals(
                side * side,
                count(FloodFill.fill(w, w, buf, 16, 16, exact(grow = grow))),
                "grow $grow is a $side x $side square",
            )
        }
    }

    // ── 7. options are clamped, not obeyed ──────────────────────────────────────

    /**
     * Out-of-range options are clamped, because they come off a slider. Tolerance 5 is tolerance 1
     * and fills the whole 3 px row (the most distant legal colour is exactly 1.0 away); tolerance -1
     * is tolerance 0 and fills only the seed, because the black pixel in the middle of that row
     * blocks the way to the white one beyond it.
     */
    @Test
    fun toleranceIsClampedToZeroAndOne() {
        val w = 3
        val buf = image(w, 1)
        put(buf, w, 1, 0, 0, 0, 0, 255)
        assertEquals(3, count(FloodFill.fill(w, 1, buf, 0, 0, exact(tolerance = 5f))), "as 1.0")
        assertEquals(3, count(FloodFill.fill(w, 1, buf, 0, 0, exact(tolerance = 99f))), "as 1.0")
        assertEquals(1, count(FloodFill.fill(w, 1, buf, 0, 0, exact(tolerance = -1f))), "as 0.0")
    }

    /** gapClosePx 99 is gapClosePx 8 — the same 484 a deliberate 8 gives on that broken box. */
    @Test
    fun gapClosingIsClampedToEight() {
        val w = 40
        val buf = image(w, w)
        boxWithGapInTop(buf, w, 8, 8, 31, 31, 12, 27)
        assertEquals(484, count(FloodFill.fill(w, w, buf, 20, 20, exact(gap = 99))))
        assertEquals(1524, count(FloodFill.fill(w, w, buf, 20, 20, exact(gap = -3))), "as 0, so it leaks")
    }

    /**
     * grow 99 is grow 4: identical masks, byte for byte, on a 9 x 9 with a 3 x 3 box outline in it.
     *
     * 3 * 3 - 1 = 8 black pixels, so 81 - 8 = 73 white — but the box is a CLOSED ring, so the one
     * white pixel inside it, (5,5), is not 4-connected to the outside. A fill from (0,0) is therefore
     * 72, not 73. The 73 asserted here previously was a count of the white pixels rather than of the
     * region, which is precisely the "four-connected, not eight-connected" property the fill is
     * supposed to have, so it is worth stating: the enclosed pixel is the one the test proves.
     */
    @Test
    fun growIsClampedToFour() {
        val w = 9
        val buf = image(w, w)
        box(buf, w, 4, 4, 6, 6)   // 3 * 3 - 1 = 8 black pixels, and they close a ring
        val at4 = FloodFill.fill(w, w, buf, 0, 0, exact(grow = 4))
        val at99 = FloodFill.fill(w, w, buf, 0, 0, exact(grow = 99))
        assertEquals(81, count(at4), "grow 4 reaches the middle of the block, which grow 1 would not")
        assertContentEquals(at4, at99, "and grow 99 is the same thing")

        val none = FloodFill.fill(w, w, buf, 0, 0, exact(grow = -1))
        assertEquals(72, count(none), "as 0, no grow: 81 - 8 line - 1 enclosed white")
        assertEquals(0, at(none, w, 5, 5), "the white pixel inside the ring is its own region")
    }

    // ── 8. the largest legal input, and the refusals ────────────────────────────

    /**
     * The size cap, from both sides. 2896 x 2896 = 8,386,816 is the largest square under
     * MAX_FILL_PX (2^23 = 8,388,608) and it must complete, exactly, with every pixel of it. One
     * pixel more in either direction is refused — with a four-byte buffer, which also proves the
     * cap is reached by arithmetic and not by an allocation that was never going to succeed.
     */
    @Test
    fun theLargestLegalInputCompletesAndOnePixelMoreIsRefused() {
        val w = 2896
        assertTrue(w.toLong() * w <= MAX_FILL_PX, "2896 is the largest square under the cap")
        assertTrue((w + 1).toLong() * (w + 1) > MAX_FILL_PX, "and 2897 is not")
        val buf = image(w, w)
        val mask = FloodFill.fill(w, w, buf, w / 2, w / 2, exact())
        assertEquals(w * w, mask.size)
        assertEquals(8_386_816, count(mask), "every pixel of the largest reference we accept")

        assertFailsWith<IllegalArgumentException> {
            FloodFill.fill(w + 1, w + 1, ByteArray(4), 0, 0, exact())
        }
        assertFailsWith<IllegalArgumentException> {
            FloodFill.fill(w, w + 1, ByteArray(4), 0, 0, exact())
        }
        // Two sides that overflow an Int if they are multiplied as Ints. 2^30 * 4 = 2^32.
        assertFailsWith<IllegalArgumentException> {
            FloodFill.fill(1 shl 30, 4, ByteArray(4), 0, 0, exact())
        }
    }

    /**
     * The other two refusals and the one thing that is not a refusal. A negative side is a caller
     * bug and throws; a buffer that is one byte short throws, because reading it would be reading
     * somebody else's memory; a side of zero is a legal question with an empty answer, the same
     * answer the renderer gives.
     */
    @Test
    fun illegalReferencesAreRefusedAndAnEmptyOneIsNot() {
        assertFailsWith<IllegalArgumentException> { FloodFill.fill(-1, 4, image(1, 1), 0, 0, exact()) }
        assertFailsWith<IllegalArgumentException> { FloodFill.fill(4, -1, image(1, 1), 0, 0, exact()) }
        assertFailsWith<IllegalArgumentException> {
            FloodFill.fill(4, 4, ByteArray(4 * 4 * 4 - 1), 0, 0, exact())
        }
        assertEquals(0, FloodFill.fill(0, 0, ByteArray(0), 0, 0, exact()).size)
        assertEquals(0, FloodFill.fill(0, 8, ByteArray(0), 0, 0, exact()).size)
        assertEquals(0, FloodFill.fill(8, 0, ByteArray(0), 0, 0, exact()).size)
    }

    /**
     * The performance case: 2048 x 2048, empty, plain flood, with the default options.
     *
     * The spec's target is 1.5 s, and the arithmetic says this runs in a tenth of that: 4.2 M
     * predicate evaluations, 4.2 M mask writes, and two separable dilation passes at radius 1. What
     * is asserted is 10 s, not 1.5 s, because a shared machine that loses 80 % of a core to a
     * neighbour is not a regression and a red test that says otherwise trains people to ignore red.
     * 10 s is still roughly 100x the real cost, so a genuine blow-up — a quadratic seed scan, a
     * recursive flood, a predicate that walks the image per pixel — cannot hide under it.
     *
     * It is a termination test too: 4,194,304 pixels through a flood that is iterative, bounded and
     * non-recursive, with a stack of scanline seeds rather than a queue of pixels.
     */
    @Test
    fun twentyEightyFourSquareOfNothingIsQuick() {
        val w = 2048
        val buf = image(w, w)
        val started = TimeSource.Monotonic.markNow()
        val mask = FloodFill.fill(w, w, buf, w / 2, w / 2, FillOptions())
        val ms = started.elapsedNow().inWholeMilliseconds
        assertEquals(w * w, count(mask), "the whole of it")
        assertTrue(ms < 10_000, "2048 x 2048 took $ms ms")
    }
}
