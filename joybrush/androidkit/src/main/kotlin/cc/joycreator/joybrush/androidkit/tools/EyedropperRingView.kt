package cc.joycreator.joybrush.androidkit.tools

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * The ring that follows the pen while the eyedropper is up (JB-2.03a): 44 dp across, 6 dp thick, the top half the NEW colour and the
 * bottom half the colour it would replace (the convention every painter knows), plus a faint circle at the place a hold began, which
 * is where you slide back to cancel. Draws nothing when there is no state. It takes no touches: it only shows.
 */
class EyedropperRingView(context: Context) : View(context) {

    private val d = context.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val box = RectF()
    private var state: EyedropState? = null

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** UI thread. Null hides the ring. */
    fun setState(s: EyedropState?) {
        state = s
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val s = state ?: return
        val r = Eyedropper.RING_DP * d / 2f
        val thick = Eyedropper.RING_THICK_DP * d

        // The cancel circle, drawn faintly: the way out.
        if (s.cancelR > 0f) {
            rim.strokeWidth = 1.5f * d
            rim.color = Color.argb(if (s.overCancel) 0xB0 else 0x60, 255, 255, 255)
            c.drawCircle(s.cancelCx, s.cancelCy, s.cancelR, rim)
        }

        box.set(s.x - r + thick / 2f, s.y - r + thick / 2f, s.x + r - thick / 2f, s.y + r - thick / 2f)
        paint.strokeWidth = thick
        // Top half: what you would take (or, over the cancel circle, what you already have).
        paint.color = if (s.overCancel) s.oldArgb else s.newArgb
        c.drawArc(box, 180f, 180f, false, paint)
        // Bottom half: what you have now.
        paint.color = s.oldArgb
        c.drawArc(box, 0f, 180f, false, paint)
        // A hairline either side so the ring reads on any colour of paint.
        rim.strokeWidth = 1f * d
        rim.color = Color.argb(0x90, 0, 0, 0)
        c.drawCircle(s.x, s.y, r, rim)
        c.drawCircle(s.x, s.y, r - thick, rim)
    }
}
