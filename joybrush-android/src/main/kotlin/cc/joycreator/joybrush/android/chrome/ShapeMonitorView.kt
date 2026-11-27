package cc.joycreator.joybrush.android.chrome

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import cc.joycreator.joybrush.core.brush.QuadPoint
import cc.joycreator.joybrush.core.brush.QuadShapeSpec
import com.fadcam.ui.faditor.Studio
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The pen monitor that is also the Unibrush shape editor (owner's mockup, BRUSH-UI §L 4, 11-27). Inside the ring sit the
 * tip's A and B shapes, turned by the tip's rotation. Tap a shape, or its A / B dot below the ring, to choose it: its
 * four points show as handles to drag. The cyan caret on the ring turns both shapes. The pen's reading (the ball that
 * grows with pressure, the cyan lean, "85% · 4°") is drawn over them as on every brush's monitor.
 *
 * Every drag reports live ([Host.shapeMoved] / [Host.angleMoved] with done = false) and once more on lift (done = true),
 * so the brush's own undo keeps one step per drag.
 */
@SuppressLint("ViewConstructor")
class ShapeMonitorView(kit: ChromeKit, private val host: Host) : PenReadingView(kit) {

    interface Host {
        /** Shape A, or null when the tip has no four-point shape. */
        fun shapeA(): QuadShapeSpec?
        /** Shape B, or null when there is only A. */
        fun shapeB(): QuadShapeSpec?
        /** The tip's rotation, degrees clockwise from upright. */
        fun angleDegrees(): Float
        /** B is the shape being edited. */
        fun bSelected(): Boolean
        /** Choose A or B (choosing B when there is none makes it, as a copy of A). */
        fun select(b: Boolean)
        fun shapeMoved(b: Boolean, shape: QuadShapeSpec, done: Boolean)
        fun angleMoved(degrees: Float, done: Boolean)
    }

    private val fillA = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = kit.ink(0.30f) }
    private val fillB = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = kit.ink(0.50f) }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = kit.dp(1.4f); color = kit.p.drawerInk }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Studio.LIVE }
    private val handleText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textAlign = Paint.Align.CENTER; textSize = kit.dp(7f) }
    private val caret = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = kit.p.stateSelected }
    private val dotText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = kit.p.drawerInk; textAlign = Paint.Align.CENTER; textSize = kit.dp(10f) }
    private val dotRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = kit.dp(1.2f); color = kit.ink(0.6f) }
    private val path = Path()
    private val tri = Path()

    private enum class Drag { NONE, POINT, TURN }
    private var drag = Drag.NONE
    private var point = -1

    init {
        kit.label(this, "Pen and tip shapes: tap A or B to reshape it by its four points; drag the cyan mark on the ring " +
            "to turn the tip. The ball shows the pen's pressure and lean")
    }

    /** Where the shapes' -1..1 square sits: inside the inner part of the ring. */
    private fun half(r: Float) = r * 0.42f

    override fun drawInside(c: Canvas, cx: Float, cy: Float, r: Float) {
        val a = host.shapeA()
        if (a == null) { super.drawInside(c, cx, cy, r); return }
        val b = host.shapeB()
        val turn = host.angleDegrees()
        c.save()
        c.rotate(turn, cx, cy)
        QuadSilhouette.build(path, a, cx, cy, half(r)); c.drawPath(path, fillA)
        if (b != null) { QuadSilhouette.build(path, b, cx, cy, half(r)); c.drawPath(path, fillB) }
        val chosen = if (host.bSelected() && b != null) b else a
        QuadSilhouette.build(path, chosen, cx, cy, half(r)); c.drawPath(path, edge)
        c.restore()
    }

    override fun drawOver(c: Canvas, cx: Float, cy: Float, r: Float) {
        val a = host.shapeA() ?: return
        val chosen = if (host.bSelected()) host.shapeB() ?: a else a
        // The chosen shape's four points, numbered, upright text over the turned shape.
        for ((i, p) in chosen.points.withIndex()) {
            val (x, y) = toScreen(p, cx, cy, r)
            c.drawCircle(x, y, kit.dp(6f), handle)
            c.drawText("${i + 1}", x, y + kit.dp(2.5f), handleText)
        }
        // The rotation caret: a cyan triangle just outside the ring, pointing in, at the tip's rotation.
        val (tx, ty) = caretAt(cx, cy, r)
        val ang = Math.toRadians(host.angleDegrees().toDouble())
        val ox = sin(ang).toFloat(); val oy = -cos(ang).toFloat()
        val s = kit.dp(6f)
        tri.reset()
        tri.moveTo(tx - ox * s * .2f, ty - oy * s * .2f)
        tri.lineTo(tx + ox * s * 1.4f - oy * s, ty + oy * s * 1.4f + ox * s)
        tri.lineTo(tx + ox * s * 1.4f + oy * s, ty + oy * s * 1.4f - ox * s)
        tri.close()
        c.drawPath(tri, caret)
        // The A and B dots, below the ring at its two lower corners.
        drawDot(c, dotAt(false, cx, cy, r), "A", !host.bSelected(), kit.ink(0.55f), true)
        drawDot(c, dotAt(true, cx, cy, r), "B", host.bSelected(), Studio.LIVE, host.shapeB() != null)
    }

    private fun drawDot(c: Canvas, at: Pair<Float, Float>, letter: String, chosen: Boolean, colour: Int, exists: Boolean) {
        val (x, y) = at
        val rad = kit.dp(6f)
        if (chosen) { handle.color = colour; c.drawCircle(x, y, rad, handle); handle.color = Studio.LIVE }
        else c.drawCircle(x, y, rad, dotRing)
        c.drawText(if (exists) letter else "+$letter", x, y - rad - kit.dp(3f), dotText)
    }

    private fun toScreen(p: QuadPoint, cx: Float, cy: Float, r: Float): Pair<Float, Float> {
        val ang = Math.toRadians(host.angleDegrees().toDouble())
        val x = p.x * half(r); val y = p.y * half(r)
        return (cx + (x * cos(ang) - y * sin(ang)).toFloat()) to (cy + (x * sin(ang) + y * cos(ang)).toFloat())
    }

    private fun fromScreen(sx: Float, sy: Float, cx: Float, cy: Float, r: Float): QuadPoint {
        val ang = -Math.toRadians(host.angleDegrees().toDouble())
        val x = sx - cx; val y = sy - cy
        val ux = (x * cos(ang) - y * sin(ang)).toFloat() / half(r)
        val uy = (x * sin(ang) + y * cos(ang)).toFloat() / half(r)
        return QuadPoint(ux.coerceIn(-1f, 1f), uy.coerceIn(-1f, 1f))
    }

    private fun caretAt(cx: Float, cy: Float, r: Float): Pair<Float, Float> {
        val ang = Math.toRadians(host.angleDegrees().toDouble())
        return (cx + sin(ang).toFloat() * r) to (cy - cos(ang).toFloat() * r)
    }

    private fun dotAt(b: Boolean, cx: Float, cy: Float, r: Float): Pair<Float, Float> =
        (cx + (if (b) 1 else -1) * r * 0.86f) to (cy + r * 0.86f)

    /** Where the A or B dot sits, in this view's px (for the options box's arrow). */
    fun dotX(b: Boolean): Float = dotAt(b, ringCenterX(), ringCenterY(), ringRadius()).first

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val a = host.shapeA() ?: return false
        val cx = ringCenterX(); val cy = ringCenterY(); val r = ringRadius()
        val reach = kit.dp(ChromeKit.TOUCH_DP / 2f)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                drag = Drag.NONE; point = -1
                val (tx, ty) = caretAt(cx, cy, r)
                if (hypot(e.x - tx, e.y - ty) < reach) drag = Drag.TURN
                else {
                    val chosen = if (host.bSelected()) host.shapeB() ?: a else a
                    val near = chosen.points.indices.minByOrNull { val (x, y) = toScreen(chosen.points[it], cx, cy, r); hypot(e.x - x, e.y - y) }
                    if (near != null) {
                        val (x, y) = toScreen(chosen.points[near], cx, cy, r)
                        if (hypot(e.x - x, e.y - y) < reach * 0.7f) { drag = Drag.POINT; point = near }
                    }
                }
                if (drag == Drag.NONE) {
                    val (ax, ay) = dotAt(false, cx, cy, r); val (bx, by) = dotAt(true, cx, cy, r)
                    when {
                        hypot(e.x - ax, e.y - ay) < reach -> { host.select(false); invalidate(); return true }
                        hypot(e.x - bx, e.y - by) < reach -> { host.select(true); invalidate(); return true }
                        hypot(e.x - cx, e.y - cy) < r * 0.6f && host.shapeB() != null -> {
                            // A tap on the shapes picks the one not being edited, so a tap is always a change.
                            host.select(!host.bSelected()); invalidate(); return true
                        }
                        else -> return false
                    }
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                val done = e.actionMasked == MotionEvent.ACTION_UP
                when (drag) {
                    Drag.TURN -> {
                        val deg = Math.toDegrees(atan2((e.x - cx).toDouble(), (cy - e.y).toDouble())).toFloat()
                        host.angleMoved(((deg + 540f) % 360f) - 180f, done)
                    }
                    Drag.POINT -> {
                        val b = host.bSelected() && host.shapeB() != null
                        val shape = (if (b) host.shapeB() else a) ?: a
                        val pts = shape.points.toMutableList()
                        pts[point] = fromScreen(e.x, e.y, cx, cy, r)
                        host.shapeMoved(b, shape.copy(points = pts), done)
                    }
                    Drag.NONE -> {}
                }
                if (done) drag = Drag.NONE
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> { drag = Drag.NONE; return true }
        }
        return true
    }
}
