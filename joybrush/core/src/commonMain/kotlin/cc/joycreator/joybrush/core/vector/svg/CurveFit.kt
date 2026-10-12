package cc.joycreator.joybrush.core.vector.svg

import cc.joycreator.joybrush.core.shape.Pt
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.hypot

/**
 * The two pure steps between a dense outline and SVG path data: Ramer–Douglas–Peucker on a CLOSED
 * ring, then cubic Béziers fitted with Schneider's algorithm (Graphics Gems I, "An Algorithm for
 * Automatically Fitting Digitized Curves", 1990) between corners.
 *
 * Error budget, stated once: RDP keeps every dropped point within `simplifyPx` of the kept polyline,
 * and the fit keeps every point of that polyline (densified to at most [DENSIFY_PX] between kept
 * vertices, so the bound holds between vertices and not only at them) within `fitPx` of the curve,
 * measured at the point's own curve parameter — an overestimate of the true distance. So the curve
 * is within `simplifyPx + fitPx` of the dense outline. `StrokeOutlineTest` measures it.
 */
internal object CurveFit {

    /** The chord spacing the fit is checked at, doc px. */
    const val DENSIFY_PX = 1.0

    /** A vertex whose path turns by more than this (radians) is a corner: the fit may break its tangent there. */
    const val CORNER_RAD = PI / 3

    // ── RDP ──────────────────────────────────────────────────────────────────────────────

    /**
     * [ring] (closed: the last point joins the first, not repeated) simplified to [tolerance]. The
     * two anchors are point 0 and the point furthest from it, so the result does not depend on a
     * seam chosen elsewhere. Iterative, so a 100 000-point ring cannot overflow the stack.
     */
    fun simplifyClosed(ring: List<Pt>, tolerance: Double): List<Pt> {
        val n = ring.size
        if (n <= 3) return ring
        var far = 0
        var best = -1.0
        for (i in 1 until n) {
            val d = dist(ring[0], ring[i])
            if (d > best) { best = d; far = i }
        }
        val keep = BooleanArray(n)
        keep[0] = true
        keep[far] = true
        val stack = ArrayList<IntArray>()
        stack.add(intArrayOf(0, far))
        stack.add(intArrayOf(far, n)) // index n is point 0 again
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeAt(stack.size - 1).let { it[0] to it[1] }
            if (b - a < 2) continue
            val pa = ring[a]
            val pb = ring[b % n]
            var worst = -1.0
            var at = -1
            for (i in a + 1 until b) {
                val d = segmentDistance(ring[i], pa, pb)
                if (d > worst) { worst = d; at = i }
            }
            if (worst > tolerance) {
                keep[at] = true
                stack.add(intArrayOf(a, at))
                stack.add(intArrayOf(at, b))
            }
        }
        val out = ArrayList<Pt>()
        for (i in 0 until n) if (keep[i]) out.add(ring[i])
        return out
    }

    // ── fitting ──────────────────────────────────────────────────────────────────────────

    /**
     * A closed polyline as one closed run of segments starting and ending at the returned start
     * point. Corners (turn > [CORNER_RAD]) keep their sharpness; everywhere else the curve is G1.
     */
    fun fitClosed(poly: List<Pt>, error: Double): StrokeOutline.Contour {
        val n = poly.size
        if (n < 3) {
            val segs = ArrayList<StrokeOutline.Segment>()
            for (i in 1..n) segs.add(line(poly[i - 1], poly[i % n]))
            return StrokeOutline.Contour(poly[0], segs)
        }
        val corners = (0 until n).filter { turn(poly[(it - 1 + n) % n], poly[it], poly[(it + 1) % n]) > CORNER_RAD }
        val segments = ArrayList<StrokeOutline.Segment>()
        if (corners.isEmpty()) {
            val data = densified(poly, 0, n)
            // Smooth everywhere: the seam at point 0 gets the same centred tangent on both sides.
            val t = centreTangent(poly[n - 1], poly[0], poly[1])
            fitCubic(data, 0, data.size - 1, t, Pt(-t.x, -t.y), error, segments)
            return StrokeOutline.Contour(poly[0], segments)
        }
        for (k in corners.indices) {
            val a = corners[k]
            val b = if (k + 1 < corners.size) corners[k + 1] else corners[0] + n
            val data = densified(poly, a, b)
            fitCubic(data, 0, data.size - 1, unit(data[0], data[1]), unit(data[data.size - 1], data[data.size - 2]),
                error, segments)
        }
        return StrokeOutline.Contour(poly[corners[0]], segments)
    }

    /** The closed polyline as straight segments, no fitting: the fallback and the test reference. */
    fun polylineClosed(poly: List<Pt>): StrokeOutline.Contour {
        val segs = ArrayList<StrokeOutline.Segment>(poly.size)
        for (i in 1..poly.size) segs.add(line(poly[i - 1], poly[i % poly.size]))
        return StrokeOutline.Contour(poly[0], segs)
    }

    /** Vertices [from]..[to] of the closed [poly] (indices mod size, [to] included), chords split to <= DENSIFY_PX. */
    private fun densified(poly: List<Pt>, from: Int, to: Int): List<Pt> {
        val n = poly.size
        val out = ArrayList<Pt>()
        for (i in from until to) {
            val p = poly[i % n]
            val q = poly[(i + 1) % n]
            out.add(p)
            val pieces = ceil(dist(p, q) / DENSIFY_PX).toInt()
            for (j in 1 until pieces) {
                val t = j.toDouble() / pieces
                out.add(Pt(p.x + (q.x - p.x) * t, p.y + (q.y - p.y) * t))
            }
        }
        out.add(poly[to % n])
        return out
    }

    /**
     * Schneider's FitCubic. [t1] points from d[first] into the curve, [t2] from d[last] back into it.
     * A two-point piece is a straight segment (the Gems' heuristic cubic is not checked there, and a
     * straight chord between two data points has no error at either).
     */
    private fun fitCubic(d: List<Pt>, first: Int, last: Int, t1: Pt, t2: Pt, error: Double, out: MutableList<StrokeOutline.Segment>) {
        if (last - first == 1) {
            out.add(line(d[first], d[last]))
            return
        }
        var u = chordParams(d, first, last)
        var bez = generate(d, first, last, u, t1, t2)
        var (maxErr, split) = maxError(d, first, last, bez, u)
        if (maxErr <= error) { out.add(curve(bez)); return }
        if (maxErr <= error * 4) {
            repeat(20) {
                u = reparameterise(d, first, last, u, bez)
                bez = generate(d, first, last, u, t1, t2)
                val e = maxError(d, first, last, bez, u)
                maxErr = e.first
                split = e.second
                if (maxErr <= error) { out.add(curve(bez)); return }
            }
        }
        val tc = centreTangent(d[split - 1], d[split], d[split + 1])
        fitCubic(d, first, split, t1, Pt(-tc.x, -tc.y), error, out)
        fitCubic(d, split, last, tc, t2, error, out)
    }

    private fun chordParams(d: List<Pt>, first: Int, last: Int): DoubleArray {
        val u = DoubleArray(last - first + 1)
        for (i in first + 1..last) u[i - first] = u[i - first - 1] + dist(d[i], d[i - 1])
        val total = u[last - first]
        if (total > 0.0) for (i in u.indices) u[i] /= total
        else for (i in u.indices) u[i] = i.toDouble() / (u.size - 1)
        return u
    }

    /** Least-squares control-point lengths along the fixed end tangents (Gems' GenerateBezier). */
    private fun generate(d: List<Pt>, first: Int, last: Int, u: DoubleArray, t1: Pt, t2: Pt): Array<Pt> {
        val p0 = d[first]
        val p3 = d[last]
        var c00 = 0.0; var c01 = 0.0; var c11 = 0.0; var x0 = 0.0; var x1 = 0.0
        for (i in u.indices) {
            val t = u[i]
            val mt = 1 - t
            val b0 = mt * mt * mt; val b1 = 3 * t * mt * mt; val b2 = 3 * t * t * mt; val b3 = t * t * t
            val a0x = t1.x * b1; val a0y = t1.y * b1
            val a1x = t2.x * b2; val a1y = t2.y * b2
            c00 += a0x * a0x + a0y * a0y
            c01 += a0x * a1x + a0y * a1y
            c11 += a1x * a1x + a1y * a1y
            val p = d[first + i]
            val tx = p.x - (p0.x * (b0 + b1) + p3.x * (b2 + b3))
            val ty = p.y - (p0.y * (b0 + b1) + p3.y * (b2 + b3))
            x0 += a0x * tx + a0y * ty
            x1 += a1x * tx + a1y * ty
        }
        val det = c00 * c11 - c01 * c01
        val seg = dist(p0, p3)
        var al = if (det == 0.0) 0.0 else (x0 * c11 - x1 * c01) / det
        var ar = if (det == 0.0) 0.0 else (c00 * x1 - c01 * x0) / det
        val eps = 1e-6 * seg
        // Too short (or backwards) is the Gems' fallback; absurdly long is a loop the error check would only reject.
        if (!(al >= eps && ar >= eps && al <= 4 * seg && ar <= 4 * seg) || !al.isFinite() || !ar.isFinite()) {
            al = seg / 3
            ar = seg / 3
        }
        return arrayOf(p0, Pt(p0.x + t1.x * al, p0.y + t1.y * al), Pt(p3.x + t2.x * ar, p3.y + t2.y * ar), p3)
    }

    private fun maxError(d: List<Pt>, first: Int, last: Int, bez: Array<Pt>, u: DoubleArray): Pair<Double, Int> {
        var worst = 0.0
        var split = (first + last + 1) / 2
        for (i in first + 1 until last) {
            val p = eval(bez, u[i - first])
            val e = dist(p, d[i])
            if (e >= worst) { worst = e; split = i }
        }
        return worst to split.coerceIn(first + 1, last - 1)
    }

    /** One Newton–Raphson step per point toward the curve parameter nearest it. */
    private fun reparameterise(d: List<Pt>, first: Int, last: Int, u: DoubleArray, bez: Array<Pt>): DoubleArray {
        val out = DoubleArray(u.size)
        for (i in u.indices) {
            val t = u[i]
            val p = d[first + i]
            val q = eval(bez, t)
            val q1 = deriv1(bez, t)
            val q2 = deriv2(bez, t)
            val num = (q.x - p.x) * q1.x + (q.y - p.y) * q1.y
            val den = q1.x * q1.x + q1.y * q1.y + (q.x - p.x) * q2.x + (q.y - p.y) * q2.y
            out[i] = if (den == 0.0 || !den.isFinite()) t else (t - num / den).coerceIn(0.0, 1.0)
        }
        return out
    }

    // ── small vector maths ───────────────────────────────────────────────────────────────

    fun eval(b: Array<Pt>, t: Double): Pt {
        val mt = 1 - t
        val b0 = mt * mt * mt; val b1 = 3 * t * mt * mt; val b2 = 3 * t * t * mt; val b3 = t * t * t
        return Pt(b[0].x * b0 + b[1].x * b1 + b[2].x * b2 + b[3].x * b3, b[0].y * b0 + b[1].y * b1 + b[2].y * b2 + b[3].y * b3)
    }

    private fun deriv1(b: Array<Pt>, t: Double): Pt {
        val mt = 1 - t
        val k0 = 3 * mt * mt; val k1 = 6 * t * mt; val k2 = 3 * t * t
        return Pt((b[1].x - b[0].x) * k0 + (b[2].x - b[1].x) * k1 + (b[3].x - b[2].x) * k2,
            (b[1].y - b[0].y) * k0 + (b[2].y - b[1].y) * k1 + (b[3].y - b[2].y) * k2)
    }

    private fun deriv2(b: Array<Pt>, t: Double): Pt {
        val mt = 1 - t
        return Pt(6 * mt * (b[2].x - 2 * b[1].x + b[0].x) + 6 * t * (b[3].x - 2 * b[2].x + b[1].x),
            6 * mt * (b[2].y - 2 * b[1].y + b[0].y) + 6 * t * (b[3].y - 2 * b[2].y + b[1].y))
    }

    private fun line(a: Pt, b: Pt) = StrokeOutline.Segment(a, b, b, straight = true)
    private fun curve(b: Array<Pt>) = StrokeOutline.Segment(b[1], b[2], b[3], straight = false)

    fun dist(a: Pt, b: Pt): Double = hypot(a.x - b.x, a.y - b.y)

    fun segmentDistance(p: Pt, a: Pt, b: Pt): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        if (len2 == 0.0) return dist(p, a)
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0.0, 1.0)
        return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
    }

    /** Unit vector from [a] toward [b]; (1, 0) for a zero-length step (a tangent must be a direction). */
    private fun unit(a: Pt, b: Pt): Pt {
        val l = dist(a, b)
        return if (l == 0.0) Pt(1.0, 0.0) else Pt((b.x - a.x) / l, (b.y - a.y) / l)
    }

    /** The tangent through [m], pointing from [m] toward [next] (average of the two chord directions). */
    private fun centreTangent(prev: Pt, m: Pt, next: Pt): Pt {
        val a = unit(prev, m)
        val b = unit(m, next)
        val x = a.x + b.x
        val y = a.y + b.y
        val l = hypot(x, y)
        return if (l < 1e-12) b else Pt(x / l, y / l)
    }

    private fun turn(a: Pt, b: Pt, c: Pt): Double {
        val ux = b.x - a.x; val uy = b.y - a.y
        val vx = c.x - b.x; val vy = c.y - b.y
        val lu = hypot(ux, uy); val lv = hypot(vx, vy)
        if (lu == 0.0 || lv == 0.0) return 0.0
        return acos(((ux * vx + uy * vy) / (lu * lv)).coerceIn(-1.0, 1.0))
    }

}
