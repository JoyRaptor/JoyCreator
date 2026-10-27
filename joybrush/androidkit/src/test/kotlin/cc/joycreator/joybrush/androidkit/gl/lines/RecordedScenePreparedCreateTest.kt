package cc.joycreator.joybrush.androidkit.gl.lines

import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.paint.UndoLog
import cc.joycreator.joybrush.core.stroke.AddressedStroke
import cc.joycreator.joybrush.core.stroke.CaptureDomain
import cc.joycreator.joybrush.core.stroke.CaptureRect
import cc.joycreator.joybrush.core.stroke.CapturedPhysicalSource
import cc.joycreator.joybrush.core.stroke.CapturedStrokeRules
import cc.joycreator.joybrush.core.stroke.LineAddress
import cc.joycreator.joybrush.core.stroke.SourcePlacement
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.RecordedCel
import cc.joycreator.joybrush.core.vector.RecordedEdits
import kotlin.test.*

/** Scene/source ownership and callback contracts with fake names, never GPU appearance proof. */
class RecordedScenePreparedCreateTest {
    private val plane: Pair<String, String?> = "paint" to null
    private val address = LineAddress("paint", null, "line")
    private val brush = BrushPreset(id = "frozen", name = "Pen", size = Param(8f))
    private val brushes = mapOf(brush.id to brush)
    private fun record(id: String = "line", seq: Long = 1L, captured: Boolean = false,
                       samples: List<PenSample> = listOf(PenSample(999_999f, -999_999f, 0.0))) =
        StrokeRecord(id, brush.id, 19L, 0.6f, 1.25f, samples, seq = seq,
            capturedPhysicalSource = if (captured) CapturedPhysicalSource(
                CaptureDomain(CaptureRect(-3, -7, 20, 30)), SourcePlacement(512, -256)) else null)
    private fun planes(records: List<StrokeRecord>) = mapOf(plane to RecordedCel<Int>("paint", null, records))
    private fun cached(record: StrokeRecord, tiles: Map<Long, Int> = mapOf(3L to 10), op: String = "normal") =
        RecordedScene.Cached(record, RecordedScene.Unit(op, tiles))

    private open class Driver : RecordedScene.Renderer<Int> {
        var next = 100
        var context = true
        var contexts = 0
        var onContext: (() -> Unit)? = null
        var onLook: ((Int?) -> Unit)? = null
        var lookResult: ((Long) -> Int?)? = null
        var failAt: Int? = null
        var failure: Throwable = IllegalStateException("composition failed")
        var releaseFailure: Throwable? = null
        val lines = mutableListOf<LineAddress>()
        val looks = mutableListOf<Pair<Long, List<RecordedScene.Source<Int>>>>()
        val released = mutableListOf<Int>()
        override fun contextCurrent(): Boolean {
            contexts++
            onContext?.invoke()
            return context
        }
        override fun line(address: LineAddress, record: StrokeRecord, preset: BrushPreset): RecordedScene.Unit<Int> {
            lines += address
            return RecordedScene.Unit(preset.blend, mapOf(7L to next++))
        }
        override fun look(plane: Pair<String, String?>, key: Long, sources: List<RecordedScene.Source<Int>>): Int? {
            looks += key to sources.toList()
            if (looks.size == failAt) throw failure
            val output = lookResult?.invoke(key) ?: if (lookResult == null) next++ else null
            onLook?.invoke(output)
            return output
        }
        override fun release(texture: Int) {
            released += texture
            releaseFailure?.let { throw it }
        }
    }
    private class Capable : Driver(), RecordedScene.CapturedSourceRenderer<Int> {
        var allow = true
        val admitted = mutableListOf<StrokeRecord>()
        var onAdmission: (() -> Unit)? = null
        override fun admitCapturedSource(address: LineAddress, record: StrokeRecord, preset: BrushPreset): Boolean {
            admitted += record
            onAdmission?.invoke()
            return allow
        }
    }
    private fun history(driver: Driver) = UndoLog<Int>(1_000_000, { 4L }, driver::release)
    private fun create(driver: Driver, history: UndoLog<Int>, record: StrokeRecord = record(),
                       sources: Map<Long, Int> = mapOf(3L to 10), protected: Set<Int> = emptySet()) =
        RecordedScene.createPrepared(planes(listOf(record)), brushes, history, driver,
            mapOf(address to cached(record, sources)), protected)

    @Test fun initialConstructionUsesExactActualFootprintsIncludingTransparentAndEmptyKeysWithoutReplay() {
        val driver = Driver(); val history = history(driver); val cursor = history.snapshotCursor()
        val first = record(); val empty = record("empty", 2L)
        val scene = RecordedScene.createPrepared(planes(listOf(first, empty)), brushes, history, driver,
            mapOf(address to cached(first, linkedMapOf(-2L to 10, 4096L to 11)),
                LineAddress("paint", null, "empty") to cached(empty, emptyMap())), emptySet())
        assertTrue(driver.lines.isEmpty())
        assertSame(cursor, history.snapshotCursor()); assertFalse(history.canUndo)
        assertEquals(setOf(-2L, 4096L), scene.savedLookKeys(plane))
        assertEquals(setOf(-2L, 4096L), scene.lookKeys(plane))
        assertEquals(emptySet(), scene.snapshotState().units.getValue(LineAddress("paint", null, "empty")).unit.tiles.keys)
        assertEquals(setOf(10, 11), driver.looks.flatMap { it.second }.map { it.texture }.toSet())
        assertTrue(driver.looks.all { it.second.single().seq == 1L && it.second.single().op == "normal" })
        scene.close(); scene.close()
        assertEquals(setOf(10, 11, 100, 101), driver.released.toSet())
        assertEquals(driver.released.size, driver.released.distinct().size)
        assertSame(cursor, history.snapshotCursor())
    }

    @Test fun ordinaryRendererStaysIncapableAndFutureOrdinaryEditsUseTheActualRenderer() {
        val ordinary = Driver(); val ordinaryHistory = history(ordinary)
        val captured = record(captured = true)
        assertFailsWith<IllegalArgumentException> { create(ordinary, ordinaryHistory, captured) }
        assertEquals(0, ordinary.contexts); assertTrue(ordinary.lines.isEmpty()); assertTrue(ordinary.released.isEmpty())

        val original = record()
        val scene = create(ordinary, ordinaryHistory, original)
        val replacement = original.copy(samples = listOf(PenSample(1f, 2f, 0.0)))
        val edit = RecordedEdits.replace(scene.planes, listOf(AddressedStroke(address, replacement)), brushes.keys)
        scene.stage(edit).use { assertTrue(it.commit()) }
        assertEquals(listOf(address), ordinary.lines)
        assertEquals(1, ordinaryHistory.undoDepth)
        assertEquals(setOf(7L), scene.savedLookKeys(plane))
        assertTrue(10 in ordinary.released)
        val before = ordinary.contexts
        val unsupported = RecordedEdits.add(scene.planes,
            listOf(AddressedStroke(LineAddress("paint", null, "captured"), captured.copy(id = "captured"))), brushes.keys)
        assertFails { scene.stage(unsupported) }
        assertEquals(before, ordinary.contexts)
        scene.close()
        assertEquals(ordinary.released.size, ordinary.released.distinct().size)
    }

    @Test fun capableRendererKeepsActualAdmissionAndReplayAfterPreparedOpening() {
        val driver = Capable(); val history = history(driver)
        val original = record(captured = true)
        val scene = create(driver, history, original)
        assertTrue(driver.admitted.isNotEmpty()); assertTrue(driver.lines.isEmpty())
        val next = original.copy(capturedPhysicalSource = original.capturedPhysicalSource!!.copy(placement = SourcePlacement(513, -256)))
        val edit = RecordedEdits.replace(scene.planes, listOf(AddressedStroke(address, next)), brushes.keys)
        scene.stage(edit).use { assertTrue(it.commit()) }
        assertEquals(listOf(address), driver.lines)
        assertTrue(driver.admitted.any { CapturedStrokeRules.sameRecord(it, next) })
        scene.close()
    }

    @Test fun entireManifestRecordAndOperatorAreValidatedBeforeAnyExternalCallback() {
        val source = record(captured = true)
        val variations = listOf(
            emptyMap(),
            mapOf(address to cached(source), LineAddress("paint", null, "extra") to cached(source)),
            mapOf(LineAddress("other", null, "line") to cached(source)),
            mapOf(address to cached(source.copy(seq = 2))),
            mapOf(address to cached(source.copy(seed = 20))),
            mapOf(address to cached(source.copy(capturedPhysicalSource = source.capturedPhysicalSource!!.copy(placement = SourcePlacement(512, -255))))),
            mapOf(address to cached(source.copy(samples = source.samples.map { it.copy(tilt = Float.fromBits(0x7fc01234)) }))),
            mapOf(address to cached(source, op = "erase")),
        )
        for (prepared in variations) {
            val driver = Capable(); val history = history(driver); val cursor = history.snapshotCursor()
            assertFails { RecordedScene.createPrepared(planes(listOf(source)), brushes, history, driver, prepared, emptySet()) }
            assertEquals(0, driver.contexts); assertTrue(driver.admitted.isEmpty())
            assertTrue(driver.lines.isEmpty()); assertTrue(driver.looks.isEmpty()); assertTrue(driver.released.isEmpty())
            assertSame(cursor, history.snapshotCursor())
        }
    }

    @Test fun capturedAdmissionRefusalAndBoundHistoryCannotAcceptBorrowedSources() {
        val driver = Capable().also { it.allow = false }
        val history = history(driver); val cursor = history.snapshotCursor()
        assertFails { create(driver, history, record(captured = true)) }
        assertTrue(driver.admitted.isNotEmpty()); assertTrue(driver.looks.isEmpty()); assertTrue(driver.released.isEmpty())
        assertSame(cursor, history.snapshotCursor())
        val bound = Driver(); val boundHistory = history(bound)
        val boundCursor = boundHistory.snapshotCursor()
        boundHistory.bindCursor { boundCursor }
        assertFails { create(bound, boundHistory) }
        assertEquals(0, bound.contexts); assertTrue(bound.released.isEmpty())
    }

    @Test fun ordinaryPreparedRawNanPayloadMustMatchBeforeCallbacksToo() {
        val driver = Driver(); val history = history(driver)
        val first = record(samples = listOf(PenSample(1f, 2f, 0.0, tilt = Float.fromBits(0x7fc01234))))
        val different = first.copy(samples = first.samples.map { it.copy(tilt = Float.fromBits(0x7fc05678)) })
        assertTrue(CapturedStrokeRules.sameRecord(first, different), "ordinary data equality canonicalizes NaN")
        assertFails { RecordedScene.createPrepared(planes(listOf(first)), brushes, history, driver,
            mapOf(address to cached(different)), emptySet()) }
        assertEquals(0, driver.contexts); assertTrue(driver.released.isEmpty())
    }

    @Test fun completePhysicalManifestRetainsSlabsHistoryAndProtectedOwnersAcrossCandidateClose() {
        val frame: Pair<String, String?> = "paint" to "frame"
        val sharedRecord = record(); val frameRecord = record() // Same id in two real addresses.
        val driver = Driver(); val history = history(driver)
        history.push(UndoLog.Step(listOf(UndoLog.TileChange("native", 0L, 40, 41))))
        val cursor = history.snapshotCursor()
        val physical = mapOf(plane to RecordedCel("paint", null, listOf(sharedRecord),
            mapOf(3L to listOf(RecordedCel.PixelSlab(0L, UndoLog.SlabValue(30, "normal"))))),
            frame to RecordedCel<Int>("paint", "frame", listOf(frameRecord)))
        val scene = RecordedScene.createPrepared(physical, brushes, history, driver,
            mapOf(address to cached(sharedRecord), LineAddress("paint", "frame", "line") to cached(frameRecord, mapOf(6L to 20))),
            setOf(50))
        assertEquals(setOf(plane, frame), scene.planes.keys)
        assertEquals(listOf(30, 10), driver.looks.first { it.first == 3L }.second.map { it.texture })
        assertTrue(driver.lines.isEmpty()); assertSame(cursor, history.snapshotCursor())
        scene.close(); scene.close()
        assertEquals(setOf(10, 20, 100, 101), driver.released.toSet())
        assertEquals(4, driver.released.size); assertSame(cursor, history.snapshotCursor())
        assertTrue(driver.released.none { it in setOf(30, 40, 41, 50) })
    }

    @Test fun cancelledFutureStageReclaimsItsNewWorkWhileKeepingPreparedOpeningSources() {
        val driver = Driver(); val history = history(driver); val original = record()
        val scene = create(driver, history, original)
        val cursor = history.snapshotCursor()
        val edit = RecordedEdits.replace(scene.planes,
            listOf(AddressedStroke(address, original.copy(samples = listOf(PenSample(1f, 2f, 0.0))))), brushes.keys)
        scene.stage(edit).use { }
        assertEquals(listOf(address), driver.lines)
        assertTrue(10 !in driver.released && 100 !in driver.released)
        assertEquals(setOf(101, 102), driver.released.toSet())
        assertSame(cursor, history.snapshotCursor()); assertEquals(mapOf(3L to 10), scene.snapshotState().units.getValue(address).unit.tiles)
        scene.close(); assertEquals(driver.released.size, driver.released.distinct().size)
    }

    @Test fun sourcesCannotAliasEachOtherSlabsEitherHistoryEndpointOrProtectedOwners() {
        val first = record(); val second = record("second", 2)
        val cases = listOf("source", "same-unit", "slab", "history-before", "history-after", "protected")
        for (case in cases) {
            val driver = Driver(); val history = history(driver)
            val physical = if (case == "slab") mapOf(plane to RecordedCel("paint", null, listOf(first),
                mapOf(9L to listOf(RecordedCel.PixelSlab(0L, UndoLog.SlabValue(10, "normal"))))))
                else planes(if (case == "source") listOf(first, second) else listOf(first))
            if (case.startsWith("history")) history.push(UndoLog.Step(listOf(UndoLog.TileChange(
                "native", 0L, if (case == "history-before") 10 else 40, if (case == "history-after") 10 else 41))))
            val inputs = if (case == "source") mapOf(address to cached(first),
                LineAddress("paint", null, "second") to cached(second, mapOf(7L to 10)))
                else mapOf(address to cached(first, if (case == "same-unit") mapOf(3L to 10, 4L to 10) else mapOf(3L to 10)))
            assertFails { RecordedScene.createPrepared(physical, brushes, history, driver, inputs,
                if (case == "protected") setOf(10) else emptySet()) }
            assertEquals(0, driver.contexts); assertTrue(driver.released.isEmpty())
        }
    }

    @Test fun failureAfterOneLookReleasesOnlyThatNewLookAndLeavesAllPreparedSourcesCallerOwned() {
        val driver = Driver().also { it.failAt = 2 }
        val history = history(driver); val cursor = history.snapshotCursor()
        val failure = assertFails { create(driver, history, sources = linkedMapOf(3L to 10, 4L to 11)) }
        assertSame(driver.failure, failure)
        assertEquals(listOf(100), driver.released)
        assertSame(cursor, history.snapshotCursor()); assertFalse(history.canUndo)
        assertTrue(driver.lines.isEmpty())
        // A refused opening releases its reentry guard; the unchanged caller-owned inputs retry.
        driver.failAt = null
        val scene = create(driver, history, sources = linkedMapOf(3L to 10, 4L to 11))
        scene.close()
        assertEquals(1, driver.released.count { it == 10 }); assertEquals(1, driver.released.count { it == 11 })
    }

    @Test fun borrowedOrDuplicateLookOutputsRefuseWithoutReleasingTheirOwners() {
        for (alias in listOf(10, 30, 40, 41, 50, 100)) {
            val driver = Driver(); val history = history(driver)
            history.push(UndoLog.Step(listOf(UndoLog.TileChange("native", 0L, 40, 41))))
            val record = record()
            val cel = RecordedCel("paint", null, listOf(record), mapOf(3L to listOf(
                RecordedCel.PixelSlab(0L, UndoLog.SlabValue(30, "normal")))))
            // The second derived output can also alias the first new look (100).
            driver.lookResult = { key -> if (key == 3L) 100 else alias }
            assertFails { RecordedScene.createPrepared(mapOf(plane to cel), brushes, history, driver,
                mapOf(address to cached(record, linkedMapOf(3L to 10, 4L to 11))), setOf(50)) }
            assertEquals(listOf(100), driver.released)
            assertTrue(driver.released.none { it in setOf(10, 11, 30, 40, 41, 50) })
        }
    }

    @Test fun transparentSourceKeyRequiresANonNullLookEvenThoughItsPixelsMayBeEmpty() {
        val driver = Driver().also { it.lookResult = { null } }
        assertFails { create(driver, history(driver)) }
        assertEquals(1, driver.looks.size); assertTrue(driver.lines.isEmpty()); assertTrue(driver.released.isEmpty())
    }

    @Test fun staleContextBeforeCompositionOrAfterReturnedOutputAbandonsNamesWithoutReleasingSources() {
        val before = Driver().also { it.context = false }
        assertFails { create(before, history(before)) }
        assertTrue(before.looks.isEmpty()); assertTrue(before.released.isEmpty())
        val during = Driver().also { driver -> driver.onLook = { driver.context = false } }
        assertFails { create(during, history(during)) }
        assertEquals(1, during.looks.size); assertTrue(during.released.isEmpty())
        val final = Driver()
        var afterLookChecks = 0
        final.onContext = { if (final.looks.isNotEmpty() && ++afterLookChecks == 2) final.context = false }
        assertFails { create(final, history(final)) }
        assertEquals(1, final.looks.size); assertTrue(final.released.isEmpty())
    }

    @Test fun historyReentryAfterLookReclaimsOnlyFreshUnownedOutput() {
        for (foreignOwnsOutput in listOf(false, true)) {
            val driver = Driver(); val history = history(driver)
            driver.onLook = { output -> history.push(UndoLog.Step(listOf(UndoLog.TileChange(
                "foreign", 0L, null, if (foreignOwnsOutput) output else 77)))) }
            assertFailsWith<IllegalStateException> { create(driver, history) }
            assertEquals(if (foreignOwnsOutput) emptyList() else listOf(100), driver.released)
            assertEquals(1, history.undoDepth)
        }
    }

    @Test fun sameHistoryNestedPreparedOpeningRefusesBeforeStealingBorrowedSources() {
        val driver = Driver(); val history = history(driver)
        var nested = 0
        driver.onLook = {
            nested++
            assertFailsWith<IllegalStateException> { create(driver, history) }
        }
        val scene = create(driver, history)
        assertEquals(1, nested); assertEquals(1, driver.looks.size); assertTrue(driver.released.isEmpty())
        driver.onLook = null
        scene.close(); scene.close()
        assertEquals(setOf(10, 100), driver.released.toSet()); assertEquals(2, driver.released.size)
    }

    @Test fun contextAndCapturedAdmissionHistoryReentryRefuseWithoutTouchingPreparedInputs() {
        val contextDriver = Driver(); val contextHistory = history(contextDriver)
        contextDriver.onContext = { contextHistory.push(UndoLog.Step(listOf(UndoLog.TileChange("native", 0L, null, 60)))) }
        assertFails { create(contextDriver, contextHistory) }
        assertTrue(contextDriver.looks.isEmpty()); assertTrue(contextDriver.released.isEmpty())
        val capturedDriver = Capable(); val capturedHistory = history(capturedDriver)
        capturedDriver.onAdmission = { capturedHistory.push(UndoLog.Step(listOf(UndoLog.TileChange("native", 0L, null, 61)))) }
        assertFails { create(capturedDriver, capturedHistory, record(captured = true)) }
        assertTrue(capturedDriver.looks.isEmpty()); assertTrue(capturedDriver.released.isEmpty())
    }

    @Test fun callerBackingMutationDuringContextCannotChangeDetachedRawUnitsOrManifest() {
        val raw = mutableListOf(PenSample(7f, 8f, 9.0, tilt = Float.fromBits(0x7fc01234), barrel = -0.0f))
        val record = record(captured = true, samples = raw)
        val preparedUnit = cached(record, linkedMapOf(3L to 10, 4L to 11))
        val input = linkedMapOf(address to preparedUnit)
        val physical = planes(listOf(record)).toMutableMap()
        val definitions = brushes.toMutableMap()
        val driver = Capable(); val history = history(driver)
        driver.onContext = {
            driver.onContext = null
            raw.clear(); input.clear(); physical.clear(); definitions.clear()
            (preparedUnit.unit.tiles as MutableMap<Long, Int>).clear()
        }
        val scene = RecordedScene.createPrepared(physical, definitions, history, driver, input, emptySet())
        val retained = scene.snapshotState().units.getValue(address)
        assertEquals(setOf(3L, 4L), retained.unit.tiles.keys)
        assertEquals(0x7fc01234, retained.record.samples.single().tilt.toRawBits())
        assertEquals((-0.0f).toRawBits(), retained.record.samples.single().barrel.toRawBits())
        assertEquals(record.capturedPhysicalSource, retained.record.capturedPhysicalSource)
        assertTrue(driver.lines.isEmpty())
        scene.close(); assertEquals(setOf(10, 11, 100, 101), driver.released.toSet())
    }

    @Test fun clearingCallerProtectedSetDuringContextDoesNotPermitABorrowedLook() {
        val driver = Driver(); val history = history(driver)
        val protected = mutableSetOf(50)
        driver.onContext = { protected.clear() }
        driver.lookResult = { 50 }
        assertFails { create(driver, history, protected = protected) }
        assertTrue(driver.looks.isNotEmpty()); assertTrue(driver.released.isEmpty())
    }

    @Test fun cleanupExceptionsNeverMaskThePrimaryCompositionFailure() {
        val driver = Driver().also {
            it.failAt = 3
            it.releaseFailure = IllegalStateException("release failed")
        }
        val failure = assertFails { create(driver, history(driver), sources = linkedMapOf(3L to 10, 4L to 11, 5L to 12)) }
        assertSame(driver.failure, failure)
        assertTrue(failure.suppressed.any { it === driver.releaseFailure })
        assertEquals(listOf(100, 101), driver.released)
    }
}
