package cc.joycreator.joybrush.core.render

import cc.joycreator.joybrush.core.doc.BlendMode
import cc.joycreator.joybrush.core.doc.Board
import cc.joycreator.joybrush.core.doc.BoardKind
import cc.joycreator.joybrush.core.doc.Cel
import cc.joycreator.joybrush.core.doc.JbDocument
import cc.joycreator.joybrush.core.doc.Layer
import cc.joycreator.joybrush.core.doc.LayerKind
import cc.joycreator.joybrush.core.doc.RectPx
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * JB-9.06b. Textured paper as the FLOOR of the export stack, laid down in bounded blocks, before
 * the first layer — the arithmetic the screen has always used and the exporters had stopped using.
 *
 * THE DEFECT THIS FILE EXISTS FOR, in one sentence: a MULTIPLY layer used to be composited over
 * TRANSPARENCY and have the paper pasted over the result, so `dst` in the blend formula was
 * `(0,0,0,0)` instead of the paper, and the exported PNG disagreed with the phone. Every test below
 * is either that claim, the bound that keeps it affordable, or a guard that has to be refused
 * before a texture is decoded.
 *
 * TWO ORACLES, ON PURPOSE. Where a value is easy to work out by hand it is written out
 * ([aMultiplyLayerMultipliesThePaperItLandedOn]); where the whole point is that twenty-seven modes
 * all agree with the flat-backdrop path that was already trusted, the oracle is that path itself
 * ([everyBlendModeAgreesWithTheFlatBackdropOfTheSameColour]). A test that only compared the new code
 * with itself would pass if the whole floor moved.
 */
class PaperBackdropTest {

    // ── the arithmetic ──────────────────────────────────────────────────────────

    /**
     * THE HEADLINE. One MULTIPLY layer over a paper that changes colour halfway across the region.
     *
     * The source is opaque mid grey, so MULTIPLY collapses to `co = sa*da*Cs*Cb = Cs*Cb` with
     * `a = 1`, and the expected value is a product of two bytes with no other term to get wrong.
     * At document (10, 10) the paper is (200, 40, 60), so the answer is 128/255 of that in each
     * channel; at (200, 10) the paper is (20, 80, 220) and the answer is 128/255 of THAT.
     *
     * THE OLD COMPOSITION IS THE THING BEING DISPROVED, and it is written out rather than left to
     * memory, because the old path is short enough to look reasonable: composite the layer over
     * nothing, then blend the finished art over the paper. The layer is opaque, so that path
     * returned (128, 128, 128) at BOTH pixels — the paper never entered the blend at all. Two
     * assertions, then: the new answer is the product, and it is not the old one.
     */
    @Test
    fun aMultiplyLayerMultipliesThePaperItLandedOn() {
        val rect = RectPx(0, 0, 300, 40)
        val d = doc(listOf(layer("m", blend = BlendMode.MULTIPLY)))
        val tiles = tilesOf(TKey("m", "m-cel", 0, 0) to solid(128, 128, 128, 255))

        val out = RegionRenderer.render(d, tiles, rect, null, null, ::paperOf)
        assertContentEquals(
            listOf(100, 20, 30, 255), pixelAt(out, rect, 10, 10),
            "MULTIPLY at x=10 must be 128/255 of the (200,40,60) paper, not a paste-over",
        )
        assertContentEquals(
            listOf(10, 40, 110, 255), pixelAt(out, rect, 200, 10),
            "the other half of the region has its own paper, and a paste-over cannot see it",
        )

        // The old answer, worked out here so the comparison is against arithmetic and not memory.
        val old = oldPostStack(listOf(128, 128, 128, 255), listOf(200, 40, 60, 255))
        assertContentEquals(listOf(128, 128, 128, 255), old, "the old path returned the layer untouched")
        assertNotEquals(old, pixelAt(out, rect, 10, 10), "the fix must actually change the picture")
    }

    /**
     * THE WHOLE-PICTURE CLAIM: for every mode the project has, a textured backdrop gives the same
     * answer as the flat colour backdrop that has been there all along.
     *
     * The callback here is CONSTANT on purpose. A flat paper is one colour for the whole region, so
     * a constant callback is the only textured paper that can be compared with it pixel for pixel;
     * anything varying would be comparing two different papers and proving nothing. What this pins
     * is that the block machinery — the loop, the strides, the pre-layer placement — is transparent
     * to the blend, which is the one thing a per-channel or whole-pixel mode could have broken.
     *
     * `ERASE_BELOW` is in [BlendMode.entries] and is checked here like any other, which is the
     * point of the other test below: an opaque callback is compatible with a translucent ANSWER.
     */
    @Test
    fun everyBlendModeAgreesWithTheFlatBackdropOfTheSameColour() {
        val rect = RectPx(-13, -7, 300, 100)   // negative origin and several blocks in both axes
        val flat = "#C8283C"
        // Every tile the region touches, so the blend is exercised across block edges rather than
        // only inside the one tile that happens to sit at the origin. A missing tile renders as
        // nothing, which would quietly reduce this to "paper equals paper" over most of the area.
        val paint = tilesCovering(rect, TKey("bg", "bg-cel", 0, 0) to solid(0, 128, 255, 128))
        val paint2 = paint + tilesCovering(rect, TKey("m", "m-cel", 0, 0) to solid(255, 64, 0, 128))
        for (blend in BlendMode.entries) {
            val d = doc(listOf(layer("bg"), layer("m", blend = blend)))
            val withPaper = RegionRenderer.renderPremultiplied(d, FakeTiles(paint2), rect, null, flat)
            val withBlocks = RegionRenderer.renderPremultiplied(d, FakeTiles(paint2), rect, null, null) { block ->
                solidRect(block, 0xC8, 0x28, 0x3C)
            }
            for (i in withPaper.indices) {
                assertEquals(withPaper[i], withBlocks[i], 1e-6f, "$blend channel $i differs from the flat backdrop")
            }
        }
    }

    /**
     * THE CLARIFICATION, as a test rather than a sentence: the callback is required to be OPAQUE, and
     * the finished picture is still allowed to be translucent afterwards.
     *
     * `ERASE_BELOW` is destination-out, so it takes the paper's own alpha away exactly as it takes
     * a flat backdrop's. A guard that demanded an opaque FINAL image would therefore be demanding
     * that the eraser stop working, and the honest statement is the narrow one: opacity is a
     * requirement of the INPUT. The equality with the flat backdrop is the proof that this is not a
     * special case invented for textured paper.
     */
    @Test
    fun eraseBelowStillRemovesAlphaFromAnOpaquePaperCallback() {
        val rect = RectPx(0, 0, 8, 8)
        val d = doc(listOf(layer("e", blend = BlendMode.ERASE_BELOW)))
        val tiles = tilesOf(TKey("e", "e-cel", 0, 0) to solid(255, 255, 255, 128))
        val blocks = RegionRenderer.render(d, tiles, rect, null, null) { solidRect(it, 0xC8, 0x28, 0x3C) }
        val flat = RegionRenderer.render(d, tiles, rect, null, "#C8283C")
        assertTrue((blocks[3].toInt() and 0xFF) < 255, "ERASE_BELOW must still take alpha away")
        assertContentEquals(pixelAt(flat, rect, 0, 0), pixelAt(blocks, rect, 0, 0), "the flat path is the oracle")
    }

    // ── the bound, and the coordinates ──────────────────────────────────────────

    /**
     * Every request is at most [PAPER_BLOCK_W] by [PAPER_BLOCK_H], and
     * the blocks TILE the region: no gap, no overlap, no empty request.
     *
     * The grid is written out rather than counted, because a count alone would pass for a renderer
     * that asked for 1012 blocks of 1x1 — also a tiling, also bounded, and a texture decode per
     * pixel. The expected start positions come from the rect, so a change to either constant is a
     * red test here instead of a slower phone.
     */
    @Test
    fun blocksAreBoundedTiledAndInDocumentCoordinates() {
        val rect = RectPx(-37, -11, 300, 100)
        val asked = ArrayList<RectPx>()
        RegionRenderer.render(doc(listOf(layer("m"))), tilesOf(), rect, null, null) { block ->
            asked += block
            solidRect(block, 1, 2, 3)
        }
        val expected = ArrayList<RectPx>()
        var y = rect.y
        while (y < rect.y + rect.h) {
            val bh = minOf(PAPER_BLOCK_H, rect.y + rect.h - y)
            var x = rect.x
            while (x < rect.x + rect.w) {
                val bw = minOf(PAPER_BLOCK_W, rect.x + rect.w - x)
                expected += RectPx(x, y, bw, bh)
                x += bw
            }
            y += bh
        }
        assertEquals(expected, asked, "the blocks must tile the region exactly, in document coordinates")
        for (b in asked) {
            assertTrue(b.w in 1..PAPER_BLOCK_W && b.h in 1..PAPER_BLOCK_H, "$b is out of bounds")
            assertTrue(b.x >= rect.x && b.y >= rect.y && b.x + b.w <= rect.x + rect.w && b.y + b.h <= rect.y + rect.h, "$b leaves the region")
        }
    }

    /**
     * NO SEAM AT A BLOCK EDGE, and no shear, and no gap — checked by making the paper a FUNCTION OF
     * DOCUMENT POSITION and demanding the finished picture reproduce it exactly, pixel for pixel,
     * over a region whose origin is negative and whose size crosses block boundaries in both axes.
     *
     * With no layers at all the output IS the paper, so every byte of a 300x100 result is an
     * assertion. That is what makes this the test that catches the three ways the copy loop can be
     * wrong: copying a block as one run (shears every row after the first), advancing by the
     * region's stride instead of the block's (leaves gaps), and asking for region-relative
     * coordinates (slides the whole paper, which is invisible on a constant paper and glaring on a
     * gradient one).
     */
    @Test
    fun aPaperThatVariesWithPositionComesBackUnchangedAcrossBlockEdges() {
        val rect = RectPx(-37, -11, 300, 100)
        val out = RegionRenderer.render(doc(emptyList()), tilesOf(), rect, null, null, ::paperOf)
        for (y in rect.y until rect.y + rect.h) {
            for (x in rect.x until rect.x + rect.w) {
                // `and 0xFF`, because these are BYTES: 200 as a Kotlin Byte is -56, and an expected
                // list full of negative channels would fail against a perfectly correct render.
                val want = paperOf(RectPx(x, y, 1, 1)).let {
                    listOf(it[0].toInt() and 0xFF, it[1].toInt() and 0xFF, it[2].toInt() and 0xFF, 255)
                }
                assertContentEquals(want, pixelAt(out, rect, x, y), "document pixel $x,$y")
            }
        }
    }

    /**
     * THE SOURCE IS NOT THE TARGET. The callback's arrays and the document's tiles are both read
     * here and never written, and a render that quietly painted a paper block into a tile would
     * corrupt the document in a way no later test could see.
     */
    @Test
    fun renderingNeverMutatesTheBlocksOrTheTiles() {
        val rect = RectPx(0, 0, 8, 8)
        val tile = solid(200, 100, 50, 255)
        val tiles = tilesOf(TKey("m", "m-cel", 0, 0) to tile)
        val tileBefore = tile.copyOf()
        val blocks = ArrayList<ByteArray>()
        RegionRenderer.render(doc(listOf(layer("m", blend = BlendMode.MULTIPLY))), tiles, rect, null, null) { block ->
            solidRect(block, 0xC8, 0x28, 0x3C).also { blocks += it }
        }
        assertContentEquals(tileBefore, tile, "a render must not write into a document tile")
        for (b in blocks) assertEquals(255, (b[3].toInt() and 0xFF), "a block handed back must come back unchanged")
    }

    // ── the guards, and the order they run in ───────────────────────────────────

    /** Two backdrops is one too many, and neither is quietly preferred. Both doors refuse. */
    @Test
    fun twoBackdropsAreRefusedOnBothDoors() {
        val d = doc(emptyList())
        val rect = RectPx(0, 0, 4, 4)
        var calls = 0
        val renderer = { _: RectPx -> calls++; ByteArray(4 * 4) }
        val fromRender = assertFailsWith<IllegalArgumentException> {
            RegionRenderer.render(d, tilesOf(), rect, null, "#FFFFFF", renderer)
        }
        assertTrue(fromRender.message!!.contains("both"), "the refusal must say what is wrong: ${fromRender.message}")
        val fromPremultiplied = assertFailsWith<IllegalArgumentException> {
            RegionRenderer.renderPremultiplied(d, tilesOf(), rect, null, "#FFFFFF", renderer)
        }
        assertTrue(fromPremultiplied.message!!.contains("both"), "both doors, the same sentence")
        assertEquals(0, calls, "a refused request must not ask for a single pixel of paper")
    }

    /**
     * A block of the wrong length is REFUSED, not padded and not truncated. The mutation that
     * removes this guard has to turn [this] red; that is the only reason the check exists as
     * anything more than a hope.
     */
    @Test
    fun aBlockOfTheWrongLengthIsRefused() {
        val rect = RectPx(0, 0, 4, 4)
        for (bad in listOf(ByteArray(0), ByteArray(4 * 4 * 4 - 1), ByteArray(4 * 4 * 4 + 3))) {
            val e = assertFailsWith<IllegalArgumentException> {
                RegionRenderer.render(doc(emptyList()), tilesOf(), rect, null, null) { bad }
            }
            assertTrue(e.message!!.contains("padded"), "the refusal must name the rule: ${e.message}")
        }
    }

    /** A translucent "paper" is a contradiction, so it is a sentence rather than a guess. */
    @Test
    fun aTranslucentBlockIsRefusedAndTheMessageNamesThePixel() {
        val rect = RectPx(0, 0, 4, 4)
        val e = assertFailsWith<IllegalArgumentException> {
            RegionRenderer.render(doc(emptyList()), tilesOf(), rect, null, null) { block ->
                // Pixel (3,0) of the block, and ONLY that one: a block that is uniformly translucent
                // would pass a check that only looked at the first pixel.
                solidRect(block, 10, 20, 30).also { it[15] = 254.toByte() }
            }
        }
        assertTrue(e.message!!.contains("always opaque"), "the refusal must name the rule: ${e.message}")
        assertTrue(e.message!!.contains("3,0"), "the refusal must say WHERE: ${e.message}")
    }

    /**
     * A RENDERER THAT THROWS ABORTS THE RENDER, and nothing is half-written.
     *
     * "Aborts" is the whole contract: an exporter that swallowed this and carried on would export a
     * file with a transparent band where the paper failed to load, which is the failure a person
     * cannot see and cannot report. The exception is allowed out unchanged, so the caller's own
     * message reaches the export button.
     */
    @Test
    fun aFailingRendererAbortsTheRender() {
        val rect = RectPx(0, 0, 300, 100)
        assertFailsWith<IllegalStateException> {
            RegionRenderer.render(doc(emptyList()), tilesOf(), rect, null, null) { block ->
                if (block.x > 200) error("that texture is not readable") else solidRect(block, 1, 1, 1)
            }
        }
    }

    /**
     * THE GUARDS RUN BEFORE THE CALLBACK, on every door and for every kind of refusal.
     *
     * An impossible region must cost a sentence, not a texture decode: `MAX_REGION_PX` exists to
     * stop a phone allocating 8 GB, and a paper renderer that decoded four 1024² textures on the way
     * to being told the region was too big would have spent real memory to be refused. A negative
     * side is a caller bug and is checked in the same place. An empty region is neither — it is a
     * legal thing to ask about — and returns empty without asking for paper either, because there
     * are no pixels to put the paper in.
     */
    @Test
    fun aRefusedOrEmptyRequestNeverAsksForPaper() {
        var calls = 0
        val renderer = { _: RectPx -> calls++; ByteArray(4) }
        val d = doc(emptyList())
        assertFailsWith<RegionException> {
            RegionRenderer.render(d, tilesOf(), RectPx(0, 0, 4096, 4096), null, null, renderer)
        }
        assertFailsWith<IllegalArgumentException> {
            RegionRenderer.render(d, tilesOf(), RectPx(0, 0, -4, 4), null, null, renderer)
        }
        assertFailsWith<RegionException> {
            RegionRenderer.renderPremultiplied(d, tilesOf(), RectPx(0, 0, 4096, 4096), null, null, renderer)
        }
        assertContentEquals(
            ByteArray(0), RegionRenderer.render(d, tilesOf(), RectPx(0, 0, 0, 8), null, null, renderer),
        )
        assertEquals(0, calls, "not one pixel of paper for a request that cannot be answered")
    }

    // ── fixtures ────────────────────────────────────────────────────────────────

    /**
     * A paper that DEPENDS ON WHERE IT IS ASKED FOR, in absolute document coordinates.
     *
     * Two flat halves rather than a smooth gradient, because a gradient would let an off-by-one at a
     * block edge hide inside a tolerance; two hard values cannot. The left half is (200,40,60) and
     * the right is (20,80,220), and the dividing line is at document x = 128 — deliberately NOT on a
     * block boundary, so the seam tests have a value change to catch inside a block.
     */
    private fun paperOf(block: RectPx): ByteArray = solidRect(block, 200, 40, 60, right = 20, greenRight = 80, blueRight = 220)

    private fun solidRect(block: RectPx, r: Int, g: Int, b: Int, right: Int = r, greenRight: Int = g, blueRight: Int = b): ByteArray {
        val out = ByteArray(block.w * block.h * 4)
        var i = 0
        for (y in 0 until block.h) {
            for (x in 0 until block.w) {
                val east = block.x + x >= 128
                out[i] = (if (east) right else r).toByte()
                out[i + 1] = (if (east) greenRight else g).toByte()
                out[i + 2] = (if (east) blueRight else b).toByte()
                out[i + 3] = 255.toByte()
                i += 4
            }
        }
        return out
    }

    /** The composition this row removed, written out so the tests above can say what changed. */
    private fun oldPostStack(art: List<Int>, paper: List<Int>): List<Int> {
        val alpha = art[3]
        return (0..2).map { c -> (art[c] * alpha + paper[c] * (255 - alpha) + 127) / 255 } + 255
    }

    private data class TKey(val layer: String, val cel: String, val tx: Int, val ty: Int)

    private class FakeTiles(private val map: Map<TKey, ByteArray>) : TileSource {
        override fun tile(layerId: String, celId: String, tx: Int, ty: Int): ByteArray? = map[TKey(layerId, celId, tx, ty)]
    }

    private fun tilesOf(vararg pairs: Pair<TKey, ByteArray>): TileSource = FakeTiles(mapOf(*pairs))

    /** The same tile at every tile coordinate [rect] touches, so a layer covers the whole region. */
    private fun tilesCovering(rect: RectPx, one: Pair<TKey, ByteArray>): Map<TKey, ByteArray> {
        val (key, bytes) = one
        val out = HashMap<TKey, ByteArray>()
        for (ty in floorDiv(rect.y, 256)..floorDiv(rect.y + rect.h - 1, 256)) {
            for (tx in floorDiv(rect.x, 256)..floorDiv(rect.x + rect.w - 1, 256)) {
                out[TKey(key.layer, key.cel, tx, ty)] = bytes
            }
        }
        return out
    }

    /** Floor division, because a document pixel may be negative and `Int./` truncates towards zero. */
    private fun floorDiv(v: Int, d: Int): Int = floor(v.toDouble() / d).toInt()

    /** A whole tile of one PREMULTIPLIED colour, which is the form a [TileSource] answers in. */
    private fun solid(r: Int, g: Int, b: Int, a: Int): ByteArray {
        val t = ByteArray(RegionRenderer.TILE_BYTES)
        var i = 0
        while (i < t.size) {
            t[i] = r.toByte(); t[i + 1] = g.toByte(); t[i + 2] = b.toByte(); t[i + 3] = a.toByte()
            i += 4
        }
        return t
    }

    private fun layer(id: String, blend: BlendMode = BlendMode.NORMAL) = Layer(
        id = id, name = id, kind = LayerKind.PAINT, blend = blend, cels = listOf(Cel("$id-cel")),
    )

    private fun doc(layers: List<Layer>) = JbDocument(
        id = "d", name = "D",
        boards = listOf(Board("canvas", "Canvas", BoardKind.CANVAS, RectPx(0, 0, 64, 64))),
        layers = layers,
    )

    /** One pixel of the rendered region as r, g, b, a. */
    private fun pixelAt(out: ByteArray, rect: RectPx, x: Int, y: Int): List<Int> {
        val i = ((y - rect.y) * rect.w + (x - rect.x)) * 4
        return listOf(out[i].toInt() and 0xFF, out[i + 1].toInt() and 0xFF, out[i + 2].toInt() and 0xFF, out[i + 3].toInt() and 0xFF)
    }
}
