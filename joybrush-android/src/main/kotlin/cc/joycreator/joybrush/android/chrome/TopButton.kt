package cc.joycreator.joybrush.android.chrome

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/**
 * One of the top bar's round buttons (JB-2.01): a 40 dp target with a 32 dp chrome chip and a line icon. [on] rings
 * it in cyan (the pinned reference is showing, say). A disabled button's icon dims; the chip stays, so the bar never jumps.
 */
@SuppressLint("ViewConstructor")
class TopButton(private val kit: ChromeKit, icon: JbIcon, label: String) : View(kit.context) {

    var on = false
        set(value) { field = value; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val drawable = icon.drawable(kit.p.drawerInk)

    init {
        isClickable = true
        isFocusable = true
        kit.label(this, label)
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        // Only the icon dims: a faded chip over white paper would read as a grey smudge, not as "nothing to undo".
        drawable.alpha = if (enabled) 255 else 90
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = kit.dp(16f)
        paint.style = Paint.Style.FILL
        paint.color = kit.chrome()
        c.drawCircle(cx, cy, r, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = Math.max(1f, kit.dp(0.75f))
        paint.color = kit.p.line
        c.drawCircle(cx, cy, r, paint)
        if (on || isPressed) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = kit.dp(1.5f)
            paint.color = if (on) kit.p.stateSelected else kit.ink(0.3f)
            c.drawCircle(cx, cy, r, paint)
        }
        val h = kit.dpi(9f)
        drawable.setBounds(Math.round(cx) - h, Math.round(cy) - h, Math.round(cx) + h, Math.round(cy) + h)
        drawable.draw(c)
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }
}
