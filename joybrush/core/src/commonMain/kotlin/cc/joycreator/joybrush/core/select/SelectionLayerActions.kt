package cc.joycreator.joybrush.core.select

import cc.joycreator.joybrush.core.doc.BlendMode
import cc.joycreator.joybrush.core.layers.LayerStack
import cc.joycreator.joybrush.core.stroke.LineAddress

/** D21: layer-menu scopes, separate from the view and GPU transaction. */
object SelectionLayerActions {
    fun bakeScope(layerId: String, selected: Set<LineAddress>, available: Set<LineAddress>): Set<LineAddress> {
        val here = available.filterTo(LinkedHashSet()) { it.layerId == layerId }
        val chosen = selected.intersect(here)
        return chosen.ifEmpty { here }
    }

    sealed interface Lower {
        data class Refused(val sentence: String) : Lower
        data class Plan(val targetId: String, val merged: List<String>, val discarded: List<String>, val after: LayerStack) : Lower
    }

    fun flattenLower(stack: LayerStack, layerId: String, hasFrames: (String) -> Boolean = { false }): Lower {
        val at = stack.indexOf(layerId)
        if (at <= 0) return Lower.Refused("There are no layers below this one to flatten.")
        val prefix = stack.layers.take(at + 1)
        if (!prefix.last().visible) return Lower.Refused("Show this layer before flattening it with the layers below.")
        prefix.firstOrNull { it.locked }?.let { return Lower.Refused("\"${it.name}\" is locked. Unlock it to flatten.") }
        if (stack.layers.getOrNull(at + 1)?.clip == true)
            return Lower.Refused("Unclip the layer above before flattening the layers below it.")
        if (prefix.any { hasFrames(it.id) }) return Lower.Refused("Flattening layers that have animation frames is coming next.")
        val visible = prefix.filter { it.visible }
        val target = visible.first()
        val kept = target.copy(opacity = 1f, blend = BlendMode.NORMAL, hasMask = false, clip = false)
        val active = if (prefix.any { it.id == stack.activeId }) target.id else stack.activeId
        return Lower.Plan(target.id, visible.map { it.id }, prefix.filterNot { it.visible }.map { it.id },
            LayerStack(listOf(kept) + stack.layers.drop(at + 1), active))
    }
}
