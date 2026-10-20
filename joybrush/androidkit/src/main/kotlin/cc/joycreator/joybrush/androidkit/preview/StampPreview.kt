package cc.joycreator.joybrush.androidkit.preview

import cc.joycreator.joybrush.androidkit.gl.SmudgeParams
import cc.joycreator.joybrush.core.brush.BrushDabber
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.BLEND_MULTIPLY
import cc.joycreator.joybrush.core.brush.BLEND_SCREEN
import cc.joycreator.joybrush.core.brush.DabInputs
import cc.joycreator.joybrush.core.brush.ENGINE_SMUDGE
import cc.joycreator.joybrush.core.brush.Scatter
import cc.joycreator.joybrush.core.brush.SplitMix
import cc.joycreator.joybrush.core.brush.StrokeColors
import cc.joycreator.joybrush.core.grain.GrainMath
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.paint.Accumulate
import cc.joycreator.joybrush.core.paint.Dab
import cc.joycreator.joybrush.core.paint.DabPlacer
import cc.joycreator.joybrush.core.paint.StrokeBlend
import cc.joycreator.joybrush.core.paint.TipShape

/**
 * A stamp-engine stroke (and a smudge, which is the same dabs with a carried colour) worked out exactly as the canvas
 * works one out, and no further: the dabber, the placer and the scatter, and then the numbers the canvas hands the GPU
 * at the stroke's first dab (JbCanvasView.paint and beginStrokeNow). Pure of GL, so it can be repeated and tested.
 *
 * The canvas smooths a live stroke before it reaches the placer. The canned stroke is already smooth, so it goes
 * straight in; the brush's own pressure and tilt curves are applied, as they are live.
 */
object StampPreview {

    class Prepared(
        val dabs: List<Dab>,
        /** Opacity of the whole stroke when it is committed (a build-up stroke's only opacity; 1 for a wash). */
        val strokeOpacity: Float,
        val tip: TipShape,
        val grain: GrainMath.StrokeGrain,
        val accumulate: Accumulate,
        val blend: StrokeBlend,
        val smudge: SmudgeParams?,
    )

    fun build(shown: BrushPreset, samples: List<PenSample>,
              colors: StrokeColors = StrokeColors(0xFF000000.toInt(), 0xFFFFFFFF.toInt())): Prepared {
        val dabber = BrushDabber(shown, CannedStroke.SEED, colors)
        val placer = DabPlacer(spacing = dabber.spacing, look = dabber::look)
        val placed = placer.add(samples.map { shown.response.apply(it) })
        val dabs = Scatter.expand(placed, shown.scatter, SplitMix(CannedStroke.SEED xor CannedStroke.SCATTER_SALT)) { dab ->
            DabInputs(
                pressure = dab.pressure, tilt = dab.tilt, speedPxPerS = Float.NaN, direction = Float.NaN, lean = Float.NaN,
                distancePx = Float.NaN, random = Float.NaN, strokeRandom = Float.NaN, barrel = Float.NaN,
            )
        }
        // Read AFTER the dabs exist: the dabber fixes these at the first dab it is asked about.
        return Prepared(
            dabs = dabs,
            strokeOpacity = dabber.strokeOpacity,
            tip = TipShape(
                aspect = shown.tip.aspect, corner = shown.tip.corner, taper = shown.tip.taper,
                hardness = dabber.strokeHardness, minPx = shown.tip.minPx,
            ),
            grain = dabber.strokeGrain,
            accumulate = accumulateOf(shown),
            blend = blendOf(shown),
            smudge = smudgeOf(shown),
        )
    }

    fun accumulateOf(p: BrushPreset): Accumulate = if (p.accumulate == "buildup") Accumulate.BUILD_UP else Accumulate.WASH

    /** The stroke's own blend, as JbCanvasView.strokeBlend reads it. */
    fun blendOf(p: BrushPreset): StrokeBlend = when {
        PreviewPlan.erases(p) -> StrokeBlend.ERASE
        p.blend == BLEND_MULTIPLY -> StrokeBlend.MULTIPLY
        p.blend == BLEND_SCREEN -> StrokeBlend.SCREEN
        else -> StrokeBlend.NORMAL
    }

    fun smudgeOf(p: BrushPreset): SmudgeParams? =
        if (p.engine == ENGINE_SMUDGE) SmudgeParams(p.smudge.pickup, p.smudge.load, p.smudge.texturePickup, p.smudge.paint) else null
}
