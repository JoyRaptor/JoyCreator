package cc.joycreator.joybrush.androidkit.gl.lines

import cc.joycreator.joybrush.androidkit.lines.LinePlane
import cc.joycreator.joybrush.core.doc.JbDocument
import cc.joycreator.joybrush.core.doc.RegionChange

/**
 * Editable lines v1.2 H4: which planes' items a board change must take away. A board operation rewrites the pixels of some
 * planes behind the items' back (a frame's region cleared, a canvas region moved into a frame, a frame copied over, a
 * frame dropped), so "tile = base + items" would stop being true there. Those planes lose their items in the SAME undo
 * step; their tiles stay as they are, so the lines become pixels. A plane whose cel the change DROPS loses them with the
 * cel's pixels. A plane whose OWN region changes (its board moved or resized, or held/unheld) loses them too, since its
 * items are clipped to the region it owns; a plane whose region stays the same keeps its lines (adding a frame elsewhere
 * must not turn the canvas's lines into pixels). Pure: no engine.
 */
internal object BoardItemFlatten {

    /** [dropped]: the cel goes with its pixels. [flattened]: the pixels stay and the items become them. */
    class Plan(val dropped: Set<LinePlane>, val flattened: Set<LinePlane>) {
        val all: Set<LinePlane> get() = dropped + flattened
        val isEmpty: Boolean get() = dropped.isEmpty() && flattened.isEmpty()
    }

    fun plan(itemPlanes: Set<LinePlane>, change: RegionChange, regionChanged: (LinePlane) -> Boolean): Plan {
        if (itemPlanes.isEmpty()) return Plan(emptySet(), emptySet())
        val dropped = LinkedHashSet<LinePlane>()
        val flattened = LinkedHashSet<LinePlane>()
        for (d in change.drops) dropped += LinePlane(d.layerId, d.celId)
        for (c in change.clears) flattened += LinePlane(c.layerId, c.celId)
        for (c in change.copies) flattened += LinePlane(c.layerId, c.toCelId)
        for (t in change.transfers) flattened += LinePlane(t.layerId, t.toCelId)
        for (p in itemPlanes) if (regionChanged(p)) flattened += p
        val onlyDropped = dropped.filterTo(LinkedHashSet()) { it in itemPlanes }
        return Plan(onlyDropped, flattened.filterTo(LinkedHashSet()) { it in itemPlanes && it !in onlyDropped })
    }

    /**
     * What decides where [plane]'s pixels are its own in [doc]: for the shared cel, the rects of the boards it does NOT
     * own (every board the layer animates in and does not hold); for a frame cel, its board's rect and whether the layer
     * holds that board. Null when the doc has no such layer or cel. Compare two of these to tell a moved region.
     */
    fun region(doc: JbDocument, plane: LinePlane): Any? {
        val layer = doc.layers.firstOrNull { it.id == plane.layerId } ?: return null
        val shared = layer.sharedCelId ?: layer.cels.singleOrNull()?.id
        fun rectOf(boardId: String) = doc.boards.firstOrNull { it.id == boardId }?.rect
        if (plane.celId == null || plane.celId == shared) {
            return layer.regions.filterNot { it.held }.map { it.boardId to rectOf(it.boardId) }.sortedBy { it.first }
        }
        val region = layer.regions.firstOrNull { plane.celId in it.frameCel.values } ?: return null
        return Triple(region.boardId, rectOf(region.boardId), region.held)
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
