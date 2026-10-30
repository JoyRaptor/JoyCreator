package cc.joycreator.joybrush.androidkit.gl.media

import cc.joycreator.joybrush.androidkit.gl.GlPaintEngine
import cc.joycreator.joybrush.androidkit.gl.ShaderLibrary
import cc.joycreator.joybrush.core.media.MediaPin
import cc.joycreator.joybrush.core.media.MediaStores
import cc.joycreator.joybrush.core.media.MediaWindowMath
import cc.joycreator.joybrush.core.paint.UndoLog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The PINNED media window (JB-2.12a's Phase E).
 *
 * Under a symmetry guide the window is pinned on the symmetry centre for the whole stroke, so that
 * every copy's wet paint lives in ONE water field: the window does not move, and a frame whose
 * paint would not fit is REFUSED rather than clipped.
 *
 * ## What the JVM can and cannot drive here
 *
 * The media engine's PASSES need a GPU: its textures and framebuffers are made with GL calls, and
 * even the trace wrapper around them reads `android.os.Build`, which is not on this classpath. So
 * the window's LOAD fails here — the PLACEMENT is what is under test, and the window sets it
 * before the load's GL work.
 *
 * The teeth are in the difference: a PINNED window refuses every move before it touches the engine
 * (no failure at all), while an UNPINNED one goes into the move and fails on the GPU. So the test
 * can tell the two apart without a GPU.
 */
class MediaWindowPinTest {

    private val layer = "wc"

    private fun window(): MediaWindow {
        val paint = GlPaintEngine().also { it.initWith { }; it.addLayer(layer) }
        return MediaWindow(paint, MediaLayerEngine(ShaderLibrary { "" })) { }
    }

    /** A rectangle far outside any window: a move would be needed to hold it. */
    private val far = doubleArrayOf(-5000.0, -5000.0, 5000.0, 5000.0)

    /**
     * A pinned window does not move — and an unpinned one TRIES to (and fails on the GPU, which is
     * how this test knows the difference).
     */
    @Test
    fun aPinnedWindowDoesNotMoveAndAnUnpinnedOneTriesTo() {
        val w = window()
        // The pin's own placement: the centre rounded to the tile grid. 1000 / 256 = 3.906, which
        // rounds to 4, and the window's tile is 4 − 2 = 2.
        runCatching { w.pin(layer, null, 1000.0, 1000.0) }   // the load needs a GPU
        val tx = w.tx
        val ty = w.ty
        assertEquals(2, tx, "the pin's own placement")
        assertEquals(2, ty)

        // Every way of moving the window is refused WHILE IT IS PINNED — and refused before the
        // engine is touched, so nothing fails and nothing moves.
        assertNull(runCatching { w.follow(far, 0.0, 0.0) }.exceptionOrNull(), "a pinned window does not follow")
        assertNull(runCatching { w.holdWhole(far) }.exceptionOrNull(), "a pinned window does not hold")
        assertNull(runCatching { w.followCentred(far) }.exceptionOrNull(), "a pinned window does not centre")
        assertNull(runCatching { w.place(layer, null, 0.0, 0.0) }.exceptionOrNull(), "a pinned window does not move")
        assertEquals(tx, w.tx, "the pinned window moved")
        assertEquals(ty, w.ty, "the pinned window moved")
        // And a pinned window cannot be made to hold a frame: it cannot move to hold it.
        assertFalse(w.holdWhole(doubleArrayOf(1000.0, 1000.0, 1010.0, 1010.0)))
        // And no move can clip anything, because no move happens.
        assertFalse(w.wouldClip(far, 0.0, 0.0, far))

        // THE HARNESS HAS TEETH: the same call on an UNPINNED window goes into the move, and the
        // move needs the GPU — so it fails there, and only there.
        w.unpin()
        val failure = runCatching { w.follow(far, 0.0, 0.0) }.exceptionOrNull()
        assertNotNull(failure, "an unpinned window does not even try to move: the assertions above would prove nothing")
        // (and it still did not move: the load needs a GPU, which this machine does not have)
        assertEquals(tx, w.tx, "the window's own placement is what the pin set")
    }

    /**
     * A frame that would not fit the pinned window is REFUSED, not clipped: a clipped dab is a lost
     * dab, and a lost dab in one copy is a broken picture.
     */
    @Test
    fun aFrameThatWouldNotFitIsRefusedAndNotClipped() {
        val (tx, ty) = MediaWindowMath.placeForRounded(1000.0, 1000.0)
        // The window's own box: 512 .. 1536 on both axes.
        assertEquals(MediaPin.Decision.RUN_WHOLE, MediaPin.decide(tx, ty, doubleArrayOf(1000.0, 1000.0, 1010.0, 1010.0)),
            "a frame inside the window runs whole")
        assertEquals(MediaPin.Decision.REFUSE, MediaPin.decide(tx, ty, doubleArrayOf(0.0, 0.0, 5000.0, 5000.0)),
            "a frame whose paint would fall off the window is refused")
        assertEquals(MediaPin.Decision.REFUSE, MediaPin.decide(tx, ty, doubleArrayOf(0.0, 0.0, 5000.0, 1010.0)),
            "one side over the edge is enough")
        assertEquals(MediaPin.Decision.RUN_WHOLE, MediaPin.decide(tx, ty, null), "a frame with nothing to paint runs whole")

        // And the window may not move while it is pinned.
        assertTrue(MediaPin.mayMove(false), "an ordinary window follows the pen")
        assertFalse(MediaPin.mayMove(true), "a pinned window does not")
    }

    /**
     * ONE media stroke with N lanes is ONE undo step.
     *
     * The copies do not add steps: the whole stroke is one [GlPaintEngine.beginMediaStroke] and one
     * [GlPaintEngine.endMediaStroke], and the end pushes ONE step however many tiles the copies
     * touched. This is proved on the real engine, which keeps its history without a GPU.
     */
    @Test
    fun oneMediaStrokeWithManyCopiesIsOneUndoStep() {
        val e = GlPaintEngine().also { it.initWith { }; it.addLayer(layer) }
        // Nine tiles' payloads: the copies' tiles. A payload with no look beside it is stale by the
        // engine's own rule (the look changed behind the media's back), which is how a media write
        // is made without a GPU.
        for (key in 1L..9L) {
            e.undo.push(UndoLog.Step(listOf(
                UndoLog.TileChange(MediaStores.id(layer, "p0"), key, before = null, after = 500 + key.toInt()),
            )))
            assertTrue(e.undoStep() && e.redoStep())
        }
        val before = e.undo.undoDepth
        // The stroke: nine lanes' worth of writes, all inside the SAME media stroke.
        e.beginMediaStroke()
        var dropped = 0
        for (key in 1L..9L) dropped += e.dropStalePayload(layer, listOf(key)).size
        assertEquals(9, dropped, "every lane's tile changed inside the stroke")
        e.endMediaStroke()
        assertEquals(before + 1, e.undo.undoDepth, "the copies' writes are ONE undo step")
        // And that ONE step holds all nine of them.
        val step = assertNotNull(e.undo.newestExtendable(), "the media stroke's step")
        assertEquals(9, step.changes.size, "the one step holds every copy's write")
    }
}
