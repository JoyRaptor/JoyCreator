package cc.joycreator.joybrush.android.select

import cc.joycreator.joybrush.androidkit.lines.LinePlane
import cc.joycreator.joybrush.core.paint.UndoLog
import cc.joycreator.joybrush.core.vector.CelComposer
import cc.joycreator.joybrush.core.vector.RecordedCel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LineSelectionClipboardTest {
    @org.junit.After fun clearClipboard() { LineSelectionClipboard.clearForTests() }
    @Test fun copiedLinesPasteAsOneNewBatchIntoAnotherLayerAndUndoRedoTogether() {
        LineSelectionClipboard.clearForTests()
        val sourceSamples = lineRecord("original", 8).samples.toMutableList()
        val source = lineRecord("original", 8).copy(samples = sourceSamples, locked = true, groupId = "pair")
        assertTrue(LineSelectionClipboard.accept(canonicalCopyPayload(listOf(source))))
        sourceSamples.clear()

        val entries = LineSelectionClipboard.snapshot()
        assertTrue(LineSelectionClipboard.hasLines)
        assertEquals("clipboard owns its sample list", 20, entries.single().record.samples.size)
        assertEquals("pair", entries.single().record.groupId)
        assertTrue(entries.single().record.locked)

        val target = LinePlane("another-layer", null)
        val pasted = LineSelectionClipboard.paste(entries, target, listOf("pasted-1"), listOf("target-brush"), 35f, -12f)
        assertEquals("one paste is one batch", 1, pasted.size)
        val item = pasted.single()
        assertEquals(target.address("pasted-1"), item.address)
        assertEquals("target-brush", item.record.brushId)
        assertEquals("the engine assigns the destination sequence", 0L, item.record.seq)
        assertEquals(45f, item.record.samples.first().x, 0f)
        assertEquals(-2f, item.record.samples.first().y, 0f)
        assertTrue(item.record.locked)
        assertNotEquals("pasted groups never join the source group", "pair", item.record.groupId)
        assertNotEquals("original", item.record.id)
    }

    @Test fun pasteIsOneUndoStepAndRedoRestoresTheSameFreshLines() {
        val entries = canonicalCopyPayload(listOf(lineRecord("source-a", 1), lineRecord("source-b", 2, y = 30f))).entries
        val plane = LinePlane("destination", "frame-1")
        val pasted = LineSelectionClipboard.paste(entries, plane, listOf("new-a", "new-b"), listOf("brush", "brush"), 0f, 0f)
        assertEquals("generated ids never collide", 2, pasted.map { it.record.id }.toSet().size)
        assertTrue(pasted.map { it.record.id }.none { id -> entries.any { it.record.id == id } })

        val after = pasted.mapIndexed { i, item -> item.record.copy(seq = i + 1L) }
        val step = UndoLog.Step<Int>(emptyList(), lines = after.map { record ->
            UndoLog.LineChange(plane.layerId, plane.celId, record.id, before = null, after = CelComposer.Line(record.seq, record))
        })
        assertEquals("both lines are recorded as the changes in one history step", 2, step.lines.size)
        val base = RecordedCel<Int>(plane.layerId, plane.celId)
        val written = base.apply(step, forward = true)
        val undone = written.apply(step, forward = false)
        val redone = undone.apply(step, forward = true)
        assertTrue(undone.records.isEmpty())
        assertEquals(after, redone.records)
    }

    @Test fun pastedGroupMembersStayTogetherWithAFreshGroupIdentity() {
        val entries = canonicalCopyPayload(listOf("a", "b").mapIndexed { i, id ->
            lineRecord(id, i + 1L).copy(groupId = "source-group")
        }).entries
        val records = LineSelectionClipboard.paste(entries, planeP, listOf("new-a", "new-b"), listOf("brush", "brush"), 0f, 0f) { "pasted-group" }
        assertEquals(listOf("pasted-group", "pasted-group"), records.map { it.record.groupId })
    }

    @Test fun lowHeapPasteRefusesBeforeTransformAndKeepsTheClipboard() {
        assertTrue(LineSelectionClipboard.accept(canonicalCopyPayload(listOf(lineRecord("source", 1)))))
        val before = LineSelectionClipboard.snapshot()
        assertFalse(LineSelectionClipboard.canPaste(before, 1L))
        assertThrows(IllegalArgumentException::class.java) {
            LineSelectionClipboard.paste(before, planeP, listOf("fresh"), listOf("brush"), 10f, 10f, availableHeapBytes = 1L)
        }
        assertTrue(before === LineSelectionClipboard.snapshot())
        assertEquals(10f, before.single().record.samples.first().x, 0f)
    }

    @Test fun pasteCannotReuseASourceIdentity() {
        val source = canonicalCopyPayload(listOf(lineRecord("source", 1))).entries
        assertThrows(IllegalArgumentException::class.java) {
            LineSelectionClipboard.paste(source, planeP, listOf("source"), listOf("brush"), 0f, 0f)
        }
    }
}
