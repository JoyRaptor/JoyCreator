package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.core.media.MediaStores
import cc.joycreator.joybrush.core.paint.UndoLog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A media layer's float stores in the engine (MEDIA_ENGINE_PLAN M5.3c, step 3a). Handles are made-up numbers planted
 * through undo, as GlContextLossTest does: what is pinned is where each name goes, not what a GPU would draw.
 */
class GlMediaStoresTest {
    private val layer = "wc"

    private fun engine() = GlPaintEngine().also { it.initWith { }; it.addLayer(layer) }

    @Test fun undoAndRedoPutFloatTilesBackAndSayWhichLayerToReload() {
        val e = engine()
        val told = ArrayList<Pair<String, Set<Long>>>()
        e.onMediaRestored = { id, keys -> told += id to keys }
        e.undo.push(UndoLog.Step(listOf(
            UndoLog.TileChange(MediaStores.id(layer, "p0"), 3, before = 501, after = 502),
            UndoLog.TileChange(MediaStores.id(layer, "w0"), 4, before = null, after = 503),
            UndoLog.TileChange(layer, 3, before = 504, after = 505),
        )))
        assertTrue(e.undoStep())
        assertEquals(501, e.mediaTile(layer, "p0", 3))
        assertNull(e.mediaTile(layer, "w0", 4), "water that did not exist before the step is gone")
        assertEquals(504, e.mediaTile(layer, e.mediaLook, 3), "the look keeps the layer's own id")
        assertEquals(listOf(layer to setOf(3L, 4L)), told, "one call per media layer: stop its simulation and reload")
        assertTrue(e.redoStep())
        assertEquals(502, e.mediaTile(layer, "p0", 3))
        assertEquals(503, e.mediaTile(layer, "w0", 4))
        assertEquals(listOf(4L), e.mediaTileKeys(layer, "w0"))
        assertTrue(e.hasMediaState(layer))
        assertEquals(2, told.size)
    }

    @Test fun aPaintOnlyStepTellsNobody() {
        val e = engine()
        var calls = 0
        e.onMediaRestored = { _, _ -> calls++ }
        e.undo.push(UndoLog.Step(listOf(UndoLog.TileChange(layer, 1, before = 601, after = 602))))
        assertTrue(e.undoStep())
        assertEquals(0, calls, "a paint layer has no simulation to stop")
        assertFalse(e.hasMediaState(layer))
    }

    @Test fun aContextLossLetsGoOfFloatTiles() {
        val e = engine()
        e.undo.push(UndoLog.Step(listOf(UndoLog.TileChange(MediaStores.id(layer, "paper"), 9, before = 701, after = 702))))
        assertTrue(e.undoStep())
        assertTrue(e.heldTextureNames() > 0)
        e.initWith { }
        assertTrue(e.lostContent, "a media layer's state is the drawing too")
        assertEquals(0, e.heldTextureNames())
        assertNull(e.mediaTile(layer, "paper", 9))
    }

    @Test fun pastTheMediaCeilingAStrokeIsRefusedInWordsBeforeAnythingIsSwapped() {
        val e = engine()
        e.mediaBudgetBytes = MediaStores.tileBytes(256).toLong()   // room for one store tile
        e.undo.push(UndoLog.Step(listOf(UndoLog.TileChange(MediaStores.id(layer, "paper"), 1, before = null, after = 801))))
        assertTrue(e.undoStep() && e.redoStep(), "plants tile 801 in the paper store")
        assertEquals(MediaStores.tileBytes(256).toLong(), e.mediaResidentBytes())
        e.beginMediaStroke()
        val refused = assertFailsWith<MediaRoomException> { e.writableMediaTiles(layer, listOf(2L), listOf("paper")) }
        assertTrue("pencil, watercolour and oil" in refused.message!!, refused.message)
        assertNull(e.mediaTile(layer, "paper", 2), "nothing was swapped in")
        assertEquals(0, e.endMediaStroke(), "and nothing reached the history")
    }

    @Test fun waterThatDriesInsideItsStepIsForgottenByThatStep() {
        val e = engine()
        e.undo.push(UndoLog.Step(listOf(
            UndoLog.TileChange(MediaStores.id(layer, "p0"), 1, before = null, after = 901),
            UndoLog.TileChange(MediaStores.id(layer, "w0"), 1, before = null, after = 902),
        )))
        assertTrue(e.undoStep() && e.redoStep(), "plants the stroke's tiles")
        e.dropMediaTiles(layer, "w0", listOf(1L))
        assertNull(e.mediaTile(layer, "w0", 1), "dry water has no tile")
        assertEquals(1, e.undo.undoDepth, "no step of its own")
        val s = e.undo.newestExtendable()!!
        assertEquals(listOf(MediaStores.id(layer, "p0")), s.changes.map { it.layerId }, "the water came and went inside the stroke")
    }

    @Test fun waterThatWasThereBeforeComesBackOnUndo() {
        val e = engine()
        e.undo.push(UndoLog.Step(listOf(UndoLog.TileChange(MediaStores.id(layer, "w0"), 2, before = null, after = 911))))
        assertTrue(e.undoStep() && e.redoStep())
        e.undo.push(UndoLog.Step(emptyList(), stackBefore = null, paperBefore = null))   // an unrelated step on top
        e.undo.push(UndoLog.Step(listOf(UndoLog.TileChange("other", 0, before = 1, after = 2))))
        e.dropMediaTiles(layer, "w0", listOf(2L))
        assertNull(e.mediaTile(layer, "w0", 2))
        assertTrue(e.undoStep(), "the fresh water step")
        assertEquals(911, e.mediaTile(layer, "w0", 2), "undo puts the water back")
    }

    @Test fun onlyWaterDriesAway() {
        val e = engine()
        assertFailsWith<IllegalArgumentException> { e.dropMediaTiles(layer, "p0", listOf(1L)) }
    }
}
