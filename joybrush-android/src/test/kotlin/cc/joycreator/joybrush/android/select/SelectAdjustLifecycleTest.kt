package cc.joycreator.joybrush.android.select

import android.graphics.Color
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.SeekBar
import cc.joycreator.joybrush.android.chrome.ChromeKit
import cc.joycreator.joybrush.android.chrome.JbIcon
import cc.joycreator.joybrush.android.chrome.Popovers
import cc.joycreator.joybrush.androidkit.JbCanvasView
import cc.joycreator.joybrush.androidkit.lines.LineEdit
import cc.joycreator.joybrush.core.select.SelectionState
import cc.joycreator.joybrush.core.stroke.AddressedStroke
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real Controller/SeekBar dispatch over the real selection host; no EGL execution is claimed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SelectAdjustLifecycleTest {
    private val surfaces = ArrayList<JbCanvasView>()
    @After fun pauseSurfaces() { surfaces.forEach { it.onPause() } }
    private fun descendants(v: View): List<View> = listOf(v) +
        if (v is ViewGroup) (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList()

    private inner class Bench {
        val context = RuntimeEnvironment.getApplication()
        val canvas = JbCanvasView(context).also { surfaces += it }
        val root = FrameLayout(context)
        val anchor = View(context).also { root.addView(it, FrameLayout.LayoutParams(40, 40)) }
        val popovers = Popovers(ChromeKit(context), root)
        val rig = Rig(mapOf(planeP to listOf(lineRecord("a", 1).copy(colorArgb = Color.RED),
            lineRecord("b", 2, y = 30f).copy(colorArgb = Color.BLUE))))
        val before = rig.host.activeLines().map { AddressedStroke(it.address, it.record) }
        val controller = SelectController(ChromeKit(context), root, canvas, rig.host, popovers, object : SelectController.App {
            override fun paperArgb() = Color.WHITE
            override fun openColour(anchor: View, current: Int, onPicked: (Int) -> Unit) {}
            override fun openLineBrushes(anchor: View, onPicked: (String) -> Unit) {}
            override fun brushIcon(brushId: String) = JbIcon.BRUSH
            override fun newLineId() = "new"
            override fun copySelection(selection: SelectionState.Selection) {}
            override fun restyleRing(on: Boolean, pulse: Boolean) {}
            override fun paintMakesLines() = false
            override fun setPaintMakesLines(on: Boolean) {}
            override fun hasRestingThumbForCount() = false
        })
        val hue: SeekBar
        private var eventTime = 0L
        init {
            rig.canvas.supportsLineLift = true
            assertTrue(controller.tool.selectAddresses(before.map { it.address }))
            layout()
            SelectController::class.java.getDeclaredMethod("showAdjust", View::class.java)
                .also { it.isAccessible = true }.invoke(controller, anchor)
            layout()
            hue = descendants(root).filterIsInstance<SeekBar>().first()
            assertTrue(hue.width > 0 && hue.height > 0)
        }
        fun layout() {
            root.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 400, 800)
        }
        fun touch(action: Int, fraction: Float) {
            eventTime += 16L
            val event = MotionEvent.obtain(0L, eventTime, action, hue.width * fraction, hue.height / 2f, 0)
            hue.dispatchTouchEvent(event)
            event.recycle()
        }
        fun preview() {
            touch(MotionEvent.ACTION_DOWN, 0.5f)
            touch(MotionEvent.ACTION_MOVE, 0.8f)
            assertTrue(rig.canvas.previews.isNotEmpty())
            assertNotEquals(before.map { it.record.colorArgb }, rig.canvas.previews.last().map { it.record.colorArgb })
            assertTrue("preview creates no history", rig.canvas.submitted.isEmpty())
        }
    }

    @Test fun cancelRestoresOriginalRecordsAndMakesNoHistoryStep() {
        val b = Bench()
        b.preview()
        b.touch(MotionEvent.ACTION_CANCEL, 0.8f)
        assertTrue("ACTION_CANCEL must not commit", b.rig.canvas.submitted.isEmpty())
        assertEquals(b.before, b.rig.canvas.previews.last())
        assertEquals(b.before, b.rig.host.activeLines().map { AddressedStroke(it.address, it.record) })
        assertEquals(180, b.hue.progress)
    }

    @Test fun dismissRollsBackAnUnfinishedPreviewWithoutHistory() {
        val b = Bench()
        b.preview()
        b.popovers.close()
        assertFalse(b.popovers.isOpen)
        assertTrue(b.rig.canvas.submitted.isEmpty())
        assertEquals("dismiss must restore the lifted colours", b.before, b.rig.canvas.previews.last())
        assertEquals(b.before, b.rig.host.activeLines().map { AddressedStroke(it.address, it.record) })
    }

    @Test fun upCommitsExactlyOnceAndDismissDoesNotUndoThatCommit() {
        val b = Bench()
        b.preview()
        b.touch(MotionEvent.ACTION_UP, 0.8f)
        assertEquals(1, b.rig.canvas.submitted.size)
        val committed = (b.rig.canvas.submitted.single().first as LineEdit.Replace).after
        assertNotEquals(b.before, committed)
        assertEquals(2, committed.map { it.record.colorArgb }.distinct().size)
        assertEquals(180, b.hue.progress)
        b.popovers.close()
        assertEquals(1, b.rig.canvas.submitted.size)
        assertEquals(committed, b.rig.host.activeLines().map { AddressedStroke(it.address, it.record) })
    }

    @Test fun oldSliderUpAfterOpenCannotEditTheNewSelection() {
        val b = Bench()
        b.preview()
        b.controller.tool.end()
        b.rig.canvas.epoch++
        b.rig.publish(mapOf(planeP to listOf(lineRecord("a", 1).copy(colorArgb = Color.GREEN))))
        assertTrue(b.controller.tool.selectAddresses(listOf(planeP.address("a"))))
        val current = b.rig.host.activeLines().map { AddressedStroke(it.address, it.record) }
        b.touch(MotionEvent.ACTION_UP, 0.8f)
        b.popovers.close()
        assertTrue("an old gesture cannot edit drawing B", b.rig.canvas.submitted.isEmpty())
        assertEquals(current, b.rig.host.activeLines().map { AddressedStroke(it.address, it.record) })
    }
}
