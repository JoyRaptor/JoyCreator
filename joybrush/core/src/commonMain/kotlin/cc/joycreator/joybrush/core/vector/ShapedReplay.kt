package cc.joycreator.joybrush.core.vector

import cc.joycreator.joybrush.core.brush.BrushDabber
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.DabInputs
import cc.joycreator.joybrush.core.brush.DualMask
import cc.joycreator.joybrush.core.brush.SampledTipSet
import cc.joycreator.joybrush.core.brush.Scatter
import cc.joycreator.joybrush.core.brush.SplitMix
import cc.joycreator.joybrush.core.brush.StrokeColors
import cc.joycreator.joybrush.core.input.StrokeSmoother
import cc.joycreator.joybrush.core.paint.Dab
import cc.joycreator.joybrush.core.paint.DabPlacer
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Required caller allowances, never a hidden/truncating global cap. */
data class ShapedReplayLimits(
    val maxRawSamples: Int,
    val maxSmoothedSamples: Int,
    val maxPlacedDabs: Int,
    val maxFinalDabs: Int,
) {
    init { require(maxRawSamples > 0 && maxSmoothedSamples > 0 && maxPlacedDabs > 0 && maxFinalDabs > 0) }
}

/**
 * Bounded two-pass transcription for WHOLE-stroke taper/dashes. Pass1 measures exact Float
 * placed-dab arc length without retaining a dab list. Pass2 repeats placement from the same raw
 * samples/seed, applies the existing taper/dash equations, scatters, scales, and admits before
 * retaining each final dab. No densification and no changes to sample/image/color RNG order.
 */
object ShapedReplay {
    fun replayAdmitted(record: StrokeRecord, preset: BrushPreset, sampled: SampledTipSet?, dual: DualMask?,
                       limits: ShapedReplayLimits, admit: (Dab) -> Unit): InkReplay.Replayed {
        InkReplay.refusal(record, preset, sampled, dual)?.let { throw InkReplayException(it) }
        require(preset.engine == "stamp" && preset.line != null) { "Shaped admission requires a whole-stroke stamp recipe" }
        require(record.brushId == preset.id) { "Shaped admission requires the record's own frozen brush" }
        require(!LineStyle.of(preset.line).isWire) { "Screen-width wire replay needs its renderer adapter" }
        if (record.samples.size > limits.maxRawSamples) throw InkReplayException("Stroke exceeds its raw-sample allowance")

        var total = 0f
        var measuredCount = 0
        var previous: Dab? = null
        walk(record, preset, sampled, dual, limits) { dab ->
            previous?.let { old -> total += hypot(dab.x - old.x, dab.y - old.y) }
            if (!total.isFinite()) throw InkReplayException("Stroke arc length is outside the replay range")
            previous = dab; measuredCount++
        }

        val scale = if (record.widthScale.isFinite()) record.widthScale else 1f
        val style = LineStyle.of(preset.line)
        val diameter = LineStyle.safeLength(preset.size.base * scale)
        var lengthIn = LineStyle.safeLength(style.taperIn) * diameter
        var lengthOut = LineStyle.safeLength(style.taperOut) * diameter
        val taper = style.tapers && measuredCount >= 2 && diameter > 0f && total > 0f
        if (taper && lengthIn + lengthOut > total) {
            val factor = total / (lengthIn + lengthOut)
            lengthIn *= factor; lengthOut *= factor
        }
        val tip = if (style.taperTip.isFinite()) style.taperTip.coerceIn(LineShaper.MIN_TIP, 1f) else .1f
        val taperShape = if (style.taperShape.isFinite()) style.taperShape.coerceIn(.25f, 4f) else 1f
        val dash = style.dashes && diameter > 0f && measuredCount > 0
        val on = LineStyle.safeLength(style.dashOn) * diameter
        val period = on + LineStyle.safeLength(style.dashOff) * diameter
        val window = max(on - diameter, 0f)
        var lastPeriod = -1L
        var along = 0f
        var prior: Dab? = null
        val output = ArrayList<Dab>()
        val scatter = SplitMix(record.seed xor Scatter.SALT)
        val dabber = walk(record, preset, sampled, dual, limits) { original ->
            prior?.let { old -> along += hypot(original.x - old.x, original.y - old.y) }
            prior = original
            var dab = original
            if (taper) {
                val fraction = min(LineShaper.ramp(along, lengthIn, tip, taperShape),
                    LineShaper.ramp(total - along, lengthOut, tip, taperShape))
                if (!(fraction >= 1f)) dab = dab.copy(radius = dab.radius * fraction)
            }
            var keep = true
            if (dash) {
                val k = floor(along / period).toLong()
                val u = along - k * period
                keep = u <= window || k != lastPeriod
                lastPeriod = k
            }
            if (keep) Scatter.expandTo(dab, preset.scatter, scatter, ::inputs) { leaf ->
                val final = rescale(leaf, scale)
                if (output.size >= limits.maxFinalDabs) throw InkReplayException("Stroke exceeds its final-dab allowance")
                admit(final) // Admission can abort before this dab or any subsequent dab is retained.
                output += final
            }
        }
        return InkReplay.Replayed(output, dabber.strokeHardness, dabber.strokeOpacity, dabber.strokeGrain)
    }

    private fun walk(record: StrokeRecord, preset: BrushPreset, sampled: SampledTipSet?, dual: DualMask?,
                     limits: ShapedReplayLimits, emit: (Dab) -> Unit): BrushDabber {
        val dabber = BrushDabber(preset, record.seed,
            StrokeColors(record.colorArgb, record.secondaryColorArgb ?: -1), sampled, dual)
        val placer = DabPlacer(spacing = dabber.spacing, look = dabber::look)
        val smoother = cc.joycreator.joybrush.core.input.StrokeStabilizer(record.stabilizer, record.smoothing, record.screenPerDoc)
        var smoothed = 0
        var placed = 0
        val placedSink: (Dab) -> Unit = { dab ->
            if (++placed > limits.maxPlacedDabs) throw InkReplayException("Stroke exceeds its placed-dab allowance")
            emit(dab)
        }
        val sampleSink: (cc.joycreator.joybrush.core.input.PenSample) -> Unit = { sample ->
            if (++smoothed > limits.maxSmoothedSamples) throw InkReplayException("Stroke exceeds its smoothed-sample allowance")
            placer.addTo(sample, placedSink)
        }
        for (sample in record.samples) smoother.addTo(preset.response.apply(sample), sampleSink)
        smoother.finishTo(sampleSink)
        return dabber
    }

    /** Exact InkReplay scatter inputs; do not invent missing speed/distance channels here. */
    private fun inputs(dab: Dab) = DabInputs(pressure = dab.pressure, tilt = dab.tilt,
        speedPxPerS = Float.NaN, direction = Float.NaN, lean = Float.NaN, distancePx = Float.NaN,
        random = Float.NaN, strokeRandom = Float.NaN, barrel = Float.NaN)

    /** Exact old final radius arithmetic, including scale==1 fast path and nonfinite reader policy. */
    private fun rescale(dab: Dab, scale: Float): Dab {
        if (scale == 1f) return dab
        val wanted = (if (dab.radius.isFinite()) dab.radius else 0f) * scale
        val radius = if (wanted.isFinite()) wanted.coerceIn(0f, DabPlacer.MAX_RADIUS_PX) else 0f
        return if (radius == dab.radius) dab else dab.copy(radius = radius)
    }
}
