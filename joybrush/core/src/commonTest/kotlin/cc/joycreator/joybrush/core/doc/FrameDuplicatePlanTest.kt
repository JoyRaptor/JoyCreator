package cc.joycreator.joybrush.core.doc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FrameDuplicatePlanTest {
    private var serial = 0
    private fun ids() = "duplicate-plan-${++serial}"

    private fun animation(): JbDocument {
        val base = JbDocument(
            id = "doc",
            name = "Drawing",
            boards = listOf(Board("root", "Root", BoardKind.CANVAS, RectPx(0, 0, 100, 100))),
            layers = listOf(
                Layer("paint-a", "Paint A", LayerKind.PAINT, cels = listOf(Cel("shared-a"))),
                Layer("paint-b", "Paint B", LayerKind.PAINT, cels = listOf(Cel("shared-b"))),
            ),
        )
        // Both layers animated (clips C1 made the default only the active one); this file tests held changes on paint-a.
        return RegionDocumentOps.create(base, "Walk", RectPx(-5, 9, 17, 23), ::ids, animate = null).doc
    }

    private fun board(doc: JbDocument) = doc.boards.single { it.kind == BoardKind.ANIMATION }

    private fun duplicate(before: JbDocument): RegionChange {
        val target = board(before)
        return RegionDocumentOps.addFrame(before, target.id, NewFrame.DUPLICATE, ::ids)
    }

    @Test
    fun duplicateMarkerRegeneratesIndependentFreshCopiesForEveryLayer() {
        val before = animation()
        val targetBoard = board(before)
        val sourceId = targetBoard.currentFrameId!!
        val change = duplicate(before)
        val marker = assertNotNull(change.frameDuplicate)
        val targetId = board(change.doc).currentFrameId!!

        assertEquals(FrameDuplicate(targetBoard.id, sourceId, targetId), marker)
        val copies = assertNotNull(FrameDuplicatePlan.validate(before, change))
        assertEquals(change.copies, copies)
        assertEquals(listOf("paint-a", "paint-b"), copies.map { it.layerId })
        val oldCelIds = before.layers.flatMap { it.cels }.map { it.id }.toSet()
        assertTrue(copies.all { it.fromCelId != it.toCelId && it.toCelId !in oldCelIds })
        assertEquals(2, copies.map { it.toCelId }.toSet().size)
        assertTrue(DocOps.validate(change.doc).isEmpty())
    }

    @Test
    fun onlyDuplicateOperationsCarryTheMarker() {
        val before = animation()
        val targetBoard = board(before)
        val duplicate = RegionDocumentOps.addFrame(before, targetBoard.id, NewFrame.DUPLICATE, ::ids)
        val link = RegionDocumentOps.addFrame(before, targetBoard.id, NewFrame.LINK, ::ids)
        val blank = RegionDocumentOps.addFrame(before, targetBoard.id, NewFrame.BLANK, ::ids)
        val empty = RegionChange(before)
        val held = RegionDocumentOps.setHeld(before, targetBoard.id, "paint-a", true)
        val unheld = RegionDocumentOps.setHeld(held.doc, targetBoard.id, "paint-a", false)

        assertNotNull(duplicate.frameDuplicate)
        for (ordinary in listOf(link, blank, empty, held, unheld)) {
            assertNull(ordinary.frameDuplicate)
        }
        assertNull(FrameDuplicatePlan.validate(before, link))
        assertNull(FrameDuplicatePlan.validate(before, blank))
        assertNull(FrameDuplicatePlan.validate(before, empty))
        assertNull(FrameDuplicatePlan.validate(before, held))
        assertNull(FrameDuplicatePlan.validate(held.doc, unheld))
    }

    @Test
    fun malformedAndMarkerOnlyChangesAreRejected() {
        val before = animation()
        val targetBoard = board(before)
        val sourceId = targetBoard.currentFrameId!!
        val change = duplicate(before)
        val marker = assertNotNull(change.frameDuplicate)
        val malformed = listOf(
            change.copy(frameDuplicate = marker.copy(boardId = "missing-board")),
            change.copy(frameDuplicate = marker.copy(sourceFrameId = "not-the-current-frame")),
            change.copy(frameDuplicate = marker.copy(targetFrameId = "")),
            change.copy(frameDuplicate = marker.copy(targetFrameId = sourceId)),
            change.copy(frameDuplicate = marker.copy(targetFrameId = "not-in-result")),
        )
        for (forged in malformed) {
            assertFailsWith<DocException> { FrameDuplicatePlan.validate(before, forged) }
        }

        val markerOnly = RegionChange(doc = change.doc, frameDuplicate = marker)
        assertFailsWith<DocException> { FrameDuplicatePlan.validate(before, markerOnly) }
    }

    @Test
    fun mismatchedDocumentsRectanglesAndHeldMappingsAreRejected() {
        val before = animation()
        val targetBoard = board(before)
        val change = duplicate(before)
        val differentRect = RectPx(20, 21, 17, 23)
        val resizedBefore = AnimationBoardOps.resize(before, targetBoard.id, differentRect).doc
        val movedAfter = AnimationBoardOps.moveAll(change.doc, targetBoard.id, differentRect.x, differentRect.y).doc
        val heldAfter = RegionDocumentOps.setHeld(change.doc, targetBoard.id, "paint-a", true).doc

        assertFailsWith<DocException> { FrameDuplicatePlan.validate(before, change.copy(doc = before)) }
        assertFailsWith<DocException> { FrameDuplicatePlan.validate(resizedBefore, change) }
        assertFailsWith<DocException> { FrameDuplicatePlan.validate(before, change.copy(doc = movedAfter)) }
        assertFailsWith<DocException> { FrameDuplicatePlan.validate(before, change.copy(doc = heldAfter)) }
    }

    @Test
    fun anyExtraRegionOperationListInvalidatesTheDuplicatePlan() {
        val before = animation()
        val change = duplicate(before)
        val copy = change.copies.first()
        val rect = copy.rect
        val transfer = RegionTransfer(copy.layerId, copy.fromCelId, copy.toCelId, rect, rect)
        val maskTransfer = RegionMaskTransfer(copy.layerId, rect, rect)
        val forged = listOf(
            change.copy(copies = change.copies + copy),
            change.copy(drops = listOf(RegionDrop(copy.layerId, copy.fromCelId))),
            change.copy(transfers = listOf(transfer)),
            change.copy(maskTransfers = listOf(maskTransfer)),
            change.copy(clears = listOf(RegionClear(copy.layerId, copy.fromCelId, rect))),
            change.copy(maskClears = listOf(RegionMaskClear(copy.layerId, rect))),
        )

        for (forgedChange in forged) {
            assertFailsWith<DocException> { FrameDuplicatePlan.validate(before, forgedChange) }
        }
    }

    @Test
    fun linkedDestinationsAndChangedSourceCursorCannotMasqueradeAsDuplicates() {
        val before = animation()
        val targetBoard = board(before)
        val linked = RegionDocumentOps.addFrame(before, targetBoard.id, NewFrame.LINK, ::ids)
        val linkedBoard = board(linked.doc)
        val linkedTarget = linkedBoard.currentFrameId!!
        val linkMarkedAsDuplicate = linked.copy(
            frameDuplicate = FrameDuplicate(targetBoard.id, targetBoard.currentFrameId!!, linkedTarget),
        )
        assertFailsWith<DocException> { FrameDuplicatePlan.validate(before, linkMarkedAsDuplicate) }

        val withSecondFrame = RegionDocumentOps.addFrame(before, targetBoard.id, NewFrame.BLANK, ::ids).doc
        val source = board(withSecondFrame).currentFrameId!!
        val realDuplicate = RegionDocumentOps.addFrame(withSecondFrame, targetBoard.id, NewFrame.DUPLICATE, ::ids)
        val changedCursor = RegionDocumentOps.selectFrame(
            withSecondFrame,
            targetBoard.id,
            board(withSecondFrame).frames.first().id,
        )
        assertTrue(source != board(changedCursor).currentFrameId)
        assertFailsWith<DocException> { FrameDuplicatePlan.validate(changedCursor, realDuplicate) }
    }
}
