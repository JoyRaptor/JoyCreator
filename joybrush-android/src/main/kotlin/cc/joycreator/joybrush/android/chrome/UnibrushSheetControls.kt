package cc.joycreator.joybrush.android.chrome

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Path
import android.graphics.Rect
import android.view.MotionEvent
import android.view.KeyEvent
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.FrameLayout
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.pow
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityViewCommand
import cc.joycreator.joybrush.core.brush.ResponseCurve

/** DRAFT — uncompiled/untested. Shared sheet widgets; no brush/model or engine ownership. */
internal class UnibrushSheetChip(
    private val kit: ChromeKit,
    private val caption: String,
    description: String,
    action: () -> Unit,
) : View(kit.context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = kit.dp(12f); textAlign = Paint.Align.CENTER
    }
    private val cutout = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    init {
        isClickable = true; isFocusable = true
        minimumHeight = kit.dpi(44f); minimumWidth = kit.dpi(44f)
        kit.label(this, description)
        setOnClickListener { action() }
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(maxOf(kit.dpi(44f), (glyph.measureText(caption) + kit.dp(24f)).roundToInt()), widthMeasureSpec),
            resolveSize(kit.dpi(44f), heightMeasureSpec))
    }
    override fun onDraw(canvas: Canvas) {
        val inset = kit.dp(3f)
        val saved = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        paint.style = Paint.Style.FILL
        paint.color = if (isSelected) kit.p.stateSelected else kit.ink(if (isPressed) .2f else .08f)
        paint.alpha = if (isEnabled) 255 else 90
        canvas.drawRoundRect(inset, inset, width - inset, height - inset, kit.dp(6f), kit.dp(6f), paint)
        glyph.color = kit.p.drawerInk
        glyph.alpha = if (isEnabled) 255 else 90
        glyph.xfermode = if (isSelected) cutout else null
        canvas.drawText(caption, width * .5f, height * .5f - (glyph.ascent() + glyph.descent()) * .5f, glyph)
        glyph.xfermode = null
        canvas.restoreToCount(saved)
        if (hasFocus()) {
            paint.style = Paint.Style.STROKE; paint.strokeWidth = kit.dp(1f); paint.color = kit.p.stateSelected
            canvas.drawRoundRect(1f, 1f, width - 1f, height - 1f, kit.dp(6f), kit.dp(6f), paint)
        }
    }
    override fun drawableStateChanged() { super.drawableStateChanged(); invalidate() }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.Button"; info.isSelected = isSelected
    }
}

/** A stable row. Model refresh never replaces this slider or moves its thumb during a drag. */
internal class UnibrushSheetSlider(
    private val kit: ChromeKit,
    title: String,
    description: String,
    private val low: Float,
    private val high: Float,
    private val read: () -> Float,
    private val edit: (Float, Boolean) -> Boolean,
    private val show: (Float) -> String,
    private val logarithmic: Boolean = false,
    private val integer: Boolean = false,
    private val power: Float = 1f,
) : LinearLayout(kit.context) {
    private val value = TextView(context).apply { setTextColor(kit.p.drawerInk); textSize = 12f; gravity = Gravity.END }
    private var dragging = false
    private var refreshing = false
    private val seek = SeekBar(context)
    init {
        require(low.isFinite() && high.isFinite() && high > low && (!logarithmic || low > 0f) && power.isFinite() && power > 0f)
        orientation = VERTICAL
        val head = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val label = TextView(context).apply { text = title; setTextColor(kit.p.drawerInk); textSize = 13f }
        kit.label(label, description); kit.label(value, "$title current value")
        head.addView(label, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        head.addView(value, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(head, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        seek.max = STEPS; seek.minimumHeight = kit.dpi(44f)
        seek.progressTintList = ColorStateList.valueOf(kit.p.stateSelected)
        seek.thumbTintList = ColorStateList.valueOf(kit.p.stateSelected)
        seek.progressBackgroundTintList = ColorStateList.valueOf(kit.ink(.18f))
        kit.label(seek, "$title. $description")
        addView(seek, LayoutParams(LayoutParams.MATCH_PARENT, kit.dpi(44f)))
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar: SeekBar) { dragging = true }
            override fun onStopTrackingTouch(bar: SeekBar) { finishEditing() }
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser || refreshing) return
                val accepted = edit(decode(progress), !dragging)
                refresh(forceThumb = !accepted)
            }
        })
        refresh()
    }
    fun refresh(forceThumb: Boolean = false) {
        val actual = read()
        value.text = show(actual)
        if (!dragging || forceThumb) {
            refreshing = true
            seek.progress = encode(actual)
            refreshing = false
        }
    }
    fun finishEditing() {
        if (!dragging) return
        dragging = false
        edit(read(), true)
        refresh()
    }
    private fun decode(progress: Int): Float {
        val t = (progress / STEPS.toFloat()).pow(power)
        val value = if (logarithmic) exp(ln(low.toDouble()) + t * ln(high.toDouble() / low)).toFloat()
            else low + t * (high - low)
        return if (integer) value.roundToInt().toFloat() else value
    }
    private fun encode(value: Float): Int {
        val safe = if (value.isFinite()) value.coerceIn(low, high) else low
        val t = if (logarithmic) (ln(safe.toDouble() / low) / ln(high.toDouble() / low)).toFloat()
            else (safe - low) / (high - low)
        return (t.pow(1f / power) * STEPS).roundToInt().coerceIn(0, STEPS)
    }
    private companion object { const val STEPS = 1000 }
}

/** Preview-input scrubber: x=pressure, y=tilt. Its callback belongs to the supplied engine preview. */
internal class UnibrushPreviewPad(private val kit: ChromeKit, private val changed: (Float, Float) -> Unit) : View(kit.context) {
    private var pressure = .7f
    private var tilt = .35f
    private var pointer = MotionEvent.INVALID_POINTER_ID
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    init {
        isFocusable = true; isClickable = true
        minimumWidth = kit.dpi(44f); minimumHeight = kit.dpi(44f)
        kit.label(this, "Preview pen input. Move left or right for pressure, up or down for tilt")
        fun action(label: String, dp: Float, dt: Float) {
            ViewCompat.addAccessibilityAction(this, label, AccessibilityViewCommand { _, _ -> set(pressure + dp, tilt + dt); true })
        }
        action("More pressure", .1f, 0f); action("Less pressure", -.1f, 0f)
        action("More tilt", 0f, .1f); action("Less tilt", 0f, -.1f)
    }
    private fun set(p: Float, t: Float) {
        if (!p.isFinite() || !t.isFinite()) return
        pressure = p.coerceIn(0f, 1f); tilt = t.coerceIn(0f, 1f)
        ViewCompat.setStateDescription(this, "Pressure ${Math.round(pressure * 100)} percent, tilt ${Math.round(tilt * 90)} degrees")
        changed(pressure, tilt * 1.5707964f); invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        paint.color = kit.ink(.08f); paint.style = Paint.Style.FILL
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), kit.dp(6f), kit.dp(6f), paint)
        paint.color = kit.p.drawerInk; paint.textSize = kit.dp(11f)
        canvas.drawText("Pressure →   Tilt ↓", kit.dp(8f), kit.dp(16f), paint)
        paint.color = kit.p.stateSelected
        canvas.drawCircle(kit.dp(12f) + pressure * (width - kit.dp(24f)), kit.dp(24f) + tilt * (height - kit.dp(36f)), kit.dp(5f), paint)
    }
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (width <= kit.dp(24f) || height <= kit.dp(36f)) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pointer = event.getPointerId(0); parent?.requestDisallowInterceptTouchEvent(true) }
            MotionEvent.ACTION_CANCEL -> { pointer = MotionEvent.INVALID_POINTER_ID; parent?.requestDisallowInterceptTouchEvent(false); return true }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == pointer) { pointer = MotionEvent.INVALID_POINTER_ID; parent?.requestDisallowInterceptTouchEvent(false) }
                return true
            }
        }
        val index = event.findPointerIndex(pointer)
        if (index >= 0 && event.actionMasked in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP))
            set((event.getX(index) - kit.dp(12f)) / (width - kit.dp(24f)), (event.getY(index) - kit.dp(24f)) / (height - kit.dp(36f)))
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            pointer = MotionEvent.INVALID_POINTER_ID; parent?.requestDisallowInterceptTouchEvent(false); performClick()
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onKeyDown(code: Int, event: KeyEvent): Boolean = when (code) {
        KeyEvent.KEYCODE_DPAD_LEFT -> { set(pressure - .05f, tilt); true }
        KeyEvent.KEYCODE_DPAD_RIGHT -> { set(pressure + .05f, tilt); true }
        KeyEvent.KEYCODE_DPAD_UP -> { set(pressure, tilt - .05f); true }
        KeyEvent.KEYCODE_DPAD_DOWN -> { set(pressure, tilt + .05f); true }
        else -> super.onKeyDown(code, event)
    }
}

/**
 * Bounded two-handle editor using the existing ResponseCurve evaluator. Each handle is a
 * real 44dp View with move actions and keyboard access. Echo/refusal resets keep the active
 * pointer; CANCEL restores the pre-gesture handles, while finishEditing commits the last edit.
 */
internal class UnibrushBezierEditorView(
    private val kit: ChromeKit,
    private val title: String,
    start: List<Float>,
    private val changed: (handles: List<Float>, done: Boolean, cancelled: Boolean) -> Unit,
) : FrameLayout(kit.context) {
    private var values = checked(start)
    private var original = values
    private var selected = 0
    private var dragging = -1
    private var pointer = MotionEvent.INVALID_POINTER_ID
    private var edited = false
    private var grabX = 0f
    private var grabY = 0f
    private var reading = Float.NaN
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val handles = List(2) { Handle(it) }
    private val target get() = kit.dp(44f)
    private val side get() = minOf(width - target, height - kit.dp(24f) - target).coerceAtLeast(0f)
    private val left get() = (width - side) * .5f
    private val top get() = kit.dp(24f) + target * .5f
    private fun x(i: Int) = left + values[i * 2] * side
    private fun y(i: Int) = top + (1f - values[i * 2 + 1]) * side

    init {
        setWillNotDraw(false)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        handles.forEach { addView(it, LayoutParams(kit.dpi(44f), kit.dpi(44f))) }
        updateDescriptions()
    }
    fun setHandles(next: List<Float>) {
        values = checked(next); updateDescriptions(); requestLayout(); invalidate()
    }
    fun showReading(value: Float) { reading = value; invalidate() }
    fun finishEditing() { if (dragging >= 0) finish(cancel = false) }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = resolveSize(kit.dpi(288f), widthMeasureSpec)
        val h = resolveSize(minOf(w + kit.dpi(24f), kit.dpi(244f)), heightMeasureSpec)
        setMeasuredDimension(w, h)
        val spec = MeasureSpec.makeMeasureSpec(kit.dpi(44f), MeasureSpec.EXACTLY)
        handles.forEach { it.measure(spec, spec) }
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        handles.forEachIndexed { i, h ->
            h.layout(0, 0, h.measuredWidth, h.measuredHeight)
            h.translationX = x(i) - h.measuredWidth * .5f
            h.translationY = y(i) - h.measuredHeight * .5f
            h.isEnabled = isEnabled && side > 0f
        }
    }
    override fun onDraw(c: Canvas) {
        paint.color = kit.p.drawerInk; paint.textSize = kit.dp(11f); paint.style = Paint.Style.FILL
        c.drawText(title, kit.dp(8f), kit.dp(16f), paint)
        if (side <= 0f) return
        paint.color = kit.ink(.25f); paint.style = Paint.Style.STROKE; paint.strokeWidth = kit.dp(1f)
        c.drawRect(left, top, left + side, top + side, paint)
        c.drawLine(left, top + side, left + side, top, paint)
        c.drawLine(left, top + side, x(0), y(0), paint)
        c.drawLine(left + side, top, x(1), y(1), paint)
        path.reset(); path.moveTo(left, top + side)
        path.cubicTo(x(0), y(0), x(1), y(1), left + side, top)
        paint.color = kit.p.drawerInk; paint.strokeWidth = kit.dp(2f)
        c.drawPath(path, paint)
        if (reading.isFinite()) {
            val input = reading.coerceIn(0f, 1f)
            paint.style = Paint.Style.FILL; paint.color = kit.p.stateSelected
            c.drawCircle(left + input * side, top + (1f - ResponseCurve.eval(values, input)) * side, kit.dp(4f), paint)
        }
    }
    private fun pick(ex: Float, ey: Float): Int {
        if (!ex.isFinite() || !ey.isFinite() || side <= 0f) return -1
        val d = (0..1).map { kotlin.math.hypot(ex - x(it), ey - y(it)) }
        val i = if (d[0] == d[1]) selected else if (d[0] < d[1]) 0 else 1
        return if (d[i] <= target * .5f) i else -1
    }
    override fun onInterceptTouchEvent(e: MotionEvent) = isEnabled &&
        (dragging >= 0 || (e.actionMasked == MotionEvent.ACTION_DOWN && pick(e.x, e.y) >= 0))

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled || side <= 0f) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = pick(e.x, e.y)
                if (hit < 0) return false
                selected = hit; dragging = hit; pointer = e.getPointerId(0)
                original = values; edited = false
                grabX = e.x - x(hit); grabY = e.y - y(hit)
                handles[hit].requestFocus(); handles.forEach { it.invalidate() }
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                if (dragging < 0) return false
                val index = e.findPointerIndex(pointer)
                if (index < 0) { finish(cancel = true); return true }
                val px = e.getX(index) - grabX; val py = e.getY(index) - grabY
                if (px.isFinite() && py.isFinite()) {
                    val next = values.toMutableList()
                    next[dragging * 2] = ((px - left) / side).coerceIn(0f, 1f)
                    next[dragging * 2 + 1] = (1f - (py - top) / side).coerceIn(0f, 1f)
                    if (next != values) { edited = true; setHandles(next); changed(values.toList(), false, false) }
                }
                if (e.actionMasked == MotionEvent.ACTION_UP) finish(cancel = false)
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (dragging >= 0 && e.getPointerId(e.actionIndex) == pointer) finish(cancel = false)
                return true
            }
            MotionEvent.ACTION_CANCEL -> { if (dragging < 0) return false; finish(cancel = true); return true }
        }
        return dragging >= 0
    }
    private fun finish(cancel: Boolean) {
        dragging = -1; pointer = MotionEvent.INVALID_POINTER_ID
        parent?.requestDisallowInterceptTouchEvent(false)
        if (edited) {
            if (cancel) setHandles(original)
            changed(values.toList(), true, cancel)
        } else handles[selected].performClick()
        edited = false
    }
    private fun nudge(i: Int, dx: Int, dy: Int, fine: Boolean = false): Boolean {
        if (!isEnabled || side <= 0f) return false
        finishEditing(); selected = i
        val step = if (fine) .005f else .025f
        val next = values.toMutableList()
        next[i * 2] = (next[i * 2] + dx * step).coerceIn(0f, 1f)
        next[i * 2 + 1] = (next[i * 2 + 1] + dy * step).coerceIn(0f, 1f)
        if (next != values) { setHandles(next); changed(values.toList(), true, false) }
        handles.forEach { it.invalidate() }
        return true
    }
    private fun updateDescriptions() {
        handles.forEachIndexed { i, handle -> ViewCompat.setStateDescription(handle,
            "Input ${Math.round(values[i * 2] * 100)} percent, output ${Math.round(values[i * 2 + 1] * 100)} percent") }
    }
    override fun onDetachedFromWindow() { if (dragging >= 0) finish(cancel = true); super.onDetachedFromWindow() }
    private inner class Handle(private val i: Int) : View(kit.context) {
        private val disc = Paint(Paint.ANTI_ALIAS_FLAG)
        private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = kit.dp(10f); textAlign = Paint.Align.CENTER }
        private val clear = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        init {
            isClickable = true; isFocusable = true; isFocusableInTouchMode = true
            kit.label(this, "$title Bezier handle ${i + 1}. Drag or use directional move actions")
            setOnClickListener { selected = i; handles.forEach { it.invalidate() } }
            fun action(name: String, dx: Int, dy: Int) {
                ViewCompat.addAccessibilityAction(this, name, AccessibilityViewCommand { _, _ -> nudge(i, dx, dy) })
            }
            action("Move left", -1, 0); action("Move right", 1, 0)
            action("Move up", 0, 1); action("Move down", 0, -1)
        }
        override fun onFocusChanged(gain: Boolean, direction: Int, old: Rect?) {
            super.onFocusChanged(gain, direction, old)
            if (gain) { selected = i; handles.forEach { it.invalidate() } }
        }
        override fun onKeyDown(code: Int, event: KeyEvent): Boolean = when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> nudge(i, -1, 0, event.isShiftPressed)
            KeyEvent.KEYCODE_DPAD_RIGHT -> nudge(i, 1, 0, event.isShiftPressed)
            KeyEvent.KEYCODE_DPAD_UP -> nudge(i, 0, 1, event.isShiftPressed)
            KeyEvent.KEYCODE_DPAD_DOWN -> nudge(i, 0, -1, event.isShiftPressed)
            else -> super.onKeyDown(code, event)
        }
        override fun onDraw(c: Canvas) {
            val cx = width * .5f; val cy = height * .5f; val r = kit.dp(9f)
            val saved = c.saveLayer(cx - r - 1f, cy - r - 1f, cx + r + 1f, cy + r + 1f, null)
            disc.style = Paint.Style.FILL; disc.color = if (selected == i) kit.p.stateSelected else kit.p.drawerInk
            c.drawCircle(cx, cy, r, disc)
            glyph.xfermode = clear
            c.drawText("${i + 1}", cx, cy - (glyph.ascent() + glyph.descent()) * .5f, glyph)
            glyph.xfermode = null; c.restoreToCount(saved)
            if (hasFocus()) { disc.style = Paint.Style.STROKE; disc.strokeWidth = kit.dp(1f); disc.color = kit.p.stateSelected; c.drawCircle(cx, cy, kit.dp(13f), disc) }
        }
    }
    private companion object {
        fun checked(h: List<Float>): List<Float> {
            require(h.size == 4 && h.all { it.isFinite() && it in 0f..1f }) { "A response curve needs four finite handles in 0..1" }
            return h.toList()
        }
    }
}
