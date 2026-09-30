package cc.joycreator.joybrush.android.chrome

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import androidx.core.graphics.ColorUtils
import kotlin.math.roundToInt

/**
 * What a size or opacity drag is doing, shown in the middle of the screen while the finger is on the strip (JB-2.01):
 * the brush's real width at the current zoom as a ring, or the colour at its opacity, and the number. It takes no
 * touches and draws nothing when idle.
 */
@SuppressLint("ViewConstructor")
class ValueHud(private val kit: ChromeKit) : View(kit.context) {

    private var mode = 0 // 0 idle, 1 size, 2 opacity
    private var sizeScreenPx = 0f
    private var sizeDocPx = 0f
    private var argb = 0
    private var opacity = 1f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
        textSize = kit.dp(13f)
    }
    private val pill = RectF()

    init {
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun showSize(docPx: Float, zoom: Float) { mode = 1; sizeDocPx = docPx; sizeScreenPx = docPx * zoom; invalidate() }
    fun showOpacity(colour: Int, value: Float) { mode = 2; argb = colour; opacity = value; invalidate() }
    fun hide() { mode = 0; invalidate() }

    override fun onDraw(c: Canvas) {
        if (mode == 0) return
        val cx = width / 2f
        val cy = height / 2f
        val label: String
        var below = kit.dp(40f)
        if (mode == 1) {
            val r = (sizeScreenPx / 2f).coerceAtLeast(kit.dp(1f))
            // Dark then light, so the ring reads on any paint.
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = kit.dp(3f)
            paint.color = ColorUtils.setAlphaComponent(kit.p.ground, 128)
            c.drawCircle(cx, cy, r, paint)
            paint.strokeWidth = kit.dp(1.5f)
            paint.color = kit.p.drawerInk
            c.drawCircle(cx, cy, r, paint)
            below = r + kit.dp(22f)
            label = if (sizeDocPx >= 10f) "${sizeDocPx.roundToInt()} px" else "%.1f px".format(sizeDocPx)
        } else {
            val r = kit.dp(34f)
            paint.style = Paint.Style.FILL
            val cell = r / 3f
            c.save()
            c.clipPath(android.graphics.Path().apply { addCircle(cx, cy, r, android.graphics.Path.Direction.CW) })
            var i = 0
            var x = cx - r
            while (x < cx + r) {
                var j = 0
                var y = cy - r
                while (y < cy + r) {
                    paint.color = if ((i + j) % 2 == 0) kit.p.drawerInk else kit.p.inkFaint
                    c.drawRect(x, y, x + cell, y + cell, paint)
                    y += cell; j++
                }
                x += cell; i++
            }
            paint.color = ColorUtils.setAlphaComponent(argb, (opacity * 255).roundToInt().coerceIn(0, 255))
            c.drawCircle(cx, cy, r, paint)
            c.restore()
            label = "${(opacity * 100).roundToInt()}%"
        }
        // The number, on a small see-through pill.
        val tw = text.measureText(label)
        val y = (cy + below).coerceAtMost(height - kit.dp(24f))
        pill.set(cx - tw / 2f - kit.dp(10f), y - kit.dp(16f), cx + tw / 2f + kit.dp(10f), y + kit.dp(8f))
        paint.style = Paint.Style.FILL
        paint.color = ColorUtils.setAlphaComponent(kit.p.ground, 153)
        c.drawRoundRect(pill, pill.height() / 2f, pill.height() / 2f, paint)
        text.color = kit.p.drawerInk
        c.drawText(label, cx, y, text)
    }
}
