package cc.joycreator.joybrush.core.vector.svg

import cc.joycreator.joybrush.core.brush.BrushInput
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.InputCurve
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.brush.TipSpec
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import java.io.ByteArrayInputStream
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.w3c.dom.Element

/**
 * The export as a parser this project did not write reads it: the JDK's namespace-aware DOM parser.
 * A file that only "looks right" to the string checks in commonTest fails here on the first
 * unescaped quote, stray control character or unbound prefix (`xlink:` without its namespace).
 */
class SvgXmlTest {

    private val pen = BrushPreset(id = "pen", name = "Pen", size = Param(14f, listOf(InputCurve(BrushInput.pressure, listOf(listOf(0f, 0.1f), listOf(1f, 1f))))),
        tip = TipSpec(hardness = Param(1f)))
    private val brushes = mapOf(
        "pen" to pen,
        "marker" to pen.copy(id = "marker", size = Param(26f), blend = "multiply", accumulate = "buildup", opacity = Param(0.6f),
            tip = TipSpec(hardness = Param(1f), aspect = 0.55f, angle = Param(35f))),
        "fill" to BrushPreset(id = "fill", name = "Fill", engine = "fill", size = Param(1f)),
    )

    private fun parse(svg: String) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(ByteArrayInputStream(svg.toByteArray(Charsets.UTF_8)))

    private fun sample(): List<StrokeRecord> {
        val wave = (0..40).map { i ->
            val t = i / 40.0
            PenSample((40 + 420 * t).toFloat(), (120 + 60 * sin(2 * PI * t)).toFloat(), i * 8.0, (0.15 + 0.85 * sin(PI * t)).toFloat())
        }
        val loop = (0..48).map { i ->
            val a = 2 * PI * i / 40
            PenSample((260 + 70 * cos(a) + i * 2).toFloat(), (300 + 70 * sin(a)).toFloat(), i * 8.0, 0.8f)
        }
        val hair = (0..30).map { i -> PenSample((60 + i * 3f), (260 - (i - 15) * (i - 15) * 0.8f), i * 8.0, 1f - i / 40f) }
        val blob = listOf(PenSample(420f, 240f, 0.0), PenSample(520f, 230f, 1.0), PenSample(540f, 330f, 2.0), PenSample(430f, 360f, 3.0))
        return listOf(
            StrokeRecord("fill<1>", "fill", 1L, 0f, 1f, blob, 0xffffd166.toInt()),
            StrokeRecord("wave \"pressure\"", "pen", 2L, 0.2f, 1f, wave, 0xff1d3557.toInt()),
            StrokeRecord("loop&marker", "marker", 3L, 0.2f, 1f, loop, 0xcce63946.toInt()),
            StrokeRecord("hairpin's", "pen", 4L, 0.1f, 1f, hair, 0xff2a9d8f.toInt()),
        )
    }

    /**
     * Parses; one `<path>` per exportable record with the original id back out of the DOM; one `<image>`
     * for the slab, its href in the xlink namespace. Would fail on any escaping or namespace mistake.
     */
    @Test fun exportIsWellFormedXml() {
        val records = sample()
        val slab = SvgWriter.Slab(10, "slab<0>", 0.0, 0.0, 4.0, 4.0, byteArrayOf(-119, 80, 78, 71))
        val r = LineSvg.export(600.0, 420.0, records, { brushes[it] }, slabs = listOf(slab))
        val doc = parse(r.svg)
        val svgNs = "http://www.w3.org/2000/svg"
        assertEquals("svg", doc.documentElement.localName)
        assertEquals(svgNs, doc.documentElement.namespaceURI)
        val paths = doc.getElementsByTagNameNS(svgNs, "path")
        assertEquals(records.size, paths.length)
        assertEquals(records.map { it.id }, (0 until paths.length).map { (paths.item(it) as Element).getAttribute("id") })
        val images = doc.getElementsByTagNameNS(svgNs, "image")
        assertEquals(1, images.length)
        val href = (images.item(0) as Element).getAttributeNS("http://www.w3.org/1999/xlink", "href")
        assertEquals("data:image/png;base64,iVBORw==", href)
        // the sample the lead looks at
        File("build/vector_svg_sample.svg").apply { parentFile?.mkdirs() }.writeText(
            LineSvg.export(600.0, 420.0, records, { brushes[it] }).svg, Charsets.UTF_8)
    }

    /** An id carrying every awkward character survives the round trip through a real parser. */
    @Test fun awkwardIdsRoundTrip() {
        val id = "a<b>&\"c'\tdé😀"
        val svg = SvgWriter.write(10.0, 10.0, listOf(SvgWriter.Fill(1, id,
            listOf(cc.joycreator.joybrush.core.shape.Pt(1.0, 1.0), cc.joycreator.joybrush.core.shape.Pt(5.0, 1.0),
                cc.joycreator.joybrush.core.shape.Pt(3.0, 4.0)), 0xff000000.toInt(), 1f))).svg
        val el = parse(svg).getElementsByTagNameNS("http://www.w3.org/2000/svg", "path").item(0) as Element
        assertEquals(id, el.getAttribute("id"))
    }

    @Test fun sampleSvgIsDeterministicAndKeepsOnePathPerStroke() {
        val records = sample()
        val a = LineSvg.export(600.0, 420.0, records, { brushes[it] })
        val b = LineSvg.export(600.0, 420.0, records, { brushes[it] })
        assertTrue(a.skipped.isEmpty())
        assertEquals(a.svg, b.svg)
        val paths = parse(a.svg).getElementsByTagNameNS("http://www.w3.org/2000/svg", "path")
        assertEquals(records.size, paths.length)
        val output = File("build/vector_svg_sample.svg")
        output.parentFile.mkdirs()
        output.writeText(a.svg, Charsets.UTF_8)
        assertEquals(a.svg, output.readText(Charsets.UTF_8))
    }
}
