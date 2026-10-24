package cc.joycreator.joybrush.core.select

import kotlin.math.abs
import kotlin.math.roundToInt

/** Relative HSB adjustment keeps each selected line's colour and alpha independent. */
object SelectionColour {
    fun shift(argb: Int, hueDegrees: Float, saturation: Float, brightness: Float): Int {
        if (!hueDegrees.isFinite() || !saturation.isFinite() || !brightness.isFinite()) return argb
        if (hueDegrees == 0f && saturation == 1f && brightness == 1f) return argb
        val r = ((argb ushr 16) and 255) / 255f
        val g = ((argb ushr 8) and 255) / 255f
        val b = (argb and 255) / 255f
        val hi = maxOf(r, g, b)
        val delta = hi - minOf(r, g, b)
        val oldHue = when {
            delta == 0f -> 0f
            hi == r -> 60f * ((g - b) / delta)
            hi == g -> 60f * ((b - r) / delta + 2f)
            else -> 60f * ((r - g) / delta + 4f)
        }
        val h = ((oldHue + hueDegrees) % 360f + 360f) % 360f
        val s = ((if (hi == 0f) 0f else delta / hi) * saturation).coerceIn(0f, 1f)
        val v = (hi * brightness).coerceIn(0f, 1f)
        val c = v * s
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = v - c
        val rgb = when {
            h < 60f -> floatArrayOf(c, x, 0f)
            h < 120f -> floatArrayOf(x, c, 0f)
            h < 180f -> floatArrayOf(0f, c, x)
            h < 240f -> floatArrayOf(0f, x, c)
            h < 300f -> floatArrayOf(x, 0f, c)
            else -> floatArrayOf(c, 0f, x)
        }
        fun channel(i: Int) = ((rgb[i] + m) * 255f).roundToInt().coerceIn(0, 255)
        return (argb and 0xFF000000.toInt()) or (channel(0) shl 16) or (channel(1) shl 8) or channel(2)
    }
}
