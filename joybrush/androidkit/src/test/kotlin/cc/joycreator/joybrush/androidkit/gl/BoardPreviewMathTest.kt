package cc.joycreator.joybrush.androidkit.gl

import org.junit.Assert.*
import org.junit.Test

class BoardPreviewMathTest {
    @Test fun allocationRoundsUpToTheStepAndNeverPastTheTextureLimit() {
        assertEquals(256, BoardPreviewMath.allocation(1, 16384))
        assertEquals(256, BoardPreviewMath.allocation(256, 16384))
        assertEquals(512, BoardPreviewMath.allocation(257, 16384))
        assertEquals(4096, BoardPreviewMath.allocation(4096, 16384))
        // Capped: a 4000 px board on a 4096 limit is allocated 4000 rounded to 4096, and 4090 against a limit of 4090 stays 4090.
        assertEquals(4096, BoardPreviewMath.allocation(4000, 4096))
        assertEquals(4090, BoardPreviewMath.allocation(4090, 4090))
        assertEquals(2000, BoardPreviewMath.allocation(1990, 2000))
    }
    @Test fun allocationAlwaysHoldsTheCropAndStaysPutWhileTheCropGrowsInsideOneStep() {
        for (used in 1..2048) {
            val a = BoardPreviewMath.allocation(used, 16384)
            assertTrue("holds $used", a >= used); assertTrue("less than a step of waste for $used", a - used < BoardPreviewMath.STEP)
        }
        // A drag from 300 to 512 px is one allocation, so no texture is remade on the way.
        assertEquals(1, (300..512).map { BoardPreviewMath.allocation(it, 16384) }.toSet().size)
    }
    @Test fun allocationRefusesWhatCannotFit() {
        for (bad in listOf(0, -1, 4097)) try { BoardPreviewMath.allocation(bad, 4096); fail("must refuse $bad") } catch (_: IllegalArgumentException) {}
    }
    @Test fun repeatIsExactForTheUsedSizeWhateverTheTextureSize() {
        val used = 300
        // Inside the board: texel = floor(uv * used).
        assertEquals(0, BoardPreviewMath.texel(0.0, used))
        assertEquals(150, BoardPreviewMath.texel(0.5, used))
        assertEquals(299, BoardPreviewMath.texel(0.9999999, used))
        // Neighbour copies repeat the same texels: left of the origin and several widths away.
        assertEquals(BoardPreviewMath.texel(0.75, used), BoardPreviewMath.texel(-0.25, used))
        assertEquals(BoardPreviewMath.texel(0.75, used), BoardPreviewMath.texel(3.75, used))
        // The seam: exactly one board width along is texel 0 again, never `used` (a stale texel).
        assertEquals(0, BoardPreviewMath.texel(1.0, used)); assertEquals(0, BoardPreviewMath.texel(-2.0, used))
    }
    @Test fun noCoordinateEverReadsBeyondTheUsedCorner() {
        for (used in listOf(1, 2, 3, 255, 257, 1000)) {
            var uv = -3.0
            while (uv <= 3.0) { val t = BoardPreviewMath.texel(uv, used); assertTrue("uv=$uv used=$used got $t", t in 0 until used); uv += 0.000731 }
            // A fract() that rounds up to exactly 1.0 in the shader is clamped to the last real texel.
            assertEquals(used - 1, BoardPreviewMath.texel(Math.nextDown(1.0), used))
        }
    }
    @Test fun whenTheSizesMatchItIsTheOldNearestSample() {
        // The previous shader sampled texture(fract(uv)) with NEAREST on a texture exactly `used` wide: texel floor(fract*used).
        val used = 256
        var uv = -1.7
        while (uv < 2.3) { val f = uv - Math.floor(uv); assertEquals(Math.floor(f * used).toInt().coerceAtMost(used - 1), BoardPreviewMath.texel(uv, used)); uv += 0.00137 }
    }
}
