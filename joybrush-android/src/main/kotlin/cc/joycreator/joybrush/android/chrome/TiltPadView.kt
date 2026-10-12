package cc.joycreator.joybrush.android.chrome

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import cc.joycreator.joybrush.core.media.PaperSlope
import cc.joycreator.joybrush.core.media.PaperTilt

/**
 * The tilt pad (M5.6, owner 2026-10-11): a small see-through disc in a bottom corner whose puck tips the paper, for the
 * hand that cannot tip the phone. It is the PC lab's pad (main.js 539-566) on a thumb: drag the puck, how far it is from
 * the middle is how steep the paper is, which way it points is where the paint runs, a double tap brings it back to level.
 * Like the lab's, the puck STAYS where it was left, so a wash can be left running while the thumb moves on.
 *
 * The pad speaks in SCREEN axes (y down); [PaperTiltController] turns that into the page's. A fill and a ring in the
 * chrome's own see-through surface and drawer ink; the cyan ring (a state, so a ring) says the paper is tipped.
 */
@SuppressLint("ViewConstructor")
class TiltPadView(private val kit: ChromeKit) : View(kit.context) {

    /** The tilt the puck makes, in screen axes ([PaperSlope.FLAT] when it is in the middle). Called on every move. */
    var onPad: ((PaperSlope) -> Unit)? = null

    private var puckX = 0f
    private var puckY = 0f
    private var lastDownAt = Long.MIN_VALUE
    private var dragging = false
    private var wanted = false
    private var chromeShown = true
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** True while the puck is off the middle: the pad is tipping the paper. */
    val tipped: Boolean get() = PaperTilt.padToSlope(puckX, puckY, travel()) != PaperSlope.FLAT

    init {
        isClickable = true
        isFocusable = true
        kit.surface(this, SIZE_DP / 2f)
        kit.label(this, "Tilt pad — drag to tip the paper so wet paint runs downhill; double-tap to level it")
        visibility = GONE
    }

    /** Where the pad sits: the bottom corner on the [left] or right, [endInsetPx] in from that edge (clear of the layer column). */
    fun dock(left: Boolean, endInsetPx: Int) {
        val lp = (layoutParams as? FrameLayout.LayoutParams) ?: return
        val g = Gravity.BOTTOM or (if (left) Gravity.START else Gravity.END)
        val m = kit.dpi(MARGIN_DP)
        val start = if (left) m + endInsetPx else m
        val end = if (left) m else m + endInsetPx
        if (lp.gravity != g || lp.marginStart != start || lp.marginEnd != end || lp.bottomMargin != m) {
            lp.gravity = g
            lp.marginStart = start
            lp.marginEnd = end
            lp.bottomMargin = m
            layoutParams = lp
        }
    }

    /** The "Tilt pad" switch. Switching it off levels the paper. */
    fun setWanted(on: Boolean) {
        wanted = on
        if (!on) flatten()
        refreshVisibility()
    }

    /** Four fingers hide the chrome; the pad goes with it and comes back with it. */
    fun setChromeShown(shown: Boolean) {
        chromeShown = shown
        refreshVisibility()
    }

    private fun refreshVisibility() {
        visibility = if (wanted && chromeShown) VISIBLE else GONE
    }

    /** Puck back to the middle: level. */
    fun flatten() {
        if (puckX == 0f && puckY == 0f) return
        puckX = 0f
        puckY = 0f
        invalidate()
        onPad?.invoke(PaperSlope.FLAT)
    }

    private fun travel(): Float = (width / 2f - kit.dp(PUCK_DP) - kit.dp(2f)).coerceAtLeast(1f)

    private fun setPuck(dx: Float, dy: Float) {
        val r = travel()
        val len = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
        val k = if (len > r) r / len else 1f
        puckX = dx * k
        puckY = dy * k
        invalidate()
        onPad?.invoke(PaperTilt.padToSlope(puckX, puckY, r))
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                // The lab's double tap: a second press within 300 ms levels it (main.js 557).
                if (e.eventTime - lastDownAt < DOUBLE_TAP_MS) flatten()
                lastDownAt = e.eventTime
            }
            MotionEvent.ACTION_MOVE -> if (dragging) setPuck(e.x - width / 2f, e.y - height / 2f)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }

    /** A screen reader's "activate" is the double tap: level the paper. */
    override fun performClick(): Boolean {
        flatten()
        return super.performClick()
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = width / 2f
        val hair = Math.max(1f, kit.dp(1f))
        // The idle ring, and a half-way ring and a middle dot so the person can see "level" and "how far".
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = hair
        paint.color = kit.ink(0.12f)
        c.drawCircle(cx, cy, r - hair / 2f, paint)
        paint.color = kit.ink(0.18f)
        c.drawCircle(cx, cy, travel() * 0.5f, paint)
        paint.style = Paint.Style.FILL
        c.drawCircle(cx, cy, kit.dp(1.5f), paint)
        val tilted = tipped
        val px = cx + puckX
        val py = cy + puckY
        paint.style = Paint.Style.FILL
        paint.color = kit.ink(if (tilted || dragging) 0.85f else 0.55f)
        c.drawCircle(px, py, kit.dp(PUCK_DP), paint)
        if (tilted) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = kit.dp(1.5f)
            paint.color = kit.p.stateSelected
            c.drawCircle(px, py, kit.dp(PUCK_DP) + kit.dp(2f), paint)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = kit.dpi(SIZE_DP)
        setMeasuredDimension(s, s)
    }

    companion object {
        /** The pad's diameter, dp (owner brief: about 72). */
        const val SIZE_DP = 72f
        private const val PUCK_DP = 12f
        private const val MARGIN_DP = 12f
        private const val DOUBLE_TAP_MS = 300L
    }
}
