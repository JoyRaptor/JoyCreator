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

/** D2 / K18: Select settings and line actions, with selected words cut out of the locked cyan gradient. */
@SuppressLint("ViewConstructor")
class SelectSettingsView(
    private val kit: ChromeKit,
    fingerSelects: Boolean,
    paintMakesLines: Boolean,
    private val onFingerSelects: (Boolean) -> Unit,
    private val onPaintMakesLines: (Boolean) -> Unit,
    pickLocked: Boolean = false,
    dimRest: Boolean = true,
    canPasteLines: Boolean = false,
    private val onSelectAllLines: () -> Unit = {},
    private val onPasteLines: () -> Unit = {},
    private val onPickLocked: (Boolean) -> Unit = {},
    private val onDimRest: (Boolean) -> Unit = {},
    showLockedOption: Boolean = false,
    perBrushLinesOverrideShelf: Boolean = false,
) : LinearLayout(kit.context) {
    init {
        orientation = VERTICAL
        heading("One finger, after using the pen")
        choices("Pans", "Selects", fingerSelects, onFingerSelects)
        heading("Paint and Airbrush make")
        choices("Pixels", "Editable lines", paintMakesLines, onPaintMakesLines)
        if (perBrushLinesOverrideShelf) addView(TextView(context).apply {
            text = "Each brush’s own Editable lines choice takes priority."
            textSize = 11f
            setTextColor(kit.p.drawerDim)
            setPadding(kit.dpi(6f), kit.dpi(8f), kit.dpi(6f), kit.dpi(4f))
        })
        action("Select all lines", "Select every editable line on this layer", onSelectAllLines)
        if (canPasteLines) action("Paste", "Paste the copied editable lines onto this layer", onPasteLines)
        if (showLockedOption || pickLocked) toggle("Pick locked lines too", pickLocked, onPickLocked)
        toggle("Dim the rest while selecting", dimRest, onDimRest)
    }

    private fun action(words: String, label: String, run: () -> Unit) {
        addView(TextView(context).apply {
            text = words
            textSize = 13f
            setTextColor(kit.p.drawerInk)
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(kit.dpi(8f), 0, kit.dpi(8f), 0)
            minHeight = kit.dpi(40f)
            kit.label(this, label)
            setOnClickListener { run() }
        })
    }

    private fun toggle(words: String, on: Boolean, changed: (Boolean) -> Unit) {
        heading(words)
        choices("Off", "On", on, changed, words)
    }

    private fun heading(words: String) {
        addView(TextView(context).apply {
            text = words
            textSize = 12f
            setTextColor(kit.p.drawerInk)
            setPadding(kit.dpi(6f), kit.dpi(10f), kit.dpi(6f), kit.dpi(6f))
        })
    }

    private fun choices(first: String, second: String, selectedSecond: Boolean, changed: (Boolean) -> Unit, label: String? = null) {
        val group = LinearLayout(context).apply { orientation = HORIZONTAL }
        val left = Choice(first, !selectedSecond)
        val right = Choice(second, selectedSecond)
        if (label != null) { kit.label(left, "$label: off"); kit.label(right, "$label: on") }
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
                paint.alpha = 255
                paint.shader = SelectAccent.shader(kit, width.toFloat(), height.toFloat())
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
