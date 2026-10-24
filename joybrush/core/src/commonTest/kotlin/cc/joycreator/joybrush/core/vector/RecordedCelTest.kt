package cc.joycreator.joybrush.core.vector

import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.paint.UndoLog
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.stroke.StrokeWashFrame
import cc.joycreator.joybrush.core.select.Homography
import kotlin.test.*

class RecordedCelTest {
    private fun record(id: String = "same", seq: Long = 1L) = StrokeRecord(id, "frozen", 5L, 0f, 1f,
        listOf(PenSample(10f, 10f, 0.0)), seq = seq)
    private fun value(texture: Int, op: String = "normal") = UndoLog.SlabValue(texture, op)
    private fun line(record: StrokeRecord) = CelComposer.Line(record.seq, record)

    @Test fun promotionKeepsPurePixelTexturesAndOnlyIntersectingNewerLinesOpenSlabs() {
        val original = mapOf(1L to 101, 2L to 102)
        val base = RecordedCel.fromPixels("layer", null, original)
        val r = record()
        val withLine = base.apply(UndoLog.Step(emptyList(), lines = listOf(
            UndoLog.LineChange("layer", null, r.id, null, line(r)))), true)
        assertEquals(original, base.slabs.mapValues { it.value.single().value.texture })
        assertNull(withLine.writableTop(1L) { _, key -> key == 1L })
        assertEquals(102, withLine.writableTop(2L) { _, key -> key == 1L }!!.value.texture)
        assertEquals(2L, withLine.nextSeq())
    }

    @Test fun oneStepRestoresLinesAndSlabsTogetherAndIgnoresSameIdsElsewhere() {
        val base = RecordedCel.fromPixels("layer", "frame", mapOf(1L to 101))
        val r = record()
        val step = UndoLog.Step<Int>(emptyList(), lines = listOf(
            UndoLog.LineChange("layer", "frame", r.id, null, line(r)),
            UndoLog.LineChange("other", "frame", r.id, null, line(r))), slabs = listOf(
            UndoLog.SlabChange("layer", "frame", 1L, 2L, null, value(202)),
            UndoLog.SlabChange("layer", null, 1L, 2L, null, value(303))))
        val after = base.apply(step, true)
        assertEquals(listOf(r), after.records)
        assertEquals(listOf(101, 202), after.slabs[1L]!!.map { it.value.texture })
        val before = after.apply(step, false)
        assertTrue(before.records.isEmpty())
        assertEquals(101, before.slabs[1L]!!.single().value.texture)
        assertEquals(listOf(101, 202), before.apply(step, true).slabs[1L]!!.map { it.value.texture })
    }

    @Test fun staleLateChangeLeavesEveryOriginalItemUntouched() {
        val base = RecordedCel.fromPixels("layer", null, mapOf(1L to 101))
        val r = record()
        val invalid = UndoLog.Step(emptyList(), lines = listOf(
            UndoLog.LineChange("layer", null, r.id, null, line(r))), slabs = listOf(
            UndoLog.SlabChange("layer", null, 1L, 0L, value(999), value(202))))
        assertFailsWith<IllegalArgumentException> { base.apply(invalid, true) }
        assertTrue(base.records.isEmpty())
        assertEquals(101, base.slabs[1L]!!.single().value.texture)
    }

    @Test fun sequenceCollisionsAndOverflowAreRefusedBeforePublication() {
        val r = record(seq = Long.MAX_VALUE)
        val cel = RecordedCel<Int>("layer", null, listOf(r))
        assertFailsWith<IllegalArgumentException> { cel.nextSeq() }
        assertFailsWith<IllegalArgumentException> {
            RecordedCel("layer", null, listOf(record()), mapOf(1L to listOf(RecordedCel.PixelSlab(1L, value(101)))))
        }
        assertFailsWith<IllegalArgumentException> { RecordedCel<Int>("layer", null, listOf(record(), record("other"))) }
    }

    @Test fun operatorsAndInputPairingSurviveOutOfSequenceEdits() {
        val a = record("a", 8L)
        val b = record("b", 2L)
        val cel = RecordedCel("layer", null, listOf(a, b), mapOf(1L to listOf(RecordedCel.PixelSlab(3L, value(101, "multiply")))))
        assertNull(cel.writableTop(1L) { _, _ -> false })
        val a2 = a.copy(colorArgb = 0xFF123456.toInt())
        val b2 = b.copy(widthScale = 2f)
        val edited = cel.apply(UndoLog.Step(emptyList(), lines = listOf(
            UndoLog.LineChange("layer", null, a.id, line(a), line(a2)),
            UndoLog.LineChange("layer", null, b.id, line(b), line(b2)))), true)
        assertEquals(listOf(b2, a2), edited.records)
        assertEquals("multiply", edited.slabs[1L]!!.single().value.op)
        assertEquals(9L, edited.nextSeq())
    }

    @Test fun aliasingSlabsRefuseBeforeUndoCouldRecycleLiveContent() {
        assertFailsWith<IllegalArgumentException> {
            RecordedCel("layer", null, slabs = mapOf(1L to listOf(
                RecordedCel.PixelSlab(0L, value(101)), RecordedCel.PixelSlab(2L, value(101)))))
        }
        assertFailsWith<IllegalArgumentException> { RecordedCel.fromPixels("layer", null, mapOf(1L to 101, 2L to 101)) }
    }

    @Test fun washFrameSurvivesCelSnapshotsAndLineUndoRedo() {
        val frame = StrokeWashFrame(1.2, 0.1, 20.0, -0.05, 0.9, 4.0, 0.0002, -0.0001, 1.0)
        val original = record().copy(washFrame = frame)
        val cel = RecordedCel<Int>("layer", null, listOf(original))
        assertEquals(frame, cel.records.single().washFrame, "physical cel snapshot retains the frame")

        val movedFrame = assertNotNull(frame.transformed(Homography.translate(30.0, -12.0)))
        val moved = original.copy(washFrame = movedFrame)
        val step = UndoLog.Step(emptyList(), lines = listOf(
            UndoLog.LineChange("layer", null, original.id, line(original), line(moved))))
        val after = cel.apply(step, true)
        assertEquals(moved, after.records.single(), "line edit publishes frame data")
        assertEquals(original, after.apply(step, false).records.single(), "undo restores prior frame")
        assertEquals(moved, after.apply(step, false).apply(step, true).records.single(), "redo restores edited frame")
    }
}
