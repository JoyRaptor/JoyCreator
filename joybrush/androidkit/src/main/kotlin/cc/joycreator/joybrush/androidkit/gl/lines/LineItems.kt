package cc.joycreator.joybrush.androidkit.gl.lines

import cc.joycreator.joybrush.androidkit.lines.LinePlane
import cc.joycreator.joybrush.core.paint.UndoLog
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.CelComposer

/**
 * Editable lines v1.2: the step changes that move a plane's items (lines, bases, slabs) as a whole. Pure bookkeeping, no GL:
 * textures are plain Ints, copied through a function the engine supplies. An undo step that carries these owns the
 * textures it took, and undo puts the plane back exactly.
 */
internal object LineItems {

    class Changes(val slabs: List<UndoLog.SlabChange<Int>>, val lines: List<UndoLog.LineChange>) {
        val isEmpty: Boolean get() = slabs.isEmpty() && lines.isEmpty()
        operator fun plus(o: Changes) = Changes(slabs + o.slabs, lines + o.lines)

        companion object { val NONE = Changes(emptyList(), emptyList()) }
    }

    /**
     * Apply a step's item changes to [nl], forward (redo, or the step as made) or back (undo). Removals go first, so a
     * baked line's slab can take the seq of the line that goes in the same step. [planeOf] says which plane a change names.
     * [footprints] hands a line the tiles it is known to cover (a fresh line's estimate plus what the GPU drew); a line
     * without an entry gets the estimate plus whatever was learned of it before.
     *
     * ALL OR NOTHING: if any change is refused (a seq clash, a bad operator), every change already made is taken back in
     * reverse order before the exception goes on, so the plane is exactly as it was.
     */
    fun apply(nl: NativeLines, slabs: List<UndoLog.SlabChange<Int>>, lines: List<UndoLog.LineChange>,
              forward: Boolean, planeOf: (layerId: String, celId: String?) -> LinePlane,
              footprints: Map<Pair<LinePlane, String>, Set<Long>> = emptyMap()) {
        val inverses = ArrayList<() -> Unit>()
        fun lineTarget(c: UndoLog.LineChange) = if (forward) c.after else c.before
        fun slabTarget(c: UndoLog.SlabChange<Int>) = if (forward) c.after else c.before
        fun setLine(plane: LinePlane, id: String, record: StrokeRecord?) {
            val previous = nl.record(plane, id)
            val previousFootprint = nl.footprint(plane, id)
            nl.put(plane, id, record, if (record == null) null else footprints[plane to id])
            inverses += { nl.put(plane, id, previous, if (previous == null) null else previousFootprint) }
        }
        fun setSlabTile(c: UndoLog.SlabChange<Int>, value: UndoLog.SlabValue<Int>?) {
            val plane = planeOf(c.layerId, c.celId)
            if (c.seq == 0L) {
                val previous = nl.base(plane, c.key)
                nl.setBase(plane, c.key, value?.texture)
                inverses += { nl.setBase(plane, c.key, previous) }
            } else {
                val previous = nl.slab(plane, c.seq)
                val previousTexture = previous?.tiles?.get(c.key)
                nl.setSlab(plane, c.seq, c.key, value?.texture, value?.op ?: "normal")
                inverses += { nl.setSlab(plane, c.seq, c.key, previousTexture, previous?.op ?: "normal") }
            }
        }
        try {
            for (c in lines) if (lineTarget(c) == null) setLine(planeOf(c.layerId, c.celId), c.id, null)
            for (c in slabs) if (slabTarget(c) == null) setSlabTile(c, null)
            for (c in slabs) slabTarget(c)?.let { setSlabTile(c, it) }
            for (c in lines) lineTarget(c)?.let { setLine(planeOf(c.layerId, c.celId), c.id, it.record) }
        } catch (e: Throwable) {
            for (undo in inverses.asReversed()) {
                try { undo() } catch (secondary: Throwable) { if (secondary !== e) e.addSuppressed(secondary) }
            }
            throw e
        }
    }

    /**
     * Everything the plane holds (its records, its bases and its slabs) as removals: [apply]ing them empties the plane, and
     * undoing them puts it back. Nothing is applied here. [cel] is the cel id the engine records the plane under.
     */
    fun removal(nl: NativeLines, plane: LinePlane, layerId: String, cel: String?): Changes {
        val slabs = ArrayList<UndoLog.SlabChange<Int>>()
        val lines = ArrayList<UndoLog.LineChange>()
        for (r in nl.lines(plane)) lines += UndoLog.LineChange(layerId, cel, r.id, CelComposer.Line(r.seq, r), null)
        for ((key, tex) in nl.bases(plane).toSortedMap()) {
            slabs += UndoLog.SlabChange(layerId, cel, key, 0L, UndoLog.SlabValue(tex, "normal"), null)
        }
        for (seq in nl.slabSeqs(plane)) {
            val slab = nl.slab(plane, seq) ?: continue
            for ((key, tex) in slab.tiles.toSortedMap()) {
                slabs += UndoLog.SlabChange(layerId, cel, key, seq, UndoLog.SlabValue(tex, slab.op), null)
            }
        }
        return Changes(slabs, lines)
    }

    /**
     * The same items on another plane, as additions: the same records and seqs, and a texture COPY (through [copyTexture])
     * of every base and slab tile, so the two planes never share a texture.
     */
    fun copyOf(nl: NativeLines, from: LinePlane, toLayerId: String, toCel: String?, copyTexture: (Int) -> Int): Changes {
        val slabs = ArrayList<UndoLog.SlabChange<Int>>()
        val lines = ArrayList<UndoLog.LineChange>()
        for (r in nl.lines(from)) lines += UndoLog.LineChange(toLayerId, toCel, r.id, null, CelComposer.Line(r.seq, r))
        for ((key, tex) in nl.bases(from).toSortedMap()) {
            slabs += UndoLog.SlabChange(toLayerId, toCel, key, 0L, null, UndoLog.SlabValue(copyTexture(tex), "normal"))
        }
        for (seq in nl.slabSeqs(from)) {
            val slab = nl.slab(from, seq) ?: continue
            for ((key, tex) in slab.tiles.toSortedMap()) {
                slabs += UndoLog.SlabChange(toLayerId, toCel, key, seq, null, UndoLog.SlabValue(copyTexture(tex), slab.op))
            }
        }
        return Changes(slabs, lines)
    }
}
