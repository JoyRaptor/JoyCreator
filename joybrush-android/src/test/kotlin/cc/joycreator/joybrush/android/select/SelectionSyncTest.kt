package cc.joycreator.joybrush.android.select

import cc.joycreator.joybrush.androidkit.lines.LineEditResult
import cc.joycreator.joybrush.androidkit.lines.LineEdit
import cc.joycreator.joybrush.androidkit.select.SelectTool
import cc.joycreator.joybrush.core.select.SelectionState
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

    @Test fun aDeleteEndsTheSelectionAndSendsOneEdit() {
        val b = Bench()
        b.select("a", "b")
        assertEquals(setOf("a", "b"), b.selectedIds())
        assertTrue(b.tool.deleteSelection())
        assertNull(b.tool.selection)
        val delete = b.rig.canvas.submitted[0].first as LineEdit.Delete
        assertEquals(setOf(planeP.address("a"), planeP.address("b")), delete.ids)
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

    @Test fun aSelectionEndsWhenItsLineIsGone() {
        val b = Bench()
        b.select("a", "b")
        b.rig.publish(mapOf(planeP to listOf(lineRecord("b", 2, y = 30f)), planeQ to listOf(lineRecord("c", 1, y = 50f))))
        SelectionSync.afterLinesChanged(b.tool, b.rig.host)
        assertNull(b.tool.selection)
        assertFalse(b.tool.canTransform)
    }
}
