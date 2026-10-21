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
 * A layer's media payload in the engine (MEDIA_ENGINE_PLAN M5.3c, step 3a; R51 and JB-2.40 slice 1). Handles are made-up numbers planted
 * through undo, as GlContextLossTest does: what is pinned is where each name goes, not what a GPU would draw.
 */
class GlMediaStoresTest {
    private val layer = "wc"

    private fun engine() = GlPaintEngine().also { it.initWith { }; it.addLayer(layer) }

    @Test fun undoAndRedoPutFloatTilesBackAndSayWhichLayerToReload() {
        val e = engine()
        val told = ArrayList<Pair<String, Set<Long>>>()
        e.onMediaRestored = { id, _, keys -> told += id to keys }
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
        e.onMediaRestored = { _, _, _ -> calls++ }
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

    // ---- JB-2.40 slice 1 (R51): the payload on an ordinary layer, the funnel, the net ----

    /** Plants a payload (p0 and the ground) on tile [key] beside look [look], as a media stroke's step would. */
    private fun planted(key: Long = 3, look: Int = 505): GlPaintEngine {
        val e = engine()
        e.undo.push(UndoLog.Step(listOf(
            UndoLog.TileChange(MediaStores.id(layer, "p0"), key, before = null, after = 502),
            UndoLog.TileChange(MediaStores.id(layer, MediaStores.GROUND), key, before = null, after = 503),
            UndoLog.TileChange(layer, key, before = null, after = look),
        )))
        assertTrue(e.undoStep() && e.redoStep(), "a step that carries look and payload together: they agree")
        return e
    }

    @Test fun theGroundIsPartOfThePayloadAndCountsAtItsOwnSize() {
        val e = planted()
        assertTrue(e.hasPayload(layer, 3))
        assertEquals(503, e.mediaTile(layer, MediaStores.GROUND, 3))
        assertEquals((MediaStores.tileBytes(256, "p0") + MediaStores.tileBytes(256, MediaStores.GROUND)).toLong(), e.mediaResidentBytes())
    }

    @Test fun aPlainWriteOverMediaDropsThatTilesPayloadInTheSameStepAndTellsTheWindow() {
        val e = planted()
        val told = ArrayList<Pair<String, Set<Long>>>()
        e.onLookWritten = { id, _, keys -> told += id to keys }
        val write = listOf(UndoLog.TileChange(layer, 3, before = 505, after = 506), UndoLog.TileChange(layer, 4, before = null, after = 507))
        val settled = e.settlePlainWrites(write)
        assertEquals(setOf(MediaStores.id(layer, "p0"), MediaStores.id(layer, MediaStores.GROUND)), settled.map { it.layerId }.toSet())
        assertTrue(settled.all { it.key == 3L && it.after == null }, "the payload goes, ground and all; tile 4 had none")
        assertFalse(e.hasPayload(layer, 3), "nothing is left to render the old paint over the new pixels")
        assertEquals(listOf(layer to setOf(3L, 4L)), told, "the window reloads every tile whose pixels changed")
    }

    @Test fun undoOfThatWriteBringsThePayloadBackAgreeing() {
        val e = planted()
        val write = listOf(UndoLog.TileChange(layer, 3, before = 505, after = 506))
        e.undo.push(UndoLog.Step(write + e.settlePlainWrites(write)))
        assertTrue(e.undoStep())
        assertEquals(502, e.mediaTile(layer, "p0", 3))
        assertEquals(505, e.mediaTile(layer, e.mediaLook, 3))
        assertEquals(emptySet(), e.dropStalePayload(layer, listOf(3L)), "restored together, so not stale")
        assertTrue(e.redoStep())
        assertFalse(e.hasPayload(layer, 3))
    }

    @Test fun aLookThatChangedBehindTheMediasBackLosesItsPayloadAtTheNextLoad() {
        // Requirement 5: a write that did not go through the funnel (here a bare look-only step, undone and redone).
        val e = planted()
        val warned = ArrayList<String>()
        e.onMediaWarning = { warned += it }
        assertEquals(emptySet(), e.dropStalePayload(layer, listOf(3L)), "an agreeing payload stays")
        e.undo.push(UndoLog.Step(listOf(UndoLog.TileChange(layer, 3, before = 505, after = 506))))
        assertTrue(e.undoStep() && e.redoStep(), "the look is 506 now, and nothing told the payload")
        val depth = e.undo.undoDepth
        assertEquals(setOf(3L), e.dropStalePayload(layer, listOf(3L, 4L)))
        assertFalse(e.hasPayload(layer, 3), "lossy, never wrong")
        assertEquals(depth + 1, e.undo.undoDepth, "the drop is a step of its own outside a media stroke")
        assertEquals(1, warned.size, "and it is said, because only a bypass gets here")
    }

    @Test fun insideAMediaStrokeAStaleDropAndTheNewPaintAreOneChange() {
        // The stroke drops a stale payload at a window load, then paints the same tile: undo must put the OLD payload back.
        val e = planted()
        e.undo.push(UndoLog.Step(listOf(UndoLog.TileChange(layer, 3, before = 505, after = 506))))
        assertTrue(e.undoStep() && e.redoStep())
        e.beginMediaStroke()
        assertEquals(setOf(3L), e.dropStalePayload(layer, listOf(3L)))
        e.endMediaStroke()
        val s = e.undo.newestExtendable()!!
        assertEquals(setOf(502, 503), s.changes.mapNotNull { it.before }.toSet(), "undo of the stroke restores what was dropped")
    }



    @Test fun aCancelledMediaStrokeGoesBackWithoutAStepAndRedoSurvives() {
        // The owner's finger daubs (2026-10-21): a stroke a second finger gives up must leave nothing, as a plain stroke.
        val e = planted()
        e.undo.push(UndoLog.Step(listOf(UndoLog.TileChange(layer, 3, before = 505, after = 506))))
        assertTrue(e.undoStep() && e.redoStep(), "the look changed behind the payload: the stroke will drop it")
        e.undo.push(UndoLog.Step(listOf(UndoLog.TileChange("other", 0, before = 1, after = 2))))
        assertTrue(e.undoStep(), "something to redo")
        val depth = e.undo.undoDepth
        val told = ArrayList<Set<Long>>()
        e.onMediaRestored = { _, _, keys -> told += keys }
        e.beginMediaStroke()
        assertEquals(setOf(3L), e.dropStalePayload(layer, listOf(3L)), "the stroke changed tile 3")
        assertNull(e.mediaTile(layer, "p0", 3))
        assertEquals(2, e.cancelMediaStroke(), "its two changes (p0 and the ground) went back")
        assertEquals(502, e.mediaTile(layer, "p0", 3), "the payload is back")
        assertEquals(503, e.mediaTile(layer, MediaStores.GROUND, 3))
        assertEquals(depth, e.undo.undoDepth, "no step")
        assertTrue(e.undo.canRedo, "and redo survives")
        assertEquals(listOf(setOf(3L)), told, "the media canvas is told to reload tile 3")
        assertFalse(e.mediaStrokeInProgress)
        assertEquals(0, e.cancelMediaStroke(), "nothing to cancel twice")
    }

    // ---- JB-2.40 slice 3: a payload per plane (an animation frame keeps its own pencil, watercolour and oil) ----

    private val frame = "frame-1"

    /** [planted]'s shared payload on tile 3, plus a frame plane's own payload and look on the same tile. */
    private fun plantedWithFrame(): GlPaintEngine {
        val e = planted()
        e.undo.push(UndoLog.Step(listOf(
            UndoLog.TileChange(MediaStores.id(layer, "p0"), 3, before = null, after = 601, celId = frame),
            UndoLog.TileChange(layer, 3, before = null, after = 602, celId = frame),
        )))
        assertTrue(e.undoStep() && e.redoStep())
        return e
    }

    @Test fun aFramesPayloadIsItsOwnAndTheSharedPlaneIsUntouched() {
        val e = plantedWithFrame()
        assertEquals(601, e.mediaTile(layer, "p0", 3, frame))
        assertEquals(602, e.mediaTile(layer, e.mediaLook, 3, frame), "the frame's look is its own cel")
        assertEquals(502, e.mediaTile(layer, "p0", 3), "the shared plane keeps its own paint")
        assertEquals(505, e.mediaTile(layer, e.mediaLook, 3))
        assertTrue(e.hasPayload(layer, 3, frame) && e.hasPayload(layer, 3))
        assertEquals(listOf(frame), e.mediaFramePlanes(layer))
        assertEquals(listOf(3L), e.mediaTileKeys(layer, "p0", frame))
        assertEquals((2 * MediaStores.tileBytes(256, "p0") + MediaStores.tileBytes(256, MediaStores.GROUND)).toLong(), e.mediaResidentBytes(),
            "the ceiling counts every plane")
    }

    @Test fun undoOnAFrameTellsTheWindowWhichPlane() {
        val e = plantedWithFrame()
        val told = ArrayList<Triple<String, String?, Set<Long>>>()
        e.onMediaRestored = { id, plane, keys -> told += Triple(id, plane, keys) }
        assertTrue(e.undoStep())
        assertNull(e.mediaTile(layer, "p0", 3, frame))
        assertEquals(502, e.mediaTile(layer, "p0", 3), "undoing the frame's stroke leaves the shared paint")
        assertEquals(listOf(Triple(layer, frame as String?, setOf(3L))), told)
    }

    @Test fun aPlainWriteOnAFrameSettlesOnlyThatFramesPayload() {
        val e = plantedWithFrame()
        val told = ArrayList<Triple<String, String?, Set<Long>>>()
        e.onLookWritten = { id, plane, keys -> told += Triple(id, plane, keys) }
        val settled = e.settlePlainWrites(listOf(UndoLog.TileChange(layer, 3, before = 602, after = 603, celId = frame)))
        assertEquals(listOf(MediaStores.id(layer, "p0") to frame as String?), settled.map { it.layerId to it.celId })
        assertFalse(e.hasPayload(layer, 3, frame))
        assertTrue(e.hasPayload(layer, 3), "another frame's (or the shared plane's) paint is not this write's business")
        assertEquals(listOf(Triple(layer, frame as String?, setOf(3L))), told)
    }

    @Test fun aStalePayloadOnAFrameIsJudgedAgainstThatFramesLook() {
        val e = plantedWithFrame()
        assertEquals(emptySet(), e.dropStalePayload(layer, listOf(3L), frame), "frame payload and frame look agree")
        e.undo.push(UndoLog.Step(listOf(UndoLog.TileChange(layer, 3, before = 602, after = 604, celId = frame))))
        assertTrue(e.undoStep() && e.redoStep(), "the frame's look changed behind its payload's back")
        assertEquals(emptySet(), e.dropStalePayload(layer, listOf(3L)), "the shared plane still agrees")
        assertEquals(setOf(3L), e.dropStalePayload(layer, listOf(3L), frame))
    }
}