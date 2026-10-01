package cc.joycreator.joybrush.core.paper

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * An imported brush's own texture becomes a surface (JB-9.11), test for test from the spec's list.
 *
 * This is the pure half: a greyscale pattern in, the packed RGBA layout out, through
 * [SurfaceMaps] (JB-9.01). It draws nothing and it stores nothing — which importer calls it, and
 * where the packed bytes go, is open under the spec's Questions.
 *
 * The two spec bullets are tests 1 and 2 here. Test 3 pins Decision 4 (a flat pattern), and test 4
 * pins the two guards the contract's own words imply, because a guard with no test is a comment.
 *
 * Every expected number below is derived in its own comment.
 */
class ImportedTextureTest {

    /** A 4×4 checkerboard of single texels, 0 and 255 alternating — the spec's first test input. */
    private fun singleTexelCheckerboard(w: Int, h: Int): ByteArray =
        ByteArray(w * h) { i -> if (((i % w) + (i / w)) % 2 == 0) 0 else 255.toByte() }

    /** The same 4×4 as four 2×2 blocks — see test 1 for why this is the pattern that has any slope. */
    private fun blockCheckerboard(w: Int, h: Int, cell: Int): ByteArray =
        ByteArray(w * h) { i ->
            val cx = (i % w) / cell
            val cy = (i / w) / cell
            if ((cx + cy) % 2 == 0) 0 else 255.toByte()
        }

    // ---- 1. a 4x4 checkerboard round-trips its bytes, and invert flips bytes and slopes ------------

    /**
     * The spec's first test input: a 4×4 checkerboard of 0 and 255.
     *
     * **B round-trips** — every one of the 16 B channels is the input byte, because [SurfaceMaps.pack]
     * writes the height byte through untouched (JB-9.01, test 7) and this row adds no normalisation
     * of its own (Decision 3: no resizing, and nothing says anything about rescaling).
     *
     * **The spec's "slopes non-zero at the edges" cannot hold for this input, and the test says so.**
     * A checkerboard of single texels has period 2, so `H[y][x+1] == H[y][x−1]` at every texel: the
     * Scharr kernel's three difference terms are each a difference of two EQUAL heights, so dx and dy
     * are exactly 0 everywhere — the same fact as JB-9.01's own test 2 derivation, where three
     * identical rows make dy exactly 0. On top of that, `defaultSlopeRange` floors the range at
     * 0.001, so a flat surface packs to R = G = encodeSlope(0f, range).
     *
     * So the non-zero-edge claim is pinned on the smallest pattern that CAN slope — the same 4×4 with
     * a 2-texel cell, which has period 4 — and this function pins the single-texel board's true
     * behaviour instead of a behaviour it does not have. The corners are the claim that matters: at
     * (0,0) the left neighbour is x = 3 by the wrap, so the edge texel reads the far side of the
     * pattern and cannot be zero.
     *
     * Derivations at the corner (0,0) of the 2-texel-cell board, heights in units of 1 (255/255f is
     * exactly 1f, so every difference is exactly ±1):
     * ```
     * rows:      0 0 1 1        up = row 3, down = row 1
     *           0 0 1 1
     *           1 1 0 0        left = x 3, right = x 1
     *           1 1 0 0
     * dx = (3·(H3,1 − H3,3) + 10·(H0,1 − H0,3) + 3·(H1,1 − H1,3)) / 32
     *    = (3·(1 − 0)       + 10·(0 − 1)     + 3·(0 − 1))      / 32 = −10/32 = −0.3125
     * dy = (3·(H1,3 − H3,3) + 10·(H1,0 − H3,0) + 3·(H1,1 − H3,1)) / 32
     *    = (3·(1 − 0)       + 10·(0 − 1)     + 3·(0 − 1))      / 32 = −10/32 = −0.3125
     * ```
     * −10/32 = −5/16 is an exact binary fraction, so it is asserted exactly on the kernel's own floats
     * and not within a tolerance. Both terms that survive are the two rows of the SAME cell, so the
     * corner is no steeper than any other texel on this board: 0.3125 is the maximum, and the range
     * comes out 0.313 (99.9th percentile of |slope|, then rounded UP to a multiple of 0.001).
     */
    @Test
    fun aFourByFourCheckerboardKeepsItsBytesAndItsSlopesComeFromTheWrap() {
        val w = 4
        val h = 4

        val single = ImportedTexture.toSurface(singleTexelCheckerboard(w, h), w, h, invert = false)
        assertEquals(w, single.w)
        assertEquals(h, single.h)
        assertEquals(w * h * 4, single.rgba.size, "RGBA8, four bytes a texel")
        val singleTexels = singleTexelCheckerboard(w, h)
        for (i in singleTexels.indices) {
            assertEquals(singleTexels[i], single.rgba[i * 4 + 2], "B at texel $i is the input byte")
        }
        for (i in 0 until w * h) {
            assertEquals(SurfaceMaps.encodeSlope(0f, single.slopeRange), single.rgba[i * 4].toInt() and 0xFF, "R at texel $i: a period-2 board has no dx")
            assertEquals(SurfaceMaps.encodeSlope(0f, single.slopeRange), single.rgba[i * 4 + 1].toInt() and 0xFF, "G at texel $i: a period-2 board has no dy")
        }

        val board = blockCheckerboard(w, h, 2)
        val surface = ImportedTexture.toSurface(board, w, h, invert = false)
        for (i in board.indices) {
            assertEquals(board[i], surface.rgba[i * 4 + 2], "B at texel $i is the input byte")
        }
        val range = surface.slopeRange
        assertTrue(range > 0f, "a board with a 2-texel cell slopes, so its range is not the flat floor: $range")
        for (x in 0 until w) for (y in 0 until h) {
            val i = y * w + x
            val r = surface.rgba[i * 4].toInt() and 0xFF
            val g = surface.rgba[i * 4 + 1].toInt() and 0xFF
            assertTrue(r != SurfaceMaps.encodeSlope(0f, surface.slopeRange) || g != SurfaceMaps.encodeSlope(0f, surface.slopeRange), "texel ($x,$y) is on the border and must still slope from the wrap")
        }
        assertEquals(0, surface.rgba[2].toInt() and 0xFF, "B at (0,0) is the board's own first texel")

        // The range handed back is the one JB-9.01 computes from these slopes, recomputed here.
        val (dx, dy) = SurfaceMaps.slopes(FloatArray(board.size) { board[it].toInt().and(0xFF) / 255f }, w, h)
        assertEquals(SurfaceMaps.defaultSlopeRange(dx, dy), range, "the slopeRange used is SurfaceMaps.defaultSlopeRange")

        // The corner's slopes are asserted on the KERNEL's floats and on the ENCODED byte, never on a
        // decoded one. The byte cannot carry −0.3125 exactly: the range is 0.313 (rounded up, so
        // nothing clips) and the byte is a rounding of `255·(0.5 − 0.5·0.3125/0.313)`, so decoding
        // this texel back gives −0.313. That is the range's own rounding, not a slope error, and a
        // test asserting the decoded number would pin the rounding instead of the slope.
        assertEquals(-0.3125f, dx[0], "dx at (0,0) from the kernel")
        assertEquals(-0.3125f, dy[0], "dy at (0,0) from the kernel")
        assertEquals(SurfaceMaps.encodeSlope(dx[0], range), surface.rgba[0].toInt() and 0xFF, "R at (0,0) is dx encoded")
        assertEquals(SurfaceMaps.encodeSlope(dy[0], range), surface.rgba[1].toInt() and 0xFF, "G at (0,0) is dy encoded")
    }

    /**
     * `invert` flips B (255 − b) and negates the slopes, on the same 2-texel-cell board.
     *
     * B is exact: the flipped byte IS the height byte, and [SurfaceMaps.pack] writes height through.
     * The slopes are compared as DECODED values, not as bytes, because a byte is a rounding of the
     * slope and `encode(−s)` is `255 − encode(s)` only up to that rounding: one step is `2·range/255`,
     * so the two roundings can differ by one step (a `0.5` tie goes up in both, and `255 − round(y)`
     * is not always `round(255 − y)`). Two half-steps is the honest bound, and the range itself must
     * come out IDENTICAL, because a percentile of `|slope|` cannot tell a slope from its negation.
     */
    @Test
    fun invertFlipsTheHeightBytesAndNegatesTheSlopes() {
        val w = 4
        val h = 4
        val board = blockCheckerboard(w, h, 2)
        val plain = ImportedTexture.toSurface(board, w, h, invert = false)
        val flipped = ImportedTexture.toSurface(board, w, h, invert = true)

        for (i in board.indices) {
            val want = (255 - board[i].toInt().and(0xFF)).toByte()
            assertEquals(want, flipped.rgba[i * 4 + 2], "B at texel $i is 255 − the input byte")
        }
        assertEquals(plain.slopeRange, flipped.slopeRange, "a range is a percentile of |slope|, so it is sign-blind")

        val range = plain.slopeRange
        val halfStep = range / 254f
        val slack = halfStep * 1e-3f
        for (i in 0 until w * h) {
            for (channel in 0..1) {
                val was = SurfaceMaps.decodeSlope(plain.rgba[i * 4 + channel].toInt() and 0xFF, range)
                val now = SurfaceMaps.decodeSlope(flipped.rgba[i * 4 + channel].toInt() and 0xFF, range)
                assertTrue(
                    abs(now + was) <= 2f * halfStep + slack,
                    "channel $channel at texel $i: $was became $now, and inverting must negate it",
                )
            }
        }
    }

    // ---- 2. luminance is the sRGB luma formula ------------------------------------------------------

    /**
     * Pure R, pure G, pure B, white and black, against `0.2126 R + 0.7152 G + 0.0722 B` on sRGB bytes.
     *
     * The five literals are the weights themselves pinned, and they are only reachable if the three
     * coefficients and their order are right:
     * ```
     * R     255·0.2126 =  54.213 → 54      G     255·0.7152 = 182.376 → 182
     * B     255·0.0722 =  18.411 → 18      white 255·1.0000 = 255.000 → 255      black → 0
     * ```
     * None of the five lands on a `.5` tie, so no rounding rule can pass this by luck. The whole
     * image is then re-checked against the formula written out in the test, so a weight that drifts
     * by more than a rounding step is caught even where the literal happens to survive.
     */
    @Test
    fun luminanceIsTheSrgbLumaFormula() {
        val patches = listOf(
            Triple("pure red", intArrayOf(255, 0, 0), 54),
            Triple("pure green", intArrayOf(0, 255, 0), 182),
            Triple("pure blue", intArrayOf(0, 0, 255), 18),
            Triple("white", intArrayOf(255, 255, 255), 255),
            Triple("black", intArrayOf(0, 0, 0), 0),
        )
        val w = patches.size
        val h = 3
        val rgb = ByteArray(w * h * 3)
        for (y in 0 until h) for ((x, patch) in patches.withIndex()) {
            val i = (y * w + x) * 3
            rgb[i] = patch.second[0].toByte()
            rgb[i + 1] = patch.second[1].toByte()
            rgb[i + 2] = patch.second[2].toByte()
        }
        val grey = ImportedTexture.luminance(rgb, w, h)
        assertEquals(w * h, grey.size, "one byte out per texel in")
        for (y in 0 until h) for ((x, patch) in patches.withIndex()) {
            val (r, g, b) = patch.second
            val want = (0.2126 * r + 0.7152 * g + 0.0722 * b).roundToInt()
            assertEquals(want, grey[y * w + x].toInt() and 0xFF, "${patch.first} at ($x,$y)")
        }
        for (i in grey.indices) {
            val r = rgb[i * 3].toInt() and 0xFF
            val g = rgb[i * 3 + 1].toInt() and 0xFF
            val b = rgb[i * 3 + 2].toInt() and 0xFF
            assertEquals((0.2126 * r + 0.7152 * g + 0.0722 * b).roundToInt(), grey[i].toInt() and 0xFF, "texel $i")
        }
    }

    // ---- 3. a flat pattern (Decision 4) -------------------------------------------------------------

    /**
     * A pattern that is one value all over gets `slopeRange` 0.001 and a surface that says so.
     *
     * 0.001 is Decision 4's number, and it is also what JB-9.01 hands back on its own:
     * `defaultSlopeRange` ends in `coerceAtLeast(0.001f)`, so the flat case never produces the 0 that
     * would make [SurfaceMaps.pack] refuse the range. R and G are 128 (the halfway byte) for every
     * texel, and B is the constant byte, so a flat texture is a valid surface a shader can read
     * rather than a special case. The importer's own "flat texture" flag in the import report is
     * open under the spec's Questions; the range and the bytes are not.
     */
    @Test
    fun aFlatPatternGetsTheFlatSlopeRangeAndAReadableSurface() {
        val w = 6
        val h = 5
        val flat = ByteArray(w * h) { 128.toByte() }
        val surface = ImportedTexture.toSurface(flat, w, h, invert = false)
        assertEquals(0.001f, surface.slopeRange, "Decision 4's floor, which is also SurfaceMaps' own")
        for (i in flat.indices) {
            assertEquals(SurfaceMaps.encodeSlope(0f, surface.slopeRange), surface.rgba[i * 4].toInt() and 0xFF, "R at texel $i")
            assertEquals(SurfaceMaps.encodeSlope(0f, surface.slopeRange), surface.rgba[i * 4 + 1].toInt() and 0xFF, "G at texel $i")
            assertEquals(128.toByte(), surface.rgba[i * 4 + 2], "B at texel $i")
        }
    }

    // ---- 4. the two guards the contract's words imply ----------------------------------------------

    /**
     * The contract says "any size ≥ 3" and names the arrays, so both refusals are pinned here.
     *
     * The size floor is [SurfaceMaps]'s own (`a surface needs at least 3x3 to have interior texels`),
     * because a Scharr texel reads `x±1` and `y±1` and a 2-wide map would read itself twice. A byte
     * count that does not match `w*h` is the other kind of mistake — an importer that guessed the
     * width from a header and got it wrong — and the message has to name both numbers, or the next
     * agent to hit it is guessing which side was wrong.
     */
    @Test
    fun tooSmallOrTheWrongNumberOfBytesIsRefusedByName() {
        val tooSmall = assertFailsWith<IllegalArgumentException> {
            ImportedTexture.toSurface(ByteArray(4), 2, 2, invert = false)
        }
        assertTrue(tooSmall.message.orEmpty().contains("3x3"), "names the floor: ${tooSmall.message}")

        val wrongCount = assertFailsWith<IllegalArgumentException> {
            ImportedTexture.toSurface(ByteArray(63), 8, 8, invert = false)
        }
        assertTrue(
            wrongCount.message.orEmpty().contains("63") && wrongCount.message.orEmpty().contains("64"),
            "names both counts: ${wrongCount.message}",
        )

        val wrongRgb = assertFailsWith<IllegalArgumentException> {
            ImportedTexture.luminance(ByteArray(8 * 8 * 4), 8, 8)
        }
        assertTrue(
            wrongRgb.message.orEmpty().contains("256") && wrongRgb.message.orEmpty().contains("192"),
            "names both counts, and says the channel count: ${wrongRgb.message}",
        )
    }
}
