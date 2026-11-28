package cc.joycreator.joybrush.core.trace

import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sqrt

/** The tracer's small pieces of geometry. Pure. */
internal object TraceMath {

    /**
     * Euclidean distance from each pixel of a [w] x [h] grid to the nearest pixel where [inside] is false (exact, two
     * passes of the lower envelope of parabolas; Felzenszwalb & Huttenlocher). A pixel outside is 0.
     */
    fun distanceToOutside(w: Int, h: Int, inside: BooleanArray): FloatArray {
        val inf = 1e12
        val g = DoubleArray(w * h) { if (inside[it]) inf else 0.0 }
        val f = DoubleArray(maxOf(w, h))
        val d = DoubleArray(maxOf(w, h))
        val v = IntArray(maxOf(w, h))
        val z = DoubleArray(maxOf(w, h) + 1)
        for (x in 0 until w) {
            for (y in 0 until h) f[y] = g[y * w + x]
            envelope(f, h, d, v, z)
            for (y in 0 until h) g[y * w + x] = d[y]
        }
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) f[x] = g[y * w + x]
            envelope(f, w, d, v, z)
            for (x in 0 until w) out[y * w + x] = sqrt(d[x]).toFloat()
        }
        return out
    }

    private fun envelope(f: DoubleArray, n: Int, d: DoubleArray, v: IntArray, z: DoubleArray) {
        if (n == 0) return
        var k = 0
        v[0] = 0
        z[0] = Double.NEGATIVE_INFINITY
        z[1] = Double.POSITIVE_INFINITY
        for (q in 1 until n) {
            var s = parabolaCross(f, q, v[k])
            while (s <= z[k]) {
                k--
                s = parabolaCross(f, q, v[k])
            }
            k++
            v[k] = q
            z[k] = s
            z[k + 1] = Double.POSITIVE_INFINITY
        }
        k = 0
        for (q in 0 until n) {
            while (z[k + 1] < q) k++
            val dq = (q - v[k]).toDouble()
            d[q] = dq * dq + f[v[k]]
        }
    }

    private fun parabolaCross(f: DoubleArray, q: Int, p: Int): Double =
        ((f[q] + q.toDouble() * q) - (f[p] + p.toDouble() * p)) / (2.0 * (q - p))

    /** A moving average of three, [passes] times; an open line keeps its two ends, a closed one wraps. */
    fun smooth(xs: FloatArray, ys: FloatArray, closed: Boolean, passes: Int) {
        val n = xs.size
        if (n < 3) return
        val tx = FloatArray(n)
        val ty = FloatArray(n)
        repeat(passes) {
            for (i in 0 until n) {
                val a = if (i == 0) (if (closed) n - 1 else 0) else i - 1
                val b = if (i == n - 1) (if (closed) 0 else n - 1) else i + 1
                if (!closed && (i == 0 || i == n - 1)) { tx[i] = xs[i]; ty[i] = ys[i]; continue }
                tx[i] = (xs[a] + 2 * xs[i] + xs[b]) / 4f
                ty[i] = (ys[a] + 2 * ys[i] + ys[b]) / 4f
            }
            tx.copyInto(xs)
            ty.copyInto(ys)
        }
    }

    /** The indices Ramer-Douglas-Peucker keeps of an open polyline, within [tolerance]. Always the two ends. */
    fun simplifyOpen(xs: FloatArray, ys: FloatArray, tolerance: Float): IntArray {
        val n = xs.size
        if (n <= 2) return IntArray(n) { it }
        val keep = BooleanArray(n)
        keep[0] = true
        keep[n - 1] = true
        val stack = ArrayList<Long>()
        stack += (0L shl 32) or (n - 1).toLong()
        while (stack.isNotEmpty()) {
            val top = stack.removeAt(stack.size - 1)
            val a = (top ushr 32).toInt()
            val b = (top and 0xFFFFFFFFL).toInt()
            var far = -1
            var farD = tolerance.toDouble()
            for (i in a + 1 until b) {
                val dd = distToSegment(xs[i], ys[i], xs[a], ys[a], xs[b], ys[b])
                if (dd > farD) { farD = dd; far = i }
            }
            if (far >= 0) {
                keep[far] = true
                stack += (a.toLong() shl 32) or far.toLong()
                stack += (far.toLong() shl 32) or b.toLong()
            }
        }
        return (0 until n).filter { keep[it] }.toIntArray()
    }

    /** The same for a closed polyline (its last point is not a copy of its first): split at the point farthest from 0. */
    fun simplifyClosed(xs: FloatArray, ys: FloatArray, tolerance: Float): IntArray {
        val n = xs.size
        if (n <= 3) return IntArray(n) { it }
        var far = 1
        var farD = -1.0
        for (i in 1 until n) {
            val dd = hypot((xs[i] - xs[0]).toDouble(), (ys[i] - ys[0]).toDouble())
            if (dd > farD) { farD = dd; far = i }
        }
        val first = simplifyOpen(xs.copyOfRange(0, far + 1), ys.copyOfRange(0, far + 1), tolerance)
        val bx = FloatArray(n - far + 1) { if (far + it < n) xs[far + it] else xs[0] }
        val by = FloatArray(n - far + 1) { if (far + it < n) ys[far + it] else ys[0] }
        val second = simplifyOpen(bx, by, tolerance)
        val out = ArrayList<Int>()
        for (i in first) out += i
        for (j in 1 until second.size - 1) out += far + second[j]
        return out.toIntArray()
    }

    fun distToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Double {
        val dx = (bx - ax).toDouble()
        val dy = (by - ay).toDouble()
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    /** The point [dist] along a polyline from its start (or its end, when it is shorter). */
    fun pointAlong(xs: FloatArray, ys: FloatArray, dist: Float): Pair<Float, Float> {
        var left = dist
        for (i in 1 until xs.size) {
            val seg = hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
            if (seg >= left && seg > 0f) {
                val t = left / seg
                return (xs[i - 1] + (xs[i] - xs[i - 1]) * t) to (ys[i - 1] + (ys[i] - ys[i - 1]) * t)
            }
            left -= seg
        }
        return xs.last() to ys.last()
    }

    fun clampMin(v: Float, lo: Float) = if (v < lo) lo else v

    fun minOf3(a: Float, b: Float, c: Float) = min(a, min(b, c))
}
