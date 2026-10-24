package cc.joycreator.joybrush.android.select

import android.view.MotionEvent
import android.view.View
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SelectColourDragTest {
    @Test fun aDragSamplesLiveAndTakesExactlyOnceWhileReturnAndCancelTakeNothing() {
        val view = View(RuntimeEnvironment.getApplication()).apply { layout(0, 0, 40, 40) }
        var taps = 0
        var moves = 0
        val takes = mutableListOf<Boolean>()
        view.setOnClickListener { taps++ }
        val touch = SelectColourDrag(1f, { _, _, _ -> moves++ }, { takes += it })
        fun event(action: Int, x: Float) {
            val e = MotionEvent.obtain(0, 10, action, x, 20f, 0)
            touch.onTouch(view, e); e.recycle()
        }
        event(MotionEvent.ACTION_DOWN, 20f)
        event(MotionEvent.ACTION_MOVE, 80f)
        assertEquals(1, moves)
        assertTrue(takes.isEmpty())
        event(MotionEvent.ACTION_UP, 80f)
        assertEquals(listOf(true), takes)
        event(MotionEvent.ACTION_DOWN, 20f)
        event(MotionEvent.ACTION_MOVE, 80f)
        event(MotionEvent.ACTION_UP, 20f)
        event(MotionEvent.ACTION_DOWN, 20f)
        event(MotionEvent.ACTION_MOVE, 80f)
        event(MotionEvent.ACTION_CANCEL, 80f)
        assertEquals(listOf(true, false, false), takes)
        event(MotionEvent.ACTION_DOWN, 20f)
        event(MotionEvent.ACTION_UP, 20f)
        assertEquals(1, taps)
    }
}
