package cc.joycreator.joybrush.core.select

import cc.joycreator.joybrush.core.brush.PeriodicTiles
import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.paint.Tiles

/**
 * This mask as it lands on an armed Tile board ([period] = the board rectangle, R50): every covered pixel is carried back
 * into the rectangle by whole periods, and where several copies land on one pixel the strongest coverage wins (a union, so
 * a shape that crosses the seam fills the repeat as one shape, and a shape bigger than the tile never fills a pixel twice).
 * The result has coverage ONLY inside [period]. A mask already inside it comes back unchanged.
 */
fun SelectionMask.foldInto(period: RectPx): SelectionMask {
    require(period.w > 0 && period.h > 0) { "A tile period must be at least one pixel" }
    val box = bounds() ?: return this
    if (box.x >= period.x && box.y >= period.y &&
        box.x.toLong() + box.w <= period.x.toLong() + period.w && box.y.toLong() + box.h <= period.y.toLong() + period.h) return this
    val out = HashMap<Long, ByteArray>()
    val size = Tiles.SIZE
    for ((key, tile) in tiles) {
        val ox = Tiles.tx(key) * size; val oy = Tiles.ty(key) * size
        for (row in 0 until size) {
            val wy = PeriodicTiles.wrap(oy + row, period.y, period.h)
            for (col in 0 until size) {
                val c = tile[row * size + col].toInt() and 0xFF
                if (c == 0) continue
                val wx = PeriodicTiles.wrap(ox + col, period.x, period.w)
                val target = writable(out, Tiles.key(tileOfPx(wx), tileOfPx(wy)))
                val i = indexInTile(wx, wy)
                if (c > (target[i].toInt() and 0xFF)) target[i] = c.toByte()
            }
        }
    }
    return SelectionMask(finalise(out))
}
