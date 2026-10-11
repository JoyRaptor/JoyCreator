package cc.joycreator.joybrush.core.vector.svg

import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.brush.ScatterSpec
import cc.joycreator.joybrush.core.brush.TipSpec
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.InkReplay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LineSvgTest {

    private val pen = BrushPreset(id = "pen", name = "Pen", size = Param(12f), tip = TipSpec(hardness = Param(1f)))
    private val brushes = listOf(
        pen,
        pen.copy(id = "spray", scatter = ScatterSpec(amount = Param(0.8f), count = 3)),
        pen.copy(id = "rubber", blend = "erase"),
        pen.copy(id = "wc", blend = "multiply", accumulate = "buildup", opacity = Param(0.5f)),
        pen.copy(id = "under", blend = "behind"),
        BrushPreset(id = "fill", name = "Fill", engine = "fill", size = Param(1f)),
    ).associateBy { it.id }

    private fun record(id: String, brush: String, y: Float, color: Int = 0xff203040.toInt()) =
        StrokeRecord(id, brush, 7L, 0f, 1f, (0..20).map { PenSample(20f + it * 8f, y + (it % 3) * 2f, it * 10.0, 0.4f + it * 0.02f) }, color)

    private fun export(vararg records: StrokeRecord) = LineSvg.export(400.0, 300.0, records.toList(), { brushes[it] })

    private fun paths(svg: String): List<String> = Regex("<path [^>]*/>").findAll(svg).map { it.value }.toList()

    /**
     * Each record is one `<path>` with its id, in list (= seq) order. Would fail if a stroke were split
     * into several elements or the order were not the records'.
     */
    @Test fun oneRecordOnePath() {
        val r = export(record("a", "pen", 40f), record("b", "pen", 100f), record("c", "pen", 160f))
        val p = paths(r.svg)
        assertEquals(3, p.size)
        assertEquals(listOf("a", "b", "c"), p.map { Regex("id=\"([^\"]*)\"").find(it)!!.groupValues[1] })
        assertTrue(p.all { it.split("M").size == 2 }, "an undashed stroke is a single sub-path")
    }

    /**
     * The dash hook: two runs → ONE path with two sub-paths. Would fail if each run became its own element.
     */
    @Test fun dashRunsAreSubPathsOfOnePath() {
        val r = LineSvg.export(400.0, 300.0, listOf(record("d", "pen", 40f)), { brushes[it] }, runsOf = { _, _, dabs ->
            listOf(dabs.subList(0, dabs.size / 3), dabs.subList(2 * dabs.size / 3, dabs.size))
        })
        val p = paths(r.svg)
        assertEquals(1, p.size)
        assertEquals(2, p[0].split("M").size - 1)
    }

    /**
     * A scattered brush exports its PATH's envelope — exactly the unscattered brush's outline — and says
     * so. Would fail if the leaves were traced (a different, much wider path) or the note were missing.
     */
    @Test fun scatterExportsThePlacedPathAndIsReported() {
        val plain = export(record("s", "pen", 40f))
        val spray = export(record("s", "spray", 40f))
        assertEquals(paths(plain.svg), paths(spray.svg))
        assertEquals(listOf("s"), spray.approximated.map { it.strokeId })
        // and the live replay really does scatter, so the equality above is not vacuous
        val placed = LineSvg.placedDabs(record("s", "spray", 40f), brushes.getValue("spray"))
        val leaves = InkReplay.dabs(record("s", "spray", 40f), brushes.getValue("spray"))
        assertEquals(placed.size * 3, leaves.size)
    }

    /**
     * Erase lines are skipped and counted; `behind` and unknown brushes are skipped with a reason.
     * Would fail if an eraser were drawn as ink.
     */
    @Test fun erasersBehindAndMissingBrushesAreReported() {
        val r = export(record("keep", "pen", 40f), record("rub", "rubber", 40f), record("u", "under", 40f), record("x", "nope", 40f))
        assertEquals(1, paths(r.svg).size)
        assertEquals(1, r.erasedLines)
        assertEquals(listOf("rub", "u", "x"), r.skipped.map { it.strokeId })
        assertFalse(r.svg.contains("id=\"rub\""))
    }

    /**
     * Opacity = colour alpha × brush opacity (buildup: opacity.base), multiply → mix-blend-mode. Would fail
     * if the alpha byte were dropped (0.5 instead of 0.251) or the blend ignored.
     */
    @Test fun colourAlphaAndBrushOpacityMultiply() {
        val r = export(record("w", "wc", 40f, 0x80336699.toInt()))
        val p = paths(r.svg).single()
        assertTrue(p.contains("fill=\"#336699\"") && p.contains("fill-opacity=\"0.251\"") && p.contains("mix-blend-mode:multiply"), p)
    }

    /** A fill-pen record is a nonzero polygon path. */
    @Test fun fillPenIsAPolygon() {
        val rec = StrokeRecord("f", "fill", 1L, 0f, 1f,
            listOf(PenSample(10f, 10f, 0.0), PenSample(100f, 10f, 1.0), PenSample(60f, 90f, 2.0)), 0xffff0000.toInt())
        val p = paths(export(rec).svg).single()
        assertTrue(p.contains("id=\"f\"") && p.contains("fill=\"#ff0000\"") && p.contains("fill-rule=\"nonzero\""), p)
    }

    /** Same records, same bytes. */
    @Test fun exportIsDeterministic() {
        val a = export(record("a", "pen", 40f), record("w", "wc", 90f))
        val b = export(record("a", "pen", 40f), record("w", "wc", 90f))
        assertEquals(a.svg, b.svg)
    }
}
