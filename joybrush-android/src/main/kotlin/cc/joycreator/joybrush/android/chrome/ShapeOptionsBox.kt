package cc.joycreator.joybrush.android.chrome

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.widget.LinearLayout

/**
 * The box under the pen monitor that holds the chosen shape's own options (owner's mockup, BRUSH-UI §L 5): a thin
 * rounded frame with an arrow in its top edge pointing up at shape A or B. Choosing the other shape moves the arrow.
 * [arrowX] says where the arrow points, in this box's px (null: centred).
 */
@SuppressLint("ViewConstructor")
class ShapeOptionsBox(private val kit: ChromeKit, private val arrowX: () -> Float?) : LinearLayout(kit.context) {

    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = kit.dp(1.2f); color = kit.ink(0.45f) }
    private val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = kit.p.drawerInk }
    private val box = RectF()
    private val tip = Path()

    init {
        orientation = VERTICAL
        setWillNotDraw(false)
        val pad = kit.dpi(8f)
        setPadding(pad, pad + kit.dpi(ARROW_DP), pad, pad)
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val a = kit.dp(ARROW_DP)
        val inset = frame.strokeWidth / 2f
        box.set(inset, a + inset, width - inset, height - inset)
        c.drawRoundRect(box, kit.dp(6f), kit.dp(6f), frame)
        val x = (arrowX() ?: width / 2f).coerceIn(a * 2, width - a * 2)
        tip.reset()
        tip.moveTo(x - a, a + inset)
        tip.lineTo(x + a, a + inset)
        tip.lineTo(x, inset)
        tip.close()
        c.drawPath(tip, arrow)
    }

    private companion object {
        const val ARROW_DP = 7f
    }
}
