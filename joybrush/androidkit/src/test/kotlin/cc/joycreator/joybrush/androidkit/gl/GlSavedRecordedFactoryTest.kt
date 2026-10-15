package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.androidkit.gl.lines.RecordedScene
import cc.joycreator.joybrush.androidkit.gl.watercolour.WatercolourPass
import cc.joycreator.joybrush.androidkit.gl.watercolour.WatercolourTextures
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.doc.DocOps
import cc.joycreator.joybrush.core.doc.Paper
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.paint.UndoLog
import cc.joycreator.joybrush.core.stroke.LineAddress
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.RecordedCel
import kotlin.test.*

/** No GL driver: made-up handles test concrete factory acceptance/ownership/context contracts. */
class GlSavedRecordedFactoryTest {
    private val plane: Pair<String, String?> = "paint" to null
    private val pen = BrushPreset(id = "frozen", name = "Pen", size = Param(8f))
    private fun record() = StrokeRecord("line", pen.id, 81L, 0f, 1f,
        listOf(PenSample(10f, 10f, 0.0)), seq = 1L)
    private fun engine() = GlPaintEngine().also { it.initWith {}; it.addLayer("paint") }
    @Suppress("UNCHECKED_CAST")
    private fun <T> field(owner: Any, name: String): T = owner.javaClass.getDeclaredField(name)
        .also { it.isAccessible = true }.get(owner) as T
    private fun put(owner: Any, name: String, value: Any?) = owner.javaClass.getDeclaredField(name)
        .also { it.isAccessible = true }.set(owner, value)
    private fun layer(engine: GlPaintEngine): Any = field<MutableMap<String, Any>>(engine, "localLayers").getValue("paint")
    private fun pool(engine: GlPaintEngine) = field<ArrayDeque<Int>>(engine, "freeLayerTex").toList()
    private fun renderer(scene: RecordedScene<Int>) = field<RecordedScene.Renderer<Int>>(scene, "renderer")
    private fun open(engine: GlPaintEngine, looks: Map<Long, Int> = mapOf(0L to 900),
                     cel: RecordedCel<Int> = RecordedCel("paint", null, listOf(record()))) =
        engine.createSavedRecordedScene(mapOf(plane to cel), mapOf(pen.id to pen),
            mapOf(plane to looks.keys), mapOf(plane to looks))
    private fun rejectAlias(engine: GlPaintEngine, texture: Int) {
        val beforePool = pool(engine)
        val undoHead = engine.undo.peekUndo()
        val redoHead = engine.undo.peekRedo()
        val owned = engine.protectedRecordedTextureHandles()
        assertTrue(texture in owned, "Alias fixture did not plant ownership for $texture")
        assertFailsWith<IllegalArgumentException> { open(engine, mapOf(0L to texture, 1L to 99999)) }
        assertNull(field<RecordedScene<Int>?>(engine, "localRecordedScene"))
        assertEquals(beforePool, pool(engine), "Rejection recycled protected handles or fresh upload siblings")
        assertEquals(owned, engine.protectedRecordedTextureHandles())
        assertSame(undoHead, engine.undo.peekUndo()); assertSame(redoHead, engine.undo.peekRedo())
    }

    @Test fun jointNativeFrameReplacementPreservesConcreteRecordedSharedOwnership() {
        val e = engine()
        field<MutableMap<String, HashMap<Long, Int>>>(layer(e), "cels")["native-frame"] = hashMapOf(0L to 11)
        val noDab = record().copy(samples = emptyList())
        val scene = open(e, emptyMap(), RecordedCel("paint", null, listOf(noDab)))
        val actualRenderer = renderer(scene)
        assertTrue(actualRenderer.contextCurrent())
        e.bindRecordedPaintPublication()
        val step = UndoLog.Step(listOf(UndoLog.TileChange("paint", 0L, 11, 12, "native-frame")))
        assertTrue(e.publishJointPaintStep(step))
        assertEquals(12, e.mediaTile("paint", MEDIA_LOOK, 0L, "native-frame"))
        assertTrue(actualRenderer.contextCurrent())
        assertEquals(0L, e.recordedLinePreparationCalls)
        assertEquals(0L, e.recordedCompositionCalls)
        assertTrue(e.undoStep())
        assertEquals(11, e.mediaTile("paint", MEDIA_LOOK, 0L, "native-frame"))
        assertTrue(actualRenderer.contextCurrent())
        assertTrue(e.redoStep())
        assertEquals(12, e.mediaTile("paint", MEDIA_LOOK, 0L, "native-frame"))
        assertTrue(actualRenderer.contextCurrent())
    }

    @Test fun jointNativeSharedReplacementPreservesConcreteRecordedFrameOwnership() {
        val e = engine()
        val owner = layer(e)
        field<MutableMap<Long, Int>>(owner, "tiles")[0L] = 11
        field<MutableMap<String, HashMap<Long, Int>>>(owner, "cels")["recorded-frame"] = hashMapOf()
        val frame: Pair<String, String?> = "paint" to "recorded-frame"
        val scene = e.createSavedRecordedScene(
            mapOf(frame to RecordedCel("paint", frame.second, listOf(record().copy(samples = emptyList())))),
            mapOf(pen.id to pen), mapOf(frame to emptySet()), mapOf(frame to emptyMap()))
        val actualRenderer = renderer(scene)
        e.bindRecordedPaintPublication()
        val step = UndoLog.Step(listOf(UndoLog.TileChange("paint", 0L, 11, 12)))
        assertTrue(e.publishJointPaintStep(step))
        assertEquals(12, e.mediaTile("paint", MEDIA_LOOK, 0L))
        assertTrue(actualRenderer.contextCurrent())
        assertTrue(e.undoStep())
        assertEquals(11, e.mediaTile("paint", MEDIA_LOOK, 0L))
        assertTrue(actualRenderer.contextCurrent())
        assertEquals(0L, e.recordedLinePreparationCalls)
        assertEquals(0L, e.recordedCompositionCalls)
    }

    @Test fun acceptsActualConcreteColdSceneWithoutInvokingGlReplayOrComposition() {
        val e = engine()
        val scene = open(e)
        assertFalse(scene.isMaterialized(plane))
        assertEquals(setOf(0L), scene.savedLookKeys(plane))
        assertEquals(setOf(0L), scene.lookKeys(plane))
        assertEquals(900, scene.look(plane, 0))
        assertEquals(setOf(900), scene.heldCacheTextures())
        assertEquals(listOf(record()), scene.planes.getValue(plane).records)
        assertTrue(renderer(scene).contextCurrent())
        assertFalse(e.undo.canUndo); assertFalse(e.undo.canRedo)
    }

    @Test fun sceneCloseRecyclesOnlyAcceptedUploadsExactlyOnceAndNeverBorrowedSlabs() {
        val e = engine()
        val source = RecordedCel.PixelSlab(0L, UndoLog.SlabValue(300, "normal"))
        val scene = open(e, cel = RecordedCel("paint", null, listOf(record()), mapOf(0L to listOf(source))))
        assertEquals(300, scene.planes.getValue(plane).slabs.getValue(0L).single().value.texture)
        e.releaseRecordedScene(); e.releaseRecordedScene()
        assertEquals(listOf(900), pool(e))
        assertTrue(scene.heldCacheTextures().isEmpty())
        assertFalse(300 in e.protectedRecordedTextureHandles()) // Still caller/physical-owner borrowed.
    }

    @Test fun invalidDeclaredIndexDoesNotAdoptRecycleOrChangeHistory() {
        val e = engine()
        val step = UndoLog.Step(listOf(UndoLog.TileChange("paint", 2L, 101, 102)))
        e.undo.push(step)
        assertFailsWith<IllegalArgumentException> {
            e.createSavedRecordedScene(mapOf(plane to RecordedCel<Int>("paint", null, listOf(record()))),
                mapOf(pen.id to pen), mapOf(plane to setOf(0L, 1L)), mapOf(plane to mapOf(0L to 900)))
        }
        assertSame(step, e.undo.peekUndo())
        assertTrue(pool(e).isEmpty())
        assertNull(field<RecordedScene<Int>?>(e, "localRecordedScene"))
    }

    @Test fun inputBorrowedSlabAliasAndZeroLookAreRefusedWithAllUploadsStillCallerOwned() {
        val e = engine()
        val source = RecordedCel("paint", null, listOf(record()), mapOf(0L to listOf(
            RecordedCel.PixelSlab(0L, UndoLog.SlabValue(300, "normal")))))
        assertFailsWith<IllegalArgumentException> { open(e, mapOf(0L to 300, 1L to 901), source) }
        assertFailsWith<IllegalArgumentException> { open(e, mapOf(0L to 0)) }
        val zeroSource = RecordedCel("paint", null, listOf(record()), mapOf(0L to listOf(
            RecordedCel.PixelSlab(0L, UndoLog.SlabValue(0, "normal")))))
        assertFailsWith<IllegalArgumentException> { open(e, cel = zeroSource) }
        assertTrue(pool(e).isEmpty()); assertNull(field<RecordedScene<Int>?>(e, "localRecordedScene"))
    }

    @Test fun protectsSharedFrameMaskProjectedAndBothPhysicalMediaFamilies() {
        val e = engine()
        val owner = layer(e)
        field<MutableMap<Long, Int>>(owner, "tiles")[0] = 11
        field<MutableMap<String, HashMap<Long, Int>>>(owner, "cels")["frame"] = hashMapOf(0L to 12)
        field<MutableMap<Long, Int>>(owner, "projected")[0] = 13
        put(owner, "visibleProjection", mapOf(1L to 14))
        field<MutableMap<String, HashMap<Long, Int>>>(owner, "floats")["paper"] = hashMapOf(0L to 15)
        field<MutableMap<String, HashMap<String, HashMap<Long, Int>>>>(owner, "celFloats")["frame"] =
            hashMapOf("w0" to hashMapOf(0L to 16))
        val mask = owner.javaClass.getDeclaredConstructor(String::class.java, java.lang.Boolean.TYPE,
            HashMap::class.java, Any::class.java)
            .also { it.isAccessible = true }.newInstance("paint#mask", true, HashMap<Long, Int>(), Any())
        field<MutableMap<Long, Int>>(mask, "tiles")[0] = 17
        put(owner, "mask", mask)
        for (texture in 11..17) rejectAlias(e, texture)
    }

    @Test fun protectsStrokeBuffersEveryPoolAndDetachedFloatRegistry() {
        val e = engine()
        field<MutableMap<Long, Int>>(e, "strokeTiles")[0] = 21
        field<ArrayDeque<Int>>(e, "freeLayerTex").addLast(22)
        field<ArrayDeque<Int>>(e, "freeStrokeTex").addLast(23)
        field<ArrayDeque<Int>>(e, "freeSmudgeTex").addLast(24)
        field<ArrayDeque<Int>>(e, "freeFloatTex").addLast(25)
        field<MutableSet<Int>>(e, "floatNames").add(26)
        for (texture in 21..26) rejectAlias(e, texture)
    }

    @Test fun protectsHistoryOnlyUndoBeforeAndRedoAfterHandles() {
        val e = engine()
        e.undo.push(UndoLog.Step(listOf(UndoLog.TileChange("paint", 0L, 31, 32))))
        rejectAlias(e, 31)
        assertNotNull(e.undo.undo()) // Move bookkeeping only; now history exclusively holds after.
        rejectAlias(e, 32)
    }

    @Test fun protectsGlobalClearWhiteThumbnailPaperImagesAndBothCompositors() {
        val e = engine()
        put(e, "clearTex", 41); put(e, "whiteTex", 42); put(e, "thumbTex", 43)
        put(e, "lookTexture", 44); put(e, "surfaceTexture", 45); put(e, "fluidTexture", 46)
        e.compositor.target = 47; e.compositor.backdrop = 48
        val board = field<LayerCompositor>(e, "boardCompositor")
        board.target = 49; board.backdrop = 50
        val grains = field<GrainTextures>(e, "grains")
        put(grains, "placeholder", 51)
        field<MutableMap<String, Int>>(grains, "byName")["old-brush"] = 52
        put(field<GlBoardPreview>(e, "boardPreview"), "texture", 53)
        put(field<PaperBackground>(e, "paperBackground"), "texture", 54)
        for (texture in 41..54) rejectAlias(e, texture)
        assertFalse(0 in e.protectedRecordedTextureHandles())
    }

    @Test fun protectsWholeWatercolorBandFinishPoolsAndEveryPreviouslyLoadedImageSet() {
        val e = engine()
        val pass = WatercolourPass(field<ShaderLibrary>(e, "shaders"))
        put(e, "wcPass", pass)
        field<MutableMap<Long, Int>>(pass, "bands")[0] = 61
        field<MutableMap<Long, Int>>(pass, "plain")[0] = 62
        field<MutableMap<Long, Int>>(pass, "pooled")[0] = 63
        field<ArrayDeque<Int>>(pass, "free").addLast(64)
        put(pass, "empty", 65)
        val images = field<WatercolourTextures>(pass, "textures")
        val sets = field<MutableMap<String, WatercolourTextures.Set>>(images, "sets")
        sets["current"] = WatercolourTextures.Set(66, 67, 68, 4)
        sets["previous"] = WatercolourTextures.Set(69, 70, 71, 4)
        for (texture in 61..71) rejectAlias(e, texture)
        assertFalse(4 in e.protectedRecordedTextureHandles(), "Image layer count is not a texture name")
    }

    @Test fun oldRegisteredSceneIsNeverClosedOrReplacedOnFactoryRefusal() {
        val e = engine()
        val old = open(e)
        assertFailsWith<IllegalStateException> { open(e, mapOf(0L to 901)) }
        assertSame(old, field<RecordedScene<Int>?>(e, "localRecordedScene"))
        assertEquals(900, old.look(plane, 0)); assertTrue(pool(e).isEmpty())
        e.releaseRecordedScene()
        assertEquals(listOf(900), pool(e))
    }

    @Test fun activePaintPendingWcAndMediaAreRefusedWithoutSettlingPublishingOrRecycling() {
        val e = engine()
        put(e, "strokeLayer", layer(e))
        assertFailsWith<IllegalStateException> { open(e) }
        assertTrue(e.strokeInProgress); assertFalse(e.undo.canUndo); assertTrue(pool(e).isEmpty())
        put(e, "strokeLayer", null); put(e, "wcFading", true)
        assertFailsWith<IllegalStateException> { open(e) }
        assertTrue(e.watercolourAnimating); assertFalse(e.undo.canUndo); assertTrue(pool(e).isEmpty())
        put(e, "wcFading", false)
        e.beginMediaStroke()
        assertFailsWith<IllegalStateException> { open(e) }
        assertTrue(e.mediaStrokeInProgress); assertFalse(e.undo.canUndo); assertTrue(pool(e).isEmpty())
    }

    @Test fun unreadyPlaybackAndPeriodicContextsRefuseBeforeAdoptingUploads() {
        val unready = GlPaintEngine().also { it.addLayer("paint") }
        assertFailsWith<IllegalStateException> { open(unready) }
        assertTrue(pool(unready).isEmpty())
        val playback = engine()
        put(playback, "framePreviews", mapOf("board" to "frame"))
        assertFailsWith<IllegalStateException> { open(playback) }; assertTrue(pool(playback).isEmpty())
        val periodic = engine()
        put(periodic, "tileBoardId", "board")
        assertFailsWith<IllegalStateException> { open(periodic) }; assertTrue(pool(periodic).isEmpty())
    }

    @Test fun documentSharedCelIdCannotAliasTheCanonicalNullPhysicalAddress() {
        val e = engine()
        val owner = layer(e)
        put(owner, "sharedCel", "shared")
        field<MutableMap<String, HashMap<Long, Int>>>(owner, "cels")["shared"] = field(owner, "tiles")
        val alias: Pair<String, String?> = "paint" to "shared"
        assertFailsWith<IllegalArgumentException> {
            e.createSavedRecordedScene(mapOf(alias to RecordedCel<Int>("paint", "shared", listOf(record()))),
                mapOf(pen.id to pen), mapOf(alias to setOf(0L)), mapOf(alias to mapOf(0L to 900)))
        }
        assertTrue(pool(e).isEmpty())
        assertEquals(900, open(e).look(plane, 0))
    }

    @Test fun changingCapturedPaperRefusesFirstColdEditAndKeepsAcceptedUploadAndHistory() {
        val e = engine()
        val scene = open(e)
        put(e, "localDocumentPaper", Paper(color = "#C0C0C0"))
        assertFalse(renderer(scene).contextCurrent())
        assertFailsWith<IllegalStateException> { scene.stageBake(setOf(LineAddress("paint", null, "line"))) }
        assertFalse(scene.isMaterialized(plane)); assertEquals(900, scene.look(plane, 0))
        assertFalse(e.undo.canUndo); assertTrue(pool(e).isEmpty())
    }

    @Test fun removingAndRecreatingSameLayerIdInvalidatesColdPhysicalOwnerBeforeReplay() {
        val e = engine()
        val scene = open(e)
        val oldOwner = layer(e)
        // No public removeLayer exists. Simulate a direct loader replacing the physical owner,
        // while ready=true, boardDocument=null, paper and legacy history remain identical.
        field<MutableMap<String, Any>>(e, "localLayers").remove("paint")
        e.addLayer("paint")
        assertNotSame(oldOwner, layer(e))
        assertFalse(renderer(scene).contextCurrent(), "Paper/document equality alone cannot protect physical ownership")
        assertFailsWith<IllegalStateException> { scene.stageBake(setOf(LineAddress("paint", null, "line"))) }
        assertFalse(scene.isMaterialized(plane)); assertEquals(900, scene.look(plane, 0))
        assertFalse(e.undo.canUndo); assertTrue(pool(e).isEmpty(), "A refused cold edit must not delete accepted saved looks")
    }

    @Test fun replacingExistingFrameStoreInvalidatesItsCanonicalPhysicalBinding() {
        val e = engine()
        val owner = layer(e)
        val frameStores = field<MutableMap<String, HashMap<Long, Int>>>(owner, "cels")
        frameStores["frame"] = hashMapOf()
        val frame: Pair<String, String?> = "paint" to "frame"
        val scene = e.createSavedRecordedScene(mapOf(frame to RecordedCel<Int>("paint", "frame", listOf(record()))),
            mapOf(pen.id to pen), mapOf(frame to setOf(0L)), mapOf(frame to mapOf(0L to 900)))
        frameStores["frame"] = hashMapOf()
        assertFalse(renderer(scene).contextCurrent(), "Canonical cel-store replacement must invalidate a captured renderer")
        assertFailsWith<IllegalStateException> { scene.stageBake(setOf(LineAddress("paint", "frame", "line"))) }
        assertFalse(scene.isMaterialized(frame)); assertEquals(900, scene.look(frame, 0)); assertTrue(pool(e).isEmpty())
    }

    @Test fun contextLossForgetsAcceptedUploadsRatherThanRecyclingDeadNames() {
        val e = engine()
        val scene = open(e)
        e.initWith {}
        assertTrue(scene.heldCacheTextures().isEmpty())
        assertNull(field<RecordedScene<Int>?>(e, "localRecordedScene"))
        assertTrue(pool(e).isEmpty(), "Dead uploaded names must not enter replacement-context free pools")
        e.addLayer("paint")
        assertEquals(900, open(e).look(plane, 0), "A replacement context may legitimately reuse the old numeric name")
    }
}
