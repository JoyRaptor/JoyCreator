package cc.joycreator.joybrush.core.media

import cc.joycreator.joybrush.core.paint.Tiles

/**
 * Making room when a drawing's pencil, watercolour and oil state reaches its ceiling (LayerBudget.mediaBudgetBytes).
 *
 * Until 2026-11-09 the stroke that reached it was refused in words ("this phone has room for 315 MB ... and this drawing
 * has used it"), and the artist had to clear paint or delete a layer to go on. The owner's bar is that an artist who knows
 * nothing about memory never meets it. So the oldest paint, farthest from the pen, SETS instead: its payload (pigment
 * planes, paper crush, ground) is let go and its picture stays exactly as it looks, as plain pixels. Painting over it later
 * starts from that picture, as over any picture (a tile without a payload lies on its own look, JB-2.40). What is lost is
 * only that such old paint can no longer be pushed about as wet paint. Nothing on screen changes when it happens.
 *
 * Never chosen: a tile whose water still runs or lies wet (its water stores exist), a tile the media tool holds in its
 * atlas, or a tile the write that needs the room is for. The caller filters those out; this picks among the rest.
 * Pure, so the rule is tested on the JVM.
 */
object MediaRoom {
    /** One tile's whole payload on one plane: what setting it frees, and when it was last painted (a counter; 0 = as loaded). */
    class Tile(val layerId: String, val plane: String?, val key: Long, val bytes: Long, val written: Long)

    /** Freed beyond what was asked, so that room is not made again for the very next tile: an eighth of the room, 24 MB at most. */
    fun slack(budgetBytes: Long): Long = minOf(budgetBytes / 8, 24L shl 20).coerceAtLeast(0L)

    /**
     * The tiles of [tiles] to set so that at least [needBytes] come free, or null when all of them together would not
     * (then nothing is set: losing paint without gaining the room would be loss for nothing).
     *
     * Least recently painted first; among tiles painted at the same time (a whole drawing just opened: all 0), the
     * farthest from [near] (the tiles being painted now) first. Up to [extraBytes] more is freed while tiles last.
     */
    fun choose(tiles: List<Tile>, near: Collection<Long>, needBytes: Long, extraBytes: Long = 0L): List<Tile>? {
        if (needBytes <= 0L) return emptyList()
        if (tiles.sumOf { it.bytes } < needBytes) return null
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var maxX = Int.MIN_VALUE; var maxY = Int.MIN_VALUE
        for (k in near) {
            val x = Tiles.tx(k); val y = Tiles.ty(k)
            if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y
        }
        fun away(t: Tile): Int {
            if (near.isEmpty()) return 0
            val x = Tiles.tx(t.key); val y = Tiles.ty(t.key)
            return maxOf(maxOf(minX - x, x - maxX, 0), maxOf(minY - y, y - maxY, 0))
        }
        val order = tiles.sortedWith(compareBy<Tile> { it.written }.thenByDescending(::away))
        val out = ArrayList<Tile>()
        var freed = 0L
        for (t in order) {
            if (freed >= needBytes + extraBytes) break
            out += t
            freed += t.bytes
        }
        return out
    }
}
