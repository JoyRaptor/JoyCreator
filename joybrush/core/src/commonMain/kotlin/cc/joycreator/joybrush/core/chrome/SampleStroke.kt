package cc.joycreator.joybrush.core.chrome

import cc.joycreator.joybrush.core.brush.BrushDabber
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.DabInputs
import cc.joycreator.joybrush.core.brush.ENGINE_TUFT
import cc.joycreator.joybrush.core.brush.TuftStroke
import cc.joycreator.joybrush.core.brush.Scatter
import cc.joycreator.joybrush.core.brush.SplitMix
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.paint.Dab
import cc.joycreator.joybrush.core.paint.DabPlacer
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * The stroke sample on each row of the brush drawer (JB-2.01): a real stroke of the brush, laid by its own dabber and
 * placer and scatter, not a picture of one. The shell only draws the dabs it is given. The pen swells from light to
 * full pressure and back, so a brush that follows pressure shows its taper.
 *
 * The sample is drawn at [displaySize] or the brush's own size, whichever is smaller, so a 300 px airbrush still fits
 * a 30 dp row and a hairline pen still shows as a hairline.
 */
object SampleStroke {

    /** Samples along the stroke. Enough that the placer, not the sampling, decides the spacing. */
    const val SAMPLES = 64

    /** The same seed every time, so a sample never shimmers when the drawer is reopened. */
    const val SEED = 0x5A3F1EL

    /** The scatter stream's salt, as the canvas salts its own (JbCanvasView.SCATTER_SALT). */
    private const val SCATTER_SALT = 0x5CA7L

    /** The pen's path: a gentle S from left to right inside [width] × [height], inset by [inset], pressure 0.25 → 1 → 0.25. */
    fun path(width: Float, height: Float, inset: Float): List<PenSample> {
        val out = ArrayList<PenSample>(SAMPLES)
        val w = (width - 2f * inset).coerceAtLeast(1f)
        val amp = ((height - 2f * inset) / 2f).coerceAtLeast(0f)
        val mid = height / 2f
        for (i in 0 until SAMPLES) {
            val t = i / (SAMPLES - 1f)
            val x = inset + w * t
            val y = mid - amp * sin(2.0 * PI * t).toFloat() * 0.8f
            val pressure = 0.25f + 0.75f * sin(PI * t).toFloat()
            out.add(PenSample(x, y, timeMs = i * 8.0, pressure = pressure))
        }
        return out
    }

    /**
     * The dabs of [preset]'s sample stroke in a [width] × [height] box, drawn [displaySize] px wide at most. Pure and
     * repeatable: the same preset and box always give the same dabs.
     */
    fun dabs(preset: BrushPreset, width: Float, height: Float, displaySize: Float): List<Dab> {
        val size = min(preset.size.base, displaySize).coerceAtLeast(1f)
        val shown = ToolMemory.sized(preset, size, preset.opacity.base)
        if (shown.engine == ENGINE_TUFT) return tuftDabs(shown, path(width, height, inset = size / 2f + 1f).map { shown.response.apply(it) })
        val dabber = BrushDabber(shown, SEED)
        val placer = DabPlacer(spacing = dabber.spacing, look = dabber::look)
        val placed = placer.add(path(width, height, inset = size / 2f + 1f).map { shown.response.apply(it) })
        return Scatter.expand(placed, shown.scatter, SplitMix(SEED xor SCATTER_SALT)) { dab ->
            DabInputs(
                pressure = dab.pressure,
                tilt = dab.tilt,
                speedPxPerS = Float.NaN,
                direction = Float.NaN,
                lean = Float.NaN,
                distancePx = Float.NaN,
                random = Float.NaN,
                strokeRandom = Float.NaN,
                barrel = Float.NaN,
            )
        }
    }

    /**
     * A tuft brush's sample (R9) as round dabs the drawer can draw: each footprint's teardrop filled with circles from
     * the belly to the tip, and each droplet or hair as one circle. The drawer shows the silhouette — the swell from
     * hairline to belly — not the streaks, which only the GPU draws.
     */
    private fun tuftDabs(preset: BrushPreset, samples: List<PenSample>): List<Dab> {
        val stroke = TuftStroke(preset, SEED)
        val out = ArrayList<Dab>()
        for (t in stroke.add(samples) + stroke.finish()) {
            val len = kotlin.math.hypot(t.bx - t.ax, t.by - t.ay)
            val steps = if (len < 1f) 0 else min(8, (len / kotlin.math.max(t.rb, 0.5f)).toInt() + 1)
            out.add(Dab(t.ax, t.ay, t.ra))
            for (i in 1..steps) {
                val f = i / steps.toFloat()
                out.add(Dab(t.ax + (t.bx - t.ax) * f, t.ay + (t.by - t.ay) * f, t.ra + (t.rb - t.ra) * f))
            }
        }
        return out
    }
}
