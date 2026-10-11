package cc.joycreator.joybrush.core.brush

import kotlinx.serialization.Serializable

/**
 * brush.json's `line` section (brush version [VERSION_LINES]): what a line brush does to its WHOLE stroke — taper, dashes
 * and the wire's constant screen width. The maths is `vector/LineShaper`, which reads this through `LineStyle`.
 *
 * Absent (`null` on [BrushPreset.line]) for every brush that does not shape its stroke, which is every brush shipped
 * before version [VERSION_LINES]: such a file decodes exactly as it always did.
 *
 * Lengths are in brush DIAMETERS (the preset's `size`), so a style reads the same at every brush size.
 *
 * @property taperIn length over which the start grows to full width; 0 = none.
 * @property taperOut length over which the end shrinks to [taperTip]; 0 = none. Drawn when the stroke ends.
 * @property taperTip width at the very tip, as a fraction of full width (0..1).
 * @property taperShape profile exponent: 0.5 round bullet, 1 soft point, 2 needle (0.25..4).
 * @property dashOn one dash, end to end; 0 = solid. A dash of 1 or less is a dot.
 * @property dashOff the gap between dashes.
 * @property screenWidthPx above 0, the line is a wire: this many SCREEN pixels wide at every zoom (0..64).
 */
@Serializable data class LineStyleSpec(
    val taperIn: Float = 0f,
    val taperOut: Float = 0f,
    val taperTip: Float = 0.1f,
    val taperShape: Float = 1f,
    val dashOn: Float = 0f,
    val dashOff: Float = 0f,
    val screenWidthPx: Float = 0f,
)
