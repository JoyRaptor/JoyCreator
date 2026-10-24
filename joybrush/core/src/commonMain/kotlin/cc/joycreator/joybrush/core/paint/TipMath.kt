package cc.joycreator.joybrush.core.paint

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import cc.joycreator.joybrush.core.brush.TipProfile
import cc.joycreator.joybrush.core.brush.TipProfiles
import cc.joycreator.joybrush.core.brush.TipProfileSpec
import cc.joycreator.joybrush.core.brush.QuadMath

/**
 * CPU twin of `joybrush/shaders/jb_tip.glsl` — the reference the GPU is checked against, and what
 * CPU-side tools (thumbnails, tests) use. Keep the two in step: any change to the shader's maths is
 * made here too, in the same commit.
 *
 * The one deliberate difference: the shader antialiases with `fwidth(d)` (the screen-space rate of
 * change); here that rate is estimated analytically as 1 / (the tip's local half-width in px), which
 * matches it closely at the rim where antialiasing matters.
 */
object TipMath {

    fun superellipse(qx: Float, qy: Float, hx: Float, hy: Float, n: Float): Float {
        val ux = abs(qx / hx)
        val uy = abs(qy / hy)
        val e = n.coerceIn(0.5f, 64f)
        return (ux.pow(e) + uy.pow(e)).pow(1f / e)
    }

    /** Coverage 0..1 at offset (dx, dy) px from the dab centre. */
    fun coverage(dx: Float, dy: Float, radiusPx: Float, angle: Float, tip: TipShape): Float {
        if (tip.quad?.let { QuadMath.prepare(it).empty } == true) return 0f
        val g = Geometry(radiusPx, angle, tip)
        fun dual(coverage: Float): Float = if (tip.dual == null) coverage else g.dualCoverage(dx, dy, coverage)
        val imageCoverage = if (tip.sampled != null) g.imageCoverage(dx, dy) else 1f
        if (imageCoverage <= 0f) return 0f
        // A PNG defines its own silhouette. Only an explicitly chosen profile/quad masks it.
        if (tip.sampled != null && tip.quad == null && tip.profile == null) {
            val result = imageCoverage * g.fade
            return dual(if (tip.fadeAmount > 0f) result * g.directionalFade(dx, dy, tip.fadeAmount, tip.fadeAngle) else result)
        }
        val d = g.d(dx, dy)
        // fwidth(d) exactly as the GPU forms it: forward differences one pixel along x and y.
        val aa = max(abs(g.d(dx + 1f, dy) - d) + abs(g.d(dx, dy + 1f) - d), 1e-4f)
        val inner = min(tip.hardness.coerceIn(0f, 1f), 1f - aa)
        val outer = 1f + 0.5f * aa
        fun radial(profile: TipProfileSpec?): Float = when (profile?.kind) {
            null -> 1f - smoothstep(inner, outer, d)
            TipProfile.hard -> 1f - smoothstep(1f - 0.5f * aa, outer, d)
            else -> TipProfiles.coverage(profile!!, ((d - inner) / (outer - inner)).coerceIn(0f, 1f))
        }
        val first = radial(tip.profile)
        val mix = tip.profileMix.coerceIn(0f, 1f)
        val cov = if (mix > 0f) first + (radial(tip.profileB) - first) * mix else first
        val legacy = cov * g.fade * imageCoverage
        if (!(tip.fadeAmount > 0f)) return dual(legacy)
        return dual(legacy * g.directionalFade(dx, dy, tip.fadeAmount, tip.fadeAngle))
    }

    /** The tip's normalised superellipse distance field (the part of jb_tipCoverage before antialiasing). */
    private class Geometry(radiusPx: Float, angle: Float, private val tip: TipShape) {
        private val quad = tip.quad?.let { QuadMath.prepare(it) }
        private val shift = radiusPx * (if (tip.anchor.isFinite()) tip.anchor.coerceIn(0f, 1f) else 0f)
        private val c = cos(angle)
        private val s = sin(angle)
        private val hx: Float
        private val hy: Float
        val fade: Float

        init {
            val a = tip.aspect.coerceIn(-1f, 1f)
            val rawX = radiusPx * (if (a > 0f) 1f - a else 1f)
            val rawY = radiusPx * (if (a < 0f) 1f + a else 1f)
            val halfMin = max(tip.minPx, 0.5f) * 0.5f
            // At exactly ±1 the true width is zero; a quarter-pixel floor keeps a faint hairline visible.
            fade = min(max(rawX, 0.25f) / halfMin, 1f) * min(max(rawY, 0.25f) / halfMin, 1f)
            hx = max(rawX, halfMin)
            hy = max(rawY, halfMin)
        }

        fun d(dx: Float, dy: Float): Float {
            val qx = c * dx + s * dy + shift
            val qy = -s * dx + c * dy
            val along = (qy / hy * 0.5f + 0.5f).coerceIn(0f, 1f)
            val widthScale = max(1f - tip.taper.coerceIn(0f, 1f) * along, 1e-3f)
            val shape = quad
            if (shape != null) return 1f + shape.signedDistance(qx / (hx * widthScale), qy / hy) / shape.normalizationScale
            if (tip.sampled != null) return max(abs(qx / (hx * widthScale)), abs(qy / hy))
            return superellipse(qx, qy, hx * widthScale, hy, tip.corner)
        }

        fun imageCoverage(dx: Float, dy: Float): Float {
            val qx = c * dx + s * dy + shift
            val qy = -s * dx + c * dy
            val along = (qy / hy * .5f + .5f).coerceIn(0f, 1f)
            val widthScale = max(1f - tip.taper.coerceIn(0f, 1f) * along, 1e-3f)
            return checkNotNull(tip.sampled).mask.sample(qx / (hx * widthScale), qy / hy)
        }

        fun dualCoverage(dx: Float, dy: Float, coverage: Float): Float {
            val qx = c * dx + s * dy + shift
            val qy = -s * dx + c * dy
            val along = (qy / hy * .5f + .5f).coerceIn(0f, 1f)
            val widthScale = max(1f - tip.taper.coerceIn(0f, 1f) * along, 1e-3f)
            return checkNotNull(tip.dual).apply(coverage, qx / (hx * widthScale), qy / hy)
        }

        fun directionalFade(dx: Float, dy: Float, amount: Float, angle: Float): Float {
            val vx = cos(angle)
            val vy = sin(angle)
            val qx = c * dx + s * dy + shift
            val qy = -s * dx + c * dy
            // Project in physical local space: a canvas-relative fade keeps its angle on a thin nib.
            val reach = max(sqrt(hx * hx * vx * vx + hy * hy * vy * vy), 1e-4f)
            val across = (.5f + .5f * (qx * vx + qy * vy) / reach).coerceIn(0f, 1f)
            return 1f - amount.coerceIn(0f, 1f) * across
        }
    }

    /** Half-size of a square, centred on the dab, that contains the tip at any rotation (+ AA margin). */
    fun extent(radiusPx: Float, anchor: Float = 0f,
               sampled: cc.joycreator.joybrush.core.brush.SampledStamp? = null, aspect: Float = 0f): Float {
        val shift = if (anchor.isFinite()) abs(anchor.coerceIn(0f, 1f)) else 0f
        if (sampled == null) return radiusPx * (1.4143f + shift) + 2f
        val a = if (aspect.isFinite()) aspect.coerceIn(-1f, 1f) else 0f
        val halfMin = max(sampled.minPx, .5f) * .5f
        val hx = max(radiusPx * (if (a > 0f) 1f-a else 1f), halfMin) * sampled.mask.supportHalfWidth
        val hy = max(radiusPx * (if (a < 0f) 1f+a else 1f), halfMin) * sampled.mask.supportHalfHeight
        return max(radiusPx * 1.4143f, sqrt(hx * hx + hy * hy)) + radiusPx * shift + 2f
    }

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        if (e1 <= e0) return if (x < e0) 0f else 1f
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
