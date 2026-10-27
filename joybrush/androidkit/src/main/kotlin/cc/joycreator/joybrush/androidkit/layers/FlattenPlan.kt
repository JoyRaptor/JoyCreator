package cc.joycreator.joybrush.androidkit.layers

import cc.joycreator.joybrush.core.doc.BlendMode
import cc.joycreator.joybrush.core.layers.LayerStack

/**
 * LAYER_OPS L6 (tasks/joybrush/specs/LAYER_OPS_20261018.md §3): Flatten freezes every VISIBLE layer into ONE layer of plain
 * pixels (lines, slabs and pencil/watercolour/oil state baked), the picture unchanged, ONE undo step. The bottom-most visible
 * layer keeps its place and name and becomes the result, with Normal blend, 100% opacity, no mask and no clip (their
 * effect is in its pixels). Hidden layers are kept or discarded as the person chose. Pure: the canvas asks, the engine follows.
 */
object FlattenPlan {

    /** What only the engine knows about a layer. */
    data class Facts(val hasFrames: Boolean = false)

    sealed class Result {
        data class Refused(val sentence: String) : Result()
        /**
         * [targetId]: the layer that becomes the flattened one. [merged]: the visible layers, bottom to top (target first).
         * [discarded]: hidden layers that go. [after]: the stack once done.
         */
        data class Flatten(val targetId: String, val merged: List<String>, val discarded: List<String>, val after: LayerStack) : Result()
    }

    fun decide(stack: LayerStack, discardHidden: Boolean, facts: (String) -> Facts): Result {
        val visible = stack.layers.filter { it.visible }
        if (visible.size < 2 && !(discardHidden && stack.layers.size > visible.size && visible.isNotEmpty()))
            return Result.Refused(if (visible.isEmpty()) "Nothing shows, so there is nothing to flatten." else "Only one layer shows, so there is nothing to flatten.")
        visible.firstOrNull { it.locked }?.let { return Result.Refused("\"${it.name}\" is locked. Unlock it to flatten.") }
        val target = visible.first()
        val discarded = if (discardHidden) stack.layers.filter { !it.visible }.map { it.id } else emptyList()
        val gone = (visible.drop(1).map { it.id } + discarded).toSet()
        val kept = stack.layers.filter { it.id !in gone }.map {
            if (it.id == target.id) it.copy(blend = BlendMode.NORMAL, opacity = 1f, hasMask = false, clip = false) else it
        }
        val active = if (stack.activeId in gone || stack.activeId == target.id) target.id else stack.activeId
        return Result.Flatten(target.id, visible.map { it.id }, discarded, LayerStack(kept, active))
    }
}
