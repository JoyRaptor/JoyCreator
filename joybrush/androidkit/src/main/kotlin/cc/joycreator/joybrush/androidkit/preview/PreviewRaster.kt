package cc.joycreator.joybrush.androidkit.preview

import cc.joycreator.joybrush.core.paint.Tiles

/**
 * The pixel work around a preview that needs no GPU: painting the strip or the colour bands a brush is shown on, putting
 * an engine's tiles together into one picture, and laying a media look on its paper. All of it works on premultiplied
 * RGBA8, rows top first, the layout the engine reads and writes tiles in (GlPaintEngine.readTile) and the layout
 * `Bitmap.copyPixelsFromBuffer` takes for an ARGB_8888 bitmap.
 */
object PreviewRaster {

    private const val TILE = Tiles.SIZE

    /** [argb] (straight) as premultiplied R, G, B, A bytes, rounded to the nearest. */
    fun premultiplied(argb: Int): ByteArray {
        val a = (argb ushr 24) and 0xFF
        fun ch(shift: Int): Byte = ((((argb ushr shift) and 0xFF) * a + 127) / 255).toByte()
        return byteArrayOf(ch(16), ch(8), ch(0), a.toByte())
    }

    /**
     * Paints [x0, x1) × [y0, y1) (picture px) with [argb] into [tiles], making the tiles it needs, replacing what was
     * there. The part outside the picture's top-left quadrant is ignored: previews never go negative.
     */
    fun fillRect(tiles: MutableMap<Long, ByteArray>, x0: Int, y0: Int, x1: Int, y1: Int, argb: Int) {
        val left = maxOf(0, x0)
        val top = maxOf(0, y0)
        if (x1 <= left || y1 <= top) return
        val px = premultiplied(argb)
        for (ty in top / TILE..(y1 - 1) / TILE) for (tx in left / TILE..(x1 - 1) / TILE) {
            val tile = tiles.getOrPut(Tiles.key(tx, ty)) { ByteArray(TILE * TILE * 4) }
            val ox = tx * TILE
            val oy = ty * TILE
            for (y in maxOf(top, oy) until minOf(y1, oy + TILE)) {
                for (x in maxOf(left, ox) until minOf(x1, ox + TILE)) {
                    val at = ((y - oy) * TILE + (x - ox)) * 4
                    px.copyInto(tile, at)
                }
            }
        }
    }

    /** [tiles] as one [width] × [height] picture; where there is no tile, transparent. Tiles outside the picture are ignored. */
    fun assemble(tiles: Map<Long, ByteArray>, width: Int, height: Int): ByteArray {
        val out = ByteArray(width * height * 4)
        for ((key, tile) in tiles) {
            require(tile.size == TILE * TILE * 4) { "a tile is ${TILE * TILE * 4} bytes" }
            val ox = Tiles.tx(key) * TILE
            val oy = Tiles.ty(key) * TILE
            val x0 = maxOf(0, ox)
            val x1 = minOf(width, ox + TILE)
            if (x1 <= x0) continue
            for (y in maxOf(0, oy) until minOf(height, oy + TILE)) {
                tile.copyInto(out, (y * width + x0) * 4, ((y - oy) * TILE + (x0 - ox)) * 4, ((y - oy) * TILE + (x1 - ox)) * 4)
            }
        }
        return out
    }

    /**
     * [layer] (premultiplied) laid over [paper] (opaque, straight), in place into [layer]: the picture the person would see
     * with the layer on that paper. Both are the same size.
     */
    fun overPaper(layer: ByteArray, paper: ByteArray) {
        require(layer.size == paper.size && layer.size % 4 == 0) { "layer and paper differ in size" }
        var i = 0
        while (i < layer.size) {
            val keep = 255 - (layer[i + 3].toInt() and 0xFF)
            for (c in 0 until 3) {
                val over = (layer[i + c].toInt() and 0xFF) + ((paper[i + c].toInt() and 0xFF) * keep + 127) / 255
                layer[i + c] = over.coerceAtMost(255).toByte()
            }
            layer[i + 3] = 0xFF.toByte()
            i += 4
        }
    }
}
