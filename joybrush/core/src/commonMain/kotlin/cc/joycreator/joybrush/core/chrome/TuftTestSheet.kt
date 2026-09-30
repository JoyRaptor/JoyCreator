package cc.joycreator.joybrush.core.chrome

import cc.joycreator.joybrush.core.input.PenSample
import kotlin.math.PI
import kotlin.math.sin

/**
 * The tuning sheet's "Test" strokes (R9): one fixed set of marks, each with its own pressure and speed, so a slider can be
 * judged against the same strokes every time. The rows are the marks the owner's reference inks are made of (R9 §2) and
 * the behaviours he described (§3A):
 *
 *  1. whiskers — light, fast flicks (M1, O5)
 *  2. a swelling contour, light → heavy → light, slow (M3, O6)
 *  3. hair spikes — pressed down, then flicked off fast (M4, O11)
 *  4. stripes — medium pressure with a little hand tremor (M2, O7)
 *  5. switchbacks (O9)
 *  6. a fast, heavy S-sweep (O10, O3)
 *  7. jolts — sudden presses at speed (O1)
 *  8. a noir fill — broad, heavy passes (O12, O4)
 *
 * Positions are SCREEN px inside a [width] × [height] box starting at (0, 0); the caller maps them onto the page. Times
 * are in ms, so speeds are real hand speeds on the screen. Every channel a pen may lack is left NaN, exactly as a pen
 * without tilt reports it. Pure and repeatable.
 */
object TuftTestSheet {

    fun strokes(width: Float, height: Float): List<List<PenSample>> {
        val out = ArrayList<List<PenSample>>()
        val row = height / 8f
        fun y(r: Int, f: Float = 0.5f) = row * (r + f)
        val w = width

        // 1. whiskers: short, light, fast.
        for (i in 0 until 6) {
            val x0 = w * (0.04f + i * 0.08f)
            out += line(x0, y(0, 0.75f), x0 + w * 0.07f, y(0, 0.2f), speed = 2.2f) { f -> 0.18f * sin(PI.toFloat() * f) + 0.03f }
        }
        // 2. a slow swelling contour.
        out += curve(w * 0.52f, y(0, 0.8f), w * 0.96f, y(1, 0.6f), bow = row * 0.6f, speed = 0.35f) { f ->
            0.12f + 0.8f * sin(PI.toFloat() * f)
        }
        // 3. hair spikes: heavy at the root, flicked off fast.
        for (i in 0 until 5) {
            val x0 = w * (0.05f + i * 0.09f)
            out += line(x0, y(2, 0.85f), x0 + w * 0.04f, y(1, 0.9f), speed = 2.8f) { f -> if (f < 0.35f) 1f else (1f - (f - 0.35f) / 0.65f).coerceAtLeast(0f) }
        }
        // 4. stripes with a little tremor.
        for (i in 0 until 5) {
            val yy = y(2, 0.1f) + i * row * 0.18f
            out += line(w * 0.52f, yy, w * 0.96f, yy, speed = 0.9f, tremor = 1.2f) { 0.4f }
        }
        // 5. switchbacks.
        out += zigzag(w * 0.04f, y(3, 0.15f), w * 0.46f, row * 0.7f, turns = 6, speed = 1.2f, pressure = 0.7f)
        // 6. a fast heavy S-sweep.
        out += curve(w * 0.52f, y(3, 0.2f), w * 0.96f, y(4, 0.8f), bow = row * 1.2f, speed = 3.2f, s = true) { f ->
            0.3f + 0.65f * sin(PI.toFloat() * f)
        }
        // 7. jolts: sudden presses at speed.
        out += line(w * 0.04f, y(5, 0.3f), w * 0.46f, y(5, 0.5f), speed = 2.4f) { f -> if (((f * 7f).toInt() % 2) == 0) 0.15f else 1f }
        // 8. a noir fill: broad heavy passes, back and forth.
        for (i in 0 until 6) {
            val yy = y(5, 0.1f) + i * row * 0.35f
            if (i % 2 == 0) out += line(w * 0.52f, yy, w * 0.96f, yy + row * 0.1f, speed = 1.4f) { 1f }
            else out += line(w * 0.96f, yy, w * 0.52f, yy + row * 0.1f, speed = 1.4f) { 1f }
        }
        // And a slow thin line under a fast thin one, for O6.
        out += line(w * 0.04f, y(6, 0.4f), w * 0.46f, y(6, 0.4f), speed = 0.12f) { 0.22f }
        out += line(w * 0.04f, y(6, 0.8f), w * 0.46f, y(6, 0.8f), speed = 2.5f) { 0.22f }
        return out
    }

    /** A straight stroke, one sample per screen px, at [speed] px per ms. */
    private fun line(x0: Float, y0: Float, x1: Float, y1: Float, speed: Float, tremor: Float = 0f, pressure: (Float) -> Float): List<PenSample> {
        val dx = x1 - x0
        val dy = y1 - y0
        val len = kotlin.math.hypot(dx, dy).coerceAtLeast(1f)
        val n = len.toInt().coerceAtLeast(2)
        val nx = -dy / len
        val ny = dx / len
        return (0..n).map { i ->
            val f = i / n.toFloat()
            // A hand's tremor: a few px across the stroke at an uneven rhythm.
            val t = if (tremor > 0f) tremor * (sin(i * 0.9f) * 0.6f + sin(i * 2.3f + 1f) * 0.4f) else 0f
            PenSample(x0 + dx * f + nx * t, y0 + dy * f + ny * t, timeMs = i / speed.toDouble(), pressure = pressure(f))
        }
    }

    /** A bowed stroke (or an S when [s]), sampled about once per screen px. */
    private fun curve(x0: Float, y0: Float, x1: Float, y1: Float, bow: Float, speed: Float, s: Boolean = false, pressure: (Float) -> Float): List<PenSample> {
        val dx = x1 - x0
        val dy = y1 - y0
        val len = kotlin.math.hypot(dx, dy).coerceAtLeast(1f)
        val nx = -dy / len
        val ny = dx / len
        val n = (len * 1.3f).toInt().coerceAtLeast(2)
        var t = 0.0
        var px = x0
        var py = y0
        return (0..n).map { i ->
            val f = i / n.toFloat()
            val off = if (s) bow * sin(2f * PI.toFloat() * f) else bow * sin(PI.toFloat() * f)
            val x = x0 + dx * f + nx * off
            val y = y0 + dy * f + ny * off
            t += kotlin.math.hypot(x - px, y - py) / speed
            px = x; py = y
            PenSample(x, y, timeMs = t, pressure = pressure(f))
        }
    }

    /** Back and forth across [width], each leg a little lower, with hard turns. */
    private fun zigzag(x0: Float, y0: Float, width: Float, height: Float, turns: Int, speed: Float, pressure: Float): List<PenSample> {
        val out = ArrayList<PenSample>()
        var t = 0.0
        for (leg in 0 until turns) {
            val fromX = if (leg % 2 == 0) x0 else x0 + width
            val toX = if (leg % 2 == 0) x0 + width else x0
            val yy = y0 + height * leg / turns
            val n = width.toInt()
            for (i in (if (leg == 0) 0 else 1)..n) {
                val f = i / n.toFloat()
                out += PenSample(fromX + (toX - fromX) * f, yy + height / turns * f * 0.3f, timeMs = t, pressure = pressure)
                t += 1.0 / speed
            }
        }
        return out
    }
}
