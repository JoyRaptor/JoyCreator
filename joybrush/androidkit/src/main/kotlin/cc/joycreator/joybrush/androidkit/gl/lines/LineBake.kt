package cc.joycreator.joybrush.androidkit.gl.lines

import cc.joycreator.joybrush.androidkit.lines.LinePlane
import cc.joycreator.joybrush.core.paint.UndoLog
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.CelComposer

/**
 * Editable lines v1.2 H3, Rasterize Down for one plane: each chosen line becomes a pixel SLAB at its own seq (its rendered
 * unit tiles, applied with the operator its brush says) and the record goes. The plane's tiles do not change at all: the
 * slab composes to exactly the pixels the line did, in the same place in time order. Pure bookkeeping, no GL.
 */
internal object LineBake {

    /** A baked line's rendered unit: how it applies, and its texture per tile. The slab takes these textures over. */
    class Unit(val op: String, val tiles: Map<Long, Int>)

    /**
     * The step changes for baking [baked] (records of [plane], each with its unit in [units] by id). Nothing is applied.
     * A base is dropped on a tile no item crosses any more (no other line, no slab, none of the new slabs).
     */
    fun plan(nl: NativeLines, plane: LinePlane, layerId: String, cel: String?,
             baked: List<StrokeRecord>, units: Map<String, Unit>): LineItems.Changes {
        val bakedIds = baked.mapTo(HashSet()) { it.id }
        val lines = baked.map { UndoLog.LineChange(layerId, cel, it.id, CelComposer.Line(it.seq, it), null) }
        val slabs = ArrayList<UndoLog.SlabChange<Int>>()
        val newKeys = HashSet<Long>()
        for (r in baked) {
            val unit = requireNotNull(units[r.id]) { "No rendered unit for line \"${r.id}\"" }
            for ((key, tex) in unit.tiles.toSortedMap()) {
                slabs += UndoLog.SlabChange(layerId, cel, key, r.seq, null, UndoLog.SlabValue(tex, unit.op))
                newKeys += key
            }
        }
        // The base lives while an item crosses the tile. Where only the baked lines did and no new slab does, it goes.
        val reached = baked.flatMapTo(LinkedHashSet()) { nl.footprint(plane, it.id) }.filter { it !in newKeys }.sorted()
        for (key in reached) {
            val stillCrossed = nl.linesAt(plane, key).any { it.id !in bakedIds } || nl.slabsAt(plane, key).isNotEmpty()
            if (stillCrossed) continue
            nl.base(plane, key)?.let { slabs += UndoLog.SlabChange(layerId, cel, key, 0L, UndoLog.SlabValue(it, "normal"), null) }
        }
        return LineItems.Changes(slabs, lines)
    }
}
