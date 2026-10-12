package cc.joycreator.joybrush.android.select

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import cc.joycreator.joybrush.android.chrome.ChromeKit

/** K18: tap Select again for the two choices; selected words are cut out of the room gradient (owner October12). */
@SuppressLint("ViewConstructor")
class SelectSettingsView(
    private val kit: ChromeKit,
    fingerSelects: Boolean,
    paintMakesLines: Boolean,
    private val onFingerSelects: (Boolean) -> Unit,
    private val onPaintMakesLines: (Boolean) -> Unit,
    private val accent: SelectAccent = SelectAccent.CYAN_GRADIENT,
) : LinearLayout(kit.context) {
    init {
        orientation = VERTICAL
        heading("One finger, after using the pen")
        choices("Pans", "Selects", fingerSelects, onFingerSelects)
        heading("Paint and Airbrush make")
        choices("Pixels", "Editable lines", paintMakesLines, onPaintMakesLines)
        addView(TextView(context).apply {
            text = "Each brush’s own Editable lines choice takes priority."
            textSize = 11f
            setTextColor(kit.p.drawerDim)
            setPadding(kit.dpi(6f), kit.dpi(8f), kit.dpi(6f), kit.dpi(4f))
        })
    }

    private fun heading(words: String) {
        addView(TextView(context).apply {
            text = words
            textSize = 12f
            setTextColor(kit.p.drawerInk)
            setPadding(kit.dpi(6f), kit.dpi(10f), kit.dpi(6f), kit.dpi(6f))
        })
    }

    private fun choices(first: String, second: String, selectedSecond: Boolean, changed: (Boolean) -> Unit) {
        val group = LinearLayout(context).apply { orientation = HORIZONTAL }
        val left = Choice(first, !selectedSecond)
        val right = Choice(second, selectedSecond)
        left.setOnClickListener { left.chosen = true; right.chosen = false; changed(false) }
        right.setOnClickListener { left.chosen = false; right.chosen = true; changed(true) }
        group.addView(left, LayoutParams(0, kit.dpi(40f), 1f))
        group.addView(right, LayoutParams(0, kit.dpi(40f), 1f).apply { marginStart = kit.dpi(4f) })
        addView(group)
    }

    private inner class Choice(private val word: String, on: Boolean) : View(context) {
        var chosen: Boolean = on
            set(value) { field = value; isSelected = value; invalidate() }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val cut = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }

        init {
            isSelected = on
            kit.label(this, word)
        }

        override fun onDraw(canvas: Canvas) {
            paint.style = Paint.Style.FILL
            paint.shader = null
            if (chosen) {
                val layer = canvas.saveLayer(null, null)
                paint.shader = accent.shader(kit, width.toFloat(), height.toFloat())
                canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), kit.dp(20f), kit.dp(20f), paint)
                paint.shader = null
                canvas.saveLayer(null, cut)
                drawWord(canvas, android.graphics.Color.BLACK)
                canvas.restoreToCount(layer)
            } else {
                paint.color = kit.ink(0.1f)
                canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), kit.dp(20f), kit.dp(20f), paint)
                drawWord(canvas, kit.p.drawerInk)
            }
        }

        private fun drawWord(canvas: Canvas, colour: Int) {
            paint.color = colour
            paint.textSize = kit.dp(12f)
            paint.textAlign = Paint.Align.CENTER
            val fm = paint.fontMetrics
            canvas.drawText(word, width / 2f, height / 2f - (fm.ascent + fm.descent) / 2f, paint)
        }
    }
}
