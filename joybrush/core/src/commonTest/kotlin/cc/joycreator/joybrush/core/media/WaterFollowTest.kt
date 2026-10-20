package cc.joycreator.joybrush.core.media

import cc.joycreator.joybrush.core.paint.Tiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** "Follow the water" (MediaWindowMath.waterPlacement): where the window goes while water runs with no pen down. */
class WaterFollowTest {
    private val px = MediaWindowMath.PX.toFloat()

    /** The wet tiles of a window at (0, 0): columns [cx], rows [ry]. */
    private fun wet(cx: IntRange, ry: IntRange) = ry.flatMap { y -> cx.map { x -> Tiles.key(x, y) } }

    @Test fun waterInsideTheWindowAwayFromItsEdgeLeavesItAlone() {
        assertNull(MediaWindowMath.waterPlacement(wet(1..2, 1..2), floatArrayOf(300f, 300f, 700f, 700f), 0, 0))
    }

    @Test fun aDripPressingOnTheBottomEdgeMovesTheWindowDownOneTile() {
        // A wash over the top two rows has run to the bottom edge: the rows below are empty, so the window comes down.
        val rect = floatArrayOf(100f, 100f, 900f, px)
        assertEquals(Pair(0, 1), MediaWindowMath.waterPlacement(wet(0..3, 0..1), rect, 0, 0))
    }

    @Test fun aWindowAlreadyHoldingAllTheWaterStaysPutEvenAtTheEdge() {
        // Four full rows of water: coming down would lose a row of wet tiles for a row of room, which is worth less.
        assertNull(MediaWindowMath.waterPlacement(wet(0..3, 0..3), floatArrayOf(0f, 0f, px, px), 0, 0))
    }

    @Test fun waterTheWindowLeftBehindPullsItBack() {
        // Wet tiles one row above the window while the running water is at its top edge: the window goes up to hold them.
        val above = wet(1..2, -1..-1)
        val inside = wet(1..2, 0..1)
        assertEquals(Pair(0, -1), MediaWindowMath.waterPlacement(above + inside, floatArrayOf(300f, 0f, 700f, 500f), 0, 0))
    }

    @Test fun frozenWaterFarAwayDoesNotPullTheWindowOffTheRunningWater() {
        val farAway = wet(20..23, 20..23)
        assertNull(MediaWindowMath.waterPlacement(farAway + wet(1..2, 1..2), floatArrayOf(300f, 300f, 700f, 700f), 0, 0))
    }

    @Test fun itMovesAtMostOneTileAtATime() {
        // Water pressing on the top-left corner of a window far from the wet tiles it was told of: one tile toward the room, no more.
        val p = MediaWindowMath.waterPlacement(wet(0..3, 0..0), floatArrayOf(0f, 0f, px, 200f), 5, 5)
        assertEquals(Pair(5, 4), p, "one tile, and along one axis when that holds as much")
    }

    @Test fun anEmptyRectangleIsNothingToFollow() {
        assertNull(MediaWindowMath.waterPlacement(wet(0..3, 0..3), floatArrayOf(10f, 10f, 10f, 10f), 0, 0))
    }
}
