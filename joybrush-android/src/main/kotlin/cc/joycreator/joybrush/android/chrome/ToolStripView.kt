package cc.joycreator.joybrush.android.chrome

import android.annotation.SuppressLint
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import cc.joycreator.joybrush.core.chrome.StripPlacement
import cc.joycreator.joybrush.core.chrome.ToolSlot
import cc.joycreator.joybrush.core.tool.SizeOpacityDrag
import cc.joycreator.joybrush.android.select.SelectAccent
import com.fadcam.ui.faditor.tools.RecentColorsBar
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.cos
import kotlin.math.PI

/**
 * The tool strip (JB-2.01, owner decision 1): one slim column hugging a side edge with everything a painter touches a
 * hundred times — paint, smudge, erase, size, colour, opacity — and the recent-colour hair beside it. The grip at the
 * top drags the whole strip; let go and it snaps to the nearer edge ([StripPlacement.dropAt]).
 *
 * Size and opacity change by DRAGGING on them, up for more (blueprint §3.5, the JB-2.16a maths); a tap opens their
 * slider. The strip only reports; [Host] owns the values and the canvas.
 */
@SuppressLint("ViewConstructor")
class ToolStripView(private val kit: ChromeKit, private val host: Host) : LinearLayout(kit.context) {

    interface Host {
        fun toolTapped(slot: ToolSlot, anchor: View)
        fun sizeNow(): Float
        fun opacityNow(): Float
        fun zoom(): Float
        /** A size drag moved ([done] = false) or ended ([done] = true). Document px. */
        fun sizeDragged(px: Float, done: Boolean)
        fun opacityDragged(value: Float, done: Boolean)
        fun sizeTapped(anchor: View)
        fun opacityTapped(anchor: View)
        /** The strip was let go of with its centre at ([cx], [cy]) in its parent's coordinates. */
        fun stripDropped(cx: Float, cy: Float)
        /** JB-5.20e: the Select button was tapped (first tap: the Select tool; again: its menu). Shown only via [showSelect]. */
        fun selectTapped(anchor: View) {}
    }

    private val column = LinearLayout(context).apply {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(kit.dpi(2f), kit.dpi(2f), kit.dpi(2f), kit.dpi(6f))
    }
    val recentBar = RecentColorsBar(context).apply { setOrientation(true) }

    private val grip = Item(Kind.GRIP)
    private val tools = mapOf(
        ToolSlot.BRUSH to Item(Kind.TOOL, JbIcon.BRUSH),
        ToolSlot.SMUDGE to Item(Kind.TOOL, JbIcon.SMUDGE),
        ToolSlot.ERASER to Item(Kind.TOOL, JbIcon.ERASER),
    )
    /** JB-5.20e D1.1. Not a [ToolSlot]: a slot remembers a brush, and Select holds none. Hidden until the tool exists. */
    private val select = Item(Kind.TOOL, JbIcon.SELECT).apply { visibility = GONE }
    private val size = Item(Kind.SIZE)
    private val swatchItem = Item(Kind.SWATCH)
    val swatch: View get() = swatchItem
    private val opacity = Item(Kind.OPACITY)
    private var selectionRestyle = false
    private var selectionPulse: ValueAnimator? = null
    private val selectionTargets get() = listOf(size, swatchItem, tools.getValue(ToolSlot.BRUSH))

    var edge: StripPlacement.Edge = StripPlacement.Edge.LEFT
        set(value) { field = value; arrange() }

    init {
        orientation = HORIZONTAL
        val touch = kit.dpi(ChromeKit.TOUCH_DP)
        column.addView(grip, LayoutParams(touch, kit.dpi(18f)))
        for ((slot, item) in tools) {
            column.addView(item, LayoutParams(touch, touch))
            kit.label(item, when (slot) {
                ToolSlot.BRUSH -> "Brush — tap again for the brushes"
                ToolSlot.SMUDGE -> "Smudge — tap again for the smudge brushes"
                ToolSlot.ERASER -> "Eraser — tap again for the erasers"
            })
            item.setOnClickListener { host.toolTapped(slot, item) }
        }
        column.addView(select, LayoutParams(touch, touch))
        kit.label(select, "Select — tap a line to pick it, tap again for the one under it, draw a loop, or tap again for Settings")
        select.setOnClickListener { host.selectTapped(select) }
        column.addView(size, LayoutParams(touch, touch))
        column.addView(swatchItem, LayoutParams(touch, touch))
        column.addView(opacity, LayoutParams(touch, touch))
        kit.label(grip, "Move the tool strip — drag it to either edge")
        updateRestyleLabels()
        kit.label(opacity, "Opacity — drag up or down, or tap for a slider")
        grip.setOnTouchListener(GripTouch())
        size.setOnTouchListener(ValueTouch(sizeAxis = true))
        opacity.setOnTouchListener(ValueTouch(sizeAxis = false))
        arrange()
    }

    /** Column against the edge, the hair on the inside; the column's outer corners square so it reads as part of the edge. */
    private fun arrange() {
        removeAllViews()
        val left = edge == StripPlacement.Edge.LEFT
        val r = kit.dp(23f)
        val radii = if (left) floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f) else floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
        kit.chromeSurface(column, radii)
        // The bar's 32 dp touch strip centres a 6 dp hair; pulled in so the hair sits just off the column, as drawn.
        val tuck = -kit.dpi(10f)
        val hair = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_VERTICAL
            if (left) marginStart = tuck else marginEnd = tuck
        }
        if (left) { addView(column); addView(recentBar, hair) } else { addView(recentBar, hair); addView(column) }
    }

    /** Which tools exist (a library with no smudge brush has no smudge button) and which is in the hand (null: Select is). */
    fun showTools(available: Set<ToolSlot>, active: ToolSlot?) {
        for ((slot, item) in tools) {
            item.visibility = if (slot in available) VISIBLE else GONE
            item.chosen = slot == active
            item.invalidate()
        }
    }

    /** Highlight the strip controls that edit the current selection; only their rings pulse. */
    fun setSelectionRestyle(on: Boolean, pulse: Boolean) {
        if (!on) {
            selectionRestyle = false
            cancelSelectionPulse()
            selectionTargets.forEach { it.isActivated = false; it.ringScale = 1f; it.ringAlpha = 1f; it.invalidate() }
            updateRestyleLabels()
            return
        }
        val wasOn = selectionRestyle
        selectionRestyle = true
        selectionTargets.forEach { it.isActivated = true; it.invalidate() }
        updateRestyleLabels()
        if (!pulse) {
            if (!wasOn) cancelSelectionPulse()
            return
        }
        if (animationsReduced()) {
            cancelSelectionPulse()
            return
        }
        startSelectionPulse()
    }

    private fun updateRestyleLabels() {
        kit.label(size, if (selectionRestyle)
            "Selected line weight — drag up or down, or tap for a slider"
        else "Size — drag up or down, or tap for a slider")
        kit.label(swatchItem, if (selectionRestyle)
            "Colour — change the selected lines’ colour"
        else "Colour — tap to pick, or drag onto the drawing to take a colour from it")
        kit.label(tools.getValue(ToolSlot.BRUSH), if (selectionRestyle)
            "Brush — change the selected lines’ brush"
        else "Brush — tap again for the brushes")
    }

    private fun startSelectionPulse() {
        selectionPulse?.cancel()
        selectionTargets.forEach { it.ringScale = 1f; it.ringAlpha = 1f }
        lateinit var animator: ValueAnimator
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = PULSE_CYCLE_MS * PULSE_COUNT
            interpolator = LinearInterpolator()
            addUpdateListener { a ->
                val phase = (a.animatedFraction * PULSE_COUNT) % 1f
                val rise = (0.5 - 0.5 * cos(phase * 2.0 * PI)).toFloat()
                val scale = 1f + 0.15f * rise
                val alpha = 1f - 0.6f * rise
                selectionTargets.forEach { it.ringScale = scale; it.ringAlpha = alpha; it.invalidate() }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (selectionPulse === animator) {
                        selectionTargets.forEach { it.ringScale = 1f; it.ringAlpha = 1f; it.invalidate() }
                        selectionPulse = null
                    }
                }
            })
        }
        selectionPulse = animator
        animator.start()
    }

    private fun cancelSelectionPulse() {
        selectionPulse?.cancel()
        selectionPulse = null
        selectionTargets.forEach { it.ringScale = 1f; it.ringAlpha = 1f }
    }

    private fun animationsReduced(): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled()) return true
        val resolver = context.contentResolver
        return android.provider.Settings.Global.getFloat(resolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f ||
            android.provider.Settings.Global.getFloat(resolver, android.provider.Settings.Global.TRANSITION_ANIMATION_SCALE, 1f) == 0f
    }

    override fun onDetachedFromWindow() {
        selectionRestyle = false
        cancelSelectionPulse()
        selectionTargets.forEach { it.isActivated = false; it.invalidate() }
        updateRestyleLabels()
        super.onDetachedFromWindow()
    }

    /** JB-5.20e: whether the Select button is offered, and whether it is the tool in the hand (the brush buttons then
     *  show unchosen through [showTools]). */
    fun showSelect(shown: Boolean, chosen: Boolean) {
        select.visibility = if (shown) VISIBLE else GONE
        select.chosen = chosen
        select.invalidate()
    }

    /**
     * Whether the opacity button is offered. A pencil, watercolour or oil brush has no opacity of its own: how strong it
     * lays comes from pressure, water and load (the Lead's step-4 check 3: never a knob that does nothing).
     */
    fun showOpacity(shown: Boolean) {
        val v = if (shown) VISIBLE else GONE
        if (opacity.visibility != v) opacity.visibility = v
    }

    /** The values the size, colour and opacity buttons show. */
    fun showValues(sizePx: Float, argb: Int, opacityValue: Float) {
        size.sizePx = sizePx
        swatchItem.argb = argb
        opacity.argb = argb
        opacity.opacity = opacityValue
        size.invalidate(); swatch.invalidate(); opacity.invalidate()
        recentBar.setCurrent(argb)
    }

    private enum class Kind { GRIP, TOOL, SIZE, SWATCH, OPACITY }

    /** One strip button: a 40 dp target drawing a small mark. */
    private inner class Item(val kind: Kind, val icon: JbIcon? = null) : View(context) {
        var chosen = false
            set(value) { field = value; isSelected = value; invalidate() }
        var ringScale = 1f
        var ringAlpha = 1f
        var sizePx = 12f
        var argb = 0
        var opacity = 1f
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val box = RectF()
        private val drawable = icon?.drawable(kit.p.drawerInk)

        init { isClickable = true; isFocusable = true }

        override fun onDraw(c: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            when (kind) {
                Kind.GRIP -> {
                    paint.style = Paint.Style.FILL
                    paint.color = kit.ink(0.3f)
                    box.set(cx - kit.dp(7f), cy - kit.dp(1.5f), cx + kit.dp(7f), cy + kit.dp(1.5f))
                    c.drawRoundRect(box, kit.dp(1.5f), kit.dp(1.5f), paint)
                }
                Kind.TOOL -> {
                    val h = kit.dpi(10f)
                    drawable?.setBounds(Math.round(cx) - h, Math.round(cy) - h, Math.round(cx) + h, Math.round(cy) + h)
                    drawable?.draw(c)
                }
                Kind.SIZE -> {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = kit.dp(2f)
                    paint.color = kit.p.drawerInk
                    c.drawCircle(cx, cy, kit.dp(11f), paint)
                    paint.style = Paint.Style.FILL
                    c.drawCircle(cx, cy, dotRadius(sizePx), paint)
                }
                Kind.SWATCH -> {
                    paint.style = Paint.Style.FILL
                    paint.color = ColorUtils.setAlphaComponent(argb, 255)
                    c.drawCircle(cx, cy, kit.dp(12f), paint)
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = kit.dp(2f)
                    paint.color = kit.ink(0.7f)
                    c.drawCircle(cx, cy, kit.dp(12f), paint)
                }
                Kind.OPACITY -> {
                    // A gauge, so it can never be mistaken for the colour button: a faint track, an arc of drawer ink for
                    // how opaque, and inside it the colour at that opacity over a checkerboard.
                    val r = kit.dp(11f)
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = kit.dp(2.5f)
                    paint.strokeCap = Paint.Cap.ROUND
                    paint.color = kit.ink(0.22f)
                    c.drawCircle(cx, cy, r, paint)
                    paint.color = kit.p.drawerInk
                    box.set(cx - r, cy - r, cx + r, cy + r)
                    c.drawArc(box, -90f, 360f * opacity.coerceIn(0f, 1f), false, paint)
                    val inner = kit.dp(6f)
                    c.save()
                    c.clipPath(android.graphics.Path().apply { addCircle(cx, cy, inner, android.graphics.Path.Direction.CW) })
                    paint.style = Paint.Style.FILL
                    val cell = inner / 2f
                    for (i in 0 until 4) for (j in 0 until 4) {
                        paint.color = if ((i + j) % 2 == 0) kit.p.drawerInk else kit.p.inkFaint
                        c.drawRect(cx - inner + i * cell, cy - inner + j * cell, cx - inner + (i + 1) * cell, cy - inner + (j + 1) * cell, paint)
                    }
                    paint.color = ColorUtils.setAlphaComponent(argb, Math.round(opacity * 255).coerceIn(0, 255))
                    c.drawCircle(cx, cy, inner, paint)
                    c.restore()
                }
            }
            if (chosen || isActivated) drawAccentRing(c, cx, cy)
        }

        private fun drawAccentRing(c: Canvas, cx: Float, cy: Float) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = kit.dp(1.5f)
            paint.shader = SelectAccent.shader(kit, width.toFloat(), height.toFloat())
            paint.alpha = (ringAlpha * 255).toInt().coerceIn(0, 255)
            c.drawCircle(cx, cy, kit.dp(15f) * ringScale, paint)
            paint.shader = null
            paint.alpha = 255
            paint.style = Paint.Style.FILL
        }

        /** The size dot: 1 px → 2 dp, 256 px and up → 9 dp, on a log scale so small brushes are told apart. */
        private fun dotRadius(px: Float): Float {
            val t = (ln(px.coerceIn(1f, 256f).toDouble()) / ln(256.0)).toFloat()
            return kit.dp(2f + 7f * t)
        }
    }

    /** Drag the grip to move the strip; the strip follows the finger and [Host.stripDropped] decides where it lands. */
    private inner class GripTouch : OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; parent?.requestDisallowInterceptTouchEvent(true) }
                MotionEvent.ACTION_MOVE -> { translationX = e.rawX - downX; translationY = e.rawY - downY }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val cx = left + translationX + width / 2f
                    val cy = top + translationY + height / 2f
                    translationX = 0f; translationY = 0f
                    if (e.actionMasked == MotionEvent.ACTION_UP) host.stripDropped(cx, cy)
                }
            }
            return true
        }
    }

    /**
     * Size or opacity: a drag up or down changes it (up is more), a tap opens its slider. The drag is JB-2.16a's
     * [SizeOpacityDrag], so the strip and the three-finger swipe feel the same: size doubles every 160 dp.
     */
    private inner class ValueTouch(private val sizeAxis: Boolean) : OnTouchListener {
        private var downY = 0f
        private var downX = 0f
        private var drag: SizeOpacityDrag? = null
        private val slop = ViewConfiguration.get(context).scaledTouchSlop

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; drag = null
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - downY
                    if (drag == null && hypot(e.rawX - downX, dy) > slop) {
                        drag = SizeOpacityDrag(host.sizeNow(), host.opacityNow(), host.zoom(), resources.displayMetrics.density)
                    }
                    val d = drag ?: return true
                    report(d, dy, done = false)
                }
                MotionEvent.ACTION_UP -> {
                    val d = drag
                    if (d != null) report(d, e.rawY - downY, done = true)
                    else { v.performClick(); if (sizeAxis) host.sizeTapped(v) else host.opacityTapped(v) }
                    drag = null
                }
                MotionEvent.ACTION_CANCEL -> {
                    drag?.let { report(it, e.rawY - downY, done = true) }
                    drag = null
                }
            }
            return true
        }

        /** Size: up → the drag's +x (bigger). Opacity: the drag's own y, where up is more. */
        private fun report(d: SizeOpacityDrag, dy: Float, done: Boolean) {
            if (sizeAxis) { d.move(-dy, 0f); host.sizeDragged(d.size, done) }
            else { d.move(0f, dy); host.opacityDragged(d.opacity, done) }
        }
    }

    private companion object {
        const val PULSE_CYCLE_MS = 600L
        const val PULSE_COUNT = 2
    }
}
