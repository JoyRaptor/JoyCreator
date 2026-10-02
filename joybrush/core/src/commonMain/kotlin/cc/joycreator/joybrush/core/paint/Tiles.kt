package cc.joycreator.joybrush.core.paint

import kotlin.math.ceil
import kotlin.math.floor

/**
 * Tile addressing for the sparse, unbounded canvas. A tile key packs (tx, ty) into one Long; negative
 * coordinates are fine (the canvas has no edge).
 */
object Tiles {
    const val SIZE = 256

    /**
     * True when a tile holds nothing at all: every one of its four channels is zero.
     *
     * Tiles are stored PREMULTIPLIED, so an unpainted tile is all zeros and any painted pixel has a
     * non-zero colour, a non-zero alpha, or both. There is no way to be non-zero in one channel and
     * zero in the others and still be invisible, which is what makes this a safe test rather than a
     * guess: erasing to transparent produces exactly all-zero bytes.
     *
     * The engine allocates a tile the moment a stroke touches it, so a drawing that was scribbled on
     * while zoomed out keeps a ring of tiles nothing was ever drawn into. Skipping those on save is
     * worth a lot: such a tile deflates to about 271 bytes in the archive but costs a full
     * `SIZE*SIZE*4` array every time the drawing is opened.
     */
    fun isBlank(tile: ByteArray): Boolean {
        for (b in tile) if (b.toInt() != 0) return false
        return true
    }

    fun key(tx: Int, ty: Int): Long = (tx.toLong() shl 32) or (ty.toLong() and 0xFFFF_FFFFL)
    fun tx(key: Long): Int = (key shr 32).toInt()
    fun ty(key: Long): Int = key.toInt()

    /** Every tile a dab can touch (its full rotated extent). */
    fun touchedBy(d: Dab, size: Int = SIZE): List<Long> {
        val e = TipMath.extent(d.radius, d.anchor)
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
