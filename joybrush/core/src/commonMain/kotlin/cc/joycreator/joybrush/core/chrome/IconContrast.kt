package cc.joycreator.joybrush.core.chrome

import kotlin.math.cbrt
import kotlin.math.pow

/** How an icon over the picture is inked (JB-2.01, owner 2026-09-30). */
enum class IconInk {
    /** A black icon: the picture behind is lighter than middle grey. */
    DARK,

    /** A white icon with a drop shadow: the picture behind is darker than middle grey, or mixed. */
    LIGHT,
}

/**
 * The owner's rule for the solid icons along the top and every icon that opens a menu: each one looks at the picture
 * behind it. A little lighter than middle grey, the icon is black; a little darker, it is white with a drop shadow.
 *
 * "Middle grey" is lightness L* = 50 (CIE), the grey a person sees as halfway between black and white — not sRGB 128,
 * which looks lighter than half. Two refinements keep it calm:
 * - **Hysteresis.** Once an icon has an ink it keeps it until the picture is [HYSTERESIS] past the middle the other way,
 *   so a background sitting right at middle grey (or a soft brush passing behind) never makes the icon flicker.
 * - **Mixed backgrounds.** When the samples behind one icon span [MIXED_RANGE] or more — an icon straddling a black line on
 *   white paper — it is LIGHT: white with a shadow reads on both halves, black does not.
 */
object IconContrast {

    const val MIDDLE_GREY_L = 50f
    const val HYSTERESIS = 4f
    const val MIXED_RANGE = 45f

    /** CIE lightness L* (0 = black, 100 = white) of an sRGB colour; alpha is ignored (the samples are what is on screen). */
    fun lightness(argb: Int): Float = lightnessOfLuminance(luminance(argb))

    /** Relative luminance Y (0..1) of an sRGB colour, linearised. */
    fun luminance(argb: Int): Float {
        fun lin(c: Int): Double {
            val s = c / 255.0
            return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        val r = lin((argb shr 16) and 0xFF)
        val g = lin((argb shr 8) and 0xFF)
        val b = lin(argb and 0xFF)
        return (0.2126 * r + 0.7152 * g + 0.0722 * b).toFloat()
    }

    private fun lightnessOfLuminance(y: Float): Float {
        val t = y.toDouble()
        val f = if (t > 216.0 / 24389.0) cbrt(t) else (24389.0 / 27.0 * t + 16.0) / 116.0
        return (116.0 * f - 16.0).toFloat()
    }

    /**
     * The ink for an icon whose background was sampled as [samples] (opaque ARGB), given the ink it has now ([previous],
     * null the first time). No samples keeps the previous ink, or LIGHT — the one that is safest on an unknown picture.
     */
    fun inkFor(samples: IntArray, previous: IconInk?): IconInk {
        if (samples.isEmpty()) return previous ?: IconInk.LIGHT
        var minL = Float.MAX_VALUE
        var maxL = -Float.MAX_VALUE
        var sumY = 0.0
        for (s in samples) {
            val y = luminance(s)
            val l = lightnessOfLuminance(y)
            if (l < minL) minL = l
            if (l > maxL) maxL = l
            sumY += y
        }
        if (maxL - minL >= MIXED_RANGE) return IconInk.LIGHT
        // The average is taken in LIGHT (luminance), then turned into lightness: averaging L* directly would call a fine
        // black-and-white hatching darker than it looks.
        val l = lightnessOfLuminance((sumY / samples.size).toFloat())
        return when (previous) {
            IconInk.DARK -> if (l < MIDDLE_GREY_L - HYSTERESIS) IconInk.LIGHT else IconInk.DARK
            IconInk.LIGHT -> if (l > MIDDLE_GREY_L + HYSTERESIS) IconInk.DARK else IconInk.LIGHT
            null -> if (l > MIDDLE_GREY_L) IconInk.DARK else IconInk.LIGHT
        }
    }
}
