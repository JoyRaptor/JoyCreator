package cc.joycreator.joybrush.android.select

import android.app.Activity
import android.graphics.RectF
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import cc.joycreator.joybrush.androidkit.select.LiftedPixels
import cc.joycreator.joybrush.androidkit.select.SelectTool
import cc.joycreator.joybrush.androidkit.select.SelectionHost
import cc.joycreator.joybrush.core.select.Homography
import cc.joycreator.joybrush.core.select.SelectionMask
import cc.joycreator.joybrush.core.select.SelectionState
import cc.joycreator.joybrush.core.shape.Pt
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.stroke.AddressedStroke
import cc.joycreator.joybrush.core.stroke.LineAddress
import cc.joycreator.joybrush.core.view.ViewTransform
import com.fadcam.ui.faditor.transform.HandleModel
import com.fadcam.ui.faditor.transform.TransformOverlayView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SelectBoxCancelTest {
    private class Pixels : LiftedPixels {
        var commits = 0
        var previewTransform: Homography = Homography.IDENTITY
        override fun setTransform(h: Homography) { previewTransform = h }
        fun point() = previewTransform.apply(Pt(150.0, 150.0))
        override fun commit(): Boolean { commits++; return true }
        override fun clear() = false
        override fun fill(argb: Int) = false
        override fun release() {}
    }

    private class SelectHost(private val pixels: Pixels) : SelectionHost {
        override var paintClip: SelectionMask? = null
        override fun activeLines() = emptyList<SelectionHost.Line>()
        override fun visibleLines() = emptyList<SelectionHost.Line>()
        override fun liftLines(ids: Set<LineAddress>) = null
        override fun replaceLines(after: List<AddressedStroke>) = false
        override fun addLines(records: List<AddressedStroke>) = false
        override fun deleteLines(ids: Set<LineAddress>) = false
        override fun bakeLines(ids: Set<LineAddress>) = false
        override fun liftPixels(mask: SelectionMask, keepSource: Boolean): LiftedPixels = pixels
        override fun dimRest(alpha: Float) {}
        override fun adjustColour(hueDeg: Float, saturation: Float, brightness: Float, done: Boolean) {}
        override fun penButtonTap(screenX: Float, screenY: Float) {}
        override fun toast(text: String, action: String?, onAction: (() -> Unit)?) {}
        override fun invalidate() {}
    }

    private class CountingHost(private val box: SelectBoxHost) : TransformOverlayView.Host by box {
        var commits = 0
        var cancels = 0
        override fun commitGesture(what: String) { commits++; box.commitGesture(what) }
        override fun cancelGesture() { cancels++; box.cancelGesture() }
    }

    private data class Fixture(
        val overlay: TransformOverlayView,
        val tool: SelectTool,
        val pixels: Pixels,
        val box: SelectBoxHost,
        val host: CountingHost,
        val gestures: MutableList<Boolean>,
    )

    private fun fixture(): Fixture {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        val overlay = TransformOverlayView(activity)
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        activity.setContentView(root)
        root.measure(View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 500, 500)

        val pixels = Pixels()
        val selectionHost = SelectHost(pixels)
        val tool = SelectTool(selectionHost, 1f) { true }
        tool.state.loop(
            listOf(Pt(100.0, 100.0), Pt(300.0, 100.0), Pt(300.0, 300.0), Pt(100.0, 300.0)),
            emptyList(), emptyList(), SelectionState.Reading(), adding = false,
        )
        tool.reread(SelectionState.Reading(take = SelectionState.Take.PIXELS))
        val view = ViewTransform()
        val box = SelectBoxHost(tool, { view }, { RectF(0f, 0f, 500f, 500f) })
        val counting = CountingHost(box)
        val gestures = mutableListOf<Boolean>()
        box.onGesture = { gestures += it }
        overlay.setHost(counting, HandleModel())
        return Fixture(overlay, tool, pixels, box, counting, gestures)
    }

    private fun event(action: Int, points: List<Pair<Int, Pair<Float, Float>>>, time: Long): MotionEvent {
        val properties = Array(points.size) { i ->
            MotionEvent.PointerProperties().apply { id = points[i].first; toolType = MotionEvent.TOOL_TYPE_FINGER }
        }
        val coords = Array(points.size) { i ->
            MotionEvent.PointerCoords().apply {
                x = points[i].second.first; y = points[i].second.second
                pressure = 1f; size = 1f
            }
        }
        return MotionEvent.obtain(0L, time, action, points.size, properties, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
    }

    private fun send(view: View, action: Int, vararg points: Pair<Int, Pair<Float, Float>>, time: Long) {
        event(action, points.toList(), time).also {
            try {
                val accepted = view.dispatchTouchEvent(it)
                if (it.actionMasked == MotionEvent.ACTION_DOWN || it.actionMasked == MotionEvent.ACTION_MOVE ||
                    it.actionMasked == MotionEvent.ACTION_POINTER_DOWN) assertTrue(accepted)
            } finally { it.recycle() }
        }
    }

    private fun p(id: Int, x: Float, y: Float) = id to (x to y)
    private fun quad(f: Fixture) = f.tool.boxDoc!!.copyOf()

    @Test fun movedSingleDragCancelRestoresBoxUnfadesAndDoesNotCommit() {
        val f = fixture()
        val before = quad(f)
        send(f.overlay, MotionEvent.ACTION_DOWN, p(0, 200f, 200f), time = 1L)
        send(f.overlay, MotionEvent.ACTION_MOVE, p(0, 240f, 220f), time = 20L)
        assertEquals(listOf(true), f.gestures)
        assertFalse(before.contentEquals(quad(f)))
        assertFalse(f.pixels.point().x == 150.0 && f.pixels.point().y == 150.0)
        send(f.overlay, MotionEvent.ACTION_CANCEL, p(0, 240f, 220f), time = 30L)
        assertTrue(before.contentEquals(quad(f)))
        assertEquals(Pt(150.0, 150.0), f.pixels.point())
        assertEquals(listOf(true, false), f.gestures)
        assertEquals(1, f.host.cancels)
        assertEquals(0, f.host.commits)
        assertEquals(0, f.pixels.commits)
    }

    @Test fun unmovedTapCancelsSnapshotWithoutCommitting() {
        val f = fixture()
        send(f.overlay, MotionEvent.ACTION_DOWN, p(0, 200f, 200f), time = 1L)
        send(f.overlay, MotionEvent.ACTION_UP, p(0, 200f, 200f), time = 10L)
        assertEquals(1, f.host.cancels)
        assertEquals(0, f.host.commits)
        assertEquals(0, f.pixels.commits)
        assertTrue(f.gestures.isEmpty())
    }

    @Test fun movedPinchCancelRestoresBoxAndCancelsOnce() {
        val f = fixture()
        val before = quad(f)
        send(f.overlay, MotionEvent.ACTION_DOWN, p(0, 160f, 200f), time = 1L)
        send(f.overlay, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            p(0, 160f, 200f), p(1, 240f, 200f), time = 10L)
        send(f.overlay, MotionEvent.ACTION_MOVE, p(0, 130f, 200f), p(1, 270f, 200f), time = 20L)
        assertFalse(before.contentEquals(quad(f)))
        assertFalse(f.pixels.point() == Pt(150.0, 150.0))
        send(f.overlay, MotionEvent.ACTION_CANCEL, p(0, 130f, 200f), p(1, 270f, 200f), time = 30L)
        assertTrue(before.contentEquals(quad(f)))
        assertEquals(Pt(150.0, 150.0), f.pixels.point())
        assertEquals(1, f.host.cancels)
        assertEquals(0, f.host.commits)
        assertEquals(0, f.pixels.commits)
        assertEquals(listOf(true, false), f.gestures)
    }

    @Test fun cleanMovedPinchCommitsExactlyOnceWithoutCancel() {
        val f = fixture()
        send(f.overlay, MotionEvent.ACTION_DOWN, p(0, 160f, 200f), time = 1L)
        send(f.overlay, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            p(0, 160f, 200f), p(1, 240f, 200f), time = 10L)
        send(f.overlay, MotionEvent.ACTION_MOVE, p(0, 130f, 200f), p(1, 270f, 200f), time = 20L)
        send(f.overlay, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            p(0, 130f, 200f), p(1, 270f, 200f), time = 30L)
        send(f.overlay, MotionEvent.ACTION_UP, p(0, 130f, 200f), time = 40L)
        assertEquals(1, f.host.commits)
        assertEquals(0, f.host.cancels)
        assertEquals(1, f.pixels.commits)
        assertEquals(listOf(true, false), f.gestures)
    }

    @Test fun unmovedPinchCleanTerminalCancelsWithoutCommitting() {
        val f = fixture()
        send(f.overlay, MotionEvent.ACTION_DOWN, p(0, 160f, 200f), time = 1L)
        send(f.overlay, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            p(0, 160f, 200f), p(1, 240f, 200f), time = 10L)
        send(f.overlay, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            p(0, 160f, 200f), p(1, 240f, 200f), time = 20L)
        send(f.overlay, MotionEvent.ACTION_UP, p(0, 160f, 200f), time = 30L)
        assertEquals(1, f.host.cancels)
        assertEquals(0, f.host.commits)
        assertEquals(0, f.pixels.commits)
        assertTrue(f.gestures.isEmpty())
    }

    @Test fun repeatedHostTranslationsAreMeasuredFromTheGestureStart() {
        val f = fixture()
        val before = quad(f)
        f.host.beginGesture()
        f.host.writeTranslate(10f, 0f)
        f.host.writeTranslate(20f, 0f)
        val after = quad(f)
        for (i in 0 until 4) {
            assertEquals(before[i * 2] + 20f, after[i * 2], 0.01f)
            assertEquals(before[i * 2 + 1], after[i * 2 + 1], 0.01f)
        }
        f.host.commitGesture("Move")
        assertEquals(1, f.host.commits)
        assertEquals(0, f.host.cancels)
        assertEquals(1, f.pixels.commits)
    }

    @Test fun repeatedHostSimilarityUsesStartSizeAndTheSuppliedCentroid() {
        val f = fixture()
        val before = quad(f)
        val startCx = (before[0] + before[2] + before[4] + before[6]) / 4f
        val startCy = (before[1] + before[3] + before[5] + before[7]) / 4f
        val startWidth = before[2] - before[0]
        f.host.beginGesture()
        f.host.writeSimilarity(1.2f, 0f, startCx + 10f, startCy + 5f)
        f.host.writeSimilarity(1.5f, 0f, startCx + 30f, startCy + 12f)
        val after = quad(f)
        val endCx = (after[0] + after[2] + after[4] + after[6]) / 4f
        val endCy = (after[1] + after[3] + after[5] + after[7]) / 4f
        assertEquals(startWidth * 1.5f, after[2] - after[0], 0.01f)
        assertEquals(startCx + 30f, endCx, 0.01f)
        assertEquals(startCy + 12f, endCy, 0.01f)
        f.host.commitGesture("Transform")
        assertEquals(1, f.host.commits)
        assertEquals(0, f.host.cancels)
        assertEquals(1, f.pixels.commits)
    }

    @Test fun rebasedPinchCancelReturnsToTheOriginalDragStart() {
        val f = fixture()
        val before = quad(f)
        f.host.beginGesture()
        f.host.writeTranslate(24f, 8f)
        f.host.rebaseGesture()
        f.host.writeSimilarity(1.4f, 8f, 240f, 220f)
        assertFalse(before.contentEquals(quad(f)))
        assertFalse(f.pixels.point() == Pt(150.0, 150.0))
        f.host.cancelGesture()
        assertTrue(before.contentEquals(quad(f)))
        assertEquals(Pt(150.0, 150.0), f.pixels.point())
        assertEquals(0, f.host.commits)
        assertEquals(1, f.host.cancels)
        assertEquals(0, f.pixels.commits)
        assertEquals(listOf(true, false), f.gestures)
    }

    @Test fun rebasedPinchCommitsTheAbsorbedDragAsOneStep() {
        val f = fixture()
        val before = quad(f)
        f.host.beginGesture()
        f.host.writeTranslate(24f, 8f)
        f.host.rebaseGesture()
        f.host.writeSimilarity(1.4f, 8f, 240f, 220f)
        assertFalse(before.contentEquals(quad(f)))
        f.host.commitGesture("Transform")
        assertEquals(1, f.host.commits)
        assertEquals(0, f.host.cancels)
        assertEquals(1, f.pixels.commits)
        assertEquals(listOf(true, false), f.gestures)
    }
}
