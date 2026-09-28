package cc.joycreator.joybrush.core.grain

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CloudNoiseTest {

    @Test
    fun sameArgsGiveTheSameCloud() {
        assertContentEquals(
            CloudNoise.generate(256, 1L),
            CloudNoise.generate(256, 1L),
        )
        assertFalse(
            CloudNoise.generate(256, 1L).contentEquals(CloudNoise.generate(256, 2L)),
            "seed 1 and seed 2 produced the same cloud",
        )
        assertFalse(
            CloudNoise.generate(256, 1L).contentEquals(CloudNoise.generate(256, 1L, octaves = 3)),
            "octaves did not change the cloud",
        )
        assertFalse(
            CloudNoise.generate(256, 1L).contentEquals(
                CloudNoise.generate(256, 1L, baseCells = 16, octaves = 3),
            ),
            "baseCells did not change the cloud",
        )
    }

    @Test
    fun heightsRunFromZeroToOne() {
        for (seed in 1L..3L) {
            val v = CloudNoise.generate(256, seed)
            assertEquals(0f, v.min(), "seed $seed min")
            assertEquals(1f, v.max(), "seed $seed max")
            assertTrue(v.all { it.isFinite() }, "seed $seed produced a non-finite height")
        }
    }

    @Test
    fun theWrapSeamIsNoRougherThanTheTexture() {
        val size = 256
        val v = CloudNoise.generate(size, 1L)
        for (y in 0 until size) {
            assertSeam(v, y, horizontal = true, size = size)
        }
        for (x in 0 until size) {
            assertSeam(v, x, horizontal = false, size = size)
        }
    }

    @Test
    fun oddLatticesStillTile() {
        // cells/size is not a power of two here (3/96, 48/96 is), so a lattice point can land a
        // float ULP either side of an integer. The wrap must survive that.
        val size = 96
        val v = CloudNoise.generate(size, 4L, octaves = 5, baseCells = 3)
        for (y in 0 until size) assertSeam(v, y, horizontal = true, size = size)
        for (x in 0 until size) assertSeam(v, x, horizontal = false, size = size)
    }

    @Test
    fun badSizesAreRefused() {
        // 100 is not a multiple of baseCells * 2^(octaves-1) = 64.
        assertFailsWith<IllegalArgumentException> { CloudNoise.generate(100, 1L) }
        // 96 is not a multiple of baseCells * 2^(octaves-1) = 64 for these settings.
        assertFailsWith<IllegalArgumentException> { CloudNoise.generate(96, 1L, baseCells = 16, octaves = 3) }
        assertFailsWith<IllegalArgumentException> { CloudNoise.generate(0, 1L) }
        assertFailsWith<IllegalArgumentException> { CloudNoise.generate(256, 1L, octaves = 0) }
        assertFailsWith<IllegalArgumentException> { CloudNoise.generate(256, 1L, baseCells = 0) }
        // The sizes that are multiples do come through.
        CloudNoise.generate(64, 1L)
        CloudNoise.generate(320, 1L)
    }

    @Test
    fun cloudsAreNotLopsided() {
        for (seed in 1L..5L) {
            val v = CloudNoise.generate(256, seed)
            val mean = v.sum() / v.size
            assertTrue(mean in 0.35f..0.65f, "seed $seed mean was $mean")
        }
    }

    @Test
    fun samplingFinerGivesTheSameFieldNotADifferentCloud() {
        val coarse = CloudNoise.generate(128, 7L)
        val fine = CloudNoise.generate(256, 7L)
        val n = 128 * 128
        val a = FloatArray(n)
        val b = FloatArray(n)
        for (y in 0 until 128) for (x in 0 until 128) {
            a[y * 128 + x] = coarse[y * 128 + x]
            val r = 2 * y
            val c = 2 * x
            b[y * 128 + x] = (fine[r * 256 + c] + fine[r * 256 + c + 1] +
                fine[(r + 1) * 256 + c] + fine[(r + 1) * 256 + c + 1]) / 4f
        }
        val r = correlation(a, b)
        assertTrue(r > 0.98f, "correlation between 128 and the 2x-averaged 256 was only $r")
    }

    private fun assertSeam(v: FloatArray, line: Int, horizontal: Boolean, size: Int) {
        val at = if (horizontal) { k: Int -> v[line * size + k] } else { k: Int -> v[k * size + line] }
        var widest = 0f
        for (k in 1 until size) {
            val d = abs(at(k) - at(k - 1))
            if (d > widest) widest = d
        }
        val seam = abs(at(0) - at(size - 1))
        val where = if (horizontal) "row $line" else "column $line"
        assertTrue(seam <= widest + 1e-6f, "seam of $where was $seam, interior max $widest")
    }

    private fun correlation(a: FloatArray, b: FloatArray): Float {
        val n = a.size
        val ma = a.sum() / n
        val mb = b.sum() / n
        var num = 0f
        var da = 0f
        var db = 0f
        for (i in 0 until n) {
            val x = a[i] - ma
            val y = b[i] - mb
            num += x * y
            da += x * x
            db += y * y
        }
        return num / sqrt(da * db)
    }
}
