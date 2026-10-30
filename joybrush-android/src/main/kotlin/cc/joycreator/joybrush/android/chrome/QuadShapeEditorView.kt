package cc.joycreator.joybrush.android.chrome

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityViewCommand
import cc.joycreator.joybrush.core.brush.QuadEditorGeometry
import cc.joycreator.joybrush.core.brush.QuadShapeSpec
import cc.joycreator.joybrush.core.brush.QuadMath
import cc.joycreator.joybrush.core.brush.TipQuads
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * DRAFT — not compiled or tested. Four editable shape points and one facing-angle handle.
 * A/B tabs and the real-engine stroke preview belong to the containing sheet. This view draws
 * only the normalized shape; aspect/pressure/morph are deliberately not baked into its points.
 * Each handle is a real, focusable44dp View, with native tooltip and accessibility actions.
 */
@SuppressLint("ViewConstructor")
class QuadShapeEditorView(
    private val kit: ChromeKit,
    start: QuadShapeSpec,
    facingDegrees: Float = 0f,
    private val facingEnabled: Boolean = true,
    private val onChange: (shape: QuadShapeSpec, facingDegrees: Float, done: Boolean) -> Unit,
) : FrameLayout(kit.context) {
    private var shape = snapshot(start)
    private var facing = QuadEditorGeometry.degrees(facingDegrees)
    private var geometry: QuadEditorGeometry? = null
    private var selected = 0
    private var dragging = -1
    private var pointer = MotionEvent.INVALID_POINTER_ID
    private var grabX = 0f
    private var grabY = 0f
    private var originalShape = shape
    private var originalFacing = facing
    private var changedDuringGesture = false
    private val handles = ArrayList<HandleView>(5)
    private var prepared = QuadMath.prepare(TipQuads.interpolate(shape, null, 0f))
    private val silhouette = Path().apply { fillType = Path.FillType.EVEN_ODD }
    private val faint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = kit.ink(.25f); style = Paint.Style.STROKE; strokeWidth = kit.dp(1f)
    }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = kit.p.drawerInk; style = Paint.Style.STROKE; strokeWidth = kit.dp(1.5f)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = kit.ink(.12f) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = kit.p.drawerInk; textSize = kit.dp(11f) }

    init {
        setWillNotDraw(false)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        for (index in 0..4) {
            val handle = HandleView(index)
            handles += handle
            addView(handle, LayoutParams(kit.dpi(TARGET_DP), kit.dpi(TARGET_DP)))
        }
        updateDescriptions()
    }

    /** Programmatic update/reset. Echoing the live callback does not interrupt its drag. */
    fun setShape(value: QuadShapeSpec, facingDegrees: Float) {
        val next = snapshot(value)
        val nextFacing = QuadEditorGeometry.degrees(facingDegrees)
        if (shape == next && facing == nextFacing) return
        cancelDrag(notify = false)
        shape = next; facing = nextFacing
        refreshGeometry()
    }

    fun currentShape(): QuadShapeSpec = snapshot(shape)
    fun currentFacingDegrees(): Float = facing
    /** The host may commit an in-flight drag before closing/switching A/B. Detach otherwise cancels it. */
    fun finishEditing() { if (dragging >= 0) finishDrag() }

    override fun setEnabled(enabled: Boolean) {
        if (!enabled) cancelDrag(notify = true)
        super.setEnabled(enabled)
        for ((index, child) in handles.withIndex()) child.isEnabled = enabled && geometry != null && (facingEnabled || index != 4)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = resolveSizeAndState(kit.dpi(288f), widthMeasureSpec, 0)
        val height = resolveSizeAndState((width and MEASURED_SIZE_MASK) + kit.dpi(TITLE_DP), heightMeasureSpec, 0)
        setMeasuredDimension(width, height)
        val spec = MeasureSpec.makeMeasureSpec(kit.dpi(TARGET_DP), MeasureSpec.EXACTLY)
        for (handle in handles) handle.measure(spec, spec)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        geometry = QuadEditorGeometry.fit(paddingLeft.toFloat(), paddingTop + kit.dp(TITLE_DP),
            (width - paddingLeft - paddingRight).toFloat(), height - paddingTop - paddingBottom - kit.dp(TITLE_DP),
            kit.dp(TARGET_DP * .5f), kit.dp(24f))
        val g = geometry
        for (index in handles.indices) {
            val handle = handles[index]
            handle.layout(0, 0, handle.measuredWidth, handle.measuredHeight)
            handle.visibility = if (index == 4 && !facingEnabled) GONE else if (g == null) INVISIBLE else VISIBLE
            handle.isEnabled = isEnabled && g != null && (facingEnabled || index != 4)
            if (g != null) {
                val p = if (index == 4) g.arrow(facing) else g.toView(shape.points[index])
                // Fractional translations keep Canvas coordinates and touch/accessibility bounds aligned.
                handle.translationX = p.x - handle.measuredWidth * .5f
                handle.translationY = p.y - handle.measuredHeight * .5f
            }
        }
        rebuildPath()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawText(if (facingEnabled) "Tip shape · drag points or facing arrow" else "Secondary shape · drag four points", paddingLeft + kit.dp(8f), paddingTop + kit.dp(15f), text)
        val g = geometry ?: return
        canvas.drawRect(g.centerX - g.halfSide, g.centerY - g.halfSide,
            g.centerX + g.halfSide, g.centerY + g.halfSide, faint)
        canvas.drawLine(g.centerX - g.halfSide, g.centerY, g.centerX + g.halfSide, g.centerY, faint)
        canvas.drawLine(g.centerX, g.centerY - g.halfSide, g.centerX, g.centerY + g.halfSide, faint)
        for (i in 0..3) {
            val a = g.toView(shape.points[i]); val b = g.toView(shape.points[(i + 1) % 4])
            canvas.drawLine(a.x, a.y, b.x, b.y, faint)
        }
        canvas.drawPath(silhouette, fill)
        canvas.drawPath(silhouette, outline)
        if (facingEnabled) {
            canvas.drawCircle(g.centerX, g.centerY, g.arrowRadius, faint)
            val head = g.arrow(facing)
            canvas.drawLine(g.centerX, g.centerY, head.x, head.y, outline)
        }
    }

    private fun rebuildPath() {
        silhouette.reset(); silhouette.fillType = Path.FillType.EVEN_ODD
        val g = geometry ?: return
        if (prepared.empty) return
        fun px(x: Double) = g.centerX + (x * g.halfSide).toFloat()
        fun py(y: Double) = g.centerY + (y * g.halfSide).toFloat()
        val corners = prepared.corners
        val first = corners.first()
        silhouette.moveTo(px(first.inX), py(first.inY))
        for (i in corners.indices) {
            val corner = corners[i]
            if (!corner.rounded) silhouette.lineTo(px(corner.outX), py(corner.outY))
            else {
                val start = atan2(corner.unitInY, corner.unitInX)
                var sweep = atan2(corner.unitOutY, corner.unitOutX) - start
                while (sweep > PI) sweep -= 2 * PI
                while (sweep < -PI) sweep += 2 * PI
                if (abs(abs(sweep) - PI) < 1e-10) sweep = corner.arcSign * PI
                // <=45deg cubic arcs give subpixel error and avoid enormous arcTo rectangles
                // at nearly collinear vertices. Controls are calculated from stable endpoints.
                val steps = ceil(abs(sweep) / (PI / 4)).toInt().coerceAtLeast(1)
                for (step in 0 until steps) {
                    val a = start + sweep * step / steps
                    val b = start + sweep * (step + 1) / steps
                    val k = 4.0 / 3 * tan((b - a) / 4) * corner.radius
                    val ax = corner.inX + corner.radius * (cos(a) - corner.unitInX)
                    val ay = corner.inY + corner.radius * (sin(a) - corner.unitInY)
                    val bx = if (step + 1 == steps) corner.outX else corner.inX + corner.radius * (cos(b) - corner.unitInX)
                    val by = if (step + 1 == steps) corner.outY else corner.inY + corner.radius * (sin(b) - corner.unitInY)
                    silhouette.cubicTo(px(ax - sin(a) * k), py(ay + cos(a) * k),
                        px(bx + sin(b) * k), py(by - cos(b) * k), px(bx), py(by))
                }
            }
            val next = corners[(i + 1) % corners.size]
            silhouette.lineTo(px(next.inX), py(next.inY))
        }
        silhouette.close()
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        if (dragging >= 0) return true
        return event.actionMasked == MotionEvent.ACTION_DOWN &&
            geometry?.pick(event.x, event.y, shape, facing, kit.dp(TARGET_DP * .5f), selected, includeArrow = facingEnabled)?.let { it >= 0 } == true
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        val g = geometry ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = g.pick(event.x, event.y, shape, facing, kit.dp(TARGET_DP * .5f), selected, includeArrow = facingEnabled)
                if (hit < 0) return false
                dragging = hit; pointer = event.getPointerId(0)
                originalShape = snapshot(shape); originalFacing = facing; changedDuringGesture = false
                val point = if (hit == 4) g.arrow(facing) else g.toView(shape.points[hit])
                grabX = event.x - point.x; grabY = event.y - point.y
                select(hit); handles[hit].requestFocus()
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                if (dragging < 0) return false
                val index = event.findPointerIndex(pointer)
                if (index < 0) { cancelDrag(notify = true); return true }
                val x = event.getX(index) - grabX; val y = event.getY(index) - grabY
                if (x.isFinite() && y.isFinite()) {
                    val nextShape: QuadShapeSpec
                    val nextFacing: Float
                    if (dragging == 4) {
                        nextShape = shape; nextFacing = g.facing(x, y, facing, kit.dp(8f))
                    } else {
                        val point = g.toPoint(x, y)
                        nextShape = QuadEditorGeometry.move(shape, dragging, point.x, point.y); nextFacing = facing
                    }
                    if (nextShape != shape || nextFacing != facing) {
                        shape = nextShape; facing = nextFacing; changedDuringGesture = true
                        refreshGeometry()
                        onChange(snapshot(shape), facing, false)
                    }
                }
                if (event.actionMasked == MotionEvent.ACTION_UP) finishDrag()
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val wasDragging = dragging >= 0
                if (dragging >= 0 && event.getPointerId(event.actionIndex) == pointer) finishDrag()
                return wasDragging
            }
            MotionEvent.ACTION_CANCEL -> { if (dragging < 0) return false; cancelDrag(notify = true); return true }
        }
        return dragging >= 0
    }

    private fun finishDrag() {
        val handle = dragging
        dragging = -1; pointer = MotionEvent.INVALID_POINTER_ID
        parent?.requestDisallowInterceptTouchEvent(false)
        if (changedDuringGesture) onChange(snapshot(shape), facing, true)
        else if (handle >= 0) handles[handle].performClick()
        changedDuringGesture = false
    }

    private fun cancelDrag(notify: Boolean) {
        if (dragging < 0) return
        dragging = -1; pointer = MotionEvent.INVALID_POINTER_ID
        parent?.requestDisallowInterceptTouchEvent(false)
        if (changedDuringGesture) {
            shape = originalShape; facing = originalFacing
            refreshGeometry()
            if (notify) onChange(snapshot(shape), facing, true)
        }
        changedDuringGesture = false
    }

    private fun select(index: Int) {
        selected = index
        handles[index].bringToFront()
        handles.forEach { it.invalidate() }
    }

    private fun nudge(index: Int, dx: Int, dy: Int, fine: Boolean = false): Boolean {
        if (!isEnabled || geometry == null || (index == 4 && !facingEnabled)) return false
        select(index)
        val oldShape = shape; val oldFacing = facing
        if (index == 4) facing = QuadEditorGeometry.degrees(facing + (if (dx != 0) dx else dy) * (if (fine) 1f else 5f))
        else {
            val p = shape.points[index]; val step = if (fine) .005f else .02f
            shape = QuadEditorGeometry.move(shape, index, p.x + dx * step, p.y + dy * step)
        }
        if (shape != oldShape || facing != oldFacing) {
            refreshGeometry(); onChange(snapshot(shape), facing, true)
        }
        return true
    }

    private fun refreshGeometry() {
        prepared = QuadMath.prepare(TipQuads.interpolate(shape, null, 0f))
        rebuildPath(); updateDescriptions(); requestLayout(); invalidate()
    }

    private fun updateDescriptions() {
        for (i in handles.indices) {
            val state = if (i == 4) "${Math.round(facing)} degrees"
            else "x ${Math.round(shape.points[i].x * 100)} percent, y ${Math.round(shape.points[i].y * 100)} percent"
            ViewCompat.setStateDescription(handles[i], state)
        }
    }

    override fun onDetachedFromWindow() { cancelDrag(notify = true); super.onDetachedFromWindow() }

    private inner class HandleView(private val index: Int) : View(kit.context) {
        private val disc = Paint(Paint.ANTI_ALIAS_FLAG)
        private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER; textSize = kit.dp(10f); strokeWidth = kit.dp(1.8f)
        }
        private val clear = PorterDuffXfermode(PorterDuff.Mode.CLEAR)

        init {
            isFocusable = true; isFocusableInTouchMode = true; isClickable = true
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            kit.label(this, if (index == 4) "Facing arrow. Drag to rotate the tip, or use rotate actions."
                else "Tip point ${index + 1}. Drag to reshape, or use move left, right, up and down actions.")
            setOnClickListener { select(index) }
            fun action(label: String, dx: Int, dy: Int) {
                ViewCompat.addAccessibilityAction(this, label, AccessibilityViewCommand { _, _ -> nudge(index, dx, dy) })
            }
            if (index == 4) { action("Rotate counterclockwise five degrees", -1, 0); action("Rotate clockwise five degrees", 1, 0) }
            else {
                action("Move left", -1, 0); action("Move right", 1, 0)
                action("Move up", 0, -1); action("Move down", 0, 1)
            }
        }

        override fun onFocusChanged(gain: Boolean, direction: Int, previous: android.graphics.Rect?) {
            super.onFocusChanged(gain, direction, previous)
            if (gain) select(index)
            invalidate()
        }

        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.className = "android.widget.Button"
            info.isSelected = index == selected
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        }

        override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean = when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> nudge(index, 1, 0)
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> nudge(index, -1, 0)
            else -> super.performAccessibilityAction(action, arguments)
        }

        override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> nudge(index, -1, 0, event.isShiftPressed)
            KeyEvent.KEYCODE_DPAD_RIGHT -> nudge(index, 1, 0, event.isShiftPressed)
            KeyEvent.KEYCODE_DPAD_UP -> nudge(index, 0, -1, event.isShiftPressed)
            KeyEvent.KEYCODE_DPAD_DOWN -> nudge(index, 0, 1, event.isShiftPressed)
            else -> super.onKeyDown(keyCode, event)
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width * .5f; val cy = height * .5f; val radius = kit.dp(9f)
            val chosen = selected == index
            disc.style = Paint.Style.FILL; disc.color = if (chosen) kit.p.stateSelected else kit.p.drawerInk
            disc.alpha = if (isEnabled) 255 else 100
            val save = canvas.saveLayer(cx - radius - 1, cy - radius - 1, cx + radius + 1, cy + radius + 1, null)
            canvas.drawCircle(cx, cy, radius, disc)
            glyph.xfermode = clear
            if (index == 4) {
                canvas.drawLine(cx - kit.dp(4f), cy, cx + kit.dp(4f), cy, glyph)
                canvas.drawLine(cx + kit.dp(4f), cy, cx, cy - kit.dp(4f), glyph)
                canvas.drawLine(cx + kit.dp(4f), cy, cx, cy + kit.dp(4f), glyph)
            } else canvas.drawText("${index + 1}", cx, cy - (glyph.ascent() + glyph.descent()) * .5f, glyph)
            glyph.xfermode = null; canvas.restoreToCount(save)
            if (hasFocus()) {
                disc.style = Paint.Style.STROKE; disc.strokeWidth = kit.dp(1f); disc.color = kit.p.stateSelected
                canvas.drawCircle(cx, cy, kit.dp(13f), disc)
            }
        }
    }

    private companion object {
        const val TARGET_DP = 44f
        const val TITLE_DP = 24f
        fun snapshot(value: QuadShapeSpec): QuadShapeSpec {
            require(value.points.size == 4 && value.points.all { it.x.isFinite() && it.y.isFinite() && it.x in -1f..1f && it.y in -1f..1f })
            require(value.roundness.isFinite() && value.roundness in 0f..1f)
            return value.copy(points = value.points.toList())
        }
    }
}
