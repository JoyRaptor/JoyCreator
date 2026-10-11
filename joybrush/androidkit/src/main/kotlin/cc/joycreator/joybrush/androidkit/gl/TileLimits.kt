package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.paint.Tiles
import cc.joycreator.joybrush.core.render.MAX_REGION_PX

/**
 * What the largest tile rectangle this phone can preview and paint into is. One definition, asked by
 * both [TilePainting.board] (arming and stroke start) and the board chrome's live resize (every drag
 * move), so the drag can never offer a rectangle that arming would then refuse and switch tiling off.
 * Pure Long arithmetic: no GL, so it is unit-tested on the JVM.
 */
object TileLimits {
    /** The most painting tiles a tile board may touch; a stroke's wrapped copies are bounded by it. */
    const val MAX_TILES = 256

    /** Plain-language reason [rect] cannot be a tile board, or null when it can. */
    fun refusal(rect: RectPx): String? {
        if (rect.w <= 0 || rect.h <= 0) return "A tile must be at least one pixel across"
        if (rect.x.toLong() + rect.w > Int.MAX_VALUE || rect.y.toLong() + rect.h > Int.MAX_VALUE) return "This tile is too far from the origin"
        if (rect.w.toLong() * rect.h > MAX_REGION_PX) return "This tile is too large to preview on this phone"
        val columns = Math.floorDiv(rect.x.toLong() + rect.w - 1, Tiles.SIZE.toLong()) - Math.floorDiv(rect.x.toLong(), Tiles.SIZE.toLong()) + 1
        val rows = Math.floorDiv(rect.y.toLong() + rect.h - 1, Tiles.SIZE.toLong()) - Math.floorDiv(rect.y.toLong(), Tiles.SIZE.toLong()) + 1
        if (columns * rows > MAX_TILES) return "This tile touches too many painting tiles on this phone"
        return null
    }

    fun fits(rect: RectPx): Boolean = refusal(rect) == null
}
