package cc.joycreator.joybrush.android.chrome

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils

/**
 * The colour you had and the colour you are making, side by side under the wheel (JB-2.01, the mockup's colour popover):
 * the painter's convention for "before and after". Tapping the old half puts the old colour back.
 */
@SuppressLint("ViewConstructor")
class ColourPairView(private val kit: ChromeKit, private val old: Int, private val onRestore: () -> Unit) : View(kit.context) {

    var now: Int = old
        set(value) { field = value; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    init {
        isClickable = true
        kit.label(this, "Before and after — tap the left half to go back to the colour you had")
    }

    override fun onDraw(c: Canvas) {
        val h = height.toFloat()
        val w = width.toFloat()
        val rad = h / 2f
        c.save()
        r.set(0f, 0f, w, h)
        c.clipPath(android.graphics.Path().apply { addRoundRect(r, rad, rad, android.graphics.Path.Direction.CW) })
        paint.style = Paint.Style.FILL
        paint.color = ColorUtils.setAlphaComponent(old, 255)
        c.drawRect(0f, 0f, w / 2f, h, paint)
        paint.color = ColorUtils.setAlphaComponent(now, 255)
        c.drawRect(w / 2f, 0f, w, h, paint)
        c.restore()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = kit.dp(1f)
        paint.color = kit.ink(0.5f)
        c.drawRoundRect(r, rad, rad, paint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            performClick()
            if (e.x < width / 2f) onRestore()
        }
        return true
    }
}
