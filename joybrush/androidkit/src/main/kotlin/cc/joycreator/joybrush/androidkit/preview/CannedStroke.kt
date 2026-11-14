package cc.joycreator.joybrush.androidkit.preview

import cc.joycreator.joybrush.core.brush.TestStroke
import cc.joycreator.joybrush.core.input.PenSample
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The one stroke every brush preview is drawn with, so rows differ only by the brush. Since 2026-11-14 it is the owner's
 * test stroke ([TestStroke], job BRUSH-UI): a swoosh from a feather touch to a hard press, a curl at the end with the pen
 * laid over, and a sudden lift. The samples come from the core, where every engine and the bench share them; this object
 * keeps the preview's seeds and the fill pen's blob. Positions are pixels of the picture, origin top-left.
 */
object CannedStroke {

    /** The same seed every time, as the drawer's older stand-in used (SampleStroke.SEED). */
    const val SEED = 0x5A3F1EL

    /** The scatter stream's salt, as the canvas salts its own (JbCanvasView.SCATTER_SALT). */
    const val SCATTER_SALT = 0x5CA7L

    /** The pen's lean on the swoosh (radians, document space: 0 = +x, y grows down). */
    const val LEAN = TestStroke.LEAN

    /** How far from the picture's edge the pen stays for a brush [sizePx] wide. */
    fun inset(sizePx: Float): Float = TestStroke.inset(sizePx)

    /** The test stroke inside a [width] x [height] picture, for a brush [sizePx] wide, a sample about every [pitchPx]. */
    fun pen(width: Int, height: Int, sizePx: Float, pitchPx: Float = 3f): List<PenSample> = TestStroke.pen(width, height, sizePx, pitchPx)

    /**
     * A small closed blob for the fill pen, [count] corners round in a [width] × [height] picture: wider than tall, a
     * little lumpy so it is plainly drawn and not a stock oval. The last sample is the first, so the shape is closed.
     */
    fun loop(width: Int, height: Int, count: Int = 48): List<PenSample> {
        val ry = height * 0.38f
        val rx = minOf(width * 0.3f, ry * 3f).coerceAtLeast(1f)
        val cx = width / 2f
        val cy = height / 2f
        val out = ArrayList<PenSample>(count + 1)
        for (i in 0..count) {
            val a = (2.0 * PI * (i % count) / count)
            val lump = 1f + 0.10f * sin(3.0 * a).toFloat() + 0.06f * cos(5.0 * a).toFloat()
            out.add(PenSample(cx + rx * lump * cos(a).toFloat(), cy + ry * lump * sin(a).toFloat(), timeMs = i * 8.0))
        }
        return out
    }
}
