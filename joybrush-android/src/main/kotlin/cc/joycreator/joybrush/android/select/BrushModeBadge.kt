package cc.joycreator.joybrush.android.select

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import cc.joycreator.joybrush.android.chrome.ChromeKit

/** K25: a 12 dp current-mode mark, drawn in the drawer's normal ink. */
@SuppressLint("ViewConstructor")
class BrushModeBadge(private val kit: ChromeKit, val editableLines: Boolean) : View(kit.context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init { kit.label(this, if (editableLines) LINES_LABEL else PIXELS_LABEL) }

    override fun onDraw(canvas: Canvas) {
        drawMark(canvas, paint, editableLines, kit.p.drawerInk, 0f, 0f, width.toFloat())
    }

    companion object {
        private val CURVE = Path().apply { moveTo(2.5f, 9.5f); cubicTo(2.5f, 4f, 9.5f, 8f, 9.5f, 2.5f) }

        /**
         * The mark itself, in a [size]-wide square at ([left], [top]): a curve with two handles for lines, a stair of dots
         * for pixels. Shared by the drawer's badge and the strip's tool buttons (11-27), so the two always read alike.
         */
        fun drawMark(canvas: Canvas, paint: Paint, editableLines: Boolean, ink: Int, left: Float, top: Float, size: Float) {
            val saved = canvas.save()
            canvas.translate(left, top)
            canvas.scale(size / 12f, size / 12f)
            paint.shader = null
            paint.color = ink
            paint.alpha = (Color.alpha(ink) * .8f).toInt()
            if (editableLines) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.3f
                paint.strokeCap = Paint.Cap.ROUND
                canvas.drawPath(CURVE, paint)
                paint.style = Paint.Style.FILL
                canvas.drawRect(1.4f, 8.4f, 3.6f, 10.6f, paint)
                canvas.drawRect(8.4f, 1.4f, 10.6f, 3.6f, paint)
            } else {
                paint.style = Paint.Style.FILL
                for ((x, y) in DOTS) canvas.drawCircle(x, y, 1.3f, paint)
            }
            canvas.restoreToCount(saved)
        }

        const val LINES_LABEL = "Makes editable lines"
        const val PIXELS_LABEL = "Makes pixels: this brush blends with what's under it, so its strokes can't be picked as lines."
        private val DOTS = listOf(10f to 2f, 10f to 6f, 10f to 10f, 6f to 6f, 6f to 10f, 2f to 10f)
    }
}
