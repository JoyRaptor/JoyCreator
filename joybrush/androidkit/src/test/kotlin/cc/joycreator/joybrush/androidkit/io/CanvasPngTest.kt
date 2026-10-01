package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.core.doc.*
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.*

class CanvasPngTest {
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
}
