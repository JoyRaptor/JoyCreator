package cc.joycreator.joybrush.core.paint

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

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
        val g = Geometry(radiusPx, angle, tip)
        val d = g.d(dx, dy)
        // fwidth(d) exactly as the GPU forms it: forward differences one pixel along x and y.
        val aa = max(abs(g.d(dx + 1f, dy) - d) + abs(g.d(dx, dy + 1f) - d), 1e-4f)
        val inner = min(tip.hardness.coerceIn(0f, 1f), 1f - aa)
        val cov = 1f - smoothstep(inner, 1f + 0.5f * aa, d)
        return cov * g.fade
    }

    /** The tip's normalised superellipse distance field (the part of jb_tipCoverage before antialiasing). */
    private class Geometry(radiusPx: Float, angle: Float, private val tip: TipShape) {
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
            val qx = c * dx + s * dy
            val qy = -s * dx + c * dy
            val along = (qy / hy * 0.5f + 0.5f).coerceIn(0f, 1f)
            val widthScale = max(1f - tip.taper.coerceIn(0f, 1f) * along, 1e-3f)
            return superellipse(qx, qy, hx * widthScale, hy, tip.corner)
        }
    }

    /** Half-size of a square, centred on the dab, that contains the tip at any rotation (+ AA margin). */
    fun extent(radiusPx: Float): Float = radiusPx * 1.4143f + 2f

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        if (e1 <= e0) return if (x < e0) 0f else 1f
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
