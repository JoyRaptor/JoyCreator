package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.core.doc.*
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.*

class CanvasPngTest {
    @Test fun texturedPaperExportUsesDocumentCoordinatesAndLeavesTilesUntouched() {
        val art = contents()
        val savedTile = art.tiles.values.single().copyOf()
        var calls = 0
        val renderer: (RectPx) -> ByteArray = { rect ->
            assertEquals(RectPx(0, 0, 2, 1), rect); calls++
            byteArrayOf(0, 80, 160.toByte(), 255.toByte(), 20, 40, 60, 255.toByte())
        }
        val with = ImageIO.read(ByteArrayInputStream(CanvasPng.encode(art, true, renderer)))
        val without = ImageIO.read(ByteArrayInputStream(CanvasPng.encode(art, false, renderer)))
        assertEquals(1, calls)
        assertEquals(0xFF802850.toInt(), with.getRGB(0, 0))
        assertEquals(0xFF14283C.toInt(), with.getRGB(1, 0))
        assertEquals(0x80FF0000.toInt(), without.getRGB(0, 0)); assertEquals(0, without.getRGB(1, 0))
        assertContentEquals(savedTile, art.tiles.values.single())
    }
    @Test fun opaqueLayerPixelIsIdenticalWithAndWithoutTexturedPaper() {
        val art = contents().let { source ->
            val tile = source.tiles.values.single().copyOf().also { it[0] = 255.toByte(); it[3] = 255.toByte() }
            source.copy(tiles = source.tiles.mapValues { tile })
        }
        val renderer: (RectPx) -> ByteArray = { byteArrayOf(1, 2, 3, -1, 4, 5, 6, -1) }
        val with = ImageIO.read(ByteArrayInputStream(CanvasPng.encode(art, true, renderer)))
        val without = ImageIO.read(ByteArrayInputStream(CanvasPng.encode(art, false, renderer)))
        assertEquals(without.getRGB(0, 0), with.getRGB(0, 0))
        assertEquals(255, with.getRGB(1, 0) ushr 24); assertEquals(0, without.getRGB(1, 0) ushr 24)
    }
    private fun contents(rect: RectPx = RectPx(0, 0, 2, 1)): JbContents {
        var id = 0
        val base = DocOps.newDocument("doc", "Art", rect.w, rect.h) { "id${id++}" }
        val layer = base.layers.single()
        val doc = base.copy(
            paper = Paper("#204060"),
            boards = listOf(base.boards.single().copy(rect = rect)),
            layers = listOf(layer.copy(cels = listOf(layer.cels.single().copy(tiles = listOf("0_0"))))),
        )
        val tile = ByteArray(TILE_BYTES)
        // Half-alpha premultiplied red, followed by fully transparent art.
        tile[0] = 128.toByte(); tile[3] = 128.toByte()
        return JbContents(doc, mapOf(Triple(layer.id, layer.cels.single().id, "0_0") to tile), emptyMap())
    }
    @Test fun transparentExportPreservesAlphaAndUnpremultipliesColour() {
        val image = ImageIO.read(ByteArrayInputStream(CanvasPng.encode(contents(), false)))
        assertEquals(2, image.width); assertEquals(1, image.height)
        assertEquals(0x80FF0000.toInt(), image.getRGB(0, 0))
        assertEquals(0, image.getRGB(1, 0))
    }
    @Test fun paperOptionCompositesOntoPaperWithoutChangingDocument() {
        val art = contents()
        val image = ImageIO.read(ByteArrayInputStream(CanvasPng.encode(art, true)))
        assertEquals(0xFF204060.toInt(), image.getRGB(1, 0))
        assertEquals(255, image.getRGB(0, 0) ushr 24)
        assertFalse(art.doc.paper.includeInExport)
        assertContentEquals(byteArrayOf(128.toByte(), 0, 0, 128.toByte()), art.tiles.values.single().copyOf(4))
    }
    @Test fun exportUsesSignedBoardOriginInsteadOfScreenOrigin() {
        val art = contents(RectPx(-1, 0, 2, 1))
        val image = ImageIO.read(ByteArrayInputStream(CanvasPng.encode(art, false)))
        assertEquals(0, image.getRGB(0, 0))
        assertEquals(0x80FF0000.toInt(), image.getRGB(1, 0))
    }
    @Test fun missingDeclaredPixelsAreRefused() {
        assertFailsWith<JbArchiveException> { CanvasPng.encode(contents().copy(tiles = emptyMap()), false) }
    }
    @Test fun exportKeepsPaintOnBothSidesOfNegativeTileBoundary() {
        val base = contents(RectPx(-64, 32, 128, 96))
        val layer = base.doc.layers.single()
        val cel = layer.cels.single().copy(tiles = listOf("-1_0", "0_0"))
        val tiles = (-1..0).associate { tx ->
            val bytes = ByteArray(TILE_BYTES)
            for (y in 48 until 80) for (x in -32 until 32) {
                val localX = x - tx * 256
                if (localX !in 0 until 256) continue
                val offset = (y * 256 + localX) * 4
                bytes[offset] = 20; bytes[offset + 1] = 60
                bytes[offset + 2] = 240.toByte(); bytes[offset + 3] = 255.toByte()
            }
            Triple(layer.id, cel.id, "${tx}_0") to bytes
        }
        val art = base.copy(doc = base.doc.copy(layers = listOf(layer.copy(cels = listOf(cel)))), tiles = tiles)
        for (paper in listOf(false, true)) {
            val image = ImageIO.read(ByteArrayInputStream(CanvasPng.encode(art, paper)))
            assertEquals(128, image.width); assertEquals(96, image.height)
            for (y in 0 until 96) for (x in 0 until 128) {
                val expected = if (x in 32 until 96 && y in 16 until 48) 0xFF143CF0.toInt()
                    else if (paper) 0xFF204060.toInt() else 0
                assertEquals(expected, image.getRGB(x, y), "$paper: ($x,$y)")
            }
        }
    }
}
