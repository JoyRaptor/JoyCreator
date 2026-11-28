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

    /**
     * [alpha] (0..255, [w] x [h]) blurred by about [radius] px: three box passes each way, which is close to a Gaussian.
     * Radius 0 (or less) returns it as it is.
     */
    fun soften(w: Int, h: Int, alpha: IntArray, radius: Float): IntArray {
        if (!(radius > 0f) || w == 0 || h == 0) return alpha
        val r = maxOf(1, kotlin.math.round(radius * 0.8f).toInt())
        val cur = FloatArray(w * h) { alpha[it].toFloat() }
        val tmp = FloatArray(w * h)
        repeat(3) {
            boxRows(cur, tmp, w, h, r)
            boxCols(tmp, cur, w, h, r)
        }
        return IntArray(w * h) { kotlin.math.round(cur[it]).toInt().coerceIn(0, 255) }
    }

    private fun boxRows(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int) {
        val n = 2 * r + 1
        for (y in 0 until h) {
            val row = y * w
            var acc = 0f
            for (k in -r..r) acc += src[row + k.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                dst[row + x] = acc / n
                acc += src[row + (x + r + 1).coerceAtMost(w - 1)] - src[row + (x - r).coerceAtLeast(0)]
            }
        }
    }

    private fun boxCols(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int) {
        val n = 2 * r + 1
        for (x in 0 until w) {
            var acc = 0f
            for (k in -r..r) acc += src[k.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                dst[y * w + x] = acc / n
                acc += src[(y + r + 1).coerceAtMost(h - 1) * w + x] - src[(y - r).coerceAtLeast(0) * w + x]
            }
        }
    }

    /**
     * Whether ink at [threshold] is grainy: at least eight, and more than two, specks or pinholes (patches of ink or of paper under 30 pixels)
     * for every thousand pixels of ink. Clean digital ink has none; a pencil on toothy paper has hundreds.
     */
    fun grainy(w: Int, h: Int, alpha: IntArray, threshold: Int): Boolean {
        var ink = 0
        for (a in alpha) if (a >= threshold) ink++
        if (ink == 0) return false
        var small = 0
        val seen = BooleanArray(w * h)
        val stack = IntArray(w * h)
        for (start in 0 until w * h) {
            if (seen[start]) continue
            val on = alpha[start] >= threshold
            var top = 0
            var count = 0
            var edge = false
            stack[top++] = start
            seen[start] = true
            while (top > 0) {
                val p = stack[--top]
                count++
                val x = p % w
                val y = p / w
                if (x == 0 || y == 0 || x == w - 1 || y == h - 1) edge = true
                if (x > 0 && !seen[p - 1] && (alpha[p - 1] >= threshold) == on) { seen[p - 1] = true; stack[top++] = p - 1 }
                if (x < w - 1 && !seen[p + 1] && (alpha[p + 1] >= threshold) == on) { seen[p + 1] = true; stack[top++] = p + 1 }
                if (y > 0 && !seen[p - w] && (alpha[p - w] >= threshold) == on) { seen[p - w] = true; stack[top++] = p - w }
                if (y < h - 1 && !seen[p + w] && (alpha[p + w] >= threshold) == on) { seen[p + w] = true; stack[top++] = p + w }
            }
            if (count < 30 && (on || !edge)) small++
        }
        return small >= 8 && small * 1000L > 2L * ink
    }

    /** Fills every gap inside [inside] ([w] x [h]) smaller than [minHole] pixels; gaps that reach the box's edge are outside. */
    fun fillSmallHoles(w: Int, h: Int, inside: BooleanArray, minHole: Int) {
        if (minHole <= 0) return
        val seen = BooleanArray(w * h)
        val stack = IntArray(w * h)
        val region = IntArray(w * h)
        for (start in 0 until w * h) {
            if (inside[start] || seen[start]) continue
            var top = 0
            var count = 0
            var touchesEdge = false
            stack[top++] = start
            seen[start] = true
            while (top > 0) {
                val p = stack[--top]
                region[count++] = p
                val x = p % w
                val y = p / w
                if (x == 0 || y == 0 || x == w - 1 || y == h - 1) touchesEdge = true
                // four-way: ink joined corner to corner closes a gap
                if (x > 0 && !inside[p - 1] && !seen[p - 1]) { seen[p - 1] = true; stack[top++] = p - 1 }
                if (x < w - 1 && !inside[p + 1] && !seen[p + 1]) { seen[p + 1] = true; stack[top++] = p + 1 }
                if (y > 0 && !inside[p - w] && !seen[p - w]) { seen[p - w] = true; stack[top++] = p - w }
                if (y < h - 1 && !inside[p + w] && !seen[p + w]) { seen[p + w] = true; stack[top++] = p + w }
            }
            if (!touchesEdge && count < minHole) for (k in 0 until count) inside[region[k]] = true
        }
    }

    fun clampMin(v: Float, lo: Float) = if (v < lo) lo else v

    fun minOf3(a: Float, b: Float, c: Float) = min(a, min(b, c))
}
