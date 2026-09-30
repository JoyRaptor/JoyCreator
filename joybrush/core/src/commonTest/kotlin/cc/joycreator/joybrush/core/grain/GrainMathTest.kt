package cc.joycreator.joybrush.core.grain

import cc.joycreator.joybrush.core.brush.GrainSpec
import cc.joycreator.joybrush.core.brush.Param
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * JB-1.05c: the CPU twin of the grain shader, and the NaN row both JB-1.02 reviewers made the gate.
 * Every expected value carries its derivation (LEAD_RULINGS R9).
 */
class GrainMathTest {

    private val eps = 1e-6f
    private fun assertNear(expected: Float, actual: Float, tol: Float = eps, msg: String = "") =
        assertTrue(abs(expected - actual) <= tol, "$msg expected $expected, was $actual")

    // ---- 2: the NaN row, channel by channel ---------------------------------------------------

    @Test
    fun aChannelThatIsNotANumberMeansNoTiltAndNoLean() {
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(0f, GrainMath.tiltAmount(bad), "tilt $bad is an upright pen")
            assertEquals(0f, GrainMath.leanX(bad), "azimuth $bad is no lean")
            assertEquals(0f, GrainMath.leanY(bad), "azimuth $bad is no lean")
        }
        assertEquals(0f, GrainMath.tiltAmount(0f))
        assertNear(1f, GrainMath.tiltAmount(GrainMath.TILT_FLAT)) // sin(pi/2) = 1
        assertNear(1f, GrainMath.tiltAmount(2.0f)) // beyond flat is clamped by the sensor's range: sin(pi/2)
        assertEquals(1f, GrainMath.leanX(0f)); assertEquals(0f, GrainMath.leanY(0f))
        assertNear(0f, GrainMath.leanX((PI / 2).toFloat()), 1e-6f)
        assertNear(1f, GrainMath.leanY((PI / 2).toFloat()), 1e-6f)
    }

    @Test
    fun withNoTiltAndNoLeanTheLevelIsFiniteEverywhere() {
        // 0 * NaN is the trap. With the guard, tiltAmount = 0 and lean = (0,0), so nothing NaN is ever multiplied.
        var n = -1.5f
        while (n <= 1.5f) {
            var m = -1.5f
            while (m <= 1.5f) {
                val level = GrainMath.grainLevel(
                    depth = 0.6f, tipCov = 1f, localNx = n, localNy = m, leanX = 0f, leanY = 0f,
                    tiltAmount = 0f, tiltGradient = 0.6f, radial = 0.3f,
                )
                assertTrue(level.isFinite() && level in 0f..1f, "level at ($n,$m) was $level")
                m += 0.25f
            }
            n += 0.25f
        }
    }

    // ---- 3: both endpoints of heightCoverage, at the values the maths produces ----------------

    @Test
    fun heightCoverageEndpointsArePinnedAtWhatTheMathsProduces() {
        // edge 0.25, level 0 -> threshold = 1 - 0 = 1; height 1 -> (1 - 1)/0.25 + 0.5 = 0.5 exactly.
        // It is 0.5 for ANY edge, because the +0.5 half-band overhangs the top of the height domain.
        for (edge in listOf(0.25f, 0.01f, 1.0f)) assertEquals(0.5f, GrainMath.heightCoverage(1f, 0f, edge), "edge $edge")
        // level 1 -> threshold 0; height 0 -> (0 - 0)/w + 0.5 = 0.5 exactly.
        assertEquals(0.5f, GrainMath.heightCoverage(0f, 1f, 0.25f))
        // height = 0.5 * edge = 0.125: (0.125 - 0)/0.25 + 0.5 = 1.0 exactly (0.125 and 0.25 are powers of two).
        assertEquals(1f, GrainMath.heightCoverage(0.125f, 1f, 0.25f))
        // edge 0 uses the 1e-3 floor: no divide by zero, and the answer is a number in 0..1.
        val z = GrainMath.heightCoverage(0.5f, 0.5f, 0f)
        assertTrue(z.isFinite() && z in 0f..1f)
        // The result is clamped into 0..1 whatever the inputs.
        assertEquals(1f, GrainMath.heightCoverage(1f, 1f, 0.01f)) // (1-0)/0.01 + 0.5 = 100.5 -> 1
        assertEquals(0f, GrainMath.heightCoverage(0f, 0f, 0.01f)) // (0-1)/0.01 + 0.5 = -99.5 -> 0
    }

    // ---- 4/5/6: the level ---------------------------------------------------------------------

    @Test
    fun theLeanSideTakesPaintFirstAndTheGradientSignReversesIt() {
        val lean = sin((PI / 4).toDouble()).toFloat() // tiltAmount = sin(45 deg)
        // Same length either side of the centre along the lean direction (1, 0).
        val onLeanSide = GrainMath.grainLevel(0.3f, 1f, 0.5f, 0f, 1f, 0f, lean, 0.6f, 0f)
        val opposite = GrainMath.grainLevel(0.3f, 1f, -0.5f, 0f, 1f, 0f, lean, 0.6f, 0f)
        // level = depth*tipCov + plane; plane = grad * tilt * dot(localN, lean) = +/-(0.6 * 0.7071 * 0.5) = +/-0.21213
        assertNear(0.3f + 0.6f * lean * 0.5f, onLeanSide, 1e-6f)
        assertNear(0.3f - 0.6f * lean * 0.5f, opposite, 1e-6f)
        assertTrue(onLeanSide > opposite)
        // A negative gradient reverses which side wins.
        val reversed = GrainMath.grainLevel(0.3f, 1f, 0.5f, 0f, 1f, 0f, lean, -0.6f, 0f)
        assertTrue(reversed < opposite + 1e-6f && reversed < onLeanSide)
        // At tiltAmount 0 the two sides are equal to the bit (the multiply by zero is exact).
        val a = GrainMath.grainLevel(0.3f, 1f, 0.5f, 0f, 1f, 0f, 0f, 0.6f, 0f)
        val b = GrainMath.grainLevel(0.3f, 1f, -0.5f, 0f, 1f, 0f, 0f, 0.6f, 0f)
        assertEquals(a, b)
    }

    @Test
    fun radialDropsTheRimAndLeavesTheCentre() {
        // dome = radial * |localN|^2: 0 at the centre, exactly `radial` at |localN| = 1 in any direction.
        val centre = GrainMath.grainLevel(0.8f, 1f, 0f, 0f, 0f, 0f, 0f, 0f, 0.3f)
        assertEquals(0.8f, centre)
        val rimX = GrainMath.grainLevel(0.8f, 1f, 1f, 0f, 0f, 0f, 0f, 0f, 0.3f)
        val rimD = GrainMath.grainLevel(0.8f, 1f, 0.6f, 0.8f, 0f, 0f, 0f, 0f, 0.3f) // (0.6, 0.8) has length 1
        assertNear(0.8f - 0.3f, rimX)
        assertNear(0.8f - 0.3f, rimD)
    }

    @Test
    fun aSoftEdgePressesLessDeep() {
        // level = depth*tipCov + plane - dome. At tipCov 0 and no plane: 0 - dome, clamped to 0.
        assertEquals(0f, GrainMath.grainLevel(0.9f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f))
        // At tipCov 1, depth 0.6: 0.6 exactly.
        assertEquals(0.6f, GrainMath.grainLevel(0.6f, 1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f))
    }

    // ---- 7/8: coverage never leaks; grains combine by minimum ---------------------------------

    @Test
    fun grainNeverLeaksPastTheTipEdge() {
        // smoothstep(0, 0.06, tipCov): 0 where the tip has no coverage, 1 from tipCov 0.06 up, and
        // smoothstep(0.03) = 0.5*0.5*(3 - 2*0.5) = 0.5 half way. (JB-1.05c's own test 7 said the gate is
        // closed AT 0.06; it is the other way round: it is closed at 0 and fully open at 0.06.)
        for (g in listOf(0f, 0.3f, 1f)) {
            assertEquals(0f, GrainMath.grainedCoverage(0f, g), "no tip coverage, grain $g")
            assertEquals(0f, GrainMath.grainedCoverage(-0.5f, g), "negative tip coverage, grain $g")
            assertEquals(g, GrainMath.grainedCoverage(0.06f, g), "gate fully open at 0.06, grain $g")
            assertEquals(g, GrainMath.grainedCoverage(1f, g), "solid tip, grain $g")
        }
        assertNear(0.5f, GrainMath.grainedCoverage(0.03f, 1f), 1e-6f)
        // Grain can only ever remove paint: it never exceeds the grain value itself.
        for (cov in listOf(0f, 0.02f, 0.04f, 0.5f, 1f)) assertTrue(GrainMath.grainedCoverage(cov, 0.7f) <= 0.7f + 1e-6f)
    }

    @Test
    fun twoGrainsCombineByMinimumAndAnOffGrainIsOne() {
        assertEquals(0.3f, GrainMath.combine(0.3f, 0.8f))
        assertEquals(0.3f, GrainMath.combine(0.8f, 0.3f)) // order does not matter — that is the point
        assertEquals(0.8f, GrainMath.combine(1f, 0.8f)) // tip grain off (=1)
        assertEquals(1f, GrainMath.combine(1f, 1f)) // both off: the dab is unchanged
    }

    // ---- 9: the tip's frame -------------------------------------------------------------------

    @Test
    fun rotatingOffsetAndLeanTogetherNeverChangesTheLevel() {
        // Decision 4 rotates localN AND the lean into the tip's frame. Both by the same angle, so their
        // dot product — the only thing the tilt plane reads — is invariant. Derivation: (Ro).(Rl) = o.l.
        // (JB-1.05c's own test 9 claimed the OTHER point wins at 90 degrees; that is not what the maths
        // gives: the point on the lean side wins at every angle.)
        val out = FloatArray(2)
        val leanOut = FloatArray(2)
        for (angle in listOf(0f, 0.7f, (PI / 2).toFloat(), 2.5f, -1.2f, 6.0f)) {
            GrainMath.intoTipFrame(0.8f, -0.3f, angle, out)
            val nx = out[0]; val ny = out[1]
            GrainMath.intoTipFrame(1f, 0f, angle, leanOut)
            val rotated = GrainMath.grainLevel(0.4f, 1f, nx, ny, leanOut[0], leanOut[1], 0.9f, 0.6f, 0.2f)
            val plain = GrainMath.grainLevel(0.4f, 1f, 0.8f, -0.3f, 1f, 0f, 0.9f, 0.6f, 0.2f)
            assertNear(plain, rotated, 1e-6f, "angle $angle")
        }
        // And the lean-side point is the higher one at 90 degrees, whichever frame it is asked in.
        val lean = 0.9f
        val onLean = GrainMath.grainLevel(0.3f, 1f, 1f, 0f, 1f, 0f, lean, 0.6f, 0f)
        val across = GrainMath.grainLevel(0.3f, 1f, 0f, 1f, 1f, 0f, lean, 0.6f, 0f)
        assertTrue(onLean > across)
    }

    // ---- 10: the two UV frames against each other ---------------------------------------------

    @Test
    fun theTipTextureTurnsWithTheBrushAndThePaperGrainDoesNot() {
        val uv = FloatArray(2)
        // pitch 8, angle 0: offset (0,0) is the middle of a repeat (0.5, 0.5).
        GrainMath.tipGrainUv(0f, 0f, 0f, 8f, uv)
        assertNear(0.5f, uv[0]); assertNear(0.5f, uv[1])
        // offset (8,0) is exactly one repeat further: (1.5, 0.5).
        GrainMath.tipGrainUv(8f, 0f, 0f, 8f, uv)
        assertNear(1.5f, uv[0]); assertNear(0.5f, uv[1])
        // angle pi/2, offset (0,8): q = (c*0 + s*8, -s*0 + c*8) = (8, ~0) -> (1.5, 0.5): the rotation is in the tip's frame.
        GrainMath.tipGrainUv(0f, 8f, (PI / 2).toFloat(), 8f, uv)
        assertNear(1.5f, uv[0], 1e-5f); assertNear(0.5f, uv[1], 1e-5f)

        // The paper grain is the document position in repeats: (8, 0) at pitch 8 is (1, 0). (JB-1.05c's own
        // list says (1.5, 0.0); the 0.5 belongs to the TIP texture's centring only, and adding it here would
        // shift the paper by half a repeat for no reason.)
        GrainMath.paperGrainUv(8f, 0f, 8f, uv)
        assertNear(1f, uv[0]); assertNear(0f, uv[1])
        // The assertion that matters: it does not depend on the brush at all — there is no angle parameter —
        // and negative document coordinates give negative texture coordinates (GL_REPEAT wants those).
        GrainMath.paperGrainUv(-16f, -4f, 8f, uv)
        assertNear(-2f, uv[0]); assertNear(-0.5f, uv[1])
    }

    // ---- 11: the pitch ------------------------------------------------------------------------

    @Test
    fun theGrainPitchIsAFixedPhysicalSize() {
        assertEquals(64f, GrainMath.pitchPxFor(1f)) // 64 / 1
        assertEquals(8f, GrainMath.pitchPxFor(8f)) // 64 / 8
        assertEquals(1f, GrainMath.pitchPxFor(64f)) // the top of the validated range: 1 px
        for (bad in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val e = runCatching { GrainMath.pitchPxFor(bad) }.exceptionOrNull()
            assertTrue(e is IllegalArgumentException && e.message!!.contains(bad.toString()), "scale $bad")
        }
        // A disabled grain is pitch 0: both textures OFF, the dab unchanged (Decision 9).
        assertEquals(0f, GrainMath.pitchPxFor(GrainSpec()))
        assertEquals(8f, GrainMath.pitchPxFor(GrainSpec(enabled = true, scale = 8f)))
    }

    // ---- 12: no NaN ever reaches the shader ---------------------------------------------------

    @Test
    fun noInputCombinationCanProduceANaNOrLeaveZeroToOne() {
        val values = listOf(0f, 0.5f, 1f, Float.POSITIVE_INFINITY, Float.NaN, -1f)
        for (depthRaw in values) for (edgeRaw in values) for (gradRaw in values) for (radialRaw in values) {
            val spec = GrainSpec(enabled = true, scale = 8f, depth = Param(0.7f), edge = edgeRaw,
                tiltGradient = gradRaw, radial = radialRaw)
            val u = GrainMath.uniformsFor(spec, depthRaw)
            for (tilt in listOf(0.5f, Float.NaN)) for (az in listOf(0.9f, Float.NaN)) {
                val level = GrainMath.grainLevel(
                    u.depth, 0.8f, 0.3f, -0.4f, GrainMath.leanX(az), GrainMath.leanY(az),
                    GrainMath.tiltAmount(tilt), u.tiltGradient, u.radial,
                )
                for (height in listOf(0f, 0.5f, 1f)) {
                    val cov = GrainMath.grainedCoverage(0.8f, GrainMath.heightCoverage(height, level, u.edge))
                    assertFalse(cov.isNaN(), "NaN for depth=$depthRaw edge=$edgeRaw grad=$gradRaw radial=$radialRaw")
                    assertTrue(cov in 0f..1f, "cov $cov out of range")
                }
            }
        }
    }

    // ---- 13: from a brush file to uniforms ----------------------------------------------------

    @Test
    fun aGrainThatIsOffOrNamesAnUnsafeAssetIsOff() {
        assertFalse(GrainMath.uniformsFor(GrainSpec(), 1f).enabled)
        assertFalse(GrainMath.uniformsFor(GrainSpec(enabled = true, image = "../../secret.png"), 1f).enabled)
        assertFalse(GrainMath.uniformsFor(GrainSpec(enabled = true, image = "a/b.png"), 1f).enabled)
        assertFalse(GrainMath.uniformsFor(GrainSpec(enabled = true, image = "x.jpg"), 1f).enabled)
        assertNull(GrainMath.assetNameFor(GrainSpec(image = "..png/../x.png")))
        assertEquals("cloud_256.png", GrainMath.assetNameFor(GrainSpec()))
        assertEquals("cloud_fine_256.png", GrainMath.assetNameFor(GrainSpec(image = "cloud_fine_256.png")))
    }

    @Test
    fun aDepthThatIsNotFiniteReadsAsTheFilesOwnBase() {
        val spec = GrainSpec(enabled = true, scale = 8f, depth = Param(0.4f))
        assertEquals(0.4f, GrainMath.uniformsFor(spec, Float.NaN).depth)
        assertEquals(0.9f, GrainMath.uniformsFor(spec, 0.9f).depth)
        assertEquals(1f, GrainMath.uniformsFor(spec, 7f).depth) // a finite depth is clamped to 0..1
        assertNotEquals(0f, sqrt(GrainMath.uniformsFor(spec, 0.9f).pitchPx))
    }
}
