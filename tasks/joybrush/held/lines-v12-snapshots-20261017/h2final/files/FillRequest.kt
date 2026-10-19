package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.core.fill.MaskPaintMode
import cc.joycreator.joybrush.core.select.SelectionMask

/**
 * A fill pen's shape and paint, handed to [GlPaintEngine.replaceTiles] beside the composited tiles (editable lines v1.2,
 * H2). On a plane that holds editable lines or pixel slabs the engine keeps the fill as a pixel slab in time order
 * (coverage x colour x opacity, premultiplied, applied with the mode's operator) instead of baking it into the lines.
 */
class FillRequest(val mask: SelectionMask, val argb: Int, val opacity: Float, val mode: MaskPaintMode) {
    /** The slab operator for this mode: FILL is `normal`. */
    val operator: String get() = when (mode) {
        MaskPaintMode.FILL -> "normal"
        MaskPaintMode.BEHIND -> "behind"
        MaskPaintMode.ERASE -> "erase"
    }
}
