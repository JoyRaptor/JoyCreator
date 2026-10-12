package cc.joycreator.joybrush.core.vector.svg

import cc.joycreator.joybrush.core.paint.Accumulate
import cc.joycreator.joybrush.core.paint.Dab
import cc.joycreator.joybrush.core.paint.TipShape
import cc.joycreator.joybrush.core.shape.Pt
import cc.joycreator.joybrush.core.vector.InkRaster
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Test-side raster of an outline, written independently of the code under test: contours are
 * flattened into line segments and filled by a plain scanline with NONZERO winding, sampled at pixel
 * centres on the same grid `InkRaster` uses (`doc = origin + (px + 0.5) / scale`).
 */
internal object OutlineRaster {

    /** The window both rasters are drawn in. */
    data class Grid(val docX: Int, val docY: Int, val width: Int, val height: Int, val scale: Float)

    fun flatten(contours: List<StrokeOutline.Contour>, steps: Int = 24): List<List<Pt>> = contours.map { c ->
        val out = ArrayList<Pt>()
        var p = c.start
        out.add(p)
        for (s in c.segments) {
            if (s.straight) out.add(s.end) else {
                val b = arrayOf(p, s.c1, s.c2, s.end)
                for (k in 1..steps) out.add(CurveFit.eval(b, k.toDouble() / steps))
            }
            p = s.end
        }
        out
    }

    /** Nonzero-winding coverage (true = inside) of closed polygons. */
    fun fill(polys: List<List<Pt>>, g: Grid): BooleanArray {
        val out = BooleanArray(g.width * g.height)
        val xs = ArrayList<Double>()
        val ws = ArrayList<Int>()
        for (row in 0 until g.height) {
            val y = g.docY + (row + 0.5) / g.scale
            xs.clear(); ws.clear()
            for (poly in polys) for (i in poly.indices) {
                val a = poly[i]
                val b = poly[(i + 1) % poly.size]
                if (a.y == b.y) continue
                val up = b.y > a.y
                val lo = min(a.y, b.y)
                val hi = max(a.y, b.y)
                if (y < lo || y >= hi) continue
                xs.add(a.x + (y - a.y) * (b.x - a.x) / (b.y - a.y))
                ws.add(if (up) 1 else -1)
            }
            if (xs.isEmpty()) continue
            val order = xs.indices.sortedBy { xs[it] }
            var wind = 0
            for (k in 0 until order.size - 1) {
                wind += ws[order[k]]
                if (wind == 0) continue
                val x0 = xs[order[k]]
                val x1 = xs[order[k + 1]]
                // pixel centres px with x0 <= docX + (px + 0.5)/scale < x1
                val p0 = max(0, ceil((x0 - g.docX) * g.scale - 0.5).toInt())
                val p1 = min(g.width - 1, ceil((x1 - g.docX) * g.scale - 0.5).toInt() - 1)
                for (px in p0..p1) out[row * g.width + px] = true
            }
        }
        return out
    }

    /** The production raster of the dabs (hard tip, opacity 1) as alpha floats. */
    fun dabAlpha(dabs: List<Dab>, tip: TipShape, g: Grid): FloatArray {
        val px = InkRaster.premultiplied(dabs, tip.copy(hardness = 1f), Accumulate.WASH, 1f, 0xFF000000.toInt(),
            g.docX, g.docY, g.width, g.height, g.scale)!!
        return FloatArray(g.width * g.height) { px[it * 4 + 3] }
    }

    fun iou(a: BooleanArray, b: BooleanArray): Double {
        var both = 0
        var either = 0
        for (i in a.indices) {
            if (a[i] && b[i]) both++
            if (a[i] || b[i]) either++
        }
        return if (either == 0) 1.0 else both.toDouble() / either
    }

    /** Distance from [p] to the closed polyline [ring]. */
    fun distanceToRing(p: Pt, ring: List<Pt>): Double {
        var best = Double.MAX_VALUE
        for (i in ring.indices) best = min(best, CurveFit.segmentDistance(p, ring[i], ring[(i + 1) % ring.size]))
        return best
    }

    /**
     * Dabs along a path given as a dense polyline: one every `spacing × diameter` of arc length
     * (at least 0.5 px, like `DabPlacer`), radius from [radiusAt] (arc length → px), angle from
     * [angleAt] (arc length, unit travel x, y → radians).
     */
    fun dabsAlong(
        path: List<Pt>,
        spacing: Double,
        radiusAt: (Double) -> Double,
        angleAt: (Double, Double, Double) -> Double = { _, _, _ -> 0.0 },
    ): List<Dab> {
        val out = ArrayList<Dab>()
        var s = 0.0
        var next = 0.0
        for (i in 0 until path.size - 1) {
            val a = path[i]
            val b = path[i + 1]
            val l = hypot(b.x - a.x, b.y - a.y)
            if (l == 0.0) continue
            val tx = (b.x - a.x) / l
            val ty = (b.y - a.y) / l
            while (next <= s + l) {
                val t = (next - s) / l
                val r = radiusAt(next)
                out.add(Dab((a.x + (b.x - a.x) * t).toFloat(), (a.y + (b.y - a.y) * t).toFloat(), r.toFloat(),
                    angle = angleAt(next, tx, ty).toFloat(), travelX = tx.toFloat(), travelY = ty.toFloat(), travelKnown = true))
                next += max(spacing * 2 * r, 0.5)
            }
            s += l
        }
        return out
    }

    /** A dense polyline of a parametric curve, t in 0..1. */
    fun curve(n: Int, f: (Double) -> Pt): List<Pt> = (0..n).map { f(it.toDouble() / n) }

}
