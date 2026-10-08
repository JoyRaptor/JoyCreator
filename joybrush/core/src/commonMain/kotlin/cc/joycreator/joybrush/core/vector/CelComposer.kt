package cc.joycreator.joybrush.core.vector

import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.ENGINE_FILL
import cc.joycreator.joybrush.core.doc.TILE_SIZE
import cc.joycreator.joybrush.core.paint.TipMath
import cc.joycreator.joybrush.core.paint.TipShape
import cc.joycreator.joybrush.core.paint.Tiles
import cc.joycreator.joybrush.core.paint.tipShape
import cc.joycreator.joybrush.core.render.RegionRenderer
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import kotlin.math.floor

/**
 * JB-5.20b: one cel's pixels and editable lines, in the order they were made (R51; JB-5.20 D1–D3, D8, D8a, D12).
 *
 * Pure: no GL, no undo, no document model. The engine (5.20d) keeps these same rules on the GPU; this is the reference
 * they are tested against, and what export (5.20f) draws with.
 *
 * - Every line and every slab has a `seq`, increasing in the order things were made (D1). Lines have distinct seqs;
 *   one event that opens slabs on several tiles (a stroke, a bake) gives them all its one seq.
 * - A **slab** is one tile of premultiplied pixels with an operator (D8a: `normal` for paint; a baked line keeps its own).
 *   A pixel write lands in the tile's top slab when that slab is normal and no line made after it reaches the tile;
 *   otherwise it starts a new slab (D2). So a cel with no lines has one slab per tile: today's tiles exactly.
 * - A tile's **look** is its slabs and the lines that reach it, applied in seq order in ONE float pass and rounded
 *   once (D3). Lines are drawn with [InkTiles]' own steps, so a baked line is bit-for-bit the line it was (D8a).
 */
object CelComposer {

    const val NORMAL = "normal"

    /** One tile of pixels at [seq], composited with [op] (`normal`, `erase`, `behind` or a blend mode's lower-case name). */
    class Slab(val seq: Long, val bytes: ByteArray, val op: String = NORMAL) {
        init {
            require(bytes.size == RegionRenderer.TILE_BYTES) { "a slab is one ${TILE_SIZE}px tile" }
            require(InkTiles.knowsOp(op)) { "slab $seq has an operator nothing composites: \"$op\"" }
        }
    }

    /** An editable line at [seq]. */
    class Line(val seq: Long, val record: StrokeRecord)

    /** A cel's content. [lines] sorted by seq; [slabs] per tile key, each sorted by seq; empty tiles dropped. Immutable. */
    class Items(lines: List<Line>, slabs: Map<Long, List<Slab>>) {
        val lines: List<Line> = lines.sortedBy { it.seq }
        val slabs: Map<Long, List<Slab>> = slabs.filterValues { it.isNotEmpty() }.mapValues { (_, v) -> v.sortedBy { it.seq } }

        init {
            val lineSeqs = this.lines.map { it.seq }
            require(lineSeqs.toSet().size == lineSeqs.size) { "two lines share a seq" }
            val live = lineSeqs.toSet()
            for ((key, list) in this.slabs) {
                require(list.map { it.seq }.toSet().size == list.size) { "two slabs share a seq on tile $key" }
                require(list.none { it.seq in live }) { "a slab on tile $key has the seq of a line that still exists" }
            }
        }

        /** The seq the next event takes. */
        val nextSeq: Long = maxOf(this.lines.maxOfOrNull { it.seq } ?: 0L, this.slabs.values.flatten().maxOfOrNull { it.seq } ?: 0L) + 1L

        companion object {
            val EMPTY = Items(emptyList(), emptyMap())
        }
    }

    /**
     * [items] with its lines replayed once, so many tiles can be composed without replaying again. Lines whose brush is
     * missing or cannot draw a line are in [refusals] and are left as they are by every operation here: never drawn,
     * never baked, never dropped.
     */
    class Drawn internal constructor(
        val items: Items,
        internal val marks: Map<Long, InkTiles.Mark>,
        val refusals: List<InkTiles.Refusal>,
    ) {
        /** The same lines over other slabs: no replay. */
        internal fun withSlabs(slabs: Map<Long, List<Slab>>) = Drawn(Items(items.lines, slabs), marks, refusals)
    }

    fun draw(items: Items, brushLookup: (String) -> BrushPreset?): Drawn {
        val prepared = InkTiles.prepare(items.lines.map { it.record }, brushLookup)
        val refused = prepared.refusals.map { it.index }.toSet()
        val marks = HashMap<Long, InkTiles.Mark>()
        var next = 0
        for ((index, line) in items.lines.withIndex()) {
            if (index in refused) continue
            marks[line.seq] = prepared.marks[next++]
        }
        return Drawn(items, marks, prepared.refusals)
    }

    /** The drawable lines that reach tile [key], in seq order. */
    fun linesOn(d: Drawn, key: Long): List<Line> =
        d.items.lines.filter { line -> d.marks[line.seq]?.let { InkTiles.reaches(it, Tiles.tx(key), Tiles.ty(key)) } == true }

    /** D3: the tile's look, or null when nothing on it is visible. */
    fun look(d: Drawn, key: Long): ByteArray? {
        val tx = Tiles.tx(key)
        val ty = Tiles.ty(key)
        InkTiles.origin(tx, ty)
        val px = FloatArray(RegionRenderer.TILE_BYTES)
        val slabs = d.items.slabs[key].orEmpty()
        val lines = linesOn(d, key)
        var i = 0
        var j = 0
        while (i < slabs.size || j < lines.size) {
            if (j >= lines.size || (i < slabs.size && slabs[i].seq < lines[j].seq)) {
                val slab = slabs[i++]
                InkTiles.applyOp(slab.op, slab.bytes, px)
            } else {
                val mark = d.marks.getValue(lines[j++].seq)
                InkTiles.buffer(mark, tx, ty)?.let { InkTiles.applyOp(mark.brush.blend, it, px) }
            }
        }
        return InkTiles.quantise(px)
    }

    /** Every tile that holds a slab or that a drawable line reaches. */
    fun tiles(d: Drawn): Set<Long> {
        val out = HashSet<Long>(d.items.slabs.keys)
        for (mark in d.marks.values) out.addAll(tilesReached(mark))
        return out
    }

    /** D2: the slab a pixel write to tile [key] goes into, or null when the write must start a new slab. */
    fun writableTop(d: Drawn, key: Long): Slab? {
        val top = d.items.slabs[key]?.lastOrNull() ?: return null
        if (top.op != NORMAL) return null
        return if (linesOn(d, key).any { it.seq > top.seq }) null else top
    }

    /**
     * A pixel write on tile [key] (D2). [write] is given the bytes it paints into (the top slab's, or null for a new
     * slab) and returns the result, or null for nothing. A new slab takes [seq], the writing event's own, which must be
     * later than everything already on the tile.
     */
    fun paint(d: Drawn, key: Long, seq: Long, write: (ByteArray?) -> ByteArray?): Drawn {
        val list = d.items.slabs[key].orEmpty()
        val top = writableTop(d, key)
        val next = if (top != null) {
            val bytes = write(top.bytes.copyOf())
            if (bytes == null) list - top else list.map { if (it === top) Slab(top.seq, bytes, NORMAL) else it }
        } else {
            require(list.none { it.seq >= seq } && linesOn(d, key).none { it.seq >= seq }) {
                "a new slab's seq $seq must come after everything on tile $key"
            }
            val bytes = write(null) ?: return d
            list + Slab(seq, bytes, NORMAL)
        }
        return d.withSlabs(d.items.slabs + (key to next))
    }

    /**
     * D8 "ignores lines" (smudge paint only, blur, the media brushes as pixel writes): [tool] is applied to each slab of
     * tile [key] on its own, so it never reads a line and can never copy one into pixels. [tool] returns the slab's new
     * bytes, or null to empty it.
     */
    fun eachSlab(d: Drawn, key: Long, tool: (Slab) -> ByteArray?): Drawn {
        val list = d.items.slabs[key] ?: return d
        val next = list.mapNotNull { slab -> tool(slab)?.let { Slab(slab.seq, it, slab.op) } }
        return d.withSlabs(d.items.slabs + (key to next))
    }

    /**
     * D8 "consumes lines" and D12 Rasterize Down: each line in [ids] becomes pixels WHOLE. On every tile it reaches, a
     * slab of its own stroke buffer with its own operator at its own seq (D8a), so every look is unchanged; then the line
     * is gone. A refused line is left alone (it is in [Drawn.refusals]).
     */
    fun bake(d: Drawn, ids: Set<String>): Drawn {
        val baking = d.items.lines.filter { it.record.id in ids && d.marks.containsKey(it.seq) }
        if (baking.isEmpty()) return d
        val slabs = d.items.slabs.mapValues { it.value.toMutableList() }.toMutableMap()
        for (line in baking) {
            val mark = d.marks.getValue(line.seq)
            for (key in tilesReached(mark)) {
                val bytes = InkTiles.buffer(mark, Tiles.tx(key), Tiles.ty(key)) ?: continue
                if (bytes.indices.step(4).none { bytes[it + 3] != 0.toByte() }) continue
                slabs.getOrPut(key) { ArrayList() }.add(Slab(line.seq, bytes, mark.brush.blend))
            }
        }
        val gone = baking.map { it.seq }.toSet()
        return Drawn(Items(d.items.lines.filter { it.seq !in gone }, slabs), d.marks - gone, d.refusals)
    }

    /**
     * Two neighbouring normal slabs on tile [key] with no line between them become one (the upper over the lower, at the
     * lower's seq). The look is unchanged to within one step of rounding: the merged slab is rounded once more.
     */
    fun compact(d: Drawn, key: Long): Drawn {
        val list = d.items.slabs[key] ?: return d
        if (list.size < 2) return d
        val lines = linesOn(d, key).map { it.seq }
        val out = ArrayList<Slab>()
        for (slab in list) {
            val below = out.lastOrNull()
            val mergeable = below != null && below.op == NORMAL && slab.op == NORMAL &&
                lines.none { it > below.seq && it < slab.seq }
            if (!mergeable) { out.add(slab); continue }
            val px = FloatArray(RegionRenderer.TILE_BYTES)
            InkTiles.applyOp(NORMAL, below!!.bytes, px)
            InkTiles.applyOp(NORMAL, slab.bytes, px)
            val merged = InkTiles.quantise(px)
            out.removeAt(out.lastIndex)
            if (merged != null) out.add(Slab(below.seq, merged, NORMAL))
        }
        return d.withSlabs(d.items.slabs + (key to out))
    }

    /**
     * Applies the line half of undo steps (JB-5.20 D9) to one cel: each change of [layerId]/[celId] replaces its line by
     * the `after` side ([forward], a redo or the first doing) or the `before` side (an undo). Seqs come with the lines, so
     * an undone delete returns to its own place in time order. The caller replays (via [draw]) only if lines changed.
     */
    fun applyLines(items: Items, changes: List<cc.joycreator.joybrush.core.paint.UndoLog.LineChange>, layerId: String,
                   celId: String?, forward: Boolean): Items {
        val mine = changes.filter { it.layerId == layerId && it.celId == celId }
        if (mine.isEmpty()) return items
        val out = items.lines.associateBy { it.record.id }.toMutableMap()
        for (c in if (forward) mine else mine.asReversed()) {
            out.remove(c.id)
            (if (forward) c.after else c.before)?.let { out[c.id] = it }
        }
        return Items(out.values.toList(), items.slabs)
    }

    /** The tiles [mark] can reach, from its dabs' reach (or its fill outline), checked tile by tile. */
    private fun tilesReached(mark: InkTiles.Mark): List<Long> {
        val p = mark.brush
        var minX = Float.POSITIVE_INFINITY; var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY
        if (p.engine == ENGINE_FILL) {
            for (pt in mark.outline) {
                minX = minOf(minX, pt.x.toFloat()); maxX = maxOf(maxX, pt.x.toFloat())
                minY = minOf(minY, pt.y.toFloat()); maxY = maxOf(maxY, pt.y.toFloat())
            }
        } else {
            val tip = TipShape(p.tip.aspect, p.tip.corner, p.tip.taper, p.tip.hardness.base, p.tip.minPx)
            for (dab in mark.dabs) {
                val e = TipMath.extent(dab.radius, dab.tipShape(tip).anchor) + 1f
                minX = minOf(minX, dab.x - e); maxX = maxOf(maxX, dab.x + e)
                minY = minOf(minY, dab.y - e); maxY = maxOf(maxY, dab.y + e)
            }
        }
        if (!minX.isFinite() || !minY.isFinite() || !maxX.isFinite() || !maxY.isFinite()) return emptyList()
        val out = ArrayList<Long>()
        for (ty in floor(minY / TILE_SIZE).toInt()..floor(maxY / TILE_SIZE).toInt()) {
            for (tx in floor(minX / TILE_SIZE).toInt()..floor(maxX / TILE_SIZE).toInt()) {
                if (InkTiles.reaches(mark, tx, ty)) out.add(Tiles.key(tx, ty))
            }
        }
        return out
    }
}
