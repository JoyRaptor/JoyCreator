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
}
