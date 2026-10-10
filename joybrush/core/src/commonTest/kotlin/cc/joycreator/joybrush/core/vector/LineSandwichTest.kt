package cc.joycreator.joybrush.core.vector

import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.brush.TipSpec
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.paint.Tiles
import cc.joycreator.joybrush.core.render.RegionRenderer
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.CelComposer.Items
import cc.joycreator.joybrush.core.vector.CelComposer.Line
import cc.joycreator.joybrush.core.vector.CelComposer.Slab
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The sandwich cache against the reference it previews: [CelComposer.look] of the cel with the edited line.
 *
 * The cel is deliberately hostile. Translucent paint with a different alpha at every pixel, and lines with every
 * operator the map claims (normal, multiply, screen, erase, behind) interleaved above and below the dragged line. A
 * wrong entry in the operator table, or a wrong composition rule, shows up as whole regions off by tens of steps, not
 * as the one-step rounding the KDoc allows.
 */
class LineSandwichTest {

    private val k00 = Tiles.key(0, 0)

    private val pen = BrushPreset(id = "pen", name = "Pen", size = Param(14f), tip = TipSpec(hardness = Param(0.8f)),
        spacing = 0.05f, opacity = Param(0.85f))
    private val brushes = mapOf(
        "pen" to pen,
        "multiply" to pen.copy(id = "multiply", blend = "multiply"),
        "screen" to pen.copy(id = "screen", blend = "screen"),
        "erase" to pen.copy(id = "erase", blend = "erase"),
        "behind" to pen.copy(id = "behind", blend = "behind"),
        "darken" to pen.copy(id = "darken", blend = "darken"),
    )
    private val lookup: (String) -> BrushPreset? = { brushes[it] }

    /** A diagonal-ish line across tile (0,0) from ([x0],[y0]) to ([x1],[y1]). */
    private fun line(id: String, brush: String, x0: Float, y0: Float, x1: Float, y1: Float, argb: Int) =
        StrokeRecord(id, brush, id.hashCode().toLong(), 0f, 1f,
            (0..30).map { i -> val t = i / 30f; PenSample(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, i * 8.0, 0.4f + 0.6f * t) },
            argb)

    /** Premultiplied paint whose alpha changes at every pixel, so no operator's alpha term can hide. */
    private fun paint(seed: Int): ByteArray = ByteArray(RegionRenderer.TILE_BYTES).also { b ->
        for (p in 0 until 256 * 256) {
            val a = ((p * 7 + seed * 31) % 200) + 40
            b[p * 4] = (a * ((p + seed) % 5) / 4).toByte()
            b[p * 4 + 1] = (a * ((p / 256 + seed) % 3) / 2).toByte()
            b[p * 4 + 2] = (a / 2).toByte()
            b[p * 4 + 3] = a.toByte()
        }
    }

    private val dragged = line("m", "pen", 20f, 40f, 230f, 210f, 0xFF2050E0.toInt())

    /** Below the dragged line (seq 4): paint, a multiply line, a behind line. Above it: screen, paint, erase, normal. */
    private fun cel(draggedRecord: StrokeRecord, aboveBrush: String = "screen") = Items(
        listOf(
            Line(2, line("a", "multiply", 10f, 128f, 250f, 120f, 0xFFE0A020.toInt())),
            Line(3, line("b", "behind", 128f, 5f, 120f, 250f, 0xFF20C040.toInt())),
            Line(4, draggedRecord),
            Line(5, line("c", aboveBrush, 30f, 200f, 220f, 30f, 0xFFC03080.toInt())),
            Line(7, line("d", "erase", 60f, 10f, 70f, 245f, 0xFF000000.toInt())),
            Line(8, line("e", "pen", 5f, 60f, 250f, 70f, 0xFF101010.toInt())),
        ),
        mapOf(k00 to listOf(Slab(1, paint(1)), Slab(6, paint(2)))),
    )

    private fun compare(expected: ByteArray?, actual: ByteArray?): Pair<Int, Int> {
        assertNotNull(expected); assertNotNull(actual)
        var worst = 0
        var differing = 0
        for (i in expected.indices) {
            val d = abs((expected[i].toInt() and 255) - (actual[i].toInt() and 255))
            if (d != 0) differing++
            worst = max(worst, d)
        }
        return worst to differing
    }

    @Test
    fun aDragFrameMatchesTheFullRenderOfTheMovedLine() {
        val tile = assertNotNull(LineSandwich.build(CelComposer.draw(cel(dragged), lookup), k00, seq = 4))
        val moved = dragged.copy(samples = dragged.samples.map { it.copy(x = it.x + 17f, y = it.y - 9f) }, widthScale = 1.4f)
        val reference = CelComposer.look(CelComposer.draw(cel(moved), lookup), k00)
        val (worst, differing) = compare(reference, LineSandwich.look(tile, moved, pen))
        assertTrue(worst <= 1 && differing <= RegionRenderer.TILE_BYTES / 200,
            "preview must be the full render within float rounding: worst $worst, $differing bytes differ")

        // Non-vacuous: the move really changes the tile, so a sandwich that ignored the record could not pass.
        val (_, moveChanged) = compare(CelComposer.look(CelComposer.draw(cel(dragged), lookup), k00), reference)
        assertTrue(moveChanged > 2_000, "the move changes the look ($moveChanged bytes)")
    }

    @Test
    fun withNothingAboveThePreviewIsExact() {
        // The map is the identity (K = 1, u = 0, c = 0), and below is built with the same applyOp calls in the same
        // order as look: the bytes must be identical, not merely close.
        val items = Items(cel(dragged).lines.filter { it.seq <= 4 }, mapOf(k00 to listOf(Slab(1, paint(1)))))
        val tile = assertNotNull(LineSandwich.build(CelComposer.draw(items, lookup), k00, seq = 4))
        val recoloured = dragged.copy(colorArgb = 0xFFFF0000.toInt())
        val reference = CelComposer.look(CelComposer.draw(Items(items.lines.map { if (it.seq == 4L) Line(4, recoloured) else it },
            items.slabs), lookup), k00)
        assertContentEquals(reference, LineSandwich.look(tile, recoloured, pen))
    }

    @Test
    fun aNonAffineOperatorAboveTheLineRefusesButBelowItIsFine() {
        assertNull(LineSandwich.build(CelComposer.draw(cel(dragged, aboveBrush = "darken"), lookup), k00, seq = 4),
            "darken above the line cannot collapse into a map")
        // The same darken line BELOW the dragged one is just pixels in `below`.
        val tile = LineSandwich.build(CelComposer.draw(cel(dragged, aboveBrush = "darken"), lookup), k00, seq = 6)
        assertNotNull(tile, "a split above every darken item is fine")
    }

    @Test
    fun eachOperatorAloneAboveTheLineMatches() {
        // One operator at a time, so a wrong row in the table names itself.
        for (op in listOf("pen", "multiply", "screen", "erase", "behind")) {
            val items = Items(listOf(Line(4, dragged), Line(5, line("c", op, 30f, 200f, 220f, 30f, 0xFFC03080.toInt()))),
                mapOf(k00 to listOf(Slab(1, paint(3)))))
            val tile = assertNotNull(LineSandwich.build(CelComposer.draw(items, lookup), k00, seq = 4), op)
            val moved = dragged.copy(samples = dragged.samples.map { it.copy(y = it.y + 23f) })
            val reference = CelComposer.look(CelComposer.draw(Items(listOf(Line(4, moved), items.lines[1]), items.slabs), lookup), k00)
            val (worst, differing) = compare(reference, LineSandwich.look(tile, moved, pen))
            assertTrue(worst <= 1 && differing <= RegionRenderer.TILE_BYTES / 200, "$op: worst $worst, $differing bytes differ")
        }
    }
}
