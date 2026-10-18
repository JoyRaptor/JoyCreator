package cc.joycreator.joybrush.androidkit.preview

import cc.joycreator.joybrush.core.paint.Tiles
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The GPU-free pixel work around a preview. Tiles are premultiplied RGBA8, rows top first. */
class PreviewRasterTest {

    private fun px(rgba: ByteArray, w: Int, x: Int, y: Int) = (0 until 4).map { rgba[(y * w + x) * 4 + it].toInt() and 0xFF }

    @Test fun premultiplyingKeepsOpaqueColoursWholeAndZeroesTransparentOnes() {
        assertContentEquals(byteArrayOf(10, 20, 30, -1), PreviewRaster.premultiplied(0xFF0A141E.toInt()))
        assertContentEquals(byteArrayOf(0, 0, 0, 0), PreviewRaster.premultiplied(0x00FFFFFF))
    }

    @Test fun premultiplyingRoundsToTheNearest() {
        // 200 at alpha 128 is 100.39; 255 at alpha 0x59 (89) is exactly 89.
        assertEquals(listOf(100, 100, 100, 128), PreviewRaster.premultiplied(0x80C8C8C8.toInt()).map { it.toInt() and 0xFF })
        assertEquals(listOf(0x59, 0x59, 0x59, 0x59), PreviewRaster.premultiplied(0x59FFFFFF).map { it.toInt() and 0xFF })
    }

    @Test fun aFilledRectLandsWhereItWasAskedAcrossTileBoundaries() {
        val tiles = HashMap<Long, ByteArray>()
        PreviewRaster.fillRect(tiles, 250, 3, 262, 6, 0xFFFF0000.toInt())
        assertEquals(setOf(Tiles.key(0, 0), Tiles.key(1, 0)), tiles.keys)
        val out = PreviewRaster.assemble(tiles, 300, 10)
        assertEquals(listOf(255, 0, 0, 255), px(out, 300, 250, 3))
        assertEquals(listOf(255, 0, 0, 255), px(out, 300, 261, 5))
        assertEquals(listOf(0, 0, 0, 0), px(out, 300, 249, 3), "left of it")
        assertEquals(listOf(0, 0, 0, 0), px(out, 300, 262, 3), "right of it")
        assertEquals(listOf(0, 0, 0, 0), px(out, 300, 250, 6), "below it")
        assertEquals(36, (0 until 300 * 10).count { out[it * 4 + 3].toInt() != 0 })
    }

    @Test fun aRectWithNothingInsideThePictureMakesNoTiles() {
        val tiles = HashMap<Long, ByteArray>()
        PreviewRaster.fillRect(tiles, -20, -20, 0, 5, 0xFFFFFFFF.toInt())
        PreviewRaster.fillRect(tiles, 5, 5, 5, 9, 0xFFFFFFFF.toInt())
        assertTrue(tiles.isEmpty())
        PreviewRaster.fillRect(tiles, -4, -4, 2, 2, 0xFF00FF00.toInt())
        assertEquals(setOf(Tiles.key(0, 0)), tiles.keys, "the part inside the picture is kept")
    }

    @Test fun assemblingKeepsEachTilesRowsAndSkipsTilesOutsideThePicture() {
        val tile = ByteArray(Tiles.SIZE * Tiles.SIZE * 4)
        for (y in 0 until Tiles.SIZE) for (x in 0 until Tiles.SIZE) {
            val a = (y * Tiles.SIZE + x) * 4
            tile[a] = x.toByte(); tile[a + 1] = y.toByte(); tile[a + 3] = -1
        }
        val out = PreviewRaster.assemble(mapOf(Tiles.key(1, 0) to tile, Tiles.key(0, 3) to tile, Tiles.key(-1, 0) to tile), 300, 40)
        assertEquals(listOf(0, 0, 0, 255), px(out, 300, 256, 0))
        assertEquals(listOf(43, 39, 0, 255), px(out, 300, 299, 39))
        assertEquals(listOf(0, 0, 0, 0), px(out, 300, 255, 0), "no tile there")
        assertEquals(300 * 40 * 4, out.size)
    }

    @Test fun aTileOfTheWrongSizeIsRefused() {
        assertFailsWith<IllegalArgumentException> { PreviewRaster.assemble(mapOf(Tiles.key(0, 0) to ByteArray(10)), 8, 8) }
    }

    @Test fun aLayerOverPaperKeepsTheLayerWhereItIsOpaqueAndThePaperWhereItIsNot() {
        val layer = byteArrayOf(0, 0, 0, 0, 50, 0, 0, -1, 50, 100, 0, 127)
        val paper = byteArrayOf(-56, -56, -56, -1, 9, 9, 9, -1, -56, -56, -56, -1)   // -56 is 200
        PreviewRaster.overPaper(layer, paper)
        assertEquals(listOf(200, 200, 200, 255), px(layer, 3, 0, 0))
        assertEquals(listOf(50, 0, 0, 255), px(layer, 3, 1, 0))
        // premultiplied, alpha 127: paper keeps (255 - 127) / 255 of itself, 50 + 100.4 = 150, 100 + 100.4 = 200, 0 + 100.4 = 100
        assertEquals(listOf(150, 200, 100, 255), px(layer, 3, 2, 0))
    }

    @Test fun aLayerThatOverflowsAChannelIsClampedNotWrapped() {
        val layer = byteArrayOf(-6, 0, 0, 100)   // 250 premultiplied at alpha 100: more than the alpha allows
        PreviewRaster.overPaper(layer, byteArrayOf(-1, 0, 0, -1))
        assertEquals(255, layer[0].toInt() and 0xFF)
    }

    @Test fun layerAndPaperMustBeTheSameSize() {
        assertFailsWith<IllegalArgumentException> { PreviewRaster.overPaper(ByteArray(8), ByteArray(4)) }
    }
}
