package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.paint.Tiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FrameDuplicatePixelsTest {
    private val bytes = Tiles.SIZE * Tiles.SIZE * 4
    private fun pixel(tile: ByteArray, x: Int, y: Int) =
        tile.sliceArray((y * Tiles.SIZE + x) * 4 until (y * Tiles.SIZE + x + 1) * 4).toList()

    @Test fun independentClipHandlesNegativeOriginAndOddExclusiveRightBottom() {
        val negative = Tiles.key(-1, -1)
        val positive = Tiles.key(1, 1)
        val outside = Tiles.key(-2, -2)
        val source = listOf(negative, positive, outside).associateWith { ByteArray(bytes) { 37 } }
        val result = FrameDuplicatePixels.clipped(source, RectPx(-19, -13, 293, 271))
        val paint = List(4) { 37.toByte() }
        val zero = List(4) { 0.toByte() }
        assertEquals(paint, pixel(result.getValue(negative), 237, 243))
        assertEquals(zero, pixel(result.getValue(negative), 236, 243))
        assertEquals(zero, pixel(result.getValue(negative), 237, 242))
        assertEquals(paint, pixel(result.getValue(positive), 17, 1))
        assertEquals(zero, pixel(result.getValue(positive), 18, 1))
        assertEquals(zero, pixel(result.getValue(positive), 17, 2))
        assertTrue(result.getValue(outside).all { it == 0.toByte() })
        assertTrue(source.values.all { tile -> tile.all { it == 37.toByte() } })
    }

    @Test fun zeroAlphaDoesNotHideNonzeroRgbInTransparentHalo() {
        val rogue = ByteArray(bytes).also { it[0] = 12 }
        assertFalse(FrameDuplicatePixels.same(null, rogue))
        assertTrue(FrameDuplicatePixels.same(null, ByteArray(bytes)))
        assertTrue(FrameDuplicatePixels.same(ByteArray(bytes), null))
        assertTrue(FrameDuplicatePixels.same(rogue, rogue.copyOf()))
    }

    @Test fun haloIncludesNeighborsAcrossNegativeAndPositiveTileBoundary() {
        val halo = FrameDuplicatePixels.halo(setOf(Tiles.key(-1, 0), Tiles.key(0, 0)))
        assertEquals(12, halo.size)
        assertTrue(Tiles.key(-2, -1) in halo)
        assertTrue(Tiles.key(1, 1) in halo)
        assertFalse(Tiles.key(2, 0) in halo)
    }

    @Test fun changedCountsPixelsAndDetectsUnexpectedNewTiles() {
        val key = Tiles.key(0, 0)
        val a = ByteArray(bytes).also { it[3] = -1 }
        val b = a.copyOf().also { it[0] = 7; it[1] = 8 }
        assertEquals(1L, FrameDuplicatePixels.changed(mapOf(key to a), mapOf(key to b)))
        assertEquals(1L, FrameDuplicatePixels.changed(emptyMap(), mapOf(key to b)))
        assertEquals(1L, FrameDuplicatePixels.painted(mapOf(key to b)))
        assertEquals(0L, FrameDuplicatePixels.changed(emptyMap(), mapOf(key to ByteArray(bytes))))
    }
}
