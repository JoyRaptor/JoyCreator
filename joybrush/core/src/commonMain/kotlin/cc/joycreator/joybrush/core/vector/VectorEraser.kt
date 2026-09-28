package cc.joycreator.joybrush.core.vector

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * JB-5.10 — the vector eraser. On an Ink (vector) layer the eraser cuts LINES, not pixels: it
 * measures how close a line's centreline comes to its own path and removes the stretches that come
 * within `eraser.radius + the line's half-width` there. Nothing in this file knows about layers,
 * strokes, undo or pixels.
 */
enum class EraseMode {
    /** Cut out only the stretch under the eraser; the rest of the line survives. */
    PARTIAL,

    /** Any line the eraser touches disappears entirely. */
    WHOLE_STROKE,

    /** Only the stretch out to the nearest crossings with other lines disappears. */
    TO_INTERSECTION,
}

/**
 * The eraser's path for this gesture, and its radius, both in doc px. A single sample is a dot; two
 * or more are the polyline the eraser swept.
 */
data class EraserPath(val xs: DoubleArray, val ys: DoubleArray, val radius: Double) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        val o = other as? EraserPath ?: return false
        return radius == o.radius && xs.contentEquals(o.xs) && ys.contentEquals(o.ys)
    }

    override fun hashCode(): Int {
        var h = xs.contentHashCode()
        h = 31 * h + ys.contentHashCode()
        h = 31 * h + radius.hashCode()
        return h
    }
}

/**
 * A piece of a line that SURVIVES, named in fractional point indices of the line it came from:
 * 3.25 is a quarter of the way from point 3 to point 4, and [0, n-1] is the whole line. Cutting a
 * line and rebuilding strokes from these is a later spec's job; this is the geometry only.
 */
data class Piece(val sourceId: String, val from: Double, val to: Double)

/**
 * What survived. A line whose id is absent was never touched. An id present with an empty list is
 * a line the eraser removed.
 */
data class EraseResult(
    val survivors: Map<String, List<Piece>>,
)

/**
 * Cuts lines with an eraser path.
 *
 * The touch test is exact, not sampled: for a line segment and an eraser segment, the set of
 * positions where `distance <= radius + halfWidth` is solved as a quadratic inequality per regime,
 * so a graze that is thinner than the eraser path's own segment spacing still registers.
 */
object VectorEraser {

    /** Survivor pieces thinner than this many doc px of centreline are dropped as specks. */
    private const val MIN_PIECE_ARC = 0.5

    fun erase(lines: List<InkLine>, eraser: EraserPath, mode: EraseMode): EraseResult {
        // A gesture with no samples yet (or a half-built path) touches nothing rather than throwing.
        val n = min(eraser.xs.size, eraser.ys.size)
        if (n == 0 || lines.isEmpty()) return EraseResult(emptyMap())

        val eSegs = segmentCountOf(n)
        val eBoxes = DoubleArray(eSegs * 4)
        for (j in 0 until eSegs) {
            val j0 = min(j, n - 1)
            val j1 = min(j + 1, n - 1)
            val o = j * 4
            eBoxes[o] = min(eraser.xs[j0], eraser.xs[j1])
            eBoxes[o + 1] = max(eraser.xs[j0], eraser.xs[j1])
            eBoxes[o + 2] = min(eraser.ys[j0], eraser.ys[j1])
            eBoxes[o + 3] = max(eraser.ys[j0], eraser.ys[j1])
        }

        val touched = ArrayList<ParamRange>(8)
        val raw = ArrayList<ParamRange>(4)
        val cuts = ArrayList<ParamRange>(8)
        val crosses = ArrayList<Double>(16)
        val breaks = DoubleArray(5)
        val hit = DoubleArray(4)

        val toIntersection = mode == EraseMode.TO_INTERSECTION
        val lineBoxes = if (toIntersection) lineBoxesOf(lines) else DoubleArray(0)
        // Other lines, walked left to right, so the crossing search can stop early.
        val sweep = if (toIntersection) leftToRightOrder(lines.size, lineBoxes) else IntArray(0)

        val out = LinkedHashMap<String, List<Piece>>()
        for (li in lines.indices) {
            val line = lines[li]
            touchedRangesOf(line, eraser, n, eSegs, eBoxes, raw, touched, breaks)
            if (touched.isEmpty()) continue // untouched lines are simply not mentioned
            when (mode) {
                EraseMode.WHOLE_STROKE -> out[line.id] = emptyList()
                EraseMode.PARTIAL -> out[line.id] =
                    complementOf(line, touched).filter { it.arcLengthIn(line) >= MIN_PIECE_ARC }
                EraseMode.TO_INTERSECTION -> {
                    crossingsOf(line, li, lines, lineBoxes, sweep, hit, crosses)
                    cuts.clear()
                    for (t in touched) {
                        // Out to the crossing before the stretch, or to the line's end if none, and
                        // on to the crossing after it, or to the line's end if none. A line with no
                        // crossings at all therefore loses the stretch's whole length.
                        val lo = nearestAtOrBefore(crosses, t.from) ?: 0.0
                        val hi = nearestAtOrAfter(crosses, t.to) ?: line.lastParam
                        cuts.add(ParamRange(lo, hi))
                    }
                    mergeRanges(cuts)
                    out[line.id] = complementOf(line, cuts)
                }
            }
        }
        return EraseResult(out)
    }

    // ---- touching ---------------------------------------------------------------------------------

    /**
     * Every stretch of [line] whose centreline comes within `radius + halfWidth` of [eraser], in
     * line-parameter space, merged and sorted. Empty means the eraser never touched this line.
     */
    private fun touchedRangesOf(
        line: InkLine,
        eraser: EraserPath,
        n: Int,
        eSegs: Int,
        eBoxes: DoubleArray,
        raw: MutableList<ParamRange>,
        out: MutableList<ParamRange>,
        breaks: DoubleArray,
    ) {
        out.clear()
        val pts = line.pointCount
        val last = (pts - 1).toDouble()
        val segs = segmentCountOf(pts)
        val radius = eraser.radius
        for (i in 0 until segs) {
            val i0 = min(i, pts - 1)
            val i1 = min(i + 1, pts - 1)
            val ax = line.xs[i0]
            val ay = line.ys[i0]
            val bx = line.xs[i1]
            val by = line.ys[i1]
            val h0 = line.halfWidths[i0]
            val h1 = line.halfWidths[i1]
            val pad = radius + max(h0, h1)
            if (pad < 0.0) continue // the eraser never reaches this thick-less centreline
            val lMinX = min(ax, bx)
            val lMaxX = max(ax, bx)
            val lMinY = min(ay, by)
            val lMaxY = max(ay, by)
            for (j in 0 until eSegs) {
                val o = j * 4
                if (eBoxes[o] > lMaxX + pad || eBoxes[o + 1] < lMinX - pad ||
                    eBoxes[o + 2] > lMaxY + pad || eBoxes[o + 3] < lMinY - pad
                ) continue
                val j0 = min(j, n - 1)
                val j1 = min(j + 1, n - 1)
                raw.clear()
                appendTouchingRanges(
                    ax, ay, bx, by, h0, h1, radius,
                    eraser.xs[j0], eraser.ys[j0], eraser.xs[j1], eraser.ys[j1],
                    breaks, raw,
                )
                for (r in raw) {
                    val from = (i + r.from).coerceIn(0.0, last)
                    val to = (i + r.to).coerceIn(0.0, last)
                    if (to >= from) out.add(ParamRange(from, to))
                }
            }
        }
        mergeRanges(out)
    }

    /**
     * Appends the positions u in [0,1] along segment (ax, ay)-(bx, by) where
     * `dist(segment point, eraser segment) <= radius + halfWidth(u)`, exactly.
     *
     * On each regime the closest feature of the eraser segment is the same one (its first end, its
     * last end, or its interior), so `distance²` is a quadratic in u. The threshold is linear, so
     * `threshold²` is a quadratic too, and the condition is one quadratic inequality per regime.
     * The regimes are separated by the foot of the perpendicular sliding off either end, and by the
     * threshold crossing zero (below zero nothing can be within it).
     */
    private fun appendTouchingRanges(
        ax: Double, ay: Double, bx: Double, by: Double,
        h0: Double, h1: Double, radius: Double,
        cx: Double, cy: Double, dx: Double, dy: Double,
        breaks: DoubleArray,
        out: MutableList<ParamRange>,
    ) {
        val rax = bx - ax
        val ray = by - ay
        val ebx = dx - cx
        val eby = dy - cy
        val ee = ebx * ebx + eby * eby
        val th0 = radius + h0
        val dth = h1 - h0

        var count = 2
        breaks[0] = 0.0
        breaks[1] = 1.0
        if (ee > 0.0) {
            // Foot parameter along the eraser segment: s(u) = s0 + u * ds. It leaves [0,1] here.
            val s0 = ((ax - cx) * ebx + (ay - cy) * eby) / ee
            val ds = (rax * ebx + ray * eby) / ee
            if (ds != 0.0) {
                val at0 = -s0 / ds
                val at1 = (1.0 - s0) / ds
                if (at0 > 0.0 && at0 < 1.0) {
                    breaks[count] = at0
                    count++
                }
                if (at1 > 0.0 && at1 < 1.0) {
                    breaks[count] = at1
                    count++
                }
            }
        }
        if (dth != 0.0) {
            val atZero = -th0 / dth
            if (atZero > 0.0 && atZero < 1.0) {
                breaks[count] = atZero
                count++
            }
        }
        for (i in 1 until count) { // insertion sort: count is at most 5
            val v = breaks[i]
            var j = i - 1
            while (j >= 0 && breaks[j] > v) {
                breaks[j + 1] = breaks[j]
                j--
            }
            breaks[j + 1] = v
        }

        val qa = rax * rax + ray * ray
        val cw = dth * dth
        val bw = 2.0 * th0 * dth
        val cwt = th0 * th0
        for (i in 0 until count - 1) {
            val lo = breaks[i]
            val hi = breaks[i + 1]
            if (hi <= lo) continue
            val mid = 0.5 * (lo + hi)
            if (th0 + mid * dth < 0.0) continue // the threshold is negative: nothing can reach it
            val regime = if (ee <= 0.0) {
                0 // the eraser is a dot: one feature only
            } else {
                val s = ((ax + mid * rax - cx) * ebx + (ay + mid * ray - cy) * eby) / ee
                if (s < 0.0) 0 else if (s > 1.0) 1 else 2
            }
            var qaR = 0.0
            var qb = 0.0
            var qc = 0.0
            when (regime) {
                0 -> { // distance to the eraser segment's first end
                    qaR = qa
                    qb = 2.0 * ((ax - cx) * rax + (ay - cy) * ray)
                    qc = (ax - cx) * (ax - cx) + (ay - cy) * (ay - cy)
                }
                1 -> { // distance to its last end
                    qaR = qa
                    qb = 2.0 * ((ax - dx) * rax + (ay - dy) * ray)
                    qc = (ax - dx) * (ax - dx) + (ay - dy) * (ay - dy)
                }
                else -> { // perpendicular distance to its interior: a cross product, squared
                    val cr = (ax - cx) * eby - (ay - cy) * ebx
                    val dcr = rax * eby - ray * ebx
                    qaR = dcr * dcr / ee
                    qb = 2.0 * cr * dcr / ee
                    qc = cr * cr / ee
                }
            }
            appendQuadLeq(qaR - cw, qb - bw, qc - cwt, lo, hi, out)
        }
    }

    /**
     * Appends every part of [lo,hi] where `a u² + b u + c <= 0` — nothing, the whole thing, the
     * outside or the inside — using the numerically stable root form so a huge `a` next to a tiny
     * `c` still gives the right answer.
     */
    private fun appendQuadLeq(
        a: Double, b: Double, c: Double, lo: Double, hi: Double, out: MutableList<ParamRange>,
    ) {
        if (a == 0.0) {
            if (b == 0.0) {
                if (c <= 0.0) out.add(ParamRange(lo, hi))
                return
            }
            val root = -c / b
            if (b > 0.0) {
                if (min(hi, root) >= lo) out.add(ParamRange(lo, min(hi, root)))
            } else {
                if (max(lo, root) <= hi) out.add(ParamRange(max(lo, root), hi))
            }
            return
        }
        val disc = b * b - 4.0 * a * c
        if (disc < 0.0) {
            if (a < 0.0) out.add(ParamRange(lo, hi)) // always negative
            return
        }
        val sq = sqrt(disc)
        val q = -0.5 * (b + if (b >= 0.0) sq else -sq)
        val r1 = q / a
        val r2 = if (q != 0.0) c / q else r1
        val x1 = min(r1, r2)
        val x2 = max(r1, r2)
        if (a > 0.0) {
            val s = max(lo, x1)
            val e = min(hi, x2)
            if (s <= e) out.add(ParamRange(s, e))
        } else {
            if (min(hi, x1) >= lo) out.add(ParamRange(lo, min(hi, x1)))
            if (max(lo, x2) <= hi) out.add(ParamRange(max(lo, x2), hi))
        }
    }

    // ---- crossings --------------------------------------------------------------------------------

    /** Centreline bounding boxes of [lines], 4 doubles each, for the crossing prefilter. */
    private fun lineBoxesOf(lines: List<InkLine>): DoubleArray {
        val boxes = DoubleArray(lines.size * 4)
        for (i in lines.indices) {
            val l = lines[i]
            var minX = Double.MAX_VALUE
            var maxX = -Double.MAX_VALUE
            var minY = Double.MAX_VALUE
            var maxY = -Double.MAX_VALUE
            for (k in 0 until l.pointCount) {
                val x = l.xs[k]
                val y = l.ys[k]
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
            val o = i * 4
            boxes[o] = minX
            boxes[o + 1] = maxX
            boxes[o + 2] = minY
            boxes[o + 3] = maxY
        }
        return boxes
    }

    /**
     * The [count] line indices, ordered by the left edge of their box in [boxes], so that a sweep
     * can stop as soon as it meets a line that starts past the one it is working on. [boxes] is the
     * packed 4-doubles-per-line array [lineBoxesOf] returns.
     */
    private fun leftToRightOrder(count: Int, boxes: DoubleArray): IntArray {
        val order = IntArray(count) { it }
        for (i in 1 until order.size) { // insertion sort; this list is short by construction
            val v = order[i]
            val left = boxes[v * 4]
            var j = i - 1
            while (j >= 0 && boxes[order[j] * 4] > left) {
                order[j + 1] = order[j]
                j--
            }
            order[j + 1] = v
        }
        return order
    }

    /**
     * The parameters along [line] where its centreline crosses another line's centreline, sorted.
     * A line is never compared with itself — a stroke looping back over its own tail is not a
     * crossing, and the spec forbids counting it as one.
     */
    private fun crossingsOf(
        line: InkLine,
        li: Int,
        lines: List<InkLine>,
        lineBoxes: DoubleArray,
        sweep: IntArray,
        hit: DoubleArray,
        out: MutableList<Double>,
    ) {
        out.clear()
        val pts = line.pointCount
        val aSegs = segmentCountOf(pts)
        val left = lineBoxes[li * 4]
        val right = lineBoxes[li * 4 + 1]
        val bottom = lineBoxes[li * 4 + 2]
        val top = lineBoxes[li * 4 + 3]
        for (oi in sweep) {
            if (lineBoxes[oi * 4] > right) break // everything left is sorted: it starts past us
            if (oi == li) continue
            if (lineBoxes[oi * 4 + 1] < left || lineBoxes[oi * 4 + 3] < bottom ||
                lineBoxes[oi * 4 + 2] > top
            ) continue
            val other = lines[oi]
            if (other.id == line.id) continue // a stroke never crosses itself
            val b = other.pointCount
            val bSegs = segmentCountOf(b)
            for (i in 0 until aSegs) {
                val i0 = min(i, pts - 1)
                val i1 = min(i + 1, pts - 1)
                val ax = line.xs[i0]
                val ay = line.ys[i0]
                val bx = line.xs[i1]
                val by = line.ys[i1]
                for (j in 0 until bSegs) {
                    val j0 = min(j, b - 1)
                    val j1 = min(j + 1, b - 1)
                    if (!boxesMayTouch(
                            ax, ay, bx, by, GEOM_EPS,
                            other.xs[j0], other.ys[j0], other.xs[j1], other.ys[j1],
                        )
                    ) continue
                    val hits = segmentIntersections(
                        ax, ay, bx, by, other.xs[j0], other.ys[j0], other.xs[j1], other.ys[j1], hit,
                    )
                    for (k in 0 until hits) out.add(i + hit[2 * k])
                }
            }
        }
        out.sort()
    }

    /** The pieces of [line] that are not inside any of the merged [removed] stretches. */
    private fun complementOf(line: InkLine, removed: List<ParamRange>): List<Piece> {
        val last = line.lastParam
        val out = ArrayList<Piece>(removed.size + 1)
        var cursor = 0.0
        for (r in removed) {
            if (r.from > cursor) out.add(Piece(line.id, cursor, r.from))
            if (r.to > cursor) cursor = r.to
        }
        if (cursor < last) out.add(Piece(line.id, cursor, last))
        return out
    }

    /** Sorts by start and merges stretches that overlap or merely touch. */
    private fun mergeRanges(ranges: MutableList<ParamRange>) {
        if (ranges.size <= 1) return
        ranges.sortBy { it.from }
        var write = 0
        var cur = ranges[0]
        for (i in 1 until ranges.size) {
            val r = ranges[i]
            if (r.from <= cur.to) {
                if (r.to > cur.to) cur = ParamRange(cur.from, r.to)
            } else {
                ranges[write] = cur
                write++
                cur = r
            }
        }
        ranges[write] = cur
        while (ranges.size > write + 1) ranges.removeAt(ranges.size - 1)
    }

    /** The largest value in the sorted [values] that is at or below [t], or null if there is none. */
    private fun nearestAtOrBefore(values: List<Double>, t: Double): Double? {
        var best: Double? = null
        for (v in values) {
            if (v <= t) best = v else return best
        }
        return best
    }

    /** The smallest value in the sorted [values] that is at or above [t], or null if there is none. */
    private fun nearestAtOrAfter(values: List<Double>, t: Double): Double? {
        for (v in values) {
            if (v >= t) return v
        }
        return null
    }
}
