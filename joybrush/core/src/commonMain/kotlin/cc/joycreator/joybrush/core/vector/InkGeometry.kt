package cc.joycreator.joybrush.core.vector

import kotlin.math.floor
import kotlin.math.min

/**
 * JB-5.10 — one ink line as the eraser sees it.
 *
 * A line is a CENTRELINE plus a half-width per point, never pixels: the eraser decides what to cut
 * by measuring how close the centreline comes to its own path. [xs], [ys] and [halfWidths] are
 * parallel arrays of the same length; the centreline is the polyline through (xs[i], ys[i]), and
 * the half-width is interpolated linearly between points inside a segment.
 *
 * Length 1 is legal and means a dot: a dab with no travel. Such a line is touched or it is not.
 *
 * Equality is by content, because a [Piece] set computed from one copy of a line has to be
 * comparable with the set computed from an identical copy.
 */
data class InkLine(
    val id: String,
    val xs: DoubleArray,
    val ys: DoubleArray,
    val halfWidths: DoubleArray,
) {
    init {
        require(xs.size == ys.size && xs.size == halfWidths.size) {
            "InkLine '$id': xs, ys and halfWidths must be the same length " +
                "(${xs.size}, ${ys.size}, ${halfWidths.size})"
        }
        require(xs.isNotEmpty()) { "InkLine '$id' needs at least one point" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        val o = other as? InkLine ?: return false
        return id == o.id && xs.contentEquals(o.xs) && ys.contentEquals(o.ys) &&
            halfWidths.contentEquals(o.halfWidths)
    }

    override fun hashCode(): Int {
        var h = id.hashCode()
        h = 31 * h + xs.contentHashCode()
        h = 31 * h + ys.contentHashCode()
        h = 31 * h + halfWidths.contentHashCode()
        return h
    }
}

/** A point of a line, in the three things the eraser needs: where it is and how fat it is there. */
data class InkPoint(val x: Double, val y: Double, val halfWidth: Double)

/** How many points this line has. */
val InkLine.pointCount: Int get() = xs.size

/** The largest parameter a piece of this line can name: its last point index. */
val InkLine.lastParam: Double get() = (pointCount - 1).toDouble()

/** How many segments a line of [pointCount] points has. A dot still has one (degenerate) segment. */
internal fun segmentCountOf(pointCount: Int): Int = if (pointCount <= 1) 1 else pointCount - 1

/**
 * The point at fractional index [param]: 3.25 is a quarter of the way from point 3 to point 4.
 * Clamped to the line, so a piece can never name a point that is not there.
 */
fun InkLine.pointAt(param: Double): InkPoint {
    val n = pointCount
    if (n == 1) return InkPoint(xs[0], ys[0], halfWidths[0])
    val p = param.coerceIn(0.0, lastParam)
    val i = min(p.toInt(), n - 2)
    val t = p - i
    return InkPoint(
        xs[i] + t * (xs[i + 1] - xs[i]),
        ys[i] + t * (ys[i + 1] - ys[i]),
        halfWidths[i] + t * (halfWidths[i + 1] - halfWidths[i]),
    )
}

/** Length in doc px of the piece of centreline between parameters [from] and [to]. */
fun InkLine.arcLengthBetween(from: Double, to: Double): Double {
    val n = pointCount
    if (n < 2) return 0.0
    val a = from.coerceIn(0.0, lastParam)
    val b = to.coerceIn(0.0, lastParam)
    if (b <= a) return 0.0
    var total = 0.0
    var i = floor(a).toInt().coerceIn(0, n - 2)
    var at = a // the parameter we are standing at, which is a for the first segment
    while (i < n - 1) {
        val end = (i + 1).toDouble()
        val stop = if (b < end) b else end
        total += (stop - at) * len(xs[i + 1] - xs[i], ys[i + 1] - ys[i])
        if (b <= end) return total
        at = end
        i++
    }
    return total
}

/** Length in doc px of the whole centreline. */
val InkLine.totalArcLength: Double get() = arcLengthBetween(0.0, lastParam)

/** Doc px of centreline this piece keeps. */
fun Piece.arcLengthIn(line: InkLine): Double = line.arcLengthBetween(from, to)
