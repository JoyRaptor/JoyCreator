package cc.joycreator.joybrush.core.shape

import cc.joycreator.joybrush.core.input.PenSample
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** JB-2.10 spec tests 1–9, each over seeds 1…10 (a shape must be recognised every time, not usually). */
class ShapeRecognizerTest {

    private val seeds = 1..10

    private fun d(a: Pt, b: Pt) = hypot(a.x - b.x, a.y - b.y)
    private fun deg(r: Double) = r * 180 / PI

    @Test
    fun aStraightLineIsALineWithItsDrawnEnds() {
        for (seed in seeds) {
            val s = StrokeMaker.polyline(listOf(Pt(50.0, 80.0), Pt(350.0, 80.0)), seed)
            val shape = assertIs<Shape.Line>(ShapeRecognizer.recognize(s), "seed $seed")
            // The ends are the DRAWN ends (Decision 4), which the roughening moves up to 2 px (wobble)
            // + 2.1 px (jitter) from the ideal ones.
            assertEquals(Pt(s.first().x.toDouble(), s.first().y.toDouble()), shape.a, "seed $seed")
            assertEquals(Pt(s.last().x.toDouble(), s.last().y.toDouble()), shape.b, "seed $seed")
            assertTrue(d(shape.a, Pt(50.0, 80.0)) < 4.5, "seed $seed start ${shape.a}")
            assertTrue(d(shape.b, Pt(350.0, 80.0)) < 4.5, "seed $seed end ${shape.b}")
            // Drawing direction is kept.
            assertTrue(shape.a.x < shape.b.x)
        }
    }

    @Test
    fun aCircleWithOverlapIsACircle() {
        for (seed in seeds) {
            val s = StrokeMaker.ellipse(400.0, 300.0, 100.0, 100.0, 0.0, 1.1, seed, startDeg = seed * 33.0)
            val e = assertIs<Shape.Ellipse>(ShapeRecognizer.recognize(s), "seed $seed")
            assertEquals(e.rx, e.ry, "seed $seed: a circle")
            assertTrue(abs(e.rx - 100) < 5, "seed $seed radius ${e.rx}")
            assertTrue(d(e.center, Pt(400.0, 300.0)) < 5, "seed $seed centre ${e.center}")
        }
    }

    @Test
    fun aRotatedEllipseKeepsItsRotationAndRadii() {
        for (seed in seeds) {
            val s = StrokeMaker.ellipse(500.0, 400.0, 160.0, 80.0, 30.0, 1.05, seed, startDeg = seed * 27.0)
            val e = assertIs<Shape.Ellipse>(ShapeRecognizer.recognize(s), "seed $seed")
            val rotErr = abs(((deg(e.rotation) - 30) % 180 + 270) % 180 - 90)
            assertTrue(rotErr < 5, "seed $seed rotation ${deg(e.rotation)}")
            assertTrue(abs(e.rx - 160) < 8, "seed $seed rx ${e.rx}")
            assertTrue(abs(e.ry - 80) < 8, "seed $seed ry ${e.ry}")
        }
    }

    private fun rect(cx: Double, cy: Double, w: Double, h: Double, deg: Double): List<Pt> {
        val r = deg * PI / 180
        return listOf(-1.0 to -1.0, 1.0 to -1.0, 1.0 to 1.0, -1.0 to 1.0).map { (sx, sy) ->
            val u = sx * w / 2; val v = sy * h / 2
            Pt(cx + cos(r) * u - sin(r) * v, cy + sin(r) * u + cos(r) * v)
        }
    }

    private fun assertTrueRectangle(corners: List<Pt>, msg: String) {
        assertEquals(4, corners.size, msg)
        for (i in 0 until 4) {
            val a = corners[(i + 3) % 4]; val b = corners[i]; val c = corners[(i + 1) % 4]
            val ux = a.x - b.x; val uy = a.y - b.y; val vx = c.x - b.x; val vy = c.y - b.y
            val ang = deg(acos((ux * vx + uy * vy) / (hypot(ux, uy) * hypot(vx, vy))))
            assertTrue(abs(ang - 90) < 0.5, "$msg angle $ang")
        }
    }

    @Test
    fun aRotatedRectangleSnapsToATrueRectangle() {
        val truth = rect(300.0, 250.0, 200.0, 120.0, 15.0)
        for (seed in seeds) {
            val s = StrokeMaker.polyline(truth, seed, closed = true)
            val p = assertIs<Shape.Polygon>(ShapeRecognizer.recognize(s), "seed $seed")
            assertTrueRectangle(p.corners, "seed $seed")
            for (t in truth) assertTrue(p.corners.any { d(it, t) < 6 }, "seed $seed corner $t in ${p.corners}")
            // Starts at the corner nearest where the pen went down.
            assertTrue(d(p.corners[0], truth[0]) < 6, "seed $seed first corner")
        }
    }

    @Test
    fun aRectangleBegunMidSideStillHasFourCorners() {
        val t = rect(300.0, 250.0, 200.0, 120.0, 15.0)
        val mid = Pt((t[0].x + t[1].x) / 2, (t[0].y + t[1].y) / 2)
        for (seed in seeds) {
            val s = StrokeMaker.polyline(listOf(mid, t[1], t[2], t[3], t[0]), seed, closed = true)
            val p = assertIs<Shape.Polygon>(ShapeRecognizer.recognize(s), "seed $seed")
            assertTrueRectangle(p.corners, "seed $seed")
            for (c in t) assertTrue(p.corners.any { d(it, c) < 6 }, "seed $seed corner $c")
        }
    }

    @Test
    fun aTriangleIsAThreeCornerPolygon() {
        val truth = listOf(Pt(100.0, 100.0), Pt(300.0, 100.0), Pt(200.0, 270.0))
        for (seed in seeds) {
            val s = StrokeMaker.polyline(truth, seed, closed = true)
            val p = assertIs<Shape.Polygon>(ShapeRecognizer.recognize(s), "seed $seed")
            assertEquals(3, p.corners.size, "seed $seed")
            for (i in 0 until 3) assertTrue(d(p.corners[i], truth[i]) < 8, "seed $seed corner $i ${p.corners}")
        }
    }

    @Test
    fun anArcIsAnArcWithItsSweepAndDirection() {
        for (seed in seeds) {
            val s = StrokeMaker.arc(300.0, 300.0, 150.0, 200.0, 120.0, seed)
            val a = assertIs<Shape.Arc>(ShapeRecognizer.recognize(s), "seed $seed")
            assertTrue(abs(a.radius - 150) < 6, "seed $seed radius ${a.radius}")
            assertTrue(abs(deg(a.sweep) - 120) < 8, "seed $seed sweep ${deg(a.sweep)}")
            // Drawn the other way → negative sweep.
            val back = assertIs<Shape.Arc>(ShapeRecognizer.recognize(s.reversed()), "seed $seed reversed")
            assertTrue(abs(deg(back.sweep) + 120) < 8, "seed $seed reversed sweep ${deg(back.sweep)}")
        }
    }

    @Test
    fun aScribbleIsLeftAlone() {
        for (seed in seeds) assertNull(ShapeRecognizer.recognize(StrokeMaker.scribble(seed)), "seed $seed")
    }

    @Test
    fun aDotIsLeftAlone() {
        for (seed in seeds) {
            val dot = StrokeMaker.ellipse(50.0, 50.0, 1.0, 1.0, 0.0, 1.0, seed).take(4) +
                List(10) { PenSample(50f + (it % 3), 50f + (it % 2), it.toDouble()) }
            assertNull(ShapeRecognizer.recognize(dot), "seed $seed")
        }
        assertNull(ShapeRecognizer.recognize(emptyList()))
        assertNull(ShapeRecognizer.recognize(List(3) { PenSample(it * 50f, 0f, it.toDouble()) }))
    }

    @Test
    fun recognitionIsTheSameAtAnyZoom() {
        for (seed in seeds) {
            val s = StrokeMaker.ellipse(400.0, 300.0, 100.0, 100.0, 0.0, 1.1, seed)
            val small = s.map { it.copy(x = it.x * 0.25f, y = it.y * 0.25f) }
            val big = assertIs<Shape.Ellipse>(ShapeRecognizer.recognize(s))
            val zoomed = assertIs<Shape.Ellipse>(ShapeRecognizer.recognize(small, screenPerDoc = 4f), "seed $seed")
            assertTrue(abs(zoomed.rx * 4 - big.rx) < 0.01, "seed $seed")
            assertTrue(d(Pt(zoomed.center.x * 4, zoomed.center.y * 4), big.center) < 0.01)
        }
    }

    @Test
    fun aSlightlyBowedLineIsStillALine() {
        // A 300 px line with a 6 px bow; with the roughening it strays ~9.5 px, under the 4% (12 px) limit.
        val bumped = StrokeMaker.polyline(listOf(Pt(0.0, 0.0), Pt(150.0, 6.0), Pt(300.0, 0.0)), 3)
        assertIs<Shape.Line>(ShapeRecognizer.recognize(bumped))
    }

    @Test
    fun garbageInputIsRefusedNotThrown() {
        val bad = List(20) { PenSample(Float.NaN, it.toFloat(), it.toDouble()) }
        assertNull(ShapeRecognizer.recognize(bad))
        val s = StrokeMaker.ellipse(400.0, 300.0, 100.0, 100.0, 0.0, 1.1, 1)
        assertNull(ShapeRecognizer.recognize(s, screenPerDoc = 0f))
        assertNull(ShapeRecognizer.recognize(s, screenPerDoc = Float.NaN))
    }
}
