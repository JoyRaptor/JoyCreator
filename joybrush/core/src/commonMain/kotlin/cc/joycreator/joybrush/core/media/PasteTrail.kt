package cc.joycreator.joybrush.core.media

import kotlin.math.hypot

/**
 * The pen's recent path, for a brush dragged along it like a string: its tail lies on the path the pen has already drawn, as
 * far back as the brush is long. Laid from the pen to that point the brush can never swing out past a corner, and when the pen
 * doubles back the two ends come together, so it turns about its middle (the owner, 2026-10-31: "the pivot needs to be in the
 * center of the sample on extreme rotations, and on the ends with drag").
 */
internal class PasteTrail {
    private val xs = DoubleArray(CAPACITY)
    private val ys = DoubleArray(CAPACITY)
    private var newest = -1
    private var count = 0

    /** The pen is at ([x], [y]), doc px. A brush held still adds nothing. */
    fun add(x: Double, y: Double) {
        if (count > 0 && xs[newest] == x && ys[newest] == y) return
        newest = (newest + 1) % CAPACITY
        xs[newest] = x; ys[newest] = y
        if (count < CAPACITY) count++
    }

    /** Into [out]: the point [px] along the path behind the pen, or where the path kept begins when it is shorter than that. */
    fun behind(px: Double, out: DoubleArray) {
        var i = newest
        var left = px
        for (n in 1 until count) {
            val j = (i - 1 + CAPACITY) % CAPACITY
            val d = hypot(xs[i] - xs[j], ys[i] - ys[j])
            if (d >= left) {
                val k = left / d
                out[0] = xs[i] + (xs[j] - xs[i]) * k; out[1] = ys[i] + (ys[j] - ys[i]) * k
                return
            }
            left -= d
            i = j
        }
        out[0] = xs[i]; out[1] = ys[i]
    }

    private companion object {
        /** Steps are at least a pixel apart, and most several, so this is longer than any brush at any size. */
        const val CAPACITY = 2048
    }
}
