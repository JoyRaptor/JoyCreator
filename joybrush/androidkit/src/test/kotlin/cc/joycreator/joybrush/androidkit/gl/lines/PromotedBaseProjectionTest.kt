package cc.joycreator.joybrush.androidkit.gl.lines

import cc.joycreator.joybrush.core.paint.UndoLog
import cc.joycreator.joybrush.core.vector.RecordedCel
import kotlin.test.*

/** Authored/unrun proposal tests; actual engine transfer/GPU activation is deliberately separate. */
class PromotedBaseProjectionTest {
    private val shared: Pair<String, String?> = "paint" to null
    private val frame: Pair<String, String?> = "paint" to "frame1"
    private fun cel(texture: Int = 10) = RecordedCel.fromPixels("paint", null, mapOf(3L to texture))
    private fun oldStep() = UndoLog.Step(listOf(UndoLog.TileChange("paint", 3, 9, 10)))
    private fun cursorFor(s: UndoLog.Step<Int>, forward: Boolean = false): UndoLog.Cursor<Int> {
        val history = UndoLog<Int>(100_000, { 4 }, { error("fixture preparation must not retire") })
        history.push(s)
        return if (forward) assertNotNull(history.prepareUndo()).cursor else history.snapshotCursor()
    }
    private fun prepare(c: RecordedCel<Int>, s: UndoLog.Step<Int>, forward: Boolean = false,
                        isNew: Boolean = false, eligible: List<UndoLog.Step<Int>> = listOf(s)) =
        PromotedBaseProjection.prepare(mapOf(shared to c), s,
            if (isNew) RecordedScene.Direction.NEW else if (forward) RecordedScene.Direction.REDO
            else RecordedScene.Direction.UNDO, cursorFor(s, forward), eligible, setOf(shared))

    @Test fun promotionRetainsActualNamesAndDetachesMapBackingWithoutAnyRawRecords() {
        val native = linkedMapOf(3L to 10, 4L to 11)
        val c = RecordedCel.fromPixels("paint", null, native)
        native.clear()
        assertTrue(c.records.isEmpty())
        assertEquals(setOf(3L, 4L), c.slabs.keys)
        assertTrue(c.slabs.values.all { it.single().seq == 0L && it.single().value.op == "normal" })
        assertEquals(listOf(10, 11), c.slabs.values.map { it.single().value.texture })
    }

    @Test fun coldPlainFactoryCallsNoLineLookOrReleaseAndOwnsNoSavedLookCache() {
        var callbacks = 0
        val history = UndoLog<Int>(100, { 4 }, { callbacks++ })
        val renderer = object : RecordedScene.Renderer<Int> {
            override fun contextCurrent() = true
            override fun line(address: cc.joycreator.joybrush.core.stroke.LineAddress,
                              record: cc.joycreator.joybrush.core.stroke.StrokeRecord,
                              preset: cc.joycreator.joybrush.core.brush.BrushPreset): RecordedScene.Unit<Int> =
                error("plain activation must not replay")
            override fun look(plane: Pair<String, String?>, key: Long,
                              sources: List<RecordedScene.Source<Int>>): Int? = error("plain activation must not compose")
            override fun release(texture: Int) { callbacks++ }
        }
        val planes = mapOf(shared to cel(), frame to RecordedCel.fromPixels("paint", "frame1", emptyMap<Long, Int>()))
        val scene = RecordedScene.createSaved(planes, emptyMap(), history, renderer,
            planes.mapValues { emptySet<Long>() }, planes.mapValues { emptyMap<Long, Int>() }, setOf(10))
        assertEquals(0, callbacks)
        assertTrue(scene.savedLookKeys(shared).isEmpty()); assertTrue(scene.savedLookKeys(frame).isEmpty())
        assertFalse(scene.isMaterialized(shared)); assertFalse(scene.isMaterialized(frame))
        assertTrue(scene.heldCacheTextures().isEmpty())
        assertEquals(10, PromotedBaseProjection.displayTile(scene.planes.getValue(shared), emptySet(), null, 3))
        scene.close()
        assertEquals(0, callbacks) // Scene.close does not own/retire physical slabs.
    }

    @Test fun originalStepAndCursorRemainActualHistoryWhileProjectionMovesSeq0OnUndoRedo() {
        val released = mutableListOf<Int>()
        val history = UndoLog<Int>(100, { 4 }, { released += it })
        val original = oldStep()
        history.push(original)
        val oldCursor = history.snapshotCursor()
        assertEquals(setOf(9), oldCursor.heldTextures()) // Current base10 is not history-owned.
        val p = PromotedBaseProjection.prepare(mapOf(shared to cel()), original,
            RecordedScene.Direction.UNDO, oldCursor, listOf(original), setOf(shared))
        assertSame(original, p.fullStep)
        assertSame(original, history.peekUndo()); assertSame(oldCursor, history.snapshotCursor())
        assertTrue(p.recordedEdit.step.changes.isEmpty())
        assertEquals(9, p.recordedEdit.after.getValue(shared).slabs.getValue(3).single().value.texture)
        val undo = assertNotNull(history.prepareUndo(oldCursor, original))
        assertSame(original, undo.step)
        val redo = assertNotNull(history.prepareRedo(undo.cursor, original))
        assertSame(original, redo.step)
        val q = PromotedBaseProjection.prepare(p.recordedEdit.after, original,
            RecordedScene.Direction.REDO, undo.cursor, listOf(original), setOf(shared))
        assertEquals(10, q.recordedEdit.after.getValue(shared).slabs.getValue(3).single().value.texture)
        assertTrue(released.isEmpty())
    }

    @Test fun activationAddsNoStepAndFirstLineUndoLeavesTheOriginalBase() {
        val history = UndoLog<Int>(100_000, { 4 }, { error("metadata promotion releases nothing") })
        val original = oldStep(); history.push(original)
        val cursor = history.snapshotCursor()
        val base = cel()
        assertSame(cursor, history.snapshotCursor())
        val record = cc.joycreator.joybrush.core.stroke.StrokeRecord("line", "pen", 2, 0f, 1f,
            listOf(cc.joycreator.joybrush.core.input.PenSample(1f, 1f, 0.0)), seq = base.nextSeq())
        val line = UndoLog.Step<Int>(emptyList(), lines = listOf(UndoLog.LineChange("paint", null, "line", null,
            cc.joycreator.joybrush.core.vector.CelComposer.Line(record.seq, record))))
        val after = base.apply(line, true)
        val newCursor = history.prepareNew(line, cursor).cursor
        assertEquals(2, newCursor.undoDepth)
        assertSame(original, newCursor.undoSteps.first())
        val undo = assertNotNull(history.prepareUndo(newCursor, line))
        assertSame(original, undo.cursor.peekUndo())
        val restored = after.apply(line, false)
        assertTrue(restored.records.isEmpty())
        assertEquals(10, restored.slabs.getValue(3).single().value.texture)
    }

    @Test fun wrongCurrentBaseAndEquivalentUncapturedStepRefuseWithoutMutation() {
        val original = oldStep()
        val c = cel(99)
        assertFailsWith<IllegalArgumentException> { prepare(c, original) }
        assertEquals(99, c.slabs.getValue(3).single().value.texture)
        assertFailsWith<IllegalArgumentException> { prepare(cel(), oldStep(), eligible = listOf(original)) }
    }

    @Test fun newNativeWritesOnPromotedPlaneAndMediaAliasesRefuse() {
        val original = oldStep()
        assertFailsWith<IllegalArgumentException> { prepare(cel(), original, isNew = true) }
        val media = UndoLog.Step(listOf(UndoLog.TileChange("paint#p0", 3, 9, 10)))
        assertFailsWith<IllegalArgumentException> { prepare(cel(), media) }
    }

    @Test fun disjointNativeMediaRemainsInCompleteStepAndDoesNotChangeRecordedSources() {
        val c = cel()
        val s = UndoLog.Step(listOf(UndoLog.TileChange("other#p0", 3, 19, 20)))
        val p = prepare(c, s, isNew = true, eligible = emptyList())
        assertSame(s, p.fullStep)
        assertSame(c, p.recordedEdit.after.getValue(shared))
        assertTrue(p.recordedEdit.changedPlanes.isEmpty()); assertTrue(p.bridgedChanges.isEmpty())
    }

    @Test fun frameCelAndSharedAreIndependentPhysicalDestinations() {
        val sharedCel = cel()
        val frameCel = RecordedCel.fromPixels("paint", "frame1", mapOf(3L to 30))
        val s = UndoLog.Step(listOf(UndoLog.TileChange("paint", 3, 29, 30, "frame1")))
        val p = PromotedBaseProjection.prepare(mapOf(shared to sharedCel, frame to frameCel), s,
            RecordedScene.Direction.UNDO, cursorFor(s), listOf(s), setOf(shared, frame))
        assertSame(sharedCel, p.recordedEdit.after.getValue(shared))
        assertEquals(29, p.recordedEdit.after.getValue(frame).slabs.getValue(3).single().value.texture)
    }

    @Test fun crossFamilyTransferStepStaysForbiddenAndPlainDisplayNeverInventsSavedKeys() {
        assertFailsWith<IllegalArgumentException> {
            UndoLog.Step(listOf(UndoLog.TileChange("paint", 3, 10, null)), slabs = listOf(
                UndoLog.SlabChange("paint", null, 3, 0, null, UndoLog.SlabValue(10, "normal"))))
        }
        assertEquals(10, PromotedBaseProjection.displayTile(cel(), emptySet(), null, 3))
        assertFailsWith<IllegalArgumentException> {
            PromotedBaseProjection.displayTile(cel(), setOf(3), null, 3)
        }
        assertEquals(50, PromotedBaseProjection.displayTile(cel(), setOf(3), 50, 3))
        val ordered = RecordedCel("paint", null, slabs = mapOf(3L to listOf(
            RecordedCel.PixelSlab(0, UndoLog.SlabValue(10, "normal")),
            RecordedCel.PixelSlab(2, UndoLog.SlabValue(11, "erase")))))
        assertFailsWith<IllegalArgumentException> {
            PromotedBaseProjection.displayTile(ordered, emptySet(), null, 3)
        }
    }

    @Test fun eligibleOldStepStillRefusesWhenItIsNotActualCursorHead() {
        val s = oldStep()
        val newer = UndoLog.Step(listOf(UndoLog.TileChange("other", 1, 19, 20)))
        assertFailsWith<IllegalArgumentException> {
            PromotedBaseProjection.prepare(mapOf(shared to cel()), s, RecordedScene.Direction.UNDO,
                cursorFor(newer), listOf(s, newer), setOf(shared))
        }
        assertFailsWith<IllegalArgumentException> {
            PromotedBaseProjection.prepare(mapOf(shared to cel(9)), s, RecordedScene.Direction.REDO,
                cursorFor(s), listOf(s), setOf(shared))
        }
    }

    @Test fun absentEndpointsDeleteAndRestoreWithoutInventingLines() {
        val s = UndoLog.Step(listOf(UndoLog.TileChange("paint", 3, null, 10)))
        val p = prepare(cel(), s)
        assertTrue(p.recordedEdit.after.getValue(shared).slabs.isEmpty())
        assertTrue(p.recordedEdit.step.lines.isEmpty())
        val q = prepare(p.recordedEdit.after.getValue(shared), s, forward = true)
        assertEquals(10, q.recordedEdit.after.getValue(shared).slabs.getValue(3).single().value.texture)
        assertSame(s.changes.single(), q.bridgedChanges.single())
        assertSame(s, q.fullStep)
    }

    @Test fun duplicatePromotedTargetAndZeroEndpointsRefuse() {
        val repeated = UndoLog.Step(listOf(UndoLog.TileChange("paint", 3, 8, 9),
            UndoLog.TileChange("paint", 3, 9, 10)))
        assertFailsWith<IllegalArgumentException> { prepare(cel(), repeated) }
        val zero = UndoLog.Step(listOf(UndoLog.TileChange("paint", 3, 0, 10)))
        assertFailsWith<IllegalArgumentException> { prepare(cel(), zero) }
        val aliasedNoOp = UndoLog.Step(listOf(UndoLog.TileChange("paint", 3, 10, 10)))
        assertFailsWith<IllegalArgumentException> { prepare(cel(), aliasedNoOp) }
    }

    @Test fun noOpLegacyHistoryMovesWithoutCreatingIllegalSameTextureSlabChange() {
        // Engine activation rejects a live history-owned alias. The pure bridge also preserves
        // null/null historical no-op movement; it must not fabricate texture ownership.
        val s = UndoLog.Step<Int>(listOf(UndoLog.TileChange("paint", 3, null, null)))
        val c = RecordedCel.fromPixels("paint", null, emptyMap<Long, Int>())
        val p = prepare(c, s)
        assertSame(s, p.fullStep)
        assertSame(s.changes.single(), p.bridgedChanges.single())
        assertSame(c, p.recordedEdit.after.getValue(shared))
        assertTrue(p.recordedEdit.step.slabs.isEmpty())
    }
}
