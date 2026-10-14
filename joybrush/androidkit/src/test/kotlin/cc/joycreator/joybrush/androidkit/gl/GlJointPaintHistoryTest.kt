package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.androidkit.gl.lines.RecordedScene
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.doc.Paper
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.paint.UndoLog
import cc.joycreator.joybrush.core.stroke.AddressedStroke
import cc.joycreator.joybrush.core.stroke.LineAddress
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.CelComposer
import cc.joycreator.joybrush.core.vector.RecordedCel
import cc.joycreator.joybrush.core.vector.RecordedEdits
import kotlin.test.*

/** Exercises the engine's joint native/recorded history root with fake texture identities only. */
class GlJointPaintHistoryTest {
    private val recordedPlane = "paint" to null
    private val nativeKey = 7L
    private val brush = BrushPreset(id = "frozen", name = "Pen", size = Param(8f))

    private fun record(seq: Long = 1L, x: Float = 0f) = StrokeRecord(
        "line", brush.id, 19L, 0f, 1f, listOf(PenSample(x, 0f, 0.0)), seq = seq)

    private class Renderer : RecordedScene.Renderer<Int> {
        var next = 1_000
        val lineCalls = mutableListOf<LineAddress>()
        val lookCalls = mutableListOf<Pair<Pair<String, String?>, Long>>()
        val released = mutableListOf<Int>()
        var onLine: (() -> Unit)? = null
        var onRelease: (() -> Unit)? = null
        override fun line(address: LineAddress, record: StrokeRecord, preset: BrushPreset): RecordedScene.Unit<Int> {
            lineCalls += address
            val texture = next++
            onLine?.invoke()
            return RecordedScene.Unit(preset.blend, mapOf(record.samples.single().x.toLong() to texture))
        }
        override fun look(plane: Pair<String, String?>, key: Long,
                          sources: List<RecordedScene.Source<Int>>): Int {
            lookCalls += plane to key
            return next++
        }
        override fun release(texture: Int) { released += texture; onRelease?.invoke() }
    }

    private data class Fixture(val engine: GlPaintEngine, val scene: RecordedScene<Int>, val renderer: Renderer)

    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).also { it.isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    private fun nativeLayers(engine: GlPaintEngine): LinkedHashMap<String, Any> {
        val published = field(engine, "publishedPaint").get(engine)
        val owner = published ?: engine
        val name = if (published == null) "localLayers" else "nativeLayers"
        return field(owner, name).get(owner) as LinkedHashMap<String, Any>
    }

    @Suppress("UNCHECKED_CAST")
    private fun tiles(engine: GlPaintEngine, layer: String): HashMap<Long, Int> =
        field(nativeLayers(engine).getValue(layer), "tiles").get(nativeLayers(engine).getValue(layer)) as HashMap<Long, Int>

    private fun root(engine: GlPaintEngine): Any = checkNotNull(field(engine, "publishedPaint").get(engine))

    private fun fixture(beforeBind: (GlPaintEngine, Renderer) -> Unit = { _, _ -> }): Fixture {
        val engine = GlPaintEngine().also { it.initWith {} }
        engine.addLayer("paint")
        engine.addLayer("pixels")
        engine.addLayer("other")
        tiles(engine, "pixels")[nativeKey] = 2_000
        tiles(engine, "other")[nativeKey] = 2_010
        val renderer = Renderer()
        val scene = RecordedScene.create(
            mapOf(recordedPlane to RecordedCel<Int>("paint", null, listOf(record()))),
            mapOf(brush.id to brush), engine.undo, renderer)
        engine.registerRecordedScene(scene)
        beforeBind(engine, renderer)
        engine.bindRecordedPaintPublication()
        return Fixture(engine, scene, renderer)
    }

    private fun mixedStep(nativeBefore: Int = 2_000, nativeAfter: Int = 2_001): UndoLog.Step<Int> =
        UndoLog.Step(
            changes = listOf(UndoLog.TileChange("pixels", nativeKey, nativeBefore, nativeAfter)),
            lines = listOf(UndoLog.LineChange("paint", null, "line",
                CelComposer.Line(1L, record()), CelComposer.Line(2L, record(seq = 2L, x = 1f))))
        )

    private fun assertRefusedBeforeRenderer(f: Fixture, step: UndoLog.Step<Int>) {
        val beforeRoot = root(f.engine)
        val nativeBefore = tiles(f.engine, "pixels").toMap()
        val lines = f.renderer.lineCalls.size
        val looks = f.renderer.lookCalls.size
        val releases = f.renderer.released.size
        assertFails { f.engine.publishJointPaintStep(step) }
        assertSame(beforeRoot, root(f.engine))
        assertEquals(nativeBefore, tiles(f.engine, "pixels"))
        assertFalse(f.engine.undo.canUndo)
        assertFalse(f.engine.undo.canRedo)
        assertEquals(lines, f.renderer.lineCalls.size)
        assertEquals(releases, f.renderer.released.size)
        assertEquals(looks, f.renderer.lookCalls.size)
    }

    @Test fun mixedLineAndNativeTilesPublishUndoAndRedoAsOneRootStep() {
        val f = fixture()
        val step = mixedStep()
        val beforeRoot = root(f.engine)
        assertTrue(f.engine.publishJointPaintStep(step))
        assertNotSame(beforeRoot, root(f.engine))
        assertEquals(2_001, tiles(f.engine, "pixels").getValue(nativeKey))
        assertEquals(record(seq = 2L, x = 1f), f.scene.planes.getValue(recordedPlane).records.single())
        assertSame(step, f.engine.undo.peekUndo())

        assertTrue(f.engine.undoStep())
        assertEquals(2_000, tiles(f.engine, "pixels").getValue(nativeKey))
        assertEquals(record(), f.scene.planes.getValue(recordedPlane).records.single())
        assertSame(step, f.engine.undo.peekRedo())

        assertTrue(f.engine.redoStep())
        assertEquals(2_001, tiles(f.engine, "pixels").getValue(nativeKey))
        assertEquals(record(seq = 2L, x = 1f), f.scene.planes.getValue(recordedPlane).records.single())
        assertSame(step, f.engine.undo.peekUndo())
    }

    @Test fun renderCallbacksSeeTheOldCompleteRootUntilSinglePublication() {
        lateinit var f: Fixture
        var observed = 0
        f = fixture()
        val beforeRoot = root(f.engine)
        f.renderer.onLine = {
            observed++
            assertSame(beforeRoot, root(f.engine))
            assertEquals(2_000, tiles(f.engine, "pixels").getValue(nativeKey))
            assertSame(beforeRoot.javaClass.getDeclaredField("recorded").also { it.isAccessible = true }.get(beforeRoot),
                f.scene.snapshotState())
            assertSame(beforeRoot.javaClass.getDeclaredField("cursor").also { it.isAccessible = true }.get(beforeRoot),
                f.engine.undo.snapshotCursor())
        }
        assertTrue(f.engine.publishJointPaintStep(mixedStep()))
        assertTrue(observed > 0)
        assertEquals(2_001, tiles(f.engine, "pixels").getValue(nativeKey))
    }

    @Test fun nativeOnlyHistoryPublishesWithoutReplayingOrComposingLines() {
        val f = fixture()
        val lineCount = f.renderer.lineCalls.size
        val lookCount = f.renderer.lookCalls.size
        val step = UndoLog.Step(listOf(UndoLog.TileChange("pixels", nativeKey, 2_000, 2_001)))
        assertTrue(f.engine.publishJointPaintStep(step))
        assertEquals(2_001, tiles(f.engine, "pixels").getValue(nativeKey))
        assertEquals(lineCount, f.renderer.lineCalls.size)
        assertEquals(lookCount, f.renderer.lookCalls.size)
        assertSame(step, f.engine.undo.peekUndo())
    }

    @Test fun disjointMediaSharesTheMixedStepAndAcceptsAStagedFloatRegistryName() {
        val f = fixture { engine, _ ->
            @Suppress("UNCHECKED_CAST")
            val floats = field(nativeLayers(engine).getValue("pixels"), "floats")
                .get(nativeLayers(engine).getValue("pixels")) as HashMap<String, HashMap<Long, Int>>
            floats["p0"] = hashMapOf(nativeKey to 2_020)
            @Suppress("UNCHECKED_CAST")
            val names = field(engine, "floatNames").get(engine) as HashSet<Int>
            names.addAll(listOf(2_020, 2_021))
        }
        val basic = mixedStep()
        val step = UndoLog.Step(basic.changes + UndoLog.TileChange("pixels#p0", nativeKey, 2_020, 2_021),
            lines = basic.lines)
        assertTrue(f.engine.publishJointPaintStep(step))
        assertSame(step, f.engine.undo.peekUndo())
        assertEquals(2_001, f.engine.mediaTile("pixels", MEDIA_LOOK, nativeKey))
        assertEquals(2_021, f.engine.mediaTile("pixels", "p0", nativeKey))
        assertTrue(f.engine.undoStep())
        assertEquals(2_000, f.engine.mediaTile("pixels", MEDIA_LOOK, nativeKey))
        assertEquals(2_020, f.engine.mediaTile("pixels", "p0", nativeKey))
        assertEquals(record(), f.scene.planes.getValue(recordedPlane).records.single())
        assertSame(step, f.engine.undo.peekRedo())
        assertTrue(f.engine.redoStep())
        assertEquals(2_021, f.engine.mediaTile("pixels", "p0", nativeKey))
        f.engine.mediaBudgetBytes = 0 // Undo restores already-owned endpoints without a fresh media allocation.
        assertTrue(f.engine.undoStep())
        assertEquals(2_020, f.engine.mediaTile("pixels", "p0", nativeKey))
        assertTrue(f.engine.redoStep())
        assertEquals(2_021, f.engine.mediaTile("pixels", "p0", nativeKey))
        assertSame(step, f.engine.undo.peekUndo())
    }

    @Test fun jointDocumentResetRetiresEachOwnerOnceAndResumesTheOrdinaryPath() {
        val f = fixture()
        assertTrue(f.engine.publishJointPaintStep(mixedStep()))
        f.engine.resetDocument()
        assertFalse(f.engine.hasJointPaintPublication)
        assertFalse(f.scene.isStateBound)
        assertFalse(f.engine.undo.isCursorBound)
        assertTrue(f.scene.planes.isEmpty())
        assertTrue(f.engine.layerIds().isEmpty())
        assertFalse(f.engine.undo.canUndo); assertFalse(f.engine.undo.canRedo)
        assertEquals(setOf(1_000, 1_001, 1_002, 1_003), f.renderer.released.toSet())
        assertEquals(4, f.renderer.released.size)
        @Suppress("UNCHECKED_CAST")
        val pool = field(f.engine, "freeLayerTex").get(f.engine) as ArrayDeque<Int>
        assertEquals(setOf(2_000, 2_001, 2_010), pool.toSet())
        assertEquals(3, pool.size)
        f.engine.addLayer("after-reset")
        assertEquals(listOf("after-reset"), f.engine.layerIds())
        f.engine.resetDocument()
        assertEquals(4, f.renderer.released.size)
    }

    @Test fun liveResetRefusesOutstandingJointCacheLeaseBeforeChangingAnySlice() {
        val f = fixture()
        val before = root(f.engine)
        val pending = f.scene.stageJoint(mixedStep())
        assertFailsWith<IllegalStateException> { f.engine.resetDocument() }
        assertSame(before, root(f.engine))
        assertEquals(2_000, f.engine.mediaTile("pixels", MEDIA_LOOK, nativeKey))
        assertTrue(f.renderer.released.isEmpty())
        assertFalse(f.engine.undo.canUndo)
        pending.close()
        f.engine.resetDocument()
        assertFalse(f.engine.hasJointPaintPublication)
        assertEquals(4, f.renderer.released.size)
        assertEquals(4, f.renderer.released.toSet().size)
    }

    @Test fun actualContextLossAbandonsCurrentAndPendingOwnershipWithoutOldNameRelease() {
        val f = fixture()
        val pending = f.scene.stageJoint(mixedStep())
        f.engine.initWith {}
        assertTrue(f.engine.lostContent)
        assertFalse(f.engine.hasJointPaintPublication)
        assertFalse(f.scene.isStateBound)
        assertFalse(f.engine.undo.isCursorBound)
        assertTrue(f.scene.planes.isEmpty())
        assertEquals(0, f.engine.heldTextureNames())
        assertFalse(pending.canPublish())
        pending.close()
        assertTrue(f.renderer.released.isEmpty())
        f.engine.addLayer("new-context")
        assertEquals(listOf("new-context"), f.engine.layerIds())
    }

    @Test fun physicalContextLossDuringResetCacheReleaseStopsAllRemainingOldOwners() {
        val f = fixture()
        f.renderer.onRelease = {
            f.renderer.onRelease = null
            f.engine.initWith {}
        }
        f.engine.resetDocument()
        assertEquals(1, f.renderer.released.size)
        assertEquals(0, f.engine.heldTextureNames())
        assertFalse(f.engine.hasJointPaintPublication)
        assertFalse(f.engine.undo.canUndo)
        f.engine.addLayer("after-context-loss")
        assertEquals(listOf("after-context-loss"), f.engine.layerIds())
    }

    @Test fun staleNativeBeforeEndpointRefusesBeforeRecordedRenderingOrHistoryMove() {
        assertRefusedBeforeRenderer(fixture(), mixedStep(nativeBefore = 1_999))
    }

    @Test fun recordedPlaneCannotAlsoBeAddressedAsNativePixels() {
        val f = fixture()
        val step = UndoLog.Step(listOf(UndoLog.TileChange<Int>("paint", nativeKey, null, 2_101)))
        assertRefusedBeforeRenderer(f, step)
    }

    @Test fun recordedMediaPayloadAndNativeSlabFamiliesRefuseWithoutPartialPublication() {
        val media = fixture()
        assertRefusedBeforeRenderer(media, UndoLog.Step(listOf(
            UndoLog.TileChange<Int>("paint#p0", nativeKey, null, 2_021))))

        val slab = fixture()
        val unknownSlab = UndoLog.Step<Int>(emptyList(), slabs = listOf(
            UndoLog.SlabChange("pixels", null, nativeKey, 1L, null, UndoLog.SlabValue(3_000, "normal"))))
        assertRefusedBeforeRenderer(slab, unknownSlab)
    }

    @Test fun metadataMaskAndSharedCelAliasesRefuseBeforeAnyRendererWork() {
        val metadata = fixture()
        assertRefusedBeforeRenderer(metadata, UndoLog.Step(emptyList(), paperBefore = Paper()))

        val mask = fixture()
        assertRefusedBeforeRenderer(mask, UndoLog.Step(listOf(
            UndoLog.TileChange("pixels#mask", nativeKey, 2_000, 2_001))))

        val shared = fixture { engine, _ ->
            val owner = nativeLayers(engine).getValue("pixels")
            @Suppress("UNCHECKED_CAST")
            val stores = field(owner, "cels").get(owner) as LinkedHashMap<String, HashMap<Long, Int>>
            val base = field(owner, "tiles").get(owner) as HashMap<Long, Int>
            stores["frame"] = base
            field(owner, "sharedCel").set(owner, "frame")
        }
        assertRefusedBeforeRenderer(shared, UndoLog.Step(listOf(
            UndoLog.TileChange("pixels", nativeKey, 2_000, 2_001, celId = "frame"))))
    }
    @Test fun newNativeEndpointsCannotAliasSourceCacheOrFreePoolHandles() {
        for (alias in listOf(1_000, 1_001, 2_010, 2_090)) {
            val f = fixture { engine, _ ->
                if (alias == 2_090) {
                    @Suppress("UNCHECKED_CAST")
                    (field(engine, "freeLayerTex").get(engine) as ArrayDeque<Int>).addLast(alias)
                }
            }
            assertRefusedBeforeRenderer(f, UndoLog.Step(listOf(
                UndoLog.TileChange("pixels", nativeKey, 2_000, alias))))
        }
    }

    @Test fun bindRefusesOutstandingLocalSceneLeaseWithoutBindingEitherFacade() {
        val engine = GlPaintEngine().also { it.initWith {}; it.addLayer("paint"); it.addLayer("pixels") }
        tiles(engine, "pixels")[nativeKey] = 2_000
        val renderer = Renderer()
        val scene = RecordedScene.create(mapOf(recordedPlane to RecordedCel<Int>("paint", null, listOf(record()))),
            mapOf(brush.id to brush), engine.undo, renderer).also(engine::registerRecordedScene)
        val edit = RecordedEdits.replace(scene.planes,
            listOf(AddressedStroke(LineAddress("paint", null, "line"), record(x = 1f))), scene.brushes.keys)
        val pending = scene.stage(edit)
        assertFailsWith<IllegalStateException> { engine.bindRecordedPaintPublication() }
        assertFalse(engine.hasJointPaintPublication)
        assertFalse(scene.isStateBound)
        assertFalse(engine.undo.isCursorBound)
        pending.close()
        assertFalse(engine.hasJointPaintPublication)
    }

    @Test fun legacyWritersRefuseBeforeChangingMapsOrAllocatingWhenBound() {
        val f = fixture()
        val beforeRoot = root(f.engine)
        val beforePixels = tiles(f.engine, "pixels").toMap()
        val lines = f.renderer.lineCalls.size
        val looks = f.renderer.lookCalls.size
        val attempts: List<() -> Unit> = listOf(
            { f.engine.clearLayer("pixels") },
            { f.engine.setDocumentPaper(Paper()) },
            { f.engine.setLayerName("pixels", "renamed") },
            { f.engine.writeTile("pixels", 9L, ByteArray(0)) },
            { f.engine.writableMediaTiles("pixels", listOf(nativeKey), listOf("p0")) },
            { f.engine.addLayer("new") },
        )
        attempts.forEach { assertFailsWith<IllegalStateException>(block = it) }
        assertSame(beforeRoot, root(f.engine))
        assertEquals(beforePixels, tiles(f.engine, "pixels"))
        assertFalse(f.engine.undo.canUndo)
        assertFalse(f.engine.undo.canRedo)
        assertEquals(lines, f.renderer.lineCalls.size)
        assertEquals(looks, f.renderer.lookCalls.size)
    }

    @Test fun reentrantWinningNativePublicationMakesOuterMixedProposalStale() {
        val f = fixture()
        var injected = false
        f.renderer.onLine = {
            if (!injected) {
                injected = true
                val winner = UndoLog.Step(listOf(UndoLog.TileChange("other", nativeKey, 2_010, 2_011)))
                assertTrue(f.engine.publishJointPaintStep(winner))
            }
        }
        val outer = mixedStep()
        assertFails { f.engine.publishJointPaintStep(outer) }
        assertTrue(injected)
        assertEquals(2_011, tiles(f.engine, "other").getValue(nativeKey))
        assertEquals(2_000, tiles(f.engine, "pixels").getValue(nativeKey))
        assertEquals(record(), f.scene.planes.getValue(recordedPlane).records.single())
        assertNotSame(outer, f.engine.undo.peekUndo())
        assertEquals(1, f.engine.undo.undoDepth)
        assertTrue(f.renderer.released.isNotEmpty(), "The stale outer proposal should reclaim its fresh same-epoch caches")
    }
}
