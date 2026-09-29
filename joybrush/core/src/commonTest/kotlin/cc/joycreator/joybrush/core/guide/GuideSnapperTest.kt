package cc.joycreator.joybrush.core.guide

import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.input.Tool
import cc.joycreator.joybrush.core.shape.Pt
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** JB-2.12a spec tests 1–8, plus the two promises of Decision 5 (channels, non-finite input). */
class GuideSnapperTest {

    /** A sample with every channel filled in, so "only x and y moved" is a real check. */
    private fun ps(x: Float, y: Float, t: Double, p: Float) =
        PenSample(x, y, t, p, 0.3f, 1.2f, 0.4f, Tool.STYLUS, false)

    private fun deg(r: Double) = r * 180.0 / PI

    /** Distance from (x, y) to the line through the origin in direction (ux, uy), in doc px. */
    private fun lineResidual(x: Float, y: Float, ux: Double, uy: Double): Double {
        val cr = x * uy - y * ux
        val l = hypot(ux, uy)
        return abs(cr) / l
    }

    /** Perp distance from (px, py) to the INFINITE line through (ax, ay) and (bx, by), in doc px. */
    private fun perpToLine(
        px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double,
    ): Double {
        val dx = bx - ax
        val dy = by - ay
        return abs((px - ax) * dy - (py - ay) * dx) / hypot(dx, dy)
    }

    // ── 1 ─────────────────────────────────────────────────────────────────────

    @Test
    fun aWobblingHorizontalStrokeOnAGridLocksToYZeroAndKeepsItsPressure() {
        val sn = GuideSnapper(listOf<Guide>(Guide.Grid(10.0)), 1f)
        val raw = listOf(0f to 0f, 4f to 2f, 8f to -2f, 12f to 3f, 16f to -3f, 20f to 2f, 24f to -1f, 28f to 3f)
        val ins = raw.mapIndexed { i, p -> ps(p.first, p.second, i * 8.0, 0.2f + 0.05f * i) }
        val out = ins.map { sn.map(it) }

        // 4.47, 8.25 screen px from the start: still inside the 12 px lock radius, so not rewritten.
        for (i in 0..2) assertEquals(ins[i], out[i], "sample $i must not be rewritten")
        // The third one out is 12.37 screen px away and 14.0° off +x: the x axis locks (20° window).
        for (i in 3..7) {
            assertEquals(0f, out[i].y, "sample $i must sit on y = 0 exactly")
            assertEquals(ins[i].x, out[i].x, "sample $i keeps its x")
        }
        assertTrue(sn.locked)
        assertEquals(ins.map { it.pressure }, out.map { it.pressure })
        // Every channel other than x and y came through untouched.
        for (i in ins.indices) {
            assertEquals(ins[i].copy(x = out[i].x, y = out[i].y), out[i], "sample $i channels")
        }
    }

    // ── 2 ─────────────────────────────────────────────────────────────────────

    @Test
    fun theLockIsTwelveScreenPxSoAtZoomFourItLandsOnThreeDocPx() {
        val guides = listOf<Guide>(Guide.Grid(10.0))
        val ins = listOf(0f to 0f, 1f to 0.2f, 2f to 0.4f, 3f to 0.6f, 4f to 0.8f, 5f to 1f)
            .mapIndexed { i, p -> ps(p.first, p.second, i * 8.0, 0.5f) }

        val sn4 = GuideSnapper(guides, 4f)
        val out = ins.map { sn4.map(it) }
        // 4.08 and 8.16 screen px: inside the radius. 12.24 screen px: locked, 11.3° off +x.
        for (i in 0..2) assertEquals(ins[i], out[i], "sample $i at 4:1 must not be rewritten")
        for (i in 3..5) {
            assertEquals(0f, out[i].y, "sample $i at 4:1 must sit on y = 0")
            assertEquals(ins[i].x, out[i].x, "sample $i at 4:1 keeps its x")
        }
        assertEquals(3f, out[3].x)
        assertTrue(sn4.locked)

        // The very same stroke at 1:1 has travelled 5.10 screen px by the end: nothing locks.
        val sn1 = GuideSnapper(guides, 1f)
        val out1 = ins.map { sn1.map(it) }
        for (i in ins.indices) assertEquals(ins[i], out1[i], "sample $i at 1:1")
        assertTrue(!sn1.locked)
    }

    // ── 3 ─────────────────────────────────────────────────────────────────────

    @Test
    fun aFortyFiveDegreeStrokeIsAlongNeitherAxisSoNothingLocks() {
        val sn = GuideSnapper(listOf<Guide>(Guide.Grid(10.0)), 1f)
        val ins = (0..12).map { i -> ps(i * 8f, i * 8f, i * 8.0, 0.5f) }
        val out = ins.map { sn.map(it) }
        // At 22.6 px the pen is 45.0° off the x axis and 45.0° off the y axis — both past 20°.
        for (i in ins.indices) assertEquals(ins[i], out[i], "sample $i")
        assertTrue(!sn.locked)
    }

    // ── 4 ─────────────────────────────────────────────────────────────────────

    @Test
    fun anIsometricGuideLocksAStrokeAtTwentyEightDegreesToThirty() {
        val sn = GuideSnapper(listOf<Guide>(Guide.Isometric(20.0)), 1f)
        val d = 28.0 * PI / 180.0
        val ux = cos(d)
        val uy = sin(d)
        val ins = (0..4).map { i -> ps((i * 10.0 * ux).toFloat(), (i * 10.0 * uy).toFloat(), i * 10.0, 0.4f) }
        val out = ins.map { sn.map(it) }

        val c30 = cos(PI / 6.0)
        val s30 = sin(PI / 6.0)
        // 10 doc px out: inside the 12 px radius, so this one keeps its 2° error.
        assertEquals(ins[1], out[1])
        assertTrue(lineResidual(out[1].x, out[1].y, c30, s30) > 0.3, "pre-lock sample must be untouched")
        // 20 doc px out at 28.0°: 2.0° from the 30° direction, 62° from the 90° one, 58° from the 150°.
        for (i in 2..4) {
            assertTrue(lineResidual(out[i].x, out[i].y, c30, s30) < 1e-4, "sample $i is off the 30° line")
            val a = deg(atan2(out[i].y.toDouble(), out[i].x.toDouble()))
            assertTrue(abs(a - 30.0) < 0.01, "sample $i runs at $a°")
        }
        assertTrue(sn.locked)
    }

    // ── 5 ─────────────────────────────────────────────────────────────────────

    @Test
    fun aStrokeHeadingForTheSecondVanishingPointLocksOntoThatRay() {
        val sn = GuideSnapper(
            listOf<Guide>(Guide.Perspective(listOf(Pt(-1000.0, 0.0), Pt(1000.0, 0.0)))), 1f,
        )
        val ux = 1000.0
        val uy = -200.0
        val l = hypot(ux, uy)
        // Start at (0, 200) and walk towards (1000, 0): -11.3° from +x.
        val ins = (0..4).map { i ->
            ps((i * 6.0 * ux / l).toFloat(), (200.0 + i * 6.0 * uy / l).toFloat(), i * 6.0, 0.6f)
        }
        val out = ins.map { sn.map(it) }
        assertEquals(ins[0], out[0])
        assertEquals(ins[1], out[1])
        for (i in 2..4) {
            val cr = abs(out[i].x * uy - (out[i].y - 200f) * ux) / l
            assertTrue(cr < 1e-3, "sample $i is $cr px off the ray to (1000, 0)")
            // The other way round as well: the vanishing point lies ON the locked line. This stroke
            // walks exactly along start -> vanishing point, which is why it cannot tell the two
            // readings apart — the test below that can is a stroke drawn OFF the ray.
            val vpOff = perpToLine(1000.0, 0.0, 0.0, 200.0, out[i].x.toDouble(), out[i].y.toDouble())
            assertTrue(vpOff < 1e-3, "sample $i's line passes $vpOff px from the vanishing point")
        }
        assertTrue(sn.locked)
    }

    // ── the perspective direction is measured at the START (review Finding 1) ──────

    @Test
    fun aPerspectiveStrokeRunsOnTheLineFromTheStartToTheVanishingPoint() {
        val vp1 = Pt(1000.0, 0.0)
        val vp2 = Pt(-1000.0, 0.0)
        val d = 18.0 * PI / 180.0
        // A stroke drawn 18° off the guide, walking 7 px a sample out to ~950 px: the case a
        // perspective guide exists for, since it is the one that has to be right over a long line.
        val ins = (0..136).map { i ->
            val t = i * 7.0
            ps((t * cos(d)).toFloat(), (t * sin(d)).toFloat(), t, 0.4f)
        }
        val sn = GuideSnapper(listOf<Guide>(Guide.Perspective(listOf(vp1, vp2))), 1f)
        val out = ins.map { sn.map(it) }

        // 7.0 px and 14.0 px from the start; the lock is decided by the second of those.
        val deciding = out.indices.first { out[it] != ins[it] }
        assertEquals(2, deciding)
        assertEquals(ins[0], out[0])
        assertEquals(ins[1], out[1])
        assertTrue(sn.locked)

        // First, prove the test can tell the two readings apart at all. Measured at the start, the
        // vanishing points sit at 0.0° and 180.0° — one line, y = 0. Measured at the deciding
        // sample, 14 px along and 4.33 px off that line, they sit at -0.251° and -179.755°: a line
        // 0.245° off, which misses (1000, 0) by 4.3 px. If these agreed the assertions below would
        // pass against the wrong code as well.
        val c = ins[deciding]
        val atStart = deg(atan2(vp1.y, vp1.x))
        val atDeciding = deg(atan2(vp1.y - c.y, vp1.x - c.x))
        assertTrue(abs(atStart - atDeciding) > 0.1, "the two readings agree here: $atStart vs $atDeciding")

        // The property, not a number from the implementation: the stroke runs on the line that joins
        // the start to each vanishing point, checked both ways round so neither half can be faked.
        // (Both vanishing points are on y = 0 with the start at the origin, so that line is y = 0.)
        // The worst value over the whole stroke is the one that is asserted — at the deciding sample
        // a wrong direction still lands close to the line, and it is 950 px down the stroke that it
        // is miles out.
        var worst = 0.0
        var worstAt = ""
        for (i in deciding..ins.lastIndex) {
            for (vp in listOf(vp1, vp2)) {
                val off = perpToLine(out[i].x.toDouble(), out[i].y.toDouble(), 0.0, 0.0, vp.x, vp.y)
                if (off > worst) {
                    worst = off
                    worstAt = "sample $i sits $off px off the line from the start to $vp"
                }
                val vpOff = perpToLine(vp.x, vp.y, 0.0, 0.0, out[i].x.toDouble(), out[i].y.toDouble())
                if (vpOff > worst) {
                    worst = vpOff
                    worstAt = "sample $i's line passes $vpOff px from $vp"
                }
            }
        }
        assertTrue(worst < 0.05, worstAt)
        for (i in deciding..ins.lastIndex) {
            assertEquals(0f, out[i].y, "sample $i must sit on the line through the start and the point")
        }
    }

    @Test
    fun aVanishingPointSittingOnTheStartOffersNoDirectionAndLeavesTheStrokeFree() {
        val guides = listOf<Guide>(Guide.Perspective(listOf(Pt(0.0, 0.0), Pt(0.0, 1000.0))))
        val d = 18.0 * PI / 180.0
        val ins = (0..4).map { i ->
            val t = i * 7.0
            ps((t * cos(d)).toFloat(), (t * sin(d)).toFloat(), t, 0.4f)
        }
        val sn = GuideSnapper(guides, 1f)
        val out = ins.map { sn.map(it) }

        // The stroke starts ON a vanishing point, so "the line from here to there" has no direction
        // to give: it is the point itself, and every direction out of it reaches it. Offering one
        // would pin the stroke to whatever the pen happened to be doing, so it offers none and the
        // only direction left is the vertical — 72° from this stroke, past the 20° window.
        for (i in ins.indices) assertEquals(ins[i], out[i], "sample $i")
        assertTrue(!sn.locked)
    }

    @Test
    fun aVanishingPointOnTheStartDoesNotStopTheOtherDirectionsFromWorking() {
        val guides = listOf<Guide>(Guide.Perspective(listOf(Pt(0.0, 0.0), Pt(0.0, 1000.0))))
        val d = 85.0 * PI / 180.0
        val ins = (0..4).map { i ->
            val t = i * 10.0
            ps((300.0 + t * cos(d)).toFloat(), (t * sin(d)).toFloat(), t, 0.4f)
        }
        val sn = GuideSnapper(guides, 1f)
        val out = ins.map { sn.map(it) }
        // 5° from the vertical, which is still on offer: the guide has not been broken by the
        // vanishing point sitting on the start, it has lost one direction and kept the rest.
        for (i in 2..4) assertEquals(300f, out[i].x, "sample $i must sit on the vertical through the start")
        assertTrue(sn.locked)
    }

    // ── a tracer with nothing to snap to is refused, not taken (Findings 2 and 3) ──

    @Test
    fun anEllipseTracerMissingACentreOrARotationIsRefusedRatherThanTaken() {
        val unusable = listOf(
            Guide.EllipseTracer(Pt(Double.NaN, 300.0), 120.0, 60.0, 0.3),
            Guide.EllipseTracer(Pt(200.0, 300.0), 120.0, 60.0, Double.NaN),
        )
        for (g in unusable) {
            val sn = GuideSnapper(listOf<Guide>(g), 1f)
            // Starting right on where the curve would be: nothing is out of reach here, the tracer
            // simply has no curve to reach for.
            val ins = (0..5).map { i -> ps(i * 7f, 300f + i * 2f, i * 7.0, 0.4f) }
            val out = ins.map { sn.map(it) }
            for (i in ins.indices) assertEquals(ins[i], out[i], "$g sample $i")
            assertTrue(!sn.locked, "$g must not light up a locked highlight it cannot honour")
        }

        // The same ellipse with all its numbers: still snaps, so the refusal above is a refusal and
        // not a broken branch.
        val good = Guide.EllipseTracer(Pt(200.0, 300.0), 120.0, 60.0, 0.3)
        val sn = GuideSnapper(listOf<Guide>(good), 1f)
        val ins = (0..10).map { i ->
            val t = i * 0.12
            ps(
                (good.center.x + cos(good.rotation) * good.rx * cos(t)).toFloat(),
                (good.center.y + sin(good.rotation) * good.rx * cos(t)).toFloat(),
                i * 8.0, 0.4f,
            )
        }
        val out = ins.map { sn.map(it) }
        assertTrue(sn.locked)
        for (i in ins.indices) {
            val d = distanceToEllipse(good, out[i].x.toDouble(), out[i].y.toDouble())
            assertTrue(d < 0.05, "sample $i is $d px off the ellipse")
        }
    }

    @Test
    fun aRulerWhoseTwoEndsAreTheSamePointIsRefusedRatherThanTaken() {
        val sn = GuideSnapper(listOf<Guide>(Guide.Ruler(Pt(50.0, 50.0), Pt(50.0, 50.0))), 1f)
        val ins = (0..5).map { i -> ps(50f + i * 7, 55f + i * 2, i * 7.0, 0.4f) }
        val out = ins.map { sn.map(it) }
        // A dot has no direction to project onto, so the overlay draws nothing for it and the
        // snapper must not claim the stroke: an untouched stroke under a "locked" highlight.
        for (i in ins.indices) assertEquals(ins[i], out[i], "sample $i")
        assertTrue(!sn.locked)
    }

    @Test
    fun aDotOfARulerDoesNotStopTheRealRulerBesideItFromTakingTheStroke() {
        val guides = listOf<Guide>(
            Guide.Ruler(Pt(0.0, 12.0), Pt(0.0, 12.0)),
            Guide.Ruler(Pt(0.0, 0.0), Pt(100.0, 0.0)),
        )
        val sn = GuideSnapper(guides, 1f)
        val ins = (0..5).map { i -> ps(i * 7f, 10f + i * 2f, i * 7.0, 0.4f) }
        val out = ins.map { sn.map(it) }
        // The dot is 2 px from the start against the real ruler's 10, so if a dot were merely ignored
        // rather than refused it would win on "nearest tracer" and pin the stroke to itself.
        assertTrue(sn.locked)
        for (i in ins.indices) assertEquals(0f, out[i].y, "sample $i must lie on the real ruler")
    }

    @Test
    fun withTwoVanishingPointsTheVerticalIsADirectionToo() {
        val sn = GuideSnapper(
            listOf<Guide>(Guide.Perspective(listOf(Pt(-1000.0, 0.0), Pt(1000.0, 0.0)))), 1f,
        )
        val d = 85.0 * PI / 180.0
        val ux = cos(d)
        val uy = sin(d)
        val ins = (0..4).map { i ->
            ps((300.0 + i * 10.0 * ux).toFloat(), (200.0 + i * 10.0 * uy).toFloat(), i * 10.0, 0.6f)
        }
        val out = ins.map { sn.map(it) }
        assertEquals(ins[0], out[0])
        assertEquals(ins[1], out[1])
        // 5.0° from the vertical, 76.2° from the ray to (-1000, 0), 79.1° from the ray to (1000, 0):
        // the vertical wins, and projecting onto it changes only x.
        for (i in 2..4) {
            assertEquals(300f, out[i].x, "sample $i must sit on the vertical through the start")
            assertEquals(ins[i].y, out[i].y, "sample $i keeps its y")
        }
        assertTrue(sn.locked)
    }

    // ── 6 ─────────────────────────────────────────────────────────────────────

    @Test
    fun aRulerTenScreenPxAwayTakesTheStrokeAndOneAtFortyDoesNot() {
        val guides = listOf<Guide>(Guide.Ruler(Pt(0.0, 0.0), Pt(100.0, 0.0)))

        val near = GuideSnapper(guides, 1f)
        val ins = (0..5).map { i -> ps(i * 7f, 10f + i * 2f, i * 7.0, 0.3f + 0.1f * i) }
        val out = ins.map { near.map(it) }
        for (i in ins.indices) {
            assertEquals(0f, out[i].y, "sample $i must lie on the ruler")
            assertEquals(ins[i].x, out[i].x, "sample $i")
        }
        assertTrue(near.locked)

        val far = GuideSnapper(guides, 1f)
        val ins2 = (0..5).map { i -> ps(i * 7f, 40f + i * 2f, i * 7.0, 0.3f + 0.1f * i) }
        val out2 = ins2.map { far.map(it) }
        for (i in ins2.indices) assertEquals(ins2[i], out2[i], "sample $i is out of reach")
        assertTrue(!far.locked)
    }

    // ── 7 ─────────────────────────────────────────────────────────────────────

    @Test
    fun anEllipseTracerPutsEverySampleOnTheCurve() {
        val e = Guide.EllipseTracer(Pt(200.0, 300.0), 120.0, 60.0, 0.3)
        val sn = GuideSnapper(listOf<Guide>(e), 1f)
        val cr = cos(e.rotation)
        val sr = sin(e.rotation)
        // A hand drawn 5% outside the curve: 5.4 px from it at the start, so the tracer is in reach.
        val ins = (0..24).map { i ->
            val t = 0.5 + i * 0.05
            val lx = 1.05 * e.rx * cos(t)
            val ly = 1.05 * e.ry * sin(t)
            ps(
                (e.center.x + cr * lx - sr * ly).toFloat(),
                (e.center.y + sr * lx + cr * ly).toFloat(),
                i * 8.0, 0.2f + 0.02f * i,
            )
        }
        val out = ins.map { sn.map(it) }
        assertTrue(sn.locked)
        for (i in ins.indices) {
            val d = distanceToEllipse(e, out[i].x.toDouble(), out[i].y.toDouble())
            assertTrue(d < 0.05, "sample $i is $d px off the ellipse")
        }
        for (i in ins.indices) {
            assertEquals(ins[i].copy(x = out[i].x, y = out[i].y), out[i], "sample $i channels")
        }
    }

    /** Distance from (x, y) to the curve, by a dense scan of the parameter — deliberately not the
     *  method under test, so the two agree by accident only if both are right. */
    private fun distanceToEllipse(e: Guide.EllipseTracer, x: Double, y: Double): Double {
        val cr = cos(e.rotation)
        val sr = sin(e.rotation)
        var best = Double.MAX_VALUE
        for (i in 0..20000) {
            val t = i * (2 * PI / 20000)
            val lx = e.rx * cos(t)
            val ly = e.ry * sin(t)
            val px = e.center.x + cr * lx - sr * ly
            val py = e.center.y + sr * lx + cr * ly
            val d = hypot(px - x, py - y)
            if (d < best) best = d
        }
        return best
    }

    // ── 8 ─────────────────────────────────────────────────────────────────────

    @Test
    fun aTracerInReachBeatsADirectionGuide() {
        val guides = listOf<Guide>(Guide.Grid(50.0), Guide.Ruler(Pt(0.0, 0.0), Pt(100.0, 0.0)))
        val sn = GuideSnapper(guides, 1f)
        // Climbing away from a 50 px grid: without the ruler this would lock to the grid's vertical.
        val ins = (0..5).map { i -> ps(0.5f * i, 10f + 10f * i, i * 10.0, 0.5f) }
        val out = ins.map { sn.map(it) }
        assertTrue(sn.locked)
        for (i in ins.indices) {
            assertEquals(0f, out[i].y, "sample $i: the ruler won, so y is 0")
            assertEquals(ins[i].x, out[i].x, "sample $i: the grid's vertical did not win")
        }
    }

    // ── Decision 5: the promise itself ─────────────────────────────────────────

    @Test
    fun noGuidesAndANonFiniteSampleBothComeStraightBack() {
        val sn = GuideSnapper(emptyList(), 1f)
        val ins = (0..5).map { i -> ps(i * 20f, i * 3f, i * 8.0, 0.5f) }
        for (i in ins.indices) assertEquals(ins[i], sn.map(ins[i]), "sample $i")
        assertTrue(!sn.locked)

        val bad = PenSample(Float.NaN, 5f, 3.0, 0.4f)
        assertEquals(bad, sn.map(bad))
        val bad2 = PenSample(5f, Float.NaN, 3.0, 0.4f)
        assertEquals(bad2, sn.map(bad2))

        // And the same on a locked stroke: a bad sample does not move the start or break the lock.
        val grid = GuideSnapper(listOf<Guide>(Guide.Grid(10.0)), 1f)
        assertEquals(bad, grid.map(bad))
        val straight = listOf(ps(0f, 0f, 0.0, 0.5f), ps(4f, 2f, 8.0, 0.5f), ps(12f, 3f, 16.0, 0.5f))
        val mapped = straight.map { grid.map(it) }
        assertEquals(0f, mapped[2].y)
        assertTrue(grid.locked)
    }
}
