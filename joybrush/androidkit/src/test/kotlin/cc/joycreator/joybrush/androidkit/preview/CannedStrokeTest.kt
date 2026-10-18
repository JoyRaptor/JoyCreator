package cc.joycreator.joybrush.androidkit.preview

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The one stroke every brush preview is drawn with: repeatable, inside the picture, swelling, closed where it should be. */
class CannedStrokeTest {

    @Test fun theSameBoxGivesTheSameSamplesEveryTime() {
        assertEquals(CannedStroke.pen(900, 90, 20f), CannedStroke.pen(900, 90, 20f))
        assertEquals(CannedStroke.loop(900, 90), CannedStroke.loop(900, 90))
    }

    @Test fun theStrokeStaysInsideTheBrushsInsetOnEverySide() {
        val size = 30f
        val inset = CannedStroke.inset(size)
        for (s in CannedStroke.pen(1000, 100, size)) {
            assertTrue(s.x >= inset - 0.01f && s.x <= 1000 - inset + 0.01f, "x ${s.x}")
            assertTrue(s.y >= inset - 0.01f && s.y <= 100 - inset + 0.01f, "y ${s.y}")
        }
    }

    @Test fun theStrokeRunsLeftToRightAndTheClockOnlyMovesForward() {
        val s = CannedStroke.pen(800, 80, 10f)
        assertTrue(s.zipWithNext().all { (a, b) -> b.x > a.x && b.timeMs >= a.timeMs })
        assertEquals(0.0, s.first().timeMs)
        assertTrue(s.last().timeMs > 0.0)
    }

    @Test fun pressureSwellsFromLightToFullAndBack() {
        val s = CannedStroke.pen(800, 80, 10f)
        assertEquals(0.25f, s.first().pressure, 0.001f)
        assertEquals(0.25f, s.last().pressure, 0.001f)
        val peak = s.maxOf { it.pressure }
        assertEquals(1f, peak, 0.01f)
        assertTrue(abs(s.indexOfFirst { it.pressure == peak } - s.size / 2) <= 2, "the swell is mid-stroke")
    }

    @Test fun thePenIsTiltedALittleMoreAtTheSwellAndLeansUpAndLeft() {
        val s = CannedStroke.pen(800, 80, 10f)
        assertEquals(0.4f, s.first().tilt, 0.001f)
        assertEquals(0.85f, s.maxOf { it.tilt }, 0.01f)
        assertTrue(s.all { it.azimuth == CannedStroke.LEAN && it.hasTilt })
        assertTrue(CannedStroke.LEAN < 0f)
    }

    @Test fun theSampleCountFollowsTheLengthButStaysBounded() {
        assertTrue(CannedStroke.pen(300, 60, 10f).size < CannedStroke.pen(1200, 60, 10f).size)
        assertEquals(24, CannedStroke.pen(40, 60, 10f).size)
        assertEquals(600, CannedStroke.pen(2048, 60, 10f, pitchPx = 1f).size)
    }

    @Test fun aPitchOfFivePixelsSpacesTheSamplesAboutFivePixelsApartAlongX() {
        val s = CannedStroke.pen(1000, 8, 6f, pitchPx = 5f)
        val gap = (s.last().x - s.first().x) / (s.size - 1)
        assertEquals(5f, gap, 0.5f)
    }

    @Test fun aPictureNoTallerThanTheBrushStillGivesAStraightLine() {
        val s = CannedStroke.pen(500, 10, 40f)
        assertTrue(s.isNotEmpty() && s.all { it.y == 5f })
    }

    @Test fun theFillBlobIsClosedAndInsideThePictureAndWiderThanTall() {
        val loop = CannedStroke.loop(900, 90)
        assertEquals(loop.first().x, loop.last().x, 0.001f)
        assertEquals(loop.first().y, loop.last().y, 0.001f)
        assertTrue(loop.all { it.x in 0f..900f && it.y in 0f..90f })
        assertTrue(loop.maxOf { it.x } - loop.minOf { it.x } > loop.maxOf { it.y } - loop.minOf { it.y })
    }
}
