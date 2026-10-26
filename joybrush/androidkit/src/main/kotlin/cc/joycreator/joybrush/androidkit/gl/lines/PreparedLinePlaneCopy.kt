package cc.joycreator.joybrush.androidkit.gl.lines

import cc.joycreator.joybrush.androidkit.lines.LinePlane
import cc.joycreator.joybrush.core.paint.UndoLog
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.CelComposer
import java.util.Collections

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
    footprints: Map<Pair<LinePlane, String>, Set<Long>>,
) {
    private data class Slab(val seq: Long, val op: String, val tiles: Map<Long, Int>)

    // Kotlin read-only collection types alone do not prevent mutation through JVM casts.
    val footprints: Map<Pair<LinePlane, String>, Set<Long>> = Collections.unmodifiableMap(
        footprints.mapValues { (_, keys) -> Collections.unmodifiableSet(HashSet(keys)) },
    )

    val textureCount: Int = bases.size + slabs.sumOf { it.tiles.size }

    /**
     * Borrowed source handle for EACH new base/slab texture that materialize will copy, in the
     * same order. Duplicates deliberately remain: each entry needs its own destination texture.
     * The caller deduplicates the retained originals together with visible-raster source handles
     * when reserving the shared transaction allowance. This immutable snapshot grants no lease;
     * the engine must retain the original handles until copying completes.
     */
    val borrowedSourceTextures: List<Int> = Collections.unmodifiableList(
        ArrayList<Int>(textureCount).apply {
            addAll(bases.values)
            for (slab in slabs) addAll(slab.tiles.values)
        },
    )

    /**
     * Copies prepared textures and constructs additions for the SAME UndoLog.Step as the board
     * document/pixel changes. The caller owns every texture returned by [copyTexture], including
     * ones allocated before an exception, and must release them on failure before publication.
     * Use [footprints] in LineItems.apply when publishing; NativeLines retains the extra footprint
     * information for undo/redo after that first publication. Source handles are borrowed and
     * remain valid only while the caller retains the source texture ownership through copying.
     */
    fun materialize(copyTexture: (Int) -> Int): LineItems.Changes =
        materializeWithClippedBases(copyTexture) { _, texture -> copyTexture(texture) }

    /** Bases have no later line-domain mask: the engine must copy their physical owned rect. */
    fun materializeWithClippedBases(copyTexture: (Int) -> Int, copyBase: (Long, Int) -> Int): LineItems.Changes {
        val outputSlabs = ArrayList<UndoLog.SlabChange<Int>>(textureCount)
        val outputLines = records.map { record ->
            UndoLog.LineChange(destination.layerId, destination.celId, record.id,
                null, CelComposer.Line(record.seq, record))
        }
        for ((key, texture) in bases) {
            outputSlabs += UndoLog.SlabChange(destination.layerId, destination.celId, key, 0L,
                null, UndoLog.SlabValue(copyBase(key, texture), "normal"))
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
            // StrokeRecord's PenSample values and capture/wash frames are immutable, but its
            // caller-supplied samples List need not be. Freeze that list as at the Select copy
            // boundary, so later source-list edits cannot change the prepared recipe.
            val records = lines.lines(source).map { record ->
                record.copy(samples = Collections.unmodifiableList(ArrayList(record.samples)))
            }
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
