package cc.joycreator.joybrush.core.chrome

/** A rectangle in whole pixels: where a picture is drawn. */
data class FitBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * Putting a picture of its own shape into a box without bending it (owner, 2026-10-27: Joybot's top-bar button squeezed his
 * tall 360 x 564 art into a square, so he looked about 57% too wide).
 */
object IconFit {

    /**
     * The picture's box when it is fitted INSIDE the box [left], [top], [right], [bottom]: as large as will go, the picture's own
     * shape, centred. A tall picture fills the height and leaves room at the sides; a wide one fills the width.
     *
     * A picture or box with no size to speak of ([srcW], [srcH] or the box not above zero) gets the whole box back, as
     * drawing it plainly would.
     */
    fun inside(left: Int, top: Int, right: Int, bottom: Int, srcW: Int, srcH: Int): FitBox {
        val bw = right - left
        val bh = bottom - top
        if (srcW <= 0 || srcH <= 0 || bw <= 0 || bh <= 0) return FitBox(left, top, right, bottom)
        val w: Int
        val h: Int
        // Wider than the box (compared without dividing): the width is the limit. Otherwise the height is.
        if (srcW.toLong() * bh >= bw.toLong() * srcH) {
            w = bw
            h = ((bw.toLong() * srcH * 2 + srcW) / (2L * srcW)).toInt().coerceIn(1, bh)
        } else {
            h = bh
            w = ((bh.toLong() * srcW * 2 + srcH) / (2L * srcH)).toInt().coerceIn(1, bw)
        }
        val l = left + (bw - w) / 2
        val t = top + (bh - h) / 2
        return FitBox(l, t, l + w, t + h)
    }
}
