package cc.joycreator.joybrush.core.doc

import cc.joycreator.joybrush.core.paint.Tiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The document model. The rules that matter most here are the ones a renderer would otherwise crash
 * on, so most of these tests build a document that is wrong in exactly ONE way and insist that
 * [DocOps.validate] says so in exactly one sentence.
 */
class DocModelTest {

    /** Fixed ids in order, so a test can say which id it means. */
    private fun ids(): () -> String {
        var n = 0
        return { "id${n++}" }
    }

    private fun fresh(w: Int = 800, h: Int = 600) = DocOps.newDocument("doc", "Test", w, h, ids())

    /**
     * Swaps the boards out for fresh ones. `activeBoardId` is moved with them, otherwise rule 9 fires
     * as well and the test below would be counting two problems when it means to break one thing.
     */
    private fun JbDocument.replacingBoards(vararg boards: Board): JbDocument =
        copy(boards = boards.toList(), activeBoardId = boards.firstOrNull()?.id)

    // ---------- 1. a new document is valid and has the shape the blueprint promises ----------

    @Test
    fun newDocumentIsValidAndMinimal() {
        val doc = fresh()
        assertEquals(emptyList(), DocOps.validate(doc))
        assertEquals(1, doc.boards.size)
        assertEquals(1, doc.layers.size)
        assertEquals(1, doc.layers.single().cels.size)
        assertEquals("Board 1", doc.boards.single().name)
        assertEquals(BoardKind.CANVAS, doc.boards.single().kind)
        assertEquals(RectPx(0, 0, 800, 600), doc.boards.single().rect)
        assertEquals("Layer 1", doc.layers.single().name)
        assertEquals(LayerKind.PAINT, doc.layers.single().kind)
        assertNull(doc.layers.single().animatedIn)
        assertEquals(doc.boards.single().id, doc.activeBoardId)
        assertEquals(doc.layers.single().id, doc.activeLayerId)
    }

    @Test
    fun newDocumentTakesEveryIdFromTheCaller() {
        var n = 0
        val doc = DocOps.newDocument("d", "n", 10, 10) { "fixed${n++}" }
        assertEquals("fixed0", doc.boards.single().id)
        assertEquals("fixed1", doc.layers.single().id)
        assertEquals("fixed2", doc.layers.single().cels.single().id)
    }

    // ---------- 2. the round trip, on a document that uses every kind ----------

    /** Every board kind, an animated INK layer whose frames 2 and 3 share one cel, sparse tiles. */
    private fun richDocument(): JbDocument {
        val doc = fresh()
        val canvas = doc.boards.single()
        val anim = Board(
            id = "b-anim", name = "Ani", kind = BoardKind.ANIMATION,
            rect = RectPx(0, 0, 128, 64), fps = 24f,
            frames = listOf(Frame("f1", 1), Frame("f2", 2), Frame("f3", 1)),
        )
        val sprite = Board(
            id = "b-spr", name = "Spr", kind = BoardKind.SPRITE,
            rect = RectPx(-16, -32, 64, 64), grid = SpriteGrid(4, 4, 16, 16),
        )
        val puppet = Board("b-pup", "Pup", BoardKind.PUPPET, RectPx(0, 0, 32, 32), clipToBoard = true)
        val character = Board("b-cha", "Cha", BoardKind.CHARACTER, RectPx(0, 0, 32, 32))

        val paint = Layer(
            id = "l-paint", name = "Paint", kind = LayerKind.PAINT,
            cels = listOf(Cel("c-p", tiles = listOf("0_0", "-1_2", "3_-4"))),
        )
        val ink = Layer(
            id = "l-ink", name = "Ink", kind = LayerKind.INK, opacity = 0.5f,
            blend = BlendMode.MULTIPLY, animatedIn = "b-anim",
            cels = listOf(
                Cel("c-i1", strokesFile = "strokes/ink-1.jsonl"),
                Cel("c-i2", strokesFile = "strokes/ink-2.jsonl"),
            ),
            // frames 2 and 3 share a cel — that is how "hold" is stored
            frameCel = mapOf("f1" to "c-i1", "f2" to "c-i2", "f3" to "c-i2"),
        )

        return doc.copy(
            paper = Paper(color = "#102030", textureId = "grain-soft", textureScale = 2.5f, includeInExport = true),
            boards = listOf(canvas, anim, sprite, puppet, character),
            layers = listOf(paint, ink),
            activeLayerId = "l-ink",
            activeBoardId = "b-anim",
        )
    }

    @Test
    fun encodeDecodeRoundTripsTheWholeModel() {
        val doc = richDocument()
        assertEquals(emptyList(), DocOps.validate(doc), "the fixture itself must be valid")
        val back = DocJson.decode(DocJson.encode(doc))
        assertEquals(doc, back)
    }

    @Test
    fun framesCanShareACel() {
        val ink = richDocument().layers.last { it.id == "l-ink" }
        assertEquals("c-i1", DocOps.celFor(ink, "f1")?.id)
        assertEquals("c-i2", DocOps.celFor(ink, "f2")?.id)
        assertEquals("c-i2", DocOps.celFor(ink, "f3")?.id, "f3 holds f2's cel")
    }

    // ---------- 3. reading the future ----------

    @Test
    fun unknownKeysAreIgnoredAtEveryLevel() {
        val doc = fresh()
        val json = DocJson.encode(doc)
            .replaceFirst("\"format\"", "\"future\": 1,\n  \"format\"")
            .replaceFirst("\"visible\"", "\"fromTheFuture\": \"yes\",\n    \"visible\"")
        val back = DocJson.decode(json)
        assertEquals(doc, back)
    }

    @Test
    fun aNewerVersionStillDecodesAndIsReportedInWords() {
        val doc = fresh().copy(version = 2)
        val back = DocJson.decode(DocJson.encode(doc))
        assertEquals(2, back.version, "decoding must not refuse a newer document")
        val problems = DocOps.validate(back)
        assertEquals(1, problems.size, "got $problems")
        assertTrue(
            problems.single().contains("newer Joy Brush"),
            "the message must say why, got: ${problems.single()}",
        )
    }

    // ---------- 4. unreadable json fails loudly, not cryptically ----------

    @Test
    fun unreadableJsonThrowsDocException() {
        assertFailsWith<DocException> { DocJson.decode("{ not json") }
        assertFailsWith<DocException> { DocJson.decode("[]") }
    }

    // ---------- 5. one broken thing, one sentence ----------

    /** Asserts the document is broken in exactly one way, and that validate() says one thing. */
    private fun assertOneProblem(doc: JbDocument, vararg mustMention: String) {
        val problems = DocOps.validate(doc)
        assertEquals(1, problems.size, "expected exactly one problem, got $problems")
        for (word in mustMention) {
            assertTrue(problems.single().contains(word), "\"$word\" missing from: ${problems.single()}")
        }
    }

    // rule 2 — duplicate ids
    @Test
    fun rule2_duplicateBoardId() {
        val doc = fresh()
        val twin = doc.boards.single().copy(name = "Board 2")
        assertOneProblem(doc.copy(boards = doc.boards + twin), "boards")
    }

    @Test
    fun rule2_duplicateCelIdWithinALayer() {
        val doc = fresh()
        val celId = doc.layers.single().cels.single().id
        // An animated layer, so rule 6 stays quiet and this is the ONLY thing wrong.
        val board = Board("b", "B", BoardKind.ANIMATION, RectPx(0, 0, 10, 10), frames = listOf(Frame("f1")))
        val l = doc.layers.single().copy(
            animatedIn = "b",
            cels = listOf(Cel(celId), Cel(celId)),
            frameCel = mapOf("f1" to celId),
        )
        assertOneProblem(doc.replacingBoards(board).copy(layers = listOf(l)), "cels")
    }

    // rule 3 — a board with no room
    @Test
    fun rule3_emptyBoardRect() {
        val doc = fresh().replacingBoards(Board("b", "B", BoardKind.CANVAS, RectPx(0, 0, 0, 600)))
        assertOneProblem(doc, "room")
    }

    // rule 4 — an animation board that cannot play
    @Test
    fun rule4_animationBoardWithNoFrames() {
        val doc = fresh().replacingBoards(Board("b", "B", BoardKind.ANIMATION, RectPx(0, 0, 10, 10)))
        assertOneProblem(doc, "no frames")
    }

    @Test
    fun rule4_holdFramesMustBeAtLeastOne() {
        val board = Board("b", "B", BoardKind.ANIMATION, RectPx(0, 0, 10, 10), frames = listOf(Frame("f", 0)))
        assertOneProblem(fresh().replacingBoards(board), "held")
    }

    @Test
    fun rule4_fpsMustBePlayable() {
        val board = Board("b", "B", BoardKind.ANIMATION, RectPx(0, 0, 10, 10), fps = 120f, frames = listOf(Frame("f")))
        assertOneProblem(fresh().replacingBoards(board), "fps")
    }

    // rule 5 — a sprite board with no grid
    @Test
    fun rule5_spriteBoardWithNoGrid() {
        assertOneProblem(fresh().replacingBoards(Board("b", "B", BoardKind.SPRITE, RectPx(0, 0, 10, 10))), "grid")
    }

    // rule 6 — a static layer is exactly one cel
    @Test
    fun rule6_staticLayerMustHaveOneCel() {
        val doc = fresh()
        val l = doc.layers.single()
        assertOneProblem(doc.copy(layers = listOf(l.copy(cels = l.cels + Cel("other")))), "cels")
    }

    // rule 7 — an animated layer covers every frame with a cel it has
    @Test
    fun rule7_animatedLayerMissingAFrame() {
        val doc = fresh()
        val celId = doc.layers.single().cels.single().id
        val board = Board("b", "B", BoardKind.ANIMATION, RectPx(0, 0, 10, 10), frames = listOf(Frame("f1"), Frame("f2")))
        val l = doc.layers.single().copy(animatedIn = "b", frameCel = mapOf("f1" to celId))
        assertOneProblem(doc.replacingBoards(board).copy(layers = listOf(l)), "frame")
    }

    @Test
    fun rule7_frameMayNotPointAtACelTheLayerDoesNotHave() {
        val doc = fresh()
        val board = Board("b", "B", BoardKind.ANIMATION, RectPx(0, 0, 10, 10), frames = listOf(Frame("f1")))
        val l = doc.layers.single().copy(animatedIn = "b", frameCel = mapOf("f1" to "ghost"))
        assertOneProblem(doc.replacingBoards(board).copy(layers = listOf(l)), "cel")
    }

    // rule 8 — pixels and strokes do not mix
    @Test
    fun rule8_paintCelHasNoStrokes() {
        val doc = fresh()
        val l = doc.layers.single()
        val bad = l.copy(cels = listOf(l.cels.single().copy(strokesFile = "strokes/x.jsonl")))
        assertOneProblem(doc.copy(layers = listOf(bad)), "paint")
    }

    @Test
    fun rule8_inkCelHasNoTiles() {
        val doc = fresh()
        val l = doc.layers.single().copy(kind = LayerKind.INK)
        val bad = l.copy(cels = listOf(l.cels.single().copy(tiles = listOf("0_0"))))
        assertOneProblem(doc.copy(layers = listOf(bad)), "ink")
    }

    // rule 9 — the saved "where I was" must point at something
    @Test
    fun rule9_activeLayerMustExist() {
        assertOneProblem(fresh().copy(activeLayerId = "ghost"), "active layer")
    }

    // rule 10 — values a renderer multiplies by or paints with
    @Test
    fun rule10_opacityIsZeroToOne() {
        val doc = fresh()
        val bad = doc.layers.single().copy(opacity = 1.5f)
        assertOneProblem(doc.copy(layers = listOf(bad)), "opaque")
    }

    @Test
    fun rule10_paperColourIsHex() {
        assertOneProblem(fresh().copy(paper = Paper(color = "cornflower")), "paper")
    }

    @Test
    fun rule10_rejectsColoursThatLookCloseEnough() {
        // three-digit shorthand, eight-digit with alpha, a name, and a missing hash
        for (bad in listOf("#FFF", "#FFFFFFFF", "white", "FFFFFF")) {
            val problems = DocOps.validate(fresh().copy(paper = Paper(color = bad)))
            assertEquals(1, problems.size, "\"$bad\" should be rejected, got $problems")
        }
    }

    @Test
    fun rule10_notANumberIsNotInRange() {
        // NaN fails every `<` and `>` comparison, so a naive range check would wave it through.
        val doc = fresh()
        val nanLayer = doc.copy(layers = listOf(doc.layers.single().copy(opacity = Float.NaN)))
        assertTrue(DocOps.validate(nanLayer).any { it.contains("opaque") }, "NaN opacity must be caught")

        val nanFps = doc.replacingBoards(
            Board("b", "B", BoardKind.ANIMATION, RectPx(0, 0, 9, 9), fps = Float.NaN, frames = listOf(Frame("f"))),
        )
        assertTrue(DocOps.validate(nanFps).any { it.contains("fps") }, "NaN fps must be caught")
    }

    // ---------- 6. celFor ----------

    @Test
    fun rule6_celForStaticAnimatedAndMissing() {
        val doc = richDocument()
        val paint = doc.layers.first { it.id == "l-paint" }
        val ink = doc.layers.first { it.id == "l-ink" }

        // static: the only cel, whatever frame you ask about, even none at all
        assertEquals("c-p", DocOps.celFor(paint, "f1")?.id)
        assertEquals("c-p", DocOps.celFor(paint, null)?.id)

        assertEquals("c-i1", DocOps.celFor(ink, "f1")?.id)
        assertNull(DocOps.celFor(ink, "nope"), "a frame that is not on the board")
        assertNull(DocOps.celFor(ink, null), "an animated layer needs a frame")

        val dangling = ink.copy(frameCel = mapOf("f1" to "ghost"))
        assertNull(DocOps.celFor(dangling, "f1"), "a mapping to a cel the layer does not have")
    }

    // ---------- 7. tile addressing on an unbounded canvas ----------

    @Test
    fun tileOfFloorsBothWays() {
        assertEquals(-1 to -1, DocOps.tileOf(-1, -1))
        assertEquals(0 to 1, DocOps.tileOf(255, 256))
        assertEquals(0 to 0, DocOps.tileOf(0, 0))
        assertEquals(0 to 0, DocOps.tileOf(255, 255))
        assertEquals(1 to 1, DocOps.tileOf(256, 256))
        assertEquals(-1 to 0, DocOps.tileOf(-1, 0))
        assertEquals(-2 to -1, DocOps.tileOf(-257, -256), "-257 floors down, not toward zero")
    }

    @Test
    fun keyIsTheTileCoordinate() {
        assertEquals("3_-2", DocOps.key(3, -2))
        assertEquals("0_0", DocOps.key(0, 0))
    }

    @Test
    fun keyAndTileOfAgree() {
        for (px in listOf(-600, -1, 0, 1, 255, 256, 1000)) {
            val (tx, ty) = DocOps.tileOf(px, px)
            assertEquals(DocOps.key(tx, ty), "${tx}_$ty")
        }
    }

    // ---------- 8. encoding is stable ----------

    @Test
    fun encodingIsStable() {
        val doc = richDocument()
        assertEquals(DocJson.encode(doc), DocJson.encode(doc))
        assertEquals(DocJson.encode(doc), DocJson.encode(doc.copy()))
    }

    @Test
    fun encodingIsPrettyAndStatesItsKeys() {
        val text = DocJson.encode(fresh())
        assertTrue(text.contains("\n"), "pretty printed, so it has lines")
        assertTrue(text.contains("\"format\": \"joybrush.document\""), "got:\n$text")
        // defaults are written out, so a reader never has to know them
        assertTrue(text.contains("\"color\": \"#FFFFFF\""), "got:\n$text")
        assertTrue(text.contains("\"textureId\": null"), "got:\n$text")
    }

    // ---------- the model on its own terms ----------

    @Test
    fun docTileSizeIsTheEngineTileSize() {
        // Cel.tiles holds the strings the ENGINE wrote with paint.Tiles. If these two constants ever
        // drift apart, every tile in every saved document silently points at the wrong place.
        assertEquals(Tiles.SIZE, TILE_SIZE)
    }

    @Test
    fun encodingIsStableWhateverOrderTheFrameMapWasBuiltIn() {
        val board = Board("b", "B", BoardKind.ANIMATION, RectPx(0, 0, 9, 9), frames = listOf(Frame("f1"), Frame("f2"), Frame("f3")))
        val cels = listOf(Cel("c1"), Cel("c2"), Cel("c3"))
        val l1 = Layer("l", "l", LayerKind.INK, animatedIn = "b", cels = cels,
            frameCel = linkedMapOf("f1" to "c1", "f2" to "c2", "f3" to "c3"))
        val l2 = l1.copy(frameCel = linkedMapOf("f3" to "c3", "f2" to "c2", "f1" to "c1"))
        val base = fresh().replacingBoards(board).copy(layers = listOf(l1), activeLayerId = "l")
        val shuffled = base.copy(layers = listOf(l2))

        // Same document, same bytes — so a re-save is not a change and a diff means something.
        assertEquals(DocJson.encode(base), DocJson.encode(shuffled))
        assertEquals(base, shuffled, "sorting for output must not change the document")
        // And re-saving what we just read changes nothing.
        val once = DocJson.encode(base)
        assertEquals(once, DocJson.encode(DocJson.decode(once)))
    }

    @Test
    fun paperIsNotALayerAndBoardsAreNotLayers() {
        val doc = fresh()
        assertEquals(LayerKind.PAINT, doc.layers.single().kind)
        assertEquals(1, doc.boards.size)
        assertTrue(doc.paper.includeInExport.not(), "\"include paper\" is off by default")
    }

    @Test
    fun validateAcceptsEveryBoundaryValue() {
        val doc = richDocument().copy(
            paper = Paper(color = "#000000"),
            layers = listOf(
                Layer("a", "a", LayerKind.PAINT, opacity = 0f, cels = listOf(Cel("c"))),
                Layer("b", "b", LayerKind.PAINT, opacity = 1f, cels = listOf(Cel("c"))),
            ),
            activeLayerId = "a",
        )
        assertEquals(emptyList(), DocOps.validate(doc), "0 and 1 are legal opacities")
    }

    // ---------- Lead rulings, 2026-09-28 ----------

    @Test
    fun tileSizeIsTheEngineTileSizeNotASecondCopy() {
        // `Cel.tiles` holds the keys the ENGINE wrote with paint.Tiles, so doc.TILE_SIZE must be that
        // same constant rather than a second literal — two literals drift, and every tile in every
        // saved document then points somewhere silent and wrong.
        assertEquals(Tiles.SIZE, TILE_SIZE)
        assertEquals(0, DocOps.tileOf(Tiles.SIZE - 1, 0).first, "the tile before the edge")
        assertEquals(1, DocOps.tileOf(Tiles.SIZE, 0).first, "tiles break where the engine breaks them")
    }

    @Test
    fun rule11_aDocumentNeedsABoardAndALayer() {
        val doc = fresh()
        assertOneProblem(doc.copy(boards = emptyList(), activeBoardId = null), "no boards")
        assertOneProblem(doc.copy(layers = emptyList(), activeLayerId = null), "no layers")
        // Empty in both is two separate things wrong, and both are said.
        assertEquals(2, DocOps.validate(doc.copy(boards = emptyList(), layers = emptyList(),
            activeBoardId = null, activeLayerId = null)).size)
    }

    @Test
    fun rule7_aFrameMapMayNotNameAFrameThatDoesNotExist() {
        val doc = fresh()
        val celId = doc.layers.single().cels.single().id
        val board = Board("b", "B", BoardKind.ANIMATION, RectPx(0, 0, 9, 9), frames = listOf(Frame("f1")))
        val l = doc.layers.single().copy(animatedIn = "b", frameCel = mapOf("f1" to celId, "ghost" to celId))
        assertOneProblem(doc.replacingBoards(board).copy(layers = listOf(l)), "not a frame")

        // Deleting a frame leaves the mapping behind; that is caught too, not just invented keys.
        val shortened = doc.replacingBoards(board.copy(frames = emptyList()))
            .copy(layers = listOf(doc.layers.single().copy(animatedIn = "b", frameCel = mapOf("f1" to celId))))
        val problems = DocOps.validate(shortened)
        assertTrue(problems.any { it.contains("no frames") }, "got $problems")
        assertTrue(problems.any { it.contains("not a frame") }, "the stale mapping must be named too")
    }

    @Test
    fun rule10_paperTextureScaleHasToBeSane() {
        val doc = fresh()
        for (bad in listOf(0f, -1f, 64.5f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val problems = DocOps.validate(doc.copy(paper = Paper(textureScale = bad)))
            assertEquals(1, problems.size, "textureScale $bad should be rejected, got $problems")
            assertTrue(problems.single().contains("texture"), "got: ${problems.single()}")
        }
        // The ends themselves are legal: 0 is excluded, 64 is not.
        for (good in listOf(0.0001f, 1f, 64f)) {
            assertEquals(emptyList(), DocOps.validate(doc.copy(paper = Paper(textureScale = good))),
                "textureScale $good should be accepted")
        }
    }

    @Test
    fun newDocumentRefusesADocumentWithNoRoom() {
        for ((w, h) in listOf(0 to 600, 800 to 0, 0 to 0, -1 to 600, 800 to -1)) {
            assertFailsWith<IllegalArgumentException>("${w}x$h should be refused") {
                DocOps.newDocument("d", "n", w, h) { "x" }
            }
        }
        // One pixel is still a room.
        assertEquals(emptyList(), DocOps.validate(DocOps.newDocument("d", "n", 1, 1) { "x" }))
    }
}
