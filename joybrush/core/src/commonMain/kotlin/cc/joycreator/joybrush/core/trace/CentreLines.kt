package cc.joycreator.joybrush.core.trace

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/** One traced polyline before it is coloured: points in picture pixels, a half-width each. */
internal class RawLine(val xs: FloatArray, val ys: FloatArray, val halfWidths: FloatArray, val closed: Boolean)

/**
 * A thin mark traced along its middle: thin it to a one-pixel skeleton (Zhang-Suen), find its ends and crossings, pair
 * the branches that run straight on through a crossing (an X is two lines that cross, a T a bar and a stem), and walk
 * each run of pixels into a line. Every grid here is the mark's own box, [ox], [oy] from the picture's corner.
 */
internal object CentreLines {

    fun trace(w: Int, h: Int, inside: BooleanArray, dist: FloatArray, ox: Int, oy: Int, tolerance: Float): List<RawLine> {
        val skel = inside.copyOf()
        thin(w, h, skel)
        repeat(3) { if (!prune(w, h, skel, dist)) return@repeat }
        val graph = Graph(w, h, skel, dist)
        return graph.lines().mapNotNull { finish(it, ox, oy, tolerance) }
    }

    // ── thinning ─────────────────────────────────────────────────────────────────────────────

    private fun thin(w: Int, h: Int, img: BooleanArray) {
        fun at(x: Int, y: Int) = x in 0 until w && y in 0 until h && img[y * w + x]
        val drop = ArrayList<Int>()
        var changed = true
        while (changed) {
            changed = false
            for (step in 0..1) {
                drop.clear()
                for (y in 0 until h) for (x in 0 until w) {
                    if (!img[y * w + x]) continue
                    val p2 = at(x, y - 1); val p3 = at(x + 1, y - 1); val p4 = at(x + 1, y); val p5 = at(x + 1, y + 1)
                    val p6 = at(x, y + 1); val p7 = at(x - 1, y + 1); val p8 = at(x - 1, y); val p9 = at(x - 1, y - 1)
                    val ring = booleanArrayOf(p2, p3, p4, p5, p6, p7, p8, p9)
                    val b = ring.count { it }
                    if (b < 2 || b > 6) continue
                    var a = 0
                    for (i in 0 until 8) if (!ring[i] && ring[(i + 1) % 8]) a++
                    if (a != 1) continue
                    if (step == 0) {
                        if (p2 && p4 && p6) continue
                        if (p4 && p6 && p8) continue
                    } else {
                        if (p2 && p4 && p8) continue
                        if (p2 && p6 && p8) continue
                    }
                    drop += y * w + x
                }
                if (drop.isNotEmpty()) changed = true
                for (i in drop) img[i] = false
            }
        }
    }

    /** Neighbours that count as joined: four-way always, diagonal only when no four-way pixel already joins the pair. */
    private fun neighbours(w: Int, h: Int, img: BooleanArray, i: Int, out: IntArray): Int {
        val x = i % w
        val y = i / w
        fun at(xx: Int, yy: Int) = xx in 0 until w && yy in 0 until h && img[yy * w + xx]
        var n = 0
        if (at(x, y - 1)) out[n++] = (y - 1) * w + x
        if (at(x + 1, y)) out[n++] = y * w + x + 1
        if (at(x, y + 1)) out[n++] = (y + 1) * w + x
        if (at(x - 1, y)) out[n++] = y * w + x - 1
        if (at(x + 1, y - 1) && !at(x, y - 1) && !at(x + 1, y)) out[n++] = (y - 1) * w + x + 1
        if (at(x + 1, y + 1) && !at(x, y + 1) && !at(x + 1, y)) out[n++] = (y + 1) * w + x + 1
        if (at(x - 1, y + 1) && !at(x, y + 1) && !at(x - 1, y)) out[n++] = (y + 1) * w + x - 1
        if (at(x - 1, y - 1) && !at(x, y - 1) && !at(x - 1, y)) out[n++] = (y - 1) * w + x - 1
        return n
    }

    /** Removes short branches from an end to a crossing (the bristles thinning leaves). True when one went. */
    private fun prune(w: Int, h: Int, img: BooleanArray, dist: FloatArray): Boolean {
        val nb = IntArray(8)
        var any = false
        for (start in img.indices) {
            if (!img[start] || neighbours(w, h, img, start, nb) != 1) continue
            val path = arrayListOf(start)
            var prev = -1
            var cur = start
            var endsAtCrossing = false
            while (true) {
                val n = neighbours(w, h, img, cur, nb)
                if (cur != start && n >= 3) { endsAtCrossing = true; break }
                if (cur != start && n != 2) break
                var next = -1
                for (k in 0 until n) if (nb[k] != prev && nb[k] != cur) { next = nb[k]; break }
                if (next < 0 || path.size > 4096) break
                prev = cur
                cur = next
                path += cur
            }
            if (!endsAtCrossing) continue
            val crossing = path.last()
            val reach = 2f * dist[crossing] + 2f
            if (path.size - 1 < reach) {
                for (k in 0 until path.size - 1) img[path[k]] = false
                any = true   // the crossing may no longer be one: the next pass sees the new shape
            }
        }
        return any
    }

    // ── the skeleton as ends, crossings and runs ──────────────────────────────────────────────

    private class Edge(val pixels: IntArray, val fromNode: Int, val toNode: Int)

    private class Graph(val w: Int, val h: Int, val img: BooleanArray, val dist: FloatArray) {
        val nb = IntArray(8)
        val degree = IntArray(w * h)
        val nodeOf = IntArray(w * h) { -1 }
        val nodeX = ArrayList<Float>()
        val nodeY = ArrayList<Float>()
        val nodeHw = ArrayList<Float>()
        val edges = ArrayList<Edge>()
        val visited = HashSet<Int>()
        val rings = ArrayList<IntArray>()
        val area = w.toLong() * h

        init {
            for (i in img.indices) if (img[i]) degree[i] = neighbours(w, h, img, i, nb)
            // Crossing pixels next to each other are one crossing; an end is a node of its own.
            for (i in img.indices) {
                if (!img[i] || degree[i] == 2 || nodeOf[i] >= 0) continue
                val id = nodeX.size
                val stack = arrayListOf(i)
                nodeOf[i] = id
                var sx = 0f; var sy = 0f; var sh = 0f; var c = 0
                while (stack.isNotEmpty()) {
                    val p = stack.removeAt(stack.size - 1)
                    sx += p % w + 0.5f; sy += p / w + 0.5f; sh += dist[p]; c++
                    if (degree[p] < 3) continue
                    val n = neighbours(w, h, img, p, nb)
                    for (k in 0 until n) {
                        val q = nb[k]
                        if (degree[q] >= 3 && nodeOf[q] < 0) { nodeOf[q] = id; stack += q }
                    }
                }
                nodeX += sx / c; nodeY += sy / c; nodeHw += sh / c
            }
            val seen = HashSet<Long>()
            val around = IntArray(8)
            for (i in img.indices) {
                if (!img[i] || nodeOf[i] < 0) continue
                val m = neighbours(w, h, img, i, around)
                for (k in 0 until m) {
                    val first = around[k]
                    if (nodeOf[first] == nodeOf[i]) continue
                    if (i.toLong() * area + first in seen) continue
                    walk(i, first, seen)
                }
            }
            // Rings: runs with no end and no crossing.
            for (i in img.indices) {
                if (!img[i] || degree[i] != 2 || nodeOf[i] >= 0 || i in visited) continue
                val ring = arrayListOf(i)
                visited += i
                var prev = -1
                var cur = i
                while (true) {
                    val m = neighbours(w, h, img, cur, nb)
                    var next = -1
                    for (k in 0 until m) if (nb[k] != prev) { next = nb[k]; break }
                    if (next < 0 || next == i || !visited.add(next)) break
                    prev = cur
                    cur = next
                    ring += cur
                }
                if (ring.size >= 3) rings += ring.toIntArray()
            }
        }

        private fun walk(start: Int, first: Int, seen: HashSet<Long>) {
            val path = arrayListOf(start)
            var prev = start
            var cur = first
            seen += start.toLong() * area + first
            while (nodeOf[cur] < 0) {
                path += cur
                visited += cur
                val m = neighbours(w, h, img, cur, nb)
                var next = -1
                for (k in 0 until m) if (nb[k] != prev) { next = nb[k]; break }
                if (next < 0) break
                prev = cur
                cur = next
            }
            if (nodeOf[cur] < 0) return
            path += cur
            seen += cur.toLong() * area + prev
            edges += Edge(path.toIntArray(), nodeOf[start], nodeOf[cur])
        }

        /** Every line: runs joined straight through crossings, then the rings. */
        fun lines(): List<RawLine> {
            val out = ArrayList<RawLine>()
            mergeCloseCrossings()
            // Each edge has two ends: 2e is its start, 2e+1 its end. [link] pairs ends that run on through a crossing.
            val link = IntArray(edges.size * 2) { -1 }
            val endsAt = HashMap<Int, MutableList<Int>>()
            for (e in edges.indices) {
                if (dropped[e]) continue
                endsAt.getOrPut(edges[e].fromNode) { ArrayList() } += 2 * e
                endsAt.getOrPut(edges[e].toNode) { ArrayList() } += 2 * e + 1
            }
            // A node nothing runs from is a dot: a mark too small to have a length.
            for (n in nodeX.indices) if (root(n) == n && n !in endsAt && isolated(n)) {
                out += RawLine(floatArrayOf(nodeX[n]), floatArrayOf(nodeY[n]), floatArrayOf(nodeHw[n]), false)
            }
            for ((node, ends) in endsAt) {
                if (ends.size < 3) {
                    // an end of the mark, or a run that only bends here: two ends just join
                    if (ends.size == 2) { link[ends[0]] = ends[1]; link[ends[1]] = ends[0] }
                    continue
                }
                val dirs = ends.map { direction(it, node) }
                val pairs = ArrayList<Triple<Double, Int, Int>>()
                for (a in ends.indices) for (b in a + 1 until ends.size) {
                    val dot = dirs[a].first * dirs[b].first + dirs[a].second * dirs[b].second
                    pairs += Triple(dot, a, b)
                }
                pairs.sortBy { it.first }
                for ((dot, a, b) in pairs) {
                    if (dot > -0.5) break
                    if (link[ends[a]] >= 0 || link[ends[b]] >= 0) continue
                    link[ends[a]] = ends[b]
                    link[ends[b]] = ends[a]
                }
            }
            val used = BooleanArray(edges.size) { dropped[it] }
            fun chainFrom(startEnd: Int): RawLine {
                val xs = ArrayList<Float>(); val ys = ArrayList<Float>(); val hw = ArrayList<Float>()
                var end = startEnd           // we enter the edge at this end
                var closed = false
                startNode(end)?.let { (x, y, r) -> xs += x; ys += y; hw += r }
                while (true) {
                    val e = end / 2
                    used[e] = true
                    val px = edges[e].pixels
                    val forward = end % 2 == 0
                    for (k in 1 until px.size - 1) {
                        val p = px[if (forward) k else px.size - 1 - k]
                        xs += p % w + 0.5f; ys += p / w + 0.5f; hw += dist[p]
                    }
                    val far = if (forward) 2 * e + 1 else 2 * e
                    val node = if (forward) edges[e].toNode else edges[e].fromNode
                    xs += nodeX[node]; ys += nodeY[node]; hw += nodeHw[node]
                    val next = link[far]
                    if (next < 0) break
                    if (used[next / 2]) { closed = next == startEnd; break }
                    end = next
                }
                if (closed && xs.size > 1) { xs.removeAt(xs.size - 1); ys.removeAt(ys.size - 1); hw.removeAt(hw.size - 1) }
                return RawLine(xs.toFloatArray(), ys.toFloatArray(), hw.toFloatArray(), closed)
            }
            // Open chains first, from an end that nothing continues.
            for (e in edges.indices) {
                if (used[e]) continue
                when {
                    link[2 * e] < 0 -> out += chainFrom(2 * e)
                    link[2 * e + 1] < 0 -> out += chainFrom(2 * e + 1)
                }
            }
            for (e in edges.indices) if (!used[e]) out += chainFrom(2 * e)
            for (r in rings) out += RawLine(
                FloatArray(r.size) { r[it] % w + 0.5f }, FloatArray(r.size) { r[it] / w + 0.5f },
                FloatArray(r.size) { dist[r[it]] }, true,
            )
            return out
        }

        private val parent = IntArray(0).toMutableList()
        private var dropped = BooleanArray(0)

        private fun root(n: Int): Int {
            var r = n
            while (parent[r] != r) r = parent[r]
            return r
        }

        private fun isolated(n: Int): Boolean = edges.none { root(it.fromNode) == n || root(it.toNode) == n }

        /**
         * Two forks closer together than the mark is wide are one crossing: thinning splits a shallow X (a figure
         * eight's waist) into two forks and a stub between them, and the branches only pair up right across both.
         */
        private fun mergeCloseCrossings() {
            parent.clear()
            for (n in nodeX.indices) parent += n
            dropped = BooleanArray(edges.size)
            val crossing = BooleanArray(nodeX.size)
            val endCount = IntArray(nodeX.size)
            for (e in edges) { endCount[e.fromNode]++; endCount[e.toNode]++ }
            for (n in nodeX.indices) crossing[n] = endCount[n] >= 3
            for (e in edges.indices) {
                val a = edges[e].fromNode
                val b = edges[e].toNode
                if (a == b || !crossing[a] || !crossing[b]) continue
                val reach = 2f * (nodeHw[a] + nodeHw[b]) + 2f
                if (edges[e].pixels.size - 1 > reach) continue
                val ra = root(a)
                val rb = root(b)
                if (ra == rb) { dropped[e] = true; continue }
                dropped[e] = true
                // the merged crossing sits between the two
                nodeX[ra] = (nodeX[ra] + nodeX[rb]) / 2f
                nodeY[ra] = (nodeY[ra] + nodeY[rb]) / 2f
                nodeHw[ra] = maxOf(nodeHw[ra], nodeHw[rb])
                parent[rb] = ra
            }
            for (i in edges.indices) {
                if (dropped[i]) continue
                val e = edges[i]
                val f = root(e.fromNode)
                val t = root(e.toNode)
                if (f != e.fromNode || t != e.toNode) edges[i] = Edge(e.pixels, f, t)
            }
        }

        private fun startNode(end: Int): Triple<Float, Float, Float>? {
            val e = end / 2
            val node = if (end % 2 == 0) edges[e].fromNode else edges[e].toNode
            return Triple(nodeX[node], nodeY[node], nodeHw[node])
        }

        /** Which way an edge leaves [node], measured some way out so the crossing's own pixels do not bend it. */
        private fun direction(end: Int, node: Int): Pair<Double, Double> {
            val px = edges[end / 2].pixels
            val forward = end % 2 == 0
            val reach = max(6f, 3f * nodeHw[node])
            var best = px[if (forward) px.size - 1 else 0]
            var acc = 0f
            var lastX = nodeX[node]; var lastY = nodeY[node]
            for (k in px.indices) {
                val p = px[if (forward) k else px.size - 1 - k]
                val x = p % w + 0.5f; val y = p / w + 0.5f
                acc += hypot(x - lastX, y - lastY)
                lastX = x; lastY = y
                best = p
                if (acc >= reach) break
            }
            val dx = (best % w + 0.5f - nodeX[node]).toDouble()
            val dy = (best / w + 0.5f - nodeY[node]).toDouble()
            val len = sqrt(dx * dx + dy * dy)
            return if (len == 0.0) 0.0 to 0.0 else dx / len to dy / len
        }
    }

    /** Pixel centres to a clean line: smoothed, simplified, half-widths from the distance to the mark's edge. */
    private fun finish(raw: RawLine, ox: Int, oy: Int, tolerance: Float): RawLine? {
        if (raw.xs.isEmpty()) return null
        val xs = raw.xs.copyOf(); val ys = raw.ys.copyOf()
        TraceMath.smooth(xs, ys, raw.closed, 2)
        val keep = if (raw.closed) TraceMath.simplifyClosed(xs, ys, tolerance) else TraceMath.simplifyOpen(xs, ys, tolerance)
        val hw = FloatArray(keep.size) { k ->
            // the distance from a skeleton pixel's centre to the nearest empty pixel's centre, less half a pixel
            TraceMath.clampMin(raw.halfWidths[keep[k]] - 0.5f, 0.5f)
        }
        return RawLine(FloatArray(keep.size) { xs[keep[it]] + ox }, FloatArray(keep.size) { ys[keep[it]] + oy }, hw, raw.closed)
    }
}
