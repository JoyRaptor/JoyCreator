package cc.joycreator.joybrush.core.media

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.floor
import kotlin.math.sign

/**
 * A hair paste brush's second colour, deeper in the brush (port of lab main.js bellyFor; owner, 2026-10-07: choosing a
 * second colour every stroke gets tedious). Modes are [cc.joycreator.joybrush.core.brush.BELLY_MODES]. Colours are sRGB
 * 0..1. Null = no belly (one colour through the brush).
 */
object MediaBelly {
    fun colorFor(mode: String, color: DoubleArray, seed: Int, manual: DoubleArray? = null, last: DoubleArray? = null): DoubleArray? {
        val (h, s, l) = rgbToHsl(color)
        return when (mode) {
            "manual" -> manual
            "darker" -> hslToRgb(hueToward(h, 0.66, 0.03), min(1.0, s * 1.1), l * 0.45)
            "lighter" -> hslToRgb(h, s * 0.8, l + (1 - l) * 0.55)
            "warmer" -> hslToRgb(hueToward(h, 0.08, 0.07), min(1.0, s * 1.05), min(0.95, l * 1.05))
            "cooler" -> hslToRgb(hueToward(h, 0.6, 0.07), s * 0.95, l * 0.92)
            "last colour" -> last
            "shift" -> {
                var x = (seed.toLong() * 2654435761L) and 0xFFFFFFFFL
                fun r(): Double { x = (x * 1664525L + 1013904223L) and 0xFFFFFFFFL; return x.toDouble() / 4294967296.0 - 0.5 }
                hslToRgb(h + 0.06 * r(), min(1.0, max(0.0, s + 0.2 * r())), min(0.95, max(0.05, l + 0.18 * r())))
            }
            else -> null
        }
    }

    internal fun rgbToHsl(c: DoubleArray): Triple<Double, Double, Double> {
        val (r, g, b) = Triple(c[0], c[1], c[2])
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b)); val l = (mx + mn) / 2
        if (mx == mn) return Triple(0.0, 0.0, l)
        val d = mx - mn
        val s = if (l > 0.5) d / (2 - mx - mn) else d / (mx + mn)
        val h = when (mx) { r -> (g - b) / d + (if (g < b) 6 else 0); g -> (b - r) / d + 2; else -> (r - g) / d + 4 }
        return Triple(h / 6, s, l)
    }

    internal fun hslToRgb(h: Double, s: Double, l: Double): DoubleArray {
        if (s == 0.0) return doubleArrayOf(l, l, l)
        val q = if (l < 0.5) l * (1 + s) else l + s - l * s
        val p = 2 * l - q
        fun f(t0: Double): Double {
            val t = ((t0 % 1) + 1) % 1
            return when { t < 1.0 / 6 -> p + (q - p) * 6 * t; t < 0.5 -> q; t < 2.0 / 3 -> p + (q - p) * (2.0 / 3 - t) * 6; else -> p }
        }
        return doubleArrayOf(f(h + 1.0 / 3), f(h), f(h - 1.0 / 3))
    }

    /** Toward hue [target] (0..1) by at most [by] of a turn, the short way round (as the lab, JS Math.round). */
    private fun hueToward(h: Double, target: Double, by: Double): Double {
        var d = target - h
        d -= floor(d + 0.5)   // JS Math.round
        return h + sign(d) * min(abs(d), by)
    }
}
