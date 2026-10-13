package cc.joycreator.joybrush.android.select

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.graphics.ColorUtils
import cc.joycreator.joybrush.android.chrome.ChromeKit
import cc.joycreator.joybrush.core.chrome.IconInk
import kotlin.math.roundToLong

/** K11 tap-order scrubber. The controller owns the stable candidate snapshot and applies choices. */
@SuppressLint("ViewConstructor")
class SelectCountView(private val kit: ChromeKit, private val callbacks: Callbacks) : View(kit.context) {
    data class Model(val index: Int, val total: Int)

    interface Callbacks {
        /** Begin a scrub from the current tap pick; null declines because there is no count to scrub. */
        fun begin(pen: Boolean): Model?
        /** Select one candidate from the captured order. False means the model refused this step. */
        fun choose(index: Int): Boolean
        fun end()
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("monospace", android.graphics.Typeface.NORMAL)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pill = RectF()
    private var model = Model(0, 1)
    private var paperArgb = 0xFF070709.toInt()
    private var pointerId = MotionEvent.INVALID_POINTER_ID
    private var downRawX = 0f
    private var downRawY = 0f
    private var startIndex = 0
    private var startTotal = 1
    private var lastRequested = 0
    private var acceptedIndex = 0
    private var moved = false
    private var ending = false
    private var consumeRemaining = false
    private var ignoredTouch = false
    private var touchClickDispatch = false

    /** White or black ink, selected by the controller's screen sampler. */
    var ink: IconInk = IconInk.LIGHT
        set(value) { field = value; invalidate() }

    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        minimumWidth = kit.dpi(44f)
        minimumHeight = kit.dpi(44f)
        textPaint.textSize = kit.dp(TEXT_SIZE_DP)
        refreshLabel()
    }

    /** Update the visible count. An active pointer keeps its original start index and total. */
    fun show(index: Int, total: Int) {
        if (total <= 0) {
            endSession(consumeTail = true)
            visibility = GONE
            return
        }
        val normalized = Math.floorMod(index, total)
        model = Model(normalized, total)
        refreshLabel()
        if (visibility != VISIBLE) visibility = VISIBLE
        requestLayout()
        invalidate()
    }

    /** The backing follows the paper while remaining translucent. */
    fun setPaper(argb: Int) {
        paperArgb = argb
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        textPaint.textSize = kit.dp(TEXT_SIZE_DP)
        val textWidth = textPaint.measureText(text()) + kit.dp(HORIZONTAL_INSET_DP * 2f)
        val desiredW = maxOf(kit.dpi(TOUCH_MIN_DP), kotlin.math.ceil(textWidth.toDouble()).toInt())
        val desiredH = kit.dpi(TOUCH_MIN_DP)
        setMeasuredDimension(resolveSize(desiredW, widthMeasureSpec), resolveSize(desiredH, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val visualH = kit.dp(VISUAL_HEIGHT_DP)
        val visualW = minOf(width.toFloat(), maxOf(kit.dp(TOUCH_MIN_DP), textPaint.measureText(text()) + kit.dp(HORIZONTAL_INSET_DP * 2f)))
        pill.set((width - visualW) / 2f, (height - visualH) / 2f, (width + visualW) / 2f, (height + visualH) / 2f)
        fill.color = ColorUtils.setAlphaComponent(paperArgb, PAPER_ALPHA)
        fill.style = Paint.Style.FILL
        canvas.drawRoundRect(pill, visualH / 2f, visualH / 2f, fill)
        outline.shader = SelectAccent.shader(kit, pill.width(), pill.height()).apply {
            setLocalMatrix(Matrix().apply { setTranslate(pill.left, pill.top) })
        }
        outline.strokeWidth = kit.dp(OUTLINE_DP)
        canvas.drawRoundRect(pill, visualH / 2f, visualH / 2f, outline)
        outline.shader = null
        textPaint.color = if (ink == IconInk.DARK) DARK_INK else LIGHT_INK
        val shadow = if (ink == IconInk.DARK) LIGHT_INK else DARK_INK
        textPaint.setShadowLayer(kit.dp(1f), 0f, 0f, shadow)
        val baseline = height / 2f - (textPaint.ascent() + textPaint.descent()) / 2f
        canvas.drawText(text(), width / 2f, baseline, textPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            // A hidden view can miss the previous stream's terminal event. Treat this DOWN as a fresh stream.
            if (pointerId != MotionEvent.INVALID_POINTER_ID) endSession()
            pointerId = MotionEvent.INVALID_POINTER_ID
            ignoredTouch = false
            consumeRemaining = false
        }
        if (ignoredTouch) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) ignoredTouch = false
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // A fresh DOWN begins a new stream if a previous CANCEL had no trailing UP.
                consumeRemaining = false
                val index = event.actionIndex.coerceIn(0, event.pointerCount - 1)
                val candidate = callbacks.begin(isPen(event, index))
                if (candidate == null) { ignoredTouch = true; return true }
                val total = candidate.total
                if (total <= 0) { callbacks.end(); ignoredTouch = true; return true }
                model = Model(Math.floorMod(candidate.index, total), total)
                startIndex = model.index
                startTotal = model.total
                lastRequested = startIndex
                acceptedIndex = startIndex
                pointerId = event.getPointerId(index)
                downRawX = rawX(event, index)
                downRawY = rawY(event, index)
                moved = false
                refreshLabel()
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (consumeRemaining) return true
                val id = pointerId
                if (id == MotionEvent.INVALID_POINTER_ID) return super.onTouchEvent(event)
                val index = event.findPointerIndex(id)
                if (index < 0) return true
                val dx = rawX(event, index) - downRawX
                val dy = rawY(event, index) - downRawY
                if (dx * dx + dy * dy > kit.dp(TAP_SLOP_DP) * kit.dp(TAP_SLOP_DP)) moved = true
                if (moved && startTotal > 1) {
                    val steps = (dx / kit.dp(STEP_DP)).roundToLong()
                    val target = wrappedIndex(startIndex, steps, startTotal)
                    requestChoice(target)
                }
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> return pointerId != MotionEvent.INVALID_POINTER_ID || consumeRemaining || super.onTouchEvent(event)
            MotionEvent.ACTION_POINTER_UP -> {
                if (consumeRemaining) return true
                val index = event.actionIndex
                if (pointerId != MotionEvent.INVALID_POINTER_ID && event.getPointerId(index) == pointerId) {
                    endSession()
                    pointerId = MotionEvent.INVALID_POINTER_ID
                    consumeRemaining = true
                }
                return pointerId != MotionEvent.INVALID_POINTER_ID || consumeRemaining || super.onTouchEvent(event)
            }
            MotionEvent.ACTION_UP -> {
                if (consumeRemaining) { consumeRemaining = false; return true }
                if (pointerId == MotionEvent.INVALID_POINTER_ID) return super.onTouchEvent(event)
                val index = event.findPointerIndex(pointerId)
                if (index >= 0 && !moved && startTotal > 1) {
                    requestChoice(Math.floorMod(startIndex.toLong() + 1L, startTotal.toLong()).toInt())
                    touchClickDispatch = true
                    try { performClick() } finally { touchClickDispatch = false }
                }
                endSession()
                pointerId = MotionEvent.INVALID_POINTER_ID
                consumeRemaining = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (consumeRemaining) { consumeRemaining = false; return true }
                endSession(consumeTail = true)
                pointerId = MotionEvent.INVALID_POINTER_ID
                return true
            }
        }
        return pointerId != MotionEvent.INVALID_POINTER_ID || consumeRemaining || super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        if (!touchClickDispatch && !gestureActive()) cycleBy(1)
        return true
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = android.widget.SeekBar::class.java.name
        if (model.total > 0) {
            info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(
                AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, 1f, model.total.toFloat(), (model.index + 1).toFloat(),
            )
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        }
    }

    override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean {
        when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> { if (!gestureActive()) cycleBy(1); return true }
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> { if (!gestureActive()) cycleBy(-1); return true }
        }
        return super.performAccessibilityAction(action, arguments)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) endSession(consumeTail = true)
    }

    override fun onDetachedFromWindow() {
        endSession(consumeTail = true)
        pointerId = MotionEvent.INVALID_POINTER_ID
        ignoredTouch = false
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        consumeRemaining = false
        ignoredTouch = false
    }

    private fun cycleBy(delta: Int) {
        if (gestureActive()) return
        val initial = callbacks.begin(pen = false) ?: return
        if (initial.total <= 0) { callbacks.end(); return }
        val current = Math.floorMod(initial.index, initial.total)
        val target = Math.floorMod(current.toLong() + delta.toLong(), initial.total.toLong()).toInt()
        if (target != current && callbacks.choose(target)) show(target, initial.total)
        callbacks.end()
    }

    private fun wrappedIndex(start: Int, steps: Long, total: Int): Int {
        val n = total.toLong()
        return ((start.toLong() + Math.floorMod(steps, n)) % n).toInt()
    }

    private fun requestChoice(index: Int) {
        if (index == acceptedIndex) { lastRequested = index; return }
        if (index == lastRequested) return
        lastRequested = index
        if (callbacks.choose(index)) {
            acceptedIndex = index
            model = Model(index, startTotal)
            refreshLabel()
            invalidate()
        } else {
            endSession(consumeTail = true)
        }
    }

    private fun endSession(consumeTail: Boolean = false) {
        if (ending || pointerId == MotionEvent.INVALID_POINTER_ID) return
        ending = true
        try { callbacks.end() } finally {
            pointerId = MotionEvent.INVALID_POINTER_ID
            if (consumeTail) consumeRemaining = true
            ending = false
        }
    }

    private fun gestureActive() = pointerId != MotionEvent.INVALID_POINTER_ID || consumeRemaining || ignoredTouch

    private fun rawX(event: MotionEvent, pointerIndex: Int): Float =
        event.rawX + (event.getX(pointerIndex) - event.x)

    private fun rawY(event: MotionEvent, pointerIndex: Int): Float =
        event.rawY + (event.getY(pointerIndex) - event.y)

    private fun isPen(event: MotionEvent, pointerIndex: Int): Boolean =
        event.getToolType(pointerIndex) == MotionEvent.TOOL_TYPE_STYLUS ||
            event.getToolType(pointerIndex) == MotionEvent.TOOL_TYPE_ERASER ||
            (event.source and InputDevice.SOURCE_STYLUS) == InputDevice.SOURCE_STYLUS

    private fun text() = "‹ ${model.index + 1} / ${model.total} ›"

    private fun refreshLabel() {
        kit.label(this, "Line ${model.index + 1} of ${model.total}. Tap to choose the next line or drag horizontally to choose another")
    }

    companion object {
        private const val TEXT_SIZE_DP = 11f
        private const val HORIZONTAL_INSET_DP = 9f
        private const val VISUAL_HEIGHT_DP = 22f
        private const val TOUCH_MIN_DP = 44f
        private const val TAP_SLOP_DP = 4f
        private const val STEP_DP = 12f
        private const val OUTLINE_DP = 1.5f
        private const val PAPER_ALPHA = 0xE0
        private const val DARK_INK = 0xFF121214.toInt()
        private const val LIGHT_INK = 0xFFF7F7F9.toInt()
    }
}
