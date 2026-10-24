package cc.joycreator.joybrush.core.brush

import cc.joycreator.joybrush.core.input.Angles
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.media.DOC_PX_PER_MM
import kotlinx.serialization.Serializable
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.hypot

/** Serialized names are append-only; new names require a brush version bump (R3). */
@Serializable enum class TipAngleSource { fixed, stroke, penAzimuth, penBarrel }

/**
 * Explicit Unibrush rotation, independent of the legacy hybrid DirectionTracker used by dynamics.
 * One per stroke, no clock/zoom/device inputs or random draws. The 1.5 mm filter uses the existing
 * document-space media convention (20 px/mm), so a recording reproduces it on any screen.
 * Missing sensors hold the last valid heading; before a reading, the fixed offset is used.
 */
internal class TipRotation(private val source: TipAngleSource) {
    private var hasPosition = false
    private var x = 0f
    private var y = 0f
    private var hasHeading = false
    private var heading = 0.0

    fun update(sample: PenSample): Float {
        if (source == TipAngleSource.fixed) return 0f
        if (!sample.isPlaceable) return heading.toFloat()
        val step = if (hasPosition) hypot((sample.x - x).toDouble(), (sample.y - y).toDouble()) else 0.0
        val target = when (source) {
            TipAngleSource.fixed -> 0.0
            TipAngleSource.stroke -> if (step >= MIN_STEP) atan2((sample.y - y).toDouble(), (sample.x - x).toDouble()) else Double.NaN
            TipAngleSource.penAzimuth -> sample.azimuth.toDouble()
            TipAngleSource.penBarrel -> sample.barrel.toDouble()
        }
        // Sub-pixel moves accumulate instead of starving the filter when dab spacing is small.
        if (!hasPosition || step >= MIN_STEP) {
            hasPosition = true
            x = sample.x
            y = sample.y
        }
        if (target.isFinite()) {
            if (!hasHeading) {
                heading = Angles.wrap(target)
                hasHeading = true
            } else if (step >= MIN_STEP) {
                val blend = 1.0 - exp(-step / LENGTH_PX)
                // Angular short-arc filtering cannot stick at a perfect 180-degree reversal.
                heading = Angles.wrap(heading + Angles.wrap(target - heading) * blend)
            }
        }
        return heading.toFloat()
    }

    private companion object {
        const val MIN_STEP = 0.25
        const val LENGTH_PX = 1.5 * DOC_PX_PER_MM
    }
}
