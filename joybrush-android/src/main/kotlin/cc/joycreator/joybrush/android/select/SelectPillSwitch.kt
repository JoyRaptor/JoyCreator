package cc.joycreator.joybrush.android.select

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.widget.Switch
import cc.joycreator.joybrush.android.chrome.ChromeKit

/** Native accessible Switch with the Select choice pill styling used by SelectSettingsView. */
@Suppress("DEPRECATION")
class SelectPillSwitch(
    private val kit: ChromeKit,
) : Switch(kit.context) {
    init {
        gravity = Gravity.CENTER_VERTICAL
        showText = false
        textOn = "On"
        textOff = "Off"
        switchMinWidth = kit.dpi(TRACK_WIDTH_DP)
        splitTrack = false
        thumbDrawable = null
        trackDrawable = SelectPillTrackDrawable(kit)
        // Clear platform/theme tints so the stateful shader and explicit off colour render unchanged.
        thumbTintList = null
        trackTintList = null
    }

    companion object {
        const val TRACK_WIDTH_DP = 56f
        const val TRACK_HEIGHT_DP = 32f
    }
}

/** Track content mirrors the selected/unselected Choice in SelectSettingsView. */
private class SelectPillTrackDrawable(private val kit: ChromeKit) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cutoutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }
    private val rect = RectF()
    private var checked = false
    private var drawableAlpha = 255

    override fun isStateful(): Boolean = true

    override fun onStateChange(state: IntArray): Boolean {
        val next = state.contains(android.R.attr.state_checked)
        if (next == checked) return false
        checked = next
        invalidateSelf()
        return true
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val outer = canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        rect.set(0f, 0f, b.width().toFloat(), b.height().toFloat())
        val alphaLayer = canvas.saveLayerAlpha(rect, drawableAlpha)
        val radius = rect.height() / 2f
        if (checked) {
            val layer = canvas.saveLayer(rect, null)
            paint.reset()
            paint.isAntiAlias = true
            paint.alpha = 255
            paint.style = Paint.Style.FILL
            paint.shader = SelectAccent.shader(kit, rect.width(), rect.height())
            canvas.drawRoundRect(rect, radius, radius, paint)
            paint.shader = null
            drawWord(canvas, "On", cutoutPaint)
            canvas.restoreToCount(layer)
        } else {
            paint.reset()
            paint.isAntiAlias = true
            paint.alpha = 255
            paint.style = Paint.Style.FILL
            paint.color = kit.ink(0.1f)
            canvas.drawRoundRect(rect, radius, radius, paint)
            paint.shader = null
            drawWord(canvas, "Off", paint.apply { color = kit.p.drawerInk })
        }
        canvas.restoreToCount(alphaLayer)
        canvas.restoreToCount(outer)
    }

    private fun drawWord(canvas: Canvas, word: String, target: Paint) {
        target.textSize = kit.dp(12f)
        target.textAlign = Paint.Align.CENTER
        target.style = Paint.Style.FILL
        target.shader = null
        target.colorFilter = null
        val fm = target.fontMetrics
        canvas.drawText(word, rect.centerX(), rect.centerY() - (fm.ascent + fm.descent) / 2f, target)
    }

    override fun getIntrinsicWidth(): Int = kit.dpi(SelectPillSwitch.TRACK_WIDTH_DP)
    override fun getIntrinsicHeight(): Int = kit.dpi(SelectPillSwitch.TRACK_HEIGHT_DP)
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT

    override fun setAlpha(alpha: Int) {
        val next = alpha.coerceIn(0, 255)
        if (drawableAlpha == next) return
        drawableAlpha = next
        invalidateSelf()
    }

    override fun getAlpha(): Int = drawableAlpha

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        // Track colors are palette-driven; callers must not replace the selected shader with a tint.
        if (colorFilter != null) invalidateSelf()
    }
}
