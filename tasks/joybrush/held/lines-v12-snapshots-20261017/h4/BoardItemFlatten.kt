package cc.joycreator.joybrush.androidkit.gl.lines

import cc.joycreator.joybrush.androidkit.lines.LinePlane
import cc.joycreator.joybrush.core.doc.RegionChange

/**
 * Editable lines v1.2 H4: which planes' items a board change must take away. A board operation rewrites the pixels of some
 * planes behind the items' back (a frame's region cleared, a canvas region moved into a frame, a frame copied over, a
 * frame dropped), so "tile = base + items" would stop being true there. Those planes lose their items in the SAME undo
 * step; their tiles stay as they are, so the lines become pixels. A plane whose cel the change DROPS loses them with the
 * cel's pixels. A layer whose region layout changes (frames added, moved or removed) loses the items of every plane, since
 * a plane's items are clipped to the regions it owns. Pure: no engine.
 */
internal object BoardItemFlatten {

    /** [dropped]: the cel goes with its pixels. [flattened]: the pixels stay and the items become them. */
    class Plan(val dropped: Set<LinePlane>, val flattened: Set<LinePlane>) {
        val all: Set<LinePlane> get() = dropped + flattened
        val isEmpty: Boolean get() = dropped.isEmpty() && flattened.isEmpty()
    }

    fun plan(itemPlanes: Set<LinePlane>, change: RegionChange, layerLayoutChanged: (layerId: String) -> Boolean): Plan {
        if (itemPlanes.isEmpty()) return Plan(emptySet(), emptySet())
        val dropped = LinkedHashSet<LinePlane>()
        val flattened = LinkedHashSet<LinePlane>()
        for (d in change.drops) dropped += LinePlane(d.layerId, d.celId)
        for (c in change.clears) flattened += LinePlane(c.layerId, c.celId)
        for (c in change.copies) flattened += LinePlane(c.layerId, c.toCelId)
        for (t in change.transfers) flattened += LinePlane(t.layerId, t.toCelId)
        for (layerId in itemPlanes.map { it.layerId }.distinct()) {
            if (layerLayoutChanged(layerId)) itemPlanes.filterTo(flattened) { it.layerId == layerId }
        }
        val onlyDropped = dropped.filterTo(LinkedHashSet()) { it in itemPlanes }
        return Plan(onlyDropped, flattened.filterTo(LinkedHashSet()) { it in itemPlanes && it !in onlyDropped })
    }

    /** "Editable lines on A and B became pixels for this board change." (one sentence, layers by name). */
    fun sentence(layerNames: List<String>): String? {
        val names = layerNames.distinct()
        if (names.isEmpty()) return null
        val list = when (names.size) {
            1 -> names[0]
            2 -> "${names[0]} and ${names[1]}"
            else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
        }
        return "Editable lines on $list became pixels for this board change."
    }
}
