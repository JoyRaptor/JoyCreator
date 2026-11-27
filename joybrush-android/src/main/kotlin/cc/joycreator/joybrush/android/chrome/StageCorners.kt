package cc.joycreator.joybrush.android.chrome

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/**
 * Rounded corners for a test-stroke stage that is a live drawing surface (BRUSH-UI §L addition 5). A SurfaceView cannot be
 * clipped to a round outline on the Note 9's Android 10, so four small caps are drawn OVER it instead, each a square corner
 * with a quarter circle cut out, in the drawer's own see-through fill: the corner reads as the drawer over paper, the same
 * as the drawer beside it. It takes no touch.
 */
@SuppressLint("ViewConstructor")
class StageCorners(context: Context, private val radiusPx: Float, fill: Int) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = fill }
    private val caps = Path()
    private val arc = RectF()

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val r = radiusPx.coerceAtMost(minOf(w, h) / 2f)
        caps.reset()
        corner(0f, 0f, r, 180f)                           // top left
        corner(w - 2 * r, 0f, r, 270f)                    // top right
        corner(w - 2 * r, h - 2 * r, r, 0f)               // bottom right
        corner(0f, h - 2 * r, r, 90f)                     // bottom left
    }

    /** A corner square of side [r] at the box's corner, less the quarter circle of the box ([x], [y], 2r). */
    private fun corner(x: Float, y: Float, r: Float, start: Float) {
        arc.set(x, y, x + 2 * r, y + 2 * r)
        val cx = if (start == 180f || start == 90f) x else x + 2 * r
        val cy = if (start == 180f || start == 270f) y else y + 2 * r
        caps.moveTo(cx, cy)
        caps.arcTo(arc, start, 90f, false)
        caps.close()
    }

    override fun onDraw(c: Canvas) { c.drawPath(caps, paint) }
}
