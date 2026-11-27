package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.androidkit.gl.lines.NativeBaseTransferProof
import cc.joycreator.joybrush.androidkit.gl.lines.RecordedScene
import cc.joycreator.joybrush.androidkit.gl.watercolour.WatercolourPass
import cc.joycreator.joybrush.androidkit.gl.watercolour.WatercolourTextures
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.doc.*
import cc.joycreator.joybrush.core.paint.UndoLog
import cc.joycreator.joybrush.core.stroke.LineAddress
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.RecordedCel
import kotlin.test.*

/** No GL driver: injected names test real store/ownership bookkeeping, NOT GPU texture format. */
class GlPlainPromotionInventoryTest {
    private val shared: Pair<String, String?> = "paint" to null
    private data class Fixture(val engine: GlPaintEngine, val owner: Any, val doc: JbDocument)
    @Suppress("UNCHECKED_CAST")
    private fun <T> field(owner: Any, name: String): T = owner.javaClass.getDeclaredField(name)
        .also { it.isAccessible = true }.get(owner) as T
    private fun put(owner: Any, name: String, value: Any?) = owner.javaClass.getDeclaredField(name)
        .also { it.isAccessible = true }.set(owner, value)
    private fun layers(e: GlPaintEngine) = field<LinkedHashMap<String, Any>>(e, "localLayers")
    private fun tiles(owner: Any) = field<HashMap<Long, Int>>(owner, "tiles")
    private fun cels(owner: Any) = field<MutableMap<String, HashMap<Long, Int>>>(owner, "cels")
    private fun floats(owner: Any) = field<MutableMap<String, HashMap<Long, Int>>>(owner, "floats")
    private fun agreements(e: GlPaintEngine) = field<MutableMap<Triple<String, String?, Long>, Int>>(e, "localAgreedLook")
    private fun fixture(regional: Boolean = false): Fixture {
        val ids = ArrayDeque(listOf("board", "paint", "shared"))
        var doc = DocOps.newDocument("document", "Art", 512, 512) { ids.removeFirst() }
        if (regional) {
            var serial = 0
            doc = RegionDocumentOps.create(doc, "Loop", RectPx(0, 0, 256, 256)) { "r-${++serial}" }.doc
            val board = doc.boards.single { it.kind == BoardKind.ANIMATION }
            doc = RegionDocumentOps.addFrame(doc, board.id, NewFrame.BLANK) { "r-${++serial}" }.doc
        }
        val e = GlPaintEngine().also { it.initWith {}; it.addLayer("paint"); it.setBoardDocument(doc) }
        val owner = layers(e).getValue("paint")
        tiles(owner)[3L] = 10
        return Fixture(e, owner, doc)
    }
    private fun inspect(f: Fixture) = f.engine.inspectPlainPromotionInventory("paint")
    private fun refused(f: Fixture) {
        val beforeLayers = layers(f.engine).toMap()
        val stores = cels(f.owner).mapValues { it.value.toMap() }
        val beforeTiles = tiles(f.owner).toMap()
        val beforePools = listOf("freeLayerTex", "freeStrokeTex", "freeSmudgeTex", "freeFloatTex")
            .associateWith { field<ArrayDeque<Int>>(f.engine, it).toList() }
        val cursor = f.engine.undo.snapshotCursor()
        assertFails { inspect(f) }
        assertEquals(beforeLayers, layers(f.engine)); assertEquals(stores, cels(f.owner).mapValues { it.value.toMap() })
        assertEquals(beforeTiles, tiles(f.owner)); assertSame(cursor, f.engine.undo.snapshotCursor())
        beforePools.forEach { (name, values) -> assertEquals(values, field<ArrayDeque<Int>>(f.engine, name).toList()) }
        assertFalse(f.engine.hasJointPaintPublication)
        assertEquals(0L, f.engine.recordedLinePreparationCalls); assertEquals(0L, f.engine.recordedCompositionCalls)
    }
    private fun mask(owner: Any): Any = owner.javaClass.getDeclaredConstructor(String::class.java, java.lang.Boolean.TYPE,
        HashMap::class.java, Any::class.java).also { it.isAccessible = true }
        .newInstance("paint#mask", true, HashMap<Long, Int>(), Any())

    @Test fun sharedAliasCountsOnceAndReturnsExactBorrowedTuplesWithDetachedContents() {
        val f = fixture(); val cursor = f.engine.undo.snapshotCursor(); val s = inspect(f)
        assertEquals(setOf(shared), s.physical.keys)
        assertSame(f.owner, s.physical.getValue(shared).owner)
        assertSame(tiles(f.owner), s.physical.getValue(shared).store)
        assertSame(tiles(f.owner), s.physical.getValue(shared).sharedAlias)
        assertEquals(mapOf(3L to 10), s.contents.getValue(shared)); assertSame(cursor, s.cursor)
        assertSame(f.doc, s.context.document); assertEquals(setOf(10), s.rgba8Bases)
        assertEquals(NativeBaseTransferProof.Slot(shared, 3), s.occurrences.single { it.texture == 10 }.physical)
        tiles(f.owner)[3] = 11
        assertEquals(10, s.contents.getValue(shared).getValue(3))
        assertEquals(11, s.physical.getValue(shared).store.getValue(3))
        assertFalse(f.engine.hasJointPaintPublication); assertFalse(f.engine.undo.canUndo)
        assertEquals(0L, f.engine.recordedLinePreparationCalls); assertEquals(0L, f.engine.recordedCompositionCalls)
    }

    @Test fun includesInactiveAndEmptyFramesAsDistinctActualPhysicalMaps() {
        val f = fixture(regional = true); val metadata = f.doc.layers.single()
        val frames = metadata.cels.filter { it.id != metadata.sharedCelId }
        assertEquals(2, frames.size)
        cels(f.owner).getValue(frames.first().id)[0] = 20
        val s = inspect(f)
        assertEquals(setOf(shared) + frames.map { "paint" to it.id }, s.physical.keys)
        frames.forEach { assertSame(cels(f.owner).getValue(it.id), s.physical.getValue("paint" to it.id).store) }
        assertTrue(s.physical.getValue("paint" to frames.last().id).store.isEmpty())
        assertEquals(setOf(10, 20), s.rgba8Bases)
    }

    @Test fun missingDocumentOwnerStoreSharedAliasAndUnknownFrameRefuseWithoutCreation() {
        fixture().also { put(it.engine, "localBoardDocument", null); refused(it) }
        fixture().also { put(it.engine, "localBoardDocument", it.doc.copy(layers = emptyList())); refused(it) }
        fixture().also { cels(it.owner).remove("shared"); refused(it) }
        fixture().also { cels(it.owner)["shared"] = HashMap(tiles(it.owner)); refused(it) }
        fixture().also { put(it.owner, "sharedCel", "wrong"); refused(it) }
        fixture().also { cels(it.owner)["unknown"] = hashMapOf(); refused(it) }
        fixture().also { field<MutableMap<String, HashMap<String, HashMap<Long, Int>>>>(it.owner, "celFloats")["unknown"] = hashMapOf(); refused(it) }
        fixture(true).also { f -> cels(f.owner).remove(f.doc.layers.single().cels.last().id); refused(f) }
    }

    @Test fun duplicateEmptyFrameMapsAndDuplicateCurrentNamesRefuse() {
        fixture(true).also { f ->
            val frames = f.doc.layers.single().cels.filter { it.id != "shared" }
            cels(f.owner)[frames.last().id] = cels(f.owner).getValue(frames.first().id)
            refused(f)
        }
        fixture(true).also { f -> cels(f.owner).getValue(f.doc.layers.single().cels.last().id)[0] = 10; refused(f) }
        fixture().also { tiles(it.owner)[4] = 10; refused(it) }
    }

    @Test fun selectedMasksClipMediaAndAgreementMetadataRefuse() {
        fixture().also { put(it.owner, "mask", mask(it.owner)); refused(it) }
        fixture().also { put(it.owner, "clip", true); refused(it) }
        fixture().also { put(it.engine, "localBoardDocument", it.doc.copy(layers = it.doc.layers.map { l -> l.copy(mask = Cel("mask")) })); refused(it) }
        fixture().also { floats(it.owner)["p0"] = hashMapOf(0L to 70); refused(it) }
        fixture(true).also { f ->
            val payload = field<MutableMap<String, HashMap<String, HashMap<Long, Int>>>>(f.owner, "celFloats")
            payload[f.doc.layers.single().cels.last().id] = hashMapOf("g" to hashMapOf(0L to 71))
            refused(f)
        }
        fixture().also { agreements(it.engine)[Triple("paint", null, 3)] = 10; refused(it) }
    }

    @Test fun staleSavedFramePlanAndSelectedRecordedHeadersRefuse() {
        fixture(true).also { f -> put(f.owner, "plan", RegionPaintPlan("shared", emptyList())); refused(f) }
        fixture().also { f -> put(f.engine, "localBoardDocument", f.doc.copy(layers = f.doc.layers.map { l ->
            l.copy(cels = l.cels.map { it.copy(strokesFile = "layers/paint/shared/strokes.jbs") }) })); refused(f) }
    }

    @Test fun currentUndoAfterReferenceIsBorrowedButUndoBeforeAndRedoAfterAreOwners() {
        val f = fixture(); val step = UndoLog.Step(listOf(UndoLog.TileChange("paint", 3, 9, 10)))
        f.engine.undo.push(step)
        val s = inspect(f)
        assertSame(step, s.cursor.peekUndo())
        assertNull(s.occurrences.single { it.texture == 9 }.physical)
        assertEquals(1, s.occurrences.count { it.texture == 10 })
        assertNotNull(f.engine.undo.undo()); refused(f) // Now redo owns10, still injected as live10.
        val before = fixture(); before.engine.undo.push(UndoLog.Step(listOf(UndoLog.TileChange("paint", 3, 10, 11))))
        refused(before)
    }

    @Test fun historyMultiplicityIsPreservedInsteadOfUsingHeldTextureUnion() {
        val f = fixture()
        f.engine.undo.push(UndoLog.Step(listOf(UndoLog.TileChange("other", 1, 31, 32))))
        f.engine.undo.push(UndoLog.Step(listOf(UndoLog.TileChange("other", 2, 31, 33))))
        val s = inspect(f)
        assertEquals(2, s.occurrences.count { it.texture == 31 && it.physical == null })
        assertTrue(32 !in s.occurrences.map { it.texture }) // Unowned historical after reference.
    }

    @Test fun siblingMaskProjectionMediaAndEveryPoolAliasesRefuse() {
        fixture().also { f -> f.engine.addLayer("other"); tiles(layers(f.engine).getValue("other"))[0] = 10; refused(f) }
        fixture().also { f -> f.engine.addLayer("other"); val other = layers(f.engine).getValue("other")
            val m = mask(other); tiles(m)[0] = 10; put(other, "mask", m); refused(f) }
        fixture().also { field<MutableMap<Long, Int>>(it.owner, "projected")[0] = 10; refused(it) }
        fixture().also { f -> f.engine.addLayer("other"); floats(layers(f.engine).getValue("other"))["p0"] = hashMapOf(0L to 10); refused(f) }
        for (name in listOf("freeLayerTex", "freeStrokeTex", "freeSmudgeTex", "freeFloatTex"))
            fixture().also { field<ArrayDeque<Int>>(it.engine, name).addLast(10); refused(it) }
        fixture().also { field<MutableMap<Long, Int>>(it.engine, "strokeTiles")[0] = 10; refused(it) }
    }

    @Test fun nonzeroFloatAndGlobalTextureIdentitiesNeverBecomeRgba8Bases() {
        fixture().also { tiles(it.owner)[3] = 0; refused(it) }
        fixture().also { field<MutableSet<Int>>(it.engine, "floatNames").add(10); refused(it) }
        for (name in listOf("clearTex", "whiteTex", "thumbTex", "tuftPaperTex", "lookTexture", "surfaceTexture", "fluidTexture"))
            fixture().also { put(it.engine, name, 10); refused(it) }
        fixture().also { it.engine.compositor.target = 10; refused(it) }
        fixture().also { field<LayerCompositor>(it.engine, "boardCompositor").backdrop = 10; refused(it) }
    }

    @Test fun grainPreviewBackgroundAndEveryWatercolorOwnedFamilyProtectNames() {
        fixture().also { put(field<GrainTextures>(it.engine, "grains"), "placeholder", 10); refused(it) }
        fixture().also { put(field<GlBoardPreview>(it.engine, "boardPreview"), "texture", 10); refused(it) }
        fixture().also { put(field<PaperBackground>(it.engine, "paperBackground"), "texture", 10); refused(it) }
        for (name in listOf("bands", "plain", "pooled", "free", "empty", "images")) fixture().also { f ->
            val pass = WatercolourPass(field(f.engine, "shaders")); put(f.engine, "wcPass", pass)
            when (name) {
                "free" -> field<ArrayDeque<Int>>(pass, name).addLast(10)
                "empty" -> put(pass, name, 10)
                "images" -> {
                    val sets = field<MutableMap<String, WatercolourTextures.Set>>(field<WatercolourTextures>(pass, "textures"), "sets")
                    sets["old"] = WatercolourTextures.Set(10, 72, 73, 4)
                }
                else -> field<MutableMap<Long, Int>>(pass, name)[0] = 10
            }
            refused(f)
        }
    }

    @Test fun visibleDirectSlicesBorrowNativeSourcesWithoutBecomingCacheOwners() {
        val f = fixture(true)
        val plan = field<RegionPaintPlan>(f.owner, "plan")
        cels(f.owner).getValue(plan.frames.single().celId)[0] = 20
        put(f.owner, "visibleProjection", mapOf(3L to 10, 0L to 20))
        val before = field<Map<Long, Int>>(f.owner, "visibleProjection")
        val s = inspect(f)
        assertEquals(1, s.occurrences.count { it.texture == 10 }); assertEquals(1, s.occurrences.count { it.texture == 20 })
        assertSame(before, field<Map<Long, Int>>(f.owner, "visibleProjection"))
        assertTrue(10 in f.engine.protectedRecordedTextureHandles()) // Global cold protection is unchanged.
    }

    @Test fun negativeBitPatternTextureNamesRemainValidNonzeroNativeSourcesAndDisplayBorrowers() {
        val f = fixture(true)
        val sharedName = Int.MIN_VALUE + 1
        val frameName = Int.MIN_VALUE + 2
        val plan = field<RegionPaintPlan>(f.owner, "plan")
        tiles(f.owner)[3] = sharedName
        cels(f.owner).getValue(plan.frames.single().celId)[0] = frameName
        put(f.owner, "visibleProjection", mapOf(3L to sharedName, 0L to frameName))
        val summary = inspect(f)
        assertEquals(setOf(sharedName, frameName), summary.rgba8Bases)
        assertEquals(sharedName, summary.contents.getValue(shared).getValue(3))
        assertEquals(1, summary.occurrences.count { it.texture == sharedName })
        assertEquals(1, summary.occurrences.count { it.texture == frameName })
        assertTrue(sharedName in f.engine.protectedRecordedTextureHandles())
        assertTrue(frameName in f.engine.protectedRecordedTextureHandles())
        // These are injected bookkeeping bit patterns, not a driver format/allocation proof.
        assertFalse(f.engine.hasJointPaintPublication)
    }

    @Test fun ownedSlicedProjectionIsCountedOnceAndStaleWrongKeyOrMissingIndexRefuses() {
        val f = fixture(true)
        val current = f.doc.boards.single { it.kind == BoardKind.ANIMATION }
        val doc = f.doc.copy(boards = f.doc.boards.map { if (it.id == current.id) it.copy(rect = RectPx(0, 0, 128, 128)) else it })
        f.engine.setBoardDocument(doc)
        val plan = field<RegionPaintPlan>(f.owner, "plan")
        cels(f.owner).getValue(plan.frames.single().celId)[0] = 20
        field<MutableMap<Long, Int>>(f.owner, "projected")[0] = 30
        put(f.owner, "visibleProjection", mapOf(3L to 10, 0L to 30))
        val s = inspect(f)
        assertNull(s.occurrences.single { it.texture == 30 }.physical)
        assertEquals(1, s.occurrences.count { it.texture == 10 })
        put(f.owner, "visibleProjection", mapOf(3L to 10, 0L to 20)); refused(f)
        put(f.owner, "visibleProjection", mapOf(3L to 10)); refused(f)
        put(f.owner, "visibleProjection", mapOf(3L to 10, 1L to 30)); refused(f)
    }

    @Test fun agreementsBorrowOnlyCurrentSiblingLookWithActualPayload() {
        val f = fixture(); f.engine.addLayer("other"); val other = layers(f.engine).getValue("other")
        tiles(other)[0] = 40; floats(other)["g"] = hashMapOf(0L to 41)
        agreements(f.engine)[Triple("other", null, 0)] = 40
        val s = inspect(f)
        assertEquals(1, s.occurrences.count { it.texture == 40 })
        assertNull(s.occurrences.single { it.texture == 41 }.physical)
        agreements(f.engine)[Triple("other", null, 0)] = 10; refused(f)
        agreements(f.engine)[Triple("other", null, 0)] = 40; floats(other).clear(); refused(f)
    }

    @Test fun registeredSceneCachesAndBorrowedPhysicalSlabsAreNonbaseProtections() {
        val pen = BrushPreset(id = "pen", name = "Pen", size = Param(4f))
        val record = StrokeRecord("line", pen.id, 1, 0f, 1f, emptyList(), seq = 1)
        fun register(f: Fixture, look: Int, source: Int) {
            val renderer = object : RecordedScene.Renderer<Int> {
                override fun contextCurrent() = true
                override fun line(address: LineAddress, record: StrokeRecord, preset: BrushPreset): RecordedScene.Unit<Int> =
                    error("Inspection must not replay")
                override fun look(plane: Pair<String, String?>, key: Long, sources: List<RecordedScene.Source<Int>>): Int? =
                    error("Inspection must not compose")
                override fun release(texture: Int) = error("Inspection must not release")
            }
            // Direct pure factory inputs intentionally exercise potentially untrusted registration.
            // Real engine cold upload protection must still reject native look aliases (separate test).
            val plane: Pair<String, String?> = "other" to null
            val cel = RecordedCel("other", null, listOf(record), mapOf(0L to listOf(
                RecordedCel.PixelSlab(0L, UndoLog.SlabValue(source, "normal")))))
            val scene = RecordedScene.createSaved(mapOf(plane to cel), mapOf(pen.id to pen), f.engine.undo,
                renderer, mapOf(plane to setOf(0L)), mapOf(plane to mapOf(0L to look)))
            f.engine.registerRecordedScene(scene)
        }
        val good = fixture(); register(good, 90, 91)
        val summary = inspect(good)
        assertNull(summary.occurrences.single { it.texture == 90 }.physical)
        assertNull(summary.occurrences.single { it.texture == 91 }.physical)
        fixture().also { register(it, 10, 91); refused(it) }
        fixture().also { register(it, 90, 10); refused(it) }
    }

    @Test fun activeUnreadyPlaybackPeriodicAndDeadContextInspectorsRefuseWithoutSettling() {
        fixture().also { put(it.engine, "ready", false); refused(it) }
        fixture().also { put(it.engine, "strokeLayer", it.owner); refused(it) }
        fixture().also { put(it.engine, "wcFading", true); refused(it) }
        fixture().also { it.engine.beginMediaStroke(); refused(it); assertTrue(it.engine.mediaStrokeInProgress) }
        fixture().also { put(it.engine, "framePreviews", mapOf("board" to "frame")); refused(it) }
        fixture().also { put(it.engine, "tileBoardId", "board"); refused(it) }
        fixture().also { put(it.engine, "forgettingGraphicsContext", true); refused(it) }
    }

    @Test fun summaryKeepsExactContextIdentityAndFreshInspectionDetectsSourceReplacement() {
        val f = fixture(); val old = inspect(f)
        val nextTiles = hashMapOf<Long, Int>()
        val tuple = old.physical.getValue(shared)
        val next = mapOf(shared to NativeBaseTransferProof.Physical(Any(), tuple.token, tuple.plan, nextTiles, nextTiles))
        val proof = NativeBaseTransferProof.capture(old.context, old.cursor, old.physical, next,
            old.occurrences, old.rgba8Bases, old.cursor.heldTextures())
        proof.prepareRoot(Any())
        assertTrue(proof.contextCurrent(null, old.context, old.physical))
        tiles(f.owner)[3] = 11
        val current = inspect(f)
        assertFalse(proof.contextCurrent(null, current.context, current.physical))
        put(f.engine, "physicalContextEpoch", old.context.physicalEpoch + 1)
        assertFalse(proof.contextCurrent(null, inspect(f).context, current.physical))
    }

    @Test fun successfulInspectionDoesNotAuthorizeNativeAliasesAsNewColdLooks() {
        val f = fixture(); inspect(f)
        val pen = BrushPreset(id = "pen", name = "Pen", size = Param(4f))
        val record = StrokeRecord("line", pen.id, 1, 0f, 1f, emptyList(), seq = 1)
        assertFailsWith<IllegalArgumentException> {
            f.engine.createSavedRecordedScene(mapOf(shared to RecordedCel<Int>("paint", null, listOf(record))),
                mapOf(pen.id to pen), mapOf(shared to setOf(0L)), mapOf(shared to mapOf(0L to 10)))
        }
        assertNull(field<RecordedScene<Int>?>(f.engine, "recordedScene"))
        assertTrue(field<ArrayDeque<Int>>(f.engine, "freeLayerTex").isEmpty())
    }
}
