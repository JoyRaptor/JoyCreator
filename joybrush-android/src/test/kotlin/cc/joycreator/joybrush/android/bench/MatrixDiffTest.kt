package cc.joycreator.joybrush.android.bench

import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.paint.Tiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MatrixDiffTest {
    private val bytes = Tiles.SIZE * Tiles.SIZE * 4

    private fun tile(vararg painted: Pair<Int, Int>): ByteArray = ByteArray(bytes).also { t ->
        for ((x, y) in painted) { val o = (y * Tiles.SIZE + x) * 4; t[o] = 9; t[o + 3] = 9 }
    }

    @Test fun aMissingTileIsABlankTile() {
        val key = Tiles.key(0, 0)
        assertEquals(0, MatrixDiff.count(mapOf(key to tile()), emptyMap()).px)
        assertTrue(MatrixDiff.same(mapOf(key to tile()), emptyMap()))
        val c = MatrixDiff.count(emptyMap(), mapOf(key to tile(1 to 1, 2 to 1)))
        assertEquals(2, c.px); assertEquals(1, c.tiles)
    }

    @Test fun anyOneChannelCountsAsAChangedPixel() {
        val a = tile(); val b = tile(); b[3 * 4 + 2] = 1
        assertEquals(1, MatrixDiff.count(mapOf(0L to a), mapOf(0L to b)).px)
    }

    @Test fun tilesAreLocatedInDocumentPixelsIncludingNegativeOnes() {
        val key = Tiles.key(-1, 0)   // document x -256..-1
        val c = MatrixDiff.count(emptyMap(), mapOf(key to tile(255 to 3, 0 to 3)), RectPx(-10, 0, 20, 10), strip = 5)
        assertEquals(2, c.px)
        assertEquals(1, c.insidePx)    // x = -1 is in the zone, x = -256 is not
        assertEquals(1, c.outsidePx)
        assertEquals(0, c.stripPx)     // the strip is the zone's first 5 columns (-10..-6); -1 is not in it
        val c2 = MatrixDiff.count(emptyMap(), mapOf(key to tile(250 to 3)), RectPx(-10, 0, 20, 10), strip = 5)
        assertEquals(1, c2.stripPx)    // x = -6
    }

    @Test fun differentTilesAreNotTheSamePicture() {
        assertFalse(MatrixDiff.same(mapOf(0L to tile(1 to 1)), mapOf(0L to tile(2 to 1))))
        assertTrue(MatrixDiff.same(mapOf(0L to tile(1 to 1)), mapOf(0L to tile(1 to 1))))
    }

    @Test fun theVerdictNamesEveryFailureAndOnlyWordsMakeARefusalValid() {
        fun v(error: String? = null, refused: Boolean = false, px: Int = 10, steps: Int = 1, exact: Boolean? = true,
              board: String = "canvas", wrap: Int = 0, frame: Int = 0, shared: Int = 0) =
            MatrixDiff.verdict(error, refused, px, steps, exact, board, wrap, frame, shared)
        assertEquals("works", v())
        assertEquals("error", v(error = "boom"))
        assertEquals("refused", v(refused = true, px = 0, steps = 0, exact = null))
        assertEquals("noop", v(px = 0, steps = 0, exact = null))
        assertEquals("undo-missing", v(steps = 0, exact = null))
        assertEquals("undo-bad", v(exact = false))
        assertEquals("nowrap", v(board = "tile", wrap = 0))
        assertEquals("works", v(board = "tile", wrap = 4))
        assertEquals("scope", v(board = "animation", frame = 5, shared = 3))
        assertEquals("scope", v(board = "animation", frame = 0, shared = 0))
        assertEquals("works", v(board = "animation", frame = 5, shared = 0))
        // A notice while paint landed is still a working stroke.
        assertEquals("works", v(refused = true))
    }
}
