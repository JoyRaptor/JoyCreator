package cc.joycreator.joybrush.core.input

import kotlin.math.PI
import kotlin.math.hypot

/**
 * The 1€ filter (Casiez, Roussel & Vogel, CHI 2012) applied to a 2-D point: a low-pass filter whose
 * cutoff rises with speed.
 *
 * This is what makes smoothing "velocity sensitive" in the way the owner described: when the hand
 * moves slowly — where tremor and a cheap pen's waviness show — the cutoff is low and jitter is
 * removed; when the hand moves fast the cutoff rises and the line follows the pen with almost no
 * lag. Both axes share ONE cutoff taken from the speed of the point, so a diagonal line is treated the
 * same as a horizontal one.
 *
 * Units are whatever the caller feeds in; [StrokeSmoother] feeds SCREEN pixels so the filter feels
 * the same at every zoom.
 *
 * @param minCutoff cutoff in Hz when the point is still. Lower = smoother and laggier when slow.
 * @param beta how fast the cutoff rises with speed (Hz per unit/second).
 * @param dCutoff cutoff for the speed estimate itself.
 */
class OneEuroFilter(
    var minCutoff: Double,
    var beta: Double,
    var dCutoff: Double = 1.0,
) {
    private var hasPrev = false
    private var prevX = 0.0
    private var prevY = 0.0
    private var prevDx = 0.0
    private var prevDy = 0.0
    private var prevT = 0.0

    fun reset() {
        hasPrev = false
    }

    /** Filters one point. Returns the filtered (x, y). [timeMs] must not go backwards. */
    fun filter(x: Double, y: Double, timeMs: Double): Pair<Double, Double> {
        if (!hasPrev) {
            hasPrev = true
            prevX = x; prevY = y; prevDx = 0.0; prevDy = 0.0; prevT = timeMs
            return x to y
        }
        // Some platforms deliver several samples with the same millisecond timestamp. Treat them
        // as 1 ms apart rather than dividing by zero or dropping real pen data.
        val dt = ((timeMs - prevT).coerceAtLeast(1.0)) / 1000.0
        val aD = alpha(dCutoff, dt)
        val dx = aD * ((x - prevX) / dt) + (1 - aD) * prevDx
        val dy = aD * ((y - prevY) / dt) + (1 - aD) * prevDy
        val cutoff = minCutoff + beta * hypot(dx, dy)
        val a = alpha(cutoff, dt)
        val fx = a * x + (1 - a) * prevX
        val fy = a * y + (1 - a) * prevY
        prevX = fx; prevY = fy; prevDx = dx; prevDy = dy; prevT = timeMs
        return fx to fy
    }

    private fun alpha(cutoff: Double, dt: Double): Double {
        val tau = 1.0 / (2 * PI * cutoff)
        return 1.0 / (1.0 + tau / dt)
    }
}
