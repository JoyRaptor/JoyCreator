package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.androidkit.gl.lines.RecordedScene
import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.stroke.AddressedStroke
import cc.joycreator.joybrush.core.stroke.LineAddress
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.RecordedCel
import cc.joycreator.joybrush.core.vector.RecordedEdits
import kotlin.test.*

class GlRecordedContextTest {
    private val plane: Pair<String, String?> = "paint" to null
    private class Driver : RecordedScene.Renderer<Int> {
        var next = 100
        val released = mutableListOf<Int>()
        override fun line(address: LineAddress, record: StrokeRecord, preset: BrushPreset) =
            RecordedScene.Unit(preset.blend, if (record.samples.isEmpty()) emptyMap() else mapOf(0L to next++))
        override fun look(plane: Pair<String, String?>, key: Long, sources: List<RecordedScene.Source<Int>>) = next++
        override fun release(texture: Int) { released += texture }
    }
    private fun create(engine: GlPaintEngine, driver: Driver, noDab: Boolean = false): RecordedScene<Int> {
        val record = StrokeRecord("line", "frozen", 19, 0f, 1f,
            if (noDab) emptyList() else listOf(PenSample(0f, 0f, 0.0)), seq = 1)
        return RecordedScene.create(mapOf(plane to RecordedCel<Int>("paint", null, listOf(record))),
            mapOf("frozen" to BrushPreset(id = "frozen", name = "Pen", size = Param(8f))), engine.undo, driver).also(engine::registerRecordedScene)
    }

    @Test fun engineContextLossInvalidatesCurrentAndStagedSceneCachesWithoutDeletingStaleNames() {
        val engine = GlPaintEngine().also { it.initWith {} }
        val driver = Driver()
        val scene = create(engine, driver)
        assertEquals(2, engine.heldTextureNames())
        val record = scene.planes.getValue(plane).records.single()
        val staged = scene.stage(RecordedEdits.replace(scene.planes,
            listOf(AddressedStroke(LineAddress("paint", null, "line"), record.copy(widthScale = 2f))), scene.brushes.keys))
        engine.initWith {}
        assertTrue(engine.lostContent)
        assertEquals(0, engine.heldTextureNames())
        assertTrue(scene.planes.isEmpty())
        assertFalse(staged.commit())
        staged.close(); scene.close()
        assertTrue(driver.released.isEmpty())
        assertFalse(engine.undo.canUndo)
    }

    @Test fun rawRecordWithoutRenderedTilesStillCountsAsLostArtwork() {
        val engine = GlPaintEngine().also { it.initWith {} }
        val driver = Driver()
        val scene = create(engine, driver, noDab = true)
        assertEquals(0, engine.heldTextureNames())
        assertTrue(scene.hasContent)
        engine.initWith {}
        assertTrue(engine.lostContent)
        assertFalse(scene.hasContent)
        assertTrue(driver.released.isEmpty())
    }

    @Test fun documentResetReleasesDerivedCachesOnceAndUnregistersScene() {
        val engine = GlPaintEngine().also { it.initWith {} }
        val driver = Driver()
        val scene = create(engine, driver)
        engine.resetDocument()
        assertEquals(listOf(100, 101), driver.released)
        assertEquals(0, engine.heldTextureNames())
        scene.close()
        assertEquals(listOf(100, 101), driver.released)
        create(engine, driver, noDab = true)
        engine.resetDocument()
        assertEquals(listOf(100, 101), driver.released)
    }
}
