package cc.joycreator.joybrush.core.media

import cc.joycreator.joybrush.core.layers.LayerStack
import cc.joycreator.joybrush.core.layers.LayerState
import cc.joycreator.joybrush.core.paint.UndoLog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The Lead's running-water rule (MEDIA_ENGINE_PLAN M5.3c): a drip joins whatever media step is on top; history stays linear. */
class RunningWaterUndoTest {
    private val released = ArrayList<Int>()
    private val log = UndoLog<Int>(1L shl 40, { 1L }, { released.add(it) })
    private fun tile(store: String, key: Long, before: Int?, after: Int) = UndoLog.TileChange(MediaStores.id("wc", store), key, before, after)
    private val stack = LayerStack(listOf(LayerState("wc", "Watercolour")), "wc")

    @Test fun storeIdsRoundTrip() {
        assertEquals("wc" to "w0", MediaStores.parse("wc#w0"))
        assertEquals(null, MediaStores.parse("wc#mask"))
        assertEquals(null, MediaStores.parse("wc"))
        assertEquals("a#b" to "p1", MediaStores.parse("a#b#p1"))
    }

    @Test fun aDripAfterASecondStrokeJoinsTheSecondStroke() {
        log.push(UndoLog.Step(listOf(tile("p0", 1, null, 10), tile("w0", 1, null, 11))))   // stroke A
        log.push(UndoLog.Step(listOf(tile("p0", 2, null, 20))))                              // stroke B, A still wet
        MediaStores.recordWater(log, listOf(tile("w0", 3, null, 30)))                         // A's drip reaches tile 3
        assertEquals(2, log.undoDepth, "no step of its own")
        val b = assertNotNull(log.undo())
        assertEquals(listOf(2L, 3L), b.changes.map { it.key }, "undoing B removes the drip that ran during B")
    }

    @Test fun aDripAfterALayerChangeOrAnUndoTakesAFreshWaterStep() {
        log.push(UndoLog.Step(listOf(tile("p0", 1, null, 10))))
        log.push(UndoLog.Step(emptyList(), stackBefore = stack, stackAfter = stack.copy(activeId = "wc")))
        MediaStores.recordWater(log, listOf(tile("w0", 2, null, 20)))
        assertEquals(3, log.undoDepth, "a layer change is never extended")
        MediaStores.recordWater(log, listOf(tile("w0", 3, null, 30)))
        assertEquals(3, log.undoDepth, "the rest of the episode joins the water step")
        log.undo()
        MediaStores.recordWater(log, listOf(tile("w0", 4, null, 40)))
        assertFalse(log.canRedo, "with something to redo, the water takes a fresh step (and redo is gone, as after any new mark)")
        assertEquals(listOf(4L), assertNotNull(log.undo()).changes.map { it.key })
    }

    @Test fun aTileIsNeverSnapshottedTwiceInOneStep() {
        log.push(UndoLog.Step(listOf(tile("w0", 1, null, 10))))
        assertEquals(listOf("wc#w0"), log.newestExtendable()?.changes?.map { it.layerId })
        assertFailsWith<IllegalArgumentException> { MediaStores.recordWater(log, listOf(tile("w0", 1, 10, 11))) }
    }

    @Test fun waterThatCameAndDriedInsideOneStepLeavesNoTrace() {
        log.push(UndoLog.Step(listOf(tile("p0", 1, 5, 10), tile("w0", 1, null, 11), tile("w0", 2, 20, 21))))
        assertTrue(log.replaceInNewest(listOf(
            UndoLog.TileChange(MediaStores.id("wc", "w0"), 1, null, null),   // made in the step, now dry: gone
            UndoLog.TileChange(MediaStores.id("wc", "w0"), 2, 20, null),     // was there before: undo brings it back
        )))
        val s = assertNotNull(log.undo())
        assertEquals(listOf("wc#p0" to 1L, "wc#w0" to 2L), s.changes.map { it.layerId to it.key })
        assertEquals(null, s.changes.last().after)
        log.redo()
        assertFalse(log.replaceInNewest(listOf(UndoLog.TileChange(MediaStores.id("wc", "w0"), 9, null, null))), "a tile the step does not hold")
    }
}
