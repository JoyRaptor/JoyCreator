package cc.joycreator.joybrush.core.select

import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.shape.Pt
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.stroke.SourcePlacement
import cc.joycreator.joybrush.core.stroke.CapturedStrokeRules
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * JB-5.20e core item 2 — a line carried through the Select box: move, scale, turn, flip, tilt, free (any [Homography]).
 *
 * The owner's words are the contract: the line moves "while keeping all the pressure, velocity, and tilt". So:
 * - `x, y` go through the map. `timeMs`, `pressure` and `tilt` (how far the pen leans) are kept exactly.
 * - `azimuth` (which way the pen leans, document space, 0 = +x) goes through the map's LOCAL linear part at that sample,
 *   so a turn turns it and a flip mirrors it: a pencil shaded from the left and flipped is shaded from the right.
 * - `barrel` turns with the map's rotation, and is mirrored (negated, then turned) under a flip. NaN stays NaN.
 * - `screenPerDoc` is divided by the map's scale `s = √|det J|` (taken at the line's centroid). Smoothing is screen-
 *   relative (`StrokeSmoother(smoothing, screenPerDoc)`, InkReplay), so without this a line scaled ×2 would replay with
 *   half its smoothing. The tuft engine judges speed on screen through `screenPerDoc` too, so this keeps it right.
 *   Known limit (vector specialist, 2026-10-11): `BrushDabber`'s speed input is in DOC px/s, so a speed-curve stamp brush
 *   hears a scaled line as faster; no shipped stamp preset uses speed, and the fix belongs in the dabber.
 * - `widthScale` is multiplied by `s` when [Weight.SCALE] (Concepts' Scale), kept when [Weight.KEEP] (Concepts'
 *   Stretch). Tapers and dashes are in brush diameters × widthScale, so they keep their proportions only with SCALE;
 *   with KEEP they keep their length in doc px.
 *   Dab SPACING is the brush's own (widthScale is applied after placement), so a line scaled ×2 with SCALE replays
 *   with twice as many dabs: the same line for an opaque ink, a denser one for a translucent stamp that builds up.
 *   Spacing by size × widthScale would be a replay change, flagged to the lead 2026-10-11 (LineTransformTest pins today).
 * - Everything else (id, seed, brush, colour, smoothing, and any field added later, such as `seq`) is kept: the result is
 *   a `copy` of the record. JB-5.20 D1: editing a line keeps its place in time.
 */
object LineTransform {

    enum class Weight { SCALE, KEEP }

    /**
     * [r] carried through [h], or null when any sample would land nowhere (a corner dragged past the vanishing line):
     * the caller refuses that move in words rather than drawing half a line.
     */
    fun apply(r: StrokeRecord, h: Homography, weight: Weight): StrokeRecord? {
        if (r.capturedPhysicalSource != null) {
            val m = h.m.copyOf()
            if (m.any { !it.isFinite() } || m[8] == 0.0 || m[0] != m[8] || m[4] != m[8] ||
                m[1] != 0.0 || m[3] != 0.0 || m[6] != 0.0 || m[7] != 0.0) return null
            val dx = m[2] / m[8]; val dy = m[5] / m[8]
            if (!dx.isFinite() || !dy.isFinite() || dx < Int.MIN_VALUE.toDouble() || dx > Int.MAX_VALUE.toDouble() ||
                dy < Int.MIN_VALUE.toDouble() || dy > Int.MAX_VALUE.toDouble() ||
                dx != dx.toInt().toDouble() || dy != dy.toInt().toDouble()) return null
            return translateCaptured(r, dx.toInt(), dy.toInt())
        }
        if (r.samples.isEmpty()) return r
        val out = ArrayList<PenSample>(r.samples.size)
        for (s in r.samples) {
            val q = h.apply(Pt(s.x.toDouble(), s.y.toDouble()))
            if (!q.x.isFinite() || !q.y.isFinite()) return null
            val j = jacobian(h, s.x.toDouble(), s.y.toDouble(), q)
            out.add(s.copy(x = q.x.toFloat(), y = q.y.toFloat(), azimuth = lean(s.azimuth, j), barrel = twist(s.barrel, j)))
        }
        var cx = 0.0; var cy = 0.0
        for (s in r.samples) { cx += s.x; cy += s.y }
        cx /= r.samples.size; cy /= r.samples.size
        val c = h.apply(Pt(cx, cy))
        val scale = if (c.x.isFinite() && c.y.isFinite()) {
            val j = jacobian(h, cx, cy, c)
            sqrt(abs(j[0] * j[3] - j[1] * j[2]))
        } else 1.0
        val s = if (scale.isFinite() && scale > 0.0) scale else 1.0
        return r.copy(
            samples = out,
            screenPerDoc = (r.screenPerDoc / s).toFloat(),
            widthScale = if (weight == Weight.SCALE) (r.widthScale * s).toFloat() else r.widthScale,
        )
    }

    /** Exact appearance translation: raw positions, all sensor bits and brush recipe stay untouched. */
    fun translateCaptured(r: StrokeRecord, dx: Int, dy: Int): StrokeRecord? {
        val source = r.capturedPhysicalSource ?: return null
        if (CapturedStrokeRules.problems(r).isNotEmpty()) return null
        val placement = try { source.placement.then(SourcePlacement(dx, dy)) }
            catch (_: IllegalArgumentException) { return null }
        return CapturedStrokeRules.snapshot(r.copy(capturedPhysicalSource = source.copy(placement = placement)))
    }

    /**
     * The map's local linear part at (x, y) → [q], row-major [dX/dx, dX/dy, dY/dx, dY/dy]. For X = (m0 x + m1 y + m2)/w
     * and w = m6 x + m7 y + m8: dX/dx = (m0 − X m6)/w, and the same pattern for the other three.
     */
    internal fun jacobian(h: Homography, x: Double, y: Double, q: Pt): DoubleArray {
        val m = h.m
        val w = m[6] * x + m[7] * y + m[8]
        return doubleArrayOf((m[0] - q.x * m[6]) / w, (m[1] - q.x * m[7]) / w, (m[3] - q.y * m[6]) / w, (m[4] - q.y * m[7]) / w)
    }

    /** A lean direction through [j]; NaN stays NaN, and a direction the map crushes to nothing is kept as it was. */
    internal fun lean(azimuth: Float, j: DoubleArray): Float {
        if (azimuth.isNaN()) return azimuth
        val vx = cos(azimuth.toDouble()); val vy = sin(azimuth.toDouble())
        val tx = j[0] * vx + j[1] * vy; val ty = j[2] * vx + j[3] * vy
        if (tx * tx + ty * ty < 1e-18) return azimuth
        return atan2(ty, tx).toFloat()
    }

    /** A barrel twist through [j]: turned by the map's rotation, or mirrored and turned under a flip. */
    internal fun twist(barrel: Float, j: DoubleArray): Float {
        if (barrel.isNaN()) return barrel
        val det = j[0] * j[3] - j[1] * j[2]
        val turned = if (det >= 0) barrel + atan2(j[2] - j[1], j[0] + j[3]) else -barrel + atan2(j[2] + j[1], j[0] - j[3])
        return wrap(turned).toFloat()
    }

    private fun wrap(a: Double): Double {
        var r = (a + PI) % (2 * PI)
        if (r < 0) r += 2 * PI
        return r - PI
    }
}
