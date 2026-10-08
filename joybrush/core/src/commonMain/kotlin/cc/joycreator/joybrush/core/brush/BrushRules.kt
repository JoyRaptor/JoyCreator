package cc.joycreator.joybrush.core.brush

import cc.joycreator.joybrush.core.doc.LayerKind

/**
 * Which brush engines may draw on which kind of layer (LEAD_RULINGS R20, given a home by R47).
 *
 * `smudge`, `push` and `wet` READ the pixels underneath, so they are paint-layer brushes: an ink layer is a
 * recording of strokes and has no pixels to read, and re-rendering it at another zoom would smear
 * differently every time. `stamp` and `fill` write and read nothing, so they work anywhere.
 *
 * It lives in `core` rather than in a picker so that the keyboard, a stroke re-brush (JB-5.03a) and a file
 * cannot walk past it: a refusal that only exists in a menu is not a refusal.
 */
object BrushRules {

    /** Engines that read the layer under them. */
    val READS_PIXELS: Set<String> = setOf(ENGINE_SMUDGE, ENGINE_PUSH, "wet")

    /**
     * The sentence to show a person if [engine] may not be used on a [kind] layer, or null if it may.
     *
     * Since R51 there is one kind of pixel layer: every brush paints on PAINT (a v8 MEDIA layer is read as PAINT, and
     * [kind] says MEDIA only for one that was not). A media brush lays its paint over the pixels under it and keeps a
     * payload beside them, so like the brushes that read the paint it needs pixels: on INK it is refused (until ink
     * joins the one kind, JB-5.20).
     */
    fun refusalFor(engine: String, kind: LayerKind): String? {
        if (kind != LayerKind.INK) return null
        if (engine == ENGINE_MEDIA) return "Pencil, watercolour and oil paint on a paint layer, and an ink layer has no pixels for them."
        if (engine !in READS_PIXELS) return null
        val what = when (engine) {
            ENGINE_SMUDGE -> "Smudge"
            ENGINE_PUSH -> "Push"
            else -> "Wet paint"
        }
        return "$what reads the paint under it, and an ink layer has none."
    }
}
