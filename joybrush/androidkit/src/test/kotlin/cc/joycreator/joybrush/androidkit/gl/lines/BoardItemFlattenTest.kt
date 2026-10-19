package cc.joycreator.joybrush.androidkit.gl.lines

import cc.joycreator.joybrush.androidkit.lines.LinePlane
import cc.joycreator.joybrush.core.doc.*
import cc.joycreator.joybrush.core.paint.Tiles
import kotlin.test.*

/** Editable lines v1.2 H4 (after review): a board change flattens a plane only where it touches the plane's items. */
class BoardItemFlattenTest {
    private val shared = LinePlane("paint", "shared")
    private val frame1 = LinePlane("paint", "f1")
    private val frame2 = LinePlane("paint", "f2")
    private val other = LinePlane("sketch", "s2")
    private val all = setOf(shared, frame1, frame2, other)
    /** A board at (x, 0) 200 px square: tiles (0,0) when x = 0, (2,0) when x = 600. */
    private fun board(x: Int) = Board("B", "B", BoardKind.ANIMATION, RectPx(x, 0, 200, 200),
        frames = listOf(Frame("a"), Frame("b")), currentFrameId = "a")
    private fun doc(x: Int = 0, held: Boolean = false, frames: List<Frame>? = null) = JbDocument(id = "d", name = "d",
        boards = listOf(board(x).let { if (frames == null) it else it.copy(frames = frames) }),
        layers = listOf(
            Layer("paint", "Ink", LayerKind.PAINT, cels = listOf(Cel("shared"), Cel("f1"), Cel("f2")), sharedCelId = "shared",
                regions = listOf(RegionFrames("B", mapOf("a" to "f1", "b" to "f2"), held))),
            Layer("sketch", "Sketch", LayerKind.PAINT, cels = listOf(Cel("s2")), sharedCelId = "s2")))
    private val near = setOf(Tiles.key(0, 0))
    private val far = setOf(Tiles.key(9, 9))

    private fun plan(change: RegionChange, before: JbDocument = doc(), crossed: (LinePlane) -> Set<Long> = { near }) =
        BoardItemFlatten.plan(all, change, before, crossed)

    @Test fun aChangeWithNoPixelOperationsTakesNothing() {
        assertTrue(plan(RegionChange(doc())).isEmpty)
    }

    @Test fun aBoardMadeOverLinesFlattensThemAndOneMadeElsewhereLeavesThemEditable() {
        val rect = RectPx(0, 0, 200, 200)
        val change = RegionChange(doc(), transfers = listOf(RegionTransfer("paint", "shared", "f1", rect, rect)),
            clears = listOf(RegionClear("paint", "shared", rect)))
        assertEquals(setOf(shared, frame1), plan(change).flattened)
        assertTrue(plan(change) { far }.isEmpty, "lines far from the new board stay lines")
    }

    @Test fun aFrameCopyFlattensTheDestinationOnly() {
        val p = plan(RegionChange(doc(), copies = listOf(RegionCopy("paint", "f1", "f2", RectPx(0, 0, 200, 200)))))
        assertEquals(setOf(frame2), p.flattened)
    }

    @Test fun aDroppedFrameTakesItsItemsWithItsPixelsAndIsNotSaidToBecomePixels() {
        val rect = RectPx(0, 0, 200, 200)
        val p = plan(RegionChange(doc(), drops = listOf(RegionDrop("paint", "f1")),
            transfers = listOf(RegionTransfer("paint", "f1", "shared", rect, rect))))
        assertEquals(setOf(frame1), p.dropped)
        assertEquals(setOf(shared), p.flattened, "the canvas it was moved onto is rewritten")
    }

    @Test fun movingTheBoardFlattensOnlyPlanesWithLinesAtTheOldOrNewPlace() {
        val change = RegionChange(doc(x = 600))
        assertEquals(setOf(shared, frame1, frame2), plan(change).flattened, "lines at the old place")
        assertEquals(setOf(shared, frame1, frame2), plan(change) { setOf(Tiles.key(2, 0)) }.flattened, "lines at the new place")
        assertTrue(plan(change) { far }.isEmpty)
        assertFalse(other in plan(change).flattened, "a layer that does not animate in the board is untouched")
    }

    @Test fun addingOrSwitchingFramesKeepsEveryLine() {
        val change = RegionChange(doc(frames = listOf(Frame("a"), Frame("b"), Frame("c"))))
        assertTrue(plan(change).isEmpty)
    }

    @Test fun holdingTheBoardFlattensLinesInsideIt() {
        assertEquals(setOf(shared, frame1, frame2), plan(RegionChange(doc(held = true))).flattened)
    }

    @Test fun tilesOfARectIncludeEveryTileItReachesAndNoMore() {
        assertEquals(listOf(Tiles.key(0, 0), Tiles.key(1, 0)), BoardItemFlatten.tilesOf(RectPx(200, 10, 100, 20)).toList())
        assertEquals(listOf(Tiles.key(-1, -1)), BoardItemFlatten.tilesOf(RectPx(-10, -10, 5, 5)).toList())
        assertTrue(BoardItemFlatten.tilesOf(RectPx(0, 0, 0, 5)).none())
    }

    @Test fun theSentenceNamesTheLayersInOneLine() {
        assertNull(BoardItemFlatten.sentence(emptyList()))
        assertEquals("Editable lines on Ink became pixels for this board change.", BoardItemFlatten.sentence(listOf("Ink", "Ink")))
        assertEquals("Editable lines on Ink and Colour became pixels for this board change.", BoardItemFlatten.sentence(listOf("Ink", "Colour")))
        assertEquals("Editable lines on A, B and C became pixels for this board change.", BoardItemFlatten.sentence(listOf("A", "B", "C")))
    }
}
