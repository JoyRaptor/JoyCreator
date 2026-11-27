package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.doc.Board
import cc.joycreator.joybrush.core.doc.BoardKind
import cc.joycreator.joybrush.core.doc.Cel
import cc.joycreator.joybrush.core.doc.DocOps
import cc.joycreator.joybrush.core.doc.Frame
import cc.joycreator.joybrush.core.doc.JbDocument
import cc.joycreator.joybrush.core.doc.Layer
import cc.joycreator.joybrush.core.doc.LayerKind
import cc.joycreator.joybrush.core.doc.Paper
import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.export.AnimExport
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.render.RegionException
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The animation/INK lookup boundary, as it stands today.
 *
 * LEGACY CURRENT SAFE-REFUSAL CONTRACT, NOT A DESIRED FUTURE LIMITATION. [OraExport] already
 * draws procedural INK through an explicit brush lookup (built-ins by default, caller-supplied
 * for custom presets). [AnimExportRunner] has no brush-lookup parameter at all, so every
 * [RegionRenderer.render] it makes arrives with a null lookup and [RegionRenderer] refuses a
 * document containing visible INK before any pixel is allocated. That refusal is the safe
 * behaviour today — a missing picture with a sentence rather than a blank animation — and this
 * file pins it. The desired future is to thread the same lookup through the animation path so
 * INK animates; when that lands, the refusal tests below go red on purpose and are replaced.
 *
 * This is a CLASS on purpose. JUnit 4 runs methods on an instance, so top-level `@Test`
 * functions compile, report green, and execute nothing.
 */
class AnimInkLookupBoundaryTest {
    private val BOARD = "b1"
    private val INK = "ink"
    private val W = 64
    private val H = 48

    /**
     * Procedural stamp brush with every default that matters: `stamp` engine draws ink lines,
     * `procedural` tip, no tip grain, `normal` blend. A custom id so the test never depends on
     * which brushes happened to be packaged; the ORA half passes it explicitly, which is what
     * makes the brush resolvable by construction rather than by resource luck.
     */
    private val brush = BrushPreset(id = "boundary-pen", name = "Boundary pen", size = Param(12f))

    private fun stroke(id: String, y: Float, color: Int): StrokeRecord = StrokeRecord(
        id = id,
        brushId = brush.id,
        seed = 7L,
        smoothing = 0f,
        screenPerDoc = 1f,
        samples = listOf(
            PenSample(8f, y, 0.0),
            PenSample(24f, y, 1.0),
            PenSample(40f, y, 2.0),
            PenSample(56f, y, 3.0),
        ),
        colorArgb = color,
    )

    private fun board() = Board(
        id = BOARD,
        name = "Board",
        kind = BoardKind.ANIMATION,
        rect = RectPx(0, 0, W, H),
        fps = 12f,
        frames = listOf(Frame("f0"), Frame("f1")),
    )

    private fun doc(): JbDocument {
        val b = board()
        return JbDocument(
            id = "doc-1",
            name = "Ink boundary",
            paper = Paper(color = "#FFFFFF", includeInExport = false),
            boards = listOf(b),
            layers = listOf(
                Layer(
                    id = INK,
                    name = "Ink",
                    kind = LayerKind.INK,
                    animatedIn = b.id,
                    cels = listOf(Cel("ink-f0"), Cel("ink-f1")),
                    frameCel = mapOf("f0" to "ink-f0", "f1" to "ink-f1"),
                ),
            ),
            activeLayerId = INK,
            activeBoardId = b.id,
        )
    }

    private fun contents(): JbContents {
        val d = doc()
        return JbContents(
            doc = d,
            tiles = emptyMap(),
            strokes = mapOf(
                (INK to "ink-f0") to listOf(stroke("red", 10f, 0xFFFF0000.toInt())),
                (INK to "ink-f1") to listOf(stroke("blue", 30f, 0xFF0000FF.toInt())),
            ),
        )
    }

    private fun lookup(): (String) -> BrushPreset? = { id -> if (id == brush.id) brush else null }

    private fun entriesOf(archive: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        val zis = ZipInputStream(ByteArrayInputStream(archive))
        while (true) {
            val entry = zis.nextEntry ?: break
            out[entry.name] = zis.readBytes()
            zis.closeEntry()
        }
        return out
    }

    private fun image(archive: ByteArray, name: String): BufferedImage {
        val bytes = assertNotNull(entriesOf(archive)[name], "no entry \"$name\" in the .ora")
        return assertNotNull(ImageIO.read(ByteArrayInputStream(bytes)), "\"$name\" is not a PNG ImageIO can read")
    }

    private fun pixelAt(img: BufferedImage, x: Int, y: Int): List<Int> {
        val raster = img.raster
        assertEquals(4, raster.numBands, "the PNG is not RGBA")
        return (0..3).map { raster.getSample(x, y, it) }
    }

    private fun opaqueCount(img: BufferedImage): Int {
        var n = 0
        for (y in 0 until img.height) for (x in 0 until img.width) {
            if (img.raster.getSample(x, y, 3) != 0) n++
        }
        return n
    }

    private fun rgbaOf(img: BufferedImage): ByteArray {
        val raster = img.raster
        val out = ByteArray(img.width * img.height * 4)
        var at = 0
        for (y in 0 until img.height) for (x in 0 until img.width) {
            for (b in 0 until 4) out[at + b] = raster.getSample(x, y, b).toByte()
            at += 4
        }
        return out
    }

    /**
     * The fixture is good: a real procedural INK document whose per-frame layer PNG and merged
     * PNG, decoded by the foreign `javax.imageio` decoder, nontrivially match the intended
     * brush output. Hand-derived geometry and colour — a horizontal stroke at y=10 in opaque
     * red for f0, at y=30 in opaque blue for f1 — never re-derived from the renderer, so this
     * cannot agree with itself. Without this, the animation refusal below could be passing
     * because the brush was unresolvable rather than because the animation path has no lookup.
     */
    @Test fun oraDecodesProceduralInkPerLayerAndMergedToIntendedBrushOutput() {
        val c = contents()
        assertTrue(DocOps.validate(c.doc).isEmpty(), "fixture must be valid: ${DocOps.validate(c.doc)}")

        for ((frameId, y, rgb) in listOf(
            Triple("f0", 10, listOf(255, 0, 0)),
            Triple("f1", 30, listOf(0, 0, 255)),
        )) {
            val out = ByteArrayOutputStream()
            OraExport.write(out, c, BOARD, frameId, false, brushLookup = lookup())
            val archive = out.toByteArray()
            val layer = image(archive, "data/0.png")
            val merged = image(archive, "mergedimage.png")
            assertEquals(W, layer.width, "$frameId layer width")
            assertEquals(H, layer.height, "$frameId layer height")
            assertEquals(W, merged.width, "$frameId merged width")
            assertEquals(H, merged.height, "$frameId merged height")

            // On the stroke: opaque, and exactly the record's own RGB.
            assertEquals(rgb + 255, pixelAt(layer, 32, y), "$frameId layer on-stroke pixel")
            assertEquals(rgb + 255, pixelAt(merged, 32, y), "$frameId merged on-stroke pixel")
            // Far from either stroke: nothing drew there.
            assertEquals(0, pixelAt(layer, 60, 44)[3], "$frameId layer far corner must stay transparent")
            assertEquals(0, pixelAt(merged, 60, 44)[3], "$frameId merged far corner must stay transparent")
            assertEquals(0, pixelAt(layer, 0, 0)[3], "$frameId layer origin must stay transparent")
            // The other frame's stroke is not in this frame.
            val otherY = if (y == 10) 30 else 10
            assertEquals(0, pixelAt(layer, 32, otherY)[3], "$frameId must not contain the other frame's stroke")

            // Nontrivial: neither blank nor a full flood. A 48 px line at 12 px wide covers
            // several hundred pixels; bounds are deliberately loose so brush tuning cannot flake this.
            val n = opaqueCount(layer)
            assertTrue(n in 100..2500, "$frameId opaque count $n is blank or a flood")
            assertTrue(opaqueCount(merged) in 100..2500, "$frameId merged opaque count is blank or a flood")

            // One INK layer and no paper: the merged image is that layer alone.
            assertContentEquals(rgbaOf(layer), rgbaOf(merged), "$frameId layer and merged must agree")
        }

        // The two frames really are two different pictures.
        val f0 = image(run {
            val out = ByteArrayOutputStream()
            OraExport.write(out, c, BOARD, "f0", false, brushLookup = lookup())
            out.toByteArray()
        }, "data/0.png")
        val f1 = image(run {
            val out = ByteArrayOutputStream()
            OraExport.write(out, c, BOARD, "f1", false, brushLookup = lookup())
            out.toByteArray()
        }, "data/0.png")
        assertTrue(!rgbaOf(f0).contentEquals(rgbaOf(f1)), "the two frames must differ")
    }

    /**
     * LEGACY CONTRACT: animation has no brush lookup today, so the same valid, resolvable INK
     * document is refused explicitly before any output. The type and the sentence are the
     * assertion — a generic `Exception` regardless of cause would also pass for a validation
     * slip, a budget refusal, or a sink failure, and would pin nothing.
     */
    @Test fun animationRefusesInkWithoutLookupBeforeAnyOutput() {
        val c = contents()
        assertTrue(DocOps.validate(c.doc).isEmpty(), "inputs must be valid: ${DocOps.validate(c.doc)}")
        val plan = AnimExport.plan(c.doc.boards.single(), c.doc.boards.single().name)
        assertEquals(listOf("f0", "f1"), plan.frameIds, "the plan covers both frames")
        assertEquals(W, plan.width)
        assertEquals(H, plan.height)

        for (format in listOf(AnimFormat.GIF, AnimFormat.SPRITE_SHEET)) {
            var rendered = 0
            val e = assertFailsWith<RegionException>("$format must refuse INK explicitly") {
                AnimExportRunner.encodeOne(format, c, BOARD, plan, false, onFrame = { _, _ -> rendered++ })
            }
            val message = e.message.orEmpty()
            assertTrue(message.contains("Vector artwork"), "$format refusal must name the legacy cause: $message")
            assertEquals(0, rendered, "$format refused before its first frame")
        }

        val written = ArrayList<String>()
        val sink = AnimFileSink { name, _, _ -> written.add(name) }
        var rendered = 0
        val seq = assertFailsWith<RegionException>("a sequence must refuse INK explicitly") {
            AnimExportRunner.writeSequence(c, BOARD, plan, false, sink, onFrame = { _, _ -> rendered++ })
        }
        assertTrue(seq.message.orEmpty().contains("Vector artwork"), "sequence refusal: ${seq.message}")
        assertEquals(0, rendered, "the sequence refused before its first frame")
        assertTrue(written.isEmpty(), "the sequence wrote nothing: $written")
    }
}
