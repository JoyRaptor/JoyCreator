package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.core.paint.Accumulate
import cc.joycreator.joybrush.core.paint.StrokeBlend
import cc.joycreator.joybrush.core.paint.TipShape
import cc.joycreator.joybrush.core.paint.UndoLog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * JB-0.07 review F1: a GL context loss used to leave the engine holding the OLD texture names, so
 * the re-created context bound them as if they were the person's tiles — a blank or black document
 * presented as their painting, with an undo history that lied about it.
 *
 * The policy these tests pin is (a) of the two the review allowed: **`init` on a new context
 * empties every GPU-side record and SAYS SO** through [GlPaintEngine.lostContent]. The pixels are
 * not faked back and the document is not quietly repainted with the clear tex — a loss is reported,
 * and the caller decides what to tell the person.
 *
 * Every handle planted here is a made-up number, so nothing here can be mistaken for a real
 * texture: the point is that the engine cannot tell the difference, and must drop them all. The
 * planting uses only public API — an undo step put back through [GlPaintEngine.undoStep] drops a
 * fake name straight into a layer's tiles (which is how a committed stroke's tiles get their names),
 * and [GlPaintEngine.resetDocument] pushes them into the free pool.
 */
class GlContextLossTest {

    private val layer = "layer-1"

    /** Re-runs `init` with the GL calls behind a lambda, which is how `init()` runs them. */
    private fun GlPaintEngine.reInit() = initWith { }

    @Test
    fun aFirstInitOnAVirginContextLosesNothingBecauseThereWasNothing() {
        val engine = GlPaintEngine()
        engine.reInit()

        assertTrue(engine.ready)
        assertFalse(engine.lostContent, "a brand new engine has no drawing to lose")
        assertEquals(0, engine.heldTextureNames())
        assertEquals(emptyList(), engine.layerIds())
        assertFalse(engine.undo.canUndo)
        assertFalse(engine.undo.canRedo)
        assertFalse(engine.strokeInProgress)
    }

    @Test
    fun aContextLossLeavesNoStaleHandleToBeMistakenForTheDrawing() {
        val engine = GlPaintEngine()
        engine.reInit()
        plantDeadHandles(engine)

        // The state the review described: names in a layer, and a stroke in flight — all of it gone
        // with the context that minted it.
        assertTrue(engine.heldTextureNames() > 0, "the test must plant something for the loss to lose")
        assertTrue(engine.strokeInProgress)

        engine.reInit()

        assertEquals(0, engine.heldTextureNames(), "a dead name was still being held after the loss")
        assertEquals(emptyList(), engine.layerIds())
        assertEquals(emptyList(), engine.tileKeys(layer))
        assertEquals(0, engine.tileCount(layer))
        assertEquals(0L, engine.undo.heldBytes, "the undo log still owns tiles from the dead context")
        assertEquals(0, engine.undo.undoDepth)
        assertFalse(engine.undo.canUndo)
        assertFalse(engine.undo.canRedo)
        assertFalse(engine.strokeInProgress, "a stroke in flight survived the context that held it")
        assertFalse(engine.undoStep(), "the undo history still offered a step whose tiles are gone")
        assertFalse(engine.redoStep())
    }

    @Test
    fun aContextLossIsReportedSoTheCallerCanTellThePerson() {
        val engine = GlPaintEngine()
        engine.reInit()
        assertFalse(engine.lostContent)

        plantDeadHandles(engine)
        engine.reInit()

        assertTrue(engine.lostContent, "the engine emptied itself without saying so")

        // …and the flag describes THAT loss, not every init: an engine with nothing left in it has
        // nothing to announce, so a re-init after a loss is a clean start.
        engine.reInit()
        assertFalse(engine.lostContent)
    }

    @Test
    fun namesSittingInTheFreePoolAreLostTooAndAreNeverHandedOutAgain() {
        val engine = GlPaintEngine()
        engine.reInit()
        plantDeadHandles(engine)
        // resetDocument recycles the layer's tiles into the free pool — the worst place for a dead
        // name to sit, because newLayerTile() would hand it straight back out, and a recycled tile
        // never gets its storage re-specified.
        engine.resetDocument()
        assertTrue(engine.heldTextureNames() > 0, "the test must leave a name in a free pool")

        engine.reInit()

        assertEquals(0, engine.heldTextureNames(), "a pooled dead name was still there to be recycled")
        assertTrue(engine.lostContent, "pooled names from a dead context are still a lost drawing")
    }

    @Test
    fun drawingWorksAgainAfterALossWithoutAnythingDeadComingBack() {
        val engine = GlPaintEngine()
        engine.reInit()
        plantDeadHandles(engine)
        engine.reInit()

        // The engine is usable again, and everything it hands out from here is its own: a fresh
        // layer, an empty history, and no name from the old context left to be recycled.
        engine.addLayer(layer)
        assertEquals(listOf(layer), engine.layerIds())
        assertEquals(0, engine.tileCount(layer))
        assertFalse(engine.undo.canUndo)
        assertTrue(engine.ready)
    }

    /**
     * Puts made-up texture names where a real drawing would have put them, through public API only:
     * an undo step undone into a layer, and a stroke in flight.
     */
    private fun plantDeadHandles(engine: GlPaintEngine) {
        engine.addLayer(layer)
        engine.undo.push(
            UndoLog.Step(
                listOf(
                    UndoLog.TileChange(layer, 7, before = 404, after = 405),
                    UndoLog.TileChange(layer, 9, before = 406, after = null),
                )
            )
        )
        assertTrue(engine.undoStep(), "the planted undo step should undo")
        assertEquals(setOf(7L, 9L), engine.tileKeys(layer).toSet(), "the test failed to plant layer tiles")
        engine.beginStroke(layer, 0xFF1B1B22.toInt(), 1f, Accumulate.WASH, StrokeBlend.NORMAL, TipShape())
    }

    @Test
    fun aContextLossLetsGoOfTheCompositeTargetsToo() {
        // JB-2.20b: the two screen-sized textures and the two framebuffers are context objects like any other.
        // A name planted the way the tiles are (made-up numbers) must be gone after a loss, and must not be
        // counted as held by an engine that has been told the context is new.
        val engine = GlPaintEngine()
        engine.reInit()
        engine.compositor.target = 9001
        engine.compositor.backdrop = 9002
        engine.compositor.targetFbo = 9003
        engine.compositor.backdropFbo = 9004
        engine.compositor.width = 1080
        engine.compositor.height = 2400
        assertEquals(2, engine.heldTextureNames(), "the two composite textures are held names")

        engine.reInit()
        assertEquals(0, engine.heldTextureNames())
        assertEquals(0, engine.compositor.target)
        assertEquals(0, engine.compositor.backdrop)
        assertEquals(0, engine.compositor.targetFbo)
        assertEquals(0, engine.compositor.backdropFbo)
        assertEquals(0, engine.compositor.width, "a stale size would make ensure() think the new context already has targets")
    }
}
