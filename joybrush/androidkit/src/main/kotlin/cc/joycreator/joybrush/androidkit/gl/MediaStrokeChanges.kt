package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.core.paint.UndoLog

/**
 * What a media stroke's change list does when a stale payload is dropped in the middle of the stroke
 * ([GlPaintEngine.dropStalePayload]). Pure (no GL), so the rule is tested on the JVM.
 *
 * The rule: **one change per tile in a stroke**. Undo puts every change's `before` back in list order, so two changes on
 * one tile (`A -> T`, then `T -> null`) would leave the tile on T, a texture the step also owns as an `after`: the picture
 * is wrong, and the texture is let go twice (once as the step's after, once as the live tile). That was the Note 9's
 * intermittent "duplicate after texture ownership" at the end of a palette-knife stroke (2026-10-20): a tile the stroke
 * itself had just made, found stale at the next window load.
 */
internal object MediaStrokeChanges {
    /**
     * Adds [drops] (each `before` = the tile's texture, `after` = null) to [stroke]. A drop of a texture the stroke itself made
     * (some change already has it as its `after`) does not add a change: it ends that change, so the stroke's change for the
     * tile goes back to what the tile was before the stroke (or away, if it was nothing). Returns the textures no change names
     * any more: the caller gives them back to the pool.
     */
    fun addDrops(stroke: MutableList<UndoLog.TileChange<Int>>, drops: List<UndoLog.TileChange<Int>>): List<Int> {
        val freed = ArrayList<Int>()
        for (d in drops) {
            val texture = d.before
            val at = if (texture == null) -1 else stroke.indexOfFirst {
                it.after == texture && it.layerId == d.layerId && it.key == d.key && it.celId == d.celId
            }
            if (at < 0) { stroke.add(d); continue }
            val made = stroke[at]
            if (made.before == null) stroke.removeAt(at)
            else stroke[at] = UndoLog.TileChange(made.layerId, made.key, made.before, null, made.celId)
            freed += texture!!
        }
        return freed
    }
}
