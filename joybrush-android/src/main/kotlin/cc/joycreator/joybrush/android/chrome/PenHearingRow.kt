package cc.joycreator.joybrush.android.chrome

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * How a brush hears the pen, as one row shared by every brush editor (owner's mockup, BRUSH-UI §L, 11-27): the pen
 * monitor, the 0° / 35° / 70° footprints stacked beside it, then the Pressure and Tilt curves. The monitor keeps the
 * paint editor's size; a small grey button in its corner makes it large over the Pressure and Tilt area, and back
 * (§L owner addition 2). For Unibrush the monitor also holds the A and B shapes (later step).
 */
@SuppressLint("ViewConstructor")
class PenHearingRow(
    private val kit: ChromeKit,
    val monitor: View,
    val footprint: FootprintView,
    val pressure: CurveEditorView,
    val tilt: CurveEditorView,
) : LinearLayout(kit.context) {

    /** The monitor is shown large, over the curves. */
    var expanded = false
        private set

    private val monitorBox = FrameLayout(context)
    private val expand = ExpandButton()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.TOP
        footprint.vertical = true
        monitorBox.addView(monitor, FrameLayout.LayoutParams(MATCH, WRAP))
        monitorBox.addView(expand, FrameLayout.LayoutParams(kit.dpi(28f), kit.dpi(28f), Gravity.TOP or Gravity.END))
        expand.setOnClickListener { setExpanded(!expanded) }
        addView(monitorBox, LayoutParams(0, WRAP, 1f))
        addView(footprint, LayoutParams(kit.dpi(FOOTPRINT_DP), MATCH).apply { marginStart = kit.dpi(4f) })
        addView(pressure, LayoutParams(0, WRAP, 1f).apply { marginStart = kit.dpi(6f) })
        addView(tilt, LayoutParams(0, WRAP, 1f).apply { marginStart = kit.dpi(6f) })
        label()
    }

    /** Large over the Pressure and Tilt area ([on]), or back beside them. */
    fun setExpanded(on: Boolean) {
        if (on == expanded) return
        expanded = on
        pressure.visibility = if (on) GONE else VISIBLE
        tilt.visibility = if (on) GONE else VISIBLE
        (monitorBox.layoutParams as LayoutParams).weight = if (on) 3f else 1f
        monitorBox.requestLayout()
        expand.invalidate()
        label()
    }

    private fun label() = kit.label(expand, if (expanded) "Make the pen monitor small again, beside the curves"
        else "Make the pen monitor large, over the Pressure and Tilt curves")

    /** Two small corner brackets in the chrome's quiet grey: out to grow, in to shrink. */
    private inner class ExpandButton : View(context) {
        private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = kit.dp(1.4f); strokeCap = Paint.Cap.ROUND; color = kit.ink(0.45f)
        }

        init { isClickable = true; isFocusable = true }

        override fun onDraw(c: Canvas) {
            val s = minOf(width, height).toFloat()
            val a = s * 0.3f; val b = s * 0.7f; val k = s * 0.16f
            if (!expanded) {
                // Out: arrows to the top-right and bottom-left corners.
                c.drawLine(b - k, a, b, a, ink); c.drawLine(b, a, b, a + k, ink); c.drawLine(b, a, s * .55f, s * .45f, ink)
                c.drawLine(a, b - k, a, b, ink); c.drawLine(a, b, a + k, b, ink); c.drawLine(a, b, s * .45f, s * .55f, ink)
            } else {
                // In: arrows pointing back to the middle.
                c.drawLine(s * .55f, s * .45f, s * .55f + k, s * .45f, ink); c.drawLine(s * .55f, s * .45f, s * .55f, s * .45f - k, ink)
                c.drawLine(s * .55f, s * .45f, b, a, ink)
                c.drawLine(s * .45f, s * .55f, s * .45f - k, s * .55f, ink); c.drawLine(s * .45f, s * .55f, s * .45f, s * .55f + k, ink)
                c.drawLine(s * .45f, s * .55f, a, b, ink)
            }
        }
    }

    private companion object {
        const val MATCH = LayoutParams.MATCH_PARENT
        const val WRAP = LayoutParams.WRAP_CONTENT
        /** The footprint column, as narrow as the mockup draws it. */
        const val FOOTPRINT_DP = 40f
    }
}
