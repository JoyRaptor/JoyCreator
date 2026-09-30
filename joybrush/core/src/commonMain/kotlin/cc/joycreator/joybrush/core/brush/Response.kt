package cc.joycreator.joybrush.core.brush

import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.input.Tool
import kotlinx.serialization.Serializable

/** The brush version that introduced the `response` section. */
const val VERSION_RESPONSE = 5

/**
 * How a brush hears the pen (owner, 2026-09-30: "adjustable curves for pressure and for tilt, with Bezier handles"). Each
 * curve runs from (0, 0) to (1, 1) through two handles, `[x1, y1, x2, y2]`, like a CSS cubic-bezier: the pen's reading
 * goes in along x, what the brush feels comes out on y. The straight line is the default and changes nothing.
 *
 * Tilt is read as 0 = upright … 1 = flat on the glass.
 */
@Serializable data class ResponseSpec(
    val pressure: List<Float> = ResponseCurve.LINEAR,
    val tilt: List<Float> = ResponseCurve.LINEAR,
) {
    /** True when both curves are the straight line, so the file needs no newer version to say it. */
    val isDefault: Boolean get() = ResponseCurve.isLinear(pressure) && ResponseCurve.isLinear(tilt)

    /**
     * [s] as the brush hears it. A finger or a mouse keeps its pressure (it has none to shape), and a channel the pen
     * cannot measure stays NaN.
     */
    fun apply(s: PenSample): PenSample {
        if (isDefault) return s
        val p = if (s.tool == Tool.FINGER || s.tool == Tool.MOUSE || !s.pressure.isFinite()) s.pressure
        else ResponseCurve.eval(pressure, s.pressure.coerceIn(0f, 1f))
        val t = if (!s.tilt.isFinite()) s.tilt
        else ResponseCurve.eval(tilt, (s.tilt / HALF_PI).coerceIn(0f, 1f)) * HALF_PI
        return s.copy(pressure = p, tilt = t)
    }

    private companion object {
        const val HALF_PI = 1.5707964f
    }
}

/** The cubic Bezier from (0, 0) to (1, 1) that a [ResponseSpec] curve is. Pure, the same on every platform. */
object ResponseCurve {

    val LINEAR: List<Float> = listOf(1f / 3f, 1f / 3f, 2f / 3f, 2f / 3f)

    fun isLinear(h: List<Float>): Boolean =
        h.size == 4 && (0 until 4).all { kotlin.math.abs(h[it] - LINEAR[it]) < 1e-4f }

    /** y at [x] (0..1). The handles' x are clamped to 0..1, so x(t) only rises and one t answers each x. */
    fun eval(h: List<Float>, x: Float): Float {
        if (h.size != 4) return x
        val x1 = h[0].coerceIn(0f, 1f)
        val y1 = h[1]
        val x2 = h[2].coerceIn(0f, 1f)
        val y2 = h[3]
        val target = x.coerceIn(0f, 1f)
        var lo = 0f
        var hi = 1f
        repeat(24) {
            val mid = (lo + hi) / 2f
            if (bez(x1, x2, mid) < target) lo = mid else hi = mid
        }
        return bez(y1, y2, (lo + hi) / 2f).coerceIn(0f, 1f)
    }

    private fun bez(a: Float, b: Float, t: Float): Float {
        val u = 1f - t
        return 3f * u * u * t * a + 3f * u * t * t * b + t * t * t
    }
}
