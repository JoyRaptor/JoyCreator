package cc.joycreator.joybrush.core.media

import cc.joycreator.joybrush.core.paint.Tiles
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

    @Test fun theTilesLeftBehindAreTheOldWindowsOnesTheNewOneDoesNotHold() {
        val old = MediaWindowMath.allKeys(0, 0)
        val going = MediaWindowMath.leaving(old, 1, 0)
        assertEquals((0..3).map { Tiles.key(0, it) }, going, "moving one tile right leaves the first column")
        assertTrue(MediaWindowMath.leaving(old, 0, 0).isEmpty())
        assertEquals(old.size, MediaWindowMath.leaving(old, 9, 9).size)
    }
}
