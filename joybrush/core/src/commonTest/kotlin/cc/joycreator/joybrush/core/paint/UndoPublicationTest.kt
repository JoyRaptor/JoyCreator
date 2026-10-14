package cc.joycreator.joybrush.core.paint

import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.CelComposer
import kotlin.test.*

class UndoPublicationTest {
    private fun step(before: Int?, after: Int?) = UndoLog.Step(listOf(UndoLog.TileChange("paint", 0, before, after)))

    @Test fun failedNewPublicationKeepsRedoAndBothResourceOwnershipSides() {
        val released = mutableListOf<Int>()
        val log = UndoLog<Int>(100, { 4 }, released::add)
        val first = step(1, 2)
        log.push(first)
        assertSame(first, log.undo())
        assertFailsWith<IllegalStateException> { log.pushAndPublish(step(1, 3)) { error("staged scene unavailable") } }
        assertSame(first, log.peekRedo())
        assertNull(log.peekUndo())
        assertTrue(released.isEmpty())
        log.clear()
        assertEquals(listOf(2), released)
    }

    @Test fun failedUndoAndRedoPublicationsLeaveTheExpectedStepAndDepthUnchanged() {
        val log = UndoLog<Int>(100, { 4 }, {})
        val first = step(1, 2)
        log.push(first)
        assertFailsWith<IllegalStateException> { log.undoAndPublish(first) { error("undo look failed") } }
        assertSame(first, log.peekUndo())
        assertNull(log.peekRedo())
        assertEquals(1, log.undoDepth)
        assertTrue(log.undoAndPublish(first) {})
        assertFailsWith<IllegalStateException> { log.redoAndPublish(first) { error("redo look failed") } }
        assertSame(first, log.peekRedo())
        assertNull(log.peekUndo())
        assertEquals(0, log.undoDepth)
    }

    @Test fun staleExpectedIdentityRefusesWithoutCallingPublication() {
        val log = UndoLog<Int>(100, { 4 }, {})
        val first = step(1, 2)
        val equivalentButStale = step(1, 2)
        log.push(first)
        assertFalse(log.undoAndPublish(equivalentButStale) { error("stale callback ran") })
        log.undo()
        assertFalse(log.redoAndPublish(equivalentButStale) { error("stale callback ran") })
        assertSame(first, log.peekRedo())
    }

    @Test fun capacityAndBudgetPreflightFailurePrecedesPublicationAndReleasesNothing() {
        val released = mutableListOf<Int>()
        var failSize = false
        val log = UndoLog<Int>(4, { if (failSize) error("budget preflight failed") else 4 }, released::add)
        val first = step(1, 2)
        log.push(first)
        failSize = true
        assertFailsWith<IllegalStateException> { log.pushAndPublish(step(2, 3)) { error("publication must not run") } }
        assertSame(first, log.peekUndo())
        assertEquals(1, log.undoDepth)
        assertTrue(released.isEmpty())
    }

    @Test fun successfulPublicationPrecedesDiscardAndBudgetReleasesExactlyOnce() {
        val events = mutableListOf<String>()
        val log = UndoLog<Int>(4, { 4 }, { events += "release:$it" })
        val first = step(1, 2)
        val second = step(2, 3)
        log.push(first)
        log.pushAndPublish(second) { events += "publish" }
        assertEquals(listOf("publish", "release:1"), events)
        assertSame(second, log.peekUndo())
        log.undo()
        log.pushAndPublish(step(2, 4)) { events += "replace" }
        assertEquals(listOf("publish", "release:1", "replace", "release:3"), events)
        log.clear()
        assertEquals(1, events.count { it == "release:2" })
    }

    @Test fun listsAndLineSamplesAreDetachedAtStepConstruction() {
        val samples = mutableListOf(PenSample(1f, 2f, 0.0))
        val record = StrokeRecord("line", "frozen", 1, 0f, 1f, samples, seq = 1)
        val changes = mutableListOf(UndoLog.TileChange("paint", 0, 1, 2))
        val lines = mutableListOf(UndoLog.LineChange("paint", null, "line", null, CelComposer.Line(1, record)))
        val slabs = mutableListOf(UndoLog.SlabChange("other", null, 0, 0,
            UndoLog.SlabValue(3, "normal"), UndoLog.SlabValue(4, "normal")))
        val saved = UndoLog.Step(changes, lines = lines, slabs = slabs)
        changes.clear(); lines.clear(); slabs.clear(); samples.clear()
        assertEquals(1, saved.changes.size)
        assertEquals(1, saved.slabs.size)
        assertEquals(listOf(PenSample(1f, 2f, 0.0)), saved.lines.single().after!!.record.samples)
    }

    @Test fun duplicateHeldEndpointsAndTileSlabCrossFamilyAliasesRefuse() {
        fun slab(before: Int, after: Int) = UndoLog.SlabChange("paint", null, 1, 0,
            UndoLog.SlabValue(before, "normal"), UndoLog.SlabValue(after, "normal"))
        assertFailsWith<IllegalArgumentException> {
            UndoLog.Step(listOf(UndoLog.TileChange("paint", 0, 1, 2)), slabs = listOf(slab(1, 3)))
        }
        assertFailsWith<IllegalArgumentException> {
            UndoLog.Step(listOf(UndoLog.TileChange("paint", 0, 1, 2)), slabs = listOf(slab(3, 2)))
        }
        assertFailsWith<IllegalArgumentException> {
            UndoLog.Step(listOf(UndoLog.TileChange("paint", 0, 1, 2)), slabs = listOf(slab(2, 3)))
        }
        assertFailsWith<IllegalArgumentException> {
            UndoLog.Step(listOf(UndoLog.TileChange("paint", 0, 1, 2)), slabs = listOf(slab(3, 1)))
        }
    }

    @Test fun mixedFamiliesWithDistinctOwnershipClearUndoAndRedoOnlyOnce() {
        val released = mutableListOf<Int>()
        val log = UndoLog<Int>(100, { 4 }, released::add)
        val mixed = UndoLog.Step(listOf(UndoLog.TileChange("plain", 0, 1, 2)), slabs = listOf(
            UndoLog.SlabChange("recorded", null, 0, 0, UndoLog.SlabValue(3, "normal"), UndoLog.SlabValue(4, "normal"))))
        log.push(mixed)
        log.undo()
        log.clear(); log.clear()
        assertEquals(listOf(2, 4), released)
        assertEquals(0, log.heldBytes)
    }
}
