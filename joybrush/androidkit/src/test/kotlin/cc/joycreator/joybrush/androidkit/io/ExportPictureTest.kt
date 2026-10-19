package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.core.doc.*
import cc.joycreator.joybrush.core.render.RegionRenderer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.imageio.ImageIO
import kotlin.test.*

/** LAYER_OPS §6 and the editable-lines export refusal: exporters read the picture as the screen shows it. */
class ExportPictureTest {
    private fun png(bytes: ByteArray) = ImageIO.read(ByteArrayInputStream(bytes))

    @Test fun aDrawingWithEditableLinesExportsWhatTheScreenShows() {
        val lines = LineArchiveFixture.contents()
        assertNotNull(RegionRenderer.unsupportedContentReason(lines.doc), "the exporters refuse line storage itself")
        val shown = ExportPicture.asShown(lines)
        assertNull(RegionRenderer.unsupportedContentReason(shown.doc))
        // The look (red 13), not the base (red 5): the picture includes the lines.
        assertEquals(13, png(CanvasPng.encode(shown, false)).getRGB(0, 0) shr 16 and 0xFF)
        assertTrue(shown.slabs.isEmpty() && shown.looks.isEmpty() && shown.strokes.isEmpty())
        // The drawing itself is untouched.
        assertEquals(listOf("0_0"), lines.doc.layers.single().cels.single().looks)
        assertEquals(5, lines.tiles.getValue(Triple("l", "c", "0_0"))[0].toInt())
    }

    @Test fun aDrawingWithoutLinesIsHandedOverAsItIs() {
        val plain = drawing(mapOf("0_0" to dot(3, 4)))
        assertSame(plain, ExportPicture.asShown(plain))
    }

    @Test fun theWholeDrawingReachesEveryPaintedPixelAcrossTilesAndNegativeCoordinates() {
        val c = drawing(mapOf("0_0" to dot(10, 20), "-1_1" to dot(TILE_SIZE - 3, 5), "2_0" to ByteArray(TILE_BYTES)))
        assertEquals(RectPx(-3, 20, 14, TILE_SIZE + 5 - 20 + 1), ExportPicture.paintedBounds(c))
    }

    @Test fun hiddenLayersAndMasksAreNotPartOfTheWholeDrawing() {
        val c = drawing(mapOf("0_0" to dot(1, 1)))
        val l = c.doc.layers.single()
        val hidden = c.copy(doc = c.doc.copy(layers = listOf(l.copy(visible = false))))
        assertNull(ExportPicture.paintedBounds(hidden))
        assertFailsWith<JbArchiveException> { ExportPicture.wholeDrawing(hidden) }.message!!.let { assertTrue("nothing is drawn" in it) }
    }

    @Test fun theWholeDrawingExportsFlatAndLayeredAtItsPaintedSize() {
        val c = drawing(mapOf("0_0" to dot(10, 20), "1_0" to dot(5, 30)))
        val rect = ExportPicture.wholeDrawing(c)
        assertEquals(RectPx(10, 20, TILE_SIZE + 5 - 10 + 1, 11), rect)
        val flat = png(CanvasPng.encodeRegion(c, rect, false))
        assertEquals(rect.w, flat.width); assertEquals(rect.h, flat.height)
        assertEquals(0xFFFF0000.toInt(), flat.getRGB(0, 0))
        assertEquals(0xFFFF0000.toInt(), flat.getRGB(rect.w - 1, rect.h - 1))
        val ora = ByteArrayOutputStream().also { OraExport.write(it, c, "", null, false, brushLookup = null, region = rect) }.toByteArray()
        val names = ZipInputStream(ByteArrayInputStream(ora)).use { z -> generateSequence { z.nextEntry?.name }.toList() }
        assertEquals("mimetype", names.first())
        assertTrue("stack.xml" in names && "mergedimage.png" in names)
    }

    /** One opaque red pixel at ([x], [y]) of a tile. */
    private fun dot(x: Int, y: Int) = ByteArray(TILE_BYTES).also { val i = (y * TILE_SIZE + x) * 4; it[i] = -1; it[i + 3] = -1 }

    private fun drawing(tiles: Map<String, ByteArray>): JbContents {
        var id = 0
        val base = DocOps.newDocument("doc", "Art", 64, 64) { "id${id++}" }
        val layer = base.layers.single()
        val cel = layer.cels.single()
        val doc = base.copy(layers = listOf(layer.copy(cels = listOf(cel.copy(tiles = tiles.keys.sorted())))))
        return JbContents(doc, tiles.mapKeys { (k, _) -> Triple(layer.id, cel.id, k) }, emptyMap())
    }
}
