package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.core.doc.*
import cc.joycreator.joybrush.core.paint.Tiles
import cc.joycreator.joybrush.core.paint.UndoLog
import kotlin.test.*

class BoardTransactionMemoryTest {
    private val tileBytes = 256L * 256 * 4

    @Test fun fullTwelveFrameMoveFitsConfiguredSnapshotBytes() {
        val memory = BoardTransactionMemory<String>(192L shl 20, tileBytes, false, "Moving this board")
        // 512², two layers, twelve independent frame cels; clear old tiles and write new tiles.
        for (cel in 0 until 24) for (tile in 0 until 4) {
            memory.observe("old-$cel-$tile", cel * 4 + tile + 1, true)
            memory.observe("new-$cel-$tile", null, true)
        }
        assertEquals(96, memory.originalCount)
        assertEquals(192, memory.stagedCount)
        assertEquals(72L shl 20, memory.peakBytes)
    }

    @Test fun duplicateAddressesAndMaskHandlesCountOnlyTheirRealStorage() {
        val memory = BoardTransactionMemory<String>(16L shl 20, tileBytes, true, "Copying this board's paint")
        repeat(20) { memory.observe("paint", 1, true) }
        memory.observe("mask", 2, true)
        memory.observe("alias-of-original", 1, false)
        assertEquals(2, memory.originalCount)
        assertEquals(2, memory.stagedCount)
        assertEquals(5 * tileBytes, memory.peakBytes) // before + after + one replacement scratch
    }

    @Test fun dropOnlyKeepsBeforeSnapshotsWithoutChargingAfterOrScratch() {
        val memory = BoardTransactionMemory<String>(4 * tileBytes, tileBytes, true, "Removing this board or frame")
        for (tile in 0 until 4) memory.observe("drop-$tile", tile + 1, false)
        assertEquals(4 * tileBytes, memory.peakBytes)
        val refused = assertFailsWith<IllegalArgumentException> { memory.observe("drop-5", 5, false) }
        assertTrue(refused.message.orEmpty().startsWith("Removing"))
        assertTrue(refused.message.orEmpty().contains("Clear smaller painted areas"))
    }

    @Test fun multiplicationOverflowRefusesInsteadOfWrapping() {
        val memory = BoardTransactionMemory<String>(Long.MAX_VALUE - 1, Long.MAX_VALUE, true, "Moving this board")
        assertFailsWith<IllegalArgumentException> { memory.observe("tile", 1, true) }
        assertEquals(Long.MAX_VALUE, memory.peakBytes)
    }

    private var serial = 0
    private fun ids() = "board-budget-${++serial}"
    private fun twelveFrames(): JbDocument {
        val base = DocOps.newDocument("doc", "Budget", 512, 512, ::ids)
        val second = base.layers.single().copy(id = "second", cels = listOf(Cel("second-cel")))
        var doc = RegionDocumentOps.create(base.copy(layers = base.layers + second), "Animation", RectPx(0, 0, 512, 512), ::ids).doc
        repeat(11) { doc = RegionDocumentOps.addFrame(doc, doc.boards.last().id, NewFrame.BLANK, ::ids).doc }
        return doc
    }

    private fun engine(doc: JbDocument, budget: Long = 192L shl 20, masks: Boolean = false): GlPaintEngine {
        val e = GlPaintEngine(undoBudgetBytes = budget)
        e.initWith { }
        doc.layers.forEach { e.addLayer(it.id) }
        e.setBoardDocument(doc)
        val changes = ArrayList<UndoLog.TileChange<Int>>()
        var texture = 100
        for (layer in doc.layers) for (cel in layer.regions.single().frameCel.values) for (ty in 0..1) for (tx in 0..1)
            changes.add(UndoLog.TileChange(layer.id, Tiles.key(tx, ty), texture++, null, cel))
        if (masks) for (layer in doc.layers) changes.add(UndoLog.TileChange(maskStoreId(layer.id), Tiles.key(0, 0), texture++, null))
        // Plant handles without issuing GL uploads; this tests ownership/history, not GPU pixels.
        e.undo.push(UndoLog.Step(changes))
        assertTrue(e.undoStep())
        return e
    }

    @Test fun insufficientBudgetRefusesMovesCopiesAndDropsBeforeLiveChanges() {
        val doc = twelveFrames()
        val boardId = doc.boards.last().id
        val changes = listOf(
            AnimationBoardOps.moveAll(doc, boardId, 768, 0),
            RegionDocumentOps.addFrame(doc, boardId, NewFrame.DUPLICATE, ::ids),
            AnimationBoardOps.remove(doc, boardId))
        val starts = listOf("Moving", "Duplicating", "Removing")
        for ((index, change) in changes.withIndex()) {
            val e = engine(doc, 1)
            val held = e.heldTextureNames()
            val error = assertFailsWith<IllegalArgumentException> { e.applyBoardChange(change) }
            assertTrue(error.message.orEmpty().startsWith(starts[index]), error.message)
            assertTrue(error.message.orEmpty().contains("MiB"))
            assertEquals(doc, e.boardDocument)
            assertEquals(0, e.undo.undoDepth)
            assertTrue(e.undo.canRedo)
            assertEquals(held, e.heldTextureNames())
        }
    }

    @Test fun maskOnlyTransferRefusesBeforeAllocationOrHistoryChange() {
        val doc = twelveFrames().let { it.copy(layers = it.layers.map { layer -> layer.copy(mask = Cel("${layer.id}-mask")) }) }
        val e = engine(doc, 1, masks = true)
        val held = e.heldTextureNames()
        val change = RegionChange(doc, maskTransfers = listOf(RegionMaskTransfer(doc.layers.first().id,
            RectPx(0, 0, 256, 256), RectPx(768, 0, 256, 256))))
        val error = assertFailsWith<IllegalArgumentException> { e.applyBoardChange(change) }
        assertTrue(error.message.orEmpty().startsWith("Swapping"))
        assertEquals(doc, e.boardDocument)
        assertEquals(0, e.undo.undoDepth)
        assertTrue(e.undo.canRedo)
        assertEquals(held, e.heldTextureNames())
    }

    @Test fun operationNamesDistinguishMoveResizeRemoveDuplicateAndSwap() {
        val doc = twelveFrames()
        val id = doc.boards.last().id
        assertEquals("Moving this board", BoardTransactionMemory.operation(doc, AnimationBoardOps.moveAll(doc, id, 768, 0)))
        val single = RegionDocumentOps.create(DocOps.newDocument("single", "Single", 512, 512, ::ids),
            "Single frame", RectPx(0, 0, 512, 512), ::ids).doc
        assertEquals("Resizing this board", BoardTransactionMemory.operation(single,
            AnimationBoardOps.resize(single, single.boards.last().id, RectPx(0, 0, 500, 500))))
        assertEquals("Removing this board or frame", BoardTransactionMemory.operation(doc, AnimationBoardOps.remove(doc, id)))
        assertEquals("Duplicating this frame", BoardTransactionMemory.operation(doc, RegionDocumentOps.addFrame(doc, id, NewFrame.DUPLICATE, ::ids)))
        assertEquals("Duplicating this board", BoardTransactionMemory.operation(doc, AnimationBoardOps.duplicate(doc, id, 768, 0, ::ids)))
        val l = doc.layers.first(); val cel = l.cels.first().id
        assertEquals("Swapping these cells", BoardTransactionMemory.operation(doc, RegionChange(doc, transfers = listOf(
            RegionTransfer(l.id, cel, cel, RectPx(0, 0, 16, 16), RectPx(16, 0, 16, 16))))))
    }
}
