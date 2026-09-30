package cc.joycreator.joybrush.androidkit.io

import cc.joycreator.joybrush.core.doc.BlendMode
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
import cc.joycreator.joybrush.core.render.Blend
import cc.joycreator.joybrush.core.render.RegionRenderer
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import javax.imageio.ImageIO
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * JB-2.14b. The spec's five checks, and then every attempt I could think of to make an `.ora` lose
 * somebody's drawing or make a desktop app refuse it: an empty stack, a layer nobody has drawn on, a
 * hidden layer, a layer at 0%, an INK layer, an animated layer asked for a frame it does not have, a
 * name with an XML metacharacter in it, a name with a character XML forbids in it, a name with two
 * hyphens in it, a board nowhere near the origin, a paper colour that is not a colour, and a stream
 * that dies half way out.
 *
 * THE LOAD-BEARING ONE is [theLayersAndTheXmlReproduceTheMergedImage]. Every other test here checks
 * that the file is well formed; that one checks that the file says the same thing twice and means
 * it, which is the difference between a file Krita opens and a file it opens and shows the wrong
 * thing in.
 *
 * This is a CLASS on purpose. JUnit 4 runs methods on an instance, so a file of top-level `@Test`
 * functions compiles, reports a green build and executes nothing.
 */
class OraExportTest {

    // ── the two-layer fixture, exactly as the spec describes it ───────────────────

    private val BOARD = "b1"
    private val BG = "bg"
    private val GLOSS = "gloss"
    private val W = 300
    private val H = 200

    /**
     * Board at the origin, `Background` underneath and `Gloss` on top at half opacity with MULTIPLY.
     *
     * Tiles 0_0 and 1_0 exist, and the board is 300 wide, so every pixel of it is covered. A board
     * that ISN'T fully covered is [wideDoc] below, and the two together are what cover the sparse
     * case and the full case without either of them being an accident.
     *
     * The one hand-worked pixel, at (0,0), where both layers are opaque:
     *
     *   Background over nothing : 128/255 grey at a = 1     -> (0.50196, 0.50196, 0.50196, 1)
     *   Gloss s = white x 0.5   : sa = 0.5, Cs = (1,1,1), Cb = 0.50196, B = Cs*Cb = 0.50196
     *   co = 0.5(1-1)(1) + 0.5 x 1 x 0.50196 + 0.5 x 1 x 0.50196 = 0.50196, a = 1
     *
     * so the byte is (128, 128, 128, 255): half-opaque white multiplying to the backdrop unchanged.
     * That is the same claim `RegionRendererTest.aWhiteLayerAtHalfOpacityMultipliesToTheBackdropUnchanged`
     * makes, reached here through a file rather than through the renderer.
     */
    private fun doc(
        boardRect: RectPx = RectPx(0, 0, W, H),
        boards: List<Board> = listOf(Board(BOARD, "Board 1", BoardKind.CANVAS, boardRect)),
        layers: List<Layer> = listOf(background(), gloss()),
        paper: Paper = Paper(color = "#FFFFFF"),
    ) = JbDocument(
        id = "doc-1",
        name = "Two layers",
        paper = paper,
        boards = boards,
        layers = layers,
        activeLayerId = BG,
        activeBoardId = BOARD,
    )

    private fun background(
        id: String = BG,
        name: String = "Background",
        tiles: List<String> = listOf("0_0", "1_0"),
        visible: Boolean = true,
        opacity: Float = 1f,
        blend: BlendMode = BlendMode.NORMAL,
    ) = Layer(
        id = id,
        name = name,
        kind = LayerKind.PAINT,
        visible = visible,
        opacity = opacity,
        blend = blend,
        cels = listOf(Cel(id = "$id-cel", tiles = tiles)),
    )

    private fun gloss(tiles: List<String> = listOf("0_0", "1_0")) = Layer(
        id = GLOSS,
        name = "Gloss",
        kind = LayerKind.PAINT,
        opacity = 0.5f,
        blend = BlendMode.MULTIPLY,
        cels = listOf(Cel(id = "$GLOSS-cel", tiles = tiles)),
    )

    private fun ink(id: String = "ink", name: String = "Ink line") = Layer(
        id = id,
        name = name,
        kind = LayerKind.INK,
        cels = listOf(Cel(id = "$id-cel")),
    )

    private fun contents(d: JbDocument = doc()): JbContents = JbContents(
        doc = d,
        tiles = mapOf(
            Triple(BG, "$BG-cel", "0_0") to solid(128, 128, 128, 255),
            Triple(BG, "$BG-cel", "1_0") to solid(128, 128, 128, 255),
            Triple(GLOSS, "$GLOSS-cel", "0_0") to solid(255, 255, 255, 255),
            Triple(GLOSS, "$GLOSS-cel", "1_0") to solid(255, 255, 255, 255),
        ),
        strokes = emptyMap(),
    )

    // ── the exact stack.xml, worked out by hand ─────────────────────────────────

    /**
     * Byte for byte, the `stack.xml` the fixture above must produce.
     *
     * BUILT WITH `append` RATHER THAN A RAW STRING ON PURPOSE. This assertion is about exact bytes —
     * two spaces of indent, one trailing newline, `%.3f` on the opacity — and `trimIndent` has
     * opinions about leading and trailing blank lines that are a poor thing for an exactness test to
     * depend on. It is also spelled out so that a change to the writer and a change to the test
     * cannot both be "obviously the same change".
     *
     * The order is TOP FIRST, so Gloss is the first line, while the `data/<n>.png` numbers still
     * ascend with the Joy Brush stack. The numbers in the file therefore run downwards while the
     * lines run upwards, and `data/0.png` is the BOTTOM layer — which is what a person unzipping the
     * file is looking for.
     */
    private fun expectedStack(): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<image version=\"0.0.3\" w=\"$W\" h=\"$H\">\n")
        append("  <stack>\n")
        append("    <layer name=\"Gloss\" src=\"data/1.png\" x=\"0\" y=\"0\" opacity=\"0.500\" visibility=\"visible\" composite-op=\"svg:multiply\"/>\n")
        append("    <layer name=\"Background\" src=\"data/0.png\" x=\"0\" y=\"0\" opacity=\"1.000\" visibility=\"visible\" composite-op=\"svg:src-over\"/>\n")
        append("  </stack>\n")
        append("</image>\n")
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private fun encoded(
        c: JbContents = contents(),
        boardId: String = BOARD,
        frameId: String? = null,
        includePaper: Boolean = false,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        OraExport.write(out, c, boardId, frameId, includePaper)
        return out.toByteArray()
    }

    /** name, method and bytes for every entry, in the order the zip holds them. */
    private fun entriesOf(archive: ByteArray): List<Triple<String, Int, ByteArray>> {
        val out = ArrayList<Triple<String, Int, ByteArray>>()
        val zis = ZipInputStream(ByteArrayInputStream(archive))
        while (true) {
            val entry = zis.nextEntry ?: break
            out.add(Triple(entry.name, entry.method, zis.readBytes()))
            zis.closeEntry()
        }
        return out
    }

    private fun namesOf(archive: ByteArray): List<String> = entriesOf(archive).map { it.first }

    private fun entry(archive: ByteArray, name: String): ByteArray =
        assertNotNull(entriesOf(archive).firstOrNull { it.first == name }, "no entry \"$name\" in the .ora").third

    private fun stackXml(archive: ByteArray): String = entry(archive, "stack.xml").decodeToString()

    private fun image(archive: ByteArray, name: String): BufferedImage =
        assertNotNull(ImageIO.read(ByteArrayInputStream(entry(archive, name))), "\"$name\" is not a PNG ImageIO can read")

    /** Straight RGBA bytes of a decoded PNG, row 0 at the top. Fails loudly if the bands are not R,G,B,A. */
    private fun rgbaOf(img: BufferedImage): ByteArray {
        val raster = img.raster
        assertEquals(4, raster.numBands, "the PNG is not RGBA, so the band order is not the one this file assumes")
        val out = ByteArray(img.width * img.height * 4)
        var at = 0
        for (y in 0 until img.height) {
            for (x in 0 until img.width) {
                for (b in 0 until 4) out[at + b] = raster.getSample(x, y, b).toByte()
                at += 4
            }
        }
        return out
    }

    /** One pixel as r, g, b, a. */
    private fun pixelAt(img: BufferedImage, x: Int, y: Int): List<Int> {
        val raster = img.raster
        return (0..3).map { raster.getSample(x, y, it) }
    }

    /** The `<stack>` children in document order, as name, src, x, y, opacity, visibility, composite-op. */
    private fun layersOf(xml: String): List<List<String>> {
        val stack = parse(xml).getElementsByTagName("stack").item(0) ?: return emptyList()
        val out = ArrayList<List<String>>()
        val kids = stack.childNodes
        for (i in 0 until kids.length) {
            val node = kids.item(i)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            val e = node as Element
            out.add(
                listOf(
                    e.getAttribute("name"),
                    e.getAttribute("src"),
                    e.getAttribute("x"),
                    e.getAttribute("y"),
                    e.getAttribute("opacity"),
                    e.getAttribute("visibility"),
                    e.getAttribute("composite-op"),
                ),
            )
        }
        return out
    }

    private fun commentsOf(xml: String): List<String> {
        val out = ArrayList<String>()
        val kids = parse(xml).childNodes
        for (i in 0 until kids.length) collectComments(kids.item(i), out)
        return out
    }

    private fun collectComments(node: Node, into: MutableList<String>) {
        if (node.nodeType == Node.COMMENT_NODE) into.add(node.nodeValue.orEmpty().trim())
        val kids = node.childNodes
        for (i in 0 until kids.length) collectComments(kids.item(i), into)
    }

    private fun parse(xml: String) = documentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))

    private fun documentBuilder() = DocumentBuilderFactory.newInstance().apply {
        // The file is generated by this very file, so a DOCTYPE in it would mean we wrote one. Turning
        // the feature on is what turns that from a mystery into a red test rather than an XXE.
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }.newDocumentBuilder()

    private fun refusal(
        c: JbContents = contents(),
        boardId: String = BOARD,
        includePaper: Boolean = false,
    ): String = assertFailsWith<JbArchiveException> { encoded(c, boardId, null, includePaper) }.message.orEmpty()

    private fun solid(r: Int, g: Int, b: Int, a: Int): ByteArray {
        val t = ByteArray(RegionRenderer.TILE_BYTES)
        var i = 0
        while (i < t.size) {
            t[i] = r.toByte()
            t[i + 1] = g.toByte()
            t[i + 2] = b.toByte()
            t[i + 3] = a.toByte()
            i += 4
        }
        return t
    }

    /**
     * A board twice as wide, so its right-hand 88 columns (512 to 599) are in tile 2_0, which the
     * document does not have and `JbContents` therefore does not hold.
     */
    private fun wideDoc(): JbDocument = doc(boardRect = RectPx(0, 0, 600, H))

    // ── 1. entry order and the STORED mimetype ──────────────────────────────────

    @Test
    fun theEntriesAreInTheOrderTheFormatAsksFor() {
        assertEquals(
            listOf(
                "mimetype",
                "stack.xml",
                "data/0.png",
                "data/1.png",
                "data/2.png",
                "Thumbnails/thumbnail.png",
                "mergedimage.png",
            ),
            namesOf(encoded(includePaper = true)),
        )
    }

    @Test
    fun theMimetypeIsTheFirstEntryAndStored() {
        val zis = ZipInputStream(ByteArrayInputStream(encoded()))
        val first = assertNotNull(zis.nextEntry)
        assertEquals("mimetype", first.name)
        assertEquals(ZipEntry.STORED, first.method)
        // Counted from the constant, never by hand: the content assertion below is the real one.
        assertEquals(ORA_MIMETYPE.length.toLong(), first.size)
        assertEquals(ORA_MIMETYPE, zis.readBytes().decodeToString())
    }

    // ── 2. stack.xml parses, top first, right ops and opacity ──────────────────

    @Test
    fun theStackXmlIsExactlyWhatWasWorkedOutByHand() {
        assertEquals(expectedStack(), stackXml(encoded()))
    }

    @Test
    fun theStackXmlParsesAndListsTheLayersTopFirst() {
        val root = parse(stackXml(encoded())).documentElement
        assertEquals("image", root.nodeName)
        assertEquals("0.0.3", root.getAttribute("version"))
        assertEquals("$W", root.getAttribute("w"))
        assertEquals("$H", root.getAttribute("h"))
        assertEquals("stack", parse(stackXml(encoded())).getElementsByTagName("stack").item(0).nodeName)

        val layers = layersOf(stackXml(encoded()))
        assertEquals(2, layers.size)
        // TOP FIRST: Gloss is above Background in JbDocument.layers, so it is the first line here.
        assertEquals(listOf("Gloss", "data/1.png", "0", "0", "0.500", "visible", "svg:multiply"), layers[0])
        assertEquals(listOf("Background", "data/0.png", "0", "0", "1.000", "visible", "svg:src-over"), layers[1])
    }

    @Test
    fun theOpacityIsWrittenInTheCLocaleEvenOnADeviceThatThinksOtherwise() {
        // THE NEGATIVE CONTROL. `String.format` with no locale uses the device's, and a phone set to
        // German writes "0,500" — which is not a number to anything reading the XML, so the file stops
        // opening on exactly the devices whose owner most deserves to open it. Setting the locale is
        // what gives this test teeth: without it, the assertion passes on every machine in the world
        // and would go on passing if the Locale.ROOT were deleted.
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            for (archive in listOf(encoded(), encoded(includePaper = true))) {
                val xml = stackXml(archive)
                assertTrue(xml.contains("opacity=\"0.500\""), "the half-opaque layer: $xml")
                assertFalse(xml.contains("0,500"), "a locale leaked a decimal comma: $xml")
            }
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test
    fun everyBlendModeBecomesItsSvgOperator() {
        val expected = mapOf(
            BlendMode.NORMAL to "svg:src-over",
            BlendMode.MULTIPLY to "svg:multiply",
            BlendMode.SCREEN to "svg:screen",
            BlendMode.OVERLAY to "svg:overlay",
            BlendMode.ADD to "svg:plus",
            BlendMode.DARKEN to "svg:darken",
            BlendMode.LIGHTEN to "svg:lighten",
            // Destination-out, and not src-over. An erase written as src-over would put the eraser
            // layer's own pixels into the file as though they were paint.
            BlendMode.ERASE_BELOW to "svg:dst-out",
            // The Studio's modes that SVG's feBlend also names, so these are EXACT mappings rather
            // than approximations. Named here explicitly for the same reason the arithmetic side is:
            // a mode missing from this table must be a compile error, never a silent src-over.
            BlendMode.DIFFERENCE to "svg:difference",
            BlendMode.EXCLUSION to "svg:exclusion",
            BlendMode.COLOR_DODGE to "svg:color-dodge",
            BlendMode.COLOR_BURN to "svg:color-burn",
            BlendMode.HARD_LIGHT to "svg:hard-light",
            BlendMode.SOFT_LIGHT to "svg:soft-light",
            BlendMode.HUE to "svg:hue",
            BlendMode.SATURATION to "svg:saturation",
            BlendMode.COLOR to "svg:color",
            BlendMode.LUMINOSITY to "svg:luminosity",
        )
        assertEquals(
            BlendMode.entries.toSet(),
            expected.keys.toSet() + unexpressibleBlendModes(),
            "every mode is either mapped exactly or refused by name - never approximated",
        )
        for (mode in expected.keys) {
            val d = doc(layers = listOf(background(blend = mode), gloss()))
            val layers = layersOf(stackXml(encoded(contents(d))))
            assertEquals(2, layers.size, "$mode")
            assertEquals(expected.getValue(mode), layers[1][6], "$mode's composite-op")
            assertEquals("visible", layers[1][5], "$mode is written visible, because only visible layers are written")
        }
    }

    /**
     * The nine modes SVG has no operator for must stop the export IN WORDS. This is the test that
     * makes the refusal a promise rather than a comment: the failure being prevented is a .ora that
     * opens, looks plausible, and is quietly wrong — which is worse than no export at all, because
     * the person finds out weeks later when they compare it to the app.
     */
    @Test
    fun aBlendModeSvgCannotExpressStopsTheExportByName() {
        for (mode in unexpressibleBlendModes()) {
            val d = doc(layers = listOf(background(blend = mode), gloss()))
            val thrown = assertFailsWith<IllegalArgumentException>("$mode must not be approximated") {
                stackXml(encoded(contents(d)))
            }
            assertTrue(
                thrown.message.orEmpty().contains(mode.name),
                "the refusal must name the mode, got: ${thrown.message}",
            )
        }
    }

    /**
     * A refusal must not become a blanket ban: the modes SVG *can* express still export, so the
     * guard cannot quietly grow into "reject everything".
     */
    @Test
    fun everyModeSvgCanExpressStillExports() {
        for (mode in BlendMode.entries - unexpressibleBlendModes()) {
            val d = doc(layers = listOf(background(blend = mode), gloss()))
            val layers = layersOf(stackXml(encoded(contents(d))))
            assertEquals(2, layers.size, "$mode must still export")
            assertTrue(layers[1][6].startsWith("svg:"), "$mode must map to a real svg: operator")
        }
    }

    /**
     * The nine modes SVG has no operator for, in ONE place, so the table above, the refusal test and
     * the still-exports test cannot disagree about which nine they are. Three copies of this list
     * would be three chances to be wrong.
     */
    private fun unexpressibleBlendModes(): Set<BlendMode> = setOf(
        BlendMode.LINEAR_BURN, BlendMode.LINEAR_LIGHT, BlendMode.VIVID_LIGHT,
        BlendMode.PIN_LIGHT, BlendMode.HARD_MIX, BlendMode.SUBTRACT, BlendMode.DIVIDE,
        BlendMode.DARKER_COLOR, BlendMode.LIGHTER_COLOR,
    )

    // ── 3. every layer PNG decodes, to the right size and a known pixel ─────────

    @Test
    fun eachLayerPngIsThatLayerAloneAtFullOpacity() {
        val archive = encoded()
        for (name in listOf("data/0.png", "data/1.png")) {
            val img = image(archive, name)
            assertEquals(W, img.width, "$name width")
            assertEquals(H, img.height, "$name height")
        }

        // Background alone: its own grey.
        assertEquals(listOf(128, 128, 128, 255), pixelAt(image(archive, "data/0.png"), 0, 0))

        // Gloss alone, at OPACITY 1 and NORMAL, so it is PURE WHITE: not half-transparent, and not
        // multiplied against anything. The 0.500 and the multiply are two numbers in the XML, and if
        // either had been baked into the pixels the file would be a flat picture wearing a stack's
        // clothes. This is the most load-bearing thing to know about a layer PNG.
        assertEquals(listOf(255, 255, 255, 255), pixelAt(image(archive, "data/1.png"), 0, 0))
    }

    @Test
    fun aColumnNoTileCoversIsTransparentInEveryLayerAndInTheMergedImage() {
        // Tiles 0_0 and 1_0 reach document column 511. A 600-wide board therefore has 88 columns in
        // no tile at all, and every one of the three images has to agree that they are transparent.
        val archive = encoded(contents(wideDoc()))
        // What the LAST COVERED column holds in each: the grey layer alone is grey, the white layer
        // alone is white, and the merged image is the grey again because white x 0.5 multiply leaves
        // the backdrop alone. If the merged one were 255 this is where it would show.
        val covered = mapOf("data/0.png" to 128, "data/1.png" to 255, "mergedimage.png" to 128)
        for (name in listOf("data/0.png", "data/1.png", "mergedimage.png")) {
            val img = image(archive, name)
            assertEquals(600, img.width, name)
            assertEquals(H, img.height, name)
            assertEquals(0, pixelAt(img, 512, 0)[3], "$name at x=512, which is in no tile")
            assertEquals(0, pixelAt(img, 599, 199)[3], "$name at the far corner")
            assertEquals(covered.getValue(name), pixelAt(img, 511, 0)[0], "$name at the last covered column")
            assertEquals(255, pixelAt(img, 511, 0)[3], "$name is opaque at the last covered column")
        }
    }

    @Test
    fun theMergedImageIsTheWholeStack() {
        val img = image(encoded(), "mergedimage.png")
        assertEquals(W, img.width)
        assertEquals(H, img.height)
        // 128 grey, hand-worked in the fixture's KDoc: grey under a half-opaque white multiply.
        assertEquals(listOf(128, 128, 128, 255), pixelAt(img, 0, 0))
        assertEquals(listOf(128, 128, 128, 255), pixelAt(img, 299, 199), "and the same at the far corner")
    }

    // ── 4. includePaper ─────────────────────────────────────────────────────────

    @Test
    fun includePaperAddsThePaperLayerAtTheBottomOfTheStack() {
        val with = encoded(includePaper = true)
        val without = encoded(includePaper = false)

        // The Paper layer is LAST in the XML, which is the BOTTOM of the stack, and it is `data/0.png`
        // because the numbering follows the same bottom-up order. Getting either of those two the
        // wrong way round puts an opaque white sheet on top of the painting.
        val layers = layersOf(stackXml(with))
        assertEquals(3, layers.size)
        assertEquals("Paper", layers[2][0], "the bottom of the stack")
        assertEquals("data/0.png", layers[2][1], "and the bottom layer is the one numbered first")
        assertEquals("1.000", layers[2][4])
        assertEquals("svg:src-over", layers[2][6])
        assertEquals(3, entriesOf(with).count { it.first.startsWith("data/") }, "one more layer PNG")
        assertEquals(2, entriesOf(without).count { it.first.startsWith("data/") }, "and one fewer without paper")

        // The paper layer is a real opaque image, all the way through.
        val paper = image(with, "data/0.png")
        assertEquals(listOf(255, 255, 255, 255), pixelAt(paper, 0, 0))
        assertEquals(listOf(255, 255, 255, 255), pixelAt(paper, 299, 199))
    }

    @Test
    fun includePaperMakesTheMergedImageOpaqueWhereNothingIsDrawn() {
        // The 600-wide board, because the 300-wide one is covered by art from end to end and its
        // merged image is opaque either way — so the spec's "makes mergedimage opaque" is only a
        // real claim where there is a gap for the paper to reach.
        val c = contents(wideDoc())
        val with = image(encoded(c, includePaper = true), "mergedimage.png")
        val without = image(encoded(c, includePaper = false), "mergedimage.png")
        assertEquals(listOf(255, 255, 255, 255), pixelAt(with, 512, 0), "paper reaches where no tile does")
        assertEquals(0, pixelAt(without, 512, 0)[3], "and without it, nothing does")
        assertEquals(listOf(128, 128, 128, 255), pixelAt(with, 511, 0), "while the art is still on top of it")
        assertEquals(listOf(128, 128, 128, 255), pixelAt(without, 511, 0), "which is the same with or without paper")
    }

    // ── 5. the thumbnail ────────────────────────────────────────────────────────

    @Test
    fun theThumbnailLongestSideIsAtMost256() {
        // Small boards only, deliberately: 999 by 1000 is the biggest here and costs 4 MB, where a
        // 4096 by 2160 case would cost 176 MB of live buffers to learn the same thing. The cap is
        // arithmetic, not a size-dependent behaviour.
        for ((w, h) in listOf(1 to 1, 300 to 200, 1000 to 37, 37 to 1000, 999 to 1000, 257 to 256, 600 to 1)) {
            val rect = RectPx(0, 0, w, h)
            val board = Board(BOARD, "Board 1", BoardKind.CANVAS, rect)
            val thumb = image(encoded(contents(doc(boardRect = rect, boards = listOf(board)))), "Thumbnails/thumbnail.png")
            assertTrue(
                max(thumb.width, thumb.height) <= 256,
                "a ${w}x$h board gave a ${thumb.width}x${thumb.height} thumbnail",
            )
            assertTrue(thumb.width >= 1 && thumb.height >= 1, "a ${w}x$h board gave a thumbnail of no size")
        }
        // And the exact numbers for the spec's own fixture: 300 x 256/300 = 256, 200 x 256/300 = 170.
        val img = image(encoded(), "Thumbnails/thumbnail.png")
        assertEquals(256, img.width)
        assertEquals(170, img.height)
    }

    @Test
    fun theThumbnailIsEverySourcePixelOrNothingInvented() {
        // Nearest-neighbour, so every destination pixel is EXACTLY a source pixel. Anything else —
        // a blend, a black frame, a background the renderer filled in — would be a claim this test
        // is entitled to make and this file is not.
        val archive = encoded()
        val thumb = image(archive, "Thumbnails/thumbnail.png")
        val grey = listOf(128, 128, 128, 255)
        for (y in 0 until thumb.height) {
            for (x in 0 until thumb.width) {
                assertEquals(grey, pixelAt(thumb, x, y), "thumbnail pixel $x,$y is neither the layer nor transparent")
            }
        }
        // The merged image really does hold that grey, so the thumbnail did not invent it.
        assertEquals(128, rgbaOf(image(archive, "mergedimage.png"))[0].toInt() and 0xFF)
    }

    @Test
    fun theThumbnailIsASmallerCopyOfTheMergedImageIncludingWhereItIsTransparent() {
        // A wide board, so the thumbnail has to be told apart in two places: over the art, and over
        // the gap no tile covers. A thumbnail that quietly filled the gap with paper would be a lie
        // about a file exported without paper.
        val archive = encoded(contents(wideDoc()))
        val merged = rgbaOf(image(archive, "mergedimage.png"))
        val thumb = image(archive, "Thumbnails/thumbnail.png")
        // 600 x 256/600 = 256 wide, 200 x 256/600 = 85 down.
        assertEquals(256, thumb.width)
        assertEquals(85, thumb.height)
        for (x in 0 until thumb.width) {
            val p = pixelAt(thumb, x, 0)
            if (x <= 217) {
                assertEquals(128, p[0], "thumbnail column $x is over the art")
                assertEquals(255, p[3], "thumbnail column $x is over the art")
            } else {
                assertEquals(0, p[3], "thumbnail column $x is over the gap, which the merged image leaves alone")
            }
        }
        // The gap starts at source column 512, and centre-sampling maps 512 to thumbnail column 218.
        assertEquals(0, merged[512 * 4 + 3].toInt() and 0xFF, "the merged image really is transparent there")
        assertEquals(128, merged[511 * 4].toInt() and 0xFF, "and really is grey on the other side")
    }

    // ── THE BIG ONE: the file says the same thing twice, and means it ───────────

    /**
     * Read the opacity and the operator OUT OF the stack.xml the exporter wrote, decode the PNGs it
     * wrote, combine them the way a W3C reader does, and compare with the mergedimage.png in the
     * same file.
     *
     * A file that is internally inconsistent is a file that opens in Krita showing something the
     * exporter never drew, and NOT ONE other test in this suite would notice. This runs both with
     * and without paper, because paper enters the same stack by two different routes — as a layer
     * and as the merged image's backdrop — and those two routes have to land in the same place.
     */
    @Test
    fun theLayersAndTheXmlReproduceTheMergedImage() {
        for (includePaper in listOf(false, true)) {
            val archive = encoded(includePaper = includePaper)
            val layers = layersOf(stackXml(archive))
            assertTrue(layers.isNotEmpty())
            // From nothing up, bottom layer first. The Paper layer is the bottom when it is there, so
            // it comes out of the same loop as everything else.
            var composed = ByteArray(W * H * 4)
            for (i in layers.indices.reversed()) {
                val row = layers[i]
                val opacity = assertNotNull(row[4].toFloatOrNull(), "opacity \"${row[4]}\" is not a number")
                composed = compositeOver(composed, rgbaOf(image(archive, row[1])), opacity, opOf(row[6]), W, H)
            }
            assertClose(
                rgbaOf(image(archive, "mergedimage.png")),
                composed,
                "stack.xml against mergedimage.png, includePaper=$includePaper",
            )
        }
    }

    // ── nothing is dropped silently ────────────────────────────────────────────

    @Test
    fun aDocumentWithNoExportableLayersIsStillAFileThatOpens() {
        val d = doc(
            layers = listOf(
                ink(),
                background(id = "hidden", name = "Sketch", visible = false),
                background(id = "zero", name = "Wash", opacity = 0f),
            ),
        )
        val archive = encoded(contents(d))

        // No layer, but every one of the three is named, and the file is otherwise complete.
        val xml = stackXml(archive)
        assertEquals(0, layersOf(xml).size)
        val comments = commentsOf(xml)
        assertEquals(3, comments.size, "every omitted layer is named: $comments")
        assertTrue(comments.any { it.contains("\"Ink line\"") && it.contains("INK") }, "$comments")
        assertTrue(comments.any { it.contains("\"Sketch\"") && it.contains("hidden") }, "$comments")
        assertTrue(comments.any { it.contains("\"Wash\"") && it.contains("0% opaque") }, "$comments")

        // An empty stack is a legal OpenRaster image. It still has a mimetype, a stack, a thumbnail
        // and a flattened image, all of the right size, all of them decodable.
        assertEquals(
            listOf("mimetype", "stack.xml", "Thumbnails/thumbnail.png", "mergedimage.png"),
            namesOf(archive),
        )
        val merged = image(archive, "mergedimage.png")
        assertEquals(W, merged.width)
        assertEquals(H, merged.height)
        for (y in 0 until H step 37) for (x in 0 until W step 53) {
            assertEquals(0, pixelAt(merged, x, y)[3], "nothing is drawn, so nothing is opaque at $x,$y")
        }
        assertEquals(256, image(archive, "Thumbnails/thumbnail.png").width)
    }

    @Test
    fun anInkLayerIsSkippedAndNamedWhileThePaintLayersBesideItAreExported() {
        val archive = encoded(contents(doc(layers = listOf(background(), ink(), gloss()))))
        val xml = stackXml(archive)

        // The two PAINT layers are still there and still numbered 0 and 1: INK must not take a
        // number, because a `src` that shifted by one would point every reader at the wrong file.
        val layers = layersOf(xml)
        assertEquals(2, layers.size)
        assertEquals("data/0.png", layers[1][1], "Background")
        assertEquals("data/1.png", layers[0][1], "Gloss")
        // Two image files, not three: the two `src` values above are the complete list, and an INK
        // layer that is skipped must not leave a hole in the numbering. (This said 3, which is the
        // number of LAYERS in the fixture rather than the number of files the writer produces.)
        assertEquals(2, entriesOf(archive).count { it.first.startsWith("data/") })

        val comments = commentsOf(xml)
        assertEquals(1, comments.size)
        assertTrue(comments[0].contains("\"Ink line\""), comments[0])
    }

    @Test
    fun aPaintLayerNobodyHasDrawnOnIsStillExported() {
        val empty = background(id = "fresh", name = "Fresh", tiles = emptyList())
        val archive = encoded(contents(doc(layers = listOf(background(), empty, gloss()))))
        val layers = layersOf(stackXml(archive))
        assertEquals(3, layers.size, "an empty PAINT layer is a layer, not a missing one")
        assertEquals("Fresh", layers[1][0])
        assertEquals("data/1.png", layers[1][1], "and it does take a number, between its neighbours")

        // And its PNG is a real image of the right size, transparent everywhere.
        val img = image(archive, "data/1.png")
        assertEquals(W, img.width)
        assertEquals(H, img.height)
        assertEquals(0, pixelAt(img, 0, 0)[3])
        assertEquals(0, pixelAt(img, 299, 199)[3])
    }

    @Test
    fun aLayerAtZeroOpacityOrHiddenOrMeaninglessIsNamedRatherThanVanishing() {
        val d = doc(
            layers = listOf(
                background(id = "z", name = "Wash", opacity = 0f),
                background(id = "n", name = "Backwards", opacity = -1f),
                background(id = "x", name = "Nonsense", opacity = Float.NaN),
                background(id = "h", name = "Sketch", visible = false),
                background(id = "l", name = "Loud", opacity = 2f),
                gloss(),
            ),
        )
        val archive = encoded(contents(d))
        val xml = stackXml(archive)

        // FOUR layers contribute nothing and are named; TWO survive. A NaN opacity has to count as
        // nothing, because `coerceIn` would hand a NaN straight through and turn the whole region
        // into NaN pixels rather than into an invisible layer.
        //
        // "Loud", at opacity 2f, is NOT one of the omissions. It clamps to 1f, which is a perfectly
        // ordinary opaque layer, and this test previously said 5 omissions and 1 survivor while its
        // own next three lines asserted that Loud is exported at "1.000" — so the fixture and the
        // expectations disagreed and the count lost. Loud is now asserted to be present by name,
        // which is the stronger claim: it pins WHERE the line is drawn, not just how many things
        // are on the far side of it.
        val comments = commentsOf(xml)
        assertEquals(4, comments.size, "$comments")
        for (name in listOf("Wash", "Backwards", "Nonsense", "Sketch")) {
            assertTrue(comments.any { it.contains("\"$name\"") }, "\"$name\" is not named: $comments")
        }
        assertFalse(
            comments.any { it.contains("\"Loud\"") },
            "a layer clamped to 100% is a layer, not an omission: $comments",
        )
        val layers = layersOf(xml)
        assertEquals(2, layers.size, "Loud and Gloss both export: $layers")
        assertEquals(setOf("Loud", "Gloss"), layers.map { it[0] }.toSet(), "and they are the two")
        // Loud means 100%, and the XML says so. A strict reader would refuse `opacity="2.000"`, and
        // NaN is not a number at all.
        assertEquals("1.000", layers.first { it[0] == "Loud" }[4], "clamped the way the renderer clamps it")
        assertFalse(xml.contains("2.000"))
        assertFalse(xml.contains("NaN"))
    }

    @Test
    fun anAnimatedLayerWithNoCelForThisFrameIsNamed() {
        val anim = Board("anim", "Anim", BoardKind.ANIMATION, RectPx(0, 0, 64, 64), frames = listOf(Frame("f1")))
        val moving = Layer(
            id = "moving",
            name = "Frame one",
            kind = LayerKind.PAINT,
            animatedIn = "anim",
            cels = listOf(Cel("c1", tiles = listOf("0_0"))),
            frameCel = mapOf("f1" to "c1"),
        )
        val d = doc(boards = listOf(anim), layers = listOf(background(), moving))
        val base = contents(d)
        val c = base.copy(
            tiles = base.tiles + (Triple("moving", "c1", "0_0") to solid(255, 0, 0, 255)),
        )

        // A frame it has no cel for: named, not exported as an empty layer claiming to be a frame.
        val elsewhere = stackXml(encoded(c, boardId = "anim", frameId = "f9"))
        assertEquals(1, layersOf(elsewhere).size, "only the static layer is there")
        assertTrue(commentsOf(elsewhere).single().contains("no cel for this frame"), commentsOf(elsewhere).toString())

        // And the frame that IS mapped exports it, with its own red.
        val onF1 = encoded(c, boardId = "anim", frameId = "f1")
        assertEquals(2, layersOf(stackXml(onF1)).size)
        assertEquals(listOf(255, 0, 0, 255), pixelAt(image(onF1, "data/1.png"), 0, 0), "the moving layer's own red")
    }

    // ── names a reader has to be able to survive ───────────────────────────────

    @Test
    fun aNameWithXmlMetacharactersInItSurvivesTheRoundTrip() {
        val name = "Fish & <chips> \"quoted\" 'apostrophe'"
        val archive = encoded(contents(doc(layers = listOf(background(name = name), gloss()))))
        val xml = stackXml(archive)
        assertTrue(xml.contains("&amp;"), "the ampersand is escaped: $xml")
        assertTrue(xml.contains("&lt;chips&gt;"), "the angle brackets are escaped: $xml")
        // A raw `&` or `<` in an attribute would make this file UNPARSEABLE, which is the whole
        // point — so the assertion is the PARSE, not the string, and a lazier escaping cannot pass.
        assertEquals(name, layersOf(xml)[1][0], "and it comes back exactly as it went in")
    }

    @Test
    fun aNameWithWhitespaceInItIsNotQuietlyFlattenedToSpaces() {
        val name = "Line 1\nLine 2\tTabbed"
        val xml = stackXml(encoded(contents(doc(layers = listOf(background(name = name))))))
        assertTrue(xml.contains("&#10;") && xml.contains("&#9;"), "numeric references, not raw: $xml")
        // XML attribute-value normalisation turns a LITERAL newline into a plain space, so without
        // the numeric references this would parse happily, arrive as "Line 1 Line 2 Tabbed", and
        // nothing anywhere would say so.
        assertEquals(name, layersOf(xml)[0][0], "the newline and the tab both survive")
    }

    @Test
    fun aNameWithACharacterXmlForbidsDoesNotCostThePersonTheirWholeFile() {
        // Built from Char values rather than escapes, so that nothing in this file's own encoding can
        // quietly turn this test into a different test.
        val hostile = "Sketch " + 1.toChar() + 27.toChar() + "[31m & trouble"
        val xml = stackXml(encoded(contents(doc(layers = listOf(background(name = hostile), gloss())))))

        // THE FILE PARSES. That is the assertion. A raw control character in a document is not a
        // bad name, it is not a document, and every reader refuses the WHOLE FILE — so one stray
        // character would cost somebody their entire export rather than one layer's name.
        val layers = layersOf(xml)
        assertEquals(2, layers.size)
        assertTrue(layers[1][0].contains("& trouble"), "the readable part survives: ${layers[1][0]}")
        assertTrue(layers[1][0].contains(0xFFFD.toChar()), "the illegal characters became replacement characters")
    }

    @Test
    fun aNameWithTwoHyphensInItCannotEndTheCommentEarly() {
        // An XML comment is the one place in a document where nothing is escaped, and `--` ends it.
        // The layer is hidden, so its name goes into a comment, and a comment broken here is a FILE
        // broken here — the layer is the least of what a person would lose.
        val name = "sketch--final--version"
        val d = doc(layers = listOf(background(name = name).copy(visible = false), gloss()))
        val xml = stackXml(encoded(contents(d)))
        assertEquals(1, layersOf(xml).size, "the file still parses and the layer is still gone")
        val comments = commentsOf(xml)
        assertEquals(1, comments.size)
        assertTrue(comments[0].contains("sketch- -final- -version"), "the hyphens were separated: ${comments[0]}")
        assertFalse(comments[0].contains("--"), "and no comment of ours can ever contain two in a row")
    }

    @Test
    fun thePaperLayerDoesNotGetTheSameNameAsALayerThatAlreadyHasIt() {
        val named = doc(layers = listOf(background(name = "Paper"), gloss()))
        val layers = layersOf(stackXml(encoded(contents(named), includePaper = true)))
        assertEquals(listOf("Gloss", "Paper", "Paper 2"), layers.map { it[0] }, "two rows both reading Paper is the papercut")

        // And the disambiguation cannot spin: with "Paper" AND "Paper 2" both taken, it has to find
        // the next one and stop there.
        val crowded = doc(
            layers = listOf(background(name = "Paper"), background(id = "p2", name = "Paper 2"), gloss()),
        )
        val three = layersOf(stackXml(encoded(contents(crowded), includePaper = true)))
        assertEquals(listOf("Gloss", "Paper 2", "Paper", "Paper 3"), three.map { it[0] })
        assertEquals("data/0.png", three[3][1], "and the synthetic paper is the bottom of the stack, numbered first")
    }

    // ── the board's origin ─────────────────────────────────────────────────────

    @Test
    fun aBoardNowhereNearTheOriginExportsTheSamePicture() {
        // The same drawing, the same board SIZE, the same tiles, and only the WINDOW moved. A board
        // at (-40,-15) sees document pixels -40..259 by -15..184, which reaches three tile columns
        // and two tile rows where the board at the origin reaches two and one — so the away window
        // needs those extra tiles, and they get the same colour as the ones already there.
        val at = RectPx(0, 0, W, H)
        val away = RectPx(-40, -15, W, H)
        val here = encoded(contents(doc(boardRect = at)))

        val tiles = LinkedHashMap<Triple<String, String, String>, ByteArray>()
        for (tx in -1..1) for (ty in -1..0) {
            tiles[Triple(BG, "$BG-cel", DocOps.key(tx, ty))] = solid(128, 128, 128, 255)
            tiles[Triple(GLOSS, "$GLOSS-cel", DocOps.key(tx, ty))] = solid(255, 255, 255, 255)
        }
        val there = encoded(JbContents(doc(boardRect = away), tiles, emptyMap()))

        // The two pictures are byte-identical, so the origin really was normalised away rather than
        // shifted: the same window of the same canvas, cropped the same way, with nothing left of
        // where it happened to sit.
        for (name in listOf("data/0.png", "data/1.png", "Thumbnails/thumbnail.png", "mergedimage.png")) {
            assertContentEquals(entry(here, name), entry(there, name), name)
        }
        // And the XML is identical too, because x and y are 0 in both: an .ora canvas has its own
        // origin, and the format has nowhere to put a board's absolute x and y.
        assertContentEquals(stackXml(here).toByteArray(), stackXml(there).toByteArray())
        assertTrue(stackXml(there).contains("x=\"0\" y=\"0\""), stackXml(there))
    }

    // ── repeatability ──────────────────────────────────────────────────────────

    @Test
    fun twoExportsOfTheSameDrawingAgreeOnEverythingButTheTimestamps() {
        val a = encoded(includePaper = true)
        val b = encoded(includePaper = true)
        assertEquals(a.size, b.size, "the same length, so nothing is written in a different order")
        val ea = entriesOf(a)
        val eb = entriesOf(b)
        assertEquals(ea.map { it.first }, eb.map { it.first }, "the same entries, in the same order")
        for (i in ea.indices) {
            assertEquals(ea[i].second, eb[i].second, "${ea[i].first} compression")
            // contentEquals, because `==` on two ByteArrays compares REFERENCES and would call these
            // different every single time — which is the trap JbContents documents.
            assertContentEquals(ea[i].third, eb[i].third, "${ea[i].first} came out different")
        }
    }

    // ── refusals ───────────────────────────────────────────────────────────────

    @Test
    fun refusesABoardThatIsNotInTheDocument() {
        assertEquals("this document has no board called \"nope\" (it has 1)", refusal(boardId = "nope"))
    }

    @Test
    fun refusesABoardWithNoRoom() {
        for (rect in listOf(RectPx(0, 0, 0, 200), RectPx(0, 0, 300, 0), RectPx(0, 0, -1, 5))) {
            val board = Board(BOARD, "Board 1", BoardKind.CANVAS, rect)
            val message = refusal(contents(doc(boardRect = rect, boards = listOf(board))))
            assertTrue(message.startsWith("board \"$BOARD\" has no room"), "$rect was refused with: $message")
        }
    }

    @Test
    fun refusesABoardLargerThanAPngSideEvenThoughThePixelBudgetWouldAllowIt() {
        // 65536 by 1 is 65,536 pixels: nowhere near MAX_REGION_PX, and not a PNG. Without this check
        // the failure would arrive from `PngWriter` as an IllegalArgumentException about an array
        // size, three allocations later, from a function that is supposed to be about layers.
        val rect = RectPx(0, 0, 65536, 1)
        val board = Board(BOARD, "Board 1", BoardKind.CANVAS, rect)
        val message = refusal(contents(doc(boardRect = rect, boards = listOf(board))))
        assertTrue(message.contains("65536"), "the message names the size: $message")
        assertTrue(message.contains("65535"), "and the ceiling: $message")
    }

    @Test
    fun refusesABoardOverTheRenderBudgetWithTheSameNumberTheRendererWouldGive() {
        val rect = RectPx(0, 0, 4096, 2049)
        val board = Board(BOARD, "Board 1", BoardKind.CANVAS, rect)
        val wanted = 4096L * 2049
        val message = refusal(contents(doc(boardRect = rect, boards = listOf(board))))
        assertTrue(message.contains("$wanted"), "names the pixel count it wanted: $message")
        assertTrue(message.contains("8388608"), "and the budget: $message")
        // And the TYPE: this is our own refusal, not the renderer's, and not an OutOfMemoryError.
        // One `catch` at the export button is the whole promise of this file.
        assertTrue(message.startsWith("board \"$BOARD\" is"), message)
    }

    @Test
    fun refusesAPaperColourItCannotReadRatherThanWritingABlackLayer() {
        for (bad in listOf("FFFFFF", "#FFF", "#GGGGGG", "red", "")) {
            val d = doc(paper = Paper(color = bad))
            val message = refusal(contents(d), includePaper = true)
            assertTrue(message.contains("is not a #RRGGBB colour"), "paper \"$bad\" was refused with: $message")
        }
        // Without paper the colour is not read at all, so a document with a bad one still exports its
        // art rather than refusing the whole file over a setting nobody asked to use.
        val d = doc(paper = Paper(color = "not a colour"))
        assertEquals(2, layersOf(stackXml(encoded(contents(d)))).size)
    }

    @Test
    fun aRefusedExportWritesNothingAtAll() {
        val out = ByteArrayOutputStream()
        val d = doc(paper = Paper(color = "not a colour"))
        assertFailsWith<JbArchiveException> { OraExport.write(out, contents(d), BOARD, null, true) }
        assertEquals(0, out.size(), "the checks happen before the first byte, not after")
    }

    @Test
    fun aTileOfTheWrongSizeIsReportedRatherThanWrittenOutAsAFileThatOpensWrong() {
        val c = contents()
        val broken = c.copy(tiles = c.tiles + (Triple(BG, "$BG-cel", "0_0") to ByteArray(10)))
        val message = refusal(broken)
        assertTrue(message.startsWith("the OpenRaster file could not be written:"), message)
    }

    @Test
    fun aStreamThatDiesHalfWayIsReportedAndWhatLandedIsNotAnOra() {
        val sink = ByteArrayOutputStream()
        var calls = 0
        val stream = object : OutputStream() {
            override fun write(one: Int) {
                calls++
                sink.write(one)
                if (calls >= 4) throw IOException("the disk went away")
            }

            override fun write(bytes: ByteArray, off: Int, len: Int) {
                calls++
                sink.write(bytes, off, len)
                if (calls >= 4) throw IOException("the disk went away")
            }
        }
        val message = assertFailsWith<JbArchiveException> { OraExport.write(stream, contents(), BOARD, null, false) }
            .message.orEmpty()
        assertEquals("the OpenRaster file could not be written: the disk went away", message)
        assertTrue(sink.size() > 0, "nothing was written, so this failed before it started")
        // And what landed is not an .ora: no central directory was ever finished. Walking it is
        // allowed to fail as well — an entry header with its data cut off is not a zip any reader
        // accepts — so the assertion is on what came back rather than on HOW the JDK reports it.
        val landed = runCatching { namesOf(sink.toByteArray()) }
        assertTrue(
            landed.isFailure || "mergedimage.png" !in landed.getOrThrow(),
            "a half-written archive is not an export: ${landed.getOrNull()}",
        )
    }

    @Test
    fun writeLeavesTheCallersStreamOpen() {
        val out = CloseWatching()
        OraExport.write(out, contents(), BOARD, null, false)
        assertFalse(out.closed, "close() on the caller's stream would finish THEIR file early")
        assertTrue(out.sink.size() > 0)
        assertEquals(6, namesOf(out.sink.toByteArray()).size, "every entry arrived")
    }

    /** A sink that remembers whether anybody closed it, which `ByteArrayOutputStream` cannot tell you. */
    private class CloseWatching : OutputStream() {
        val sink = ByteArrayOutputStream()
        var closed = false

        override fun write(one: Int) {
            sink.write(one)
        }

        override fun write(bytes: ByteArray, off: Int, len: Int) {
            sink.write(bytes, off, len)
        }

        override fun close() {
            closed = true
            sink.close()
        }
    }

    // ── the compositing a reader does, written out by hand ─────────────────────

    /**
     * What a W3C reader does with a layer PNG: premultiply the source, scale ALL FOUR channels by
     * the `opacity` the XML gave, blend, and hand back straight alpha.
     *
     * Written here rather than reached for through `RegionRenderer`, on purpose. A test that used the
     * renderer to check the renderer would pass whatever the renderer did; this is the reader's side
     * of the same arithmetic, from [Blend], and it is what the layer PNGs have to add up to.
     *
     * [Blend] is the shared arithmetic and NOT the thing under test. The thing under test is that a
     * PNG holds the layer ALONE, so folding the opacity in here is right rather than counting it
     * twice.
     */
    private fun compositeOver(
        dst: ByteArray,
        src: ByteArray,
        opacity: Float,
        mode: BlendMode,
        w: Int,
        h: Int,
    ): ByteArray {
        val s = FloatArray(4)
        val d = FloatArray(4)
        val o = FloatArray(4)
        val out = ByteArray(w * h * 4)
        var p = 0
        while (p < out.size) {
            for (c in 0 until 4) {
                s[c] = (src[p + c].toInt() and 0xFF) / 255f * opacity
                d[c] = (dst[p + c].toInt() and 0xFF) / 255f
            }
            Blend.apply(mode, s, d, o)
            val k = if (o[3] > 0f) 1f / o[3] else 0f
            for (c in 0 until 3) out[p + c] = byte255(o[c] * k)
            out[p + 3] = byte255(o[3])
            p += 4
        }
        return out
    }

    private fun byte255(v: Float): Byte = (v * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()

    private fun opOf(name: String): BlendMode = when (name) {
        "svg:src-over" -> BlendMode.NORMAL
        "svg:multiply" -> BlendMode.MULTIPLY
        "svg:screen" -> BlendMode.SCREEN
        "svg:overlay" -> BlendMode.OVERLAY
        "svg:plus" -> BlendMode.ADD
        "svg:darken" -> BlendMode.DARKEN
        "svg:lighten" -> BlendMode.LIGHTEN
        "svg:dst-out" -> BlendMode.ERASE_BELOW
        // A reader that met an operator nobody here knows would be a real bug in the writer, and it
        // has to be an error rather than a silent NORMAL.
        else -> throw AssertionError("the exporter wrote an operator nobody here knows: $name")
    }

    /**
     * Two pictures of the same size, within one step of each other per channel.
     *
     * One step, not zero, and the reason is honest: the exporter's number came from one rounding of
     * this float arithmetic and this one is a second rounding of the same arithmetic in a different
     * place. A difference of one is invisible; the exact-value assertions elsewhere in this file are
     * the ones that would catch a difference of two.
     */
    private fun assertClose(want: ByteArray, got: ByteArray, what: String) {
        assertEquals(want.size, got.size, "$what: the two pictures are different sizes")
        for (i in want.indices) {
            val d = abs((want[i].toInt() and 0xFF) - (got[i].toInt() and 0xFF))
            assertTrue(
                d <= 1,
                "$what: byte $i (pixel ${i / 4} channel ${i % 4}) is " +
                    "${want[i].toInt() and 0xFF} against ${got[i].toInt() and 0xFF}",
            )
        }
    }

    /**
     * JB-2.23: OpenRaster has no clipping, so a clipped layer's PNG is cut to its base before it is written. The base
     * here covers only the LEFT tile, so the clipped red layer is red at x = 10 and nothing at x = 260 — in its own PNG,
     * exactly as in the merged image.
     */
    @Test
    fun aClippedLayerIsWrittenAlreadyCutToItsBase() {
        val clipped = Layer(id = "clip1", name = "Shade", kind = LayerKind.PAINT, clip = true,
            cels = listOf(Cel(id = "clip1-cel", tiles = listOf("0_0", "1_0"))))
        val d = doc(layers = listOf(background(tiles = listOf("0_0")), clipped))
        val c = JbContents(
            doc = d,
            tiles = mapOf(
                Triple(BG, "$BG-cel", "0_0") to solid(128, 128, 128, 255),
                Triple("clip1", "clip1-cel", "0_0") to solid(255, 0, 0, 255),
                Triple("clip1", "clip1-cel", "1_0") to solid(255, 0, 0, 255),
            ),
            strokes = emptyMap(),
        )
        val img = image(encoded(c), "data/1.png")
        assertEquals(listOf(255, 0, 0, 255), pixelAt(img, 10, 10), "inside the base: the layer, whole")
        assertEquals(0, pixelAt(img, 260, 10)[3], "outside the base: nothing")
    }
}
