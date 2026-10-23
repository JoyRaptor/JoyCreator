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

    @Test fun rasterizedLinesKeepTheirUpperSlabsInPngAndOpenRaster() {
        val plain = drawing(mapOf("0_0" to dot(0, 0)))
        val layer = plain.doc.layers.single()
        val cel = layer.cels.single()
        val blue = dot(0, 0).also { it[0] = 0; it[2] = -1 }
        val original = plain.copy(doc = plain.doc.copy(layers = listOf(layer.copy(cels = listOf(
            cel.copy(slabs = listOf(SlabRef("0_0", 0), SlabRef("0_0", 2)))
        )))), slabs = mapOf(SlabKey(layer.id, cel.id, "0_0", 2) to blue))
        val shown = ExportPicture.asShown(original)
        assertEquals(0xFF0000FF.toInt(), png(CanvasPng.encode(shown, false)).getRGB(0, 0))
        val ora = ByteArrayOutputStream().also {
            OraExport.write(it, shown, "", null, false, brushLookup = null, region = RectPx(0, 0, 1, 1))
        }.toByteArray()
        val merged = ZipInputStream(ByteArrayInputStream(ora)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null && entry.name != "mergedimage.png") entry = zip.nextEntry
            assertNotNull(entry)
            png(zip.readBytes())
        }
        assertEquals(0xFF0000FF.toInt(), merged.getRGB(0, 0))
        assertEquals(255, original.tiles.getValue(Triple(layer.id, cel.id, "0_0"))[0].toInt() and 255)
        assertEquals(2, original.doc.layers.single().cels.single().slabs.size)
    }

    @Test fun aMissingDeclaredSavedLookIsRefusedBeforeTheBaseCanBeExported() {
        val original = LineArchiveFixture.contents()
        val damaged = original.copy(looks = emptyMap())
        assertFailsWith<cc.joycreator.joybrush.core.render.RegionException> { ExportPicture.asShown(damaged) }
        assertTrue(damaged.doc.layers.single().cels.single().looks.isNotEmpty())
    }

    @Test fun aFullyErasedSlabStackExportsTransparencyWithoutDeclaringAMissingTile() {
        val plain = drawing(mapOf("0_0" to dot(0, 0)))
        val layer = plain.doc.layers.single()
        val cel = layer.cels.single()
        val original = plain.copy(doc = plain.doc.copy(layers = listOf(layer.copy(cels = listOf(
            cel.copy(slabs = listOf(SlabRef("0_0", 0), SlabRef("0_0", 1, "erase")))
        )))), slabs = mapOf(SlabKey(layer.id, cel.id, "0_0", 1) to dot(0, 0)))
        val shown = ExportPicture.asShown(original)
        assertTrue(shown.doc.layers.single().cels.single().tiles.isEmpty())
        assertFalse(Triple(layer.id, cel.id, "0_0") in shown.tiles)
        assertEquals(0, png(CanvasPng.encode(shown, false)).getRGB(0, 0))
        assertEquals(listOf("0_0"), original.doc.layers.single().cels.single().tiles)
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
