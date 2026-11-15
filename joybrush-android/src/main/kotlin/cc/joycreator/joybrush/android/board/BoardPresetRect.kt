package cc.joycreator.joybrush.android.board

import cc.joycreator.joybrush.core.doc.RectPx
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Where a board made from a preset goes (BOT-7, Tools → Boards): centred on what the screen shows, as large as
 * [SHARE] of it at the asked shape. A sprite's cells are whole pixels and all the same size, so the board is an exact
 * grid. Pure, so it is tested off the phone.
 */
object BoardPresetRect {
    const val SHARE = 0.7f
    /** Never smaller than this, so a board made while zoomed far in can still be drawn on. */
    const val MIN_SIDE = 64

    /** A board of exactly [w] x [h] px, centred on what the screen shows (a tile of a chosen size, a sprite's cells). */
    fun centred(left: Float, top: Float, right: Float, bottom: Float, w: Int, h: Int): RectPx =
        RectPx(((left + right) / 2f - w / 2f).roundToInt(), ((top + bottom) / 2f - h / 2f).roundToInt(), w, h)

    /**
     * [left], [top], [right], [bottom]: the document area the screen shows. [aspectW]:[aspectH] is the board's shape, or
     * each cell's for a sprite of [columns] x [rows].
     */
    fun fit(left: Float, top: Float, right: Float, bottom: Float, aspectW: Int, aspectH: Int, columns: Int = 1, rows: Int = 1): RectPx {
        val viewW = (right - left) * SHARE
        val viewH = (bottom - top) * SHARE
        val shapeW = aspectW.toFloat() * columns
        val shapeH = aspectH.toFloat() * rows
        val scale = minOf(viewW / shapeW, viewH / shapeH)
        // Whole cells: every cell the same whole number of pixels.
        val cellW = max(1, (aspectW * scale).roundToInt())
        val cellH = max(1, (aspectH * scale).roundToInt())
        val grow = maxOf(1, (MIN_SIDE + minOf(cellW * columns, cellH * rows) - 1) / maxOf(1, minOf(cellW * columns, cellH * rows)))
        val w = cellW * columns * grow
        val h = cellH * rows * grow
        val cx = (left + right) / 2f
        val cy = (top + bottom) / 2f
        return RectPx((cx - w / 2f).roundToInt(), (cy - h / 2f).roundToInt(), w, h)
    }
}
