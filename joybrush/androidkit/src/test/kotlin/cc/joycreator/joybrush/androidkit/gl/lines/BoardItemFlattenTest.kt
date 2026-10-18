package cc.joycreator.joybrush.androidkit.gl.lines

import cc.joycreator.joybrush.androidkit.lines.LinePlane
import cc.joycreator.joybrush.core.doc.JbDocument
import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.doc.RegionChange
import cc.joycreator.joybrush.core.doc.RegionClear
import cc.joycreator.joybrush.core.doc.RegionCopy
import cc.joycreator.joybrush.core.doc.RegionDrop
import cc.joycreator.joybrush.core.doc.RegionTransfer
import kotlin.test.*

/** Editable lines v1.2 H4: which planes a board change takes the items from. */
class BoardItemFlattenTest {
    private val doc = JbDocument(id = "d", name = "d", boards = emptyList(), layers = emptyList())
    private val rect = RectPx(0, 0, 100, 100)
    private val shared = LinePlane("paint", "shared")
    private val frame1 = LinePlane("paint", "f1")
    private val frame2 = LinePlane("paint", "f2")
    private val other = LinePlane("sketch", "shared")
    private val all = setOf(shared, frame1, frame2, other)

    private fun plan(change: RegionChange, items: Set<LinePlane> = all, layoutChanged: Set<String> = emptySet()) =
        BoardItemFlatten.plan(items, change) { it.layerId in layoutChanged }

    @Test fun aChangeWithNoPixelOperationsTakesNothing() {
        assertTrue(plan(RegionChange(doc)).isEmpty)
    }

    @Test fun anAnimationBoardCreatedOverTheArtMovesItIntoAFrameAndClearsTheCanvasUnderIt() {
        // RegionDocumentOps.create: a transfer shared -> new frame cel, and a clear of the shared cel in the rect.
        val change = RegionChange(doc,
            transfers = listOf(RegionTransfer("paint", "shared", "f1", rect, rect)),
            clears = listOf(RegionClear("paint", "shared", rect)))
        val p = plan(change)
        assertEquals(setOf(shared, frame1), p.flattened)
        assertTrue(p.dropped.isEmpty())
    }

    @Test fun aFrameCopyFlattensTheDestinationOnlyAndLeavesTheSourceItsLines() {
        val p = plan(RegionChange(doc, copies = listOf(RegionCopy("paint", "f1", "f2", rect))))
        assertEquals(setOf(frame2), p.flattened)
    }

    @Test fun aDroppedFrameTakesItsItemsWithItsPixelsAndIsNotAlsoFlattened() {
        val change = RegionChange(doc, drops = listOf(RegionDrop("paint", "f1")),
            transfers = listOf(RegionTransfer("paint", "f1", "shared", rect, rect)))
        val p = plan(change)
        assertEquals(setOf(frame1), p.dropped)
        assertEquals(setOf(shared), p.flattened, "the canvas it was moved onto is rewritten")
    }

    @Test fun planesWhoseRegionChangedLoseTheirItems() {
        val p = plan(RegionChange(doc), layoutChanged = setOf("paint"))
        assertEquals(setOf(shared, frame1, frame2), p.flattened)
        assertFalse(other in p.flattened, "another layer is untouched")
    }

    @Test fun planesWithoutItemsAreNeverNamed() {
        val change = RegionChange(doc, clears = listOf(RegionClear("paint", "shared", rect), RegionClear("paint", "f9", rect)),
            drops = listOf(RegionDrop("paint", "f8")))
        val p = plan(change, items = setOf(shared))
        assertEquals(setOf(shared), p.flattened)
        assertTrue(p.dropped.isEmpty())
    }

    @Test fun noItemsAnywhereIsAnEmptyPlanWhateverTheChange() {
        val change = RegionChange(doc, clears = listOf(RegionClear("paint", "shared", rect)))
        assertTrue(plan(change, items = emptySet(), layoutChanged = setOf("paint")).isEmpty)
    }

    @Test fun theSentenceNamesTheLayersInOneLine() {
        assertNull(BoardItemFlatten.sentence(emptyList()))
        assertEquals("Editable lines on Ink became pixels for this board change.", BoardItemFlatten.sentence(listOf("Ink", "Ink")))
        assertEquals("Editable lines on Ink and Colour became pixels for this board change.", BoardItemFlatten.sentence(listOf("Ink", "Colour")))
        assertEquals("Editable lines on A, B and C became pixels for this board change.", BoardItemFlatten.sentence(listOf("A", "B", "C")))
    }

    // ── region(): what a plane owns ──
    private fun board(id: String, x: Int) = cc.joycreator.joybrush.core.doc.Board(id, id, cc.joycreator.joybrush.core.doc.BoardKind.ANIMATION,
        RectPx(x, 0, 100, 100), frames = listOf(cc.joycreator.joybrush.core.doc.Frame("a"), cc.joycreator.joybrush.core.doc.Frame("b")), currentFrameId = "a")
    private fun layered(boards: List<cc.joycreator.joybrush.core.doc.Board>, held: Boolean = false) = doc.copy(boards = boards, layers = listOf(
        cc.joycreator.joybrush.core.doc.Layer("paint", "Ink", cc.joycreator.joybrush.core.doc.LayerKind.PAINT,
            cels = listOf(cc.joycreator.joybrush.core.doc.Cel("shared"), cc.joycreator.joybrush.core.doc.Cel("f1"), cc.joycreator.joybrush.core.doc.Cel("f2")),
            sharedCelId = "shared",
            regions = listOf(cc.joycreator.joybrush.core.doc.RegionFrames("B", mapOf("a" to "f1", "b" to "f2"), held)))))

    @Test fun addingAFrameOrSwitchingFramesKeepsEveryRegion() {
        val before = layered(listOf(board("B", 0)))
        val after = before.copy(boards = listOf(board("B", 0).copy(frames = board("B", 0).frames + cc.joycreator.joybrush.core.doc.Frame("c"), currentFrameId = "b")))
        for (p in listOf(shared, frame1, frame2)) assertEquals(BoardItemFlatten.region(before, p), BoardItemFlatten.region(after, p), "$p")
    }

    @Test fun movingTheBoardChangesTheCanvasAndItsFramesButNotOtherLayers() {
        val before = layered(listOf(board("B", 0)))
        val after = layered(listOf(board("B", 300)))
        for (p in listOf(shared, frame1, frame2)) assertNotEquals(BoardItemFlatten.region(before, p), BoardItemFlatten.region(after, p), "$p")
        assertNull(BoardItemFlatten.region(after, other), "a layer the doc lacks has no region")
    }

    @Test fun holdingTheBoardChangesTheRegions() {
        val before = layered(listOf(board("B", 0)))
        val after = layered(listOf(board("B", 0)), held = true)
        assertNotEquals(BoardItemFlatten.region(before, shared), BoardItemFlatten.region(after, shared))
        assertNotEquals(BoardItemFlatten.region(before, frame1), BoardItemFlatten.region(after, frame1))
    }
}
