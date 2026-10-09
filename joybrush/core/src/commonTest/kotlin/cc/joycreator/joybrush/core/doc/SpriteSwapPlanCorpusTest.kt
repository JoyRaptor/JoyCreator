package cc.joycreator.joybrush.core.doc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * JB-NOW-16 — Sprite swap address-plan involution corpus.
 *
 * Proves [SpriteCellOps.swap] address plans: linked-frame / held-region /
 * hidden-and-locked layers, PAINT and INK plans, same-cell no-op, invalid
 * indices, malformed grids and unlocked-board refusal.
 *
 * Byte-model note: the bounded [ByteModel] below is a CPU test oracle that
 * interprets [RegionChange] transfers over small integer stores. It is NOT a
 * GPU proof and MUST NOT be read as one. No executor for unsupported content
 * (INK strokes, MEDIA state) is implemented here; this corpus proves address
 * plans only and asserts INK payload metadata is untouched.
 */
class SpriteSwapPlanCorpusTest {
    // Sprite under test: rect (0,0,4,2), grid 2x1 of 2x2.
    // cell 0 = RectPx(0,0,2,2), cell 1 = RectPx(2,0,2,2).
    private val cellA = RectPx(0, 0, 2, 2)
    private val cellB = RectPx(2, 0, 2, 2)

    private fun staticSwapDoc(): JbDocument {
        val doc = JbDocument(
            id = "drawing",
            name = "SwapCorpus",
            paper = Paper(color = "#112233"),
            boards = listOf(
                Board("sprite", "Sheet", BoardKind.SPRITE, RectPx(0, 0, 4, 2),
                    grid = SpriteGrid(2, 1, 2, 2), locked = true)
            ),
            layers = listOf(
                Layer("paint", "Paint", LayerKind.PAINT, cels = listOf(Cel("paint-shared"))),
                Layer("hidden", "Hidden", LayerKind.PAINT, visible = false, locked = true,
                    cels = listOf(Cel("hidden-shared"))),
                Layer("ink", "Ink", LayerKind.INK,
                    cels = listOf(Cel("ink-shared", strokesFile = "ink-shared.bin")))
            ),
            activeLayerId = "paint",
            activeBoardId = "sprite"
        )
        assertTrue(DocOps.validate(doc).isEmpty(), "static fixture must validate")
        return doc
    }

    /**
     * Animated fixture with independent hand-placed addresses (no ID generator):
     * - anim board RectPx(1,0,2,2) cuts both sprite cells at x=1/x=2 interior seams;
     * - paint + ink animate there with LINKED frames (f1 and f2 share one cel);
     * - hidden is hidden+locked AND held on anim (uses shared canvas there);
     * - far board RectPx(100,100,4,4) is unrelated and must never appear in transfers;
     * - masked is a static PAINT layer with its own mask store.
     */
    private fun animatedDoc(): JbDocument {
        val doc = JbDocument(
            id = "drawing",
            name = "SwapCorpus",
            paper = Paper(color = "#112233"),
            boards = listOf(
                Board("sprite", "Sheet", BoardKind.SPRITE, RectPx(0, 0, 4, 2),
                    grid = SpriteGrid(2, 1, 2, 2), locked = true),
                Board("anim", "Walk", BoardKind.ANIMATION, RectPx(1, 0, 2, 2),
                    frames = listOf(Frame("f1"), Frame("f2")), currentFrameId = "f2"),
                Board("far", "Far", BoardKind.ANIMATION, RectPx(100, 100, 4, 4),
                    frames = listOf(Frame("g1")), currentFrameId = "g1")
            ),
            layers = listOf(
                Layer("paint", "Paint", LayerKind.PAINT,
                    cels = listOf(Cel("paint-shared"), Cel("paint-f1"), Cel("paint-far")),
                    sharedCelId = "paint-shared",
                    regions = listOf(
                        RegionFrames("anim", mapOf("f1" to "paint-f1", "f2" to "paint-f1")),
                        RegionFrames("far", mapOf("g1" to "paint-far"))
                    )),
                Layer("hidden", "Hidden", LayerKind.PAINT, visible = false, locked = true,
                    cels = listOf(Cel("hidden-shared"), Cel("hidden-f1"), Cel("hidden-far")),
                    sharedCelId = "hidden-shared",
                    regions = listOf(
                        RegionFrames("anim", mapOf("f1" to "hidden-f1", "f2" to "hidden-f1"), held = true),
                        RegionFrames("far", mapOf("g1" to "hidden-far"))
                    )),
                Layer("ink", "Ink", LayerKind.INK,
                    cels = listOf(
                        Cel("ink-shared", strokesFile = "ink-shared.bin"),
                        Cel("ink-f1", strokesFile = "ink-f1.bin"),
                        Cel("ink-far", strokesFile = "ink-far.bin")
                    ),
                    sharedCelId = "ink-shared",
                    regions = listOf(
                        RegionFrames("anim", mapOf("f1" to "ink-f1", "f2" to "ink-f1")),
                        RegionFrames("far", mapOf("g1" to "ink-far"))
                    )),
                Layer("masked", "Masked", LayerKind.PAINT, opacity = 0.5f, blend = BlendMode.MULTIPLY,
                    cels = listOf(Cel("masked-shared")), mask = Cel("masked-mask"))
            ),
            activeLayerId = "paint",
            activeBoardId = "sprite"
        )
        assertTrue(DocOps.validate(doc).isEmpty(), "animated fixture must validate")
        return doc
    }

    @Test fun `static paint and ink share exact whole cell address plan including hidden locked layer`() {
        val doc = staticSwapDoc()
        val change = SpriteCellOps.swap(doc, "sprite", 0, 1)
        assertSame(doc, change.doc)
        assertTrue(change.copies.isEmpty() && change.drops.isEmpty())
        assertTrue(change.clears.isEmpty() && change.maskClears.isEmpty())
        assertTrue(change.maskTransfers.isEmpty())
        // Independently listed whole-cell swap: one reciprocal pair per layer, layer-major order.
        val expected = listOf(
            RegionTransfer("paint", "paint-shared", "paint-shared", cellA, cellB),
            RegionTransfer("paint", "paint-shared", "paint-shared", cellB, cellA),
            RegionTransfer("hidden", "hidden-shared", "hidden-shared", cellA, cellB),
            RegionTransfer("hidden", "hidden-shared", "hidden-shared", cellB, cellA),
            RegionTransfer("ink", "ink-shared", "ink-shared", cellA, cellB),
            RegionTransfer("ink", "ink-shared", "ink-shared", cellB, cellA)
        )
        assertEquals(expected, change.transfers)
        // PAINT and INK use the same address plan geometry; only cel IDs differ.
        val paintRects = change.transfers.filter { it.layerId == "paint" }
            .map { it.sourceRect to it.destinationRect }
        val inkRects = change.transfers.filter { it.layerId == "ink" }
            .map { it.sourceRect to it.destinationRect }
        assertEquals(paintRects, inkRects)
        // Hidden-and-locked layer participates; INK payload is not converted.
        assertTrue(change.transfers.any { it.layerId == "hidden" })
        assertEquals("ink-shared.bin", change.doc.layers.first { it.id == "ink" }.cels.single().strokesFile)
        assertEquals(doc.paper, change.doc.paper)
        assertEquals(doc.boards, change.doc.boards)
    }

    @Test fun `linked held and hidden partition exact plan with far board absent`() {
        val doc = animatedDoc()
        // Linked frames retain one physical cel across f1/f2.
        for (layerId in listOf("paint", "hidden", "ink")) {
            val region = doc.layers.first { it.id == layerId }.regions.first { it.boardId == "anim" }
            assertEquals(region.frameCel.getValue("f1"), region.frameCel.getValue("f2"))
        }
        val change = SpriteCellOps.swap(doc, "sprite", 0, 1)
        assertSame(doc, change.doc)
        assertTrue(change.copies.isEmpty() && change.drops.isEmpty())
        assertTrue(change.clears.isEmpty() && change.maskClears.isEmpty())
        // Independently listed partition: anim rect (1,0,2,2) cuts cell 0 at local x=1
        // and cell 1 at local x=1, giving 1-px columns; y is uncut. Held layer keeps
        // whole cells on shared content. Static masked layer keeps whole cells.
        val expected = listOf(
            RegionTransfer("paint", "paint-shared", "paint-f1", RectPx(0, 0, 1, 2), RectPx(2, 0, 1, 2)),
            RegionTransfer("paint", "paint-f1", "paint-shared", RectPx(2, 0, 1, 2), RectPx(0, 0, 1, 2)),
            RegionTransfer("paint", "paint-f1", "paint-shared", RectPx(1, 0, 1, 2), RectPx(3, 0, 1, 2)),
            RegionTransfer("paint", "paint-shared", "paint-f1", RectPx(3, 0, 1, 2), RectPx(1, 0, 1, 2)),
            RegionTransfer("hidden", "hidden-shared", "hidden-shared", cellA, cellB),
            RegionTransfer("hidden", "hidden-shared", "hidden-shared", cellB, cellA),
            RegionTransfer("ink", "ink-shared", "ink-f1", RectPx(0, 0, 1, 2), RectPx(2, 0, 1, 2)),
            RegionTransfer("ink", "ink-f1", "ink-shared", RectPx(2, 0, 1, 2), RectPx(0, 0, 1, 2)),
            RegionTransfer("ink", "ink-f1", "ink-shared", RectPx(1, 0, 1, 2), RectPx(3, 0, 1, 2)),
            RegionTransfer("ink", "ink-shared", "ink-f1", RectPx(3, 0, 1, 2), RectPx(1, 0, 1, 2)),
            RegionTransfer("masked", "masked-shared", "masked-shared", cellA, cellB),
            RegionTransfer("masked", "masked-shared", "masked-shared", cellB, cellA)
        )
        assertEquals(expected, change.transfers)
        // Masks are an independent global store: reciprocal whole cells, never paint cels.
        val expectedMasks = listOf(
            RegionMaskTransfer("masked", cellA, cellB),
            RegionMaskTransfer("masked", cellB, cellA)
        )
        assertEquals(expectedMasks, change.maskTransfers)
        assertTrue(change.transfers.none { it.fromCelId == "masked-mask" || it.toCelId == "masked-mask" })
        // Unrelated far-board frame cels are never addressed by a sprite-cell swap.
        assertTrue(change.transfers.none { it.fromCelId.endsWith("-far") || it.toCelId.endsWith("-far") })
        // Held region keeps shared content: every hidden transfer names the shared cel twice.
        assertTrue(change.transfers.filter { it.layerId == "hidden" }
            .all { it.fromCelId == "hidden-shared" && it.toCelId == "hidden-shared" })
        // INK participates in the ownership plan without converting its strokes.
        assertEquals(
            doc.layers.first { it.id == "ink" }.cels,
            change.doc.layers.first { it.id == "ink" }.cels
        )
        // Masks, metadata and layer flags stay outside transfer writes (same document).
        assertEquals(doc.paper, change.doc.paper)
        assertEquals(doc.boards, change.doc.boards)
        assertEquals(doc.layers, change.doc.layers)
    }

    @Test fun `swap twice restores bounded byte model and leaves outside writes alone`() {
        val doc = animatedDoc()
        val change = SpriteCellOps.swap(doc, "sprite", 0, 1)
        // Bounded CPU byte-model fixture (NOT GPU): exact sprite cells plus named
        // outside/unrelated probes. Each cel gets a distinct non-default value.
        val before = ByteModel()
        val cels = listOf(
            "paint" to "paint-shared", "paint" to "paint-f1", "paint" to "paint-far",
            "hidden" to "hidden-shared", "hidden" to "hidden-f1", "hidden" to "hidden-far",
            "ink" to "ink-shared", "ink" to "ink-f1", "ink" to "ink-far",
            "masked" to "masked-shared"
        )
        for ((li, entry) in cels.withIndex()) {
            for (y in 0..1) for (x in 0..3) {
                before.put(entry.first, entry.second, x, y, 1000 + li * 100 + y * 4 + x)
            }
        }
        before.put("masked", "masked-mask", 0, 0, 7, default = -1)
        before.put("masked", "masked-mask", 2, 0, 9, default = -1)
        // Outside sprite cells and unrelated far art: must survive both swaps.
        before.put("paint", "paint-shared", -1, 0, 111)
        before.put("paint", "paint-shared", 4, 1, 222)
        before.put("paint", "paint-shared", 0, 2, 333)
        before.put("paint", "paint-far", 101, 101, 444)
        before.put("ink", "ink-far", 101, 101, 555)
        val after = before.apply(change)
        // Independent per-pixel oracle: hand-rolled ownership (anim rect only for
        // unheld paint/ink; held hidden always shared; far only at 100,100).
        val expected = before.copy()
        for (y in 0..1) for (x in 0..1) {
            val mirror = x + 2
            for (layer in listOf("paint", "ink")) {
                val frameCel = if (layer == "paint") "paint-f1" else "ink-f1"
                val shared = if (layer == "paint") "paint-shared" else "ink-shared"
                val a = if (x in 1..2 && y in 0..1) frameCel else shared
                val b = if (mirror in 1..2 && y in 0..1) frameCel else shared
                expected.put(layer, a, x, y, before.get(layer, b, mirror, y))
                expected.put(layer, b, mirror, y, before.get(layer, a, x, y))
            }
            expected.put("hidden", "hidden-shared", x, y,
                before.get("hidden", "hidden-shared", mirror, y))
            expected.put("hidden", "hidden-shared", mirror, y,
                before.get("hidden", "hidden-shared", x, y))
            expected.put("masked", "masked-shared", x, y,
                before.get("masked", "masked-shared", mirror, y))
            expected.put("masked", "masked-shared", mirror, y,
                before.get("masked", "masked-shared", x, y))
        }
        expected.put("masked", "masked-mask", 0, 0, before.get("masked", "masked-mask", 2, 0, default = -1), default = -1)
        expected.put("masked", "masked-mask", 2, 0, before.get("masked", "masked-mask", 0, 0, default = -1), default = -1)
        assertEquals(expected.values, after.values)
        // Outside writes and unrelated far/shared content are untouched.
        assertEquals(111, after.get("paint", "paint-shared", -1, 0))
        assertEquals(222, after.get("paint", "paint-shared", 4, 1))
        assertEquals(333, after.get("paint", "paint-shared", 0, 2))
        assertEquals(444, after.get("paint", "paint-far", 101, 101))
        assertEquals(555, after.get("ink", "ink-far", 101, 101))
        // Involution: swap twice restores the byte model exactly.
        assertEquals(before.values, after.apply(change).values)
        // Every destination pixel is written exactly once (no gaps, no double writes):
        // 4 layers x 8 cell pixels each (2 cells of 2x2) = 32 addressed destinations.
        val destinations = HashSet<BytePixel>()
        for (t in change.transfers) {
            for (y in t.destinationRect.y until t.destinationRect.y + t.destinationRect.h) {
                for (x in t.destinationRect.x until t.destinationRect.x + t.destinationRect.w) {
                    assertTrue(destinations.add(BytePixel(t.layerId, t.toCelId, x, y)),
                        "destination written twice: $t at $x,$y")
                }
            }
        }
        assertEquals(32, destinations.size)
    }

    @Test fun `masks metadata and unrelated regions stay outside transfer writes`() {
        val doc = animatedDoc()
        val beforePaper = doc.paper
        val beforeBoards = doc.boards
        val beforeLayers = doc.layers
        val change = SpriteCellOps.swap(doc, "sprite", 1, 0)
        // Direction does not matter: same reciprocal address plan, reversed order.
        assertEquals(12, change.transfers.size)
        assertEquals(2, change.maskTransfers.size)
        assertEquals(cellA, change.maskTransfers.first().destinationRect)
        assertEquals(cellB, change.maskTransfers.first().sourceRect)
        assertTrue(change.transfers.none { it.fromCelId == "masked-mask" || it.toCelId == "masked-mask" })
        assertTrue(change.transfers.none { it.fromCelId.endsWith("-far") || it.toCelId.endsWith("-far") })
        // Document metadata is untouched: same instance, equal paper/boards/layers.
        assertSame(doc, change.doc)
        assertEquals(beforePaper, change.doc.paper)
        assertEquals(beforeBoards, change.doc.boards)
        assertEquals(beforeLayers, change.doc.layers)
        assertEquals(0.5f, change.doc.layers.first { it.id == "masked" }.opacity)
        assertEquals(BlendMode.MULTIPLY, change.doc.layers.first { it.id == "masked" }.blend)
        assertEquals(false, change.doc.layers.first { it.id == "hidden" }.visible)
        assertEquals(true, change.doc.layers.first { it.id == "hidden" }.locked)
        // No unsupported-content executor: no copies/drops/clears and INK strokes intact.
        assertTrue(change.copies.isEmpty() && change.drops.isEmpty())
        assertTrue(change.clears.isEmpty() && change.maskClears.isEmpty())
        assertEquals(
            listOf("ink-shared.bin", "ink-f1.bin", "ink-far.bin"),
            change.doc.layers.first { it.id == "ink" }.cels.map { it.strokesFile }
        )
        // Byte-model spot check: far cel bytes never move; mask white default preserved outside.
        val before = ByteModel()
        before.put("paint", "paint-far", 100, 100, 61)
        before.put("paint", "paint-shared", 50, 50, 62)
        before.put("masked", "masked-mask", 50, 50, 63, default = -1)
        val after = before.apply(change)
        assertEquals(61, after.get("paint", "paint-far", 100, 100))
        assertEquals(62, after.get("paint", "paint-shared", 50, 50))
        assertEquals(63, after.get("masked", "masked-mask", 50, 50, default = -1))
        assertEquals(-1, after.get("masked", "masked-mask", 51, 51, default = -1))
    }

    @Test fun `same cell no-op invalid malformed and unlocked refusals`() {
        val doc = staticSwapDoc()
        // Same cell is an empty no-op returning the same document.
        assertEquals(RegionChange(doc), SpriteCellOps.swap(doc, "sprite", 0, 0))
        assertEquals(RegionChange(doc), SpriteCellOps.swap(doc, "sprite", 1, 1))
        // Invalid indices refuse with the documented refusal type.
        assertFailsWith<DocException> { SpriteCellOps.swap(doc, "sprite", -1, 0) }
        assertFailsWith<DocException> { SpriteCellOps.swap(doc, "sprite", 0, -1) }
        assertFailsWith<DocException> { SpriteCellOps.swap(doc, "sprite", 0, 2) }
        assertFailsWith<DocException> { SpriteCellOps.swap(doc, "sprite", 2, 0) }
        assertFailsWith<DocException> { SpriteCellOps.swap(doc, "sprite", 2, 2) }
        assertFailsWith<DocException> { SpriteCellOps.swap(doc, "sprite", 99, 0) }
        // Wrong board selection refuses.
        assertFailsWith<DocException> { SpriteCellOps.swap(doc, "missing", 0, 1) }
        assertFailsWith<DocException> {
            SpriteCellOps.swap(doc.copy(boards = doc.boards.map {
                it.copy(id = "anim", kind = BoardKind.ANIMATION)
            }), "anim", 0, 1)
        }
        // Unlocked board refuses: lock is required before rearranging.
        assertFailsWith<DocException> {
            SpriteCellOps.swap(doc.copy(boards = doc.boards.map { it.copy(locked = false) }), "sprite", 0, 1)
        }
        // Malformed grids refuse instead of silently clamping (cellRect clamps; swap must not).
        val malformed = listOf(
            SpriteGrid(0, 1, 2, 2),
            SpriteGrid(2, 0, 2, 2),
            SpriteGrid(2, 1, 0, 2),
            SpriteGrid(2, 1, 2, 0),
            SpriteGrid(4097, 1, 1, 1),
            SpriteGrid(2, 1, 5000, 2),
            SpriteGrid(2, 1, 2, 5000),
            SpriteGrid(2, 1, 3, 2)
        )
        for (grid in malformed) {
            assertFailsWith<DocException>("grid $grid must refuse") {
                SpriteCellOps.swap(doc.copy(boards = doc.boards.map { it.copy(grid = grid) }), "sprite", 0, 1)
            }
        }
        assertFailsWith<DocException> {
            SpriteCellOps.swap(doc.copy(boards = doc.boards.map { it.copy(grid = null) }), "sprite", 0, 1)
        }
        // Refusals leave the caller's document value unchanged.
        assertEquals(staticSwapDoc(), doc)
    }

    /**
     * Bounded CPU byte-model oracle (NOT GPU): maps (layer, cel, x, y) to an
     * integer store value. Paint default 0 (transparent); mask default -1
     * (white, per [RegionMaskTransfer] contract). [apply] always reads the
     * original store, never staged destinations, even for same-cel swaps.
     */
    private data class BytePixel(val layer: String, val cel: String, val x: Int, val y: Int)

    private class ByteModel(val values: MutableMap<BytePixel, Int> = mutableMapOf()) {
        fun copy() = ByteModel(values.toMutableMap())
        fun get(layer: String, cel: String, x: Int, y: Int, default: Int = 0) =
            values[BytePixel(layer, cel, x, y)] ?: default

        fun put(layer: String, cel: String, x: Int, y: Int, value: Int, default: Int = 0) {
            val key = BytePixel(layer, cel, x, y)
            if (value == default) values.remove(key) else values[key] = value
        }

        fun apply(change: RegionChange): ByteModel {
            val after = copy()
            fun move(layer: String, from: String, to: String, source: RectPx, destination: RectPx, default: Int) {
                for (dy in 0 until source.h) for (dx in 0 until source.w) {
                    after.put(layer, to, destination.x + dx, destination.y + dy,
                        get(layer, from, source.x + dx, source.y + dy, default), default)
                }
            }
            for (t in change.transfers) {
                move(t.layerId, t.fromCelId, t.toCelId, t.sourceRect, t.destinationRect, 0)
            }
            for (t in change.maskTransfers) {
                val mask = change.doc.layers.first { it.id == t.layerId }.mask!!.id
                move(t.layerId, mask, mask, t.sourceRect, t.destinationRect, -1)
            }
            return after
        }
    }
}
