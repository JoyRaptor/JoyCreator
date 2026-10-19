package cc.joycreator.joybrush.androidkit.preview

import cc.joycreator.joybrush.core.input.PenSample
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The one stroke every brush preview is drawn with, so rows differ only by the brush: a gentle S from left to right, the
 * pressure swelling from light to full and back, the pen tilted a little (more at the swell) and leaning up and to the
 * left as a right hand holds it, at a steady walking speed. Pure and repeatable: the same box always gives the same
 * samples, so a preview never shimmers between renders. Positions are pixels of the picture, origin top-left.
 */
object CannedStroke {

    /** The same seed every time, as the drawer's older stand-in used (SampleStroke.SEED). */
    const val SEED = 0x5A3F1EL

    /** The scatter stream's salt, as the canvas salts its own (JbCanvasView.SCATTER_SALT). */
    const val SCATTER_SALT = 0x5CA7L

    /** The pen leans up and to the left (radians, document space: 0 = +x, y grows down). */
    const val LEAN = (-0.75 * PI).toFloat()

    /** Speed along the path: pixels per millisecond (about a relaxed stroke). */
    const val SPEED_PX_PER_MS = 1.2f

    private const val MIN_SAMPLES = 24
    private const val MAX_SAMPLES = 600

    /** How far from the picture's edge the pen stays for a brush [sizePx] wide. */
    fun inset(sizePx: Float): Float = sizePx / 2f + 2f

    /**
     * The S inside a [width] × [height] picture, for a brush [sizePx] wide, a sample about every [pitchPx]. The first
     * sample is at pressure 0.25 and tilt 0.4 rad; the swell (mid-stroke) is pressure 1 and tilt 0.85 rad.
     */
    fun pen(width: Int, height: Int, sizePx: Float, pitchPx: Float = 3f): List<PenSample> {
        val inset = inset(sizePx)
        val w = (width - 2f * inset).coerceAtLeast(1f)
        val amp = ((height - 2f * inset) / 2f).coerceAtLeast(0f)
        val mid = height / 2f
        val n = (w / pitchPx.coerceAtLeast(1f)).roundToInt().coerceIn(MIN_SAMPLES, MAX_SAMPLES)
        val out = ArrayList<PenSample>(n)
        var time = 0.0
        var px = 0f
        var py = 0f
        for (i in 0 until n) {
            val t = i / (n - 1f)
            val x = inset + w * t
            val y = mid - amp * sin(2.0 * PI * t).toFloat() * 0.8f
            if (i > 0) time += hypot(x - px, y - py) / SPEED_PX_PER_MS
            px = x; py = y
            val swell = sin(PI * t).toFloat()
            out.add(PenSample(x, y, timeMs = time, pressure = 0.25f + 0.75f * swell, tilt = 0.4f + 0.45f * swell, azimuth = LEAN))
        }
        return out
    }

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
