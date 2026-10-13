package cc.joycreator.joybrush.core.render

import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.doc.*
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import kotlin.test.*

class RegionCapabilityTest {
    private fun doc(cel: Cel) = JbDocument(id = "doc", name = "Drawing",
        boards = listOf(Board("board", "Canvas", BoardKind.CANVAS, RectPx(0, 0, 1, 1))),
        layers = listOf(Layer("layer", "Content", LayerKind.PAINT, cels = listOf(cel))))

    @Test fun everyRenderDoorRefusesDeclaredRecordsSlabsAndLooksBeforeAnyCallback() {
        val unsupported = listOf(Cel("cel", strokesFile = "layers/layer/cel/strokes.jbs"),
            Cel("cel", tiles = listOf("0_0"), slabs = listOf(SlabRef("0_0", 0))),
            Cel("cel", looks = listOf("0_0")))
        for (cel in unsupported) for (empty in listOf(false, true)) {
            var reads = 0
            val source = TileSource { _, _, _, _ -> reads++; null }
            val paper: (RectPx) -> ByteArray = { reads++; error("paper must not run") }
            val brushes: (String) -> BrushPreset? = { reads++; null }
            val records: (String, String) -> List<StrokeRecord>? = { _, _ -> reads++; emptyList() }
            val rect = RectPx(0, 0, if (empty) 0 else 1, 1)
            val drawing = doc(cel)
            val calls = listOf<() -> Unit>(
                { RegionRenderer.render(drawing, source, rect, null, null, paper); Unit },
                { RegionRenderer.renderPremultiplied(drawing, source, rect, null, null, paper); Unit },
                { RegionRenderer.render(drawing, source, rect, null, null, paper, brushes, records) { reads++ }; Unit },
                { RegionRenderer.renderPremultiplied(drawing, source, rect, null, null, paper, brushes, records) { reads++ }; Unit })
            for (call in calls) {
                val message = assertFailsWith<RegionException> { call() }.message.orEmpty()
                assertTrue("layer" in message && "cel" in message && "cannot be exported" in message, message)
                assertEquals(0, reads)
            }
        }
    }

    @Test fun guardChecksHiddenNoncurrentAndMaskCelsWithoutGuessingReach() {
        val clean = doc(Cel("current"))
        val extra = Cel("noncurrent", strokesFile = "lines.jbs")
        val cases = listOf(
            clean.copy(layers = clean.layers.map { it.copy(visible = false, cels = listOf(extra)) }),
            clean.copy(layers = clean.layers.map { it.copy(opacity = 0f, cels = listOf(extra)) }),
            clean.copy(layers = clean.layers.map { it.copy(cels = it.cels + extra) }),
            clean.copy(layers = clean.layers.map { it.copy(mask = extra) }))
        for (drawing in cases) assertTrue(RegionRenderer.unsupportedContentReason(drawing).orEmpty().contains("noncurrent"))
    }

    @Test fun unusedBrushMetadataAndPixelMediaCelsKeepExistingPixels() {
        val cel = Cel("cel", tiles = listOf("0_0"), floatTiles = listOf("0_0"))
        val base = doc(cel)
        val tile = ByteArray(RegionRenderer.TILE_BYTES).also { it[0] = 10; it[1] = 20; it[2] = 30; it[3] = -1 }
        val source = TileSource { _, _, _, _ -> tile }
        val rect = RectPx(0, 0, 1, 1)
        assertNull(RegionRenderer.unsupportedContentReason(base.copy(brushes = listOf("unused"))))
        assertContentEquals(byteArrayOf(10, 20, 30, -1), RegionRenderer.render(base, source, rect, null, null))
        assertContentEquals(RegionRenderer.render(base, source, rect, null, null),
            RegionRenderer.render(base.copy(brushes = listOf("unused")), source, rect, null, null))
    }

    @Test fun legacyInkStillUsesItsBrushCallbackMath() {
        val base = doc(Cel("cel", strokesFile = "lines.jbs"))
        val legacy = base.copy(version = 9, layers = base.layers.map { it.copy(kind = LayerKind.INK) })
        val brush = BrushPreset(id = "ink", name = "Ink", size = Param(2f))
        val record = StrokeRecord("a", "ink", 1, 0f, 1f, emptyList())
        val source = TileSource { _, _, _, _ -> error("legacy ink must replay rather than read pixel tiles") }
        assertNull(RegionRenderer.unsupportedContentReason(legacy))
        assertContentEquals(ByteArray(4), RegionRenderer.render(legacy, source, RectPx(0, 0, 1, 1), null, null,
            brushLookup = { brush }, strokeSource = { _, _ -> listOf(record) }))
    }
}
