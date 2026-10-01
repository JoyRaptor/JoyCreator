package cc.joycreator.joybrush.core.paper

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The surface map's own arithmetic (JB-9.01), test for test from the spec's list.
 *
 * `SurfaceMaps` is the Kotlin twin of `joybrush/tools/paper/pack.py`, and the shipped
 * `assets/paper/surface_pulp_artisan.png` is the golden: [SurfaceAssetTest] (jvmTest) checks this
 * code reproduces that file. These eight tests are what make the twin's *conventions* pinned, so
 * that a well-meaning change to a weight, a sign or a division goes red here first, with a name
 * that says which convention moved, instead of red over in the asset comparison.
 *
 * Every expected number below is derived in its own comment.
 */
class SurfaceMapsTest {

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
    fun aSlopeEncodesToItsByteWithTheHalfWayPointAt128() {
        val r = 0.5f
        assertEquals(128, SurfaceMaps.encodeSlope(0f, r))
        assertEquals(255, SurfaceMaps.encodeSlope(r, r))
        assertEquals(0, SurfaceMaps.encodeSlope(-r, r))
        assertEquals(255, SurfaceMaps.encodeSlope(10f * r, r), "past the rail it clamps, it does not wrap")
    }

    /**
     * `decodeSlope(encodeSlope(s, r), r)` is within `r/255` of `s` for 101 samples of −r..r.
     *
     * Derivation: encode rounds `255·(0.5 + 0.5·s/r)` to a byte, so the byte carries the encoded
     * value to within half a step; decode is `(b/255 − 0.5)·2r`, i.e. it scales that same step back.
     * One step is `2r/255`, so half a step — the worst case of the rounding — is exactly `r/255`.
     *
     * The bound is r/255 and the sample at i = 50 sits exactly ON it: that sample's encoded value
     * lands on a whole number, so the round-trip error is the full half step, not less. Half a
     * Float ulp on the four multiply/divide steps then carries it ~1.7e-5 of a step past the bound, so
     * the comparison carries the same sliver of slack. A step and a half would be the honest failure
     * to catch here: a wrong scale factor in [decodeSlope] moves the error by 2x or 0.5x, not by a
     * fraction of a percent.
     */
    @Test
    fun aSlopeSurvivesTheByteRoundTrip() {
        val r = 0.099f
        val halfStep = r / 255f
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

    // ---- 8. a range that cannot divide ----------------------------------------------------------------

    @Test
    fun aZeroSlopeRangeIsRefusedByName() {
        val e = assertFailsWith<IllegalArgumentException> { SurfaceMaps.pack(ByteArray(64), 8, 8, 0f) }
        assertTrue(e.message.orEmpty().contains("slopeRange"), "the message should say which argument: ${e.message}")
    }
}
