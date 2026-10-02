package cc.joycreator.joybrush.core.paper

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The surface map's own arithmetic (JB-9.01), test for test from the spec's list.
 *
 * `SurfaceMaps` is the Kotlin twin of `joybrush/tools/paper/pack.py`, and the shipped
 * `assets/paper/surface_pulp_artisan.png` is the golden: `SurfaceAssetTest` (jvmTest) checks this
 * code reproduces that file. These tests are what make the twin's *conventions* pinned, so that a
 * well-meaning change to a weight, a sign or a division goes red here first, with a name that says which
 * convention moved, instead of red over in the asset comparison.
 *
 * Three of them go past the spec's list, because the audit of this row found three things the list did
 * not ask about and the spec's own text got wrong: that the slope byte layout has no half-byte offset,
 * that a non-finite slope used to pack as the opposite rail, and that `A − B²` is quantisation error
 * rather than the local variance JB-9.01 Decision 3 promised.
 *
 * Every expected number below is derived in its own comment.
 */
class SurfaceMapsTest {
    @Test fun normalizedFloatRoundOffDoesNotMakeAFlatSurfaceTilt() {
        assertEquals(0f, SurfaceMaps.decodeFilteredSlope(127f / 255f, 0.099f))
        // A sub-byte filtered slope is preserved; this must never become whole-byte quantization.
        val v = (127f + 1f / 1024f) / 255f
        assertTrue(SurfaceMaps.decodeFilteredSlope(v, 0.099f) > 0f)
    }

    /** `h = 0.5 + 0.5·sin(2πx/w)` for every row, row-major, w wide. */
    private fun sineRamp(w: Int, h: Int): FloatArray =
        FloatArray(w * h) { i -> 0.5f + 0.5f * sin(2.0 * PI * (i % w) / w).toFloat() }

    // ---- 1. a flat surface has no slope, exactly -------------------------------------------------

    /**
     * Constant height 0.4 on 8×8 → every dx, dy == 0f EXACTLY, not "within a tolerance".
     *
     * The kernel is a signed sum of differences of equal values, so the answer is 0 by construction
     * and any epsilon slack here would be slack the kernel is never entitled to.
     */
    @Test
    fun aFlatSurfaceHasNoSlopeAtAll() {
        val (dx, dy) = SurfaceMaps.slopes(FloatArray(64) { 0.4f }, 8, 8)
        for (i in dx.indices) {
            assertEquals(0f, dx[i], "dx at texel $i")
            assertEquals(0f, dy[i], "dy at texel $i")
        }
    }

    // ---- 2. a ramp in x reads as the central difference; /32 is what pins the scale ------------

    /**
     * A wrapped 64×4 sine `h = 0.5 + 0.5·sin(2πx/64)`. dx is within 1e-3 of the central difference
     * `(h[x+1] − h[x−1])/2`, and dy is 0 within 1e-6.
     *
     * Derivation of the /32: the three rows are identical, so every one of the kernel's three
     * difference terms is the same number `D = h[x+1] − h[x−1]` and
     * `dx = (3 + 10 + 3)·D/32 = 16·D/32 = D/2`. So the Scharr weights over 2 texels ARE the central
     * difference, and a divisor of 8 or 16 (the Sobel mistake the spec forbids) would come out 4× or
     * 2× too large — which is why this is the row the mutation check breaks.
     *
     * dy: `H[y+1][*]` and `H[y−1][*]` are the same array, so each dy term is a difference of equal
     * values and dy is exactly 0. 1e-6 is stated to allow the compiler no surprises, not to allow a
     * small non-zero answer.
     */
    @Test
    fun aSineRampInXReadsAsTheCentralDifferenceAndHasNoVerticalSlope() {
        val w = 64
        val h = 4
        val height = sineRamp(w, h)
        val (dx, dy) = SurfaceMaps.slopes(height, w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val central = (height[y * w + (x + 1) % w] - height[y * w + (x - 1 + w) % w]) / 2f
            assertTrue(
                abs(dx[i] - central) <= 1e-3f,
                "dx at ($x,$y) = ${dx[i]}, central difference = $central",
            )
            assertTrue(abs(dy[i]) <= 1e-6f, "dy at ($x,$y) = ${dy[i]} on a horizontal ramp")
        }
    }

    @Test
    fun aSineRampInYReadsAsTheCentralDifferenceAndHasNoHorizontalSlope() {
        val w = 4; val h = 64
        val height = FloatArray(w * h) { i -> (0.5 + 0.5 * sin(2.0 * PI * (i / w) / h)).toFloat() }
        val (dx, dy) = SurfaceMaps.slopes(height, w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val central = (height[((y + 1) % h) * w + x] - height[((y - 1 + h) % h) * w + x]) / 2f
            assertEquals(central, dy[y * w + x], 1e-6f, "dy at ($x,$y)")
            assertEquals(0f, dx[y * w + x], "dx at ($x,$y)")
        }
        assertEquals(0f, SurfaceMaps.decodeSlope(SurfaceMaps.encodeSlope(0f, 0.099f), 0.099f))
    }

    // ---- 3. every edge wraps --------------------------------------------------------------------

    /**
     * One bump at (0, 0) on 8×8. dx must be non-zero at x = 7 — the wrap neighbour — and carry the
     * right sign on both sides of the bump.
     *
     * Derivation at (7, 0), with the bump `H[0][0] = 1` and every other texel 0. The kernel's three
     * difference terms are `H[7][0] − H[7][6] = 0`, `H[0][0] − H[0][6] = 1` and `H[1][0] − H[1][6] = 0`
     * (the ±1 in x wraps 7 → 0 and 7 → 6), so `dx(7,0) = 10/32 > 0`: the ground falls away from x
     * towards the wrapped bump. At (1, 0) the middle term is `H[0][2] − H[0][0] = −1`, so
     * `dx(1,0) = −10/32 < 0`. Both signs are what a seam that leaked would get backwards.
     */
    @Test
    fun theEdgesWrapSoTheBumpAtTheOriginStillSlopes() {
        val w = 8
        val height = FloatArray(w * w)
        height[0] = 1f
        val (dx, _) = SurfaceMaps.slopes(height, w, w)
        assertTrue(dx[7] > 0f, "dx(7,0) = ${dx[7]}, want > 0: the bump is the wrap neighbour of x = 7")
        assertTrue(dx[1] < 0f, "dx(1,0) = ${dx[1]}, want < 0: falling away from the bump")
    }

    // ---- 4. y grows DOWN ------------------------------------------------------------------------

    /**
     * A ground rising towards the bottom of the picture (`h = y/8` on 8×8) gives dy > 0 on the
     * interior rows 2..5.
     *
     * Derivation: the kernel's three terms are `H[y+1] − H[y−1]` each, so
     * `dy = 16·(H[y+1] − H[y−1])/32`, which is positive exactly when the row below is higher than
     * the row above. On a full ramp that holds for rows 1..6. The assertion is made on the interior
     * rows only because this ramp is NOT tileable (it is a spec test of the sign convention, and
     * Decision 4 says a non-tileable input still wraps): row 0 reads its `y−1` as row 7, and row 7
     * reads its `y+1` as row 0, so those two rows see the seam and their dy has the wrong sign. Rows
     * 2..5 are clear of it.
     */
    @Test
    fun aGroundRisingTowardsTheBottomReadsAsPositiveDy() {
        val w = 8
        val height = FloatArray(w * w) { i -> (i / w) / 8f }
        val (_, dy) = SurfaceMaps.slopes(height, w, w)
        for (y in 2..5) for (x in 0 until w) {
            assertTrue(dy[y * w + x] > 0f, "dy at ($x,$y) = ${dy[y * w + x]}, want > 0 (y grows down)")
        }
    }

    // ---- 5..6. the byte encoding ----------------------------------------------------------------

    /** The four anchors: flat, both rails, and a value far past the rail. */
    @Test
    fun aSlopeEncodesToItsByteWithTheHalfWayPointAt127() {
        val r = 0.5f
        assertEquals(127, SurfaceMaps.encodeSlope(0f, r))
        assertEquals(254, SurfaceMaps.encodeSlope(r, r))
        assertEquals(0, SurfaceMaps.encodeSlope(-r, r))
        assertEquals(254, SurfaceMaps.encodeSlope(10f * r, r), "past the rail it clamps, it does not wrap")
    }

    /**
     * The byte layout has no half-byte offset: 127 is flat and the two rails are 0 and 254.
     *
     * The encode is `round(127 + 127·clamp(s/r, −1, 1))`, so for an integer k in −127..127 — already
     * inside the clamp — `encodeSlope(k·r/127, r)` is exactly `127 + k`. Every one of the 255 bytes the
     * encoder can write therefore names the slope `k·r/127` with k = b − 127, which puts
     * ```
     *   byte 127 -> 0 exactly      byte 126 -> -r/127      byte 128 -> +r/127
     * ```
     * so 126 and 128 are the two encode steps either side of flat and not a bias towards one end, and
     * [SurfaceMaps.decodeSlope] is the exact inverse of [SurfaceMaps.encodeSlope] rather than an
     * approximation of it. Byte 255 is never written; read one and it decodes to 128r/127, past the rail.
     *
     * The six anchors below are asserted with NO tolerance because each side reduces to a value both
     * sides can hold exactly: 0/127f·r is 0, ±127/127f·r is ±r, and `1/127f·r` and `r/127f` are the same
     * Float at r = 0.099. The loop over all 255 bytes cannot be exact, because the code computes
     * `(k/127f)·r` while the grid value is `k·r/127` and the two associations differ by up to one ulp;
     * one ulp of 0.099 is 7.45e-9, so 1e-8 is about 1.3 ulps and nothing more.
     */
    @Test
    fun theSlopeByteLayoutHasNoHalfByteOffset() {
        val r = 0.099f
        assertEquals(0f, SurfaceMaps.decodeSlope(127, r))
        assertEquals(r / 127f, SurfaceMaps.decodeSlope(128, r))
        assertEquals(-(r / 127f), SurfaceMaps.decodeSlope(126, r))
        assertEquals(-r, SurfaceMaps.decodeSlope(0, r))
        assertEquals(r, SurfaceMaps.decodeSlope(254, r))
        assertEquals(128f * (r / 127f), SurfaceMaps.decodeSlope(255, r))
        for (k in -127..127) {
            assertEquals(127 + k, SurfaceMaps.encodeSlope(k * r / 127f, r), "byte for k = $k")
            assertEquals(k * r / 127f, SurfaceMaps.decodeSlope(127 + k, r), 1e-8f, "slope for byte ${127 + k}")
        }
    }

    /** Exact-zero encoding has 254 intervals; the worst round-trip error is r/254. */
    @Test
    fun aSlopeSurvivesTheByteRoundTrip() {
        val r = 0.099f
        val halfStep = r / 254f
        val slack = halfStep * 1e-3f
        for (i in 0..100) {
            val s = -r + 2f * r * i / 100f
            val back = SurfaceMaps.decodeSlope(SurfaceMaps.encodeSlope(s, r), r)
            assertTrue(
                abs(back - s) <= halfStep + slack,
                "s = $s came back as $back, an error of ${abs(back - s)} against a half step of $halfStep",
            )
        }
    }

    // ---- 7. pack keeps the height and squares it into alpha ---------------------------------------

    /**
     * B is the input byte, untouched, and A is `round(255·(b/255)²)`.
     *
     * The five bytes are the ones that can go wrong: 0 and 255 are the rails, 1 is the byte where a
     * naive `round(255·b)/255` would put alpha above 0, and 128 and 254 sit on either side of the
     * middle of the range. Their alphas are 0, 64, 253 and 255 — 128 → `255·(128/255)²` = 64.25 and
     * 254 → 253.01, both well clear of a .5 tie, so a different rounding rule cannot pass this.
     */
    @Test
    fun packRoundTripsTheHeightAndSquaresItIntoAlpha() {
        val w = 8
        val probes = intArrayOf(0, 1, 128, 254, 255)
        val heightBytes = ByteArray(w * w)
        probes.forEachIndexed { i, b -> heightBytes[i] = b.toByte() }
        val packed = SurfaceMaps.pack(heightBytes, w, w, 0.099f)
        for (i in 0 until heightBytes.size) {
            assertEquals(heightBytes[i], packed[i * 4 + 2], "B channel at texel $i must be the input byte")
        }
        probes.forEachIndexed { i, b ->
            val want = (255f * (b / 255f) * (b / 255f)).roundToInt()
            assertEquals(want, packed[i * 4 + 3].toInt() and 0xFF, "A channel for height byte $b")
        }
    }

    /**
     * `A − B²` is what a consumer reading "variance = A − B²" gets, measured from the bytes `pack`
     * actually wrote. It is the rounding error of a byte and nothing else.
     *
     * With b the height byte, `u = b²/255`, `A = round(u)` and `B² = b²/65025 = u/255`, so
     * ```
     *   A − B² = (round(u) − u)/255 = e/255,   e ∈ [-1/2, +1/2]
     * ```
     * Put `r = b² mod 255`. Then `e` is `−r/255` when `r ≤ 127` and `(255 − r)/255` when `r ≥ 128`, so
     * ```
     *   A − B² = -r/65025          for b² mod 255 <= 127
     *   A − B² = (255 - r)/65025   for b² mod 255 >= 128
     * ```
     * The bound is therefore 127/65025 = 1.95309e-3 (the coarser 1/510 = 1.96078e-3 is the same bound
     * without the mod), the SIGN is decided by `b² mod 255` and not by the surface, and both signs occur
     * over the byte range: the rule above pins each of the 256 bytes, and it is 1.75e-16 or better
     * everywhere, so the assertion carries 1e-12.
     *
     * Two probes, worked by hand, and they are neighbours on the same dark sheet:
     * - b = 1: u = 1/255, A = 0, so A − B² = −1/65025 = −1.53787e-5.
     * - b = 12: u = 144/255, A = 1, so A − B² = 111/65025 = +1.70704e-3, which is 87.4% of the bound.
     * One is a "negative variance" and one is a "positive variance", and both carry the same single bit
     * of A (0 against 1), so nothing downstream can tell "dark" from "flat" in that channel.
     */
    @Test
    fun theAlphaChannelIsAQuauntisedSecondMomentAndNotAVariance() {
        val n = 16
        val heightBytes = ByteArray(n * n) { (it and 0xFF).toByte() } // every height byte, 0..255
        val packed = SurfaceMaps.pack(heightBytes, n, n, 0.099f)
        var negative = 0
        for (b in 0..255) {
            assertEquals(
                (b * b / 255.0).roundToInt(), packed[b * 4 + 3].toInt() and 0xFF,
                "A for height byte $b: 255·(b/255)² is b²/255 and no float step reaches a .5 tie",
            )
            val residue = (b * b) % 255
            val want = if (residue <= 127) -residue / 65025.0 else (255 - residue) / 65025.0
            val got = aMinusBSquared(packed, b)
            assertEquals(want, got, 1e-12, "A − B² for height byte $b, residue $residue")
            assertTrue(abs(got) <= 127.0 / 65025.0, "A − B² = $got at height byte $b, over 127/65025")
            if (got < 0.0) negative++
        }
        assertTrue(negative > 0, "no height byte gave a negative A − B², so the sign rule is not exercised")
        assertEquals(-1.0 / 65025.0, aMinusBSquared(packed, 1), 1e-12)
        assertEquals(111.0 / 65025.0, aMinusBSquared(packed, 12), 1e-12)
        // what JB-9.06's zoom maths is named to do with it
        assertTrue(sqrt(aMinusBSquared(packed, 1)).isNaN(), "sqrt of a negative A − B² must be NaN, not 0")
        assertTrue(!sqrt(aMinusBSquared(packed, 12)).isNaN())
        // and twenty height bytes carry no signal in A at all: b² < 127.5 gives 0, b² < 382.5 gives 1
        for (b in 0..11) assertEquals(0, packed[b * 4 + 3].toInt() and 0xFF, "A for height byte $b")
        for (b in 12..19) assertEquals(1, packed[b * 4 + 3].toInt() and 0xFF, "A for height byte $b")
        assertEquals(2, packed[20 * 4 + 3].toInt() and 0xFF, "A for height byte 20")
    }

    /** `A/255 − (b/255)²`, read back out of packed bytes, i.e. exactly what a consumer of the layout sees. */
    private fun aMinusBSquared(packed: ByteArray, b: Int): Double {
        val a = packed[b * 4 + 3].toInt() and 0xFF
        val h = b / 255.0
        return a / 255.0 - h * h
    }

    // ---- 8. a range that cannot divide ----------------------------------------------------------------

    @Test
    fun aZeroSlopeRangeIsRefusedByName() {
        val e = assertFailsWith<IllegalArgumentException> { SurfaceMaps.pack(ByteArray(64), 8, 8, 0f) }
        assertTrue(e.message.orEmpty().contains("slopeRange"), "the message should say which argument: ${e.message}")
    }

    /**
     * A slope that is not a number is refused by name, instead of packing as the opposite rail.
     *
     * What it did before the guard, step by step. `coerceIn(-1.0, 1.0)` returns its argument whenever
     * neither `this < min` nor `this > max` holds, and for a NaN neither holds, so the NaN survived the
     * clamp. `round` of a NaN is a NaN. `Double.toInt()` narrows NaN to 0 (JLS 5.1.3). Byte 0 is the
     * −`slopeRange` rail, so one NaN in a hand-built slope array became a texel tilted as hard downhill
     * as the format can express, with no exception and no message. An infinity gets the same treatment
     * by the opposite route: `+∞` clamps to 1 and packs as 254, `−∞` as 0.
     *
     * [SurfaceMaps.defaultSlopeRange] does not always notice first, either: its percentile index is
     * `0.999·(n − 1)`, well below the last element, so on a field large enough that the non-finite
     * values sort to the end the range comes back finite and legal and `pack` proceeds. On a field small
     * enough that the percentile index itself lands on one, the range check throws and the same input
     * looks like a different bug. `pack` itself cannot reach any of this: its heights come from bytes.
     *
     * The guard is on the VALUE and not on the call: with a valid range, the legal anchors either side of
     * it still encode, which is what the last two assertions are for.
     */
    @Test
    fun aNonFiniteSlopeIsRefusedByName() {
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val e = assertFailsWith<IllegalArgumentException> { SurfaceMaps.encodeSlope(bad, 0.099f) }
            assertTrue(
                e.message.orEmpty().contains("slope must be"),
                "the message should say the slope is the problem, not the range: ${e.message}",
            )
        }
        assertEquals(127, SurfaceMaps.encodeSlope(0f, 0.099f))
        assertEquals(254, SurfaceMaps.encodeSlope(0.099f, 0.099f))
    }
}
