package cc.joycreator.joybrush.android.chrome

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.text.TextUtils
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityViewCommand
import cc.joycreator.joybrush.core.brush.FalloffCurveEditing
import cc.joycreator.joybrush.core.brush.FalloffEditorGeometry
import cc.joycreator.joybrush.core.brush.TipProfileSpec
import cc.joycreator.joybrush.core.brush.TipProfiles
import java.util.Locale

/**
 * DRAFT — not compiled/tested. Edits the actual saved custom points, in private until Done.
 * There is one focusable 44dp active handle; Previous/Next reaches every dense/overlapping point.
 * Host owns the dialog and supplies the final editProfilePoints callback; no engine/history calls.
 */
@SuppressLint("ViewConstructor")
class TipProfilePointEditorView(
    private val kit: ChromeKit,
    start: TipProfileSpec,
    private val onDone: (TipProfileSpec) -> Unit,
    private val onCancel: () -> Unit,
) : LinearLayout(kit.context) {
    private val initial = FalloffCurveEditing.checked(start)
    private var model = initial
    private var selected = 0
    private var closed = false
    private val readout = TextView(context).apply { textSize = 12f; setTextColor(kit.p.drawerInk) }
    private val error = TextView(context).apply {
        textSize = 12f; setTextColor(kit.p.drawerInk); maxLines = 3; ellipsize = TextUtils.TruncateAt.END; visibility = GONE
        ViewCompat.setAccessibilityLiveRegion(this, ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE)
    }
    private val graph = Graph()
    private val previous = command("Previous", "Select the previous stored falloff point") { navigate(-1) }
    private val next = command("Next", "Select the next stored falloff point") { navigate(1) }
    private val add = command("Add", "Add a point halfway after the selected point; at the rim use the previous gap") {
        finishEditing()
        attempt { FalloffCurveEditing.addAfter(model, selected).let { model = it.profile; selected = it.selected } }
    }
    private val remove = command("Remove", "Remove the selected interior falloff point; endpoints stay") {
        finishEditing()
        attempt { FalloffCurveEditing.remove(model, selected).let { model = it.profile; selected = it.selected } }
    }

    init {
        orientation = VERTICAL
        setPadding(kit.dpi(8f), kit.dpi(8f), kit.dpi(8f), kit.dpi(8f))
        kit.surface(this, 12f)
        addView(TextView(context).apply {
            text = "Custom falloff points"; textSize = 16f; setTextColor(kit.p.drawerInk); kit.label(this, text.toString())
        }, LayoutParams(MATCH, WRAP))
        addView(TextView(context).apply {
            text = "Radius → · Coverage ↑ · ${FalloffCurveEditing.maxPoints} points maximum. The rim stays at zero."
            textSize = 11f; setTextColor(kit.p.drawerInk); kit.label(this, text.toString())
        }, LayoutParams(MATCH, WRAP))
        addView(graph, LayoutParams(MATCH, 0, 1f))
        addView(readout, LayoutParams(MATCH, kit.dpi(44f)))
        val actions = LinearLayout(context).apply { orientation = HORIZONTAL }
        listOf(previous, next, add, remove).forEach { actions.addView(it, LayoutParams(0, kit.dpi(44f), 1f)) }
        addView(actions, LayoutParams(MATCH, WRAP))
        addView(error, LayoutParams(MATCH, WRAP))
        val closing = LinearLayout(context).apply { orientation = HORIZONTAL }
        closing.addView(command("Cancel", "Discard all point edits and return to the unchanged falloff") { cancelEditing() }, LayoutParams(0, kit.dpi(44f), 1f))
        closing.addView(command("Done", "Use this custom falloff and close the point editor") { commit() }, LayoutParams(0, kit.dpi(44f), 1f))
        addView(closing, LayoutParams(MATCH, WRAP))
        refresh()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(heightMeasureSpec).takeIf { MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED }
            ?: resources.displayMetrics.heightPixels
        val height = minOf(available, (resources.displayMetrics.heightPixels * .70f).toInt())
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
    }

    /** Selecting/navigating never modifies a stored coordinate. */
    fun currentProfile(): TipProfileSpec = FalloffCurveEditing.snapshot(model)
    fun finishEditing() { graph.finishGesture(cancel = false) }

    fun cancelEditing() {
        if (closed) return
        graph.finishGesture(cancel = true)
        model = initial; selected = 0; refresh()
        closed = true; onCancel()
    }

    private fun commit() {
        if (closed) return
        finishEditing()
        try {
            val result = FalloffCurveEditing.checked(model)
            closed = true // Dismissal from inside the callback cannot return a second Cancel result.
            onDone(result) // Host should refuse before publishing if this callback can fail.
        } catch (e: Exception) { closed = false; showError(e.message ?: "The falloff could not be applied") }
    }

    private fun command(caption: String, label: String, run: () -> Unit) = UnibrushSheetChip(kit, caption, label) {
        if (!closed) run()
    }
    private fun navigate(delta: Int) {
        finishEditing(); select((selected + delta).coerceIn(0, model.curve.lastIndex))
    }
    private fun select(index: Int) { selected = index; refresh() }
    private fun attempt(edit: () -> Unit) {
        if (closed) return
        try { edit(); error.visibility = GONE; refresh() }
        catch (e: Exception) { showError(e.message ?: "That point could not be changed") }
    }
    private fun showError(message: String) { error.text = message; kit.label(error, message); error.visibility = VISIBLE }
    private fun refresh() {
        val p = model.curve[selected]
        val suffix = when (selected) { 0 -> " · radius fixed"; model.curve.lastIndex -> " · fixed rim"; else -> "" }
        val state = String.format(Locale.US, "Point %d of %d · radius %.6g · coverage %.6g%s", selected + 1, model.curve.size, p[0], p[1], suffix)
        readout.text = state; kit.label(readout, state)
        previous.isEnabled = selected > 0; next.isEnabled = selected < model.curve.lastIndex
        add.isEnabled = model.curve.size < FalloffCurveEditing.maxPoints
        remove.isEnabled = selected > 0 && selected < model.curve.lastIndex
        graph.refreshProfile()
    }
    private fun nudge(dx: Int, dy: Int, fine: Boolean = false): Boolean {
        if (closed) return false
        finishEditing()
        val p = model.curve[selected]; val step = if (fine) .001f else .01f
        attempt { model = FalloffCurveEditing.move(model, selected, p[0] + dx * step, p[1] + dy * step) }
        return true
    }

    override fun onDetachedFromWindow() {
        graph.finishGesture(cancel = true)
        super.onDetachedFromWindow()
    }

    private inner class Graph : FrameLayout(kit.context) {
        private var geometry: FalloffEditorGeometry? = null
        private var samples = TipProfiles.samples(model)
        private var dragging = false
        private var pointer = MotionEvent.INVALID_POINTER_ID
        private var before = model
        private var beforeSelected = selected
        private var grabX = 0f
        private var grabY = 0f
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        private val handle = ActiveHandle()
        init {
            setWillNotDraw(false)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            kit.label(this, "Custom falloff curve. Tap near a stored point to select it, or use Previous and Next; drag the selected point to edit")
            addView(handle, LayoutParams(kit.dpi(44f), kit.dpi(44f)))
        }
        fun refreshProfile() {
            samples = TipProfiles.samples(model)
            kit.label(handle, "Selected falloff point ${selected + 1}. Drag or use directional move actions. Previous and Next select every stored point.")
            val p = model.curve[selected]
            ViewCompat.setStateDescription(handle, "Radius ${p[0]}, coverage ${p[1]}" +
                if (selected == model.curve.lastIndex) ", fixed rim" else if (selected == 0) ", radius fixed" else "")
            handle.invalidate(); requestLayout(); invalidate()
        }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            val exact = MeasureSpec.makeMeasureSpec(kit.dpi(44f), MeasureSpec.EXACTLY)
            handle.measure(exact, exact)
        }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            geometry = FalloffEditorGeometry.fit(width.toFloat(), height.toFloat(), kit.dp(22f))
            handle.layout(0, 0, handle.measuredWidth, handle.measuredHeight)
            val g = geometry
            handle.visibility = if (g == null) INVISIBLE else VISIBLE
            if (g != null) {
                val p = g.position(model.curve[selected])
                handle.translationX = p.x - handle.measuredWidth * .5f
                handle.translationY = p.y - handle.measuredHeight * .5f
            }
        }
        override fun onDraw(c: Canvas) {
            val g = geometry ?: return
            paint.color = kit.ink(.22f); paint.style = Paint.Style.STROKE; paint.strokeWidth = kit.dp(1f)
            c.drawRect(g.left, g.top, g.left + g.width, g.top + g.height, paint)
            for (i in 1..3) {
                c.drawLine(g.left + g.width * i / 4f, g.top, g.left + g.width * i / 4f, g.top + g.height, paint)
                c.drawLine(g.left, g.top + g.height * i / 4f, g.left + g.width, g.top + g.height * i / 4f, paint)
            }
            // Stored control polygon, with every original point visible; no input resampling.
            path.reset()
            model.curve.forEachIndexed { i, point ->
                val p = g.position(point)
                if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            c.drawPath(path, paint)
            // Exact immutable 256-entry custom coverage LUT, connected as the engine interpolates it.
            path.reset()
            samples.forEachIndexed { i, y ->
                val px = g.left + g.width * i / 255f; val py = g.top + (1f - y) * g.height
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            paint.color = kit.p.drawerInk; paint.strokeWidth = kit.dp(2f); c.drawPath(path, paint)
            paint.style = Paint.Style.FILL
            model.curve.forEach { point -> val p = g.position(point); c.drawCircle(p.x, p.y, kit.dp(2.5f), paint) }
        }
        override fun onInterceptTouchEvent(e: MotionEvent): Boolean = !closed &&
            (dragging || (e.actionMasked == MotionEvent.ACTION_DOWN && geometry?.nearest(model, e.x, e.y, kit.dp(22f), selected)?.let { it >= 0 } == true))

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (closed) return false
            val g = geometry ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val hit = g.nearest(model, e.x, e.y, kit.dp(22f), selected)
                    if (hit < 0) return false
                    before = model; beforeSelected = selected
                    select(hit)
                    val p = g.position(model.curve[hit]); grabX = e.x - p.x; grabY = e.y - p.y
                    dragging = true; pointer = e.getPointerId(0); handle.requestFocus()
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                    if (!dragging) return false
                    val index = e.findPointerIndex(pointer)
                    if (index < 0) { finishGesture(cancel = true); return true }
                    val x = e.getX(index) - grabX; val y = e.getY(index) - grabY
                    if (x.isFinite() && y.isFinite()) attempt {
                        model = FalloffCurveEditing.move(model, selected, g.radius(x), g.coverage(y))
                    }
                    if (e.actionMasked == MotionEvent.ACTION_UP) finishGesture(cancel = false)
                    return true
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    if (dragging && e.getPointerId(e.actionIndex) == pointer) finishGesture(cancel = false)
                    return true
                }
                MotionEvent.ACTION_CANCEL -> { if (!dragging) return false; finishGesture(cancel = true); return true }
            }
            return dragging
        }
        fun finishGesture(cancel: Boolean) {
            if (!dragging) return
            dragging = false; pointer = MotionEvent.INVALID_POINTER_ID
            parent?.requestDisallowInterceptTouchEvent(false)
            if (cancel) { model = before; selected = beforeSelected; refresh() }
        }

        private inner class ActiveHandle : View(kit.context) {
            private val disc = Paint(Paint.ANTI_ALIAS_FLAG)
            private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = kit.dp(10f); textAlign = Paint.Align.CENTER }
            private val clear = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            init {
                isFocusable = true; isFocusableInTouchMode = true; isClickable = true; isSelected = true
                setOnClickListener { /* Selection is explicit; clicking never changes coordinates. */ }
                fun action(name: String, dx: Int, dy: Int) {
                    ViewCompat.addAccessibilityAction(this, name, AccessibilityViewCommand { _, _ -> nudge(dx, dy) })
                }
                action("Move radius left", -1, 0); action("Move radius right", 1, 0)
                action("Increase coverage", 0, 1); action("Decrease coverage", 0, -1)
            }
            override fun onFocusChanged(gain: Boolean, direction: Int, old: Rect?) { super.onFocusChanged(gain, direction, old); invalidate() }
            override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(info)
                info.className = "android.widget.Button"; info.isSelected = true
            }
            override fun onKeyDown(code: Int, event: KeyEvent): Boolean = when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> nudge(-1, 0, event.isShiftPressed)
                KeyEvent.KEYCODE_DPAD_RIGHT -> nudge(1, 0, event.isShiftPressed)
                KeyEvent.KEYCODE_DPAD_UP -> nudge(0, 1, event.isShiftPressed)
                KeyEvent.KEYCODE_DPAD_DOWN -> nudge(0, -1, event.isShiftPressed)
                else -> super.onKeyDown(code, event)
            }
            override fun onDraw(c: Canvas) {
                val cx = width * .5f; val cy = height * .5f; val radius = kit.dp(10f)
                val saved = c.saveLayer(cx - radius - 1f, cy - radius - 1f, cx + radius + 1f, cy + radius + 1f, null)
                disc.style = Paint.Style.FILL; disc.color = kit.p.stateSelected
                c.drawCircle(cx, cy, radius, disc)
                glyph.xfermode = clear
                c.drawText("${selected + 1}", cx, cy - (glyph.ascent() + glyph.descent()) * .5f, glyph)
                glyph.xfermode = null; c.restoreToCount(saved)
                if (hasFocus()) {
                    disc.style = Paint.Style.STROKE; disc.strokeWidth = kit.dp(1f)
                    c.drawCircle(cx, cy, kit.dp(14f), disc)
                }
            }
        }
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
