package cc.joycreator.joybrush.core.input

import kotlin.math.PI

/** Small angle helpers shared by the input pipeline. Angles are radians. */
internal object Angles {
    private const val TWO_PI = 2 * PI

    /** Wraps any angle into -PI..PI. */
    fun wrap(a: Double): Double {
        var r = (a + PI) % TWO_PI
        if (r < 0) r += TWO_PI
        return r - PI
    }

    /**
     * Interpolates between two angles along the SHORT way round, so 170° → -170° passes through
     * 180°, not through 0°. NaN in either end yields NaN (the channel is absent).
     */
    fun lerp(a: Float, b: Float, t: Float): Float {
        if (a.isNaN() || b.isNaN()) return Float.NaN
        val d = wrap((b - a).toDouble())
        return wrap(a + d * t).toFloat()
    }
}
