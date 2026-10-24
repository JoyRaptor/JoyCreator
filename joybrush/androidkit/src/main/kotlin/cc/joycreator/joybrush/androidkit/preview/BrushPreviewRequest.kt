package cc.joycreator.joybrush.androidkit.preview

import cc.joycreator.joybrush.core.brush.BrushAssetPaths
import cc.joycreator.joybrush.core.brush.BrushJson
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.FrozenBrushPngs
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.input.StabilizerSpec
import cc.joycreator.joybrush.core.input.StabilizerPreference

/** A scrub-pad reading, applied before the brush's pressure and tilt response curves. */
data class PreviewPenOverride(val pressure: Float, val tiltRadians: Float) {
    init {
        require(pressure.isFinite() && pressure in 0f..1f) { "Preview pressure must be between 0 and 1" }
        require(tiltRadians.isFinite() && tiltRadians in 0f..(kotlin.math.PI.toFloat() / 2f)) {
            "Preview tilt must be between 0 and PI/2"
        }
    }
    fun apply(samples: List<PenSample>): List<PenSample> = samples.map {
        it.copy(pressure = pressure, tilt = tiltRadians)
    }
    val key: String get() = "${pressure.toRawBits()}:${tiltRadians.toRawBits()}"
}

/**
 * UI-thread snapshot handed intact to the cache and then the GL owner. The flight owns the immutable
 * PNG bundle; only [resourceFingerprint] enters a retained bitmap key. No library/provider is consulted
 * after admission. Decoded masks belong to the renderer's independent GL-thread resolver.
 */
class BrushPreviewRequest private constructor(
    val preset: BrushPreset,
    val width: Int,
    val height: Int,
    val style: BrushPreviewStyle,
    val pngs: FrozenBrushPngs?,
    val actualStabilizer: StabilizerSpec?,
    val penOverride: PreviewPenOverride?,
    val resourceFingerprint: String,
) {
    val requiresResourceAwareMaker: Boolean
        get() = pngs != null || actualStabilizer != null || penOverride != null

    /** Exact stored settings, including inactive fields; null preserves the legacy preview path. */
    val actualStabilizerKey: String get() = actualStabilizer?.let {
        "${it.mode.code}:${it.strength.toRawBits()}:${it.radiusPx.toRawBits()}:${it.windowSize}"
    } ?: "legacy"

    companion object {
        /** Match live contact policy: historical brushes keep their historical canned preview. */
        fun defaultActualStabilizer(preset: BrushPreset, preference: StabilizerPreference,
                                    legacySmoothing: Float = preset.smoothing): StabilizerSpec? =
            if (preset.engine == "stamp" && preset.extensions["unibrush.family"] in setOf("builtin", "user"))
                preference.resolve(preset.stabilizer, legacySmoothing) else null

        fun snapshot(
            preset: BrushPreset, width: Int, height: Int,
            style: BrushPreviewStyle = BrushPreviewStyle(),
            pngs: FrozenBrushPngs? = null,
            actualStabilizer: StabilizerSpec? = null,
            penOverride: PreviewPenOverride? = null,
        ): BrushPreviewRequest {
            // Detach lists/maps from the editable sheet before a queued IO or GL operation can run.
            val frozen = BrushJson.decodeChecked(BrushJson.encode(preset))
            val required = BrushAssetPaths.required(frozen)
            if (required.isNotEmpty()) requireNotNull(pngs) { "The preview needs its frozen PNG resources" }
            pngs?.requirePaths(required)
            actualStabilizer?.let { require(it.problems().isEmpty()) { it.problems().joinToString("; ") } }
            if (actualStabilizer != null) {
                require(frozen.engine == "stamp") { "Actual stabilizer settings require a stamp preview" }
                require(!cc.joycreator.joybrush.core.vector.LineStyle.of(frozen.line).isWire) {
                    "Actual stabilizer settings on wire previews are not supported"
                }
            }
            if (penOverride != null) {
                require(PreviewPlan.kindOf(frozen) in setOf(PreviewKind.STAMP, PreviewKind.SMUDGE)) {
                    "Pen overrides require a stamp or smudge preview"
                }
            }
            return BrushPreviewRequest(frozen, width, height, style.copy(), pngs,
                actualStabilizer?.copy(), penOverride?.copy(), PreviewResourceFingerprint.of(pngs))
        }
    }
}
