package cc.joycreator.joybrush.core.paint

import cc.joycreator.joybrush.core.input.Angles
import cc.joycreator.joybrush.core.input.PenSample
import kotlin.math.hypot
import kotlin.math.max

/**
 * What the brush decides about ONE dab. Asked exactly once per dab (see [DabPlacer]).
 * [cap] overrides the placer's cap for this dab (NaN = use the placer's) — this is how a WASH brush
 * fades with pressure: each dab's ceiling is its own opacity.
 */
data class DabLook(val radius: Float, val angle: Float = 0f, val flow: Float = 1f, val cap: Float = Float.NaN,
    val aspect: Float = Float.NaN,
    val hardness: Float = Float.NaN,
    val tipDepth: Float = Float.NaN,
    val paperDepth: Float = Float.NaN,
    val anchor: Float = Float.NaN,
    val fadeAmount: Float = Float.NaN,
    val fadeAngle: Float = Float.NaN,
    val profile: cc.joycreator.joybrush.core.brush.TipProfileSpec? = null,
    val profileB: cc.joycreator.joybrush.core.brush.TipProfileSpec? = null,
    val profileMix: Float = 0f,
    val quad: cc.joycreator.joybrush.core.brush.QuadDab? = null,
    val color: cc.joycreator.joybrush.core.brush.ColorDab? = null,
    val sampled: cc.joycreator.joybrush.core.brush.SampledStamp? = null,
    val spacingMultiplier: Float = 1f,
)

/**
 * Turns a stream of (smoothed) pen samples into evenly spaced dabs.
 *
 * Spacing is a fraction of the dab DIAMETER at that point (never below [minSpacingPx]). The leftover
 * distance is carried between calls, so the dabs are identical however the samples happen to be
 * batched — the result does not depend on the device's event rate (MyPaint's rule, R3).
 * Every channel is interpolated between the two samples a dab falls between.
 *
 * The brush decides each dab through [look], which is called **exactly once per dab, in stroke
 * order**, with the distance travelled along the stroke so far and the dab's index. So a brush may
 * keep state inside it (a random generator, a speed filter) without double-counting — the JB-0.03
 * builder caught the earlier version asking for the radius twice.
 */
class DabPlacer(
    private val spacing: Float,
    private val look: (sample: PenSample, distancePx: Float, index: Int) -> DabLook,
    private val cap: Float = 1f,
    private val minSpacingPx: Float = 0.5f,
) {
    /** Convenience for brushes whose dabs depend only on the sample. */
    constructor(
        spacing: Float,
        radiusOf: (PenSample) -> Float,
        angleOf: (PenSample) -> Float = { 0f },
        flowOf: (PenSample) -> Float = { 1f },
        cap: Float = 1f,
        minSpacingPx: Float = 0.5f,
    ) : this(spacing, { s, _, _ -> DabLook(radiusOf(s), angleOf(s), flowOf(s)) }, cap, minSpacingPx)

    private var prev: PenSample? = null
    private var untilNext = 0f
    private var travelled = 0f
    private var index = 0
    private val travel = DabTravel()

    fun add(samples: List<PenSample>): List<Dab> {
        val out = ArrayList<Dab>()
        addTo(samples) { out.add(it) }
        return out
    }

    /** Same state/arithmetic as [add], without an intermediate placed-dab list. A throwing sink
     * aborts the placement; do not resume or reenter this placer after admission refusal. */
    fun addTo(samples: List<PenSample>, emit: (Dab) -> Unit) {
        for (sample in samples) addOne(sample, emit)
    }

    /** One final smoothed point, with no one-element collection allocation. */
    fun addTo(sample: PenSample, emit: (Dab) -> Unit) = addOne(sample, emit)

    private fun addOne(s: PenSample, emitDab: (Dab) -> Unit) {
        val p = prev
        if (p == null) {
            untilNext = emit(s, 0f, emitDab)
            prev = s
            return
        }
        val len = hypot(s.x - p.x, s.y - p.y)
        if (len <= 0f) { prev = s; return }
        var along = untilNext
        while (along <= len) {
            along += emit(lerp(p, s, along / len), travelled + along, emitDab)
        }
        untilNext = along - len
        travelled += len
        prev = s
    }

    /** Emits one dab and returns the distance to the next one. */
    private fun emit(s: PenSample, distance: Float, emitDab: (Dab) -> Unit): Float {
        val l = look(s, distance, index++)
        travel.update(s.x, s.y)
        // A brush file can only reach here after validation, but the placer must never hang or
        // explode on a bad number (review JB-0.03 F1: size 1e999 = Infinity froze the stroke).
        val radius = if (l.radius.isFinite()) l.radius.coerceIn(0f, MAX_RADIUS_PX) else 0f
        emitDab(Dab(x = s.x, y = s.y, radius = radius, angle = if (l.angle.isFinite()) l.angle else 0f,
            flow = if (l.flow.isFinite()) l.flow.coerceIn(0f, 1f) else 0f,
            cap = if (l.cap.isNaN()) cap else l.cap.coerceIn(0f, 1f), pressure = s.pressure,
            tilt = s.tilt, azimuth = s.azimuth, travelX = travel.x, travelY = travel.y, travelKnown = true,
            profile = l.profile, fadeAmount = optional(l.fadeAmount, 0f, 1f), fadeAngle = if (l.fadeAngle.isFinite()) l.fadeAngle else Float.NaN,
            quad = l.quad, profileB = l.profileB, profileMix = l.profileMix,
            color = l.color,
            sampled = l.sampled,
            aspect = optional(l.aspect, -1f, 1f), hardness = optional(l.hardness, 0f, 1f),
            tipDepth = optional(l.tipDepth, 0f, 1f), paperDepth = optional(l.paperDepth, 0f, 1f),
            anchor = optional(l.anchor, 0f, 1f)))
        val baseStep = 2f * radius * spacing
        val multiplier = if (l.spacingMultiplier.isFinite()) l.spacingMultiplier.coerceIn(0f, 2f) else 1f
        val step = if (multiplier == 1f) baseStep else baseStep * multiplier
        return if (step.isFinite()) max(step, minSpacingPx) else minSpacingPx
    }

    private fun optional(v: Float, lo: Float, hi: Float): Float =
        if (v.isFinite()) v.coerceIn(lo, hi) else Float.NaN

    private fun lerp(a: PenSample, b: PenSample, t: Float) = PenSample(
        x = a.x + (b.x - a.x) * t,
        y = a.y + (b.y - a.y) * t,
        timeMs = a.timeMs + (b.timeMs - a.timeMs) * t,
        pressure = a.pressure + (b.pressure - a.pressure) * t,
        tilt = if (a.tilt.isNaN() || b.tilt.isNaN()) Float.NaN else a.tilt + (b.tilt - a.tilt) * t,
        // Angles take the short way round (review JB-0.07 F3: these were copied from the later sample).
        azimuth = Angles.lerp(a.azimuth, b.azimuth, t),
        barrel = Angles.lerp(a.barrel, b.barrel, t),
        tool = b.tool,
    )

    companion object {
        /** Largest dab radius the engine accepts (a 4096 px wide brush). */
        const val MAX_RADIUS_PX = 2048f
    }
}
