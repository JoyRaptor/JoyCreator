package cc.joycreator.joybrush.core.doc

import cc.joycreator.joybrush.core.paint.Tiles
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
            // JB-2.23 (R48): a mask, so the walk reaches its keys too.
            mask = Cel("mask", tiles = listOf("0_0")),
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
    //
    // The test that used to live here, `unknownKeysAreIgnoredAtEveryLevel`, is DELETED, not
    // renamed and not rewritten. It asserted the behaviour R31 removes: it injected two foreign
    // keys and required the document to come back equal. A test that keeps its name while
    // asserting the opposite is worse than no test, so it is gone and its replacement is section
    // 3b below, which asserts the opposite with the same `replaceFirst` trick.

    @Test
    fun aNewerVersionStillDecodesAndIsReportedInWords() {
        val doc = fresh().copy(version = DOC_VERSION + 1)
        val back = DocJson.decode(DocJson.encode(doc))
        assertEquals(DOC_VERSION + 1, back.version, "decoding must not refuse a newer document")
        val problems = DocOps.validate(back)
        assertEquals(1, problems.size, "got $problems")
        assertTrue(
            problems.single().contains("newer Joy Brush"),
            "the message must say why, got: ${problems.single()}",
        )
    }

    // ---------- 3b. a key this build does not know is refused BY NAME (R31, JB-0.02d) ----------
    //
    // `ignoreUnknownKeys` is still `true` (orchestrator's ruling, Q0), so the walk in `DocJson` is
    // the WHOLE guard. A hole in that walk fails OPEN — the key is dropped silently and the next
    // autosave loses it under a current version stamp — which is the exact failure this section
    // exists to stop. So most of these tests are about DEPTH: they put the key where a shallow
    // walk would never look.

    /** The eight rows of the key table, as a path → keys map, so a drift is named, not counted. */
    private fun expectedKeyTable(): Map<String, Set<String>> = mapOf(
        "\$" to setOf(
            "format", "version", "id", "name", "paper", "boards", "layers",
            "activeLayerId", "activeBoardId",
        ),
        "\$.paper" to setOf(
            "color", "textureId", "textureScale", "includeInExport",
            // v4 (JB-9.05): the owner's paper controls. The first four are v3 and keep their names.
            "lookId", "tint", "show", "bite", "light", "screenTransparent",
        ),
        "\$.boards[]" to setOf("id", "name", "kind", "rect", "clipToBoard", "fps", "frames", "grid", "tiled", "currentFrameId", "locked"),
        "\$.boards[].rect" to setOf("x", "y", "w", "h"),
        "\$.boards[].frames[]" to setOf("id", "holdFrames"),
        "\$.boards[].grid" to setOf("cols", "rows", "cellW", "cellH"),
        "\$.layers[]" to setOf(
            "id", "name", "kind", "visible", "locked", "opacity", "blend", "animatedIn",
            "cels", "frameCel", "mask", "clip", "sharedCelId", "regions",
        ),
        "\$.layers[].cels[]" to setOf("id", "tiles", "strokesFile"),
        "\$.layers[].mask" to setOf("id", "tiles", "strokesFile"),
    )

    /**
     * The ONE place in `document.json` whose object keys are DATA rather than keys of the format:
     * `frameCel` maps a frame id to a cel id, so `f1`/`f2`/`f3` are ids somebody chose. Collecting
     * them would make [everyKeyInAFileThisBuildWritesIsAKnownKey] red for a CORRECT implementation,
     * so they are stepped over here.
     */
    private val KEYS_THAT_ARE_DATA = setOf("frameCel")

    /** Collects the keys at every object in a parsed document, with array indices collapsed. */
    private fun collectKeys(node: JsonElement, path: String, into: MutableMap<String, MutableSet<String>>) {
        when (node) {
            is JsonObject -> {
                if (path.substringAfterLast('.') !in KEYS_THAT_ARE_DATA) {
                    into.getOrPut(path) { sortedSetOf() }.addAll(node.keys)
                }
                for ((key, value) in node) collectKeys(value, "$path.$key", into)
            }
            is JsonArray -> node.forEach { collectKeys(it, "$path[]", into) }
            else -> Unit
        }
    }

    // 1 — the control. Without it, tests 2-10 could all be green because the fixture was broken.
    @Test
    fun aFileWithNoKeysThisBuildDoesNotKnowStillDecodes() {
        for (doc in listOf(fresh(), richDocument())) {
            val text = DocJson.encode(doc)
            assertEquals(doc, DocJson.decode(text), "a file this build wrote must read back as itself")
        }
    }

    // 2
    @Test
    fun anUnknownKeyAtTheRootIsRefusedAndNamed() {
        val json = DocJson.encode(fresh())
            .replaceFirst("\"format\"", "\"future\": 1,\n  \"format\"")
        val e = assertFailsWith<DocException> { DocJson.decode(json) }
        assertTrue(e.message!!.contains("future"), "the key must be named: ${e.message}")
    }

    // 3
    @Test
    fun anUnknownKeyInsideALayerIsRefusedAndNamed() {
        val json = DocJson.encode(fresh())
            .replaceFirst("\"visible\"", "\"fromTheFuture\": \"yes\",\n      \"visible\"")
        val e = assertFailsWith<DocException> { DocJson.decode(json) }
        assertTrue(e.message!!.contains("fromTheFuture"), "the key must be named: ${e.message}")
    }

    // 4
    @Test
    fun anUnknownKeyInsideABoardIsRefused() {
        val json = DocJson.encode(richDocument())
            .replaceFirst("\"clipToBoard\"", "\"boardFromTheFuture\": true,\n      \"clipToBoard\"")
        val e = assertFailsWith<DocException> { DocJson.decode(json) }
        assertTrue(e.message!!.contains("boardFromTheFuture"), "the key must be named: ${e.message}")
        // The path, so "a key called `id`" never arrives without a location.
        assertTrue(e.message!!.contains("\$.boards[0]"), "the path must be named: ${e.message}")
    }

    /**
     * 5 — THE BURIED-KEY TEST, and the reason this section exists.
     *
     * Both keys are two descriptor hops below an object that is itself inside an array, so a walk
     * that only looked at the root, or only one level down, would pass tests 2-4 and fail here.
     * That is what makes it non-vacuous: the shallow walk is exactly the walk `ignoreUnknownKeys`
     * would allow to be wrong.
     */
    @Test
    fun anUnknownKeyAtTheDeepestLevelIsRefused() {
        // layers[0].cels[0] — root → layers[] → cels[] → Cel.
        val inCel = DocJson.encode(richDocument())
            .replaceFirst("\"strokesFile\"", "\"celFromTheFuture\": 1,\n            \"strokesFile\"")
        val celMessage = assertFailsWith<DocException> { DocJson.decode(inCel) }.message!!
        assertTrue(celMessage.contains("celFromTheFuture"), celMessage)
        assertTrue(celMessage.contains("\$.layers[0].cels[0]"), celMessage)

        // boards[1].frames[0] — root → boards[] → frames[] → Frame.
        val inFrame = DocJson.encode(richDocument())
            .replaceFirst("\"holdFrames\"", "\"frameFromTheFuture\": 1,\n            \"holdFrames\"")
        val frameMessage = assertFailsWith<DocException> { DocJson.decode(inFrame) }.message!!
        assertTrue(frameMessage.contains("frameFromTheFuture"), frameMessage)
        assertTrue(frameMessage.contains("\$.boards[1].frames[0]"), frameMessage)
    }

    /**
     * 6 — the DRIFT GUARD, and the only one of these tests that is about the file rather than
     * about one bad key.
     *
     * It walks what `DocJson.encode` ACTUALLY writes and compares every object's key set to the
     * documented table. A field added to `DocModel.kt` without the table changing turns this red,
     * which is the "hand-maintained list of a data class's fields is the unsafe kind of trap"
     * failure this project has been bitten by.
     */
    @Test
    fun everyKeyInAFileThisBuildWritesIsAKnownKey() {
        val found = sortedMapOf<String, MutableSet<String>>()
        collectKeys(Json.parseToJsonElement(DocJson.encode(richDocument())), "$", found)
        assertEquals(
            expectedKeyTable(),
            found.mapValues { it.value.toSet() },
            "the file this build writes must not say a key the table does not list",
        )
        // And the map was really walked: `richDocument` has an animated layer with a frameCel, so
        // if the walker had stopped at the first object the table above would be short.
        assertTrue(found.keys.any { it.endsWith("cels[]") }, "the walk did not reach the cels: ${found.keys}")
    }

    // 7 — Decision 2, and the test that pins the version gate in the right place.
    @Test
    fun aNewerVersionWithAnUnknownKeyStillDecodesAndIsStillReportedAsNewer() {
        val json = DocJson.encode(fresh().copy(version = DOC_VERSION + 1))
            .replaceFirst("\"format\"", "\"audio\": [],\n  \"format\"")
        val back = DocJson.decode(json)
        assertEquals(DOC_VERSION + 1, back.version, "a file from a newer build must still decode")
        val problems = DocOps.validate(back)
        assertEquals(1, problems.size, "got $problems")
        assertTrue(
            problems.single().contains("newer Joy Brush"),
            "the version sentence must still win over any key sentence, got: ${problems.single()}",
        )
    }

    // 8 — an OLDER file must not be refused. `version` 1 <= DOC_VERSION, so the scan runs and
    // every key in the fixture has to be a key this build knows. Copied verbatim out of
    // `EnumFreezeTest.documentWith`, which is `private` to another class in a file outside this
    // row's owner area and so cannot be called from here.
    @Test
    fun aVersionOneDocumentStillOpens() {
        val v1 = """
            { "format": "joybrush.document", "version": 1, "id": "d", "name": "D", "boards": [ { "id": "b", "name": "B", "kind": "CANVAS", "rect": { "x": 0, "y": 0, "w": 8, "h": 8 } } ], "layers": [ { "id": "l", "name": "L", "kind": "PAINT", "blend": "NORMAL", "cels": [ { "id": "c" } ] } ] }
        """.trimIndent()
        val back = DocJson.decode(v1)
        assertEquals(1, back.version)
        assertEquals(BoardKind.CANVAS, back.boards.single().kind)
        // The gate is `>`, not `>=`: the same file at the current version is scanned too.
        val atCurrent = v1.replace("\"version\": 1", "\"version\": $DOC_VERSION")
            .replaceFirst("\"B\"", "\"B\", \"fromTheFuture\": 1")
        assertFailsWith<DocException> { DocJson.decode(atCurrent) }
    }

    // 9 — names the R31 bug itself: a document CAME BACK. This fails the moment any future change
    // lets one through again, whatever the reason.
    @Test
    fun nothingIsEverReSavedWithoutAKeyItDidNotUnderstand() {
        val json = DocJson.encode(richDocument())
            .replaceFirst("\"color\"", "\"paperFromTheFuture\": 1,\n    \"color\"")
        // There is no path to a JbDocument at all, so there is nothing an autosave could write back
        // stripped — and the key is still in the text afterwards, because nothing consumed it.
        assertFailsWith<DocException> { DocJson.decode(json) }
        assertTrue(json.contains("paperFromTheFuture"), "the fixture lost its own key")
    }

    // 10 — `JbArchive` catches only DocException (JbArchive.kt:383), so a raw library exception
    // escaping decode would crash the archive read instead of being reported to a person.
    @Test
    fun aFileWithAnUnknownKeyStillRefusesWithADocExceptionNotALibraryError() {
        val json = DocJson.encode(fresh())
            .replaceFirst("\"format\"", "\"future\": 1,\n  \"format\"")
        val e = assertFailsWith<DocException> { DocJson.decode(json) }
        assertEquals(DocException::class.java, e::class.java, "exactly DocException, not a subclass and not a library type")
        // The key sentence, not the generic parse failure — this proves WHERE it came from.
        assertFalse(
            e.message!!.startsWith("document.json cannot be read"),
            "the refusal must be the walk's own sentence, got: ${e.message}",
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

    // ---------- the paper, v4 (JB-9.05) -------------------------------------------------------------
    //
    // `Paper` gains the owner's own controls here: which look, tinted how strongly, how visible, how
    // much the brushes feel it, and whether its relief is lit. The fields that make a document SAVED
    // are below; the fields that make it CORRECT are [PaperStateTest] in the paper package, because
    // resolving an id needs the catalogue and the catalogue is not this package's business.

    /** A paper with every field set, so a round trip cannot pass by leaving a field at its default. */
    private fun everyPaperField() = Paper(
        color = "#112233",
        textureId = "pulp_artisan",
        textureScale = 2.5f,
        includeInExport = true,
        lookId = "off_white",
        tint = "#445566",
        show = 0.4f,
        bite = 0.6f,
        light = false,
    )

    /** Every field set, out and back unchanged. The whole point of a serialised setting. */
    @Test
    fun everyPaperFieldSurvivesARoundTrip() {
        val doc = fresh().copy(paper = everyPaperField())
        val back = DocJson.decode(DocJson.encode(doc))
        assertEquals(everyPaperField(), back.paper, "a paper must come back exactly as it went in")
        assertEquals(7, back.version, "the codec stamps the current region document version")
        assertEquals(DOC_VERSION, back.version, "and reads it from the one constant, not a literal here")
    }

    /**
     * **A v3 file opens with every v4 field at its default, and the defaults reproduce today exactly.**
     * The cost of a version bump is that old files exist, and this is the test that says what happens
     * to them: a v3 document is white flat paper with no surface, show 1 and bite 1 — which is what
     * such a file meant under v3, so the drawing a person saved last month looks the same this month.
     *
     * The fixture is a real v3 shape written out in full, because a v3 file that is really a v4 file
     * with the new keys deleted would not prove the DEFAULTS, only that the fields are nullable.
     */
    @Test
    fun aVersionThreeDocumentOpensWithEveryPaperDefault() {
        val v3 = """
            {
              "format": "joybrush.document", "version": 3, "id": "d", "name": "D",
              "paper": { "color": "#F3EFE6", "textureId": "cloud_fine_256", "textureScale": 1.5, "includeInExport": true },
              "boards": [ { "id": "b", "name": "B", "kind": "CANVAS", "rect": { "x": 0, "y": 0, "w": 8, "h": 8 } } ],
              "layers": [ { "id": "l", "name": "L", "kind": "PAINT", "blend": "NORMAL", "cels": [ { "id": "c" } ] } ]
            }
        """.trimIndent()
        val back = DocJson.decode(v3)
        assertEquals(3, back.version, "the file's own version is kept; only the codec stamps on write")
        // The v3 fields are still read, not dropped.
        assertEquals("#F3EFE6", back.paper.color)
        assertEquals("cloud_fine_256", back.paper.textureId, "`textureId` keeps its name, so a v3 field means the same thing")
        assertEquals(1.5f, back.paper.textureScale)
        assertTrue(back.paper.includeInExport)
        // …and every v4 field arrives at the default that reproduces today's behaviour.
        assertNull(back.paper.lookId, "a v3 file has no look, so the paper is a flat colour")
        assertNull(back.paper.tint)
        assertEquals(1f, back.paper.show, "show 1 = fully visible, which is what a flat paper was")
        assertEquals(1f, back.paper.bite, "bite 1 = brushes feel the paper fully, which is what the old file meant")
        assertNull(back.paper.light, "null = inherit the look's default; with no look that is lit")
    }

    /** A brand new document is the v3 defaults exactly, which is what makes an old file look unchanged. */
    @Test
    fun aNewDocumentsPaperIsTheVersionThreePaper() {
        val p = fresh().paper
        assertEquals("#FFFFFF", p.color)
        assertNull(p.textureId)
        assertEquals(1f, p.textureScale)
        assertFalse(p.includeInExport)
        assertNull(p.lookId)
        assertNull(p.tint)
        assertEquals(1f, p.show)
        assertEquals(1f, p.bite)
        assertNull(p.light)
    }

    // `show` and `bite` are the two fractions a renderer multiplies by. Written as one test with a loop
    // because they are the same rule twice, and a loop makes the "both ends are legal" half as short as
    // two tests would.
    @Test
    fun showAndBiteMustBeFractions() {
        val doc = fresh()
        for (bad in listOf(-0.01f, 1.01f, 5f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertOneProblem(doc.copy(paper = Paper(show = bad)), "show")
            assertOneProblem(doc.copy(paper = Paper(bite = bad)), "bite")
        }
        // The ends are the paper's own two extremes and both are legal: show 0 is a flat colour with no
        // relief, bite 0 is a paper the brushes cannot feel.
        for (end in listOf(0f, 1f, 0.5f)) {
            assertEquals(emptyList(), DocOps.validate(doc.copy(paper = Paper(show = end, bite = end))))
        }
    }

    @Test
    fun theTintMustBeAColour() {
        val doc = fresh()
        for (bad in listOf("white", "#FFF", "0x445566", "#44556", "#GGGGGG", "445566", "")) {
            assertOneProblem(doc.copy(paper = Paper(tint = bad)), "tint")
        }
        assertEquals(emptyList(), DocOps.validate(doc.copy(paper = Paper(tint = "#445566"))))
        assertEquals(emptyList(), DocOps.validate(doc.copy(paper = Paper(tint = "#445566".lowercase()))))
    }

    /**
     * **An unknown catalogue id is NOT a validation error, and that is the assertion.** A drawing saved
     * with a paper a later build added must open here; refusing it in [DocOps.validate] would stop the
     * document loading over a background. [cc.joycreator.joybrush.core.paper.PaperState] resolves such an
     * id to smooth/flat and reports it, which is the right place for it.
     */
    @Test
    fun anUnknownCatalogueIdIsNotAValidationError() {
        val doc = fresh()
        assertEquals(
            emptyList(),
            DocOps.validate(doc.copy(paper = Paper(lookId = "rice_paper", textureId = "chalk_green"))),
            "DocOps knows nothing about the catalogue; it must not refuse an id it cannot check",
        )
    }

    /**
     * `textureScale` keeps its v3 rule — over 0, at most 64 — even though [cc.joycreator.joybrush.core.paper.PaperState]
     * clamps it to 0.25..4. The specialist's answer on JB-9.05: a clamp is not a validation rule, so a
     * file written under v3 stays valid instead of turning red on open. [rule10_paperTextureScaleHasToBeSane]
     * is that rule and it is unchanged by this row.
     */
    @Test
    fun thePaperScaleKeepsItsVersionThreeRule() {
        val doc = fresh()
        for (good in listOf(0.0001f, 1f, 4f, 64f)) {
            assertEquals(
                emptyList(), DocOps.validate(doc.copy(paper = Paper(textureScale = good))),
                "textureScale $good is legal under the v3 rule and must stay legal",
            )
        }
    }
}
