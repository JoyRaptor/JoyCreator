package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.androidkit.gl.lines.RecordedScene
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.paint.UndoLog
import cc.joycreator.joybrush.core.paper.ResolvedPaper
import cc.joycreator.joybrush.core.stroke.CaptureDomain
import cc.joycreator.joybrush.core.stroke.CapturedPhysicalSource
import cc.joycreator.joybrush.core.stroke.CapturedStrokeRules
import cc.joycreator.joybrush.core.stroke.LineAddress
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.RecordedCel
import kotlin.test.*

/** Real engine publication/public undo-redo with fake source payloads; no GL or GPU-budget proof. */
class GlRecordedJointBakeEngineTest {
    private val plane: Pair<String, String?> = "paint" to null
    private val address = LineAddress("paint", null, "line")
    private val nativeKey = 7L
    private val brush = BrushPreset(id = "frozen", name = "Frozen", size = Param(8f), opacity = Param(.5f))
    private fun record(id: String = "line", seq: Long = 7L) = StrokeRecord(id, brush.id, 19L, 0f, 1f,
        listOf(PenSample(0f, 0f, 0.0)), seq = seq, capturedPhysicalSource = CapturedPhysicalSource(CaptureDomain()))
    private fun paper(argb: Int) = ResolvedPaper(surface = null, look = null, baseArgb = argb,
        scale = 1f, show = 0f, bite = 0f, light = false)
    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).also { it.isAccessible = true }
    private fun root(engine: GlPaintEngine): Any = checkNotNull(field(engine, "publishedPaint").get(engine))
    private fun rootSlice(engine: GlPaintEngine, name: String): Any? = root(engine).let { field(it, name).get(it) }

    @Suppress("UNCHECKED_CAST")
    private fun nativeLayers(engine: GlPaintEngine): LinkedHashMap<String, Any> {
        val published = field(engine, "publishedPaint").get(engine)
        return if (published == null) field(engine, "localLayers").get(engine) as LinkedHashMap<String, Any>
        else field(published, "nativeLayers").get(published) as LinkedHashMap<String, Any>
    }
    @Suppress("UNCHECKED_CAST")
    private fun tiles(engine: GlPaintEngine, layer: String): HashMap<Long, Int> {
        val owner = nativeLayers(engine).getValue(layer)
        return field(owner, "tiles").get(owner) as HashMap<Long, Int>
    }

    private class Renderer(val context: () -> Boolean) : RecordedScene.CapturedSourceRenderer<Int> {
        var next = 1_000
        var live = true
        var lineCalls = 0
        var lookCalls = 0
        var contextCalls = 0
        var failCopyAt = 0
        var failLook = false
        var copyAlias: Int? = null
        var wrongKeys = false
        var wrongOp = false
        var onCopy: (() -> Unit)? = null
        var onLook: (() -> Unit)? = null
        var onContext: (() -> Unit)? = null
        val payload = linkedMapOf(30 to "seq0 source")
        val minted = mutableListOf<Int>()
        val released = mutableListOf<Int>()
        val copyAddresses = mutableListOf<LineAddress>()
        val copyInputs = mutableListOf<RecordedScene.Unit<Int>>()
        val copies = mutableListOf<RecordedScene.Unit<Int>>()
        private fun mint(value: String): Int {
            val texture = next++
            minted += texture; payload[texture] = value
            return texture
        }
        override fun contextCurrent(): Boolean {
            contextCalls++; onContext?.invoke()
            return live && context()
        }
        override fun admitCapturedSource(address: LineAddress, record: StrokeRecord, preset: BrushPreset) = true
        override fun line(address: LineAddress, record: StrokeRecord, preset: BrushPreset): RecordedScene.Unit<Int> {
            lineCalls++
            return RecordedScene.Unit(preset.blend, (0L..1L).associateWith { mint("raw:${record.id}:$it:opacity-once") })
        }
        override fun copyForBake(address: LineAddress, unit: RecordedScene.Unit<Int>): RecordedScene.Unit<Int> {
            copyAddresses += address; copyInputs += unit
            if (copyAddresses.size == failCopyAt) {
                // Renderer cleans partial work it did not return to the Scene.
                release(mint("unreturned partial copy"))
                error("copy failure")
            }
            val copied = RecordedScene.Unit(if (wrongOp) "erase" else unit.op, unit.tiles.map { (key, texture) ->
                (if (wrongKeys) key + 20L else key) to
                    (copyAlias?.takeIf { key == 0L } ?: mint(payload.getValue(texture)))
            }.toMap())
            copies += copied
            onCopy?.invoke()
            return copied
        }
        override fun look(plane: Pair<String, String?>, key: Long, sources: List<RecordedScene.Source<Int>>): Int {
            lookCalls++
            if (failLook) error("look failure")
            val texture = mint(sources.joinToString("|") { "${it.seq}:${it.op}:${payload.getValue(it.texture)}" })
            onLook?.invoke()
            return texture
        }
        override fun release(texture: Int) { released += texture }
        fun clearCalls() {
            minted.clear(); released.clear(); copyAddresses.clear(); copyInputs.clear(); copies.clear()
            lineCalls = 0; lookCalls = 0; contextCalls = 0
        }
    }
    private class Fixture(val engine: GlPaintEngine, val scene: RecordedScene<Int>, val renderer: Renderer)

    private fun fixture(cold: Boolean = false, records: List<StrokeRecord> = listOf(record())): Fixture {
        val engine = GlPaintEngine().also { it.initWith {} }
        engine.addLayer("paint"); engine.addLayer("pixels")
        tiles(engine, "pixels")[nativeKey] = 2_000
        // Preserve an existing native history prefix while the bake adds its one new Step.
        engine.undo.push(UndoLog.Step(listOf(UndoLog.TileChange("pixels", nativeKey, 1_999, 2_000))))
        val resolved = paper(0xffeeeeee.toInt())
        field(engine, "resolvedPaper").set(engine, resolved)
        val admittedPaper = engine.documentPaper
        val physicalEpoch = field(engine, "physicalContextEpoch").get(engine)
        val renderer = Renderer { engine.ready && field(engine, "physicalContextEpoch").get(engine) == physicalEpoch &&
            engine.documentPaper == admittedPaper && field(engine, "resolvedPaper").get(engine) === resolved }
        val physical = RecordedCel("paint", null, records,
            mapOf(0L to listOf(RecordedCel.PixelSlab(0L, UndoLog.SlabValue(30, "normal")))))
        val scene = if (cold) RecordedScene.createSaved(mapOf(plane to physical), mapOf(brush.id to brush),
            engine.undo, renderer, mapOf(plane to setOf(0L, 1L)), mapOf(plane to mapOf(0L to 60, 1L to 61)))
        else RecordedScene.create(mapOf(plane to physical), mapOf(brush.id to brush), engine.undo, renderer)
        engine.registerRecordedScene(scene)
        engine.bindRecordedPaintPublication()
        renderer.clearCalls()
        return Fixture(engine, scene, renderer)
    }

    private fun assertSlices(f: Fixture) {
        assertSame(rootSlice(f.engine, "recorded"), f.scene.snapshotState())
        assertSame(rootSlice(f.engine, "cursor"), f.engine.undo.snapshotCursor())
        assertEquals(2_000, f.engine.mediaTile("pixels", MEDIA_LOOK, nativeKey))
        assertTrue(tiles(f.engine, "paint").isEmpty()) // Cached looks were never installed as native paint.
        assertEquals(30, f.scene.planes.getValue(plane).slabs.getValue(0L).single { it.seq == 0L }.value.texture)
    }
    private fun assertAttemptReleasedOnce(f: Fixture) {
        assertEquals(f.renderer.minted.toSet(), f.renderer.released.toSet())
        assertEquals(f.renderer.minted.toSet().size, f.renderer.released.size)
    }

    @Test fun bakePublishesActualSourceAndCursorOnceThenPublicUndoRedoPreserveNativeOwner() {
        val f = fixture()
        val before = root(f.engine)
        val cursor = f.engine.undo.snapshotCursor()
        val source = f.scene.snapshotState().units.getValue(address).unit
        val oldCaches = f.scene.heldCacheTextures()
        val oldLooks = f.scene.lookKeys(plane).associateWith { f.renderer.payload.getValue(f.scene.look(plane, it)!!) }
        val nativeOwner = nativeLayers(f.engine).getValue("pixels")
        val nativeStore = tiles(f.engine, "pixels")
        var observations = 0
        val observe = {
            observations++
            assertSame(before, root(f.engine))
            assertSame(cursor, f.engine.undo.snapshotCursor())
            assertSame(source, f.scene.snapshotState().units.getValue(address).unit)
            assertEquals(1, f.scene.planes.getValue(plane).records.size)
            assertSlices(f)
        }
        f.renderer.onContext = observe; f.renderer.onCopy = observe; f.renderer.onLook = observe
        assertTrue(f.engine.bakeRecordedLines(setOf(address)))
        f.renderer.onContext = null; f.renderer.onCopy = null; f.renderer.onLook = null
        assertTrue(observations > 0)
        assertNotSame(before, root(f.engine))
        assertSlices(f)
        val step = assertNotNull(f.engine.undo.peekUndo())
        assertEquals(2, f.engine.undo.undoDepth)
        assertSame(cursor.undoSteps.single(), f.engine.undo.snapshotCursor().undoSteps.first())
        assertEquals(listOf(7L), step.lines.map { assertNotNull(it.before).seq })
        assertTrue(CapturedStrokeRules.sameRecord(record(), step.lines.single().before!!.record))
        assertTrue(step.lines.all { it.after == null })
        assertTrue(step.changes.isEmpty())
        assertEquals(0, f.renderer.lineCalls) // Bake consumed the actual already-materialized unit.
        assertEquals(listOf(7L, 7L), step.slabs.map { it.seq })
        assertSame(source, f.renderer.copyInputs.single())
        val copied = f.renderer.copies.single()
        val slabNames = step.slabs.map { it.after!!.texture }.toSet()
        assertEquals(copied.tiles.values.toSet(), slabNames)
        assertTrue(slabNames.intersect(oldCaches).isEmpty())
        for ((key, texture) in copied.tiles) {
            assertEquals(f.renderer.payload.getValue(source.tiles.getValue(key)), f.renderer.payload.getValue(texture))
            assertTrue(f.renderer.payload.getValue(texture).startsWith("raw:"))
            val slab = f.scene.planes.getValue(plane).slabs.getValue(key).single { it.seq == 7L }
            assertEquals(texture, slab.value.texture)
            assertEquals(source.op, slab.value.op)
        }
        assertTrue(f.scene.planes.getValue(plane).records.isEmpty())
        assertEquals(oldLooks, f.scene.lookKeys(plane).associateWith { f.renderer.payload.getValue(f.scene.look(plane, it)!!) })
        assertEquals(oldCaches, f.renderer.released.toSet())
        assertEquals(oldCaches.size, f.renderer.released.size)
        assertTrue(slabNames.none { it in f.renderer.released })
        val bakedRoot = root(f.engine)
        assertTrue(f.engine.undoStep())
        assertNotSame(bakedRoot, root(f.engine))
        assertSlices(f)
        assertTrue(CapturedStrokeRules.sameRecord(record(), f.scene.planes.getValue(plane).records.single()))
        assertSame(step, f.engine.undo.peekRedo())
        assertTrue(slabNames.all { it in f.engine.undo.heldTextures() })
        assertTrue(slabNames.none { it in f.renderer.released })
        assertSame(nativeOwner, nativeLayers(f.engine).getValue("pixels"))
        assertSame(nativeStore, tiles(f.engine, "pixels"))
        val undoneRoot = root(f.engine)
        assertTrue(f.engine.redoStep())
        assertNotSame(undoneRoot, root(f.engine))
        assertSlices(f)
        assertSame(step, f.engine.undo.peekUndo())
        assertTrue(f.scene.planes.getValue(plane).records.isEmpty())
        assertTrue(slabNames.none { it in f.renderer.released })
        assertSame(nativeOwner, nativeLayers(f.engine).getValue("pixels"))
        assertSame(nativeStore, tiles(f.engine, "pixels"))
        assertTrue(oldCaches.all { old -> f.renderer.released.count { it == old } == 1 })
    }

    @Test fun invalidOrFailedCopiesKeepActualRootCursorAndBorrowedSources() {
        for (failure in listOf("native-alias", "keys", "operator", "late-copy", "look")) {
            val f = fixture(records = listOf(record(), record("later", 12L)))
            val before = root(f.engine)
            val cursor = f.engine.undo.snapshotCursor()
            val caches = f.scene.heldCacheTextures()
            val nativeOwner = nativeLayers(f.engine).getValue("pixels")
            when (failure) {
                "native-alias" -> f.renderer.copyAlias = 2_000
                "keys" -> f.renderer.wrongKeys = true
                "operator" -> f.renderer.wrongOp = true
                "late-copy" -> f.renderer.failCopyAt = 2
                else -> f.renderer.failLook = true
            }
            assertFails { f.engine.bakeRecordedLines(linkedSetOf(address, LineAddress("paint", null, "later"))) }
            assertSame(before, root(f.engine))
            assertSame(cursor, f.engine.undo.snapshotCursor())
            assertEquals(caches, f.scene.heldCacheTextures())
            assertEquals(2, f.scene.planes.getValue(plane).records.size)
            assertSame(nativeOwner, nativeLayers(f.engine).getValue("pixels"))
            assertSlices(f)
            assertAttemptReleasedOnce(f)
            assertTrue((caches + setOf(30, 1_999, 2_000)).none { it in f.renderer.released })
            assertEquals(0, f.renderer.lineCalls)
        }
    }

    @Test fun coldBakeRefusesWithoutReplayCopyCompositionOrOwnershipChange() {
        val f = fixture(cold = true)
        val before = root(f.engine)
        val cursor = f.engine.undo.snapshotCursor()
        val caches = f.scene.heldCacheTextures()
        assertFailsWith<IllegalArgumentException> { f.engine.bakeRecordedLines(setOf(address)) }
        assertSame(before, root(f.engine))
        assertSame(cursor, f.engine.undo.snapshotCursor())
        assertEquals(caches, f.scene.heldCacheTextures())
        assertFalse(f.scene.isMaterialized(plane))
        assertEquals(0, f.renderer.contextCalls)
        assertEquals(0, f.renderer.lineCalls)
        assertEquals(0, f.renderer.lookCalls)
        assertTrue(f.renderer.copyAddresses.isEmpty())
        assertTrue(f.renderer.minted.isEmpty() && f.renderer.released.isEmpty())
        assertSlices(f)
    }

    @Test fun changedPaperResourcesOrRendererContextRefuseBeforeAnyCopy() {
        for (reason in listOf("paper", "renderer")) {
            val f = fixture()
            val before = root(f.engine)
            val cursor = f.engine.undo.snapshotCursor()
            val caches = f.scene.heldCacheTextures()
            if (reason == "paper") {
                // Adversarial context-token change, not permission for the guarded public paper API.
                field(f.engine, "resolvedPaper").set(f.engine, paper(0xff112233.toInt()))
            } else f.renderer.live = false
            assertFailsWith<IllegalStateException> { f.engine.bakeRecordedLines(setOf(address)) }
            assertSame(before, root(f.engine))
            assertSame(cursor, f.engine.undo.snapshotCursor())
            assertEquals(caches, f.scene.heldCacheTextures())
            assertTrue(f.renderer.contextCalls > 0)
            assertTrue(f.renderer.copyAddresses.isEmpty())
            assertTrue(f.renderer.minted.isEmpty() && f.renderer.released.isEmpty())
            assertSlices(f)
        }
    }

    @Test fun reentrantNativeWinnerKeepsItsActualRootWhileOuterBakeRollsBackOnlyFreshCopies() {
        val f = fixture()
        val before = root(f.engine)
        val caches = f.scene.heldCacheTextures()
        val winner = UndoLog.Step(listOf(UndoLog.TileChange("pixels", nativeKey, 2_000, 2_001)))
        f.renderer.onCopy = {
            f.renderer.onCopy = null
            assertTrue(f.engine.publishJointPaintStep(winner))
        }
        assertFailsWith<IllegalStateException> { f.engine.bakeRecordedLines(setOf(address)) }
        assertNotSame(before, root(f.engine))
        assertSame(winner, f.engine.undo.peekUndo())
        assertEquals(2_001, f.engine.mediaTile("pixels", MEDIA_LOOK, nativeKey))
        assertSame(rootSlice(f.engine, "recorded"), f.scene.snapshotState())
        assertSame(rootSlice(f.engine, "cursor"), f.engine.undo.snapshotCursor())
        assertEquals(caches, f.scene.heldCacheTextures())
        assertEquals(1, f.scene.planes.getValue(plane).records.size)
        assertAttemptReleasedOnce(f)
        assertTrue((caches + setOf(30, 1_999, 2_000, 2_001)).none { it in f.renderer.released })
    }
}
