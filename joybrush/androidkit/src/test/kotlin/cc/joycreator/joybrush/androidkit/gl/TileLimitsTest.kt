package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.core.doc.*
import cc.joycreator.joybrush.core.render.MAX_REGION_PX
import org.junit.Assert.*
import org.junit.Test

class TileLimitsTest {
    private val tooManyTiles = "This tile touches too many painting tiles on this phone"
    private val tooLarge = "This tile is too large to preview on this phone"

    @Test fun ordinaryTilesFit() {
        assertTrue(TileLimits.fits(RectPx(0, 0, 64, 64)))
        assertTrue(TileLimits.fits(RectPx(-300, -300, 1024, 1024)))
        assertTrue(TileLimits.fits(RectPx(0, 0, 2896, 2896)))
    }
    @Test fun theTileCountCapBitesOnLongStripsBecausePixelsBindFirstOnSquares() {
        // 256 painting tiles of 256 px in a row is exactly the cap; one more pixel needs a 257th.
        assertTrue(TileLimits.fits(RectPx(0, 0, TileLimits.MAX_TILES * 256, 1)))
        assertEquals(tooManyTiles, TileLimits.refusal(RectPx(0, 0, TileLimits.MAX_TILES * 256 + 1, 1)))
    }
    @Test fun positionMattersBecauseAStraddlingTileTouchesOneMoreColumn() {
        assertTrue(TileLimits.fits(RectPx(0, 0, 256 * 256, 1)))
        assertEquals(tooManyTiles, TileLimits.refusal(RectPx(1, 0, 256 * 256, 1)))
        assertTrue(TileLimits.fits(RectPx(-255, 0, 256 * 255, 1)))
    }
    @Test fun pixelBudgetIsTheProduct() {
        assertEquals(tooLarge, TileLimits.refusal(RectPx(0, 0, 1, (MAX_REGION_PX + 1).toInt())))
        assertEquals(tooLarge, TileLimits.refusal(RectPx(0, 0, 2897, 2897)))
        assertEquals(tooLarge, TileLimits.refusal(RectPx(0, 0, 4096, 4096)))
    }
    @Test fun emptyOrOverflowingRectsAreRefusedNotThrown() {
        assertNotNull(TileLimits.refusal(RectPx(0, 0, 0, 10)))
        assertNotNull(TileLimits.refusal(RectPx(Int.MAX_VALUE - 5, 0, 10, 10)))
    }
    @Test fun armingAndTheDragAskTheSameQuestion() {
        fun doc(r: RectPx) = JbDocument(id = "d", name = "D", boards = listOf(Board("t", "T", BoardKind.CANVAS, r)), layers = emptyList())
        for (r in listOf(RectPx(0, 0, 64, 64), RectPx(1, 0, 256 * 256, 1), RectPx(0, 0, 256 * 256, 1),
                RectPx(0, 0, 4096, 4096), RectPx(0, 0, 1, (MAX_REGION_PX + 1).toInt()))) {
            val armed = try { TilePainting.board(doc(r), "t"); true } catch (_: IllegalArgumentException) { false }
            assertEquals("$r", TileLimits.fits(r), armed)
        }
    }
}
