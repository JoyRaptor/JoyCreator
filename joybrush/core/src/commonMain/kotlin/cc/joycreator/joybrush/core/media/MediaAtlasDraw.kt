package cc.joycreator.joybrush.core.media

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Media Atlas v2 (tasks/joybrush/specs/MEDIA_ATLAS_V2_20261016.md), slice B1: the per-slot instances a pass draws when the
 * media shaders are built with JB_ATLAS (shaders/media/jb_media_atlas.glsl).
 *
 * A layer-shaped pass over a rectangle is ONE instanced draw: one instance per slot it writes, carrying the slot's origin
 * (atlas texels), its tile, and the part of the rectangle inside that tile (`a_slot`, `a_clip` in jb_media_quad.vert). A dab
 * pass draws each dab once per slot its box touches, in dab order (so every texel still receives its dabs in order), with
 * the slot appended (`a_slot`, location 6). Pure arithmetic over a page table given as a function, so it is JVM-tested and
 * the GL driver only uploads what it returns. Buffers are reused: no allocation per pass once they have grown.
 */
class MediaAtlasDraw {
    /** Per-instance floats of a rect pass: slot origin x, y (texels), tile x, y, then clip x0, y0, x1, y1 (pass-resolution px). */
    var rect = FloatArray(8 * 16); private set
    var rectCount = 0; private set
    /** Per-instance floats of a dab pass: the dab's [DabBatch.FLOATS] then slot origin x, y and tile x, y. */
    var dabs = FloatArray(DAB_FLOATS * 64); private set
    var dabCount = 0; private set

    /**
     * Instances of a pass over [x0, y0, x1, y1) (layer px at the pass's resolution: a tile is `1 shl shift` of them), for every
     * tile it touches. [slotOf] maps a tile to its slot (column + row × [slotsX]), or −1 when it is not resident; a tile that
     * is not resident is not drawn (the residency rule makes that only ever a tile the pass does not write). Returns the count.
     */
    fun rects(x0: Float, y0: Float, x1: Float, y1: Float, shift: Int, slotsX: Int, slotOf: (Int, Int) -> Int): Int {
        require(shift in 1..15 && slotsX > 0) { "bad atlas geometry" }
        rectCount = 0
        if (!(x1 > x0 && y1 > y0)) return 0
        val side = 1 shl shift
        val tx0 = floorDiv(x0, side); val ty0 = floorDiv(y0, side)
        val tx1 = floorDiv(ceilBelow(x1), side); val ty1 = floorDiv(ceilBelow(y1), side)
        for (ty in ty0..ty1) for (tx in tx0..tx1) {
            val cx0 = max(x0, (tx * side).toFloat()); val cy0 = max(y0, (ty * side).toFloat())
            val cx1 = min(x1, ((tx + 1) * side).toFloat()); val cy1 = min(y1, ((ty + 1) * side).toFloat())
            if (!(cx1 > cx0 && cy1 > cy0)) continue
            val slot = slotOf(tx, ty)
            if (slot < 0) continue
            val o = rectCount * 8
            if (o + 8 > rect.size) rect = rect.copyOf(rect.size * 2)
            rect[o] = ((slot % slotsX) shl shift).toFloat(); rect[o + 1] = ((slot / slotsX) shl shift).toFloat()
            rect[o + 2] = tx.toFloat(); rect[o + 3] = ty.toFloat()
            rect[o + 4] = cx0; rect[o + 5] = cy0; rect[o + 6] = cx1; rect[o + 7] = cy1
            rectCount++
        }
        return rectCount
    }

    /**
     * Instances of [count] dabs packed [DabBatch.FLOATS] each in [src]; [box] writes dab i's layer-px bounding box (x0, y0,
     * x1, y1, full resolution) into its array. Each dab is emitted once per resident slot its box touches, dabs in order.
     */
    fun dabs(src: FloatArray, count: Int, slotsX: Int, slotOf: (Int, Int) -> Int, box: (Int, FloatArray) -> Unit): Int =
        dabs(src, count, slotsX, SHIFT, slotOf, box)

    /** [dabs] into a target at [shift] (7: the half-resolution water of coarse water): boxes in that target's px. */
    fun dabs(src: FloatArray, count: Int, slotsX: Int, shift: Int, slotOf: (Int, Int) -> Int, box: (Int, FloatArray) -> Unit): Int {
        require(count >= 0 && count * DabBatch.FLOATS <= src.size) { "dab batch is shorter than its count" }
        dabCount = 0
        val b = scratch
        for (i in 0 until count) {
            box(i, b)
            if (!(b[2] >= b[0] && b[3] >= b[1])) continue
            val side = 1 shl shift
            val tx0 = floorDiv(b[0], side); val ty0 = floorDiv(b[1], side); val tx1 = floorDiv(b[2], side); val ty1 = floorDiv(b[3], side)
            for (ty in ty0..ty1) for (tx in tx0..tx1) {
                val slot = slotOf(tx, ty)
                if (slot < 0) continue
                val o = dabCount * DAB_FLOATS
                if (o + DAB_FLOATS > dabs.size) dabs = dabs.copyOf(dabs.size * 2)
                src.copyInto(dabs, o, i * DabBatch.FLOATS, (i + 1) * DabBatch.FLOATS)
                dabs[o + DabBatch.FLOATS] = ((slot % slotsX) shl shift).toFloat(); dabs[o + DabBatch.FLOATS + 1] = ((slot / slotsX) shl shift).toFloat()
                dabs[o + DabBatch.FLOATS + 2] = tx.toFloat(); dabs[o + DabBatch.FLOATS + 3] = ty.toFloat()
                dabCount++
            }
        }
        return dabCount
    }

    /**
     * Makes [count] instances of [src] (laid out as [rect], 8 floats each), those at [index] 0 until [count], the current
     * [rect] instances, as if [rects] had made them: a pass drawn group by group ([MediaAtlasGl.drawRect]).
     */
    fun load(src: FloatArray, index: IntArray, count: Int) {
        if (8 * count > rect.size) rect = FloatArray(8 * count)
        for (i in 0 until count) src.copyInto(rect, i * 8, index[i] * 8, index[i] * 8 + 8)
        rectCount = count
    }

    /** The parts of the last [rects] at least a reach in from their tile's edges ([split]), as [rect] instances. */
    var inner = FloatArray(8 * 16); private set
    var innerCount = 0; private set
    /** ... and what is left of each instance: the strips along its tile's edges, or the whole instance. */
    var border = FloatArray(8 * 32); private set
    var borderCount = 0; private set

    /**
     * Splits the instances of the last [rects] (made with [shift]) by [reach] (pass px): the part of each at least [reach]
     * in from every edge of its tile goes to [inner], the rest to [border] (up to four strips, or the whole instance when
     * nothing of it is that far in). A pass reading no farther than [reach] reads only its own tile in [inner], so there it
     * needs no neighbour addressing (JB_ATLAS_OWN). The new edges are whole pixels and pixel centres sit at halves, so
     * every pixel of an instance falls in exactly one part; a part's outer edges are the instance's own.
     */
    fun split(reach: Int, shift: Int) {
        val side = 1 shl shift
        innerCount = 0; borderCount = 0
        for (i in 0 until rectCount) {
            val o = i * 8
            val tx0 = rect[o + 2] * side; val ty0 = rect[o + 3] * side
            val x0 = rect[o + 4]; val y0 = rect[o + 5]; val x1 = rect[o + 6]; val y1 = rect[o + 7]
            val ix0 = max(x0, tx0 + reach); val iy0 = max(y0, ty0 + reach)
            val ix1 = min(x1, tx0 + side - reach); val iy1 = min(y1, ty0 + side - reach)
            if (!(ix1 > ix0 && iy1 > iy0)) { putBorder(o, x0, y0, x1, y1); continue }
            if (8 * (innerCount + 1) > inner.size) inner = inner.copyOf(inner.size * 2)
            put(inner, innerCount++, o, ix0, iy0, ix1, iy1)
            if (iy0 > y0) putBorder(o, x0, y0, x1, iy0)
            if (iy1 < y1) putBorder(o, x0, iy1, x1, y1)
            if (ix0 > x0) putBorder(o, x0, iy0, ix0, iy1)
            if (ix1 < x1) putBorder(o, ix1, iy0, x1, iy1)
        }
    }
    private fun putBorder(o: Int, x0: Float, y0: Float, x1: Float, y1: Float) {
        if (8 * (borderCount + 1) > border.size) border = border.copyOf(border.size * 2)
        put(border, borderCount++, o, x0, y0, x1, y1)
    }
    private fun put(to: FloatArray, at: Int, o: Int, x0: Float, y0: Float, x1: Float, y1: Float) {
        rect.copyInto(to, at * 8, o, o + 4)
        to[at * 8 + 4] = x0; to[at * 8 + 5] = y0; to[at * 8 + 6] = x1; to[at * 8 + 7] = y1
    }

    private val scratch = FloatArray(4)

    companion object {
        /** Full-resolution slot side, log2: 256 = Tiles.SIZE (jb_media_atlas.glsl JB_SLOT_SHIFT). */
        const val SHIFT = 8
        const val SIDE = 1 shl SHIFT
        /** Floats per dab instance in the atlas build: the dab, then its slot (96 bytes, location 6 at byte 80). */
        const val DAB_FLOATS = DabBatch.FLOATS + 4

        /** The identity layout of B1: the window's [n] × [n] tiles are its slots, in place. */
        fun identity(n: Int): (Int, Int) -> Int = identity(n, n)
        /** The identity layout of a [nx] × [ny] window (slots per row = [nx]). */
        fun identity(nx: Int, ny: Int): (Int, Int) -> Int = { tx, ty -> if (tx in 0 until nx && ty in 0 until ny) tx + ty * nx else -1 }

        private fun floorDiv(v: Float, side: Int): Int = floor(v / side).toInt()
        /** The last pixel a half-open edge [v] covers starts below v: an edge on a tile border does not touch the next tile. */
        private fun ceilBelow(v: Float): Float { val f = floor(v); return if (f == v) v - 1f else f }
    }
}
