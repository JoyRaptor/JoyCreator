package cc.joycreator.joybrush.core.media

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The window patch (2026-10-19): a frame part of at most SPAN is always held where the window centres on it. */
class MediaWindowPatchTest {
    @Test fun anyPartUpToTheSpanIsHeldByTheWindowCentredOnIt() {
        val r = Random(7)
        repeat(20000) {
            val w = r.nextDouble(1.0, MediaWindowMath.SPAN.toDouble()); val h = r.nextDouble(1.0, MediaWindowMath.SPAN.toDouble())
            val x = r.nextDouble(-5000.0, 5000.0); val y = r.nextDouble(-5000.0, 5000.0)
            val rect = doubleArrayOf(x, y, x + w, y + h)
            val (tx, ty) = MediaWindowMath.placeForRect(rect)
            assertTrue(MediaWindowMath.holds(tx, ty, rect), "${rect.toList()} at $tx,$ty")
        }
        assertEquals(512, MediaWindowMath.SPAN, "4 tiles less the two margins and one tile of rounding")
    }

    @Test fun aFrameClipsOnlyWhenItsPaintLeavesTheWindowItself() {
        assertTrue(MediaWindowMath.within(0, 0, doubleArrayOf(0.0, 0.0, 1024.0, 1024.0)), "the whole window, edge to edge")
        assertTrue(!MediaWindowMath.within(0, 0, doubleArrayOf(-1.0, 10.0, 100.0, 100.0)), "a pixel past the left edge")
        assertTrue(!MediaWindowMath.within(1, 0, doubleArrayOf(300.0, 0.0, 1281.0, 20.0)), "a pixel past the right edge")
        assertTrue(MediaWindowMath.within(1, 0, doubleArrayOf(300.0, 0.0, 1280.0, 20.0)))
    }
}
