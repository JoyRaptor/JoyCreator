package cc.joycreator.joybrush.core.shape

import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.shape.Geometry.dist
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Hold-to-shape, part 1: is this rough stroke a line, arc, ellipse, triangle or rectangle? (JB-2.10)
 *
 * Every tolerance is in SCREEN px, so a circle is recognised the same way at any zoom. The steps and
 * numbers are the spec's Decisions 1–8; the three places this goes beyond the spec text are marked
 * "Lead:" and recorded in the spec.
 */
object ShapeRecognizer {

    private const val MIN_POINTS = 5
    private const val MIN_LENGTH = 12.0
    private const val CLOSE_MIN = 20.0
    private const val CLOSE_FRACTION = 0.12
    private const val RDP_MIN = 3.0
    private const val RDP_FRACTION = 0.035
    private const val MERGE_TURN = 25.0 * PI / 180
    private const val LINE_DEVIATION = 0.04
    private const val MIN_SIDE_FRACTION = 0.15
    private const val RIGHT_ANGLE_SLACK = 18.0 * PI / 180
    private const val ELLIPSE_RESIDUAL = 0.10
    private const val CIRCLE_RATIO = 0.88
    private const val ARC_RESIDUAL = 0.05
    private const val ARC_MAX_SWEEP = 330.0 * PI / 180
    private const val ARC_MAX_RADIUS_PER_DIAGONAL = 4.0

    /**
     * @param points the stroke's (smoothed) samples in document px, in drawing order.
     * @param screenPerDoc current zoom; tolerances are in SCREEN px so recognition feels the same at any zoom.
     * @return the recognised shape, or null when the stroke is not clearly a shape (leave it alone).
     */
    fun recognize(points: List<PenSample>, screenPerDoc: Float = 1f): Shape? {
        val k = screenPerDoc.toDouble()
        if (!(k > 0.0) || !k.isFinite()) return null
        val p = points.filter { it.x.isFinite() && it.y.isFinite() }.map { Pt(it.x * k, it.y * k) }
        // 1. Too small.
        if (p.size < MIN_POINTS) return null
        val length = Geometry.pathLength(p)
        if (length < MIN_LENGTH) return null
        val diagonal = Geometry.bboxDiagonal(p)

        // 2. Closed?
        val closeDist = max(CLOSE_MIN, CLOSE_FRACTION * length)
        val closed = dist(p.first(), p.last()) < closeDist

        // 3. Simplify.
        val eps = max(RDP_MIN, RDP_FRACTION * diagonal)
        val v = simplify(p, eps, closed, closeDist)

        val shape = if (closed) {
            polygon(p, v, length) ?: ellipse(p, length)
        } else {
            line(p, v) ?: arc(p, diagonal)
        }
        return shape?.let { scale(it, 1.0 / k) }
    }

    /** Decision 3: RDP, drop the closure, then merge nearly-straight vertices. Returns vertex points. */
    private fun simplify(p: List<Pt>, eps: Double, closed: Boolean, closeDist: Double): List<Pt> {
        val v = Geometry.rdp(p, eps).map { p[it] }.toMutableList()
        if (closed) {
            // The spec's closure rule: the final vertex is the closure if it is near the first.
            if (v.size > 1 && dist(v.last(), v.first()) < closeDist) v.removeAt(v.size - 1)
            // Lead: an overshoot past the start leaves ANOTHER copy of the start corner just before
            // it. Drop trailing vertices that sit on the start corner (within 2 eps) too.
            while (v.size > 1 && dist(v.last(), v.first()) < 2 * eps) v.removeAt(v.size - 1)
        }
        // Merge nearly-straight runs, repeating until none is removed.
        // Lead: a closed stroke is merged CYCLICALLY, vertex 0 included, so a rectangle begun in the
        // middle of a side still has four corners (the start point is not a corner).
        var removed = true
        while (removed && v.size > 2) {
            removed = false
            val range = if (closed) v.indices else 1 until v.size - 1
            for (i in range) {
                val a = v[(i - 1 + v.size) % v.size]
                val b = v[i]
                val c = v[(i + 1) % v.size]
                if (Geometry.turnAngle(a, b, c) < MERGE_TURN) {
                    v.removeAt(i)
                    removed = true
                    break
                }
            }
        }
        return v
    }

    /** Decision 4. */
    private fun line(p: List<Pt>, v: List<Pt>): Shape? {
        if (v.size != 2) return null
        val a = p.first(); val b = p.last()
        val chord = dist(a, b)
        if (chord <= 0.0) return null
        val worst = p.maxOf { Geometry.segmentDistance(it, a, b) }
        return if (worst < LINE_DEVIATION * chord) Shape.Line(a, b) else null
    }

    /** Decision 5. */
    private fun polygon(p: List<Pt>, v: List<Pt>, length: Double): Shape? {
        val n = v.size
        if (n != 3 && n != 4) return null
        for (i in 0 until n) if (dist(v[i], v[(i + 1) % n]) < MIN_SIDE_FRACTION * length / n) return null
        if (n == 4) rectangle(p, v)?.let { return it }
        return Shape.Polygon(v)
    }

    /** The rectangle snap of Decision 5, or null when an interior angle is not within 90° ± 18°. */
    private fun rectangle(p: List<Pt>, v: List<Pt>): Shape.Polygon? {
        for (i in 0 until 4) {
            val turn = Geometry.turnAngle(v[(i + 3) % 4], v[i], v[(i + 1) % 4])
            // The interior angle is π − turn; within 90° ± 18° ⇔ turn within 90° ± 18°.
            if (abs(turn - PI / 2) > RIGHT_ANGLE_SLACK) return null
        }
        // θ = mean edge direction folded into [0, 90°).
        var sc = 0.0; var ss = 0.0
        for (i in 0 until 4) {
            val a = v[i]; val b = v[(i + 1) % 4]
            val phi = atan2(b.y - a.y, b.x - a.x)
            sc += cos(4 * phi); ss += sin(4 * phi)
        }
        val theta = atan2(ss, sc) / 4
        val ux = cos(theta); val uy = sin(theta)
        val vx = -uy; val vy = ux
        val cx = v.sumOf { it.x } / 4; val cy = v.sumOf { it.y } / 4
        val widths = ArrayList<Double>(); val heights = ArrayList<Double>()
        for (i in 0 until 4) {
            val ex = v[(i + 1) % 4].x - v[i].x; val ey = v[(i + 1) % 4].y - v[i].y
            val alongU = abs(ex * ux + ey * uy); val alongV = abs(ex * vx + ey * vy)
            if (alongU >= alongV) widths += alongU else heights += alongV
        }
        if (widths.size != 2 || heights.size != 2) return null
        val hw = widths.average() / 2; val hh = heights.average() / 2
        // Positive orientation in (u, v), which is a rotation, so positive shoelace.
        var corners = listOf(
            Pt(cx + ux * hw + vx * hh, cy + uy * hw + vy * hh),
            Pt(cx - ux * hw + vx * hh, cy - uy * hw + vy * hh),
            Pt(cx - ux * hw - vx * hh, cy - uy * hw - vy * hh),
            Pt(cx + ux * hw - vx * hh, cy + uy * hw - vy * hh),
        )
        if (Geometry.shoelace(p) < 0) corners = corners.reversed()
        val first = p.first()
        val start = corners.indices.minBy { dist(corners[it], first) }
        return Shape.Polygon(List(4) { corners[(start + it) % 4] })
    }

    /** Decision 6. */
    private fun ellipse(p: List<Pt>, length: Double): Shape? {
        val lap = oneLap(p, length)
        val f = Geometry.fitEllipse(lap) ?: return null
        if (Geometry.ellipseResidual(lap, f) >= ELLIPSE_RESIDUAL) return null
        val centre = Pt(f.cx, f.cy)
        if (min(f.rx, f.ry) / max(f.rx, f.ry) > CIRCLE_RATIO) {
            val r = (f.rx + f.ry) / 2
            return Shape.Ellipse(centre, r, r, 0.0)
        }
        // Keep the rotation in [0, π): an ellipse is the same shape turned half a turn.
        var rot = f.rotation % PI
        if (rot < 0) rot += PI
        return Shape.Ellipse(centre, f.rx, f.ry, rot)
    }

    /**
     * Lead: a closed stroke usually overlaps its start by a few percent, and that overlap is drawn
     * twice — it would pull the centre towards itself. Cut the stroke at the point of its second
     * half nearest the start, i.e. after exactly one lap.
     */
    private fun oneLap(p: List<Pt>, length: Double): List<Pt> {
        val cum = Geometry.cumulative(p)
        var best = p.size - 1
        var bestD = Double.MAX_VALUE
        for (i in p.indices) {
            if (cum[i] < 0.5 * length) continue
            val d = dist(p[i], p[0])
            if (d < bestD) { bestD = d; best = i }
        }
        return p.subList(0, best + 1)
    }

    /** Decision 7. */
    private fun arc(p: List<Pt>, diagonal: Double): Shape? {
        val c = Geometry.fitCircle(p) ?: return null
        val r = c.r
        val centre = Pt(c.cx, c.cy)
        val residual = p.sumOf { abs(dist(it, centre) - r) } / p.size
        if (residual >= ARC_RESIDUAL * r) return null
        if (r >= ARC_MAX_RADIUS_PER_DIAGONAL * diagonal) return null
        val start = atan2(p[0].y - c.cy, p[0].x - c.cx)
        var sweep = 0.0
        var prev = start
        for (i in 1 until p.size) {
            val a = atan2(p[i].y - c.cy, p[i].x - c.cx)
            sweep += Geometry.wrap(a - prev)
            prev = a
        }
        if (abs(sweep) >= ARC_MAX_SWEEP) return null
        return Shape.Arc(centre, r, start, sweep)
    }

    private fun scale(s: Shape, f: Double): Shape {
        fun q(p: Pt) = Pt(p.x * f, p.y * f)
        return when (s) {
            is Shape.Line -> Shape.Line(q(s.a), q(s.b))
            is Shape.Ellipse -> Shape.Ellipse(q(s.center), s.rx * f, s.ry * f, s.rotation)
            is Shape.Arc -> Shape.Arc(q(s.center), s.radius * f, s.startAngle, s.sweep)
            is Shape.Polygon -> Shape.Polygon(s.corners.map(::q))
        }
    }
}
