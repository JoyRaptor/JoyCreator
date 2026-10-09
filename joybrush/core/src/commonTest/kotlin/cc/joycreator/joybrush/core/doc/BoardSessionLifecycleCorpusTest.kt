package cc.joycreator.joybrush.core.doc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * JB-NOW-14 board selection/arming lifecycle corpus (repro JB14-S01..S12).
 *
 * Exercises the real [BoardSession.select]/[BoardSession.arm]/[BoardSession.reconcile]
 * against Image (CANVAS), Tile-as-CANVAS (CANVAS with [Board.tiled]), Sprite and
 * Animation boards: null selections, missing IDs, deletion, kind changes, lock
 * independence, selected-armed divergence, short deterministic transition sequences
 * and invalid documents (exact refusal type [DocException]).
 *
 * Lock/arm model per locked JB-3.00a B2: lock is a document property of the canvas,
 * arm is an ephemeral feature toggle; Tile is not a kind of its own. Core semantics
 * only, not UI preview proof.
 */
class BoardSessionLifecycleCorpusTest {
    private fun base() = JbDocument(id = "doc", name = "Drawing",
        boards = listOf(Board("root", "Root", BoardKind.CANVAS, RectPx(0, 0, 100, 100))),
        layers = listOf(Layer("layer", "Paint", LayerKind.PAINT, cels = listOf(Cel("shared")))))

    private fun fixedIds(vararg ids: String): () -> String {
        val at = ids.iterator()
        return { at.next() }
    }

    private fun twoBoard(): JbDocument =
        BoardDocumentOps.createSprite(base(), "Sheet", RectPx(0, 0, 8, 8), SpriteGrid(1, 1, 8, 8),
            fixedIds("sprite-1")).doc

    private fun mixedDoc(): JbDocument =
        BoardDocumentOps.createAnimation(twoBoard(), "Walk", RectPx(200, 0, 16, 16),
            fixedIds("anim-1", "anim-f1", "anim-c1")).doc

    @Test fun `S01 select accepts Image Sprite Animation and null and leaves the document untouched`() {
        val doc = mixedDoc()
        val snapshot = doc
        assertEquals(BoardSession(selectedBoardId = "root"), BoardSession().select(doc, "root"))
        assertEquals(BoardSession(selectedBoardId = "sprite-1"), BoardSession().select(doc, "sprite-1"))
        assertEquals(BoardSession(selectedBoardId = "anim-1"), BoardSession().select(doc, "anim-1"))
        assertEquals(BoardSession(), BoardSession(selectedBoardId = "anim-1").select(doc, null))
        assertEquals(snapshot, doc)
        assertTrue(DocOps.validate(doc).isEmpty())
    }

    @Test fun `S02 session selection is ephemeral while document selection moves the saved cursor`() {
        val doc = base()
        assertEquals(null, doc.activeBoardId)
        val session = BoardSession().select(doc, "root")
        assertEquals("root", session.selectedBoardId)
        assertEquals(null, doc.activeBoardId)
        val moved = BoardDocumentOps.select(doc, "root")
        assertEquals("root", moved.activeBoardId)
        assertEquals(doc.layers, moved.layers)
        assertEquals(doc.boards, moved.boards)
    }

    @Test fun `S03 arming accepts plain Image and Tile-as-CANVAS without changing saved tiling`() {
        val doc = twoBoard()
        assertEquals(BoardSession(armedBoardId = "root"), BoardSession().arm(doc, "root"))
        val tiled = BoardDocumentOps.markWrappedStroke(doc, "root")
        assertTrue(tiled.boards.first { it.id == "root" }.tiled)
        assertFalse(doc.boards.first { it.id == "root" }.tiled)
        val armedTile = BoardSession().arm(tiled, "root")
        assertEquals(BoardSession(armedBoardId = "root"), armedTile)
        assertEquals(BoardSession(armedBoardId = "root"), armedTile.reconcile(tiled))
        assertEquals(doc.layers, tiled.layers)
    }

    @Test fun `S04 arming accepts Sprite and null clears only the armed pointer`() {
        val doc = twoBoard()
        assertEquals(BoardSession(armedBoardId = "sprite-1"), BoardSession().arm(doc, "sprite-1"))
        assertEquals(BoardSession(selectedBoardId = "root", armedBoardId = null),
            BoardSession(selectedBoardId = "root", armedBoardId = "sprite-1").arm(doc, null))
        assertEquals(BoardSession(selectedBoardId = "sprite-1", armedBoardId = "sprite-1"),
            BoardSession(selectedBoardId = "sprite-1").arm(doc, "sprite-1"))
    }

    @Test fun `S05 arming refuses Animation Puppet and Character with the exact kind refusal`() {
        val doc = mixedDoc()
        val animErr = assertFailsWith<DocException> { BoardSession().arm(doc, "anim-1") }
        assertEquals("Only Image tiling and Sprite rearrangement can be armed", animErr.message)
        val odd = twoBoard().copy(boards = twoBoard().boards + listOf(
            Board("pup-1", "Pup", BoardKind.PUPPET, RectPx(300, 300, 20, 20)),
            Board("char-1", "Char", BoardKind.CHARACTER, RectPx(400, 400, 20, 20))))
        assertTrue(DocOps.validate(odd).isEmpty())
        for (id in listOf("pup-1", "char-1")) {
            val err = assertFailsWith<DocException> { BoardSession().arm(odd, id) }
            assertEquals("Only Image tiling and Sprite rearrangement can be armed", err.message)
            assertEquals(id, BoardSession().select(odd, id).selectedBoardId)
        }
        val lockedAnim = BoardDocumentOps.setLocked(doc, "anim-1", true)
        val lockedErr = assertFailsWith<DocException> { BoardSession().arm(lockedAnim, "anim-1") }
        assertEquals("Only Image tiling and Sprite rearrangement can be armed", lockedErr.message)
    }

    @Test fun `S06 selecting or arming a missing board refuses with no such board`() {
        val doc = twoBoard()
        val selectErr = assertFailsWith<DocException> { BoardSession().select(doc, "missing-board") }
        assertEquals("No such board", selectErr.message)
        val armErr = assertFailsWith<DocException> { BoardSession().arm(doc, "missing-board") }
        assertEquals("No such board", armErr.message)
    }

    @Test fun `S07 lock is independent of ephemeral selection and arming`() {
        val doc = twoBoard()
        val locked = BoardDocumentOps.setLocked(BoardDocumentOps.setLocked(doc, "root", true), "sprite-1", true)
        assertEquals(BoardSession("root", "root"), BoardSession().select(locked, "root").arm(locked, "root"))
        assertEquals(BoardSession("sprite-1", "sprite-1"),
            BoardSession().select(locked, "sprite-1").arm(locked, "sprite-1"))
        assertTrue(locked.boards.first { it.id == "root" }.locked)
        assertTrue(locked.boards.first { it.id == "sprite-1" }.locked)
        assertEquals(doc.layers, locked.layers)
        assertFailsWith<DocException> { BoardDocumentOps.move(locked, "root", 1, 1) }
    }

    @Test fun `S08 selected board can differ from armed board in either direction`() {
        val doc = twoBoard()
        val first = BoardSession().select(doc, "root").arm(doc, "sprite-1")
        assertEquals("root", first.selectedBoardId)
        assertEquals("sprite-1", first.armedBoardId)
        val flipped = BoardSession().select(doc, "sprite-1").arm(doc, "root")
        assertEquals("sprite-1", flipped.selectedBoardId)
        assertEquals("root", flipped.armedBoardId)
    }

    @Test fun `S09 deterministic transition sequence preserves document values`() {
        val doc = mixedDoc()
        assertEquals(mixedDoc(), doc)
        val s1 = BoardSession().select(doc, "root")
        val s2 = s1.arm(doc, "sprite-1")
        val s3 = s2.select(doc, "anim-1")
        val s4 = s3.arm(doc, null)
        val s5 = s4.reconcile(doc)
        assertEquals(BoardSession("root", null), s1)
        assertEquals(BoardSession("root", "sprite-1"), s2)
        assertEquals(BoardSession("anim-1", "sprite-1"), s3)
        assertEquals(BoardSession("anim-1", null), s4)
        assertEquals(BoardSession("anim-1", null), s5)
        assertEquals(mixedDoc().boards, doc.boards)
        assertEquals(mixedDoc().layers, doc.layers)
        assertTrue(DocOps.validate(doc).isEmpty())
    }

    @Test fun `S10 deletion drops only the stale pointer and guards last canvas and animation`() {
        val doc = twoBoard()
        val session = BoardSession(selectedBoardId = "sprite-1", armedBoardId = "sprite-1")
        val withoutSprite = BoardDocumentOps.remove(doc, "sprite-1")
        assertEquals(BoardSession(null, null), session.reconcile(withoutSprite))
        assertEquals(listOf("root"), withoutSprite.boards.map { it.id })
        val hero = BoardDocumentOps.createImage(base(), "Hero", RectPx(40, 40, 20, 20), fixedIds("hero-1")).doc
        val heroSession = BoardSession(selectedBoardId = "hero-1", armedBoardId = "root")
        val withoutHero = BoardDocumentOps.remove(hero, "hero-1")
        assertEquals(BoardSession(null, "root"), heroSession.reconcile(withoutHero))
        val mixed = mixedDoc()
        assertEquals(BoardSession("anim-1", "root"),
            BoardSession("anim-1", "root").reconcile(BoardDocumentOps.remove(mixed, "sprite-1")))
        assertFailsWith<DocException> { BoardDocumentOps.remove(mixed, "anim-1") }
        assertFailsWith<DocException> { BoardDocumentOps.remove(doc, "root") }
        assertFailsWith<DocException> { BoardDocumentOps.remove(base(), "root") }
    }

    @Test fun `S11 kind changes drop armed but keep live selected per actual reconcile rules`() {
        val doc = twoBoard()
        val session = BoardSession("sprite-1", "sprite-1")
        val toAnim = doc.copy(boards = doc.boards.map { board ->
            if (board.id == "sprite-1") board.copy(kind = BoardKind.ANIMATION, grid = null,
                frames = listOf(Frame("kf-1")), currentFrameId = "kf-1") else board
        })
        assertTrue(DocOps.validate(toAnim).isEmpty())
        assertEquals(BoardSession("sprite-1", null), session.reconcile(toAnim))
        val toPuppet = doc.copy(boards = doc.boards.map { board ->
            if (board.id == "sprite-1") board.copy(kind = BoardKind.PUPPET, grid = null) else board
        })
        assertTrue(DocOps.validate(toPuppet).isEmpty())
        assertEquals(BoardSession("sprite-1", null), session.reconcile(toPuppet))
        val rootToSprite = doc.copy(boards = doc.boards.map { board ->
            if (board.id == "root") board.copy(kind = BoardKind.SPRITE, grid = SpriteGrid(4, 4, 25, 25)) else board
        })
        assertTrue(DocOps.validate(rootToSprite).isEmpty())
        assertEquals(BoardSession("root", "root"), BoardSession("root", "root").reconcile(rootToSprite))
        assertEquals(BoardSession("anim-1", null), BoardSession("anim-1", "anim-1").reconcile(mixedDoc()))
    }

    @Test fun `S12 invalid documents refuse select arm and reconcile with DocException`() {
        val zeroRoom = base().copy(boards = listOf(Board("root", "Root", BoardKind.CANVAS, RectPx(0, 0, 0, 10))))
        val dupIds = base().copy(boards = listOf(
            Board("dup", "A", BoardKind.CANVAS, RectPx(0, 0, 10, 10)),
            Board("dup", "B", BoardKind.CANVAS, RectPx(20, 20, 10, 10))))
        val spriteNoGrid = base().copy(boards = base().boards +
            Board("bad-sprite", "Bad", BoardKind.SPRITE, RectPx(0, 0, 8, 8)))
        val future = base().copy(version = DOC_VERSION + 1)
        for (bad in listOf(zeroRoom, dupIds, spriteNoGrid, future)) {
            assertTrue(DocOps.validate(bad).isNotEmpty())
            assertFailsWith<DocException> { BoardSession().select(bad, "root") }
            assertFailsWith<DocException> { BoardSession().arm(bad, "root") }
            assertFailsWith<DocException> { BoardSession().reconcile(bad) }
            assertFailsWith<DocException> { BoardSession().select(bad, null) }
            assertFailsWith<DocException> { BoardSession().arm(bad, null) }
        }
    }
}
