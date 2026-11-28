package cc.joycreator.joybrush.core.trace

import cc.joycreator.joybrush.core.brush.BrushInput
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.InputCurve
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.vector.InkReplay
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Concepts parity 4c: a traced line becomes an ordinary editable line that the real replay draws where, and as wide as, it was traced. */
class TracedStrokesTest {

    /** A pen whose width follows pressure: 10% wide at the lightest touch, full at a full press. */
    private val pen = BrushPreset(
        id = "trace-test-pen", name = "Trace test pen",
        size = Param(8f, listOf(InputCurve(BrushInput.pressure, listOf(listOf(0f, 0.1f), listOf(1f, 1f))))),
    )

    /** A pen that draws one width whatever the pressure. */
    private val monoline = BrushPreset(id = "trace-test-mono", name = "Mono", size = Param(10f))

    private fun line(vararg pts: Triple<Float, Float, Float>, closed: Boolean = false, colour: Int = 0xFF2040E0.toInt()) =
        TracedLine(FloatArray(pts.size) { pts[it].first }, FloatArray(pts.size) { pts[it].second },
            FloatArray(pts.size) { pts[it].third }, closed, colour, TracedLine.Kind.CENTRE)

    private fun median(v: List<Float>) = v.sorted()[v.size / 2]

    @Test
    fun aLineIsDrawnAsWideAsItWasTraced() {
        val rec = TracedStrokes.toRecords(listOf(line(Triple(10f, 10f, 2f), Triple(110f, 10f, 2f))), pen, 1L, { "t$it" }).single()
        val r = median(InkReplay.dabs(rec, pen).map { it.radius })
        assertTrue(abs(r - 2f) <= 0.25f, "half-width 2 comes back as about 2, was $r")
    }

    @Test
    fun aTaperIsKept() {
        val rec = TracedStrokes.toRecords(listOf(line(Triple(0f, 0f, 1f), Triple(50f, 0f, 2f), Triple(100f, 0f, 3f))), pen, 1L, { "t$it" }).single()
        val dabs = InkReplay.dabs(rec, pen)
        val start = median(dabs.filter { it.x < 10f }.map { it.radius })
        val end = median(dabs.filter { it.x > 90f }.map { it.radius })
        assertTrue(abs(start - 1.1f) <= 0.4f, "the thin end stays thin, was $start")
        assertTrue(abs(end - 2.9f) <= 0.4f, "the wide end stays wide, was $end")
    }

    @Test
    fun aBrushThatIgnoresPressureDrawsTheMiddleWidth() {
        val rec = TracedStrokes.toRecords(listOf(line(Triple(0f, 0f, 1.5f), Triple(80f, 0f, 1.5f))), monoline, 1L, { "t$it" }).single()
        val r = median(InkReplay.dabs(rec, monoline).map { it.radius })
        assertTrue(abs(r - 1.5f) <= 0.2f, "was $r")
    }

    @Test
    fun theRecordsAreOrdinaryLines() {
        val lines = listOf(
            line(Triple(0f, 0f, 1f), Triple(40f, 0f, 1f), colour = 0xFFE0201A.toInt()),
            line(Triple(0f, 0f, 1f), Triple(20f, 0f, 1f), Triple(20f, 20f, 1f), closed = true),
        )
        val recs = TracedStrokes.toRecords(lines, pen, 7L, { "traced-$it" }, TracedStrokes.Placement(100f, 50f, 2f))
        assertEquals(listOf("traced-0", "traced-1"), recs.map { it.id })
        assertEquals(listOf(7L, 8L), recs.map { it.seq }, "they follow on in the cel's time order")
        assertTrue(recs.all { it.brushId == pen.id && !it.locked && it.groupId == null })
        assertEquals(0xFFE0201A.toInt(), recs[0].colorArgb, "the ink's colour")
        assertEquals(100f, recs[0].samples.first().x, "placed in the drawing")
        assertEquals(180f, recs[0].samples.last().x, "at the drawing's scale")
        val ring = recs[1].samples
        assertTrue(ring.first().x == ring.last().x && ring.first().y == ring.last().y, "a closed line ends where it starts")
        assertTrue(ring.zipWithNext().all { (a, b) -> b.timeMs > a.timeMs }, "time runs forward along it")
    }

    @Test
    fun aDrawnMarkComesBackAsALineOnTheSamePath() {
        // the whole way: pixels of a known mark -> traced -> a record -> the real replay's dabs
        val w = 160
        val h = 60
        val argb = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val d = abs(y + 0.5 - (30.0 + 0.1 * (x - 20))).let { if (x in 20..140) it else hypot(x + 0.5 - (if (x < 20) 20.0 else 140.0), y + 0.5 - (30.0 + 0.1 * ((if (x < 20) 20 else 140) - 20))) }
            val c = (2.0 + 0.5 - d).coerceIn(0.0, 1.0)
            argb[y * w + x] = ((c * 255).toInt() shl 24)
        }
        val traced = RasterTracer.trace(TraceImage(w, h, argb))
        assertEquals(1, traced.size)
        val rec = TracedStrokes.toRecords(traced, pen, 1L, { "t$it" }).single()
        val dabs = InkReplay.dabs(rec, pen)
        assertTrue(dabs.isNotEmpty())
        for (d in dabs) {
            val onPath = abs(d.y - (30.0 + 0.1 * (d.x - 20)))
            assertTrue(d.x < 18f || d.x > 142f || onPath <= 1.2, "a dab at (${d.x}, ${d.y}) is off the mark by $onPath")
        }
        val r = median(dabs.map { it.radius })
        assertTrue(abs(r - 2f) <= 0.6f, "drawn 2 px each side, replayed $r")
    }
}
