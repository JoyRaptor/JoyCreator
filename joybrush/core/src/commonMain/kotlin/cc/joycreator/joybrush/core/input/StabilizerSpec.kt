package cc.joycreator.joybrush.core.input

import kotlinx.serialization.Serializable

/** Unibrush v18 contact/replay contract. Binary codes are explicit; never reorder/reuse them. */
@Serializable
enum class StabilizerMode(val code: Int) {
    off(0), smooth(1), pulledString(2), movingAverage(3), catchUpOnLift(4)
}

/**
 * Exact settings selected at contact. Radius is measured in screen pixels at contact zoom.
 * Smooth reuses StrokeSmoother. Pulled-string leaves the rope trailing on lift; catch-up
 * uses the same rope and emits the real lift point on finish. Moving average is causal,
 * averaging only positions; pressure, tilt, time and angles stay those of each input.
 */
@Serializable
data class StabilizerSpec(
    val mode: StabilizerMode = StabilizerMode.smooth,
    val strength: Float = .35f,
    val radiusPx: Float = 32f,
    val windowSize: Int = 8,
) {
    fun problems(): List<String> = buildList {
        if (!strength.isFinite() || strength !in 0f..1f) add("Stabilizer strength must be between 0 and 1")
        if (!radiusPx.isFinite() || radiusPx !in 0f..512f) add("Stabilizer radius must be between 0 and 512 screen pixels")
        if (windowSize !in 1..256) add("Stabilizer window must contain between 1 and 256 samples")
    }
}

@Serializable
enum class StabilizerPolicy { brushSuggestion, alwaysMine }

@Serializable
data class StabilizerPreference(
    val policy: StabilizerPolicy = StabilizerPolicy.brushSuggestion,
    val mine: StabilizerSpec = StabilizerSpec(),
) {
    /** Resolve ONCE at contact, freeze this value in StrokeCapture, then use it live. */
    fun resolve(suggestion: StabilizerSpec?, legacyBrushSmoothing: Float): StabilizerSpec =
        if (policy == StabilizerPolicy.alwaysMine) mine
        else suggestion ?: StabilizerSpec(strength = legacyBrushSmoothing)
}
