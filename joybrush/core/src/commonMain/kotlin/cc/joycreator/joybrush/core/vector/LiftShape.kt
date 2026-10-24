package cc.joycreator.joybrush.core.vector

import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.DualMask
import cc.joycreator.joybrush.core.brush.SampledTipSet
import cc.joycreator.joybrush.core.stroke.StrokeRecord

/**
 * Unibrush U6: a brush that shapes the WHOLE stroke (taper in and out, dashes) cannot know its end until the pen lifts, so
 * the canvas draws the stroke plainly while the pen is down and, at the lift, redraws it shaped from its own recording.
 * This is that redraw's dab list: [ShapedReplay] under the canvas's allowances. Null when the stroke is beyond them (a very
 * long stroke) or the recipe cannot be shaped: the canvas then keeps the plain stroke and says so, never dropping it.
 */
object LiftShape {
    /** Room for a long stroke (about 80 s of a 240 Hz pen) without letting one hold the GL thread for seconds. */
    val LIMITS = ShapedReplayLimits(maxRawSamples = 20_000, maxSmoothedSamples = 80_000, maxPlacedDabs = 200_000,
        maxFinalDabs = 200_000)

    /** Shapes the brush asks for at the lift; a wire's width belongs to its renderer and is not redrawn here. */
    fun wanted(preset: BrushPreset?): Boolean {
        val style = LineStyle.of(preset?.line)
        return preset?.engine == "stamp" && (style.tapers || style.dashes) && !style.isWire
    }

    fun shaped(record: StrokeRecord, preset: BrushPreset, sampled: SampledTipSet?, dual: DualMask?,
               limits: ShapedReplayLimits = LIMITS): InkReplay.Replayed? =
        try { ShapedReplay.replayAdmitted(record, preset, sampled, dual, limits) {} }
        catch (e: InkReplayException) { null }
        catch (e: IllegalArgumentException) { null }
}
