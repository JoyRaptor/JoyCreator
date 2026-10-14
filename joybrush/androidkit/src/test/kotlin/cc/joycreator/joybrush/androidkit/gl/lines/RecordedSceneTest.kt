package cc.joycreator.joybrush.androidkit.gl.lines

import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.paint.UndoLog
import cc.joycreator.joybrush.core.stroke.AddressedStroke
import cc.joycreator.joybrush.core.stroke.LineAddress
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.RecordedCel
import cc.joycreator.joybrush.core.vector.RecordedEdits
import kotlin.test.*

class RecordedSceneTest {
    private val plane: Pair<String, String?> = "paint" to null
    private val brush = BrushPreset(id = "frozen", name = "Pen", size = Param(8f))
    private fun record(id: String = "line", seq: Long = 1, x: Float = 0f) =
        StrokeRecord(id, brush.id, 19, 0f, 1f, listOf(PenSample(x, 0f, 0.0)), seq = seq)
    private fun cel(records: List<StrokeRecord> = listOf(record()), layer: String = "paint", frame: String? = null) =
        RecordedCel<Int>(layer, frame, records)

    private class Driver : RecordedScene.Renderer<Int> {
        var next = 100
        val rendered = mutableListOf<LineAddress>()
        val composed = mutableListOf<Pair<Pair<String, String?>, Long>>()
        val released = mutableListOf<Int>()
        var failLine: String? = null
        var failLayer: String? = null
        var failLook: Long? = null
        var blankLook: Long? = null
        var alias: Int? = null
        var liveContext = true
        override fun contextCurrent(): Boolean = liveContext
        override fun line(address: LineAddress, record: StrokeRecord, preset: BrushPreset): RecordedScene.Unit<Int> {
            rendered += address
            if (address.recordId == failLine && (failLayer == null || address.layerId == failLayer)) error("injected replay failure")
            return RecordedScene.Unit(preset.blend, if (record.samples.isEmpty()) emptyMap()
                else mapOf(record.samples.first().x.toLong() to (alias ?: next++)))
        }
        override fun look(plane: Pair<String, String?>, key: Long, sources: List<RecordedScene.Source<Int>>): Int? {
            composed += plane to key
            assertTrue(sources.zipWithNext().all { (a, b) -> a.seq < b.seq })
            if (key == failLook) error("injected composition failure")
            if (key == blankLook) return null
            return alias ?: next++
        }
        override fun release(texture: Int) { released += texture }
    }
    private class Fixture(val driver: Driver, val history: UndoLog<Int>, val scene: RecordedScene<Int>)
    private fun fixture(planes: Map<Pair<String, String?>, RecordedCel<Int>> = mapOf(plane to cel())): Fixture {
        val driver = Driver()
        val history = UndoLog<Int>(1_000_000, { 4 }, driver.released::add)
        val scene = RecordedScene.create(planes, mapOf(brush.id to brush), history, driver)
        driver.rendered.clear(); driver.composed.clear()
        return Fixture(driver, history, scene)
    }
    private fun replacement(f: Fixture, x: Float = 2f) = RecordedEdits.replace(f.scene.planes,
        listOf(AddressedStroke(LineAddress("paint", null, "line"),
            f.scene.planes.getValue(plane).records.first { it.id == "line" }.copy(samples = listOf(PenSample(x, 0f, 0.0))))),
        f.scene.brushes.keys)

    @Test fun editPublishesOneSceneAndHistoryAfterRenderingBothOldAndNewTiles() {
        val f = fixture()
        val old = f.scene.planes
        val look = f.scene.look(plane, 0)
        f.scene.stage(replacement(f)).use { staged ->
            assertSame(old, f.scene.planes)
            assertEquals(look, f.scene.look(plane, 0))
            assertFalse(f.history.canUndo)
            assertEquals(setOf(0L, 2L), staged.affected.getValue(plane))
            assertTrue(staged.commit())
        }
        assertEquals(setOf(2L), f.scene.lookKeys(plane))
        assertEquals(2f, f.scene.planes.getValue(plane).records.single().samples.single().x)
        assertEquals(1, f.history.undoDepth)
        assertEquals(listOf(LineAddress("paint", null, "line")), f.driver.rendered)
        assertEquals(listOf(100, 101), f.driver.released)
    }

    @Test fun undoFailureKeepsEditedSceneAndHistoryThenRetryRestoresOldLocation() {
        val f = fixture()
        f.scene.stage(replacement(f)).use { assertTrue(it.commit()) }
        val step = assertNotNull(f.history.peekUndo())
        val current = f.scene.planes
        val currentLook = f.scene.look(plane, 2)
        f.driver.failLook = 0
        assertFailsWith<IllegalStateException> {
            f.scene.stage(RecordedEdits.prepare(current, step, false), RecordedScene.Direction.UNDO)
        }
        assertSame(current, f.scene.planes)
        assertSame(step, f.history.peekUndo())
        assertFalse(f.history.canRedo)
        assertEquals(currentLook, f.scene.look(plane, 2))
        f.driver.failLook = null
        f.scene.stage(RecordedEdits.prepare(current, step, false), RecordedScene.Direction.UNDO).use { assertTrue(it.commit()) }
        assertEquals(setOf(0L), f.scene.lookKeys(plane))
        assertSame(step, f.history.peekRedo())
        f.scene.stage(RecordedEdits.prepare(f.scene.planes, step, true), RecordedScene.Direction.REDO).use { assertTrue(it.commit()) }
        assertEquals(setOf(2L), f.scene.lookKeys(plane))
        assertSame(step, f.history.peekUndo())
    }

    @Test fun laterReplayFailureReleasesOnlyFreshCachesAndKeepsEveryPlane() {
        val other: Pair<String, String?> = "other" to "frame"
        val f = fixture(mapOf(plane to cel(), other to cel(layer = "other", frame = "frame")))
        val before = f.scene.planes
        val oldLooks = listOf(f.scene.look(plane, 0), f.scene.look(other, 0))
        val edit = RecordedEdits.replace(before, listOf(
            AddressedStroke(LineAddress("paint", null, "line"), record(x = 3f)),
            AddressedStroke(LineAddress("other", "frame", "line"), record(x = 4f))), setOf(brush.id))
        // Failure in the later plane after the first unit was already allocated.
        f.driver.failLine = "line"
        f.driver.failLayer = "other"
        assertFailsWith<IllegalStateException> { f.scene.stage(edit) }
        assertSame(before, f.scene.planes)
        assertEquals(oldLooks, listOf(f.scene.look(plane, 0), f.scene.look(other, 0)))
        assertFalse(f.history.canUndo)
        assertEquals(listOf(104), f.driver.released)
    }

    @Test fun discardedProposalReleasesFreshUnitsAndLooksWithoutChangingHistory() {
        val f = fixture()
        val before = f.scene.planes
        f.scene.stage(replacement(f)).close()
        assertSame(before, f.scene.planes)
        assertFalse(f.history.canUndo)
        assertEquals(listOf(102, 103), f.driver.released)
        assertEquals(setOf(0L), f.scene.lookKeys(plane))
    }

    @Test fun staleProposalCannotOverwriteACommittedEdit() {
        val f = fixture()
        val first = f.scene.stage(replacement(f, 2f))
        val second = f.scene.stage(replacement(f, 3f))
        second.use { assertTrue(it.commit()) }
        first.use { assertFalse(it.commit()) }
        assertEquals(setOf(3L), f.scene.lookKeys(plane))
        assertEquals(1, f.history.undoDepth)
        assertEquals(f.driver.released.size, f.driver.released.toSet().size)
    }

    @Test fun unrelatedPixelHistoryMovementMakesAnOutstandingProposalStale() {
        val f = fixture()
        f.scene.stage(replacement(f)).use { staged ->
            f.history.push(UndoLog.Step(listOf(UndoLog.TileChange("plain", 9, null, 99))))
            assertFalse(staged.commit())
        }
        assertEquals(setOf(0L), f.scene.lookKeys(plane))
        assertEquals(1, f.history.undoDepth)
        assertTrue(assertNotNull(f.history.peekUndo()).lines.isEmpty())
    }

    @Test fun contextLossForgetsCurrentAndStagedNamesWithoutDeletingNewContextResources() {
        val f = fixture()
        val staged = f.scene.stage(replacement(f))
        f.scene.forget()
        assertFalse(staged.commit())
        staged.close()
        assertTrue(f.driver.released.isEmpty())
        assertTrue(f.scene.planes.isEmpty())
        assertFalse(f.history.canUndo)
    }

    @Test fun sameIdAcrossPlanesKeepsOtherCacheAndSparseLineUntouched() {
        val other: Pair<String, String?> = "paint" to "frame"
        val f = fixture(mapOf(plane to cel(listOf(record(), record("distant", 2, 1000f))),
            other to cel(frame = "frame")))
        val distant = f.scene.look(plane, 1000)
        val frame = f.scene.look(other, 0)
        f.scene.stage(replacement(f)).use {
            assertEquals(mapOf(plane to setOf(0L, 2L)), it.affected)
            assertTrue(it.commit())
        }
        assertEquals(distant, f.scene.look(plane, 1000))
        assertEquals(frame, f.scene.look(other, 0))
        assertEquals(1, f.driver.rendered.size)
        assertEquals(listOf(plane to 2L), f.driver.composed)
    }

    @Test fun changedFrozenDefinitionRefusesBeforeAnyRendering() {
        val f = fixture()
        val changed = mapOf(brush.id to brush.copy(size = Param(10f)))
        assertFailsWith<IllegalArgumentException> { f.scene.stage(replacement(f), frozen = changed) }
        assertTrue(f.driver.rendered.isEmpty())
        assertTrue(f.driver.composed.isEmpty())
        assertFalse(f.history.canUndo)
    }

    @Test fun legacyMixedStepRefusesBeforeRenderingRatherThanDroppingItsPixelHalf() {
        val f = fixture()
        val line = replacement(f).step.lines.single()
        val mixed = UndoLog.Step(listOf(UndoLog.TileChange("plain", 9, null, 99)), lines = listOf(line))
        val edit = RecordedEdits.prepare(f.scene.planes, mixed, true)
        assertFailsWith<IllegalArgumentException> { f.scene.stage(edit) }
        assertTrue(f.driver.rendered.isEmpty())
        assertFalse(f.history.canUndo)
    }

    @Test fun rendererAliasingCurrentLookRefusesWithoutReleasingTheLiveResource() {
        val f = fixture()
        val live = assertNotNull(f.scene.look(plane, 0))
        f.driver.alias = live
        assertFailsWith<IllegalArgumentException> { f.scene.stage(replacement(f)) }
        assertEquals(live, f.scene.look(plane, 0))
        assertTrue(f.driver.released.isEmpty())
        assertFalse(f.history.canUndo)
    }

    @Test fun closingSceneReleasesOnlyDerivedCachesAndRetainsBorrowedSlab() {
        val base = RecordedCel.fromPixels("paint", null, mapOf(0L to 7))
        val f = fixture(mapOf(plane to base))
        assertEquals(setOf(0L), f.scene.lookKeys(plane))
        f.scene.close(); f.scene.close()
        assertEquals(listOf(100), f.driver.released)
        assertFalse(7 in f.driver.released)
    }

    @Test fun noDabLineRetainsRecordWithExplicitEmptyAuthoritativeLookIndex() {
        val f = fixture(mapOf(plane to cel(listOf(record().copy(samples = emptyList())))))
        assertTrue(f.scene.lookKeys(plane).isEmpty())
        assertTrue(f.scene.savedLookKeys(plane).isEmpty())
        assertEquals(1, f.scene.planes.getValue(plane).records.size)
        assertTrue(f.driver.composed.isEmpty())
        f.scene.close()
        assertTrue(f.driver.released.isEmpty())
    }

    @Test fun slabChangeSharesTheSamePreparedHistoryAndUndoPublicationAsLines() {
        val f = fixture(mapOf(plane to RecordedCel.fromPixels("paint", null, mapOf(0L to 7))))
        val step = UndoLog.Step<Int>(emptyList(), slabs = listOf(UndoLog.SlabChange("paint", null, 0, 0,
            UndoLog.SlabValue(7, "normal"), UndoLog.SlabValue(8, "normal"))))
        f.scene.stage(RecordedEdits.prepare(f.scene.planes, step, true)).use { assertTrue(it.commit()) }
        assertSame(step, f.history.peekUndo())
        assertEquals(8, f.scene.planes.getValue(plane).slabs.getValue(0).single().value.texture)
        f.scene.stage(RecordedEdits.prepare(f.scene.planes, step, false), RecordedScene.Direction.UNDO).use { assertTrue(it.commit()) }
        assertEquals(7, f.scene.planes.getValue(plane).slabs.getValue(0).single().value.texture)
        assertSame(step, f.history.peekRedo())
        f.scene.close()
        assertFalse(7 in f.driver.released)
        assertFalse(8 in f.driver.released)
        f.history.clear()
        assertEquals(1, f.driver.released.count { it == 8 })
    }

    @Test fun lateLookFailureReleasesAllFreshUnitsAndEarlierLookWithoutPublishing() {
        val f = fixture(mapOf(plane to cel(listOf(record(), record("other", 2, 1f)))))
        val before = f.scene.planes
        val oldLooks = f.scene.lookKeys(plane).associateWith { f.scene.look(plane, it) }
        val edit = RecordedEdits.replace(before, listOf(
            AddressedStroke(LineAddress("paint", null, "line"), record(x = 2f)),
            AddressedStroke(LineAddress("paint", null, "other"), record("other", 2, 3f))), setOf(brush.id))
        f.driver.failLook = 3
        assertFailsWith<IllegalStateException> { f.scene.stage(edit) }
        assertSame(before, f.scene.planes)
        assertEquals(oldLooks, f.scene.lookKeys(plane).associateWith { f.scene.look(plane, it) })
        assertEquals(listOf(104, 105, 106), f.driver.released)
        assertFalse(f.history.canUndo)
    }

    @Test fun savedLookIndexExcludesPlainSlabTilesAndUsesTheWholePhysicalAddress() {
        val frame: Pair<String, String?> = "paint" to "frame"
        val sources = RecordedCel<Int>("paint", null, listOf(record()), mapOf(999L to listOf(
            RecordedCel.PixelSlab(0, UndoLog.SlabValue(7, "normal")))))
        val f = fixture(mapOf(plane to sources, frame to cel(listOf(record(x = 5f)), frame = "frame")))
        assertEquals(setOf(0L, 999L), f.scene.lookKeys(plane))
        assertEquals(setOf(0L), f.scene.savedLookKeys(plane))
        assertEquals(setOf(5L), f.scene.savedLookKeys(frame))
    }

    @Test fun omittingTransparentLookForALineRefusesInsteadOfCreatingAnIncompleteSaveIndex() {
        val f = fixture()
        val before = f.scene.planes
        f.driver.blankLook = 2
        assertFailsWith<IllegalArgumentException> { f.scene.stage(replacement(f)) }
        assertSame(before, f.scene.planes)
        assertFalse(f.history.canUndo)
        assertEquals(listOf(102), f.driver.released)
    }

    @Test fun changedPaperOrClipContextRefusesBeforeReusingOrRenderingUnits() {
        val f = fixture()
        f.driver.liveContext = false
        assertFailsWith<IllegalStateException> { f.scene.stage(replacement(f)) }
        assertTrue(f.driver.rendered.isEmpty())
        assertTrue(f.driver.composed.isEmpty())
        assertTrue(f.driver.released.isEmpty())
        assertFalse(f.history.canUndo)
    }

    @Test fun changedPaperOrClipContextCannotPublishAnOutstandingProposal() {
        val f = fixture()
        val before = f.scene.planes
        f.scene.stage(replacement(f)).use { staged ->
            f.driver.liveContext = false
            assertFalse(staged.commit())
        }
        assertSame(before, f.scene.planes)
        assertFalse(f.history.canUndo)
        assertEquals(listOf(102, 103), f.driver.released)
    }
}
