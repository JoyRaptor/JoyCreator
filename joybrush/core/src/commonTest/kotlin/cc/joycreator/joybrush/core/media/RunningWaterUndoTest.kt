package cc.joycreator.joybrush.core.media

import cc.joycreator.joybrush.core.layers.LayerStack
import cc.joycreator.joybrush.core.layers.LayerState
import cc.joycreator.joybrush.core.paint.UndoLog
import cc.joycreator.joybrush.core.doc.DocOps
import cc.joycreator.joybrush.core.doc.Paper
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.vector.CelComposer
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

    @Test fun aPlainStrokeThatSettledAPayloadIsNeverJoinedByADrip() {
        log.push(UndoLog.Step(listOf(tile("p0", 1, null, 10), tile("w0", 1, null, 11))))   // a wash, still running
        // An ink line over tile 1 (JB-2.40 slice 1): its own pixels change, and the tile's payload is dropped with it.
        log.push(UndoLog.Step(listOf(UndoLog.TileChange("wc", 1, 50, 51), UndoLog.TileChange(MediaStores.id("wc", "p0"), 1, 10, null))))
        assertFalse(MediaStores.isMediaStep(assertNotNull(log.newestExtendable())), "dropping a payload is not writing one")
        MediaStores.recordWater(log, listOf(tile("w0", 2, null, 20)))                         // the wash drips on elsewhere
        assertEquals(3, log.undoDepth, "the drip takes its own step")
        log.undo()
        assertEquals(listOf("wc" to 1L, "wc#p0" to 1L), assertNotNull(log.undo()).changes.map { it.layerId to it.key },
            "one Undo takes back the line alone, never the wash's progress with it")
    }

    @Test fun aDripAfterAMixedRecordedLineEditTakesASeparateWaterStep() {
        val line = CelComposer.Line(1, StrokeRecord("line", "frozen", 1, 0f, 1f, emptyList(), seq = 1))
        log.push(UndoLog.Step(listOf(tile("p0", 1, null, 10)), lines = listOf(
            UndoLog.LineChange("wc", null, "line", null, line))))
        MediaStores.recordWater(log, listOf(tile("w0", 2, null, 20)))
        assertEquals(2, log.undoDepth)
        val drip = assertNotNull(log.undo())
        assertEquals(listOf(2L), drip.changes.map { it.key }); assertTrue(drip.lines.isEmpty())
        val edit = assertNotNull(log.undo())
        assertEquals(listOf("line"), edit.lines.map { it.id }); assertEquals(listOf(1L), edit.changes.map { it.key })
    }

    @Test fun aDripAfterAMixedSlabEditTakesASeparateWaterStep() {
        val slab = UndoLog.SlabChange("wc", null, 4, 0,
            UndoLog.SlabValue(1, "normal"), UndoLog.SlabValue(2, "normal"))
        log.push(UndoLog.Step(listOf(tile("p0", 1, null, 10)), slabs = listOf(slab)))
        MediaStores.recordWater(log, listOf(tile("w0", 2, null, 20)))
        assertEquals(2, log.undoDepth)
        val drip = assertNotNull(log.undo())
        assertEquals(listOf(2L), drip.changes.map { it.key }); assertTrue(drip.slabs.isEmpty())
        val edit = assertNotNull(log.undo())
        assertEquals(listOf(4L), edit.slabs.map { it.key }); assertEquals(listOf(1L), edit.changes.map { it.key })
    }

    @Test fun afterOnlyPaperOrDocumentMetadataCannotAbsorbRunningWater() {
        var serial = 0
        val doc = DocOps.newDocument("doc", "Art", 64, 64) { "id-${++serial}" }
        val steps = listOf(
            UndoLog.Step(listOf(tile("p0", 1, null, 10)), paperAfter = Paper()),
            UndoLog.Step(listOf(tile("p0", 2, null, 20)), documentAfter = doc))
        steps.forEachIndexed { index, step ->
            log.push(step)
            val dripKey = 10L + index
            MediaStores.recordWater(log, listOf(tile("w0", dripKey, null, 30 + index)))
            assertEquals(2, log.undoDepth)
            assertEquals(listOf(dripKey), assertNotNull(log.undo()).changes.map { it.key })
            val edit = assertNotNull(log.undo())
            assertTrue(if (index == 0) edit.paperAfter != null else edit.documentAfter != null)
        }
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

    @Test fun aWetOnlyStepWhoseWaterAllDriesLeavesTheHistoryAsItWas() {
        log.push(UndoLog.Step(listOf(tile("p0", 1, null, 10))))                            // stroke A
        MediaStores.recordWater(log, emptyList())
        log.push(UndoLog.Step(listOf(tile("w0", 2, null, 20), tile("w1", 2, null, 21))))  // only water (a clear-water stroke)
        assertEquals(2, log.undoDepth)
        assertTrue(log.replaceInNewest(listOf(
            UndoLog.TileChange(MediaStores.id("wc", "w0"), 2, null, null),
            UndoLog.TileChange(MediaStores.id("wc", "w1"), 2, null, null))))
        assertEquals(1, log.undoDepth, "the empty step is gone: the next Undo undoes stroke A")
        assertEquals(listOf(1L), assertNotNull(log.undo()).changes.map { it.key })
    }
}
