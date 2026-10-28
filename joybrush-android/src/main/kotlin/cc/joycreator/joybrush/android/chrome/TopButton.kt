package cc.joycreator.joybrush.android.chrome

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import androidx.core.graphics.ColorUtils
import cc.joycreator.joybrush.core.chrome.IconFit
import cc.joycreator.joybrush.core.chrome.IconInk

/**
 * One of the top bar's buttons (JB-2.01): a 40 dp target holding a SOLID icon straight over the picture, with no chip
 * behind it. Its [ink] follows the picture behind it (owner, 2026-09-30; the rule is [cc.joycreator.joybrush.core.chrome.IconContrast]):
 * black over a picture lighter than middle grey, white with a drop shadow over a darker one.
 *
 * [on] rings it in cyan (the pinned reference is showing, say). A disabled button's icon fades; the button stays put, so
 * the bar never jumps.
 */
@SuppressLint("ViewConstructor")
class TopButton(private val kit: ChromeKit, icon: JbIcon, label: String) : View(kit.context) {

    var on = false
        set(value) { field = value; invalidate() }

    var ink: IconInk = IconInk.LIGHT
        set(value) { if (field != value) { field = value; inkChanged() } }

    /** Joybot's own two faces (the app's art): the regular one, and the smiling one while pressed. Null = the vector icon. */
    var faces: Pair<android.graphics.drawable.Drawable, android.graphics.drawable.Drawable>? = null
        set(value) { field = value; inkChanged() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val drawable = icon.drawable(kit.p.drawerInk)

    init {
        isClickable = true
        isFocusable = true
        kit.label(this, label)
        inkChanged()
    }

    private fun inkChanged() {
        if (ink == IconInk.DARK) {
            drawable.color = kit.p.ground
            drawable.shadow(0f, 0f, 0)
        } else {
            drawable.color = kit.p.drawerInk
            drawable.shadow(kit.dp(3f), kit.dp(1f), ColorUtils.setAlphaComponent(kit.p.ground, SHADOW_ALPHA))
        }
        faces?.let { (a, b) ->
            // The art is white line art on transparency: it wears the ink the picture behind asks for.
            val tint = android.graphics.PorterDuffColorFilter(if (ink == IconInk.DARK) kit.p.ground else kit.p.drawerInk, android.graphics.PorterDuff.Mode.SRC_IN)
            a.colorFilter = tint; b.colorFilter = tint
        }
        applyEnabled()
        invalidate()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        applyEnabled()
        invalidate()
    }

    private fun applyEnabled() {
        drawable.alpha = if (isEnabled) 255 else DISABLED_ALPHA
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        if (on) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = kit.dp(1.5f)
            paint.color = kit.p.stateSelected
            c.drawCircle(cx, cy, kit.dp(17f), paint)
        }
        // Pressed: the icon dips a little, the way a key goes down. No wash: there is no chip to wash.
        faces?.let { (neutral, smile) ->
            val f = kit.dp(13f).let { if (isPressed) it - kit.dp(1.5f) else it }
            val d = if (isPressed) smile else neutral
            d.alpha = if (isEnabled) 255 else DISABLED_ALPHA
            // Joybot's art is tall (360 x 564): fitted INSIDE the square in its own shape, never squeezed into it.
            val box = IconFit.inside(Math.round(cx - f), Math.round(cy - f), Math.round(cx + f), Math.round(cy + f), d.intrinsicWidth, d.intrinsicHeight)
            d.setBounds(box.left, box.top, box.right, box.bottom)
            d.draw(c)
            return
        }
        val h = if (isPressed) kit.dp(9.5f) else kit.dp(11f)
        drawable.setBounds(Math.round(cx - h), Math.round(cy - h), Math.round(cx + h), Math.round(cy + h))
        drawable.draw(c)
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    private companion object {
        /** 55%: dark enough to hold a white icon on white paper, soft enough not to look like a sticker. */
        const val SHADOW_ALPHA = 140
        const val DISABLED_ALPHA = 90
    }
}
