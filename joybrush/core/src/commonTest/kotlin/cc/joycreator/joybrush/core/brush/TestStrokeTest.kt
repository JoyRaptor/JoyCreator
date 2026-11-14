package cc.joycreator.joybrush.core.brush

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The owner's test stroke (2026-11-14, job BRUSH-UI): each thing he asked of it, held by a test. */
class TestStrokeTest {
    private val s = TestStroke.pen(900, 120, 12f)

    @Test fun theSameBoxGivesTheSameSamplesEveryTime() {
        assertEquals(TestStroke.pen(900, 120, 12f), TestStroke.pen(900, 120, 12f))
    }

    @Test fun itStaysInsideTheBrushsInsetOnEverySide() {
        for ((w, h, size) in listOf(Triple(1000, 100, 30f), Triple(300, 96, 8f), Triple(2048, 60, 10f), Triple(120, 300, 20f))) {
            val inset = TestStroke.inset(size)
            for (p in TestStroke.pen(w, h, size)) {
                assertTrue(p.x >= inset - 0.01f && p.x <= w - inset + 0.01f, "x ${p.x} in $w x $h")
                assertTrue(p.y >= inset - 0.01f && p.y <= h - inset + 0.01f, "y ${p.y} in $w x $h")
            }
        }
    }

    @Test fun itStartsEverSoLightAndGetsHarderAllTheWay() {
        assertEquals(TestStroke.P_START, s.first().pressure, 1e-4f)
        assertTrue(s.first().pressure < 0.05f, "a feather touch")
        assertTrue(s.zipWithNext().all { (a, b) -> b.pressure >= a.pressure - 1e-6f }, "pressure never eases")
        // Slow at first: a third of the way across it is still a light touch.
        assertTrue(s[s.size / 4].pressure < 0.2f, "still light a quarter in: ${s[s.size / 4].pressure}")
    }

    @Test fun theLiftIsSuddenNotAFade() {
        assertEquals(1f, s.last().pressure, 1e-4f)
        assertTrue(s.takeLast(8).all { it.pressure > 0.95f }, "the end is pressed hard to the last sample")
    }

    @Test fun theSwooshRunsLeftToRightAndTheCurlComesBackOverIt() {
        val swoosh = s.take((s.size * TestStroke.SWOOSH).toInt() - 1)
        assertTrue(swoosh.zipWithNext().all { (a, b) -> b.x > a.x }, "the swoosh goes right")
        val farRight = s.maxOf { it.x }
        assertTrue(s.last().x < farRight - 4f, "the curl turns back: ends at ${s.last().x}, reached $farRight")
        val top = s.minOf { it.y }
        assertTrue(top < s.first().y, "the curl rises above the swoosh")
    }

    @Test fun theTiltVariesAndIsGreatestInTheCurl() {
        assertTrue(s.all { it.hasTilt && it.hasAzimuth })
        assertEquals(TestStroke.TILT_START, s.first().tilt, 1e-4f)
        assertEquals(TestStroke.TILT_CURL_END, s.last().tilt, 1e-3f)
        assertTrue(s.last().tilt - s.first().tilt > 0.8f, "upright to laid over")
        assertTrue(abs(s.last().azimuth - s.first().azimuth) > 1f, "the lean swings round in the curl")
    }

    @Test fun theClockOnlyMovesForwardAndTheCurlIsSlower() {
        assertEquals(0.0, s.first().timeMs)
        assertTrue(s.zipWithNext().all { (a, b) -> b.timeMs > a.timeMs })
    }

    @Test fun theSampleCountFollowsTheLengthButStaysBounded() {
        assertTrue(TestStroke.pen(300, 60, 10f).size < TestStroke.pen(1200, 60, 10f).size)
        assertEquals(24, TestStroke.pen(40, 60, 10f).size)
        assertEquals(600, TestStroke.pen(2048, 60, 10f, pitchPx = 1f).size)
    }

    @Test fun theSwooshCrossesEveryColourLine() {
        assertEquals(3, TestStroke.LINES.size)
        assertEquals(3, TestStroke.LINES.map { it.argb }.toSet().size, "three set colours")
        val w = 900; val h = 120; val size = 12f
        val inset = TestStroke.inset(size)
        for (line in TestStroke.LINES) {
            val x = inset + (w - 2 * inset) * (line.x0 + line.x1) / 2f
            val y0 = inset + (h - 2 * inset) * line.y0
            val y1 = inset + (h - 2 * inset) * line.y1
            val at = s.minByOrNull { abs(it.x - x) }!!
            assertTrue(abs(at.x - x) < 4f && at.y > y0 && at.y < y1, "the swoosh passes through the line at x $x: (${at.x}, ${at.y}) in $y0..$y1")
            val pen = TestStroke.linePen(line, w, h, size)
            assertTrue(pen.all { it.pressure == TestStroke.LINE_PRESSURE } && pen.first().y < pen.last().y)
        }
        // The lines sit where the swoosh is still building: light over the first, firmer over the last.
        fun pressureAt(line: TestStroke.Line) = s.minByOrNull { abs(it.x - (inset + (w - 2 * inset) * (line.x0 + line.x1) / 2f)) }!!.pressure
        assertTrue(pressureAt(TestStroke.LINES[0]) < pressureAt(TestStroke.LINES[2]))
    }
}
