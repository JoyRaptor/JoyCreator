package cc.joycreator.joybrush.core.vector

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** JB-5.20e — [StrokePicker.rank] is [StrokePicker.pick]'s order, read without touching the cycle (one source of truth). */
class StrokePickerRankTest {

    private fun hline(id: String, y: Double, half: Double = 1.0) =
        InkLine(id, DoubleArray(11) { it * 10.0 }, DoubleArray(11) { y }, DoubleArray(11) { half })

    private val lines = listOf(hline("a", 50.0), hline("b", 53.0), hline("c", 56.0), hline("d", 200.0))

    @Test
    fun rankIsTheOrderRepeatedTapsVisit() {
        val order = StrokePicker().rank(lines, 40.0, 53.0, 1.0)
        val p = StrokePicker()
        val taps = (0 until order.size).map { p.pick(lines, 40.0, 53.0, it * 50.0, 1.0) }
        assertEquals(order, taps, "rank()[k] is the k-th quick tap")
        assertEquals(order[0], StrokePicker().pick(lines, 40.0, 53.0, 0.0, 1.0), "rank()[0] is the first pick")
    }

    @Test
    fun rankNeverDisturbsTheCycle() {
        val p = StrokePicker()
        val first = p.pick(lines, 40.0, 53.0, 0.0, 1.0)
        repeat(5) { p.rank(lines, 40.0, 53.0, 1.0) }
        val second = p.pick(lines, 40.0, 53.0, 100.0, 1.0)
        val order = p.rank(lines, 40.0, 53.0, 1.0)
        assertEquals(order[1], second, "the cycle advanced exactly once, as if rank had never been called")
        assertTrue(first != second)
    }

    @Test
    fun rankHonoursTheZoomAndTheSlop() {
        // At zoom 1 the 12 px slop reaches all three hatch lines from y 53; at zoom 8 it is 1.5 doc px.
        assertEquals(3, StrokePicker().rank(lines, 40.0, 53.0, 1.0).size)
        assertEquals(listOf("b"), StrokePicker().rank(lines, 40.0, 53.0, 8.0))
        assertEquals(emptyList(), StrokePicker().rank(lines, 40.0, 120.0, 1.0))
    }
}
