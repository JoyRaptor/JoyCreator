package cc.joycreator.joybrush.android.chrome

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import cc.joycreator.joybrush.android.select.SelectAccent
import cc.joycreator.joybrush.core.guide.Guide
import cc.joycreator.joybrush.core.guide.GuideSettings
import kotlin.math.ceil
import kotlin.math.roundToLong

/**
 * The floating widget that sits beside the symmetry line: how many copies the brush makes, and the three chips that
 * shape them (owner, 2026-10-19).
 *
 * It is chrome over the drawing, never a layer: like every guide it never reaches the art and never exports. The
 * count is a sideways scrubber (the gesture and the accessibility pattern of
 * [cc.joycreator.joybrush.android.select.SelectCountView]: a drag of about 12 dp a step, a tap for one more, and a
 * SeekBar a screen reader can turn); the chips are the owner's cyan cut-out chips
 * ([cc.joycreator.joybrush.android.select.SelectBarView]'s), on the app's own chrome surface in a pill.
 *
 * Every change goes back the way the ruler's handles do: the widget reports the new settings and whether the finger
 * is done, and the screen writes them and remembers them when it is.
 */
@SuppressLint("ViewConstructor")
class SymmetryWidgetView(private val kit: ChromeKit) : LinearLayout(kit.context) {

    /** The guide settings the widget shows. Only the symmetry is touched; the other guides are left alone. */
    var settings: GuideSettings = GuideSettings.NONE
        set(value) { field = value; readModel() }

    /** The settings changed: called on every move and once more when the finger lifts. */
    var onChanged: ((GuideSettings, done: Boolean) -> Unit)? = null

    private var count = MIN_COUNT
    private var kaleido = false
    private var overlap = false
    private var symmetric = true

    private val scrubber = CountScrubber()
    private val mirrorChip = Chip(
        "Mirror",
        "Mirror: every copy is mirrored as well, a kaleidoscope. It only means something with three copies or more",
    ) { setKaleido(!kaleido) }
    private val overlapChip = Chip(
        "Overlap",
        "Overlap: the copies are separate brushes where they cross. Off, they merge into one stroke",
    ) { setOverlap(!overlap) }
    private val symmetryChip = Chip(
        "Symmetry",
        "Symmetry: on, every copy shares one brush's randomness (jitter and tilt). Off, each copy draws its own",
    ) { setSymmetric(!symmetric) }

    /** Where the widget wants to sit: the screen point of the line's middle. */
    private var anchorX = 0f
    private var anchorY = 0f

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(kit.dpi(PAD_DP), kit.dpi(3f), kit.dpi(PAD_DP), kit.dpi(3f))
        // The pill: ChromeKit.chromeSurface, and NOT the app's own pill() — that one is 10% white with white text, which
        // is unreadable on white paper, and this widget floats over whatever the person is painting. The chrome surface
        // (near-solid panel plus a hairline of the ground ramp's line) reads on white paper and on black paint alike.
        kit.chromeSurface(this, FloatArray(8) { kit.dp(PILL_RADIUS_DP) })
        scrubber.onValue = { value, done -> setCount(value, done) }
        addView(scrubber, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        for (c in listOf(mirrorChip, overlapChip, symmetryChip)) {
            addView(c, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = kit.dpi(GAP_DP) })
        }
        readModel()
    }

    // ── what the widget shows ──

    private fun readModel() {
        val sym: Guide.Symmetry? = settings.symmetry
        if (sym == null) {
            visibility = GONE
            return
        }
        if (visibility != VISIBLE) visibility = VISIBLE
        count = sym.count.coerceIn(MIN_COUNT, MAX_COUNT)
        kaleido = sym.kaleido
        overlap = sym.overlap
        symmetric = sym.symmetric
        scrubber.value = count
        mirrorChip.on = kaleido
        overlapChip.on = overlap
        symmetryChip.on = symmetric
        // The kaleidoscope needs copies to mirror: with two there is only the one mirror.
        mirrorChip.live = count >= 3
    }

    private fun report(done: Boolean) {
        val sym = settings.symmetry ?: return
        val next = sym.copy(count = count, kaleido = kaleido, overlap = overlap, symmetric = symmetric)
        onChanged?.invoke(settings.copy(symmetry = next), done)
    }

    private fun setCount(value: Int, done: Boolean) {
        val v = value.coerceIn(MIN_COUNT, MAX_COUNT)
        if (v != count) {
            count = v
            scrubber.value = v
            mirrorChip.live = v >= 3
        }
        report(done)
    }

    private fun setKaleido(on: Boolean) {
        if (kaleido == on) return
        kaleido = on
        mirrorChip.on = on
        report(true)
    }

    private fun setOverlap(on: Boolean) {
        if (overlap == on) return
        overlap = on
        overlapChip.on = on
        report(true)
    }

    private fun setSymmetric(on: Boolean) {
        if (symmetric == on) return
        symmetric = on
        symmetryChip.on = on
        report(true)
    }

    // ── where the widget sits ──

    /**
     * Puts the widget beside the point ([x], [y]) on the screen, kept inside the window (the same clamp
     * [cc.joycreator.joybrush.android.chrome.Popovers] uses for a panel).
     */
    fun placeBeside(x: Float, y: Float) {
        anchorX = x
        anchorY = y
        applyPlacement()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        applyPlacement()
    }

    private fun applyPlacement() {
        if (width <= 0 || height <= 0) return
        val host = parent as? ViewGroup ?: return
        val gap = kit.dpi(GAP_DP).toFloat()
        val minX = host.paddingLeft + gap
        val maxX = host.width - host.paddingRight - gap - width
        val minY = host.paddingTop + gap
        val maxY = host.height - host.paddingBottom - gap - height
        val wantX = anchorX + kit.dp(OFFSET_DP)
        val wantY = anchorY - height / 2f
        this.x = wantX.coerceIn(minX, maxOf(minX, maxX))
        this.y = wantY.coerceIn(minY, maxOf(minY, maxY))
    }

    // ── the count scrubber ──

    /**
     * The number of copies. A drag sideways moves it, a tap adds one, and a screen reader turns it like a slider
     * (the pattern of [cc.joycreator.joybrush.android.select.SelectCountView]).
     */
    private inner class CountScrubber : View(kit.context) {

        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = kit.dp(COUNT_SIZE_DP)
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.create("monospace", android.graphics.Typeface.NORMAL)
        }
        private var downX = 0f
        private var downY = 0f
        private var startValue = MIN_COUNT
        private var current = MIN_COUNT
        private var moved = false
        private var fromTouch = false

        /** The count shown. */
        var value = MIN_COUNT
            set(v) { field = v; refreshLabel(); requestLayout(); invalidate() }

        /** The count changed: the new number, and whether the finger is done. */
        var onValue: ((Int, Boolean) -> Unit)? = null

        init {
            isClickable = true
            isFocusable = true
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            minimumWidth = kit.dpi(TOUCH_MIN_DP)
            refreshLabel()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            p.textSize = kit.dp(COUNT_SIZE_DP)
            val textWidth = p.measureText(text()) + kit.dp(COUNT_PAD_DP) * 2f
            val desiredW = maxOf(kit.dpi(TOUCH_MIN_DP), ceil(textWidth.toDouble()).toInt())
            setMeasuredDimension(resolveSize(desiredW, widthMeasureSpec), resolveSize(kit.dpi(HEIGHT_DP), heightMeasureSpec))
        }

        override fun onDraw(canvas: Canvas) {
            p.textSize = kit.dp(COUNT_SIZE_DP)
            p.color = kit.p.drawerInk
            p.style = Paint.Style.FILL
            p.shader = null
            val baseline = height / 2f - (p.ascent() + p.descent()) / 2f
            canvas.drawText(text(), width / 2f, baseline, p)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    startValue = value
                    current = value
                    moved = false
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (!moved && dx * dx + dy * dy > kit.dp(SLOP_DP) * kit.dp(SLOP_DP)) moved = true
                    if (moved) {
                        val steps = (dx / kit.dp(STEP_DP)).roundToLong()
                        val target = (startValue + steps).toInt().coerceIn(MIN_COUNT, MAX_COUNT)
                        if (target != current) {
                            current = target
                            value = target
                            onValue?.invoke(target, false)
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        // A tap: one more copy.
                        val target = (value + 1).coerceAtMost(MAX_COUNT)
                        current = target
                        value = target
                        onValue?.invoke(target, true)
                        fromTouch = true
                        try { performClick() } finally { fromTouch = false }
                    } else {
                        onValue?.invoke(current, true)
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> return true
            }
            return super.onTouchEvent(event)
        }

        override fun performClick(): Boolean {
            super.performClick()
            if (!fromTouch) {
                val target = (value + 1).coerceAtMost(MAX_COUNT)
                value = target
                onValue?.invoke(target, true)
            }
            return true
        }

        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.className = android.widget.SeekBar::class.java.name
            info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(
                AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, MIN_COUNT.toFloat(), MAX_COUNT.toFloat(), value.toFloat(),
            )
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        }

        override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
            when (action) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> {
                    val target = (value + 1).coerceAtMost(MAX_COUNT)
                    value = target
                    onValue?.invoke(target, true)
                    return true
                }
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> {
                    val target = (value - 1).coerceAtLeast(MIN_COUNT)
                    value = target
                    onValue?.invoke(target, true)
                    return true
                }
            }
            return super.performAccessibilityAction(action, arguments)
        }

        private fun text() = "×$value"

        private fun refreshLabel() {
            kit.label(this, "Copies: $value. Drag sideways for more or fewer, or tap for one more")
        }
    }

    // ── a chip ──

    /**
     * One chip: ON is the owner's cyan accent with the word cut out of it; OFF is a hairline and a dimmer word
     * ([cc.joycreator.joybrush.android.select.SelectBarView]'s chip, drawn here).
     */
    private inner class Chip(val word: String, label: String, private val onTap: () -> Unit) : View(kit.context) {

        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = kit.dp(CHIP_SIZE_DP) }
        private val cut = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
        private var accent: Shader? = null
        private var accentW = 0f
        private var accentH = 0f

        /** The chip is on. */
        var on = false
            set(v) { field = v; invalidate() }

        /** The chip means something right now; a chip that cannot is dimmed and does nothing. */
        var live = true
            set(v) { field = v; invalidate() }

        init {
            kit.label(this, label)
            isClickable = true
            // A chip that cannot mean anything right now (the Mirror with only two copies) does nothing when tapped.
            setOnClickListener { if (live) onTap() }
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = (p.measureText(word) + kit.dp(CHIP_PAD_DP) * 2f).toInt()
            setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize(kit.dpi(HEIGHT_DP), heightMeasureSpec))
        }

        override fun onDraw(cv: Canvas) {
            val ink = kit.p.drawerInk
            val radius = kit.dp(CHIP_RADIUS_DP)
            p.textSize = kit.dp(CHIP_SIZE_DP)
            if (on && live) {
                // The owner's locked cyan gradient, with the word cut out of it.
                p.style = Paint.Style.FILL
                p.alpha = 255
                p.shader = accent()
                val base = cv.saveLayer(null, null)
                cv.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, p)
                p.shader = null
                cv.saveLayer(null, cut)
                p.color = android.graphics.Color.BLACK
                cv.drawText(word, kit.dp(CHIP_PAD_DP), baseline(), p)
                cv.restoreToCount(base)
                return
            }
            p.style = Paint.Style.STROKE
            p.strokeWidth = kit.dp(1f)
            p.color = ColorUtils.setAlphaComponent(ink, if (live) 0x29 else 0x14)
            val inset = p.strokeWidth / 2
            cv.drawRoundRect(inset, inset, width - inset, height - inset, radius, radius, p)
            p.style = Paint.Style.FILL
            p.color = ColorUtils.setAlphaComponent(ink, if (live) 0xBF else 0x55)
            cv.drawText(word, kit.dp(CHIP_PAD_DP), baseline(), p)
        }

        private fun baseline() = height / 2f + p.textSize * 0.35f

        /** The accent, made once per size: a shader is a native object and must not be made every frame. */
        private fun accent(): Shader {
            val w = width.toFloat()
            val h = height.toFloat()
            if (accent == null || accentW != w || accentH != h) {
                accent = SelectAccent.shader(kit, w, h)
                accentW = w
                accentH = h
            }
            return accent!!
        }
    }

    companion object {
        /** One copy is the original stroke: the symmetry is off. */
        const val MIN_COUNT = 1

        /** The most copies around the line's middle. */
        const val MAX_COUNT = 30

        private const val HEIGHT_DP = 32f
        private const val PAD_DP = 6f
        private const val GAP_DP = 5f
        private const val PILL_RADIUS_DP = 19f
        private const val CHIP_RADIUS_DP = 16f
        private const val OFFSET_DP = 14f
        private const val COUNT_SIZE_DP = 12f
        private const val COUNT_PAD_DP = 9f
        private const val CHIP_SIZE_DP = 11.5f
        private const val CHIP_PAD_DP = 10f
        private const val TOUCH_MIN_DP = 44f
        private const val SLOP_DP = 4f
        private const val STEP_DP = 12f
    }
}
