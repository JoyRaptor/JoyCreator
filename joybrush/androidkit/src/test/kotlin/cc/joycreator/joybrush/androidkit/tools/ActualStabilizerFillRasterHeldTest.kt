package cc.joycreator.joybrush.androidkit.tools

import cc.joycreator.joybrush.core.brush.FillPen
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.input.StabilizerMode
import cc.joycreator.joybrush.core.input.StabilizerSpec
import cc.joycreator.joybrush.core.select.SelectionMask
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** DRAFT held Android adapter test, not compiled/run and not canonical. */
class ActualStabilizerFillRasterHeldTest {
    private val raw = listOf(20f to 20f, 20f to 20f, 180f to 28f, 180f to 28f,
        168f to 180f, 28f to 168f, 20f to 20f).mapIndexed { i, (x, y) -> PenSample(x, y, i * 16.0) }

    @Test fun actualAdapterMaskEqualsCoreOutlineAndWrongSmoothDiffers() {
        val spec = StabilizerSpec(StabilizerMode.movingAverage, 1f, 25f, 4)
        val expected = SelectionMask.polygon(FillPen.outline(raw, .73f, 2f, spec))
        val actual = FillPenRaster.prepare(raw, .73f, 2f, spec)
        assertEquals(expected.tileKeys, actual.tileKeys)
        for (key in expected.tileKeys) assertContentEquals(expected.tile(key), actual.tile(key))
        val wrong = FillPenRaster.prepare(raw, .73f, 2f)
        assertTrue(expected.tileKeys != wrong.tileKeys || expected.tileKeys.any { key ->
            !(expected.tile(key)?.contentEquals(wrong.tile(key) ?: ByteArray(0)) ?: (wrong.tile(key) == null))
        })
    }

    @Test fun invalidSettingsCannotHideBehindAnEmptyGesture() {
        assertFailsWith<IllegalArgumentException> {
            FillPenRaster.prepare(emptyList(), .3f, 1f, StabilizerSpec(windowSize = 0))
        }
    }
}
