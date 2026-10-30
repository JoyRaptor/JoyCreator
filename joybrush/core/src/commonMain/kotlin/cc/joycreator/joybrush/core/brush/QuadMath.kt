package cc.joycreator.joybrush.core.brush

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

@Serializable data class QuadPoint(val x: Float, val y: Float)

/** One resolved shape, in normalized tip coordinates. The cache is not part of value equality. */
data class QuadDab(
    val x0: Float, val y0: Float, val x1: Float, val y1: Float,
    val x2: Float, val y2: Float, val x3: Float, val y3: Float,
    val roundness: Float,
) {
    val prepared: QuadMath.Prepared by lazy(LazyThreadSafetyMode.PUBLICATION) { QuadMath.build(this) }
}

/**
 * A rounded closed path, with even-odd fill (also for a crossing quad). Straight/backtracking
 * vertices are removed before distance evaluation, so canceled retraced edges cannot leave hairlines.
 * Corner caps are symmetric differences: each replaces V-P/V-Q with the minor tangent-circle arc.
 */
object QuadMath {
    /** Duplicate distance relative to the largest bounding-box span. Shared GPU contract. */
    const val DUPLICATE_REL = 1e-7
    /** |cross(a,b)| <= this for unit adjacent rays means collinear, including backtracking. */
    const val COLLINEAR_SIN_EPS = 1e-7
    /** Normalized numerical floor: at the largest brush this is under 0.0005 target pixel. */
    const val NUMERICAL_FLOOR = 1e-7
    private const val CIRCLE_REL = 1e-12

    fun prepare(quad: QuadDab): Prepared = quad.prepared

    /** Scalar snapshots for fixture/GPU adapters; no mutable arrays escape Prepared. */
    data class Corner(
        val vertexX: Double, val vertexY: Double,
        val inX: Double, val inY: Double, val outX: Double, val outY: Double,
        val centerX: Double, val centerY: Double, val radius: Double,
        val unitInX: Double, val unitInY: Double, val unitOutX: Double, val unitOutY: Double,
        val arcSign: Int,
    ) {
        val rounded: Boolean get() = radius > 0.0
    }

    class Prepared internal constructor(
        private val data: Array<Corner>,
        val roundness: Float,
        val circle: Boolean,
        val circleCenterX: Double,
        val circleCenterY: Double,
        val circleRadius: Double,
        private val span: Double,
    ) {
        val count: Int get() = data.size
        val empty: Boolean get() = data.size < 3
        val points: List<QuadPoint> = data.map { QuadPoint(it.vertexX.toFloat(), it.vertexY.toFloat()) }
        val corners: List<Corner> = data.toList()
        val normalizationScale: Float = referenceDepth()

        fun x(index: Int): Float = data[index].vertexX.toFloat()
        fun y(index: Int): Float = data[index].vertexY.toFloat()

        /** No allocations or curve/geometry preparation on this pixel path. Empty paths draw nothing. */
        fun signedDistance(x: Float, y: Float): Float =
            if (!x.isFinite() || !y.isFinite()) Float.POSITIVE_INFINITY else distance(x.toDouble(), y.toDouble()).toFloat()

        private fun distance(x: Double, y: Double): Double {
            if (empty) return Double.POSITIVE_INFINITY
            if (circle) return hypot(x - circleCenterX, y - circleCenterY) - circleRadius
            var inside = false
            var best = Double.POSITIVE_INFINITY
            for (i in data.indices) {
                val c = data[i]
                val next = data[(i + 1) % count]
                if ((c.vertexY > y) != (next.vertexY > y)) {
                    val hitX = c.vertexX + (y - c.vertexY) *
                        (next.vertexX - c.vertexX) / (next.vertexY - c.vertexY)
                    if (x < hitX) inside = !inside
                }
                best = min(best, segmentSquared(x, y, c.outX, c.outY, next.inX, next.inY))
            }
            for (c in data) {
                if (!c.rounded) continue
                // Unit endpoint radii avoid subtracting a huge center near a straight corner.
                val inArc = cross(c.unitInX, c.unitInY, x - c.inX, y - c.inY) * c.arcSign >= 0.0 &&
                    cross(x - c.outX, y - c.outY, c.unitOutX, c.unitOutY) * c.arcSign >= 0.0
                val radial = if (inArc || inCapTriangle(x, y, c)) circleDistance(x, y, c) else 0.0
                if (inArc) best = min(best, radial * radial)
                else best = min(best, min(squared(x - c.inX, y - c.inY), squared(x - c.outX, y - c.outY)))
                if (radial > 0.0 && inCapTriangle(x, y, c)) inside = !inside
            }
            val result = sqrt(max(best, 0.0))
            return if (inside) -result else result
        }

        private fun referenceDepth(): Float {
            if (empty) return 1f
            if (circle) return positiveFloat(circleRadius)
            var depth = 0.0
            fun probe(x: Double, y: Double) { depth = max(depth, -distance(x, y)) }
            probe(data.sumOf { it.vertexX } / count, data.sumOf { it.vertexY } / count)
            for (i in 0 until count) for (j in i + 1 until count) for (k in j + 1 until count) {
                probe((data[i].vertexX + data[j].vertexX + data[k].vertexX) / 3.0,
                    (data[i].vertexY + data[j].vertexY + data[k].vertexY) / 3.0)
            }
            // A symmetric bow tie's ordinary triangle centroids all lie on its diagonals.
            // Its crossing plus each original edge gives the actual lobe-triangle centroids.
            for (i in 0 until count) for (j in i + 1 until count) {
                if (j == i + 1 || (i == 0 && j == count - 1)) continue
                val a = data[i]; val b = data[(i + 1) % count]
                val c = data[j]; val d = data[(j + 1) % count]
                val ux = b.vertexX - a.vertexX; val uy = b.vertexY - a.vertexY
                val vx = d.vertexX - c.vertexX; val vy = d.vertexY - c.vertexY
                val det = cross(ux, uy, vx, vy)
                if (det == 0.0) continue
                val wx = c.vertexX - a.vertexX; val wy = c.vertexY - a.vertexY
                val t = cross(wx, wy, vx, vy) / det
                val u = cross(wx, wy, ux, uy) / det
                if (t !in 0.0..1.0 || u !in 0.0..1.0) continue
                val ix = a.vertexX + ux * t; val iy = a.vertexY + uy * t
                for (k in data.indices) {
                    val p = data[k]; val q = data[(k + 1) % count]
                    probe((ix + p.vertexX + q.vertexX) / 3.0, (iy + p.vertexY + q.vertexY) / 3.0)
                }
            }
            if (depth <= 0.0) {
                // Deterministic fallback for unusual cap overlaps: probe both sides of two
                // non-midpoint edge locations (a crossing may lie exactly at the midpoint).
                for (i in data.indices) {
                    val a = data[i]; val b = data[(i + 1) % count]
                    val dx = b.inX - a.outX; val dy = b.inY - a.outY
                    val length = hypot(dx, dy)
                    if (length == 0.0) continue
                    for (fraction in listOf(1.0 / 3.0, 2.0 / 3.0)) {
                        val px = a.outX + dx * fraction; val py = a.outY + dy * fraction
                        var step = min(length, span) * .125
                        repeat(48) {
                            probe(px - dy / length * step, py + dx / length * step)
                            probe(px + dy / length * step, py - dx / length * step)
                            step *= .5
                        }
                    }
                }
            }
            // Positive and finite even at Float's representational limits. The fallback is
            // only for numerically collapsed interiors, after all deterministic probes.
            return positiveFloat(if (depth > 0.0) depth else span * DUPLICATE_REL)
        }
    }

    internal fun build(q: QuadDab): Prepared {
        val points = mutableListOf(QuadPoint(q.x0, q.y0), QuadPoint(q.x1, q.y1),
            QuadPoint(q.x2, q.y2), QuadPoint(q.x3, q.y3))
        val rounded = if (q.roundness.isFinite()) q.roundness.coerceIn(0f, 1f) else 0f
        if (points.any { !it.x.isFinite() || !it.y.isFinite() }) return blank(rounded)
        val span = max(points.maxOf { it.x.toDouble() } - points.minOf { it.x.toDouble() },
            points.maxOf { it.y.toDouble() } - points.minOf { it.y.toDouble() })
        if (span < NUMERICAL_FLOOR) return blank(rounded)
        val duplicate = max(span * DUPLICATE_REL, NUMERICAL_FLOOR)
        val duplicateSquared = duplicate * duplicate
        var changed = true
        while (changed && points.size >= 3) {
            changed = false
            for (i in points.indices) {
                val j = (i + 1) % points.size
                if (squared(points[i].x.toDouble() - points[j].x, points[i].y.toDouble() - points[j].y) <= duplicateSquared) {
                    points.removeAt(j); changed = true; break
                }
            }
            if (changed) continue
            for (i in points.indices) {
                val p = points[(i + points.size - 1) % points.size]
                val v = points[i]; val n = points[(i + 1) % points.size]
                val ax = p.x.toDouble() - v.x; val ay = p.y.toDouble() - v.y
                val bx = n.x.toDouble() - v.x; val by = n.y.toDouble() - v.y
                if (abs(cross(ax, ay, bx, by)) <= COLLINEAR_SIN_EPS * hypot(ax, ay) * hypot(bx, by)) {
                    points.removeAt(i); changed = true; break
                }
            }
        }
        if (points.size < 3) return blank(rounded)
        val corners = Array(points.size) { i -> corner(points[(i + points.size - 1) % points.size],
            points[i], points[(i + 1) % points.size], rounded.toDouble()) }
        val circle = rounded == 1f && isSquare(points)
        val cx = if (circle) points.sumOf { it.x.toDouble() } / 4.0 else 0.0
        val cy = if (circle) points.sumOf { it.y.toDouble() } / 4.0 else 0.0
        val radius = if (circle) hypot(points[1].x.toDouble() - points[0].x, points[1].y.toDouble() - points[0].y) * .5 else 0.0
        return Prepared(corners, rounded, circle, cx, cy, radius, span)
    }

    private fun blank(roundness: Float) = Prepared(emptyArray(), roundness, false, 0.0, 0.0, 0.0, 1.0)

    private fun corner(prev: QuadPoint, v: QuadPoint, next: QuadPoint, roundness: Double): Corner {
        val x = v.x.toDouble(); val y = v.y.toDouble()
        val ax0 = prev.x.toDouble() - x; val ay0 = prev.y.toDouble() - y
        val bx0 = next.x.toDouble() - x; val by0 = next.y.toDouble() - y
        val la = hypot(ax0, ay0); val lb = hypot(bx0, by0)
        fun sharp() = Corner(x, y, x, y, x, y, x, y, 0.0, 0.0, 0.0, 0.0, 0.0, 0)
        if (roundness <= 0.0 || la == 0.0 || lb == 0.0) return sharp()
        val ax = ax0 / la; val ay = ay0 / la; val bx = bx0 / lb; val by = by0 / lb
        val cross = cross(ax, ay, bx, by)
        if (abs(cross) <= COLLINEAR_SIN_EPS) return sharp()
        val dot = (ax * bx + ay * by).coerceIn(-1.0, 1.0)
        val tangent = roundness * .5 * min(la, lb)
        // Algebraically tan(theta/2), choosing the form without near-zero subtraction.
        val radius = tangent * if (dot < 0.0) (1.0 - dot) / abs(cross) else abs(cross) / (1.0 + dot)
        val sign = if (cross > 0.0) 1 else -1
        val ux = sign * ay; val uy = -sign * ax
        val vx = -sign * by; val vy = sign * bx
        val px = x + ax * tangent; val py = y + ay * tangent
        val qx = x + bx * tangent; val qy = y + by * tangent
        // Never XOR numerically collapsed caps, or let their tiny radii overflow the
        // GPU's radial calculation.
        if (abs(cross(px - x, py - y, qx - x, qy - y)) <= NUMERICAL_FLOOR * NUMERICAL_FLOOR) return sharp()
        return Corner(x, y, px, py, qx, qy, px - radius * ux, py - radius * uy,
            radius, ux, uy, vx, vy, -sign)
    }

    private fun isSquare(points: List<QuadPoint>): Boolean {
        if (points.size != 4) return false
        val edge = Array(4) { i ->
            val a = points[i]; val b = points[(i + 1) % 4]
            (b.x.toDouble() - a.x) to (b.y.toDouble() - a.y)
        }
        val length = squared(edge[0].first, edge[0].second)
        if (length <= 0.0) return false
        var turn = 0
        for (i in 0..3) {
            val a = edge[i]; val b = edge[(i + 1) % 4]
            if (abs(squared(a.first, a.second) - length) > CIRCLE_REL * length) return false
            if (abs(a.first * b.first + a.second * b.second) > CIRCLE_REL * length) return false
            val sign = if (cross(a.first, a.second, b.first, b.second) > 0.0) 1 else -1
            if (turn != 0 && turn != sign) return false
            turn = sign
        }
        return true
    }

    private fun inCapTriangle(x: Double, y: Double, c: Corner): Boolean {
        // The cap and original polygon share V-P/V-Q. Their boundary classifications must
        // cancel under XOR, including at V itself: use the polygon's same half-open ray rule,
        // rather than inclusive triangle half-planes that would toggle an already-outside V.
        return rayCrosses(x, y, c.vertexX, c.vertexY, c.inX, c.inY) xor
            rayCrosses(x, y, c.inX, c.inY, c.outX, c.outY) xor
            rayCrosses(x, y, c.outX, c.outY, c.vertexX, c.vertexY)
    }

    private fun rayCrosses(x: Double, y: Double, ax: Double, ay: Double, bx: Double, by: Double): Boolean =
        (ay > y) != (by > y) && x < ax + (y - ay) * (bx - ax) / (by - ay)

    private fun circleDistance(x: Double, y: Double, c: Corner): Double {
        val wx = x - c.inX; val wy = y - c.inY
        // Same rationalized expression as jb_quad.glsl, avoiding huge-center subtraction.
        return (squared(wx, wy) + 2.0 * c.radius * (wx * c.unitInX + wy * c.unitInY)) /
            (hypot(wx + c.radius * c.unitInX, wy + c.radius * c.unitInY) + c.radius)
    }

    private fun segmentSquared(x: Double, y: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax; val dy = by - ay
        val length = squared(dx, dy)
        val t = if (length == 0.0) 0.0 else ((x - ax) * dx + (y - ay) * dy) / length
        val s = t.coerceIn(0.0, 1.0)
        return squared(x - ax - dx * s, y - ay - dy * s)
    }

    private fun cross(ax: Double, ay: Double, bx: Double, by: Double) = ax * by - ay * bx
    private fun squared(x: Double, y: Double) = x * x + y * y
    private fun positiveFloat(value: Double): Float = value.coerceIn(NUMERICAL_FLOOR, Float.MAX_VALUE.toDouble()).toFloat()
}
