package cc.joycreator.joybrush.android.select

import android.app.Activity
import android.graphics.RectF
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import cc.joycreator.joybrush.androidkit.select.LiftedLines
import cc.joycreator.joybrush.androidkit.select.LiftedPixels
import cc.joycreator.joybrush.androidkit.select.SelectTool
import cc.joycreator.joybrush.androidkit.select.SelectionHost
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.select.SelectionMask
import cc.joycreator.joybrush.core.select.SelectionState
import cc.joycreator.joybrush.core.stroke.AddressedStroke
import cc.joycreator.joybrush.core.stroke.LineAddress
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.InkLine
import cc.joycreator.joybrush.core.view.ViewTransform
import com.fadcam.ui.faditor.transform.HandleModel
import com.fadcam.ui.faditor.transform.TransformOverlayView
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Body contacts belong to the box while they are in flight; only a clean body tap is replayed to SelectTool. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SelectBodyTapRoutingTest {
    private class Host : SelectionHost {
        private val records = listOf(record("a", 1L), record("b", 2L))
        private fun record(id: String, seq: Long) = StrokeRecord(
            id, "brush", 7L, 0f, 1f,
            listOf(PenSample(100f, 100f, 0.0), PenSample(200f, 200f, 1.0), PenSample(300f, 300f, 2.0)),
            seq = seq,
        )

        private fun lines() = records.map { r ->
            val xs = r.samples.map { it.x.toDouble() }.toDoubleArray()
            val ys = r.samples.map { it.y.toDouble() }.toDoubleArray()
            SelectionHost.Line(
                LineAddress("layer", null, r.id),
                InkLine(r.id, xs, ys, DoubleArray(xs.size) { 1.0 }),
                r, false, null,
            )
        }

        var editCalls = 0
        override var paintClip: SelectionMask? = null
        override fun activeLines() = lines()
        override fun visibleLines() = lines()
        override fun liftLines(ids: Set<LineAddress>): LiftedLines? = null
        override fun replaceLines(after: List<AddressedStroke>): Boolean { editCalls++; return false }
        override fun addLines(records: List<AddressedStroke>): Boolean { editCalls++; return false }
        override fun deleteLines(ids: Set<LineAddress>): Boolean { editCalls++; return false }
        override fun bakeLines(ids: Set<LineAddress>): Boolean { editCalls++; return false }
        override fun liftPixels(mask: SelectionMask, keepSource: Boolean): LiftedPixels? = null
        override fun dimRest(alpha: Float) {}
        override fun adjustColour(hueDeg: Float, saturation: Float, brightness: Float, done: Boolean) {}
        override fun penButtonTap(screenX: Float, screenY: Float) {}
        override fun toast(text: String, action: String?, onAction: (() -> Unit)?) {}
        override fun invalidate() {}
    }

    private data class Fixture(
        val overlay: TransformOverlayView,
        val tool: SelectTool,
        val host: Host,
    )

    private fun fixture(): Fixture {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        val overlay = TransformOverlayView(activity)
        overlay.setCleanStyle(true)
        root.addView(overlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        activity.setContentView(root)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 500, 500)

        val host = Host()
        val tool = SelectTool(host, 1f) { true }
        val addresses = listOf(LineAddress("layer", null, "a"), LineAddress("layer", null, "b"))
        tool.chooseFromOrder(addresses, 0, adding = false, at = SelectionState.Pick(200.0, 200.0))
        assertEquals(setOf(addresses[0]), (tool.selection as SelectionState.Lines).ids)

        val box = SelectBoxHost(tool, { ViewTransform() }, { RectF(0f, 0f, 500f, 500f) })
        // Mirror the SelectController gate: record only the initial body DOWN. The overlay's
        // pinch terminal reason must suppress replay even though that original contact was single.
        overlay.setTouchGate { event, kind ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN && kind == HandleModel.Kind.BODY) {
                box.noteBodyContact(event.x, event.y, single = event.pointerCount == 1)
            }
            true
        }
        overlay.setHost(box, HandleModel())
        return Fixture(overlay, tool, host)
    }

    private fun event(action: Int, points: List<Pair<Int, Pair<Float, Float>>>, time: Long): MotionEvent {
        val properties = Array(points.size) { i ->
            MotionEvent.PointerProperties().apply { id = points[i].first; toolType = MotionEvent.TOOL_TYPE_FINGER }
        }
        val coords = Array(points.size) { i ->
            MotionEvent.PointerCoords().apply {
                x = points[i].second.first
                y = points[i].second.second
                pressure = 1f
                size = 1f
            }
        }
        return MotionEvent.obtain(
            0L, time, action, points.size, properties, coords, 0, 0, 1f, 1f,
            0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
    }

    private fun send(view: View, action: Int, vararg points: Pair<Int, Pair<Float, Float>>, time: Long) {
        event(action, points.toList(), time).also {
            try {
                val accepted = view.dispatchTouchEvent(it)
                if (it.actionMasked == MotionEvent.ACTION_DOWN || it.actionMasked == MotionEvent.ACTION_MOVE ||
                    it.actionMasked == MotionEvent.ACTION_POINTER_DOWN) assertTrue(accepted)
            } finally {
                it.recycle()
            }
        }
    }

    private fun p(id: Int, x: Float, y: Float) = id to (x to y)
    private fun selectedIds(f: Fixture) = (f.tool.selection as SelectionState.Lines).ids

    @Test fun bodyDownThenCancelDoesNotCycleOrWriteAnEdit() {
        val f = fixture()
        val before = f.tool.boxDoc!!.copyOf()
        send(f.overlay, MotionEvent.ACTION_DOWN, p(0, 200f, 200f), time = 1L)
        send(f.overlay, MotionEvent.ACTION_CANCEL, p(0, 200f, 200f), time = 10L)

        assertEquals(setOf(LineAddress("layer", null, "a")), selectedIds(f))
        assertArrayEquals(before, f.tool.boxDoc!!, 0.001f)
        assertEquals(0, f.host.editCalls)
    }

    @Test fun cleanBodyDownUpCyclesToTheOverlappingLineWithoutWritingAnEdit() {
        val f = fixture()
        send(f.overlay, MotionEvent.ACTION_DOWN, p(0, 200f, 200f), time = 1L)
        send(f.overlay, MotionEvent.ACTION_UP, p(0, 200f, 200f), time = 10L)

        assertEquals(setOf(LineAddress("layer", null, "b")), selectedIds(f))
        assertEquals(0, f.host.editCalls)
    }

    @Test fun unmovedTwoFingerPinchThenReleaseDoesNotReplayABodyTap() {
        val f = fixture()
        send(f.overlay, MotionEvent.ACTION_DOWN, p(0, 180f, 200f), time = 1L)
        send(f.overlay, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            p(0, 180f, 200f), p(1, 220f, 200f), time = 10L)
        send(f.overlay, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            p(0, 180f, 200f), p(1, 220f, 200f), time = 20L)
        send(f.overlay, MotionEvent.ACTION_UP, p(0, 180f, 200f), time = 30L)

        assertEquals(setOf(LineAddress("layer", null, "a")), selectedIds(f))
        assertEquals(0, f.host.editCalls)
    }

    @Test fun movedBodyDragCanceledRestoresTheBoxAndSelectionWithoutAnEdit() {
        val f = fixture()
        val before = f.tool.boxDoc!!.copyOf()
        send(f.overlay, MotionEvent.ACTION_DOWN, p(0, 200f, 200f), time = 1L)
        send(f.overlay, MotionEvent.ACTION_MOVE, p(0, 240f, 220f), time = 20L)
        assertNotNull(f.tool.boxDoc)
        assertTrue(!before.contentEquals(f.tool.boxDoc!!))
        send(f.overlay, MotionEvent.ACTION_CANCEL, p(0, 240f, 220f), time = 30L)

        assertArrayEquals(before, f.tool.boxDoc!!, 0.001f)
        assertEquals(setOf(LineAddress("layer", null, "a")), selectedIds(f))
        assertEquals(0, f.host.editCalls)
    }
}
