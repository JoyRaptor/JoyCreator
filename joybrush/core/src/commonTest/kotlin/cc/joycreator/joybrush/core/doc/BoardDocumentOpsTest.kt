package cc.joycreator.joybrush.core.doc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoardDocumentOpsTest {
    private var serial = 0
    private fun ids() = "board-edit-${++serial}"
    private fun base() = JbDocument(id = "doc", name = "Drawing",
        boards = listOf(Board("root", "Root", BoardKind.CANVAS, RectPx(0, 0, 100, 100))),
        layers = listOf(Layer("layer", "Paint", LayerKind.PAINT, cels = listOf(Cel("shared")))))

    @Test fun `sprite grid fits whole cells at the same origin and keeps every content address`() {
        val source = BoardDocumentOps.createSprite(base(),"Sheet",RectPx(-9,7,100,50),SpriteGrid(4,2,25,25),::ids).doc
        val id = source.boards.last().id
        val grid = cc.joycreator.joybrush.core.sprite.SpriteGridMath.byCount(source.boards.last().rect,3,2)
        val changed = BoardDocumentOps.setSpriteGrid(source,id,grid)
        assertEquals(RectPx(-9,7,99,50),changed.boards.last().rect)
        assertEquals(grid,changed.boards.last().grid)
        assertEquals(source.layers,changed.layers)
        assertEquals(source.boards.first(),changed.boards.first())
        assertTrue(DocOps.validate(changed).isEmpty())
        assertEquals(changed,DocJson.decode(DocJson.encode(changed)))
    }
    @Test fun `grid edits refuse locked wrong kind invalid cells and overflow before publishing`() {
        val source = BoardDocumentOps.createSprite(base(),"Sheet",RectPx(Int.MAX_VALUE-20,7,20,20),SpriteGrid(1,1,20,20),::ids).doc
        val id = source.boards.last().id
        assertFailsWith<DocException> { BoardDocumentOps.setSpriteGrid(BoardDocumentOps.setLocked(source,id,true),id,SpriteGrid(2,1,10,20)) }
        assertFailsWith<DocException> { BoardDocumentOps.setSpriteGrid(source,"root",SpriteGrid(1,1,1,1)) }
        assertFailsWith<DocException> { BoardDocumentOps.setSpriteGrid(source,id,SpriteGrid(0,1,1,1)) }
        assertFailsWith<DocException> { BoardDocumentOps.setSpriteGrid(source,id,SpriteGrid(2,1,20,20)) }
        assertEquals(RectPx(Int.MAX_VALUE-20,7,20,20),source.boards.last().rect)
    }

    @Test fun `passive creation and duplication keep all paint and number names`() {
        val source = base()
        val first = BoardDocumentOps.createImage(source, "Hero", RectPx(-5, 8, 20, 30), ::ids)
        val second = BoardDocumentOps.duplicatePassive(first.doc, first.doc.activeBoardId!!, ::ids)
        val third = BoardDocumentOps.duplicatePassive(second.doc, first.doc.activeBoardId!!, ::ids)
        assertEquals(listOf("Root", "Hero", "Hero 2", "Hero 3"), third.doc.boards.map { it.name })
        assertEquals(source.layers, third.doc.layers)
        assertTrue(first.copies.isEmpty())
        assertTrue(DocOps.validate(third.doc).isEmpty())
        assertEquals(1, source.boards.size)
    }

    @Test fun `typed resize keeps top left and lock blocks geometry not selection`() {
        val source = base()
        val resized = BoardDocumentOps.resizeTyped(source, "root", 201, 77)
        assertEquals(RectPx(0, 0, 201, 77), resized.boards.single().rect)
        val locked = BoardDocumentOps.setLocked(resized, "root", true)
        assertFailsWith<DocException> { BoardDocumentOps.move(locked, "root", 2, 3) }
        assertFailsWith<DocException> { BoardDocumentOps.resizeTyped(locked, "root", 1, 1) }
        assertEquals("root", BoardDocumentOps.select(locked, "root").activeBoardId)
        assertEquals("Renamed", BoardDocumentOps.rename(locked, "root", " Renamed ").boards.single().name)
    }

    @Test fun `sprite dimensions are whole cells and invalid extents never publish`() {
        val sprite = BoardDocumentOps.createSprite(base(), "Sheet", RectPx(4, 7, 32, 48), SpriteGrid(2, 3, 16, 16), ::ids)
        assertFailsWith<DocException> { BoardDocumentOps.resizeTyped(sprite.doc, sprite.doc.activeBoardId!!, 31, 48) }
        assertEquals(4, BoardDocumentOps.resizeTyped(sprite.doc, sprite.doc.activeBoardId!!, 64, 48).boards.last().grid!!.cols)
        assertFailsWith<DocException> { BoardDocumentOps.createImage(base(), "Bad", RectPx(Int.MAX_VALUE, 0, 2, 1), ::ids) }
        assertFailsWith<DocException> { BoardDocumentOps.createImage(base(), "Bad", RectPx(0, 0, 0, 1), ::ids) }
        assertFailsWith<DocException> { BoardDocumentOps.createImage(base(), "Bad", RectPx(0, 0, 1, 1)) { "shared" } }
    }

    @Test fun `removing passive board preserves root and paint`() {
        val added = BoardDocumentOps.createSprite(base(), "Sheet", RectPx(0, 0, 8, 8), SpriteGrid(1, 1, 8, 8), ::ids).doc
        assertFailsWith<DocException> { BoardDocumentOps.remove(added, "root") }
        val removed = BoardDocumentOps.remove(added, added.activeBoardId!!)
        assertEquals(base().boards, removed.boards)
        assertEquals(base().layers, removed.layers)
        assertEquals("root", removed.activeBoardId)
    }

    @Test fun `session selection stays passive and only wrapped stroke saves tile icon`() {
        val added = BoardDocumentOps.createSprite(base(), "Sheet", RectPx(0, 0, 8, 8), SpriteGrid(1, 1, 8, 8), ::ids).doc
        val session = BoardSession().arm(added, "root").select(added, added.activeBoardId).select(added, null)
        assertEquals("root", session.armedBoardId)
        assertEquals(null, session.selectedBoardId)
        assertFalse(added.boards.first().tiled)
        assertTrue(BoardDocumentOps.markWrappedStroke(added, "root").boards.first().tiled)
        assertEquals(null, session.arm(added, null).armedBoardId)
        assertEquals(BoardSession(), BoardSession(added.activeBoardId, added.activeBoardId).reconcile(base()))
    }

    @Test fun `animation geometry and duplicate refuse rather than pretend to move pixels`() {
        val anim = BoardDocumentOps.createAnimation(base(), "Walk", RectPx(5, 7, 10, 10), ::ids).doc
        val id = anim.activeBoardId!!
        assertFailsWith<DocException> { BoardDocumentOps.move(anim, id, 30, 30) }
        assertFailsWith<DocException> { BoardDocumentOps.resizeTyped(anim, id, 3, 3) }
        assertFailsWith<DocException> { BoardDocumentOps.duplicatePassive(anim, id, ::ids) }
        assertFailsWith<DocException> { BoardDocumentOps.remove(anim, id) }
        assertFailsWith<DocException> { BoardSession().arm(anim, id) }
        assertFailsWith<DocException> { BoardDocumentOps.createAnimation(anim, "Overlap", RectPx(14, 16, 3, 3), ::ids) }
    }
}
