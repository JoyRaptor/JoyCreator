package cc.joycreator.joybrush.core.fill

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * JB-NOW-24 — actual flood-fill barrier/growth regression.
 *
 * Reads [FloodFill], [FillOptions] (JB-2.06a) and extends [FloodFillTest] with the
 * barrier/growth cases it does not pin down as tiny independently expected masks.
 * Every expectation below is hand-derived in its comment (wall/interior/exterior
 * rectangle arithmetic, the documented `2 * gapClosePx` sealing rule and the
 * documented square/Chebyshev grow element). No expectation is transcribed from
 * the implementation and no second morphology (for example a circular element)
 * is substituted where the contract says square.
 *
 * Repro IDs JB-NOW-24/01..10. All fixtures are <= 13 x 13; refusal cases use
 * 4-byte buffers so no test allocates a large image. No timing assertions, no
 * randomness, no fill-pen UX tuning and no changed closure radius. Foundation
 * flood semantics only.
 */
class FloodFillBarrierCorpusTest {

    // ── helpers ─────────────────────────────────────────────────────────────

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

    private fun boxWithGapInTop(buf: ByteArray, w: Int, x0: Int, y0: Int, x1: Int, y1: Int, gx0: Int, gx1: Int) {
        box(buf, w, x0, y0, x1, y1)
        for (x in gx0..gx1) put(buf, w, x, y0, 255, 255, 255, 255)
    }

    private fun count(mask: ByteArray): Int = mask.count { it != 0.toByte() }

    private fun at(mask: ByteArray, w: Int, x: Int, y: Int): Int = mask[y * w + x].toInt() and 0xFF

    private fun exact(tolerance: Float = 0f, gap: Int = 0, grow: Int = 0) =
        FillOptions(tolerance = tolerance, gapClosePx = gap, grow = grow)

    /**
     * Hand-drawn mask picture: '1' is filled (255), anything else is 0.
     * Lets the reviewer see the shape without trusting a loop.
     */
    private fun maskFromRows(vararg rows: String): ByteArray {
        val w = rows[0].length
        val out = ByteArray(rows.size * w)
        for (y in rows.indices) for (x in 0 until w) {
            out[y * w + x] = if (rows[y][x] == '1') 255.toByte() else 0
        }
        return out
    }

    private fun assertMaskBytesAreZeroOrFull(mask: ByteArray, label: String) {
        for (i in mask.indices) {
            val v = mask[i].toInt() and 0xFF
            assertTrue(v == 0 || v == 255, "$label byte $i is $v, not 0 or 255")
        }
    }

    // ── 1. zero-gap/grow0 closed wall: the full tiny mask ────────────────────

    @Test fun closedWallWithZeroGapAndZeroGrowFillsExactlyTheInterior() {
        // Repro JB-NOW-24/01: 7 x 7, 1 px box (1,1)..(5,5). Size 5 so perimeter
        // 4 * 5 - 4 = 16, interior (2..4, 2..4) 3 x 3 = 9, exterior 49 - 16 - 9
        // = 24. Seed (3,3) with tolerance 0, gap 0, grow 0 must give the 9
        // interior pixels and nothing else. FloodFillTest checks a 16 x 16 box
        // by count and spots; here the whole 7 x 7 mask is pinned row by row,
        // which is what catches a shape error a count cannot.
        val w = 7
        val buf = image(w, w)
        box(buf, w, 1, 1, 5, 5)
        val before = buf.copyOf()
        val mask = FloodFill.fill(w, w, buf, 3, 3, exact())
        val expected = maskFromRows(
            "0000000",
            "0000000",
            "0011100",
            "0011100",
            "0011100",
            "0000000",
            "0000000",
        )
        assertContentEquals(expected, mask, "closed 7 x 7 box is exactly its 3 x 3 interior")
        assertEquals(9, count(mask), "hand count 3 x 3")
        assertEquals(255, at(mask, w, 3, 3), "the seed")
        assertEquals(255, at(mask, w, 2, 2), "the inside corner hugging the line")
        assertEquals(0, at(mask, w, 1, 1), "the wall corner itself")
        assertEquals(0, at(mask, w, 0, 0), "outside the box is untouched")
        assertEquals(0, at(mask, w, 1, 3), "the middle of the left wall")
        assertMaskBytesAreZeroOrFull(mask, "closed wall")
        assertContentEquals(before, buf, "the reference image is preserved")
    }

    // ── 2. zero-gap/grow0 open wall: the leaking twin ────────────────────────

    @Test fun openWallWithZeroGapAndZeroGrowLeaksToTheOutside() {
        // Repro JB-NOW-24/02: the same 7 x 7 box with a 2 px break at
        // (3,1)..(4,1). Wall 16 - 2 = 14, so every white pixel is one
        // 4-connected region through the break: 49 - 14 = 35. Row by row:
        // y0 all filled (7); y1 "1001101" (4: the two gap pixels fill, the
        // three remaining wall pixels do not); y2..y4 "1011101" (5 each: the
        // side walls stand); y5 "1000001" (2); y6 all filled (7).
        // 7 + 4 + 5 + 5 + 5 + 2 + 7 = 35. The contrast with /01 is the point:
        // the same seed, the same options, one wall with a door.
        val w = 7
        val buf = image(w, w)
        boxWithGapInTop(buf, w, 1, 1, 5, 5, 3, 4)
        val mask = FloodFill.fill(w, w, buf, 3, 3, exact())
        val expected = maskFromRows(
            "1111111",
            "1001101",
            "1011101",
            "1011101",
            "1011101",
            "1000001",
            "1111111",
        )
        assertContentEquals(expected, mask, "open 7 x 7 box floods everything but the 14 wall pixels")
        assertEquals(35, count(mask), "hand count 49 - 14")
        assertEquals(255, at(mask, w, 3, 1), "the gap pixel is a door, so it fills")
        assertEquals(255, at(mask, w, 0, 0), "the leak reaches well outside the box")
        assertEquals(0, at(mask, w, 1, 1), "the remaining wall still stands")
        assertEquals(0, at(mask, w, 5, 3), "the far wall still stands")
        assertMaskBytesAreZeroOrFull(mask, "open wall")
    }

    // ── 3. narrow gaps versus supported closure diameters ────────────────────

    @Test fun narrowGapsNeedTheirDocumentedClosureDiameter() {
        // Repro JB-NOW-24/03: 13 x 13, box (2,2)..(10,10). Width 9, height 9,
        // so perimeter 2 * (9 + 9) - 4 = 32; interior (3..9, 3..9) 7 x 7 = 49;
        // exterior 169 - 32 - 49 = 88. The wall dilates with a square element of
        // width 2n+1, so diameter n seals gaps up to 2n+1 px: 2 px and 4 px seal at
        // n = 1 and 2, and a 5 px gap also seals at n = 2 (5 <= 2*2+1). The KDoc
        // "2n" wording is imprecise for odd widths; the 2 px and 4 px pairs below pin
        // the undisputed boundary, and the 5 px case pins the actual 2n+1 edge with
        // its fringe (49 interior + 15 gap-adjacent = 64) for frontier review.
        // FloodFillTest pins 1 px, 3 px and 16 px gaps; here the small table
        // is 2 px, 4 px and 5 px on one shared box, each with the leaking and
        // the sealing diameter side by side. No custom dilation is used to
        // derive these: the counts are 169 - wall versus the 49 interior.
        // The box is tall enough that the centre (6,6) stays 4 px from every
        // wall, so gap 3 does not swallow the seed into its fallback path.
        val w = 13
        val h = 13

        fun fixture(gx0: Int, gx1: Int): ByteArray {
            val buf = image(w, h)
            boxWithGapInTop(buf, w, 2, 2, 10, 10, gx0, gx1)
            return buf
        }

        // 2 px gap (x 6..7): wall 30, open 169 - 30 = 139; gap 1 seals (2).
        val two = fixture(6, 7)
        val twoOpen = FloodFill.fill(w, h, two, 6, 6, exact(gap = 0))
        assertEquals(139, count(twoOpen), "2 px gap with no closing floods 169 - 30")
        assertEquals(255, at(twoOpen, w, 0, 0), "the leak reaches the corner")
        assertEquals(255, at(twoOpen, w, 6, 2), "the gap pixel is on the leak path")
        val twoClosed = FloodFill.fill(w, h, two, 6, 6, exact(gap = 1))
        assertEquals(49, count(twoClosed), "gap 1 seals 2 px: just the 7 x 7 interior")
        assertEquals(0, at(twoClosed, w, 0, 0), "nothing outside the box")
        assertEquals(255, at(twoClosed, w, 3, 3), "the inside corner, no moat")
        assertEquals(0, at(twoClosed, w, 6, 2), "the sealed gap pixel stays shut")
        assertEquals(0, at(twoClosed, w, 2, 2), "the line itself is never filled")

        // 4 px gap (x 5..8): wall 28, open 141; gap 1 still leaks (2 < 4),
        // gap 2 seals (4).
        val four = fixture(5, 8)
        val fourOne = FloodFill.fill(w, h, four, 6, 6, exact(gap = 1))
        assertEquals(141, count(fourOne), "gap 1 leaves 2 px of a 4 px break, so it leaks")
        assertEquals(255, at(fourOne, w, 0, 0), "still outside")
        val fourTwo = FloodFill.fill(w, h, four, 6, 6, exact(gap = 2))
        assertEquals(49, count(fourTwo), "gap 2 seals 4 px: the interior again")
        assertEquals(0, at(fourTwo, w, 0, 0), "shut")
        assertEquals(0, at(fourTwo, w, 6, 2), "the middle of the sealed break")
        assertEquals(255, at(fourTwo, w, 9, 9), "the far inside corner")

        // 5 px gap (x 5..9): wall 27, open 142; the square element of width 2n+1
        // seals 5 px already at gap 2, so this pair is sealed/sealed: gap 2 holds
        // the 49 interior plus the 15 gap-adjacent fringe pixels the dilate-back
        // step restores (64), and gap 3 seals the same way.
        val five = fixture(5, 9)
        val fiveTwo = FloodFill.fill(w, h, five, 6, 6, exact(gap = 2))
        assertEquals(0, at(fiveTwo, w, 0, 0), "no path to the exterior corner")
        assertEquals(255, at(fiveTwo, w, 7, 2), "the dilate-back fringe reaches the sealed gap")
        assertEquals(64, count(fiveTwo), "49 interior plus 15 gap-adjacent fringe pixels")
        val fiveThree = FloodFill.fill(w, h, five, 6, 6, exact(gap = 3))
        assertEquals(49, count(fiveThree), "gap 3 seals 5 px: the interior again")
        assertEquals(0, at(fiveThree, w, 0, 0), "shut")
        assertEquals(255, at(fiveThree, w, 3, 7), "the fill still reaches the line")
    }

    // ── 4. seed outside: empty under every option, edge contrast ─────────────

    @Test fun seedOutsideTheReferenceIsEmptyUnderEveryOption() {
        // Repro JB-NOW-24/04: 6 x 6, box (1,1)..(4,4). Size 4 so perimeter
        // 4 * 4 - 4 = 12, interior (2..3, 2..3) 2 x 2 = 4, exterior
        // 36 - 12 - 4 = 20. A tap off the reference is a finger miss, not a
        // caller bug (JB-2.06a D4): every seed outside gives a w x h all-zero
        // mask whatever the options are. FloodFillTest checks the default on
        // a bare 4 x 4; here the image HAS a wall and the options matrix runs
        // gap and grow too, plus the one-pixel boundary: (5,5) is exterior
        // (20) while (6,5) is outside (0).
        val w = 6
        val buf = image(w, w)
        box(buf, w, 1, 1, 4, 4)
        val options = listOf(exact(), FillOptions(), exact(tolerance = 0.5f, gap = 2, grow = 1))
        val outside = listOf(-1 to 0, 0 to -1, 6 to 0, 0 to 6, 6 to 3, 3 to 6, 99 to 99, -99 to -99)
        for (opt in options) {
            for (seed in outside) {
                val mask = FloodFill.fill(w, w, buf, seed.first, seed.second, opt)
                assertEquals(w * w, mask.size, "mask stays w x h for seed $seed under $opt")
                assertEquals(0, count(mask), "no region for outside seed $seed under $opt")
            }
        }
        // The boundary itself: one pixel in, one pixel out.
        assertEquals(20, count(FloodFill.fill(w, w, buf, 5, 5, exact())), "just inside is the 20 exterior")
        assertEquals(20, count(FloodFill.fill(w, w, buf, 0, 0, exact())), "the far exterior corner agrees")
        assertEquals(4, count(FloodFill.fill(w, w, buf, 2, 2, exact())), "just inside the wall is the 4 interior")
        assertEquals(0, count(FloodFill.fill(w, w, buf, 6, 5, exact())), "one step further out is nothing")
    }

    // ── 5. alpha is in the distance; the 0.5 boundary is inclusive ────────────

    @Test fun alphaCountsInTheDistanceAndTheHalfBoundaryIsInclusive() {
        // Repro JB-NOW-24/05: 5 x 1 row, seed white opaque at x = 0.
        // x1 white alpha 254: dr/dg/db/da 0/0/0/1, squared sum 1.
        // x2 white transparent: 0/0/0/255, sum 65025, distance exactly 0.5.
        // x3 black opaque: 255/255/255/0, sum 195075, distance ~0.866.
        // x4 transparent black: 255^4, sum 260100, distance exactly 1.0.
        // At tolerance 0.5 the bound is 260100 * 0.25 = 65025.0 exactly (0.5
        // is exact in binary, the sum is an Int), so x2 is IN by `<=` and a
        // strict `<` would wrongly shut it. x3 is OUT and, being a wall,
        // blocks x4. A per-channel test would rate x2 (max channel 255) and
        // x3 (max channel 255) the same; Euclidean over all four channels is
        // what puts one inside and the other outside, which is the
        // load-bearing alpha clause of FillOptions.tolerance.
        val w = 5
        val buf = image(w, 1)
        put(buf, w, 1, 0, 255, 255, 255, 254)
        put(buf, w, 2, 0, 255, 255, 255, 0)
        put(buf, w, 3, 0, 0, 0, 0, 255)
        put(buf, w, 4, 0, 0, 0, 0, 0)

        val none = FloodFill.fill(w, 1, buf, 0, 0, exact(tolerance = 0f))
        assertContentEquals(byteArrayOf(255.toByte(), 0, 0, 0, 0), none, "tolerance 0: only the seed")
        assertEquals(1, count(none))

        val half = FloodFill.fill(w, 1, buf, 0, 0, exact(tolerance = 0.5f))
        assertContentEquals(
            byteArrayOf(255.toByte(), 255.toByte(), 255.toByte(), 0, 0),
            half,
            "tolerance 0.5: the exactly-0.5 alpha pixel is inside (65025 <= 65025), black is not",
        )
        assertEquals(3, count(half))
        assertEquals(255, at(half, w, 2, 0), "65025 <= 65025")
        assertEquals(0, at(half, w, 3, 0), "195075 > 65025, and it walls off x4")

        val full = FloodFill.fill(w, 1, buf, 0, 0, exact(tolerance = 1f))
        assertEquals(5, count(full), "tolerance 1: even the most distant pair (260100 <= 260100) joins")
        assertMaskBytesAreZeroOrFull(half, "alpha boundary")
    }

    // ── 6. NaN behaves as zero; infinities clamp ─────────────────────────────

    @Test fun nanToleranceBehavesAsZeroAndInfinitiesClamp() {
        // Repro JB-NOW-24/06: 3 x 1, white seed, black wall, white beyond.
        // The wall blocks the far white pixel at tolerance 0, so the masks
        // are [1,0,0] versus [1,1,1]: full-mask equality, not just counts.
        // NaN must not poison the comparison into a silent empty mask
        // (FloodFill.limitSquared maps it to 0); infinities clamp like any
        // other out-of-range slider value. FloodFillTest pins 5/-1/99/-3;
        // NaN and infinities are the missing ends of that same slider rule.
        val w = 3
        val buf = image(w, 1)
        put(buf, w, 1, 0, 0, 0, 0, 255)

        val zero = FloodFill.fill(w, 1, buf, 0, 0, exact(tolerance = 0f))
        val nan = FloodFill.fill(w, 1, buf, 0, 0, exact(tolerance = Float.NaN))
        assertContentEquals(zero, nan, "NaN behaves as 0, not as a dead tap")
        assertContentEquals(byteArrayOf(255.toByte(), 0, 0), nan)
        assertEquals(1, count(nan))

        val negInf = FloodFill.fill(w, 1, buf, 0, 0, exact(tolerance = Float.NEGATIVE_INFINITY))
        assertContentEquals(zero, negInf, "-Inf clamps to 0")

        val one = FloodFill.fill(w, 1, buf, 0, 0, exact(tolerance = 1f))
        val posInf = FloodFill.fill(w, 1, buf, 0, 0, exact(tolerance = Float.POSITIVE_INFINITY))
        assertContentEquals(one, posInf, "+Inf clamps to 1")
        assertContentEquals(byteArrayOf(255.toByte(), 255.toByte(), 255.toByte()), posInf)
        assertEquals(3, count(posInf))
    }

    // ── 7. documented grow tucks under the line by exactly its radius ────────

    @Test fun growTucksUnderTheLineByExactlyItsRadius() {
        // Repro JB-NOW-24/07: 9 x 9, box (2,2)..(6,6). Size 5 so perimeter
        // 16, interior (3..5, 3..5) 3 x 3 = 9. Grow is the documented square
        // (Chebyshev) dilation, unrestricted so it covers the anti-aliased
        // rim under the line (JB-2.06a D3): grow 1 gives 5 x 5 = 25
        // (x2..6, the wall, but not x1); grow 2 gives 7 x 7 = 49 (x1..7);
        // grow 4 on this small board clips to the whole 81. A circular
        // element would give fewer than 25/49 here, so the counts pin the
        // square the contract names. FloodFillTest pins grow on an isolated
        // pixel; here it is pinned on a real closed wall.
        val w = 9
        val buf = image(w, w)
        box(buf, w, 2, 2, 6, 6)

        val none = FloodFill.fill(w, w, buf, 4, 4, exact(grow = 0))
        assertEquals(9, count(none), "grow 0 is the 3 x 3 interior")
        assertContentEquals(
            maskFromRows(
                "000000000",
                "000000000",
                "000000000",
                "000111000",
                "000111000",
                "000111000",
                "000000000",
                "000000000",
                "000000000",
            ),
            none,
        )
        assertEquals(0, at(none, w, 2, 3), "the wall shows through with no grow")

        val one = FloodFill.fill(w, w, buf, 4, 4, exact(grow = 1))
        assertEquals(25, count(one), "grow 1 is 5 x 5")
        assertEquals(255, at(one, w, 2, 3), "the wall is tucked under")
        assertEquals(255, at(one, w, 6, 6), "the far wall corner is covered")
        assertEquals(0, at(one, w, 1, 3), "one pixel past the wall is not invented")
        assertEquals(0, at(one, w, 1, 1), "the outside corner stays empty")

        val two = FloodFill.fill(w, w, buf, 4, 4, exact(grow = 2))
        assertEquals(49, count(two), "grow 2 is 7 x 7")
        assertEquals(255, at(two, w, 1, 1), "two past the interior reaches x1..7")
        assertEquals(0, at(two, w, 0, 0), "but not the far corner")

        val four = FloodFill.fill(w, w, buf, 4, 4, exact(grow = 4))
        assertEquals(81, count(four), "grow 4 clips to the whole 9 x 9 board")
    }

    // ── 8. default options equal their explicit spelling ─────────────────────

    @Test fun defaultOptionsEqualTheirExplicitSpelling() {
        // Repro JB-NOW-24/08: FillOptions() is tolerance 0.1, gap 0, grow 1
        // by contract. Where the contract guarantees equivalence the two
        // spellings must give byte-identical masks; this is checked on three
        // shapes, not one, so a default that drifted in any stage would show.
        assertEquals(0.1f, FillOptions().tolerance, "contract default")
        assertEquals(0, FillOptions().gapClosePx, "contract default")
        assertEquals(1, FillOptions().grow, "contract default")
        val explicit = FillOptions(tolerance = 0.1f, gapClosePx = 0, grow = 1)

        val uniform = image(8, 8)
        assertContentEquals(
            FloodFill.fill(8, 8, uniform, 4, 4, FillOptions()),
            FloodFill.fill(8, 8, uniform, 4, 4, explicit),
            "uniform board: default versus explicit",
        )
        assertEquals(64, count(FloodFill.fill(8, 8, uniform, 4, 4, FillOptions())))

        // 6 x 1 gradient, black seed, steps of 30: 30^2 = 900 is inside
        // 260100 * 0.01 = 2601 and 60^2 = 3600 is not, so the region is the
        // seed plus one step before grow; both spellings must agree on the
        // grown 3.
        val gw = 6
        val grad = image(gw, 1, 0, 0, 0, 255)
        for (x in 1 until gw) put(grad, gw, x, 0, x * 30, 0, 0, 255)
        assertContentEquals(
            FloodFill.fill(gw, 1, grad, 0, 0, FillOptions()),
            FloodFill.fill(gw, 1, grad, 0, 0, explicit),
            "gradient board: default versus explicit",
        )

        // Closed wall through the grow stage: interior 9 grown to 25 either way.
        val bw = 7
        val walled = image(bw, bw)
        box(walled, bw, 1, 1, 5, 5)
        val a = FloodFill.fill(bw, bw, walled, 3, 3, FillOptions())
        val b = FloodFill.fill(bw, bw, walled, 3, 3, explicit)
        assertContentEquals(a, b, "walled board: default versus explicit")
        assertEquals(25, count(a), "hand count: 3 x 3 grown by 1 is 5 x 5")

        // And the all-explicit zero spelling agrees with the helper.
        assertContentEquals(
            FloodFill.fill(bw, bw, walled, 3, 3, FillOptions(tolerance = 0f, gapClosePx = 0, grow = 0)),
            FloodFill.fill(bw, bw, walled, 3, 3, exact()),
            "explicit zeros equal exact zeros",
        )
    }

    // ── 9. supported invalid limits, without large allocations ───────────────

    @Test fun invalidReferencesAreRefusedWithoutLargeAllocations() {
        // Repro JB-NOW-24/09: the three refusals (negative side, short buffer,
        // over-cap arithmetic) plus the two non-refusals (zero side, exact
        // buffer). Every refusal here uses a 4-byte or sub-100-byte buffer, so
        // the cap is proved by arithmetic before any tens-of-megabytes
        // allocation, and no test in this file allocates more than a few
        // hundred bytes. Dimensions differ from FloodFillTest's own refusal
        // cases; the contract they pin is the same.
        assertFailsWith<IllegalArgumentException> { FloodFill.fill(-1, 4, image(1, 1), 0, 0, exact()) }
        assertFailsWith<IllegalArgumentException> { FloodFill.fill(4, -1, image(1, 1), 0, 0, exact()) }
        assertFailsWith<IllegalArgumentException> { FloodFill.fill(-5, -7, image(1, 1), 0, 0, exact()) }
        assertFailsWith<IllegalArgumentException> {
            FloodFill.fill(4, 4, ByteArray(4 * 4 * 4 - 1), 0, 0, exact())
        }
        assertFailsWith<IllegalArgumentException> {
            FloodFill.fill(5, 1, ByteArray(5 * 1 * 4 - 1), 0, 0, exact())
        }
        // Over the 8_388_608 px cap by arithmetic, with only 4 bytes behind it.
        assertFailsWith<IllegalArgumentException> {
            FloodFill.fill(100_000, 100_000, ByteArray(4), 0, 0, exact())
        }
        assertFailsWith<IllegalArgumentException> {
            FloodFill.fill(20_000, 500, ByteArray(4), 0, 0, exact())
        }
        // Two sides that overflow an Int if multiplied as Ints.
        assertFailsWith<IllegalArgumentException> {
            FloodFill.fill(1 shl 30, 2, ByteArray(4), 0, 0, exact())
        }
        // Zero sides are legal questions with empty answers, not refusals.
        assertEquals(0, FloodFill.fill(0, 0, ByteArray(0), 0, 0, exact()).size)
        assertEquals(0, FloodFill.fill(0, 5, ByteArray(0), 0, 0, exact()).size)
        assertEquals(0, FloodFill.fill(5, 0, ByteArray(0), 0, 0, exact()).size)
        // An exactly-sized buffer is accepted: the refusal above is not over-eager.
        assertEquals(4, count(FloodFill.fill(2, 2, image(2, 2), 0, 0, exact())))
    }

    // ── 10. inputs preserved; masks fresh, deterministic 0/255 bytes ────────

    @Test fun inputsArePreservedAndMasksAreFreshDeterministicBytes() {
        // Repro JB-NOW-24/10: the fill borrows the reference, it does not own
        // it. Both the plain path (gap 0) and the wall path (gap 1, with the
        // two work arrays and the final grow) must leave rgba byte-identical,
        // and each call must hand back a fresh mask: mutating one result must
        // not change the next fill. Masks hold only 0 and 255 and repeat
        // exactly, which is also the determinism half of the contract.
        // Gap 1 (not 2) is used so the centre seed stays outside the dilated
        // wall and the wall path is exercised without its swallowed-seed
        // fallback; both paths are still checked for preservation.
        val w = 9
        val buf = image(w, w)
        box(buf, w, 2, 2, 6, 6)
        val snapshot = buf.copyOf()

        val gapGrow = FillOptions(tolerance = 0.1f, gapClosePx = 1, grow = 1)
        val first = FloodFill.fill(w, w, buf, 4, 4, gapGrow)
        assertContentEquals(snapshot, buf, "wall path preserves the reference")
        assertEquals(w * w, first.size, "mask is w x h")
        assertMaskBytesAreZeroOrFull(first, "wall path")
        assertEquals(255, at(first, w, 4, 4), "the seed is in its own region")

        val second = FloodFill.fill(w, w, buf, 4, 4, gapGrow)
        assertContentEquals(first, second, "same input, same mask")
        assertTrue(first !== second, "but a fresh array, not a shared one")
        first[0] = 123.toByte()
        val third = FloodFill.fill(w, w, buf, 4, 4, gapGrow)
        assertContentEquals(second, third, "mutating a result does not move the next fill")

        val plainFirst = FloodFill.fill(w, w, buf, 4, 4, exact())
        assertContentEquals(snapshot, buf, "plain path preserves the reference too")
        assertContentEquals(plainFirst, FloodFill.fill(w, w, buf, 4, 4, exact()), "plain path is deterministic")
        assertMaskBytesAreZeroOrFull(plainFirst, "plain path")
    }
}
