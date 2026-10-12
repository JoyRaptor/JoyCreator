package cc.joycreator.joybrush.android.select

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import cc.joycreator.joybrush.android.chrome.ChromeKit
import cc.joycreator.joybrush.android.chrome.JbIcon
import cc.joycreator.joybrush.core.chrome.IconInk
import kotlin.math.abs
import kotlin.math.pow

/**
 * JB-5.20e — the selection's row of actions (spec D16, §K K1–K3, K9, K10). It sits ABOVE the selection (the controller
 * places it), wears the PAPER's colour at 88% with white or black icons by the brightness behind it, and fades out
 * while the box moves and back in after (slow, never a pop).
 *
 * Lines: Colour · Brush · Weight │ Nudge · Duplicate · Delete │ Flip horizontal · Flip vertical │ More.
 * Paint: Fill · Duplicate · Delete │ Flip horizontal · Flip vertical │ More.
 * With the owner's P3 C the colour and brush chips are left out (the strip's own buttons restyle).
 */
@SuppressLint("ViewConstructor")
class SelectRowView(private val kit: ChromeKit, private val actions: Actions) : LinearLayout(kit.context) {

    interface Actions {
        fun colour(anchor: View)
        fun brush(anchor: View)
        /** The weight chip dragged: [factor] from where the drag began; [done] on lift (ONE undo step). */
        fun weight(factor: Float, done: Boolean)
        fun weightSlider(anchor: View)
        fun nudge()
        fun duplicate()
        fun delete()
        fun flip(horizontal: Boolean)
        fun more(anchor: View)
        fun fill()
    }

    /** White or black, by the brightness behind the row (IconContrast). */
    var ink: IconInk = IconInk.LIGHT
        set(value) { field = value; recolour() }

    private val back = GradientDrawable()
    private var paperArgb = 0xFF070709.toInt()
    private val items = ArrayList<Item>()
    private var weightLabel = "1.0×"
    private val ease = PathInterpolator(0.23f, 1f, 0.32f, 1f)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val pad = kit.dpi(3f)
        setPadding(pad, kit.dpi(2f), pad, kit.dpi(2f))
        back.cornerRadius = kit.dp(14f)
        background = back
        elevation = 0f
    }

    /** The paper the row stands on (its backing is the paper at 88%). */
    fun setPaper(argb: Int) {
        paperArgb = argb
        back.setColor(ColorUtils.setAlphaComponent(argb, 0xE0))
        recolour()
    }

    /** A line selection's row. [colours] are the selected lines' colours (two shows a split dot). */
    fun showLines(colours: List<Int>, brushIcon: JbIcon, weight: Float, chips: Boolean) {
        removeAllViews(); items.clear()
        if (chips) {
            add(Item(Kind.COLOUR).also { it.colours = colours }, "Recolour the selected lines") { actions.colour(it) }
            add(Item(Kind.ICON, brushIcon), "Swap the selected lines to another line brush") { actions.brush(it) }
        }
        val w = Item(Kind.WEIGHT, JbIcon.WEIGHT)
        weightLabel = fmt(weight)
        add(w, "Drag to make the selected lines thicker or thinner, or tap for a slider") { actions.weightSlider(it) }
        w.setOnTouchListener(WeightDrag())
        sep()
        add(Item(Kind.ICON, JbIcon.NUDGE), "Push or pull part of the selected lines") { actions.nudge() }
        add(Item(Kind.ICON, JbIcon.DUPLICATE), "Duplicate") { actions.duplicate() }
        add(Item(Kind.ICON, JbIcon.DELETE).also { it.danger = true }, "Delete") { actions.delete() }
        sep()
        add(Item(Kind.ICON, JbIcon.FLIP_H), "Flip horizontal") { actions.flip(true) }
        add(Item(Kind.ICON, JbIcon.FLIP_V), "Flip vertical") { actions.flip(false) }
        sep()
        add(Item(Kind.ICON, JbIcon.MORE), "More: copy, lock, adjust colour, or turn the lines into pixels") { actions.more(it) }
        recolour()
    }

    /** A paint selection's row. [fillArgb] colours the bucket's paint (K10). */
    fun showPixels(fillArgb: Int) {
        removeAllViews(); items.clear()
        add(Item(Kind.FILL).also { it.colours = listOf(fillArgb) }, "Fill the selection with the current colour") { actions.fill() }
        add(Item(Kind.ICON, JbIcon.DUPLICATE), "Duplicate") { actions.duplicate() }
        add(Item(Kind.ICON, JbIcon.DELETE).also { it.danger = true }, "Delete") { actions.delete() }
        sep()
        add(Item(Kind.ICON, JbIcon.FLIP_H), "Flip horizontal") { actions.flip(true) }
        add(Item(Kind.ICON, JbIcon.FLIP_V), "Flip vertical") { actions.flip(false) }
        sep()
        add(Item(Kind.ICON, JbIcon.MORE), "More: copy, invert, or adjust colour") { actions.more(it) }
        recolour()
    }

    /** The weight chip's number, live while it is dragged. */
    fun showWeight(factor: Float) {
        weightLabel = fmt(factor)
        items.firstOrNull { it.kind == Kind.WEIGHT }?.invalidate()
    }

    /** K1: out over 220 ms when the box starts moving. */
    fun fadeOut() {
        animate().cancel()
        animate().alpha(0f).setStartDelay(0).setDuration(220).setInterpolator(ease).withEndAction { isEnabled = false }.start()
    }

    /** K1: back over 300 ms, 120 ms after the lift. */
    fun fadeIn() {
        isEnabled = true
        animate().cancel()
        animate().alpha(1f).setStartDelay(120).setDuration(300).setInterpolator(ease).start()
    }

    private fun fmt(f: Float) = String.format(java.util.Locale.ROOT, "%.1f×", f)

    private fun add(item: Item, label: String, onTap: (View) -> Unit) {
        val w = if (item.kind == Kind.WEIGHT) kit.dpi(52f) else kit.dpi(ChromeKit.TOUCH_DP)
        addView(item, LayoutParams(w, kit.dpi(ChromeKit.TOUCH_DP)))
        kit.label(item, label)
        item.setOnClickListener { onTap(it) }
        items.add(item)
    }

    private fun sep() {
        val v = View(context)
        v.tag = SEP
        addView(v, LayoutParams(kit.dpi(1f), kit.dpi(22f)).apply { marginStart = kit.dpi(2f); marginEnd = kit.dpi(2f) })
    }

    private fun inkColour(): Int = if (ink == IconInk.DARK) 0xFF121214.toInt() else 0xFFF7F7F9.toInt()

    private fun recolour() {
        val c = inkColour()
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            if (v.tag == SEP) v.setBackgroundColor(ColorUtils.setAlphaComponent(c, 0x29))
        }
        back.setStroke(kit.dpi(1f), ColorUtils.setAlphaComponent(c, 0x29))
        items.forEach { it.invalidate() }
    }

    private enum class Kind { ICON, COLOUR, WEIGHT, FILL }

    /** One 40 dp button drawing a solid icon in the row's ink (or a colour dot, the weight, the coloured bucket). */
    private inner class Item(val kind: Kind, val icon: JbIcon? = null) : View(context) {
        var colours: List<Int> = emptyList()
        var danger = false
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val p = Path()

        override fun onDraw(c: Canvas) {
            val ink = inkColour()
            val cx = width / 2f
            val cy = height / 2f
            when (kind) {
                Kind.ICON -> drawIcon(c, icon!!, cx, cy, ink)
                Kind.WEIGHT -> {
                    drawIcon(c, icon!!, kit.dp(13f), cy, ink, size = kit.dp(16f))
                    paint.color = ink
                    paint.textSize = kit.dp(11.5f)
                    paint.typeface = android.graphics.Typeface.MONOSPACE
                    paint.textAlign = Paint.Align.LEFT
                    c.drawText(weightLabel, kit.dp(23f), cy + kit.dp(4f), paint)
                }
                Kind.COLOUR -> {
                    val r = kit.dp(10f)
                    paint.style = Paint.Style.FILL
                    if (colours.size > 1) {
                        paint.color = colours[0]; c.drawArc(cx - r, cy - r, cx + r, cy + r, 90f, 180f, true, paint)
                        paint.color = colours[1]; c.drawArc(cx - r, cy - r, cx + r, cy + r, 270f, 180f, true, paint)
                    } else { paint.color = colours.firstOrNull() ?: ink; c.drawCircle(cx, cy, r, paint) }
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = kit.dp(1f)
                    paint.color = ColorUtils.setAlphaComponent(ink, 0x73)
                    c.drawCircle(cx, cy, r, paint)
                    paint.style = Paint.Style.FILL
                }
                Kind.FILL -> drawBucket(c, cx, cy, ink, colours.firstOrNull() ?: ink)
            }
            if (danger) {
                paint.style = Paint.Style.FILL
                paint.color = 0xFFFA3D5D.toInt()
                c.drawCircle(width - kit.dp(10f), kit.dp(10f), kit.dp(2.5f), paint)
            }
        }

        private fun drawIcon(c: Canvas, icon: JbIcon, cx: Float, cy: Float, colour: Int, size: Float = kit.dp(20f)) {
            val d = icon.drawable(colour)
            d.setBounds((cx - size / 2).toInt(), (cy - size / 2).toInt(), (cx + size / 2).toInt(), (cy + size / 2).toInt())
            d.draw(c)
        }

        /** K10: a solid bucket whose paint, in the bucket and in the pour, is the colour it will fill with. */
        private fun drawBucket(c: Canvas, cx: Float, cy: Float, ink: Int, paintArgb: Int) {
            val s = kit.dp(20f) / 24f
            val ox = cx - 12f * s
            val oy = cy - 12f * s
            fun path(vararg xy: Float) { p.reset(); p.moveTo(ox + xy[0] * s, oy + xy[1] * s); var i = 2; while (i < xy.size) { p.lineTo(ox + xy[i] * s, oy + xy[i + 1] * s); i += 2 }; p.close() }
            paint.style = Paint.Style.FILL
            paint.color = ink
            path(6f, 10.5f, 12.5f, 4f, 19.5f, 11f, 13f, 17.5f) // the tilted bucket
            c.drawPath(p, paint)
            paint.color = paintArgb
            path(7.6f, 10.9f, 18f, 10.9f, 12.9f, 16f) // the paint in it
            c.drawPath(p, paint)
            c.drawCircle(ox + 19f * s, oy + 16.5f * s, 2f * s, paint) // the pour
            c.drawRect(ox + 18.2f * s, oy + 11f * s, ox + 19.8f * s, oy + 16f * s, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = kit.dp(0.8f)
            paint.color = ColorUtils.setAlphaComponent(ink, 0x8C)
            c.drawCircle(ox + 19f * s, oy + 16.5f * s, 2f * s, paint)
            paint.style = Paint.Style.FILL
        }
    }

    /** The weight chip: drag left or right to scrub (×0.25 to ×4, log scale, one step per 6 dp); a tap opens the slider. */
    private inner class WeightDrag : OnTouchListener {
        private var x0 = 0f
        private var dragging = false
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { x0 = e.rawX; dragging = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - x0
                    if (!dragging && abs(dx) > kit.dp(6f)) dragging = true
                    if (dragging) { val f = factor(dx); showWeight(f); actions.weight(f, done = false) }
                }
                MotionEvent.ACTION_UP -> if (dragging) { actions.weight(factor(e.rawX - x0), done = true); dragging = false; return true }
                MotionEvent.ACTION_CANCEL -> if (dragging) { actions.weight(1f, done = true); dragging = false }
            }
            return dragging || v.onTouchEvent(e)
        }

        private fun factor(dx: Float): Float = 2f.pow(dx / kit.dp(60f)).coerceIn(0.25f, 4f)
    }

    private companion object {
        const val SEP = "sep"
    }
}
