package cc.joycreator.joybrush.core.vector

import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.paint.Tiles
import cc.joycreator.joybrush.core.render.RegionRenderer
import cc.joycreator.joybrush.core.stroke.StrokeRecord

/**
 * Editing one line among many without re-drawing the others (VECTOR_RENDER_DESIGN §1, "the sandwich cache").
 *
 * A tile's look is its items applied in `seq` order ([CelComposer.look]). While one line at `seq = q` is dragged, the
 * items below q and the items above q do not change. So at grab time each tile the line touches is split into:
 * - **below**: everything before q, composited once, kept in float;
 * - **above**: everything after q, kept not as pixels but as the one per-pixel MAP those items apply to whatever lies
 *   under them.
 *
 * Every drag frame is then: below → the moved line's own buffer with its operator → the above map. One line is drawn,
 * not a thousand.
 *
 * ## The map
 *
 * Each operator a line or slab can carry is, per pixel and for a fixed source `s` (premultiplied), a function of the
 * destination `D` of the form
 *
 *     D′ = K ∘ D + u · αD + c          (K, u, c: four numbers each; ∘ is per channel)
 *
 * worked from [cc.joycreator.joybrush.core.render.Blend]'s own formula `co = sa(1−da)Cs + sa·da·B + (1−sa)da·Cb`:
 *
 * | op | K (rgb, a) | u (rgb, a) | c |
 * |---|---|---|---|
 * | normal | 1−sa, 1−sa | 0, 0 | s |
 * | erase (destination-out, all four channels) | 1−sa, 1−sa | 0, 0 | 0 |
 * | behind (`Blend(NORMAL, d, s)` = destination-over) | 1, 1 | −s_rgb, −sa | s |
 * | multiply (`B = Cs·Cb`) | s_c + 1 − sa, 1−sa | −s_c, 0 | s |
 * | screen (`B = Cs + Cb − Cs·Cb`) | 1 − s_c, 1−sa | 0, 0 | s |
 *
 * Maps of this form compose into the same form ([Above.push]), so any run of these five operators collapses into one
 * K, u and c per pixel: 12 floats, whatever the number of items. The other blend modes (darken, overlay, hue, …) are
 * not affine in `D`. A tile with one of them above the line answers null from [build], and the caller re-renders it
 * fully, which is always correct.
 *
 * ## Exactness
 *
 * The map is exact in real arithmetic. In float it rounds differently from applying the items one at a time, so a
 * drag frame can differ from [CelComposer.look] by one step of 255 in a few bytes. That is a preview: when the gesture
 * ends, the caller re-renders the touched tiles with [CelComposer.look] (D3), and that is what is kept.
 */
object LineSandwich {

    /** Whether [op] collapses into the map; anything else makes [build] answer null. */
    fun supports(op: String): Boolean = op in AFFINE

    private val AFFINE = setOf("normal", "erase", "behind", "multiply", "screen")

    /** The items above the line as one per-pixel map; starts as the identity. */
    class Above internal constructor() {
        internal val k = FloatArray(RegionRenderer.TILE_BYTES) { 1f }
        internal val u = FloatArray(RegionRenderer.TILE_BYTES)
        internal val c = FloatArray(RegionRenderer.TILE_BYTES)

        /**
         * Composes the operator [op] with source [bytes] AFTER the map so far: `this := op ∘ this`.
         *
         * For a new map (K2, u2, c2) after (K1, u1, c1), the intermediate alpha is `K1α·αD + u1α·αD + c1α`, so
         *
         *     K = K2∘K1     u = K2∘u1 + u2·(K1α + u1α)     c = K2∘c1 + u2·c1α + c2
         */
        internal fun push(op: String, bytes: ByteArray) {
            require(supports(op)) { "operator \"$op\" does not collapse into a map" }
            val k2 = FloatArray(4)
            val u2 = FloatArray(4)
            val c2 = FloatArray(4)
            var i = 0
            while (i < bytes.size) {
                // A transparent source leaves the destination as it was ([InkTiles.applyOp] skips it too).
                if (bytes[i + 3] == 0.toByte()) { i += 4; continue }
                val s0 = (bytes[i].toInt() and 255) / 255f
                val s1 = (bytes[i + 1].toInt() and 255) / 255f
                val s2 = (bytes[i + 2].toInt() and 255) / 255f
                val sa = (bytes[i + 3].toInt() and 255) / 255f
                opMap(op, s0, s1, s2, sa, k2, u2, c2)
                val k1a = k[i + 3]
                val u1a = u[i + 3]
                val c1a = c[i + 3]
                for (ch in 0..3) {
                    val j = i + ch
                    val k1 = k[j]
                    val u1 = u[j]
                    val c1 = c[j]
                    k[j] = k2[ch] * k1
                    u[j] = k2[ch] * u1 + u2[ch] * (k1a + u1a)
                    c[j] = k2[ch] * c1 + u2[ch] * c1a + c2[ch]
                }
                i += 4
            }
        }

        /** Applies the map to the float tile [px] in place. */
        internal fun applyTo(px: FloatArray) {
            var i = 0
            while (i < px.size) {
                val a = px[i + 3]
                for (ch in 0..3) {
                    val j = i + ch
                    px[j] = k[j] * px[j] + u[j] * a + c[j]
                }
                i += 4
            }
        }
    }

    /** One tile split around one line, built once per gesture. */
    class Tile internal constructor(val key: Long, val seq: Long, internal val below: FloatArray, internal val above: Above)

    /**
     * Splits tile [key] around the line at [seq]: everything before it composited into [Tile.below], everything after
     * it collapsed into [Tile.above]. The line itself is in neither.
     *
     * @return null when an item above the line has an operator that does not collapse ([supports]); the caller then
     *   re-renders the tile with [CelComposer.look] on every frame.
     */
    fun build(d: CelComposer.Drawn, key: Long, seq: Long): Tile? {
        val tx = Tiles.tx(key)
        val ty = Tiles.ty(key)
        val below = FloatArray(RegionRenderer.TILE_BYTES)
        val above = Above()
        val slabs = d.items.slabs[key].orEmpty()
        val lines = CelComposer.linesOn(d, key).filter { it.seq != seq }
        var i = 0
        var j = 0
        while (i < slabs.size || j < lines.size) {
            val takeSlab = j >= lines.size || (i < slabs.size && slabs[i].seq < lines[j].seq)
            val itemSeq: Long
            val op: String
            val bytes: ByteArray?
            if (takeSlab) {
                val slab = slabs[i++]
                itemSeq = slab.seq; op = slab.op; bytes = slab.bytes
            } else {
                val mark = d.marks.getValue(lines[j].seq)
                itemSeq = lines[j++].seq; op = mark.brush.blend; bytes = InkTiles.buffer(mark, tx, ty)
            }
            if (bytes == null) continue
            if (itemSeq < seq) {
                InkTiles.applyOp(op, bytes, below)
            } else {
                if (!supports(op)) return null
                above.push(op, bytes)
            }
        }
        return Tile(key, seq, below, above)
    }

    /**
     * One drag frame: the tile's look with the line redrawn as [record] (moved, re-weighted, recoloured or re-brushed
     * with [brush]). Null when nothing on the tile is visible, as with [CelComposer.look].
     */
    fun look(tile: Tile, record: StrokeRecord, brush: BrushPreset?): ByteArray? = look(tile, record, brush, null)

    fun look(tile: Tile, record: StrokeRecord, brush: BrushPreset?,
        sampledLookup: ((String) -> cc.joycreator.joybrush.core.brush.SampledTipSet?)?): ByteArray? {
        val prepared = InkTiles.prepare(listOf(record), { brush }, sampledLookup)
        if (brush?.tip?.sampled != null || sampledLookup != null) {
            prepared.refusals.firstOrNull()?.let { throw InkReplayException(it.reason) }
        }
        val px = tile.below.copyOf()
        val mark = prepared.marks.firstOrNull()
        if (mark != null) {
            InkTiles.buffer(mark, Tiles.tx(tile.key), Tiles.ty(tile.key))?.let { InkTiles.applyOp(mark.brush.blend, it, px) }
        }
        tile.above.applyTo(px)
        return InkTiles.quantise(px)
    }

    /** The per-operator map `(K, u, c)` for one source pixel; see the table on [LineSandwich]. */
    private fun opMap(op: String, s0: Float, s1: Float, s2: Float, sa: Float, k: FloatArray, u: FloatArray, c: FloatArray) {
        val inv = 1f - sa
        when (op) {
            "normal" -> { k.fill(inv); u.fill(0f); set(c, s0, s1, s2, sa) }
            "erase" -> { k.fill(inv); u.fill(0f); c.fill(0f) }
            "behind" -> { k.fill(1f); set(u, -s0, -s1, -s2, -sa); set(c, s0, s1, s2, sa) }
            "multiply" -> { set(k, s0 + inv, s1 + inv, s2 + inv, inv); set(u, -s0, -s1, -s2, 0f); set(c, s0, s1, s2, sa) }
            "screen" -> { set(k, 1f - s0, 1f - s1, 1f - s2, inv); u.fill(0f); set(c, s0, s1, s2, sa) }
            else -> error("operator \"$op\" does not collapse into a map")
        }
    }

    private fun set(v: FloatArray, a: Float, b: Float, c: Float, d: Float) {
        v[0] = a; v[1] = b; v[2] = c; v[3] = d
    }
}
