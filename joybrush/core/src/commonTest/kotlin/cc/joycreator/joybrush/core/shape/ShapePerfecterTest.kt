package cc.joycreator.joybrush.core.shape

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** JB-2.10 spec tests 10–11, plus the other three shapes. The feel of the stroke must survive. */
class ShapePerfecterTest {

    @Test
    fun perfectingACircleKeepsEveryFeelChannel() {
        for (seed in 1..10) {
            val s = StrokeMaker.ellipse(400.0, 300.0, 100.0, 100.0, 0.0, 1.1, seed, startDeg = seed * 33.0)
            val e = assertIs<Shape.Ellipse>(ShapeRecognizer.recognize(s))
            val out = ShapePerfecter.perfect(s, e)
            assertEquals(s.size, out.size)
            for (i in s.indices) {
                assertEquals(s[i].pressure, out[i].pressure)
                assertEquals(s[i].tilt, out[i].tilt)
                assertEquals(s[i].azimuth, out[i].azimuth)
                assertEquals(s[i].timeMs, out[i].timeMs)
                assertEquals(s[i].tool, out[i].tool)
                val r = hypot(out[i].x - e.center.x, out[i].y - e.center.y)
                assertTrue(abs(r - e.rx) < 0.5, "seed $seed sample $i off the circle by ${r - e.rx}")
            }
            // Starts where the pen went down and goes round the way it was drawn.
            val a0 = kotlin.math.atan2(out[0].y - e.center.y, out[0].x - e.center.x)
            val s0 = kotlin.math.atan2(s[0].y - e.center.y, s[0].x - e.center.x)
            assertTrue(abs(Geometry.wrap(a0 - s0)) < 0.05, "seed $seed start angle")
            val a1 = kotlin.math.atan2(out[20].y - e.center.y, out[20].x - e.center.x)
            assertTrue(Geometry.wrap(a1 - a0) > 0, "seed $seed direction")
        }
    }

    @Test
    fun perfectingALineKeepsItsDirectionAndSpacing() {
        val s = StrokeMaker.polyline(listOf(Pt(300.0, 40.0), Pt(20.0, 40.0)), 4)
        val line = assertIs<Shape.Line>(ShapeRecognizer.recognize(s))
        val out = ShapePerfecter.perfect(s, line)
        assertEquals(s.size, out.size)
        assertEquals(line.a.x.toFloat(), out.first().x)
        assertEquals(line.b.x.toFloat(), out.last().x, 1e-3f)
        for (i in 1 until out.size) assertTrue(out[i].x <= out[i - 1].x, "monotone right-to-left at $i")
        // Every output point lies on the chord.
        for (p in out) assertTrue(Geometry.segmentDistance(Pt(p.x.toDouble(), p.y.toDouble()), line.a, line.b) < 1e-3)
    }

    @Test
    fun perfectingARectangleWalksItsCornersInOrder() {
        val r = 15 * PI / 180
        val truth = listOf(-1.0 to -1.0, 1.0 to -1.0, 1.0 to 1.0, -1.0 to 1.0).map { (sx, sy) ->
            val u = sx * 100; val v = sy * 60
            Pt(300 + cos(r) * u - sin(r) * v, 250 + sin(r) * u + cos(r) * v)
        }
        val s = StrokeMaker.polyline(truth, 2, closed = true)
        val p = assertIs<Shape.Polygon>(ShapeRecognizer.recognize(s))
        val out = ShapePerfecter.perfect(s, p)
        assertEquals(s.size, out.size)
        assertEquals(p.corners[0].x.toFloat(), out[0].x, 1e-3f)
        for (q in out) {
            val pt = Pt(q.x.toDouble(), q.y.toDouble())
            val onEdge = (0 until 4).minOf { Geometry.segmentDistance(pt, p.corners[it], p.corners[(it + 1) % 4]) }
            assertTrue(onEdge < 1e-3, "on an edge")
        }
        // The sample halfway along the stroke is halfway round the perimeter: at the opposite corner.
        val half = s.indices.minBy { abs(it - s.size / 2) }
        val q = Pt(out[half].x.toDouble(), out[half].y.toDouble())
        assertTrue(Geometry.dist(q, p.corners[2]) < 8, "halfway is the far corner, got $q")
    }

    @Test
    fun perfectingAnArcStaysOnTheArc() {
        val s = StrokeMaker.arc(300.0, 300.0, 150.0, 200.0, 120.0, 5)
        val a = assertIs<Shape.Arc>(ShapeRecognizer.recognize(s))
        val out = ShapePerfecter.perfect(s, a)
        for (p in out) assertTrue(abs(hypot(p.x - a.center.x, p.y - a.center.y) - a.radius) < 0.01)
        assertEquals(s.map { it.pressure }, out.map { it.pressure })
    }

    @Test
    fun aZeroLengthStrokeDoesNotBreak() {
        val s = List(6) { cc.joycreator.joybrush.core.input.PenSample(5f, 5f, it.toDouble()) }
        val out = ShapePerfecter.perfect(s, Shape.Line(Pt(0.0, 0.0), Pt(10.0, 0.0)))
        assertEquals(6, out.size)
        assertTrue(out.all { it.x == 0f && it.y == 0f })
        assertEquals(emptyList(), ShapePerfecter.perfect(emptyList(), Shape.Line(Pt(0.0, 0.0), Pt(1.0, 0.0))))
    }
}
