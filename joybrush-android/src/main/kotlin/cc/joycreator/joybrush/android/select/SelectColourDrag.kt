package cc.joycreator.joybrush.android.select

import android.view.MotionEvent
import android.view.View
import cc.joycreator.joybrush.androidkit.tools.Eyedropper
import kotlin.math.hypot

/** A tap opens the colour picker; dragging away samples the canvas. Returning to the dot cancels. */
internal class SelectColourDrag(
    private val density: Float,
    private val move: (Float, Float, Boolean) -> Unit,
    private val end: (Boolean) -> Unit,
) : View.OnTouchListener {
    private var x = 0f
    private var y = 0f
    private var dragging = false

    override fun onTouch(v: View, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                x = e.rawX; y = e.rawY; dragging = false
                v.parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (hypot(e.rawX - x, e.rawY - y) > Eyedropper.DRAG_OFF_DP * density) dragging = true
                if (dragging) move(e.rawX, e.rawY, e.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER)
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) end(!Eyedropper.overPill(e.x, e.y, v.width, v.height)) else v.performClick()
                dragging = false
                v.parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) end(false)
                dragging = false
                v.parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }
}
