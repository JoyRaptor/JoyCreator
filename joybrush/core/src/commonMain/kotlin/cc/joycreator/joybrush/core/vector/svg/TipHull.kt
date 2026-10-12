package cc.joycreator.joybrush.core.vector.svg

import cc.joycreator.joybrush.core.paint.Dab
import cc.joycreator.joybrush.core.paint.TipShape
import cc.joycreator.joybrush.core.paint.tipShape
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One dab's tip as a SHAPE in document px: the region `TipMath`'s distance field calls inside
 * (`d <= 1`), with no antialiasing, no hardness and no fade. That is the zoom-free truth an outline
 * should trace — the 1x raster's 50 % contour sits a quarter of a SCREEN pixel inside it, and that
 * quarter pixel belongs to the screen, not to the line.
 *
 * The geometry is `TipMath.Geometry`'s, copied because that class is private (and must stay the one
 * the shader is checked against): half-axes from radius and aspect, the `minPx` floor, the `anchor`
 * shift along the tip's own x axis, the superellipse exponent clamped to 0.5..64, and the in-dab
 * `taper`, which scales the x half-axis by `1 - taper * along(qy)`. If `TipMath.Geometry` changes,
 * this file changes in the same commit; `StrokeOutlineTest`'s IoU cases compare against the real
 * `InkRaster`, so a drift shows up there.
 *
 * Two ways to answer "which point of the tip lies furthest along u":
 *  - **exact** (`taper == 0` and `corner >= 1`, the convex case and nearly every shipped brush):
 *    the superellipse's support point in closed form (Hölder's equality case). Sweeping the
 *    direction then traces the boundary, and it puts points where the curvature is.
 *  - **sampled** (a taper, or `corner < 1`, which is a four-pointed star and not convex): the
 *    boundary is sampled into a polygon in the tip's own winding order, so a cap can follow its real
 *    (concave) edge rather than its hull.
 */
internal class TipHull(dab: Dab, shape: TipShape) {
    val cx: Double = dab.x.toDouble()
    val cy: Double = dab.y.toDouble()
    private val c = cos(dab.angle.toDouble())
    private val s = sin(dab.angle.toDouble())
    private val shift: Double
    private val hx: Double
    private val hy: Double
    private val n: Double
    private val taper: Double
    private val exact: Boolean

    /** The largest half-axis plus the anchor shift: every boundary point is within this of the centre. */
    val reach: Double

    init {
        val tip = dab.tipShape(shape)
        val r = dab.radius.toDouble()
        val a = (if (tip.aspect.isFinite()) tip.aspect else 0f).coerceIn(-1f, 1f).toDouble()
        val rawX = r * (if (a > 0.0) 1.0 - a else 1.0)
        val rawY = r * (if (a < 0.0) 1.0 + a else 1.0)
        val halfMin = max(if (tip.minPx.isFinite()) tip.minPx.toDouble() else 1.0, 0.5) * 0.5
        hx = max(rawX, halfMin)
        hy = max(rawY, halfMin)
        shift = r * (if (tip.anchor.isFinite()) tip.anchor.coerceIn(0f, 1f).toDouble() else 0.0)
        n = (if (tip.corner.isFinite()) tip.corner else 2f).coerceIn(0.5f, 64f).toDouble()
        taper = (if (tip.taper.isFinite()) tip.taper else 0f).coerceIn(0f, 1f).toDouble()
        exact = taper == 0.0 && n >= 1.0
        reach = max(hx, hy) + shift
    }

    /** The sampled boundary, world coordinates interleaved (x0, y0, x1, y1, …), in the tip's winding order. */
    private val ring: DoubleArray by lazy { sampleRing() }
    private val ringSize: Int get() = ring.size / 2

    /** The support point in world direction ([ux], [uy]) (unit), written to [out] (x, y). */
    fun support(ux: Double, uy: Double, out: DoubleArray) {
        if (exact) {
            exactSupport(ux, uy, out)
        } else {
            val i = argmax(ux, uy)
            out[0] = ring[2 * i]
            out[1] = ring[2 * i + 1]
        }
    }

    /** The support function `max p·u` over the tip, in world units. */
    fun h(ux: Double, uy: Double): Double {
        if (exact) {
            val p = DoubleArray(2)
            exactSupport(ux, uy, p)
            return p[0] * ux + p[1] * uy
        }
        var best = Double.NEGATIVE_INFINITY
        val r = ring
        var i = 0
        while (i < r.size) {
            val v = r[i] * ux + r[i + 1] * uy
            if (v > best) best = v
            i += 2
        }
        return best
    }

    /**
     * The boundary between the support point at direction angle [from] and the one at
     * `from + sweep`, EXCLUDING both ends, in the order met when the direction turns by [sweep]
     * (signed radians, |sweep| up to 2π). Turning the direction by a full −2π walks the whole
     * outline once.
     */
    fun arc(from: Double, sweep: Double, sink: (Double, Double) -> Unit) {
        if (sweep == 0.0) return
        if (exact) {
            val steps = ceil(abs(sweep) / angleStep()).toInt().coerceAtLeast(1)
            val p = DoubleArray(2)
            for (k in 1 until steps) {
                val phi = from + sweep * k / steps
                exactSupport(cos(phi), sin(phi), p)
                sink(p[0], p[1])
            }
            return
        }
        val k = ringSize
        val i0 = argmax(cos(from), sin(from))
        val to = from + sweep
        val i1 = argmax(cos(to), sin(to))
        val step = if (sweep > 0.0) 1 else -1
        val full = abs(sweep) >= 2.0 * PI - 1e-9
        var walk = ((i1 - i0) * step).mod(k)
        if (walk == 0 && full) walk = k
        // Support points of a polygon are hull vertices, met in winding order as the direction turns.
        // A small turn that would walk most of the ring is a sampling tie, not a path: take the chord.
        if (abs(sweep) < PI / 2 && walk > k / 2) return
        var i = i0
        for (j in 1 until walk) {
            i = (i + step).mod(k)
            sink(ring[2 * i], ring[2 * i + 1])
        }
    }

    // ── geometry ───────────────────────────────────────────────────────────────────────────

    /** Local tip coordinates (as `TipMath.Geometry.d` reads them) to world. */
    private fun toWorld(qx: Double, qy: Double, out: DoubleArray) {
        val lx = qx - shift
        out[0] = cx + c * lx - s * qy
        out[1] = cy + s * lx + c * qy
    }

    /**
     * Support of `|X|^n + |Y|^n <= 1` scaled by (hx, hy), for n >= 1. With a = hx·vx, b = hy·vy the
     * maximiser is X = sign(a)·|a|^(1/(n-1)) / N (Y likewise), N = (|a|^p + |b|^p)^(1/n), p = n/(n-1).
     * Normalised by max(|a|, |b|) first so a corner of 1.0001 does not underflow to 0/0.
     */
    private fun exactSupport(ux: Double, uy: Double, out: DoubleArray) {
        val vx = c * ux + s * uy
        val vy = -s * ux + c * uy
        val a = hx * vx
        val b = hy * vy
        val m = max(abs(a), abs(b))
        if (m == 0.0) { toWorld(0.0, 0.0, out); return }
        val aa = abs(a) / m
        val bb = abs(b) / m
        val x: Double
        val y: Double
        if (n - 1.0 < 1e-6) {
            // The diamond: a vertex. Ties go to x, and the boundary between two vertices is a straight edge either way.
            if (aa >= bb) { x = 1.0; y = 0.0 } else { x = 0.0; y = 1.0 }
        } else {
            val e = 1.0 / (n - 1.0)
            val xa = aa.pow(e)
            val yb = bb.pow(e)
            val norm = (xa * aa + yb * bb).pow(1.0 / n) // |a|^p = |a|^(1/(n-1)) * |a|
            x = xa / norm
            y = yb / norm
        }
        toWorld(hx * x * sign(a), hy * y * sign(b), out)
    }

    private fun sign(v: Double): Double = if (v < 0.0) -1.0 else 1.0

    /** Direction step for sweeping an exact tip: a chord sagitta of at most ~[SAGITTA_PX] on its roundest part. */
    private fun angleStep(): Double = min(PI / 16, sqrt(8.0 * SAGITTA_PX / max(hx, hy)))

    private fun argmax(ux: Double, uy: Double): Int {
        val r = ring
        var best = Double.NEGATIVE_INFINITY
        var at = 0
        var i = 0
        while (i < r.size) {
            val v = r[i] * ux + r[i + 1] * uy
            if (v > best) { best = v; at = i / 2 }
            i += 2
        }
        return at
    }

    /**
     * The boundary at K points, parameter t → (sgn cos t |cos t|^(2/n), sgn sin t |sin t|^(2/n)) on
     * the unit superellipse, which crowds points into the corners where a superellipse bends. The
     * taper is then an x-scale by row, exactly as `TipMath.Geometry.d` applies it.
     */
    private fun sampleRing(): DoubleArray {
        val big = max(hx, hy)
        val want = ceil(PI * sqrt(big / (2.0 * SAGITTA_PX))).toInt()
        val k = ((want + 7) / 8 * 8).coerceIn(32, 1024)
        val out = DoubleArray(2 * k)
        val p = DoubleArray(2)
        val e = 2.0 / n
        for (i in 0 until k) {
            val t = 2.0 * PI * i / k
            val ct = cos(t)
            val st = sin(t)
            val ux = sign(ct) * abs(ct).pow(e)
            val uy = sign(st) * abs(st).pow(e)
            val qy = hy * uy
            val along = (qy / hy * 0.5 + 0.5).coerceIn(0.0, 1.0)
            val w = max(1.0 - taper * along, 1e-3)
            toWorld(hx * w * ux, qy, p)
            out[2 * i] = p[0]
            out[2 * i + 1] = p[1]
        }
        return out
    }

    companion object {
        /** How far a sampled tip edge may sit inside the true one, doc px. Far below the 0.1 px simplification. */
        const val SAGITTA_PX = 0.01
    }
}
