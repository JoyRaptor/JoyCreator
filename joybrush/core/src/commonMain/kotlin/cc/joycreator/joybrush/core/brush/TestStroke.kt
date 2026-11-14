package cc.joycreator.joybrush.core.brush

import cc.joycreator.joybrush.core.input.PenSample
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The one test stroke every brush is shown with (the owner, 2026-11-14, job BRUSH-UI): "a horizontal swoosh that is ever
 * so light, and then it gets harder as it goes across, and the tilt varies as it does a little curl at the end. And then
 * instead of a gradual buildup, it should have a sudden lift so that you get to see the most variety in a single stroke
 * as possible. And it should have it at some point crossing over a sample line or two or three that are set colors."
 *
 * It is data, not drawing: pen samples (place, pressure, tilt, lean, time) for a box of any size, and the three colour
 * [LINES] the swoosh crosses, in the box's own shares. Every engine takes the same samples (stamp and Unibrush, tuft,
 * pencil, watercolour, oil), so two brushes' pictures differ only by the brush. Pure and repeatable: the same box gives
 * the same samples, so a picture never shimmers between renders.
 *
 * The shape, left to right in a picture whose y grows down:
 *  - the swoosh ([SWOOSH] of the way): from a feather touch ([P_START]) the pressure climbs slowly, then fast, to
 *    [P_SWOOSH_END]; the pen stands nearly upright and lays over a little as it goes; it sinks gently toward the right;
 *  - the curl: a loop most of the way round at the right end, back over its own line, pressed hard, the pen laid far
 *    over and its lean swinging round with the hand, slower than the swoosh;
 *  - the lift: the last sample is still at full pressure. Nothing fades out.
 */
object TestStroke {
    /** One colour line the swoosh crosses: from ([x0], [y0]) to ([x1], [y1]) as shares of the box (0..1 each way), opaque ARGB. */
    class Line(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val argb: Int)

    /** Three short upright strokes of set colours, laid before the swoosh so it shows what the brush does over paint. */
    val LINES: List<Line> = listOf(
        Line(0.30f, 0.14f, 0.33f, 0.86f, 0xFFC72A22.toInt()),   // a warm red
        Line(0.43f, 0.14f, 0.46f, 0.86f, 0xFF2440A8.toInt()),   // a deep blue
        Line(0.56f, 0.14f, 0.59f, 0.86f, 0xFFD99A1F.toInt()),   // a yellow ochre
    )

    /** The pressure and tilt a colour line is laid with: firm and plain, the same for every brush. */
    const val LINE_PRESSURE = 0.7f
    const val LINE_TILT = 0.35f

    /** The share of the stroke's samples that are the swoosh; the rest are the curl. */
    const val SWOOSH = 0.72f

    const val P_START = 0.03f
    const val P_SWOOSH_END = 0.9f
    const val TILT_START = 0.12f
    const val TILT_SWOOSH_END = 0.5f
    const val TILT_CURL_END = 1.1f

    /** The pen leans up and to the left as a right hand holds it (radians, 0 = +x, y grows down); the curl swings it round. */
    const val LEAN = (-0.75 * PI).toFloat()
    const val LEAN_SWING = 1.4f

    /** How far round the curl goes (radians): past the top and back down across its own line. */
    const val CURL_TURN = (1.85 * PI).toFloat()

    /** Speed along the path, picture pixels per millisecond: the swoosh quick, the curl slower. */
    const val SWOOSH_PX_PER_MS = 1.4f
    const val CURL_PX_PER_MS = 0.7f

    private const val MIN_SAMPLES = 24
    private const val MAX_SAMPLES = 600

    /** How far from the box's edge the pen stays for a brush [sizePx] wide. */
    fun inset(sizePx: Float): Float = sizePx / 2f + 2f

    /** The curl's radius in a box whose pen may use [w] by [h] pixels: most of the height, never much of the width. */
    private fun curlRadius(w: Float, h: Float): Float = min(h * 0.4f, w * 0.12f)

    /** The stroke for a [width] x [height] picture and a brush [sizePx] wide, a sample about every [pitchPx] along it. */
    fun pen(width: Int, height: Int, sizePx: Float, pitchPx: Float = 3f): List<PenSample> {
        val inset = inset(sizePx)
        val w = (width - 2f * inset).coerceAtLeast(1f)
        val h = (height - 2f * inset).coerceAtLeast(0f)
        val r = curlRadius(w, h)
        val left = inset
        val cx = inset + w - r                       // the curl's centre; the swoosh ends under it
        // A picture no taller than the brush has no room to move up or down: the stroke runs along its middle.
        val top = if (h > 0f) inset else height / 2f
        val yStart = top + h * 0.38f
        val yEnd = top + h * 0.5f + r                // the swoosh's last point: the bottom of the curl
        val cy = yEnd - r
        val swooshLen = hypot(cx - left, yEnd - yStart)
        val length = swooshLen + r * CURL_TURN
        val n = (length / pitchPx.coerceAtLeast(1f)).roundToInt().coerceIn(MIN_SAMPLES, MAX_SAMPLES)
        val nSwoosh = max(2, (n * SWOOSH).roundToInt())
        val out = ArrayList<PenSample>(n)
        var time = 0.0
        var px = 0f
        var py = 0f
        for (i in 0 until n) {
            val x: Float
            val y: Float
            val pressure: Float
            val tilt: Float
            val lean: Float
            val speed: Float
            if (i < nSwoosh) {
                val k = i / (nSwoosh - 1f)
                x = left + (cx - left) * k
                y = yStart + (yEnd - yStart) * k * k                      // level at first, sinking toward the curl
                pressure = P_START + (P_SWOOSH_END - P_START) * k.pow(1.7f)
                tilt = TILT_START + (TILT_SWOOSH_END - TILT_START) * k
                lean = LEAN
                speed = SWOOSH_PX_PER_MS
            } else {
                val k = (i - nSwoosh + 1f) / (n - nSwoosh)                // 0 (exclusive) .. 1 at the lift
                val a = CURL_TURN * k
                x = cx + r * sin(a)
                y = cy + r * cos(a)
                pressure = P_SWOOSH_END + (1f - P_SWOOSH_END) * k
                tilt = TILT_SWOOSH_END + (TILT_CURL_END - TILT_SWOOSH_END) * k
                lean = LEAN - LEAN_SWING * k
                speed = CURL_PX_PER_MS
            }
            if (i > 0) time += hypot(x - px, y - py) / speed
            px = x; py = y
            out.add(PenSample(x, y, timeMs = time, pressure = pressure, tilt = tilt, azimuth = lean))
        }
        return out
    }

    /** Colour line [line] as pen samples for the same box: a plain firm stroke top to bottom. */
    fun linePen(line: Line, width: Int, height: Int, sizePx: Float, pitchPx: Float = 3f): List<PenSample> {
        val inset = inset(sizePx)
        val w = (width - 2f * inset).coerceAtLeast(1f)
        val h = (height - 2f * inset).coerceAtLeast(0f)
        val x0 = inset + w * line.x0; val y0 = inset + h * line.y0
        val x1 = inset + w * line.x1; val y1 = inset + h * line.y1
        val len = hypot(x1 - x0, y1 - y0)
        val n = (len / pitchPx.coerceAtLeast(1f)).roundToInt().coerceIn(8, MAX_SAMPLES)
        return List(n) { i ->
            val k = i / (n - 1f)
            PenSample(x0 + (x1 - x0) * k, y0 + (y1 - y0) * k, timeMs = (len * k / SWOOSH_PX_PER_MS).toDouble(),
                pressure = LINE_PRESSURE, tilt = LINE_TILT, azimuth = LEAN)
        }
    }
}
