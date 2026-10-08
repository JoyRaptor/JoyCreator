package cc.joycreator.joybrush.core.anim

import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** JB-5.40a, the spec's eight tests. Each states the arithmetic it checks; each can fail on its own. */
class TweenTest {

    private fun rec(id: String, pts: List<Pair<Float, Float>>, argb: Int = 0xFF000000.toInt()) =
        StrokeRecord(id, "pen", 9L, 0f, 1f, pts.mapIndexed { i, (x, y) -> PenSample(x, y, i * 8.0, pressure = 0.5f) }, argb)

    /** [n] points from (x0, y0) to (x1, y1). */
    private fun seg(x0: Float, y0: Float, x1: Float, y1: Float, n: Int = 11) =
        (0 until n).map { i -> val f = i / (n - 1f); (x0 + f * (x1 - x0)) to (y0 + f * (y1 - y0)) }

    private fun turned(pts: List<Pair<Float, Float>>, deg: Double, cx: Float, cy: Float) = pts.map { (x, y) ->
        val a = deg * PI / 180.0
        val dx = x - cx; val dy = y - cy
        (cx + dx * cos(a) - dy * sin(a)).toFloat() to (cy + dx * sin(a) + dy * cos(a)).toFloat()
    }

    private fun near(want: Float, got: Float, tol: Float, what: String) = assertTrue(abs(want - got) <= tol, "$what: want $want, got $got")

    @Test
    fun aLineTurnedAboutItsMiddleTurnsHalfwayWithoutShrinking() {
        // 1. (-50,0)..(50,0) turned 90° about (0,0). At t = 0.5 it lies at 45° and every point keeps its distance from (0,0).
        val a = seg(-50f, 0f, 50f, 0f)
        val mid = Tween.between(listOf(rec("l", a)), listOf(rec("l", turned(a, 90.0, 0f, 0f))), 0.5f).single()
        for ((i, s) in mid.samples.withIndex()) near(hypot(a[i].first, a[i].second), hypot(s.x, s.y), 1e-3f, "radius of point $i")
        val end = mid.samples.last()
        near(45f, (atan2(end.y.toDouble(), end.x.toDouble()) * 180.0 / PI).toFloat(), 1e-3f, "the line's angle")
    }

    @Test
    fun aShiftedLineMovesInAStraightLineAtAnEvenSpeed() {
        // 2. B = A + (40, -20). At t = 0.25 every point is A + (10, -5); t = 0 is A and t = 1 is B.
        val a = seg(0f, 0f, 60f, 30f)
        val b = a.map { (x, y) -> (x + 40f) to (y - 20f) }
        val q = Tween.between(listOf(rec("l", a)), listOf(rec("l", b)), 0.25f).single()
        for ((i, s) in q.samples.withIndex()) { near(a[i].first + 10f, s.x, 1e-3f, "x$i"); near(a[i].second - 5f, s.y, 1e-3f, "y$i") }
        val zero = Tween.between(listOf(rec("l", a)), listOf(rec("l", b)), 0f).single()
        val one = Tween.between(listOf(rec("l", a)), listOf(rec("l", b)), 1f).single()
        for (i in a.indices) {
            near(a[i].first, zero.samples[i].x, 1e-3f, "t=0 x$i"); near(b[i].first, one.samples[i].x, 1e-3f, "t=1 x$i")
            near(a[i].second, zero.samples[i].y, 1e-3f, "t=0 y$i"); near(b[i].second, one.samples[i].y, 1e-3f, "t=1 y$i")
        }
    }

    @Test
    fun aLineScaledFourTimesIsTwiceAsBigHalfwayNotTwoAndAHalf() {
        // 3. B = (10,10) + 4·(A − (10,10)). Log-scale: at t = 0.5 the scale is √4 = 2, about (10,10).
        val a = seg(20f, 10f, 30f, 25f)
        val b = a.map { (x, y) -> (10f + 4f * (x - 10f)) to (10f + 4f * (y - 10f)) }
        val q = Tween.between(listOf(rec("l", a)), listOf(rec("l", b)), 0.5f).single()
        for ((i, s) in q.samples.withIndex()) {
            near(10f + 2f * (a[i].first - 10f), s.x, 1e-3f, "x$i"); near(10f + 2f * (a[i].second - 10f), s.y, 1e-3f, "y$i")
        }
    }

    @Test
    fun aNudgedEndBendsHalfwayAndTheRestStaysPut() {
        // 4. The last point lifted 10 px. The fit is a small turn, so unmoved points wobble by under a quarter pixel at
        //    t = 0.5, and the bent point is within a quarter pixel of halfway, (50, -5).
        val a = seg(-50f, 0f, 50f, 0f)
        val b = a.mapIndexed { i, p -> if (i == a.lastIndex) p.first to (p.second - 10f) else p }
        val q = Tween.between(listOf(rec("l", a)), listOf(rec("l", b)), 0.5f).single()
        for (i in 0 until a.lastIndex) {
            assertTrue(hypot(q.samples[i].x - a[i].first, q.samples[i].y - a[i].second) < 0.25f, "point $i stays put")
        }
        assertTrue(hypot(q.samples.last().x - 50f, q.samples.last().y + 5f) < 0.25f, "the bent end is halfway: ${q.samples.last()}")
    }

    @Test
    fun linesTurnedTogetherFormOneGroupAndLinesTurnedApartFormTwo() {
        // 5. Two lines turned 90° together about (0,0): one group, and at t = 0.5 both keep their distances from (0,0).
        val l1 = seg(10f, 0f, 50f, 0f); val l2 = seg(10f, 20f, 50f, 30f)
        val a = listOf(rec("one", l1), rec("two", l2))
        val together = listOf(rec("one", turned(l1, 90.0, 0f, 0f)), rec("two", turned(l2, 90.0, 0f, 0f)))
        val q = Tween.between(a, together, 0.5f)
        for ((line, src) in q.zip(listOf(l1, l2))) for ((i, s) in line.samples.withIndex()) {
            near(hypot(src[i].first, src[i].second), hypot(s.x, s.y), 1e-3f, "${line.id} radius $i")
        }
        // Turned 90° each about its own middle: two pivots, two groups.
        val apart = listOf(rec("one", turned(l1, 90.0, 30f, 0f)), rec("two", turned(l2, 90.0, 30f, 25f)))
        assertEquals(1, groupsOf(a, together).size)
        assertEquals(2, groupsOf(a, apart).size)
    }

    private fun groupsOf(a: List<StrokeRecord>, b: List<StrokeRecord>): List<List<Tween.Matched>> =
        Tween.groups(a.zip(b).map { (x, y) -> Tween.Matched(x, y, x.samples, y.samples) })

    @Test
    fun differentSampleCountsArePairedByArcLengthEndsToEnds() {
        // 6. B is A cut to its first 60% with fewer samples. Both are resampled to A's 21; at t = 1 the ends are B's ends.
        val a = seg(0f, 0f, 100f, 0f, n = 21)
        val b = seg(0f, 0f, 60f, 0f, n = 7)
        val one = Tween.between(listOf(rec("l", a)), listOf(rec("l", b)), 1f).single()
        assertEquals(21, one.samples.size)
        near(0f, one.samples.first().x, 1e-3f, "first end"); near(60f, one.samples.last().x, 1e-3f, "last end")
        near(30f, one.samples[10].x, 1e-3f, "the middle sample is the middle of B")
        val resampled = Tween.resample(rec("b", b).samples, 21)
        near(3f, resampled[1].x, 1e-4f, "equal arc steps: 60 / 20")
    }

    @Test
    fun colourSlidesTheShortWayRoundAndGreyTakesTheOtherHue() {
        // 7. Red to blue goes through magenta (hue 300), not green. Grey to red keeps red's hue: G = B < R.
        val m = Tween.hsbSlide(0xFFFF0000.toInt(), 0xFF0000FF.toInt(), 0.5f)
        assertEquals(0xFFFF00FF.toInt(), m, "magenta, ${m.toUInt().toString(16)}")
        val g = Tween.hsbSlide(0xFF808080.toInt(), 0xFFFF0000.toInt(), 0.5f)
        val r = (g shr 16) and 255; val gg = (g shr 8) and 255; val b = g and 255
        assertEquals(gg, b, "no hue swing"); assertTrue(r > gg, "reddish: $r $gg $b")
    }

    @Test
    fun theSameLinesInAnyOrderGiveTheSameTween() {
        // 8. Determinism: order in does not change any line out (compared by id), and A's order is kept.
        val l1 = seg(10f, 0f, 50f, 0f); val l2 = seg(10f, 20f, 50f, 30f); val l3 = seg(-40f, -40f, -10f, 5f)
        val a = listOf(rec("one", l1), rec("two", l2), rec("three", l3))
        val b = listOf(rec("one", turned(l1, 30.0, 0f, 0f)), rec("two", turned(l2, 30.0, 0f, 0f)), rec("three", turned(l3, -20.0, -25f, -18f)))
        val q1 = Tween.between(a, b, 0.4f)
        val q2 = Tween.between(a.reversed(), b.shuffled(kotlin.random.Random(3)), 0.4f)
        assertEquals(listOf("one", "two", "three"), q1.map { it.id })
        assertEquals(q1.associateBy { it.id }, q2.associateBy { it.id })
        // A line only in A is unchanged; a line only in B does not appear.
        val only = Tween.between(listOf(rec("solo", l1)), listOf(rec("other", l2)), 0.5f)
        assertEquals(listOf(rec("solo", l1)), only)
    }
}
