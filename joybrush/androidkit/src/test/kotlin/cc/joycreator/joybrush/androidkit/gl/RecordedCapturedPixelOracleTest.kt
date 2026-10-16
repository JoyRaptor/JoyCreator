package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.core.paint.Tiles
import cc.joycreator.joybrush.core.stroke.CaptureDomain
import cc.joycreator.joybrush.core.stroke.CaptureRect
import cc.joycreator.joybrush.core.stroke.CapturedPhysicalSource
import cc.joycreator.joybrush.core.stroke.SourcePlacement
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RecordedCapturedPixelOracleTest {
    private fun patterned(size: Int) = ByteArray(size * size * 4) { (it + 1).toByte() }

    private fun pixel(bytes: ByteArray, size: Int, x: Int, y: Int): ByteArray {
        val start = (y * size + x) * 4
        return bytes.copyOfRange(start, start + 4)
    }

    @Test fun negativePlacementSplitsTilesAndHonorsWindowHolePerPixel() {
        val size = 4
        val sourceBytes = patterned(size)
        val source = CapturedPhysicalSource(
            CaptureDomain(
                window = CaptureRect(1, 0, 3, 4),
                excluded = listOf(CaptureRect(2, 1, 1, 2)),
            ),
            SourcePlacement(dx = -2, dy = 1),
        )
        val placed = RecordedCapturedPixelOracle.place(mapOf(Tiles.key(0, 0) to sourceBytes), source, size)

        assertEquals(setOf(
            Tiles.key(-1, 0), Tiles.key(0, 0), Tiles.key(-1, 1), Tiles.key(0, 1),
        ), placed.keys)
        assertContentEquals(pixel(sourceBytes, size, 1, 0), pixel(placed.getValue(Tiles.key(-1, 0)), size, 3, 1))
        assertContentEquals(pixel(sourceBytes, size, 3, 0), pixel(placed.getValue(Tiles.key(0, 0)), size, 1, 1))
        assertContentEquals(pixel(sourceBytes, size, 1, 3), pixel(placed.getValue(Tiles.key(-1, 1)), size, 3, 0))
        assertContentEquals(ByteArray(4), pixel(placed.getValue(Tiles.key(0, 0)), size, 0, 2),
            "The excluded source texel leaves a zero destination")
        assertContentEquals(ByteArray(4), pixel(placed.getValue(Tiles.key(-1, 0)), size, 2, 1),
            "The source texel outside the window is not copied")
    }

    @Test fun transparentSourceStillCreatesItsDestinationTileKey() {
        val zero = ByteArray(4 * 4 * 4)
        val source = CapturedPhysicalSource(CaptureDomain(), SourcePlacement(dx = 4, dy = 0))

        val placed = RecordedCapturedPixelOracle.place(mapOf(Tiles.key(0, 0) to zero), source, tileSize = 4)

        assertEquals(setOf(Tiles.key(1, 0)), placed.keys)
        assertTrue(placed.getValue(Tiles.key(1, 0)).all { it == 0.toByte() })
    }

    @Test fun zeroOffsetCopiesOnlyTheWindowAcrossExistingCanonicalTiles() {
        val size = 4
        val first = patterned(size)
        val outside = ByteArray(size * size * 4) { 0x7f }
        val source = CapturedPhysicalSource(CaptureDomain(CaptureRect(1, 1, 2, 2)))

        val placed = RecordedCapturedPixelOracle.place(
            mapOf(Tiles.key(0, 0) to first, Tiles.key(1, 0) to outside), source, size,
        )

        assertEquals(setOf(Tiles.key(0, 0)), placed.keys)
        val result = placed.getValue(Tiles.key(0, 0))
        for (y in 0 until size) for (x in 0 until size) {
            val expected = if (x in 1..2 && y in 1..2) pixel(first, size, x, y) else ByteArray(4)
            assertContentEquals(expected, pixel(result, size, x, y), "pixel ($x,$y)")
        }
    }

    @Test fun invalidByteLengthsAndSourceOrDestinationLimitsRefuseWholeResult() {
        val full = CapturedPhysicalSource(CaptureDomain())
        assertFailsWith<IllegalArgumentException> {
            RecordedCapturedPixelOracle.place(mapOf(Tiles.key(0, 0) to ByteArray(3)), full, 4)
        }

        val tooMany = (0..64).associate { Tiles.key(it, 0) to ByteArray(4 * 4 * 4) }
        assertFailsWith<IllegalArgumentException> { RecordedCapturedPixelOracle.place(tooMany, full, 4) }

        val twoTiles = mapOf(
            Tiles.key(0, 0) to ByteArray(4 * 4 * 4),
            Tiles.key(1, 0) to ByteArray(4 * 4 * 4),
        )
        assertFailsWith<IllegalArgumentException> {
            RecordedCapturedPixelOracle.place(twoTiles, full, tileSize = 4, maxOutputTiles = 1)
        }
        assertFailsWith<IllegalArgumentException> {
            RecordedCapturedPixelOracle.place(twoTiles, full, tileSize = 4, maxOutputTiles = 257)
        }
    }

    @Test fun placementOutsideIntPixelRangeRefusesInsteadOfClipping() {
        val highestFullTileTx = Int.MAX_VALUE / 4
        val source = CapturedPhysicalSource(CaptureDomain(), SourcePlacement(dx = 8))
        val tiles = mapOf(Tiles.key(highestFullTileTx, 0) to ByteArray(4 * 4 * 4))

        assertFailsWith<IllegalArgumentException> {
            RecordedCapturedPixelOracle.place(tiles, source, tileSize = 4)
        }
    }
}
