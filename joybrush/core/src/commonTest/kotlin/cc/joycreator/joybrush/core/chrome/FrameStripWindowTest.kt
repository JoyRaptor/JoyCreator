package cc.joycreator.joybrush.core.chrome

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** JB-ANIM-03: thumbnails are asked for only where the screen is, turned canvas included. */
class FrameStripWindowTest {
    private val screen = BoardChromeLayout.Rect(100f, 100f, 600f, 1100f)

    @Test fun unturnedItIsTheScreenPlusThePrefetch() {
        val w = FrameStripWindow.visible(screen, 100f, 100f, 0f, prefetch = 10f)
        assertEquals(90f, w.left, 0.01f); assertEquals(610f, w.right, 0.01f)
        assertEquals(90f, w.top, 0.01f); assertEquals(1110f, w.bottom, 0.01f)
    }

    @Test fun aQuarterTurnSwapsTheScreensSidesAroundThePivot() {
        // Turned a quarter clockwise around the screen's top left: the view's own frame shows the
        // screen's height across (x) and its width up the other way.
        val w = FrameStripWindow.visible(screen, 100f, 100f, (PI / 2).toFloat(), prefetch = 0f)
        assertEquals(100f, w.left, 0.5f); assertEquals(1100f, w.right, 0.5f)
        assertEquals(-400f, w.top, 0.5f); assertEquals(100f, w.bottom, 0.5f)
    }

    @Test fun atAnAngleItIsNeverWiderThanTheDiagonal() {
        val w = FrameStripWindow.visible(screen, 350f, 600f, (PI / 4).toFloat(), prefetch = 0f)
        val diagonal = kotlin.math.hypot(500f, 1000f)
        assertTrue(w.right - w.left <= diagonal + 1f)
        assertTrue(w.left < 350f && w.right > 350f, "the pivot stays inside")
    }
}
