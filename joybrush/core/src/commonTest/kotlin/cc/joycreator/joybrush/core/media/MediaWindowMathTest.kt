package cc.joycreator.joybrush.core.media

import cc.joycreator.joybrush.core.paint.Tiles
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The media window's placement and the running-water spread cap (M5.3c; the Lead's test owed for 3a). */
class MediaWindowMathTest {
    @Test fun theWindowIsCentredOnThePenAndTileAligned() {
        assertEquals(Pair(-2, -2), MediaWindowMath.placeFor(10.0, 10.0))
        assertEquals(Pair(1, -3), MediaWindowMath.placeFor(3 * 256.0 + 5, -1.0))
        val (tx, ty) = MediaWindowMath.placeFor(1000.0, 1000.0)
        assertTrue(MediaWindowMath.holds(tx, ty, doubleArrayOf(990.0, 990.0, 1010.0, 1010.0)))
        assertFalse(MediaWindowMath.holds(tx, ty, doubleArrayOf(990.0, 990.0, 1010.0 + 600, 1010.0)), "far along the stroke the window must move")
    }

    @Test fun keysAreTheTilesARectTouchesInsideTheWindow() {
        assertEquals(listOf(Tiles.key(5, 7)), MediaWindowMath.keysIn(5, 7, floatArrayOf(0f, 0f, 10f, 10f)))
        assertEquals(listOf(Tiles.key(5, 7), Tiles.key(6, 7)), MediaWindowMath.keysIn(5, 7, floatArrayOf(250f, 0f, 260f, 256f)))
        assertEquals(16, MediaWindowMath.allKeys(0, 0).size)
        assertEquals(16, MediaWindowMath.keysIn(0, 0, floatArrayOf(-50f, -50f, 5000f, 5000f)).size, "clamped to the window")
        assertEquals(emptyList(), MediaWindowMath.keysIn(0, 0, null))
    }

    @Test fun flatPaperNeverGrowsTheWater() {
        val r = floatArrayOf(100f, 100f, 200f, 200f)
        assertContentEquals(r, WetSpread.grow(r, r, 0f, 0f, 4, 0.05, 1024, 1024))
    }

    @Test fun waterOnATiltedPageStopsFourCentimetresDownhillAndOneSideways() {
        val paint = floatArrayOf(1000f, 1000f, 1100f, 1100f)
        var r = paint.copyOf()
        repeat(5000) { r = WetSpread.grow(r, paint, 0f, 0.5f, 4, 0.05, 100_000, 100_000) }   // 20 px/mm, a long wait
        assertEquals(1100f + 800f, r[3], "40 mm downhill and no further")
        assertEquals(1000f - 200f, r[1], "10 mm uphill, the sideways reach")
        assertEquals(1000f - 200f, r[0]); assertEquals(1100f + 200f, r[2])
    }

    @Test fun andNeverPastTheWindow() {
        val paint = floatArrayOf(400f, 400f, 500f, 500f)
        var r = paint.copyOf()
        repeat(5000) { r = WetSpread.grow(r, paint, 0f, 1f, 4, 0.05, MediaWindowMath.PX, MediaWindowMath.PX) }
        assertEquals(MediaWindowMath.PX.toFloat(), r[3])
    }

    @Test fun itGrowsOnlyAsFastAsWaterRuns() {
        val paint = floatArrayOf(1000f, 1000f, 1100f, 1100f)
        val r = WetSpread.grow(paint, paint, 0f, 1f, 4, 0.05, 100_000, 100_000)
        assertEquals(1100f + 4f, r[3], "ceil(4 × 0.45) + 2 px a frame")
    }
}
