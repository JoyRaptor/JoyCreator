package cc.joycreator.joybrush.core.media

import cc.joycreator.joybrush.core.paint.Tiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which old paint sets when a drawing's media room is full ([MediaRoom]). */
class MediaRoomTest {
    private val mb = 1L shl 20
    private fun tile(x: Int, y: Int, written: Long, bytes: Long = mb, layer: String = "a") = MediaRoom.Tile(layer, null, Tiles.key(x, y), bytes, written)
    private fun at(ts: List<MediaRoom.Tile>?) = ts?.map { Tiles.tx(it.key) to Tiles.ty(it.key) }

    @Test fun theLeastRecentlyPaintedSetsFirst() {
        val tiles = listOf(tile(0, 0, written = 9), tile(1, 0, written = 2), tile(2, 0, written = 5))
        assertEquals(listOf(1 to 0), at(MediaRoom.choose(tiles, near = listOf(Tiles.key(1, 0)), needBytes = mb)))
        assertEquals(listOf(1 to 0, 2 to 0), at(MediaRoom.choose(tiles, near = emptyList(), needBytes = mb + 1)))
    }

    @Test fun inADrawingJustOpenedTheFarthestFromThePenSetsFirst() {
        // Every tile reads 0 (as loaded): the pen is at (10, 10), so the far corner goes and what is by the pen stays.
        val tiles = listOf(tile(10, 11, 0), tile(0, 0, 0), tile(12, 10, 0), tile(40, 10, 0))
        assertEquals(listOf(40 to 10, 0 to 0), at(MediaRoom.choose(tiles, near = listOf(Tiles.key(10, 10)), needBytes = 2 * mb)))
    }

    @Test fun aLittleMoreIsFreedSoRoomIsNotMadeAgainForTheNextTile() {
        val tiles = (0 until 10).map { tile(it, 0, written = it.toLong()) }
        assertEquals(4, MediaRoom.choose(tiles, emptyList(), needBytes = mb, extraBytes = 3 * mb)!!.size)
        // The extra is a wish, the need is not: with little left, what there is goes and the need is still met.
        assertEquals(2, MediaRoom.choose(tiles.take(2), emptyList(), needBytes = mb, extraBytes = 3 * mb)!!.size)
        assertEquals(24 * mb, MediaRoom.slack(315 * mb)); assertEquals(8 * mb, MediaRoom.slack(64 * mb))
    }

    @Test fun nothingSetsWhenItWouldNotMakeTheRoom() {
        val tiles = listOf(tile(0, 0, 0), tile(1, 0, 0))
        assertNull(MediaRoom.choose(tiles, emptyList(), needBytes = 3 * mb), "paint is never lost for room that still would not be there")
        assertTrue(MediaRoom.choose(tiles, emptyList(), needBytes = 0)!!.isEmpty())
    }

    @Test fun tilesOfOtherLayersCountByWhereTheyLieNotWhoseTheyAre() {
        val tiles = listOf(tile(10, 10, 0, layer = "under"), tile(30, 30, 0, layer = "a"))
        assertEquals(listOf(30 to 30), at(MediaRoom.choose(tiles, near = listOf(Tiles.key(10, 10)), needBytes = mb)))
    }
}
