package cc.joycreator.joybrush.core.paper

import kotlin.math.sqrt

/** Periodic surface adapter for Tile mode. The canonical hex sampler remains unchanged.
 * Coordinates, rect and footprint are in SOURCE TEXELS, as in [HexTile.sampleSurface].
 * Convert a document rect by its paper texel pitch before calling; slopes retain the canonical
 * per-texel units. GLSL's rect is in document pixels, and its output slopes are per document pixel.
 * Four canonical reads (twelve texture fetches); device performance is unmeasured.
 */
object TilePaperSampler {
    data class Rect(val x: Double, val y: Double, val width: Double, val height: Double)

    /** Null rect or both nonpositive periods disables Tile and calls HexTile directly, bit for bit.
     * A partially enabled or nonfinite rect is a caller error, refused before sampling.
     * Smoothstep has zero weight derivative at the edges: this adds no first-derivative wrap seam
     * wherever the canonical filtered surface is differentiable. Intrinsic bilinear texel kinks
     * remain; neither this adapter nor the canonical sampler promises global C1 filtering.
     */
    fun sampleSurface(
        tex: PaperTexture, px: Double, py: Double, hexTexels: Double, rotatable: Boolean,
        slopeRange: Float, out: FloatArray, rect: Rect? = null, seed: Int = 0,
        footprint: Double = 1.0, heightMean: Float = 0.5f,
    ) {
        if (rect == null || (rect.width <= 0.0 && rect.height <= 0.0)) {
            HexTile.sampleSurface(tex, px, py, hexTexels, rotatable, slopeRange, out,
                seed, footprint, heightMean)
            return
        }
        require(out.size >= 4) { "tile paper needs at least 4 output floats" }
        require(rect.x.isFinite() && rect.y.isFinite() && rect.width.isFinite() && rect.height.isFinite()) {
            "tile paper rect must be finite"
        }
        require(rect.width > 0.0 && rect.height > 0.0) { "tile paper needs two positive periods" }
        require(px.isFinite() && py.isFinite()) { "tile paper point must be finite" }
        require((px - rect.x).isFinite() && (py - rect.y).isFinite()) { "tile paper relative point overflow" }
        val ux = wrap(px - rect.x, rect.width)
        val uy = wrap(py - rect.y, rect.height)
        val qx = rect.x + ux
        val qy = rect.y + uy
        val sx = smooth((ux / rect.width).toFloat())
        val sy = smooth((uy / rect.height).toFloat())
        val weights = floatArrayOf((1f - sx) * (1f - sy), sx * (1f - sy), (1f - sx) * sy, sx * sy)
        val sample = FloatArray(4)
        for (c in 0..3) out[c] = 0f
        var spread = 0f
        var weightSquares = 0f
        for (n in 0..3) {
            HexTile.sampleSurface(tex, qx - if (n and 1 != 0) rect.width else 0.0,
                qy - if (n and 2 != 0) rect.height else 0.0,
                hexTexels, rotatable, slopeRange, sample, seed, footprint, heightMean)
            val w = weights[n]
            out[0] += w * sample[0]
            out[1] += w * sample[1]
            out[2] += w * (sample[2] - heightMean)
            spread += w * w * (sample[3] - sample[2] * sample[2]).coerceAtLeast(0f)
            weightSquares += w * w
        }
        val keep = 1f / sqrt(weightSquares)
        out[0] *= keep
        out[1] *= keep
        out[2] = (heightMean + out[2] * keep).coerceIn(0f, 1f)
        out[3] = out[2] * out[2] + spread * keep * keep
    }

    fun sampleHeight(tex: PaperTexture, px: Double, py: Double, hexTexels: Double,
        rotatable: Boolean, slopeRange: Float, rect: Rect? = null, seed: Int = 0,
        footprint: Double = 1.0, heightMean: Float = 0.5f): Float {
        val out = FloatArray(4)
        sampleSurface(tex, px, py, hexTexels, rotatable, slopeRange, out, rect, seed, footprint, heightMean)
        return out[2]
    }

    /** The same global arrangement; only footprint is multiplied by eight, as in jb_paper. */
    fun sampleCoarseHeight(tex: PaperTexture, px: Double, py: Double, hexTexels: Double,
        rotatable: Boolean, slopeRange: Float, rect: Rect? = null, seed: Int = 0,
        footprint: Double = 1.0, heightMean: Float = 0.5f): Float =
        sampleHeight(tex, px, py, hexTexels, rotatable, slopeRange, rect, seed, footprint * 8.0, heightMean)

    private fun smooth(t: Float): Float = t * t * (3f - 2f * t)
    private fun wrap(p: Double, period: Double): Double {
        val remainder = p % period
        return if (remainder < 0.0) remainder + period else remainder
    }
}
