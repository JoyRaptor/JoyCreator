package cc.joycreator.joybrush.core.select

import cc.joycreator.joybrush.core.vector.InkLine
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The whole-line eraser's hit test (Concepts' stroke eraser, owner 11-27): which lines a stretch of the eraser's path
 * touches. A line is a RIBBON with a half-width (as [cc.joycreator.joybrush.core.vector.StrokePicker] reads it), and the
 * eraser is a disc of [radius] dragged from A to B, so a line is touched when the closest its centreline comes to the
 * segment AB is within its own half-width plus the eraser's radius. All in document px. Pure, no Android.
 */
object StrokeEraseHit {

    /** Indices into [lines] of every line the eraser touches between ([ax], [ay]) and ([bx], [by]). */
    fun touched(lines: List<InkLine>, ax: Double, ay: Double, bx: Double, by: Double, radius: Double): List<Int> {
        val r = if (radius.isFinite() && radius > 0.0) radius else 0.0
        val out = ArrayList<Int>()
        for (i in lines.indices) if (touches(lines[i], ax, ay, bx, by, r)) out.add(i)
        return out
    }

    private fun touches(line: InkLine, ax: Double, ay: Double, bx: Double, by: Double, r: Double): Boolean {
        val n = line.xs.size
        var widest = 0.0
        for (w in line.halfWidths) if (w > widest) widest = w
        val reach = r + widest
        // Cheap reject: the line's box, grown by how far it can be reached, against the eraser segment's box.
        var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        for (k in 0 until n) {
            minX = min(minX, line.xs[k]); maxX = max(maxX, line.xs[k]); minY = min(minY, line.ys[k]); maxY = max(maxY, line.ys[k])
        }
        if (max(ax, bx) < minX - reach || min(ax, bx) > maxX + reach || max(ay, by) < minY - reach || min(ay, by) > maxY + reach)
            return false
        if (n == 1) return segmentDistance(line.xs[0], line.ys[0], line.xs[0], line.ys[0], ax, ay, bx, by) <= r + line.halfWidths[0]
        for (k in 0 until n - 1) {
            val d = segmentDistance(line.xs[k], line.ys[k], line.xs[k + 1], line.ys[k + 1], ax, ay, bx, by)
            if (d <= r + max(line.halfWidths[k], line.halfWidths[k + 1])) return true
        }
        return false
    }

    /** The closest two segments PQ and AB come, in px. Zero when they cross. */
    internal fun segmentDistance(px: Double, py: Double, qx: Double, qy: Double,
                                 ax: Double, ay: Double, bx: Double, by: Double): Double {
        if (crosses(px, py, qx, qy, ax, ay, bx, by)) return 0.0
        return min(min(pointToSegment(px, py, ax, ay, bx, by), pointToSegment(qx, qy, ax, ay, bx, by)),
            min(pointToSegment(ax, ay, px, py, qx, qy), pointToSegment(bx, by, px, py, qx, qy)))
    }

    private fun pointToSegment(x: Double, y: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (((x - ax) * dx + (y - ay) * dy) / len2).coerceIn(0.0, 1.0)
        val cx = ax + t * dx - x; val cy = ay + t * dy - y
        return sqrt(cx * cx + cy * cy)
    }

    private fun crosses(px: Double, py: Double, qx: Double, qy: Double,
                        ax: Double, ay: Double, bx: Double, by: Double): Boolean {
        val d1 = cross(ax, ay, bx, by, px, py); val d2 = cross(ax, ay, bx, by, qx, qy)
        val d3 = cross(px, py, qx, qy, ax, ay); val d4 = cross(px, py, qx, qy, bx, by)
        return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))
    }

    private fun cross(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double): Double =
        (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
}
