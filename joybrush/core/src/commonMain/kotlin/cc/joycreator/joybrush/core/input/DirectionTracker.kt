package cc.joycreator.joybrush.core.input

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Which way the brush tip points — the "rotation" channel for pens with no barrel-twist sensor
 * (every S Pen). Owner's idea, 2026-09-28, refined by the Expresii research (R2):
 *
 * - **Pen leaning** (tilt above [tiltThreshold]): the tip points the way the pen leans, like the
 *   side of a real pencil or a flat brush dragged on its edge. Every Galaxy Note reports tilt.
 * - **Pen upright**: the tip trails the direction of travel, like bristles dragged behind a brush.
 *   The swing is damped by DISTANCE travelled, not by time, over [lengthScale] document pixels —
 *   long soft bristles (large scale) swing slowly into a new direction; short stiff ones snap round.
 *   Below [minStep] of movement nothing changes, so a pause does not make the tip spin.
 * - **Barrel sensor present** (Wacom Art Pen, Apple Pencil Pro): the sensor wins outright.
 *
 * The direction is filtered as a unit VECTOR, never as an angle, so crossing ±180° never makes it
 * flip the long way round. The first real movement seeds it directly so a stroke does not start
 * pointing the wrong way.
 *
 * Round tips ignore rotation entirely (Expresii's developer removed twist from round brushes because
 * it made them worse); that choice belongs to the brush, not to this tracker.
 */
class DirectionTracker(
    var lengthScale: Float = 6f,
    var tiltThreshold: Float = 0.17f, // ≈ 10°
    var minStep: Float = 0.25f,
) {
    private var hasPos = false
    private var lastX = 0f
    private var lastY = 0f
    private var hasDir = false
    private var dx = 1.0
    private var dy = 0.0

    fun reset() {
        hasPos = false
        hasDir = false
        dx = 1.0; dy = 0.0
    }

    /** Feeds one (already smoothed) sample and returns the tip direction in radians, -PI..PI. */
    fun update(s: PenSample): Float {
        if (s.hasBarrel) return s.barrel
        if (!hasPos) {
            hasPos = true
            lastX = s.x; lastY = s.y
        }
        val mx = (s.x - lastX).toDouble()
        val my = (s.y - lastY).toDouble()
        val step = hypot(mx, my)

        if (s.hasTilt && s.hasAzimuth && s.tilt > tiltThreshold) {
            dx = cos(s.azimuth.toDouble()); dy = sin(s.azimuth.toDouble())
            hasDir = true
        } else if (step >= minStep) {
            val tx = mx / step
            val ty = my / step
            if (!hasDir) {
                dx = tx; dy = ty; hasDir = true
            } else {
                val kBlend = 1.0 - exp(-step / lengthScale)
                val nx = dx + (tx - dx) * kBlend
                val ny = dy + (ty - dy) * kBlend
                val len = hypot(nx, ny)
                // A perfect reversal can cancel to zero; keep the old direction for that one step.
                if (len > 1e-6) { dx = nx / len; dy = ny / len }
            }
        }
        if (step >= minStep) { lastX = s.x; lastY = s.y }
        return atan2(dy, dx).toFloat()
    }
}
