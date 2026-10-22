package cc.joycreator.joybrush.core.doc

import kotlin.test.*
import cc.joycreator.joybrush.core.render.*

class VectorBoardOwnershipTest {
    private var serial = 0
    private fun ids() = "fresh-${++serial}"
    private fun mixed(): JbDocument = JbDocument(id = "doc", name = "Mixed", version = DOC_VERSION,
        boards = listOf(Board("root", "Image", BoardKind.CANVAS, RectPx(0, 0, 256, 256))),
        layers = listOf(Layer("paint", "Paint", LayerKind.PAINT, cels = listOf(Cel("pixels"))),
            Layer("ink", "Lines", LayerKind.PAINT, cels = listOf(Cel("vectors", strokesFile = "ink.bin")))))

    @Test fun mixedLayersShareBoardOwnershipAndRetainLineRecordMetadata() {
        val created = RegionDocumentOps.create(mixed(), "Loop", RectPx(-5, 10, 100, 90), ::ids, animate = null)
        val first = created.doc
        assertTrue(DocOps.validate(first).isEmpty())
        assertEquals(listOf(LayerKind.PAINT, LayerKind.PAINT), first.layers.map { it.kind })
        assertEquals("ink.bin", first.layers.last().cels.first { it.strokesFile != null }.strokesFile)
        // Creation plans raster addresses; line records remain on their cel identities.
        assertEquals(2, created.transfers.size); assertEquals(2, created.clears.size); assertTrue(created.copies.isEmpty())
        val linked = RegionDocumentOps.addFrame(first, first.boards.last().id, NewFrame.LINK, ::ids).doc
        val copied = RegionDocumentOps.addFrame(linked, linked.boards.last().id, NewFrame.DUPLICATE, ::ids)
        assertTrue(DocOps.validate(copied.doc).isEmpty())
        assertEquals(2, copied.copies.size)
        for (layer in linked.layers) {
            val region = layer.regions.single()
            assertEquals(1, region.frameCel.values.toSet().size)
            val plan = RegionDocumentOps.paintPlan(linked, layer.id)
            assertEquals(layer.sharedCelId, plan.planeAt(-6, 10).celId)
            assertEquals(region.frameCel.values.first(), plan.planeAt(-5, 10).celId)
        }
        val held = RegionDocumentOps.setHeld(copied.doc, first.boards.last().id, "ink", true)
        assertTrue(DocOps.validate(held.doc).isEmpty())
        assertTrue(held.copies.isEmpty())
        assertEquals("ink", held.transfers.single().layerId)
        assertTrue(RegionDocumentOps.paintPlan(held.doc, "ink").frames.isEmpty())
        val unheld = RegionDocumentOps.setHeld(held.doc, first.boards.last().id, "ink", false)
        assertTrue(DocOps.validate(unheld.doc).isEmpty())
    }

    @Test fun cloningPreservesLinksAndKindsWithoutAliasingStoredContent() {
        val first = RegionDocumentOps.create(mixed(), "Loop", RectPx(10, 10, 90, 90), ::ids, animate = null).doc
        val linked = RegionDocumentOps.addFrame(first, first.boards.last().id, NewFrame.LINK, ::ids).doc
        for (source in linked.layers) {
            val clone = LayerContentClone.plan(source, "copy-${source.id}", "Copy", ::ids)
            assertEquals(source.kind, clone.layer.kind)
            assertEquals(DocOps.storedCels(source).size, clone.copies.size)
            assertTrue(clone.layer.cels.all { it.tiles.isEmpty() && it.strokesFile == null })
            assertEquals(1, clone.layer.regions.single().frameCel.values.toSet().size)
            assertTrue(clone.layer.cels.none { cel -> source.cels.any { it.id == cel.id } })
            assertTrue(DocOps.validate(linked.copy(layers = linked.layers + clone.layer)).isEmpty())
        }
    }

    @Test fun rasterExportsRefusePaintLineRecordsBeforeReadingAnyPayload() {
        val doc = RegionDocumentOps.create(mixed(), "Loop", RectPx(10, 10, 90, 90), ::ids, animate = null).doc
        assertEquals(LayerKind.PAINT, doc.layers.last().kind)
        assertTrue(doc.layers.last().cels.any { it.strokesFile == "ink.bin" }, "the refused content is v10 PAINT line metadata")
        var reads = 0
        val source = TileSource { _, _, _, _ -> reads++; null }
        assertFailsWith<RegionException> {
            RegionRenderer.render(doc, source, RectPx(0, 0, 1, 1), null, null)
        }
        assertFailsWith<RegionException> {
            RegionRenderer.renderPremultiplied(doc, source, RectPx(0, 0, 1, 1), null, null)
        }
        assertEquals(0, reads)
    }
}
