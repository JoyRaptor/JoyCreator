package cc.joycreator.joybrush.core.chrome

import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.ENGINE_FILL
import cc.joycreator.joybrush.core.brush.ENGINE_MEDIA
import cc.joycreator.joybrush.core.brush.MEDIUM_DRY
import cc.joycreator.joybrush.core.brush.MEDIUM_WET
import cc.joycreator.joybrush.core.brush.ENGINE_PUSH
import cc.joycreator.joybrush.core.brush.ENGINE_SMUDGE
import cc.joycreator.joybrush.core.stroke.StrokeEdit

/**
 * The brush drawer's left column (JB-2.01): brushes grouped into the kinds a painter looks for, the way Infinite
 * Painter shelves them. A brush file carries no kind of its own (adding one is a format change, R3), so the kind is
 * read from what the file already says: where it came from, what it does, and for the built-in set, its id.
 *
 * Only kinds that hold a brush are shown ([shelves]); an empty shelf is a dead knob.
 */
object BrushShelf {

    /** In the order the drawer lists them. [ALL] is not a kind a brush has: it is the shelf that holds every brush. */
    enum class Kind { ALL, PENCILS, INKS, MARKERS, PAINT, WATERCOLOUR, OILS, AIRBRUSH, SMUDGE, FILL, ERASERS, IMPORTED }

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
    )

    fun kindOf(p: BrushPreset): Kind = when {
        // What it DOES outranks where it came from: an imported eraser is found with the erasers.
        p.blend == "erase" -> Kind.ERASERS
        p.engine == ENGINE_SMUDGE || p.engine == ENGINE_PUSH -> Kind.SMUDGE
        p.engine == ENGINE_FILL -> Kind.FILL
        // A media brush says its medium outright (contract point 6: Pencils / Watercolour / Oils).
        p.engine == ENGINE_MEDIA -> when (p.media?.medium) {
            MEDIUM_DRY -> Kind.PENCILS
            MEDIUM_WET -> Kind.WATERCOLOUR
            else -> Kind.OILS
        }
        p.sourceFormat != "native" -> Kind.IMPORTED
        else -> {
            val words = (p.id + " " + p.name).lowercase()
            WORDS.firstOrNull { words.contains(it.first) }?.second ?: Kind.PAINT
        }
    }

    /**
     * Whether a stroke of [p] stays an editable line or bakes to pixels (R51; JB-5.20 D6). Only engines that can draw an
     * ink line ([StrokeEdit.drawsInkLines]: stamp and fill) ever make lines: media keeps its own paint state, and smudge,
     * push, wet and tuft read what is underneath. An eraser is never a line: it cuts lines and clears pixels (D7). Beyond
     * that the shelf decides: pens, inks, markers, plain pencils and the fill pen are the Concepts side and make lines;
     * paint, airbrush and imported brushes are the Infinite Painter side and make pixels, so painting stays light (the
     * owner's JB-5.20 Q2; a per-brush "Editable lines" switch arrives with the drawer badge in 5.20e).
     */
    fun makesLines(p: BrushPreset): Boolean {
        if (!StrokeEdit.drawsInkLines(p.engine) || p.blend == "erase") return false
        return when (kindOf(p)) {
            Kind.PENCILS, Kind.INKS, Kind.MARKERS, Kind.FILL -> true
            else -> false
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
