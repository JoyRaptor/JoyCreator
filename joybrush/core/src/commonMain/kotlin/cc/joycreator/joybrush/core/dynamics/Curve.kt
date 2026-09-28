package cc.joycreator.joybrush.core.dynamics

/**
 * A response curve: maps an input (pressure, speed, tilt, …, normalised to 0..1) to an output.
 *
 * Every brush dynamic in Joy Brush is "input → curve → setting" (MyPaint's model, R3). A curve is
 * stored as a short list of points and evaluated through a 256-entry lookup table, so the phone and
 * the PC Brush Lab compute bit-identical values from the same brush file.
 *
 * Points are joined by straight lines (piecewise linear). Outside the first/last point the curve
 * holds its end value. Points are sorted by x on construction; duplicate x keeps the later point,
 * which allows a deliberate step.
 */
class Curve(points: List<Pair<Float, Float>>) {

    val points: List<Pair<Float, Float>>
    private val lut = FloatArray(LUT_SIZE)

    init {
        require(points.isNotEmpty()) { "a curve needs at least one point" }
        this.points = points.sortedBy { it.first }
        for (i in 0 until LUT_SIZE) lut[i] = exact(i / (LUT_SIZE - 1f))
    }

    /** Evaluates the curve at [x] (clamped to 0..1) through the lookup table. */
    fun eval(x: Float): Float {
        if (x.isNaN()) return lut[0]
        val f = x.coerceIn(0f, 1f) * (LUT_SIZE - 1)
        val i = f.toInt().coerceAtMost(LUT_SIZE - 2)
        val t = f - i
        return lut[i] + (lut[i + 1] - lut[i]) * t
    }

    /** Evaluates the piecewise-linear curve directly (used to fill the table). */
    fun exact(x: Float): Float {
        val p = points
        if (x <= p.first().first) return p.first().second
        if (x >= p.last().first) return p.last().second
        for (i in 1 until p.size) {
            val (x1, y1) = p[i]
            if (x <= x1) {
                val (x0, y0) = p[i - 1]
                if (x1 == x0) return y1
                return y0 + (y1 - y0) * (x - x0) / (x1 - x0)
            }
        }
        return p.last().second
    }

    companion object {
        const val LUT_SIZE = 256
        val IDENTITY = Curve(listOf(0f to 0f, 1f to 1f))
        fun constant(v: Float) = Curve(listOf(0f to v, 1f to v))
    }
}
