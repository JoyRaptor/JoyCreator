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
        val fx = x - cx
        val fy = y - cy
        val x0 = wrap(cx, w)
        val y0 = wrap(cy, h)
        val x1 = if (x0 + 1 == w) 0 else x0 + 1
        val y1 = if (y0 + 1 == h) 0 else y0 + 1
        val i00 = (y0 * w + x0) * 4
        val i10 = (y0 * w + x1) * 4
        val i01 = (y1 * w + x0) * 4
        val i11 = (y1 * w + x1) * 4
        val g00 = (1.0 - fx) * (1.0 - fy)
        val g10 = fx * (1.0 - fy)
        val g01 = (1.0 - fx) * fy
        val g11 = fx * fy
        for (c in 0..3) {
            out[c] = ((g00 * (rgba[i00 + c].toInt() and 255) + g10 * (rgba[i10 + c].toInt() and 255) +
                g01 * (rgba[i01 + c].toInt() and 255) + g11 * (rgba[i11 + c].toInt() and 255)) / 255.0).toFloat()
        }
    }

    // Decoded bytes are immutable after construction, including while cached levels are in use.
    private val mips: List<PaperTexture> by lazy {
        val levels = mutableListOf(this)
        var source = this
        while (source.w > 1 || source.h > 1) {
            val nw = maxOf(1, source.w / 2); val nh = maxOf(1, source.h / 2)
            val data = ByteArray(nw * nh * 4)
            // Area averaging also preserves all texels of odd-sized imported images.
            for (y in 0 until nh) for (x in 0 until nw) for (c in 0..3) {
                val left = x.toDouble() * source.w / nw; val right = (x + 1.0) * source.w / nw
                val top = y.toDouble() * source.h / nh; val bottom = (y + 1.0) * source.h / nh
                var sum = 0.0
                for (sy in floor(top).toInt() until kotlin.math.ceil(bottom).toInt())
                    for (sx in floor(left).toInt() until kotlin.math.ceil(right).toInt()) {
                        val weight = (minOf(right, sx + 1.0) - maxOf(left, sx.toDouble())) *
                            (minOf(bottom, sy + 1.0) - maxOf(top, sy.toDouble()))
                        sum += (source.rgba[(sy * source.w + sx) * 4 + c].toInt() and 255) * weight
                    }
                data[(y * nw + x) * 4 + c] = kotlin.math.round(sum / ((right - left) * (bottom - top))).toInt().toByte()
            }
            source = PaperTexture(nw, nh, data)
            levels.add(source)
        }
        levels
    }

    /** Trilinear mip read matching LINEAR_MIPMAP_LINEAR, footprint in source texels per pixel. */
    fun filtered(tx: Double, ty: Double, footprint: Double, out: FloatArray) {
        require(footprint.isFinite() && footprint > 0.0)
        if (footprint <= 1.0) { bilinear(tx, ty, out); return }
        val levels = mips
        val lod = (kotlin.math.ln(footprint) / kotlin.math.ln(2.0)).coerceIn(0.0, levels.lastIndex.toDouble())
        val low = floor(lod).toInt(); val high = minOf(low + 1, levels.lastIndex)
        val a = levels[low]; val b = levels[high]
        a.bilinear(tx * a.w / w, ty * a.h / h, out)
        if (high == low || lod == low.toDouble()) return
        val second = FloatArray(4)
        b.bilinear(tx * b.w / w, ty * b.h / h, second)
        val weight = (lod - low).toFloat()
        for (c in 0..3) out[c] += (second[c] - out[c]) * weight
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
