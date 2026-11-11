package cc.joycreator.joybrush.core.media

import kotlin.test.Test
import kotlin.test.assertEquals
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
}
