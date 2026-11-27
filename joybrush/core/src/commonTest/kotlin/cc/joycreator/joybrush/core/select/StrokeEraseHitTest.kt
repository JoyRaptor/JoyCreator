package cc.joycreator.joybrush.core.select

import cc.joycreator.joybrush.core.vector.InkLine
import kotlin.test.Test
import kotlin.test.assertEquals

/** The whole-line eraser's hit test: a disc dragged along a segment against lines that are ribbons. */
class StrokeEraseHitTest {

    private fun line(id: String, vararg xy: Double, half: Double = 1.0): InkLine {
        val xs = DoubleArray(xy.size / 2) { xy[it * 2] }
        val ys = DoubleArray(xy.size / 2) { xy[it * 2 + 1] }
        return InkLine(id, xs, ys, DoubleArray(xs.size) { half })
    }

    private val horizontal = line("h", 0.0, 50.0, 100.0, 50.0)
    private val far = line("far", 0.0, 300.0, 100.0, 300.0)
    private val dot = line("dot", 200.0, 200.0, half = 2.0)

    @Test fun aSwipeAcrossALineTouchesIt() {
        assertEquals(listOf(0), StrokeEraseHit.touched(listOf(horizontal, far), 50.0, 0.0, 50.0, 100.0, 4.0))
    }

    @Test fun aSwipeThatPassesBesideALineByMoreThanBothWidthsMissesIt() {
        // 10 px above the line; reach is eraser 4 + half-width 1 = 5.
        assertEquals(emptyList(), StrokeEraseHit.touched(listOf(horizontal), 0.0, 40.0, 100.0, 40.0, 4.0))
        // 4.5 px above: within reach.
        assertEquals(listOf(0), StrokeEraseHit.touched(listOf(horizontal), 0.0, 45.5, 100.0, 45.5, 4.0))
    }

    @Test fun aTapThatIsAPointStillErases() {
        assertEquals(listOf(0), StrokeEraseHit.touched(listOf(horizontal), 30.0, 52.0, 30.0, 52.0, 3.0))
    }

    @Test fun aSinglePointLineIsADotWithItsOwnWidth() {
        assertEquals(listOf(2), StrokeEraseHit.touched(listOf(horizontal, far, dot), 205.0, 200.0, 205.0, 200.0, 3.0))
        assertEquals(emptyList(), StrokeEraseHit.touched(listOf(dot), 210.0, 200.0, 210.0, 200.0, 3.0))
    }

    @Test fun aBadRadiusErasesOnlyWhatThePathLandsOn() {
        assertEquals(listOf(0), StrokeEraseHit.touched(listOf(horizontal), 50.0, 0.0, 50.0, 100.0, Double.NaN))
        assertEquals(emptyList(), StrokeEraseHit.touched(listOf(horizontal), 0.0, 45.0, 100.0, 45.0, -5.0))
    }

    @Test fun segmentsThatCrossAreZeroApart() {
        assertEquals(0.0, StrokeEraseHit.segmentDistance(0.0, 0.0, 10.0, 10.0, 0.0, 10.0, 10.0, 0.0))
        assertEquals(5.0, StrokeEraseHit.segmentDistance(0.0, 0.0, 10.0, 0.0, 15.0, 0.0, 20.0, 0.0))
    }
}
