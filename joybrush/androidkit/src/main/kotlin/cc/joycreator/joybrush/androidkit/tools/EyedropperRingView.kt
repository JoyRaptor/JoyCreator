package cc.joycreator.joybrush.androidkit.tools

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * The ring that follows the pen while the eyedropper is up (JB-2.03a): 44 dp across (92 dp, lifted off the fingertip with a stem and a
 * crosshair, for a finger), 6 dp thick, the top half the NEW colour and the
 * bottom half the colour it would replace (the convention every painter knows), plus a faint circle at the place a hold began, which
 * is where you slide back to cancel. Draws nothing when there is no state. It takes no touches: it only shows.
 *
 * The faint circle is drawn whenever a hold has one, and it brightens only when [EyedropState.overCancel] is true — the touch is inside
 * it AND has already been outside it once ([Eyedropper.overCancel]). So the ring shows the new colour in its top half from the frame
 * after the first screen read, not from the moment it appears: at that moment the sampled colour is not known yet and
 * [EyedropState.newArgb] carries the old colour, which the top half then matches the bottom half for one frame.
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
        // A finger gets the big ring, lifted off the fingertip (owner, 2026-09-30); a pen the small one under its tip.
        val r = (if (s.finger) Eyedropper.FINGER_RING_DP else Eyedropper.RING_DP) * d / 2f
        val thick = (if (s.finger) Eyedropper.FINGER_RING_THICK_DP else Eyedropper.RING_THICK_DP) * d

        // The cancel circle, drawn faintly: the way out. A dark hairline under the white one, as the main ring has, or a white circle
        // vanishes into white paper and there is no way out to see.
        if (s.cancelR > 0f) {
            rim.strokeWidth = 3.5f * d
            rim.color = Color.argb(0x60, 0, 0, 0)
            c.drawCircle(s.cancelCx, s.cancelCy, s.cancelR, rim)
            rim.strokeWidth = 1.5f * d
            rim.color = Color.argb(if (s.overCancel) 0xB0 else 0x60, 255, 255, 255)
            c.drawCircle(s.cancelCx, s.cancelCy, s.cancelR, rim)
        }

        if (s.finger) {
            // The stem from the fingertip to the ring, dark under light, so the eye follows it to the ring on any paint.
            val dx = s.x - s.fingerX
            val dy = s.y - s.fingerY
            val len = kotlin.math.hypot(dx, dy)
            if (len > r) {
                val ex = s.x - dx / len * r
                val ey = s.y - dy / len * r
                rim.strokeWidth = 3f * d
                rim.color = Color.argb(0x80, 0, 0, 0)
                c.drawLine(s.fingerX, s.fingerY, ex, ey, rim)
                rim.strokeWidth = 1.5f * d
                rim.color = Color.argb(0xE0, 255, 255, 255)
                c.drawLine(s.fingerX, s.fingerY, ex, ey, rim)
            }
        }

        box.set(s.x - r + thick / 2f, s.y - r + thick / 2f, s.x + r - thick / 2f, s.y + r - thick / 2f)
        paint.strokeWidth = thick
        // Top half: what you would take — or, once the touch is back inside a circle it has already left, what you already have,
        // which is the cancel. Both halves then read the same, and that is the tell.
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

        if (s.finger) {
            // The crosshair: the exact pixel being picked, since the finger is not on it.
            val arm = 7f * d
            val gap = 2.5f * d
            for (pass in 0..1) {
                rim.strokeWidth = if (pass == 0) 3f * d else 1.5f * d
                rim.color = if (pass == 0) Color.argb(0x90, 0, 0, 0) else Color.WHITE
                c.drawLine(s.x - arm, s.y, s.x - gap, s.y, rim)
                c.drawLine(s.x + gap, s.y, s.x + arm, s.y, rim)
                c.drawLine(s.x, s.y - arm, s.x, s.y - gap, rim)
                c.drawLine(s.x, s.y + gap, s.x, s.y + arm, rim)
            }
        }
    }
}
