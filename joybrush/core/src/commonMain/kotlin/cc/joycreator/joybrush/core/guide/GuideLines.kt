package cc.joycreator.joybrush.core.guide

import cc.joycreator.joybrush.core.shape.Pt
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The segments an overlay draws for one guide. JB-2.12a, spec Decision 6.
 *
 * One call, one list, and nothing else in the app has to know how a grid is built: the Android
 * shell feeds the list straight to a line batch, the PC Brush Lab to a GL batch, and both get the
 * same picture. Segments are (x0, y0, x1, y1) in DOCUMENT px — the caller multiplies by its own
 * `screenPerDoc` and pan, because only the caller knows where the page is.
 *
 * Three rules hold for every guide:
 *
 * - **Clipped to the view.** Nothing comes back outside [viewDoc], so the overlay never draws over
 *   a panel or off the edge of the window. The one exception is the ellipse tracer's polyline: it is
 *   a fixed 128 pieces, clipped by the caller's scissor, because a tracer is something you drew and
 *   dragging it partly off the page must not change its shape.
 * - **Thinned.** A grid that is zoomed out to less than [MIN_SCREEN_GAP] between lines is drawn
 *   every 2nd, 4th, 8th… line until it is at least that far apart again. A 1 px grid at 5% zoom
 *   would otherwise be a grey block.
 * - **Capped** at [MAX_SEGMENTS] whatever happens, because this is a drawing loop and the caller's
 *   buffer is finite. The cap is reached by stopping, not by allocating and throwing away.
 *
 * The whole thing is pure maths: a bad number in (a NaN spacing, a zero zoom, a view that is upside
 * down) produces no segments, never an exception and never an infinite loop.
 */
object GuideLines {

    /** The most segments any one guide may produce, whatever the view, the zoom or the spacing. */
    const val MAX_SEGMENTS = 2000

    /** Lines closer together than this, in SCREEN px, are thinned away. */
    const val MIN_SCREEN_GAP = 8.0

    /** Rays drawn out of each vanishing point. */
    const val PERSPECTIVE_RAYS = 24

    /** Pieces the ellipse tracer's outline is cut into. */
    const val ELLIPSE_SEGMENTS = 128

    /** How far, in document px, a clip is allowed to reach before the view rect is what ends it. */
    private const val FAR = 1.0e9

    /** Slack when turning a rect extent into a line index, so a line exactly on the edge is kept. */
    private const val EDGE = 1.0e-9

    /**
     * Segments to draw for [guide] inside [viewDoc] — the visible area in document px, as
     * `floatArrayOf(left, top, right, bottom)`, exactly what [cc.joycreator.joybrush.core.view.ViewTransform]
     * can hand back. [screenPerDoc] is the current zoom, which only decides how far apart the lines
     * of a grid are on screen (and so whether they get thinned); the segments are always in
     * document px.
     */
    fun visible(guide: Guide, viewDoc: FloatArray, screenPerDoc: Float): List<FloatArray> {
        if (viewDoc.size < 4) return emptyList()
        val left = viewDoc[0].toDouble()
        val top = viewDoc[1].toDouble()
        val right = viewDoc[2].toDouble()
        val bottom = viewDoc[3].toDouble()
        if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite()) return emptyList()
        if (right < left || bottom < top) return emptyList()
        val k = screenPerDoc.toDouble()
        if (!k.isFinite() || k <= 0.0) return emptyList()

        val out = ArrayList<FloatArray>()
        when (guide) {
            is Guide.Grid -> grid(out, guide, left, top, right, bottom, k)
            is Guide.Isometric -> isometric(out, guide, left, top, right, bottom, k)
            is Guide.Perspective -> perspective(out, guide, left, top, right, bottom)
            is Guide.Ruler -> ruler(out, guide, left, top, right, bottom)
            is Guide.EllipseTracer -> ellipse(out, guide)
        }
        return out
    }

    // ── grids ─────────────────────────────────────────────────────────────────

    private fun grid(
        out: ArrayList<FloatArray>, g: Guide.Grid,
        left: Double, top: Double, right: Double, bottom: Double, k: Double,
    ) {
        val step = thinned(g.spacing, k)
        if (step <= 0.0) return
        if (!g.origin.x.isFinite() || !g.origin.y.isFinite()) return
        val a = if (g.angle.isFinite()) g.angle else 0.0
        val c = cos(a)
        val s = sin(a)
        if (out.size < MAX_SEGMENTS) family(out, g.origin, Pt(c, s), step, left, top, right, bottom)
        if (out.size < MAX_SEGMENTS) family(out, g.origin, Pt(-s, c), step, left, top, right, bottom)
    }

    private fun isometric(
        out: ArrayList<FloatArray>, g: Guide.Isometric,
        left: Double, top: Double, right: Double, bottom: Double, k: Double,
    ) {
        val step = thinned(g.spacing, k)
        if (step <= 0.0) return
        if (!g.origin.x.isFinite() || !g.origin.y.isFinite()) return
        val h = PI / 6.0
        val c = cos(h)
        val s = sin(h)
        if (out.size < MAX_SEGMENTS) family(out, g.origin, Pt(c, s), step, left, top, right, bottom)
        if (out.size < MAX_SEGMENTS) family(out, g.origin, Pt(0.0, 1.0), step, left, top, right, bottom)
        if (out.size < MAX_SEGMENTS) family(out, g.origin, Pt(-c, s), step, left, top, right, bottom)
    }

    /**
     * The spacing a grid is drawn with: [spacing] doubled until its lines are at least
     * [MIN_SCREEN_GAP] screen px apart. Returns 0 for a spacing that is not a usable length, which
     * every caller reads as "nothing to draw".
     */
    private fun thinned(spacing: Double, k: Double): Double {
        if (!spacing.isFinite() || spacing <= 0.0) return 0.0
        var s = spacing
        var n = 0
        while (s * k < MIN_SCREEN_GAP && n < 64) {
            s *= 2.0
            n++
        }
        return if (s.isFinite()) s else 0.0
    }

    /**
     * One family of parallel lines: the lines in direction (ux, uy), one every [step] document px,
     * the whole family anchored on [origin]. Only the ones that reach the view are built.
     *
     * A line of the family is `Q·n̂ = origin·n̂ + i·step` for n̂ perpendicular to (ux, uy), so the
     * first and last index the view can need come straight off the rect's own extent along n̂ — no
     * guessing how many lines to step over, and no walking a million of them.
     */
    private fun family(
        out: ArrayList<FloatArray>, origin: Pt, dir: Pt, step: Double,
        left: Double, top: Double, right: Double, bottom: Double,
    ) {
        if (!dir.x.isFinite() || !dir.y.isFinite()) return
        val nx = -dir.y
        val ny = dir.x
        val c0 = origin.x * nx + origin.y * ny
        // Extent of Q·n̂ over the rect: each term takes whichever end the normal points away from.
        val lo = (if (nx >= 0.0) left * nx else right * nx) + (if (ny >= 0.0) top * ny else bottom * ny)
        val hi = (if (nx >= 0.0) right * nx else left * nx) + (if (ny >= 0.0) bottom * ny else top * ny)
        val iLo = ceil((lo - c0) / step - EDGE)
        val iHi = floor((hi - c0) / step + EDGE)
        if (!iLo.isFinite() || !iHi.isFinite() || iHi < iLo) return
        var i = iLo
        while (i <= iHi) {
            val off = i * step
            val sx = origin.x + nx * off
            val sy = origin.y + ny * off
            val seg = clip(sx, sy, dir.x, dir.y, -FAR, FAR, left, top, right, bottom)
            if (seg != null) {
                out.add(seg)
                if (out.size >= MAX_SEGMENTS) return
            }
            i += 1.0
        }
    }

    // ── perspective ───────────────────────────────────────────────────────────

    private fun perspective(
        out: ArrayList<FloatArray>, g: Guide.Perspective,
        left: Double, top: Double, right: Double, bottom: Double,
    ) {
        for (vp in g.vanishingPoints) {
            if (!vp.x.isFinite() || !vp.y.isFinite()) continue
            for (i in 0 until PERSPECTIVE_RAYS) {
                val a = i * (2 * PI / PERSPECTIVE_RAYS)
                val seg = clip(vp.x, vp.y, cos(a), sin(a), 0.0, FAR, left, top, right, bottom)
                if (seg != null) {
                    out.add(seg)
                    if (out.size >= MAX_SEGMENTS) return
                }
            }
        }
        // The horizon: the line through the first two vanishing points, when there are two.
        if (g.vanishingPoints.size >= 2) {
            val a = g.vanishingPoints[0]
            val b = g.vanishingPoints[1]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val l = hypot(dx, dy)
            if (l > 0.0 && l.isFinite()) {
                val seg = clip(a.x, a.y, dx / l, dy / l, -FAR, FAR, left, top, right, bottom)
                if (seg != null) {
                    out.add(seg)
                    if (out.size >= MAX_SEGMENTS) return
                }
            }
        }
    }

    // ── tracers ───────────────────────────────────────────────────────────────

    private fun ruler(
        out: ArrayList<FloatArray>, g: Guide.Ruler,
        left: Double, top: Double, right: Double, bottom: Double,
    ) {
        if (!g.a.x.isFinite() || !g.a.y.isFinite() || !g.b.x.isFinite() || !g.b.y.isFinite()) return
        val dx = g.b.x - g.a.x
        val dy = g.b.y - g.a.y
        val l = hypot(dx, dy)
        if (!(l > 0.0) || !l.isFinite()) return
        val seg = clip(g.a.x, g.a.y, dx / l, dy / l, -FAR, FAR, left, top, right, bottom) ?: return
        out.add(seg)
    }

    private fun ellipse(out: ArrayList<FloatArray>, g: Guide.EllipseTracer) {
        if (!g.center.x.isFinite() || !g.center.y.isFinite()) return
        if (!g.rx.isFinite() || !g.ry.isFinite() || g.rx <= 0.0 || g.ry <= 0.0) return
        val rot = if (g.rotation.isFinite()) g.rotation else 0.0
        val c = cos(rot)
        val s = sin(rot)
        var px = g.center.x + g.rx * c
        var py = g.center.y + g.rx * s
        for (i in 1..ELLIPSE_SEGMENTS) {
            val t = i * (2 * PI / ELLIPSE_SEGMENTS)
            val ux = g.rx * cos(t)
            val uy = g.ry * sin(t)
            val x = g.center.x + c * ux - s * uy
            val y = g.center.y + s * ux + c * uy
            out.add(floatArrayOf(px.toFloat(), py.toFloat(), x.toFloat(), y.toFloat()))
            if (out.size >= MAX_SEGMENTS) return
            px = x
            py = y
        }
    }

    // ── clipping ──────────────────────────────────────────────────────────────

    /**
     * Liang–Barsky: the part of the straight run `P + u·D`, `u` in [uMin, uMax], that lies inside
     * the view rect, as a segment — or null when there is none. An infinite line is a run with both
     * limits far away, a ray is a run that starts at `P`.
     */
    private fun clip(
        px: Double, py: Double, dx: Double, dy: Double,
        uMin: Double, uMax: Double,
        left: Double, top: Double, right: Double, bottom: Double,
    ): FloatArray? {
        var a = uMin
        var b = uMax
        if (abs(dx) < 1.0e-12) {
            if (px < left || px > right) return null
        } else {
            val lo = minOf((left - px) / dx, (right - px) / dx)
            val hi = maxOf((left - px) / dx, (right - px) / dx)
            if (lo > a) a = lo
            if (hi < b) b = hi
            if (a > b) return null
        }
        if (abs(dy) < 1.0e-12) {
            if (py < top || py > bottom) return null
        } else {
            val lo = minOf((top - py) / dy, (bottom - py) / dy)
            val hi = maxOf((top - py) / dy, (bottom - py) / dy)
            if (lo > a) a = lo
            if (hi < b) b = hi
            if (a > b) return null
        }
        if (b - a <= 0.0) return null
        return floatArrayOf(
            (px + dx * a).toFloat(), (py + dy * a).toFloat(),
            (px + dx * b).toFloat(), (py + dy * b).toFloat(),
        )
    }
}
