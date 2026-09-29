package cc.joycreator.joybrush.core.guide

import cc.joycreator.joybrush.core.input.Angles
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.shape.Pt
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pulls one stroke onto the guides that are switched on. JB-2.12a, spec Decisions 1–7.
 *
 * ONE snapper per stroke: hand it every raw sample through [map] in order and draw what comes out.
 * It remembers only three things — where the stroke started, which tracer (if any) took it, and
 * which direction (if any) it locked to.
 *
 * Two kinds of guide, two different rules:
 *
 * - **Tracers** ([Guide.Ruler], [Guide.EllipseTracer]) are magnetic EDGES. A stroke that STARTS
 *   within [REACH_SCREEN_PX] of one is pulled onto it for its whole length; a stroke that starts
 *   further away is not touched by it at all.
 * - **Direction guides** ([Guide.Grid], [Guide.Isometric], [Guide.Perspective]) are a set of LINES
 *   through the start point. Samples pass through untouched until the pen is [LOCK_SCREEN_PX] from
 *   the start, and then the candidate direction nearest the pen's own direction is locked for the
 *   rest of the stroke. If the nearest candidate is further than [MAX_OFF_AXIS] the person is not
 *   drawing along a guide, and the stroke stays free from end to end.
 *
 * Directions are lines, not rays: a stroke may be drawn either way along one. The samples before
 * the lock are NOT rewritten — they are all within [LOCK_SCREEN_PX] of the start, so the brush
 * already covers them and rewriting them would only make the stroke twitch.
 *
 * [map] promises what hold-to-shape promises: x and y and nothing else. Pressure, tilt, azimuth,
 * barrel, time, tool and the predicted flag come through untouched, and a sample with a
 * non-finite position comes back as the very same object.
 *
 * Every tolerance below is in SCREEN px, divided by [screenPerDoc] to reach the document, so a
 * guide feels the same at 5% and at 6400%.
 */
class GuideSnapper(val guides: List<Guide>, val screenPerDoc: Float) {

    private val k = screenPerDoc.toDouble()
    private val canSnap = k.isFinite() && k > 0.0

    private var hasStart = false
    private var sx = 0.0
    private var sy = 0.0

    /** The tracer that took this stroke, decided once, at the stroke's first sample. */
    private var tracer: Guide? = null

    /** The locked direction as a unit vector, or (0, 0) while nothing is locked. */
    private var dirX = 0.0
    private var dirY = 0.0
    private var hasDir = false

    /** Set when no candidate was close enough: the stroke is free for good and is not re-tried. */
    private var gaveUp = false

    private var isLocked = false

    /** True once the stroke is locked to a guide — the UI highlights the guide then. */
    val locked: Boolean get() = isLocked

    /**
     * The sample to draw instead of [s]: the position is moved onto the guide, every other field is
     * identical. Returns [s] itself when the sample is not snapped, so an unsnapped stroke is
     * bit-for-bit the one that came in.
     */
    fun map(s: PenSample): PenSample {
        if (!canSnap) return s
        if (!s.x.isFinite() || !s.y.isFinite()) return s
        val x = s.x.toDouble()
        val y = s.y.toDouble()

        if (!hasStart) {
            hasStart = true
            sx = x
            sy = y
            val t = nearestTracer(x, y)
            if (t != null) {
                tracer = t
                isLocked = true
            }
        }

        val t0 = tracer
        if (t0 != null) return ontoTracer(s, t0, x, y)
        if (hasDir) return ontoLine(s, x, y)
        if (gaveUp) return s

        val mx = x - sx
        val my = y - sy
        if (hypot(mx, my) * k < LOCK_SCREEN_PX) return s

        val best = nearestDirection(mx, my)
        if (best == null) {
            gaveUp = true
            return s
        }
        dirX = best.x
        dirY = best.y
        hasDir = true
        isLocked = true
        return ontoLine(s, x, y)
    }

    // ── direction guides ───────────────────────────────────────────────────────

    /** The candidate direction nearest the pen's own direction, or null when none is close enough. */
    private fun nearestDirection(mx: Double, my: Double): Pt? {
        var best: Pt? = null
        var bestDiff = Double.MAX_VALUE
        val at = atan2(my, mx)
        for (c in candidates(mx + sx, my + sy)) {
            val d = lineAngleDiff(at, atan2(c.y, c.x))
            if (d < bestDiff) {
                bestDiff = d
                best = c
            }
        }
        val b = best ?: return null
        if (!bestDiff.isFinite() || bestDiff > MAX_OFF_AXIS) return null
        return b
    }

    /**
     * Every direction any direction guide offers through the point (x, y) — the stroke's start.
     * Tracers offer none: they are edges, not directions.
     */
    private fun candidates(x: Double, y: Double): List<Pt> {
        val out = ArrayList<Pt>()
        for (g in guides) {
            when (g) {
                is Guide.Grid -> {
                    val c = cos(g.angle)
                    val s = sin(g.angle)
                    out.add(Pt(c, s))
                    out.add(Pt(-s, c))
                }
                is Guide.Isometric -> {
                    val h = PI / 6.0
                    out.add(Pt(cos(h), sin(h)))
                    out.add(Pt(0.0, 1.0))
                    out.add(Pt(-cos(h), sin(h)))
                }
                is Guide.Perspective -> {
                    for (vp in g.vanishingPoints) {
                        val dx = vp.x - x
                        val dy = vp.y - y
                        val l = hypot(dx, dy)
                        if (l > 1e-9 && l.isFinite()) out.add(Pt(dx / l, dy / l))
                    }
                    if (g.vanishingPoints.size <= 2) out.add(Pt(0.0, 1.0))
                    if (g.vanishingPoints.size <= 1) out.add(Pt(1.0, 0.0))
                }
                is Guide.Ruler -> Unit
                is Guide.EllipseTracer -> Unit
            }
        }
        return out
    }

    /** The angle between two DIRECTIONS, folded into 0..90°: a guide is a line, not a ray. */
    private fun lineAngleDiff(a: Double, b: Double): Double {
        var d = abs(Angles.wrap(a - b))
        if (d > PI / 2.0) d = PI - d
        return d
    }

    /** Orthogonal projection of (x, y) onto the locked line through the start point. */
    private fun ontoLine(s: PenSample, x: Double, y: Double): PenSample {
        val t = (x - sx) * dirX + (y - sy) * dirY
        val px = sx + dirX * t
        val py = sy + dirY * t
        if (px == x && py == y) return s
        return s.copy(x = px.toFloat(), y = py.toFloat())
    }

    // ── tracers ───────────────────────────────────────────────────────────────

    /**
     * The tracer in reach at the stroke's start, nearest first (Decision 4). Reach is
     * [REACH_SCREEN_PX] of SCREEN distance from the start to the tracer itself — to a ruler's
     * infinite line, to the ellipse's curve.
     */
    private fun nearestTracer(x: Double, y: Double): Guide? {
        var best: Guide? = null
        var bestDist = Double.MAX_VALUE
        for (g in guides) {
            val d = when (g) {
                is Guide.Ruler -> rulerDistance(g, x, y)
                is Guide.EllipseTracer -> ellipseDistance(g, x, y)
                is Guide.Grid -> Double.MAX_VALUE
                is Guide.Isometric -> Double.MAX_VALUE
                is Guide.Perspective -> Double.MAX_VALUE
            }
            if (d < bestDist) {
                bestDist = d
                best = g
            }
        }
        if (best == null || !bestDist.isFinite()) return null
        if (bestDist > REACH_SCREEN_PX / k) return null
        return best
    }

    /** Orthogonal distance from (x, y) to the INFINITE line through a and b (0 for a==b). */
    private fun rulerDistance(g: Guide.Ruler, x: Double, y: Double): Double {
        if (!g.a.x.isFinite() || !g.a.y.isFinite() || !g.b.x.isFinite() || !g.b.y.isFinite()) {
            return Double.MAX_VALUE
        }
        val dx = g.b.x - g.a.x
        val dy = g.b.y - g.a.y
        val len2 = dx * dx + dy * dy
        if (!(len2 > 0.0) || !len2.isFinite()) return hypot(x - g.a.x, y - g.a.y)
        return abs((x - g.a.x) * dy - (y - g.a.y) * dx) / sqrt(len2)
    }

    /** How far (x, y) is from the ellipse's curve, in document px. */
    private fun ellipseDistance(g: Guide.EllipseTracer, x: Double, y: Double): Double {
        if (!g.rx.isFinite() || !g.ry.isFinite() || g.rx <= 0.0 || g.ry <= 0.0) return Double.MAX_VALUE
        val p = ellipsePoint(g, x, y)
        return hypot(p.x - x, p.y - y)
    }

    private fun ontoTracer(s: PenSample, g: Guide, x: Double, y: Double): PenSample {
        val p = when (g) {
            is Guide.Ruler -> rulerPoint(g, x, y)
            else -> ellipsePoint(g as Guide.EllipseTracer, x, y)
        }
        if (p.x == x && p.y == y) return s
        return s.copy(x = p.x.toFloat(), y = p.y.toFloat())
    }

    /** Orthogonal projection onto the infinite line through a and b; a==b is left where it is. */
    private fun rulerPoint(g: Guide.Ruler, x: Double, y: Double): Pt {
        val dx = g.b.x - g.a.x
        val dy = g.b.y - g.a.y
        val len2 = dx * dx + dy * dy
        if (!(len2 > 0.0) || !len2.isFinite()) return Pt(x, y)
        val t = ((x - g.a.x) * dx + (y - g.a.y) * dy) / len2
        return Pt(g.a.x + dx * t, g.a.y + dy * t)
    }

    /**
     * The point of the ellipse's curve nearest to (x, y), by Newton's method on the parametric
     * angle [NEWTON_STEPS] times, started from the angle the point makes in the ellipse's own
     * frame. The squared distance along t is a concave function of t (its second derivative is
     * always negative for positive radii), so Newton walks straight down to the local minimum
     * instead of hunting — six steps is far more than drawing a line by hand needs.
     */
    private fun ellipsePoint(g: Guide.EllipseTracer, x: Double, y: Double): Pt {
        if (!g.center.x.isFinite() || !g.center.y.isFinite()) return Pt(x, y)
        if (!g.rx.isFinite() || !g.ry.isFinite() || g.rx <= 0.0 || g.ry <= 0.0) return Pt(x, y)
        val c = cos(g.rotation)
        val s = sin(g.rotation)
        val dx = x - g.center.x
        val dy = y - g.center.y
        val u = c * dx + s * dy
        val v = -s * dx + c * dy
        var t = atan2(v / g.ry, u / g.rx)
        repeat(NEWTON_STEPS) {
            val ct = cos(t)
            val st = sin(t)
            val f1 = 2.0 * (g.rx * st * (u - g.rx * ct) - g.ry * ct * (v - g.ry * st))
            // D(t)   = (rx·cos t - u)² + (ry·sin t - v)²
            // D'(t)  = f1
            // D''(t) = 2[ (ry² - rx²)(cos²t - sin²t) + rx·u·cos t + ry·v·sin t ]
            //
            // The (ry² - rx²)(cos²t - sin²t) term is the whole difficulty. Written out longhand it
            // is what makes D'' positive at the minimum — it collapses to 2(ry² + rx²·sin²t) when
            // u, v sit ON the curve — so dropping it, or replacing it with a constant, hands Newton
            // a curvature that is negative where it must be positive and the iteration walks away
            // from the answer: t runs 0.5 -> 0.12 -> -1.6 -> -3.8 and settles 230 px from a pen that
            // is 5 px off the curve. The symptom is not a snapped stroke in the wrong place; it is a
            // stroke that is never captured at all, because the distance it measured was nonsense.
            val f2 = 2.0 * (
                (g.ry * g.ry - g.rx * g.rx) * (ct * ct - st * st) +
                    g.rx * u * ct + g.ry * v * st
                )
            if (f2 != 0.0) t = Angles.wrap(t - f1 / f2)
        }
        val ct = cos(t)
        val st = sin(t)
        val ux = g.rx * ct
        val uy = g.ry * st
        return Pt(g.center.x + c * ux - s * uy, g.center.y + s * ux + c * uy)
    }

    companion object {
        /** How close the pen must get to a tracer, in SCREEN px, before the stroke is pulled onto it. */
        const val REACH_SCREEN_PX = 24.0

        /** How far from its start the pen must get, in SCREEN px, before a direction can lock. */
        const val LOCK_SCREEN_PX = 12.0

        /** The most a pen may be off a candidate direction, in radians (20°), before nothing locks. */
        const val MAX_OFF_AXIS = 20.0 * PI / 180.0

        /** Newton's steps on the ellipse's parametric angle. */
        const val NEWTON_STEPS = 6
    }
}
