package cc.joycreator.joybrush.core.doc

import kotlin.test.*

class BoardChangePaintTest {
    private var serial = 0
    private fun ids() = "paint-change-${++serial}"
    private fun source(): JbDocument {
        val base = JbDocument(id = "drawing", name = "Test",
            boards = listOf(Board("image", "Image", BoardKind.CANVAS, RectPx(-100, -100, 200, 200))),
            layers = listOf(Layer("paint", "Paint", LayerKind.PAINT, cels = listOf(Cel("shared")), mask = Cel("mask"))))
        val one = RegionDocumentOps.create(base, "Animation", RectPx(-20, -10, 40, 30), ::ids).doc
        return RegionDocumentOps.addFrame(one, one.activeBoardId!!, NewFrame.BLANK, ::ids).doc
    }
    private fun live(doc: JbDocument, previews: Map<String, String> = emptyMap()) =
        RegionDocumentOps.paintPlans(doc, previews).mapValues { it.value.frames }
    private fun writes(doc: JbDocument, next: JbDocument) = BoardChangePaint.writes(RegionChange(next), live(doc), emptyMap())

    @Test fun aNewNameALockOrAFramesHoldLeavesTheWashWet() {
        val doc = source(); val id = doc.activeBoardId!!
        assertFalse(writes(doc, doc), "nothing changed")
        assertFalse(writes(doc, BoardDocumentOps.rename(doc, id, "Walk cycle")), "a new name")
        assertFalse(writes(doc, BoardDocumentOps.setLocked(doc, id, true)), "a lock")
        val frame = doc.boards.first { it.id == id }.frames.first().id
        assertFalse(writes(doc, RegionDocumentOps.setHold(doc, id, frame, 3)), "a frame held longer")
    }

    @Test fun anEditThatMovesAFrameOrShowsAnotherSetsIt() {
        val doc = source(); val id = doc.activeBoardId!!
        val board = doc.boards.first { it.id == id }
        val other = board.frames.first { it.id != board.currentFrameId }.id
        assertTrue(writes(doc, RegionDocumentOps.selectFrame(doc, id, other)), "another frame shows")
        val moved = AnimationBoardOps.moveAll(doc, id, 4, -1).doc
        assertTrue(BoardChangePaint.writes(RegionChange(moved), live(doc), emptyMap()), "the board lies elsewhere")
    }

    @Test fun anEditThatMovesPixelsSetsItWhateverTheFramesDo() {
        val doc = source(); val id = doc.activeBoardId!!
        val resize = AnimationBoardOps.resize(doc, id, RectPx(-10, -10, 40, 30))
        assertTrue(BoardChangePaint.writes(resize, live(doc), emptyMap()))
        val added = RegionDocumentOps.addFrame(doc, id, NewFrame.BLANK, ::ids)
        assertTrue(BoardChangePaint.writes(added, live(doc), emptyMap()), "a new frame shows instead of the one painted on")
    }

    @Test fun inDoubtItSets() {
        val doc = source(); val id = doc.activeBoardId!!
        assertTrue(BoardChangePaint.writes(RegionChange(doc), live(doc) + ("gone" to emptyList()), emptyMap()), "a layer the document does not have")
        assertTrue(BoardChangePaint.writes(RegionChange(doc), live(doc).mapValues { null }, emptyMap()), "a layer with no plan yet")
        assertTrue(BoardChangePaint.writes(RegionChange(doc), live(doc), mapOf(id to "no-such-frame")), "a preview the document cannot keep")
    }
}
