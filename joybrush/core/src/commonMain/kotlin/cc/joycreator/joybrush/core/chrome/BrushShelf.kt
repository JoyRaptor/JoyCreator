package cc.joycreator.joybrush.core.chrome

import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.ENGINE_FILL
import cc.joycreator.joybrush.core.brush.ENGINE_PUSH
import cc.joycreator.joybrush.core.brush.ENGINE_SMUDGE

/**
 * The brush drawer's left column (JB-2.01): brushes grouped into the kinds a painter looks for, the way Infinite
 * Painter shelves them. A brush file carries no kind of its own (adding one is a format change, R3), so the kind is
 * read from what the file already says: where it came from, what it does, and for the built-in set, its id.
 *
 * Only kinds that hold a brush are shown ([shelves]); an empty shelf is a dead knob.
 */
object BrushShelf {

    /** In the order the drawer lists them. [ALL] is not a kind a brush has: it is the shelf that holds every brush. */
    enum class Kind { ALL, PENCILS, INKS, MARKERS, PAINT, AIRBRUSH, SMUDGE, FILL, ERASERS, IMPORTED }

    /** Words in a built-in brush's id or name that say what it is. Checked in this order, so "pencil" wins over "pen". */
    private val WORDS: List<Pair<String, Kind>> = listOf(
        "pencil" to Kind.PENCILS,
        "graphite" to Kind.PENCILS,
        "ink" to Kind.INKS,
        "pen" to Kind.INKS,
        "liner" to Kind.INKS,
        "marker" to Kind.MARKERS,
        "airbrush" to Kind.AIRBRUSH,
        "softair" to Kind.AIRBRUSH,
        "soft air" to Kind.AIRBRUSH,
        "spray" to Kind.AIRBRUSH,
        // Flat Paint borrows the smudge ENGINE, and the engine rule below used to file it on the
        // SMUDGE shelf beside the Smudge utility brush, where nobody looks for a paint brush. A
        // native brush that names itself outranks the engine it borrows.
        "flat paint" to Kind.PAINT,
        "flatpaint" to Kind.PAINT,
    )

    fun kindOf(p: BrushPreset): Kind {
        // What it DOES outranks where it came from: an imported eraser is found with the erasers.
        if (p.blend == "erase") return Kind.ERASERS
        // And the fill engine outranks the name, or "Fill pen" reads as an INK because it contains
        // "pen" (BrushTuningTest caught that when this rule was first written the other way round).
        if (p.engine == ENGINE_FILL) return Kind.FILL
        if (p.sourceFormat == "native") {
            val words = (p.id + " " + p.name).lowercase()
            WORDS.firstOrNull { words.contains(it.first) }?.let { return it.second }
        }
        return when {
            p.engine == ENGINE_SMUDGE || p.engine == ENGINE_PUSH -> Kind.SMUDGE
            p.sourceFormat != "native" -> Kind.IMPORTED
            else -> Kind.PAINT
        }
    }

    /**
     * The drawer's shelves: [Kind.ALL] first with every brush, then each kind that holds at least one, in [Kind] order.
     * Brushes keep [library] order within a shelf. An empty library has no shelves at all.
     */
    fun shelves(library: List<BrushPreset>): List<Pair<Kind, List<BrushPreset>>> {
        if (library.isEmpty()) return emptyList()
        val grouped = library.groupBy { kindOf(it) }
        val out = ArrayList<Pair<Kind, List<BrushPreset>>>()
        out.add(Kind.ALL to library)
        for (k in Kind.entries) {
            val list = grouped[k] ?: continue
            out.add(k to list)
        }
        return out
    }
}
