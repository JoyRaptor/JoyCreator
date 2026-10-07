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
     * A media layer's pixels are a look rendered from its paint and water, so anything that writes only pixels would be
     * undone by the next render (the Lead, step-4 check 1): on MEDIA only media brushes paint, and an eraser ([erases]) is
     * let through because the canvas routes it to the media engine, which erases the state itself. A media brush is
     * never refused here: on a paint or ink layer the canvas makes a media layer above for it (contract point 5).
     */
    fun refusalFor(engine: String, kind: LayerKind, erases: Boolean = false): String? {
        if (engine == ENGINE_MEDIA) return null
        if (kind == LayerKind.MEDIA) {
            if (erases) return null
            val what = when (engine) {
                ENGINE_SMUDGE -> "Smudge"; ENGINE_PUSH -> "Push"; "wet" -> "Wet paint"; ENGINE_FILL -> "Fill"
                else -> "This brush"
            }
            return "$what works on a paint layer. A pencil, watercolour or oil layer is painted and blended with its own brushes."
        }
        if (kind == LayerKind.PAINT || engine !in READS_PIXELS) return null
        val what = when (engine) {
            ENGINE_SMUDGE -> "Smudge"
            ENGINE_PUSH -> "Push"
            else -> "Wet paint"
        }
        return "$what reads the paint under it, and an ink layer has none."
    }
}
