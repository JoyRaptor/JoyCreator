package cc.joycreator.joybrush.android.select

import android.app.Activity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import cc.joycreator.joybrush.android.chrome.ChromeKit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SelectCountViewTest {
    private class Actions(var model: SelectCountView.Model? = SelectCountView.Model(0, 5)) : SelectCountView.Callbacks {
        val begins = mutableListOf<Boolean>()
        val chosen = mutableListOf<Int>()
        var ends = 0
        var accept = true

        override fun begin(pen: Boolean): SelectCountView.Model? {
            begins += pen
            return model
        }

        override fun choose(index: Int): Boolean {
            chosen += index
            if (accept) model = model?.copy(index = index)
            return accept
        }

        override fun end() { ends++ }
    }

    private fun view(actions: Actions = Actions()): SelectCountView =
        SelectCountView(ChromeKit(RuntimeEnvironment.getApplication()), actions).apply { show(0, 5) }

    private fun p(id: Int, x: Float, y: Float = 20f, tool: Int = MotionEvent.TOOL_TYPE_FINGER) =
        Pointer(id, x, y, tool)

    private data class Pointer(val id: Int, val x: Float, val y: Float, val tool: Int)

    private fun event(action: Int, pointers: List<Pointer>, time: Long): MotionEvent {
        val props = Array(pointers.size) { i -> MotionEvent.PointerProperties().apply {
            id = pointers[i].id
            toolType = pointers[i].tool
        } }
        val coords = Array(pointers.size) { i -> MotionEvent.PointerCoords().apply {
            x = pointers[i].x; y = pointers[i].y; pressure = 1f; size = 1f
        } }
        val source = if (pointers.firstOrNull()?.tool == MotionEvent.TOOL_TYPE_STYLUS) InputDevice.SOURCE_STYLUS else InputDevice.SOURCE_TOUCHSCREEN
        return MotionEvent.obtain(0L, time, action, pointers.size, props, coords, 0, 0, 1f, 1f, 0, 0, source, 0)
    }

    private fun send(view: SelectCountView, action: Int, time: Long, vararg pointers: Pointer) {
        event(action, pointers.toList(), time).also { try { view.dispatchTouchEvent(it) } finally { it.recycle() } }
    }

    private fun begin(view: SelectCountView, time: Long = 1L, pointer: Pointer = p(0, 50f)) =
        send(view, MotionEvent.ACTION_DOWN, time, pointer)

    private fun move(view: SelectCountView, time: Long, vararg pointers: Pointer) =
        send(view, MotionEvent.ACTION_MOVE, time, *pointers)

    private fun attach(view: SelectCountView) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        activity.setContentView(root)
        root.measure(android.view.View.MeasureSpec.makeMeasureSpec(300, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(200, android.view.View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 300, 200)
    }

    @Test fun dragStepsLiveInBothDirectionsAndWraps() {
        val actions = Actions(SelectCountView.Model(2, 5))
        val view = view(actions)
        begin(view)
        move(view, 10, p(0, 62f))
        move(view, 20, p(0, 74f))
        move(view, 30, p(0, 86f))
        send(view, MotionEvent.ACTION_UP, 40, p(0, 86f))
        assertEquals(listOf(3, 4, 0), actions.chosen)
        assertEquals(1, actions.ends)

        actions.model = SelectCountView.Model(1, 5)
        begin(view, 50, p(0, 86f))
        move(view, 60, p(0, 74f))
        move(view, 70, p(0, 62f))
        send(view, MotionEvent.ACTION_UP, 80, p(0, 62f))
        assertEquals(listOf(3, 4, 0, 0, 4), actions.chosen)
        assertEquals(2, actions.ends)
    }

    @Test fun stationaryTapCyclesOnceAndCancelKeepsAcceptedStepsWithoutAnotherPick() {
        val actions = Actions(SelectCountView.Model(4, 5))
        val view = view(actions)
        begin(view)
        send(view, MotionEvent.ACTION_UP, 10, p(0, 50f))
        assertEquals(listOf(0), actions.chosen)
        assertEquals(1, actions.ends)

        begin(view, 20, p(0, 50f))
        move(view, 30, p(0, 62f))
        send(view, MotionEvent.ACTION_CANCEL, 40, p(0, 62f))
        assertEquals(listOf(0, 1), actions.chosen)
        assertEquals(2, actions.ends)
    }

    @Test fun secondaryPointerCannotTakeOverAndOwnerLiftEndsWithoutTap() {
        val actions = Actions(SelectCountView.Model(0, 4))
        val view = view(actions)
        begin(view)
        send(view, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 10,
            p(0, 50f), p(1, 90f))
        move(view, 20, p(0, 50f), p(1, 130f))
        assertTrue(actions.chosen.isEmpty())
        move(view, 30, p(0, 62f), p(1, 142f))
        assertEquals(listOf(1), actions.chosen)
        send(view, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 40,
            p(0, 62f), p(1, 142f))
        assertEquals(0, actions.ends)
        send(view, MotionEvent.ACTION_POINTER_UP, 50, p(0, 62f), p(1, 142f))
        assertEquals(1, actions.ends)
        send(view, MotionEvent.ACTION_UP, 60, p(1, 142f))
        assertEquals(listOf(1), actions.chosen)
        assertEquals(1, actions.ends)
    }

    @Test fun showRefreshDuringDragDoesNotMoveTheDownBaseline() {
        val actions = Actions(SelectCountView.Model(1, 5))
        val view = view(actions)
        begin(view)
        move(view, 10, p(0, 62f))
        assertEquals(listOf(2), actions.chosen)
        view.show(2, 5) // controller refresh after the first live choice
        move(view, 20, p(0, 74f))
        assertEquals(listOf(2, 3), actions.chosen)
        send(view, MotionEvent.ACTION_UP, 30, p(0, 74f))
        assertEquals(1, actions.ends)
    }

    @Test fun accessibilityClickAndScrollUseTheSameWrappedSessionCallbacks() {
        val actions = Actions(SelectCountView.Model(4, 5))
        val view = view(actions)
        assertTrue(view.contentDescription.toString().contains("Tap to choose the next line"))
        assertTrue(view.performClick())
        assertTrue(view.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, null))
        assertEquals(listOf(0, 4), actions.chosen)
        assertEquals(listOf(false, false), actions.begins)
        assertEquals(2, actions.ends)
    }

    @Test fun accessibilityActionsDoNotStartASecondSessionDuringTouch() {
        val actions = Actions(SelectCountView.Model(0, 4))
        val view = view(actions)
        begin(view)
        assertTrue(view.performClick())
        assertTrue(view.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null))
        assertEquals(listOf(false), actions.begins)
        assertTrue(actions.chosen.isEmpty())
        send(view, MotionEvent.ACTION_CANCEL, 10, p(0, 50f))
        assertEquals(1, actions.ends)
    }

    @Test fun declinedBeginAndChoiceDoNotLeakOrRepeatCallbacks() {
        val actions = Actions(model = null)
        val view = view(actions)
        begin(view)
        send(view, MotionEvent.ACTION_UP, 10, p(0, 50f))
        assertTrue(actions.chosen.isEmpty())
        assertEquals(0, actions.ends)

        actions.model = SelectCountView.Model(0, 3)
        actions.accept = false
        begin(view, 20)
        move(view, 30, p(0, 62f))
        move(view, 40, p(0, 62f))
        send(view, MotionEvent.ACTION_UP, 50, p(0, 62f))
        assertEquals(listOf(1), actions.chosen)
        assertEquals(1, actions.ends)
    }

    @Test fun declinedTouchBlocksAccessibilityUntilItsTerminalEvent() {
        val actions = Actions(model = null)
        val view = view(actions)
        begin(view)
        assertTrue(view.performClick())
        assertTrue(view.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null))
        assertEquals(listOf(false), actions.begins)
        send(view, MotionEvent.ACTION_UP, 10, p(0, 50f))
        assertTrue(view.performClick())
        assertEquals(listOf(false, false), actions.begins)
    }

    @Test fun verticalTravelSuppressesTapWithoutChangingHorizontalStepChoice() {
        val actions = Actions(SelectCountView.Model(0, 4))
        val view = view(actions)
        begin(view, pointer = p(0, 50f, 20f))
        move(view, 10, p(0, 50f, 40f))
        send(view, MotionEvent.ACTION_UP, 20, p(0, 50f, 40f))
        assertTrue(actions.chosen.isEmpty())
        assertEquals(1, actions.ends)
    }

    @Test fun freshDownAfterHideDoesNotInheritMissingTerminalEvent() {
        val actions = Actions(SelectCountView.Model(0, 4))
        val view = view(actions)
        attach(view)
        begin(view)
        view.visibility = android.view.View.GONE
        assertEquals(1, actions.ends)
        view.show(0, 4)
        begin(view, 20, p(1, 60f)) // The hidden stream intentionally had no UP or CANCEL.
        send(view, MotionEvent.ACTION_UP, 30, p(1, 60f))
        assertEquals(listOf(1), actions.chosen)
        assertEquals(2, actions.ends)
    }

    @Test fun penOriginIsReportedAndHidingEndsAnActiveSession() {
        val actions = Actions(SelectCountView.Model(0, 3))
        val view = view(actions)
        attach(view)
        begin(view, pointer = p(4, 40f, tool = MotionEvent.TOOL_TYPE_STYLUS))
        assertEquals(listOf(true), actions.begins)
        view.visibility = android.view.View.GONE
        assertEquals(1, actions.ends)
        send(view, MotionEvent.ACTION_UP, 10, p(4, 40f, tool = MotionEvent.TOOL_TYPE_STYLUS))
        assertEquals(1, actions.ends)
    }

    @Test fun detachingDuringTouchConsumesTrailingUpWithoutCyclingAgain() {
        val actions = Actions(SelectCountView.Model(0, 3))
        val view = view(actions)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = FrameLayout(activity)
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        activity.setContentView(root)
        root.measure(android.view.View.MeasureSpec.makeMeasureSpec(300, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(200, android.view.View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 300, 200)
        begin(view)
        root.removeView(view)
        assertEquals(1, actions.ends)
        send(view, MotionEvent.ACTION_UP, 10, p(0, 50f))
        assertTrue(actions.chosen.isEmpty())
        assertEquals(1, actions.ends)
    }
}
