package cc.joycreator.joybrush.core.select

import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.paint.UndoLog
import cc.joycreator.joybrush.core.shape.Pt
import cc.joycreator.joybrush.core.stroke.AddressedStroke
import cc.joycreator.joybrush.core.stroke.CaptureDomain
import cc.joycreator.joybrush.core.stroke.CaptureRect
import cc.joycreator.joybrush.core.stroke.CapturedPhysicalSource
import cc.joycreator.joybrush.core.stroke.CapturedStrokeRules
import cc.joycreator.joybrush.core.stroke.LineAddress
import cc.joycreator.joybrush.core.stroke.SourcePlacement
import cc.joycreator.joybrush.core.stroke.StrokeCodec
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.stroke.StrokeWashFrame
import cc.joycreator.joybrush.core.vector.CelComposer
import cc.joycreator.joybrush.core.vector.RecordedCel
import cc.joycreator.joybrush.core.vector.RecordedEdits
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Pure source/history contract; no brush replay, pixels, archive admission or runtime activation. */
class CapturedRecordHistoryTest {
    private val address = LineAddress("paint", null, "captured")
    private val key = address.layerId to address.celId
    private val frozenIds = setOf("ink", "other")

    private fun record(seq: Long = 3L): StrokeRecord = StrokeRecord(
        id = address.recordId, brushId = "ink", seed = 0x12345678L, smoothing = 0.25f,
        screenPerDoc = 2f, samples = listOf(
            PenSample(-0.0f, 300f, -0.0, 0.25f, Float.fromBits(0x7FC00001),
                Float.fromBits(0xFFC00002.toInt()), Float.fromBits(0x7FC12345)),
            PenSample(800f, -40f, 12.5, 1f, 0.2f, 0.3f, 0.4f)),
        colorArgb = 0x80443322.toInt(), widthScale = 1.5f, seq = seq,
        capturedPhysicalSource = CapturedPhysicalSource(CaptureDomain(
            CaptureRect(-100, -200, 500, 700), listOf(CaptureRect(-20, -30, 40, 80))),
            SourcePlacement(10, -20)),
    )

    private fun planes(r: StrokeRecord = record()): Map<Pair<String, String?>, RecordedCel<Int>> =
        mapOf(key to RecordedCel<Int>(address.layerId, address.celId, listOf(r)))

    private fun line(r: StrokeRecord) = CelComposer.Line(r.seq, r)
    private fun step(before: StrokeRecord?, after: StrokeRecord?): UndoLog.Step<Int> =
        UndoLog.Step(emptyList(), lines = listOf(UndoLog.LineChange(
            address.layerId, address.celId, address.recordId, before?.let(::line), after?.let(::line))))

    private fun exact(expected: StrokeRecord, actual: StrokeRecord) {
        assertTrue(CapturedStrokeRules.sameRecord(expected, actual))
        assertContentEquals(StrokeCodec.encode(expected), StrokeCodec.encode(actual))
        val back = StrokeCodec.decode(StrokeCodec.encode(actual))
        assertTrue(CapturedStrokeRules.sameRecord(expected, back))
    }

    private fun exactPlanes(expected: Map<Pair<String, String?>, RecordedCel<Int>>,
                            actual: Map<Pair<String, String?>, RecordedCel<Int>>) {
        assertEquals(expected.keys, actual.keys)
        for ((owner, cel) in expected) {
            val other = actual.getValue(owner)
            assertContentEquals(StrokeCodec.encodeAll(cel.records), StrokeCodec.encodeAll(other.records))
            assertEquals(cel.slabs.mapValues { (_, slabs) -> slabs.map { Triple(it.seq, it.value.texture, it.value.op) } },
                other.slabs.mapValues { (_, slabs) -> slabs.map { Triple(it.seq, it.value.texture, it.value.op) } })
        }
    }

    @Test fun addMoveMetadataDeleteUndoRedoRetainsCompleteStepsAndExactCodecSource() {
        val inactiveKey = "paint" to "inactive"
        val inactive = RecordedCel<Int>("paint", "inactive", listOf(record(9)))
        var current: Map<Pair<String, String?>, RecordedCel<Int>> = mapOf(
            key to RecordedCel.fromPixels("paint", null, mapOf(5L to 101)), inactiveKey to inactive)
        val released = mutableListOf<Int>()
        val history = UndoLog<Int>(1_000_000L, { 64L }, { released.add(it) })
        val states = mutableListOf(current)
        val steps = mutableListOf<UndoLog.Step<Int>>()
        fun publish(prepared: RecordedEdits.Prepared<Int>) {
            assertTrue(prepared.isCurrent(current))
            history.pushAndPublish(prepared.step) { current = prepared.after }
            assertSame(prepared.step, history.peekUndo())
            assertSame(inactive, current.getValue(inactiveKey))
            states.add(current)
            steps.add(prepared.step)
        }
        val incoming = record(0)
        publish(RecordedEdits.add(current, listOf(AddressedStroke(address, incoming)), frozenIds))
        val added = current.getValue(key).records.single()
        assertEquals(1L, added.seq)
        exact(incoming.copy(seq = 1L), added)
        val moved = assertNotNull(LineTransform.translateCaptured(added, -257, 513))
        publish(RecordedEdits.replace(current, listOf(AddressedStroke(address, moved)), frozenIds))
        val tagged = moved.copy(locked = true, groupId = "group")
        publish(RecordedEdits.replace(current, listOf(AddressedStroke(address, tagged)), frozenIds))
        publish(RecordedEdits.delete(current, setOf(address), allowLocked = true))
        assertTrue(current.getValue(key).records.isEmpty())
        for (index in steps.indices.reversed()) {
            val originalStep = steps[index]
            assertSame(originalStep, history.peekUndo())
            val next = RecordedEdits.prepare(current, originalStep, false).after
            assertTrue(history.undoAndPublish(originalStep) { current = next })
            assertSame(originalStep, history.peekRedo())
            exactPlanes(states[index], current)
        }
        assertFalse(history.canUndo)
        for (index in steps.indices) {
            val originalStep = steps[index]
            assertSame(originalStep, history.peekRedo())
            val next = RecordedEdits.prepare(current, originalStep, true).after
            assertTrue(history.redoAndPublish(originalStep) { current = next })
            assertSame(originalStep, history.peekUndo())
            exactPlanes(states[index + 1], current)
        }
        assertFalse(history.canRedo)
        assertEquals(4, history.undoDepth)
        assertEquals(101, current.getValue(key).slabs.getValue(5L).single().value.texture)
        assertTrue(released.isEmpty(), "Line-only history must not retire the cel's base texture")
    }

    @Test fun completeLineAndSlabStepAppliesInBothDirections() {
        val before = record()
        val after = assertNotNull(LineTransform.translateCaptured(before, 2, -3))
        val cel = RecordedCel("paint", null, listOf(before), mapOf(5L to listOf(
            RecordedCel.PixelSlab(0L, UndoLog.SlabValue(101, "normal")))))
        val complete = UndoLog.Step(emptyList(), lines = step(before, after).lines, slabs = listOf(
            UndoLog.SlabChange("paint", null, 5L, 0L, UndoLog.SlabValue(101, "normal"),
                UndoLog.SlabValue(202, "normal"))))
        CapturedStrokeRules.requireNewStep(complete)
        val next = cel.apply(complete, true)
        exact(after, next.records.single())
        assertEquals(202, next.slabs.getValue(5L).single().value.texture)
        val restored = next.apply(complete, false)
        exact(before, restored.records.single())
        assertEquals(101, restored.slabs.getValue(5L).single().value.texture)
    }

    @Test fun integerMoveChangesOnlyPlacementAndRoundTripsBackToOriginal() {
        val frame = StrokeWashFrame(1.2, 0.1, 20.0, -0.05, 0.9, 4.0, 0.0002, -0.0001, 1.0)
        val before = record().copy(washFrame = frame)
        val moved = assertNotNull(LineTransform.translateCaptured(before, -257, 513))
        assertEquals(SourcePlacement(-247, 493), moved.capturedPhysicalSource!!.placement)
        assertEquals(frame, moved.washFrame, "captured appearance placement is independent of wash texture frame")
        exact(before, moved.copy(capturedPhysicalSource = before.capturedPhysicalSource))
        assertSame(before.capturedPhysicalSource!!.domain, moved.capturedPhysicalSource!!.domain)
        exact(before, assertNotNull(LineTransform.translateCaptured(moved, 257, -513)))
    }

    @Test fun exactHomogeneousIntegerTranslationWorksWithEitherWeightPolicy() {
        val before = record()
        val h = Homography(doubleArrayOf(2.0, 0.0, 6.0, 0.0, 2.0, -8.0, 0.0, 0.0, 2.0))
        val expected = assertNotNull(LineTransform.translateCaptured(before, 3, -4))
        for (weight in LineTransform.Weight.entries) exact(expected, assertNotNull(LineTransform.apply(before, h, weight)))
    }

    @Test fun unsupportedTransformsRefuseBeforeEmptyCapturedFastPath() {
        val transforms = listOf(
            Homography.translate(0.5, 0.0), Homography.translate(0.0, 1e-12),
            Homography.scale(2.0, 2.0, Pt(0.0, 0.0)),
            Homography.scale(1.0 + 1e-12, 1.0, Pt(0.0, 0.0)),
            Homography.scale(-1.0, 1.0, Pt(0.0, 0.0)),
            Homography.rotate(PI / 2, Pt(0.0, 0.0)),
            Homography(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 1e-15, 0.0, 1.0)),
            Homography(doubleArrayOf(1.0, 0.0, Double.NaN, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)),
            Homography(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0)),
            Homography.translate(Int.MAX_VALUE.toDouble() + 1.0, 0.0))
        for (before in listOf(record(), record().copy(samples = emptyList()))) {
            for ((index, h) in transforms.withIndex()) for (weight in LineTransform.Weight.entries) {
                assertNull(LineTransform.apply(before, h, weight), "transform $index, empty=${before.samples.isEmpty()}")
            }
        }
    }

    @Test fun cumulativePlacementOverflowRefusesWithoutChangingOriginal() {
        for ((placement, dx, dy) in listOf(
            Triple(SourcePlacement(Int.MAX_VALUE, 0), 1, 0),
            Triple(SourcePlacement(Int.MIN_VALUE, 0), -1, 0),
            Triple(SourcePlacement(0, Int.MAX_VALUE), 0, 1),
            Triple(SourcePlacement(0, Int.MIN_VALUE), 0, -1))) {
            val before = record().copy(capturedPhysicalSource = record().capturedPhysicalSource!!.copy(placement = placement))
            val bytes = StrokeCodec.encode(before)
            assertNull(LineTransform.translateCaptured(before, dx, dy))
            assertNull(LineTransform.apply(before, Homography.translate(dx.toDouble(), dy.toDouble()), LineTransform.Weight.KEEP))
            assertContentEquals(bytes, StrokeCodec.encode(before))
        }
    }

    @Test fun ordinaryTransformStillMovesSamplesAndScalesRecipe() {
        val ordinary = record().copy(capturedPhysicalSource = null)
        val moved = assertNotNull(LineTransform.apply(ordinary, Homography.translate(0.5, 1.25), LineTransform.Weight.KEEP))
        assertEquals(ordinary.samples[0].x + 0.5f, moved.samples[0].x)
        assertEquals(ordinary.samples[0].y + 1.25f, moved.samples[0].y)
        assertNull(moved.capturedPhysicalSource)
        val scaled = assertNotNull(LineTransform.apply(ordinary, Homography.scale(2.0, 2.0, Pt(0.0, 0.0)), LineTransform.Weight.SCALE))
        assertEquals(ordinary.widthScale * 2f, scaled.widthScale)
        assertEquals(ordinary.screenPerDoc / 2f, scaled.screenPerDoc)
    }

    @Test fun recipeAndSourceReinterpretationRefuseWithoutChangingPlane() {
        val before = record()
        val source = before.capturedPhysicalSource!!
        val invalid = listOf(before.copy(brushId = "other"), before.copy(seed = before.seed + 1),
            before.copy(smoothing = 0.5f), before.copy(screenPerDoc = 3f), before.copy(widthScale = 2f),
            before.copy(colorArgb = 0xFF000000.toInt()), before.copy(seq = 99),
            before.copy(washFrame = StrokeWashFrame(1.0, 0.0, 1.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)),
            before.copy(samples = before.samples.dropLast(1)),
            before.copy(capturedPhysicalSource = source.copy(domain = CaptureDomain())),
            before.copy(capturedPhysicalSource = null))
        val current = planes(before)
        val bytes = StrokeCodec.encode(before)
        for (after in invalid) {
            assertFailsWith<IllegalArgumentException> { CapturedStrokeRules.requireSupportedReplacement(before, after) }
            assertFailsWith<IllegalArgumentException> {
                RecordedEdits.replace(current, listOf(AddressedStroke(address, after)), frozenIds)
            }
            assertContentEquals(bytes, StrokeCodec.encode(current.getValue(key).records.single()))
        }
        val ordinary = before.copy(capturedPhysicalSource = null)
        assertFailsWith<IllegalArgumentException> { CapturedStrokeRules.requireSupportedReplacement(ordinary, before) }
    }

    @Test fun changedNaNPayloadRefusesBeforeDataClassNoOpDetection() {
        val before = record()
        val changed = before.copy(samples = before.samples.mapIndexed { index, s ->
            if (index == 0) s.copy(tilt = Float.fromBits(0x7FC00003)) else s })
        assertEquals(before, changed, "Data-class equality hides the source-changing NaN payload")
        assertFalse(CapturedStrokeRules.sameRecord(before, changed))
        assertFailsWith<IllegalArgumentException> { CapturedStrokeRules.requireSupportedReplacement(before, changed) }
        val current = planes(before)
        assertFailsWith<IllegalArgumentException> {
            RecordedEdits.replace(current, listOf(AddressedStroke(address, changed)), frozenIds)
        }
        exact(before, current.getValue(key).records.single())
    }

    @Test fun rawSampleDifferencesAreDetectedAcrossEveryStoredChannel() {
        val before = record()
        val s = before.samples[0]
        val changedSamples = listOf(s.copy(x = 0f), s.copy(y = 301f), s.copy(timeMs = 0.0),
            s.copy(pressure = 0.5f), s.copy(tilt = Float.fromBits(0x7FC00004)),
            s.copy(azimuth = Float.fromBits(0x7FC00005)), s.copy(barrel = Float.fromBits(0x7FC00006)))
        for (changed in changedSamples) {
            val after = before.copy(samples = listOf(changed, before.samples[1]))
            assertFalse(CapturedStrokeRules.sameRecord(before, after))
            assertFailsWith<IllegalArgumentException> { CapturedStrokeRules.requireSupportedReplacement(before, after) }
        }
    }

    @Test fun ordinarySameRecordRetainsExistingEquality() {
        val before = record().copy(capturedPhysicalSource = null)
        val after = before.copy(samples = before.samples.map { it.copy(tilt = Float.fromBits(0x7FC00009)) })
        // Only the missing first reading is changed; keep the finite second reading unchanged.
        val nanOnly = after.copy(samples = listOf(after.samples[0], before.samples[1]))
        assertEquals(before, nanOnly)
        assertTrue(CapturedStrokeRules.sameRecord(before, nanOnly))
    }

    @Test fun forgedExpectedNaNPayloadCannotMatchCurrentCelHistory() {
        val before = record()
        val forged = before.copy(samples = listOf(before.samples[0].copy(barrel = Float.fromBits(0x7FC00007)), before.samples[1]))
        val moved = assertNotNull(LineTransform.translateCaptured(forged, 2, 3))
        val cel = planes(before).getValue(key)
        assertFailsWith<IllegalArgumentException> { cel.apply(step(forged, moved), true) }
        assertFailsWith<IllegalArgumentException> { cel.apply(step(moved, forged), false) }
        exact(before, cel.records.single())
    }

    @Test fun unsupportedNewStepCannotEnterUndoLog() {
        val before = record()
        val changed = before.copy(samples = listOf(before.samples[0].copy(tilt = Float.fromBits(0x7FC0000A)), before.samples[1]))
        val history = UndoLog<Int>(1_000_000L, { 64L }, {})
        val cursor = history.snapshotCursor()
        assertFailsWith<IllegalArgumentException> { CapturedStrokeRules.requireNewStep(step(before, changed)) }
        assertFailsWith<IllegalArgumentException> { history.prepareNew(step(before, changed)) }
        assertSame(cursor, history.snapshotCursor())
        assertFalse(history.canUndo)
    }

    @Test fun retainedStepCollectionsCannotBeRewrittenToBypassNewSourceAdmission() {
        val before = record()
        val moved = assertNotNull(LineTransform.translateCaptured(before, 2, 3))
        val accepted = step(before, moved)
        val history = UndoLog<Int>(1_000_000L, { 64L }, {})
        history.push(accepted)
        assertFails { (accepted.lines as MutableList<UndoLog.LineChange>).clear() }
        assertFails { (accepted.changes as MutableList<UndoLog.TileChange<Int>>).clear() }
        assertFails { (accepted.slabs as MutableList<UndoLog.SlabChange<Int>>).clear() }
        val restored = planes(moved).getValue(key).apply(assertNotNull(history.undo()), false)
        exact(before, restored.records.single())
    }

    @Test fun deleteThenAddCannotReinterpretCapturedRecipeWithinOneNewStep() {
        val before = record()
        val changed = before.copy(seed = before.seed + 1)
        val chained = UndoLog.Step<Int>(emptyList(), lines = step(before,null).lines + step(null,changed).lines)
        val history = UndoLog<Int>(1_000_000L, { 64L }, {})
        val cursor = history.snapshotCursor()
        assertFailsWith<IllegalArgumentException> { history.prepareNew(chained) }
        assertSame(cursor, history.snapshotCursor())
        val scoped = UndoLog.Step<Int>(emptyList(), lines = step(before,null).lines + listOf(
            UndoLog.LineChange("other-layer",null,before.id,null,CelComposer.Line(changed.seq,changed))))
        CapturedStrokeRules.requireNewStep(scoped) // Same id on another layer is a distinct address.
    }

    @Test fun addDeletePolicyValidatesWholeCapturedEndpoint() {
        val valid = record()
        CapturedStrokeRules.requireSupportedReplacement(null, valid)
        CapturedStrokeRules.requireSupportedReplacement(valid, null)
        val invalid = valid.copy(smoothing = Float.NaN)
        assertFailsWith<IllegalArgumentException> { CapturedStrokeRules.requireSupportedReplacement(null, invalid) }
        assertFailsWith<IllegalArgumentException> { CapturedStrokeRules.requireSupportedReplacement(invalid, null) }
        val before = mapOf(key to RecordedCel<Int>("paint", null))
        assertFailsWith<IllegalArgumentException> {
            RecordedEdits.add(before, listOf(AddressedStroke(address, invalid)), frozenIds)
        }
        assertTrue(before.getValue(key).records.isEmpty())
    }

    @Test fun ordinaryManualStepKeepsLegacyAdmissionEvenInMixedBatch() {
        val ordinary = record().copy(capturedPhysicalSource = null)
        val legacy = UndoLog.LineChange("", "", "", null, CelComposer.Line(ordinary.seq, ordinary))
        CapturedStrokeRules.requireNewStep(UndoLog.Step<Int>(emptyList(), lines = listOf(legacy)))
        CapturedStrokeRules.requireNewStep(UndoLog.Step<Int>(emptyList(), lines = listOf(legacy) + step(null,record()).lines))
    }

    @Test fun exactNoOpIsAllowedButProducesNoReplacementHistory() {
        val before = record()
        val copy = StrokeCodec.decode(StrokeCodec.encode(before))
        CapturedStrokeRules.requireSupportedReplacement(before, copy)
        val prepared = RecordedEdits.replace(planes(before), listOf(AddressedStroke(address, copy)), frozenIds)
        assertTrue(prepared.step.lines.isEmpty())
    }

    @Test fun snapshotsDefendInputAliasesAndRejectMutableCasts() {
        val samples = record().samples.toMutableList()
        val input = record().copy(samples = samples)
        val originals = mutableListOf(input)
        val one = CapturedStrokeRules.snapshot(input)
        val many = CapturedStrokeRules.snapshots(originals)
        val bytes = StrokeCodec.encode(one)
        samples[0] = samples[0].copy(x = 999f)
        originals.clear()
        assertContentEquals(bytes, StrokeCodec.encode(one))
        exact(one, many.single())
        assertFails { (one.samples as MutableList<PenSample>).clear() }
        assertFails { (many as MutableList<StrokeRecord>).clear() }
        assertContentEquals(bytes, StrokeCodec.encode(one))
    }

    @Test fun celAndStepOwnIndependentImmutableSampleSnapshots() {
        val samples = record().samples.toMutableList()
        val input = record().copy(samples = samples)
        val records = mutableListOf(input)
        val cel = RecordedCel<Int>("paint", null, records)
        val newStep = step(null, input)
        val bytes = StrokeCodec.encode(input)
        samples.clear()
        records.clear()
        assertContentEquals(bytes, StrokeCodec.encode(cel.records.single()))
        assertContentEquals(bytes, StrokeCodec.encode(newStep.lines.single().after!!.record))
        assertFails { (cel.records as MutableList<StrokeRecord>).clear() }
        assertFails { (cel.records.single().samples as MutableList<PenSample>).clear() }
        assertFails { (newStep.lines.single().after!!.record.samples as MutableList<PenSample>).clear() }
    }

    @Test fun preparedAdditionDoesNotBorrowCallerSampleBacking() {
        val samples = record(0).samples.toMutableList()
        val incoming = record(0).copy(samples = samples)
        val before = mapOf(key to RecordedCel<Int>("paint", null))
        val prepared = RecordedEdits.add(before, listOf(AddressedStroke(address, incoming)), frozenIds)
        val expected = StrokeCodec.encode(incoming.copy(seq = 1L))
        samples.clear()
        assertContentEquals(expected, StrokeCodec.encode(prepared.after.getValue(key).records.single()))
        assertContentEquals(expected, StrokeCodec.encode(prepared.step.lines.single().after!!.record))
        assertTrue(before.getValue(key).records.isEmpty())
    }
}
