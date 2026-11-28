package cc.joycreator.joybrush.core.trace

/**
 * A filled shape traced along its edge: marching squares over the ink's own alpha, so the edge falls where the paint is
 * half there (between pixels, not on their staircase). One closed line per edge: the outside, and each hole.
 *
 * [value] is the shape's alpha in its own box (0 outside the shape), [w] x [h], [ox], [oy] from the picture's corner.
 */
internal object Outlines {

    fun trace(w: Int, h: Int, value: FloatArray, level: Float, ox: Int, oy: Int, tolerance: Float, halfWidth: Float): List<RawLine> {
        // Samples sit at pixel centres; a frame of empty samples round the box closes every edge.
        val gw = w + 2
        val gh = h + 2
        fun v(x: Int, y: Int): Float = if (x in 1..w && y in 1..h) value[(y - 1) * w + (x - 1)] else 0f
        // A crossing on a horizontal grid edge (x, y)-(x+1, y) is id 2*(y*gw+x); on a vertical one (x, y)-(x, y+1), +1.
        val at = HashMap<Int, FloatArray>()
        val joins = HashMap<Int, MutableList<Int>>()
        fun crossing(id: Int): Int {
            if (id !in at) {
                val horiz = id % 2 == 0
                val cell = id / 2
                val x = cell % gw
                val y = cell / gw
                val a = v(x, y)
                val b = if (horiz) v(x + 1, y) else v(x, y + 1)
                val t = if (b == a) 0.5f else ((level - a) / (b - a)).coerceIn(0f, 1f)
                at[id] = if (horiz) floatArrayOf(x + t, y.toFloat()) else floatArrayOf(x.toFloat(), y + t)
            }
            return id
        }
        fun join(a: Int, b: Int) {
            joins.getOrPut(crossing(a)) { ArrayList() } += crossing(b)
            joins.getOrPut(crossing(b)) { ArrayList() } += crossing(a)
        }
        for (y in 0 until gh - 1) for (x in 0 until gw - 1) {
            val tl = v(x, y) >= level
            val tr = v(x + 1, y) >= level
            val br = v(x + 1, y + 1) >= level
            val bl = v(x, y + 1) >= level
            val top = 2 * (y * gw + x)
            val bottom = 2 * ((y + 1) * gw + x)
            val left = 2 * (y * gw + x) + 1
            val right = 2 * (y * gw + x + 1) + 1
            val code = (if (tl) 8 else 0) or (if (tr) 4 else 0) or (if (br) 2 else 0) or (if (bl) 1 else 0)
            when (code) {
                0, 15 -> {}
                1, 14 -> join(left, bottom)
                2, 13 -> join(bottom, right)
                3, 12 -> join(left, right)
                4, 11 -> join(top, right)
                6, 9 -> join(top, bottom)
                7, 8 -> join(left, top)
                5, 10 -> {
                    // A saddle: the cell's middle decides whether the two inside corners are joined.
                    val mid = (v(x, y) + v(x + 1, y) + v(x + 1, y + 1) + v(x, y + 1)) / 4f >= level
                    if ((code == 5) == mid) { join(left, top); join(bottom, right) } else { join(left, bottom); join(top, right) }
                }
            }
        }
        val out = ArrayList<RawLine>()
        val done = HashSet<Int>()
        for (start in joins.keys.sorted()) {
            if (start in done) continue
            val loop = ArrayList<Int>()
            var prev = -1
            var cur = start
            while (cur !in done) {
                done += cur
                loop += cur
                val next = joins[cur]!!.firstOrNull { it != prev && it !in done } ?: break
                prev = cur
                cur = next
            }
            if (loop.size < 3) continue
            // Grid sample (x, y) is pixel (x - 1, y - 1)'s centre: picture position x - 1 + 0.5.
            val xs = FloatArray(loop.size) { at[loop[it]]!![0] - 0.5f + ox }
            val ys = FloatArray(loop.size) { at[loop[it]]!![1] - 0.5f + oy }
            val keep = TraceMath.simplifyClosed(xs, ys, tolerance)
            out += RawLine(FloatArray(keep.size) { xs[keep[it]] }, FloatArray(keep.size) { ys[keep[it]] },
                FloatArray(keep.size) { halfWidth }, true)
        }
        return out
    }
}
