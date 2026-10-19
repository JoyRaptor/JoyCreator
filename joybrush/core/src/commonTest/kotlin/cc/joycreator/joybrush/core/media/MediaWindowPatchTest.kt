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

    @Test fun aPenTravellingRightOrDownGetsTheRoomAhead() {
        val x = 1000.0; val y = 700.0
        val (tx, ty) = MediaWindowMath.placeFor(x, y)
        assertEquals(MediaWindowMath.placeFor(x, y), MediaWindowMath.placeForTravel(x, y, -30.0, -30.0), "left and up: as before")
        assertEquals(MediaWindowMath.placeFor(x, y), MediaWindowMath.placeForTravel(x, y, 1.0, 0.0), "still: as before")
        assertEquals(Pair(tx + 1, ty + 1), MediaWindowMath.placeForTravel(x, y, 30.0, 30.0))
        // The pen sits in the second tile: two tiles and a bit ahead, at least MARGIN behind (it is still held).
        val (nx, ny) = MediaWindowMath.placeForTravel(x, y, 30.0, 30.0)
        assertTrue(MediaWindowMath.holds(nx, ny, doubleArrayOf(x - 100, y - 100, x + 100, y + 100)))
        val ahead = (nx * 256 + MediaWindowMath.PX - MediaWindowMath.MARGIN) - x
        assertTrue(ahead >= 2 * 256 - MediaWindowMath.MARGIN, "room ahead $ahead")
    }

    @Test fun aFrameClipsOnlyWhenItsPaintLeavesTheWindowItself() {
        assertTrue(MediaWindowMath.within(0, 0, doubleArrayOf(0.0, 0.0, 1024.0, 1024.0)), "the whole window, edge to edge")
        assertTrue(!MediaWindowMath.within(0, 0, doubleArrayOf(-1.0, 10.0, 100.0, 100.0)), "a pixel past the left edge")
        assertTrue(!MediaWindowMath.within(1, 0, doubleArrayOf(300.0, 0.0, 1281.0, 20.0)), "a pixel past the right edge")
        assertTrue(MediaWindowMath.within(1, 0, doubleArrayOf(300.0, 0.0, 1280.0, 20.0)))
    }
}
