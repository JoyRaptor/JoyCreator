package cc.joycreator.joybrush.core.vector.svg

import cc.joycreator.joybrush.core.paint.Dab
import cc.joycreator.joybrush.core.paint.TipShape
import cc.joycreator.joybrush.core.shape.Pt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SvgWriterTest {

    private val tip = TipShape(hardness = 1f)

    private fun run(x0: Float, y: Float, len: Int, r: Float = 4f) = (0..len).map { Dab(x0 + it, y, r) }

    private fun line(seq: Long, id: String, runs: List<List<Dab>>, argb: Int = 0xff102030.toInt(), opacity: Float = 1f,
                     blend: SvgWriter.Blend = SvgWriter.Blend.NORMAL) =
        SvgWriter.Line(seq, id, StrokeOutline.strokeContours(runs, tip), argb, opacity, blend)

    private fun count(text: String, what: String): Int = text.windowed(what.length).count { it == what }

    private fun paths(svg: String): List<String> = Regex("<path [^>]*/>").findAll(svg).map { it.value }.toList()

    /**
     * Exactly one `<path>` per stroke, whatever its runs: a three-dash stroke is one element with three
     * `M` sub-paths. Would fail if the writer emitted one element per contour (or per dab).
     */
    @Test fun oneElementPerStrokeWithDashesAsSubPaths() {
        val svg = SvgWriter.write(200.0, 100.0, listOf(
            line(1, "solid", listOf(run(10f, 10f, 50))),
            line(2, "dashed", listOf(run(10f, 40f, 20), run(40f, 40f, 20), run(70f, 40f, 20))),
            line(3, "dot", listOf(listOf(Dab(150f, 80f, 6f)))),
        )).svg
        val p = paths(svg)
        assertEquals(3, p.size)
        assertEquals(1, count(p[0], "M"))
        assertEquals(3, count(p[1], "M"))
        assertEquals(3, count(p[1], "Z"))
        assertTrue(p.all { it.contains("fill-rule=\"nonzero\"") }, "the outline's self-overlap needs nonzero")
    }

    /**
     * Same input, byte-identical output, twice. Would fail on any hash-ordered map, clock, random id or
     * locale-dependent number format in the path.
     */
    @Test fun outputIsDeterministic() {
        fun build() = SvgWriter.write(321.5, 123.25, listOf(
            line(5, "b", listOf(run(10f, 10f, 40, 3.3f))),
            line(2, "a", listOf(run(10f, 60f, 40, 7.7f)), 0x80123456.toInt(), 0.37f, SvgWriter.Blend.MULTIPLY),
            SvgWriter.Fill(3, "f", listOf(Pt(1.0, 1.0), Pt(50.0, 1.0), Pt(25.0, 40.0)), 0xffabcdef.toInt(), 1f),
            SvgWriter.Slab(4, "s", 0.0, 0.0, 2.0, 2.0, byteArrayOf(1, 2, 3, 4, 5)),
        )).svg
        assertEquals(build(), build())
    }

    /**
     * `fill` is the straight sRGB of the record colour (alpha byte NOT included), `fill-opacity` the given
     * opacity, multiply becomes mix-blend-mode. Would fail if the colour were premultiplied, if alpha
     * leaked into the hex, or if the blend were dropped.
     */
    @Test fun colourOpacityAndBlendMapping() {
        val svg = SvgWriter.write(100.0, 100.0, listOf(
            line(1, "n", listOf(run(10f, 10f, 10)), 0x80ff8000.toInt(), 0.25f),
            line(2, "m", listOf(run(10f, 30f, 10)), 0xff00a0ff.toInt(), 1f, SvgWriter.Blend.MULTIPLY),
            line(3, "nan", listOf(run(10f, 50f, 10)), 0xff000000.toInt(), Float.NaN),
        )).svg
        val p = paths(svg)
        assertTrue(p[0].contains("fill=\"#ff8000\"") && p[0].contains("fill-opacity=\"0.25\""), p[0])
        assertFalse(p[0].contains("mix-blend-mode"))
        assertTrue(p[1].contains("fill=\"#00a0ff\"") && p[1].contains("fill-opacity=\"1\"") &&
            p[1].contains("style=\"mix-blend-mode:multiply\""), p[1])
        assertTrue(p[2].contains("fill-opacity=\"1\""), "NaN opacity reads as 1: ${p[2]}")
        assertTrue(svg.contains("isolation:isolate"), "multiply must blend within the cel, not with the page")
    }

    /**
     * Erase items are left out and counted. Would fail if an eraser were written as a black fill.
     */
    @Test fun eraseIsSkippedAndCounted() {
        val r = SvgWriter.write(100.0, 100.0, listOf(
            line(1, "keep", listOf(run(10f, 10f, 10))),
            line(2, "rub", listOf(run(10f, 10f, 10)), blend = SvgWriter.Blend.ERASE),
        ))
        assertEquals(1, r.skippedErase)
        assertEquals(1, r.written)
        assertFalse(r.svg.contains("rub"))
    }

    /** Items come out in seq order (stable for ties). Would fail if list order were trusted over seq. */
    @Test fun itemsAreWrittenInSeqOrder() {
        val svg = SvgWriter.write(100.0, 100.0, listOf(
            line(9, "third", listOf(run(10f, 10f, 5))),
            line(1, "first", listOf(run(10f, 10f, 5))),
            SvgWriter.Slab(4, "second", 0.0, 0.0, 1.0, 1.0, byteArrayOf(0)),
            line(9, "fourth", listOf(run(10f, 10f, 5))),
        )).svg
        val order = listOf("first", "second", "third", "fourth").map { svg.indexOf("id=\"$it\"") }
        assertTrue(order.all { it >= 0 } && order == order.sorted(), "$order")
    }

    /**
     * Record ids are attribute text: markup characters escaped, characters XML cannot hold replaced.
     * Would fail (ill-formed file) if `"` or `<` went through raw, or a U+0001 were written.
     */
    @Test fun idsAreEscaped() {
        assertEquals("a&lt;b&gt;&amp;&quot;c&apos;�&#9;😀�", SvgText.attr("a<b>&\"c'\u0001\t😀\uD800"))
        val svg = SvgWriter.write(10.0, 10.0, listOf(line(1, "x\"/><script>", listOf(run(1f, 1f, 3))))).svg
        assertTrue(svg.contains("id=\"x&quot;/&gt;&lt;script&gt;\""))
        assertFalse(svg.contains("<script>"))
    }

    /**
     * Numbers: 2 decimals, half up, no trailing zeros, no exponent, no "-0". Would fail with Kotlin's
     * `toString` (exponents for 1e7, 0.1 + 0.2 = 0.30000000000000004) or a locale comma.
     */
    @Test fun numberFormatIsFixed() {
        assertEquals("0", SvgText.num(-0.004))
        assertEquals("0", SvgText.num(0.0))
        assertEquals("3", SvgText.num(3.0))
        assertEquals("0.3", SvgText.num(0.1 + 0.2))
        assertEquals("-12.35", SvgText.num(-12.3456))
        assertEquals("2.05", SvgText.num(2.05))
        assertEquals("10000000", SvgText.num(1e7))
        assertEquals("0.5", SvgText.opacity(0.5f))
        assertEquals("0.251", SvgText.opacity(0x80 / 255f * 0.5f))
        assertEquals("0", SvgText.opacity(-1f))
    }

    /** RFC 4648 §10's test vectors. Would fail on a wrong alphabet, bit order or padding. */
    @Test fun base64MatchesRfc4648() {
        val cases = mapOf("" to "", "f" to "Zg==", "fo" to "Zm8=", "foo" to "Zm9v", "foob" to "Zm9vYg==",
            "fooba" to "Zm9vYmE=", "foobar" to "Zm9vYmFy")
        for ((plain, coded) in cases) assertEquals(coded, SvgText.base64(plain.encodeToByteArray()))
        assertEquals("/+8=", SvgText.base64(byteArrayOf(-1, -17)))
    }

    /** A slab is an `<image>` with its PNG inline at its doc-px rectangle. */
    @Test fun slabIsAnInlineImage() {
        val svg = SvgWriter.write(512.0, 512.0, listOf(SvgWriter.Slab(1, "tile_0_0", 256.0, 0.0, 256.0, 256.0, "foo".encodeToByteArray()))).svg
        assertTrue(svg.contains("<image id=\"tile_0_0\" x=\"256\" y=\"0\" width=\"256\" height=\"256\" preserveAspectRatio=\"none\" " +
            "xlink:href=\"data:image/png;base64,Zm9v\"/>"), svg)
    }
}
