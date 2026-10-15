package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.androidkit.gl.lines.RecordedScene
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.doc.*
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.paint.UndoLog
import cc.joycreator.joybrush.core.stroke.AddressedStroke
import cc.joycreator.joybrush.core.stroke.LineAddress
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.RecordedCel
import cc.joycreator.joybrush.core.vector.RecordedEdits
import kotlin.test.*

/** Real engine ticket/root/history, fake names only: driver appearance and format need GPU proof. */
class GlPlainPromotionTest {
    private val plane: Pair<String, String?> = "paint" to null
    private val brush = BrushPreset(id = "frozen", name = "Pen", size = Param(8f))
    private data class Fixture(val engine: GlPaintEngine, val owner: Any, val doc: JbDocument)
    @Suppress("UNCHECKED_CAST")
    private fun <T> get(owner: Any, name: String): T = owner.javaClass.getDeclaredField(name)
        .also { it.isAccessible = true }.get(owner) as T
    private fun put(owner: Any, name: String, value: Any?) = owner.javaClass.getDeclaredField(name)
        .also { it.isAccessible = true }.set(owner, value)
    private fun root(e: GlPaintEngine): Any? = get(e, "publishedPaint")
    private fun local(e: GlPaintEngine) = get<LinkedHashMap<String, Any>>(e, "localLayers")
    private fun owners(e: GlPaintEngine) = root(e)?.let { get<LinkedHashMap<String, Any>>(it, "nativeLayers") } ?: local(e)
    private fun tiles(owner: Any) = get<HashMap<Long, Int>>(owner, "tiles")
    private fun cels(owner: Any) = get<MutableMap<String, HashMap<Long, Int>>>(owner, "cels")
    private fun scene(e: GlPaintEngine): RecordedScene<Int> = get(checkNotNull(root(e)), "coordinator")
    private fun renderer(e: GlPaintEngine): RecordedScene.Renderer<Int> = get(scene(e), "renderer")
    private fun pool(e: GlPaintEngine) = get<ArrayDeque<Int>>(e, "freeLayerTex")
    private fun fixture(regional: Boolean = false, empty: Boolean = false): Fixture {
        val ids = ArrayDeque(listOf("board", "paint", "shared"))
        var doc = DocOps.newDocument("doc", "Art", 512, 512) { ids.removeFirst() }
        if (regional) {
            var serial = 0
            doc = RegionDocumentOps.create(doc, "Loop", RectPx(0, 0, 256, 256)) { "r-${++serial}" }.doc
            val board = doc.boards.single { it.kind == BoardKind.ANIMATION }
            doc = RegionDocumentOps.addFrame(doc, board.id, NewFrame.BLANK) { "r-${++serial}" }.doc
        }
        val e = GlPaintEngine().also { it.initWith {}; it.addLayer("paint"); it.setBoardDocument(doc) }
        val owner = local(e).getValue("paint")
        if (!empty) tiles(owner)[3L] = 10
        return Fixture(e, owner, doc)
    }
    private fun failPrepare(f: Fixture) {
        val before = tiles(f.owner).toMap(); val cursor = f.engine.undo.snapshotCursor()
        assertFails { f.engine.preparePlainRecordingPromotion("paint") }
        assertNull(root(f.engine)); assertSame(f.owner, local(f.engine)["paint"])
        assertEquals(before, tiles(f.owner)); assertSame(cursor, f.engine.undo.snapshotCursor())
        assertTrue(pool(f.engine).isEmpty()); assertEquals(0L, f.engine.recordedLinePreparationCalls)
        assertEquals(0L, f.engine.recordedCompositionCalls)
    }
    private class Renderer : RecordedScene.Renderer<Int> {
        var next = 1000; val released = mutableListOf<Int>()
        override fun line(address: LineAddress, record: StrokeRecord, preset: BrushPreset) =
            RecordedScene.Unit(preset.blend, mapOf(3L to next++))
        override fun look(plane: Pair<String, String?>, key: Long, sources: List<RecordedScene.Source<Int>>) = next++
        override fun release(texture: Int) { released += texture }
    }
    private fun fakeReplay(e: GlPaintEngine): Renderer = Renderer().also { put(scene(e), "renderer", it) }
    private fun record(id: String = "line", seq: Long = 1) = StrokeRecord(id, brush.id, 19, 0f, 1f,
        listOf(PenSample(20f, 30f, 0.0)), seq = seq)
    private fun add(e: GlPaintEngine, id: String = "line"): UndoLog.Step<Int> {
        val cel = scene(e).planes.getValue(plane)
        return RecordedEdits.add(scene(e).planes,
            listOf(AddressedStroke(LineAddress(plane.first, plane.second, id), record(id, cel.nextSeq()))), setOf(brush.id)).step
    }

    @Test fun transfersExactBasesAndCursorAtOneRootWithoutRenderingOrClearingBorrowedMaps() {
        val f = fixture(); val cursor = f.engine.undo.snapshotCursor(); val original = tiles(f.owner)
        val ticket = f.engine.preparePlainRecordingPromotion("paint")
        assertNull(root(f.engine)); assertSame(original, tiles(f.owner)); assertTrue(ticket.canPublish())
        assertTrue(ticket.publish()); assertFalse(ticket.canPublish()); assertFails { ticket.publish() }
        assertSame(cursor, f.engine.undo.snapshotCursor()); assertTrue(f.engine.undo.isCursorBound)
        val native = owners(f.engine).getValue("paint")
        assertNotSame(f.owner, native); assertNotSame(original, tiles(native)); assertTrue(tiles(native).isEmpty())
        assertEquals(mapOf(3L to 10), original)
        assertSame(tiles(native), cels(native).getValue("shared"))
        assertEquals(10, scene(f.engine).planes.getValue(plane).slabs.getValue(3).single().value.texture)
        assertEquals(listOf(3L), f.engine.tileKeys("paint")); assertEquals(listOf(3L), f.engine.celTileKeys("paint", "shared"))
        assertEquals(1, f.engine.tileCount("paint")); assertTrue(scene(f.engine).savedLookKeys(plane).isEmpty())
        assertTrue(scene(f.engine).heldCacheTextures().isEmpty()); assertTrue(scene(f.engine).isStateBound)
        assertEquals(0L, f.engine.recordedLinePreparationCalls); assertEquals(0L, f.engine.recordedCompositionCalls)
        assertTrue(pool(f.engine).isEmpty())
    }

    @Test fun transfersEveryKnownInactiveAndEmptyFrameWithDistinctNewPlaceholders() {
        val f = fixture(regional = true)
        val sharedId = f.doc.layers.single().sharedCelId!!
        val frameIds = cels(f.owner).keys.filter { it != sharedId }
        cels(f.owner).getValue(frameIds.first())[0] = 20
        val old = cels(f.owner).mapValues { it.value }
        assertTrue(f.engine.preparePlainRecordingPromotion("paint").publish())
        val next = owners(f.engine).getValue("paint")
        assertEquals(setOf(plane) + frameIds.map { "paint" to it }, scene(f.engine).planes.keys)
        for (id in frameIds) { assertTrue(cels(next).getValue(id).isEmpty()); assertNotSame(old[id], cels(next)[id]) }
        assertEquals(20, scene(f.engine).planes.getValue("paint" to frameIds.first()).slabs.getValue(0).single().value.texture)
        assertEquals(listOf(0L), f.engine.celTileKeys("paint", frameIds.first()))
        assertTrue(f.engine.celTileKeys("paint", frameIds.last()).isEmpty())
        assertTrue(scene(f.engine).heldCacheTextures().isEmpty()); assertTrue(renderer(f.engine).contextCurrent())
    }

    @Test fun emptyOwnerStillTransfersCanonicalScopeWithNoSourceOrCacheAllocation() {
        val f = fixture(empty = true); val cursor = f.engine.undo.snapshotCursor()
        assertTrue(f.engine.preparePlainRecordingPromotion("paint").publish())
        assertEquals(setOf(plane), scene(f.engine).planes.keys); assertTrue(scene(f.engine).planes.getValue(plane).slabs.isEmpty())
        assertSame(cursor, f.engine.undo.snapshotCursor()); assertTrue(f.engine.tileKeys("paint").isEmpty())
        assertEquals(0L, f.engine.recordedCompositionCalls)
    }

    @Test fun cancelledTicketDropsOnlyMetadataAndNeverReleasesSourcesOrHistory() {
        val f = fixture(); val cursor = f.engine.undo.snapshotCursor()
        val ticket = f.engine.preparePlainRecordingPromotion("paint"); ticket.close(); ticket.close()
        assertFalse(ticket.canPublish()); assertFails { ticket.publish() }; assertNull(root(f.engine))
        assertEquals(10, tiles(f.owner)[3]); assertSame(cursor, f.engine.undo.snapshotCursor()); assertTrue(pool(f.engine).isEmpty())
        assertTrue(f.engine.preparePlainRecordingPromotion("paint").publish())
    }

    @Test fun everyStaleLocalOwnerAndEndpointRefusesPublicationWithoutRelease() {
        val mutations: List<(Fixture) -> Unit> = listOf(
            { tiles(it.owner)[3] = 11 },
            { cels(it.owner)["shared"] = hashMapOf(3L to 10) },
            { put(it.owner, "opacity", .5f) },
            { put(it.owner, "sharedCel", "foreign") },
            { put(it.engine, "physicalContextEpoch", get<Long>(it.engine, "physicalContextEpoch") + 1L) },
            { put(it.engine, "ready", false) },
            { local(it.engine)["paint"] = local(fixture().engine).getValue("paint") },
            { it.engine.undo.push(UndoLog.Step(listOf(UndoLog.TileChange("paint", 3, 9, 10)))) },
        )
        for ((index, mutate) in mutations.withIndex()) {
            val f = fixture(); val ticket = f.engine.preparePlainRecordingPromotion("paint"); mutate(f)
            assertFalse(ticket.canPublish(), "stale mutation $index"); assertFalse(ticket.publish()); assertNull(root(f.engine)); assertTrue(pool(f.engine).isEmpty())
        }
    }

    @Test fun finalChecksRefreshPoolsGlobalsAndAllSiblingMetadata() {
        for (name in listOf("freeLayerTex", "freeStrokeTex", "freeSmudgeTex", "freeFloatTex")) {
            val f = fixture(); val ticket = f.engine.preparePlainRecordingPromotion("paint")
            get<ArrayDeque<Int>>(f.engine, name).addLast(10)
            assertFalse(ticket.publish()); assertNull(root(f.engine)); assertEquals(10, tiles(f.owner)[3])
            assertEquals(listOf(10), get<ArrayDeque<Int>>(f.engine, name).toList())
        }
        fixture().also { f -> val t = f.engine.preparePlainRecordingPromotion("paint"); put(f.engine, "clearTex", 10)
            assertFalse(t.publish()); assertNull(root(f.engine)) }
        fixture().also { f -> f.engine.addLayer("sibling"); val sibling = local(f.engine).getValue("sibling")
            val t = f.engine.preparePlainRecordingPromotion("paint"); put(sibling, "name", "changed")
            assertFalse(t.publish()); assertNull(root(f.engine)) }
    }

    @Test fun documentBackingMutationCannotPassByRetainingTheSameDocumentObject() {
        val f = fixture(); val names = mutableListOf<String>()
        val doc = f.doc.copy(brushes = names); f.engine.setBoardDocument(doc)
        val ticket = f.engine.preparePlainRecordingPromotion("paint"); names.add("foreign")
        assertSame(doc, f.engine.boardDocument); assertFalse(ticket.publish()); assertNull(root(f.engine))
        assertEquals(10, tiles(f.owner)[3]); assertTrue(pool(f.engine).isEmpty())
    }

    @Test fun structuralPaperDocumentLineAndSlabHistoryRefusesBeforeAnyRendering() {
        val bad: List<UndoLog.Step<Int>> = listOf(
            UndoLog.Step(emptyList(), paperBefore = Paper(), paperAfter = Paper(color = "#FF0000")),
            UndoLog.Step(emptyList(), documentBefore = fixture().doc, documentAfter = fixture().doc),
            UndoLog.Step(emptyList(), lines = listOf(UndoLog.LineChange("paint", null, "line", null,
                cc.joycreator.joybrush.core.vector.CelComposer.Line(1, record())))),
            UndoLog.Step(emptyList(), slabs = listOf(UndoLog.SlabChange("paint", null, 3, 1, null, UndoLog.SlabValue(40, "normal")))),
        )
        for (step in bad) { val f = fixture(); f.engine.undo.push(step); failPrepare(f) }
        val redo = fixture(); redo.engine.undo.push(bad.first()); redo.engine.undo.undo(); failPrepare(redo)
    }

    @Test fun unsupportedOlderHistoryCannotBeHiddenBehindASupportedLatestPixelStep() {
        val f = fixture()
        f.engine.undo.push(UndoLog.Step(emptyList(), paperBefore = Paper(), paperAfter = Paper(color = "#FF0000")))
        f.engine.undo.push(UndoLog.Step(listOf(UndoLog.TileChange("paint", 3, 9, 10))))
        failPrepare(f)
    }

    @Test fun unownedExistingFrozenOrSiblingRecordedMetadataCannotBeDroppedDuringActivation() {
        fixture().also { f -> f.engine.setBoardDocument(f.doc.copy(brushes=listOf("unowned-frozen"))); failPrepare(f) }
        fixture().also { f ->
            f.engine.addLayer("other")
            val other=Layer("other","Other",LayerKind.PAINT,cels=listOf(Cel("other-cel",
                strokesFile="layers/other/other-cel/strokes.jbs")))
            f.engine.setBoardDocument(f.doc.copy(layers=f.doc.layers+other)); failPrepare(f)
        }
    }

    @Test fun staleMissingDuplicateAndFormatHistoryEndpointsRefuseBeforeTransfer() {
        val changes = listOf(
            listOf(UndoLog.TileChange("paint", 3, 9, 11)),
            listOf(UndoLog.TileChange("missing", 3, 9, 10)),
            listOf(UndoLog.TileChange("paint", 3, 9, 10, "shared")),
            listOf(UndoLog.TileChange("paint", 3, 9, 10), UndoLog.TileChange("paint", 3, 8, 11)),
            listOf(UndoLog.TileChange("paint#p0", 3, 9, 11)),
            listOf(UndoLog.TileChange("paint", 3, 10, 10)),
        )
        for (batch in changes) { val f = fixture(); f.engine.undo.push(UndoLog.Step(batch)); failPrepare(f) }
        val format = fixture(); get<MutableSet<Int>>(format.engine, "floatNames").add(9)
        format.engine.undo.push(UndoLog.Step(listOf(UndoLog.TileChange("paint", 3, 9, 10)))); failPrepare(format)
    }

    @Test fun legacyUndoAndRedoKeepTheOriginalStepAndTransferSourcesBetweenHistoryAndSeqZero() {
        val f = fixture(); val step = UndoLog.Step(listOf(UndoLog.TileChange("paint", 3, 9, 10)))
        f.engine.undo.push(step); val cursor = f.engine.undo.snapshotCursor()
        assertTrue(f.engine.preparePlainRecordingPromotion("paint").publish()); assertSame(cursor, f.engine.undo.snapshotCursor())
        fakeReplay(f.engine)
        assertTrue(f.engine.undoStep()); assertSame(step, f.engine.undo.peekRedo())
        assertEquals(9, scene(f.engine).planes.getValue(plane).slabs.getValue(3).single().value.texture)
        assertEquals(setOf(10), f.engine.undo.heldTextures()); assertTrue(tiles(owners(f.engine).getValue("paint")).isEmpty())
        assertTrue(f.engine.redoStep()); assertSame(step, f.engine.undo.peekUndo())
        assertEquals(10, scene(f.engine).planes.getValue(plane).slabs.getValue(3).single().value.texture)
        assertEquals(setOf(9), f.engine.undo.heldTextures()); assertEquals(mapOf(3L to 10), tiles(f.owner))
    }

    @Test fun actualRawAddUndoLegacyUndoAndRedoUseOneCompleteHistoryWithoutDuplicateNativeCommit() {
        val f = fixture(); val old = UndoLog.Step(listOf(UndoLog.TileChange("paint", 3, 9, 10)))
        f.engine.undo.push(old); assertTrue(f.engine.preparePlainRecordingPromotion("paint").publish()); fakeReplay(f.engine)
        val raw = add(f.engine)
        assertTrue(f.engine.publishJointPaintStep(raw, frozen = mapOf(brush.id to brush)))
        assertSame(raw, f.engine.undo.peekUndo()); assertEquals(2, f.engine.undo.undoDepth)
        assertTrue(raw.changes.isEmpty()); assertTrue(raw.slabs.isEmpty()); assertEquals(1, raw.lines.size)
        assertEquals(listOf(record()), scene(f.engine).planes.getValue(plane).records)
        assertTrue(f.engine.undoStep()); assertTrue(scene(f.engine).planes.getValue(plane).records.isEmpty())
        assertTrue(f.engine.undoStep()); assertEquals(9, scene(f.engine).planes.getValue(plane).slabs.getValue(3).single().value.texture)
        assertTrue(f.engine.redoStep()); assertTrue(f.engine.redoStep()); assertSame(raw, f.engine.undo.peekUndo())
        assertEquals(10, scene(f.engine).planes.getValue(plane).slabs.getValue(3).single().value.texture)
        assertEquals(listOf(record()), scene(f.engine).planes.getValue(plane).records)
    }

    @Test fun disjointNativeAndRecordedChangesRemainOneStepAndInvalidateDerivedProjection() {
        val f = fixture(); f.engine.addLayer("pixels"); tiles(local(f.engine).getValue("pixels"))[3] = 30
        get<MutableMap<Long, Int>>(f.owner, "projected")[3] = 50
        assertTrue(f.engine.preparePlainRecordingPromotion("paint").publish()); fakeReplay(f.engine)
        val raw = add(f.engine)
        val step = UndoLog.Step(listOf(UndoLog.TileChange("pixels", 3, 30, 31)), lines = raw.lines)
        assertTrue(f.engine.publishJointPaintStep(step, frozen = mapOf(brush.id to brush)))
        assertSame(step, f.engine.undo.peekUndo()); assertEquals(31, tiles(owners(f.engine).getValue("pixels"))[3])
        assertTrue(get<MutableMap<Long, Int>>(owners(f.engine).getValue("paint"), "projected").isEmpty())
        assertEquals(1, pool(f.engine).count { it == 50 })
        assertTrue(f.engine.undoStep()); assertEquals(30, tiles(owners(f.engine).getValue("pixels"))[3])
        assertTrue(scene(f.engine).planes.getValue(plane).records.isEmpty()); assertTrue(f.engine.redoStep())
    }

    @Test fun unconvertedPublicWritersRefuseBeforeChangingPromotedPhysicalSources() {
        val f = fixture(); assertTrue(f.engine.preparePlainRecordingPromotion("paint").publish())
        val before = root(f.engine); val cursor = f.engine.undo.snapshotCursor()
        assertFails { f.engine.writeTile("paint", 3, ByteArray(256 * 256 * 4)) }
        assertFails { f.engine.writeCelTile("paint", "shared", 3, ByteArray(256 * 256 * 4)) }
        assertFails { f.engine.addLayer("new") }; assertFails { f.engine.setBoardDocument(f.doc) }
        assertSame(before, root(f.engine)); assertSame(cursor, f.engine.undo.snapshotCursor())
        assertEquals(10, scene(f.engine).planes.getValue(plane).slabs.getValue(3).single().value.texture)
    }

    @Test fun orderedPlainSlabsUseActualDerivedLookWithoutTurningItIntoSourceOrSavedLineIndex() {
        val f = fixture(); assertTrue(f.engine.preparePlainRecordingPromotion("paint").publish()); fakeReplay(f.engine)
        val step = UndoLog.Step<Int>(emptyList(), slabs = listOf(
            UndoLog.SlabChange("paint", null, 3, 1, null, UndoLog.SlabValue(40, "multiply"))))
        assertTrue(f.engine.publishJointPaintStep(step)); val s = scene(f.engine)
        assertTrue(s.savedLookKeys(plane).isEmpty()); assertEquals(setOf(3L), s.lookKeys(plane))
        val cache = checkNotNull(s.look(plane, 3)); assertTrue(cache != 10 && cache != 40)
        val physical = s.planes.getValue(plane)
        assertEquals(listOf(10, 40), physical.slabs.getValue(3).map { it.value.texture })
        assertEquals(cache, cc.joycreator.joybrush.androidkit.gl.lines.PromotedBaseProjection.displayTile(physical, emptySet(), cache, 3))
        assertEquals(listOf(3L), f.engine.tileKeys("paint")); assertEquals(listOf(3L), f.engine.celTileKeys("paint", "shared"))
        assertTrue(f.engine.undoStep()); assertEquals(10, s.planes.getValue(plane).slabs.getValue(3).single().value.texture)
        assertTrue(f.engine.redoStep()); assertSame(step, f.engine.undo.peekUndo())
    }

    @Test fun coldUncomposedPlainSlabsCannotBindThenFailOnlyWhenRead() {
        val f = fixture(empty = true); val physical = RecordedCel<Int>("paint", null, slabs = mapOf(3L to listOf(
            RecordedCel.PixelSlab(0, UndoLog.SlabValue(10, "normal")),
            RecordedCel.PixelSlab(1, UndoLog.SlabValue(40, "multiply")))))
        val cold = RecordedScene.createSaved(mapOf(plane to physical), emptyMap(), f.engine.undo, Renderer(),
            mapOf(plane to emptySet()), mapOf(plane to emptyMap()))
        f.engine.registerRecordedScene(cold); val cursor = f.engine.undo.snapshotCursor()
        assertFails { f.engine.bindRecordedPaintPublication() }
        assertNull(root(f.engine)); assertFalse(cold.isStateBound); assertSame(cursor, f.engine.undo.snapshotCursor())
        assertFalse(f.engine.undo.isCursorBound); assertTrue(pool(f.engine).isEmpty())
    }

    @Test fun rendererRefusesPhysicalEpochAndPlaceholderReplacementEvenForEmptySources() {
        fixture(empty = true).also { f -> assertTrue(f.engine.preparePlainRecordingPromotion("paint").publish())
            assertTrue(renderer(f.engine).contextCurrent())
            put(f.engine, "physicalContextEpoch", get<Long>(f.engine, "physicalContextEpoch") + 1L)
            assertFalse(renderer(f.engine).contextCurrent()) }
        fixture().also { f -> assertTrue(f.engine.preparePlainRecordingPromotion("paint").publish())
            val native = owners(f.engine).getValue("paint"); cels(native)["shared"] = HashMap()
            assertFalse(renderer(f.engine).contextCurrent()) }
    }

    @Test fun contextLossDropsRootAndHistoryWithoutReturningDeadTransferredNamesToPools() {
        val f = fixture(); f.engine.undo.push(UndoLog.Step(listOf(UndoLog.TileChange("paint", 3, 9, 10))))
        assertTrue(f.engine.preparePlainRecordingPromotion("paint").publish())
        val oldScene = scene(f.engine); f.engine.initWith {}
        assertNull(root(f.engine)); assertTrue(f.engine.layerIds().isEmpty()); assertFalse(f.engine.undo.canUndo)
        assertFalse(oldScene.isStateBound); assertTrue(oldScene.planes.isEmpty()); assertTrue(pool(f.engine).isEmpty())
        assertEquals(mapOf(3L to 10), tiles(f.owner))
    }
}
