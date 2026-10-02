package cc.joycreator.joybrush.core.paper

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * JB-9.02: the hex-tile paper sampler. The maths here is the contract; `joybrush/shaders/jb_paper.glsl`
 * is its twin, and the tests are what pin the two together.
 *
 * Where the twin cannot be pinned, this file says so out loud. The shader's copy of the hex-centre
 * formula is inline in GLSL with no JVM test able to reach it, so the Kotlin side is the one that is
 * pinned and the Lead has to carry the other half.
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

    /**
     * The weight contrast is `w^HEX_GAMMA`, read from the constant rather than written out as `w*w*w`.
     * The spec's contract names `HEX_GAMMA` as the knob (JB-9.02 line 30) and its maths block uses the
     * name, so a public constant that nothing reads is a knob that lies to the next reader.
     *
     * Hand-derived, with `HEX_GAMMA = 3` and the raw weights `(1/2, 1/4, 1/4)`:
     * ```
     *   w^3   = (1/8, 1/64, 1/64) = (8, 1, 1)/64
     *   Σ w^3 = 10/64
     *   w'    = (8/10, 1/10, 1/10) = (0.8, 0.1, 0.1)
     * ```
     * Both 1/8 and 10/64 are binary fractions (2^-3 and 5·2^-7), so the Float cubing and the Float
     * division are exact and 0.8f / 0.1f on the right-hand side are the same Floats the code produces.
     * A gamma of 1 is the plain weights (total = 1 exactly for a partition of unity) and a gamma of 0 is
     * a flat 1/3 each, both by the same arithmetic.
     *
     * THE REFUSAL IS THE PIN. While `HEX_GAMMA` is 3, a `w*w*w` and a `w^HEX_GAMMA` produce identical
     * bytes, so no assertion about a blended value can tell them apart — not this one, not any other. The
     * only thing that can is a gamma that is not a whole number of multiplies, which a hard-coded cube
     * would accept silently and a read constant must reject; that is why the exponent is a parameter with
     * the constant as its default. Change the implementation back to `w*w*w` and this test goes red on
     * the refusal, not on a number.
     */
    @Test
    fun theWeightContrastIsTheOneTheConstantNames() {
        assertEquals(3f, HexTile.HEX_GAMMA, "the contract fixes the contrast at 3 (JB-9.02 Decision 2)")
        val raw = floatArrayOf(0.5f, 0.25f, 0.25f)
        val w = HexTile.gammaWeights(raw)
        assertEquals(0.8f, w[0]); assertEquals(0.1f, w[1]); assertEquals(0.1f, w[2])
        assertTrue(w.contentEquals(HexTile.gammaWeights(raw, HexTile.HEX_GAMMA)), "the default gamma is the constant")
        assertTrue(raw.contentEquals(HexTile.gammaWeights(raw, 1f)), "gamma 1 is the plain weights")
        val flat = HexTile.gammaWeights(raw, 0f)
        for (k in 0..2) assertEquals(1f / 3f, flat[k], "gamma 0 weight $k")
        val e = assertFailsWith<IllegalArgumentException> { HexTile.gammaWeights(raw, 2.5f) }
        assertTrue(e.message.orEmpty().contains("HEX_GAMMA"), "the message should name the knob: ${e.message}")
        assertFailsWith<IllegalArgumentException> { HexTile.gammaWeights(raw, -1f) }
        assertFailsWith<IllegalArgumentException> { HexTile.gammaWeights(raw, Float.NaN) }
        // over the family the sampler actually sees, the contrast keeps a partition of unity
        val rng = Lcg(0x6A44AL)
        var n = 0
        while (n < 200) {
            val px = (rng.nextUnit() - 0.5) * 5000.0
            val py = (rng.nextUnit() - 0.5) * 5000.0
            val g = HexTile.gammaWeights(HexTile.lattice(px, py, 37.0).w)
            assertEquals(1f, g[0] + g[1] + g[2], 1e-6f, "contrast weights at ($px, $py)")
            for (k in 0..2) assertTrue(g[k] >= 0f, "contrast weight $k at ($px, $py) is ${g[k]}")
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

    /**
     * The centre formula the spec writes down — `c = H·(i + j/2, j·√3/2)` (JB-9.02 maths block line 66)
     * — pinned as numbers rather than as a copy of the formula inside some other test. The other tests in
     * this file now CALL [HexTile.centreX] and [HexTile.centreY]; without a test that states the value,
     * a change to either function would be followed along by every caller and nothing would say so.
     *
     * Worked at H = 10, with √3 = 1.7320508075688772935…:
     * - `(2, −3)`: x is `10·(2 + (−3)/2.0)` = `10·(2 − 1.5)` = 5.0, and y is `10·(−3·√3/2)` = −15√3 =
     *   −25.98076211353316. This pair is here for `j / 2.0`: an INTEGER `j / 2` truncates −3/2 towards
     *   zero to −1 and gives x = 10.0, so the assertion fails by 5.0 if the `2.0` is ever dropped.
     * - `(0, 1)`: x is `10·0.5` = 5.0 and y is `10·(√3/2)` = 5√3 = 8.660254037844386.
     * - `(−1, 0)`: x is `10·(−1)` = −10.0 and y is 0.0, the row where the √3 term vanishes.
     * - `(3, 5)`: x is `10·(3 + 2.5)` = 55.0 and y is `10·(5·√3/2)` = 25√3 = 43.301270189221932.
     *
     * Every x value is a sum of halves and tens, so it is exact in Double and is asserted with no
     * tolerance at all. Every y value carries a Double √3, so it picks up up to half an ulp of the √3,
     * half an ulp of `j·√3` and half an ulp of the final scale — about 3·1.11e-16 of relative error —
     * plus half an ulp of the decimal literal written above, which at magnitude 43 (one ulp 7.11e-15) is
     * about 1.8e-14. The tolerance is 1e-13, roughly six times that worst case and still four orders of
     * magnitude below every mutation the next comment names.
     */
    @Test
    fun theHexCentreIsThePointTheSpecWroteDown() {
        assertEquals(5.0, HexTile.centreX(2, -3, 10.0), "x of hex (2,-3) at H=10")
        assertEquals(-25.98076211353316, HexTile.centreY(2, -3, 10.0), 1e-13, "y of hex (2,-3) at H=10")
        assertEquals(5.0, HexTile.centreX(0, 1, 10.0), "x of hex (0,1) at H=10")
        assertEquals(8.660254037844386, HexTile.centreY(0, 1, 10.0), 1e-13, "y of hex (0,1) at H=10")
        assertEquals(-10.0, HexTile.centreX(-1, 0, 10.0), "x of hex (-1,0) at H=10")
        assertEquals(0.0, HexTile.centreY(-1, 0, 10.0), "y of hex (-1,0) at H=10")
        assertEquals(55.0, HexTile.centreX(3, 5, 10.0), "x of hex (3,5) at H=10")
        assertEquals(43.301270189221932, HexTile.centreY(3, 5, 10.0), 1e-13, "y of hex (3,5) at H=10")
        // and the mutations this pins, by how far off each one lands:
        //   `j / 2` as Int          -> x(2,-3) = 10.0                (5.0 off)
        //   the H multiply dropped  -> y(2,-3) = -2.598076211353316  (23.4 off)
        //   SQRT3 replaced by 1     -> y(2,-3) = -15.0               (11.0 off)
        //   `i + j/2` as `i - j/2`  -> x(2,-3) = 35.0                (30.0 off)
        //   `j * (SQRT3 / 2.0)` is the same value, not a mutation: halving a Double only drops an
        //   exponent, so `j * RN(SQRT3/2)` and `RN(j * SQRT3)/2` are one rounding either way.
    }

    @Test
    fun aHexCentreLandsBackInsideItsOwnHex() {
        for (j in -3..3) {
            for (i in -3..3) {
                // The real functions, not the formula written out again: the round trip below is only a
                // guard on the centres if the centres it walks into are the ones the sampler uses.
                val cx = HexTile.centreX(i, j, 12.0)
                val cy = HexTile.centreY(i, j, 12.0)
                val l = HexTile.lattice(cx + 0.001, cy, 12.0) // the nudge is the spec's "tiny"; the
                // unflipped centre lands inside its own hex as well, and the new test above is what pins
                // the value rather than the nudge
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

    /**
     * The no-repeat property, on a texture with grain at every scale: a 32×32 field of random bytes, so
     * the field being compared against its own copy one texture period away carries no low-frequency
     * content for the hex blend to line up with. That is the right instrument for the property — if two
     * fields this far apart still match, the tiling is back — and the plain-tiling control below proves
     * the instrument reads 1.0 when there IS a repeat, so a small number here means the hex read broke
     * it rather than that the measurement was blind.
     *
     * WHAT THE NUMBER IS NOT. The board's row for JB-9.02 quotes "one texture period apart the read
     * correlates 0.004". That number describes THIS texture, not the property, and it is quoted as
     * though it described the shipped paper. It does not. The same measurement on the shipped
     * `assets/paper/surface_pulp_artisan.png` — which is a smooth, low-frequency sheet — reads about
     * +0.95, because on a texture with little content at the texel scale a two-dimensional
     * field-against-field number mostly measures how smooth the paper is. The tiling is still broken
     * there: the hex read's autocorrelation along a line at the texture period is about −0.037, where a
     * single plain read's is about −0.0003, so the hex read decorrelates FASTER than the texture's own
     * smoothness. Those figures are the auditor's, measured on the asset, and they are not asserted here
     * because the asset is a file and this test is commonTest. Correcting the board's number is a
     * question for the Lead, in `tasks/joybrush/reviews/JB-9.01_9.02__bunny-fixes.md`.
     */
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

    /**
     * The rotation HAPPENS — the slope keeps its length and lands in at least 6 of 8 octants. This is
     * the row's own mutation check: drop `R(-θ)` altogether and every hex reads the texture's fixed +x
     * direction, so all 40 land in one octant and the spread assertion goes red.
     *
     * What it deliberately does NOT do is pin WHICH WAY the turn goes. `R(+θ)` and `R(-θ)` are mirror
     * images of one another, so a rotation and its mirror have the same length and, over 40 samples, the
     * same octant spread. Flipping only the sign of this rotation leaves this test green. That is not a
     * flaw in the test; it is a property of the measurement, and
     * [theBackRotationTurnsTheSlopeBackTheSameWayTheReadWasTurned] is the test that closes the gap.
     */
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
                // A hex centre, where one gamma weight is 1 and the other two are 0 — the real
                // centre functions, so this test walks into the same centres the sampler turns about.
                val cx = HexTile.centreX(i, j, 11.0)
                val cy = HexTile.centreY(i, j, 11.0)
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

    /**
     * The SIGN of the back-rotation, which length and octant spread cannot see: `R(+θ)` and `R(−θ)` are
     * mirror images, so a measurement of how long the slope is and how many of eight directions it falls
     * in stays green when one of them turns into the other. This is the only test in either row that
     * does, and it is here because the CPU and the shader must agree on which way a hex turns — the
     * shader does `slope = mat2(c, -s, s, c) * slope` against this file's `R(−θ)`.
     *
     * HOW IT DISTINGUISHES THEM. The texture's slope field points along +x and nowhere else, so at a hex
     * centre the sampler reads `slope_tex = (f, 0)` and then
     * ```
     *   R(-θ)·(f, 0) = ( cos θ · f , −sin θ · f )     <- the spec's maths block, line 71
     *   R(+θ)·(f, 0) = ( cos θ · f , +sin θ · f )
     * ```
     * The two agree on `dx` and disagree on `dy`, and a texture with no y-slope at all is the cheapest
     * thing that can show it. Sampling at a hex centre also makes `p − c` exactly `(0, 0)`, so the
     * forward rotation moves nothing and the texel the read lands on is `centre + offset` — which this
     * test can state exactly, from [HexTile.centreX], [HexTile.centreY] and [HexTile.hash].
     * `HexTile.rotation` is private, so θ is rebuilt here as `hash(i, j, 3)·2π`, the same expression;
     * a change to the hash channel it reads would fail here rather than pass unnoticed.
     *
     * THE TEXTURE. Byte `127 + m(x)` in R on a 32-wide texture, with `m(x) = min(x, 31 − x)`, so m runs
     * 0, 1, …, 15, 15, …, 1, 0 and the wrap from x = 31 back to x = 0 is 0 → 0, with no jump at the seam.
     * `encodeSlope(m·R/127, R)` is `round(127 + 127·m/127)` = `127 + m` exactly for every integer m in
     * 0..15, and `127 + m ≤ 142`, so nothing clamps. G is `encodeSlope(0, R)` = 127, which
     * [SurfaceMaps.decodeFilteredSlope] maps to exactly 0, so the texture really has no y-slope at all.
     * m is affine on texel pairs `[0, 15]` and `[16, 31]` and flat across `[15, 16]`, so the read must
     * not land there; that is the first filter below.
     *
     * WHICH HEXES. A read at `t.x` lands between texel centres `x0 = floor(t.x − 0.5)` and `x0 + 1`
     * (the half-texel convention `PaperTexture.bilinear` uses, and which
     * [aFilteredSlopeIsDecodedWithoutByteRounding] pins). The filter keeps `x0 ∈ [8, 14] ∪ [16, 22]`,
     * which puts the interpolated m in `[8, 15]` and so `f ∈ [8R/127, 15R/127]` = `[0.031496,
     * 0.059055]` at R = 0.5. The second filter, `|sin θ| ≥ 0.5`, holds for 2/3 of a uniform hash. The
     * two together keep 14/32 × 2/3 = 0.29 of the hexes, so 96 scanned hexes give 28 in expectation
     * with a spread of 4.4, and the assertion asks for 8 — about 4.5 spreads below that.
     *
     * THE GAP. A flipped sign puts the answer `2·sin θ·f` away, and with both filters in place that is
     * at least `2 · 0.5 · 8·0.5/127 = 0.031496`. The tolerance is 1e-6, so a flipped sign lands 31 496
     * times outside it.
     */
    @Test
    fun theBackRotationTurnsTheSlopeBackTheSameWayTheReadWasTurned() {
        val hexTexels = 16.0
        val w = 32
        val h = 16
        val px = ByteArray(w * h * 4)
        for (y in 0 until h) for (x in 0 until w) {
            val o = (y * w + x) * 4
            px[o] = SurfaceMaps.encodeSlope(minOf(x, w - 1 - x) * (SLOPE_RANGE / 127f), SLOPE_RANGE).toByte()
            px[o + 1] = SurfaceMaps.encodeSlope(0f, SLOPE_RANGE).toByte()
            px[o + 2] = 128
            px[o + 3] = 0
        }
        val tex = PaperTexture(w, h, px)
        val out = FloatArray(4)
        val probe = FloatArray(4)
        var used = 0
        for (j in 0..7) {
            for (i in 0..11) {
                val cx = HexTile.centreX(i, j, hexTexels)
                val cy = HexTile.centreY(i, j, hexTexels)
                // A centre really is a centre — one weight 1, the others 0. That family is what
                // aHexCentreLandsBackInsideItsOwnHex pins; here it is only a filter, and a hex that
                // failed it would be skipped, which is why the count at the end is asserted.
                val lw = HexTile.lattice(cx, cy, hexTexels).w
                if (maxOf(lw[0], lw[1], lw[2]) < 0.999f) continue
                val tx = HexTile.centreX(i, j, hexTexels) + HexTile.hash(i, j, 1) * tex.w
                val ty = HexTile.centreY(i, j, hexTexels) + HexTile.hash(i, j, 2) * tex.h
                val x0 = ((floor(tx - 0.5).toInt() % w) + w) % w
                if (!(x0 in 8..14 || x0 in 16..22)) continue
                val theta = HexTile.hash(i, j, 3) * (2.0 * PI)
                val sn = sin(theta)
                if (abs(sn) < 0.5) continue
                used++
                // What the texture says where this hex read, with no rotation because p − c is zero.
                tex.bilinear(tx, ty, probe)
                val f = SurfaceMaps.decodeFilteredSlope(probe[0], SLOPE_RANGE)
                HexTile.sampleSurface(tex, cx, cy, hexTexels, rotatable = true, slopeRange = SLOPE_RANGE, out = out)
                assertEquals(cos(theta) * f.toDouble(), out[0].toDouble(), 1e-6, "dx at hex ($i, $j)")
                assertEquals(-sn * f.toDouble(), out[1].toDouble(), 1e-6, "dy at hex ($i, $j)")
            }
        }
        assertTrue(used >= 8, "only $used of the 96 hexes passed the two filters, so the sign is barely pinned")
    }

    /**
     * `hexTexels` is the divisor, so it is the argument that has to be checked, and it had none.
     * [HexTile] guarded `out.size` and, through `decodeFilteredSlope`, `slopeRange` — and divided by
     * this without a word.
     *
     * What each bad value did before the guard, with `px = 3.0`, `py = 5.0`. The narrowing in play is
     * `Double.toInt()` (JLS 5.1.3): a NaN becomes 0, `+Infinity` becomes `Int.MAX_VALUE`, `−Infinity`
     * becomes `Int.MIN_VALUE`, and anything else truncates. None of those throw, which is the whole
     * problem:
     * - `0.0`: `px / 0.0` and `py / 0.0` are both `+Infinity`, so `a` is `Infinity − Infinity` = NaN and
     *   `b` is `+Infinity`. The hex indices came out `(0, Int.MAX_VALUE)`, `centreX`/`centreY` then
     *   multiplied by 0 and gave 0, and `PaperTexture.bilinear` — which wraps — returned a finite,
     *   plausible sample of a patch nobody asked for.
     * - `−16.0`: a finite negative divisor. Nothing overflows at all; the lattice is quietly mirrored,
     *   which is nonsense for a cell size and looked exactly like working code.
     * - `Double.NaN`: every quotient is a NaN, both indices narrow to 0, and the read sampled hexes
     *   around the origin with no complaint. (The audit filed this one as throwing a bare
     *   `IllegalArgumentException`; it does not — Kotlin's narrowing returns 0 for a NaN.)
     * - `+Infinity`: both quotients are 0, so every point collapses onto hex (0, 0) and the read
     *   returned the origin's patch forever, again without a word.
     *
     * Either way nothing was raised, the result was a plausible number, and the caller had no way to
     * tell. `PaperCatalogue` validates the shipped value (16..size) and `PaperRaster.localFrame`
     * requires a positive one, so neither is the way in; a direct call was.
     *
     * The guard is on the VALUE and not on the call: the last three lines are a legal size going through
     * every entry point, which is what says the guard rejects the argument rather than the argument list.
     */
    @Test
    fun aHexSizeThatCannotDivideIsRefusedByName() {
        val tex = smoothWrap16()
        val out = FloatArray(4)
        val look = FloatArray(3)
        for (bad in listOf(0.0, -16.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val e = assertFailsWith<IllegalArgumentException> { HexTile.lattice(3.0, 5.0, bad) }
            assertTrue(
                e.message.orEmpty().contains("hexTexels"),
                "the message should say which argument: ${e.message}",
            )
            assertFailsWith<IllegalArgumentException> {
                HexTile.sampleSurface(tex, 3.0, 5.0, bad, rotatable = false, slopeRange = 0.5f, out = out)
            }
            assertFailsWith<IllegalArgumentException> {
                HexTile.sampleLook(tex, 3.0, 5.0, bad, rotatable = false, out = look)
            }
        }
        assertEquals(3, HexTile.lattice(3.0, 5.0, 16.0).w.size)
        HexTile.sampleSurface(tex, 3.0, 5.0, 16.0, rotatable = true, slopeRange = 0.5f, out = out)
        HexTile.sampleLook(tex, 3.0, 5.0, 16.0, rotatable = true, out = look)
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
