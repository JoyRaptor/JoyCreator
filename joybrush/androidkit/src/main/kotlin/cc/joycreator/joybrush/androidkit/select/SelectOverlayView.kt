package cc.joycreator.joybrush.androidkit.select

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.View

/**
 * JB-5.20e — what the Select tool shows that is not the box: the loop being drawn (D8), the glow on the line a tap or a
 * loop would take (D5, D8), the finger's crosshair (D7), the nudge ring (D13), the tip dots (D14, K16) and a paint
 * selection's marching ants (D17). It draws the [tool]'s model and never takes a touch. It sits over the canvas and
 * under the Studio box.
 *
 * Colours are the house state colours: cyan for "what you are pointing at", blue for the bend family (tips).
 */
@SuppressLint("ViewConstructor")
class SelectOverlayView(context: Context, private val tool: SelectTool) : View(context) {

    private val dp = context.resources.displayMetrics.density
    private val path = Path()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dash = DashPathEffect(floatArrayOf(6f * dp, 4f * dp), 0f)

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onTouchEvent(event: android.view.MotionEvent?): Boolean = false

    override fun onDraw(c: Canvas) {
        drawBeforeNudge(c)
        drawGlow(c)
        drawAnts(c)
        drawLoop(c)
        drawRing(c)
        drawTips(c)
        drawCrosshair(c)
    }

    private fun drawBeforeNudge(c: Canvas) {
        val ghosts = tool.nudgeBeforeScreen()
        if (ghosts.isEmpty()) return
        val layer = c.saveLayerAlpha(null, 51)
        line.pathEffect = null
        for (ghost in ghosts) {
            val points = ghost.points
            val widths = ghost.widths
            line.color = ghost.argb
            if (widths.size == 1) {
                line.strokeWidth = maxOf(1f, widths[0])
                c.drawPoint(points[0], points[1], line)
            }
            for (i in 1 until widths.size) {
                line.strokeWidth = maxOf(1f, (widths[i - 1] + widths[i]) / 2f)
                c.drawLine(points[(i - 1) * 2], points[(i - 1) * 2 + 1], points[i * 2], points[i * 2 + 1], line)
            }
        }
        c.restoreToCount(layer)
    }

    /** A cyan rim around each glowing line: the overlay sits over the canvas, so the line's own width is cut back out. */
    private fun drawGlow(c: Canvas) {
        val glows = tool.glowScreen()
        if (glows.isEmpty()) return
        val layer = c.saveLayer(null, null)
        for ((pts, half) in glows) {
            if (pts.size < 2) continue
            polyline(pts, close = false)
            line.pathEffect = null
            line.color = CYAN_GLOW
            line.strokeWidth = half * 2f + 2.6f * dp * 2f
            c.drawPath(path, line)
            line.xfermode = CLEAR
            line.strokeWidth = maxOf(half * 2f, 1f)
            c.drawPath(path, line)
            line.xfermode = null
        }
        c.restoreToCount(layer)
    }

    private fun drawAnts(c: Canvas) {
        val pts = tool.loopScreen() ?: return
        if (pts.size < 6) return
        polyline(pts, close = true)
        line.pathEffect = null
        line.strokeWidth = 1.5f * dp
        line.color = HALO
        c.drawPath(path, line)
        // Marching: the dash phase walks 30 px a second (still when the system asks for reduced motion).
        val phase = if (animationsOff()) 0f else (SystemClock.uptimeMillis() % 1000L) / 1000f * 30f * dp
        line.pathEffect = DashPathEffect(floatArrayOf(6f * dp, 4f * dp), -phase)
        line.color = CYAN
        c.drawPath(path, line)
        line.pathEffect = null
        if (!animationsOff()) postInvalidateOnAnimation()
    }

    private fun drawLoop(c: Canvas) {
        val pts = tool.lassoScreen ?: return
        if (pts.size < 4) return
        polyline(pts, close = false)
        fill.color = CYAN_WASH
        c.drawPath(path, fill)
        line.pathEffect = dash
        line.strokeWidth = 1.5f * dp
        line.color = CYAN
        c.drawPath(path, line)
        line.pathEffect = null
    }

    private fun drawRing(c: Canvas) {
        if (!tool.nudging || tool.selection == null) return
        val at = tool.ringAt ?: tool.hoverAt ?: return
        val r = tool.nudgeRadiusDp * dp
        fill.color = CYAN_WASH
        c.drawCircle(at[0], at[1], r, fill)
        line.strokeWidth = 3f * dp
        line.color = HALO
        c.drawCircle(at[0], at[1], r, line)
        line.strokeWidth = 1.5f * dp
        line.color = CYAN
        c.drawCircle(at[0], at[1], r, line)
    }

    private fun drawTips(c: Canvas) {
        for (p in tool.tipDots()) {
            fill.color = TIP_FILL
            c.drawCircle(p[0], p[1], 7f * dp, fill)
            line.strokeWidth = 1.75f * dp
            line.color = BEND_BLUE
            c.drawCircle(p[0], p[1], 7f * dp, line)
        }
    }

    private fun drawCrosshair(c: Canvas) {
        val q = tool.crosshairAt ?: return
        val stemTop = q[1] + 10f * dp
        line.strokeWidth = 1f * dp
        line.color = CYAN
        c.drawLine(q[0], stemTop, q[0], q[1] + 48f * dp, line)
        line.strokeWidth = 3f * dp
        line.color = HALO
        c.drawCircle(q[0], q[1], 9f * dp, line)
        line.strokeWidth = 1.5f * dp
        line.color = CYAN
        c.drawCircle(q[0], q[1], 9f * dp, line)
        val a = 6f * dp
        val b = 15f * dp
        c.drawLine(q[0] - b, q[1], q[0] - a, q[1], line)
        c.drawLine(q[0] + a, q[1], q[0] + b, q[1], line)
        c.drawLine(q[0], q[1] - b, q[0], q[1] - a, line)
        c.drawLine(q[0], q[1] + a, q[0], q[1] + b, line)
        if (tool.addArmed) {
            line.strokeWidth = 2f * dp
            c.drawLine(q[0] + 13f * dp, q[1] - 15f * dp, q[0] + 21f * dp, q[1] - 15f * dp, line)
            c.drawLine(q[0] + 17f * dp, q[1] - 19f * dp, q[0] + 17f * dp, q[1] - 11f * dp, line)
        }
    }

    private fun polyline(pts: FloatArray, close: Boolean) {
        path.reset()
        path.moveTo(pts[0], pts[1])
        var i = 2
        while (i + 1 < pts.size) { path.lineTo(pts[i], pts[i + 1]); i += 2 }
        if (close) path.close()
    }

    private fun animationsOff(): Boolean =
        android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    private companion object {
        val CLEAR = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
        const val CYAN = 0xFF22D3EE.toInt()
        const val CYAN_GLOW = 0xBF22D3EE.toInt()
        const val CYAN_WASH = 0x1422D3EE
        const val HALO = 0x8C000000.toInt()
        const val BEND_BLUE = 0xFF5AA9FF.toInt()
        const val TIP_FILL = 0x8C0D0D10.toInt()
    }
}
