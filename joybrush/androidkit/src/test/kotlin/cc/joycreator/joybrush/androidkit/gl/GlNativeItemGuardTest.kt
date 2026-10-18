package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.androidkit.lines.LinePlane
import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.layers.LayerStack
import cc.joycreator.joybrush.core.layers.LayerState
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import kotlin.test.*

/**
 * Editable lines v1.2 H2, the ENGINE guard: a pixel write that is not a line or a slab, on a tile an item crosses, is
 * refused before anything is touched (it can no longer silently break "tile = base + items"). The guard runs before the
 * first GL call, so it is checked here without a driver; that the guard lets a tile NO item crosses through is a phone
 * check (the write itself needs GL).
 */
class GlNativeItemGuardTest {
    private val at = LinePlane("paint", null)
    private val tileBytes = 256 * 256 * 4

    private fun engine() = GlPaintEngine().also {
        it.initWith {}
        it.setStack(LayerStack(listOf(LayerState("paint", "Paint")), "paint"))
    }

    private fun withLineOnTile0(engine: GlPaintEngine) {
        engine.nativeLines.put(at, "line", StrokeRecord("line", "frozen", 19, 0f, 1f, listOf(PenSample(1f, 1f, 0.0)), seq = 1),
            footprint = setOf(0L))
    }

    @Test fun aPlainTileReplacementOnACrossedTileIsRefusedBeforeAnythingChanges() {
        val engine = engine()
        withLineOnTile0(engine)
        val failure = assertFailsWith<IllegalStateException> { engine.replaceTiles("paint", mapOf(0L to ByteArray(tileBytes))) }
        assertTrue(failure.message!!.contains("editable lines"))
        assertFalse(engine.undo.canUndo, "no step was pushed")
        assertEquals(0, engine.tileKeys("paint").size, "no tile was made")
        assertEquals(listOf("line"), engine.nativeLines.lines(at).map { it.id })
    }

    @Test fun aDeletionOfACrossedTileIsRefusedToo() {
        val engine = engine()
        withLineOnTile0(engine)
        assertFailsWith<IllegalStateException> { engine.replaceTiles("paint", mapOf(0L to null)) }
        assertFalse(engine.undo.canUndo)
    }

    @Test fun aSlabAloneAlsoGuardsItsTiles() {
        val engine = engine()
        engine.nativeLines.setSlab(at, 2, 3L, 23, "erase")
        assertFailsWith<IllegalStateException> { engine.replaceTiles("paint", mapOf(3L to ByteArray(tileBytes))) }
        assertTrue(engine.nativeLines.hasSlabs(at))
    }

    @Test fun realisticPaintIsRefusedWhereAnItemCrossesBeforeAnythingIsSwapped() {
        val engine = engine()
        withLineOnTile0(engine)
        val failure = assertFailsWith<IllegalStateException> {
            engine.writableMediaTiles("paint", listOf(0L), listOf("p0"))
        }
        assertTrue(failure.message!!.contains("editable lines"))
        assertFalse(engine.undo.canUndo)
    }
}
