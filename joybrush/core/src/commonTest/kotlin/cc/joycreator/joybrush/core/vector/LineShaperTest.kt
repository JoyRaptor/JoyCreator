package cc.joycreator.joybrush.core.vector

import cc.joycreator.joybrush.core.paint.Dab
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The whole-stroke line maths (VECTOR_RENDER_DESIGN §3). Every expected number is worked by hand from a straight line
 * of dabs, so a change to the arithmetic is a red test, not a different-looking line on the phone.
 *
 * This is a CLASS on purpose: JUnit runs methods on an instance, so top-level `@Test` functions would report green
 * and execute nothing.
 */
class LineShaperTest {

    /** Dabs every [step] px along +x from 0 to [length], all of radius [r]. */
    private fun line(length: Float, step: Float, r: Float = 5f): List<Dab> {
        val n = (length / step).toInt()
        return List(n + 1) { i -> Dab(x = i * step, y = 0f, radius = r, angle = 0.3f, flow = 0.8f, pressure = 0.5f) }
    }

    private fun near(expected: Float, actual: Float, tol: Float = 1e-4f, what: String = "") =
        assertTrue(abs(expected - actual) <= tol, "$what expected $expected ± $tol, was $actual")

    // ── taper ────────────────────────────────────────────────────────────────────────────

    @Test
    fun taperShrinksBothEndsToTheTipAndLeavesTheBodyAlone() {
        // 101 dabs 1 px apart, diameter 10: taper in over 20 px, out over 30 px. A taper that measured in dabs rather than
        // pixels, or in radii rather than diameters, would put full width somewhere other than 20 and 70.
        val dabs = line(100f, 1f)
        val out = LineShaper.taper(dabs, LineStyle(taperIn = 2f, taperOut = 3f, taperTip = 0.1f), diameterPx = 10f)
        near(0.5f, out.first().radius, what = "start tip = 5 × 0.1")
        near(0.5f, out.last().radius, what = "end tip = 5 × 0.1")
        for (i in 20..70) near(5f, out[i].radius, what = "body dab $i")
        for (i in 1..20) assertTrue(out[i].radius > out[i - 1].radius, "taper-in must grow, dab $i")
        for (i in 71..100) assertTrue(out[i].radius < out[i - 1].radius, "taper-out must shrink, dab $i")
        // Only the radius moves: a taper that touched position, angle or flow would move the picker and the scatter.
        for (i in dabs.indices) assertEquals(dabs[i].copy(radius = out[i].radius), out[i], "dab $i: only radius changes")
    }

    @Test
    fun aShortStrokeSharesItsLengthBetweenTheTwoTapers() {
        // 10 px stroke, tapers 20 + 30 px asked: scaled by 10/50 to 4 + 6 px, so they meet at 4 px and full width is
        // reached exactly there. Without the scaling, every dab would sit inside both tapers and never reach full width.
        val out = LineShaper.taper(line(10f, 1f), LineStyle(taperIn = 2f, taperOut = 3f), diameterPx = 10f)
        near(5f, out[4].radius, what = "the meeting point is full width")
        near(0.5f, out[0].radius)
        near(0.5f, out[10].radius)
        assertTrue(out.all { it.radius.isFinite() && it.radius > 0f })
    }

    @Test
    fun aTapIsNotTapered() {
        // A tap's two ends are the same point, so both tapers would shrink it to a tenth. Two dabs at one point too.
        val tap = listOf(Dab(3f, 4f, 5f), Dab(3f, 4f, 5f))
        assertSame(tap, LineShaper.taper(tap, LineStyle(taperIn = 2f, taperOut = 2f), 10f))
        val one = listOf(Dab(3f, 4f, 5f))
        assertSame(one, LineShaper.taper(one, LineStyle(taperIn = 2f, taperOut = 2f), 10f))
    }

    @Test
    fun theTaperShapeOrdersNeedlePointAndBullet() {
        // At a quarter of the way in, u = 1 − 0.75² = 0.4375. Needle p=2: 0.1914; point p=1: 0.4375; bullet p=0.5: 0.6614.
        // Then width = 0.1 + 0.9 · that. A shape knob wired backwards swaps the first and last.
        val tip = 0.1f
        near(0.1f + 0.9f * 0.19140625f, LineShaper.ramp(1f, 4f, tip, 2f))
        near(0.1f + 0.9f * 0.4375f, LineShaper.ramp(1f, 4f, tip, 1f))
        near(0.1f + 0.9f * sqrt(0.4375f), LineShaper.ramp(1f, 4f, tip, 0.5f))
        // Every shape meets the body with no kink: just inside the end, all three are within 1e-3 of full width.
        for (p in floatArrayOf(0.5f, 1f, 2f)) near(1f, LineShaper.ramp(3.98f, 4f, tip, p), 1e-3f, "p=$p at the join")
    }

    @Test
    fun brokenStyleNumbersChangeNothing() {
        // R1/R10: NaN and infinities read as "no taper", never as a NaN radius that the GPU would draw as nothing.
        val dabs = line(50f, 1f)
        assertSame(dabs, LineShaper.taper(dabs, LineStyle(taperIn = Float.NaN, taperOut = Float.NEGATIVE_INFINITY), 10f))
        assertSame(dabs, LineShaper.taper(dabs, LineStyle(taperIn = 1f), Float.NaN))
        val odd = LineShaper.taper(dabs, LineStyle(taperIn = 1f, taperTip = Float.NaN, taperShape = Float.NaN), 10f)
        assertTrue(odd.all { it.radius.isFinite() })
    }

    // ── dashes ───────────────────────────────────────────────────────────────────────────

    @Test
    fun dashesFallWhereTheHandWorkedPatternSaysTheyDo() {
        // Diameter 10, on 3, off 2: period 50 px, centre window 30 − 10 = 20 px. Dabs every 0.5 px from 0 to 200, so the
        // runs are [0,20], [50,70], [100,120], [150,170] and the lone dab at 200 (start of the fifth period).
        val runs = LineShaper.dashRuns(line(200f, 0.5f), LineStyle(dashOn = 3f, dashOff = 2f), 10f)
        assertEquals(5, runs.size, "four dashes and the start of a fifth")
        for (k in 0..3) {
            near(k * 50f, runs[k].first().x, what = "dash $k start")
            near(k * 50f + 20f, runs[k].last().x, what = "dash $k end")
        }
        near(200f, runs[4].single().x)
    }

    @Test
    fun aDashOfOneDiameterOrLessIsADot() {
        // on 0.5, off 1.5, diameter 10: period 20 px, so 11 dots at 0, 20, …, 200, each exactly one dab.
        val runs = LineShaper.dashRuns(line(200f, 0.5f), LineStyle(dashOn = 0.5f, dashOff = 1.5f), 10f)
        assertEquals(11, runs.size)
        runs.forEachIndexed { k, r -> assertEquals(1, r.size, "dot $k"); near(k * 20f, r[0].x) }
    }

    @Test
    fun aSolidStyleIsOneRunOfTheSameList() {
        val dabs = line(40f, 1f)
        val runs = LineShaper.dashRuns(dabs, LineStyle(), 10f)
        assertEquals(1, runs.size)
        assertSame(dabs, runs[0])
        assertSame(dabs, LineShaper.dash(dabs, LineStyle(dashOn = 3f, dashOff = 0f), 10f), "no gap is no dash")
    }

    @Test
    fun dashesFollowTheDrawnPathRoundACorner() {
        // An L: 0..30 along x, then 30 down y. Period 20 (on 1.5, off 0.5 at d 10), window 5. A dash finder that used
        // straight-line distance from the start instead of distance along the path would put the third dash elsewhere.
        val l = List(31) { Dab(it.toFloat(), 0f, 5f) } + List(30) { Dab(30f, (it + 1).toFloat(), 5f) }
        val runs = LineShaper.dashRuns(l, LineStyle(dashOn = 1.5f, dashOff = 0.5f), 10f)
        assertEquals(4, runs.size)
        assertEquals(Dab(30f, 10f, 5f), runs[2].first(), "third dash starts 40 px along the path: 30 across, 10 down")
    }

    // ── deep zoom ────────────────────────────────────────────────────────────────────────

    @Test
    fun theStepLimitPutsTheDipAtAQuarterScreenPixel() {
        for ((r, z) in listOf(0.5f to 16f, 1f to 8f, 3f to 4f, 12f to 16f)) {
            val h = LineShaper.maxStepFor(r, z)
            val dip = r - sqrt(r * r - (h / 2) * (h / 2))
            near(0.25f, dip * z, 1e-3f, "r=$r zoom=$z")
        }
        // The placer's 0.5 px floor is fine for a 6 px ink at 1:1 and NOT for a 1 px fineliner at 8×; that is the case
        // the deep-zoom levels exist for.
        assertTrue(LineShaper.maxStepFor(3f, 1f) > 0.5f)
        assertTrue(LineShaper.maxStepFor(0.5f, 8f) < 0.5f)
        assertEquals(Float.POSITIVE_INFINITY, LineShaper.maxStepFor(0.01f, 1f), "a hairline below a quarter pixel")
    }

    @Test
    fun densifyKeepsEveryOriginalAndFillsTheGaps() {
        val a = Dab(0f, 0f, 0.5f, angle = (350.0 * PI / 180).toFloat(), tilt = 0.2f, hardness = Float.NaN)
        val b = Dab(4f, 0f, 1.5f, angle = (10.0 * PI / 180).toFloat(), tilt = 0.6f, hardness = 0.9f)
        val out = LineShaper.densify(listOf(a, b), zoom = 16f)
        assertSame(a, out.first())
        assertSame(b, out.last())
        val limit = LineShaper.maxStepFor(0.5f, 16f)
        for (i in 1 until out.size) {
            assertTrue(hypot(out[i].x - out[i - 1].x, out[i].y - out[i - 1].y) <= limit + 1e-4f, "gap $i")
        }
        val mid = out[out.size / 2]
        // The angle goes the short way through 0°, never back round through 180°.
        assertTrue(abs(mid.angle) < (11.0 * PI / 180), "angle short way: ${mid.angle}")
        assertTrue(mid.hardness.isNaN(), "an optional channel one end lacks stays absent")
        near(0.5f + (mid.x / 4f) * 1f, mid.radius, 1e-4f, "radius linear in position")
    }

    @Test
    fun onlySolidHardBrushesMayBeDensified() {
        assertTrue(LineShaper.densifySafe(flow = 1f, hardness = 0.95f, overlap = 12f), "ink")
        assertTrue(LineShaper.densifySafe(flow = 0.7f, hardness = 0.95f, overlap = 12f), "marker: 0.3^12 ≈ 5e-7")
        assertTrue(!LineShaper.densifySafe(flow = 0.05f, hardness = 0.95f, overlap = 12f), "airbrush flow builds up")
        assertTrue(!LineShaper.densifySafe(flow = 1f, hardness = 0.5f, overlap = 12f), "a soft rim builds up")
        assertTrue(!LineShaper.densifySafe(flow = Float.NaN, hardness = 1f, overlap = 12f))
    }

    // ── wire ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun aWireIsTheSameScreenWidthAtEveryZoom() {
        val dabs = line(20f, 0.5f, r = 3f)
        for (z in floatArrayOf(0.25f, 1f, 4f, 16f)) {
            val w = LineShaper.wire(dabs, LineStyle(screenWidthPx = 1.5f), z)
            assertTrue(w.all { abs(it.radius * 2f * z - 1.5f) < 1e-4f }, "1.5 screen px wide at $z×")
            val limit = LineShaper.maxStepFor(w[0].radius, z)
            for (i in 1 until w.size) assertTrue(w[i].x - w[i - 1].x <= limit + 1e-4f, "no beads at $z×, gap $i")
        }
        near(0.5f, LineShaper.wireRadius(0.2f, 1f), what = "never thinner than one target pixel")
        assertSame(dabs, LineShaper.wire(dabs, LineStyle(), 4f), "not a wire: untouched")
    }

    // ── review M-minors (Lead, 2026-10-11) ───────────────────────────────────────────────

    @Test
    fun aDashShorterThanTheGapBetweenDabsIsStillADot() {
        // Dabs every 12 px, dots every 20 px (on 0.5, off 1.5, diameter 10). Periods 0..12 each hold at least one dab, so
        // there are 13 dots. The old rule kept a period's first dab only when it fell inside the dash itself, and lost
        // the period starting at 40 (its first dab, at 48, is 8 px in).
        val sparse = List(21) { Dab(it * 12f, 0f, 5f) }
        val runs = LineShaper.dashRuns(sparse, LineStyle(dashOn = 0.5f, dashOff = 1.5f), 10f)
        assertEquals(13, runs.size)
        assertTrue(runs.all { it.size == 1 })
        near(48f, runs[2].single().x)
    }

    @Test
    fun aZeroTipIsDrawnAtTwoPercent() {
        val out = LineShaper.taper(line(100f, 1f), LineStyle(taperIn = 2f, taperTip = 0f), 10f)
        near(5f * LineShaper.MIN_TIP, out.first().radius, what = "never a radius of exactly 0")
    }

    @Test
    fun aPositionThatIsNotANumberLeavesTheLineAlone() {
        // NaN <= 0 is false, so a length guard written `total <= 0f` let a NaN through to every radius.
        val broken = line(50f, 1f).toMutableList().also { it[10] = it[10].copy(x = Float.NaN) }
        assertSame(broken, LineShaper.taper(broken, LineStyle(taperIn = 1f, taperOut = 1f), 10f))
        assertSame(broken, LineShaper.dashRuns(broken, LineStyle(dashOn = 1f, dashOff = 1f), 10f).single())
    }
}
