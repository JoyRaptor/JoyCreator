package cc.joycreator.joybrush.core.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HalfFloatTest {
    @Test fun everyHalfRoundTripsExactly() {
        for (h in 0 until 0x10000) {
            val f = HalfFloat.toFloat(h)
            if (f.isNaN()) { assertTrue(HalfFloat.toFloat(HalfFloat.toHalf(f)).isNaN()); continue }
            assertEquals(h, HalfFloat.toHalf(f), "half 0x${h.toString(16)} = $f")
        }
    }

    @Test fun knownValuesAndRounding() {
        assertEquals(0x3C00, HalfFloat.toHalf(1f))
        assertEquals(0xC000, HalfFloat.toHalf(-2f))
        assertEquals(0x7BFF, HalfFloat.toHalf(65504f))
        assertEquals(0x7C00, HalfFloat.toHalf(65520f), "past the largest half rounds to Infinity")
        assertEquals(0x0001, HalfFloat.toHalf(5.9604645e-8f), "the smallest subnormal")
        assertEquals(0x0000, HalfFloat.toHalf(2.0e-8f), "below half the smallest subnormal is zero")
        // 1 + 2^-11 is exactly between 1 and the next half: ties go to the even one (1).
        assertEquals(0x3C00, HalfFloat.toHalf(1f + 1f / 2048))
        assertEquals(0x3C02, HalfFloat.toHalf(1f + 3f / 2048), "and the other tie goes up to even")
        // A media amount: a 0.002 mm deposit keeps three significant figures.
        val v = HalfFloat.toFloat(HalfFloat.toHalf(0.002f))
        assertTrue(kotlin.math.abs(v - 0.002f) / 0.002f < 1e-3f, "$v")
    }

    @Test fun bytesAreLittleEndianPairs() {
        val bytes = HalfFloat.encode(floatArrayOf(1f, -2f))
        assertEquals(listOf(0x00, 0x3C, 0x00, 0xC0), bytes.map { it.toInt() and 0xFF })
        assertEquals(listOf(1f, -2f), HalfFloat.decode(bytes).toList())
    }
}
