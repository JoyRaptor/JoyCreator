package cc.joycreator.joybrush.core.doc

import cc.joycreator.joybrush.core.paint.Tiles
import cc.joycreator.joybrush.core.sprite.SpriteGridMath
import kotlin.test.*

class SpriteCellOpsTest {
    private var serial = 0
    private fun ids() = "swap-${++serial}"
    private fun base() = JbDocument(id = "drawing", name = "Swap",
        boards = listOf(Board("sprite", "Sheet", BoardKind.SPRITE, RectPx(-260, -1, 520, 3),
            grid = SpriteGrid(2, 1, 260, 3), locked = true)),
        layers = listOf(Layer("paint", "Paint", LayerKind.PAINT, cels = listOf(Cel("shared")))))

    @Test fun `same cel swap snapshots both sides including transparent pixels and reverses exactly`() {
        val doc = base()
        val before = Pixels()
        before.put("paint", "shared", -260, -1, 0x12345678)
        before.put("paint", "shared", 0, -1, 0x23456789)
        before.put("paint", "shared", -1, 1, 0x3456789a)
        before.put("paint", "shared", -261, -1, 123) // Outside both cells.
        before.put("paint", "shared", 260, 1, 456)
        val change = SpriteCellOps.swap(doc, "sprite", 0, 1)
        assertEquals(2, change.transfers.size)
        assertSame(doc, change.doc)
        assertTrue(change.copies.isEmpty() && change.drops.isEmpty())
        val after = before.apply(change)
        assertEquals(0x23456789, after.get("paint", "shared", -260, -1))
        assertEquals(0x12345678, after.get("paint", "shared", 0, -1))
        assertEquals(0, after.get("paint", "shared", -1, 1))
        assertEquals(0x3456789a, after.get("paint", "shared", 259, 1))
        assertEquals(123, after.get("paint", "shared", -261, -1))
        assertEquals(456, after.get("paint", "shared", 260, 1))
        assertEquals(after.values, before.apply(change.copy(transfers = change.transfers.reversed())).values)
        assertEquals(before.values, after.apply(change).values)
    }

    @Test fun `both animation boundaries preserve hidden shared art other frames held and linked addresses`() {
        var doc = base().copy(layers = base().layers + Layer("hidden", "Hidden", LayerKind.PAINT,
            visible = false, locked = true, cels = listOf(Cel("hidden-shared"))))
        doc = RegionDocumentOps.create(doc, "Left", RectPx(-250, -1, 12, 2), ::ids).doc
        val leftId = doc.boards.last().id
        doc = RegionDocumentOps.addFrame(doc, leftId, NewFrame.BLANK, ::ids).doc
        doc = RegionDocumentOps.addFrame(doc, leftId, NewFrame.LINK, ::ids).doc
        doc = RegionDocumentOps.create(doc, "Right", RectPx(6, 0, 9, 2), ::ids).doc
        val rightId = doc.boards.last().id
        doc = RegionDocumentOps.addFrame(doc, rightId, NewFrame.BLANK, ::ids).doc
        doc = doc.copy(layers = doc.layers.map { layer ->
            if (layer.id == "hidden") layer.copy(regions = layer.regions.map { it.copy(held = true) }) else layer
        })
        assertTrue(DocOps.validate(doc).isEmpty())
        val plans = RegionDocumentOps.paintPlans(doc, emptyMap())
        val before = Pixels()
        // Populate EVERY cel, including art hidden under active regions and non-current frames.
        for ((li, layer) in doc.layers.withIndex()) for ((ci, cel) in layer.cels.withIndex()) {
            for (y in -2..2) for (x in -261..260) {
                before.put(layer.id, cel.id, x, y, 1 + li * 100000 + ci * 3000 + (y + 2) * 522 + x + 261)
            }
        }
        val change = SpriteCellOps.swap(doc, "sprite", 0, 1)
        val after = before.apply(change)
        val expected = before.copy()
        // Independent per-pixel ownership oracle; no transfer partitioning reused here.
        for (layer in doc.layers) {
            val plan = plans.getValue(layer.id)
            for (y in -1..1) for (x in -260..-1) {
                val otherX = x + 260
                val a = plan.planeAt(x.toLong(), y.toLong()).celId
                val b = plan.planeAt(otherX.toLong(), y.toLong()).celId
                expected.put(layer.id, a, x, y, before.get(layer.id, b, otherX, y))
                expected.put(layer.id, b, otherX, y, before.get(layer.id, a, x, y))
            }
        }
        assertEquals(expected.values, after.values)
        assertEquals(before.values, after.apply(change).values)
        assertEquals(doc, change.doc)
        val linked = doc.layers.first().regions.first { it.boardId == leftId }.frameCel.values.toList()
        assertEquals(linked[1], linked[2])
        assertTrue(change.transfers.filter { it.layerId == "hidden" }.all {
            it.fromCelId == "hidden-shared" && it.toCelId == "hidden-shared"
        })
        // Every destination pixel is written exactly once, even where ownership changes on both sides.
        val destinations = mutableSetOf<Pixel>()
        for (transfer in change.transfers) forEachPixel(transfer.destinationRect) { x, y ->
            assertTrue(destinations.add(Pixel.at(transfer.layerId, transfer.toCelId, x, y)))
        }
        assertEquals(2 * 520 * 3, destinations.size)
    }

    @Test fun `masks use separate reciprocal whole cells and absent coverage is white`() {
        val source = base()
        val doc = source.copy(layers = listOf(source.layers.single().copy(mask = Cel("mask"))))
        val before = Pixels()
        before.put("paint", "mask", -260, -1, 0, default = -1)
        before.put("paint", "mask", -261, -1, 17, default = -1)
        val change = SpriteCellOps.swap(doc, "sprite", 0, 1)
        assertEquals(2, change.maskTransfers.size)
        assertTrue(change.transfers.none { it.fromCelId == "mask" || it.toCelId == "mask" })
        assertEquals(SpriteGridMath.cellRect(doc.boards.single(), 0), change.maskTransfers.first().sourceRect)
        val after = before.apply(change)
        assertEquals(-1, after.get("paint", "mask", -260, -1, default = -1))
        assertEquals(0, after.get("paint", "mask", 0, -1, default = -1))
        assertEquals(17, after.get("paint", "mask", -261, -1, default = -1))
        assertEquals(before.values, after.apply(change).values)
        assertEquals(doc.paper, change.doc.paper)
    }

    @Test fun `ink participates in ownership plan without converting its content`() {
        val source = base()
        val ink = Layer("ink", "Ink", LayerKind.INK, visible = false,
            cels = listOf(Cel("ink-shared", strokesFile = "ink.bin")))
        val doc = RegionDocumentOps.create(source.copy(layers = source.layers + ink),
            "Region", RectPx(-250, -1, 4, 2), ::ids).doc
        val change = SpriteCellOps.swap(doc, "sprite", 0, 1)
        assertSame(doc, change.doc)
        assertTrue(change.transfers.any { it.layerId == "ink" && it.fromCelId != "ink-shared" })
        assertEquals(ink.cels.first(), change.doc.layers.last().cels.first())
    }

    @Test fun `same cell is empty and invalid unlocked or wrong board refuses`() {
        val doc = base()
        assertEquals(RegionChange(doc), SpriteCellOps.swap(doc, "sprite", 1, 1))
        assertFailsWith<DocException> { SpriteCellOps.swap(doc, "missing", 0, 1) }
        assertFailsWith<DocException> { SpriteCellOps.swap(doc, "sprite", -1, 0) }
        assertFailsWith<DocException> { SpriteCellOps.swap(doc, "sprite", 0, 2) }
        assertFailsWith<DocException> { SpriteCellOps.swap(doc, "sprite", 2, 2) }
        assertFailsWith<DocException> { SpriteCellOps.swap(doc.copy(boards = doc.boards.map { it.copy(locked = false) }), "sprite", 0, 1) }
        assertFailsWith<DocException> { SpriteCellOps.swap(doc.copy(boards = doc.boards.map {
            it.copy(kind = BoardKind.CANVAS, grid = null)
        }), "sprite", 0, 1) }
        assertFailsWith<DocException> { SpriteCellOps.swap(doc.copy(boards = doc.boards.map {
            it.copy(grid = SpriteGrid(2, 1, 261, 3))
        }), "sprite", 0, 1) }
        assertFailsWith<DocException> { SpriteCellOps.swap(doc.copy(boards = doc.boards.map {
            it.copy(grid = SpriteGrid(4097, 1, 1, 1))
        }), "sprite", 0, 1) }
    }

    @Test fun `transfer refuses scaling empty bounds and overflowing destination`() {
        val rect = RectPx(0, 0, 2, 3)
        assertFailsWith<IllegalArgumentException> { RegionTransfer("l", "a", "b", rect, rect.copy(w = 1)) }
        assertFailsWith<IllegalArgumentException> { RegionTransfer("l", "a", "b", rect.copy(w = 0), rect.copy(w = 0)) }
        assertFailsWith<IllegalArgumentException> { RegionMaskTransfer("l", rect, rect.copy(x = Int.MAX_VALUE)) }
    }

    /** Sparse packed RGBA reference, addressed by physical cel, tile and pixel (negative-safe). */
    private data class Pixel(val layer: String, val cel: String, val tile: Long, val offset: Int) {
        companion object {
            fun at(layer: String, cel: String, x: Int, y: Int) =
                Pixel(layer, cel, Tiles.key(x shr 8, y shr 8), (y and 255) * 256 + (x and 255))
        }
    }

    private class Pixels(val values: MutableMap<Pixel, Int> = mutableMapOf()) {
        fun copy() = Pixels(values.toMutableMap())
        fun get(layer: String, cel: String, x: Int, y: Int, default: Int = 0) = values[Pixel.at(layer, cel, x, y)] ?: default
        fun put(layer: String, cel: String, x: Int, y: Int, value: Int, default: Int = 0) {
            val key = Pixel.at(layer, cel, x, y)
            if (value == default) values.remove(key) else values[key] = value
        }
        fun apply(change: RegionChange): Pixels {
            val after = copy()
            fun transfer(layer: String, from: String, to: String, source: RectPx, destination: RectPx, default: Int) {
                for (y in 0 until source.h) for (x in 0 until source.w) {
                    // Always read THIS original store, never after, even for same-cel swaps.
                    after.put(layer, to, destination.x + x, destination.y + y,
                        get(layer, from, source.x + x, source.y + y, default), default)
                }
            }
            for (t in change.transfers) transfer(t.layerId, t.fromCelId, t.toCelId, t.sourceRect, t.destinationRect, 0)
            for (t in change.maskTransfers) {
                val mask = change.doc.layers.first { it.id == t.layerId }.mask!!.id
                transfer(t.layerId, mask, mask, t.sourceRect, t.destinationRect, -1)
            }
            return after
        }
    }

    private fun forEachPixel(rect: RectPx, action: (Int, Int) -> Unit) {
        for (y in rect.y until rect.y + rect.h) for (x in rect.x until rect.x + rect.w) action(x, y)
    }
}
