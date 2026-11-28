package cc.joycreator.joybrush.androidkit.gl.lines

import cc.joycreator.joybrush.androidkit.lines.LineCutPlan
import cc.joycreator.joybrush.androidkit.lines.LinePlane
import cc.joycreator.joybrush.core.paint.UndoLog
import cc.joycreator.joybrush.core.vector.CelComposer

/**
 * The room a cut makes (Concepts parity 4a, 11-28), as undo changes: every line and pixel slab of [plane] that [plan]
 * moves, from its old seq to its new one. Nothing is applied here ([LineItems.apply] does that, forward for the edit and
 * back for its undo). A slab goes to its new place as a [copy]: history keeps the texture it had (UndoLog.SlabChange), the
 * live slab owns the copy; there is one change per (seq, tile). Pure apart from [copy], so it is tested on a plain JVM.
 */
internal object LineCutMoves {

    class Changes(val lines: List<UndoLog.LineChange>, val slabs: List<UndoLog.SlabChange<Int>>,
                  val footprints: Map<Pair<LinePlane, String>, Set<Long>>)

    fun of(nl: NativeLines, plane: LinePlane, layerId: String, cel: String?, plan: LineCutPlan.Plan, copy: (Int) -> Int): Changes {
        val lines = ArrayList<UndoLog.LineChange>()
        val footprints = HashMap<Pair<LinePlane, String>, Set<Long>>()
        for (l in nl.lines(plane)) {
            val to = plan.moves[l.seq] ?: continue
            lines += UndoLog.LineChange(layerId, cel, l.id, CelComposer.Line(l.seq, l), CelComposer.Line(to, l.copy(seq = to)))
            footprints[plane to l.id] = nl.footprint(plane, l.id)
        }
        val before = HashMap<Pair<Long, Long>, UndoLog.SlabValue<Int>>()
        val after = HashMap<Pair<Long, Long>, UndoLog.SlabValue<Int>>()
        for (q in nl.slabSeqs(plane)) {
            val to = plan.moves[q] ?: continue
            val slab = nl.slab(plane, q) ?: continue
            for ((key, tex) in slab.tiles) {
                before[q to key] = UndoLog.SlabValue(tex, slab.op)
                after[to to key] = UndoLog.SlabValue(copy(tex), slab.op)
            }
        }
        val slabs = (before.keys + after.keys).toSortedSet(compareBy({ it.first }, { it.second }))
            .map { at -> UndoLog.SlabChange(layerId, cel, at.second, at.first, before[at], after[at]) }
        return Changes(lines, slabs, footprints)
    }
}
