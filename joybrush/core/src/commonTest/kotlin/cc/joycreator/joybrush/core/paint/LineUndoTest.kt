package cc.joycreator.joybrush.core.paint

import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.paint.UndoLog.LineChange
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.CelComposer
import cc.joycreator.joybrush.core.vector.CelComposer.Items
import cc.joycreator.joybrush.core.vector.CelComposer.Line
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** JB-5.20 D9 (5.20c): editable lines share the one undo history. One press, one step, lines or pixels. */
class LineUndoTest {

    private fun log() = UndoLog<String>(budgetBytes = 1L shl 30, sizeOf = { 1L }, release = {})

    private fun line(id: String, seq: Long, x: Float = 0f, n: Int = 3) =
        Line(seq, StrokeRecord(id, "pen", 1L, 0f, 1f, (0 until n).map { PenSample(x + it, 0f, it.toDouble()) }))

    private fun change(id: String, before: Line?, after: Line?) = LineChange("L", "c", id, before, after)

    @Test
    fun aBatchOfLineEditsFoldsToTheFirstBeforeAndTheLastAfter() {
        val u = log()
        val a1 = line("a", 1); val a2 = line("a", 1, x = 5f); val b1 = line("b", 2)
        u.push(UndoLog.Step(emptyList(), lines = listOf(change("a", null, a1))))
        u.push(UndoLog.Step(emptyList(), lines = listOf(change("a", a1, a2), change("b", null, b1))))
        u.push(UndoLog.Step(emptyList(), lines = listOf(change("b", b1, null))))
        u.mergeNewest(3)
        val step = u.undo()!!
        // a: made then moved = made at its moved place. b: made then deleted inside the batch = no change at all.
        assertEquals(listOf("a"), step.lines.map { it.id })
        assertNull(step.lines.single().before)
        assertSame(a2, step.lines.single().after)
    }

    @Test
    fun aStepThatOnlyChangedLinesIsNeverPoppedAsEmpty() {
        // replaceInNewest pops a step left with nothing in it; a line change is something.
        val u = log()
        u.push(UndoLog.Step(listOf(UndoLog.TileChange("L", 1L, "before", "after")), lines = listOf(change("a", null, line("a", 1)))))
        assertTrue(u.replaceInNewest(listOf(UndoLog.TileChange("L", 1L, null, null))))
        assertTrue(u.canUndo, "the line change keeps the step")
        assertEquals(1, u.undo()!!.lines.size)
    }

    @Test
    fun extendingTheNewestStepKeepsItsLines() {
        val u = log()
        u.push(UndoLog.Step(listOf(UndoLog.TileChange("L", 1L, "x", "y")), lines = listOf(change("a", null, line("a", 1)))))
        assertTrue(u.extendNewest(listOf(UndoLog.TileChange("L", 2L, null, "z"))) { true })
        assertEquals(1, u.undo()!!.lines.size)
    }

    @Test
    fun linesCountTowardTheBudgetBySamples() {
        val u = UndoLog<String>(budgetBytes = 1L shl 30, sizeOf = { 0L }, release = {})
        u.push(UndoLog.Step(emptyList(), lines = listOf(change("a", line("a", 1, n = 10), line("a", 1, n = 30)))))
        assertEquals(LineChange.LINE_BYTES + 40 * LineChange.SAMPLE_BYTES, u.heldBytes)
    }

    @Test
    fun undoingADeletePutsTheLineBackInItsPlaceInTimeOrder() {
        val a = line("a", 1); val b = line("b", 3); val c = line("c", 5)
        val start = Items(listOf(a, b, c), emptyMap())
        val delete = listOf(change("b", b, null))
        val gone = CelComposer.applyLines(start, delete, "L", "c", forward = true)
        assertEquals(listOf("a", "c"), gone.lines.map { it.record.id })
        val back = CelComposer.applyLines(gone, delete, "L", "c", forward = false)
        assertEquals(listOf("a", "b", "c"), back.lines.map { it.record.id }, "b returns between a and c, by its seq")
        // Another cel's changes are not this cel's.
        assertSame(start, CelComposer.applyLines(start, listOf(LineChange("L", "other", "b", b, null)), "L", "c", forward = true))
    }

    @Test
    fun aBatchUndoneAppliesItsChangesNewestFirst() {
        // Redo walks a step forward; undo walks it back in reverse, so an edit after a creation unwinds to nothing.
        val a1 = line("a", 1); val a2 = line("a", 1, x = 9f)
        val step = listOf(change("a", null, a1), change("a", a1, a2))
        val done = CelComposer.applyLines(Items.EMPTY, step, "L", "c", forward = true)
        assertSame(a2.record, done.lines.single().record)
        assertTrue(CelComposer.applyLines(done, step, "L", "c", forward = false).lines.isEmpty())
    }
}
