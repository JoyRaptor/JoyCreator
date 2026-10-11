package cc.joycreator.joybrush.core.media

import kotlin.math.max

/**
 * How media paint sits on the pixels under it (JB-2.40 §Q1, R51): the CPU twin of the app branch of
 * `jb_media_render.frag` (`u_look = 1`), so the arithmetic is tested where it can be.
 *
 * Colours are sRGB in 0..1; [ground] and the result are premultiplied RGBA, like a look tile.
 *
 * - The paint is lit and mixed over [base]: the ground over the flat paper colour, which is what Kubelka–Munk sees
 *   under it (the same approximation the look's alpha has always made: the layers below are not seen).
 * - What the media did is handed over as the least coverage that turns [base] into the lit result ([leastCover]), laid
 *   over the ground ([layerPixel]). Over the paper colour this reproduces the lit result exactly.
 * - A pixel with no media of its own is the ground itself, untouched (requirement 3): the shader returns it as is, and
 *   [layerPixel] of an unchanged [base] gives it back to the bit.
 */
object MediaGround {

    /** The ground over the flat paper colour: what the paint lies on, as the screen shows it over the paper. */
    fun base(ground: DoubleArray, paper: DoubleArray): DoubleArray =
        DoubleArray(3) { ground[it] + paper[it] * (1.0 - ground[3]) }

    /**
     * The least coverage that turns [bg] into [lit] (darker or brighter), as premultiplied RGBA: the alpha is the largest
     * per-channel fraction the change needs, and the colour follows from it. Zero when nothing changed.
     */
    fun leastCover(lit: DoubleArray, bg: DoubleArray): DoubleArray {
        var a = 0.0
        for (i in 0 until 3) {
            a = max(a, (bg[i] - lit[i]) / max(bg[i], 1.0e-3))
            a = max(a, (lit[i] - bg[i]) / max(1.0 - bg[i], 1.0e-3))
        }
        a = a.coerceIn(0.0, 1.0)
        if (a <= 1.0e-4) return doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        return DoubleArray(4) { if (it == 3) a else ((lit[it] - (1.0 - a) * bg[it]) / a).coerceIn(0.0, 1.0) * a }
    }

    /** The layer's pixel: what the media did ([leastCover] of [lit] over the [base]) laid over the [ground]. */
    fun layerPixel(lit: DoubleArray, ground: DoubleArray, paper: DoubleArray): DoubleArray {
        val d = leastCover(lit, base(ground, paper))
        return DoubleArray(4) { d[it] + ground[it] * (1.0 - d[3]) }
    }

    /** A premultiplied pixel over the flat paper colour, as the screen shows it. */
    fun overPaper(px: DoubleArray, paper: DoubleArray): DoubleArray = DoubleArray(3) { px[it] + paper[it] * (1.0 - px[3]) }
}
