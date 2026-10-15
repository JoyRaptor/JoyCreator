package cc.joycreator.joybrush.androidkit.gl.lines

import cc.joycreator.joybrush.core.brush.BrushPreset
import cc.joycreator.joybrush.core.brush.Param
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.paint.UndoLog
import cc.joycreator.joybrush.core.stroke.CaptureDomain
import cc.joycreator.joybrush.core.stroke.CapturedPhysicalSource
import cc.joycreator.joybrush.core.stroke.LineAddress
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.RecordedCel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** These scene-admission guards must run before any renderer callback, with or without saved looks. */
class RecordedCapturedSourceGateTest {
    private val address = "paint" to null
    private val brush = BrushPreset(id = "frozen", name = "Pen", size = Param(8f))
    private fun record(captured: Boolean) = StrokeRecord(
        "line", brush.id, 19L, 0f, 1f, emptyList(), seq = 1L,
        capturedPhysicalSource = if (captured) CapturedPhysicalSource(CaptureDomain()) else null,
    )
    private fun plane(record: StrokeRecord) = mapOf(address to RecordedCel("paint", null, listOf(record)))
    private fun history() = UndoLog<Int>(1_000_000, { 4 }, {})

    private class ProbeRenderer : RecordedScene.Renderer<Int> {
        var contextCalls = 0
        var lineCalls = 0
        var lookCalls = 0
        override fun contextCurrent(): Boolean { contextCalls++; return true }
        override fun line(address: LineAddress, record: StrokeRecord, preset: BrushPreset): RecordedScene.Unit<Int> {
            lineCalls++
            return RecordedScene.Unit(preset.blend, emptyMap())
        }
        override fun look(plane: Pair<String, String?>, key: Long, sources: List<RecordedScene.Source<Int>>): Int? {
            lookCalls++
            return null
        }
        override fun release(texture: Int) = Unit
    }

    @Test
    fun eagerAndSavedSceneFactoriesRefuseCapturedRecordsBeforeRendererCallbacks() {
        val eagerRenderer = ProbeRenderer()
        assertFailsWith<IllegalArgumentException> {
            RecordedScene.create(plane(record(captured = true)), mapOf(brush.id to brush), history(), eagerRenderer)
        }
        assertEquals(0, eagerRenderer.contextCalls)
        assertEquals(0, eagerRenderer.lineCalls)
        assertEquals(0, eagerRenderer.lookCalls)

        val savedRenderer = ProbeRenderer()
        assertFailsWith<IllegalArgumentException> {
            RecordedScene.createSaved(
                plane(record(captured = true)), mapOf(brush.id to brush), history(), savedRenderer,
                savedLookKeys = mapOf(address to emptySet()), uploadedLooks = mapOf(address to emptyMap()),
            )
        }
        assertEquals(0, savedRenderer.contextCalls)
        assertEquals(0, savedRenderer.lineCalls)
        assertEquals(0, savedRenderer.lookCalls)
    }

    @Test
    fun ordinaryRecordRemainsAcceptedByEagerAndSavedSceneFactories() {
        val eagerRenderer = ProbeRenderer()
        val eager = RecordedScene.create(plane(record(captured = false)), mapOf(brush.id to brush), history(), eagerRenderer)
        assertEquals(1, eagerRenderer.lineCalls)
        assertTrue(eagerRenderer.contextCalls > 0)
        eager.close()

        val savedRenderer = ProbeRenderer()
        val saved = RecordedScene.createSaved(
            plane(record(captured = false)), mapOf(brush.id to brush), history(), savedRenderer,
            savedLookKeys = mapOf(address to emptySet()), uploadedLooks = mapOf(address to emptyMap()),
        )
        assertTrue(savedRenderer.contextCalls > 0)
        assertEquals(0, savedRenderer.lineCalls, "saved acceptance keeps its no-replay contract")
        assertEquals(0, savedRenderer.lookCalls)
        saved.close()
    }
}
