package cc.joycreator.joybrush.core.paper

import kotlin.math.floor

/**
 * A decoded tileable texture: RGBA8, row-major, row 0 = top. The one texture type both paper reads
 * take, so a surface map (R,G slopes, B height, A height²) and a look map (R,G,B colour) are the same
 * object to [HexTile] and the same bytes to whoever decoded them.
 */
class PaperTexture(val w: Int, val h: Int, val rgba: ByteArray) {

    init {
        require(w > 0 && h > 0) { "a paper texture needs a positive size, got ${w}x$h" }
        require(rgba.size == w * h * 4) {
            "a ${w}x$h RGBA8 texture is ${w * h * 4} bytes, got ${rgba.size}"
        }
    }

    /**
     * Bilinear, wrapping, at TEXEL coordinates (texel centres at i + 0.5). Returns 4 channels 0..1
     * into [out] (size >= 4).
     *
     * Wrapping, not clamping: a paper texture is tileable by construction (JB-9.01 Decision 4), and a
     * read that clamped would draw the edge texel as a seam a thousand times a screen.
     */
    fun bilinear(tx: Double, ty: Double, out: FloatArray) {
        require(out.size >= 4) { "bilinear needs an output array of at least 4 floats, got ${out.size}" }
        // Texel centres sit at i + 0.5, so the index space is the coordinate space shifted by half.
        val x = tx - 0.5
        val y = ty - 0.5
        val cx = floor(x)
        val cy = floor(y)
        val fx = (x - cx).toFloat()
        val fy = (y - cy).toFloat()
        val x0 = wrap(cx, w)
        val y0 = wrap(cy, h)
        val x1 = if (x0 + 1 == w) 0 else x0 + 1
        val y1 = if (y0 + 1 == h) 0 else y0 + 1
        val i00 = (y0 * w + x0) * 4
        val i10 = (y0 * w + x1) * 4
        val i01 = (y1 * w + x0) * 4
        val i11 = (y1 * w + x1) * 4
        val g00 = (1f - fx) * (1f - fy)
        val g10 = fx * (1f - fy)
        val g01 = (1f - fx) * fy
        val g11 = fx * fy
        for (c in 0..3) {
            out[c] = g00 * byte(rgba[i00 + c]) + g10 * byte(rgba[i10 + c]) +
                g01 * byte(rgba[i01 + c]) + g11 * byte(rgba[i11 + c])
        }
    }

    /** One channel, 0..1, at one texel index. */
    fun channelAt(x: Int, y: Int, c: Int): Float = byte(rgba[(y * w + x) * 4 + c])

    private fun byte(b: Byte): Float = (b.toInt() and 0xFF) * (1f / 255f)

    /**
     * A whole number of texels along one axis, wrapped into 0 until [n]. Modulo rather than a mask,
     * because a paper read on an endless canvas is millions of texels from the origin and the wrap has
     * to survive that.
     */
    private fun wrap(v: Double, n: Int): Int {
        val m = floor(v).toLong() % n
        return if (m < 0) (m + n).toInt() else m.toInt()
    }
}
