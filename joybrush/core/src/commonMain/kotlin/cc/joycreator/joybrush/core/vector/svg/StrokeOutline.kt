package cc.joycreator.joybrush.core.vector.svg

import cc.joycreator.joybrush.core.paint.Dab
import cc.joycreator.joybrush.core.paint.TipShape
import cc.joycreator.joybrush.core.shape.Pt
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * The variable-width outline of a run of dabs: ONE closed shape per connected run, never one per dab
 * (patent research R8; VECTOR_RENDER_DESIGN §4). Pressure, taper and speed survive because every
 * dab's own radius, angle and live tip shape is traced.
 *
 * ## The geometry
 *
 * The outline is the boundary of the dabs' SWEEP: between consecutive dabs A and B the sweep is the
 * convex hull of the two tips, whose edge on each side of travel is their common tangent (the
 * bitangent). Its direction is where A's and B's support functions cross, `h_A(φ) = h_B(φ)`, found
 * on the half-turn of directions on that side and refined by bisection. A support point taken
 * straight across the travel direction instead would cut inside the true edge wherever the width
 * changes: by `r·(1/cos φ − 1)` with `sin φ = dr/ds`, 15 % of the radius at `dr/ds = 0.5`.
 *
 * - A side is the chain of bitangent touch points, each dab contributing the stretch of its own
 *   edge between the tangent it shares with the dab before and the one with the dab after.
 * - The END cap is the last tip's edge, swept from its left tangent forward round to its right one;
 *   the START cap is the first tip's edge swept round behind. A run of one dab is that tip's outline.
 * - A dab wholly inside its neighbour (a fast width change) adds nothing to the sweep and is dropped
 *   first, so every remaining pair has a tangent on each side.
 * - Consecutive dabs that do not touch (spacing ≥ ~1, "dots") start a new closed piece rather than
 *   being bridged by a tangent, so a dotted line is one `<path>` of several sub-paths.
 *
 * ## Why self-overlap is safe
 *
 * Every piece winds the same way (clockwise on screen axes turned y-up, i.e. the side left of travel
 * is walked forward and the caps turn the direction negatively). Where a tight turn folds the inside
 * edge back over itself it makes a small loop of the SAME orientation, so the winding there is −2,
 * not 0. Fill with `fill-rule="nonzero"` and turns, hairpins and crossings stay solid; even-odd would
 * punch holes in them. `StrokeOutlineTest` checks a hairpin for holes.
 *
 * ## What it is not
 *
 * Not antialiasing (an outline is the tip's `d <= 1` region), not hardness, not grain, not flow, not
 * the sub-`minPx` fade of a hairline — an SVG fill is flat. Brush spacing above ~0.3 leaves scallops
 * between dabs (depth `r(1 − √(1 − σ²))`); the sweep fills them, and below 1.0 it never bridges dots.
 * A spiky tip (`corner < 1`) is traced as its sweep too, which is wider than its dabs wherever the
 * spike tips of neighbouring dabs do not overlap (~2 px per side at spacing 0.05, r = 14: IoU ~0.89
 * against the raster). Only tuft brushes ship such tips today, and tuft does not draw ink lines.
 */
object StrokeOutline {

    /** RDP tolerance, doc px. */
    const val SIMPLIFY_PX = 0.1

    /** Bézier fit tolerance, doc px (measured against the simplified polyline). */
    const val FIT_PX = 0.15

    /** One piece of a closed contour, from the previous segment's end (or the contour start) to [end]. */
    data class Segment(val c1: Pt, val c2: Pt, val end: Pt, val straight: Boolean)

    /** One closed sub-path: a start point and segments that end back at it. */
    data class Contour(val start: Pt, val segments: List<Segment>)

    /**
     * Fitted contours of several runs of ONE stroke (a dashed stroke: one run per dash). The caller
     * writes all of them into one `<path>`.
     */
    fun strokeContours(runs: List<List<Dab>>, tip: TipShape, fit: Boolean = true): List<Contour> =
        runs.flatMap { contours(it, tip, fit) }

    /**
     * Fitted contours of one run: [rings] → RDP at [SIMPLIFY_PX] → cubic Béziers within [FIT_PX]
     * (or, with [fit] false, the simplified polygon as straight segments).
     */
    fun contours(run: List<Dab>, tip: TipShape, fit: Boolean = true): List<Contour> =
        rings(run, tip).mapNotNull { ring ->
            val simple = CurveFit.simplifyClosed(ring, SIMPLIFY_PX)
            when {
                simple.size < 2 -> null
                fit -> CurveFit.fitClosed(simple, FIT_PX)
                else -> CurveFit.polylineClosed(simple)
            }
        }

    /**
     * The dense outline rings of one run (dabs in stroke order, unscattered), one per connected
     * piece, before any simplification. Closed: the last point joins the first and is not repeated.
     *
     * Dabs with a non-finite position, angle or radius, or a radius ≤ 0, are skipped — the same dabs
     * `InkRaster` draws nothing for.
     */
    fun rings(run: List<Dab>, tip: TipShape): List<List<Pt>> {
        val tips = ArrayList<Placed>()
        for (d in run) {
            if (!d.x.isFinite() || !d.y.isFinite() || !d.angle.isFinite() || !d.radius.isFinite() || d.radius <= 0f) continue
            val t = Placed(d, TipHull(d, tip))
            // A tip inside the one before adds nothing; one that swallows the one before replaces it.
            if (tips.isNotEmpty() && contains(tips.last().hull, t.hull)) continue
            while (tips.isNotEmpty() && contains(t.hull, tips.last().hull)) tips.removeAt(tips.size - 1)
            tips.add(t)
        }
        if (tips.isEmpty()) return emptyList()
        val out = ArrayList<List<Pt>>()
        var start = 0
        for (i in 1..tips.size) {
            if (i == tips.size || apart(tips[i - 1].hull, tips[i].hull)) {
                out.add(ring(tips.subList(start, i)))
                start = i
            }
        }
        return out
    }

    // ── one connected piece ──────────────────────────────────────────────────────────────

    private class Placed(val dab: Dab, val hull: TipHull)

    private fun ring(t: List<Placed>): List<Pt> {
        val out = Ring()
        if (t.size == 1) {
            val h = t[0].hull
            out.support(h, 0.0)
            h.arc(0.0, -2.0 * PI, out::add)
            return out.closed()
        }
        val m = t.size - 1
        val alpha = travel(t)
        // phi[side][i]: the bitangent direction of pair (i, i+1) on side +1 (index 0) or −1 (index 1),
        // unwrapped as alpha + side·π·(1 − τ) so the caps can be swept without wrapping arithmetic.
        val plus = DoubleArray(m) { bitangent(t[it].hull, t[it + 1].hull, alpha[it], +1) }
        val minus = DoubleArray(m) { bitangent(t[it].hull, t[it + 1].hull, alpha[it], -1) }

        // Side +1, forward.
        out.support(t[0].hull, plus[0])
        for (i in 1 until m) {
            val h = t[i].hull
            out.support(h, plus[i - 1])
            h.arc(plus[i - 1], sideSweep(plus[i - 1], plus[i], +1), out::add)
            out.support(h, plus[i])
        }
        // End cap: the last tip, turned from its +1 tangent forward round to its −1 one.
        val last = t[m].hull
        out.support(last, plus[m - 1])
        last.arc(plus[m - 1], minus[m - 1] - plus[m - 1], out::add)
        out.support(last, minus[m - 1])
        // Side −1, backward: the same per-dab edge stretches, walked in reverse.
        for (i in m - 1 downTo 1) {
            val h = t[i].hull
            out.support(h, minus[i])
            h.arc(minus[i], -sideSweep(minus[i - 1], minus[i], -1), out::add)
            out.support(h, minus[i - 1])
        }
        // Start cap: the first tip, turned from its −1 tangent on round behind to its +1 one.
        val first = t[0].hull
        out.support(first, minus[0])
        first.arc(minus[0], (plus[0] - 2.0 * PI) - minus[0], out::add)
        return out.closed()
    }

    /**
     * The direction of travel of each pair, radians: B − A, or for a pair with no distance between
     * them (a dwell where only the tip turned) the dab's own recorded travel, else the last known one.
     */
    private fun travel(t: List<Placed>): DoubleArray {
        val m = t.size - 1
        val a = DoubleArray(m) { Double.NaN }
        for (i in 0 until m) {
            val dx = t[i + 1].hull.cx - t[i].hull.cx
            val dy = t[i + 1].hull.cy - t[i].hull.cy
            if (hypot(dx, dy) > 1e-9) {
                a[i] = atan2(dy, dx)
            } else {
                val d = t[i + 1].dab
                if (d.travelKnown && (d.travelX != 0f || d.travelY != 0f)) a[i] = atan2(d.travelY.toDouble(), d.travelX.toDouble())
            }
        }
        var known = a.firstOrNull { !it.isNaN() } ?: 0.0
        for (i in 0 until m) if (a[i].isNaN()) a[i] = known else known = a[i]
        return a
    }

    /**
     * The common tangent of [a] then [b] on [side] of travel [alpha]. Directions on that side are
     * `φ(τ) = alpha + side·π·(1 − τ)`: τ = 0 is straight behind, τ = 1 straight ahead. Behind, A
     * reaches further (`g = h_A − h_B > 0`); ahead, B does. The tangent is the τ where g changes from
     * + to −; with several such changes (two tips turning in place), the one nearest the side normal.
     * With none (cannot happen for two tips neither of which contains the other, short of sampling
     * ties), the side normal itself.
     */
    private fun bitangent(a: TipHull, b: TipHull, alpha: Double, side: Int): Double {
        fun g(tau: Double): Double {
            val phi = alpha + side * PI * (1 - tau)
            val ux = cos(phi)
            val uy = sin(phi)
            return a.h(ux, uy) - b.h(ux, uy)
        }
        val n = SAMPLES
        var prev = g(0.0)
        var best = Double.NaN
        var bestLo = 0.0
        var bestHi = 0.0
        for (k in 1..n) {
            val tau = k.toDouble() / n
            val cur = g(tau)
            if (prev > 0.0 && cur <= 0.0) {
                val lo = (k - 1).toDouble() / n
                val mid = (lo + tau) / 2
                if (best.isNaN() || abs(mid - 0.5) < abs(best - 0.5)) { best = mid; bestLo = lo; bestHi = tau }
            }
            prev = cur
        }
        val tau = if (best.isNaN()) 0.5 else {
            var lo = bestLo
            var hi = bestHi
            repeat(40) {
                val mid = (lo + hi) / 2
                if (g(mid) > 0.0) lo = mid else hi = mid
            }
            (lo + hi) / 2
        }
        return alpha + side * PI * (1 - tau)
    }

    /**
     * How a side's direction turns across one dab, from the tangent with the dab before ([from]) to
     * the one with the dab after ([to]): the short way round. An exact reversal (a zero-radius
     * hairpin) has no short way; it is taken through the forward direction, which is the side's
     * outer edge of the turn.
     */
    private fun sideSweep(from: Double, to: Double, side: Int): Double {
        var d = (to - from) % (2 * PI)
        if (d > PI) d -= 2 * PI
        if (d < -PI) d += 2 * PI
        if (abs(d) > PI - 1e-3) d = -side * abs(d)
        return d
    }

    /** B ⊆ A, judged on the support function at [SAMPLES]·2 directions, to a nanometre-scale tolerance. */
    private fun contains(a: TipHull, b: TipHull): Boolean {
        val eps = 1e-7 * max(1.0, a.reach)
        for (k in 0 until 2 * SAMPLES) {
            val phi = 2 * PI * k / (2 * SAMPLES)
            val ux = cos(phi)
            val uy = sin(phi)
            if (b.h(ux, uy) > a.h(ux, uy) + eps) return false
        }
        return true
    }

    /** A and B do not touch: separated along the line between their centres. */
    private fun apart(a: TipHull, b: TipHull): Boolean {
        val dx = b.cx - a.cx
        val dy = b.cy - a.cy
        val l = hypot(dx, dy)
        if (l < 1e-9) return false
        val ux = dx / l
        val uy = dy / l
        return a.h(ux, uy) < -b.h(-ux, -uy)
    }

    /** Directions sampled per half-turn when looking for a tangent. */
    private const val SAMPLES = 32

    /** A point list that drops exact and near-exact repeats as it grows. */
    private class Ring {
        private val pts = ArrayList<Pt>()
        private val p = DoubleArray(2)

        fun add(x: Double, y: Double) {
            val last = pts.lastOrNull()
            if (last != null && abs(last.x - x) < 1e-9 && abs(last.y - y) < 1e-9) return
            pts.add(Pt(x, y))
        }

        fun support(h: TipHull, phi: Double) {
            h.support(cos(phi), sin(phi), p)
            add(p[0], p[1])
        }

        fun closed(): List<Pt> {
            while (pts.size > 1 && abs(pts[0].x - pts.last().x) < 1e-9 && abs(pts[0].y - pts.last().y) < 1e-9) {
                pts.removeAt(pts.size - 1)
            }
            return pts
        }
    }
}
