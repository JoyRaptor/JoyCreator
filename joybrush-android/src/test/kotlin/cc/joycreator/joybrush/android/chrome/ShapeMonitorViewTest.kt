package cc.joycreator.joybrush.android.chrome

import android.view.MotionEvent
import android.view.View
import cc.joycreator.joybrush.core.brush.QuadShapeSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** BRUSH-UI §L 4 (owner's mockup): the pen monitor that holds Unibrush's A and B shapes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "mdpi")
class ShapeMonitorViewTest {
    private val kit = ChromeKit(RuntimeEnvironment.getApplication())

    private class Host : ShapeMonitorView.Host {
        var a: QuadShapeSpec? = QuadShapeSpec(roundness = 0f)
        var b: QuadShapeSpec? = null
        var angle = 0f
        var onB = false
        val moves = ArrayList<Pair<QuadShapeSpec, Boolean>>()
        val turns = ArrayList<Pair<Float, Boolean>>()
        override fun shapeA() = a
        override fun shapeB() = b
        override fun angleDegrees() = angle
        override fun bSelected() = onB && b != null
        override fun select(b: Boolean) { if (b && this.b == null) this.b = a; onB = b }
        override fun shapeMoved(b: Boolean, shape: QuadShapeSpec, done: Boolean) { moves += shape to done; if (b) this.b = shape else a = shape }
        override fun angleMoved(degrees: Float, done: Boolean) { turns += degrees to done; angle = degrees }
    }

    private fun monitor(host: Host): ShapeMonitorView = ShapeMonitorView(kit, host).apply {
        measure(View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        layout(0, 0, measuredWidth, measuredHeight)
    }

    private fun touch(v: View, action: Int, x: Float, y: Float) = v.onTouchEvent(MotionEvent.obtain(0L, 0L, action, x, y, 0))

    // Geometry as drawn at mdpi: the ring is centred at (100, 16 + 94) with radius 94; the shapes' square is 0.42 r.
    private val cx = 100f
    private val cy = 110f
    private val r = 94f

    @Test fun draggingAPointReshapesTheChosenShapeAsOneStep() {
        val host = Host()
        val v = monitor(host)
        // Point 1 of the square is its top-left corner, (-1, -1).
        val px = cx - r * .42f; val py = cy - r * .42f
        assertTrue(touch(v, MotionEvent.ACTION_DOWN, px, py))
        touch(v, MotionEvent.ACTION_MOVE, px - 6f, py - 2f)
        touch(v, MotionEvent.ACTION_UP, px - 8f, py - 4f)
        assertEquals("live moves, then one done on lift", listOf(false, true), host.moves.map { it.second })
        val moved = host.a!!.points[0]
        assertTrue("the point moved out (clamped to the shape's own square)", moved.x <= -1f && moved.y <= -1f)
        assertEquals("the other points stay", QuadShapeSpec().points[1], host.a!!.points[1])
    }

    @Test fun theCaretOnTheRingTurnsTheTip() {
        val host = Host()
        val v = monitor(host)
        assertTrue(touch(v, MotionEvent.ACTION_DOWN, cx, cy - r))       // the caret sits at the top at 0°
        touch(v, MotionEvent.ACTION_MOVE, cx + r, cy)                  // to the right of the ring: 90°
        touch(v, MotionEvent.ACTION_UP, cx + r, cy)
        assertEquals(90f, host.angle, 0.5f)
        assertEquals(listOf(false, true), host.turns.map { it.second })
    }

    @Test fun theBDotMakesAndChoosesShapeB() {
        val host = Host()
        val v = monitor(host)
        assertFalse(host.bSelected())
        assertTrue(touch(v, MotionEvent.ACTION_DOWN, cx + r * .86f, cy + r * .86f))
        assertTrue("B now exists, as a copy of A, and is the shape being edited", host.b != null && host.bSelected())
        touch(v, MotionEvent.ACTION_DOWN, cx - r * .86f, cy + r * .86f)
        assertFalse(host.bSelected())
    }

    @Test fun withNoFourPointShapeItIsAPlainPenMonitor() {
        val host = Host().apply { a = null }
        val v = monitor(host)
        assertFalse("no shapes, nothing to grab", touch(v, MotionEvent.ACTION_DOWN, cx, cy - r))
    }
}
