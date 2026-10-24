package cc.joycreator.joybrush.android.select

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import androidx.core.graphics.ColorUtils
import cc.joycreator.joybrush.android.chrome.ChromeKit
import cc.joycreator.joybrush.android.chrome.JbIcon
import cc.joycreator.joybrush.core.chrome.IconInk

/**
 * JB-5.20e — the bar under the selection (spec D9, D13, §K K3, K13, K14): what a loop took and the chips that re-read it,
 * the weight rule, the ＋ chip, and while nudging the ring's size. It wears the paper like the row (K3). The chips are
 * never greyed: tapping a line filter on paint switches to lines (K13); the controller does the re-read.
 */
@SuppressLint("ViewConstructor")
class SelectBarView(private val kit: ChromeKit, private val chips: Chips) : LinearLayout(kit.context) {

    interface Chips {
        fun take(lines: Boolean)
        fun whole(whole: Boolean)
        fun allLayers(all: Boolean)
        fun keepWeights(keep: Boolean)
        fun add(on: Boolean)
        fun ringSize(dp: Float)
        fun backToBox()
    }

    /** What the bar shows. */
    class Model(
        val lines: Boolean,
        val count: Int,
        /** A loop made it: the three re-read chips show. */
        val fromLoop: Boolean,
        val whole: Boolean,
        val allLayers: Boolean,
        val keepWeights: Boolean,
        val addArmed: Boolean,
        /** P6 A: the ＋ chip is offered. */
        val offerAdd: Boolean,
        val nudging: Boolean,
        val ringDp: Float,
    )

    var ink: IconInk = IconInk.LIGHT
        set(value) { field = value; repaint() }

    private val back = GradientDrawable()
    private val segBacks = ArrayList<GradientDrawable>()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(kit.dpi(4f), kit.dpi(4f), kit.dpi(4f), kit.dpi(4f))
        back.cornerRadius = kit.dp(20f)
        background = back
    }

    fun setPaper(argb: Int) {
        back.setColor(ColorUtils.setAlphaComponent(argb, 0xE0))
        repaint()
    }

    fun show(m: Model) {
        removeAllViews()
        segBacks.clear()
        if (m.nudging) {
            text("Nudge")
            val seek = SeekBar(context).apply {
                max = 144
                progress = (m.ringDp - 16f).toInt().coerceIn(0, 144)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) chips.ringSize(16f + p) }
                    override fun onStartTrackingTouch(s: SeekBar) {}
                    override fun onStopTrackingTouch(s: SeekBar) {}
                })
            }
            kit.label(seek, "How far a nudge reaches. It stays the same size on screen at any zoom")
            addView(seek, LayoutParams(kit.dpi(140f), kit.dpi(32f)))
            chip(JbIcon.BOX, null, false, "Back to moving and resizing the selection") { chips.backToBox() }
            repaint()
            return
        }
        if (m.fromLoop) {
            seg(
                Chip(null, if (m.count > 0) "Lines ${m.count}" else "Lines", m.lines, "Take the lines in this loop") { chips.take(true) },
                Chip(null, "Paint", !m.lines, "Take the paint inside the loop instead") { chips.take(false) },
            )
            seg(
                Chip(JbIcon.WHOLE, null, m.lines && m.whole, "Only lines inside the loop (switches to lines)") { chips.whole(true) },
                Chip(JbIcon.TOUCH, null, m.lines && !m.whole, "Every line the loop touches (switches to lines)") { chips.whole(false) },
            )
            seg(
                Chip(JbIcon.LAYER_ONE, null, m.lines && !m.allLayers, "Lines on this layer") { chips.allLayers(false) },
                Chip(JbIcon.LAYERS, null, m.lines && m.allLayers, "Lines on every layer (switches to lines)") { chips.allLayers(true) },
            )
        } else {
            text(if (m.lines) (if (m.count == 1) "1 line" else "${m.count} lines") else "Paint")
        }
        if (m.lines) {
            chip(if (m.keepWeights) JbIcon.KEEP_WEIGHTS else JbIcon.SCALE_WEIGHTS, null, m.keepWeights,
                "Keep line thickness when you resize, or let it grow and shrink with the box") { chips.keepWeights(!m.keepWeights) }
            if (m.offerAdd) chip(JbIcon.PLUS, null, m.addArmed, "Add: taps, loops, the crosshair, fan and count add lines, or remove ones already selected") { chips.add(!m.addArmed) }
        }
        repaint()
    }

    private fun inkColour(): Int = if (ink == IconInk.DARK) 0xFF121214.toInt() else 0xFFF7F7F9.toInt()

    private fun repaint() {
        val c = inkColour()
        // No perimeter: on blank paper the backing disappears and only the controls read.
        for (g in segBacks) g.setStroke(kit.dpi(1f), ColorUtils.setAlphaComponent(c, 0x29))
        for (i in 0 until childCount) getChildAt(i).invalidate()
        for (i in 0 until childCount) (getChildAt(i) as? LinearLayout)?.let { g -> for (j in 0 until g.childCount) g.getChildAt(j).invalidate() }
    }

    private class Chip(val icon: JbIcon?, val text: String?, val on: Boolean, val label: String, val onTap: () -> Unit)

    private fun text(t: String) {
        addView(Label(t), LayoutParams(LayoutParams.WRAP_CONTENT, kit.dpi(32f)))
    }

    private fun chip(icon: JbIcon?, text: String?, on: Boolean, label: String, onTap: () -> Unit) {
        val v = ChipView(Chip(icon, text, on, label, onTap), alone = true)
        addView(v, LayoutParams(if (text == null) kit.dpi(34f) else LayoutParams.WRAP_CONTENT, kit.dpi(32f)).apply { marginStart = kit.dpi(5f) })
    }

    private fun seg(vararg cs: Chip) {
        val g = LinearLayout(context).apply { orientation = HORIZONTAL }
        val outline = GradientDrawable().apply { cornerRadius = kit.dp(16f) }
        outline.setStroke(kit.dpi(1f), ColorUtils.setAlphaComponent(inkColour(), 0x29))
        segBacks.add(outline)
        g.background = outline
        for (c in cs) g.addView(ChipView(c, alone = false), LayoutParams(if (c.text == null) kit.dpi(34f) else LayoutParams.WRAP_CONTENT, kit.dpi(32f)))
        addView(g, LayoutParams(LayoutParams.WRAP_CONTENT, kit.dpi(32f)).apply { marginStart = kit.dpi(5f) })
    }

    private inner class Label(val t: String) : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = kit.dp(11f); typeface = android.graphics.Typeface.MONOSPACE }
        override fun onMeasure(w: Int, h: Int) = setMeasuredDimension((p.measureText(t) + kit.dp(14f)).toInt(), kit.dpi(32f))
        override fun onDraw(c: Canvas) { p.color = ColorUtils.setAlphaComponent(inkColour(), 0xCC); c.drawText(t, kit.dp(7f), height / 2f + kit.dp(4f), p) }
    }

    /** One chip: ON is the chosen selection accent with the word or icon cut out; OFF is dimmer. */
    private inner class ChipView(val c: Chip, val alone: Boolean) : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = kit.dp(11.5f) }

        init {
            kit.label(this, c.label)
            setOnClickListener { c.onTap() }
        }

        override fun onMeasure(w: Int, h: Int) {
            val width = if (c.text != null) (p.measureText(c.text) + kit.dp(20f)).toInt() else kit.dpi(34f)
            setMeasuredDimension(width, kit.dpi(32f))
        }

        override fun onDraw(cv: Canvas) {
            val ink = inkColour()
            if (c.on) {
                // K20's cut-out icon or word, in the owner's locked cyan gradient.
                p.style = Paint.Style.FILL
                p.alpha = 255
                p.shader = SelectAccent.shader(kit, width.toFloat(), height.toFloat())
                val base = cv.saveLayer(null, null)
                cv.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), kit.dp(16f), kit.dp(16f), p)
                p.shader = null
                cv.saveLayer(null, cut)
                content(cv, 0xFF000000.toInt())
                cv.restoreToCount(base)
                // On light or mid paper (kraft) the paper seen through the cut is pale against the cyan and the word
                // fades (Select bench, 10-24): fill the cut dark, so the chosen chip reads at a glance on every paper.
                if (this@SelectBarView.ink == IconInk.DARK) content(cv, 0xFF121214.toInt())
                return
            }
            if (alone) {
                p.style = Paint.Style.STROKE
                p.strokeWidth = kit.dp(1f)
                p.color = ColorUtils.setAlphaComponent(ink, 0x29)
                val inset = p.strokeWidth / 2
                cv.drawRoundRect(inset, inset, width - inset, height - inset, kit.dp(16f), kit.dp(16f), p)
                p.style = Paint.Style.FILL
            }
            content(cv, ColorUtils.setAlphaComponent(ink, 0xBF))
        }

        private val cut = Paint().apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT) }

        private fun content(cv: Canvas, colour: Int) {
            val a = android.graphics.Color.alpha(colour)
            val ink = colour or (0xFF shl 24)
            if (c.icon != null) {
                val d = c.icon.drawable(ColorUtils.setAlphaComponent(ink, a))
                val s = kit.dp(20f)
                d.setBounds(((width - s) / 2).toInt(), ((height - s) / 2).toInt(), ((width + s) / 2).toInt(), ((height + s) / 2).toInt())
                d.draw(cv)
            } else if (c.text != null) {
                p.color = ColorUtils.setAlphaComponent(ink, a)
                cv.drawText(c.text, kit.dp(10f), height / 2f + kit.dp(4f), p)
            }
        }
    }
}
