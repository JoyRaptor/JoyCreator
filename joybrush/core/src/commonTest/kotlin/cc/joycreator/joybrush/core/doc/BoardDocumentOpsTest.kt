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
        val snapped = BoardDocumentOps.resizeTyped(sprite.doc, sprite.doc.activeBoardId!!, 31, 48).boards.last()
        assertEquals(RectPx(4,7,30,48),snapped.rect)
        assertEquals(SpriteGrid(2,3,15,16),snapped.grid)
        assertEquals(SpriteGrid(2,3,32,16), BoardDocumentOps.resizeTyped(sprite.doc, sprite.doc.activeBoardId!!, 64, 48).boards.last().grid)
        assertFailsWith<DocException> { BoardDocumentOps.createImage(base(), "Bad", RectPx(Int.MAX_VALUE, 0, 2, 1), ::ids) }
        assertFailsWith<DocException> { BoardDocumentOps.createImage(base(), "Bad", RectPx(0, 0, 0, 1), ::ids) }
        assertFailsWith<DocException> { BoardDocumentOps.createImage(base(), "Bad", RectPx(0, 0, 1, 1)) { "shared" } }
    }

    @Test fun `sprite board resize keeps count and snaps nearest cell size with typed parity`() {
        val before = BoardDocumentOps.createSprite(base(),"Sheet",RectPx(-10,7,96,60),SpriteGrid(3,2,32,30),::ids).doc
        val id = before.activeBoardId!!
        val drag = BoardDocumentOps.resize(before,id,RectPx(-10,7,101,65))
        assertEquals(SpriteGrid(3,2,34,33),drag.boards.last().grid)
        assertEquals(RectPx(-10,7,102,66),drag.boards.last().rect)
        assertEquals(drag,BoardDocumentOps.resizeTyped(before,id,101,65))
        assertEquals(RectPx(-10,7,99,64),BoardDocumentOps.resizeTyped(drag,id,100,65).boards.last().rect)
        assertEquals(before.layers,drag.layers)
        assertTrue(DocOps.validate(drag).isEmpty())
    }

    @Test fun `left and top sprite handles preserve opposite edges after snapping`() {
        val before = BoardDocumentOps.createSprite(base(),"Sheet",RectPx(-10,7,96,60),SpriteGrid(3,2,32,30),::ids).doc
        val id = before.activeBoardId!!
        val corner = BoardDocumentOps.resize(before,id,RectPx(-15,2,101,65)).boards.last()
        assertEquals(RectPx(-16,1,102,66),corner.rect)
        assertEquals(86,corner.rect.x+corner.rect.w)
        assertEquals(67,corner.rect.y+corner.rect.h)
        val left = BoardDocumentOps.resize(before,id,RectPx(-15,7,101,60)).boards.last()
        assertEquals(RectPx(-16,7,102,60),left.rect)
        val top = BoardDocumentOps.resize(before,id,RectPx(-10,2,96,65)).boards.last()
        assertEquals(RectPx(-10,1,96,66),top.rect)
        // Moving both edges explicitly uses the requested origin, rather than inventing an anchor.
        assertEquals(RectPx(50,60,102,66),BoardDocumentOps.resize(before,id,RectPx(50,60,101,65)).boards.last().rect)
    }

    @Test fun `sprite resize uses existing minimum maximum and signed half step rules`() {
        val before = BoardDocumentOps.createSprite(base(),"Sheet",RectPx(0,0,40,40),SpriteGrid(4,4,10,10),::ids).doc
        val id = before.activeBoardId!!
        assertEquals(SpriteGrid(4,4,11,11),BoardDocumentOps.resizeTyped(before,id,42,42).boards.last().grid)
        assertEquals(SpriteGrid(4,4,9,9),BoardDocumentOps.resizeTyped(before,id,38,38).boards.last().grid)
        assertEquals(RectPx(0,0,4,4),BoardDocumentOps.resizeTyped(before,id,1,1).boards.last().rect)
        val cap = cc.joycreator.joybrush.core.sprite.SpriteGridMath.MAX_CELL_PX
        assertEquals(SpriteGrid(4,4,cap,cap),BoardDocumentOps.resizeTyped(before,id,Int.MAX_VALUE,Int.MAX_VALUE).boards.last().grid)
        assertFailsWith<DocException> { BoardDocumentOps.resizeTyped(before,id,0,10) }
    }

    @Test fun `snapping refuses both positive edge overflow and negative anchor overflow`() {
        val high = BoardDocumentOps.createSprite(base(),"High",RectPx(Int.MAX_VALUE-101,0,99,10),SpriteGrid(3,1,33,10),::ids).doc
        assertFailsWith<DocException> { BoardDocumentOps.resizeTyped(high,high.activeBoardId!!,101,10) }
        val low = BoardDocumentOps.createSprite(base(),"Low",RectPx(Int.MIN_VALUE+1,0,100,10),SpriteGrid(2,1,50,10),::ids).doc
        assertFailsWith<DocException> { BoardDocumentOps.resize(low,low.activeBoardId!!,RectPx(Int.MIN_VALUE,0,101,10)) }
        val locked = BoardDocumentOps.setLocked(low,low.activeBoardId!!,true)
        assertFailsWith<DocException> { BoardDocumentOps.resize(locked,locked.activeBoardId!!,RectPx(0,0,200,20)) }
        assertEquals(RectPx(Int.MIN_VALUE+1,0,100,10),low.boards.last().rect)
    }

    @Test fun `sprite resize is one reversible metadata value preserving paint ink masks and other boards`() {
        val content = base().copy(layers = listOf(
            Layer("paint","Paint",LayerKind.PAINT,cels=listOf(Cel("paint-cel",tiles=listOf("-1_0"))),mask=Cel("mask",tiles=listOf("0_0"))),
            Layer("ink","Ink",LayerKind.INK,cels=listOf(Cel("ink-cel",strokesFile="strokes/ink.bin")))))
        val before = BoardDocumentOps.createSprite(content,"Sheet",RectPx(0,0,40,60),SpriteGrid(2,3,20,20),::ids).doc
        val after = BoardDocumentOps.resize(before,before.activeBoardId!!,RectPx(0,0,63,76))
        assertEquals(before.layers,after.layers)
        assertEquals(before.boards.first(),after.boards.first())
        val change = RegionChange(after)
        assertTrue(change.copies.isEmpty()); assertTrue(change.drops.isEmpty())
        val undone = BoardHistory.restore(before,after,after)
        assertEquals(before,undone)
        assertEquals(after,BoardHistory.restore(after,before,undone))
        assertEquals(after,DocJson.decode(DocJson.encode(after)))
        assertEquals(RectPx(2,3,17,19),BoardDocumentOps.resize(base(),"root",RectPx(2,3,17,19)).boards.single().rect)
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
        assertFailsWith<DocException> { BoardDocumentOps.resize(anim, id, RectPx(30,30,3,3)) }
        assertFailsWith<DocException> { BoardDocumentOps.duplicatePassive(anim, id, ::ids) }
        assertFailsWith<DocException> { BoardDocumentOps.remove(anim, id) }
        assertFailsWith<DocException> { BoardSession().arm(anim, id) }
        assertFailsWith<DocException> { BoardDocumentOps.createAnimation(anim, "Overlap", RectPx(14, 16, 3, 3), ::ids) }
    }
}
