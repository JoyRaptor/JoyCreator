package cc.joycreator.joybrush.core.brush

import cc.joycreator.joybrush.core.input.DirectionTracker
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.paint.DabLook
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max

/**
 * Drives every dab of one stroke from a brush file: the curves, the jitter, the pen's pressure and
 * tilt, the speed the nib is really moving at, and the tip's direction.
 *
 * ONE per stroke. Pass [look] as [cc.joycreator.joybrush.core.paint.DabPlacer]'s `look` and
 * [spacing] as its `spacing`; the placer then asks this exactly once per dab, in stroke order, and
 * everything here is allowed to keep state because of it:
 *
 *  - a [SplitMix] stream (drawn in three per dab — see below),
 *  - a speed filter, which needs the previous dab's distance and time,
 *  - a [DirectionTracker], which needs the previous dab's position,
 *  - the first-dab captures of [strokeHardness] and [strokeOpacity].
 *
 * An earlier version passed three separate lambdas and the brush was asked for its radius TWICE per
 * dab, which advanced the random stream twice as fast and made every jittered brush draw
 * differently from a correct one. One question, one answer, one state advance — that is the whole
 * point of the placer's contract, and the reason this is a class and not a function.
 *
 * Not thread-safe, and neither is the placer: a stroke's dabs are built on the thread that is
 * drawing, in order.
 */
class BrushDabber(val preset: BrushPreset, seed: Long) {

    private val rng = SplitMix(seed)

    /** One draw per STROKE, taken before the first dab so a whole stroke shares it. */
    private val strokeRandom: Float = rng.nextFloat()

    private val buildUp: Boolean = preset.accumulate == "buildup"
    private val tracker = DirectionTracker()

    private var speedPxPerS = 0f
    private var hasTime = false
    private var prevTimeMs = 0.0
    private var prevDistance = 0f
    private var dabCount = 0
    private var hardness = preset.tip.hardness.base
    private var strokeOpacityValue = if (buildUp) preset.opacity.base else 1f

    /** [cc.joycreator.joybrush.core.paint.DabPlacer]'s `spacing`: a fraction of the dab diameter. */
    val spacing: Float = preset.spacing

    /**
     * Hardness for the whole stroke, as the shader takes it: one uniform, so it is evaluated at the
     * FIRST dab and then fixed. Before the first [look] it is the file's own base value.
     */
    val strokeHardness: Float get() = hardness

    /**
     * What opacity is applied to the whole stroke when it is committed — a BUILD_UP stroke's only
     * channel for it, and 1 for a WASH stroke, which gets its opacity as a per-dab cap instead.
     * Evaluated at the first [look]; before that, BUILD_UP carries `preset.opacity.base` and WASH
     * carries 1.
     */
    val strokeOpacity: Float get() = strokeOpacityValue

    /**
     * The brush's answer for one dab. Called once per dab, in stroke order, by the placer.
     *
     * [distancePx] is the distance travelled along the stroke so far and [index] is the dab's
     * number. The dabber keeps its OWN count for the first-dab captures rather than trusting
     * [index], so it stays correct however it is driven; [index] is part of the signature because
     * the placer's lambda is.
     *
     * A second call for the same dab gives a DIFFERENT answer — the stream has moved. That is not a
     * bug and must not be made into one: it is why the caller has to ask once (see [DabPlacer]).
     */
    fun look(sample: PenSample, distancePx: Float, index: Int): DabLook {
        // The tracker is fed the sample the placer INTERPOLATED for this dab, not the last raw
        // event, so its answer is the tip's direction here. It also returns the barrel reading
        // outright when the pen has that sensor.
        val direction = tracker.update(sample)

        // Three draws per dab, always, always in this order (decision 1): the `random` input, the
        // size jitter, the angle jitter. The COUNT is part of the brush's identity — a dabber that
        // drew two on a dab with no size jitter would put every later dab a step out of step with
        // the same seed — so they are drawn even when the file sets both jitters to zero.
        val random = rng.nextFloat()
        val sizeDraw = rng.nextFloat()
        val angleDraw = rng.nextFloat()

        stepSpeed(sample.timeMs, distancePx)

        val inputs = DabInputs(
            pressure = sample.pressure,
            tilt = sample.tilt,
            speedPxPerS = speedPxPerS,
            direction = direction,
            lean = sample.azimuth,
            distancePx = distancePx,
            random = random,
            strokeRandom = strokeRandom,
            barrel = sample.barrel,
        )

        // Size is a DIAMETER in the file; a radius is what a dab carries.
        var diameter = Dynamics.eval(preset.size, inputs)
        diameter *= 1f + preset.sizeJitter * (2f * sizeDraw - 1f)
        // A curve with a negative y can ask for no dab at all; minPx is the floor that stops that,
        // and it is the only guard here — nothing is divided by a value that can be zero.
        val radius = max(diameter, preset.tip.minPx) / 2f

        // Angle in radians, and the file's own angle is in DEGREES. A NaN from that one curve must
        // not wipe out the direction the tracker found, so it is the only term that is guarded.
        var angle = 0f
        val angleDeg = Dynamics.eval(preset.tip.angle, inputs)
        if (!angleDeg.isNaN()) angle = degToRad(angleDeg)
        if (preset.tip.followDirection) angle += direction
        angle += degToRad(preset.angleJitter) * (2f * angleDraw - 1f)

        // A NaN flow would make the placer drop the dab's flow to 0 and the brush would vanish, so
        // a NaN here stands for "draw normally". A NaN CAP is the opposite: NaN is the sentinel for
        // "use the placer's cap", so it passes straight through.
        val flow = unit(Dynamics.eval(preset.flow, inputs), 1f)
        val cap = if (buildUp) 1f else unit(Dynamics.eval(preset.opacity, inputs), Float.NaN)

        if (dabCount == 0) {
            // A shader uniform out of range draws nothing sensible, and a NaN one poisons the whole
            // batch, so the stroke's two whole-stroke numbers are the ones that get clamped.
            hardness = unit(Dynamics.eval(preset.tip.hardness, inputs), preset.tip.hardness.base)
            if (buildUp) strokeOpacityValue = unit(Dynamics.eval(preset.opacity, inputs), 1f)
        }
        dabCount++

        return DabLook(radius = radius, angle = angle, flow = flow, cap = cap)
    }

    /**
     * The speed filter, over the dabs rather than over the raw events: what matters is how fast the
     * stroke is being laid down, not how often the device reports.
     *
     * `raw = distance / time × 1000` px/s, then an exponential move of `1 − exp(−dt / 50ms)`. A dab
     * that shares its timestamp with the last one (or goes backwards) carries the old speed rather
     * than dividing by a time of zero.
     */
    private fun stepSpeed(timeMs: Double, distancePx: Float) {
        if (!hasTime) {
            hasTime = true
            prevTimeMs = timeMs
            prevDistance = distancePx
            return
        }
        val dt = timeMs - prevTimeMs
        if (dt > 0.0) {
            // dt so small it rounds to zero as a float would divide by zero; the result is dropped
            // instead, and the old speed stands.
            val raw = (distancePx - prevDistance) / dt.toFloat() * 1000f
            if (raw.isFinite()) {
                val k = (1.0 - exp(-dt / SPEED_TAU_MS)).toFloat()
                speedPxPerS += (raw - speedPxPerS) * k
            }
        }
        prevTimeMs = timeMs
        prevDistance = distancePx
    }

    /** 0..1, with [fallback] standing in for a NaN the file could not answer. */
    private fun unit(v: Float, fallback: Float): Float = if (v.isNaN()) fallback else v.coerceIn(0f, 1f)

    private companion object {
        /** The speed filter's time constant, in milliseconds. */
        const val SPEED_TAU_MS = 50.0
    }
}

/** Degrees to radians, the same way the rest of the engine does it (`StrokeSmoother`). */
private fun degToRad(deg: Float): Float = deg * PI.toFloat() / 180f
