package cc.joycreator.joybrush.core.shape

import cc.joycreator.joybrush.core.input.PenSample
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Hold-to-shape, part 2: move the stroke onto the perfect shape while keeping its feel (JB-2.10).
 *
 * Only x and y change. Pressure, tilt, azimuth, barrel, tool and time stay exactly as drawn, and each
 * sample lands at the same FRACTION of the way along the shape as it was along the stroke — so a
 * press that swelled a third of the way round the circle still swells a third of the way round.
 */
object ShapePerfecter {

    private const val ELLIPSE_SAMPLES = 720

    /**
     * Moves every sample onto [shape], keeping its pressure, tilt, azimuth, barrel, tool and time.
     * Sample i goes to the point at the same FRACTION OF ARC LENGTH along the shape as it had along
     * the original stroke. Returns exactly points.size samples.
     */
    fun perfect(points: List<PenSample>, shape: Shape): List<PenSample> {
        if (points.isEmpty()) return emptyList()
        val f = fractions(points)
        val place: (Double) -> Pt = when (shape) {
            is Shape.Line -> { t ->
                Pt(shape.a.x + (shape.b.x - shape.a.x) * t, shape.a.y + (shape.b.y - shape.a.y) * t)
            }
            is Shape.Arc -> { t ->
                val a = shape.startAngle + shape.sweep * t
                Pt(shape.center.x + shape.radius * cos(a), shape.center.y + shape.radius * sin(a))
            }
            is Shape.Polygon -> closedPath(shape.corners)
            is Shape.Ellipse -> closedPath(ellipsePath(shape, points))
        }
        return points.mapIndexed { i, s ->
            val q = place(f[i])
            s.copy(x = q.x.toFloat(), y = q.y.toFloat())
        }
    }

    /** Cumulative arc-length fraction of each original sample, 0 … 1 (all 0 for a zero-length stroke). */
    private fun fractions(points: List<PenSample>): DoubleArray {
        val f = DoubleArray(points.size)
        for (i in 1 until points.size) {
            val dx = (points[i].x - points[i - 1].x).toDouble()
            val dy = (points[i].y - points[i - 1].y).toDouble()
            val d = kotlin.math.hypot(dx, dy)
            f[i] = f[i - 1] + if (d.isFinite()) d else 0.0
        }
        val total = f[f.size - 1]
        if (total > 0.0) for (i in f.indices) f[i] /= total
        return f
    }

    /** Arc-length placement around a closed polygon, from vertex 0 and back to it. */
    private fun closedPath(corners: List<Pt>): (Double) -> Pt {
        val cum = Geometry.closedCumulative(corners)
        val perimeter = cum[cum.size - 1]
        return { t -> Geometry.pointAlong(corners, cum, t * perimeter) }
    }

    /**
     * The ellipse as [ELLIPSE_SAMPLES] points, starting at the angle of the first sample and going the
     * way the stroke was drawn (the sign of its shoelace area).
     */
    private fun ellipsePath(e: Shape.Ellipse, points: List<PenSample>): List<Pt> {
        val c = cos(e.rotation); val s = sin(e.rotation)
        val dx = points[0].x - e.center.x; val dy = points[0].y - e.center.y
        val t0 = atan2((-s * dx + c * dy) / e.ry, (c * dx + s * dy) / e.rx)
        val drawn = Geometry.shoelace(points.map { Pt(it.x.toDouble(), it.y.toDouble()) })
        val dir = if (drawn < 0) -1.0 else 1.0
        return List(ELLIPSE_SAMPLES) { i ->
            val t = t0 + dir * 2 * PI * i / ELLIPSE_SAMPLES
            val u = e.rx * cos(t); val v = e.ry * sin(t)
            Pt(e.center.x + c * u - s * v, e.center.y + s * u + c * v)
        }
    }
}
