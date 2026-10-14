package cc.joycreator.joybrush.androidkit.gl.lines

import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.BrushJson
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.doc.DocOps
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.paint.UndoLog
import cc.joycreator.joybrush.core.media.MediaStores
import cc.joycreator.joybrush.core.stroke.AddressedStroke
import cc.joycreator.joybrush.core.stroke.LineAddress
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.CelComposer
import cc.joycreator.joybrush.core.vector.RecordedCel
import cc.joycreator.joybrush.core.vector.RecordedEdits
import kotlin.test.*

/** Actual coordinator and history, fake textures/root. No GL, media dynamics or UI activation. */
class RecordedSceneJointTest {
    private val plane: Pair<String, String?> = "paint" to null
    private val frame: Pair<String, String?> = "paint" to "frame"
    private val pen = BrushPreset(id = "pen", name = "Pen", size = Param(8f))
    private fun record(id: String = "same", key: Long = 0, seq: Long = 1) =
        StrokeRecord(id, pen.id, 19, 0f, 1f, listOf(PenSample(key.toFloat(), 0f, 0.0)), seq = seq)
    private fun cel(key: Pair<String, String?> = plane, records: List<StrokeRecord> = listOf(record()),
                    slabs: Map<Long, List<RecordedCel.PixelSlab<Int>>> = emptyMap()) =
        RecordedCel(key.first, key.second, records, slabs)
    private fun slab(seq: Long, texture: Int, op: String = "normal") =
        RecordedCel.PixelSlab(seq, UndoLog.SlabValue(texture, op))

    private class Driver : RecordedScene.Renderer<Int> {
        var next = 100
        val minted = mutableListOf<Int>()
        val released = mutableListOf<Int>()
        val lines = mutableListOf<LineAddress>()
        val linePresets = mutableListOf<BrushPreset>()
        val looks = mutableListOf<Pair<Pair<String, String?>, Long>>()
        val lookSources = mutableListOf<List<RecordedScene.Source<Int>>>()
        var copies = 0
        var contextCalls = 0
        var context = true
        var afterContext: (() -> Unit)? = null
        var afterLine: (() -> Unit)? = null
        var afterLook: (() -> Unit)? = null
        var afterCopy: (() -> Unit)? = null
        var afterRelease: (() -> Unit)? = null
        var lineFailure: String? = null
        var failureMessage = "line failed"
        var lookFailure: Long? = null
        var lineAlias: Int? = null
        var lookAlias: Int? = null
        var lineResult: ((RecordedScene.Unit<Int>) -> RecordedScene.Unit<Int>)? = null
        private fun mint(): Int {
            val texture = next++
            minted += texture
            return texture
        }
        override fun contextCurrent(): Boolean {
            contextCalls++
            afterContext?.invoke()
            return context
        }
        override fun line(address: LineAddress, record: StrokeRecord, preset: BrushPreset): RecordedScene.Unit<Int> {
            lines += address
            linePresets += preset
            if (record.id == lineFailure) error(failureMessage)
            val result = RecordedScene.Unit(preset.blend, if (record.samples.isEmpty()) emptyMap()
                else mapOf(record.samples.first().x.toLong() to (lineAlias ?: mint())))
            afterLine?.invoke()
            return lineResult?.invoke(result) ?: result
        }
        override fun look(plane: Pair<String, String?>, key: Long, sources: List<RecordedScene.Source<Int>>): Int? {
            looks += plane to key
            lookSources += sources.toList()
            if (key == lookFailure) error("look failed")
            val result = lookAlias ?: mint()
            afterLook?.invoke()
            return result
        }
        override fun copyForBake(address: LineAddress, unit: RecordedScene.Unit<Int>): RecordedScene.Unit<Int> {
            copies++
            val result = RecordedScene.Unit(unit.op, unit.tiles.mapValues { mint() })
            afterCopy?.invoke()
            return result
        }
        override fun release(texture: Int) {
            released += texture
            afterRelease?.invoke()
        }
        fun clearCalls() { lines.clear(); linePresets.clear(); looks.clear(); lookSources.clear(); contextCalls = 0; copies = 0 }
    }

    private data class NativeKey(val layer: String, val cel: String?, val key: Long)
    private data class NativeSlabKey(val layer: String, val cel: String?, val key: Long, val seq: Long)
    private data class Root(
        override val recorded: RecordedScene.State<Int>,
        override val cursor: UndoLog.Cursor<Int>,
        override val graphicsEpoch: Long = 7,
        val native: Map<NativeKey, Int> = mapOf(NativeKey("native", null, 0) to 50),
        val nativeSlabs: Map<NativeSlabKey, UndoLog.SlabValue<Int>> = emptyMap(),
        val global: Set<Int> = setOf(700, 701),
    ) : RecordedScene.Publication<Int> {
        override val protectedTextures = global + native.values + nativeSlabs.values.map { it.texture }
    }

    private inner class Fixture(
        val driver: Driver,
        val history: UndoLog<Int>,
        val scene: RecordedScene<Int>,
        var root: Root,
    ) {
        var assignments = 0
        var rootReads = 0
        fun bind() {
            scene.bindState { rootReads++; root }
            history.bindCursor { root.cursor }
        }
        fun mixedEdit(key: Long = 2, nativeAfter: Int = 51): UndoLog.Step<Int> {
            val old = scene.planes.getValue(plane).records.first { it.id == "same" }
            return UndoLog.Step(listOf(UndoLog.TileChange("native", 0,
                root.native[NativeKey("native", null, 0)], nativeAfter)), lines = listOf(
                UndoLog.LineChange(plane.first, plane.second, "same", CelComposer.Line(old.seq, old),
                    CelComposer.Line(old.seq, old.copy(samples = listOf(PenSample(key.toFloat(), 0f, 0.0)))))))
        }
        /** Test owner stages native maps privately, then publishes all slices in one assignment. */
        fun publish(proposal: RecordedScene<Int>.JointStaged): Boolean {
            val expected = root
            val step = proposal.historyProposal.step
            val forward = proposal.historyProposal.direction != UndoLog.Direction.UNDO
            val native = root.native.toMutableMap()
            for (c in step.changes) {
                val key = NativeKey(c.layerId, c.celId, c.key)
                assertEquals(if (forward) c.before else c.after, native[key])
                val output = if (forward) c.after else c.before
                if (output == null) native.remove(key) else native[key] = output
            }
            val nativeSlabs = root.nativeSlabs.toMutableMap()
            for (c in step.slabs.filter { (it.layerId to it.celId) !in root.recorded.planes }) {
                val key = NativeSlabKey(c.layerId, c.celId, c.key, c.seq)
                val output = if (forward) c.after else c.before
                if (output == null) nativeSlabs.remove(key) else nativeSlabs[key] = output
            }
            val retiredHistory = proposal.historyProposal.retirements
            val next = root.copy(recorded = proposal.candidateState, cursor = proposal.historyProposal.cursor,
                native = native.toMap(), nativeSlabs = nativeSlabs.toMap())
            if (!proposal.canPublish() || root !== expected || root.graphicsEpoch != expected.graphicsEpoch) return false
            root = next
            assignments++
            proposal.adoptAfterPublication()
            proposal.releaseRetiredAfterPublication()
            for (texture in retiredHistory) {
                if (root.graphicsEpoch != expected.graphicsEpoch) break
                driver.release(texture)
            }
            return true
        }
    }

    private fun fixture(cold: Boolean = true,
                        planes: Map<Pair<String, String?>, RecordedCel<Int>> = mapOf(plane to cel()),
                        keys: Map<Pair<String, String?>, Set<Long>> = planes.mapValues { (_, c) ->
                            c.records.flatMap { it.samples.take(1).map { sample -> sample.x.toLong() } }.toSet() },
                        driver: Driver = Driver(), sizeOf: (Int) -> Long = { 4 }, budget: Long = 1_000_000,
                        bind: Boolean = true): Fixture {
        val history = UndoLog<Int>(budget, sizeOf, driver::release)
        var upload = 10
        val scene = if (cold) RecordedScene.createSaved(planes, mapOf(pen.id to pen), history, driver,
            keys, keys.mapValues { (_, tiles) -> tiles.associateWith { upload++ } })
        else RecordedScene.create(planes, mapOf(pen.id to pen), history, driver)
        val f = Fixture(driver, history, scene, Root(scene.snapshotState(), history.snapshotCursor()))
        if (bind) f.bind()
        driver.clearCalls(); driver.minted.clear(); driver.released.clear()
        return f
    }

    @Test fun mixedStepStagesDetachedThenOneAssignmentPublishesNativeRecordedAndCompleteHistory() {
        val f = fixture()
        val before = f.root
        val step = f.mixedEdit()
        f.scene.stageJoint(step).use { p ->
            assertSame(before, f.root)
            assertSame(before.recorded, f.scene.snapshotState())
            assertSame(before.cursor, f.history.snapshotCursor())
            assertSame(step, p.historyProposal.step)
            assertNotSame(before.recorded, p.candidateState)
            assertEquals(setOf(0L, 2L), p.affected.getValue(plane))
            assertTrue(f.publish(p))
            assertSame(p.candidateState, f.scene.snapshotState())
            assertSame(p.historyProposal.cursor, f.history.snapshotCursor())
        }
        assertEquals(1, f.assignments)
        assertEquals(51, f.root.native[NativeKey("native", null, 0)])
        assertEquals(setOf(2L), f.scene.lookKeys(plane))
        assertSame(step, f.history.peekUndo())
        assertEquals(setOf(50), f.history.heldTextures())
        assertEquals(setOf(10, 100), f.driver.released.toSet()) // Uploaded old look + transient old unit.
        assertFalse(50 in f.driver.released)
        assertFalse(51 in f.driver.released)
    }

    @Test fun completeMixedUndoRedoMovesEverySliceWithoutIndependentHistoryPublication() {
        val f = fixture()
        val step = f.mixedEdit()
        f.scene.stageJoint(step).use { assertTrue(f.publish(it)) }
        f.scene.stageJoint(step, RecordedScene.Direction.UNDO).use { assertTrue(f.publish(it)) }
        assertEquals(50, f.root.native[NativeKey("native", null, 0)])
        assertEquals(setOf(0L), f.scene.lookKeys(plane))
        assertSame(step, f.history.peekRedo())
        assertEquals(setOf(51), f.history.heldTextures())
        f.scene.stageJoint(step, RecordedScene.Direction.REDO).use { assertTrue(f.publish(it)) }
        assertEquals(51, f.root.native[NativeKey("native", null, 0)])
        assertEquals(setOf(2L), f.scene.lookKeys(plane))
        assertSame(step, f.history.peekUndo())
        assertEquals(3, f.assignments)
        assertEquals(f.driver.released.size, f.driver.released.toSet().size)
    }

    @Test fun nativeOnlyTransactionLeavesAllColdPlanesAndLooksUnmaterializedWithZeroRendererCalls() {
        val f = fixture(planes = mapOf(plane to cel(), frame to cel(frame)))
        val old = f.root.recorded
        val step = UndoLog.Step(listOf(UndoLog.TileChange("native", 0, 50, 51)))
        f.scene.stageJoint(step).use { p ->
            assertSame(old, p.candidateState)
            assertTrue(p.freshCacheTextures.isEmpty())
            assertTrue(p.retiredCacheTextures.isEmpty())
            assertTrue(p.affected.isEmpty())
            assertTrue(f.driver.lines.isEmpty())
            assertTrue(f.driver.looks.isEmpty())
            assertEquals(0, f.driver.copies)
            assertTrue(f.publish(p))
        }
        assertSame(old, f.scene.snapshotState())
        assertFalse(f.scene.isMaterialized(plane))
        assertFalse(f.scene.isMaterialized(frame))
        assertEquals(10, f.scene.look(plane, 0))
        assertEquals(11, f.scene.look(frame, 0))
    }

    @Test fun coldMixedEditWarmsOnlyTouchedPhysicalCelAndKeepsSiblingSavedPixelsOwned() {
        val f = fixture(planes = mapOf(plane to cel(), frame to cel(frame)))
        val sibling = f.scene.planes.getValue(frame)
        val siblingLook = f.scene.look(frame, 0)
        f.scene.stageJoint(f.mixedEdit()).use { assertTrue(f.publish(it)) }
        assertTrue(f.scene.isMaterialized(plane))
        assertFalse(f.scene.isMaterialized(frame))
        assertSame(sibling, f.scene.planes.getValue(frame))
        assertEquals(siblingLook, f.scene.look(frame, 0))
        assertTrue(f.driver.lines.all { it.layerId == plane.first && it.celId == plane.second })
        assertFalse(siblingLook in f.driver.released)
    }

    @Test fun recordedAndNativeSlabProjectionPreservesFullStepOperatorsAndSequence() {
        val f = fixture(planes = mapOf(plane to cel(slabs = mapOf(0L to listOf(slab(0, 30))))))
        val full = UndoLog.Step<Int>(emptyList(), slabs = listOf(
            UndoLog.SlabChange("paint", null, 0, 0, UndoLog.SlabValue(30, "normal"), UndoLog.SlabValue(31, "erase")),
            UndoLog.SlabChange("native-slab", "physical", 7, 9, null, UndoLog.SlabValue(60, "behind"))))
        f.scene.stageJoint(full).use { p ->
            assertSame(full, p.historyProposal.step)
            assertEquals(2, p.historyProposal.step.slabs.size)
            assertEquals(31, p.candidateState.planes.getValue(plane).slabs.getValue(0).single().value.texture)
            assertTrue(f.publish(p))
        }
        assertEquals("behind", f.root.nativeSlabs.values.single().op)
        assertEquals(9L, f.root.nativeSlabs.keys.single().seq)
        assertSame(full, f.history.peekUndo())
        assertEquals(setOf(30), f.history.heldTextures())
        assertFalse(f.driver.released.any { it in setOf(30, 31, 60) })
        assertEquals(listOf(0L, 1L), f.driver.lookSources.last().map { it.seq })
        assertEquals(listOf("erase", "normal"), f.driver.lookSources.last().map { it.op })
    }

    @Test fun cancelledLeaseReleasesFreshCachesOnlyAndKeepsColdRootAndBorrowedEndpoints() {
        val f = fixture()
        val before = f.root
        val p = f.scene.stageJoint(f.mixedEdit())
        val fresh = p.freshCacheTextures
        assertFailsWith<IllegalStateException> { p.releaseRetiredAfterPublication() }
        p.close(); p.close()
        assertSame(before, f.root)
        assertSame(before.cursor, f.history.snapshotCursor())
        assertFalse(f.scene.isMaterialized(plane))
        assertEquals(fresh, f.driver.released.toSet())
        assertEquals(fresh.size, f.driver.released.size)
        assertFalse(f.driver.released.any { it in setOf(10, 50, 51) })
    }

    @Test fun adoptedLeaseCloseNeverDeletesCandidateAndRetirementIsIdempotent() {
        val f = fixture()
        val p = f.scene.stageJoint(f.mixedEdit())
        assertTrue(f.publish(p))
        val released = f.driver.released.toList()
        val live = f.scene.heldCacheTextures()
        p.close(); p.releaseRetiredAfterPublication()
        assertEquals(released, f.driver.released)
        assertTrue(live.none { it in released })
        assertFailsWith<IllegalStateException> { p.adoptAfterPublication() }
        assertFailsWith<IllegalStateException> { p.canPublish() }
    }

    @Test fun unsupportedMetadataRefusesBeforeRootContextBudgetOrRenderCallbacks() {
        val f = fixture()
        var id = 0
        val doc = DocOps.newDocument("d", "name", 20, 20) { "id-${++id}" }
        val full = UndoLog.Step(f.mixedEdit().changes, documentBefore = doc, documentAfter = doc,
            lines = f.mixedEdit().lines)
        f.driver.clearCalls()
        val reads = f.rootReads
        assertFailsWith<IllegalArgumentException> { f.scene.stageJoint(full) }
        assertEquals(reads, f.rootReads)
        assertEquals(0, f.driver.contextCalls)
        assertTrue(f.driver.lines.isEmpty())
        assertTrue(f.driver.looks.isEmpty())
        assertTrue(f.driver.released.isEmpty())
        assertFalse(f.history.canUndo)
    }

    @Test fun legacyTileChangeOnRecordedPlaneRefusesBeforeRendering() {
        val f = fixture()
        val full = UndoLog.Step(listOf(UndoLog.TileChange("paint", 0, null, 99)), lines = f.mixedEdit().lines)
        assertFailsWith<IllegalArgumentException> { f.scene.stageJoint(full) }
        assertTrue(f.driver.lines.isEmpty())
        assertEquals(0, f.driver.contextCalls)
        assertFalse(f.history.canUndo)
    }

    @Test fun unknownLineCelRefusesRatherThanDroppingItsRecordedHalf() {
        val f = fixture()
        val old = record()
        val full = UndoLog.Step<Int>(emptyList(), lines = listOf(
            UndoLog.LineChange("missing", null, old.id, CelComposer.Line(1, old), null)))
        assertFailsWith<IllegalArgumentException> { f.scene.stageJoint(full) }
        assertTrue(f.driver.lines.isEmpty())
        assertEquals(0, f.driver.contextCalls)
        assertFalse(f.history.canUndo)
    }

    @Test fun allUnconvertedSceneMutatorsRefuseWhenBoundBeforeRendererWork() {
        val f = fixture()
        val edit = RecordedEdits.replace(f.scene.planes, listOf(AddressedStroke(LineAddress("paint", null, "same"),
            record(key = 2))), setOf(pen.id))
        val before = f.root
        assertFailsWith<IllegalStateException> { f.scene.stage(edit) }
        assertFailsWith<IllegalStateException> { f.scene.stageBake(emptySet()) }
        assertFailsWith<IllegalStateException> { f.scene.forget() }
        assertFailsWith<IllegalStateException> { f.scene.close() }
        assertSame(before, f.root)
        assertEquals(0, f.driver.contextCalls)
        assertTrue(f.driver.lines.isEmpty())
        assertTrue(f.driver.looks.isEmpty())
        assertEquals(0, f.driver.copies)
        assertTrue(f.driver.released.isEmpty())
    }

    @Test fun bindingWithAnOutstandingLocalLeaseRefusesBeforeProviderThenSucceedsAfterRollback() {
        val f = fixture(cold = false, bind = false)
        val edit = RecordedEdits.replace(f.scene.planes, listOf(AddressedStroke(LineAddress("paint", null, "same"),
            record(key = 2))), setOf(pen.id))
        val lease = f.scene.stage(edit)
        var called = false
        assertFailsWith<IllegalStateException> { f.scene.bindState { called = true; f.root } }
        assertFalse(called)
        lease.close()
        f.bind()
        assertTrue(f.scene.isStateBound)
        assertTrue(f.history.isCursorBound)
    }

    @Test fun sceneBindingRechecksReentrantSceneAndHistoryChanges() {
        val f = fixture(bind = false)
        val old = f.root
        assertFailsWith<IllegalStateException> {
            f.scene.bindState { f.scene.forget(); old }
        }
        assertFalse(f.scene.isStateBound)
        assertTrue(f.scene.planes.isEmpty())
        val other = fixture(bind = false)
        val initial = other.root
        assertFailsWith<IllegalStateException> {
            other.scene.bindState {
                other.history.push(UndoLog.Step(listOf(UndoLog.TileChange("native", 0, 50, 51))))
                initial
            }
        }
        assertFalse(other.scene.isStateBound)
        assertEquals(1, other.history.undoDepth)
    }

    @Test fun foreignSceneStateAndSecondBindingAreRefusedWithoutTakingOwnership() {
        val f = fixture(bind = false)
        val other = fixture(bind = false)
        assertFailsWith<IllegalArgumentException> { f.scene.bindState { other.root } }
        assertFalse(f.scene.isStateBound)
        f.bind()
        var called = false
        assertFailsWith<IllegalStateException> { f.scene.bindState { called = true; f.root } }
        assertFalse(called)
        assertTrue(f.driver.released.isEmpty())
    }

    @Test fun aSceneBindingWithoutMatchingBoundHistoryCannotStageJointWork() {
        val f = fixture(bind = false)
        f.scene.bindState { f.root }
        assertFailsWith<IllegalStateException> { f.scene.stageJoint(f.mixedEdit()) }
        assertTrue(f.driver.lines.isEmpty())
        assertEquals(0, f.driver.contextCalls)
    }

    @Test fun staleRootAndCursorProposalsPreserveWinningRootAndReleaseOnlyLoserFreshCaches() {
        val f = fixture()
        val first = f.scene.stageJoint(f.mixedEdit(2, 51))
        val second = f.scene.stageJoint(f.mixedEdit(3, 52))
        assertTrue(f.publish(second))
        val winner = f.root
        assertFalse(first.canPublish())
        first.close(); second.close()
        assertSame(winner, f.root)
        assertEquals(setOf(3L), f.scene.lookKeys(plane))
        assertSame(second.historyProposal.step, f.history.peekUndo())
        assertTrue(f.scene.heldCacheTextures().none { it in f.driver.released })
        assertEquals(f.driver.released.size, f.driver.released.toSet().size)
    }

    @Test fun changedNativeRootWithSameCursorMakesRecordedProposalStale() {
        val f = fixture()
        val p = f.scene.stageJoint(f.mixedEdit())
        f.root = f.root.copy(native = f.root.native + (NativeKey("distant", null, 8) to 80))
        assertTrue(f.history.isCurrent(p.historyProposal))
        assertFalse(p.canPublish())
        p.close()
        assertEquals(80, f.root.native[NativeKey("distant", null, 8)])
        assertFalse(f.scene.isMaterialized(plane))
        assertFalse(80 in f.driver.released)
    }

    @Test fun finalContextCallbackRootMutationIsCaughtBeforePublication() {
        val f = fixture()
        val p = f.scene.stageJoint(f.mixedEdit())
        f.driver.afterContext = { f.root = f.root.copy(global = f.root.global + 702) }
        assertFalse(p.canPublish())
        f.driver.afterContext = null
        p.close()
        assertFalse(f.history.canUndo)
        assertEquals(0, f.assignments)
    }

    @Test fun wrongRendererContextRefusesAndRollbackKeepsRootAndHistory() {
        val f = fixture()
        val before = f.root
        f.driver.context = false
        assertFailsWith<IllegalStateException> { f.scene.stageJoint(f.mixedEdit()) }
        assertSame(before, f.root)
        assertTrue(f.driver.lines.isEmpty())
        f.driver.context = true
        val p = f.scene.stageJoint(f.mixedEdit())
        f.driver.context = false
        assertFalse(p.canPublish())
        p.close()
        assertSame(before, f.root)
        assertFalse(f.history.canUndo)
    }

    @Test fun coldDeclarationMismatchRefusesEditAndReclaimsUnitsWithoutDeletingSavedLook() {
        for (keys in listOf(emptySet(), setOf(0L, 9L))) {
            val f = fixture(keys = mapOf(plane to keys))
            val before = f.root
            assertFailsWith<IllegalArgumentException> { f.scene.stageJoint(f.mixedEdit()) }
            assertSame(before, f.root)
            assertFalse(f.scene.isMaterialized(plane))
            assertFalse(f.history.canUndo)
            assertEquals(f.driver.minted, f.driver.released)
            assertTrue(f.scene.heldCacheTextures().none { it in f.driver.released })
        }
    }

    @Test fun coldNoDabEmptyIndexRemainsAuthoritativeThroughJointMaterialization() {
        val f = fixture(planes = mapOf(plane to cel(records = listOf(record().copy(samples = emptyList())))))
        val old = f.scene.planes.getValue(plane).records.single()
        val step = UndoLog.Step<Int>(emptyList(), lines = listOf(UndoLog.LineChange("paint", null, "same",
            CelComposer.Line(1, old), CelComposer.Line(1, old.copy(locked = true)))))
        f.scene.stageJoint(step).use { p ->
            assertTrue(p.freshCacheTextures.isEmpty())
            assertTrue(p.candidateState.units.values.single().unit.tiles.isEmpty())
            assertTrue(f.publish(p))
        }
        assertTrue(f.scene.isMaterialized(plane))
        assertTrue(f.scene.savedLookKeys(plane).isEmpty())
        assertTrue(f.scene.lookKeys(plane).isEmpty())
        assertEquals(1, f.driver.lines.size)
        assertTrue(f.driver.looks.isEmpty())
    }

    @Test fun coldRendererCapOrAssetFailurePreservesRawSavedPixelsAndCursor() {
        for (reason in listOf("dab cap", "grain asset unavailable")) {
            val f = fixture()
            val before = f.root
            f.driver.lineFailure = "same"
            f.driver.failureMessage = reason
            val failure = assertFailsWith<IllegalStateException> { f.scene.stageJoint(f.mixedEdit()) }
            assertEquals(reason, failure.message)
            assertSame(before, f.root)
            assertEquals(10, f.scene.look(plane, 0))
            assertFalse(f.scene.isMaterialized(plane))
            assertTrue(f.driver.released.isEmpty())
        }
    }

    @Test fun budgetFailureHappensBeforeRendererAndRetainsAllSlicesAndOwnership() {
        var fail = false
        val f = fixture(sizeOf = { if (fail) error("budget") else 4 })
        val first = UndoLog.Step(listOf(UndoLog.TileChange("native", 0, 50, 51)))
        f.scene.stageJoint(first).use { assertTrue(f.publish(it)) }
        val before = f.root
        f.driver.clearCalls()
        fail = true
        assertFailsWith<IllegalStateException> { f.scene.stageJoint(f.mixedEdit(nativeAfter = 52)) }
        assertSame(before, f.root)
        assertTrue(f.driver.lines.isEmpty())
        assertEquals(0, f.driver.contextCalls)
        assertTrue(f.driver.released.isEmpty())
    }

    @Test fun protectedNativeSiblingGlobalAndCompleteStepEndpointAliasesRefuseWithoutDeletingOwners() {
        for (alias in listOf(50, 51, 700, 701)) {
            val f = fixture()
            val before = f.root
            f.driver.lineAlias = alias
            assertFailsWith<IllegalArgumentException> { f.scene.stageJoint(f.mixedEdit()) }
            assertSame(before, f.root)
            assertFalse(alias in f.driver.released)
            assertFalse(f.history.canUndo)
        }
    }

    @Test fun invalidAliasBatchCleansFreshSiblingsAndDoesNotDeleteProtectedTexture() {
        val f = fixture()
        f.driver.lineResult = { unit -> RecordedScene.Unit(unit.op, unit.tiles + (9L to 700)) }
        assertFailsWith<IllegalArgumentException> { f.scene.stageJoint(f.mixedEdit()) }
        assertEquals(listOf(100), f.driver.released)
        assertFalse(700 in f.driver.released)
        assertFalse(f.scene.isMaterialized(plane))
    }

    @Test fun lookAliasToNativeOutputRefusesAndCleansAllFreshUnits() {
        val f = fixture()
        val before = f.root
        f.driver.lookAlias = 51
        assertFailsWith<IllegalArgumentException> { f.scene.stageJoint(f.mixedEdit()) }
        assertSame(before, f.root)
        assertEquals(setOf(100, 101), f.driver.released.toSet())
        assertFalse(51 in f.driver.released)
        assertFalse(10 in f.driver.released)
    }

    @Test fun sameEpochRootChangeInsideLineStopsBeforeNextRendererWorkAndReclaimsReturnedFreshUnit() {
        val f = fixture(planes = mapOf(plane to cel(records = listOf(record(), record("second", 4, 2)))))
        f.driver.afterLine = { f.root = f.root.copy(global = f.root.global + 702) }
        assertFailsWith<IllegalStateException> { f.scene.stageJoint(f.mixedEdit()) }
        assertEquals(1, f.driver.lines.size)
        assertTrue(f.driver.looks.isEmpty())
        assertEquals(0, f.driver.copies)
        assertEquals(listOf(100), f.driver.released)
        assertFalse(f.scene.isMaterialized(plane))
    }

    @Test fun epochResetInsideLineStopsAllFurtherWorkWithoutDeletingOldNames() {
        val f = fixture(planes = mapOf(plane to cel(records = listOf(record(), record("second", 4, 2)))))
        f.driver.afterLine = { f.root = f.root.copy(graphicsEpoch = 8) }
        assertFailsWith<IllegalStateException> { f.scene.stageJoint(f.mixedEdit()) }
        assertEquals(1, f.driver.lines.size)
        assertTrue(f.driver.looks.isEmpty())
        assertEquals(0, f.driver.copies)
        assertTrue(f.driver.released.isEmpty())
        assertFalse(f.history.canUndo)
    }

    @Test fun lateLookFailureReclaimsAllNewCachesAndPreservesNativeAndSavedRoot() {
        val f = fixture()
        val before = f.root
        f.driver.lookFailure = 2
        assertFailsWith<IllegalStateException> { f.scene.stageJoint(f.mixedEdit()) }
        assertSame(before, f.root)
        assertEquals(f.driver.minted.toSet(), f.driver.released.toSet())
        assertEquals(f.driver.released.size, f.driver.released.toSet().size)
        assertFalse(10 in f.driver.released)
        assertFalse(50 in f.driver.released)
    }

    @Test fun rollbackAndPostPublicationRetirementCheckEpochBeforeEachRelease() {
        val cancelled = fixture()
        val p = cancelled.scene.stageJoint(cancelled.mixedEdit())
        cancelled.driver.afterRelease = { cancelled.root = cancelled.root.copy(graphicsEpoch = 8) }
        p.close()
        assertEquals(1, cancelled.driver.released.size)
        val accepted = fixture()
        val q = accepted.scene.stageJoint(accepted.mixedEdit())
        accepted.driver.afterRelease = { accepted.root = accepted.root.copy(graphicsEpoch = 8) }
        assertTrue(accepted.publish(q))
        assertEquals(1, accepted.driver.released.size)
        q.close(); q.releaseRetiredAfterPublication()
        assertEquals(1, accepted.driver.released.size)
    }

    @Test fun unboundFactoriesStagesBakeLifecycleAndCursorAbaProtectionStillWork() {
        val f = fixture(cold = false, bind = false)
        val old = f.scene.snapshotState()
        val edit = RecordedEdits.replace(f.scene.planes, listOf(AddressedStroke(LineAddress("paint", null, "same"),
            record(key = 2))), setOf(pen.id))
        val native = UndoLog.Step(listOf(UndoLog.TileChange("native", 0, 50, 51)))
        f.history.push(native)
        val stale = f.scene.stage(edit)
        f.history.undo(); f.history.redo()
        assertFalse(stale.commit()) // Heads are not a sufficient substitute for cursor identity.
        stale.close()
        f.scene.stage(edit).use { assertTrue(it.commit()) }
        assertNotSame(old, f.scene.snapshotState())
        f.scene.stageBake(setOf(LineAddress("paint", null, "same"))).use { assertTrue(it.commit()) }
        assertEquals(1, f.driver.copies)
        assertTrue(f.scene.planes.getValue(plane).records.isEmpty())
        val sources = f.scene.planes.getValue(plane).slabs.values.flatMap { it.map { slab -> slab.value.texture } }
        f.scene.close(); f.scene.close()
        assertTrue(f.scene.planes.isEmpty())
        assertTrue(sources.none { it in f.driver.released })
    }

    @Test fun completedLegacyLeaseCommitRefusesAfterBindingBeforeAnyContextCallback() {
        val f = fixture(cold = false, bind = false)
        val edit = RecordedEdits.replace(f.scene.planes, listOf(AddressedStroke(LineAddress("paint", null, "same"),
            record(key = 2))), setOf(pen.id))
        val oldLease = f.scene.stage(edit)
        assertTrue(oldLease.commit())
        f.root = f.root.copy(recorded = f.scene.snapshotState(), cursor = f.history.snapshotCursor())
        f.bind()
        f.driver.clearCalls()
        assertFailsWith<IllegalStateException> { oldLease.commit() }
        assertEquals(0, f.driver.contextCalls)
    }

    @Test fun rendererAliasToHistoryHeldNativeBeforeRefusesWithoutRetiringItsOwner() {
        val f = fixture()
        val native = UndoLog.Step(listOf(UndoLog.TileChange("native", 0, 50, 51)))
        f.scene.stageJoint(native).use { assertTrue(f.publish(it)) }
        assertEquals(setOf(50), f.history.heldTextures())
        val before = f.root
        f.driver.lineAlias = 50
        assertFailsWith<IllegalArgumentException> { f.scene.stageJoint(f.mixedEdit(nativeAfter = 52)) }
        assertSame(before, f.root)
        assertFalse(50 in f.driver.released)
        assertSame(native, f.history.peekUndo())
    }

    @Test fun fullMixedStepBranchAndBudgetRetireOnlyHistoryOwnedNativeEndpointsAfterPublication() {
        val branch = fixture()
        val first = branch.mixedEdit()
        branch.scene.stageJoint(first).use { assertTrue(branch.publish(it)) }
        branch.scene.stageJoint(first, RecordedScene.Direction.UNDO).use { assertTrue(branch.publish(it)) }
        val next = branch.mixedEdit(3, 52)
        branch.scene.stageJoint(next).use { p ->
            assertEquals(listOf(51), p.historyProposal.retirements)
            assertFalse(51 in p.retiredCacheTextures)
            assertFalse(51 in p.freshCacheTextures)
            assertFalse(51 in branch.driver.released)
            assertTrue(branch.publish(p))
        }
        assertEquals(1, branch.driver.released.count { it == 51 })
        assertEquals(setOf(50), branch.history.heldTextures())
        assertFalse(branch.history.canRedo)

        val trimmed = fixture(budget = 1)
        trimmed.scene.stageJoint(trimmed.mixedEdit()).use { assertTrue(trimmed.publish(it)) }
        trimmed.scene.stageJoint(trimmed.mixedEdit(3, 52)).use { p ->
            assertEquals(listOf(50), p.historyProposal.retirements)
            assertFalse(50 in p.retiredCacheTextures)
            assertFalse(50 in trimmed.driver.released)
            assertTrue(trimmed.publish(p))
        }
        assertEquals(1, trimmed.history.undoDepth)
        assertEquals(setOf(51), trimmed.history.heldTextures())
        assertEquals(1, trimmed.driver.released.count { it == 50 })
        assertFalse(51 in trimmed.driver.released)
        assertFalse(52 in trimmed.driver.released)
    }

    @Test fun sameEpochRootChangeInsideLookReclaimsAllFreshCachesAndStopsFurtherComposition() {
        val f = fixture(planes = mapOf(plane to cel(records = listOf(record(), record("second", 4, 2)))))
        // Change both lines so there would otherwise be another look after key 2.
        val full = UndoLog.Step(f.mixedEdit().changes, lines = f.mixedEdit().lines +
            UndoLog.LineChange("paint", null, "second", CelComposer.Line(2, record("second", 4, 2)),
                CelComposer.Line(2, record("second", 5, 2))))
        f.driver.afterLook = { f.root = f.root.copy(global = f.root.global + 702) }
        assertFailsWith<IllegalStateException> { f.scene.stageJoint(full) }
        assertEquals(1, f.driver.looks.size)
        assertEquals(f.driver.minted.toSet(), f.driver.released.toSet())
        assertEquals(f.driver.released.size, f.driver.released.toSet().size)
        assertFalse(10 in f.driver.released)
        assertFalse(f.scene.isMaterialized(plane))
    }

    @Test fun mediaPayloadChangesOnRecordedSharedAndFrameOwnersRefuseBeforeCallbacks() {
        for (destination in listOf(plane, frame)) for (store in MediaStores.PAYLOAD) {
            val f = fixture(planes = mapOf(plane to cel(), frame to cel(frame)))
            val edit = f.mixedEdit()
            val full = UndoLog.Step(edit.changes + UndoLog.TileChange(MediaStores.id(destination.first, store),
                0, null, 60, destination.second), lines = edit.lines)
            val before = f.root
            val reads = f.rootReads
            assertFailsWith<IllegalArgumentException> { f.scene.stageJoint(full) }
            assertSame(before, f.root)
            assertEquals(reads + 1, f.rootReads) // One immutable root lookup, no work after scope refusal.
            assertEquals(0, f.driver.contextCalls)
            assertTrue(f.driver.lines.isEmpty())
            assertTrue(f.driver.looks.isEmpty())
            assertTrue(f.driver.released.isEmpty())
            assertFalse(f.history.canUndo)
        }
    }

    @Test fun disjointNativeMediaPublishesWithRecordedEditAsTheSameCompleteStep() {
        val f = fixture()
        val edit = f.mixedEdit()
        val pigment = MediaStores.id("native", "p0")
        val ground = MediaStores.id("native", MediaStores.GROUND)
        val full = UndoLog.Step(edit.changes + listOf(
            UndoLog.TileChange(pigment, 0, null, 60), UndoLog.TileChange(ground, 0, null, 61)), lines = edit.lines)
        f.scene.stageJoint(full).use { p ->
            assertSame(full, p.historyProposal.step)
            assertEquals(listOf("native", pigment, ground), p.historyProposal.step.changes.map { it.layerId })
            assertTrue(f.publish(p))
        }
        assertEquals(60, f.root.native[NativeKey(pigment, null, 0)])
        assertEquals(61, f.root.native[NativeKey(ground, null, 0)])
        assertSame(full, f.history.peekUndo())
        assertEquals(1, f.assignments)
        assertFalse(f.driver.released.any { it in setOf(50, 51, 60, 61) })
    }

    @Test fun mediaOwnerNormalizationDoesNotCollapseASeparateMaskStoreIntoThePaintPlane() {
        val f = fixture()
        val mask = "paint#mask"
        assertNull(MediaStores.parse(mask))
        val edit = f.mixedEdit()
        val full = UndoLog.Step(edit.changes + UndoLog.TileChange(mask, 0, null, 60), lines = edit.lines)
        f.scene.stageJoint(full).use { p ->
            assertSame(full, p.historyProposal.step)
            assertEquals(mask, p.historyProposal.step.changes.last().layerId)
        }
        // Actual clipping compatibility belongs to the engine adapter, not MediaStores.parse.
        assertFalse(f.history.canUndo)
    }

    @Test fun localContextWarmRenderAndBakeCallbacksCannotBindBeforeAProposalExists() {
        for (boundary in listOf("context", "warm", "render", "copy")) {
            val f = fixture(cold = boundary != "render" && boundary != "copy", bind = false)
            val edit = RecordedEdits.replace(f.scene.planes, listOf(AddressedStroke(LineAddress("paint", null, "same"),
                record(key = 2))), setOf(pen.id))
            var attempts = 0
            var providerCalls = 0
            val attempt = {
                attempts++
                assertFailsWith<IllegalStateException> { f.scene.bindState { providerCalls++; f.root } }
                Unit
            }
            when (boundary) {
                "context" -> f.driver.afterContext = attempt
                "warm", "render" -> f.driver.afterLine = attempt
                "copy" -> f.driver.afterCopy = attempt
            }
            val proposal = if (boundary == "copy") f.scene.stageBake(setOf(LineAddress("paint", null, "same")))
                else f.scene.stage(edit)
            assertTrue(attempts > 0, boundary)
            assertEquals(0, providerCalls, boundary)
            assertFalse(f.scene.isStateBound, boundary)
            proposal.close()
            f.driver.afterContext = null; f.driver.afterLine = null; f.driver.afterCopy = null
            f.bind() // Counter also unwinds after successful staging/rollback.
            assertTrue(f.scene.isStateBound)
        }
    }

    @Test fun localPreparationCounterUnwindsAfterFailureAndAllowsBinding() {
        val f = fixture(bind = false)
        val edit = RecordedEdits.replace(f.scene.planes, listOf(AddressedStroke(LineAddress("paint", null, "same"),
            record(key = 2))), setOf(pen.id))
        f.driver.lineFailure = "same"
        assertFailsWith<IllegalStateException> { f.scene.stage(edit) }
        f.bind()
        assertTrue(f.scene.isStateBound)
        assertTrue(f.history.isCursorBound)
    }

    @Test fun emptyStateStagesAResetWithoutIndependentPublicationOrResourceRelease() {
        val f = fixture()
        val before = f.root
        val empty = f.scene.emptyState()
        assertTrue(empty.planes.isEmpty())
        assertTrue(empty.cacheTextures().isEmpty())
        assertTrue(empty.brushes.isEmpty())
        assertSame(before, f.root)
        assertSame(before.recorded, f.scene.snapshotState())
        assertEquals(10, f.scene.look(plane, 0))
        assertTrue(f.driver.released.isEmpty())
        assertFailsWith<IllegalStateException> { f.scene.forget() }
        assertFailsWith<IllegalStateException> { f.scene.close() }
    }

    @Test fun unboundBudgetCallbackSceneResetCannotRepublishOldNamesIntoReplacementState() {
        var reset = false
        lateinit var f: Fixture
        f = fixture(bind = false, sizeOf = {
            if (reset) {
                reset = false
                f.scene.forget()
            }
            4
        })
        val native = UndoLog.Step(listOf(UndoLog.TileChange("native", 0, 50, 51)))
        f.history.push(native)
        val edit = RecordedEdits.replace(f.scene.planes, listOf(AddressedStroke(LineAddress("paint", null, "same"),
            record(key = 2))), setOf(pen.id))
        val p = f.scene.stage(edit)
        val cursor = f.history.snapshotCursor()
        reset = true
        assertFailsWith<IllegalStateException> { p.commit() }
        assertSame(cursor, f.history.snapshotCursor())
        assertTrue(f.scene.planes.isEmpty())
        assertTrue(f.driver.released.isEmpty())
        p.close()
    }

    @Test fun nativeOrRecordedSourceEndpointsCannotStealCurrentSavedCacheOwnership() {
        for (source in listOf("native", "recorded")) {
            val f = fixture(planes = mapOf(plane to cel(slabs = mapOf(0L to listOf(slab(0, 30))))))
            val before = f.root
            val saved = assertNotNull(f.scene.look(plane, 0))
            val full = if (source == "native") UndoLog.Step(listOf(UndoLog.TileChange("native", 0, 50, saved)))
                else UndoLog.Step<Int>(emptyList(), slabs = listOf(UndoLog.SlabChange("paint", null, 0, 0,
                    UndoLog.SlabValue(30, "normal"), UndoLog.SlabValue(saved, "normal"))))
            assertFailsWith<IllegalArgumentException> { f.scene.stageJoint(full) }
            assertSame(before, f.root)
            assertEquals(0, f.driver.contextCalls)
            assertTrue(f.driver.lines.isEmpty())
            assertTrue(f.driver.released.isEmpty())
        }
    }

    @Test fun newFrozenDefinitionIsDetachedBeforeWarmRendererMutatesCallerMap() {
        val f = fixture()
        val added = pen.copy(id = "added", size = Param(10f))
        val frozen = mutableMapOf(pen.id to pen, added.id to added)
        val edit = f.mixedEdit()
        val c = edit.lines.single()
        val changed = c.after!!.record.copy(brushId = added.id)
        val full = UndoLog.Step(edit.changes, lines = listOf(UndoLog.LineChange(c.layerId, c.celId, c.id,
            c.before, CelComposer.Line(changed.seq, changed))))
        f.driver.afterLine = { frozen[added.id] = added.copy(size = Param(20f)) }
        f.scene.stageJoint(full, frozen = frozen).use { p ->
            assertEquals(listOf(8f, 10f), f.driver.linePresets.map { it.size.base })
            assertEquals(10f, BrushJson.decodeChecked(p.candidateState.brushes.getValue(added.id)).size.base)
            assertEquals(20f, frozen.getValue(added.id).size.base)
            assertTrue(f.publish(p))
        }
        assertEquals(10f, f.scene.brushes.getValue(added.id).size.base)
    }
}
