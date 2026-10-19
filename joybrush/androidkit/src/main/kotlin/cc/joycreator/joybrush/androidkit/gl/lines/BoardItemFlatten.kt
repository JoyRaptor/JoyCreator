package cc.joycreator.joybrush.androidkit.gl.lines

import cc.joycreator.joybrush.androidkit.lines.LinePlane
import cc.joycreator.joybrush.core.doc.JbDocument
import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.doc.RegionChange
import cc.joycreator.joybrush.core.paint.Tiles

/**
 * Editable lines v1.2 H4: which planes' items a board change must take away. A board operation rewrites the pixels of some
 * planes behind the items' back (a frame's region cleared, a canvas region moved into a frame, a frame copied over), or
 * moves the region a plane owns (a board moved, resized, held or let go), so "tile = base + items" would stop being true
 * THERE. A plane is flattened (its items become its pixels, in the same undo step) only when such a rectangle reaches a
 * tile its items cross: lines elsewhere stay editable (review of v1.2: making a board in an empty corner must not turn
 * every layer's lines into pixels). A plane whose cel the change DROPS loses its items with the cel's pixels. Pure.
 */
internal object BoardItemFlatten {

    /** [dropped]: the cel goes with its pixels. [flattened]: the pixels stay and the items become them. */
    class Plan(val dropped: Set<LinePlane>, val flattened: Set<LinePlane>) {
        val all: Set<LinePlane> get() = dropped + flattened
        val isEmpty: Boolean get() = dropped.isEmpty() && flattened.isEmpty()
    }

    /** [crossed]: the tiles a plane's items cross (NativeLines.crossedKeys). */
    fun plan(itemPlanes: Set<LinePlane>, change: RegionChange, before: JbDocument, crossed: (LinePlane) -> Set<Long>): Plan {
        if (itemPlanes.isEmpty()) return Plan(emptySet(), emptySet())
        val dropped = change.drops.map { LinePlane(it.layerId, it.celId) }.filterTo(LinkedHashSet()) { it in itemPlanes }
        val flattened = LinkedHashSet<LinePlane>()
        for (p in itemPlanes) {
            if (p in dropped) continue
            val rects = touched(p, change, before)
            if (rects.isEmpty()) continue
            val keys = crossed(p)
            if (rects.any { r -> tilesOf(r).any { it in keys } }) flattened += p
        }
        return Plan(dropped, flattened)
    }

    /** Every rectangle where [change] rewrites [p]'s pixels or moves the edge of the region [p] owns. */
    fun touched(p: LinePlane, change: RegionChange, before: JbDocument): List<RectPx> {
        val out = ArrayList<RectPx>()
        for (c in change.clears) if (LinePlane(c.layerId, c.celId) == p) out += c.rect
        for (c in change.copies) if (LinePlane(c.layerId, c.toCelId) == p) out += c.rect
        for (t in change.transfers) if (LinePlane(t.layerId, t.toCelId) == p) out += t.destinationRect
        out += movedRegions(before, change.doc, p)
        return out
    }

    /**
     * The old and new rects of every board whose place or hold changed for [p]: for the shared cel, any board of the layer
     * (it owns everything outside the boards it does not hold); for a frame cel, its own board only.
     */
    fun movedRegions(before: JbDocument, after: JbDocument, p: LinePlane): List<RectPx> {
        fun boards(doc: JbDocument): Map<String, Pair<RectPx?, Boolean>> {
            val layer = doc.layers.firstOrNull { it.id == p.layerId } ?: return emptyMap()
            return layer.regions.associate { r -> r.boardId to (doc.boards.firstOrNull { it.id == r.boardId }?.rect to r.held) }
        }
        val own = (before.layers + after.layers).firstOrNull { it.id == p.layerId }?.let { l ->
            val shared = l.sharedCelId ?: l.cels.singleOrNull()?.id
            if (p.celId == null || p.celId == shared) null
            else (before.layers + after.layers).filter { it.id == p.layerId }.flatMap { it.regions }
                .firstOrNull { p.celId in it.frameCel.values }?.boardId
        }
        val x = boards(before); val y = boards(after)
        val out = ArrayList<RectPx>()
        for (id in x.keys + y.keys) {
            if (own != null && id != own) continue
            if (x[id] == y[id]) continue
            x[id]?.first?.let(out::add); y[id]?.first?.let(out::add)
        }
        return out
    }

    /** The tile keys [r] reaches. */
    fun tilesOf(r: RectPx): Sequence<Long> {
        if (r.w <= 0 || r.h <= 0) return emptySequence()
        val s = Tiles.SIZE
        val x0 = Math.floorDiv(r.x, s); val x1 = Math.floorDiv(r.x + r.w - 1, s)
        val y0 = Math.floorDiv(r.y, s); val y1 = Math.floorDiv(r.y + r.h - 1, s)
        return sequence { for (ty in y0..y1) for (tx in x0..x1) yield(Tiles.key(tx, ty)) }
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
