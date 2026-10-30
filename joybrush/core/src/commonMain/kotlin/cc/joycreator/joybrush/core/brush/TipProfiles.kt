package cc.joycreator.joybrush.core.brush

import cc.joycreator.joybrush.core.dynamics.Curve
import kotlinx.serialization.Serializable
import kotlin.concurrent.Volatile
import kotlin.math.exp

/** Serialized names/codes are append-only (R3); zero stays the legacy profile. */
@Serializable enum class TipProfile(val code: Int) { hard(1), linear(2), smooth(3), gaussian(4), custom(5) }
@Serializable enum class FadeFrame { tip, canvas, penAzimuth }

/** Curve coordinates are radius/coverage, both 0..1. A custom rim ends at zero. */
@Serializable data class TipProfileSpec(
    val kind: TipProfile = TipProfile.smooth,
    val curve: List<List<Float>> = listOf(listOf(0f, 1f), listOf(1f, 0f)),
)

@Serializable data class TipFadeSpec(
    val amount: Param = Param(0f),
    val angle: Float = 0f,
    val relativeTo: FadeFrame = FadeFrame.tip,
)

const val VERSION_TIP_PROFILE = 13

/** Shared finite-support radial profiles. The shader uses the same 256-entry custom lookup. */
object TipProfiles {
    fun coverage(spec: TipProfileSpec, t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return when (spec.kind) {
            TipProfile.hard -> if (x < 1f) 1f else 0f
            TipProfile.linear -> 1f - x
            TipProfile.smooth -> 1f - x * x * (3f - 2f * x)
            TipProfile.gaussian -> ((exp(-4.5f * x * x) - TAIL) / (1f - TAIL)).coerceIn(0f, 1f)
            TipProfile.custom -> {
                val samples = lut(spec)
                val f = x * 255f
                val i = f.toInt().coerceIn(0, 254)
                (samples[i] + (samples[i + 1] - samples[i]) * (f - i)).coerceIn(0f, 1f)
            }
        }
    }

    /** Internal read-only lookup; consumers must not mutate it. Built once, never per dab/pixel. */
    internal fun lut(spec: TipProfileSpec): FloatArray {
        for (entry in cache) if (entry.first === spec) return entry.second
        val curve = Curve(spec.curve.mapNotNull { if (it.size == 2) it[0] to it[1] else null })
        val samples = FloatArray(256) { curve.eval(it / 255f).coerceIn(0f, 1f) }
        val previous = cache
        cache = if (previous.size >= 16) listOf(spec to samples) else previous + (spec to samples)
        return samples
    }

    /** A defensive copy for a platform uploader/fixture: no caller can poison replay's cached LUT. */
    fun samples(spec: TipProfileSpec): FloatArray = lut(spec).copyOf()

    private val TAIL = exp(-4.5f)
    @Volatile private var cache: List<Pair<TipProfileSpec, FloatArray>> = emptyList()
}
