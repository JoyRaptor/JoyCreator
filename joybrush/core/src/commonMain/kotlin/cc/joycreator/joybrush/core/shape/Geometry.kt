package cc.joycreator.joybrush.core.shape

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** The plain geometry under hold-to-shape (JB-2.10). Pure maths, no Joy Brush types but [Pt]. */
internal object Geometry {

    fun dist(a: Pt, b: Pt): Double = hypot(a.x - b.x, a.y - b.y)

    fun pathLength(p: List<Pt>): Double {
        var l = 0.0
        for (i in 1 until p.size) l += dist(p[i - 1], p[i])
        return l
    }

    /** Cumulative arc length at each point, starting at 0. */
    fun cumulative(p: List<Pt>): DoubleArray {
        val c = DoubleArray(p.size)
        for (i in 1 until p.size) c[i] = c[i - 1] + dist(p[i - 1], p[i])
        return c
    }

    fun bboxDiagonal(p: List<Pt>): Double {
        var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE
        var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        for (q in p) { x0 = min(x0, q.x); y0 = min(y0, q.y); x1 = max(x1, q.x); y1 = max(y1, q.y) }
        return hypot(x1 - x0, y1 - y0)
    }

    /** Distance from [p] to the segment [a]–[b]. */
    fun segmentDistance(p: Pt, a: Pt, b: Pt): Double {
        val dx = b.x - a.x; val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        if (len2 == 0.0) return dist(p, a)
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0.0, 1.0)
        return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
    }

    /** Ramer–Douglas–Peucker. Returns the indices kept, ascending, always including both ends. Iterative. */
    fun rdp(p: List<Pt>, eps: Double): List<Int> {
        if (p.size < 3) return p.indices.toList()
        val keep = BooleanArray(p.size)
        keep[0] = true; keep[p.size - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to p.size - 1)
        while (stack.isNotEmpty()) {
            val (s, e) = stack.removeLast()
            var worst = -1.0; var at = -1
            for (i in s + 1 until e) {
                val d = segmentDistance(p[i], p[s], p[e])
                if (d > worst) { worst = d; at = i }
            }
            if (at >= 0 && worst > eps) {
                keep[at] = true
                stack.addLast(s to at); stack.addLast(at to e)
            }
        }
        return p.indices.filter { keep[it] }
    }

    /** How far the path turns at [b] going [a] → [b] → [c], radians in 0..π (0 = straight on). */
    fun turnAngle(a: Pt, b: Pt, c: Pt): Double {
        val ux = b.x - a.x; val uy = b.y - a.y
        val vx = c.x - b.x; val vy = c.y - b.y
        val nu = hypot(ux, uy); val nv = hypot(vx, vy)
        if (nu == 0.0 || nv == 0.0) return 0.0
        return acos(((ux * vx + uy * vy) / (nu * nv)).coerceIn(-1.0, 1.0))
    }

    /** Twice the signed area (shoelace) of the closed polygon through [p]. */
    fun shoelace(p: List<Pt>): Double {
        var a = 0.0
        for (i in p.indices) {
            val q = p[i]; val r = p[(i + 1) % p.size]
            a += q.x * r.y - r.x * q.y
        }
        return a
    }

    /** Wrap an angle to (−π, π]. */
    fun wrap(a: Double): Double {
        var r = a % (2 * PI)
        if (r <= -PI) r += 2 * PI
        if (r > PI) r -= 2 * PI
        return r
    }

    // ── ellipse: PCA ───────────────────────────────────────────────────────────

    class EllipseFit(val cx: Double, val cy: Double, val rx: Double, val ry: Double, val rotation: Double)

    /**
     * PCA ellipse fit: centre = weighted mean; axes = eigenvectors of the 2×2 weighted covariance;
     * r = sqrt(2 λ). That is exact when the points are spread EVENLY IN THE ELLIPSE'S OWN ANGLE t
     * (x = rx cos t, y = ry sin t). A drawn stroke is spread evenly in ARC LENGTH instead, which
     * under-weights the pointed ends and shrinks the long axis (a 160×80 ellipse fits ~10 px short).
     * So after a first equal-weight fit each point is re-weighted by the span of t it covers, and the
     * fit is repeated; three rounds converge to well under a pixel.
     */
    fun fitEllipse(p: List<Pt>): EllipseFit? {
        if (p.size < 5) return null
        val w = DoubleArray(p.size) { 1.0 }
        var fit = weightedPca(p, w) ?: return null
        repeat(3) {
            val c = cos(fit.rotation); val s = sin(fit.rotation)
            val t = DoubleArray(p.size) { i ->
                val dx = p[i].x - fit.cx; val dy = p[i].y - fit.cy
                atan2((-s * dx + c * dy) / fit.ry, (c * dx + s * dy) / fit.rx)
            }
            for (i in p.indices) {
                val prev = if (i > 0) abs(wrap(t[i] - t[i - 1])) else 0.0
                val next = if (i < p.size - 1) abs(wrap(t[i + 1] - t[i])) else 0.0
                w[i] = (prev + next) * 0.5
            }
            fit = weightedPca(p, w) ?: return fit
        }
        return fit
    }

    private fun weightedPca(p: List<Pt>, w: DoubleArray): EllipseFit? {
        var sw = 0.0; var mx = 0.0; var my = 0.0
        for (i in p.indices) { sw += w[i]; mx += w[i] * p[i].x; my += w[i] * p[i].y }
        if (sw <= 0.0) return null
        mx /= sw; my /= sw
        var sxx = 0.0; var sxy = 0.0; var syy = 0.0
        for (i in p.indices) {
            val dx = p[i].x - mx; val dy = p[i].y - my
            sxx += w[i] * dx * dx; sxy += w[i] * dx * dy; syy += w[i] * dy * dy
        }
        sxx /= sw; sxy /= sw; syy /= sw
        val half = (sxx + syy) / 2
        val root = sqrt(((sxx - syy) / 2).let { it * it } + sxy * sxy)
        val l1 = half + root
        val l2 = half - root
        if (l2 <= 0.0) return null
        val rot = 0.5 * atan2(2 * sxy, sxx - syy)
        return EllipseFit(mx, my, sqrt(2 * l1), sqrt(2 * l2), rot)
    }

    /** Mean |ρ − 1| where ρ is each point's normalised elliptical radius (0 = on the ellipse). */
    fun ellipseResidual(p: List<Pt>, f: EllipseFit): Double {
        val c = cos(f.rotation); val s = sin(f.rotation)
        var sum = 0.0
        for (q in p) {
            val dx = q.x - f.cx; val dy = q.y - f.cy
            val u = (c * dx + s * dy) / f.rx
            val v = (-s * dx + c * dy) / f.ry
            sum += abs(sqrt(u * u + v * v) - 1)
        }
        return sum / p.size
    }

    // ── circle: Kåsa ──────────────────────────────────────────────────────────

    class CircleFit(val cx: Double, val cy: Double, val r: Double)

    /**
     * Kåsa's algebraic circle fit: least squares on x² + y² + Dx + Ey + F = 0. The points are centred
     * first so the 3×3 normal equations stay well conditioned at any document position.
     */
    fun fitCircle(p: List<Pt>): CircleFit? {
        if (p.size < 3) return null
        var mx = 0.0; var my = 0.0
        for (q in p) { mx += q.x; my += q.y }
        mx /= p.size; my /= p.size
        // Normal equations A·[D E F] = b, rows over (x, y, 1) with target −(x² + y²).
        val a = Array(3) { DoubleArray(3) }
        val b = DoubleArray(3)
        for (q in p) {
            val x = q.x - mx; val y = q.y - my
            val row = doubleArrayOf(x, y, 1.0)
            val z = -(x * x + y * y)
            for (i in 0..2) {
                for (j in 0..2) a[i][j] += row[i] * row[j]
                b[i] += row[i] * z
            }
        }
        val sol = solve3(a, b) ?: return null
        val cx = -sol[0] / 2; val cy = -sol[1] / 2
        val r2 = cx * cx + cy * cy - sol[2]
        if (!(r2 > 0.0) || !r2.isFinite()) return null
        return CircleFit(cx + mx, cy + my, sqrt(r2))
    }

    /** Gaussian elimination with partial pivoting; null when singular. */
    private fun solve3(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val m = Array(3) { i -> doubleArrayOf(a[i][0], a[i][1], a[i][2], b[i]) }
        for (col in 0..2) {
            var piv = col
            for (r in col + 1..2) if (abs(m[r][col]) > abs(m[piv][col])) piv = r
            if (abs(m[piv][col]) < 1e-12) return null
            val tmp = m[col]; m[col] = m[piv]; m[piv] = tmp
            for (r in 0..2) {
                if (r == col) continue
                val k = m[r][col] / m[col][col]
                for (c in col..3) m[r][c] -= k * m[col][c]
            }
        }
        return DoubleArray(3) { m[it][3] / m[it][it] }
    }

    // ── arc-length parametrisation ─────────────────────────────────────────────

    /**
     * The point at arc length [s] along the polyline [p] (closed: back to p[0] at the end).
     * [s] is clamped to 0..perimeter.
     */
    fun pointAlong(p: List<Pt>, cum: DoubleArray, s: Double): Pt {
        val total = cum[cum.size - 1]
        val d = s.coerceIn(0.0, total)
        // Binary search for the segment that holds d.
        var lo = 0; var hi = cum.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (cum[mid] <= d) lo = mid else hi = mid
        }
        val seg = cum[hi] - cum[lo]
        val t = if (seg > 0.0) (d - cum[lo]) / seg else 0.0
        val a = p[lo]; val b = p[hi % p.size]
        return Pt(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
    }

    /** Cumulative lengths of a CLOSED polygon: size n + 1, last entry = perimeter. */
    fun closedCumulative(p: List<Pt>): DoubleArray {
        val c = DoubleArray(p.size + 1)
        for (i in 1..p.size) c[i] = c[i - 1] + dist(p[i - 1], p[i % p.size])
        return c
    }
}
