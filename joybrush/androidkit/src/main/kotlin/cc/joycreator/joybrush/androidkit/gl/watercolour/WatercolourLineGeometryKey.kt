package cc.joycreator.joybrush.androidkit.gl.watercolour

import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.stroke.StrokeRecord

/**
 * Geometry cache identity for a recorded watercolor line. Only tint and the finish-only wash frame
 * are omitted; every other record field and the complete frozen brush participate in equality.
 * Samples are copied when the key is made so a caller-owned mutable list cannot change the key.
 */
internal class WatercolourLineGeometryKey private constructor(
    private val recordRecipe: StrokeRecord,
    private val preset: BrushPreset,
) {
    override fun equals(other: Any?): Boolean = other is WatercolourLineGeometryKey &&
        recordRecipe == other.recordRecipe && preset == other.preset

    override fun hashCode(): Int = 31 * recordRecipe.hashCode() + preset.hashCode()

    companion object {
        fun of(record: StrokeRecord, preset: BrushPreset): WatercolourLineGeometryKey =
            WatercolourLineGeometryKey(
                record.copy(colorArgb = 0, washFrame = null, samples = record.samples.toList()),
                preset,
            )
    }
}
