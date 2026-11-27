package cc.joycreator.joybrush.android.select

import android.view.MotionEvent
import cc.joycreator.joybrush.androidkit.lines.LineEdit
import cc.joycreator.joybrush.androidkit.select.StrokeEraserTool
import cc.joycreator.joybrush.androidkit.tools.CanvasTool
import cc.joycreator.joybrush.core.view.ViewTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The whole-line eraser (owner 11-27): one stroke, every line it touched on the active layer gone, ONE undo step; a
 * cancelled stroke takes nothing; locked lines stay; with another tool in the hand it steps aside for Select's tool.
 * Lines a (y 10) and b (y 30) on the active plane, c (y 50) on another cel; all run x 10..48 (the shared fixture).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class StrokeEraserToolTest {

    private val view = ViewTransform()

    private fun ev(action: Int, x: Float, y: Float): MotionEvent = MotionEvent.obtain(0L, 0L, action, x, y, 0)

    private class Next : CanvasTool {
        var downs = 0
        override fun down(ev: MotionEvent, pointerIndex: Int, view: ViewTransform): Boolean { downs++; return true }
        override fun event(ev: MotionEvent, view: ViewTransform) = true
    }

    private fun tool(rig: Rig, inHand: Boolean = true, next: CanvasTool? = null,
                     mode: cc.joycreator.joybrush.core.vector.InkEraseMode = cc.joycreator.joybrush.core.vector.InkEraseMode.WHOLE_STROKE) =
        StrokeEraserTool(host = { rig.host }, inHand = { inHand }, radiusDoc = { 3f }, next = { next }, fingersErase = { true },
            say = { rig.ui.notes += it }, mode = { mode })

    /** A vertical line at x = 30 from y 0 to 38, crossing line a (y 10) and b (y 30) of the shared fixture. */
    private fun vertical(id: String, seq: Long) = lineRecord(id, seq).let { r ->
        r.copy(samples = r.samples.mapIndexed { i, s -> s.copy(x = 30f, y = i * 2f) }) }

    private fun stroke(t: StrokeEraserTool, x0: Float, y0: Float, x1: Float, y1: Float) {
        t.down(ev(MotionEvent.ACTION_DOWN, x0, y0), 0, view)
        t.event(ev(MotionEvent.ACTION_MOVE, (x0 + x1) / 2f, (y0 + y1) / 2f), view)
        t.event(ev(MotionEvent.ACTION_MOVE, x1, y1), view)
        t.event(ev(MotionEvent.ACTION_UP, x1, y1), view)
    }

    @Test fun trimToCrossingCutsAnOvershootBackToTheLineItCrossesAsOneStep() {
        val rig = Rig(mapOf(planeP to listOf(lineRecord("a", 1, y = 10f), vertical("v", 2)), planeQ to emptyList()))
        // Line a runs x 10..48 and crosses v at x 30: erase its left end, before the crossing.
        stroke(tool(rig, mode = cc.joycreator.joybrush.core.vector.InkEraseMode.TO_INTERSECTION), 14f, 0f, 14f, 20f)
        val edit = rig.canvas.submitted.single().first as LineEdit.Split
        assertTrue("nothing erased whole", edit.removed.isEmpty())
        val a = edit.after.single()
        assertEquals("a", a.address.recordId)
        assertTrue("trimmed back to the crossing: " + a.record.samples.map { it.x }, a.record.samples.all { it.x >= 26f })
        assertTrue("the far end is kept", a.record.samples.any { it.x >= 46f })
        assertTrue("the crossing line is not in the edit", edit.addresses().none { it.recordId == "v" })
    }

    @Test fun partOfALineShortensItsEndInPlaceAndErasesAWholeShortLine() {
        val rig = Rig(mapOf(planeP to listOf(lineRecord("a", 1, y = 10f), lineRecord("tiny", 2, x0 = 100f, y = 60f).let { r ->
            r.copy(samples = r.samples.take(3)) }), planeQ to emptyList()))
        val t = tool(rig, mode = cc.joycreator.joybrush.core.vector.InkEraseMode.PARTIAL)
        stroke(t, 47f, 0f, 47f, 20f)
        var edit = rig.canvas.submitted.last().first as LineEdit.Split
        val a = edit.after.single()
        assertTrue("its right end is gone", a.record.samples.maxOf { it.x } < 46f)
        assertTrue("its left end is kept", a.record.samples.minOf { it.x } <= 11f)
        rig.canvas.submitted.clear()
        stroke(t, 100f, 50f, 106f, 70f)
        edit = rig.canvas.submitted.single().first as LineEdit.Split
        assertEquals("a line rubbed out completely goes", setOf("tiny"), edit.removed.map { it.recordId }.toSet())
    }

    @Test fun aCutInTheMiddleOfALineLeavesItWholeAndSaysSo() {
        val rig = Rig(mapOf(planeP to listOf(lineRecord("a", 1, y = 10f)), planeQ to emptyList()))
        stroke(tool(rig, mode = cc.joycreator.joybrush.core.vector.InkEraseMode.PARTIAL), 28f, 0f, 28f, 20f)
        assertTrue("two pieces would need two seqs: nothing is written", rig.canvas.submitted.isEmpty())
        assertEquals(1, rig.ui.notes.count { it.startsWith("Cutting a line in two isn't possible yet") })
    }

    @Test fun aStrokeAcrossTwoLinesTakesBothAwayAsOneStep() {
        val rig = Rig()
        val t = tool(rig)
        assertTrue(t.down(ev(MotionEvent.ACTION_DOWN, 20f, 0f), 0, view))
        t.event(ev(MotionEvent.ACTION_MOVE, 20f, 40f), view)   // crosses a (y 10) and b (y 30), not c (y 50)
        t.event(ev(MotionEvent.ACTION_UP, 20f, 40f), view)
        assertEquals("one stroke is ONE edit, so one press of Undo", 1, rig.canvas.submitted.size)
        val delete = rig.canvas.submitted.single().first as LineEdit.Delete
        assertEquals(setOf("a", "b"), delete.ids.map { it.recordId }.toSet())
    }

    @Test fun aStrokeThatTouchesNothingWritesNothing() {
        val rig = Rig()
        val t = tool(rig)
        t.down(ev(MotionEvent.ACTION_DOWN, 200f, 200f), 0, view)
        t.event(ev(MotionEvent.ACTION_MOVE, 220f, 220f), view)
        t.event(ev(MotionEvent.ACTION_UP, 220f, 220f), view)
        assertTrue(rig.canvas.submitted.isEmpty())
    }

    @Test fun aCancelledStrokeTakesNothingAway() {
        val rig = Rig()
        val t = tool(rig)
        t.down(ev(MotionEvent.ACTION_DOWN, 20f, 0f), 0, view)
        t.event(ev(MotionEvent.ACTION_MOVE, 20f, 40f), view)
        t.cancel()
        assertTrue(rig.canvas.submitted.isEmpty())
    }

    @Test fun lockedLinesStay() {
        val rig = Rig(mapOf(planeP to listOf(lineRecord("a", 1, y = 10f).copy(locked = true), lineRecord("b", 2, y = 30f)),
            planeQ to listOf(lineRecord("c", 1, y = 50f))))
        val t = tool(rig)
        t.down(ev(MotionEvent.ACTION_DOWN, 20f, 0f), 0, view)
        t.event(ev(MotionEvent.ACTION_MOVE, 20f, 40f), view)
        t.event(ev(MotionEvent.ACTION_UP, 20f, 40f), view)
        val delete = rig.canvas.submitted.single().first as LineEdit.Delete
        assertEquals(setOf("b"), delete.ids.map { it.recordId }.toSet())
    }

    @Test fun withAnotherToolInTheHandItStepsAsideForSelect() {
        val rig = Rig()
        val next = Next()
        val t = tool(rig, inHand = false, next = next)
        assertTrue("Select's tool behind it is offered the contact", t.down(ev(MotionEvent.ACTION_DOWN, 20f, 0f), 0, view))
        assertEquals(1, next.downs)
        t.event(ev(MotionEvent.ACTION_MOVE, 20f, 40f), view)
        t.event(ev(MotionEvent.ACTION_UP, 20f, 40f), view)
        assertTrue("nothing erased when it is not in the hand", rig.canvas.submitted.isEmpty())
        assertFalse(tool(rig, inHand = false, next = null).down(ev(MotionEvent.ACTION_DOWN, 20f, 0f), 0, view))
    }

    @Test fun aLayerWithNoLinesSaysSoOnce() {
        val rig = Rig(mapOf(planeP to emptyList(), planeQ to listOf(lineRecord("c", 1, y = 50f))))
        val t = tool(rig)
        repeat(2) {
            t.down(ev(MotionEvent.ACTION_DOWN, 20f, 0f), 0, view)
            t.event(ev(MotionEvent.ACTION_UP, 20f, 0f), view)
        }
        assertEquals(1, rig.ui.notes.count { it.startsWith("This eraser takes away whole lines") })
        assertTrue(rig.canvas.submitted.isEmpty())
    }
}
