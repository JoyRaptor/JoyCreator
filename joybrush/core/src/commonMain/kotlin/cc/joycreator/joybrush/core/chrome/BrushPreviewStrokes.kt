package cc.joycreator.joybrush.core.chrome

import cc.joycreator.joybrush.core.input.PenSample
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The live preview in a brush's settings (owner, 2026-09-30: "as I adjust the sliders I can clearly see exactly what is
 * changing on a sample stroke — versatile enough in tilt, pressure and speed"). The same strokes every time, so a slider
 * move is the only thing that changes:
 *
 *  - **the long stroke** across the top walks every mode in one go: a hairline (light, slow), line weight, then pressed
 *    flat and laid over for shadow (heavy, tilted, fast), and a quick lift;
 *  - **hair spikes**: pressed at the root, flicked off;
 *  - **switchbacks** at line weight;
 *  - **a laid-over shadow pass**: heavy, tilted, the side of the brush;
 *  - **a jolt**: sudden presses at speed;
 *  - **whiskers**: light and fast.
 *
 * Positions are SCREEN px inside a [width] × [height] box from (0, 0), times in ms (real hand speeds). The lean points up
 * and to the left, as a right hand holds a pen. Pure and repeatable.
 */
object BrushPreviewStrokes {

    /** The pen leans up-left (radians, document space: 0 = +x, y grows down). */
    private val LEAN = (-PI * 0.75).toFloat()

    fun strokes(width: Float, height: Float): List<List<PenSample>> {
        val w = width
        val h = height
        val out = ArrayList<List<PenSample>>()

        // The long stroke: hairline → line weight → pressed flat and laid over → quick lift.
        out += path(n = (w * 1.2f).toInt(), x = { t -> w * (0.03f + 0.94f * t) },
            y = { t -> h * (0.3f + 0.16f * sin(2f * PI.toFloat() * t)) },
            pressure = { t ->
                when {
                    t < 0.2f -> 0.04f + 0.26f * (t / 0.2f)
                    t < 0.45f -> 0.3f + 0.45f * ((t - 0.2f) / 0.25f)
                    t < 0.75f -> 0.75f + 0.25f * ((t - 0.45f) / 0.3f)
                    else -> 1f - 0.75f * ((t - 0.75f) / 0.25f)
                }
            },
            tilt = { t -> 1.15f * smooth(0.4f, 0.7f, t) - 0.5f * smooth(0.8f, 1f, t) },
            speed = { t -> 0.35f + 1.6f * smooth(0.25f, 0.6f, t) + 1.4f * smooth(0.85f, 1f, t) })

        val lowTop = h * 0.62f
        val lowBottom = h * 0.95f
        // Hair spikes.
        for (i in 0 until 4) {
            val x0 = w * (0.03f + i * 0.065f)
            out += path(n = 60, x = { t -> x0 + w * 0.03f * t }, y = { t -> lowBottom - (lowBottom - lowTop) * t },
                pressure = { t -> if (t < 0.3f) 1f else (1f - (t - 0.3f) / 0.7f).coerceAtLeast(0f) },
                tilt = { 0.3f }, speed = { 2.6f })
        }
        // Switchbacks at line weight.
        run {
            val x0 = w * 0.32f
            val span = w * 0.2f
            val legs = 4
            out += path(n = (span * legs).toInt(), x = { t ->
                val leg = (t * legs).toInt().coerceAtMost(legs - 1)
                val f = t * legs - leg
                if (leg % 2 == 0) x0 + span * f else x0 + span * (1f - f)
            }, y = { t -> lowTop + (lowBottom - lowTop) * t }, pressure = { 0.62f }, tilt = { 0.2f }, speed = { 1.1f })
        }
        // A laid-over shadow pass.
        out += path(n = (w * 0.24f).toInt(), x = { t -> w * (0.56f + 0.22f * t) }, y = { t -> lowTop + (lowBottom - lowTop) * (0.3f + 0.3f * t) },
            pressure = { 0.95f }, tilt = { 1.2f }, speed = { 1.3f })
        // A jolt: sudden presses at speed.
        out += path(n = (w * 0.17f).toInt(), x = { t -> w * (0.8f + 0.17f * t) }, y = { t -> lowTop + (lowBottom - lowTop) * 0.25f },
            pressure = { t -> if (((t * 5f).toInt() % 2) == 0) 0.15f else 1f }, tilt = { 0.3f }, speed = { 2.4f })
        // Whiskers.
        for (i in 0 until 3) {
            val x0 = w * (0.81f + i * 0.055f)
            out += path(n = 40, x = { t -> x0 + w * 0.04f * t }, y = { t -> lowBottom - (lowBottom - lowTop) * 0.45f * t },
                pressure = { t -> 0.18f * sin(PI.toFloat() * t) + 0.03f }, tilt = { 0.2f }, speed = { 2.2f })
        }
        return out
    }

    private fun path(n: Int, x: (Float) -> Float, y: (Float) -> Float, pressure: (Float) -> Float, tilt: (Float) -> Float,
                     speed: (Float) -> Float): List<PenSample> {
        val count = n.coerceAtLeast(2)
        val out = ArrayList<PenSample>(count + 1)
        var time = 0.0
        var px = x(0f)
        var py = y(0f)
        for (i in 0..count) {
            val t = i / count.toFloat()
            val nx = x(t)
            val ny = y(t)
            time += hypot(nx - px, ny - py) / speed(t).coerceAtLeast(0.01f)
            px = nx; py = ny
            out += PenSample(nx, ny, timeMs = time, pressure = pressure(t).coerceIn(0f, 1f), tilt = tilt(t).coerceIn(0f, 1.5f),
                azimuth = LEAN)
        }
        return out
    }

    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
