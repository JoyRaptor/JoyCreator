package cc.joycreator.joybrush.core.doc

import kotlin.test.*

class AnimationBoardOpsTest {
    private var serial = 0
    private fun ids() = "animation-op-${++serial}"
    private fun source(): JbDocument {
        val base = JbDocument(id = "drawing", name = "Test",
            boards = listOf(Board("image", "Image", BoardKind.CANVAS, RectPx(-100, -100, 200, 200))),
            layers = listOf(Layer("paint", "Paint", LayerKind.PAINT, cels = listOf(Cel("shared")), mask = Cel("mask"))))
        return RegionDocumentOps.create(base, "Animation", RectPx(-2, -1, 4, 3), ::ids).doc
    }
    private fun cel(doc: JbDocument, board: String = doc.activeBoardId!!) = doc.layers.first().regions
        .first { it.boardId == board }.frameCel.getValue(doc.boards.first { it.id == board }.currentFrameId!!)

    @Test fun `art under a new board moves into frame 1, so moving the board brings nothing old back`() {
        // The board audit's case (2026-10-08): board over a sketch, sketch cleaned off frame 1, Move all.
        val base = JbDocument(id = "drawing", name = "Test",
            boards = listOf(Board("image", "Image", BoardKind.CANVAS, RectPx(-100, -100, 200, 200))),
            layers = listOf(Layer("paint", "Paint", LayerKind.PAINT, cels = listOf(Cel("shared")), mask = Cel("mask"))))
        val change = RegionDocumentOps.create(base, "Animation", RectPx(-2, -1, 4, 3), ::ids)
        val doc = change.doc; val frame = cel(doc)
        val sketch = Pixels().also { it.put("shared", 0, 0, 42); it.put("shared", 8, 0, 7) }
        val made = sketch.apply(change)
        assertEquals(42, made.get(frame, 0, 0), "the sketch is frame 1's")
        assertEquals(0, made.get("shared", 0, 0), "and nothing is left hidden under the board")
        assertEquals(7, made.get("shared", 8, 0), "art outside the board is untouched")
        made.put(frame, 0, 0, 0)
        val moved = made.apply(AnimationBoardOps.moveAll(doc, doc.activeBoardId!!, 4, -1))
        assertEquals(0, moved.get("shared", 0, 0), "where the board was is empty canvas: the cleaned sketch stays gone")
        assertEquals(7, moved.get("shared", 8, 0), "shared art beside the destination is untouched")
    }

    @Test fun `single frame crop changes keep world pixels stationary including entering shared and leaving frame`() {
        val doc = source(); val id = doc.activeBoardId!!; val frame = cel(doc)
        val before = Pixels()
        before.put(frame, -2, 0, 11); before.put("shared", -2, 0, 99)
        before.put(frame, 0, 0, 12); before.put("shared", 2, 0, 13)
        before.put(frame, 2, 0, 88) // Dormant out-of-bounds cel pixel must not reappear on expansion.
        before.put("shared", 8, 0, 14); before.put("mask", -2, 0, 15, 255)
        val change = AnimationBoardOps.resize(doc, id, RectPx(-1, -1, 4, 3))
        val after = before.apply(change)
        assertEquals(11, after.get("shared", -2, 0)); assertEquals(0, after.get(frame, -2, 0))
        assertEquals(12, after.get(frame, 0, 0)); assertEquals(13, after.get(frame, 2, 0))
        assertEquals(0, after.get("shared", 2, 0), "art the board grew over is taken in, not left hidden under it")
        assertEquals(14, after.get("shared", 8, 0)); assertEquals(15, after.get("mask", -2, 0, 255))
        assertTrue(change.maskTransfers.isEmpty() && change.maskClears.isEmpty())
        assertEquals(doc.layers, change.doc.layers)
        assertEquals(RegionChange(doc), AnimationBoardOps.resize(doc, id, doc.boards.last().rect))
    }

    @Test fun `resize refuses lock multiple frames neighboring overlap and overflow`() {
        val doc = source(); val id = doc.activeBoardId!!
        val rect = RectPx(4, 2, 4, 3)
        assertFailsWith<DocException> { AnimationBoardOps.resize(BoardDocumentOps.setLocked(doc, id, true), id, rect) }
        val multi = RegionDocumentOps.addFrame(doc, id, NewFrame.LINK, ::ids).doc
        assertFailsWith<DocException> { AnimationBoardOps.resize(multi, id, rect) }
        val neighbor = RegionDocumentOps.create(doc, "Neighbor", rect, ::ids).doc
        assertFailsWith<DocException> { AnimationBoardOps.resize(neighbor, id, rect) }
        assertFailsWith<DocException> { AnimationBoardOps.resize(doc, id, rect.copy(x = Int.MAX_VALUE)) }
        assertFailsWith<DocException> { AnimationBoardOps.resize(doc, "image", rect) }
    }

    @Test fun `overlapping move all snapshots every unique linked frame and preserves hidden substrate`() {
        var doc = source(); val id = doc.activeBoardId!!; val first = cel(doc)
        doc = RegionDocumentOps.addFrame(doc, id, NewFrame.BLANK, ::ids).doc
        val second = cel(doc)
        doc = RegionDocumentOps.addFrame(doc, id, NewFrame.LINK, ::ids).doc
        doc = BoardDocumentOps.setLocked(doc, id, true)
        val before = Pixels()
        for (x in -2..1) { before.put(first, x, 0, x + 20); before.put(second, x, 0, x + 40) }
        before.put("shared", -2, 0, 99); before.put("shared", 4, 0, 77)
        before.put("mask", -2, 0, 23, 255); before.put("mask", 1, 0, 27, 255)
        val change = AnimationBoardOps.moveAll(doc, id, 0, -1)
        val after = before.apply(change)
        assertEquals(2, change.transfers.size) // Linked cel is moved once, not once per frame.
        for (x in 0..3) {
            assertEquals(x + 18, after.get(first, x, 0)); assertEquals(x + 38, after.get(second, x, 0))
        }
        assertEquals(0, after.get(first, -2, 0)); assertEquals(99, after.get("shared", -2, 0))
        assertEquals(77, after.get("shared", 4, 0)); assertEquals(255, after.get("mask", -2, 0, 255))
        assertEquals(23, after.get("mask", 0, 0, 255)); assertEquals(27, after.get("mask", 3, 0, 255))
        assertEquals(doc.layers, change.doc.layers)
        assertEquals(doc.boards.last().frames, change.doc.boards.last().frames)
        assertEquals(doc.boards.last().currentFrameId, change.doc.boards.last().currentFrameId)
        assertEquals(RegionChange(doc), AnimationBoardOps.moveAll(doc, id, -2, -1))
    }

    @Test fun `held artwork moves shared and dormant frame addresses while neighbor content survives`() {
        var doc = source(); val id = doc.activeBoardId!!; val frame = cel(doc)
        doc = RegionDocumentOps.setHeld(doc, id, "paint", true).doc
        doc = RegionDocumentOps.create(doc, "Neighbor", RectPx(20, 0, 3, 3), ::ids).doc
        val neighborCel = cel(doc)
        val before = Pixels(); before.put("shared", -2, 0, 5); before.put(frame, -2, 0, 6)
        before.put(neighborCel, 20, 0, 7); before.put("shared", 20, 0, 8)
        val change = AnimationBoardOps.moveAll(doc, id, 4, -1)
        val after = before.apply(change)
        assertEquals(5, after.get("shared", 4, 0)); assertEquals(0, after.get("shared", -2, 0))
        assertEquals(6, after.get(frame, 4, 0)); assertEquals(0, after.get(frame, -2, 0))
        assertEquals(7, after.get(neighborCel, 20, 0)); assertEquals(8, after.get("shared", 20, 0))
        assertFailsWith<DocException> { AnimationBoardOps.moveAll(doc, id, 20, 0) }
    }

    @Test fun `duplicate creates independent frame cels preserving links timing current selection held and masks`() {
        var doc = source(); val id = doc.activeBoardId!!
        doc = RegionDocumentOps.addFrame(doc, id, NewFrame.LINK, ::ids).doc
        doc = RegionDocumentOps.setHold(doc, id, doc.boards.last().currentFrameId!!, 9)
        doc = RegionDocumentOps.setHeld(doc, id, "paint", true).doc
        val before = Pixels(); before.put("shared", -2, 0, 5); before.put(cel(doc), -2, 0, 6)
        before.put("mask", -2, 0, 23, 255)
        val change = AnimationBoardOps.duplicate(doc, id, 5, -1, ::ids)
        val copy = change.doc.boards.last(); val after = before.apply(change)
        val copiedRegion = change.doc.layers.single().regions.last()
        val newCel = copiedRegion.frameCel.values.singleDistinct()
        assertNotEquals(cel(doc), newCel)
        assertTrue(copiedRegion.held)
        assertEquals("Animation 2", copy.name)
        assertEquals(listOf(1, 9), copy.frames.map { it.holdFrames })
        assertEquals(copy.frames.last().id, copy.currentFrameId)
        assertEquals(5, after.get("shared", 5, 0)); assertEquals(6, after.get(newCel, 5, 0))
        assertEquals(23, after.get("mask", 5, 0, 255)); assertEquals(23, after.get("mask", -2, 0, 255))
        assertEquals(6, after.get(cel(doc), -2, 0))
        assertTrue(change.clears.isEmpty() && change.drops.isEmpty())
        assertEquals(change.doc, DocJson.decode(DocJson.encode(change.doc)))
        assertFailsWith<DocException> { AnimationBoardOps.duplicate(doc, id, -2, -1, ::ids) }
        assertFailsWith<DocException> { AnimationBoardOps.duplicate(doc, id, 5, -1) { id } }
    }

    @Test fun `remove flattens selected blank pixels and drops each unused linked cel only once`() {
        var doc = source(); val id = doc.activeBoardId!!; val first = cel(doc)
        doc = RegionDocumentOps.addFrame(doc, id, NewFrame.BLANK, ::ids).doc
        val selected = cel(doc)
        doc = RegionDocumentOps.addFrame(doc, id, NewFrame.LINK, ::ids).doc
        val before = Pixels(); before.put(first, -2, 0, 4); before.put(selected, -1, 0, 8)
        before.put("shared", -2, 0, 9); before.put("shared", 4, 0, 10)
        before.put("mask", -2, 0, 11, 255)
        val change = AnimationBoardOps.remove(doc, id); val after = before.apply(change)
        assertEquals(2, change.drops.size); assertEquals(setOf(first, selected), change.drops.map { it.celId }.toSet())
        assertEquals(0, after.get("shared", -2, 0)); assertEquals(8, after.get("shared", -1, 0))
        assertEquals(10, after.get("shared", 4, 0)); assertEquals(11, after.get("mask", -2, 0, 255))
        assertEquals(listOf(Cel("shared")), change.doc.layers.single().cels)
        assertTrue(change.doc.layers.single().regions.isEmpty())
        assertEquals("image", change.doc.activeBoardId)
        assertTrue(DocOps.validate(change.doc).isEmpty())
    }

    @Test fun `remove held board retains shared and neighboring regions including ink plans`() {
        var doc = source(); val id = doc.activeBoardId!!
        doc = doc.copy(layers = doc.layers + doc.layers.first().copy(id = "ink", kind = LayerKind.INK,
            mask = null, cels = doc.layers.first().cels.map { Cel("ink-${it.id}") }, sharedCelId = "ink-shared",
            regions = doc.layers.first().regions.map { r -> r.copy(frameCel = r.frameCel.mapValues { "ink-${it.value}" }) }))
        doc = RegionDocumentOps.setHeld(doc, id, "paint", true).doc
        doc = RegionDocumentOps.create(doc, "Neighbor", RectPx(20, 0, 3, 3), ::ids).doc
        val neighbor = doc.activeBoardId!!
        val change = AnimationBoardOps.remove(doc, id)
        assertTrue(change.transfers.none { it.layerId == "paint" })
        assertTrue(change.transfers.any { it.layerId == "ink" })
        for (layer in change.doc.layers) {
            assertEquals(listOf(neighbor), layer.regions.map { it.boardId })
            assertTrue(layer.regions.single().frameCel.values.none { c -> change.drops.any { it.layerId == layer.id && it.celId == c } })
        }
        val move = AnimationBoardOps.moveAll(doc, id, 5, -1)
        val copy = AnimationBoardOps.duplicate(doc, id, 5, -1, ::ids)
        assertTrue(move.transfers.any { it.layerId == "ink" })
        assertTrue(copy.transfers.any { it.layerId == "ink" })
    }

    @Test fun `plans scale by frame addresses not board pixels and negative extents remain exact`() {
        val doc = source(); val id = doc.activeBoardId!!
        val huge = AnimationBoardOps.resize(doc, id, RectPx(-1000000, -1000000, 2000000, 2000000))
        assertTrue(huge.transfers.size <= 8 && huge.clears.size <= 4)
        val moved = AnimationBoardOps.moveAll(huge.doc, id, -2000000, -2000000)
        assertEquals(1, moved.transfers.size)
        assertTrue(moved.clears.size <= 4)
    }

    private fun Collection<String>.singleDistinct() = toSet().single()
    private data class Pixel(val layer: String, val cel: String, val x: Int, val y: Int)
    private class Pixels(val values: MutableMap<Pixel, Int> = mutableMapOf()) {
        fun get(cel: String, x: Int, y: Int, default: Int = 0) = values[Pixel("paint", cel, x, y)] ?: default
        fun put(cel: String, x: Int, y: Int, value: Int, default: Int = 0) {
            val key = Pixel("paint", cel, x, y)
            if (value == default) values.remove(key) else values[key] = value
        }
        fun apply(change: RegionChange): Pixels {
            val after = Pixels(values.toMutableMap())
            fun clear(cel: String, rect: RectPx, default: Int) {
                for (y in rect.y until rect.y + rect.h) for (x in rect.x until rect.x + rect.w) after.put(cel, x, y, default, default)
            }
            for (c in change.clears) clear(c.celId, c.rect, 0)
            for (c in change.maskClears) clear(change.doc.layers.first { it.id == c.layerId }.mask!!.id, c.rect, 255)
            fun transfer(from: String, to: String, source: RectPx, dest: RectPx, default: Int) {
                for (y in 0 until source.h) for (x in 0 until source.w)
                    after.put(to, dest.x + x, dest.y + y, get(from, source.x + x, source.y + y, default), default)
            }
            for (t in change.transfers) transfer(t.fromCelId, t.toCelId, t.sourceRect, t.destinationRect, 0)
            for (t in change.maskTransfers) {
                val mask = change.doc.layers.first { it.id == t.layerId }.mask!!.id
                transfer(mask, mask, t.sourceRect, t.destinationRect, 255)
            }
            for (drop in change.drops) after.values.keys.removeAll { it.layer == drop.layerId && it.cel == drop.celId }
            return after
        }
    }
}
