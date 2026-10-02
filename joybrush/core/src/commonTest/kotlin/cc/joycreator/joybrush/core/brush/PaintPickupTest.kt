package cc.joycreator.joybrush.core.brush

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PaintPickupTest {
    private fun mix(source: FloatArray, amount: Float = 0.8f,
                    carried: FloatArray = floatArrayOf(0f, 0.5f, 0f, 0.5f)): FloatArray =
        FloatArray(4).also { PaintPickup.mix(carried[0], carried[1], carried[2], carried[3],
            source[0], source[1], source[2], source[3], amount, it) }

    @Test fun alternatingUnderpaintRetainsDifferentPixelColorsInsteadOfOneMean() {
        val red = mix(floatArrayOf(1f, 0f, 0f, 1f))
        val blue = mix(floatArrayOf(0f, 0f, 1f, 1f))
        assertEquals(0.4f, red[0], 1e-6f); assertEquals(0f, red[2])
        assertEquals(0.4f, blue[2], 1e-6f); assertEquals(0f, blue[0])
        assertEquals(0.1f, red[1], 1e-6f); assertEquals(0.1f, blue[1], 1e-6f)
        assertEquals(0.5f, red[3]); assertEquals(0.5f, blue[3])
    }

    @Test fun aFaintSourceTransfersStraightColorWeightedByItsAlphaWithoutAHalo() {
        // Source is red at alpha .25; carried paint has alpha .5. Weight=.8*.25=.2.
        val out = mix(floatArrayOf(0.25f, 0f, 0f, 0.25f))
        assertContentEquals(floatArrayOf(0.1f, 0.4f, 0f, 0.5f), out)
        assertTrue(out.take(3).all { it <= out[3] })
        assertContentEquals(FloatArray(4), mix(floatArrayOf(0.25f, 0f, 0f, 0.25f),
            carried = FloatArray(4)))
    }

    @Test fun emptySourceAndZeroPickupKeepTheCarriedPaintExactly() {
        val carried = floatArrayOf(0.08f, 0.12f, 0.2f, 0.3f)
        assertContentEquals(carried, mix(FloatArray(4), carried = carried))
        assertContentEquals(carried, mix(floatArrayOf(1f, 0f, 0f, 1f), amount = 0f, carried = carried))
    }

    @Test fun texturePickupAndPaintAreVersionSevenWordsAndRoundTrip() {
        for (spec in listOf(SmudgeSpec(texturePickup = 0.6f), SmudgeSpec(paint = true))) {
            val p = BrushPreset(version = 3, id = "test", name = "Test", engine = ENGINE_SMUDGE,
                size = Param(20f), smudge = spec)
            val encoded = BrushJson.encode(p)
            assertEquals(p.copy(version = 7), BrushJson.decodeChecked(encoded))
            val older = encoded.replace("\"version\": 7", "\"version\": 6")
            assertFailsWith<BrushException> { BrushJson.decode(older) }
            assertTrue(BrushValidate.validate(p).any { it.contains("needs brush version 7") })
        }
        val legacy = BrushPreset(version = 3, id = "old", name = "Old", engine = ENGINE_SMUDGE, size = Param(20f))
        assertEquals(legacy, BrushJson.decodeChecked(BrushJson.encode(legacy)))
        assertEquals(0f, legacy.smudge.texturePickup)
        assertEquals(false, legacy.smudge.paint)
    }

    @Test fun nonfiniteAndOutOfRangePickupIsRefusedByValidationAndCpuLaw() {
        for (amount in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -0.01f, 1.01f)) {
            val p = BrushPreset(id = "bad", name = "Bad", size = Param(20f),
                smudge = SmudgeSpec(texturePickup = amount))
            assertTrue(BrushValidate.validate(p).any { it.contains("smudge.texturePickup") }, "$amount")
            assertFailsWith<IllegalArgumentException> { mix(FloatArray(4), amount) }
        }
        assertFailsWith<IllegalArgumentException> { mix(floatArrayOf(Float.NaN, 0f, 0f, 1f)) }
        assertFailsWith<IllegalArgumentException> { mix(floatArrayOf(1f, 0f, 0f, 0.2f)) }
    }
}
