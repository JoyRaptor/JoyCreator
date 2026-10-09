package cc.joycreator.joybrush.core.doc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * JB-NOW-20 — document JSON refusal/roundtrip corpus (repro JB20-R01..G05).
 *
 * Owns ONLY this file. No production edits, no version bump, no Gradle run.
 *
 * What this covers beyond [DocModelTest], [EnumFreezeTest], [RegionDocumentTest]
 * and [MediaLayerDocTest] (read first):
 * - one small valid mixed-board document with sparse negative origins, a Sprite
 *   grid sized to its rect, tiling/lock metadata, region frame links (including
 *   a LINKED pair) and supported layer fields (visible/locked/opacity/blend/
 *   mask/clip/sharedCelId/regions/active cursors), round-tripped with
 *   INDEPENDENT hand-listed ownership expectations (not just `doc == back`);
 * - a second smaller negative-origin document proving a second roundtrip;
 * - versions with actual read support only (current [DOC_VERSION], 6, 5 and
 *   newer+1 for the documented words); the codec is never bumped here;
 * - mutations of the ACTUAL encoded JSON (parsed tree, edited, re-serialised):
 *   unknown keys at new depths (rect/grid/mask/region), missing required vs
 *   missing optional with documented defaults, wrong JSON types, duplicate
 *   identities and invalid geometry — each asserting the ACTUAL documented
 *   refusal ([DocException] with/without the parse prefix, or [DocOps.validate]
 *   words), never an invented migration.
 *
 * A valid serialized structure failing validation is a repro, not a rewritten
 * codec. Limits: CPU field/JSON checks only; no tile bytes, GPU, archive ZIP
 * or catalogue resolution. Existing enum-value refusal stays in
 * [EnumFreezeTest] and is not repeated here.
 */
class DocJsonBoardCorpusTest {

    // ---------- fixtures (small, deterministic, bounded) ----------

    /** Comprehensive mixed-board document. All ids hand-picked, all numbers tiny. */
    private fun mixedBoardDocument(): JbDocument = JbDocument(
        id = "doc-mixed",
        name = "Mixed",
        paper = Paper(color = "#1A2B3C", textureScale = 2f, includeInExport = true),
        boards = listOf(
            Board(
                id = "b-canvas", name = "Canvas", kind = BoardKind.CANVAS,
                rect = RectPx(-64, -48, 128, 96),
                clipToBoard = true, tiled = true, locked = true,
            ),
            Board(
                id = "b-sprite", name = "Sheet", kind = BoardKind.SPRITE,
                rect = RectPx(-48, -32, 64, 32),
                grid = SpriteGrid(cols = 4, rows = 2, cellW = 16, cellH = 16),
                tiled = false, locked = true,
            ),
            Board(
                id = "b-anim", name = "Walk", kind = BoardKind.ANIMATION,
                rect = RectPx(-32, -16, 32, 32),
                fps = 12f,
                frames = listOf(Frame("f1", 1), Frame("f2", 2), Frame("f3", 1)),
                tiled = false, currentFrameId = "f2", locked = false,
            ),
        ),
        layers = listOf(
            Layer(
                id = "l-paint", name = "Paint", kind = LayerKind.PAINT,
                visible = true, locked = false, opacity = 1f, blend = BlendMode.NORMAL,
                cels = listOf(
                    Cel("c-shared-paint", tiles = listOf("-1_-1", "0_0")),
                    Cel("c-a1", tiles = listOf("0_0")),
                    Cel("c-a2", tiles = emptyList()),
                ),
                mask = Cel("c-mask-paint", tiles = listOf("0_0")),
                clip = false,
                sharedCelId = "c-shared-paint",
                regions = listOf(
                    RegionFrames(
                        boardId = "b-anim",
                        frameCel = mapOf("f1" to "c-a1", "f2" to "c-a2", "f3" to "c-a2"),
                        held = false,
                    ),
                ),
            ),
            Layer(
                id = "l-ink", name = "Ink", kind = LayerKind.INK,
                visible = false, locked = true, opacity = 0.5f, blend = BlendMode.MULTIPLY,
                cels = listOf(
                    Cel("c-shared-ink", strokesFile = "strokes/shared.jsonl"),
                    Cel("c-i1", strokesFile = "strokes/i1.jsonl"),
                    Cel("c-i2", strokesFile = "strokes/i2.jsonl"),
                ),
                mask = null, clip = true,
                sharedCelId = "c-shared-ink",
                regions = listOf(
                    RegionFrames(
                        boardId = "b-anim",
                        frameCel = mapOf("f1" to "c-i1", "f2" to "c-i2", "f3" to "c-i1"),
                        held = true,
                    ),
                ),
            ),
        ),
        activeLayerId = "l-ink",
        activeBoardId = "b-anim",
    )

    /** Second smaller negative-origin document: canvas + sprite + one single-frame region. */
    private fun smallNegativeDocument(): JbDocument = JbDocument(
        id = "doc-small",
        name = "Small",
        paper = Paper(color = "#FFFFFF"),
        boards = listOf(
            Board("b-root", "Root", BoardKind.CANVAS, RectPx(-8, -8, 16, 16)),
            Board(
                "b-sheet", "Sheet", BoardKind.SPRITE, RectPx(-4, -4, 8, 8),
                grid = SpriteGrid(2, 2, 4, 4),
            ),
            Board(
                "b-seq", "Seq", BoardKind.ANIMATION, RectPx(0, 0, 8, 8),
                frames = listOf(Frame("f1")), currentFrameId = "f1",
            ),
        ),
        layers = listOf(
            Layer(
                "l0", "L0", LayerKind.PAINT,
                cels = listOf(Cel("shared", tiles = listOf("-1_-1")), Cel("a1")),
                sharedCelId = "shared",
                regions = listOf(RegionFrames("b-seq", mapOf("f1" to "a1"))),
            ),
        ),
        activeLayerId = "l0",
        activeBoardId = "b-seq",
    )

    // ---------- JSON-tree mutation helpers (actual encoded JSON, edited) ----------

    private fun parsed(text: String): JsonObject =
        Json.parseToJsonElement(text) as JsonObject

    private fun boardsOf(root: JsonObject): JsonArray =
        root.getValue("boards") as JsonArray

    private fun layersOf(root: JsonObject): JsonArray =
        root.getValue("layers") as JsonArray

    private fun withBoard(root: JsonObject, index: Int, edit: (JsonObject) -> JsonObject): JsonObject {
        val boards = boardsOf(root)
        val nextBoards = boards.mapIndexed { i, e -> if (i == index) edit(e as JsonObject) else e }
        val next = root.toMutableMap()
        next["boards"] = JsonArray(nextBoards)
        return JsonObject(next)
    }

    private fun withLayer(root: JsonObject, index: Int, edit: (JsonObject) -> JsonObject): JsonObject {
        val layers = layersOf(root)
        val nextLayers = layers.mapIndexed { i, e -> if (i == index) edit(e as JsonObject) else e }
        val next = root.toMutableMap()
        next["layers"] = JsonArray(nextLayers)
        return JsonObject(next)
    }

    private fun withoutKey(obj: JsonObject, key: String): JsonObject =
        JsonObject(obj.toMutableMap().also { it.remove(key) })

    private fun withKey(obj: JsonObject, key: String, value: JsonElement): JsonObject =
        JsonObject(obj.toMutableMap().also { it[key] = value })

    // ---------- 1. roundtrips with independent ownership ----------

    @Test
    fun `JB20-R01 mixed board document roundtrips with independent ownership`() {
        val doc = mixedBoardDocument()
        assertEquals(emptyList(), DocOps.validate(doc), "fixture itself must be valid")
        val text = DocJson.encode(doc)
        assertTrue(text.contains("\"version\": $DOC_VERSION"), "codec stamps current version, never bumps: $text")
        val back = DocJson.decode(text)
        assertEquals(doc, back)
        assertEquals(text, DocJson.encode(back), "re-encode is byte-identical")

        // Independently hand-listed ownership (never derived from the doc under test).
        val anim = back.boards.first { it.id == "b-anim" }
        assertEquals(RectPx(-32, -16, 32, 32), anim.rect)
        assertEquals(listOf("f1", "f2", "f3"), anim.frames.map { it.id })
        assertEquals(listOf(1, 2, 1), anim.frames.map { it.holdFrames })
        assertEquals("f2", anim.currentFrameId)
        assertEquals(SpriteGrid(4, 2, 16, 16), back.boards.first { it.id == "b-sprite" }.grid)
        assertEquals(RectPx(-48, -32, 64, 32), back.boards.first { it.id == "b-sprite" }.rect)
        assertEquals(RectPx(-64, -48, 128, 96), back.boards.first { it.id == "b-canvas" }.rect)
        assertTrue(back.boards.first { it.id == "b-canvas" }.tiled)
        assertTrue(back.boards.first { it.id == "b-canvas" }.locked)
        assertTrue(back.boards.first { it.id == "b-sprite" }.locked)
        assertFalse(back.boards.first { it.id == "b-anim" }.locked)
        assertTrue(back.boards.first { it.id == "b-canvas" }.clipToBoard)

        val paint = back.layers.first { it.id == "l-paint" }
        assertEquals("c-shared-paint", paint.sharedCelId)
        assertEquals(mapOf("f1" to "c-a1", "f2" to "c-a2", "f3" to "c-a2"), paint.regions.single().frameCel)
        assertEquals("c-a2", paint.regions.single().frameCel.getValue("f2"))
        assertEquals(paint.regions.single().frameCel.getValue("f2"), paint.regions.single().frameCel.getValue("f3"), "f2/f3 link")
        assertEquals(false, paint.regions.single().held)
        assertEquals("c-mask-paint", paint.mask?.id)
        assertEquals(1f, paint.opacity)
        assertEquals(BlendMode.NORMAL, paint.blend)

        val ink = back.layers.first { it.id == "l-ink" }
        assertEquals("c-shared-ink", ink.sharedCelId)
        assertEquals(mapOf("f1" to "c-i1", "f2" to "c-i2", "f3" to "c-i1"), ink.regions.single().frameCel)
        assertEquals(ink.regions.single().frameCel.getValue("f1"), ink.regions.single().frameCel.getValue("f3"), "f1/f3 link")
        assertEquals(true, ink.regions.single().held)
        assertEquals(false, ink.visible)
        assertEquals(true, ink.locked)
        assertEquals(0.5f, ink.opacity)
        assertEquals(BlendMode.MULTIPLY, ink.blend)
        assertEquals(true, ink.clip)

        assertEquals("l-ink", back.activeLayerId)
        assertEquals("b-anim", back.activeBoardId)
        assertEquals("#1A2B3C", back.paper.color)

        // Paint-plan ownership proof on hand-picked pixels (JB-3.00a B9/B10 clip, not whole tiles).
        val paintPlan = RegionDocumentOps.paintPlan(back, "l-paint")
        assertEquals("c-shared-paint", paintPlan.shared.celId)
        assertEquals(1, paintPlan.frames.size)
        assertEquals("b-anim", paintPlan.frames.single().boardId)
        assertEquals("f2", paintPlan.frames.single().frameId)
        assertEquals("c-a2", paintPlan.frames.single().celId)
        assertEquals(RectPx(-32, -16, 32, 32), paintPlan.frames.single().rect)
        assertEquals("c-a2", paintPlan.planeAt(-32, -16).celId, "top-left inside")
        assertEquals("c-a2", paintPlan.planeAt(-1, 0).celId, "just inside bottom-right")
        assertEquals("c-shared-paint", paintPlan.planeAt(0, 0).celId, "exclusive end is outside")
        assertEquals("c-shared-paint", paintPlan.planeAt(-100, -100).celId, "far outside is shared")

        // Held ink omits its board from the plan but keeps its addresses.
        val inkPlan = RegionDocumentOps.paintPlan(back, "l-ink")
        assertEquals("c-shared-ink", inkPlan.shared.celId)
        assertTrue(inkPlan.frames.isEmpty(), "held region uses shared canvas")
    }

    @Test
    fun `JB20-R02 small negative document roundtrips`() {
        val doc = smallNegativeDocument()
        assertEquals(emptyList(), DocOps.validate(doc), "small fixture must be valid")
        val back = DocJson.decode(DocJson.encode(doc))
        assertEquals(doc, back)
        assertEquals(RectPx(-8, -8, 16, 16), back.boards.first { it.id == "b-root" }.rect)
        assertEquals(RectPx(-4, -4, 8, 8), back.boards.first { it.id == "b-sheet" }.rect)
        assertEquals(SpriteGrid(2, 2, 4, 4), back.boards.first { it.id == "b-sheet" }.grid)
        assertEquals("shared", back.layers.single().sharedCelId)
        assertEquals(mapOf("f1" to "a1"), back.layers.single().regions.single().frameCel)
        assertEquals("f1", back.boards.first { it.id == "b-seq" }.currentFrameId)
        assertEquals(DocJson.encode(doc), DocJson.encode(back))
    }

    // ---------- 2. versions with actual read support only ----------

    @Test
    fun `JB20-V01 version gates use actual read support and never bump`() {
        val doc = mixedBoardDocument()
        // Current version roundtrips at DOC_VERSION (no bump).
        assertEquals(DOC_VERSION, DocJson.decode(DocJson.encode(doc)).version)

        // v6 region document still opens and validates (regional needs 6).
        run {
            val root = parsed(DocJson.encode(doc))
            val v6 = JsonObject(root.toMutableMap().also { it["version"] = Json.parseToJsonElement("6") })
            val back = DocJson.decode(v6.toString())
            assertEquals(6, back.version)
            assertEquals(emptyList(), DocOps.validate(back), "v6 region doc is readable: ${DocOps.validate(back)}")
        }
        // v5 with regions still decodes but is reported in words (not silently migrated).
        run {
            val root = parsed(DocJson.encode(doc))
            val v5 = JsonObject(root.toMutableMap().also { it["version"] = Json.parseToJsonElement("5") })
            val back = DocJson.decode(v5.toString())
            assertEquals(5, back.version)
            val problems = DocOps.validate(back)
            assertTrue(problems.any { it.contains("region animation needs document version 6") }, "got $problems")
        }
        // Newer version still decodes so validate can say so in words.
        run {
            val root = parsed(DocJson.encode(doc))
            val newer = JsonObject(root.toMutableMap().also { it["version"] = Json.parseToJsonElement((DOC_VERSION + 1).toString()) })
            val back = DocJson.decode(newer.toString())
            assertEquals(DOC_VERSION + 1, back.version)
            val problems = DocOps.validate(back)
            assertEquals(1, problems.size, "got $problems")
            assertTrue(problems.single().contains("newer Joy Brush"), "got ${problems.single()}")
        }
    }

    // ---------- 3. unknown keys at new depths ----------

    @Test
    fun `JB20-U01 unknown keys inside rect grid mask and region are refused by name`() {
        val text = DocJson.encode(mixedBoardDocument())

        // boards[2].rect (anim board).
        run {
            val root = parsed(text)
            val board = boardsOf(root)[2] as JsonObject
            val rect = board.getValue("rect") as JsonObject
            val badRect = withKey(rect, "rectFromTheFuture", Json.parseToJsonElement("\"future\""))
            val bad = withBoard(root, 2) { withKey(it, "rect", badRect) }
            val e = assertFailsWith<DocException> { DocJson.decode(bad.toString()) }
            assertTrue(e.message!!.contains("rectFromTheFuture"), e.message)
            assertTrue(e.message!!.contains("\$.boards[2].rect"), e.message)
            assertFalse(e.message!!.startsWith("document.json cannot be read"), e.message)
        }
        // boards[1].grid (sprite board).
        run {
            val root = parsed(text)
            val board = boardsOf(root)[1] as JsonObject
            val grid = board.getValue("grid") as JsonObject
            val badGrid = withKey(grid, "gridFromTheFuture", Json.parseToJsonElement("\"future\""))
            val bad = withBoard(root, 1) { withKey(it, "grid", badGrid) }
            val e = assertFailsWith<DocException> { DocJson.decode(bad.toString()) }
            assertTrue(e.message!!.contains("gridFromTheFuture"), e.message)
            assertTrue(e.message!!.contains("\$.boards[1].grid"), e.message)
            assertFalse(e.message!!.startsWith("document.json cannot be read"), e.message)
        }
        // layers[0].mask (paint mask).
        run {
            val root = parsed(text)
            val layer = layersOf(root)[0] as JsonObject
            val mask = layer.getValue("mask") as JsonObject
            val badMask = withKey(mask, "maskFromTheFuture", Json.parseToJsonElement("\"future\""))
            val bad = withLayer(root, 0) { withKey(it, "mask", badMask) }
            val e = assertFailsWith<DocException> { DocJson.decode(bad.toString()) }
            assertTrue(e.message!!.contains("maskFromTheFuture"), e.message)
            assertTrue(e.message!!.contains("\$.layers[0].mask"), e.message)
            assertFalse(e.message!!.startsWith("document.json cannot be read"), e.message)
        }
        // layers[0].regions[0] (region frame link).
        run {
            val root = parsed(text)
            val layer = layersOf(root)[0] as JsonObject
            val regions = layer.getValue("regions") as JsonArray
            val first = regions[0] as JsonObject
            val badFirst = withKey(first, "regionFromTheFuture", Json.parseToJsonElement("\"future\""))
            val badRegions = JsonArray(listOf(badFirst) + regions.drop(1))
            val bad = withLayer(root, 0) { withKey(it, "regions", badRegions) }
            val e = assertFailsWith<DocException> { DocJson.decode(bad.toString()) }
            assertTrue(e.message!!.contains("regionFromTheFuture"), e.message)
            assertTrue(e.message!!.contains("\$.layers[0].regions[0]"), e.message)
            assertFalse(e.message!!.startsWith("document.json cannot be read"), e.message)
        }
    }

    // ---------- 4. missing fields ----------

    @Test
    fun `JB20-M01 missing required fields are refused at parse`() {
        val text = DocJson.encode(mixedBoardDocument())
        // Board rect is required (no default).
        run {
            val bad = withBoard(parsed(text), 0) { withoutKey(it, "rect") }
            val e = assertFailsWith<DocException> { DocJson.decode(bad.toString()) }
            assertTrue(e.message!!.startsWith("document.json cannot be read"), e.message)
        }
        // Layer cels are required.
        run {
            val bad = withLayer(parsed(text), 0) { withoutKey(it, "cels") }
            val e = assertFailsWith<DocException> { DocJson.decode(bad.toString()) }
            assertTrue(e.message!!.startsWith("document.json cannot be read"), e.message)
        }
        // Cel id is required: layers[0].cels[0] without id.
        run {
            val root = parsed(text)
            val layer = layersOf(root)[0] as JsonObject
            val cels = layer.getValue("cels") as JsonArray
            val first = withoutKey(cels[0] as JsonObject, "id")
            val badCels = JsonArray(listOf(first) + cels.drop(1))
            val bad = withLayer(root, 0) { withKey(it, "cels", badCels) }
            val e = assertFailsWith<DocException> { DocJson.decode(bad.toString()) }
            assertTrue(e.message!!.startsWith("document.json cannot be read"), e.message)
        }
    }

    @Test
    fun `JB20-M02 missing optional fields decode with documented defaults`() {
        val text = DocJson.encode(mixedBoardDocument())
        // tiled defaults to false (canvas had true, so removal is observable).
        run {
            val bad = withBoard(parsed(text), 0) { withoutKey(it, "tiled") }
            val back = DocJson.decode(bad.toString())
            assertFalse(back.boards.first { it.id == "b-canvas" }.tiled)
            assertEquals(emptyList(), DocOps.validate(back))
        }
        // holdFrames defaults to 1 (f2 had 2, so removal is observable).
        run {
            val root = parsed(text)
            val board = boardsOf(root)[2] as JsonObject
            val frames = board.getValue("frames") as JsonArray
            val second = withoutKey(frames[1] as JsonObject, "holdFrames")
            val badFrames = JsonArray(listOf(frames[0], second, frames[2]))
            val badBoard = withKey(board, "frames", badFrames)
            val bad = withBoard(root, 2) { badBoard }
            val back = DocJson.decode(bad.toString())
            assertEquals(1, back.boards.first { it.id == "b-anim" }.frames.first { it.id == "f2" }.holdFrames)
            assertEquals(emptyList(), DocOps.validate(back))
        }
        // currentFrameId defaults to null, which then fails region validation in words.
        run {
            val bad = withBoard(parsed(text), 2) { withoutKey(it, "currentFrameId") }
            val back = DocJson.decode(bad.toString())
            assertNull(back.boards.first { it.id == "b-anim" }.currentFrameId)
            val problems = DocOps.validate(back)
            assertTrue(problems.any { it.contains("has no valid current frame") }, "got $problems")
        }
    }

    // ---------- 5. wrong types ----------

    @Test
    fun `JB20-W01 wrong JSON types are refused at parse`() {
        val text = DocJson.encode(mixedBoardDocument())
        // rect.w expects Int.
        run {
            val root = parsed(text)
            val board = boardsOf(root)[0] as JsonObject
            val rect = board.getValue("rect") as JsonObject
            val badRect = withKey(rect, "w", Json.parseToJsonElement("\"wide\""))
            val bad = withBoard(root, 0) { withKey(it, "rect", badRect) }
            val e = assertFailsWith<DocException> { DocJson.decode(bad.toString()) }
            assertTrue(e.message!!.startsWith("document.json cannot be read"), e.message)
        }
        // fps expects Float.
        run {
            val bad = withBoard(parsed(text), 2) { withKey(it, "fps", Json.parseToJsonElement("\"fast\"")) }
            val e = assertFailsWith<DocException> { DocJson.decode(bad.toString()) }
            assertTrue(e.message!!.startsWith("document.json cannot be read"), e.message)
        }
        // opacity expects Float.
        run {
            val bad = withLayer(parsed(text), 1) { withKey(it, "opacity", Json.parseToJsonElement("\"opaque\"")) }
            val e = assertFailsWith<DocException> { DocJson.decode(bad.toString()) }
            assertTrue(e.message!!.startsWith("document.json cannot be read"), e.message)
        }
        // holdFrames expects Int.
        run {
            val root = parsed(text)
            val board = boardsOf(root)[2] as JsonObject
            val frames = board.getValue("frames") as JsonArray
            val badSecond = withKey(frames[1] as JsonObject, "holdFrames", Json.parseToJsonElement("\"many\""))
            val badFrames = JsonArray(listOf(frames[0], badSecond, frames[2]))
            val bad = withBoard(root, 2) { withKey(it, "frames", badFrames) }
            val e = assertFailsWith<DocException> { DocJson.decode(bad.toString()) }
            assertTrue(e.message!!.startsWith("document.json cannot be read"), e.message)
        }
    }

    // ---------- 6. duplicate identities (decode ok, validate refuses) ----------

    @Test
    fun `JB20-D01 duplicate identities survive JSON but fail validation in words`() {
        val text = DocJson.encode(mixedBoardDocument())
        // Duplicate board id.
        run {
            val bad = withBoard(parsed(text), 1) { withKey(it, "id", Json.parseToJsonElement("\"b-canvas\"")) }
            val back = DocJson.decode(bad.toString())
            val problems = DocOps.validate(back)
            assertTrue(problems.any { it.contains("two boards are both called") && it.contains("b-canvas") }, "got $problems")
        }
        // Duplicate layer id.
        run {
            val bad = withLayer(parsed(text), 1) { withKey(it, "id", Json.parseToJsonElement("\"l-paint\"")) }
            val back = DocJson.decode(bad.toString())
            val problems = DocOps.validate(back)
            assertTrue(problems.any { it.contains("two layers are both called") && it.contains("l-paint") }, "got $problems")
        }
        // Duplicate frame id (also disturbs the region map, so check containment, not count).
        run {
            val root = parsed(text)
            val board = boardsOf(root)[2] as JsonObject
            val frames = board.getValue("frames") as JsonArray
            val dup = withKey(frames[1] as JsonObject, "id", Json.parseToJsonElement("\"f1\""))
            val badFrames = JsonArray(listOf(frames[0], dup, frames[2]))
            val bad = withBoard(root, 2) { withKey(it, "frames", badFrames) }
            val back = DocJson.decode(bad.toString())
            val problems = DocOps.validate(back)
            assertTrue(problems.any { it.contains("two frames called") && it.contains("f1") }, "got $problems")
        }
        // Duplicate region for the same board.
        run {
            val root = parsed(text)
            val layer = layersOf(root)[0] as JsonObject
            val regions = layer.getValue("regions") as JsonArray
            val doubled = JsonArray(listOf(regions[0], regions[0]))
            val bad = withLayer(root, 0) { withKey(it, "regions", doubled) }
            val back = DocJson.decode(bad.toString())
            val problems = DocOps.validate(back)
            assertTrue(problems.any { it.contains("has two regions for board") && it.contains("b-anim") }, "got $problems")
        }
    }

    // ---------- 7. invalid geometry (decode ok, validate refuses) ----------

    @Test
    fun `JB20-G01 invalid geometry survives JSON but fails validation in words`() {
        val text = DocJson.encode(mixedBoardDocument())
        // Board with no room.
        run {
            val root = parsed(text)
            val board = boardsOf(root)[0] as JsonObject
            val rect = board.getValue("rect") as JsonObject
            val badRect = withKey(rect, "w", Json.parseToJsonElement("0"))
            val bad = withBoard(root, 0) { withKey(it, "rect", badRect) }
            val back = DocJson.decode(bad.toString())
            val problems = DocOps.validate(back)
            assertTrue(problems.any { it.contains("has no room") && it.contains("b-canvas") }, "got $problems")
        }
        // Sprite grid with no cells.
        run {
            val root = parsed(text)
            val board = boardsOf(root)[1] as JsonObject
            val grid = board.getValue("grid") as JsonObject
            val badGrid = withKey(grid, "cols", Json.parseToJsonElement("0"))
            val bad = withBoard(root, 1) { withKey(it, "grid", badGrid) }
            val back = DocJson.decode(bad.toString())
            val problems = DocOps.validate(back)
            assertTrue(problems.any { it.contains("has a grid with no cells") && it.contains("b-sprite") }, "got $problems")
        }
        // Unplayable fps.
        run {
            val bad = withBoard(parsed(text), 2) { withKey(it, "fps", Json.parseToJsonElement("120")) }
            val back = DocJson.decode(bad.toString())
            val problems = DocOps.validate(back)
            assertTrue(problems.any { it.contains("runs at") && it.contains("fps") }, "got $problems")
        }
        // Opacity outside 0..1.
        run {
            val bad = withLayer(parsed(text), 1) { withKey(it, "opacity", Json.parseToJsonElement("1.5")) }
            val back = DocJson.decode(bad.toString())
            val problems = DocOps.validate(back)
            assertTrue(problems.any { it.contains("opaque") && it.contains("l-ink") }, "got $problems")
        }
        // Paper colour not #RRGGBB.
        run {
            val root = parsed(text)
            val paper = root.getValue("paper") as JsonObject
            val badPaper = withKey(paper, "color", Json.parseToJsonElement("\"white\""))
            val bad = JsonObject(root.toMutableMap().also { it["paper"] = badPaper })
            val back = DocJson.decode(bad.toString())
            val problems = DocOps.validate(back)
            assertTrue(problems.any { it.contains("paper colour") }, "got $problems")
        }
    }
}
