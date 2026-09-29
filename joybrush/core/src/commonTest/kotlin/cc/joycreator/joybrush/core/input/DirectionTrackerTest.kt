package cc.joycreator.joybrush.core.input

import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DirectionTrackerTest {

    private fun angleDiff(a: Float, b: Float): Double = abs(Angles.wrap((a - b).toDouble()))

    @Test
    fun firstMovementSeedsTheDirection() {
        val d = DirectionTracker()
        d.update(PenSample(0f, 0f, 0.0))
        val a = d.update(PenSample(0f, 3f, 4.0))
        assertEquals(PI / 2, a.toDouble(), 1e-6)
    }

    @Test
    fun uprightPenSwingsIntoANewDirectionOverDistance() {
        val d = DirectionTracker(lengthScale = 6f)
        var x = 0f
        var t = 0.0
        d.update(PenSample(x, 0f, t))
        repeat(20) { x += 1f; t += 4.0; d.update(PenSample(x, 0f, t)) } // heading +x
        var y = 0f
        y += 1f; t += 4.0
        val afterOnePx = d.update(PenSample(x, y, t)) // turn to +y
        assertTrue(angleDiff(afterOnePx, (PI / 2).toFloat()) > 0.5, "should still lag after 1 px")
        repeat(40) { y += 1f; t += 4.0; d.update(PenSample(x, y, t)) }
        val after = d.update(PenSample(x, y + 1f, t + 4.0))
        assertTrue(angleDiff(after, (PI / 2).toFloat()) < 0.05, "should have swung round after 40 px")
    }

    @Test
    fun crossingTheBackOfTheCircleDoesNotSpin() {
        val d = DirectionTracker(lengthScale = 2f)
        // Moving in a slow arc through 180° (heading -x): consecutive outputs must never jump.
        var prev: Float? = null
        var t = 0.0
        for (i in 0..200) {
            val ang = PI * 0.9 + i * (PI * 0.2 / 200) // 162° → 198°
            val r = 400.0
            val x = (r * kotlin.math.cos(ang - PI / 2)).toFloat()
            val y = (r * kotlin.math.sin(ang - PI / 2)).toFloat()
            val a = d.update(PenSample(x, y, t))
            if (prev != null && i > 5) assertTrue(angleDiff(a, prev) < 0.1, "jump at step $i")
            prev = a
            t += 4.0
        }
    }

    @Test
    fun leaningPenPointsTheWayItLeans() {
        val d = DirectionTracker()
        val a = d.update(PenSample(0f, 0f, 0.0, tilt = 0.8f, azimuth = 1.0f))
        assertEquals(1.0f, a, 1e-6f)
    }

    @Test
    fun barrelSensorWins() {
        val d = DirectionTracker()
        val a = d.update(PenSample(0f, 0f, 0.0, tilt = 0.8f, azimuth = 1.0f, barrel = -2.0f))
        assertEquals(-2.0f, a)
    }

    @Test
    fun pausingDoesNotChangeTheDirection() {
        val d = DirectionTracker()
        d.update(PenSample(0f, 0f, 0.0))
        val a = d.update(PenSample(5f, 0f, 4.0))
        val b = d.update(PenSample(5.05f, 0.1f, 8.0)) // tiny tremor while paused
        assertEquals(a, b)
    }

    // ── review JB-0.01 F4: a broken reading must fall back, not turn NaN on ──────────────────────

    /**
     * The reviewer's REPRODUCED trigger, which is not the one originally filed: `hasAzimuth` is
     * `!isNaN`, so an Inf azimuth PASSES it and `cos(Inf)` is NaN — the branch then returns
     * `atan2(NaN, NaN)`. (`Inf` tilt with a NaN azimuth fails `hasAzimuth` and never gets there, so
     * it already failed safe; the azimuth is the channel that matters.) An impossible angle must fall
     * back to the direction of travel, which is the answer the pen would have given upright.
     */
    @Test
    fun aNonFiniteAzimuthFallsBackToTheDirectionOfTravel() {
        val d = DirectionTracker(lengthScale = 2f)
        d.update(PenSample(0f, 0f, 0.0))
        repeat(30) { i -> d.update(PenSample(0f, i.toFloat(), i * 4.0)) } // settled heading +y
        val a = d.update(
            PenSample(0f, 31f, 124.0, tilt = 1.0f, azimuth = Float.POSITIVE_INFINITY)
        )
        assertTrue(a.isFinite(), "an infinite azimuth must not switch NaN on, got $a")
        assertTrue(angleDiff(a, (PI / 2).toFloat()) < 0.05, "should fall back to travel (+y), got $a")

        val n = d.update(PenSample(0f, 32f, 128.0, tilt = 1.0f, azimuth = Float.NEGATIVE_INFINITY))
        assertTrue(n.isFinite(), "got $n")
        assertTrue(angleDiff(n, (PI / 2).toFloat()) < 0.05, "got $n")
    }

    /** An infinite tilt is not a measurement either: it must not be read as "lying perfectly flat". */
    @Test
    fun aNonFiniteTiltFallsBackToTheDirectionOfTravel() {
        val d = DirectionTracker(lengthScale = 2f)
        d.update(PenSample(0f, 0f, 0.0))
        repeat(30) { i -> d.update(PenSample(0f, i.toFloat(), i * 4.0)) } // settled heading +y
        val a = d.update(
            PenSample(0f, 31f, 124.0, tilt = Float.POSITIVE_INFINITY, azimuth = 0f)
        )
        assertTrue(a.isFinite(), "got $a")
        assertTrue(angleDiff(a, (PI / 2).toFloat()) < 0.05, "should fall back to travel (+y), got $a")
    }

    /**
     * The barrel shortcut is a direct return, so an infinite barrel value reached the tip maths as
     * `cos(Inf)` = NaN. It must fall back like every other broken reading (R1) — and "fall back" means
     * the next channel with something to say, which here (no tilt, no azimuth) is the direction of
     * travel. A barrel sensor that genuinely reports a number still wins outright: see
     * `barrelSensorWins` above.
     */
    @Test
    fun aNonFiniteBarrelFallsBackInsteadOfBeingUsedRaw() {
        val d = DirectionTracker(lengthScale = 2f)
        d.update(PenSample(0f, 0f, 0.0))
        repeat(30) { i -> d.update(PenSample(0f, i.toFloat(), i * 4.0)) } // settled heading +y
        val a = d.update(
            PenSample(0f, 31f, 124.0, barrel = Float.POSITIVE_INFINITY)
        )
        assertTrue(a.isFinite(), "an infinite barrel must not switch NaN on, got $a")
        assertTrue(angleDiff(a, (PI / 2).toFloat()) < 0.05, "should fall back to travel (+y), got $a")
        // …and the tracker is still healthy afterwards: a real barrel reading still wins
        assertEquals(-2.0f, d.update(PenSample(0f, 32f, 128.0, barrel = -2.0f)))
    }
}
