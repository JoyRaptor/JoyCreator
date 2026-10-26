package cc.joycreator.joybrush.androidkit.gl.lines

import cc.joycreator.joybrush.androidkit.lines.LinePlane
import cc.joycreator.joybrush.core.paint.UndoLog
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.CelComposer

/**
 * Immutable preparation for an independent copy of one entire editable plane. The engine must
 * establish that the destination is a fresh frame cel with the same ownership rectangle; this
 * must never be used for a partial Hold/Unhold copy into the shared canvas.
 *
 * Capturing does not allocate textures or mutate NativeLines. The texture count lets the board
 * transaction reserve base/slab copies in addition to its ordinary visible pixel copies before
 * starting any GPU work. Known GPU-widened footprints are kept separately for publication.
 */
internal class PreparedLinePlaneCopy private constructor(
    val source: LinePlane,
    val destination: LinePlane,
    private val records: List<StrokeRecord>,
    private val bases: Map<Long, Int>,
    private val slabs: List<Slab>,
    val footprints: Map<Pair<LinePlane, String>, Set<Long>>,
) {
    private data class Slab(val seq: Long, val op: String, val tiles: Map<Long, Int>)

    val textureCount: Int = bases.size + slabs.sumOf { it.tiles.size }

    /**
     * Copies prepared textures and constructs additions for the SAME UndoLog.Step as the board
     * document/pixel changes. The caller owns every texture returned by [copyTexture], including
     * ones allocated before an exception, and must release them on failure before publication.
     * Use [footprints] in LineItems.apply when publishing; NativeLines retains the extra footprint
     * information for undo/redo after that first publication.
     */
    fun materialize(copyTexture: (Int) -> Int): LineItems.Changes {
        val outputSlabs = ArrayList<UndoLog.SlabChange<Int>>(textureCount)
        val outputLines = records.map { record ->
            UndoLog.LineChange(destination.layerId, destination.celId, record.id,
                null, CelComposer.Line(record.seq, record))
        }
        for ((key, texture) in bases) {
            outputSlabs += UndoLog.SlabChange(destination.layerId, destination.celId, key, 0L,
                null, UndoLog.SlabValue(copyTexture(texture), "normal"))
        }
        for (slab in slabs) for ((key, texture) in slab.tiles) {
            outputSlabs += UndoLog.SlabChange(destination.layerId, destination.celId, key, slab.seq,
                null, UndoLog.SlabValue(copyTexture(texture), slab.op))
        }
        return LineItems.Changes(outputSlabs, outputLines)
    }

    companion object {
        fun capture(lines: NativeLines, source: LinePlane, destination: LinePlane): PreparedLinePlaneCopy {
            require(source != destination) { "An editable frame copy needs an independent destination" }
            require(!lines.hasItems(destination) && lines.bases(destination).isEmpty()) {
                "An editable frame copy cannot overwrite an existing plane"
            }
            val records = lines.lines(source).toList()
            val footprints = records.associate { record ->
                (destination to record.id) to lines.footprint(source, record.id).toSet()
            }
            val bases = lines.bases(source).toSortedMap()
            val slabs = lines.slabSeqs(source).map { seq ->
                val slab = checkNotNull(lines.slab(source, seq)) { "A prepared source slab disappeared" }
                Slab(seq, slab.op, slab.tiles.toSortedMap())
            }
            return PreparedLinePlaneCopy(source, destination, records, bases, slabs, footprints)
        }
    }
}
