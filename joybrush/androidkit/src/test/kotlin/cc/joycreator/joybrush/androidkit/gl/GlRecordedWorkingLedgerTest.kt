package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.androidkit.gl.lines.RecordedScene
import cc.joycreator.joybrush.core.paint.Tiles
import cc.joycreator.joybrush.core.paint.UndoLog
import cc.joycreator.joybrush.core.stroke.LineAddress
import cc.joycreator.joybrush.core.vector.RecordedCel
import kotlin.test.*

/** Real engine lifecycle/UndoLog hooks, seeded metadata and fake names; no GL allocation claim. */
class GlRecordedWorkingLedgerTest {
    private val tileBytes = Tiles.SIZE.toLong() * Tiles.SIZE * 4
    private val plane: Pair<String, String?> = "paint" to null
    private fun engine() = GlPaintEngine().also { it.initWith {} }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(owner: Any, name: String): T = owner.javaClass.getDeclaredField(name)
        .also { it.isAccessible = true }.get(owner) as T
    private fun epoch(engine: GlPaintEngine): Long = field(engine, "physicalContextEpoch")
    private fun ledger(engine: GlPaintEngine): RecordedWorkingTileLedger = field(engine, "recordedWorkingTiles")
    private fun pool(engine: GlPaintEngine): List<Int> = field<ArrayDeque<Int>>(engine, "freeLayerTex").toList()
    private fun track(engine: GlPaintEngine, vararg names: Int) {
        ledger(engine).register(epoch(engine), names.toList(), tileBytes)
    }
    private fun snapshot(engine: GlPaintEngine) = ledger(engine).snapshot(epoch(engine))
    private fun renderer(engine: GlPaintEngine): RecordedScene.Renderer<Int> {
        val scene = engine.createSavedRecordedScene(emptyMap(), emptyMap(), emptyMap(), emptyMap())
        return field(scene, "renderer")
    }

    @Test fun rendererReleaseRemovesTheStageTagWhileThePoolStillOwnsItsAllocation() {
        val engine = engine()
        val renderer = renderer(engine)
        track(engine, 71)
        assertEquals(mapOf(71 to tileBytes), snapshot(engine))
        assertTrue(71 !in engine.protectedRecordedTextureHandles(), "tracking is not actual ownership")
        renderer.release(71)
        assertTrue(snapshot(engine).isEmpty())
        assertEquals(listOf(71), pool(engine))
        assertTrue(71 in engine.protectedRecordedTextureHandles())
        assertEquals(tileBytes, ledger(engine).admit(epoch(engine), mapOf(71 to tileBytes), 0, tileBytes))
    }

    @Test fun unsignedHighBitTextureNamesUseTheSameCentralRetirementHook() {
        val engine = engine()
        val renderer = renderer(engine)
        val name = Int.MIN_VALUE + 17
        track(engine, name)
        renderer.release(name)
        assertTrue(snapshot(engine).isEmpty())
        assertEquals(listOf(name), pool(engine))
    }

    @Test fun jointPhysicalSlabResetRetiresThroughTheCentralHookWithoutRendererRelease() {
        val engine = engine()
        engine.addLayer("paint")
        val source = RecordedCel.fromPixels("paint", null, mapOf(0L to 72))
        engine.createSavedRecordedScene(mapOf(plane to source), emptyMap(),
            mapOf(plane to emptySet()), mapOf(plane to emptyMap()))
        track(engine, 72) // A prior bake output is now a persistent slab, rather than a cache.
        engine.bindRecordedPaintPublication()
        assertTrue(engine.hasJointPaintPublication)
        engine.resetDocument()
        assertFalse(engine.hasJointPaintPublication)
        assertTrue(snapshot(engine).isEmpty())
        assertEquals(listOf(72), pool(engine))
    }

    @Test fun nativeAndBakedSlabHistoryRetirementBothRemoveStageTags() {
        val native = engine()
        track(native, 81)
        native.undo.push(UndoLog.Step(listOf(UndoLog.TileChange("native", 0L, 81, 82))))
        assertEquals(setOf(81), native.undo.heldTextures())
        native.undo.clear()
        assertTrue(snapshot(native).isEmpty())
        assertEquals(listOf(81), pool(native))

        val baked = engine()
        track(baked, 83)
        val step = UndoLog.Step<Int>(emptyList(), slabs = listOf(UndoLog.SlabChange(
            "paint", null, 0L, 7L, null, UndoLog.SlabValue(83, "erase"))))
        baked.undo.push(step)
        assertSame(step, baked.undo.undo()) // The redo branch now owns the removed baked source.
        assertEquals(setOf(83), baked.undo.heldTextures())
        baked.undo.clear()
        assertTrue(snapshot(baked).isEmpty())
        assertEquals(listOf(83), pool(baked))
    }

    @Test fun physicalForgetClearsOldMetadataAndOldRendererCannotRetireAReusedName() {
        val engine = engine()
        val oldRenderer = renderer(engine)
        val oldEpoch = epoch(engine)
        track(engine, 91)
        engine.initWith {
            assertTrue(snapshot(engine).isEmpty(), "clear before replacement allocations")
            assertTrue(pool(engine).isEmpty())
        }
        assertTrue(epoch(engine) != oldEpoch)
        track(engine, 91)
        assertFalse(ledger(engine).forget(oldEpoch, 91))
        oldRenderer.release(91)
        assertEquals(mapOf(91 to tileBytes), snapshot(engine))
        assertTrue(pool(engine).isEmpty())
        renderer(engine).release(91)
        assertTrue(snapshot(engine).isEmpty())
        assertEquals(listOf(91), pool(engine))
    }

    @Test fun failedReplacementInitializationAndUnreadyReleaseClearAccountingWithoutGl() {
        val engine = engine()
        val oldRenderer = renderer(engine)
        track(engine, 92)
        assertFailsWith<IllegalStateException> {
            engine.initWith {
                assertTrue(snapshot(engine).isEmpty())
                error("replacement failed")
            }
        }
        assertFalse(engine.ready)
        oldRenderer.release(92)
        assertTrue(snapshot(engine).isEmpty())
        assertTrue(pool(engine).isEmpty())

        val unready = GlPaintEngine()
        track(unready, 93)
        unready.release() // Its early release path cannot reach any GLES entry point.
        assertTrue(snapshot(unready).isEmpty())
    }

    @Test fun emptyBakeCallbackKeepsPriorStagedMetadataAndOrdinarySourceGuardsRemainClosed() {
        val engine = engine()
        val renderer = renderer(engine)
        track(engine, 94)
        val before = snapshot(engine)
        val empty = renderer.copyForBake(LineAddress("paint", null, "line"), RecordedScene.Unit("normal", emptyMap()))
        assertTrue(empty.tiles.isEmpty())
        assertEquals(before, snapshot(engine))
        assertTrue(renderer !is RecordedScene.CapturedSourceRenderer<*>)
        assertTrue(pool(engine).isEmpty())
    }
}
