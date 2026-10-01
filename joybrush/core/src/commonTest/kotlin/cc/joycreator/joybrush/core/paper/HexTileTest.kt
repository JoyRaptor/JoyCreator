package cc.joycreator.joybrush.core.paper

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * JB-9.02: the hex-tile paper sampler. The maths here is the contract; `joybrush/shaders/jb_paper.glsl`
 * is its twin, and the tests are what pin the two together.
 */
class HexTileTest {
    @Test fun aFilteredSlopeIsDecodedWithoutByteRounding() {
        val bytes = ByteArray(4 * 4 * 4)
        for (y in 0..3) for (x in 0..3) {
            val i = (y * 4 + x) * 4
            bytes[i] = (127 + x).toByte(); bytes[i + 1] = 127
        }
        val tex = PaperTexture(4, 4, bytes)
        val out = FloatArray(4)
        val sample = FloatArray(4)
        // At the lattice origin exactly one vertex contributes: its offset lands between texels.
        tex.bilinear(HexTile.hash(0, 0, 1) * 4.0, HexTile.hash(0, 0, 2) * 4.0, sample)
        HexTile.sampleSurface(tex, 0.0, 0.0, 180.0, false, 0.5f, out)
        assertEquals((sample[0] * 255f - 127f) / 127f * 0.5f, out[0], 1e-8f)
        // Flat encoded 127 is exact zero everywhere, including rotated blends.
        for (i in bytes.indices step 4) bytes[i] = 127
        HexTile.sampleSurface(tex, 19.2, -37.1, 180.0, true, 0.5f, out)
        assertEquals(0f, out[0]); assertEquals(0f, out[1])
    }

    @Test fun octaveSeedsAreDeterministicAndChangeBothReads() {
        val bytes = ByteArray(16 * 16 * 4) { i -> ((i * 73 + i / 5) and 255).toByte() }
        val tex = PaperTexture(16, 16, bytes)
        val a = FloatArray(4); val b = FloatArray(4); val again = FloatArray(4)
        HexTile.sampleSurface(tex, 19.3, -7.9, 32.0, true, 0.1f, a)
        HexTile.sampleSurface(tex, 19.3, -7.9, 32.0, true, 0.1f, b, seed = 10)
        HexTile.sampleSurface(tex, 19.3, -7.9, 32.0, true, 0.1f, again, seed = 10)
        assertTrue(b.contentEquals(again)); assertTrue(!a.contentEquals(b))
        HexTile.sampleLook(tex, 19.3, -7.9, 32.0, true, a)
        HexTile.sampleLook(tex, 19.3, -7.9, 32.0, true, b, seed = 10)
        assertTrue(!a.take(3).equals(b.take(3)))
    }

    /**
     * A seeded generator written out here rather than `kotlin.random`, so every number in this file is
     * the same number on every run and on every platform (the spec's "Do not" list).
     */
    private class Lcg(seed: Long) {
        private var s = seed

        fun nextBits(): Int {
            s = s * 6364136223846793005L + 1442695040888963407L
            return ((s ushr 33).toInt() and 0x7FFFFFF)
        }

        /** 0..1. */
        fun nextUnit(): Double = nextBits().toDouble() / 0x7FFFFFF
    }

    // ---- 1. the hash, against an independent reading of the formula -------------------------------

    /** The spec's four lines of lowbias32, transcribed from the maths and not from [HexTile]. */
    private fun referenceHash(i: Int, j: Int, k: Int): Float {
        var x = i.toUInt() * 73856093u
        x = x xor (j.toUInt() * 19349663u)
        x = x xor (k.toUInt() * 83492791u)
        var y = x
        y = y xor (y shr 16)
        y *= 0x7feb352du
        y = y xor (y shr 15)
        y *= 0x846ca68bu
        y = y xor (y shr 16)
        // 24 bits, so the whole value is exact in float on either side of the twin.
        return ((y shr 8).toDouble() / 16777216.0).toFloat()
    }

    @Test
    fun theHashIsTheLowbias32TheSpecWroteDown() {
        val golden = listOf(
            Triple(0, 0, 0),
            Triple(1, 0, 0),
            Triple(0, 1, 0),
            Triple(-1, -1, 1),
            Triple(123456, -654321, 3),
        )
        for ((i, j, k) in golden) {
            assertEquals(referenceHash(i, j, k), HexTile.hash(i, j, k), 0f, "hash($i, $j, $k)")
        }
    }

    @Test
    fun everyHashIsAUnitNumber() {
        val rng = Lcg(0x5EEDL)
        var n = 0
        while (n < 4000) {
            val i = rng.nextBits() - 0x40000000
            val j = rng.nextBits() - 0x40000000
            val k = rng.nextBits() - 0x40000000
            val v = HexTile.hash(i, j, k)
            assertTrue(v >= 0f && v < 1f, "hash($i, $j, $k) = $v is outside [0, 1)")
            n++
        }
    }

    // ---- 2. the three weights are a partition of unity --------------------------------------------

    @Test
    fun theThreeWeightsAreNeverNegativeAndAlwaysSumToOne() {
        val rng = Lcg(0xA11CEL)
        var n = 0
        while (n < 200) {
            val px = (rng.nextUnit() - 0.5) * 5000.0
            val py = (rng.nextUnit() - 0.5) * 5000.0
            val l = HexTile.lattice(px, py, 37.0)
            for (k in 0..2) {
                assertTrue(l.w[k] >= 0f, "weight $k at ($px, $py) is ${l.w[k]}")
            }
            assertEquals(1f, l.w[0] + l.w[1] + l.w[2], 1e-6f, "weights at ($px, $py)")
            n++
        }
    }

    // ---- 3. no seam: the height never jumps as the read walks across hex edges --------------------

    /** A 16x16 surface whose height is a smooth function that wraps: `0.5 + 0.2·sin(2π(x + 2y)/16)`. */
    private fun smoothWrap16(): PaperTexture {
        val n = 16
        val px = ByteArray(n * n * 4)
        for (y in 0 until n) {
            for (x in 0 until n) {
                val height = 0.5 + 0.2 * sin(2.0 * PI * (x + 2.0 * y) / n)
                val o = (y * n + x) * 4
                px[o] = 127.toByte() // flat slope, so only the height channel is under test
                px[o + 1] = 127.toByte()
                px[o + 2] = (height * 255.0).roundToInt().coerceIn(0, 255).toByte()
                px[o + 3] = (height * height * 255.0).roundToInt().coerceIn(0, 255).toByte()
            }
        }
        return PaperTexture(n, n, px)
    }

    @Test
    fun walkingAcrossHexEdgesNeverJumpsTheHeight() {
        val tex = smoothWrap16()
        val out = FloatArray(4)
        var previous: Double? = null
        var worst = 0.0
        var n = 0
        while (n < 200) {
            val t = n * 0.25
            // A line that is not parallel to any hex row, so it crosses edges in both directions.
            val px = t
            val py = t * 0.37
            HexTile.sampleSurface(tex, px, py, 16.0, rotatable = true, slopeRange = 0.5f, out = out)
            val height = out[2].toDouble()
            if (previous != null) {
                val jump = abs(height - previous)
                if (jump > worst) worst = jump
            }
            previous = height
            n++
        }
        assertTrue(worst <= 0.06, "the largest step in height along the line was $worst, over the 0.06 budget")
    }

    // ---- 4. a hex's own centre belongs to that hex -----------------------------------------------

    @Test
    fun aHexCentreLandsBackInsideItsOwnHex() {
        for (j in -3..3) {
            for (i in -3..3) {
                val cx = HexTile.SQRT3.let { 12.0 * (i + j / 2.0) }
                val cy = 12.0 * (j * HexTile.SQRT3 / 2.0)
                val l = HexTile.lattice(cx + 0.001, cy, 12.0)
                var found = false
                for (k in 0..2) {
                    if (l.vi[k] == i && l.vj[k] == j) {
                        found = true
                        assertTrue(l.w[k] > 0.99f, "hex ($i, $j) has weight ${l.w[k]} at its own centre")
                    }
                }
                assertTrue(found, "hex ($i, $j) is not one of the three vertices at its own centre")
            }
        }
    }

    // ---- 5. the whole point: the grid does not come back ------------------------------------------

    private fun correlation(a: DoubleArray, b: DoubleArray): Double {
        val n = a.size
        var ma = 0.0
        var mb = 0.0
        for (i in 0 until n) {
            ma += a[i]
            mb += b[i]
        }
        ma /= n
        mb /= n
        var num = 0.0
        var da = 0.0
        var db = 0.0
        for (i in 0 until n) {
            val x = a[i] - ma
            val y = b[i] - mb
            num += x * y
            da += x * x
            db += y * y
        }
        return num / sqrt(da * db)
    }

    @Test
    fun theSamePaperOneTexturePeriodAwayIsNotTheSamePaper() {
        val n = 32
        val rng = Lcg(0xC0FFEEL)
        val px = ByteArray(n * n * 4)
        for (i in px.indices) px[i] = rng.nextBits().toByte()

        val tex = PaperTexture(n, n, px)
        val grid = 256
        val direct = DoubleArray(grid * grid)       // plain tiling, unshifted
        val directShifted = DoubleArray(grid * grid) // plain tiling, one texture period along
        val hexed = DoubleArray(grid * grid)        // this row's read, unshifted
        val hexedShifted = DoubleArray(grid * grid)  // this row's read, one texture period along
        val out = FloatArray(4)
        val flat = FloatArray(4)
        for (y in 0 until grid) {
            for (x in 0 until grid) {
                val o = y * grid + x
                tex.bilinear(x + 0.5, y + 0.5, flat)
                direct[o] = flat[2].toDouble()
                tex.bilinear(x + 32.5, y + 32.5, flat)
                directShifted[o] = flat[2].toDouble()
                HexTile.sampleSurface(tex, x + 0.5, y + 0.5, 12.0, rotatable = true, slopeRange = 0.25f, out = out)
                hexed[o] = out[2].toDouble()
                HexTile.sampleSurface(tex, x + 32.5, y + 32.5, 12.0, rotatable = true, slopeRange = 0.25f, out = out)
                hexedShifted[o] = out[2].toDouble()
            }
        }
        assertEquals(1.0, correlation(direct, directShifted), 1e-9, "the plain-tiled control must repeat exactly")
        val c = correlation(hexed, hexedShifted)
        assertTrue(c < 0.3, "the read repeats itself: correlation one texture period apart is $c")
    }

    // ---- 6 and 7. rotation, or none of it ---------------------------------------------------------

    private val SLOPE_RANGE = 0.5f
    private val R = SLOPE_RANGE

    /** R encodes `+R` everywhere, G is 127. Whatever the hex does, it is reading a slope of `+R` on x. */
    private fun uniformSlopeTexture(): PaperTexture {
        val n = 8
        val px = ByteArray(n * n * 4)
        val gx = SurfaceMaps.encodeSlope(R, SLOPE_RANGE)
        for (o in px.indices step 4) {
            px[o] = gx.toByte()
            px[o + 1] = SurfaceMaps.encodeSlope(0f, SLOPE_RANGE).toByte()
            px[o + 2] = 0
            px[o + 3] = 0
        }
        return PaperTexture(n, n, px)
    }

    @Test
    fun aPaperWithNoDirectionKeepsItsDirection() {
        val tex = uniformSlopeTexture()
        val out = FloatArray(4)
        val rng = Lcg(0xD12EC7L)
        var n = 0
        while (n < 200) {
            val px = rng.nextUnit() * 900.0 - 450.0
            val py = rng.nextUnit() * 900.0 - 450.0
            HexTile.sampleSurface(tex, px, py, 11.0, rotatable = false, slopeRange = SLOPE_RANGE, out = out)
            assertEquals(R, out[0], 2f * R / 255f, "dx at ($px, $py)")
            assertEquals(0f, out[1], 2f * R / 255f, "dy at ($px, $py)")
            n++
        }
    }

    @Test
    fun aRotatablePaperTurnsEachHexsSlopeAndKeepsItsLength() {
        val tex = uniformSlopeTexture()
        val out = FloatArray(4)
        val octants = BooleanArray(8)
        var counted = 0
        var j = -2
        while (counted < 40) {
            var i = -6
            while (i <= 6 && counted < 40) {
                // A hex centre, where one gamma weight is 1 and the other two are 0.
                val cx = 11.0 * (i + j / 2.0)
                val cy = 11.0 * (j * HexTile.SQRT3 / 2.0)
                val l = HexTile.lattice(cx, cy, 11.0)
                val sum = l.w[0] + l.w[1] + l.w[2]
                val top = maxOf(l.w[0] * l.w[0] * l.w[0], l.w[1] * l.w[1] * l.w[1], l.w[2] * l.w[2] * l.w[2]) / sum
                if (top <= 0.98f) {
                    i++
                    continue
                }
                HexTile.sampleSurface(tex, cx, cy, 11.0, rotatable = true, slopeRange = SLOPE_RANGE, out = out)
                val length = sqrt((out[0] * out[0] + out[1] * out[1]).toDouble())
                assertEquals(R.toDouble(), length, 0.05 * R, "the slope at hex ($i, $j) was shortened")
                var bin = (atan2(out[1].toDouble(), out[0].toDouble()) / (2.0 * PI / 8.0)).toInt()
                bin = ((bin % 8) + 8) % 8
                octants[bin] = true
                counted++
                i++
            }
            j++
        }
        assertEquals(40, counted)
        val spread = octants.count { it }
        assertTrue(spread >= 6, "the 40 slopes fell in only $spread of 8 directions, so they are not being turned")
    }

    // ---- 8. the look read -------------------------------------------------------------------------

    @Test
    fun aFlatColourComesBackAsThatColourWhereverYouLook() {
        val n = 16
        val px = ByteArray(n * n * 4)
        for (o in px.indices step 4) {
            px[o] = 37.toByte()
            px[o + 1] = 199.toByte()
            px[o + 2] = 88.toByte()
            px[o + 3] = 11.toByte() // ignored by design
        }
        val tex = PaperTexture(n, n, px)
        val out = FloatArray(3)
        val rng = Lcg(0x10CA1L)
        var i = 0
        while (i < 50) {
            val px2 = rng.nextUnit() * 4000.0 - 2000.0
            val py = rng.nextUnit() * 4000.0 - 2000.0
            HexTile.sampleLook(tex, px2, py, 25.0, rotatable = true, out = out)
            assertEquals(37 / 255f, out[0], 1f / 255f, "r at ($px2, $py)")
            assertEquals(199 / 255f, out[1], 1f / 255f, "g at ($px2, $py)")
            assertEquals(88 / 255f, out[2], 1f / 255f, "b at ($px2, $py)")
            i++
        }
    }
}
