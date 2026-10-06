package cc.joycreator.joybrush.core.doc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BoardPreviewHistoryTest {
    private var serial = 0
    private fun ids() = "preview-${++serial}"
    private fun fixture(kind: LayerKind = LayerKind.PAINT): JbDocument {
        val base = JbDocument(id = "doc", name = "Drawing",
            boards = listOf(Board("root", "Root", BoardKind.CANVAS, RectPx(0, 0, 100, 100))),
            layers = listOf(Layer("layer", "Content", kind, cels = listOf(Cel("shared")))))
        val first = RegionDocumentOps.create(base, "A", RectPx(0, 0, 10, 10), ::ids).doc
        val a = first.boards.last().id
        val linked = RegionDocumentOps.addFrame(first, a, NewFrame.LINK, ::ids).doc
        val second = RegionDocumentOps.create(linked, "B", RectPx(20, 0, 10, 10), ::ids).doc
        return RegionDocumentOps.addFrame(second, second.boards.last().id, NewFrame.BLANK, ::ids).doc
    }

    @Test fun `transient override preserves other saved cursors and linked ownership`() {
        val doc = fixture(); val a = doc.boards[1]; val b = doc.boards[2]
        val saved = DocJson.encode(doc)
        val original = RegionDocumentOps.paintPlan(doc, "layer")
        val preview = RegionDocumentOps.paintPlan(doc, "layer", mapOf(a.id to a.frames.first().id))
        assertEquals(a.frames.first().id, preview.frames.first { it.boardId == a.id }.frameId)
        assertEquals(original.frames.first { it.boardId == a.id }.celId, preview.frames.first { it.boardId == a.id }.celId)
        assertEquals(original.frames.first { it.boardId == b.id }, preview.frames.first { it.boardId == b.id })
        assertEquals(saved, DocJson.encode(doc))
        assertEquals(original.frames, RegionDocumentOps.paintPlan(doc, "layer", emptyMap()).frames)
    }

    @Test fun `overrides validate even held boards and keep held content shared for INK`() {
        val doc = fixture(LayerKind.INK); val a = doc.boards[1]; val b = doc.boards[2]
        val held = RegionDocumentOps.setHeld(doc, a.id, "layer", true).doc
        val preview = RegionDocumentOps.paintPlan(held, "layer", mapOf(a.id to a.frames.first().id, b.id to b.frames.first().id))
        assertEquals("shared", preview.planeAt(1, 1).celId)
        assertEquals(b.frames.first().id, preview.frames.single().frameId)
        assertFailsWith<DocException> { RegionDocumentOps.paintPlan(held, "layer", mapOf(a.id to b.frames.first().id)) }
        assertFailsWith<DocException> { RegionDocumentOps.paintPlan(held, "layer", mapOf("missing" to a.frames.first().id)) }
        assertFailsWith<DocException> { RegionDocumentOps.paintPlan(held, "layer", mapOf("root" to a.frames.first().id)) }
        assertFailsWith<DocException> { RegionDocumentOps.paintPlan(held, "missing", emptyMap()) }
    }

    @Test fun `bulk preview returns complete mixed content ownership without saved edits`() {
        val paint = fixture()
        val ink = paint.layers.single().copy(id = "ink", kind = LayerKind.INK,
            sharedCelId = "ink-shared", cels = paint.layers.single().cels.map { it.copy(id = "ink-${it.id}") },
            regions = paint.layers.single().regions.map { r -> r.copy(frameCel = r.frameCel.mapValues { "ink-${it.value}" }) })
        // The original shared cel is called shared; make its prefixed identity agree as well.
        val mixed = paint.copy(layers = paint.layers + ink)
        val overrides = mixed.boards.filter { it.kind == BoardKind.ANIMATION }
            .associate { it.id to it.frames.first().id }
        val saved = DocJson.encode(mixed)
        val plans = RegionDocumentOps.paintPlans(mixed, overrides)
        assertEquals(mixed.layers.map { it.id }.toSet(), plans.keys)
        mixed.layers.forEach { layer ->
            assertEquals(RegionDocumentOps.paintPlan(mixed, layer.id, overrides).frames, plans.getValue(layer.id).frames)
        }
        assertEquals(saved, DocJson.encode(mixed))
        assertFailsWith<DocException> { RegionDocumentOps.paintPlans(mixed, mapOf("root" to "missing")) }
    }

    @Test fun `unrelated metadata undo and redo retain later navigation independently per board`() {
        val before = fixture(); val a = before.boards[1]; val b = before.boards[2]
        val after = before.copy(layers = before.layers.map { it.copy(name = "Renamed") })
        val live = RegionDocumentOps.selectFrame(RegionDocumentOps.selectFrame(after, a.id, a.frames.first().id), b.id, b.frames.first().id)
        val undone = BoardHistory.restore(before, after, live)
        assertEquals(a.frames.first().id, undone.boards[1].currentFrameId)
        assertEquals(b.frames.first().id, undone.boards[2].currentFrameId)
        assertEquals(before.layers, undone.layers)
        val redone = BoardHistory.restore(after, before, undone)
        assertEquals(undone.boards, redone.boards)
        assertEquals(after.layers, redone.layers)
    }

    @Test fun `cursor-changing operation restores target rather than newer live navigation`() {
        val before = fixture(); val a = before.boards[1]
        val after = RegionDocumentOps.selectFrame(before, a.id, a.frames.first().id)
        val live = RegionDocumentOps.selectFrame(after, a.id, a.frames.last().id)
        assertEquals(a.frames.last().id, BoardHistory.restore(before, after, live).boards[1].currentFrameId)
        assertEquals(a.frames.first().id, BoardHistory.restore(after, before, live).boards[1].currentFrameId)
    }

    @Test fun `removed live frame never survives restoration even when history cursor did not change`() {
        val original = fixture(); val b = original.boards[2]
        val selected = RegionDocumentOps.selectFrame(original, b.id, b.frames.first().id)
        val target = RegionDocumentOps.deleteFrame(selected, b.id, b.frames.last().id).doc
        val live = RegionDocumentOps.selectFrame(original, b.id, b.frames.last().id)
        val restored = BoardHistory.restore(target, selected, live)
        assertEquals(b.frames.first().id, restored.boards[2].currentFrameId)
        assertTrue(DocOps.validate(restored).isEmpty())
        assertFailsWith<DocException> { BoardHistory.restore(target, selected, live.copy(id = "other")) }
    }
}
