package cc.joycreator.joybrush.core.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which parts of the water's rectangle a step draws ([WaterCull]). */
class WaterCullTest {
    // The Note 9's sheet: 624 x 1280 cells = 39 x 80 blocks of 16.
    private val bw = 39; private val bh = 80
    private val whole = intArrayOf(0, 0, 624, 1280)

    /** A measurement with water in the blocks [wet] and dampness in [damp] ((bx, by) pairs). */
    private fun measure(wet: List<Pair<Int, Int>>, damp: List<Pair<Int, Int>> = emptyList()): FloatArray {
        val a = FloatArray(bw * bh * 4)
        for ((x, y) in wet) a[(y * bw + x) * 4] = 0.2f
        for ((x, y) in damp) a[(y * bw + x) * 4 + 1] = 0.5f
        return a
    }
    private fun row(y: Int) = (0 until bw).map { it to y }
    private fun area(parts: List<IntArray>) = parts.sumOf { (it[2] - it[0]).toLong() * (it[3] - it[1]) }
    private fun covers(parts: List<IntArray>, cx: Int, cy: Int) = parts.any { cx >= it[0] && cx < it[2] && cy >= it[1] && cy < it[3] }

    @Test fun untilSomethingIsMeasuredEverythingIsDrawn() {
        val c = WaterCull(bw, bh)
        assertNull(c.parts(whole))
        c.wetted(0f, 0f, 100f, 100f)
        assertNull(c.parts(whole), "the brush alone is no measurement")
    }

    @Test fun aBlockLeavesOnlyAfterItWasFoundDryAFewTimesInARow() {
        val c = WaterCull(bw, bh)
        val band = row(20) + row(21)
        // The first measurements: everything is still drawn (it could have been wet a step ago).
        repeat(WaterCull.HOLD - 1) { assertEquals(WaterCull.RUNNING, c.reading(measure(band))); assertNull(c.parts(whole), "reading ${it + 1}") }
        c.reading(measure(band))
        val parts = assertNotNull(c.parts(whole))
        assertTrue(area(parts) < 624L * 1280 / 4, "one band of the page: a small part of it (${area(parts)} cells)")
        // The band and a block round it are inside; the far end of the page is not.
        assertTrue(covers(parts, 300, 20 * 16 + 8) && covers(parts, 300, 19 * 16 + 8) && covers(parts, 300, 22 * 16 + 8))
        assertTrue(!covers(parts, 300, 1200))
        // The band dries: it stays drawn for HOLD measurements more.
        repeat(WaterCull.HOLD - 1) { assertEquals(WaterCull.DRY, c.reading(measure(emptyList()))); assertTrue(covers(assertNotNull(c.parts(whole)), 300, 20 * 16 + 8)) }
        c.reading(measure(emptyList()))
        assertEquals(emptyList(), assertNotNull(c.parts(whole)).map { it.toList() }, "nothing left to draw")
    }

    @Test fun twoMarksFarApartAreTwoRectanglesNotThePageBetweenThem() {
        val c = WaterCull(bw, bh)
        val marks = row(10) + row(11) + row(60) + row(61)
        repeat(WaterCull.HOLD) { c.reading(measure(marks)) }
        val parts = assertNotNull(c.parts(whole))
        assertEquals(2, parts.size, parts.joinToString { it.toList().toString() })
        assertTrue(parts.all { it[0] == 0 && it[2] == 624 }, "each the page's width")
        assertTrue(!covers(parts, 300, 35 * 16), "the dry paper between them is left out")
        assertTrue(area(parts) * 3 < 624L * 1280, "under a third of the page")
    }

    @Test fun theBrushMakesItsBlocksLiveAtOnce() {
        val c = WaterCull(bw, bh)
        repeat(WaterCull.HOLD) { c.reading(measure(row(10))) }
        assertTrue(!covers(assertNotNull(c.parts(whole)), 100, 1000))
        c.wetted(90f, 990f, 130f, 1010f)
        assertTrue(covers(assertNotNull(c.parts(whole)), 100, 1000))
        assertTrue(covers(assertNotNull(c.parts(whole)), 100, 1000 - 20), "and the ring round them")
    }

    @Test fun dampnessKeepsABlockDrawnAndTellsTheSheetItIsNotDryYet() {
        val c = WaterCull(bw, bh)
        repeat(WaterCull.HOLD + 2) { assertEquals(WaterCull.DAMP, c.reading(measure(emptyList(), damp = row(40)))) }
        assertTrue(covers(assertNotNull(c.parts(whole)), 300, 40 * 16 + 8))
    }

    @Test fun whenNearlyAllOfItIsLiveTheWholeRectangleIsDrawnAsBefore() {
        val c = WaterCull(bw, bh)
        val most = (0 until bh - 8).flatMap { row(it) }
        repeat(WaterCull.HOLD) { c.reading(measure(most)) }
        assertNull(c.parts(whole), "leaving a tenth out is not worth the extra draws")
    }

    @Test fun thePartsStayInsideTheStepsOwnRectangleAndAResetDrawsEverythingAgain() {
        val c = WaterCull(bw, bh)
        repeat(WaterCull.HOLD) { c.reading(measure(row(10) + row(11))) }
        val parts = assertNotNull(c.parts(intArrayOf(100, 150, 500, 1280)))
        assertTrue(parts.isNotEmpty() && parts.all { it[0] >= 100 && it[2] <= 500 && it[1] >= 150 })
        c.reset()
        assertNull(c.parts(whole))
    }

    /** A measurement with water in the given blocks only. */
    private fun wetAt(c: WaterCull, vararg blocks: Pair<Int, Int>) {
        val a = FloatArray(c.bw * c.bh * 4)
        for ((bx, by) in blocks) a[(by * c.bw + bx) * 4] = 0.2f
        repeat(WaterCull.HOLD + 1) { c.reading(a) }
    }

    @Test fun theMaskIsTheLiveBlocksAndTheirRingAndNothingElse() {
        val c = WaterCull(20, 30)
        assertEquals(null, c.mask(), "nothing measured: everything is drawn")
        wetAt(c, 5 to 7, 0 to 0, 19 to 29)
        val m = c.mask(1)!!
        fun on(bx: Int, by: Int) = m[by * c.bw + bx].toInt() != 0
        var n = 0
        for (b in m) if (b.toInt() != 0) n++
        assertEquals(9 + 4 + 4, n, "a block in the open has a ring of eight; one in a corner, three")
        assertTrue(on(4, 6) && on(6, 8) && on(5, 7) && on(1, 1) && on(18, 28))
        assertFalse(on(7, 7) || on(5, 9) || on(2, 0))
        val wide = c.mask(3)!!
        var w = 0
        for (b in wide) if (b.toInt() != 0) w++
        assertEquals(49 + 16 + 16, w, "grown by three blocks each way, cut at the grid's edge")
    }

    @Test fun theMaskNeverReachesPastTheRectanglesTheSameStepDraws() {
        val c = WaterCull(40, 80)
        wetAt(c, 3 to 3, 4 to 3, 20 to 40, 21 to 41, 22 to 42, 39 to 79)
        val cells = intArrayOf(0, 0, 40 * 16, 80 * 16)
        val parts = c.parts(cells)!!
        val m = c.mask(1)!!
        for (by in 0 until c.bh) for (bx in 0 until c.bw) {
            if (m[by * c.bw + bx].toInt() == 0) continue
            val x = bx * 16; val y = by * 16
            assertTrue(parts.any { x >= it[0] && x + 16 <= it[2] && y >= it[1] && y + 16 <= it[3] }, "block $bx, $by is drawn by the mask and by no rectangle")
        }
    }
}
