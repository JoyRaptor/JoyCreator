package cc.joycreator.joybrush.core.shape

import cc.joycreator.joybrush.core.input.PenSample
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * Hand-drawn-looking strokes for the JB-2.10 tests: the ideal path resampled every ~1.5 px at 240 Hz,
 * with ±1.5 px jitter, a slow 2 px wobble across the path, and pressure 0.3 → 0.9 → 0.4. Tilt rises
 * steadily so a test can tell whether it was carried over.
 */
internal object StrokeMaker {

    fun polyline(corners: List<Pt>, seed: Int, closed: Boolean = false): List<PenSample> {
        val path = if (closed) corners + corners.first() else corners
        return fromPath(densify(path), seed)
    }

    fun ellipse(cx: Double, cy: Double, rx: Double, ry: Double, rotationDeg: Double,
                turns: Double, seed: Int, startDeg: Double = 0.0): List<PenSample> {
        val rot = rotationDeg * PI / 180
        val n = 4000
        val path = List(n + 1) { i ->
            val t = startDeg * PI / 180 + turns * 2 * PI * i / n
            val u = rx * cos(t); val v = ry * sin(t)
            Pt(cx + cos(rot) * u - sin(rot) * v, cy + sin(rot) * u + cos(rot) * v)
        }
        return fromPath(path, seed)
    }

    fun arc(cx: Double, cy: Double, r: Double, startDeg: Double, sweepDeg: Double, seed: Int): List<PenSample> {
        val n = 2000
        val path = List(n + 1) { i ->
            val a = (startDeg + sweepDeg * i / n) * PI / 180
            Pt(cx + r * cos(a), cy + r * sin(a))
        }
        return fromPath(path, seed)
    }

    /** A wandering scribble: 200 steps of 4 px with a heading that drifts randomly. */
    fun scribble(seed: Int): List<PenSample> {
        val rnd = Random(seed)
        var x = 0.0; var y = 0.0; var h = 0.0
        val path = ArrayList<Pt>()
        repeat(200) {
            path += Pt(x, y)
            h += (rnd.nextDouble() * 2 - 1) * 1.4
            x += 4 * cos(h); y += 4 * sin(h)
        }
        return fromPath(densify(path), seed)
    }

    private fun densify(path: List<Pt>): List<Pt> {
        val out = ArrayList<Pt>()
        for (i in 0 until path.size - 1) {
            val a = path[i]; val b = path[i + 1]
            val steps = maxOf(1, (hypot(b.x - a.x, b.y - a.y) / 0.25).toInt())
            for (k in 0 until steps) out += Pt(a.x + (b.x - a.x) * k / steps, a.y + (b.y - a.y) * k / steps)
        }
        out += path.last()
        return out
    }

    /** Resample [path] every 1.5 px, then roughen it like a hand would. */
    private fun fromPath(path: List<Pt>, seed: Int): List<PenSample> {
        val rnd = Random(seed)
        val even = ArrayList<Pt>()
        even += path[0]
        var carry = 0.0
        for (i in 1 until path.size) {
            val a = path[i - 1]; val b = path[i]
            val d = hypot(b.x - a.x, b.y - a.y)
            var s = 1.5 - carry
            while (s <= d) {
                even += Pt(a.x + (b.x - a.x) * s / d, a.y + (b.y - a.y) * s / d)
                s += 1.5
            }
            carry = d - (s - 1.5)
        }
        val phase = rnd.nextDouble() * 2 * PI
        val n = even.size
        return List(n) { i ->
            val f = if (n > 1) i.toDouble() / (n - 1) else 0.0
            val a = even[maxOf(0, i - 1)]; val b = even[minOf(n - 1, i + 1)]
            val len = hypot(b.x - a.x, b.y - a.y).takeIf { it > 0 } ?: 1.0
            val nx = -(b.y - a.y) / len; val ny = (b.x - a.x) / len
            val wobble = 2 * sin(phase + i * 1.5 / 60 * 2 * PI)
            val jx = (rnd.nextDouble() * 2 - 1) * 1.5
            val jy = (rnd.nextDouble() * 2 - 1) * 1.5
            val pressure = if (f < 0.5) 0.3 + 1.2 * f else 0.9 - 1.0 * (f - 0.5)
            PenSample(
                x = (even[i].x + nx * wobble + jx).toFloat(),
                y = (even[i].y + ny * wobble + jy).toFloat(),
                timeMs = i * 1000.0 / 240,
                pressure = pressure.toFloat(),
                tilt = (0.2 + 0.5 * f).toFloat(),
                azimuth = (1.0 + f).toFloat(),
            )
        }
    }
}
