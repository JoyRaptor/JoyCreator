package cc.joycreator.joybrush.core.media

import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Fast strokes without facets, and without smoothing (port of lab/media/js/spline.js).
 *
 * A pen reports ~100–240 points a second; a quick flick covers millimetres between reports, and straight joins
 * show the corners (the owner saw ~16 facets in one quick curve, 2026-10-06). This passes a centripetal
 * Catmull–Rom curve THROUGH every real pen point: nothing is averaged away, sharp turns stay sharp, only the gaps
 * are filled. Cost: the curve between two points needs the next point, so the stroke trails the pen by one report.
 */
class MediaSpline(private val stepPx: Double = 0.75, private val sink: (MediaSample) -> Unit) {
    private val pts = ArrayList<MediaSample>()

    fun add(s: MediaSample) {
        val lastPt = pts.lastOrNull()
        if (lastPt != null && hypot(s.x - lastPt.x, s.y - lastPt.y) < 1e-3) { pts[pts.size - 1] = s; return }
        pts.add(s)
        if (pts.size == 1) { sink(s); return }
        if (pts.size >= 3) {
            val n = pts.size
            segment(pts[max(0, n - 4)], pts[n - 3], pts[n - 2], pts[n - 1])
        }
        if (pts.size > 4) pts.removeAt(0)
    }

    /** Lift: draw the last stretch (its far end has no next point, so the curve just ends there). */
    fun finish() {
        val n = pts.size
        if (n >= 2) segment(pts[max(0, n - 3)], pts[n - 2], pts[n - 1], pts[n - 1])
        pts.clear()
    }

    private fun segment(p0: MediaSample, p1: MediaSample, p2: MediaSample, p3: MediaSample) {
        // Identity, not equality: a repeated endpoint means "no neighbour" (as in the lab).
        val noPrev = p0 === p1
        val noNext = p2 === p3
        fun d(a: MediaSample, b: MediaSample) = max(1e-4, sqrt(hypot(b.x - a.x, b.y - a.y)))   // centripetal
        val t0 = 0.0
        val t1 = t0 + if (noPrev) d(p1, p2) else d(p0, p1)
        val t2 = t1 + d(p1, p2)
        val t3 = t2 + if (noNext) d(p1, p2) else d(p2, p3)
        val len = hypot(p2.x - p1.x, p2.y - p1.y)
        val steps = max(1, ceil(len / stepPx).toInt())
        // No neighbour: extend straight so the end is not bent.
        val q0x = if (noPrev) 2 * p1.x - p2.x else p0.x
        val q0y = if (noPrev) 2 * p1.y - p2.y else p0.y
        val q3x = if (noNext) 2 * p2.x - p1.x else p3.x
        val q3y = if (noNext) 2 * p2.y - p1.y else p3.y
        for (i in 1..steps) {
            val u = i.toDouble() / steps
            val t = t1 + (t2 - t1) * u
            fun lerp(ax: Double, ay: Double, bx: Double, by: Double, ta: Double, tb: Double): DoubleArray {
                val w = (t - ta) / (tb - ta)
                return doubleArrayOf(ax + (bx - ax) * w, ay + (by - ay) * w)
            }
            val a1 = lerp(q0x, q0y, p1.x, p1.y, t0, t1)
            val a2 = lerp(p1.x, p1.y, p2.x, p2.y, t1, t2)
            val a3 = lerp(p2.x, p2.y, q3x, q3y, t2, t3)
            val b1 = lerp(a1[0], a1[1], a2[0], a2[1], t0, t2)
            val b2 = lerp(a2[0], a2[1], a3[0], a3[1], t1, t3)
            val c = lerp(b1[0], b1[1], b2[0], b2[1], t1, t2)
            sink(MediaSample(
                c[0], c[1], p1.p + (p2.p - p1.p) * u, p1.tilt + (p2.tilt - p1.tilt) * u,
                p1.az + angleDelta(p1.az, p2.az) * u, p1.t + (p2.t - p1.t) * u,
            ))
        }
    }
}
