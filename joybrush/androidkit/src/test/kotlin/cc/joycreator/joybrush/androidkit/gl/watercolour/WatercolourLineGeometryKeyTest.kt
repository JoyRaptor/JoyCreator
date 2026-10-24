package cc.joycreator.joybrush.androidkit.gl.watercolour

import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.ENGINE_WATERCOLOUR
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.brush.WatercolourSpec
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.stroke.StrokeWashFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class WatercolourLineGeometryKeyTest {
    private val sample = PenSample(12f, 34f, 56.0, 0.7f, 0.2f, 0.3f, 0.4f)

    private fun record(samples: List<PenSample> = listOf(sample)) = StrokeRecord(
        id = "line", brushId = "wash", seed = 17L, smoothing = 0.25f, screenPerDoc = 1.5f,
        samples = samples, colorArgb = 0xFF123456.toInt(), widthScale = 1.25f,
    )

    private fun preset() = BrushPreset(
        id = "wash", name = "Wash", engine = ENGINE_WATERCOLOUR,
        size = Param(12f), watercolour = WatercolourSpec(),
    )

    private fun key(line: StrokeRecord = record(), preset: BrushPreset = preset()) =
        WatercolourLineGeometryKey.of(line, preset)

    @Test
    fun tintAndFinishOnlyFrameChangesReuseGeometry() {
        val original = record()
        val frame = StrokeWashFrame(1.0, 0.1, 4.0, -0.05, 1.0, 8.0, 0.0001, 0.0, 1.0)
        assertEquals(key(original), key(original.copy(colorArgb = 0xFFABCDEF.toInt(), washFrame = frame)))
        assertEquals(key(original.copy(washFrame = StrokeWashFrame.IDENTITY)), key(original))
    }

    @Test
    fun everyRecordGeometryAndRecipeChangeInvalidates() {
        val original = record()
        val changes = listOf(
            original.copy(smoothing = 0.5f),
            original.copy(screenPerDoc = 2f),
            original.copy(seed = original.seed + 1),
            original.copy(samples = listOf(sample.copy(x = sample.x + 1f))),
            original.copy(widthScale = 2f),
            original.copy(id = "another-line"),
            original.copy(brushId = "another-brush"),
            original.copy(seq = 2L),
            original.copy(locked = true),
            original.copy(groupId = "group"),
        )
        for ((index, changed) in changes.withIndex()) {
            assertNotEquals(key(original), key(changed), "record change $index must invalidate retained coverage")
        }
    }

    @Test
    fun completeBrushPresetParticipatesInTheKey() {
        val brush = preset()
        assertNotEquals(key(record(), brush), key(record(), brush.copy(name = "Renamed")))
        assertNotEquals(key(record(), brush), key(record(), brush.copy(size = Param(13f))))
        assertNotEquals(key(record(), brush), key(record(), brush.copy(
            watercolour = requireNotNull(brush.watercolour).copy(textureScale = 2.0),
        )))
    }

    @Test
    fun keySnapshotsCallerOwnedSamples() {
        val mutableSamples = mutableListOf(sample)
        val expected = key(record())
        val captured = key(record(mutableSamples))
        mutableSamples[0] = sample.copy(x = -500f)
        assertEquals(expected, captured)
        assertNotEquals(expected, key(record(mutableSamples)))
    }
}
