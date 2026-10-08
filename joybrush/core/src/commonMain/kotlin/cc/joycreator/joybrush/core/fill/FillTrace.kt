package cc.joycreator.joybrush.core.fill

import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.select.MAX_SELECT_SPAN
import cc.joycreator.joybrush.core.stroke.StrokeRecord

/** Flood-fill geometry kept as an editable fill-engine recording (JB-5.13).
 *
 * Growth is the square/Chebyshev metric used by [FloodFill], at exactly .75 document px.
 * We union expanded horizontal pixel runs BEFORE tracing: narrow holes that close disappear,
 * rather than turning inside out, and overlapping runs never accumulate colour/alpha twice.
 * Boundaries have filled space on their right (y down), so holes have opposite winding. All
 * rings are connected by out-and-back orthogonal bridges: their winding contribution is zero.
 * Only collinear corners are removed, an exact simplification (zero error, below .25 px).
 *
 * Arithmetic uses quarter-pixel integers. The sweep holds at most three rows of runs, not a
 * 4x supersampled bitmap. Work is O(w*h + sum of active-run sorting + boundary edges); memory
 * beyond the caller's mask is O(w + boundary edges + output samples). No GL, undo or model edits.
 */
object FillTrace {
    const val GROW_DOC_PX = 0.75f

    /** Compute gap closing with FloodFill, but do NOT add its integer growth on top of .75 px. */
    fun fill(
        w: Int, h: Int, rgba: ByteArray, seedX: Int, seedY: Int, options: FillOptions,
        id: String, brushId: String, colorArgb: Int, docX: Int = 0, docY: Int = 0,
    ): List<StrokeRecord> = trace(
        w, h, FloodFill.fill(w, h, rgba, seedX, seedY, options.copy(grow = 0)),
        id, brushId, colorArgb, docX, docY,
    )

    /** Trace a binary region (any nonzero byte is inside). Caller supplies an engine="fill" brush.
     * Empty region returns no records; otherwise returns ONE record, including every hole/component.
     * Refuses dimensions beyond the existing flood/lasso budgets or coordinates whose Float sample
     * representation cannot retain a quarter pixel, rather than returning a silently damaged shape.
     */
    fun trace(
        w: Int, h: Int, region: ByteArray, id: String, brushId: String, colorArgb: Int,
        docX: Int = 0, docY: Int = 0,
    ): List<StrokeRecord> {
        require(w >= 0 && h >= 0) { "fill trace: negative region size ${w}x$h" }
        val count = w.toLong() * h
        require(count <= MAX_FILL_PX) { "fill trace: region exceeds $MAX_FILL_PX pixels" }
        require(region.size.toLong() >= count) { "fill trace: region buffer is shorter than ${w}x$h" }
        if (count == 0L) return emptyList()
        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        for (i in 0 until count.toInt()) if (region[i].toInt() != 0) {
            val x = i % w
            val y = i / w
            minX = minOf(minX, x); maxX = maxOf(maxX, x)
            minY = minOf(minY, y); maxY = maxOf(maxY, y)
        }
        if (maxX < 0) return emptyList()
        require(maxX.toLong() - minX + 3 <= MAX_SELECT_SPAN && maxY.toLong() - minY + 3 <= MAX_SELECT_SPAN) {
            "fill trace: grown region exceeds the existing $MAX_SELECT_SPAN px fill-shape span"
        }
        val edges = ArrayList<Edge>()
        // Extend straight vertical boundaries in place; a 2048 px side is one edge, not
        // 4096 half-pixel edges waiting to be removed after a large temporary allocation.
        val leftEdges = HashMap<Int, Int>()
        val rightEdges = HashMap<Int, Int>()
        val active = mutableMapOf<Int, List<Span>>()
        var previous = emptyList<Span>()
        // Tops are 4*y-3, bottoms 4*y+7; alternating events are two quarter pixels apart.
        var y = 4 * minY - 3
        val lastY = 4 * maxY + 7
        while (y <= lastY) {
            if ((y + 3) % 4 == 0) {
                val row = (y + 3) / 4
                if (row <= maxY) active[row] = runs(w, row, region, minX, maxX + 1)
            } else {
                val row = (y - 7) / 4
                if (y >= 7) active.remove(row)
            }
            val current = union(active.values)
            // New area has a top edge running right; lost area a bottom edge running left.
            difference(current, previous) { l, r -> edges.add(Edge(P(l, y), P(r, y))) }
            difference(previous, current) { l, r -> edges.add(Edge(P(r, y), P(l, y))) }
            if (y < lastY) for (s in current) {
                val left = leftEdges[s.left]
                if (left != null && edges[left].from == P(s.left, y)) {
                    edges[left] = edges[left].copy(from = P(s.left, y + 2))
                } else {
                    leftEdges[s.left] = edges.size
                    edges.add(Edge(P(s.left, y + 2), P(s.left, y)))
                }
                val right = rightEdges[s.right]
                if (right != null && edges[right].to == P(s.right, y)) {
                    edges[right] = edges[right].copy(to = P(s.right, y + 2))
                } else {
                    rightEdges[s.right] = edges.size
                    edges.add(Edge(P(s.right, y), P(s.right, y + 2)))
                }
            }
            previous = current
            y += 2
        }
        if (edges.isEmpty()) return emptyList()
        val outgoing = HashMap<P, MutableList<Int>>()
        for (i in edges.indices) outgoing.getOrPut(edges[i].from) { ArrayList(1) }.add(i)
        val used = BooleanArray(edges.size)
        val rings = ArrayList<List<P>>()
        for (first in edges.indices) {
            if (used[first]) continue
            val ring = ArrayList<P>()
            val start = edges[first].from
            var next = first
            do {
                val e = edges[next]
                check(!used[next]) { "fill trace: boundary did not form a closed ring" }
                used[next] = true
                ring.add(e.from)
                if (e.to == start) break
                next = outgoing[e.to]?.firstOrNull { !used[it] }
                    ?: error("fill trace: boundary ended before closing")
            } while (true)
            rings.add(simplify(ring))
        }
        val points = ArrayList<P>()
        val anchor = rings.first().first()
        points.addAll(rings.first())
        points.add(anchor) // Close first ring before walking any bridges.
        for (ring in rings.drop(1)) {
            val start = ring.first()
            val elbow = P(start.x, anchor.y)
            points.add(elbow)
            points.addAll(ring)
            points.add(start)
            points.add(elbow)
            points.add(anchor)
        }
        val samples = ArrayList<PenSample>(points.size)
        for (p in points) {
            val x = docX.toDouble() + p.x / 4.0
            val yy = docY.toDouble() + p.y / 4.0
            require(x.toFloat().toDouble() == x && yy.toFloat().toDouble() == yy) {
                "fill trace: document origin cannot preserve quarter-pixel shape coordinates"
            }
            if (samples.lastOrNull()?.let { it.x.toDouble() == x && it.y.toDouble() == yy } == true) continue
            samples.add(PenSample(x.toFloat(), yy.toFloat(), samples.size.toDouble()))
        }
        // At zoom 4, every orthogonal quarter-pixel edge/bridge is an integer number of the
        // smoother's one-screen-pixel steps. Replay therefore retains EVERY corner and both
        // coincident directions of a bridge exactly, even though FillPen always resamples.
        return listOf(StrokeRecord(id, brushId, 0L, 0f, 4f, samples, colorArgb))
    }

    private data class P(val x: Int, val y: Int)
    private data class Span(val left: Int, val right: Int)
    private data class Edge(val from: P, val to: P)

    private fun runs(w: Int, row: Int, region: ByteArray, from: Int, until: Int): List<Span> {
        val spans = ArrayList<Span>()
        var x = from
        val base = row * w
        while (x < until) {
            if (region[base + x].toInt() == 0) { x++; continue }
            val left = x
            do { x++ } while (x < until && region[base + x].toInt() != 0)
            spans.add(Span(left * 4 - 3, x * 4 + 3))
        }
        return spans
    }

    private fun union(rows: Collection<List<Span>>): List<Span> {
        val sorted = rows.flatMap { it }.sortedWith(compareBy<Span> { it.left }.thenBy { it.right })
        if (sorted.isEmpty()) return emptyList()
        val out = ArrayList<Span>()
        var left = sorted[0].left
        var right = sorted[0].right
        for (i in 1 until sorted.size) {
            val s = sorted[i]
            if (s.left <= right) right = maxOf(right, s.right)
            else { out.add(Span(left, right)); left = s.left; right = s.right }
        }
        out.add(Span(left, right))
        return out
    }

    /** A minus B, with both lists sorted/disjoint; linear, not all-pairs contour comparison. */
    private inline fun difference(a: List<Span>, b: List<Span>, emit: (Int, Int) -> Unit) {
        var j = 0
        for (s in a) {
            var from = s.left
            while (j < b.size && b[j].right <= from) j++
            var k = j
            while (k < b.size && b[k].left < s.right) {
                if (b[k].left > from) emit(from, minOf(s.right, b[k].left))
                from = maxOf(from, b[k].right)
                if (from >= s.right) break
                k++
            }
            if (from < s.right) emit(from, s.right)
        }
    }

    private fun simplify(ring: List<P>): List<P> = ring.filterIndexed { i, p ->
        val before = ring[(i + ring.size - 1) % ring.size]
        val after = ring[(i + 1) % ring.size]
        !((before.x == p.x && p.x == after.x) || (before.y == p.y && p.y == after.y))
    }
}
