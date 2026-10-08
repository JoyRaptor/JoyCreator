package cc.joycreator.joybrush.core.paper

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.*

class TilePaperSamplerTest {
    private val rect = TilePaperSampler.Rect(-13.25, -8.75, 37.5, 22.25)
    // A real RGBA surface: nonflat slopes and nonzero within-texel variance, not hash stand-ins.
    private val texture = surface()
    private fun surface(): PaperTexture {
        val n = 32
        val bytes = ByteArray(n * n * 4)
        for (y in 0 until n) for (x in 0 until n) {
            val ax = 2.0 * PI * (x + 0.5) / n
            val ay = 2.0 * PI * (y + 0.5) / n
            val z = 0.5 + 0.12 * sin(ax) + 0.10 * cos(ay)
            val p = (y * n + x) * 4
            bytes[p] = (127 + 35 * cos(ax)).toInt().toByte()
            bytes[p + 1] = (127 - 29 * sin(ay)).toInt().toByte()
            bytes[p + 2] = (z * 255).toInt().toByte()
            bytes[p + 3] = ((z * z + 0.025) * 255).toInt().toByte()
        }
        return PaperTexture(n, n, bytes)
    }

    private fun sample(x: Double, y: Double, r: TilePaperSampler.Rect? = rect,
        rotated: Boolean = true, seed: Int = 7, footprint: Double = 1.0,
        mean: Float = 0.47f, slope: Float = 0.7f): FloatArray = FloatArray(4).also {
        TilePaperSampler.sampleSurface(texture, x, y, 17.3, rotated, slope, it, r, seed, footprint, mean)
    }

    private fun close(a: FloatArray, b: FloatArray, tolerance: Float = 2e-6f, label: String = "") {
        for (c in 0..3) assertEquals(a[c], b[c], tolerance, "$label channel$c")
    }

    @Test fun bothEdgesCornersAndNegativePeriodTranslationsMatchActualSurface() {
        for (rotated in listOf(false, true)) for (seed in listOf(0, 7, -19)) {
            for (f in listOf(0.0, 0.17, 0.5, 0.93, 1.0)) {
                close(sample(rect.x, rect.y + f * rect.height, rotated=rotated, seed=seed),
                    sample(rect.x + rect.width, rect.y + f * rect.height, rotated=rotated, seed=seed))
                close(sample(rect.x + f * rect.width, rect.y, rotated=rotated, seed=seed),
                    sample(rect.x + f * rect.width, rect.y + rect.height, rotated=rotated, seed=seed))
            }
            val x = rect.x + 0.37 * rect.width
            val y = rect.y + 0.71 * rect.height
            val expected = sample(x, y, rotated=rotated, seed=seed)
            for (i in -4..4) for (j in -3..3)
                close(expected, sample(x + i * rect.width, y + j * rect.height,
                    rotated=rotated, seed=seed), label="translation$i,$j")
        }
        // A different fractional, non-square period and negative origin cannot use a mask/texture-size shortcut.
        val other = TilePaperSampler.Rect(-61.125, -42.375, 0.75, 19.625)
        close(sample(other.x + 0.33, other.y + 7.1, other),
            sample(other.x + 0.33 - 3 * other.width, other.y + 7.1 + 5 * other.height, other))
    }

    @Test fun seamDerivativeLimitsMatchForEveryChannelAtSmoothLocationsAndCorners() {
        // Bilinear intrinsic kinks are not removed. These points are deliberately away from them.
        val epsilon = 0.002
        fun checkAxis(x: Double, y: Double, alongX: Boolean, rotated: Boolean) {
            val middle = sample(x, y, rotated=rotated)
            val left = sample(x - if (alongX) epsilon else 0.0, y - if (alongX) 0.0 else epsilon, rotated=rotated)
            val right = sample(x + if (alongX) epsilon else 0.0, y + if (alongX) 0.0 else epsilon, rotated=rotated)
            for (c in 0..3) {
                val before = (middle[c] - left[c]) / epsilon
                val after = (right[c] - middle[c]) / epsilon
                assertEquals(before, after, 0.0015, "derivative channel$c at$x,$y axisX=$alongX")
            }
        }
        for (rotated in listOf(false, true)) {
            checkAxis(rect.x, rect.y + 0.371 * rect.height, true, rotated)
            checkAxis(rect.x + 0.417 * rect.width, rect.y, false, rotated)
            for (x in listOf(rect.x, rect.x + rect.width)) for (y in listOf(rect.y, rect.y + rect.height)) {
                checkAxis(x, y, true, rotated); checkAxis(x, y, false, rotated)
            }
        }
        // Nontrivial boundary slopes rule out a constant-output seam "fix".
        val values = sample(rect.x, rect.y)
        assertTrue(abs(values[0]) + abs(values[1]) > 0.01f)
    }

    @Test fun disabledCallsAreBitIdenticalWithAllArgumentsForwarded() {
        for (r in listOf(null, TilePaperSampler.Rect(Double.NaN, Double.NaN, 0.0, 0.0),
            TilePaperSampler.Rect(9.0, -7.0, -3.0, -5.0)))
            for (rotated in listOf(false, true)) for (footprint in listOf(0.7, 2.4, 8.0)) {
                val expected = FloatArray(4)
                HexTile.sampleSurface(texture, -23.71, 18.39, 17.3, rotated, 0.7f, expected,
                    -41, footprint, 0.47f)
                assertContentEquals(expected.map { it.toRawBits() },
                    sample(-23.71, 18.39, r, rotated, -41, footprint).map { it.toRawBits() })
            }
    }

    @Test fun invalidPartialOrNonfiniteActiveRectsRefuse() {
        for (r in listOf(TilePaperSampler.Rect(0.0, 0.0, 0.0, 4.0),
            TilePaperSampler.Rect(0.0, 0.0, 3.0, -1.0),
            TilePaperSampler.Rect(Double.NaN, 0.0, 3.0, 4.0),
            TilePaperSampler.Rect(0.0, 0.0, Double.POSITIVE_INFINITY, 4.0),
            TilePaperSampler.Rect(0.0, 0.0, 3.0, Double.NaN)))
            assertFailsWith<IllegalArgumentException> { sample(1.0, 2.0, r) }
        assertFailsWith<IllegalArgumentException> { sample(Double.NaN, 2.0) }
        assertFailsWith<IllegalArgumentException> {
            TilePaperSampler.sampleSurface(texture, 1.0, 2.0, 17.3, true, 0.7f, FloatArray(3), rect)
        }
    }

    @Test fun contrastMomentsAndParameterForwardingAgreeWithIndependentFourReadBlend() {
        val x = rect.x + rect.width / 2
        val y = rect.y + rect.height / 2
        // At the midpoint each tensor smoothstep weight is 1/4, keep=2.
        val reads = List(4) { n -> FloatArray(4).also {
            HexTile.sampleSurface(texture, x - if (n and 1 != 0) rect.width else 0.0,
                y - if (n and 2 != 0) rect.height else 0.0, 17.3, true, 0.7f, it, 7, 2.4, 0.47f)
        } }
        val actual = sample(x, y, footprint=2.4)
        val expectedZ = (0.47 + reads.sumOf { (it[2] - 0.47f).toDouble() } * 0.5).coerceIn(0.0, 1.0)
        assertEquals(reads.sumOf { it[0].toDouble() } * 0.5, actual[0].toDouble(), 2e-6)
        assertEquals(reads.sumOf { it[1].toDouble() } * 0.5, actual[1].toDouble(), 2e-6)
        assertEquals(expectedZ, actual[2].toDouble(), 2e-6)
        val variance = reads.sumOf { maxOf(it[3] - it[2] * it[2], 0f).toDouble() } * 0.25
        assertEquals(expectedZ * expectedZ + variance, actual[3].toDouble(), 2e-6)
        assertTrue(variance > 0.001)
        assertTrue(!actual.contentEquals(sample(x, y, seed=8, footprint=2.4)))
        assertTrue(!actual.contentEquals(sample(x, y, rotated=false, footprint=2.4)))
        assertTrue(!actual.contentEquals(sample(x, y, mean=0.6f, footprint=2.4)))
        val doubleSlope = sample(x, y, footprint=2.4, slope=1.4f)
        assertEquals(actual[0] * 2f, doubleSlope[0], 2e-6f)
        assertEquals(actual[1] * 2f, doubleSlope[1], 2e-6f)
        for (i in 0..24) {
            val a = sample(rect.x + i * rect.width / 24, rect.y + i * rect.height / 24, footprint=2.4)
            assertTrue(a.all { it.isFinite() }); assertTrue(a[2] in 0f..1f)
            assertTrue(a[3] >= a[2] * a[2])
        }
    }

    @Test fun coarseChangesOnlyFootprintAndReturnsTheSurfaceHeightChannel() {
        for (r in listOf(null, rect)) {
            val x = -7.3; val y = 4.9
            val coarse = TilePaperSampler.sampleCoarseHeight(texture, x, y, 17.3, true,
                0.7f, r, 7, 1.3, 0.47f)
            assertEquals(sample(x, y, r, footprint=10.4)[2], coarse)
            assertEquals(sample(x, y, r)[2], TilePaperSampler.sampleHeight(texture, x, y,
                17.3, true, 0.7f, r, 7, 1.0, 0.47f))
            assertTrue(abs(coarse - sample(x, y, r, footprint=1.3)[2]) > 1e-4f)
        }
    }

    @Test fun clampedHeightMomentStillUsesTheFinalHeightSquared() {
        val bytes = ByteArray(16 * 16 * 4)
        for (i in bytes.indices step 4) {
            bytes[i] = 127; bytes[i + 1] = 127
            bytes[i + 2] = 235.toByte(); bytes[i + 3] = 240.toByte()
        }
        val tex = PaperTexture(16, 16, bytes)
        val x = rect.x + rect.width / 2; val y = rect.y + rect.height / 2
        val actual = FloatArray(4)
        TilePaperSampler.sampleSurface(tex, x, y, 17.3, true, 0.7f, actual, rect,
            7, 2.0, 0.2f)
        val reads = List(4) { n -> FloatArray(4).also {
            HexTile.sampleSurface(tex, x - if (n and 1 != 0) rect.width else 0.0,
                y - if (n and 2 != 0) rect.height else 0.0, 17.3, true, 0.7f, it, 7, 2.0, 0.2f)
        } }
        assertEquals(1f, actual[2])
        val variance = reads.sumOf { maxOf(it[3] - it[2] * it[2], 0f).toDouble() } * 0.25
        assertTrue(variance > 0.01)
        assertEquals(1.0 + variance, actual[3].toDouble(), 2e-6)
        assertEquals(0f, actual[0]); assertEquals(0f, actual[1])
    }
}
