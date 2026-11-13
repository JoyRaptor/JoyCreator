package cc.joycreator.joybrush.android.chrome

import android.view.MotionEvent
import cc.joycreator.joybrush.core.guide.Guide
import cc.joycreator.joybrush.core.guide.GuideSettings
import cc.joycreator.joybrush.core.shape.Pt
import cc.joycreator.joybrush.core.view.ViewTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** JBX-03: a pen stroke that starts on the symmetry line paints; only a finger takes hold of the line's body. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GuideOverlayPenTouchTest {
    private fun overlay(): Pair<GuideOverlayView, MutableList<GuideSettings>> {
        val view = GuideOverlayView(ChromeKit(RuntimeEnvironment.getApplication()), ViewTransform())
        view.layout(0, 0, 1000, 1000)
        // A vertical line through x = 500, its ends far from the touch at (500, 500).
        view.settings = GuideSettings(symmetry = Guide.Symmetry(Pt(500.0, 0.0), Pt(500.0, 1000.0)))
        val changes = ArrayList<GuideSettings>()
        view.onChanged = { s, _ -> changes.add(s) }
        return view to changes
    }

    private fun down(tool: Int, x: Float, y: Float): MotionEvent {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = tool })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f })
        return MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 1, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
    }

    @Test fun aPenOrMouseOnTheLineFallsThroughToTheCanvas() {
        for (tool in listOf(MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER, MotionEvent.TOOL_TYPE_MOUSE)) {
            val (view, changes) = overlay()
            assertFalse("tool $tool", view.onTouchEvent(down(tool, 503f, 500f)))
            assertTrue(changes.isEmpty())
        }
    }

    @Test fun aFingerOnTheLineStillTakesHoldOfIt() {
        val (view, _) = overlay()
        assertTrue(view.onTouchEvent(down(MotionEvent.TOOL_TYPE_FINGER, 503f, 500f)))
    }

    @Test fun onlyFingersGrabTheBody() {
        assertTrue(GuideOverlayView.bodyGrabsFor(MotionEvent.TOOL_TYPE_FINGER))
        assertTrue(GuideOverlayView.bodyGrabsFor(MotionEvent.TOOL_TYPE_UNKNOWN))
        assertEquals(false, GuideOverlayView.bodyGrabsFor(MotionEvent.TOOL_TYPE_STYLUS))
    }
}
