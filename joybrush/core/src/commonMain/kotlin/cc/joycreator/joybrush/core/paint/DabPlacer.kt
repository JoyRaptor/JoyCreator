package cc.joycreator.joybrush.core.paint

import cc.joycreator.joybrush.core.input.PenSample
import kotlin.math.hypot
import kotlin.math.max

/**
 * Turns a stream of (smoothed) pen samples into evenly spaced dabs.
 *
 * Spacing is a fraction of the dab DIAMETER at that point (never below [minSpacingPx]). The leftover
 * distance is carried between calls, so the dabs are identical however the samples happen to be
 * batched — the result does not depend on the device's event rate (MyPaint's rule, R3).
 * Every channel is interpolated between the two samples a dab falls between.
 *
 * The brush decides size, angle and flow per sample through the three functions; the engine never
 * interprets pressure itself.
 */
class DabPlacer(
    private val spacing: Float,
    private val radiusOf: (PenSample) -> Float,
    private val angleOf: (PenSample) -> Float = { 0f },
    private val flowOf: (PenSample) -> Float = { 1f },
    private val cap: Float = 1f,
    private val minSpacingPx: Float = 0.5f,
) {
    private var prev: PenSample? = null
    private var untilNext = 0f

    fun add(samples: List<PenSample>): List<Dab> {
        val out = ArrayList<Dab>()
        for (s in samples) addOne(s, out)
        return out
    }

    private fun addOne(s: PenSample, out: MutableList<Dab>) {
        val p = prev
        if (p == null) {
            out.add(dabAt(s))
            prev = s
            untilNext = step(s)
            return
        }
        val len = hypot(s.x - p.x, s.y - p.y)
        if (len <= 0f) { prev = s; return }
        var along = untilNext
        while (along <= len) {
            val t = along / len
            val m = lerp(p, s, t)
            out.add(dabAt(m))
            along += step(m)
        }
        untilNext = along - len
        prev = s
    }

    private fun step(s: PenSample): Float = max(2f * radiusOf(s) * spacing, minSpacingPx)

    private fun dabAt(s: PenSample) = Dab(
        x = s.x, y = s.y, radius = radiusOf(s), angle = angleOf(s), flow = flowOf(s), cap = cap,
        pressure = s.pressure,
    )

    private fun lerp(a: PenSample, b: PenSample, t: Float) = PenSample(
        x = a.x + (b.x - a.x) * t,
        y = a.y + (b.y - a.y) * t,
        timeMs = a.timeMs + (b.timeMs - a.timeMs) * t,
        pressure = a.pressure + (b.pressure - a.pressure) * t,
        tilt = if (a.tilt.isNaN() || b.tilt.isNaN()) Float.NaN else a.tilt + (b.tilt - a.tilt) * t,
        azimuth = b.azimuth,
        barrel = b.barrel,
        tool = b.tool,
    )
}
