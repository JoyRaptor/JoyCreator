package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.androidkit.gl.lines.RecordedScene
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.paint.UndoLog
import cc.joycreator.joybrush.core.stroke.AddressedStroke
import cc.joycreator.joybrush.core.stroke.LineAddress
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.CelComposer
import cc.joycreator.joybrush.core.vector.RecordedCel
import cc.joycreator.joybrush.core.vector.RecordedEdits
import kotlin.test.*

/** Exercises GlPaintEngine's real public history entry points without requiring a GL driver. */
class GlRecordedHistoryTest {
    private val plane: Pair<String, String?> = "paint" to null
    private fun record(x: Float = 0f) = StrokeRecord("line", "frozen", 19, 0f, 1f,
        listOf(PenSample(x, 0f, 0.0)), seq = 1)
    private class Driver : RecordedScene.Renderer<Int> {
        var next = 100
        var failLook: Long? = null
        val released = mutableListOf<Int>()
        override fun line(address: LineAddress, record: StrokeRecord, preset: BrushPreset) =
            RecordedScene.Unit(preset.blend, mapOf(record.samples.single().x.toLong() to next++))
        override fun look(plane: Pair<String, String?>, key: Long, sources: List<RecordedScene.Source<Int>>): Int {
            if (key == failLook) error("injected real-engine history look failure")
            return next++
        }
        override fun release(texture: Int) { released += texture }
    }
    private fun scene(engine: GlPaintEngine, driver: Driver): RecordedScene<Int> = RecordedScene.create(
        mapOf(plane to RecordedCel<Int>("paint", null, listOf(record()))),
        mapOf("frozen" to BrushPreset(id = "frozen", name = "Pen", size = Param(8f))), engine.undo, driver).also(engine::registerRecordedScene)
    private fun move(scene: RecordedScene<Int>) = RecordedEdits.replace(scene.planes,
        listOf(AddressedStroke(LineAddress("paint", null, "line"), record(2f))), scene.brushes.keys)

    @Test fun publicEngineUndoAndRedoRestoreRecordsLooksAndOneSharedStep() {
        val engine = GlPaintEngine().also { it.initWith {} }
        val scene = scene(engine, Driver())
        scene.stage(move(scene)).use { assertTrue(it.commit()) }
        val step = assertNotNull(engine.undo.peekUndo())
        assertTrue(engine.undoStep())
        assertEquals(record(), scene.planes.getValue(plane).records.single())
        assertEquals(setOf(0L), scene.savedLookKeys(plane))
        assertSame(step, engine.undo.peekRedo())
        assertTrue(engine.redoStep())
        assertEquals(record(2f), scene.planes.getValue(plane).records.single())
        assertEquals(setOf(2L), scene.savedLookKeys(plane))
        assertSame(step, engine.undo.peekUndo())
    }

    @Test fun publicEngineUndoRenderFailurePreservesCurrentSceneAndHistoryForRetry() {
        val engine = GlPaintEngine().also { it.initWith {} }
        val driver = Driver()
        val scene = scene(engine, driver)
        scene.stage(move(scene)).use { assertTrue(it.commit()) }
        val current = scene.planes
        val step = assertNotNull(engine.undo.peekUndo())
        driver.failLook = 0
        assertFailsWith<IllegalStateException> { engine.undoStep() }
        assertSame(current, scene.planes)
        assertSame(step, engine.undo.peekUndo())
        assertNull(engine.undo.peekRedo())
        driver.failLook = null
        assertTrue(engine.undoStep())
        assertSame(step, engine.undo.peekRedo())
    }

    @Test fun recordedHistoryWithoutItsSceneRefusesBeforePoppingTheStep() {
        val engine = GlPaintEngine().also { it.initWith {} }
        val step = UndoLog.Step<Int>(emptyList(), lines = listOf(UndoLog.LineChange("paint", null,
            "line", null, CelComposer.Line(1, record()))))
        engine.undo.push(step)
        assertFailsWith<IllegalStateException> { engine.undoStep() }
        assertSame(step, engine.undo.peekUndo())
        assertNull(engine.undo.peekRedo())
    }

    @Test fun legacyPixelHistoryWithARecordedSceneRefusesBeforeDroppingEitherDataFamily() {
        val engine = GlPaintEngine().also { it.initWith {} }
        val scene = scene(engine, Driver())
        val current = scene.planes
        val step = UndoLog.Step(listOf(UndoLog.TileChange("paint", 0, 1, 2)))
        engine.undo.push(step)
        assertFailsWith<IllegalStateException> { engine.undoStep() }
        assertSame(current, scene.planes)
        assertSame(step, engine.undo.peekUndo())
        assertFalse(engine.undo.canRedo)
    }
}
