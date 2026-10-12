package cc.joycreator.joybrush.core.select

import cc.joycreator.joybrush.core.shape.Pt
import cc.joycreator.joybrush.core.vector.InkLine
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * JB-5.20e D9 — which lines a loop takes. Every expected fraction is exact arithmetic on a 100 x 100 square loop
 * (0,0)-(100,100) and horizontal lines, so it is a ratio of lengths read off by eye.
 */
class LineLassoTest {

    private val square = listOf(Pt(0.0, 0.0), Pt(100.0, 0.0), Pt(100.0, 100.0), Pt(0.0, 100.0))

    /** A horizontal line from x0 to x1 at height y, in [n] equal segments. */
    private fun hline(id: String, x0: Double, x1: Double, y: Double, n: Int = 10) =
        InkLine(id, DoubleArray(n + 1) { x0 + (x1 - x0) * it / n }, DoubleArray(n + 1) { y }, DoubleArray(n + 1) { 1.0 })

    private fun near(a: Double, b: Double) = abs(a - b) < 1e-9

    @Test
    fun aLineFullyInsideIsTakenByBothReaches() {
        val l = hline("in", 10.0, 90.0, 50.0)
        assertTrue(near(1.0, LineLasso.insideFraction(l, square)))
        assertEquals(listOf("in"), LineLasso.take(listOf(l), square, LineLasso.Reach.WHOLE))
        assertEquals(listOf("in"), LineLasso.take(listOf(l), square, LineLasso.Reach.TOUCHING))
    }

    @Test
    fun ninetyFivePercentInsideIsStillWhole() {
        // 10..105 at y 50: 90 of 95 inside = 0.947..., which a loop clipping a tail by a hair should still mean.
        val l = hline("tail", 10.0, 105.0, 50.0)
        assertTrue(near(90.0 / 95.0, LineLasso.insideFraction(l, square)), "the fraction is exact, not sampled")
        assertEquals(listOf("tail"), LineLasso.take(listOf(l), square, LineLasso.Reach.WHOLE))
    }

    @Test
    fun halfInsideIsTouchingOnly() {
        // 50..150: exactly half. The crossing at x 100 falls inside a segment (segments are 10 long from 50), so the cut is
        // real arithmetic, not a vertex coincidence: use 7 segments so 100 lands mid-segment.
        val l = hline("half", 50.0, 150.0, 50.0, n = 7)
        assertTrue(near(0.5, LineLasso.insideFraction(l, square)), "got ${LineLasso.insideFraction(l, square)}")
        assertEquals(emptyList(), LineLasso.take(listOf(l), square, LineLasso.Reach.WHOLE))
        assertEquals(listOf("half"), LineLasso.take(listOf(l), square, LineLasso.Reach.TOUCHING))
    }

    @Test
    fun aLineOutsideIsNeitherAndALineThroughIsTouching() {
        val out = hline("out", 120.0, 200.0, 50.0)
        val through = hline("through", -50.0, 150.0, 50.0, n = 9)
        assertEquals(emptyList(), LineLasso.take(listOf(out), square, LineLasso.Reach.TOUCHING))
        assertTrue(near(0.5, LineLasso.insideFraction(through, square)), "100 of 200 inside")
        assertEquals(listOf("through"), LineLasso.take(listOf(out, through), square, LineLasso.Reach.TOUCHING))
    }

    @Test
    fun aFigureEightUsesNonZeroWindingLikeTheMask() {
        // Two lobes, (0..150) and (150..300), crossing at (150, 50): the eight winds them opposite ways round, which non-zero
        // counts as inside either way. The strip above the crossing is outside both. A line in each lobe is taken; one in the gap is not.
        val eight = listOf(Pt(0.0, 0.0), Pt(100.0, 0.0), Pt(200.0, 100.0), Pt(300.0, 100.0), Pt(300.0, 0.0), Pt(200.0, 0.0),
            Pt(100.0, 100.0), Pt(0.0, 100.0))
        val left = hline("left", 10.0, 40.0, 50.0)
        val right = hline("right", 260.0, 290.0, 50.0)
        val gap = hline("gap", 140.0, 160.0, 5.0)
        assertEquals(listOf("left", "right"), LineLasso.take(listOf(left, gap, right), eight, LineLasso.Reach.WHOLE))
        // And it agrees with the pixel lasso's rule on the same polygon, at the line's middle.
        val mask = SelectionMask.polygon(eight)
        assertTrue(mask.coverage(25, 50) > 0 && mask.coverage(275, 50) > 0 && mask.coverage(150, 5) == 0)
    }

    @Test
    fun aDotIsAllOrNothingAndGarbageTakesNothing() {
        val dot = InkLine("dot", doubleArrayOf(50.0), doubleArrayOf(50.0), doubleArrayOf(2.0))
        assertTrue(near(1.0, LineLasso.insideFraction(dot, square)))
        val far = InkLine("far", doubleArrayOf(500.0), doubleArrayOf(50.0), doubleArrayOf(2.0))
        assertTrue(near(0.0, LineLasso.insideFraction(far, square)))
        assertEquals(emptyList(), LineLasso.take(listOf(dot), square.take(2), LineLasso.Reach.TOUCHING), "two points are not a loop")
        assertEquals(emptyList(), LineLasso.take(listOf(dot), square + Pt(Double.NaN, 0.0), LineLasso.Reach.TOUCHING))
    }
}
