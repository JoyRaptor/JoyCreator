package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.core.doc.Board
import cc.joycreator.joybrush.core.doc.BoardKind
import cc.joycreator.joybrush.core.doc.BlendMode
import cc.joycreator.joybrush.core.export.AnimExport
import cc.joycreator.joybrush.core.export.AnimExportPlan
import cc.joycreator.joybrush.core.doc.DocOps
import cc.joycreator.joybrush.core.doc.Frame
import cc.joycreator.joybrush.core.doc.Paper
import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.render.RegionException
import cc.joycreator.joybrush.core.render.RegionRenderer
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/**
 * JB-9.06b at the level the defect was actually visible in: a decoded FILE.
 *
 * The core suite proves the arithmetic; this one proves the three exporters route through it, which
 * is the half that used to be wrong in three separate places. A MULTIPLY layer over a two-tone
 * paper is the whole demonstration, because it is the one case where "paper first" and "paper last"
 * cannot both be right:
 *
 *     paper first   128/255 x (200,40,60) = (100, 20, 30)   at x = 0
 *                   128/255 x (20,80,220)  = ( 10, 40,110)   at x = 5
 *     paper last    (128, 128, 128) at BOTH, because the layer is opaque and the paste-over never
 *                   sees the paper at all
 *
 * The two are 28 and 118 units apart in the blue channel alone, so no tolerance hides one for the
 * other and the file cannot come out right by accident.
 */
class PaperExportBlendTest {

    @Test
    fun thePngMultipliesThePaperInsteadOfPastingItOverTheTop() {
        val with = ImageIO.read(ByteArrayInputStream(CanvasPng.encode(contents(), true, paperRenderer = ::paperOf)))
        assertEquals(0xFF64141E.toInt(), with.getRGB(0, 0), "MULTIPLY over the (200,40,60) half of the paper")
        assertEquals(0xFF0A286E.toInt(), with.getRGB(5, 0), "MULTIPLY over the (20,80,220) half of the paper")
        // The removed composition, written out so the test says what it is protecting against.
        assertNotEquals(0xFF808080.toInt(), with.getRGB(0, 0), "a paste-over returns the opaque layer untouched")
        assertNotEquals(0xFF808080.toInt(), with.getRGB(5, 0), "and it cannot tell the two halves of the paper apart")
    }

    /**
     * PNG, the animation sequence and the OpenRaster merged image are three code paths that each
     * held their own opinion about where paper goes. Here they are held to one: the same document,
     * the same frame and the same paper must decode to the same bytes in all three.
     *
     * ONE DOCUMENT FOR ALL THREE, and that is why the fixture is an ANIMATION board with a single
     * frame and a static layer. A PNG export refuses an animated layer outright, so the alternatives
     * were two fixtures that differ in more than the thing under test, or a document one of the
     * exporters is not allowed to accept. A static MULTIPLY layer on a one-frame board is accepted
     * by all three, and the only thing left that could differ between the files is paper routing.
     */
    @Test
    fun everyExporterPutsTheSamePixelsUnderTheSamePaper() {
        val art = contents(animation = true)
        val png = ImageIO.read(ByteArrayInputStream(CanvasPng.encode(art, true, paperRenderer = ::paperOf)))

        val frames = ArrayList<ByteArray>()
        AnimExportRunner.writeSequence(
            art, BOARD_ID, AnimExport.plan(art.doc.boards.single(), "seq"), true,
            AnimFileSink { _, mime, bytes -> if (mime == "image/png") frames += bytes }, ::paperOf,
        )
        assertEquals(1, frames.size)
        val sequence = ImageIO.read(ByteArrayInputStream(frames.single()))

        val ora = ByteArrayOutputStream()
        OraExport.write(ora, art, BOARD_ID, "f0", true, paperRenderer = ::paperOf)
        val merged = imageIn(ora.toByteArray(), "mergedimage.png")

        // Compared against the PNG, pixel for pixel, over the whole board: a difference anywhere is
        // a difference in where the paper went, and there is no other variable in this fixture.
        for (y in 0 until 4) {
            for (x in 0 until 8) {
                assertEquals(png.getRGB(x, y), sequence.getRGB(x, y), "the sequence frame differs from the PNG at $x,$y")
                assertEquals(png.getRGB(x, y), merged.getRGB(x, y), "mergedimage differs from the PNG at $x,$y")
            }
        }
        // And the shared answer is the multiply, not the paste-over: without this the three could
        // agree with each other on the wrong picture, which is what they did before this row.
        assertEquals(0xFF64141E.toInt(), merged.getRGB(0, 0), "all three agree on the WRONG answer")
    }

    /**
     * EXCLUDED PAPER IS NOT READ, NOT RENDERED AND NOT GUESSED, and no byte changes because a
     * renderer was available. The renderer throws if it is called, so a future "cheap" default that
     * resolves the material before checking the flag turns this red instead of costing a decode on
     * every export. The Lead's screen-None state is covered by `PaperNoneExportTest`; this is the
     * plain Include-paper-off case.
     */
    @Test
    fun excludingPaperAsksForNothingAndChangesNoBytes() {
        val art = contents()
        val withRendererPresent = CanvasPng.encode(art, false, paperRenderer = { error("excluded paper must never be read") })
        val plain = CanvasPng.encode(art, false)
        assertContentEquals(plain, withRendererPresent, "an unread renderer must not change a single byte")
        // The layer, and only the layer. It is OPAQUE, so "no paper" is not transparency here but
        // the source-over term carrying the source where the backdrop is empty — and the paper
        // colours in this file are nowhere near it, so a stray paper would change every pixel.
        val image = ImageIO.read(ByteArrayInputStream(plain))
        for (x in 0 until 8) {
            assertEquals(0xFF808080.toInt(), image.getRGB(x, 0), "no paper means no paper at x=$x")
        }
    }

    /**
     * THE GUARD BEFORE THE CALLBACK, at the door a person actually presses. A board too large to
     * allocate is refused with a sentence, and refused BEFORE a single paper block is asked for —
     * `MAX_REGION_PX` exists to stop a phone trying to allocate eight gigabytes, and it would be
     * worth nothing if the texture decode happened on the way to being told no.
     */
    @Test
    fun aBoardTooLargeToRenderIsRefusedBeforeAnyPaperIsRead() {
        var calls = 0
        assertFailsWith<RegionException> {
            CanvasPng.encode(contents(RectPx(0, 0, 4096, 4096)), true, paperRenderer = { rect -> calls++; ByteArray(rect.w * rect.h * 4) })
        }
        assertEquals(0, calls, "a refused export must not have decoded a pixel of paper")
    }

    // ── fixtures ────────────────────────────────────────────────────────────────

    private val BOARD_ID = "b1"

    /**
     * One static MULTIPLY layer, opaque mid grey, on a board of [rect]. Grey at 128 is the value
     * that makes MULTIPLY a product readable by eye, and opaque is what makes the old paste-over
     * path return the layer itself — so this fixture is the one that separates the two.
     *
     * The board is built rather than taken from `DocOps.newDocument` so the CANVAS and ANIMATION
     * variants differ in exactly one field. The layer and its tile are the new document's, which is
     * what keeps `DocOps.validate` — the check every exporter makes at the door — passing.
     */
    private fun contents(rect: RectPx = RectPx(0, 0, 8, 4), animation: Boolean = false): JbContents {
        var id = 0
        val base = DocOps.newDocument("doc", "Blend", rect.w, rect.h) { "id${id++}" }
        val layer = base.layers.single()
        val doc = base.copy(
            paper = Paper("#204060"),
            boards = listOf(
                Board(
                    id = BOARD_ID,
                    name = "Board",
                    kind = if (animation) BoardKind.ANIMATION else BoardKind.CANVAS,
                    rect = rect,
                    fps = 12f,
                    frames = if (animation) listOf(Frame("f0", 1)) else emptyList(),
                ),
            ),
            layers = listOf(
                layer.copy(
                    blend = BlendMode.MULTIPLY,
                    cels = listOf(layer.cels.single().copy(tiles = listOf("0_0"))),
                ),
            ),
            activeBoardId = BOARD_ID,
        )
        val tile = ByteArray(RegionRenderer.TILE_BYTES)
        var i = 0
        while (i < tile.size) {
            tile[i] = 128.toByte(); tile[i + 1] = 128.toByte(); tile[i + 2] = 128.toByte(); tile[i + 3] = 255.toByte()
            i += 4
        }
        return JbContents(doc, mapOf(Triple(layer.id, layer.cels.single().id, "0_0") to tile), emptyMap())
    }

    /**
     * A paper in two flat halves, split at document x = 4.
     *
     * Flat rather than graded, for the reason `PaperBackdropTest` gives: a gradient would let an
     * off-by-one hide inside a tolerance, and every assertion in this file is an exact byte
     * comparison. The split is NOT on a block boundary, so a renderer that answered blocks in the
     * wrong order or the wrong place would still be caught here.
     */
    private fun paperOf(block: RectPx): ByteArray {
        val out = ByteArray(block.w * block.h * 4)
        var i = 0
        for (y in 0 until block.h) {
            for (x in 0 until block.w) {
                val east = block.x + x >= 4
                out[i] = (if (east) 20 else 200).toByte()
                out[i + 1] = (if (east) 80 else 40).toByte()
                out[i + 2] = (if (east) 220 else 60).toByte()
                out[i + 3] = 255.toByte()
                i += 4
            }
        }
        return out
    }

    private fun imageIn(archive: ByteArray, name: String): BufferedImage {
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: error("\"$name\" is not in the archive")
                if (entry.name != name) continue
                return ImageIO.read(ByteArrayInputStream(zip.readBytes()))
                    ?: error("\"$name\" is not a PNG ImageIO can read")
            }
        }
    }
}

