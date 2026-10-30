package cc.joycreator.joybrush.core.brush

import cc.joycreator.joybrush.core.input.PenSample
import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.floor

/** Serialized names are append-only once landed. */
@Serializable enum class SamplePick { sequence, random, byPressure, byDirection }
@Serializable enum class SampleCoverage { alpha, luminance }

/** Opt-in sampled recipe. The old TipSpec.image path remains an untouched legacy field. */
@Serializable data class SampledTipSpec(
    val images: List<String>,
    val pick: SamplePick = SamplePick.sequence,
    /** Symmetric displacement in degrees: -value through +value. */
    val rotateJitterDegrees: Float = 0f,
    val invert: Boolean = false,
    val coverage: SampleCoverage = SampleCoverage.alpha,
    val spacingJitter: Float = 0f,
)

object SampledTips {
    const val MAX_IMAGES = 16

    fun problems(spec: SampledTipSpec): List<String> = buildList {
        if (spec.images.size !in 1..MAX_IMAGES) add("sampled tips need 1..$MAX_IMAGES images")
        for ((index, path) in spec.images.withIndex()) {
            if (!safePath(path)) add("sample image ${index + 1} needs a safe relative PNG path")
        }
        if (!spec.rotateJitterDegrees.isFinite() || spec.rotateJitterDegrees !in 0f..360f)
            add("sample rotation jitter must be 0..360 degrees")
        if (!spec.spacingJitter.isFinite() || spec.spacingJitter !in 0f..1f)
            add("sample spacing jitter must be 0..1")
    }

    /** Duplicates are intentional weights/order entries; asset storage may deduplicate their bytes. */
    fun safePath(path: String): Boolean = path.length in 1..256 && path.endsWith(".png", ignoreCase = true) &&
        path.split('/').all { part -> part.isNotEmpty() && !part.endsWith('.') && ".." !in part &&
            part.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' || it == '.' } }
}

data class SampledDab(val imageIndex: Int, val rotationRadians: Float, val spacingMultiplier: Float)

/**
 * Called once per placed dab, before scatter. Only geometric direction has state. The indexed
 * random channels are independent of each other and of BrushDabber/Scatter's existing streams.
 * A caller may split event batches freely, but must feed the same placed samples in order.
 */
class SampledTipPicker(spec: SampledTipSpec, private val seed: Long) {
    private val recipe = spec.copy(images = spec.images.toList())
    private var previousX = Double.NaN
    private var previousY = Double.NaN
    private var heading = 0.0

    init {
        val errors = SampledTips.problems(recipe)
        if (errors.isNotEmpty()) throw BrushException(errors.joinToString("; "))
    }

    @Suppress("UNUSED_PARAMETER")
    fun next(sample: PenSample, distancePx: Float, index: Int): SampledDab {
        require(index >= 0) { "a sampled dab needs a nonnegative placed index" }
        // This deliberately never reads tilt, azimuth or barrel. The legacy DirectionTracker
        // prioritizes some of those sensors and is therefore the wrong input for image direction.
        if (sample.x.isFinite() && sample.y.isFinite()) {
            val x = sample.x.toDouble(); val y = sample.y.toDouble()
            if (previousX.isFinite() && (x != previousX || y != previousY)) heading = atan2(y - previousY, x - previousX)
            previousX = x; previousY = y
        }
        val count = recipe.images.size
        val selected = when (recipe.pick) {
            SamplePick.sequence -> index % count
            SamplePick.random -> (draw(PICK_SALT, index) * count).toInt().coerceAtMost(count - 1)
            SamplePick.byPressure -> {
                val pressure = if (sample.pressure.isFinite()) sample.pressure.coerceIn(0f, 1f) else 0f
                (pressure * count).toInt().coerceAtMost(count - 1)
            }
            // Bins are centered on +x, then clockwise in document coordinates (+y points down).
            // First contact points +x; dwell holds the previous direction rather than inventing one.
            SamplePick.byDirection -> {
                val turns = (heading / (2 * PI) + 1.0) % 1.0
                floor(turns * count + .5).toInt() % count
            }
        }
        return SampledDab(selected,
            (recipe.rotateJitterDegrees * (2f * draw(ROTATION_SALT, index) - 1f) * (PI / 180)).toFloat(),
            1f + recipe.spacingJitter * (2f * draw(SPACING_SALT, index) - 1f))
    }

    /** SplitMix64's fixed finalizer, keyed by channel and index, with no mutable stream consumption. */
    private fun draw(salt: Long, index: Int): Float {
        var z = (seed xor salt xor (index.toLong() * INDEX_STEP)) + INDEX_STEP
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6bf2b644eceeee15L
        z = z xor (z ushr 31)
        return (z ushr 40).toFloat() / 16777216f
    }

    private companion object {
        // These values, channel independence and the index mapping become frozen replay ABI on landing.
        const val INDEX_STEP = -0x61c8864680b583ebL
        const val PICK_SALT = 0x53414d504c455049L
        const val ROTATION_SALT = 0x53414d50524f5441L
        const val SPACING_SALT = 0x53414d5053504143L
    }
}
