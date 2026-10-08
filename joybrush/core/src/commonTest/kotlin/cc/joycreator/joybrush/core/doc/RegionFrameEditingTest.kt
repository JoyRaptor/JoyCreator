package cc.joycreator.joybrush.core.doc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RegionFrameEditingTest {
    private var serial = 0
    private fun ids() = "frame-edit-${++serial}"
    private fun animation(): JbDocument {
        val doc = JbDocument(id = "doc", name = "Drawing",
            boards = listOf(Board("root", "Root", BoardKind.CANVAS, RectPx(0, 0, 100, 100))),
            layers = listOf(Layer("layer", "Paint", LayerKind.PAINT, cels = listOf(Cel("shared")))))
        return RegionDocumentOps.create(doc, "Walk", RectPx(-5, 9, 17, 23), ::ids).doc
    }

    @Test fun `linked frame deletion retains shared cel until its last reference is deleted`() {
        val first = animation(); val board = first.boards.last()
        val linked = RegionDocumentOps.addFrame(first, board.id, NewFrame.LINK, ::ids).doc
        val blank = RegionDocumentOps.addFrame(linked, board.id, NewFrame.BLANK, ::ids).doc
        val deleted = RegionDocumentOps.deleteFrame(blank, board.id, board.frames.single().id)
        assertTrue(deleted.drops.isEmpty())
        val linkedId = linked.boards.last().currentFrameId!!
        val lastRef = RegionDocumentOps.deleteFrame(deleted.doc, board.id, linkedId)
        assertEquals(1, lastRef.drops.size)
        assertEquals("shared", lastRef.doc.layers.single().sharedCelId)
        assertTrue(DocOps.validate(lastRef.doc).isEmpty())
        assertFailsWith<DocException> { RegionDocumentOps.deleteFrame(lastRef.doc, board.id, lastRef.doc.boards.last().frames.single().id) }
    }

    @Test fun `delete current chooses adjacent frame without altering another board cursor`() {
        val first = animation(); val a = first.boards.last()
        val two = RegionDocumentOps.create(first, "Other", RectPx(50, 50, 10, 10), ::ids).doc
        val otherCursor = two.boards.last().currentFrameId
        val blank = RegionDocumentOps.addFrame(two, a.id, NewFrame.BLANK, ::ids).doc
        val current = blank.boards.first { it.id == a.id }.currentFrameId!!
        val deleted = RegionDocumentOps.deleteFrame(blank, a.id, current).doc
        assertEquals(a.currentFrameId, deleted.boards.first { it.id == a.id }.currentFrameId)
        assertEquals(otherCursor, deleted.boards.last().currentFrameId)
    }

    @Test fun `reorder validates permutation and preserves cursor mappings and holds`() {
        val first = animation(); val a = first.boards.last()
        val second = RegionDocumentOps.addFrame(first, a.id, NewFrame.BLANK, ::ids).doc
        val frameIds = second.boards.last().frames.map { it.id }
        val reordered = RegionDocumentOps.reorderFrames(second, a.id, frameIds.reversed())
        assertEquals(frameIds.reversed(), reordered.boards.last().frames.map { it.id })
        assertEquals(second.layers, reordered.layers)
        assertEquals(second.boards.last().currentFrameId, reordered.boards.last().currentFrameId)
        assertFailsWith<DocException> { RegionDocumentOps.reorderFrames(second, a.id, listOf(frameIds.first(), frameIds.first())) }
        assertEquals(999, RegionDocumentOps.setHold(second, a.id, frameIds.first(), Int.MAX_VALUE).boards.last().frames.first().holdFrames)
        assertEquals(1, RegionDocumentOps.setHold(second, a.id, frameIds.first(), Int.MIN_VALUE).boards.last().frames.first().holdFrames)
        assertFailsWith<DocException> { RegionDocumentOps.setHold(second, a.id, "missing", 2) }
    }

    @Test fun `held toggle copies current into shared, and un-holding moves it back in board bounds`() {
        val first = animation(); val a = first.boards.last()
        val blank = RegionDocumentOps.addFrame(first, a.id, NewFrame.BLANK, ::ids).doc
        val current = blank.layers.single().regions.single().frameCel.getValue(blank.boards.last().currentFrameId!!)
        val held = RegionDocumentOps.setHeld(blank, a.id, "layer", true)
        assertEquals(listOf(RegionCopy("layer", current, "shared", a.rect)), held.copies)
        assertEquals(blank.layers.single().regions.single().frameCel, held.doc.layers.single().regions.single().frameCel)
        assertTrue(RegionDocumentOps.setHeld(held.doc, a.id, "layer", true).copies.isEmpty())
        val unheld = RegionDocumentOps.setHeld(held.doc, a.id, "layer", false)
        // Un-holding MOVES the shared art under the board into the current frame: nothing hides under a board.
        assertTrue(unheld.copies.isEmpty())
        assertEquals(listOf(RegionTransfer("layer", "shared", current, a.rect, a.rect)), unheld.transfers)
        assertEquals(listOf(RegionClear("layer", "shared", a.rect)), unheld.clears)
        assertTrue(DocOps.validate(unheld.doc).isEmpty())
    }
}
