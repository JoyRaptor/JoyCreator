package cc.joycreator.joybrush.android.chrome

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import cc.joycreator.joybrush.core.brush.ResponseCurve

/**
 * A response curve with two Bezier handles (owner, 2026-09-30: "adjustable curves for pressure and for tilt, with Bezier
 * handles"). The pen's reading goes in along the bottom, what the brush feels comes out up the side. Drag a handle to
 * bend the curve; [onChange] hears every move, `done` when the finger lifts. [showReading] draws where the pen is right
 * now, on the curve, so the owner can see what a light touch or a 45° lean actually reads as.
 *
 * Square, drawn in the chrome's ink; the handles are 40 dp touch targets, as every control in the chrome is.
 */
@SuppressLint("ViewConstructor")
class CurveEditorView(
    private val kit: ChromeKit,
    private val title: String,
    start: List<Float>,
    private val onChange: (handles: List<Float>, done: Boolean) -> Unit,
) : View(kit.context) {

    private var h = start.toMutableList().also { while (it.size < 4) it.add(0.5f) }
    private var reading = Float.NaN
    private var dragging = -1

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = kit.dp(2f); color = kit.p.drawerInk }
    private val faint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = kit.dp(1f); color = kit.ink(0.3f) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = kit.p.drawerInk }
    private val live = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = kit.p.stateSelected }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = kit.p.drawerInk; textSize = kit.dp(11f) }
    private val path = Path()

    init {
        kit.label(this, "$title curve: drag the two handles to change how the brush hears the pen's $title")
    }

    /** Where the pen reads now, 0..1 along the bottom, or NaN to hide the marker. */
    fun showReading(x: Float) {
        reading = x
        invalidate()
    }

    /** Puts the handles back (Reset). */
    fun setHandles(handles: List<Float>) {
        h = handles.toMutableList().also { while (it.size < 4) it.add(0.5f) }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, w + kit.dpi(TITLE_DP))
    }

    private val pad get() = kit.dp(8f)
    private fun side() = width - 2f * pad
    private fun sx(x: Float) = pad + x * side()
    private fun sy(y: Float) = kit.dp(TITLE_DP) + pad + (1f - y) * side()

    override fun onDraw(c: Canvas) {
        c.drawText(title, pad, kit.dp(TITLE_DP) - kit.dp(3f), text)
        c.drawRect(sx(0f), sy(1f), sx(1f), sy(0f), faint)
        c.drawLine(sx(0f), sy(0f), sx(1f), sy(1f), faint)
        c.drawLine(sx(0f), sy(0f), sx(h[0]), sy(h[1]), faint)
        c.drawLine(sx(1f), sy(1f), sx(h[2]), sy(h[3]), faint)
        path.reset()
        path.moveTo(sx(0f), sy(0f))
        path.cubicTo(sx(h[0]), sy(h[1]), sx(h[2]), sy(h[3]), sx(1f), sy(1f))
        c.drawPath(path, line)
        c.drawCircle(sx(h[0]), sy(h[1]), kit.dp(5f), fill)
        c.drawCircle(sx(h[2]), sy(h[3]), kit.dp(5f), fill)
        if (reading.isFinite()) {
            val x = reading.coerceIn(0f, 1f)
            val y = ResponseCurve.eval(h, x)
            c.drawLine(sx(x), sy(0f), sx(x), sy(y), faint)
            c.drawCircle(sx(x), sy(y), kit.dp(4f), live)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = ((e.x - pad) / side()).coerceIn(0f, 1f)
        val y = (1f - (e.y - kit.dp(TITLE_DP) - pad) / side()).coerceIn(0f, 1f)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val d0 = dist(e.x, e.y, sx(h[0]), sy(h[1]))
                val d2 = dist(e.x, e.y, sx(h[2]), sy(h[3]))
                val reach = kit.dp(ChromeKit.TOUCH_DP / 2f)
                dragging = if (d0 <= d2 && d0 < reach) 0 else if (d2 < reach) 2 else -1
                if (dragging >= 0) parent?.requestDisallowInterceptTouchEvent(true)
                return dragging >= 0
            }
            MotionEvent.ACTION_MOVE -> if (dragging >= 0) {
                h[dragging] = x
                h[dragging + 1] = y
                invalidate()
                onChange(h.toList(), false)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging >= 0) {
                dragging = -1
                onChange(h.toList(), true)
            }
        }
        return true
    }

    private fun dist(ax: Float, ay: Float, bx: Float, by: Float) = Math.hypot((ax - bx).toDouble(), (ay - by).toDouble()).toFloat()

    private companion object {
        const val TITLE_DP = 16f
    }
}

/**
 * What the pen reads, live (owner, 2026-09-30: "a little preview circle that shows tilt — a dot if it's light, and which
 * way it's leaning"). The dot's size is the pressure; its distance from the centre is how far the pen leans, toward the
 * way it leans on the screen. Updated from the canvas's pen readings, hovering included, so the lean shows before the pen
 * even touches.
 */
@SuppressLint("ViewConstructor")
class PenReadingView(private val kit: ChromeKit) : View(kit.context) {

    private var pressure = 0f
    private var tilt = Float.NaN
    private var orientation = 0f

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = kit.dp(1f); color = kit.ink(0.4f) }
    private val lean = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = kit.dp(1.5f); color = kit.p.stateSelected }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = kit.p.drawerInk }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = kit.p.drawerInk; textSize = kit.dp(11f); textAlign = Paint.Align.CENTER }

    init {
        kit.label(this, "What the pen reads: the dot grows with pressure and moves the way the pen leans, further the more it tilts")
    }

    fun show(pressure: Float, tilt: Float, orientation: Float) {
        this.pressure = pressure
        this.tilt = tilt
        this.orientation = orientation
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, w + kit.dpi(28f))
    }

    override fun onDraw(c: Canvas) {
        val r = width / 2f - kit.dp(6f)
        val cx = width / 2f
        val cy = kit.dp(16f) + r
        c.drawText("Pen", cx, kit.dp(12f), text)
        c.drawCircle(cx, cy, r, ring)
        c.drawCircle(cx, cy, r * 0.5f, ring)
        // AXIS_ORIENTATION: 0 = the pen points up the screen, +π/2 = right. The dot sits where the pen's top leans.
        val t = if (tilt.isFinite()) tilt else 0f
        val reach = r * Math.sin(t.toDouble().coerceIn(0.0, Math.PI / 2)).toFloat()
        val dx = (Math.sin(orientation.toDouble()) * reach).toFloat()
        val dy = (-Math.cos(orientation.toDouble()) * reach).toFloat()
        if (reach > 0.5f) c.drawLine(cx, cy, cx + dx, cy + dy, lean)
        c.drawCircle(cx + dx, cy + dy, kit.dp(2f) + kit.dp(8f) * pressure.coerceIn(0f, 1f), dot)
        val tiltText = if (tilt.isFinite()) "${Math.round(Math.toDegrees(t.toDouble()))}°" else "no tilt"
        c.drawText("${Math.round(pressure * 100)}% · $tiltText", cx, cy + r + kit.dp(14f), text)
    }
}
