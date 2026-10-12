package cc.joycreator.joybrush.core.select

import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.stroke.StrokeEdit
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** JB-5.20e K15: a pushed line stays a curve. */
class LineDensifyTest {

    private fun line(n: Int, step: Float) = StrokeRecord(
        id = "a", brushId = "joybrush.ink", seed = 1L, smoothing = 0f, screenPerDoc = 1f,
        samples = (0 until n).map { PenSample(x = it * step, y = 0f, timeMs = it * 8.0, pressure = 0.5f, tilt = 0.4f, azimuth = 0.2f) },
    )

    private fun worstTurn(s: List<PenSample>): Double {
        var worst = 0.0
        for (i in 1 until s.size - 1) {
            val a = atan2((s[i].y - s[i - 1].y).toDouble(), (s[i].x - s[i - 1].x).toDouble())
            val b = atan2((s[i + 1].y - s[i].y).toDouble(), (s[i + 1].x - s[i].x).toDouble())
            var t = abs(b - a)
            if (t > PI) t = 2 * PI - t
            worst = maxOf(worst, t)
        }
        return worst
    }

    @Test
    fun nothingChangesWhenNoGapIsTooLong() {
        val r = line(20, 1f)
        assertSame(r, LineDensify.densify(r, 2f))
    }

    @Test
    fun aLongGapIsFilledOnTheLineAndKeepsEveryOriginalSample() {
        val r = line(3, 10f) // 0, 10, 20: gaps of 10
        val d = LineDensify.densify(r, 2f)
        assertTrue(d.samples.size > r.samples.size)
        for (s in r.samples) assertTrue(d.samples.contains(s), "original sample kept exactly")
        for (i in 1 until d.samples.size) assertTrue(hypot(d.samples[i].x - d.samples[i - 1].x, d.samples[i].y - d.samples[i - 1].y) <= 2f + 1e-4f)
        assertTrue(d.samples.all { abs(it.y) < 1e-5f }, "a straight line stays straight")
        assertTrue(d.samples.zipWithNext().all { (a, b) -> b.timeMs >= a.timeMs }, "time keeps running forward")
        assertTrue(d.samples.all { it.pressure == 0.5f && it.tilt == 0.4f && abs(it.azimuth - 0.2f) < 1e-6f })
    }

    @Test
    fun aPushedLineIsFilledInWithoutAnySharperTurn() {
        // A straight line pushed by 3x the ring radius: the push opens gaps of several px on its flanks. Filled in, no gap
        // is longer than 2 px and no corner is sharper than the push itself made: the samples sit on a smooth curve, the
        // turns are only shared out among more of them.
        val r = line(100, 1f)
        val radius = 20f
        val pushed = StrokeEdit.reshape(r, 50f, 0f, 0f, 3 * radius, radius)
        val d = LineDensify.densify(pushed, 2f)
        for (i in 1 until d.samples.size) assertTrue(hypot(d.samples[i].x - d.samples[i - 1].x, d.samples[i].y - d.samples[i - 1].y) <= 2f + 1e-3f)
        assertTrue(worstTurn(d.samples) <= worstTurn(pushed.samples) + 1e-9, "${worstTurn(d.samples)} vs ${worstTurn(pushed.samples)}")
    }

    @Test
    fun theLeanTurnsTheShortWayRound() {
        val r = StrokeRecord(
            id = "a", brushId = "joybrush.ink", seed = 1L, smoothing = 0f, screenPerDoc = 1f,
            samples = listOf(
                PenSample(0f, 0f, 0.0, azimuth = (PI - 0.1).toFloat()),
                PenSample(10f, 0f, 10.0, azimuth = (-PI + 0.1).toFloat()),
            ),
        )
        val d = LineDensify.densify(r, 4f)
        assertTrue(d.samples.size >= 4, "a gap of 10 at 4 needs at least two more samples")
        for (i in 1 until d.samples.size) assertTrue(hypot(d.samples[i].x - d.samples[i - 1].x, d.samples[i].y - d.samples[i - 1].y) <= 4f + 1e-4f)
        for (m in d.samples) assertTrue(abs(abs(m.azimuth) - PI.toFloat()) < 0.15f, "through PI, not through 0: ${m.azimuth}")
    }
}
