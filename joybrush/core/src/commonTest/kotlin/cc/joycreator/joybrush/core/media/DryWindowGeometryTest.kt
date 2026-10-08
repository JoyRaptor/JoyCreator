package cc.joycreator.joybrush.core.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Objective CPU checks for [DryWindowGeometry] (pure math only; no clipping fix, no wiring).
 *
 * Expected values are independent hand formulas from the shader contract
 * (jb_dry_dab.vert: lo/hi with 0.06 mm, `doc = centre + (qx*u + qy*v) * px`),
 * not copies of the implementation's forward-error code.
 */
class DryWindowGeometryTest {
    private fun record(
        cx: Float, cy: Float, lx: Float, ly: Float,
        xMin: Float, xMax: Float, yMax: Float,
    ): FloatArray {
        val r = FloatArray(DabBatch.FLOATS)
        r[0] = cx; r[1] = cy; r[2] = lx; r[3] = ly
        r[4] = xMin; r[5] = xMax; r[6] = yMax
        return r
    }

    private fun assertEncloses(box: DoubleArray, px: Double, py: Double, what: String) {
        assertTrue(box[0] <= px && px <= box[2] && box[1] <= py && py <= box[3], "$what point ($px,$py) not in ${box.toList()}")
    }

    // ---- (1) dab AABB ----

    @Test fun cardinalLeanUsesStoredAxesLiterally() {
        val m = 0.06f.toDouble()
        val loX = -1.0 - m; val hiX = 2.0 + m
        val loY = -1.5 - m; val hiY = 1.5 + m
        // Lean (1,0): u=(1,0), v=(0,1) -> x = cx + qx*20, y = cy + qy*20.
        val r = record(100f, 200f, 1f, 0f, -1f, 2f, 1.5f)
        val box = assertNotNull(DryWindowGeometry.dabDocAabb(r, 0, 20.0, 0.0))
        val ex0 = 100.0 + loX * 20.0
        val ex1 = 100.0 + hiX * 20.0
        val ey0 = 200.0 + loY * 20.0
        val ey1 = 200.0 + hiY * 20.0
        assertTrue(box[0] <= ex0 && box[2] >= ex1 && box[1] <= ey0 && box[3] >= ey1, "must enclose ideal ${box.toList()} vs [$ex0,$ey0,$ex1,$ey1]")
        // Tight: ideal width 62.4 / 62.4; padding must be << 1 px, not a broad guess.
        assertTrue(box[2] - box[0] <= (hiX - loX) * 20.0 + 1.0, "too wide: ${box.toList()}")
        assertTrue(box[3] - box[1] <= (hiY - loY) * 20.0 + 1.0, "too tall: ${box.toList()}")

        // Lean (0,1): axes swap -> x = cx + qy*20 with v=(-1,0)? u=(0,1), v=(-1,0):
        // X = qx*0 + qy*(-1) = -qy; Y = qx*1 + qy*0 = qx.
        val r2 = record(100f, 200f, 0f, 1f, -1f, 2f, 1.5f)
        val b2 = assertNotNull(DryWindowGeometry.dabDocAabb(r2, 0, 20.0, 0.0))
        // X spans -hiY..-loY, Y spans loX..hiX.
        assertTrue(b2[0] <= 100.0 - hiY * 20.0 && b2[2] >= 100.0 - loY * 20.0, "rotated X ${b2.toList()}")
        assertTrue(b2[1] <= 200.0 + loX * 20.0 && b2[3] >= 200.0 + hiX * 20.0, "rotated Y ${b2.toList()}")
        assertTrue(b2[2] - b2[0] <= (hiY - loY) * 20.0 + 1.0)
    }

    @Test fun diagonalRotationEnclosesAllFourCorners() {
        val k = 0.70710678f
        val r = record(50f, -30f, k, k, -2f, 3f, 2f)
        val box = assertNotNull(DryWindowGeometry.dabDocAabb(r, 0, 20.0, 0.0))
        val m = 0.06f.toDouble()
        val loX = -2.0 - m; val hiX = 3.0 + m
        val loY = -2.0 - m; val hiY = 2.0 + m
        val ux = k.toDouble(); val uy = ux
        val vx = -uy; val vy = ux
        val qxs = doubleArrayOf(loX, hiX, loX, hiX)
        val qys = doubleArrayOf(loY, loY, hiY, hiY)
        for (i in 0..3) {
            val px = 50.0 + (qxs[i] * ux + qys[i] * vx) * 20.0
            val py = -30.0 + (qxs[i] * uy + qys[i] * vy) * 20.0
            assertEncloses(box, px, py, "diagonal corner $i")
        }
    }

    @Test fun storedNonUnitLeanScalesLiterallyWithoutNormalising() {
        // Same extents as cardinal, but lean (2,0): shader multiplies by 2, no normalisation.
        val m = 0.06f.toDouble()
        val loX = -1.0 - m; val hiX = 2.0 + m
        val r = record(100f, 200f, 2f, 0f, -1f, 2f, 1.5f)
        val box = assertNotNull(DryWindowGeometry.dabDocAabb(r, 0, 20.0, 0.0))
        val ex0 = 100.0 + loX * 2.0 * 20.0 // ~57.6
        val ex1 = 100.0 + hiX * 2.0 * 20.0 // ~182.4
        assertTrue(box[0] <= ex0 && box[2] >= ex1, "non-unit lean must double, got ${box.toList()} vs [$ex0,$ex1]")
        // A normalising implementation would report ~[78.8, 141.2]; that must fail here.
        assertTrue(box[2] - box[0] >= 120.0, "looks normalised (narrow): ${box.toList()}")
    }

    @Test fun asymmetricLeanWithNegativeCentre() {
        val r = record(-800f, -600f, 0.25f, -0.4f, -0.5f, 10f, 2f)
        val box = assertNotNull(DryWindowGeometry.dabDocAabb(r, 0, 20.0, 0.0))
        val m = 0.06f.toDouble()
        val loX = -0.5 - m; val hiX = 10.0 + m
        val loY = -2.0 - m; val hiY = 2.0 + m
        val ux = 0.25; val uy = -0.4; val vx = 0.4; val vy = 0.25
        val qxs = doubleArrayOf(loX, hiX, loX, hiX)
        val qys = doubleArrayOf(loY, loY, hiY, hiY)
        for (i in 0..3) {
            val px = -800.0 + (qxs[i] * ux + qys[i] * vx) * 20.0
            val py = -600.0 + (qxs[i] * uy + qys[i] * vy) * 20.0
            assertEncloses(box, px, py, "asymmetric corner $i")
        }
        assertTrue(box[0] < -800.0 && box[2] > -800.0 && box[1] < -600.0 && box[3] > -600.0)
    }

    @Test fun floatIntermediateCornersAreEnclosedAtLargeCoords() {
        val cxF = 100000f; val cyF = -100000f
        val uxF = 0.6f; val uyF = 0.8f
        val vxF = -uyF; val vyF = uxF
        val xMinF = -3f; val xMaxF = 5f; val yMaxF = 4f
        val pxF = 20f
        val mF = 0.06f
        val r = record(cxF, cyF, uxF, uyF, xMinF, xMaxF, yMaxF)
        val box = assertNotNull(DryWindowGeometry.dabDocAabb(r, 0, 20.0, 0.0))
        // Independent Float32 reference: every op in Float, as the GPU does.
        val loXF = xMinF - mF; val hiXF = xMaxF + mF
        val loYF = -yMaxF - mF; val hiYF = yMaxF + mF
        val qxsF = floatArrayOf(loXF, hiXF, loXF, hiXF)
        val qysF = floatArrayOf(loYF, loYF, hiYF, hiYF)
        for (i in 0..3) {
            val fx = cxF + (qxsF[i] * uxF + qysF[i] * vxF) * pxF
            val fy = cyF + (qxsF[i] * uyF + qysF[i] * vyF) * pxF
            assertTrue(fx.isFinite() && fy.isFinite())
            assertEncloses(box, fx.toDouble(), fy.toDouble(), "float corner $i")
        }
        // And the ideal Double corners too.
        val m = mF.toDouble()
        val loX = -3.0 - m; val hiX = 5.0 + m
        val loY = -4.0 - m; val hiY = 4.0 + m
        val ux = uxF.toDouble(); val uy = uyF.toDouble(); val vx = -uy; val vy = ux
        val qxs = doubleArrayOf(loX, hiX, loX, hiX)
        val qys = doubleArrayOf(loY, loY, hiY, hiY)
        for (i in 0..3) {
            assertEncloses(
                box,
                100000.0 + (qxs[i] * ux + qys[i] * vx) * 20.0,
                -100000.0 + (qxs[i] * uy + qys[i] * vy) * 20.0,
                "ideal corner $i",
            )
        }
    }

    @Test fun callerPadExpandsSymmetrically() {
        val r = record(0f, 0f, 1f, 0f, -1f, 1f, 1f)
        val plain = assertNotNull(DryWindowGeometry.dabDocAabb(r, 0, 20.0, 0.0))
        val padded = assertNotNull(DryWindowGeometry.dabDocAabb(r, 0, 20.0, 5.0))
        assertTrue(padded[0] <= plain[0] - 5.0 && padded[2] >= plain[2] + 5.0)
        assertTrue(padded[1] <= plain[1] - 5.0 && padded[3] >= plain[3] + 5.0)
        assertEquals(padded[2] - padded[0], (plain[2] - plain[0]) + 10.0, 1e-9)
    }

    @Test fun zeroExtentsYieldMarginOnlyBox() {
        val r = record(10f, 20f, 1f, 0f, 0f, 0f, 0f)
        val box = assertNotNull(DryWindowGeometry.dabDocAabb(r, 0, 20.0, 0.0))
        val m = 0.06f.toDouble() * 20.0 // ~1.2 px
        assertTrue(box[0] <= 10.0 - m && box[2] >= 10.0 + m)
        assertTrue(box[1] <= 20.0 - m && box[3] >= 20.0 + m)
        assertTrue(box[2] - box[0] <= 2 * m + 1.0, "margin box too wide: ${box.toList()}")
        assertTrue(box[0] <= box[2] && box[1] <= box[3])
    }

    @Test fun invalidRecordsAndParamsReturnNull() {
        val good = record(0f, 0f, 1f, 0f, -1f, 1f, 1f)
        // Short array / offsets / second-record count.
        assertNull(DryWindowGeometry.dabDocAabb(FloatArray(19), 0, 20.0))
        assertNull(DryWindowGeometry.dabDocAabb(good, -1, 20.0))
        assertNull(DryWindowGeometry.dabDocAabb(good, 1, 20.0))
        assertNull(DryWindowGeometry.dabDocAabb(FloatArray(0), 0, 20.0))
        val two = FloatArray(40)
        good.copyInto(two, 0)
        good.copyInto(two, 20)
        assertNotNull(DryWindowGeometry.dabDocAabb(two, 20, 20.0), "second record at offset 20 is valid")
        assertNull(DryWindowGeometry.dabDocAabb(two, 21, 20.0))
        // pxPerMm / pad.
        assertNull(DryWindowGeometry.dabDocAabb(good, 0, Double.NaN))
        assertNull(DryWindowGeometry.dabDocAabb(good, 0, Double.POSITIVE_INFINITY))
        assertNull(DryWindowGeometry.dabDocAabb(good, 0, 0.0))
        assertNull(DryWindowGeometry.dabDocAabb(good, 0, -20.0))
        assertNull(DryWindowGeometry.dabDocAabb(good, 0, 20.0, Double.NaN))
        assertNull(DryWindowGeometry.dabDocAabb(good, 0, 20.0, Double.POSITIVE_INFINITY))
        assertNull(DryWindowGeometry.dabDocAabb(good, 0, 20.0, -1.0))
        assertNull(DryWindowGeometry.dabDocAabb(good, 0, 1e300), "px overflows Float uniform")
        // Non-finite stored.
        for (k in 0..6) {
            val bad = good.copyOf()
            bad[k] = Float.NaN
            assertNull(DryWindowGeometry.dabDocAabb(bad, 0, 20.0), "NaN at $k must refuse")
            val bad2 = good.copyOf()
            bad2[k] = Float.POSITIVE_INFINITY
            assertNull(DryWindowGeometry.dabDocAabb(bad2, 0, 20.0), "Inf at $k must refuse")
        }
        // Extents.
        assertNull(DryWindowGeometry.dabDocAabb(record(0f, 0f, 1f, 0f, 2f, 1f, 1f), 0, 20.0), "xMax < xMin")
        assertNull(DryWindowGeometry.dabDocAabb(record(0f, 0f, 1f, 0f, -1f, 1f, -1f), 0, 20.0), "yMax < 0")
    }

    @Test fun arithmeticOverflowIsRejected() {
        // q ~1e30, lean ~1e10, px 20 -> ~2e41, far past Float32 max.
        val r = record(0f, 0f, 1e10f, 0f, -1e30f, 1e30f, 1e30f)
        assertNull(DryWindowGeometry.dabDocAabb(r, 0, 20.0), "Float overflow must refuse, not Inf")
        // Centre at Float max plus a large lobe overflows past Float max to Inf.
        val r2 = record(Float.MAX_VALUE, 0f, 1f, 0f, 0f, 1e33f, 1e33f)
        assertNull(DryWindowGeometry.dabDocAabb(r2, 0, 20.0))
    }

    // ---- (2) tile-aligned window feasibility ----

    @Test fun width768FitsOnlyWhenAligned() {
        // Needs origin -128 (not a 256 multiple): no tile-aligned window holds it.
        assertNull(
            DryWindowGeometry.windowOriginFor(doubleArrayOf(0.0, 0.0, 768.0, 768.0)),
            "768-wide rect at [0,0] needs ox=-128",
        )
        assertNull(
            DryWindowGeometry.windowOriginFor(doubleArrayOf(0.0, 0.0, 769.0, 10.0)),
            "wider than 768 never fits",
        )
        assertNull(
            DryWindowGeometry.windowOriginFor(doubleArrayOf(0.0, 0.0, 10.0, 769.0)),
            "taller than 768 never fits",
        )
    }

    @Test fun negativeOriginIsFeasibleAndDeterministic() {
        val rect = doubleArrayOf(-1000.0, -1000.0, -900.0, -900.0)
        val got = assertNotNull(DryWindowGeometry.windowOriginFor(rect))
        // loX = -900+128-1024 = -1796 -> ceil(-7.0156) = -7; hiX = -1128 -> floor(-4.406) = -5.
        assertEquals(Pair(-7, -7), got, "no preference takes the smallest feasible tile")
        assertTrue(MediaWindowMath.holds(got.first, got.second, rect))
    }

    @Test fun preferredTileIsHonouredOrClamped() {
        val rect = doubleArrayOf(-1000.0, -1000.0, -900.0, -900.0) // tx,ty in [-7,-5]
        assertEquals(Pair(-5, -5), assertNotNull(DryWindowGeometry.windowOriginFor(rect, -5, -5)))
        assertEquals(
            Pair(-5, -5),
            assertNotNull(DryWindowGeometry.windowOriginFor(rect, 100, 100)),
            "far preference clamps into the feasible interval",
        )
        assertEquals(
            Pair(-7, -7),
            assertNotNull(DryWindowGeometry.windowOriginFor(rect, -100, -100)),
            "low preference clamps up",
        )
    }

    @Test fun exactFitHasExactlyOneOrigin() {
        // w=h=768 and aligned: loX = 896+128-1024 = 0, hiX = 128-128 = 0.
        val rect = doubleArrayOf(128.0, 128.0, 896.0, 896.0)
        assertEquals(Pair(0, 0), assertNotNull(DryWindowGeometry.windowOriginFor(rect)))
        assertTrue(MediaWindowMath.holds(0, 0, rect))
        // Neighbours do not hold (margin is exact).
        assertTrue(!MediaWindowMath.holds(1, 0, rect))
        assertTrue(!MediaWindowMath.holds(0, 1, rect))
    }

    @Test fun invalidRectsAndCoordinateOverflowReturnNull() {
        assertNull(DryWindowGeometry.windowOriginFor(doubleArrayOf(0.0, 0.0, 10.0)))
        assertNull(DryWindowGeometry.windowOriginFor(doubleArrayOf(Double.NaN, 0.0, 10.0, 10.0)))
        assertNull(DryWindowGeometry.windowOriginFor(doubleArrayOf(Double.POSITIVE_INFINITY, 0.0, 10.0, 10.0)))
        assertNull(DryWindowGeometry.windowOriginFor(doubleArrayOf(5.0, 0.0, 4.0, 10.0)), "unordered x")
        assertNull(DryWindowGeometry.windowOriginFor(doubleArrayOf(0.0, 5.0, 10.0, 4.0)), "unordered y")
        // tx ~ 1e12/256 ~ 3.9e9, past Int.MAX: no Int origin, refuse without saturation.
        assertNull(DryWindowGeometry.windowOriginFor(doubleArrayOf(1e12, 0.0, 1e12 + 100.0, 100.0)))
        assertNull(DryWindowGeometry.windowOriginFor(doubleArrayOf(-1e12 - 100.0, 0.0, -1e12, 100.0)))
    }

    @Test fun returnedOriginAlwaysVerifiesAgainstExistingHelper() {
        val rects = listOf(
            doubleArrayOf(990.0, 990.0, 1010.0, 1010.0),
            doubleArrayOf(-1000.0, -1000.0, -900.0, -900.0),
            doubleArrayOf(128.0, 128.0, 896.0, 896.0),
            doubleArrayOf(250.0, 10.0, 300.0, 60.0),
        )
        for (r in rects) {
            val got = assertNotNull(DryWindowGeometry.windowOriginFor(r), "rect ${r.toList()} should fit")
            assertTrue(MediaWindowMath.holds(got.first, got.second, r), "origin $got must hold ${r.toList()}")
            // Tile alignment is structural (tx,ty are tile indices); the holds check above is the proof.
            // Origin must stay exactly representable for the existing helper (|tx*256| < 2^53).
            assertTrue(kotlin.math.abs(got.first.toDouble() * 256.0) < 9.007199254740992E15)
        }
    }

    @Test fun subnormalPxUnderflowRefusesAndTinySubnormalStillEncloses() {
        val r = record(0f, 0f, 1f, 0f, -1f, 1f, 1f)
        // 1e-46 rounds to 0f in the uniform -> refuse, never a zero-scale guess.
        assertNull(DryWindowGeometry.dabDocAabb(r, 0, 1e-46), "px underflowing to 0f must refuse")
        // 1e-45 rounds to the smallest subnormal (~1.4e-45f), still > 0 -> finite tiny box.
        val tiny = assertNotNull(DryWindowGeometry.dabDocAabb(r, 0, 1e-45), "subnormal uniform stays valid")
        assertTrue(tiny[0] <= 0.0 && 0.0 <= tiny[2] && tiny[1] <= 0.0 && 0.0 <= tiny[3])
        assertTrue((tiny[2] - tiny[0]) < 1e-30 && (tiny[3] - tiny[1]) < 1e-30, "tiny box ${tiny.toList()}")
    }

    @Test fun fmaContractedCornersStillEnclosed() {
        val cxF = 100000f; val cyF = -100000f
        val uxF = 0.6f; val uyF = 0.8f
        val r = record(cxF, cyF, uxF, uyF, -3f, 5f, 4f)
        val box = assertNotNull(DryWindowGeometry.dabDocAabb(r, 0, 20.0, 0.0))
        // Single-rounding (FMA-like) reference: exact Double corner rounded once to Float.
        val m = 0.06f.toDouble()
        val loX = -3.0 - m; val hiX = 5.0 + m
        val loY = -4.0 - m; val hiY = 4.0 + m
        val ux = uxF.toDouble(); val uy = uyF.toDouble(); val vx = -uy; val vy = ux
        val qxs = doubleArrayOf(loX, hiX, loX, hiX)
        val qys = doubleArrayOf(loY, loY, hiY, hiY)
        for (i in 0..3) {
            val exactX = 100000.0 + (qxs[i] * ux + qys[i] * vx) * 20.0
            val exactY = -100000.0 + (qxs[i] * uy + qys[i] * vy) * 20.0
            assertEncloses(box, exactX.toFloat().toDouble(), exactY.toFloat().toDouble(), "fma corner $i")
        }
    }

    @Test fun nearMaxRefusesWhenWithinErrOfFloatMax() {
        // Lean (1,0), px 20: fx = MAX + qx*20. qx ~ -5e28 -> ideal MAX-1e30 (< MAX, old passes),
        // but final half-ULP at MAX (~2e31) covers it -> conservative null.
        val near = record(Float.MAX_VALUE, 0f, 1f, 0f, -5e28f, -4e28f, 0f)
        assertNull(DryWindowGeometry.dabDocAabb(near, 0, 20.0), "within err of MAX must refuse")
        // Same shape 100x further below (offset ~1e32 >> err ~2e31) stays finite.
        val safe = record(Float.MAX_VALUE, 0f, 1f, 0f, -5e30f, -4e30f, 0f)
        val box = assertNotNull(DryWindowGeometry.dabDocAabb(safe, 0, 20.0), "far-below MAX stays finite")
        assertTrue(box[0] <= box[2] && box[1] <= box[3])
        assertTrue(box[2] <= Float.MAX_VALUE.toDouble(), "finite box ${box.toList()}")
    }

    @Test fun intEdgeFeasibleOrigins() {
        val txMax = Int.MAX_VALUE
        val oxMax = txMax.toDouble() * 256.0
        val rectMax = doubleArrayOf(oxMax + 800.0, 200.0, oxMax + 800.0, 200.0)
        val gotMax = assertNotNull(DryWindowGeometry.windowOriginFor(rectMax), "Int.MAX point must fit")
        assertEquals(txMax, gotMax.first, "must use edge tile, got $gotMax")
        assertTrue(MediaWindowMath.holds(gotMax.first, gotMax.second, rectMax))
        assertNull(
            DryWindowGeometry.windowOriginFor(doubleArrayOf(oxMax + 1000.0, 200.0, oxMax + 1000.0, 200.0)),
            "past edge window (needs tx > Int.MAX) must refuse",
        )
        val txMin = Int.MIN_VALUE
        val oxMin = txMin.toDouble() * 256.0
        val rectMin = doubleArrayOf(oxMin + 800.0, 200.0, oxMin + 800.0, 200.0)
        val gotMin = assertNotNull(DryWindowGeometry.windowOriginFor(rectMin), "Int.MIN point must fit")
        assertEquals(txMin, gotMin.first, "must use edge tile, got $gotMin")
        assertTrue(MediaWindowMath.holds(gotMin.first, gotMin.second, rectMin))
        assertNull(
            DryWindowGeometry.windowOriginFor(doubleArrayOf(oxMin + 0.0, 200.0, oxMin + 0.0, 200.0)),
            "left of edge window (needs tx < Int.MIN) must refuse",
        )
    }

    @Test fun fractionalAndLargeCoordRounding() {
        // Fractional 0.1px rect: hand interval tx,ty in [-3,-1], smallest (-3,-3) holds.
        val frac = doubleArrayOf(0.1, 0.1, 100.1, 100.1)
        assertEquals(Pair(-3, -3), assertNotNull(DryWindowGeometry.windowOriginFor(frac)))
        assertTrue(MediaWindowMath.holds(-3, -3, frac))
        // Large exactly-aligned 768 rect at 1e11 (ox = 390625000*256): single origin.
        val base = 1e11
        val large = doubleArrayOf(base + 128.0, base + 128.0, base + 896.0, base + 896.0)
        assertEquals(Pair(390625000, 390625000), assertNotNull(DryWindowGeometry.windowOriginFor(large)))
        assertTrue(MediaWindowMath.holds(390625000, 390625000, large))
    }
}
