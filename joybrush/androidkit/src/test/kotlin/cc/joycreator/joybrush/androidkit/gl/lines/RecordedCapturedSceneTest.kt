package cc.joycreator.joybrush.androidkit.gl.lines

import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.paint.Tiles
import cc.joycreator.joybrush.core.paint.UndoLog
import cc.joycreator.joybrush.core.select.LineTransform
import cc.joycreator.joybrush.core.stroke.AddressedStroke
import cc.joycreator.joybrush.core.stroke.CaptureDomain
import cc.joycreator.joybrush.core.stroke.CaptureRect
import cc.joycreator.joybrush.core.stroke.CapturedPhysicalSource
import cc.joycreator.joybrush.core.stroke.CapturedSourceCopyPlan
import cc.joycreator.joybrush.core.stroke.CapturedStrokeRules
import cc.joycreator.joybrush.core.stroke.LineAddress
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.RecordedCel
import cc.joycreator.joybrush.core.vector.RecordedEdits
import cc.joycreator.joybrush.core.vector.CelComposer
import kotlin.test.*

/** Real scene/history with fake fresh handles and exact placed footprints; no GPU parity claim. */
class RecordedCapturedSceneTest {
    private val plane: Pair<String, String?> = "paint" to null
    private val address = LineAddress("paint", null, "captured")
    private val brush = BrushPreset(id = "frozen", name = "Pen", size = Param(8f), blend = "erase")
    private val brushes = mapOf(brush.id to brush)
    private fun record(id: String = address.recordId, seq: Long = 1L,
                       samples: List<PenSample> = listOf(PenSample(999f, -999f, 0.0,
                           tilt = Float.fromBits(0x7fc01234), barrel = -0.0f))) = StrokeRecord(
        id, brush.id, 19L, 0f, 1f, samples, seq = seq,
        capturedPhysicalSource = CapturedPhysicalSource(CaptureDomain(
            CaptureRect(-2, 0, 5, 4), listOf(CaptureRect(-1, 0, 1, 4)))),
    )
    private fun planes(records: List<StrokeRecord>) = mapOf(plane to RecordedCel<Int>("paint", null, records))
    private fun keys(vararg x: Int) = x.map { Tiles.key(it, 0) }.toSet()

    private class Driver : RecordedScene.CapturedSourceRenderer<Int> {
        var next = 100
        var context = true
        var contextCalls = 0
        var allow: (LineAddress, StrokeRecord, BrushPreset) -> Boolean = { _, _, _ -> true }
        var afterAdmission: (() -> Unit)? = null
        var afterContext: (() -> Unit)? = null
        val admissions = mutableListOf<Pair<LineAddress, StrokeRecord>>()
        val rendered = mutableListOf<StrokeRecord>()
        val minted = mutableListOf<Int>()
        val released = mutableListOf<Int>()
        val bakeInputs = mutableListOf<RecordedScene.Unit<Int>>()
        private fun mint(): Int = (next++).also { minted += it }
        override fun contextCurrent(): Boolean {
            contextCalls++
            afterContext?.invoke()
            return context
        }
        override fun admitCapturedSource(address: LineAddress, record: StrokeRecord, preset: BrushPreset): Boolean {
            admissions += address to record
            afterAdmission?.invoke()
            return allow(address, record, preset)
        }
        override fun line(address: LineAddress, record: StrokeRecord, preset: BrushPreset): RecordedScene.Unit<Int> {
            rendered += record
            // Canonical support is renderer-measured, deliberately unrelated to raw sample bounds.
            val sourceKeys = setOf(Tiles.key(-1, 0), Tiles.key(0, 0))
            val keys = record.capturedPhysicalSource?.let { CapturedSourceCopyPlan.prepare(sourceKeys, it, 4).destinationKeys }
                ?: sourceKeys
            return RecordedScene.Unit(preset.blend, keys.associateWith { mint() })
        }
        override fun copyForBake(address: LineAddress, unit: RecordedScene.Unit<Int>): RecordedScene.Unit<Int> {
            bakeInputs += unit
            return RecordedScene.Unit(unit.op, unit.tiles.mapValues { mint() })
        }
        override fun look(plane: Pair<String, String?>, key: Long, sources: List<RecordedScene.Source<Int>>): Int = mint()
        override fun release(texture: Int) { released += texture }
    }

    private class Fixture(val driver: Driver, val history: UndoLog<Int>, val scene: RecordedScene<Int>)
    private fun fixture(records: List<StrokeRecord> = emptyList(), driver: Driver = Driver()): Fixture {
        val history = UndoLog<Int>(1_000_000, { 4 }, driver::release)
        val scene = RecordedScene.create(planes(records), brushes, history, driver)
        return Fixture(driver, history, scene)
    }
    private fun moved(record: StrokeRecord) = assertNotNull(LineTransform.translateCaptured(record, 5, 0))
    private fun replace(f: Fixture, next: StrokeRecord) = RecordedEdits.replace(f.scene.planes,
        listOf(AddressedStroke(address, next)), brushes.keys)
    private data class Root(override val recorded: RecordedScene.State<Int>,
                            override val cursor: UndoLog.Cursor<Int>,
                            override val graphicsEpoch: Long = 7L,
                            override val protectedTextures: Set<Int> = setOf(20, 21)) : RecordedScene.Publication<Int>

    @Test fun ordinaryRendererRemainsIncapableBeforeContextOrRenderingCallbacks() {
        val driver = Driver()
        val ordinary = object : RecordedScene.Renderer<Int> by driver {}
        val history = UndoLog<Int>(1_000_000, { 4 }, driver::release)
        assertFailsWith<IllegalArgumentException> { RecordedScene.create(planes(listOf(record())), brushes, history, ordinary) }
        assertFailsWith<IllegalArgumentException> {
            RecordedScene.createSaved(planes(listOf(record())), brushes, history, ordinary,
                mapOf(plane to emptySet()), mapOf(plane to emptyMap()))
        }
        assertEquals(0, driver.contextCalls)
        assertTrue(driver.admissions.isEmpty())
        assertTrue(driver.minted.isEmpty())
        val scene = RecordedScene.create(planes(emptyList()), brushes, history, ordinary)
        val addition = RecordedEdits.add(scene.planes, listOf(AddressedStroke(address, record())), brushes.keys)
        val contextCalls = driver.contextCalls
        assertFailsWith<IllegalArgumentException> { scene.stage(addition) }
        assertEquals(contextCalls, driver.contextCalls)
        assertFalse(history.canUndo)
    }

    @Test fun admissionIsPerActualAddressRecipePlacementAndFrozenBrushBeforeAnyRendering() {
        val driver = Driver()
        driver.allow = { at, source, preset ->
            assertEquals("paint", at.layerId)
            assertEquals(source.id, at.recordId)
            assertEquals(brush, preset)
            source.id != "unsupported"
        }
        assertFailsWith<IllegalArgumentException> {
            fixture(listOf(record(), record("unsupported", 2)), driver)
        }
        assertEquals(listOf("captured", "unsupported"), driver.admissions.map { it.first.recordId })
        assertTrue(driver.rendered.isEmpty())
        assertTrue(driver.minted.isEmpty())
    }

    @Test fun refusedPlacementKeepsTheExactPriorSceneHistoryAndCaches() {
        val f = fixture(listOf(record()))
        val before = f.scene.snapshotState()
        val cursor = f.history.snapshotCursor()
        val minted = f.driver.minted.size
        f.driver.allow = { _, source, _ -> source.capturedPhysicalSource!!.placement.dx == 0 }
        assertFailsWith<IllegalArgumentException> {
            f.scene.stage(replace(f, moved(f.scene.planes.getValue(plane).records.single())))
        }
        assertSame(before, f.scene.snapshotState())
        assertSame(cursor, f.history.snapshotCursor())
        assertEquals(minted, f.driver.minted.size)
        assertTrue(f.driver.released.isEmpty())
    }

    @Test fun capableRendererDoesNotAuthorizeRawRecipeChangesInNewHistory() {
        val f = fixture(listOf(record()))
        val old = f.scene.planes.getValue(plane).records.single()
        val changed = old.copy(samples = listOf(old.samples.single().copy(x = 123f)))
        val step = UndoLog.Step<Int>(emptyList(), lines = listOf(UndoLog.LineChange(
            "paint", null, old.id, CelComposer.Line(old.seq, old), CelComposer.Line(changed.seq, changed))))
        val edit = RecordedEdits.prepare(f.scene.planes, step, true)
        val admissions = f.driver.admissions.size
        assertFailsWith<IllegalArgumentException> { f.scene.stage(edit) }
        assertEquals(admissions, f.driver.admissions.size)
        assertFalse(f.history.canUndo)
        assertTrue(CapturedStrokeRules.sameRecord(old, f.scene.planes.getValue(plane).records.single()))
    }

    @Test fun admissionCannotSubstituteAnUnadmittedRecordThroughCallerMapBacking() {
        val driver = Driver()
        val physical = planes(listOf(record())).toMutableMap()
        driver.afterAdmission = {
            physical[plane] = RecordedCel("paint", null, listOf(record("substituted")))
        }
        val history = UndoLog<Int>(1_000_000, { 4 }, driver::release)
        assertFailsWith<IllegalStateException> { RecordedScene.create(physical, brushes, history, driver) }
        assertTrue(driver.minted.isEmpty())
        assertFalse(history.canUndo)
    }

    @Test fun boundAddIntegerMoveUndoRedoUsePlacedActualFootprintsAndOneCursor() {
        val f = fixture()
        var root = Root(f.scene.snapshotState(), f.history.snapshotCursor())
        f.scene.bindState { root }
        f.history.bindCursor { root.cursor }
        fun publish(step: UndoLog.Step<Int>, direction: RecordedScene.Direction = RecordedScene.Direction.NEW): Set<Long> {
            f.scene.stageJoint(step, direction).use { staged ->
                val affected = staged.affected[plane].orEmpty()
                assertTrue(staged.canPublish())
                root = root.copy(recorded = staged.candidateState, cursor = staged.historyProposal.cursor)
                staged.adoptAfterPublication()
                staged.releaseRetiredAfterPublication()
                return affected
            }
        }
        val add = RecordedEdits.add(f.scene.planes, listOf(AddressedStroke(address, record())), brushes.keys)
        assertEquals(keys(-1, 0), publish(add.step))
        assertEquals(keys(-1, 0), f.scene.savedLookKeys(plane))
        assertTrue(f.scene.isMaterialized(plane))
        val original = f.scene.planes.getValue(plane).records.single()
        val translation = replace(f, moved(original))
        assertEquals(keys(-1, 0, 1), publish(translation.step))
        assertEquals(keys(0, 1), f.scene.savedLookKeys(plane))
        val placed = f.scene.planes.getValue(plane).records.single()
        assertEquals(original.samples, placed.samples)
        assertEquals(0x7fc01234, placed.samples.single().tilt.toRawBits())
        assertEquals((-0.0f).toRawBits(), placed.samples.single().barrel.toRawBits())
        assertEquals(2, f.history.undoDepth)
        assertSame(translation.step, f.history.peekUndo())
        publish(translation.step, RecordedScene.Direction.UNDO)
        assertTrue(CapturedStrokeRules.sameRecord(original, f.scene.planes.getValue(plane).records.single()))
        assertEquals(keys(-1, 0), f.scene.savedLookKeys(plane))
        assertSame(translation.step, f.history.peekRedo())
        publish(translation.step, RecordedScene.Direction.REDO)
        assertTrue(CapturedStrokeRules.sameRecord(placed, f.scene.planes.getValue(plane).records.single()))
        assertEquals(keys(0, 1), f.scene.savedLookKeys(plane))
        assertTrue(f.driver.released.none { it in root.protectedTextures })
    }

    @Test fun bakeCopiesPlacedSourcesAtTheirSequenceAndOperatorAndCanUndoRedo() {
        val captured = moved(record(seq = 7))
        val f = fixture(listOf(captured))
        val cached = f.scene.heldCacheTextures()
        f.scene.stageBake(setOf(address)).use { assertTrue(it.commit()) }
        val step = assertNotNull(f.history.peekUndo())
        val cel = f.scene.planes.getValue(plane)
        assertTrue(cel.records.isEmpty())
        assertEquals(keys(0, 1), cel.slabs.keys)
        assertEquals(keys(0, 1), f.driver.bakeInputs.single().tiles.keys)
        assertTrue(cel.slabs.values.flatten().all { it.seq == 7L && it.value.op == "erase" && it.value.texture !in cached })
        assertEquals(1, f.driver.rendered.size, "bake copies the cached placed source without recipe replay")
        f.scene.stage(RecordedEdits.prepare(f.scene.planes, step, false), RecordedScene.Direction.UNDO).use { assertTrue(it.commit()) }
        assertTrue(CapturedStrokeRules.sameRecord(captured, f.scene.planes.getValue(plane).records.single()))
        f.scene.stage(RecordedEdits.prepare(f.scene.planes, step, true), RecordedScene.Direction.REDO).use { assertTrue(it.commit()) }
        assertTrue(f.scene.planes.getValue(plane).records.isEmpty())
        assertEquals(keys(0, 1), f.scene.planes.getValue(plane).slabs.keys)
    }

    @Test fun savedAcceptanceDoesNotReplayAndFirstEditRequiresExactPlacedUnion() {
        for (matching in listOf(false, true)) {
            val driver = Driver()
            val history = UndoLog<Int>(1_000_000, { 4 }, driver::release)
            val savedKeys = if (matching) keys(-1, 0) else keys(99)
            val uploads = savedKeys.associateWith { 10 + savedKeys.indexOf(it) }
            val scene = RecordedScene.createSaved(planes(listOf(record())), brushes, history, driver,
                mapOf(plane to savedKeys), mapOf(plane to uploads))
            assertTrue(driver.admissions.isNotEmpty())
            assertTrue(driver.rendered.isEmpty())
            assertTrue(driver.minted.isEmpty())
            val before = scene.snapshotState()
            val next = moved(scene.planes.getValue(plane).records.single())
            val edit = RecordedEdits.replace(scene.planes, listOf(AddressedStroke(address, next)), brushes.keys)
            if (matching) {
                scene.stage(edit).use { assertTrue(it.commit()) }
                assertEquals(keys(0, 1), scene.savedLookKeys(plane))
            } else {
                assertFailsWith<IllegalArgumentException> { scene.stage(edit) }
                assertSame(before, scene.snapshotState())
                assertFalse(scene.isMaterialized(plane))
                assertFalse(history.canUndo)
                assertEquals(driver.minted.toSet(), driver.released.toSet())
                assertTrue(driver.released.none { it in uploads.values })
            }
        }
    }

    @Test fun fullyClippedCapturedSourceHasAuthoritativeEmptyFootprint() {
        val source = record().copy(capturedPhysicalSource = CapturedPhysicalSource(CaptureDomain(CaptureRect(50, 50, 1, 1))))
        val f = fixture(listOf(source))
        assertTrue(f.scene.isMaterialized(plane))
        assertTrue(f.scene.savedLookKeys(plane).isEmpty())
        assertTrue(f.scene.heldCacheTextures().isEmpty())
        f.scene.stageBake(setOf(address)).use { assertTrue(it.commit()) }
        assertTrue(f.history.peekUndo()!!.slabs.isEmpty())
        assertTrue(f.scene.planes.getValue(plane).records.isEmpty())
    }

    @Test fun admissionCannotChangeLocalGenerationOrReenterPreparation() {
        val f = fixture(listOf(record()))
        val edit = replace(f, moved(f.scene.planes.getValue(plane).records.single()))
        val minted = f.driver.minted.size
        f.driver.afterAdmission = { f.scene.forget() }
        assertFailsWith<IllegalStateException> { f.scene.stage(edit) }
        assertEquals(minted, f.driver.minted.size)
        assertFalse(f.history.canUndo)
        val g = fixture(listOf(record()))
        val replacement = replace(g, moved(g.scene.planes.getValue(plane).records.single()))
        g.driver.afterAdmission = { g.scene.stage(replacement) }
        assertFailsWith<IllegalStateException> { g.scene.stage(replacement) }
        g.driver.afterAdmission = null
        g.scene.stage(replacement).use { assertTrue(it.commit()) }
    }

    @Test fun admissionCannotMoveHistoryOrSwitchAnOtherwiseEqualBoundRoot() {
        val f = fixture(listOf(record()))
        var root = Root(f.scene.snapshotState(), f.history.snapshotCursor())
        f.scene.bindState { root }
        f.history.bindCursor { root.cursor }
        val edit = replace(f, moved(f.scene.planes.getValue(plane).records.single()))
        val minted = f.driver.minted.size
        f.driver.afterAdmission = { root = root.copy() }
        assertFailsWith<IllegalStateException> { f.scene.stageJoint(edit.step) }
        assertEquals(minted, f.driver.minted.size)
        assertFalse(f.history.canUndo)

        val g = fixture(listOf(record()))
        val next = replace(g, moved(g.scene.planes.getValue(plane).records.single()))
        // Use a concrete native history mutation, independent of this scene's source records.
        g.driver.afterAdmission = { g.history.push(UndoLog.Step(listOf(UndoLog.TileChange("native", 0L, null, 50)))) }
        val beforeMint = g.driver.minted.size
        assertFailsWith<IllegalStateException> { g.scene.stage(next) }
        assertEquals(beforeMint, g.driver.minted.size)
        assertTrue(g.history.canUndo, "external mutation is detected, never overwritten by staging")
    }

    @Test fun contextCallbackAndAdmissionLossRefuseWithoutMintingAndSourceSamplesAreDetached() {
        val samples = mutableListOf(PenSample(999f, -999f, 0.0))
        val f = fixture(listOf(record(samples = samples)))
        samples.clear()
        val old = f.scene.planes.getValue(plane).records.single()
        assertEquals(1, old.samples.size)
        val edit = replace(f, moved(old))
        val before = f.scene.snapshotState()
        val minted = f.driver.minted.size
        f.driver.afterAdmission = { f.driver.context = false }
        assertFailsWith<IllegalStateException> { f.scene.stage(edit) }
        assertSame(before, f.scene.snapshotState())
        assertEquals(minted, f.driver.minted.size)
        f.driver.context = true
        f.driver.afterAdmission = null
        val admissionCount = f.driver.admissions.size
        f.driver.afterContext = { if (f.driver.admissions.size > admissionCount) f.scene.forget() }
        assertFailsWith<IllegalStateException> { f.scene.stage(edit) }
        assertEquals(minted, f.driver.minted.size)
    }
}
