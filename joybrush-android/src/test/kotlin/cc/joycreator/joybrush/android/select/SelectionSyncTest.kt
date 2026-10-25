package cc.joycreator.joybrush.android.select

import cc.joycreator.joybrush.androidkit.lines.LineEditResult
import cc.joycreator.joybrush.androidkit.lines.LineEdit
import cc.joycreator.joybrush.androidkit.select.SelectTool
import cc.joycreator.joybrush.core.select.SelectionState
import cc.joycreator.joybrush.core.stroke.AddressedStroke
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The real Select tool over the real host (a stand-in canvas): a move reaches the engine as one edit, a second move starts
 * from the first, and when the engine refuses what the tool already showed, the box goes back where the lines are.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SelectionSyncTest {
    @org.junit.After fun clearClipboard() { LineSelectionClipboard.clearForTests() }

    @Test fun oldDrawingRefusalCannotCancelTheNewDrawingsDragOrPendingEdit() = oldDrawingAnswerIsIgnored(false)
    @Test fun oldDrawingSuccessCannotRedrawOrReconcileTheNewDrawing() = oldDrawingAnswerIsIgnored(true)

    private fun oldDrawingAnswerIsIgnored(ok: Boolean) {
        val b = Bench()
        b.rig.canvas.supportsLineLift = true
        b.select("a")
        assertTrue(b.move(12f)) // drawing A, callback still pending
        b.tool.end()
        b.rig.canvas.epoch++
        b.rig.publish(mapOf(planeP to listOf(lineRecord("a", 1, x0 = 400f), lineRecord("b", 2, y = 30f))))
        b.select("a")
        assertTrue(b.move(20f)) // drawing B has its own pending edit
        val q = b.tool.boxDoc!!.copyOf()
        for (i in 0 until 4) q[i * 2] += 9f
        b.tool.beginBox()
        assertTrue(b.tool.boxTo(q)) // B is now in a second, unfinished drag
        val shown = b.tool.boxDoc!!.copyOf()
        val notes = b.rig.ui.notes.toList()
        val redraws = b.rig.canvas.redraws
        val overlays = b.rig.ui.overlays

        b.rig.canvas.answer(0, LineEditResult(ok, if (ok) null else "Old drawing refused"))

        assertEquals("old answer must not report into drawing B", notes, b.rig.ui.notes)
        assertTrue(b.rig.ui.refusals.isEmpty())
        assertEquals(redraws, b.rig.canvas.redraws)
        assertEquals(overlays, b.rig.ui.overlays)
        assertEquals(setOf("a"), b.selectedIds())
        assertArrayEquals(shown, b.tool.boxDoc!!, 0f)
        assertTrue("B's accepted edit must stay pending", b.rig.host.editing)
        assertEquals(420f, b.rig.host.activeLines().first { it.id == "a" }.record.samples.first().x, 0f)
        b.tool.cancelBox()
    }

    private class Bench {
        val rig = Rig()
        val tool = SelectTool(rig.host, 1f) { true }

        init {
            rig.ui.onRefused = { SelectionSync.afterRefusedEdit(tool) }
        }

        fun select(vararg ids: String) {
            for ((i, id) in ids.withIndex()) {
                tool.chooseFromOrder(listOf(planeP.address(id)), 0, adding = i > 0, at = SelectionState.Pick(50.0, 10.0))
            }
        }

        /** Moves the box [dx] document px right, as a drag of the box does: one step. */
        fun move(dx: Float): Boolean {
            val q = tool.boxDoc!!.copyOf()
            for (i in 0 until 4) q[i * 2] += dx
            tool.beginBox()
            assertTrue(tool.boxTo(q))
            return tool.commitBox()
        }

        fun selectedIds(): Set<String> = (tool.selection as? SelectionState.Lines)?.ids?.map { it.recordId }?.toSet().orEmpty()
    }

    @Test fun aMoveReachesTheEngineAsOneEditOnTheSelectedLineOnly() {
        val b = Bench()
        b.select("a")
        assertEquals(setOf("a"), b.selectedIds())
        assertTrue(b.move(30f))
        assertEquals(1, b.rig.canvas.submitted.size)
        val edit = b.rig.canvas.submitted[0].first as LineEdit.Replace
        assertEquals(listOf(planeP.address("a")), edit.after.map { it.address })
        assertEquals(40f, edit.after[0].record.samples[0].x, 0.01f)
        assertEquals("seq is the engine's to keep", 1L, edit.after[0].record.seq)
    }

    @Test fun aSecondMoveStartsFromTheFirstEvenBeforeTheEngineHasAnswered() {
        val b = Bench()
        b.select("a")
        assertTrue(b.move(30f))
        assertTrue(b.move(12f))
        assertEquals(2, b.rig.canvas.submitted.size)
        val second = b.rig.canvas.submitted[1].first as LineEdit.Replace
        assertEquals("10 + 30 + 12", 52f, second.after[0].record.samples[0].x, 0.01f)
    }

    @Test fun aDuplicateIsSelectedAndItsBoxFitsIt() {
        val b = Bench()
        b.select("a")
        assertTrue(b.tool.duplicate { "copy-1" })
        assertEquals(setOf("copy-1"), b.selectedIds())
        assertNotNull(b.tool.boxDoc)
        val add = b.rig.canvas.submitted[0].first as LineEdit.Add
        assertEquals(planeP.address("copy-1"), add.records[0].address)
        assertEquals(0L, add.records[0].record.seq)
    }

    @Test fun copiedLinesPasteIntoAnEmptyDrawingWithTheirFrozenBrushAndBecomeSelected() {
        assertTrue(LineSelectionClipboard.accept(canonicalCopyPayload(listOf(lineRecord("source", 8)))))
        val rig = Rig(mapOf(planeP to emptyList()), brushes = emptyMap())
        val brush = rig.host.importBrush(LineSelectionClipboard.snapshot().single().brush())!!
        val batch = LineSelectionClipboard.paste(LineSelectionClipboard.snapshot(), rig.host.activeLinePlane()!!,
            listOf("fresh"), listOf(brush), 0f, 0f)
        val tool = SelectTool(rig.host, 1f) { true }
        assertTrue(tool.addAndSelect(batch))
        val add = rig.canvas.submitted.single().first as LineEdit.Add
        assertEquals(setOf(brush), add.brushes.keys)
        assertEquals(setOf(planeP.address("fresh")), (tool.selection as SelectionState.Lines).ids)
        assertNotNull(tool.boxDoc)
    }

    @Test fun aDeleteEndsTheSelectionAndSendsOneEdit() {
        val b = Bench()
        b.select("a", "b")
        assertEquals(setOf("a", "b"), b.selectedIds())
        assertTrue(b.tool.deleteSelection())
        assertNull(b.tool.selection)
        val delete = b.rig.canvas.submitted[0].first as LineEdit.Delete
        assertEquals(setOf(planeP.address("a"), planeP.address("b")), delete.ids)
    }

    @Test fun selectAllDoesNotExpandGroupsIntoAnotherLayer() {
        val b = Bench()
        val other = cc.joycreator.joybrush.androidkit.lines.LinePlane("other", null)
        b.rig.publish(mapOf(
            planeP to listOf(lineRecord("a", 1).copy(groupId = "group")),
            other to listOf(lineRecord("c", 1).copy(groupId = "group")),
        ))
        b.rig.canvas.visible = listOf(planeP, other)
        assertTrue(b.tool.selectAllLines())
        assertEquals(setOf("a"), b.selectedIds())
        SelectionSync.afterLinesChanged(b.tool, b.rig.host)
        assertEquals(setOf("a"), b.selectedIds())
        SelectionSync.afterRefusedEdit(b.tool)
        assertEquals(setOf("a"), b.selectedIds())
    }

    @Test fun selectAllAndInvertOnlyUsePickableLinesOnTheActiveLayer() {
        val b = Bench()
        b.rig.publish(mapOf(
            planeP to listOf(lineRecord("a", 1).copy(locked = true), lineRecord("b", 2)),
            planeQ to listOf(lineRecord("c", 1)),
        ))
        assertTrue(b.tool.selectAllLines())
        assertEquals(setOf("b"), b.selectedIds())
        assertFalse(b.tool.invertSelection())
        assertEquals(emptySet<String>(), b.selectedIds())
        assertNull(b.tool.selection)
        b.tool.state.pickLocked = true
        assertTrue(b.tool.selectAllLines())
        assertEquals(setOf("a", "b"), b.selectedIds())
    }

    @Test fun lockAndGroupChangesTravelAsOneReplaceEdit() {
        val b = Bench()
        b.select("a", "b")
        assertTrue(b.tool.lockSelection(true))
        val edit = b.rig.canvas.submitted.single().first as LineEdit.Replace
        assertEquals(2, edit.after.size)
        assertTrue(edit.after.all { it.record.locked })
        assertTrue(b.tool.groupSelection("pair"))
        val grouped = (b.rig.canvas.submitted[1].first as LineEdit.Replace).after.map { it.record }
        val saved = cc.joycreator.joybrush.core.stroke.StrokeCodec.decodeAll(
            cc.joycreator.joybrush.core.stroke.StrokeCodec.encodeAll(grouped))
        assertTrue(saved.all { it.locked && it.groupId == "pair" })
        assertTrue(b.tool.lockSelection(false))
        assertTrue(b.tool.groupSelection(null))
        val ungrouped = (b.rig.canvas.submitted[3].first as LineEdit.Replace).after
        assertTrue(ungrouped.all { !it.record.locked && it.record.groupId == null })
    }

    @Test fun whenTheEngineRefusesAMoveTheBoxGoesBackToTheLines() {
        val b = Bench()
        b.select("a")
        val home = b.tool.boxDoc!!.copyOf()
        assertTrue(b.move(30f))
        val moved = b.tool.boxDoc!!
        assertEquals("the box followed the drag", home[0] + 30f, moved[0], 0.01f)

        b.rig.canvas.answer(0, LineEditResult(false, "That layer is locked."))

        assertEquals(listOf("That layer is locked."), b.rig.ui.refusals)
        assertEquals("the same line is still selected", setOf("a"), b.selectedIds())
        assertArrayEquals("and its box is where the line really is", home, b.tool.boxDoc!!, 0.01f)
    }

    @Test fun afterARefusalTheSecondMoveStartsFromWhereTheLineIs() {
        val b = Bench()
        b.select("a")
        assertTrue(b.move(30f))
        b.rig.canvas.answer(0, LineEditResult(false, "nope"))
        assertTrue(b.move(5f))
        val edit = b.rig.canvas.submitted[1].first as LineEdit.Replace
        assertEquals("10 + 5, not 10 + 30 + 5", 15f, edit.after[0].record.samples[0].x, 0.01f)
    }

    @Test fun aRefusalWithNothingSelectedChangesNothing() {
        val b = Bench()
        SelectionSync.afterRefusedEdit(b.tool)
        assertNull(b.tool.selection)
    }

    @Test fun aRefusedDeleteRestoresTheSelectionImmediately() {
        val b = Bench()
        b.select("a")
        assertTrue(b.tool.deleteSelection())
        b.rig.canvas.answer(0, LineEditResult(false, "That layer is locked."))
        assertEquals(setOf("a"), b.selectedIds())
    }

    @Test fun anUnrelatedSnapshotDoesNotCancelAnUnfinishedBoxDrag() {
        val b = Bench()
        b.select("a")
        val moved = b.tool.boxDoc!!.copyOf().also { for (i in 0 until 4) it[2 * i] += 20f }
        b.tool.beginBox(); assertTrue(b.tool.boxTo(moved))
        b.rig.publish(mapOf(planeP to listOf(lineRecord("a", 1), lineRecord("b", 2, y = 30f), lineRecord("new", 3))))
        SelectionSync.afterLinesChanged(b.tool, b.rig.host)
        assertArrayEquals(moved, b.tool.boxDoc!!, 0.01f)
        assertTrue(b.tool.commitBox())
        assertEquals(1, b.rig.canvas.submitted.size)
    }

    @Test fun aSelectionKeepsGoingWhenTheLinesChangeButAreStillThere() {
        val b = Bench()
        b.select("a")
        b.rig.publish(mapOf(
            planeP to listOf(lineRecord("a", 1, y = 10f), lineRecord("b", 2, y = 30f), lineRecord("n", 3, y = 90f)),
            planeQ to listOf(lineRecord("c", 1, y = 50f)),
        ))
        SelectionSync.afterLinesChanged(b.tool, b.rig.host)
        assertEquals(setOf("a"), b.selectedIds())
    }

    @Test fun aSelectionKeepsItsSurvivingLinesWhenOneIsGone() {
        val b = Bench()
        b.select("a", "b")
        b.rig.publish(mapOf(planeP to listOf(lineRecord("b", 2, y = 30f)), planeQ to listOf(lineRecord("c", 1, y = 50f))))
        SelectionSync.afterLinesChanged(b.tool, b.rig.host)
        assertEquals(setOf("b"), b.selectedIds())
        assertTrue(b.tool.canTransform)
    }

    @Test fun undoAndRedoKeepASelectionAndRefitItsBoxToTheRestoredLine() {
        val b = Bench()
        b.select("a")
        val original = b.tool.boxDoc!!.copyOf()
        assertTrue(b.move(30f))
        val move = b.rig.canvas.submitted.single().first as LineEdit.Replace
        val moved = move.after.single().record
        b.rig.publish(mapOf(planeP to listOf(moved, lineRecord("b", 2, y = 30f)), planeQ to listOf(lineRecord("c", 1, y = 50f))))
        b.rig.canvas.answer(0, LineEditResult(true))
        SelectionSync.afterLinesChanged(b.tool, b.rig.host)
        assertEquals(setOf("a"), b.selectedIds())
        assertEquals(original[0] + 30f, b.tool.boxDoc!![0], 0.01f)

        b.rig.publish(mapOf(planeP to listOf(lineRecord("a", 1), lineRecord("b", 2, y = 30f)), planeQ to listOf(lineRecord("c", 1, y = 50f))))
        SelectionSync.afterLinesChanged(b.tool, b.rig.host)
        assertEquals(setOf("a"), b.selectedIds())
        assertArrayEquals(original, b.tool.boxDoc!!, 0.01f)
    }

    @Test fun undoAfterDeleteRestoresTheLinesAndTheirSelection() {
        val b = Bench()
        b.select("a")
        assertTrue(b.tool.deleteSelection())
        assertNull(b.tool.selection)

        val remainder = listOf(lineRecord("b", 2, y = 30f))
        b.rig.publish(mapOf(planeP to remainder, planeQ to listOf(lineRecord("c", 1, y = 50f))))
        b.rig.canvas.answer(0, LineEditResult(true))
        SelectionSync.afterLinesChanged(b.tool, b.rig.host)
        assertNull("a missing line is remembered only for a possible undo", b.tool.selection)

        b.rig.publish(mapOf(planeP to listOf(lineRecord("a", 1), lineRecord("b", 2, y = 30f)), planeQ to listOf(lineRecord("c", 1, y = 50f))))
        SelectionSync.afterLinesChanged(b.tool, b.rig.host)
        assertEquals(setOf("a"), b.selectedIds())
        assertNotNull(b.tool.boxDoc)
    }

    @Test fun anotherDrawingCannotReviveADeletedSelectionEvenWhenAnIdIsReused() {
        val b = Bench()
        b.select("a"); assertTrue(b.tool.deleteSelection())
        b.rig.canvas.epoch++
        SelectionSync.afterLinesChanged(b.tool, b.rig.host)
        assertNull(b.tool.selection)
    }

    @Test fun undoPastLineCreationEndsTheSelection() {
        val b = Bench()
        val new = lineRecord("created", 0, x0 = 200f)
        assertTrue(b.tool.addAndSelect(listOf(AddressedStroke(planeP.address(new.id), new))))
        val add = b.rig.canvas.submitted.single().first as LineEdit.Add
        val written = add.records.single().record.copy(seq = 3L)
        b.rig.publish(mapOf(planeP to listOf(lineRecord("a", 1), lineRecord("b", 2, y = 30f), written), planeQ to listOf(lineRecord("c", 1, y = 50f))))
        b.rig.canvas.answer(0, LineEditResult(true))
        SelectionSync.afterLinesChanged(b.tool, b.rig.host)
        assertEquals(setOf("created"), b.selectedIds())

        b.rig.publish(mapOf(planeP to listOf(lineRecord("a", 1), lineRecord("b", 2, y = 30f)), planeQ to listOf(lineRecord("c", 1, y = 50f))))
        SelectionSync.afterLinesChanged(b.tool, b.rig.host)
        assertNull(b.tool.selection)
    }
}
