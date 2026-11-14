package cc.joycreator.joybrush.androidkit.preview

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

    // The stroke's shape is the owner's test stroke (2026-11-14); core TestStrokeTest holds each thing he asked of it.
    @Test fun thePreviewDrawsTheCoresTestStroke() {
        assertEquals(cc.joycreator.joybrush.core.brush.TestStroke.pen(900, 90, 20f), CannedStroke.pen(900, 90, 20f))
        assertEquals(cc.joycreator.joybrush.core.brush.TestStroke.pen(300, 60, 8f, pitchPx = 5f), CannedStroke.pen(300, 60, 8f, pitchPx = 5f))
        assertEquals(cc.joycreator.joybrush.core.brush.TestStroke.LEAN, CannedStroke.LEAN)
        val s = CannedStroke.pen(800, 80, 10f)
        assertTrue(s.first().pressure < 0.05f && s.last().pressure == 1f, "a feather touch to a sudden lift")
        assertTrue(s.all { it.hasTilt } && CannedStroke.LEAN < 0f)
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
