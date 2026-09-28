package cc.joycreator.joybrush.core.vector

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// JB-5.10 — the low-level segment maths the vector eraser is built from. Everything here is exact
// closed-form geometry on doubles; no sampling, no iteration, no platform types.

/**
 * Slack used when deciding "parallel", "collinear" and "these two boxes can still meet".
 * Document coordinates are pixel counts, so 1e-9 is far below anything a user can see and far
 * above the rounding noise of doubles at document scale (~1e3..1e5).
 */
internal const val GEOM_EPS = 1e-9

/** Length of the vector (dx, dy). Cheaper and steadier than hypot, which overflows far sooner. */
internal fun len(dx: Double, dy: Double): Double = sqrt(dx * dx + dy * dy)

/** A closed interval in line-parameter space (fractional point index — see [Piece]). */
internal data class ParamRange(val from: Double, val to: Double) {
    init {
        require(from <= to) { "ParamRange($from, $to) is empty" }
    }
}

/** True when segment A's bounding box, grown by [pad], can still meet segment B's. */
internal fun boxesMayTouch(
    ax: Double, ay: Double, bx: Double, by: Double, pad: Double,
    cx: Double, cy: Double, dx: Double, dy: Double,
): Boolean =
    min(ax, bx) - pad <= max(cx, dx) && max(ax, bx) + pad >= min(cx, dx) &&
        min(ay, by) - pad <= max(cy, dy) && max(ay, by) + pad >= min(cy, dy)

/** Distance from the point (px, py) to the segment (ax, ay)-(bx, by). A zero-length segment is a dot. */
internal fun pointSegmentDistance(
    px: Double, py: Double,
    ax: Double, ay: Double, bx: Double, by: Double,
): Double {
    val dx = bx - ax
    val dy = by - ay
    val len2 = dx * dx + dy * dy
    if (len2 <= 0.0) return len(px - ax, py - ay)
    var t = ((px - ax) * dx + (py - ay) * dy) / len2
    if (t < 0.0) t = 0.0 else if (t > 1.0) t = 1.0
    return len(px - (ax + t * dx), py - (ay + t * dy))
}

/**
 * Distance between segment A (ax, ay)-(bx, by) and segment B (cx, cy)-(dx, dy).
 *
 * Two steps, because no single formula covers both cases:
 *  - If they touch at all — cross, share an endpoint, or lie on top of each other — the distance is
 *    zero, and only [segmentIntersections] can tell. A proper crossing puts the closest pair in the
 *    interior of *both* segments, so no endpoint distance can see it: two segments crossing in a
 *    plus sign are zero apart even though all four endpoint distances are half the length.
 *  - Once they are known to be disjoint, the closest pair always includes at least one endpoint (an
 *    interior-to-interior closest pair either crosses, or the segments are parallel and the gap is
 *    the same all along the overlap), so the four endpoint distances below are exact.
 *
 * This allocates a four-double scratch for the intersection test. That is deliberate and cheap here
 * because the eraser does not call this: it solves each touch set analytically instead, and its
 * crossing search needs the intersection parameters, not just a yes/no.
 */
internal fun segmentSegmentDistance(
    ax: Double, ay: Double, bx: Double, by: Double,
    cx: Double, cy: Double, dx: Double, dy: Double,
): Double {
    if (segmentIntersections(ax, ay, bx, by, cx, cy, dx, dy, DoubleArray(4)) > 0) return 0.0
    var best = pointSegmentDistance(ax, ay, cx, cy, dx, dy)
    val b = pointSegmentDistance(bx, by, cx, cy, dx, dy)
    if (b < best) best = b
    val c = pointSegmentDistance(cx, cy, ax, ay, bx, by)
    if (c < best) best = c
    val d = pointSegmentDistance(dx, dy, ax, ay, bx, by)
    if (d < best) best = d
    return best
}

/**
 * Every crossing of segment A (ax, ay)-(bx, by) with segment B (cx, cy)-(dx, dy), written into
 * [out] as consecutive (ta, tb) pairs of each segment's own 0..1 parameter, and returned as a
 * count: 0 = none, 1 = one crossing, 2 = collinear overlap (its entry and its exit).
 *
 * A crossing that lands exactly on an endpoint counts — the trims the eraser makes are supposed to
 * stop *at* the corner, not a hair past it.
 */
internal fun segmentIntersections(
    ax: Double, ay: Double, bx: Double, by: Double,
    cx: Double, cy: Double, dx: Double, dy: Double,
    out: DoubleArray,
): Int {
    val rax = bx - ax
    val ray = by - ay
    val ebx = dx - cx
    val eby = dy - cy
    val rr = rax * rax + ray * ray
    val ee = ebx * ebx + eby * eby
    val qpx = cx - ax
    val qpy = cy - ay

    if (rr <= 0.0) {
        // A is a dot: it only "crosses" B when it sits on it.
        if (pointSegmentDistance(ax, ay, cx, cy, dx, dy) > GEOM_EPS) return 0
        out[0] = 0.0
        out[1] = if (ee <= 0.0) 0.0 else clamp01(((ax - cx) * ebx + (ay - cy) * eby) / ee)
        return 1
    }
    if (ee <= 0.0) {
        if (pointSegmentDistance(cx, cy, ax, ay, bx, by) > GEOM_EPS) return 0
        out[0] = clamp01((qpx * rax + qpy * ray) / rr)
        out[1] = 0.0
        return 1
    }

    val denom = rax * eby - ray * ebx
    if (abs(denom) > GEOM_EPS) {
        val ta = (qpx * eby - qpy * ebx) / denom
        val tb = (qpx * ray - qpy * rax) / denom
        if (ta < 0.0 || ta > 1.0 || tb < 0.0 || tb > 1.0) return 0
        out[0] = ta
        out[1] = tb
        return 1
    }

    // Parallel. Only collinear overlap is a crossing.
    if (abs(qpx * ray - qpy * rax) > GEOM_EPS) return 0
    val t0 = (qpx * rax + qpy * ray) / rr
    val t1 = t0 + (ebx * rax + eby * ray) / rr
    val lo = max(0.0, min(t0, t1))
    val hi = min(1.0, max(t0, t1))
    if (lo > hi) return 0
    out[0] = lo
    out[1] = projectOntoB(ax + lo * rax, ay + lo * ray, cx, cy, ebx, eby, ee)
    out[2] = hi
    out[3] = projectOntoB(ax + hi * rax, ay + hi * ray, cx, cy, ebx, eby, ee)
    return 2
}

private fun projectOntoB(
    px: Double, py: Double, cx: Double, cy: Double, ebx: Double, eby: Double, ee: Double,
): Double = clamp01(((px - cx) * ebx + (py - cy) * eby) / ee)

private fun clamp01(v: Double): Double = if (v < 0.0) 0.0 else if (v > 1.0) 1.0 else v
