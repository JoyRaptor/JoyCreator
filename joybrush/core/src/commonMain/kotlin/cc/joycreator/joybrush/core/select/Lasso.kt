package cc.joycreator.joybrush.core.select

import cc.joycreator.joybrush.core.paint.Tiles
import cc.joycreator.joybrush.core.shape.Pt
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The lasso: a closed polygon in, coverage out. Non-zero winding, 4 x 4 supersampled, iterative.
 *
 * WHY NON-ZERO AND NOT EVEN-ODD, since it is the one decision here a person can feel. A lasso that
 * wiggles back across itself, or a lasso closed twice because the person went round their subject
 * and did not notice, is one region to a person and two nested loops to an even-odd fill rule. The
 * usual result is a hole punched in the middle of the thing that was just selected. Counting
 * windings and filling where the count is not zero makes the shape the person drew the shape they
 * get, whatever the path did on the way.
 *
 * WHY A SCANLINE AND NOT A POINT-IN-POLYGON TEST. Sixteen samples per pixel means sixteen point
 * tests per pixel, and a point-in-polygon test is an edge walk, so a 2048 square lasso would be
 * 4.2 M x 16 x 3000 comparisons. The scanline pays for each edge ONCE per sub-row instead, and the
 * sixteen samples of a pixel are four runs of four consecutive sub-columns, which is four integer
 * increments each. Nothing here recurses, and every buffer is allocated once and reused.
 *
 * THE FILL RULE, precisely, because "non-zero" leaves the details to the implementer and the
 * details are the test. A sub-row at height `sy` collects an edge's crossing when exactly one of
 * its ends is at or above `sy` — `y0 <= sy` XOR `y1 <= sy` — which is the half-open rule that stops
 * a vertex shared by two edges being counted twice and a horizontal edge being counted at all.
 * The crossings are sorted by x, and the span from one crossing to the next is filled when the
 * winding accumulated BEFORE that next crossing is not zero: the left end of a span is inside it
 * and the right end is not, which is what makes a 45 degree edge land on a sample position or miss
 * it by a hair and never disagree with itself.
 */
internal object Lasso {

    /** Sub-samples per pixel per axis, and therefore also the number of sub-rows per pixel row. */
    private const val SUB = 4

    /** Sub-samples per pixel, which is the denominator the coverage is divided by. */
    private const val SAMPLES = SUB * SUB

    /** How far from the origin a box corner may be and still be addressable as an Int pixel. */
    private const val MAX_COORD = 1073741824

    /**
     * @return the filled polygon, or [SelectionMask.EMPTY] for fewer than three points, a
     *   non-finite coordinate, a box with no area, or a box wider or taller than
     *   [MAX_SELECT_SPAN].
     */
    fun rasterise(points: List<Pt>): SelectionMask {
        if (points.size < 3) return SelectionMask.EMPTY

        var minX = 0.0
        var minY = 0.0
        var maxX = 0.0
        var maxY = 0.0
        for (i in points.indices) {
            val p = points[i]
            if (p.x.isNaN() || p.y.isNaN() || p.x.isInfinite() || p.y.isInfinite()) {
                return SelectionMask.EMPTY
            }
            if (i == 0) {
                minX = p.x
                maxX = p.x
                minY = p.y
                maxY = p.y
            } else {
                if (p.x < minX) minX = p.x
                if (p.x > maxX) maxX = p.x
                if (p.y < minY) minY = p.y
                if (p.y > maxY) maxY = p.y
            }
        }

        // The cap is checked in Double, BEFORE anything is converted to Int, because a polygon at
        // x = 1e300 would floor to Int.MIN_VALUE and every arithmetic after that would be a wrap
        // rather than an answer.
        val spanX = ceil(maxX) - floor(minX)
        val spanY = ceil(maxY) - floor(minY)
        if (spanX <= 0.0 || spanY <= 0.0) return SelectionMask.EMPTY
        if (spanX > MAX_SELECT_SPAN || spanY > MAX_SELECT_SPAN) return SelectionMask.EMPTY

        val x0 = floor(minX).toInt()
        val y0 = floor(minY).toInt()
        val width = ceil(maxX).toInt() - x0
        val height = ceil(maxY).toInt() - y0
        if (width <= 0 || height <= 0) return SelectionMask.EMPTY

        // Tiles are addressed with Ints, so a box whose corner is further than 2^30 from the origin
        // is not addressable at all: x0 + width would wrap to a negative pixel rather than step
        // past the edge of the canvas. Nothing a document can hold is anywhere near this.
        if (x0 < -MAX_COORD || x0 > MAX_COORD || y0 < -MAX_COORD || y0 > MAX_COORD) {
            return SelectionMask.EMPTY
        }

        val edges = Edges(points)
        if (edges.count == 0) return SelectionMask.EMPTY

        // One row of sample counts, in quarter-pixel units: sub-column q of the row is at
        // x0 + q / 4 + 1/8, so a span's bounds convert into quarter units with a single ceil and
        // the loop is integer increments. Long, because x0 can be a document coordinate near the
        // limit of Int and four times it is not.
        val qBase = x0.toLong() * SUB
        val qLimit = qBase + width.toLong() * SUB
        val counts = IntArray(width * SUB)
        val cov = IntArray(width)
        val xs = DoubleArray(edges.count)
        val dirs = IntArray(edges.count)
        val tiles = HashMap<Long, ByteArray>()

        for (row in 0 until height) {
            val y = y0 + row
            counts.fill(0)
            for (sub in 0 until SUB) {
                val sy = y.toDouble() + (sub + 0.5) / SUB
                val crossings = edges.at(sy, xs, dirs)
                sortCrossings(xs, dirs, crossings)
                var winding = 0
                var havePrevious = false
                var previousX = 0.0
                for (i in 0 until crossings) {
                    val cx = xs[i]
                    if (havePrevious && winding != 0) {
                        var from = ceil(previousX * 4.0 - 0.5).toLong()
                        if (from < qBase) from = qBase
                        var until = ceil(cx * 4.0 - 0.5).toLong()
                        if (until > qLimit) until = qLimit
                        var q = (from - qBase).toInt()
                        val end = (until - qBase).toInt()
                        while (q < end) {
                            counts[q] = counts[q] + 1
                            q++
                        }
                    }
                    winding += dirs[i]
                    previousX = cx
                    havePrevious = true
                }
            }

            var firstCovered = -1
            var px = 0
            while (px < width) {
                val b = px * SUB
                val inside = counts[b] + counts[b + 1] + counts[b + 2] + counts[b + 3]
                // round(inside * 255 / 16), as an Int: 16 samples gives 4080 + 8 = 4088 / 16 = 255,
                // and 6 samples gives 1530 + 8 = 1538 / 16 = 96, which is round(95.625).
                val c = (inside * 255 + SAMPLES / 2) / SAMPLES
                cov[px] = c
                if (c != 0 && firstCovered < 0) firstCovered = px
                px++
            }
            if (firstCovered >= 0) writeRow(tiles, cov, x0, y, width, firstCovered)
        }

        return SelectionMask(finalise(tiles))
    }

    /**
     * The edges, flattened into parallel arrays and sorted so that one sub-row is four array reads
     * per edge and nothing else.
     *
     * Each edge is stored LOWER END FIRST with its slope already divided out, because the half-open
     * crossing test only cares about the y range and the crossing x is then one multiply-add. The
     * winding direction is stored rather than recomputed: an edge running DOWN the page (y1 > y0)
     * adds one to the winding to its right, and one going up subtracts one — which is the sign the
     * polygon has as drawn, not the sign of the enclosed area, and is why a lasso drawn clockwise
     * and one drawn anticlockwise both fill.
     *
     * Horizontal edges are DROPPED rather than special-cased later: their y range is empty, so they
     * can never produce a crossing, and storing them would mean an infinite slope in the array.
     */
    private class Edges(points: List<Pt>) {
        private val x: DoubleArray
        private val yLow: DoubleArray
        private val yHigh: DoubleArray
        private val slope: DoubleArray
        private val down: BooleanArray

        val count: Int

        init {
            val n = points.size
            val xs = DoubleArray(n)
            val lo = DoubleArray(n)
            val hi = DoubleArray(n)
            val sl = DoubleArray(n)
            val dn = BooleanArray(n)
            var m = 0
            for (i in 0 until n) {
                val p0 = points[i]
                val p1 = points[(i + 1) % n]
                if (p0.y == p1.y) continue
                val goingDown = p1.y > p0.y
                val a = if (goingDown) p0 else p1
                val b = if (goingDown) p1 else p0
                xs[m] = a.x
                lo[m] = a.y
                hi[m] = b.y
                sl[m] = (b.x - a.x) / (b.y - a.y)
                dn[m] = goingDown
                m++
            }
            x = xs
            yLow = lo
            yHigh = hi
            slope = sl
            down = dn
            count = m
        }

        /**
         * The crossings of every edge with the horizontal line [sy], written into [xs] and [dirs].
         *
         * @return how many there are, which is also how much of the two arrays to sort.
         */
        fun at(sy: Double, xs: DoubleArray, dirs: IntArray): Int {
            var c = 0
            for (i in 0 until count) {
                if (sy < yLow[i]) continue
                if (sy >= yHigh[i]) continue
                xs[c] = x[i] + (sy - yLow[i]) * slope[i]
                dirs[c] = if (down[i]) 1 else -1
                c++
            }
            return c
        }
    }

    /**
     * Sorts the first [n] crossings by x, carrying the direction along. An insertion sort, and
     * deliberately so: a closed curve crosses a scanline a handful of times, so the array it is
     * given is nearly sorted and nearly short, and the general sort would be a second set of
     * bookkeeping for no gain. A lasso that really does cross a sub-row hundreds of times is a
     * star shape, where this is slower but still not wrong.
     */
    private fun sortCrossings(xs: DoubleArray, dirs: IntArray, n: Int) {
        for (i in 1 until n) {
            val keyX = xs[i]
            val keyDir = dirs[i]
            var j = i - 1
            while (j >= 0 && xs[j] > keyX) {
                xs[j + 1] = xs[j]
                dirs[j + 1] = dirs[j]
                j--
            }
            xs[j + 1] = keyX
            dirs[j + 1] = keyDir
        }
    }

    /**
     * Writes one row of coverage, tile by tile, from its first covered pixel to the end.
     *
     * The row is split at tile boundaries by ARITHMETIC rather than by asking which tile each pixel
     * is in, so a 2048 wide row costs a handful of divisions rather than 2048 of them. A tile is
     * only created once something is written into it, and [finalise] drops any that stayed empty.
     */
    private fun writeRow(
        tiles: HashMap<Long, ByteArray>,
        cov: IntArray,
        x0: Int,
        y: Int,
        width: Int,
        firstCovered: Int,
    ) {
        val ty = tileOfPx(y)
        val rowBase = (y - ty * Tiles.SIZE) * Tiles.SIZE
        var x = firstCovered
        while (x < width) {
            val tx = tileOfPx(x0 + x)
            val tileLeft = tx * Tiles.SIZE
            val from = if (tileLeft > x0) tileLeft - x0 else x
            val until = minOf(tileLeft - x0 + Tiles.SIZE - 1, width - 1)
            val tile = writable(tiles, Tiles.key(tx, ty))
            for (p in from..until) {
                val c = cov[p]
                if (c != 0) tile[rowBase + (x0 + p - tileLeft)] = c.toByte()
            }
            x = until + 1
        }
    }
}
