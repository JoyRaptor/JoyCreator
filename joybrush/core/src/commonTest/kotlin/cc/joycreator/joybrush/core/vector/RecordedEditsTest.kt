package cc.joycreator.joybrush.core.vector

import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.stroke.*
import kotlin.test.*

class RecordedEditsTest {
    private val a = LineAddress("layer", null, "same")
    private val b = LineAddress("layer", "frame", "same")
    private val c = LineAddress("other", null, "same")
    private fun record(seq: Long) = StrokeRecord("same", "frozen", 19L, 0f, 1f,
        listOf(PenSample(1f, 2f, 0.0)), seq = seq)
    private fun planes(): Map<Pair<String, String?>, RecordedCel<Int>> =
        listOf(a to record(80L), b to record(2L), c to record(40L)).associate { (address, record) ->
            (address.layerId to address.celId) to RecordedCel<Int>(address.layerId, address.celId, listOf(record))
        }
    private fun addressed(planes: Map<Pair<String, String?>, RecordedCel<Int>>, address: LineAddress) =
        AddressedStroke(address, planes.getValue(address.layerId to address.celId).records.single())

    @Test fun unsortedReplacementsKeepOwnersSequencesAndUntouchedPlanes() {
        val before = planes()
        val b2 = addressed(before, b).let { it.copy(record = it.record.copy(widthScale = 2f)) }
        val a2 = addressed(before, a).let { it.copy(record = it.record.copy(colorArgb = 0xFF123456.toInt())) }
        val plan = RecordedEdits.replace(before, listOf(b2, a2), setOf("frozen"))
        assertEquals(listOf("frame", null), plan.step.lines.map { it.celId })
        assertEquals(listOf(2L, 80L), plan.step.lines.map { it.after!!.record.seq })
        assertEquals(b2.record, plan.after.getValue(b.layerId to b.celId).records.single())
        assertSame(before.getValue(c.layerId to c.celId), plan.after.getValue(c.layerId to c.celId))
        assertTrue(plan.isCurrent(before))
        assertFalse(plan.isCurrent(plan.after))
        val unrelated = before + ((c.layerId to c.celId) to RecordedCel<Int>(c.layerId, c.celId,
            listOf(record(40).copy(widthScale = 3f))))
        assertFalse(plan.isCurrent(unrelated), "Publishing an old whole-scene map must not overwrite an unrelated edit")
        val undone = RecordedEdits.prepare(plan.after, plan.step, false)
        assertEquals(before.getValue(a.layerId to a.celId).records, undone.after.getValue(a.layerId to a.celId).records)
    }

    @Test fun lateInvalidDestinationAndBrushRefuseWithoutChangingAnyCel() {
        val before = planes()
        val valid = addressed(before, a).let { it.copy(record = it.record.copy(widthScale = 2f)) }
        val bad = addressed(before, b).let { it.copy(record = it.record.copy(brushId = "missing")) }
        assertFailsWith<IllegalArgumentException> { RecordedEdits.replace(before, listOf(valid, bad), setOf("frozen")) }
        assertEquals(record(80), before.getValue(a.layerId to a.celId).records.single())
        assertFailsWith<IllegalStateException> { RecordedEdits.delete(before, setOf(a, b.copy(celId = "missing"))) }
        assertEquals(record(2), before.getValue(b.layerId to b.celId).records.single())
    }

    @Test fun additionsAllocateSequencePerDestinationAndKeepCallerOrder() {
        val before = planes()
        val aCopy = a.copy(recordId = "copy")
        val bCopy = b.copy(recordId = "copy")
        val second = b.copy(recordId = "second")
        val adds = listOf(bCopy, aCopy, second).map { AddressedStroke(it, record(900).copy(id = it.recordId)) }
        val plan = RecordedEdits.add(before, adds, setOf("frozen"))
        assertEquals(listOf(3L, 81L, 4L), plan.step.lines.map { it.after!!.seq })
        assertEquals(listOf("same", "copy", "second"), plan.after.getValue(b.layerId to b.celId).records.map { it.id })
        assertEquals(900L, adds.first().record.seq)
        assertEquals(1, before.getValue(b.layerId to b.celId).records.size)
    }

    @Test fun occupiedDuplicateLockedAndReorderedSequenceRefuseBeforePublication() {
        val before = planes()
        val old = addressed(before, a)
        assertFailsWith<IllegalArgumentException> { RecordedEdits.add(before, listOf(old), setOf("frozen")) }
        assertFailsWith<IllegalArgumentException> { RecordedEdits.replace(before, listOf(old, old), setOf("frozen")) }
        assertFailsWith<IllegalArgumentException> { RecordedEdits.replace(before,
            listOf(old.copy(record = old.record.copy(seq = 999))), setOf("frozen")) }
        val locked = before + ((a.layerId to a.celId) to RecordedCel<Int>(a.layerId, a.celId, listOf(old.record.copy(locked = true))))
        assertFailsWith<IllegalArgumentException> { RecordedEdits.delete(locked, setOf(a)) }
        assertTrue(RecordedEdits.delete(locked, setOf(a), allowLocked = true).after.getValue(a.layerId to a.celId).records.isEmpty())
    }

    @Test fun noOpReplacementHasNoHistoryAndSequenceExhaustionRefusesWholeAddBatch() {
        val before = planes()
        assertTrue(RecordedEdits.replace(before, listOf(addressed(before, a)), setOf("frozen")).step.lines.isEmpty())
        val almostFull: Map<Pair<String, String?>, RecordedCel<Int>> = mapOf(
            (a.layerId to a.celId) to RecordedCel(a.layerId, a.celId, listOf(record(Long.MAX_VALUE - 1))))
        val adds = listOf("copy", "another").map { AddressedStroke(a.copy(recordId = it), record(0).copy(id = it)) }
        assertFailsWith<IllegalArgumentException> { RecordedEdits.add(almostFull, adds, setOf("frozen")) }
        assertEquals(Long.MAX_VALUE - 1, almostFull.values.single().records.single().seq)
    }
}
