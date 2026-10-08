package cc.joycreator.joybrush.core.render

import cc.joycreator.joybrush.core.brush.*
import cc.joycreator.joybrush.core.doc.*
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.InkTiles
import kotlin.test.*

class RegionInkTest {
    private val fill = BrushPreset(id = "fill", name = "Fill", engine = ENGINE_FILL, size = Param(8f))
    private val record = StrokeRecord("shape", "fill", 1L, 0f, 1f,
        listOf(PenSample(0f, 0f, 0.0), PenSample(512f, 0f, 1.0), PenSample(512f, 256f, 2.0),
            PenSample(0f, 256f, 3.0)), 0x80ff0000.toInt())
    private val ink = Layer("ink", "Ink", LayerKind.INK, cels = listOf(Cel("cel", strokesFile = "lines.jbs")))
    private fun doc(vararg layers: Layer) = JbDocument(id = "doc", name = "Doc", boards = emptyList(), layers = layers.toList())
    private val noPixels = TileSource { _, _, _, _ -> null }
    private val rect = RectPx(0, 0, 512, 256)

    @Test fun inkRenderingNeedsLookupAndDoesNotGuessMissingRecords() {
        assertEquals("Vector artwork rendering is not available yet", assertFailsWith<RegionException> {
            RegionRenderer.render(doc(ink), noPixels, rect, null, null)
        }.message)
        assertFailsWith<RegionException> {
            RegionRenderer.render(doc(ink), noPixels, rect, null, null, brushLookup = { fill })
        }
    }
    @Test fun inkCompositesWithLayerOpacityPaperAndMask() {
        val masked = ink.copy(opacity = 0.5f, mask = Cel("mask", tiles = listOf("0_0")))
        val source = TileSource { _, cel, tx, _ ->
            if (cel == "mask" && tx == 0) ByteArray(RegionRenderer.TILE_BYTES) { if (it % 4 == 0) 128.toByte() else 0 }
            else null
        }
        val image = RegionRenderer.render(doc(masked), source, rect, null, "#FFFFFF",
            brushLookup = { fill }, strokeSource = { _, _ -> listOf(record) })
        // alpha 128/255 * half opacity * mask 128/255, over white.
        assertEquals(listOf(255, 223, 223, 255), (0..3).map { image[it].toInt() and 255 })
        val secondTile = 256 * 4
        assertEquals(listOf(255, 191, 191, 255), (0..3).map { image[secondTile + it].toInt() and 255 })
    }
    @Test fun refusalsAreReportedOncePerCelAndGoodRecordsStillRender() {
        val bad = record.copy(brushId = "missing")
        val reports = ArrayList<InkTiles.Refusal>()
        val image = RegionRenderer.render(doc(ink), noPixels, rect, null, null,
            brushLookup = { if (it == "fill") fill else null }, strokeSource = { _, _ -> listOf(bad, record) },
            onInkRefusal = reports::add)
        assertEquals(1, reports.size)
        assertEquals(0, reports.single().index)
        assertEquals(128, image[3].toInt() and 255)
        assertFailsWith<RegionException> {
            RegionRenderer.render(doc(ink), noPixels, rect, null, null,
                brushLookup = { null }, strokeSource = { _, _ -> listOf(bad) })
        }
    }
    @Test fun hiddenAndAbsentFrameInkNeverReplay() {
        var calls = 0
        val animated = ink.copy(animatedIn = "board", frameCel = mapOf("frame" to "cel"))
        for (layer in listOf(ink.copy(visible = false), animated)) {
            val image = RegionRenderer.render(doc(layer), noPixels, rect, null, null,
                brushLookup = { calls++; fill }, strokeSource = { _, _ -> listOf(record) })
            assertTrue(image.all { it == 0.toByte() })
        }
        assertEquals(0, calls)
        val image = RegionRenderer.render(doc(animated), noPixels, rect, "frame", null,
            brushLookup = { fill }, strokeSource = { _, _ -> listOf(record) })
        assertEquals(128, image[3].toInt() and 255)
    }
    @Test fun inkCanSupplyClippingShapeWithoutPaintingItsZeroOpacityLayer() {
        val paint = Layer("paint", "Paint", LayerKind.PAINT, clip = true, cels = listOf(Cel("cel")))
        val pixels = TileSource { layer, _, _, _ -> if (layer == "paint") ByteArray(RegionRenderer.TILE_BYTES) {
            if (it % 4 == 2 || it % 4 == 3) 255.toByte() else 0
        } else null }
        val image = RegionRenderer.render(doc(ink.copy(opacity = 0f), paint), pixels, RectPx(0, 0, 1, 1), null, null,
            brushLookup = { fill }, strokeSource = { _, _ -> listOf(record) })
        assertEquals(listOf(0, 0, 255, 128), image.map { it.toInt() and 255 })
    }
}
