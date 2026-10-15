package cc.joycreator.joybrush.androidkit.gl.lines

import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.doc.Paper
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.media.MediaStores
import cc.joycreator.joybrush.core.paint.UndoLog
import cc.joycreator.joybrush.core.stroke.AddressedStroke
import cc.joycreator.joybrush.core.stroke.LineAddress
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.RecordedCel
import cc.joycreator.joybrush.core.vector.RecordedEdits
import kotlin.test.*

/** Real scene/history and fake root/texture names. Authored no-GL contracts, not engine/GPU proof. */
class RecordedSceneProjectedJointTest {
    private val paint: Pair<String, String?> = "paint" to null
    private val frame: Pair<String, String?> = "paint" to "frame"
    private val pen = BrushPreset(id = "pen", name = "Pen", size = Param(4f))
    private fun record(id: String = "line", key: Long = 0, seq: Long = 1) = StrokeRecord(
        id, pen.id, 17, 0f, 1f, listOf(PenSample(key.toFloat(), 0f, 0.0)), seq = seq)
    private fun base(texture: Int = 10, plane: Pair<String, String?> = paint) =
        RecordedCel.fromPixels(plane.first, plane.second, mapOf(0L to texture))
    private fun oldStep() = UndoLog.Step(listOf(UndoLog.TileChange("paint", 0, 9, 10)))
    private fun mixedOldStep() = UndoLog.Step(listOf(UndoLog.TileChange("paint", 0, 9, 10),
        UndoLog.TileChange("native", 0, 50, 51)))
    private data class NativeKey(val owner: String, val cel: String?, val key: Long)
    private data class Root(
        override val recorded: RecordedScene.State<Int>,
        override val cursor: UndoLog.Cursor<Int>,
        override val graphicsEpoch: Long = 7,
        override val baseTransferIdentity: Any? = Any(),
        override val promotedBases: Set<Pair<String, String?>>,
        override val legacyBaseSteps: Set<UndoLog.Step<Int>>,
        val native: Map<NativeKey, Int> = mapOf(NativeKey("native", null, 0) to 50),
        val global: Set<Int> = setOf(700),
    ) : RecordedScene.PromotedPublication<Int> {
        override val protectedTextures: Set<Int> = native.values.toSet() + global
    }

    private class Driver : RecordedScene.Renderer<Int> {
        var next = 100
        var contexts = 0
        var sizes = 0
        var context = true
        var lookAlias: Int? = null
        var lineAlias: Int? = null
        var failingLook: Long? = null
        var afterContext: (() -> Unit)? = null
        var afterLine: (() -> Unit)? = null
        var afterLook: (() -> Unit)? = null
        var afterRelease: (() -> Unit)? = null
        val minted = mutableListOf<Int>()
        val released = mutableListOf<Int>()
        val lines = mutableListOf<LineAddress>()
        val looks = mutableListOf<Pair<Pair<String, String?>, Long>>()
        val sources = mutableListOf<List<RecordedScene.Source<Int>>>()
        private fun mint(): Int = (next++).also { minted += it }
        override fun contextCurrent(): Boolean { contexts++; afterContext?.invoke(); return context }
        override fun line(address: LineAddress, record: StrokeRecord, preset: BrushPreset): RecordedScene.Unit<Int> {
            lines += address
            val unit = RecordedScene.Unit(preset.blend, if (record.samples.isEmpty()) emptyMap()
                else mapOf(record.samples.first().x.toLong() to (lineAlias ?: mint())))
            afterLine?.invoke()
            return unit
        }
        override fun look(plane: Pair<String, String?>, key: Long, sources: List<RecordedScene.Source<Int>>): Int {
            looks += plane to key; this.sources += sources.toList()
            if (key == failingLook) error("look failed")
            val output = lookAlias ?: mint()
            afterLook?.invoke()
            return output
        }
        override fun release(texture: Int) { released += texture; afterRelease?.invoke() }
    }

    private inner class Fixture(val history: UndoLog<Int>, val scene: RecordedScene<Int>, val driver: Driver,
                                var root: Root) {
        var rootReads = 0
        var assignments = 0
        fun bind() { scene.bindState { rootReads++; root }; history.bindCursor { root.cursor } }
        fun stage(step: UndoLog.Step<Int>, direction: RecordedScene.Direction = RecordedScene.Direction.UNDO) =
            scene.stageJointProjected(root, step, direction)
        /** Contract publisher only: preserves all slices in one assignment, never history.push. */
        fun publish(p: RecordedScene<Int>.JointStaged): Boolean {
            val expected = root
            val step = p.historyProposal.step
            val forward = p.historyProposal.direction != UndoLog.Direction.UNDO
            val projection = PromotedBaseProjection.prepare(root.recorded.planes, step,
                if (forward) if (p.historyProposal.direction == UndoLog.Direction.NEW) RecordedScene.Direction.NEW
                else RecordedScene.Direction.REDO else RecordedScene.Direction.UNDO,
                root.cursor, root.legacyBaseSteps, root.promotedBases)
            val native = root.native.toMutableMap()
            for (change in step.changes) if (projection.bridgedChanges.none { it === change }) {
                val key = NativeKey(change.layerId, change.celId, change.key)
                assertEquals(if (forward) change.before else change.after, native[key])
                val after = if (forward) change.after else change.before
                if (after == null) native.remove(key) else native[key] = after
            }
            val surviving = p.historyProposal.cursor.undoSteps + p.historyProposal.cursor.redoSteps
            val next = root.copy(recorded = p.candidateState, cursor = p.historyProposal.cursor, native = native.toMap(),
                legacyBaseSteps = root.legacyBaseSteps.filter { old -> surviving.any { it === old } }.toSet())
            if (!p.canPublish() || root !== expected) return false
            root = next; assignments++
            p.adoptAfterPublication(); p.releaseRetiredAfterPublication()
            for (name in p.historyProposal.retirements) {
                if (root.graphicsEpoch != expected.graphicsEpoch) break
                driver.release(name)
            }
            return true
        }
    }

    private fun fixture(steps: List<UndoLog.Step<Int>> = emptyList(),
                        planes: Map<Pair<String, String?>, RecordedCel<Int>> = mapOf(paint to base()),
                        keys: Map<Pair<String, String?>, Set<Long>> = planes.mapValues { (_, c) ->
                            c.records.flatMap { it.samples.take(1).map { sample -> sample.x.toLong() } }.toSet() },
                        budget: Long = 1_000_000, bind: Boolean = true): Fixture {
        val driver = Driver()
        val history = UndoLog<Int>(budget, { driver.sizes++; 4 }, driver::release)
        steps.forEach(history::push)
        var upload = 800
        val scene = RecordedScene.createSaved(planes, mapOf(pen.id to pen), history, driver,
            keys, keys.mapValues { (_, declared) -> declared.associateWith { upload++ } })
        val native = mutableMapOf(NativeKey("native", null, 0) to 50)
        for (step in steps) for (change in step.changes) {
            val owner = MediaStores.parse(change.layerId)?.first ?: change.layerId
            if ((owner to change.celId) in planes) continue
            val key = NativeKey(change.layerId, change.celId, change.key)
            change.after?.let { native[key] = it } ?: native.remove(key)
        }
        val f = Fixture(history, scene, driver, Root(scene.snapshotState(), history.snapshotCursor(),
            promotedBases = planes.keys, legacyBaseSteps = steps.toSet(), native = native.toMap()))
        if (bind) f.bind()
        driver.contexts = 0; driver.sizes = 0; driver.minted.clear(); driver.released.clear(); f.rootReads = 0
        return f
    }
    private fun assertPureRefusal(f: Fixture, action: () -> Unit) {
        val root = f.root; val cursor = f.history.snapshotCursor(); val reads = f.rootReads
        assertFails { action() }
        assertSame(root, f.root); assertSame(cursor, f.history.snapshotCursor())
        assertEquals(0, f.driver.contexts); assertTrue(f.driver.lines.isEmpty()); assertTrue(f.driver.looks.isEmpty())
        assertEquals(0, f.driver.sizes)
        assertTrue(f.driver.minted.isEmpty()); assertTrue(f.driver.released.isEmpty())
        assertTrue(f.rootReads <= reads + 4) // Actual root lookup/guard only; no renderer provider.
    }

    @Test fun legacyUndoAndRedoUseOriginalStepWhileOnlyTemporaryProjectionChangesSeq0() {
        val step = oldStep(); val f = fixture(listOf(step)); val before = f.root
        f.stage(step).use { p ->
            assertSame(before, p.expectedPublication); assertSame(before, f.root)
            assertSame(step, p.historyProposal.step); assertSame(step.changes.single(), p.historyProposal.step.changes.single())
            assertTrue(p.historyProposal.step.slabs.isEmpty()); assertTrue(p.historyProposal.step.lines.isEmpty())
            assertEquals(9, p.candidateState.planes.getValue(paint).slabs.getValue(0).single().value.texture)
            assertEquals(0L, p.candidateState.planes.getValue(paint).slabs.getValue(0).single().seq)
            assertEquals("normal", f.driver.sources.single().single().op)
            assertTrue(f.publish(p))
        }
        assertSame(step, f.history.peekRedo()); assertEquals(setOf(10), f.history.heldTextures())
        f.stage(step, RecordedScene.Direction.REDO).use { assertTrue(f.publish(it)) }
        assertSame(step, f.history.peekUndo()); assertEquals(setOf(9), f.history.heldTextures())
        assertEquals(10, f.scene.planes.getValue(paint).slabs.getValue(0).single().value.texture)
        assertTrue(f.driver.lines.isEmpty()); assertTrue(f.driver.released.none { it == 9 || it == 10 })
    }

    @Test fun completeOriginalMixedNativeStepAndFrameBaseMoveTogetherWithoutTouchingSibling() {
        val step = UndoLog.Step(listOf(UndoLog.TileChange("paint", 0, 29, 30, "frame"),
            UndoLog.TileChange("native", 0, 50, 51)))
        val sibling = base()
        val f = fixture(listOf(step), mapOf(paint to sibling, frame to base(30, frame)))
        f.stage(step).use { p ->
            assertSame(step, p.historyProposal.step); assertSame(sibling, p.candidateState.planes.getValue(paint))
            assertEquals(29, p.candidateState.planes.getValue(frame).slabs.getValue(0).single().value.texture)
            assertTrue(f.publish(p))
        }
        assertEquals(50, f.root.native.getValue(NativeKey("native", null, 0)))
        assertSame(step, f.history.peekRedo()); assertEquals(setOf(30, 51), f.history.heldTextures())
        assertEquals(1, f.assignments)
    }

    @Test fun rawAddUndoThenLegacyUndoRedoThenRawRedoRetainsBaseAndOriginalSequences() {
        val old = oldStep(); val f = fixture(listOf(old))
        val raw = RecordedEdits.add(f.scene.planes,
            listOf(AddressedStroke(LineAddress("paint", null, "line"), record())), setOf(pen.id)).step
        f.stage(raw, RecordedScene.Direction.NEW).use { assertTrue(f.publish(it)) }
        assertSame(raw, f.history.peekUndo()); assertEquals(1L, f.scene.planes.getValue(paint).records.single().seq)
        f.stage(raw).use { assertTrue(f.publish(it)) }
        assertEquals(10, f.scene.planes.getValue(paint).slabs.getValue(0).single().value.texture)
        assertTrue(f.scene.planes.getValue(paint).records.isEmpty())
        f.stage(old).use { assertTrue(f.publish(it)) }
        assertEquals(9, f.scene.planes.getValue(paint).slabs.getValue(0).single().value.texture)
        f.stage(old, RecordedScene.Direction.REDO).use { assertTrue(f.publish(it)) }
        f.stage(raw, RecordedScene.Direction.REDO).use { assertTrue(f.publish(it)) }
        assertSame(raw, f.history.peekUndo()); assertEquals(1L, f.scene.planes.getValue(paint).records.single().seq)
        assertTrue(f.driver.released.none { it == 9 || it == 10 })
    }

    @Test fun publicStageJointStillRefusesLegacyWritesEvenOnPromotedRoot() {
        val step = oldStep(); val f = fixture(listOf(step))
        assertPureRefusal(f) { f.scene.stageJoint(step, RecordedScene.Direction.UNDO) }
        assertSame(step, f.history.peekUndo())
    }

    @Test fun expectedRootMustBeActualBoundRootAndCannotBeAnEquivalentWrapper() {
        val step = oldStep(); val f = fixture(listOf(step)); val copied = f.root.copy()
        assertPureRefusal(f) { f.scene.stageJointProjected(copied, step, RecordedScene.Direction.UNDO) }
        val foreign = fixture(listOf(step))
        assertPureRefusal(f) { f.scene.stageJointProjected(foreign.root, step, RecordedScene.Direction.UNDO) }
    }

    @Test fun missingTransferIdentityMissingPromotedScopeAndMissingPhysicalPlaneRefuse() {
        val step = oldStep()
        fixture(listOf(step)).also { f -> f.root = f.root.copy(baseTransferIdentity = null); assertPureRefusal(f) { f.stage(step) } }
        fixture(listOf(step)).also { f -> f.root = f.root.copy(promotedBases = emptySet()); assertPureRefusal(f) { f.stage(step) } }
        fixture(listOf(step)).also { f -> f.root = f.root.copy(promotedBases = setOf("missing" to null)); assertPureRefusal(f) { f.stage(step) } }
    }

    @Test fun uncapturedEquivalentAndNonHeadStepRefuseBeforeRendering() {
        val old = oldStep(); val copied = oldStep()
        fixture(listOf(copied)).also { f -> f.root = f.root.copy(legacyBaseSteps = setOf(old)); assertPureRefusal(f) { f.stage(copied) } }
        val newer = UndoLog.Step(listOf(UndoLog.TileChange("native", 0, 50, 51)))
        val f = fixture(listOf(old, newer))
        assertPureRefusal(f) { f.stage(old) }
        assertPureRefusal(f) { f.stage(newer, RecordedScene.Direction.REDO) }
    }

    @Test fun newLegacyWriteWrongSeq0AndEncodedMediaOnPromotedPlaneRefuse() {
        val step = oldStep(); val f = fixture(listOf(step))
        assertPureRefusal(f) { f.stage(step, RecordedScene.Direction.NEW) }
        fixture(listOf(step), mapOf(paint to base(99))).also { assertPureRefusal(it) { it.stage(step) } }
        val media = UndoLog.Step(listOf(UndoLog.TileChange(MediaStores.id("paint", "p0"), 0, 9, 10)))
        fixture(listOf(media)).also { assertPureRefusal(it) { it.stage(media) } }
    }

    @Test fun structuralAndEmptyStepsRefuseBeforeBoundRootLookup() {
        val f = fixture(); val reads = f.rootReads
        val paper = UndoLog.Step<Int>(emptyList(), paperBefore = Paper("#FFFFFF"), paperAfter = Paper("#000000"))
        assertFailsWith<IllegalArgumentException> { f.stage(paper) }
        assertEquals(reads, f.rootReads)
        assertFailsWith<IllegalArgumentException> { f.stage(UndoLog.Step(emptyList())) }
        assertEquals(reads, f.rootReads); assertEquals(0, f.driver.sizes); assertEquals(0, f.driver.contexts)
    }

    @Test fun cancelledProjectionLeaseRetiresOnlyFreshCachesNeverNativeHistorySources() {
        val step = mixedOldStep(); val f = fixture(listOf(step)); val before = f.root
        val p = f.stage(step)
        assertTrue(p.canPublish()); assertTrue(p.freshCacheTextures.isNotEmpty()); p.close(); p.close()
        assertSame(before, f.root); assertSame(step, f.history.peekUndo())
        assertEquals(f.driver.minted.toSet(), f.driver.released.toSet())
        assertEquals(f.driver.released.size, f.driver.released.toSet().size)
        assertTrue(f.driver.released.none { it in setOf(9, 10, 50, 51) })
    }

    @Test fun rendererCannotAliasAnyCompleteStepEndpointOrSiblingGlobal() {
        for (alias in listOf(9, 10, 50, 51, 700)) {
            val step = mixedOldStep(); val f = fixture(listOf(step)); val before = f.root
            f.driver.lookAlias = alias
            assertFailsWith<IllegalArgumentException> { f.stage(step) }
            assertSame(before, f.root); assertSame(step, f.history.peekUndo())
            assertFalse(alias in f.driver.released)
        }
    }

    @Test fun legacySourceCannotStealExistingSavedLookCacheEvenBeforeWarm() {
        val step = UndoLog.Step(listOf(UndoLog.TileChange("paint", 0, 800, 10)))
        val cel = RecordedCel("paint", null, listOf(record()), base().slabs)
        val f = fixture(planes = mapOf(paint to cel))
        // Install malformed history metadata only after the valid factory accepted its cache:
        // factory itself correctly refuses a saved-look upload aliasing already-held history.
        val cursor = f.history.prepareNew(step, f.root.cursor).cursor
        f.root = f.root.copy(cursor = cursor, legacyBaseSteps = setOf(step)); f.driver.sizes = 0
        assertPureRefusal(f) { f.stage(step) }
        assertEquals(setOf(800), f.scene.heldCacheTextures())
    }

    @Test fun coldBaseEditWarmsOnlyTouchedCelAndValidatesItsDeclaredOldLineUnion() {
        val step = oldStep()
        val paintCel = RecordedCel("paint", null, listOf(record()), base().slabs)
        val frameCel = RecordedCel("paint", "frame", listOf(record("sibling")), base(30, frame).slabs)
        val f = fixture(listOf(step), mapOf(paint to paintCel, frame to frameCel))
        f.stage(step).use { p ->
            assertEquals(listOf(LineAddress("paint", null, "line")), f.driver.lines)
            assertSame(frameCel, p.candidateState.planes.getValue(frame))
            assertEquals(f.root.recorded.looks.getValue(frame), p.candidateState.looks.getValue(frame))
        }
        assertFalse(f.scene.isMaterialized(paint)); assertFalse(f.scene.isMaterialized(frame))
        val bad = fixture(listOf(step), mapOf(paint to paintCel), mapOf(paint to setOf(1L)))
        val oldRoot = bad.root
        assertFailsWith<IllegalArgumentException> { bad.stage(step) }
        assertSame(oldRoot, bad.root); assertEquals(listOf(100), bad.driver.released)
        assertFalse(800 in bad.driver.released)
    }

    @Test fun sameEpochRootChangeInsideLookReclaimsFreshOutputAndStopsFurtherWork() {
        val step = oldStep(); val f = fixture(listOf(step)); val before = f.root
        f.driver.afterLook = { f.root = f.root.copy(global = f.root.global + 701) }
        assertFailsWith<IllegalStateException> { f.stage(step) }
        assertNotSame(before, f.root); assertSame(before.recorded, f.root.recorded)
        assertEquals(1, f.driver.looks.size); assertEquals(f.driver.minted, f.driver.released)
        assertSame(step, f.history.peekUndo())
    }

    @Test fun actualEpochLossInsideLookAbandonsNamesWithoutRelease() {
        val step = oldStep(); val f = fixture(listOf(step))
        f.driver.afterLook = { f.root = f.root.copy(graphicsEpoch = 8) }
        assertFailsWith<IllegalStateException> { f.stage(step) }
        assertEquals(1, f.driver.looks.size); assertTrue(f.driver.minted.isNotEmpty()); assertTrue(f.driver.released.isEmpty())
        assertSame(step, f.history.peekUndo())
    }

    @Test fun lateCompositionFailureReleasesPriorFreshLookWithoutRetiringAnyBaseEndpoint() {
        val step = UndoLog.Step(listOf(UndoLog.TileChange("paint", 0, 9, 10), UndoLog.TileChange("paint", 2, 19, 20)))
        val cel = RecordedCel.fromPixels("paint", null, linkedMapOf(0L to 10, 2L to 20))
        val f = fixture(listOf(step), mapOf(paint to cel)); val before = f.root
        f.driver.failingLook = 2
        assertFailsWith<IllegalStateException> { f.stage(step) }
        assertEquals(listOf(100), f.driver.minted); assertEquals(f.driver.minted, f.driver.released)
        assertSame(before, f.root); assertSame(step, f.history.peekUndo())
        assertTrue(f.driver.released.none { it in setOf(9, 10, 19, 20) })
    }

    @Test fun projectedRouteRequiresBothFacadesBoundAndPreparationCountersUnwindOnFailure() {
        val step = oldStep(); val f = fixture(listOf(step), bind = false)
        assertPureRefusal(f) { f.stage(step) }
        f.scene.bindState { f.rootReads++; f.root }
        assertPureRefusal(f) { f.stage(step) }
        f.history.bindCursor { f.root.cursor }
        f.stage(step).use { assertTrue(it.canPublish()) }
        assertSame(step, f.history.peekUndo())
    }

    @Test fun finalContextCallbackCannotPublishStaleProjection() {
        val step = oldStep(); val f = fixture(listOf(step)); val before = f.root
        val p = f.stage(step)
        f.driver.afterContext = { f.root = f.root.copy(global = f.root.global + 701) }
        assertFalse(p.canPublish()); p.close()
        assertSame(before.recorded, f.root.recorded); assertSame(before.cursor, f.root.cursor)
        assertEquals(f.driver.minted.toSet(), f.driver.released.toSet()); assertEquals(0, f.assignments)
    }

    @Test fun branchAndBudgetRetireOriginalHistoryEndpointsAndPruneEligibilityAfterPublication() {
        val old = oldStep(); val f = fixture(listOf(old), budget = 4)
        val raw = RecordedEdits.add(f.scene.planes,
            listOf(AddressedStroke(LineAddress("paint", null, "line"), record())), setOf(pen.id)).step
        f.stage(raw, RecordedScene.Direction.NEW).use { p ->
            assertSame(raw, p.historyProposal.step); assertEquals(listOf(9), p.historyProposal.retirements)
            assertFalse(9 in f.driver.released); assertTrue(f.publish(p))
        }
        assertSame(raw, f.history.peekUndo()); assertTrue(f.root.legacyBaseSteps.isEmpty())
        assertEquals(1, f.driver.released.count { it == 9 }); assertFalse(10 in f.driver.released)
    }

    @Test fun resourceFreeLegacyNoOpMovesExactHistoryWithoutCreatingSlabOwnership() {
        val noop = UndoLog.Step<Int>(listOf(UndoLog.TileChange("paint", 0, null, null)))
        val empty = RecordedCel.fromPixels("paint", null, emptyMap<Long, Int>())
        val f = fixture(listOf(noop), mapOf(paint to empty)); val before = f.root.recorded
        f.stage(noop).use { p ->
            assertSame(before, p.candidateState); assertSame(noop, p.historyProposal.step)
            assertTrue(p.freshCacheTextures.isEmpty()); assertTrue(f.publish(p))
        }
        assertSame(noop, f.history.peekRedo()); assertEquals(1, f.driver.contexts) // final canPublish only
        assertTrue(f.driver.minted.isEmpty()); assertTrue(f.driver.released.isEmpty())
    }
}
