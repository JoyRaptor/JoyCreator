package cc.joycreator.joybrush.core.brush

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The owner's test stroke, traced from his drawing of 2026-11-14: each thing he asked of it, held by a test. */
class TestStrokeTest {
    private val w = 1150
    private val h = 280
    private val size = 12f
    private val s = TestStroke.pen(w, h, size)
    private val inset = TestStroke.inset(size)

    @Test fun theSameBoxGivesTheSameSamplesEveryTime() {
        assertEquals(TestStroke.pen(900, 120, 12f), TestStroke.pen(900, 120, 12f))
        assertEquals(TestStroke.marks(900, 120, 12f), TestStroke.marks(900, 120, 12f))
    }

    @Test fun itStaysInsideTheBrushsInsetOnEverySide() {
        for ((bw, bh, sz) in listOf(Triple(1000, 100, 30f), Triple(300, 96, 8f), Triple(2048, 60, 10f), Triple(120, 300, 20f), Triple(500, 10, 40f))) {
            val i = TestStroke.inset(sz)
            for (p in TestStroke.pen(bw, bh, sz) + TestStroke.marks(bw, bh, sz).flatten()) {
                assertTrue(p.x >= i - 0.01f && p.x <= bw - i + 0.01f, "x ${p.x} in $bw x $bh")
                assertTrue(p.y >= minOf(i, bh / 2f) - 0.01f && p.y <= bh - minOf(i, bh / 2f) + 0.01f, "y ${p.y} in $bw x $bh")
            }
        }
    }

    @Test fun itStartsAsAHairlineAtTheLowerLeft() {
        assertTrue(s.first().pressure <= 0.03f, "as light as possible")
        assertTrue(s.first().x < inset + 0.05f * (w - 2 * inset), "starts at the left")
        assertTrue(s.first().y > inset + 0.7f * (h - 2 * inset), "starts low")
        assertTrue(s.take(s.size / 8).all { it.pressure < 0.1f }, "the start stays a hairline for a while")
    }

    @Test fun maximumAndMinimumOfPressureAndTiltAllInOneLine() {
        assertTrue(s.minOf { it.pressure } < 0.05f && s.maxOf { it.pressure } > 0.97f)
        assertTrue(s.minOf { it.tilt } < 0.15f && s.maxOf { it.tilt } > 1.05f)
        assertTrue(abs(s.last().azimuth - s.first().azimuth) > 1f, "the lean swings round with the hand")
    }

    @Test fun theLiftIsQuickNotALongFade() {
        // The end flicks off over the last few samples; until just before it the pen is pressed.
        val n = s.size
        assertTrue(s[n - n / 12].pressure > 0.5f, "still pressed just before the end: ${s[n - n / 12].pressure}")
        assertTrue(s.last().pressure < 0.2f, "and lifted at the end")
    }

    @Test fun theCurveCrossesItself() {
        fun cross(a: Int, b: Int, c: Int, d: Int): Boolean {
            fun o(p: Int, q: Int, r: Int) = (s[q].x - s[p].x) * (s[r].y - s[p].y) - (s[q].y - s[p].y) * (s[r].x - s[p].x)
            return o(a, b, c) * o(a, b, d) < 0 && o(c, d, a) * o(c, d, b) < 0
        }
        var crossings = 0
        val step = 3
        var i = 0
        while (i + step < s.size) {
            var j = i + 2 * step
            while (j + step < s.size) { if (cross(i, i + step, j, j + step)) crossings++; j += step }
            i += step
        }
        assertTrue(crossings >= 1, "the loop crosses over itself")
    }

    @Test fun ithasConcaveAndConvexTurns() {
        var left = 0; var right = 0
        for (i in 4 until s.size - 4 step 4) {
            val ax = s[i].x - s[i - 4].x; val ay = s[i].y - s[i - 4].y
            val bx = s[i + 4].x - s[i].x; val by = s[i + 4].y - s[i].y
            val c = ax * by - ay * bx
            if (hypot(ax, ay) > 1f && hypot(bx, by) > 1f) { if (c > 0) left++ else if (c < 0) right++ }
        }
        assertTrue(left > 5 && right > 5, "turns both ways: $left / $right")
    }

    @Test fun theCurvePassesThroughEveryStripe() {
        assertEquals(3, TestStroke.STRIPES.size)
        assertEquals(3, TestStroke.STRIPES.map { it.argb }.toSet().size)
        for (st in TestStroke.STRIPES) {
            val x0 = inset + (w - 2 * inset) * (st.x - st.half)
            val x1 = inset + (w - 2 * inset) * (st.x + st.half)
            assertTrue(s.count { it.x in x0..x1 } > 3, "the curve passes through the stripe at ${st.x}")
        }
    }

    @Test fun theMarksAreAtOneAngleAndGetLighter() {
        val m = TestStroke.marks(w, h, size)
        assertEquals(4, m.size)
        assertTrue(m.all { it.size >= 8 })
        val lines = m.drop(1)
        val slopes = lines.map { (it.last().y - it.first().y) / (it.last().x - it.first().x) }
        assertTrue(slopes.all { it < 0f }, "up and to the right")
        assertTrue(lines.zipWithNext().all { (a, b) -> b.first().pressure < a.first().pressure && b.first().tilt < a.first().tilt })
    }

    @Test fun theSampleCountFollowsTheLengthButStaysBounded() {
        assertTrue(TestStroke.pen(300, 80, 10f).size < TestStroke.pen(1200, 280, 10f).size)
        assertEquals(48, TestStroke.pen(40, 20, 10f).size)
        assertEquals(900, TestStroke.pen(4000, 900, 10f, pitchPx = 1f).size)
        assertEquals(0.0, s.first().timeMs)
        assertTrue(s.zipWithNext().all { (a, b) -> b.timeMs >= a.timeMs })
    }
}
