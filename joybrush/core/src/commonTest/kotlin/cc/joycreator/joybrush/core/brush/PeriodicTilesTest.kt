package cc.joycreator.joybrush.core.brush

import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.paint.Dab
import cc.joycreator.joybrush.core.paint.Tiles
import cc.joycreator.joybrush.core.paint.TipShape
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tile-board wrap maths: what a pixel outside the board rectangle reads as (R50), and smudge reading across the seam. */
class PeriodicTilesTest {
    private val size = Tiles.SIZE

    /** A stored layer: deterministic noise inside [rect] only (premultiplied: rgb <= a), nothing outside, like a tile board. */
    private fun stored(rect: RectPx, seed: Int = 7): TileReader {
        val cache = HashMap<Long, ByteArray?>()
        return TileReader { tx, ty ->
            cache.getOrPut(Tiles.key(tx, ty)) {
                var any = false
                val t = ByteArray(size * size * 4)
                for (row in 0 until size) for (col in 0 until size) {
                    val x = tx * size + col; val y = ty * size + row
                    if (x < rect.x || y < rect.y || x >= rect.x + rect.w || y >= rect.y + rect.h) continue
                    val r = Random(seed * 1_000_003 + x * 7919 + y * 104_729)
                    val a = r.nextInt(256); if (a == 0) continue
                    val i = (row * size + col) * 4
                    t[i] = r.nextInt(a + 1).toByte(); t[i + 1] = r.nextInt(a + 1).toByte(); t[i + 2] = r.nextInt(a + 1).toByte(); t[i + 3] = a.toByte()
                    any = true
                }
                if (any) t else null
            }
        }
    }

    private fun pixel(reader: TileReader, x: Int, y: Int): List<Int> {
        val tx = x.floorDiv(size); val ty = y.floorDiv(size)
        val t = reader.tile(tx, ty) ?: return listOf(0, 0, 0, 0)
        val i = ((y - ty * size) * size + (x - tx * size)) * 4
        return (0..3).map { t[i + it].toInt() and 0xFF }
    }

    @Test
    fun wrapBringsEveryCoordinateIntoTheHalfOpenPeriod() {
        assertEquals(10, PeriodicTiles.wrap(10, 10, 5))
        assertEquals(14, PeriodicTiles.wrap(14, 10, 5))
        assertEquals(10, PeriodicTiles.wrap(15, 10, 5))
        assertEquals(14, PeriodicTiles.wrap(9, 10, 5))
        assertEquals(-3, PeriodicTiles.wrap(-3 - 37 * 1000, -3, 37))
        assertEquals((-3L + (Int.MIN_VALUE.toLong() + 3L).mod(37L)).toInt(), PeriodicTiles.wrap(Int.MIN_VALUE, -3, 37))
        for (v in listOf(Int.MIN_VALUE, -1, 0, 1, Int.MAX_VALUE)) assertTrue(PeriodicTiles.wrap(v, -100, 300) in -100 until 200, "v=$v")
        assertFailsWith<IllegalArgumentException> { PeriodicTiles.wrap(0, 0, 0) }
    }

    @Test
    fun insideIsTrueOnlyForATileWhollyInsideTheBoard() {
        val rect = RectPx(0, 0, 512, 300)
        assertTrue(PeriodicTiles.inside(rect, Tiles.key(0, 0)))
        assertTrue(PeriodicTiles.inside(rect, Tiles.key(1, 0)))
        assertFalse(PeriodicTiles.inside(rect, Tiles.key(0, 1)), "rows 256..511 run past y=300")
        assertFalse(PeriodicTiles.inside(rect, Tiles.key(-1, 0)))
        assertFalse(PeriodicTiles.inside(RectPx(1, 0, 600, 600), Tiles.key(0, 0)))
    }

    @Test
    fun composeEqualsThePerPixelWrappedReadForAwkwardBoards() {
        for (rect in listOf(RectPx(-40, 17, 37, 53), RectPx(100, -300, 300, 260), RectPx(0, 0, 256, 256), RectPx(5, 5, 1, 1))) {
            val layer = stored(rect)
            for (key in listOf(Tiles.key(0, 0), Tiles.key(-1, -1), Tiles.key(1, -2), Tiles.key(3, 2))) {
                val got = PeriodicTiles.compose(rect, key, layer)
                val ox = Tiles.tx(key) * size; val oy = Tiles.ty(key) * size
                var nonEmpty = false
                for (row in 0 until size step 3) for (col in 0 until size step 3) {
                    val want = pixel(layer, PeriodicTiles.wrap(ox + col, rect.x, rect.w), PeriodicTiles.wrap(oy + row, rect.y, rect.h))
                    val i = (row * size + col) * 4
                    val have = if (got == null) listOf(0, 0, 0, 0) else (0..3).map { got[i + it].toInt() and 0xFF }
                    assertEquals(want, have, "rect=$rect key=($ox,$oy) px=($col,$row)")
                    if (want[3] != 0) nonEmpty = true
                }
                assertEquals(nonEmpty, got != null, "a composed tile is null exactly when it is empty: rect=$rect")
            }
        }
    }

    @Test
    fun aTileWhollyInsideTheBoardComposesToTheStoredTileAndEachSourceIsReadOnce() {
        val rect = RectPx(0, 0, 512, 512)
        val layer = stored(rect)
        val reads = HashMap<Long, Int>()
        val counting = TileReader { tx, ty -> val k = Tiles.key(tx, ty); reads[k] = (reads[k] ?: 0) + 1; layer.tile(tx, ty) }
        assertContentEquals(layer.tile(1, 1), PeriodicTiles.compose(rect, Tiles.key(1, 1), counting))
        PeriodicTiles.compose(rect, Tiles.key(2, 1), counting)   // wholly outside: wraps onto tile (0,1)
        assertTrue(reads.values.all { it == 1 }, "reads=$reads")
    }

    @Test
    fun anEmptyBoardComposesToNull() {
        assertNull(PeriodicTiles.compose(RectPx(0, 0, 100, 100), Tiles.key(3, 3), TileReader { _, _ -> null }))
    }

    /**
     * The smudge oracle: on a tile board, a smudge reading the stored rectangle with [SmudgeStroke.period] must pick up EXACTLY
     * what an ordinary smudge picks up from a layer that physically holds the repeated picture everywhere. Dabs straddle every
     * seam and corner of a board whose edges are not on tile borders.
     */
    @Test
    fun aSmudgeOnATileBoardPicksUpExactlyWhatItWouldFromTheRepeatedPicture() {
        val rect = RectPx(30, -20, 170, 140)
        val layer = stored(rect, seed = 3)
        val repeated = TileReader { tx, ty -> PeriodicTiles.compose(rect, Tiles.key(tx, ty), layer) }
        val tip = TipShape(hardness = 0.6f)
        val dabs = buildList {
            for (k in 0 until 40) add(Dab(x = 20f + k * 6.3f, y = -25f + k * 4.1f, radius = 9f, angle = k * 0.2f))   // diagonal over corners
            for (k in 0 until 20) add(Dab(x = 199f + (k % 3), y = 40f + k * 2f, radius = 14f))                        // along the right seam
        }
        for (start in listOf(SmudgeCarried(0.9f, 0.1f, 0.4f, 1f, 0.3f, 0f), SmudgeCarried(0f, 0f, 1f, 1f, 1f, 0.2f))) {
            val wrapped = SmudgeStroke(layer, start, tip, period = rect).colours(dabs)
            val reference = SmudgeStroke(repeated, start, tip).colours(dabs)
            assertContentEquals(reference, wrapped)
        }
        // And the wrap is not a no-op: without the period, a dab off the board picks up nothing there.
        val offBoard = listOf(Dab(x = rect.x - 30f, y = rect.y + 50f, radius = 9f))
        val plain = SmudgeStroke(layer, SmudgeCarried(0f, 0f, 1f, 1f, 1f, 0f), tip).colours(offBoard + offBoard)
        val tiled = SmudgeStroke(layer, SmudgeCarried(0f, 0f, 1f, 1f, 1f, 0f), tip, period = rect).colours(offBoard + offBoard)
        assertFalse(plain.contentEquals(tiled))
    }

    @Test
    fun aPeriodThatHoldsTheWholeStrokeChangesNothing() {
        // Every sample point is already inside the period, so wrapping must be the identity: same colours, bit for bit.
        val layer = stored(RectPx(0, 0, 300, 300), seed = 11)
        val dabs = List(25) { Dab(x = 40f + it * 9f, y = 150f + it, radius = 12f) }
        val start = SmudgeCarried(0.2f, 0.4f, 0.6f, 1f, 0.5f, 0.1f)
        assertContentEquals(SmudgeStroke(layer, start, TipShape()).colours(dabs),
            SmudgeStroke(layer, start, TipShape(), period = RectPx(-1000, -1000, 4000, 4000)).colours(dabs))
    }
}
