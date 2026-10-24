package cc.joycreator.joybrush.androidkit.lines

import cc.joycreator.joybrush.core.brush.BLEND_MULTIPLY
import cc.joycreator.joybrush.core.brush.BLEND_SCREEN
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.ENGINE_FILL
import cc.joycreator.joybrush.core.brush.ENGINE_MEDIA
import cc.joycreator.joybrush.core.brush.ENGINE_SMUDGE
import cc.joycreator.joybrush.core.brush.ENGINE_WATERCOLOUR

/**
 * Editable lines v1 + v1.2: which strokes may become lines, and what happens to the rest on a layer that holds items.
 * Pure words, no engine: the canvas asks at pen-down. Each refusal is the END of a sentence ("... because <it>").
 */
object LineAdmission {

    /** Why this stroke can't be kept as an editable line, as the end of a sentence; null = it can. */
    fun lineRefusal(p: BrushPreset?, erase: Boolean, smoothing: Float, zoom: Float,
                    tiledBoard: Boolean, holdsRealisticPaint: Boolean): String? = when {
        p == null -> "the built-in round brush can't be kept as a line"
        p.engine == ENGINE_MEDIA -> "realistic pencil, watercolour and oil can't share a layer with lines yet"
        p.engine == ENGINE_SMUDGE -> "smudge doesn't work on lines yet"
        p.engine == ENGINE_FILL -> "the fill pen doesn't work on lines yet"
        p.engine !in setOf("stamp", ENGINE_WATERCOLOUR) -> "this brush can't be kept as a line yet"
        p.engine == ENGINE_WATERCOLOUR && (erase || p.blend == "erase") -> "the watercolour eraser can't be kept as a line yet"
        // A live stroke paints "behind" as plain paint (StrokeBlend has no behind), so a line would redraw differently.
        !erase && p.blend == "behind" -> "paint-behind brushes can't be kept as lines yet"
        // Tapers and dashes are kept (Unibrush U6: the stroke is shaped at the lift and its line replays shaped).
        cc.joycreator.joybrush.core.vector.LineStyle.of(p.line).isWire -> "screen-width wire brushes can't be kept as lines yet"
        !erase && p.blend !in setOf("normal", "erase", "behind", BLEND_MULTIPLY, BLEND_SCREEN) -> "the ${p.blend} blend can't be kept as a line yet"
        p.engine == "stamp" && p.tip.source != "procedural" -> "picture-tip brushes can't be kept as lines yet"
        tiledBoard -> "lines don't work on a tiled board yet"
        holdsRealisticPaint -> "it already holds realistic paint"
        !smoothing.isFinite() || smoothing !in 0f..1f || !zoom.isFinite() || zoom <= 0f -> "the smoothing setting is out of range"
        else -> null
    }

    /**
     * Why a stroke that is NOT a line cannot be painted on a layer that holds items (lines or pixel slabs), as the end of a
     * sentence; null = it is painted, and the engine keeps it as a pixel slab in time order with the lines. Realistic
     * pencil, watercolour and oil keep their own state beside the pixels, so they stay refused; so does a tiled board.
     */
    fun slabRefusal(p: BrushPreset?, tiledBoard: Boolean): String? = when {
        p?.engine == ENGINE_MEDIA -> "realistic pencil, watercolour and oil can't share a layer with lines yet"
        tiledBoard -> "lines don't work on a tiled board yet"
        else -> null
    }

    /** Slab tiles (256 KB each) the drawing may hold before a stroke over lines is refused: 640 = 160 MiB on a Note 9. */
    const val SLAB_TILE_BUDGET = 640

    /**
     * Why a stroke that would be kept as a slab can't be, as the end of a sentence; null = it can. Plain strokes merge into
     * the slab under them, so this is only reached by a great deal of painting over many lines (review of v1.2).
     */
    fun slabBudgetRefusal(slabTiles: Int): String? =
        if (slabTiles >= SLAB_TILE_BUDGET) "the painting kept over its lines has filled this phone's memory (Rasterize Down some lines to free it)" else null

    /** The sentence the person hears when a layer that holds items refuses a stroke. */
    fun refusalSentence(layerName: String, why: String): String =
        "\"$layerName\" holds editable lines, and $why. Use another layer for it."
}
