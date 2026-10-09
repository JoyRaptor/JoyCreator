package cc.joycreator.joybrush.core.doc

import kotlin.test.*

/**
 * JB-NOW-17 — Animation-board operation plan safety.
 *
 * Plan proof only: every assertion inspects the returned [RegionChange] metadata
 * (rects, transfers, clears, masks, drops, ids) and the resulting [JbDocument].
 * No pixels are executed and no GL behaviour is claimed.
 *
 * Covers supported resize/moveAll/duplicate/remove on one-frame and multi-frame
 * boards, overlap refusal, negative positions and linked/held cels. Rejected
 * inputs must leave the input document unchanged. Explicit moveAll uses its own
 * supported semantics (permitted even when locked); the ordinary locked-frame
 * drag restriction applies only to resize.
 */
class AnimationBoardOperationCorpusTest {
    private var serial = 0
    private fun ids() = "anim-op-corpus-${++serial}"

    private fun baseDoc(): JbDocument = JbDocument(
        id = "drawing",
        name = "Test",
        boards = listOf(Board("image", "Image", BoardKind.CANVAS, RectPx(-100, -100, 200, 200))),
        layers = listOf(
            Layer("paint", "Paint", LayerKind.PAINT, cels = listOf(Cel("shared")), mask = Cel("mask")),
            Layer("second", "Second", LayerKind.PAINT, cels = listOf(Cel("shared2")), mask = Cel("mask2")),
            Layer("ink", "Ink", LayerKind.INK, cels = listOf(Cel("ink-shared")))
        )
    )

    private fun singleFrame(rect: RectPx = RectPx(-4, -2, 8, 4)): JbDocument =
        RegionDocumentOps.create(baseDoc(), "Animation", rect, ::ids).doc

    private fun sharedCel(doc: JbDocument, layerId: String): String =
        requireNotNull(doc.layers.first { it.id == layerId }.sharedCelId)

    private fun frameCel(doc: JbDocument, boardId: String, layerId: String): String {
        val board = doc.boards.first { it.id == boardId }
        val region = doc.layers.first { it.id == layerId }.regions.first { it.boardId == boardId }
        return region.frameCel.getValue(requireNotNull(board.currentFrameId))
    }

    @Test fun `resize on one frame covers every layer and keeps masks stationary`() {
        val doc = singleFrame()
        val id = doc.activeBoardId!!
        assertEquals(RectPx(-4, -2, 8, 4), doc.boards.first { it.id == id }.rect)
        assertEquals(1, doc.boards.first { it.id == id }.frames.size)
        // Shift right by 2 at the same size: hand-computed leaving/entering strips.
        val target = RectPx(-2, -2, 8, 4)
        val leaving = RectPx(-4, -2, 2, 4)
        val entering = RectPx(4, -2, 2, 4)
        val change = AnimationBoardOps.resize(doc, id, target)
        assertEquals(target, change.doc.boards.first { it.id == id }.rect)
        // Three layers, each one leaving plus one entering transfer/clear.
        assertEquals(6, change.transfers.size)
        assertEquals(6, change.clears.size)
        for (layer in doc.layers) {
            val shared = sharedCel(doc, layer.id)
            val cel = frameCel(doc, id, layer.id)
            val transfers = change.transfers.filter { it.layerId == layer.id }
            assertEquals(2, transfers.size, "layer ${layer.id}")
            assertTrue(transfers.any {
                it.fromCelId == cel && it.toCelId == shared &&
                    it.sourceRect == leaving && it.destinationRect == leaving
            }, "leaving cel to shared for ${layer.id}")
            assertTrue(transfers.any {
                it.fromCelId == shared && it.toCelId == cel &&
                    it.sourceRect == entering && it.destinationRect == entering
            }, "entering shared to cel for ${layer.id}")
            val clears = change.clears.filter { it.layerId == layer.id }
            assertEquals(2, clears.size, "layer ${layer.id}")
            assertTrue(clears.any { it.celId == cel && it.rect == leaving })
            assertTrue(clears.any { it.celId == shared && it.rect == entering })
        }
        // Masks are world-space coverage: a passive crop never moves them.
        assertTrue(change.maskTransfers.isEmpty())
        assertTrue(change.maskClears.isEmpty())
        for (layer in doc.layers) {
            assertEquals(layer.mask?.id, change.doc.layers.first { it.id == layer.id }.mask?.id)
        }
        assertEquals(doc.layers, change.doc.layers)
        assertTrue(DocOps.validate(change.doc).isEmpty())
        assertEquals(RegionChange(doc), AnimationBoardOps.resize(doc, id, RectPx(-4, -2, 8, 4)))
    }

    @Test fun `locked boards refuse resize but moveAll succeeds on one and many frames`() {
        val doc = singleFrame()
        val id = doc.activeBoardId!!
        val docSnapshot = doc.copy()
        val target = RectPx(-2, -2, 8, 4)
        val locked = BoardDocumentOps.setLocked(doc, id, true)
        val lockedSnapshot = locked.copy()
        assertFailsWith<DocException> { AnimationBoardOps.resize(locked, id, target) }
        assertEquals(lockedSnapshot, locked, "rejected resize leaves the locked document unchanged")
        // Explicit moveAll is a deliberate translation: permitted even when locked.
        val movedLocked = AnimationBoardOps.moveAll(locked, id, 20, 5)
        assertEquals(RectPx(20, 5, 8, 4), movedLocked.doc.boards.first { it.id == id }.rect)
        assertTrue(movedLocked.transfers.isNotEmpty())
        assertTrue(movedLocked.doc.boards.first { it.id == id }.locked)
        // Multi-frame is fixed for resize even when unlocked, but moveAll still succeeds.
        val unlocked = BoardDocumentOps.setLocked(doc, id, false)
        val two = RegionDocumentOps.addFrame(unlocked, id, NewFrame.LINK, ::ids).doc
        assertEquals(2, two.boards.first { it.id == id }.frames.size)
        assertFailsWith<DocException> { AnimationBoardOps.resize(two, id, target) }
        assertFailsWith<DocException> { AnimationBoardOps.resize(BoardDocumentOps.setLocked(two, id, true), id, target) }
        val movedMultiLocked = AnimationBoardOps.moveAll(BoardDocumentOps.setLocked(two, id, true), id, 20, 5)
        assertEquals(RectPx(20, 5, 8, 4), movedMultiLocked.doc.boards.first { it.id == id }.rect)
        assertEquals(
            two.boards.first { it.id == id }.frames,
            movedMultiLocked.doc.boards.first { it.id == id }.frames
        )
        assertEquals(
            two.boards.first { it.id == id }.currentFrameId,
            movedMultiLocked.doc.boards.first { it.id == id }.currentFrameId
        )
        assertTrue(DocOps.validate(movedLocked.doc).isEmpty())
        assertTrue(DocOps.validate(movedMultiLocked.doc).isEmpty())
        assertEquals(docSnapshot, doc, "rejected resize leaves the caller's document value unchanged")
    }

    @Test fun `moveAll moves each unique cel once on one and many frames`() {
        val one = singleFrame()
        val id = one.activeBoardId!!
        val origin = RectPx(-4, -2, 8, 4)
        val dest = RectPx(10, 5, 8, 4)
        val movedOne = AnimationBoardOps.moveAll(one, id, 10, 5)
        assertEquals(dest, movedOne.doc.boards.first { it.id == id }.rect)
        // One frame: three layers, one unique frame cel each.
        assertEquals(3, movedOne.transfers.size)
        assertEquals(3, movedOne.clears.size)
        for (layer in one.layers) {
            val cel = frameCel(one, id, layer.id)
            val transfers = movedOne.transfers.filter { it.layerId == layer.id }
            assertEquals(1, transfers.size, "layer ${layer.id}")
            assertEquals(cel, transfers.single().fromCelId)
            assertEquals(cel, transfers.single().toCelId)
            assertEquals(origin, transfers.single().sourceRect)
            assertEquals(dest, transfers.single().destinationRect)
            assertEquals(origin, movedOne.clears.first { it.layerId == layer.id }.rect)
        }
        // Two paint masks travel independently; ink has no mask.
        assertEquals(2, movedOne.maskTransfers.size)
        assertEquals(2, movedOne.maskClears.size)
        for (t in movedOne.maskTransfers) {
            assertEquals(origin, t.sourceRect)
            assertEquals(dest, t.destinationRect)
        }
        // Many frames with LINK then BLANK: each layer has two unique cels, moved once each.
        var multi = RegionDocumentOps.addFrame(one, id, NewFrame.LINK, ::ids).doc
        multi = RegionDocumentOps.addFrame(multi, id, NewFrame.BLANK, ::ids).doc
        val board = multi.boards.first { it.id == id }
        assertEquals(3, board.frames.size)
        for (layer in multi.layers) {
            val uniques = layer.regions.first { it.boardId == id }.frameCel.values.toSet()
            assertEquals(2, uniques.size, "linked pair shares, blank distinct for ${layer.id}")
        }
        val movedMulti = AnimationBoardOps.moveAll(multi, id, 10, 5)
        assertEquals(6, movedMulti.transfers.size, "two uniques times three layers, not three frames")
        for (layer in multi.layers) {
            val uniques = layer.regions.first { it.boardId == id }.frameCel.values.toSet()
            val transfers = movedMulti.transfers.filter { it.layerId == layer.id }
            assertEquals(2, transfers.size, "layer ${layer.id}")
            assertEquals(uniques, transfers.map { it.fromCelId }.toSet())
            assertTrue(transfers.all { it.fromCelId == it.toCelId && it.sourceRect == origin && it.destinationRect == dest })
        }
        assertEquals(multi.layers, movedMulti.doc.layers)
        assertEquals(board.frames, movedMulti.doc.boards.first { it.id == id }.frames)
        assertEquals(board.currentFrameId, movedMulti.doc.boards.first { it.id == id }.currentFrameId)
        assertTrue(DocOps.validate(movedMulti.doc).isEmpty())
        assertEquals(RegionChange(one), AnimationBoardOps.moveAll(one, id, -4, -2))
    }

    @Test fun `negative origins stay exact for resize move and duplicate`() {
        val neg = RectPx(-12, -9, 6, 4)
        val doc = singleFrame(neg)
        val id = doc.activeBoardId!!
        // Grow left by 2: no leaving, one entering strip hand-computed.
        val grown = RectPx(-14, -9, 8, 4)
        val entering = RectPx(-14, -9, 2, 4)
        val resized = AnimationBoardOps.resize(doc, id, grown)
        assertEquals(grown, resized.doc.boards.first { it.id == id }.rect)
        for (layer in doc.layers) {
            val shared = sharedCel(doc, layer.id)
            val cel = frameCel(doc, id, layer.id)
            val transfers = resized.transfers.filter { it.layerId == layer.id }
            assertEquals(1, transfers.size, "layer ${layer.id}")
            assertEquals(shared, transfers.single().fromCelId)
            assertEquals(cel, transfers.single().toCelId)
            assertEquals(entering, transfers.single().sourceRect)
            assertEquals(entering, transfers.single().destinationRect)
            val clears = resized.clears.filter { it.layerId == layer.id }
            assertEquals(1, clears.size, "layer ${layer.id}")
            assertEquals(shared, clears.single().celId)
            assertEquals(entering, clears.single().rect)
        }
        // Move further negative with no self-overlap.
        val dest = RectPx(-20, -20, 6, 4)
        val moved = AnimationBoardOps.moveAll(doc, id, -20, -20)
        assertEquals(dest, moved.doc.boards.first { it.id == id }.rect)
        assertTrue(moved.transfers.all { it.sourceRect == neg && it.destinationRect == dest })
        // Duplicate to a third negative slot.
        val dupDest = RectPx(-30, -30, 6, 4)
        val dup = AnimationBoardOps.duplicate(doc, id, -30, -30, ::ids)
        assertEquals(dupDest, dup.doc.boards.last().rect)
        assertTrue(DocOps.validate(resized.doc).isEmpty())
        assertTrue(DocOps.validate(moved.doc).isEmpty())
        assertTrue(DocOps.validate(dup.doc).isEmpty())
    }

    @Test fun `duplicate remaps ids preserving links holds held and masks`() {
        val base = singleFrame()
        val id = base.activeBoardId!!
        var doc = RegionDocumentOps.addFrame(base, id, NewFrame.LINK, ::ids).doc
        val firstFrame = doc.boards.first { it.id == id }.frames.first().id
        doc = RegionDocumentOps.setHold(doc, id, firstFrame, 7)
        doc = RegionDocumentOps.setHeld(doc, id, "paint", true).doc
        val snapshot = doc
        val board = doc.boards.first { it.id == id }
        assertEquals(listOf(7, 1), board.frames.map { it.holdFrames })
        val change = AnimationBoardOps.duplicate(doc, id, 20, 0, ::ids)
        val copy = change.doc.boards.last()
        assertEquals(RectPx(20, 0, 8, 4), copy.rect)
        assertEquals("Animation 2", copy.name)
        assertEquals(copy.id, change.doc.activeBoardId)
        assertTrue(copy.id !in doc.boards.map { it.id })
        assertTrue(copy.frames.none { it.id in board.frames.map { f -> f.id } })
        assertEquals(2, copy.frames.map { it.id }.toSet().size)
        assertEquals(board.frames.map { it.holdFrames }, copy.frames.map { it.holdFrames })
        val currentIndex = board.frames.indexOfFirst { it.id == board.currentFrameId }
        assertEquals(copy.frames[currentIndex].id, copy.currentFrameId)
        val originalCelIds = doc.layers.flatMap { l -> l.cels.map { c -> c.id } }.toSet()
        for (layer in doc.layers) {
            val origRegion = layer.regions.first { it.boardId == id }
            val newRegion = change.doc.layers.first { it.id == layer.id }.regions.first { it.boardId == copy.id }
            assertEquals(origRegion.held, newRegion.held)
            assertEquals(copy.frames.map { it.id }.toSet(), newRegion.frameCel.keys)
            assertTrue(newRegion.frameCel.values.none { it in origRegion.frameCel.values })
            assertTrue(newRegion.frameCel.values.none { it in originalCelIds })
            // Linked shape survives: one shared cel maps both frames in source and in clone.
            assertEquals(1, origRegion.frameCel.values.toSet().size)
            assertEquals(1, newRegion.frameCel.values.toSet().size)
        }
        // Held paint duplicates shared art; every other region duplicates frame art.
        assertTrue(change.transfers.any {
            it.layerId == "paint" && it.fromCelId == sharedCel(doc, "paint") && it.toCelId == sharedCel(doc, "paint")
        })
        // Two paint masks copy independently; ink carries no mask plan.
        assertEquals(2, change.maskTransfers.size)
        for (t in change.maskTransfers) {
            assertEquals(RectPx(-4, -2, 8, 4), t.sourceRect)
            assertEquals(RectPx(20, 0, 8, 4), t.destinationRect)
        }
        assertTrue(change.transfers.none { it.layerId == "ink" && it.fromCelId == sharedCel(doc, "ink") })
        assertTrue(change.clears.isEmpty() && change.drops.isEmpty())
        assertTrue(DocOps.validate(change.doc).isEmpty())
        assertEquals(change.doc, DocJson.decode(DocJson.encode(change.doc)))
        assertFailsWith<DocException> { AnimationBoardOps.duplicate(doc, id, 30, 10) { "paint" } }
        assertEquals(snapshot, doc)
    }

    @Test fun `overlap is refused for resize move and duplicate`() {
        val doc = singleFrame()
        val id = doc.activeBoardId!!
        val neighbor = RegionDocumentOps.create(doc, "Neighbor", RectPx(20, 0, 8, 4), ::ids).doc
        val neighborId = neighbor.activeBoardId!!
        assertTrue(DocOps.validate(neighbor).isEmpty())
        val snapshot = neighbor
        // Resize into the neighbor: -4..22 overlaps 20..28 in x and -2..2 overlaps 0..4 in y.
        assertFailsWith<DocException> { AnimationBoardOps.resize(neighbor, id, RectPx(-4, -2, 26, 4)) }
        // Move onto the neighbor, exactly and partially.
        assertFailsWith<DocException> { AnimationBoardOps.moveAll(neighbor, id, 20, 0) }
        assertFailsWith<DocException> { AnimationBoardOps.moveAll(neighbor, id, 18, 0) }
        // Duplicate onto the neighbor and onto the source itself.
        assertFailsWith<DocException> { AnimationBoardOps.duplicate(neighbor, id, 20, 0, ::ids) }
        assertFailsWith<DocException> { AnimationBoardOps.duplicate(neighbor, id, -4, -2, ::ids) }
        // The neighbor cannot move onto the original either.
        assertFailsWith<DocException> { AnimationBoardOps.moveAll(neighbor, neighborId, -4, -2) }
        assertEquals(snapshot, neighbor)
        assertTrue(DocOps.validate(neighbor).isEmpty())
    }

    @Test fun `remove flattens current and drops only orphaned cels`() {
        var doc = singleFrame()
        val id = doc.activeBoardId!!
        doc = RegionDocumentOps.addFrame(doc, id, NewFrame.BLANK, ::ids).doc
        val secondFrame = doc.boards.first { it.id == id }.currentFrameId!!
        doc = RegionDocumentOps.addFrame(doc, id, NewFrame.LINK, ::ids).doc
        doc = RegionDocumentOps.selectFrame(doc, id, secondFrame)
        doc = RegionDocumentOps.setHeld(doc, id, "paint", true).doc
        doc = RegionDocumentOps.create(doc, "Neighbor", RectPx(20, 0, 8, 4), ::ids).doc
        val neighborId = doc.activeBoardId!!
        val snapshot = doc
        val change = AnimationBoardOps.remove(doc, id)
        // Held paint contributes no flatten transfer; animated layers flatten the selected frame.
        assertTrue(change.transfers.none { it.layerId == "paint" })
        assertTrue(change.transfers.any { it.layerId == "second" })
        assertTrue(change.transfers.any { it.layerId == "ink" })
        for (t in change.transfers) {
            assertEquals(RectPx(-4, -2, 8, 4), t.sourceRect)
            assertEquals(RectPx(-4, -2, 8, 4), t.destinationRect)
            val layer = doc.layers.first { it.id == t.layerId }
            val expectedFrom = layer.regions.first { it.boardId == id }.frameCel.getValue(secondFrame)
            assertEquals(expectedFrom, t.fromCelId)
            assertEquals(requireNotNull(layer.sharedCelId), t.toCelId)
        }
        // Each removed unique frame cel drops once; neighboring regions survive untouched.
        assertEquals(6, change.drops.size, "two uniques times three layers")
        for (layer in change.doc.layers) {
            assertEquals(listOf(neighborId), layer.regions.map { it.boardId })
            assertTrue(layer.regions.single().frameCel.values.none { c ->
                change.drops.any { it.layerId == layer.id && it.celId == c }
            })
        }
        for (layer in doc.layers) {
            assertEquals(layer.mask?.id, change.doc.layers.first { it.id == layer.id }.mask?.id)
        }
        assertEquals(neighborId, change.doc.activeBoardId)
        // Single-frame removal as well: the neighbor alone flattens its only frame.
        val singleRemove = AnimationBoardOps.remove(change.doc, neighborId)
        assertEquals(listOf("image"), singleRemove.doc.boards.map { it.id })
        assertTrue(singleRemove.doc.layers.all { it.regions.isEmpty() })
        assertTrue(DocOps.validate(change.doc).isEmpty())
        assertTrue(DocOps.validate(singleRemove.doc).isEmpty())
        assertEquals(snapshot, doc)
    }

    @Test fun `held resize keeps shared and clears dormant frame`() {
        val doc = singleFrame()
        val id = doc.activeBoardId!!
        val heldDoc = RegionDocumentOps.setHeld(doc, id, "paint", true).doc
        val target = RectPx(-2, -2, 8, 4)
        val leaving = RectPx(-4, -2, 2, 4)
        val entering = RectPx(4, -2, 2, 4)
        val change = AnimationBoardOps.resize(heldDoc, id, target)
        // Held paint: one entering shared-to-frame transfer, one leaving frame clear.
        val heldTransfers = change.transfers.filter { it.layerId == "paint" }
        assertEquals(1, heldTransfers.size)
        assertEquals(sharedCel(heldDoc, "paint"), heldTransfers.single().fromCelId)
        assertEquals(frameCel(heldDoc, id, "paint"), heldTransfers.single().toCelId)
        assertEquals(entering, heldTransfers.single().sourceRect)
        assertEquals(entering, heldTransfers.single().destinationRect)
        val heldClears = change.clears.filter { it.layerId == "paint" }
        assertEquals(1, heldClears.size)
        assertEquals(frameCel(heldDoc, id, "paint"), heldClears.single().celId)
        assertEquals(leaving, heldClears.single().rect)
        // Animated layers keep both directions.
        assertEquals(2, change.transfers.filter { it.layerId == "second" }.size)
        assertEquals(2, change.clears.filter { it.layerId == "second" }.size)
        assertEquals(2, change.transfers.filter { it.layerId == "ink" }.size)
        assertEquals(2, change.clears.filter { it.layerId == "ink" }.size)
        assertTrue(change.maskTransfers.isEmpty() && change.maskClears.isEmpty())
        assertEquals(heldDoc.layers, change.doc.layers)
        assertTrue(DocOps.validate(change.doc).isEmpty())
    }

    @Test fun `rejected inputs leave documents unchanged`() {
        val doc = singleFrame()
        val id = doc.activeBoardId!!
        val snapshot = doc
        // Wrong-kind and unknown boards.
        assertFailsWith<DocException> { AnimationBoardOps.resize(doc, "image", RectPx(0, 0, 4, 4)) }
        assertFailsWith<DocException> { AnimationBoardOps.moveAll(doc, "image", 0, 0) }
        assertFailsWith<DocException> { AnimationBoardOps.duplicate(doc, "image", 30, 30, ::ids) }
        assertFailsWith<DocException> { AnimationBoardOps.remove(doc, "image") }
        assertFailsWith<DocException> { AnimationBoardOps.resize(doc, "missing", RectPx(0, 0, 4, 4)) }
        assertFailsWith<DocException> { AnimationBoardOps.moveAll(doc, "missing", 0, 0) }
        // Empty and overflowing geometry never publishes.
        assertFailsWith<DocException> { AnimationBoardOps.resize(doc, id, RectPx(0, 0, 0, 4)) }
        assertFailsWith<DocException> { AnimationBoardOps.resize(doc, id, RectPx(Int.MAX_VALUE, 0, 10, 10)) }
        assertFailsWith<DocException> { AnimationBoardOps.moveAll(doc, id, Int.MAX_VALUE, 0) }
        assertFailsWith<DocException> { AnimationBoardOps.duplicate(doc, id, Int.MAX_VALUE, 0, ::ids) }
        // Deterministic suppliers must be non-blank and fresh.
        assertFailsWith<DocException> { AnimationBoardOps.duplicate(doc, id, 30, 30) { "" } }
        assertFailsWith<DocException> { AnimationBoardOps.duplicate(doc, id, 30, 30) { "paint" } }
        assertFailsWith<DocException> { AnimationBoardOps.duplicate(doc, id, 30, 30) { id } }
        // Removing the final board is refused.
        val singleAnim = doc.copy(
            boards = listOf(doc.boards.first { it.id == id }),
            activeBoardId = id
        )
        assertTrue(DocOps.validate(singleAnim).isEmpty())
        assertFailsWith<DocException> { AnimationBoardOps.remove(singleAnim, id) }
        assertEquals(snapshot, doc)
        assertTrue(DocOps.validate(doc).isEmpty())
    }
}
