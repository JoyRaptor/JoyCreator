package cc.joycreator.joybrush.core.paint

/**
 * Layer thumbnails (JB-2.04): a GPU readback, drawn [factor] times too big, averaged down to its real size. Pure, so the
 * arithmetic that decides whether a thumbnail looks right is a test and not a phone check.
 */
object Thumbnails {

    /**
     * [rgba] ([w] x [h], PREMULTIPLIED RGBA8, row 0 first) box-averaged by [factor] into ARGB ints that are NOT
     * premultiplied, which is what an Android Bitmap is built from. Averaging is done premultiplied (the only correct way:
     * averaging straight colour would drag a red edge towards the black of the empty pixels beside it), then divided
     * out. A fully transparent result is 0.
     */
    fun downsample(rgba: ByteArray, w: Int, h: Int, factor: Int): IntArray {
        require(factor >= 1 && w % factor == 0 && h % factor == 0) { "size must be a multiple of the factor" }
        require(rgba.size >= w * h * 4) { "not enough pixels" }
        val ow = w / factor
        val oh = h / factor
        val n = factor * factor
        val out = IntArray(ow * oh)
        for (oy in 0 until oh) for (ox in 0 until ow) {
            var r = 0; var g = 0; var b = 0; var a = 0
            for (dy in 0 until factor) {
                var i = ((oy * factor + dy) * w + ox * factor) * 4
                for (dx in 0 until factor) {
                    r += rgba[i].toInt() and 0xFF
                    g += rgba[i + 1].toInt() and 0xFF
                    b += rgba[i + 2].toInt() and 0xFF
                    a += rgba[i + 3].toInt() and 0xFF
                    i += 4
                }
            }
            if (a == 0) continue
            val alpha = (a + n / 2) / n
            // Unpremultiply from the SUMS, so no precision is lost to an intermediate rounding.
            fun un(c: Int): Int = ((c * 255 + a / 2) / a).coerceIn(0, 255)
            out[oy * ow + ox] = (alpha shl 24) or (un(r) shl 16) or (un(g) shl 8) or un(b)
        }
        return out
    }
}
