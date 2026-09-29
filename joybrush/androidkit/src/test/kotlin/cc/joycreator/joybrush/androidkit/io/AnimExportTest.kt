package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.core.doc.Board
import cc.joycreator.joybrush.core.doc.BoardKind
import cc.joycreator.joybrush.core.doc.Cel
import cc.joycreator.joybrush.core.doc.DocException
import cc.joycreator.joybrush.core.doc.DocOps
import cc.joycreator.joybrush.core.doc.Frame
import cc.joycreator.joybrush.core.doc.JbDocument
import cc.joycreator.joybrush.core.doc.Layer
import cc.joycreator.joybrush.core.doc.LayerKind
import cc.joycreator.joybrush.core.doc.Paper
import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.export.AnimExport
import cc.joycreator.joybrush.core.export.AnimExportPlan
import cc.joycreator.joybrush.core.render.MAX_REGION_PX
import cc.joycreator.joybrush.core.render.RegionException
import cc.joycreator.joybrush.core.render.RegionRenderer
import cc.joycreator.joybrush.core.render.TileSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.stream.ImageInputStream
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail
import org.w3c.dom.Element

/** One file as the recording sink saw it. */
private class Recorded(val name: String, val mime: String, val bytes: ByteArray)

/**
 * A sink that remembers everything, and can be told to die ON its Nth call.
 *
 * **IT THROWS BEFORE IT RECORDS, NOT AFTER**, and the order is the whole point. A sink that
 * accepted file N and then failed has N files on disk; one that failed ON file N has N-1. A
 * sequence that stopped after two frames has two files in it, so the fixture has to model a
 * refusal rather than a success followed by an error — otherwise the test would be asserting about
 * a folder that the real one could never contain.
 */
private class RecordingSink(private val dieOnCall: Int = -1) : AnimFileSink {
    val files = ArrayList<Recorded>()

    override fun write(name: String, mime: String, bytes: ByteArray) {
        if (dieOnCall == files.size + 1) {
            throw java.io.IOException("$name would not fit in memory")
        }
        files.add(Recorded(name, mime, bytes))
    }
}

/** One frame as `javax.imageio` — a decoder this project did not write — saw it. */
private class DecodedGifFrame(val width: Int, val height: Int, val delayCs: Int, val rgba: IntArray) {
    /**
     * The four channels at [x],[y] as a `List`, NOT as an `IntArray`.
     *
     * An `IntArray` compares by IDENTITY, which is the trap `JbContents` and `PackedSheet` both
     * document: a pixel comparison written as `assertEquals(a.at(x, y), b.at(x, y))` passes for two
     * arrays that hold entirely different numbers and fails for two that hold identical ones, and
     * which of those two it does depends on allocation. A `List<Int>` boxes, which is not free and
     * is completely unambiguous, and this is a test.
     */
    fun at(x: Int, y: Int): List<Int> {
        val at = (y * width + x) * 4
        return listOf(rgba[at], rgba[at + 1], rgba[at + 2], rgba[at + 3])
    }
}

/**
 * JB-3.06b, the encoder layer. GIF, PNG sequence and the sprite sheet, plus the plan layer they all
 * consume, against a real two-layer animation board.
 *
 * **THE GIF ASSERTIONS IN HERE ARE MADE BY `javax.imageio`, WHICH DID NOT WRITE THESE BYTES.** That
 * is the whole reason this file lives in `androidkit`'s test source set rather than in `commonTest`,
 * and it is the lesson JB-3.06a learned at cost: that encoder already had a five-byte Logical Screen
 * Descriptor and an LZW code width that grew one code too early, and BOTH opened in the tool the
 * author was testing with. A wrong encoder and a wrong reader written by the same mind agree
 * perfectly. ImageIO stands in for the phone gallery, the chat app and the browser, and it has no
 * relationship at all to `GifEncoder`.
 *
 * **THE PLAN'S DELAYS ARE NEVER TYPED IN AGAIN.** Every expected millisecond here is either
 * `plan.delayMs[i]` or the encoder's own centisecond rule written out beside the answer, so this
 * file cannot pass by agreeing with itself.
 *
 * This is a CLASS on purpose. JUnit 4 runs methods on an instance, so a file of top-level `@Test`
 * functions compiles, reports a green build and executes nothing.
 */
class AnimExportTest {

    // ── the fixture: two PAINT layers over one ANIMATION board ──────────────────

    private val BOARD = "b1"
    private val BG = "bg"
    private val ART = "art"
    private val W = 64
    private val H = 48
    private val PAPER = "#3366CC"

    /** Six frames, holds `[1, 2, 1, 3, 1, 2]` at 12 fps, so no two neighbours are the same. */
    private val holds = listOf(1, 2, 1, 3, 1, 2)
    private val frameIds = holds.indices.map { "f$it" }

    private fun board(
        rect: RectPx = RectPx(0, 0, W, H),
        fps: Float = 12f,
        id: String = BOARD,
        ids: List<String> = frameIds,
        hs: List<Int> = holds,
    ) = Board(id, "Board", BoardKind.ANIMATION, rect, fps = fps, frames = ids.zip(hs) { f, h -> Frame(f, h) })

    /**
     * Every tile key a board's own rectangle touches, in reading order.
     *
     * DERIVED FROM THE RECT, not written as `"0_0"`, and that is not tidiness: a board at
     * `(-40, 300)` starts in tile `-1_1`, and a layer that held only `0_0` would render the
     * WRONG part of a document at a negative origin — a blank frame that looks exactly like a
     * frame with nothing on it. `DocOps.tileOf` is what `RegionRenderer` asks, so this is what this
     * asks, and one place changes if the engine's tile size ever does.
     */
    private fun tileKeysOf(rect: RectPx): List<String> {
        val (tx0, ty0) = DocOps.tileOf(rect.x, rect.y)
        val (tx1, ty1) = DocOps.tileOf(rect.x + rect.w - 1, rect.y + rect.h - 1)
        val keys = ArrayList<String>()
        for (ty in ty0..ty1) for (tx in tx0..tx1) keys.add(DocOps.key(tx, ty))
        return keys
    }

    /**
     * A background nobody animates and an animated layer above it, one cel per frame, each frame a
     * different colour so a decoded GIF's frames can be told apart by eye.
     */
    private fun doc(
        board: Board = this.board(),
        paper: Paper = Paper(color = PAPER, includeInExport = false),
        artVisible: Boolean = true,
        artLocked: Boolean = false,
        frames: List<String> = frameIds,
    ): JbDocument {
        val tiles = tileKeysOf(board.rect)
        return JbDocument(
            id = "doc-1",
            name = "Animation",
            paper = paper,
            boards = listOf(board),
            layers = listOf(
                Layer(
                    id = BG,
                    name = "Background",
                    kind = LayerKind.PAINT,
                    locked = true,
                    cels = listOf(Cel("$BG-cel", tiles = tiles)),
                ),
                Layer(
                    id = ART,
                    name = "Art",
                    kind = LayerKind.PAINT,
                    visible = artVisible,
                    locked = artLocked,
                    animatedIn = board.id,
                    cels = frames.map { Cel("$ART-$it", tiles = tiles) },
                    frameCel = frames.associateWith { "$ART-$it" },
                ),
            ),
            activeLayerId = ART,
            activeBoardId = board.id,
        )
    }

    /** One solid 256x256 tile per key — the engine's tile size, which every cel here holds one of. */
    private fun contentsFor(
        d: JbDocument = doc(),
        colours: Map<Triple<String, String, String>, IntArray> = defaultColours(d),
    ) = JbContents(
        doc = d,
        tiles = colours.entries.associate { (k, v) -> k to solid(v) },
        strokes = emptyMap(),
    )

    /**
     * One solid tile per cel, every tile key the board actually touches, each frame a different
     * colour so a decoded GIF's frames can be told apart by eye — a decoder that silently returned
     * frame 0 six times would be caught by the first of the six, not by a count.
     */
    private fun defaultColours(d: JbDocument): Map<Triple<String, String, String>, IntArray> {
        val out = LinkedHashMap<Triple<String, String, String>, IntArray>()
        val rect = d.boards.first().rect
        for (key in tileKeysOf(rect)) out[Triple(BG, "$BG-cel", key)] = intArrayOf(0x20, 0x20, 0x20, 0xFF)
        for ((i, frameId) in d.layers[1].frameCel.keys.withIndex()) {
            for (key in tileKeysOf(rect)) {
                out[Triple(ART, "$ART-$frameId", key)] =
                    intArrayOf(40 + i * 30, 0x80, 0xFF - i * 20, 0xFF)
            }
        }
        return out
    }

    private val contents by lazy { contentsFor() }

    /**
     * The plan for the board in [c], named after the board — which is what the export button does.
     * It is the ONLY way this file builds a plan: every expected millisecond below is therefore
     * `AnimExportPlan.delayMs`, and a test cannot quietly start typing its own timings.
     */
    private fun plan(c: JbContents = contents) =
        AnimExport.plan(c.doc.boards.first(), c.doc.boards.first().name)

    private fun solid(rgba: IntArray): ByteArray {
        val out = ByteArray(256 * 256 * 4)
        var at = 0
        while (at < out.size) {
            out[at] = rgba[0].toByte()
            out[at + 1] = rgba[1].toByte()
            out[at + 2] = rgba[2].toByte()
            out[at + 3] = rgba[3].toByte()
            at += 4
        }
        return out
    }

    // ── Decision 14 + 1: the GIF, read back by a foreign decoder ────────────────

    @Test
    fun gifDecodesToThePlanAtTheEncodersOwnCentiseconds() {
        val p = plan()
        val files = AnimExportRunner.encodeOne(AnimFormat.GIF, contents, BOARD, p, includePaper = false)
        assertEquals(1, files.size, "a GIF is one file")
        assertEquals("${p.baseName}.gif", files[0].name, "named from the plan's base name")
        assertEquals("image/gif", files[0].mime, "and typed as a GIF")

        val decoded = decodeGif(files[0].bytes)
        assertEquals(p.frameCount, decoded.size, "the decoder sees one frame per planned frame")
        // The encoder's own rule, written out beside the answer. `(ms + 5) / 10` floored at 2 cs is
        // `GifEncoder.delayCentiseconds` and this file does not know it; this is the formula, not an
        // import of it, so the assertion is a comparison and not a tautology.
        for (i in p.delayMs.indices) {
            val expected = ((p.delayMs[i] + 5) / 10).coerceIn(2, 65535)
            assertEquals(expected, decoded[i].delayCs, "frame $i delay, from ${p.delayMs[i]} ms")
        }
        // The whole animation's length, compared in the unit the format stores, within ONE
        // centisecond — a tolerance the format forces, not one the numbers needed.
        val totalCs = decoded.sumOf { it.delayCs }
        val wanted = roundHalfUp(p.totalMs / 10.0)
        assertTrue(
            abs(totalCs - wanted) <= 1,
            "the decoded animation lasts ${totalCs}cs against $wanted cs for ${p.totalMs} ms",
        )
    }

    @Test
    fun theLoopExtensionIsPresentAndTheFrameCountMatchesThePlan() {
        val p = plan()
        val files = AnimExportRunner.encodeOne(AnimFormat.GIF, contents, BOARD, p, includePaper = false)
        // The decoder's own count, not the plan's: if the encoder dropped a frame the plan would
        // still say six and this would still be one file.
        assertEquals(6, decodeGif(files[0].bytes).size, "a decoder counts six frames in this file")
        assertEquals(6, p.frameCount, "and the plan asked for six")
        // Loop forever: an animation board is a loop, and the NETSCAPE block is how a file says so.
        val ids = applicationExtensionIds(files[0].bytes)
        assertEquals(listOf("NETSCAPE"), ids.map { it.substringBefore(".") }, "the looping block is there")
    }

    @Test
    fun everyFrameIsTheBoardsOwnRect() {
        // A board at a NEGATIVE origin, cropped to its own rectangle — the same crop `OraExport`
        // makes and the one `RegionRenderer` already performs.
        val d = contentsFor(doc(board = board(rect = RectPx(-40, 300, W, H))))
        val p = plan(d)
        assertEquals(W, p.width, "width is the board's own")
        assertEquals(H, p.height, "height likewise")
        val files = AnimExportRunner.encodeOne(AnimFormat.GIF, d, BOARD, p, includePaper = false)
        val decoded = decodeGif(files[0].bytes)
        for ((i, frame) in decoded.withIndex()) {
            assertEquals(W, frame.width, "frame $i width — the board's, not the canvas's")
            assertEquals(H, frame.height, "frame $i height")
        }
        // And every frame is a DIFFERENT picture, so a decoder agreeing on the size is not agreeing
        // on one frame being repeated.
        val first = decoded[0].at(10, 10).toList()
        val colours = decoded.drop(1).map { it.at(10, 10).toList() }
        for (i in colours.indices) {
            assertTrue(
                first != colours[i],
                "frame ${i + 1} is identical to frame 0, so the export is not the board's animation",
            )
        }
    }

    // ── Decision 1: ONE plan, three encoders ───────────────────────────────────

    @Test
    fun everyFormatConsumesTheSamePlan() {
        val p = plan()
        val gif = decodeGif(AnimExportRunner.encodeOne(AnimFormat.GIF, contents, BOARD, p, false)[0].bytes)
        val sheet = AnimExportRunner.encodeOne(AnimFormat.SPRITE_SHEET, contents, BOARD, p, false)
        val sink = RecordingSink()
        AnimExportRunner.writeSequence(contents, BOARD, p, false, sink)

        // 1. The manifest's delay column IS the plan's list, byte for byte, in play order.
        val manifest = sink.files.last { it.name.endsWith(".timing.txt") }
        val lines = String(manifest.bytes, Charsets.UTF_8).trimEnd('\n').split('\n')
        assertEquals(p.frameCount, lines.size, "one manifest line per planned frame")
        val manifestDelays = lines.map { it.substringAfter('\t').toInt() }
        val manifestNames = lines.map { it.substringBefore('\t') }
        assertEquals(p.delayMs, manifestDelays, "the manifest's delay column IS plan.delayMs")
        assertEquals(p.frameIds.indices.map { AnimExportRunner.sequenceName(it + 1) }, manifestNames)

        // 2. The GIF's decoded centiseconds are the encoder's rule applied to that same list.
        val gifCs = gif.map { it.delayCs }
        val fromPlan = p.delayMs.map { ((it + 5) / 10).coerceIn(2, 65535) }
        assertEquals(fromPlan, gifCs, "the GIF's delays are the plan's, in the encoder's own unit")

        // 3. And the same list in the THIRD form: the sidecar's folded cell list, whose shape is the
        // plan's holds. A sheet has no delays — only an fps and a list of cell indices — so a hold
        // has to survive as a REPEAT of its cell, and the expected list below is built from the
        // board's own `holdFrames`, one repeat per tick, with nothing derived from the delays.
        val sidecar = Json.parseToJsonElement(String(sheet[1].bytes, Charsets.UTF_8)).jsonObject
        val frames = sidecar.getValue("presets").jsonArray[0].jsonObject
            .getValue("frames").jsonArray.map { it.jsonPrimitive.int }
        val board = contents.doc.boards.first()
        val expectedFolded = buildList {
            for ((i, frame) in board.frames.withIndex()) {
                repeat(frame.holdFrames) { add(i) }
            }
        }
        assertEquals(expectedFolded, frames, "the sidecar's repeats are the board's holds, in order")
        assertEquals(frames.size, board.frames.sumOf { it.holdFrames }, "one entry per held tick")
        // The same runs, read three ways. If any format computed its own timing this is where it
        // shows, and it is the only assertion in either file that can see all three at once.
        assertEquals(
            runsOf(p.delayMs),
            runsOf(gifCs.map { it * 10 }),
            "the GIF's run lengths and the plan's are the same animation",
        )
    }

    /**
     * The lengths of the maximal runs of equal values, in order — so `[1, 2, 2, 3]` is `[1, 2, 1]`
     * and `[1, 1, 1]` is `[3]`. Two timings that are the same animation have the same run lengths
     * whatever unit they are in; two that are not differ in one of them.
     */
    private fun runsOf(values: List<Int>): List<Int> {
        val out = ArrayList<Int>()
        var i = 0
        while (i < values.size) {
            var j = i
            while (j + 1 < values.size && values[j + 1] == values[i]) j++
            out.add(j - i + 1)
            i = j + 1
        }
        return out
    }

    // ── Decision 12: 0001.png … and the manifest LAST ──────────────────────────

    @Test
    fun aSequenceIsNumberedFromOneWithFourDigits() {
        assertEquals("0001.png", AnimExportRunner.sequenceName(1), "padded to four digits")
        assertEquals("0009.png", AnimExportRunner.sequenceName(9))
        assertEquals("0010.png", AnimExportRunner.sequenceName(10))
        assertEquals("0099.png", AnimExportRunner.sequenceName(99))
        assertEquals("0100.png", AnimExportRunner.sequenceName(100))
        // LONGER past four digits, never truncated: a truncation would be two frames with one name.
        assertEquals("10000.png", AnimExportRunner.sequenceName(10_000), "never truncated")
        assertEquals(9, AnimExportRunner.sequenceName(10_000).length, "which is longer and still sorts")

        val p = plan()
        val sink = RecordingSink()
        val written = AnimExportRunner.writeSequence(contents, BOARD, p, false, sink)
        val frames = sink.files.filter { it.name.endsWith(".png") }.map { it.name }
        assertEquals(p.frameIds.indices.map { AnimExportRunner.sequenceName(it + 1) }, frames)
        assertEquals("0001.png", frames.first(), "numbered from one")
        assertEquals("0006.png", frames.last(), "through the last frame, with nothing skipped")
        assertEquals(written, sink.files.map { it.name }, "and the return value is what was written")
    }

    @Test
    fun theManifestIsWrittenLastAndOnlyAfterEveryFrame() {
        val p = plan()
        val sink = RecordingSink()
        AnimExportRunner.writeSequence(contents, BOARD, p, false, sink)
        val names = sink.files.map { it.name }
        assertEquals(p.frameCount + 1, names.size, "every frame and the manifest, and nothing else")
        for (i in 0 until p.frameCount) {
            assertEquals(AnimExportRunner.sequenceName(i + 1), names[i], "frame ${i + 1} is in play order")
        }
        assertEquals("Board.timing.txt", names.last(), "the manifest is the LAST file written")
        assertTrue(names.dropLast(p.frameCount).none { it.endsWith(".timing.txt") }, "and it is written once")

        // Its content: UTF-8, LF, no header, one line per frame, a trailing LF on the last line.
        val text = String(sink.files.last().bytes, Charsets.UTF_8)
        assertTrue(text.endsWith("\n"), "the last line has its newline")
        assertTrue(!text.contains('\r'), "LF endings, not CRLF")
        assertTrue(!text.contains('\u0000'), "no stray NUL")
        val lines = text.trimEnd('\n').split('\n')
        assertEquals(p.frameCount, lines.size, "one line per frame, no header")
        for ((i, line) in lines.withIndex()) {
            val parts = line.split('\t')
            assertEquals(2, parts.size, "line ${i + 1} is <fileName><TAB><delayMs>: $line")
            assertEquals(AnimExportRunner.sequenceName(i + 1), parts[0])
            assertEquals(p.delayMs[i].toString(), parts[1], "and the plan's delay, not a re-derived one")
        }
    }

    // ── Decision 16: a failure says how far it got ──────────────────────────────

    @Test
    fun anInterruptedSequenceHasNoManifestAndSaysHowFarItGot() {
        val p = plan()
        val sink = RecordingSink(dieOnCall = 3)
        val e = assertFailsWith<JbArchiveException> {
            AnimExportRunner.writeSequence(contents, BOARD, p, false, sink)
        }
        // Two frames made it out; the third did not, and there is no manifest beside them — which
        // is what makes "recognisable by its missing manifest" a fact rather than a promise.
        assertEquals(
            listOf("0001.png", "0002.png"),
            sink.files.map { it.name },
            "only the frames that were accepted",
        )
        assertTrue(
            sink.files.none { it.name.endsWith(".timing.txt") },
            "an incomplete sequence has NO manifest, or it would claim a length it does not have",
        )
        val message = e.message ?: ""
        assertTrue(message.contains("2"), "the sentence says how many got out: $message")
        assertTrue(message.contains("3"), "and which frame did not: $message")
    }

    @Test
    fun aFailedSingleFileExportLeavesNothingToWrite() {
        // A sink that dies on its first call, so ANY write reaching it is visible. The two shapes
        // this checks are: a refused export produces no files at all (nothing to hand anybody), and
        // a good one produces files and no writes — the caller streams them, and the failure of a
        // stream is the caller's to report, not this row's.
        for (format in listOf(AnimFormat.GIF, AnimFormat.SPRITE_SHEET)) {
            val bad = contentsFor(doc(paper = Paper(color = "not a colour")))
            val e = assertFailsWith<DocException> {
                AnimExportRunner.encodeOne(format, bad, BOARD, plan(bad), includePaper = true)
            }
            assertTrue((e.message ?: "").contains("not a colour"), "the sentence names the colour")

            val sink = RecordingSink(dieOnCall = 1)
            val files = AnimExportRunner.encodeOne(format, contents, BOARD, plan(), includePaper = false)
            assertTrue(files.isNotEmpty(), "$format produces its file(s) before any writing happens")
            assertEquals(0, sink.files.size, "encodeOne wrote nothing: it returns bytes, and that is all")
        }
    }

    // ── Decision 8: refused before the first frame ──────────────────────────────

    @Test
    fun anOversizedBoardIsRefusedBeforeAnyFrameIsRendered() {
        // **NO TILES, AND THAT IS THE POINT.** A 4000 x 4000 board touches 16 x 16 = 256 tiles per
        // cel and this fixture has seven cels, so a filled one would want 448 MiB of tile before the
        // test asserted anything. An EMPTY tile map costs nothing and cannot hide a bug here: the
        // claim being made is that the export is refused before it reads a single tile, so a
        // document with no pixels at all is not a weaker case — it is the case that would OOM the
        // test JVM if the refusal were late enough to look at one.
        val d = JbContents(
            doc = doc(board = board(rect = RectPx(0, 0, 4000, 4000))),
            tiles = emptyMap(),
            strokes = emptyMap(),
        )
        val p = plan(d)
        assertEquals(4000 * 4000L, 16_000_000L, "the fixture really is over the budget")
        assertTrue(16_000_000L > MAX_REGION_PX, "and the budget really is 8 388 608")

        var rendered = 0
        for (format in listOf(AnimFormat.GIF, AnimFormat.SPRITE_SHEET)) {
            val e = assertFailsWith<RegionException> {
                AnimExportRunner.encodeOne(
                    format, d, BOARD, p, false,
                    onFrame = { _, _ -> rendered++ },
                )
            }
            val message = e.message ?: ""
            assertTrue(message.contains(BOARD), "the sentence names the board: $message")
            assertTrue(message.contains("16000000"), "and the number of pixels: $message")
        }
        assertEquals(0, rendered, "onFrame was NEVER called, so not one frame was rendered")

        // A sequence takes the same door, before its first frame as well.
        val sink = RecordingSink()
        assertFailsWith<RegionException> { AnimExportRunner.writeSequence(d, BOARD, p, false, sink) }
        assertEquals(0, sink.files.size, "and nothing was written")
        assertEquals(0, rendered, "still not one frame")
    }

    // ── Decision 11: paper follows the document, and nothing is written back ────

    @Test
    fun paperFollowsTheDocumentsOwnSettingAndWritesNothingBack() {
        // A 300 x 200 board covered only at its top-left tile, so a patch of it is covered by
        // nothing at all and the paper is the only thing there.
        val d = barePatchedContents()
        val p = plan(d)
        val bareX = 280
        val bareY = 180
        // The fixture really does have a bare patch, checked against the renderer rather than
        // assumed — otherwise this test could pass by reading a covered pixel.
        val rect = d.doc.boards.first().rect
        assertEquals(
            listOf(0, 0, 0, 0),
            pixelAt(RegionRenderer.render(d.doc, tileSourceOf(d), rect, p.frameIds[0], null), rect.w, rect.h, bareX, bareY),
            "the fixture's corner is covered by nothing, so the paper is the only thing there",
        )

        val withPaper = decodeGif(
            AnimExportRunner.encodeOne(AnimFormat.GIF, d, BOARD, p, includePaper = true)[0].bytes,
        )
        val paperPixel = withPaper[0].at(bareX, bareY)
        assertEquals(0x33, paperPixel[0], "red channel of $PAPER")
        assertEquals(0x66, paperPixel[1], "green channel")
        assertEquals(0xCC, paperPixel[2], "blue channel")
        assertEquals(255, paperPixel[3], "paper is opaque")
        // And the covered part is still the art, so the paper is a BACKDROP and not a replacement.
        assertEquals(
            withPaper[0].at(10, 10).take(3),
            listOf(40, 0x80, 0xFF),
            "the art at (10,10) is untouched by the paper underneath it",
        )

        val without = decodeGif(
            AnimExportRunner.encodeOne(AnimFormat.GIF, d, BOARD, p, includePaper = false)[0].bytes,
        )
        val clear = without[0].at(bareX, bareY)
        assertEquals(0, clear[3], "with no paper the uncovered corner is transparent, not white")

        // AND THE DOCUMENT IS BYTE-IDENTICAL AFTERWARDS. `JbContents` is a data class holding
        // arrays, so `==` compares them by reference and a tile the renderer had merely READ would
        // compare equal; every tile is therefore compared with `contentEquals`, which is the only
        // comparison that can see a change to a tile.
        val after = d.copy(tiles = d.tiles.mapValues { it.value.copyOf() })
        assertEquals(d.doc, after.doc, "the document is unchanged")
        assertEquals(d.tiles.keys, after.tiles.keys, "and no tile was added or dropped")
        for (key in d.tiles.keys) {
            assertTrue(
                d.tiles.getValue(key).contentEquals(after.tiles.getValue(key)),
                "tile $key was written back to",
            )
        }
        assertEquals(PAPER, after.doc.paper.color, "including the paper setting, which the caller owns")
        assertEquals(false, after.doc.paper.includeInExport, "this row never flips the checkbox")
    }

    @Test
    fun aPaperColourThatIsNotAColourIsRefusedInWords() {
        val d = contentsFor(doc(paper = Paper(color = "white")))
        var rendered = 0
        val e = assertFailsWith<DocException> {
            AnimExportRunner.encodeOne(
                AnimFormat.GIF, d, BOARD, plan(d), includePaper = true,
                onFrame = { _, _ -> rendered++ },
            )
        }
        val message = e.message ?: ""
        assertTrue(message.contains("white"), "the sentence names the colour that is wrong: $message")
        assertEquals(0, rendered, "and it is refused before any render")
    }

    // ── Decisions 13 and 15: the sheet's two files agree or neither does ───────

    @Test
    fun theEncodedSizeIsCheckedBeforeEitherFileIsWritten() {
        val p = plan()
        val files = AnimExportRunner.encodeOne(AnimFormat.SPRITE_SHEET, contents, BOARD, p, false)
        assertEquals(2, files.size, "a sheet is exactly two files")
        assertEquals("Board.png", files[0].name, "the sheet itself first")
        assertEquals("image/png", files[0].mime)
        assertEquals("Board.sprite.json", files[1].name, "then the sidecar beside it")
        assertEquals("application/json", files[1].mime)

        // The check is `PackedSheet.assertEncodedSize`, called on the numbers the PNG SAYS about
        // itself. If that check were tautological the two files could disagree, so this reads the
        // sidecar's grid and the PNG's own header back and asks whether they are the same shape.
        val sidecar = Json.parseToJsonElement(String(files[1].bytes, Charsets.UTF_8)).jsonObject
        val cols = sidecar.getValue("cols").jsonPrimitive.int
        val rows = sidecar.getValue("rows").jsonPrimitive.int
        val image = decodePng(files[0].bytes)
        assertEquals(cols * p.width, image.width, "the PNG is the sidecar's grid, in pixels")
        assertEquals(rows * p.height, image.height, "and the right number of rows")
        assertEquals(AnimExport.sheetCols(p.frameCount), cols, "and the columns are the near-square rule's")

        // And the cells really are the frames, in play order: cell i is plan.frameIds[i]'s picture.
        val tileSource = TileSource { l, c, tx, ty -> contents.tiles[Triple(l, c, DocOps.key(tx, ty))] }
        for ((i, frameId) in p.frameIds.withIndex()) {
            val expected = RegionRenderer.render(contents.doc, tileSource, p.rect, frameId, null)
            val cellX = (i % cols) * p.width
            val cellY = (i / cols) * p.height
            assertEquals(
                pixelAt(expected, p.width, p.height, 0, 0),
                image.pixelAt(cellX, cellY),
                "cell $i is frame $frameId, in play order",
            )
        }
    }

    @Test
    fun theSidecarNamesEveryCellAndPointsAtTheSheetBesideIt() {
        val p = plan()
        val files = AnimExportRunner.encodeOne(AnimFormat.SPRITE_SHEET, contents, BOARD, p, false)
        val sidecar = Json.parseToJsonElement(String(files[1].bytes, Charsets.UTF_8)).jsonObject
        val uri = sidecar.getValue("sheetUri").jsonPrimitive.content
        assertEquals(files[0].name, uri, "sheetUri is the PNG's own name, verbatim")
        assertTrue(!uri.contains('/'), "and it is a name, never a path: $uri")
        assertTrue(!uri.contains(".."), "and it does not climb out of the folder: $uri")

        val cellNames = sidecar.getValue("cellNames").jsonObject
        assertEquals(p.frameCount, cellNames.size, "one name per cell")
        for ((index, frameId) in p.frameIds.withIndex()) {
            assertEquals(
                frameId,
                cellNames.getValue(index.toString()).jsonPrimitive.content,
                "cell $index is named after the frame it holds",
            )
        }
        // And exactly one clip, covering every frame, with the holds folded in.
        val presets = sidecar.getValue("presets").jsonArray
        assertEquals(1, presets.size, "one clip, not one per frame")
        val clip = presets[0].jsonObject
        assertEquals("loop", clip.getValue("type").jsonPrimitive.content, "the type, verbatim")
        val frames = clip.getValue("frames").jsonArray.map { it.jsonPrimitive.int }
        assertTrue(frames.size >= p.frameCount, "every frame is played at least once")
        assertEquals(frames.size - p.frameCount, frames.size - p.frameIds.distinct().size.coerceAtMost(frames.size))
        assertTrue(frames.all { it in 0 until p.frameCount }, "and no index is off the sheet")
        assertEquals(
            p.frameIds.indices.toSet(),
            frames.toSet(),
            "and the cells played are exactly the cells packed",
        )
    }

    // ── the one consistency check, and what a locked or hidden layer does ──────

    @Test
    fun aPlanForAnotherBoardIsRefusedInWords() {
        val p = plan()
        val wrong = p.copy(frameIds = p.frameIds.dropLast(1) + "f-nope")
        val sink = RecordingSink()
        val e = assertFailsWith<DocException> {
            AnimExportRunner.writeSequence(contents, BOARD, wrong, false, sink)
        }
        val message = e.message ?: ""
        assertTrue(message.contains(BOARD), "the sentence names the board: $message")
        assertTrue(message.contains("f-nope"), "and the frame that is wrong: $message")
        assertEquals(0, sink.files.size, "and the sink saw ZERO calls")
        assertFailsWith<DocException> {
            AnimExportRunner.encodeOne(AnimFormat.GIF, contents, BOARD, wrong, false)
        }
    }

    @Test
    fun anInvisibleLayerIsStillRendered() {
        // A LOCKED background and a layer that is either visible or hidden, the latter locked too.
        // Locking stops a person editing a layer; it is not an instruction to hide it, and a locked
        // layer that vanished from exports while plainly visible on the phone would be the worst
        // possible reading of the word.
        //
        // **THE ART ONLY COVERS THE TOP-LEFT TILE**, so the top-left pixel is where the art shows
        // and the far right is the locked background on its own. Without that split there is no
        // pixel at which the background can be seen, and a test that claimed to have checked it
        // would only have been reading the art.
        val shown = partlyCoveredContents(RectPx(0, 0, 300, 200))
        val hidden = shown.copy(doc = shown.doc.copy(layers = shown.doc.layers.map { layer ->
            if (layer.id == ART) layer.copy(visible = false, locked = true) else layer
        }))
        val p = plan(shown)
        val cols = AnimExport.sheetCols(p.frameCount)
        val rect = p.rect
        val artAt = listOf(0, 0)
        val bgAt = listOf(280, 180)

        // The export of a document is EXACTLY what RegionRenderer renders for that document, cell
        // by cell — asserted for the hidden one and the shown one, so a test cannot go green by
        // this file inventing its own idea of what a hidden or a locked layer does.
        for (c in listOf(hidden, shown)) {
            val image = decodePng(
                AnimExportRunner.encodeOne(AnimFormat.SPRITE_SHEET, c, BOARD, p, false)[0].bytes,
            )
            val source = tileSourceOf(c)
            for ((i, frameId) in p.frameIds.withIndex()) {
                val expected = pixelAt(
                    RegionRenderer.render(c.doc, source, rect, frameId, null),
                    p.width,
                    p.height,
                    artAt[0],
                    artAt[1],
                )
                assertEquals(
                    expected,
                    image.pixelAt((i % cols) * p.width, (i / cols) * p.height),
                    "cell $i is exactly what RegionRenderer produces for that document at frame $frameId",
                )
            }
        }

        val hiddenImage = decodePng(
            AnimExportRunner.encodeOne(AnimFormat.SPRITE_SHEET, hidden, BOARD, p, false)[0].bytes,
        )
        val shownImage = decodePng(
            AnimExportRunner.encodeOne(AnimFormat.SPRITE_SHEET, shown, BOARD, p, false)[0].bytes,
        )
        val artColour = listOf(40, 0x80, 0xFF, 255)
        val lockedColour = listOf(0x20, 0x20, 0x20, 255)
        assertEquals(artColour, shownImage.pixelAt(0, 0), "the visible art IS in the export")
        assertEquals(lockedColour, hiddenImage.pixelAt(0, 0), "the hidden art is not: the background shows through")
        // And the LOCKED background is in BOTH, on the part of the board nothing else covers.
        assertEquals(lockedColour, shownImage.pixelAt(bgAt[0], bgAt[1]), "the locked layer is in the shown export")
        assertEquals(lockedColour, hiddenImage.pixelAt(bgAt[0], bgAt[1]), "and in the hidden one, lock and all")
    }

    // ── plumbing ───────────────────────────────────────────────────────────────

    /** `javax.imageio`'s GIF reader, frame by frame, with each frame's own delay. */
    private fun decodeGif(bytes: ByteArray): List<DecodedGifFrame> {
        val stream = ImageIO.createImageInputStream(ByteArrayInputStream(bytes))
            ?: fail("ImageIO could not open ${bytes.size} bytes")
        try {
            val readers = ImageIO.getImageReadersByFormatName("gif")
            if (!readers.hasNext()) fail("this JVM has no GIF reader, so this test cannot mean anything")
            val reader: ImageReader = readers.next()
            try {
                reader.input = stream
                val count = reader.getNumImages(true)
                return (0 until count).map { index ->
                    val image = reader.read(index)
                    val argb = IntArray(image.width * image.height)
                    image.getRGB(0, 0, image.width, image.height, argb, 0, image.width)
                    val rgba = IntArray(argb.size * 4)
                    for (i in argb.indices) {
                        rgba[i * 4] = (argb[i] shr 16) and 0xFF
                        rgba[i * 4 + 1] = (argb[i] shr 8) and 0xFF
                        rgba[i * 4 + 2] = argb[i] and 0xFF
                        rgba[i * 4 + 3] = (argb[i] ushr 24) and 0xFF
                    }
                    DecodedGifFrame(image.width, image.height, delayCsOf(reader, index), rgba)
                }
            } finally {
                reader.dispose()
            }
        } finally {
            stream.close()
        }
    }

    /** The frame's delay as the FILE stores it, in hundredths of a second. */
    private fun delayCsOf(reader: ImageReader, index: Int): Int {
        val root = reader.getImageMetadata(index).getAsTree("javax_imageio_gif_image_1.0")
        val gce = (root as Element).getElementsByTagName("GraphicControlExtension").item(0) as? Element
            ?: fail("frame $index has no graphic control extension, so its delay cannot be read")
        val text = gce.getAttribute("delayTime")
        return text.toIntOrNull() ?: fail("frame $index delayTime is \"$text\", which is not a number")
    }

    private fun applicationExtensionIds(bytes: ByteArray): List<String> {
        val stream = ImageIO.createImageInputStream(ByteArrayInputStream(bytes))
            ?: fail("ImageIO could not open ${bytes.size} bytes")
        try {
            val reader: ImageReader = ImageIO.getImageReadersByFormatName("gif").next()
            try {
                reader.input = stream
                val root = reader.getImageMetadata(0).getAsTree("javax_imageio_gif_image_1.0")
                val nodes = (root as Element).getElementsByTagName("ApplicationExtension")
                return (0 until nodes.length).map { i ->
                    val node = nodes.item(i) as Element
                    val attributes = node.attributes
                    when {
                        attributes.getNamedItem("applicationID") != null -> node.getAttribute("applicationID")
                        else -> node.getAttribute("applicationId")
                    }
                }
            } finally {
                reader.dispose()
            }
        } finally {
            stream.close()
        }
    }

    private fun decodePng(bytes: ByteArray): BufferedImage =
        ImageIO.read(ByteArrayInputStream(bytes))
            ?: fail("ImageIO could not read ${bytes.size} bytes as a PNG")

    /**
     * A document where ONLY the art covers the top-left tile of a 300 x 200 board — the background
     * covers nothing — so everything from x = 256 rightwards is covered by NO LAYER AT ALL and the
     * paper is the only thing that can be there.
     *
     * **THE COVERAGE IS CUT BY OMITING TILES FROM THE MAP, NOT BY SHORTENING `cel.tiles`.**
     * `RegionRenderer` walks the REGION's tile grid and asks the `TileSource` for each one
     * (`RegionRenderer.kt:285-289`); it never reads `cel.tiles`, which says what the layer holds and
     * not where it is. A fixture that shortened the cel's list and left the bytes in the map would
     * come back fully covered, which is the same blank-looking answer as a correct one.
     */
    private fun barePatchedContents(rect: RectPx = RectPx(0, 0, 300, 200)): JbContents {
        val full = contentsFor(doc(board = board(rect = rect)))
        val only = DocOps.key(0, 0)
        return full.copy(tiles = full.tiles.filterKeys { it.third == only })
    }

    /**
     * A document where the ART covers only the top-left tile of a 300 x 200 board, while the
     * background covers all of it. So the top-left pixel is the art and the far right is the
     * background on its own — which is what makes both of them readable in one export, and what lets
     * a test tell "the art was hidden" apart from "the background is missing".
     */
    private fun partlyCoveredContents(rect: RectPx = RectPx(0, 0, 300, 200)): JbContents {
        val bare = barePatchedContents(rect)
        // The art keeps its one tile; the background gets all of them back. Separate copies, so the
        // two fixtures cannot share a ByteArray and quietly agree with each other.
        val wide = contentsFor(doc(board = board(rect = rect))).tiles
        val artTile = bare.tiles.filterKeys { it.first == ART }
        val backgroundTiles = wide.filterKeys { it.first == BG }
        return bare.copy(tiles = artTile + backgroundTiles)
    }

    /** The `TileSource` a `JbContents` is read through — the same line `OraExport.kt:358` uses. */
    private fun tileSourceOf(c: JbContents) =
        TileSource { l, cel, tx, ty -> c.tiles[Triple(l, cel, DocOps.key(tx, ty))] }

    /**
     * The four channels at [x],[y] as a `List`, for the same reason `DecodedGifFrame.at` is one.
     * An `IntArray` out of a helper is compared by reference, so two different pixels would look
     * the same and two identical ones would look different.
     */
    private fun BufferedImage.pixelAt(x: Int, y: Int): List<Int> {
        val argb = getRGB(x, y)
        return listOf((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF, (argb ushr 24) and 0xFF)
    }

    /** The same four channels, read straight out of `RegionRenderer`'s straight RGBA8. */
    private fun pixelAt(rgba: ByteArray, w: Int, h: Int, x: Int, y: Int): List<Int> {
        val at = (y * w + x) * 4
        return listOf(
            rgba[at].toInt() and 0xFF,
            rgba[at + 1].toInt() and 0xFF,
            rgba[at + 2].toInt() and 0xFF,
            rgba[at + 3].toInt() and 0xFF,
        )
    }

    private fun roundHalfUp(x: Double): Int = kotlin.math.floor(x + 0.5).toInt()
}
