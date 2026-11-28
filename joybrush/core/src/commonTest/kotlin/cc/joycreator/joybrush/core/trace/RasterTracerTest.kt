package cc.joycreator.joybrush.core.trace

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun distToSegment(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
    val dx = bx - ax
    val dy = by - ay
    val len2 = dx * dx + dy * dy
    val t = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
    return hypot(px - (ax + t * dx), py - (ay + t * dy))
}

private fun distToPolyline(px: Double, py: Double, pts: List<Pair<Double, Double>>): Double {
    if (pts.size == 1) return hypot(px - pts[0].first, py - pts[0].second)
    var best = Double.MAX_VALUE
    for (i in 1 until pts.size) best = min(best, distToSegment(px, py, pts[i - 1].first, pts[i - 1].second, pts[i].first, pts[i].second))
    return best
}

/**
 * Concepts parity 4c: pictures drawn here with known geometry (anti-aliased, like a brush leaves them), traced, and held
 * to how many lines come out and how far each may stray from what was drawn.
 */
class RasterTracerTest {

    // ── drawing ──────────────────────────────────────────────────────────────────────────────

    private class Canvas(val w: Int, val h: Int) {
        val argb = IntArray(w * h)

        /** Paint coverage [c] (0..1) of [color] over what is there (source-over, straight colour). */
        fun cover(x: Int, y: Int, c: Double, color: Int) {
            if (x !in 0 until w || y !in 0 until h || c <= 0.0) return
            val a = (min(1.0, c) * 255).toInt()
            val old = argb[y * w + x]
            val oa = (old ushr 24) and 0xFF
            if (a >= oa) argb[y * w + x] = (a shl 24) or (color and 0xFFFFFF)
        }

        /** Every pixel within [r] (+ anti-aliased edge) of the polyline. */
        fun line(pts: List<Pair<Double, Double>>, r: Double, color: Int = 0x000000) {
            for (y in 0 until h) for (x in 0 until w) {
                val d = distToPolyline(x + 0.5, y + 0.5, pts)
                cover(x, y, r + 0.5 - d, color)
            }
        }

        fun disc(cx: Double, cy: Double, r: Double, color: Int = 0x000000) {
            for (y in 0 until h) for (x in 0 until w) cover(x, y, r + 0.5 - hypot(x + 0.5 - cx, y + 0.5 - cy), color)
        }

        /** A filled square with a square hole: the edge of each is a line of its own. */
        fun frame(x0: Int, y0: Int, size: Int, hole: Int, color: Int = 0x000000) {
            val h0 = x0 + (size - hole) / 2
            val v0 = y0 + (size - hole) / 2
            for (y in y0 until y0 + size) for (x in x0 until x0 + size) {
                val inHole = x in h0 until h0 + hole && y in v0 until v0 + hole
                if (!inHole) cover(x, y, 1.0, color)
            }
        }

        fun image() = TraceImage(w, h, argb)
    }

    private fun TracedLine.points(): List<Pair<Double, Double>> =
        (0 until pointCount).map { xs[it].toDouble() to ys[it].toDouble() } + (if (closed && pointCount > 0) listOf(xs[0].toDouble() to ys[0].toDouble()) else emptyList())

    /** The farthest any traced point lies from what was drawn. */
    private fun stray(line: TracedLine, truth: (Double, Double) -> Double): Double =
        (0 until line.pointCount).maxOf { truth(line.xs[it].toDouble(), line.ys[it].toDouble()) }

    /** The farthest any drawn point lies from the traced lines: a line that only covers half the mark fails this. */
    private fun missed(lines: List<TracedLine>, drawn: List<Pair<Double, Double>>): Double =
        drawn.maxOf { (x, y) -> lines.minOf { distToPolyline(x, y, it.points()) } }

    private fun along(pts: List<Pair<Double, Double>>, step: Double = 1.0): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>()
        for (i in 1 until pts.size) {
            val (ax, ay) = pts[i - 1]
            val (bx, by) = pts[i]
            val n = max(1, (hypot(bx - ax, by - ay) / step).toInt())
            for (k in 0 until n) out += (ax + (bx - ax) * k / n) to (ay + (by - ay) * k / n)
        }
        out += pts.last()
        return out
    }

    private fun circle(cx: Double, cy: Double, r: Double, n: Int = 180) = (0..n).map { cx + r * cos(2 * PI * it / n) to cy + r * sin(2 * PI * it / n) }

    // ── thin marks: traced along their middle ─────────────────────────────────────────────────

    @Test
    fun aStraightMarkIsOneLineAlongItsMiddle() {
        val c = Canvas(140, 60)
        val drawn = listOf(20.0 to 30.0, 120.0 to 30.0)
        c.line(drawn, 1.5)
        val lines = RasterTracer.trace(c.image())
        assertEquals(1, lines.size, "one mark, one line")
        val l = lines[0]
        assertEquals(TracedLine.Kind.CENTRE, l.kind)
        assertTrue(!l.closed, "a straight mark is an open line")
        assertTrue(stray(l) { x, y -> distToPolyline(x, y, drawn) } <= 1.0, "it keeps to the middle of the mark")
        assertTrue(missed(lines, along(drawn)) <= 2.5, "and runs its whole length, end to end")
        val hw = l.halfWidths.sorted()[l.pointCount / 2]
        assertTrue(abs(hw - 1.5) <= 0.8, "its width is the mark's, was half-width $hw")
        assertTrue(l.pointCount in 2..8, "a straight line needs few points, had ${l.pointCount}")
    }

    @Test
    fun aCurveKeepsItsShape() {
        val c = Canvas(220, 120)
        val drawn = (0..200).map { val x = 10.0 + it; x to 60.0 + 35.0 * sin(it / 200.0 * 2 * PI) }
        c.line(drawn, 2.0)
        val lines = RasterTracer.trace(c.image())
        assertEquals(1, lines.size)
        assertTrue(stray(lines[0]) { x, y -> distToPolyline(x, y, drawn) } <= 1.2, "the traced curve stays on the drawn one")
        assertTrue(missed(lines, drawn) <= 3.0, "and follows all of it")
    }

    @Test
    fun twoCrossingMarksAreTwoLines() {
        val c = Canvas(120, 120)
        val a = listOf(10.0 to 10.0, 110.0 to 110.0)
        val b = listOf(10.0 to 110.0, 110.0 to 10.0)
        c.line(a, 1.5)
        c.line(b, 1.5)
        val lines = RasterTracer.trace(c.image())
        assertEquals(2, lines.size, "an X is two lines that cross, not four that meet, was ${lines.size}")
        for (l in lines) assertTrue(stray(l) { x, y -> min(distToPolyline(x, y, a), distToPolyline(x, y, b)) } <= 1.5)
        assertTrue(missed(lines, along(a) + along(b)) <= 3.0, "both marks are covered end to end")
    }

    @Test
    fun aTIsTwoLines() {
        val c = Canvas(120, 120)
        val bar = listOf(10.0 to 20.0, 110.0 to 20.0)
        val stem = listOf(60.0 to 20.0, 60.0 to 110.0)
        c.line(bar, 1.5)
        c.line(stem, 1.5)
        val lines = RasterTracer.trace(c.image())
        assertEquals(2, lines.size, "the bar runs through; the stem meets it, was ${lines.size}")
        assertTrue(missed(lines, along(bar) + along(stem)) <= 3.0)
    }

    @Test
    fun aRingIsOneClosedLine() {
        val c = Canvas(120, 120)
        val ring = circle(60.0, 60.0, 40.0)
        c.line(ring, 1.5)
        val lines = RasterTracer.trace(c.image())
        assertEquals(1, lines.size)
        assertTrue(lines[0].closed, "a ring closes on itself")
        assertTrue(stray(lines[0]) { x, y -> abs(hypot(x - 60.0, y - 60.0) - 40.0) } <= 1.0, "on the ring's radius")
        assertTrue(missed(lines, ring) <= 2.0)
    }

    @Test
    fun separateMarksKeepTheirOwnColour() {
        val c = Canvas(120, 80)
        c.line(listOf(10.0 to 20.0, 110.0 to 20.0), 1.5, 0xE0201A)
        c.line(listOf(10.0 to 60.0, 110.0 to 60.0), 1.5, 0x1A40E0)
        val lines = RasterTracer.trace(c.image()).sortedBy { it.ys[0] }
        assertEquals(2, lines.size)
        assertEquals(0xFFE0201A.toInt(), lines[0].colorArgb, "the top mark is red")
        assertEquals(0xFF1A40E0.toInt(), lines[1].colorArgb, "the bottom one blue")
    }

    @Test
    fun dustIsLeftOut() {
        val c = Canvas(60, 60)
        c.cover(10, 10, 1.0, 0)
        c.cover(11, 10, 1.0, 0)
        c.cover(40, 40, 0.3, 0)        // faint, under the threshold
        assertEquals(0, RasterTracer.trace(c.image()).size)
    }

    @Test
    fun anEmptyPictureGivesNoLines() {
        assertEquals(0, RasterTracer.trace(TraceImage(0, 0, IntArray(0))).size)
        assertEquals(0, RasterTracer.trace(TraceImage(30, 30, IntArray(900))).size)
    }

    // ── filled shapes: traced along their edge ────────────────────────────────────────────────

    @Test
    fun aFilledDiscIsItsEdge() {
        val c = Canvas(120, 120)
        c.disc(60.0, 60.0, 30.0)
        val lines = RasterTracer.trace(c.image())
        assertEquals(1, lines.size)
        val l = lines[0]
        assertEquals(TracedLine.Kind.OUTLINE, l.kind)
        assertTrue(l.closed)
        assertTrue(stray(l) { x, y -> abs(hypot(x - 60.0, y - 60.0) - 30.0) } <= 1.0, "on the disc's edge")
        assertTrue(missed(lines, circle(60.0, 60.0, 30.0)) <= 2.0, "all the way round")
    }

    @Test
    fun aFilledShapeWithAHoleIsTwoEdges() {
        val c = Canvas(100, 100)
        c.frame(10, 10, 80, 40)
        val lines = RasterTracer.trace(c.image())
        assertEquals(2, lines.size, "the outside edge and the hole's edge, was ${lines.size}")
        assertTrue(lines.all { it.kind == TracedLine.Kind.OUTLINE && it.closed })
        val outer = listOf(10.0 to 10.0, 90.0 to 10.0, 90.0 to 90.0, 10.0 to 90.0, 10.0 to 10.0)
        val hole = listOf(30.0 to 30.0, 70.0 to 30.0, 70.0 to 70.0, 30.0 to 70.0, 30.0 to 30.0)
        for (l in lines) assertTrue(stray(l) { x, y -> min(distToPolyline(x, y, outer), distToPolyline(x, y, hole)) } <= 1.0)
        assertTrue(missed(lines, along(outer) + along(hole)) <= 2.0)
        val corners = lines.map { it.pointCount }.sorted()
        assertTrue(corners.all { it in 4..12 }, "a square edge is a few corners, not a staircase, had $corners")
    }

    @Test
    fun aMarkAndAFillTogether() {
        val c = Canvas(200, 100)
        c.line(listOf(10.0 to 50.0, 90.0 to 50.0), 1.5)
        c.disc(150.0, 50.0, 25.0)
        val lines = RasterTracer.trace(c.image())
        assertEquals(listOf(TracedLine.Kind.CENTRE, TracedLine.Kind.OUTLINE), lines.sortedBy { it.xs.min() }.map { it.kind })
    }

    @Test
    fun theSamePictureTracesTheSameWay() {
        val c = Canvas(120, 120)
        c.line(circle(60.0, 60.0, 30.0), 2.0)
        c.line(listOf(5.0 to 5.0, 115.0 to 100.0), 1.5)
        val a = RasterTracer.trace(c.image())
        val b = RasterTracer.trace(c.image())
        assertEquals(a.size, b.size)
        for (i in a.indices) assertTrue(a[i].xs.contentEquals(b[i].xs) && a[i].ys.contentEquals(b[i].ys))
    }
}
