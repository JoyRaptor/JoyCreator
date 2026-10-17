package cc.joycreator.joybrush.core.brush

import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.paint.Tiles

/**
 * An armed Tile board's picture is the board rectangle repeated forever (R50). The layer only stores the rectangle; every
 * pixel outside it reads as the pixel [wrap] brings it back to. Pure arithmetic, so the tools that READ the layer (smudge's
 * carried colour and its texture pickup) agree with the stamps that WRITE it across the seam.
 */
object PeriodicTiles {
    /** [v] brought into `[origin, origin + period)`. Long arithmetic: a coordinate far from the board cannot overflow. */
    fun wrap(v: Int, origin: Int, period: Int): Int {
        require(period > 0) { "A tile period must be at least one pixel" }
        return (origin + (v.toLong() - origin).mod(period.toLong())).toInt()
    }

    /** True when every pixel of document tile [key] lies inside [period]: the stored tile already IS the periodic picture. */
    fun inside(period: RectPx, key: Long): Boolean {
        val x = Tiles.tx(key).toLong() * Tiles.SIZE; val y = Tiles.ty(key).toLong() * Tiles.SIZE
        return x >= period.x && y >= period.y &&
            x + Tiles.SIZE <= period.x.toLong() + period.w && y + Tiles.SIZE <= period.y.toLong() + period.h
    }

    /**
     * Document tile [key] of the PERIODIC picture: 256 x 256 premultiplied RGBA8 (the [TileReader] layout), each pixel copied
     * from the stored pixel it wraps to. Null when every source it reads is empty, which is what a reader says about an empty
     * tile. [reader] is asked once per distinct source tile.
     */
    fun compose(period: RectPx, key: Long, reader: TileReader): ByteArray? {
        require(period.w > 0 && period.h > 0) { "A tile period must be at least one pixel" }
        val size = Tiles.SIZE
        val ox = Tiles.tx(key) * size; val oy = Tiles.ty(key) * size
        val sources = HashMap<Long, ByteArray?>()
        fun source(tx: Int, ty: Int): ByteArray? {
            val k = Tiles.key(tx, ty)
            return if (sources.containsKey(k)) sources[k] else reader.tile(tx, ty).also { sources[k] = it }
        }
        val wx = IntArray(size) { wrap(ox + it, period.x, period.w) }
        val stx = IntArray(size) { wx[it].floorDiv(size) }
        var out: ByteArray? = null
        for (row in 0 until size) {
            val wy = wrap(oy + row, period.y, period.h)
            val sty = wy.floorDiv(size)
            val srcRow = (wy - sty * size) * size
            for (col in 0 until size) {
                val src = source(stx[col], sty) ?: continue
                val s = (srcRow + wx[col] - stx[col] * size) * 4
                if (src[s + 3].toInt() == 0 && src[s].toInt() == 0 && src[s + 1].toInt() == 0 && src[s + 2].toInt() == 0) continue
                val o = out ?: ByteArray(size * size * 4).also { out = it }
                val d = (row * size + col) * 4
                o[d] = src[s]; o[d + 1] = src[s + 1]; o[d + 2] = src[s + 2]; o[d + 3] = src[s + 3]
            }
        }
        return out
    }
}
