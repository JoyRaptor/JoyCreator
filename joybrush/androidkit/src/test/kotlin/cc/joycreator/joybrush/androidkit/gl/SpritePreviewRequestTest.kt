package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.core.doc.*
import kotlin.test.*

class SpritePreviewRequestTest {
    private var serial = 0
    private fun ids() = "sprite-preview-${serial++}"
    private fun document(): JbDocument = BoardDocumentOps.createSprite(
        DocOps.newDocument("preview", "Test", 100, 100, ::ids), "Sprites",
        RectPx(-20, 30, 60, 40), SpriteGrid(3, 2, 20, 20), ::ids,
    ).doc

    @Test fun cropsNonzeroNegativeOriginsAndDeduplicatesCells() {
        val doc = document()
        val request = SpritePreviewRequest(doc, doc.boards.last().id, listOf(5, 0, 5), 80, 60, 9)
        assertEquals(listOf(5, 0), request.bounds.keys.toList())
        assertEquals(RectPx(20, 50, 20, 20), request.bounds[5])
        assertEquals(RectPx(-20, 30, 20, 20), request.bounds[0])
        assertTrue(request.matches(doc, 9))
        assertFalse(request.matches(doc, 10), "New paint must invalidate the old pictures")
    }

    @Test fun rejectsChangedGeometryRemovedBoardsAndReplacementDocuments() {
        val doc = document()
        val board = doc.boards.last()
        val request = SpritePreviewRequest(doc, board.id, listOf(0), 80, 60, 9)
        assertFalse(request.matches(null, 9))
        assertFalse(request.matches(doc.copy(id = "replacement"), 9))
        assertFalse(request.matches(doc.copy(boards = doc.boards.dropLast(1)), 9))
        assertFalse(request.matches(doc.copy(boards = doc.boards.dropLast(1) + board.copy(
            grid = SpriteGrid(2, 2, 30, 20))), 9))
    }

    @Test fun neighboringAnimationCursorAndHeldOwnershipArePartOfTheSceneIdentity() {
        var doc = BoardDocumentOps.createAnimation(document(), "Neighbor", RectPx(-5, 35, 20, 20), ::ids).doc
        val animation = doc.boards.last()
        doc = RegionDocumentOps.addFrame(doc, animation.id, NewFrame.BLANK, ::ids).doc
        val sprite = doc.boards.first { it.kind == BoardKind.SPRITE }
        val request = SpritePreviewRequest(doc, sprite.id, listOf(0), 80, 60, 9)
        val animationNow = doc.boards.last()
        val other = animationNow.frames.first { it.id != animationNow.currentFrameId }
        assertFalse(request.matches(RegionDocumentOps.selectFrame(doc, animationNow.id, other.id), 9))
        assertFalse(request.matches(RegionDocumentOps.setHeld(doc, animationNow.id, doc.layers.first().id, true).doc, 9))
    }

    @Test fun capsRequestsBeforeAnyGraphicsAllocationAndRefusesNonSpriteCells() {
        val doc = document(); val board = doc.boards.last()
        assertFailsWith<IllegalArgumentException> { SpritePreviewRequest(doc, board.id, List(17) { 0 }, 80, 60, 0) }
        assertFailsWith<IllegalArgumentException> { SpritePreviewRequest(doc, board.id, listOf(0), 257, 60, 0) }
        assertFailsWith<IllegalArgumentException> { SpritePreviewRequest(doc, board.id, listOf(0), 80, 0, 0) }
        assertFailsWith<IllegalArgumentException> { SpritePreviewRequest(doc, board.id, listOf(6), 80, 60, 0) }
        assertFailsWith<IllegalArgumentException> { SpritePreviewRequest(doc, doc.boards.first().id, listOf(0), 80, 60, 0) }
    }
}
