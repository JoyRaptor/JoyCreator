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
        c.disc(60.0, 60.0, 30.0)                // a blob, no longer than it is wide: a shape, not a mark
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
        // walls 55 px thick: a filled shape (a thinner frame is one thick line, traced along its middle)
        val c = Canvas(170, 170)
        c.frame(10, 10, 150, 40)
        val lines = RasterTracer.trace(c.image())
        assertEquals(2, lines.size, "the outside edge and the hole's edge, was ${lines.size}")
        assertTrue(lines.all { it.kind == TracedLine.Kind.OUTLINE && it.closed })
        val outer = listOf(10.0 to 10.0, 160.0 to 10.0, 160.0 to 160.0, 10.0 to 160.0, 10.0 to 10.0)
        val hole = listOf(65.0 to 65.0, 105.0 to 65.0, 105.0 to 105.0, 65.0 to 105.0, 65.0 to 65.0)
        for (l in lines) assertTrue(stray(l) { x, y -> min(distToPolyline(x, y, outer), distToPolyline(x, y, hole)) } <= 1.0)
        assertTrue(missed(lines, along(outer) + along(hole)) <= 2.0)
        val corners = lines.map { it.pointCount }.sorted()
        assertTrue(corners.all { it in 4..12 }, "a square edge is a few corners, not a staircase, had $corners")
    }

    @Test
    fun aRingAsThickAsALineIsOneLine() {
        val c = Canvas(110, 110)
        c.frame(10, 10, 90, 70)          // walls 10 px: a thick square stroke
        val lines = RasterTracer.trace(c.image())
        assertEquals(1, lines.size)
        assertTrue(lines[0].kind == TracedLine.Kind.CENTRE && lines[0].closed, "traced along its middle, all the way round")
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

    // ── harder marks ─────────────────────────────────────────────────────────────────────────

    @Test
    fun aThickXIsStillTwoLines() {
        val c = Canvas(160, 160)
        val a = listOf(20.0 to 20.0, 140.0 to 140.0)
        val b = listOf(20.0 to 140.0, 140.0 to 20.0)
        c.line(a, 4.0)
        c.line(b, 4.0)
        val lines = RasterTracer.trace(c.image())
        assertEquals(2, lines.size, "was ${lines.size}")
        for (l in lines) assertTrue(stray(l) { x, y -> min(distToPolyline(x, y, a), distToPolyline(x, y, b)) } <= 2.0)
        assertTrue(missed(lines, along(a) + along(b)) <= 5.0)
        val hw = lines.flatMap { it.halfWidths.toList() }.sorted()
        assertTrue(abs(hw[hw.size / 2] - 4.0) <= 1.0, "a thick mark keeps its width, was ${hw[hw.size / 2]}")
    }

    @Test
    fun aVDrawnInOneGoIsOneLine() {
        val c = Canvas(140, 120)
        val v = listOf(15.0 to 15.0, 70.0 to 105.0, 125.0 to 15.0)
        c.line(v, 2.0)
        val lines = RasterTracer.trace(c.image())
        assertEquals(1, lines.size, "a corner is not a crossing, was ${lines.size}")
        assertTrue(stray(lines[0]) { x, y -> distToPolyline(x, y, v) } <= 1.5)
        assertTrue(missed(lines, along(v)) <= 3.5, "the corner is kept")
    }

    @Test
    fun aTaperedMarkGetsThinnerAlongItsLine() {
        val c = Canvas(200, 60)
        for (i in 0..160) {
            val x = 20.0 + i
            c.disc(x, 30.0, 5.0 - 4.0 * i / 160.0)
        }
        val lines = RasterTracer.trace(c.image())
        assertEquals(1, lines.size)
        val l = lines[0]
        val byX = (0 until l.pointCount).sortedBy { l.xs[it] }
        val thick = l.halfWidths[byX[byX.size / 6]]
        val thin = l.halfWidths[byX[byX.size * 5 / 6]]
        assertTrue(thick > thin + 1.0, "the wide end stays wider ($thick) than the narrow end ($thin)")
    }

    @Test
    fun aDotIsALine() {
        val c = Canvas(40, 40)
        c.disc(20.0, 20.0, 2.5)
        val lines = RasterTracer.trace(c.image())
        assertEquals(1, lines.size)
        assertTrue(stray(lines[0]) { x, y -> hypot(x - 20.0, y - 20.0) } <= 1.5, "on the dot")
    }

    @Test
    fun aLoopThatCrossesItselfIsOneLine() {
        // a looped stroke like a cursive "l": it runs up, loops over and crosses its own path once
        val c = Canvas(160, 160)
        val path = (0..240).map { val t = it / 240.0 * 2 * PI * 1.0; (80.0 + 50.0 * sin(t)) to (80.0 + 40.0 * sin(2 * t)) }
        c.line(path, 1.8)
        val lines = RasterTracer.trace(c.image())
        assertEquals(1, lines.size, "a figure eight drawn in one go traces as one line through its crossing, was ${lines.size}")
        assertTrue(stray(lines[0]) { x, y -> distToPolyline(x, y, path) } <= 1.5)
        assertTrue(missed(lines, path) <= 3.0)
    }

    @Test
    fun grainyInkIsReadAsTheMarksItMakes() {
        // a 6 px pencil-like mark: a third of its pixels missing at random, specks of grain beside it
        val c = Canvas(200, 80)
        val path = listOf(15.0 to 40.0, 185.0 to 40.0)
        c.line(path, 3.0)
        var seed = 12345L
        fun rnd(): Double { seed = seed * 6364136223846793005L + 1442695040888963407L; return ((seed ushr 33) % 1000) / 1000.0 }
        for (i in c.argb.indices) if (c.argb[i] != 0 && rnd() < 0.33) c.argb[i] = 0
        repeat(60) { c.cover((rnd() * 200).toInt(), (20 + rnd() * 40).toInt(), 1.0, 0) }
        val lines = RasterTracer.trace(c.image())
        assertEquals(1, lines.size, "one mark, however grainy, was ${lines.size}")
        assertEquals(TracedLine.Kind.CENTRE, lines[0].kind)
        val off = stray(lines[0]) { x, y -> distToPolyline(x, y, path) }
        assertTrue(off <= 3.0, "it stays inside the mark (3 px each side of its middle), strayed $off")
        val gap = missed(lines, along(path))
        assertTrue(gap <= 4.0, "and runs its length, missed $gap")
    }
}
