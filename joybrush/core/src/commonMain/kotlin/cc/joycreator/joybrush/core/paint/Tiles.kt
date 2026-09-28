package cc.joycreator.joybrush.core.paint

import kotlin.math.ceil
import kotlin.math.floor

/**
 * Tile addressing for the sparse, unbounded canvas. A tile key packs (tx, ty) into one Long; negative
 * coordinates are fine (the canvas has no edge).
 */
object Tiles {
    const val SIZE = 256

    fun key(tx: Int, ty: Int): Long = (tx.toLong() shl 32) or (ty.toLong() and 0xFFFF_FFFFL)
    fun tx(key: Long): Int = (key shr 32).toInt()
    fun ty(key: Long): Int = key.toInt()

    /** Every tile a dab can touch (its full rotated extent). */
    fun touchedBy(d: Dab, size: Int = SIZE): List<Long> {
        val e = TipMath.extent(d.radius)
        val x0 = floor((d.x - e) / size).toInt()
        val x1 = floor((d.x + e) / size).toInt()
        val y0 = floor((d.y - e) / size).toInt()
        val y1 = floor((d.y + e) / size).toInt()
        val out = ArrayList<Long>((x1 - x0 + 1) * (y1 - y0 + 1))
        for (ty in y0..y1) for (tx in x0..x1) out.add(key(tx, ty))
        return out
    }

    /** Groups dabs by the tiles they touch, keeping each tile's dabs in stroke order. */
    fun bucket(dabs: List<Dab>, size: Int = SIZE): Map<Long, List<Dab>> {
        val m = LinkedHashMap<Long, MutableList<Dab>>()
        for (d in dabs) for (k in touchedBy(d, size)) m.getOrPut(k) { ArrayList() }.add(d)
        return m
    }

    /** Tile range covering a document-space rectangle (for drawing only what is on screen). */
    fun range(left: Float, top: Float, right: Float, bottom: Float, size: Int = SIZE): IntArray = intArrayOf(
        floor(left / size).toInt(), floor(top / size).toInt(),
        ceil(right / size).toInt() - 1, ceil(bottom / size).toInt() - 1,
    )
}
