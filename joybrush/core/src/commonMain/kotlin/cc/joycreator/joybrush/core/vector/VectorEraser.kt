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

    /**
     * Survivor pieces thinner than this many doc px of centreline are dropped as specks. Applied
     * once, to every mode's result — a sub-half-doc-px stub is noise whether it is what is left of
     * a partial cut or what a trim to a nearby crossing left behind (JB-5.10 R28, Q1).
     */
    private const val MIN_PIECE_ARC = 0.5

    /**
     * TEST HOOK (JB-5.10 R28) — work counted, never time.
     *
     * A wall-clock assertion is flaky by construction: it measures the machine, the JIT and whatever
     * else the build is running beside it, not the code. These counters are deterministic — the same
     * input compares the same pairs on every machine and every run — so a bound on one is a
     * statement about the ALGORITHM and nothing else.
     *
     *  - [crossingPairTests]: segment pairs the TO_INTERSECTION crossing search compares
     *    ([crossingsOnSegment]) — every pair that gets as far as the segment-box test, whether or
     *    not the boxes then meet. This is the counter the pruning is judged by, and the one whose
     *    bound replaces the deleted "< 2 500 ms" assertion on the adversarial layout. It is counted
     *    BEFORE the box test on purpose: a prefilter that stopped working would show up as more
     *    pairs examined, which is the work, whereas counting only the pairs that go on to the
     *    intersection maths would hide it.
     *  - [touchPairTests]: segment pairs compared in the PARTIAL / WHOLE_STROKE touch test
     *    ([touchedRangesOf]) — one of the line's segments against one of the eraser path's whose
     *    boxes meet, i.e. a pair that then gets the exact quadratic treatment. Counted after that
     *    test, which is the only pruning there is: the loop itself always walks every eraser
     *    segment, so counting before it would measure nothing but the loop's length.
     *
     * The eraser never reads either; [resetWorkCounters] puts them back to zero. Not thread-safe,
     * which is fine and deliberate: a test counts one single-threaded call at a time.
     */
    internal var crossingPairTests = 0L
        private set

    internal var touchPairTests = 0L
        private set

    /** Zeroes both work counters, so a test can measure one call exactly. */
    internal fun resetWorkCounters() {
        crossingPairTests = 0L
        touchPairTests = 0L
    }

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
            // The speck rule is applied here, ONCE, to whatever the mode produced: 0.5 doc px is the
            // same noise floor for a partial cut, a whole stroke and a trim to a crossing. It is
            // vacuous for WHOLE_STROKE, which returns no pieces at all.
            val pieces: List<Piece> = when (mode) {
                EraseMode.WHOLE_STROKE -> emptyList()
                EraseMode.PARTIAL -> complementOf(line, touched)
                EraseMode.TO_INTERSECTION -> {
                    cuts.clear()
                    // Out to the crossing before each stretch, or to the line's end if none, and on
                    // to the crossing after it, or to the line's end if none. A line with no
                    // crossings at all therefore loses the stretch's whole length.
                    crossingTrims(line, li, lines, lineBoxes, sweep, touched, hit, cuts)
                    mergeRanges(cuts)
                    complementOf(line, cuts)
                }
            }
            out[line.id] = pieces.filter { it.arcLengthIn(line) >= MIN_PIECE_ARC }
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
                touchPairTests++ // this line segment against this eraser segment
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
     * Appends to [cuts] the stretch to remove for each entry of [touched], in the same order and
     * the same number: from the nearest crossing at or before that stretch's start (or the line's
     * own start) to the nearest crossing at or after its end (or the line's end). A line that
     * crosses nothing at all therefore loses every touched stretch whole.
     *
     * WHY THIS DOES NOT COLLECT EVERY CROSSING. The obvious shape of this — build the list of all
     * the line's crossings, sort it, then look up each stretch's nearest crossing either side — is
     * quadratic, and the quadratic is not the bounding boxes. A line that crosses 49 others which
     * all overlap it has a crossing every fraction of a doc px, so the list runs to millions of
     * entries per line: 50 x 49 x 499 x 499 ≈ 6.1e8 segment pairs to build, then a sort, then a
     * linear scan of the list per touched stretch (review JB-5.10 Finding 1, which is builder Q6).
     * All of that work answers a question nobody asked. Only the NEAREST crossing beside each
     * stretch is ever used, so this walks outward from the stretches and stops at the first
     * crossing it meets — in dense ink, one or two segments' worth.
     *
     * Two passes share the walking. The stretches are visited right-to-left for the "at or before
     * the start" answer and left-to-right for the "at or after the end" one, each with a frontier of
     * how far it has got and a cursor over the crossings it has found, so one segment is compared
     * with the other lines' segments at most once per direction however many stretches the eraser
     * left on the line. The answers are the same values the full list would have given.
     */
    private fun crossingTrims(
        line: InkLine,
        li: Int,
        lines: List<InkLine>,
        lineBoxes: DoubleArray,
        sweep: IntArray,
        touched: List<ParamRange>,
        hit: DoubleArray,
        cuts: MutableList<ParamRange>,
    ) {
        val count = touched.size
        if (count == 0) return
        val aSegs = segmentCountOf(line.pointCount)
        val starts = DoubleArray(count)
        val ends = DoubleArray(count)
        // The crossings this pass has found, in the order this pass walks: largest first going
        // backwards, smallest first going forwards. Only crossings the remaining stretches can
        // still use are ever added (see crossingsOnSegment), so this stays short.
        val seen = ArrayList<Double>(16)

        // Backwards. `next` is the next segment to compare and `cursor` walks the list, so a
        // stretch is answered from what the stretches to its right already found wherever that is
        // enough, and the walk only carries on when it is not.
        var next = aSegs
        var cursor = 0
        for (ri in count - 1 downTo 0) {
            val s = touched[ri].from
            val here = segmentAt(s, aSegs)
            var lo = 0.0 // no crossing at or before it: the cut starts at the line's own start
            while (true) {
                while (cursor < seen.size && seen[cursor] > s) cursor++
                if (cursor < seen.size) {
                    lo = seen[cursor]
                    break
                }
                val i = if (next - 1 < here) next - 1 else here
                if (i < 0) break
                next = i
                val from = seen.size
                crossingsOnSegment(i, line, li, lines, lineBoxes, sweep, hit, seen, s, true)
                reverseFrom(seen, from) // this pass walks backwards, so largest first
            }
            starts[ri] = lo
        }

        // Forwards, the same again for the smallest crossing at or after each stretch's end.
        seen.clear()
        next = -1
        cursor = 0
        for (ri in 0 until count) {
            val s = touched[ri].to
            val here = segmentAt(s, aSegs)
            var hi = line.lastParam // no crossing at or after it: the cut runs to the line's end
            while (true) {
                while (cursor < seen.size && seen[cursor] < s) cursor++
                if (cursor < seen.size) {
                    hi = seen[cursor]
                    break
                }
                val i = if (next + 1 > here) next + 1 else here
                if (i >= aSegs) break
                next = i
                crossingsOnSegment(i, line, li, lines, lineBoxes, sweep, hit, seen, s, false)
            }
            ends[ri] = hi
        }

        for (ri in 0 until count) cuts.add(ParamRange(starts[ri], ends[ri]))
    }

    /**
     * Appends to [seen] the parameters along [line] at which its segment [i] crosses another line's
     * centreline and which the walk can still use: at or before [limit] when walking backwards, at
     * or after it when walking forwards. The ones dropped are past the limit, and every stretch this
     * pass has left to answer is nearer the start of the line than the one asking now, so a dropped
     * crossing can never become an answer later.
     *
     * A NaN parameter is dropped too, so a degenerate pair can never become an interval end.
     *
     * The filter is this segment's own box rather than the whole line's, which is strictly tighter
     * and loses nothing — a crossing is a point that both boxes contain — and the left-to-right
     * break still holds, because the order is by the other line's left edge and a line starting
     * past this segment cannot touch it.
     */
    private fun crossingsOnSegment(
        i: Int,
        line: InkLine,
        li: Int,
        lines: List<InkLine>,
        lineBoxes: DoubleArray,
        sweep: IntArray,
        hit: DoubleArray,
        seen: MutableList<Double>,
        limit: Double,
        backwards: Boolean,
    ) {
        val pts = line.pointCount
        val i0 = min(i, pts - 1)
        val i1 = min(i + 1, pts - 1)
        val ax = line.xs[i0]
        val ay = line.ys[i0]
        val bx = line.xs[i1]
        val by = line.ys[i1]
        val left = min(ax, bx)
        val right = max(ax, bx)
        val bottom = min(ay, by)
        val top = max(ay, by)
        for (oi in sweep) {
            val box = oi * 4
            if (lineBoxes[box] > right) break // sorted by left edge, so is everything after it
            if (oi == li) continue
            if (lineBoxes[box + 1] < left || lineBoxes[box + 3] < bottom ||
                lineBoxes[box + 2] > top
            ) continue
            val other = lines[oi]
            if (other.id == line.id) continue // a stroke never crosses itself
            val b = other.pointCount
            val bSegs = segmentCountOf(b)
            for (j in 0 until bSegs) {
                val j0 = min(j, b - 1)
                val j1 = min(j + 1, b - 1)
                val cx = other.xs[j0]
                val cy = other.ys[j0]
                val dx = other.xs[j1]
                val dy = other.ys[j1]
                crossingPairTests++ // this segment against this other line's segment
                if (!boxesMayTouch(ax, ay, bx, by, GEOM_EPS, cx, cy, dx, dy)) continue
                val hits = segmentIntersections(ax, ay, bx, by, cx, cy, dx, dy, hit)
                for (k in 0 until hits) {
                    val at = i + hit[2 * k]
                    if (at.isNaN()) continue
                    if (backwards) {
                        if (at > limit) continue
                    } else if (at < limit) continue
                    seen.add(at)
                }
            }
        }
    }

    /** The segment holding parameter [v]: 3.25 is on segment 3, and the line's end is its last. */
    private fun segmentAt(v: Double, aSegs: Int): Int {
        val i = v.toInt()
        return if (i < 0) 0 else if (i > aSegs - 1) aSegs - 1 else i
    }

    /** Reverses [list] from index [from] to its end, in place — one scanned segment's crossings. */
    private fun reverseFrom(list: MutableList<Double>, from: Int) {
        var lo = from
        var hi = list.size - 1
        while (lo < hi) {
            val t = list[lo]
            list[lo] = list[hi]
            list[hi] = t
            lo++
            hi--
        }
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
}
