package cc.joycreator.joybrush.core.select

import cc.joycreator.joybrush.core.shape.Pt
import cc.joycreator.joybrush.core.vector.InkLine
import kotlin.math.hypot

/**
 * JB-5.20e D9 — which lines a drawn loop takes.
 *
 * [Lasso] answers the same question for pixels (coverage per pixel); nothing answered it for lines, and
 * `InkEditSession.band` is a rectangle that takes a line when any one dab centre is inside it. Here a line is taken by
 * how much of it the loop holds: its **inside fraction**, the share of its centreline's LENGTH inside the loop by the
 * non-zero winding rule (the rule [Lasso] fills pixels with, so a figure-eight loop means the same thing to lines and to
 * paint). Exact, not sampled: every segment is cut where it crosses the loop and each piece is tested at its middle.
 *
 * - [Reach.WHOLE] takes a line with `fraction >= WHOLE_FRACTION`. Not 1.0: a loop that clips a tail by a hair, or a
 *   tapered end that wanders out by a pixel, still means "that line".
 * - [Reach.TOUCHING] takes any line with `fraction > 0` (Concepts' Partial).
 *
 * A line of one point is a dot: fraction 1 or 0. A loop of fewer than three usable points, or any non-finite number,
 * takes nothing (a gesture is a person; it never throws).
 */
object LineLasso {

    /** How much of a line must be inside for [Reach.WHOLE]. */
    const val WHOLE_FRACTION = 0.9

    enum class Reach { WHOLE, TOUCHING }

    /** The ids of [lines] the loop takes, in [lines] order. */
    fun take(lines: List<InkLine>, loop: List<Pt>, reach: Reach): List<String> {
        return takeIndices(lines, loop, reach).map { lines[it].id }
    }

    /** Input ordinals of the taken lines; duplicate record ids remain separate candidates. */
    fun takeIndices(lines: List<InkLine>, loop: List<Pt>, reach: Reach): List<Int> {
        val poly = usable(loop) ?: return emptyList()
        val out = ArrayList<Int>()
        for ((index, line) in lines.withIndex()) {
            if (!overlaps(line, poly)) continue
            val f = fractionOf(line, poly)
            if (if (reach == Reach.WHOLE) f >= WHOLE_FRACTION else f > 0.0) out.add(index)
        }
        return out
    }

    /** The share of [line]'s centreline length inside [loop], 0..1 (0 for an unusable loop). */
    fun insideFraction(line: InkLine, loop: List<Pt>): Double {
        val poly = usable(loop) ?: return 0.0
        return fractionOf(line, poly)
    }

    // ── the loop ──

    private class Poly(val xs: DoubleArray, val ys: DoubleArray, val x0: Double, val y0: Double, val x1: Double, val y1: Double)

    private fun usable(loop: List<Pt>): Poly? {
        if (loop.size < 3) return null
        if (loop.any { !it.x.isFinite() || !it.y.isFinite() }) return null
        val xs = DoubleArray(loop.size) { loop[it].x }
        val ys = DoubleArray(loop.size) { loop[it].y }
        var x0 = xs[0]; var y0 = ys[0]; var x1 = xs[0]; var y1 = ys[0]
        for (i in xs.indices) { x0 = minOf(x0, xs[i]); x1 = maxOf(x1, xs[i]); y0 = minOf(y0, ys[i]); y1 = maxOf(y1, ys[i]) }
        return Poly(xs, ys, x0, y0, x1, y1)
    }

    private fun overlaps(line: InkLine, p: Poly): Boolean {
        var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        for (i in line.xs.indices) {
            val x = line.xs[i]; val y = line.ys[i]
            if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
        }
        return x1 >= p.x0 && x0 <= p.x1 && y1 >= p.y0 && y0 <= p.y1
    }

    /** Non-zero winding: inside when the loop winds round the point at least once, either way. */
    private fun inside(p: Poly, x: Double, y: Double): Boolean {
        var w = 0
        val n = p.xs.size
        for (i in 0 until n) {
            val ax = p.xs[i]; val ay = p.ys[i]
            val bx = p.xs[(i + 1) % n]; val by = p.ys[(i + 1) % n]
            val side = (bx - ax) * (y - ay) - (x - ax) * (by - ay)
            if (ay <= y) { if (by > y && side > 0) w++ } else if (by <= y && side < 0) w--
        }
        return w != 0
    }

    private fun fractionOf(line: InkLine, p: Poly): Double {
        val n = line.xs.size
        if (n == 1) return if (line.xs[0].isFinite() && line.ys[0].isFinite() && inside(p, line.xs[0], line.ys[0])) 1.0 else 0.0
        var total = 0.0
        var held = 0.0
        val cuts = ArrayList<Double>()
        for (i in 0 until n - 1) {
            val ax = line.xs[i]; val ay = line.ys[i]; val bx = line.xs[i + 1]; val by = line.ys[i + 1]
            if (!ax.isFinite() || !ay.isFinite() || !bx.isFinite() || !by.isFinite()) continue
            val len = hypot(bx - ax, by - ay)
            if (len == 0.0) continue
            total += len
            cuts.clear(); cuts.add(0.0); cuts.add(1.0)
            crossings(p, ax, ay, bx, by, cuts)
            cuts.sort()
            for (k in 0 until cuts.size - 1) {
                val t0 = cuts[k]; val t1 = cuts[k + 1]
                if (t1 - t0 <= 0.0) continue
                val tm = (t0 + t1) / 2
                if (inside(p, ax + (bx - ax) * tm, ay + (by - ay) * tm)) held += (t1 - t0) * len
            }
        }
        if (total == 0.0) return if (inside(p, line.xs[0], line.ys[0])) 1.0 else 0.0
        return (held / total).coerceIn(0.0, 1.0)
    }

    /** Adds the parameters (0..1 along a→b) where the segment crosses an edge of the loop. */
    private fun crossings(p: Poly, ax: Double, ay: Double, bx: Double, by: Double, into: MutableList<Double>) {
        val n = p.xs.size
        val rx = bx - ax; val ry = by - ay
        for (i in 0 until n) {
            val cx = p.xs[i]; val cy = p.ys[i]
            val sx = p.xs[(i + 1) % n] - cx; val sy = p.ys[(i + 1) % n] - cy
            val den = rx * sy - ry * sx
            if (den == 0.0) continue // parallel: the midpoint tests on either side decide
            val t = ((cx - ax) * sy - (cy - ay) * sx) / den
            val u = ((cx - ax) * ry - (cy - ay) * rx) / den
            if (t > 0.0 && t < 1.0 && u >= 0.0 && u <= 1.0) into.add(t)
        }
    }
}
