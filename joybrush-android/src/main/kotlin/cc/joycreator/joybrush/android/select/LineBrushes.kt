package cc.joycreator.joybrush.android.select

import cc.joycreator.joybrush.core.brush.BLEND_BEHIND
import cc.joycreator.joybrush.core.brush.BLEND_MULTIPLY
import cc.joycreator.joybrush.core.brush.BLEND_SCREEN
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.BrushValidate
import cc.joycreator.joybrush.core.brush.ENGINE_WATERCOLOUR
import cc.joycreator.joybrush.core.vector.InkEditSession

/**
 * Which library brushes a selected line can be re-brushed to (the row's brush chip, and the strip's Brush button while
 * lines are selected).
 *
 * These are the brush-only half of the canvas's own rule for "this stroke can be kept as an editable line"
 * (`JbCanvasView.lineRefusal`, which is private there and also looks at the board, the layer and the smoothing amount);
 * the engine replays exactly these. A brush outside it would be frozen into the drawing and then refused at replay, so it
 * is never offered. The eraser is not a pen: a line is not turned into an eraser by picking one.
 */
object LineBrushes {

    /** Why [p] cannot be a line's brush, as the end of a sentence; null = it can. */
    fun refusal(p: BrushPreset): String? = when {
        // Picture tips are frozen with their pictures when picked (CanvasSelectionHost.freezeBrush), so only the engine decides.
        !cc.joycreator.joybrush.core.stroke.StrokeEdit.drawsInkLines(p.engine) -> "it reads the pixels under the pen"
        p.engine != "stamp" && p.engine != ENGINE_WATERCOLOUR -> "this brush can't be kept as a line yet"
        p.blend == "erase" -> "an eraser is not a pen"
        cc.joycreator.joybrush.core.vector.LineStyle.of(p.line).isWire -> "screen-width wire brushes can't be kept as lines yet"
        p.blend != "normal" && p.blend != BLEND_BEHIND && p.blend != BLEND_MULTIPLY && p.blend != BLEND_SCREEN ->
            "the ${p.blend} blend can't be kept as a line yet"
        p.engine == "stamp" && p.tip.source != "procedural" -> "picture-tip brushes can't be kept as lines yet"
        BrushValidate.validate(p).isNotEmpty() -> "its settings are not valid"
        else -> null
    }

    fun canBeLine(p: BrushPreset): Boolean = refusal(p) == null

    /** [library] in its own order, less what a line cannot wear. */
    fun forPicker(library: List<BrushPreset>): List<BrushPreset> = library.filter(::canBeLine)
}
