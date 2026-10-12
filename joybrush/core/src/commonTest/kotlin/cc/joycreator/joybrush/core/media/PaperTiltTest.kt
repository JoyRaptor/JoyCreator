package cc.joycreator.joybrush.core.media

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PaperTiltTest {

    private val g = 9.81f

    // Which device axis is the screen's right / up when the phone is held in each display rotation (natural device axes:
    // x right, y up the screen at rotation 0). Worked out from how the phone turns, not from the code under test.
    private val up = listOf(Pair(0f, 1f), Pair(1f, 0f), Pair(0f, -1f), Pair(-1f, 0f))
    private val right = listOf(Pair(1f, 0f), Pair(0f, -1f), Pair(-1f, 0f), Pair(0f, 1f))

    /**
     * The gravity reading (device axes) of a phone lying nearly flat whose low side is [sx] to the screen's right and
     * [sy] toward the screen's bottom (both in sin units) when held in display rotation [rot].
     */
    private fun tip(rot: Int, sx: Float, sy: Float): FloatArray {
        val downX = sx * right[rot].first - sy * up[rot].first
        val downY = sx * right[rot].second - sy * up[rot].second
        return floatArrayOf(-downX * g, -downY * g, g * sqrt(1f - sx * sx - sy * sy))
    }

    private fun feed(t: PaperTilt, r: FloatArray, rot: Int = 0, canvas: Float = 0f) = t.feed(r[0], r[1], r[2], rot, canvas)

    /** Feed the same reading until the low-pass has settled. */
    private fun settle(t: PaperTilt, r: FloatArray, rot: Int = 0, canvas: Float = 0f): PaperSlope {
        var s = PaperSlope.FLAT
        repeat(80) { s = feed(t, r, rot, canvas) }
        return s
    }

    private fun near(expected: Float, actual: Float, tol: Float = 1e-3f, what: String = "") =
        assertTrue(abs(expected - actual) <= tol, "$what expected $expected, got $actual")

    private val flat = floatArrayOf(0f, 0f, g)

    @Test fun theSwitchOnPoseIsFlatWhateverItIs() {
        val t = PaperTilt()
        // Held at a comfortable 40 degrees: the very first reading, and every one after it, is level.
        val pose = floatArrayOf(0f, g * 0.64f, g * 0.77f)
        assertEquals(PaperSlope.FLAT, feed(t, pose))
        assertEquals(PaperSlope.FLAT, settle(t, pose))
    }

    @Test fun resetTakesANewFlat() {
        val t = PaperTilt()
        settle(t, flat)
        assertTrue(settle(t, tip(0, 0.5f, 0f)).x > 0.4f)
        t.reset()
        assertEquals(PaperSlope.FLAT, t.slope)
        assertEquals(PaperSlope.FLAT, settle(t, tip(0, 0.5f, 0f)), "the pose held at the reset is the new flat")
    }

    @Test fun deadZoneSwallowsAHandsTremble() {
        val t = PaperTilt()
        settle(t, flat)
        // 0.03 of gravity is under the 0.04 dead zone.
        assertEquals(PaperSlope.FLAT, settle(t, tip(0, 0.03f, 0f)))
        // 0.10 is outside it, and what comes out is the excess.
        val s = settle(t, tip(0, 0.10f, 0f))
        near(0.06f, s.x, 2e-3f, "past the dead zone")
        near(0f, s.y, 2e-3f)
    }

    @Test fun lowPassClosesAQuarterOfTheGapPerReading() {
        val t = PaperTilt(deadZone = 0f)
        settle(t, flat)
        val r = tip(0, 0.4f, 0f)
        near(0.25f * 0.4f, feed(t, r).x, 1e-3f, "first reading")
        near(0.4f * (1f - 0.75f * 0.75f), feed(t, r).x, 1e-3f, "second reading")
        near(0.4f, settle(t, r).x, 1e-3f, "settled")
    }

    @Test fun portraitDownhillFollowsTheLowSide() {
        val t = PaperTilt()
        settle(t, flat)
        // Right edge down: paint runs right (+x). Bottom edge down: paint runs down the screen (+y, y down).
        val r = settle(t, tip(0, 0.5f, 0f))
        near(0.46f, r.x, 2e-3f, "right x"); near(0f, r.y, 2e-3f, "right y")
        val b = settle(t, tip(0, 0f, 0.5f))
        near(0f, b.x, 2e-3f, "bottom x"); near(0.46f, b.y, 2e-3f, "bottom y")
        assertTrue(settle(t, tip(0, -0.5f, 0f)).x < -0.4f)
        assertTrue(settle(t, tip(0, 0f, -0.5f)).y < -0.4f)
    }

    @Test fun everyDisplayRotationRunsThePaintTowardTheLowSideOnScreen() {
        for (r in 0..3) {
            val t = PaperTilt()
            settle(t, flat, r)
            val toRight = settle(t, tip(r, 0.5f, 0f), r)
            near(0.46f, toRight.x, 2e-3f, "rotation $r right x"); near(0f, toRight.y, 2e-3f, "rotation $r right y")
            val toBottom = settle(t, tip(r, 0f, 0.5f), r)
            near(0f, toBottom.x, 2e-3f, "rotation $r bottom x"); near(0.46f, toBottom.y, 2e-3f, "rotation $r bottom y")
            val upLeft = settle(t, tip(r, -0.3f, -0.3f), r)
            assertTrue(upLeft.x < -0.15f && upLeft.y < -0.15f, "rotation $r up-left: $upLeft")
        }
    }

    @Test fun aTurnedCanvasTurnsTheSlopeIntoItsOwnAxes() {
        // The page is turned a quarter clockwise: document +x points screen-down, document +y points screen-left.
        val quarter = (PI / 2).toFloat()
        val t = PaperTilt()
        settle(t, flat, 0, quarter)
        // Paint running screen-right is document -y.
        val s = settle(t, tip(0, 0.4f, 0f), 0, quarter)
        near(0f, s.x, 1e-3f, "x"); near(-0.36f, s.y, 2e-3f, "y")
        // Paint running down the screen is document +x.
        val d = settle(t, tip(0, 0f, 0.4f), 0, quarter)
        near(0.36f, d.x, 2e-3f, "down x"); near(0f, d.y, 2e-3f, "down y")
        // Half a turn: everything reverses.
        val h = PaperTilt()
        settle(h, flat, 0, PI.toFloat())
        val rev = settle(h, tip(0, 0.4f, 0f), 0, PI.toFloat())
        near(-0.36f, rev.x, 2e-3f, "half-turn x"); near(0f, rev.y, 2e-3f, "half-turn y")
    }

    @Test fun theReferenceSurvivesTheScreenTurning() {
        // Switched on in portrait; then the screen turns to landscape with the phone not moving: still level.
        val t = PaperTilt()
        val pose = tip(0, 0.2f, 0.1f)
        settle(t, pose, 0)
        assertEquals(PaperSlope.FLAT, settle(t, pose, 1))
    }

    @Test fun theSlopeNeverPassesUpright() {
        val t = PaperTilt()
        settle(t, tip(0, -0.6f, 0f))
        // From 0.6 one way to 0.6 the other is a difference of 1.2; the answer is still capped at 1.
        val s = settle(t, tip(0, 0.6f, 0f))
        near(1f, hypot(s.x, s.y), 1e-3f)
    }

    @Test fun aSilentSensorDoesNothing() {
        val t = PaperTilt()
        settle(t, flat)
        assertEquals(PaperSlope.FLAT, t.feed(0f, 0f, 0f, 0, 0f))
        assertEquals(PaperSlope.FLAT, t.feed(Float.NaN, 1f, 1f, 0, 0f))
    }

    // ── the pad ──

    @Test fun padCentreIsFlat() {
        assertEquals(PaperSlope.FLAT, PaperTilt.padToSlope(0f, 0f, 100f))
        assertEquals(PaperSlope.FLAT, PaperTilt.padToSlope(1.5f, -1f, 100f), "a puck within 2% of the middle is level")
    }

    @Test fun padCurveIsFineNearLevelAndUprightAtTheRim() {
        near(1f, PaperTilt.padToSlope(100f, 0f, 100f).x, 1e-5f, "rim")
        // Half way out is 90 x 0.5^1.5 = 31.8 degrees; three quarters is 58.5.
        near(sin(31.82 * PI / 180).toFloat(), hypot(PaperTilt.padToSlope(50f, 0f, 100f).x, 0f), 1e-3f, "half")
        near(sin(58.47 * PI / 180).toFloat(), PaperTilt.padToSlope(75f, 0f, 100f).x, 1e-3f, "three quarters")
    }

    @Test fun padClampsPastTheRimWithoutChangingDirection() {
        val s = PaperTilt.padToSlope(300f, 400f, 100f)
        near(1f, hypot(s.x, s.y), 1e-5f)
        near(0.6f, s.x, 1e-5f)
        near(0.8f, s.y, 1e-5f)
    }

    @Test fun padIsYDown() {
        // Pulled toward the bottom of the screen: downhill is the bottom, which is +y in the app's y-down space.
        val down = PaperTilt.padToSlope(0f, 60f, 100f)
        assertEquals(0f, down.x)
        assertTrue(down.y > 0f)
        val up = PaperTilt.padToSlope(0f, -60f, 100f)
        assertTrue(up.y < 0f)
        near(-down.y, up.y, 1e-6f)
        val left = PaperTilt.padToSlope(-60f, 0f, 100f)
        assertTrue(left.x < 0f)
    }

    @Test fun padOnATurnedPageStillPointsDownTheScreen() {
        val quarter = (PI / 2).toFloat()
        val screen = PaperTilt.padToSlope(0f, 100f, 100f)   // toward the bottom of the screen
        val (dx, dy) = PaperTilt.screenToDoc(screen.x, screen.y, quarter)
        near(1f, dx, 1e-5f, "doc +x is screen-down on a page turned a quarter clockwise")
        near(0f, dy, 1e-5f)
        val (ux, uy) = PaperTilt.screenToDoc(screen.x, screen.y, 0f)
        near(0f, ux, 1e-6f)
        near(1f, uy, 1e-6f)
    }

    @Test fun padBadInputIsFlat() {
        assertEquals(PaperSlope.FLAT, PaperTilt.padToSlope(10f, 10f, 0f))
        assertEquals(PaperSlope.FLAT, PaperTilt.padToSlope(Float.NaN, 10f, 50f))
    }

    @Test fun combineLetsTheOffCentrePadWin() {
        val phone = PaperSlope(0.3f, 0.1f)
        assertEquals(phone, PaperTilt.combine(PaperSlope.FLAT, phone))
        val pad = PaperSlope(-0.5f, 0f)
        assertEquals(pad, PaperTilt.combine(pad, phone))
    }
}
