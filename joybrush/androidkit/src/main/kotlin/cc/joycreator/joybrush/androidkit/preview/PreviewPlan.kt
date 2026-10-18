package cc.joycreator.joybrush.androidkit.preview

import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.ENGINE_FILL
import cc.joycreator.joybrush.core.brush.ENGINE_MEDIA
import cc.joycreator.joybrush.core.brush.ENGINE_SMUDGE
import cc.joycreator.joybrush.core.brush.ENGINE_TUFT
import cc.joycreator.joybrush.core.brush.ENGINE_WATERCOLOUR
import cc.joycreator.joybrush.core.brush.MEDIUM_DRY
import cc.joycreator.joybrush.core.brush.MEDIUM_PASTE
import cc.joycreator.joybrush.core.brush.MEDIUM_WET
import cc.joycreator.joybrush.core.chrome.ToolMemory
import kotlin.math.min

/** The ways a brush is drawn, as the canvas itself tells them apart (JbCanvasView.startStroke). */
enum class PreviewKind {
    /** The stamp engine, and every engine the canvas draws with the stamp path (push, wet). */
    STAMP,
    TUFT,
    SMUDGE,
    FILL,
    /** The watercolour LINE brush (engine "watercolour"), not the media watercolour. */
    WATERCOLOUR,
    MEDIA_DRY,
    MEDIA_WET,
    MEDIA_PASTE,
    /** A media brush that carries no media section: nothing to draw. */
    NONE,
}

/** Which engine path a preview takes, and the size it is shown at. Pure. */
object PreviewPlan {

    /** A brush's eraser flag, as the canvas reads it. Orthogonal to the engine: an erasing tuft still erases. */
    fun erases(p: BrushPreset): Boolean = p.blend == "erase"

    fun kindOf(p: BrushPreset): PreviewKind = when {
        p.engine == ENGINE_MEDIA -> when (p.media?.medium) {
            MEDIUM_DRY -> PreviewKind.MEDIA_DRY
            MEDIUM_WET -> PreviewKind.MEDIA_WET
            MEDIUM_PASTE -> PreviewKind.MEDIA_PASTE
            else -> PreviewKind.NONE
        }
        p.engine == ENGINE_FILL -> PreviewKind.FILL
        p.engine == ENGINE_WATERCOLOUR && !erases(p) -> PreviewKind.WATERCOLOUR
        p.engine == ENGINE_TUFT -> PreviewKind.TUFT
        p.engine == ENGINE_SMUDGE -> PreviewKind.SMUDGE
        else -> PreviewKind.STAMP
    }

    /** The share of the row's height the brush may fill at its fullest, so a 300 px airbrush still fits a row. */
    const val MAX_SHARE = 0.55f

    /** [p] as shown in a row [heightPx] tall: its own size, or the most the row allows. Only the base size changes. */
    fun shown(p: BrushPreset, heightPx: Int): BrushPreset =
        ToolMemory.sized(p, min(p.size.base, heightPx * MAX_SHARE).coerceAtLeast(1f), p.opacity.base)
}
