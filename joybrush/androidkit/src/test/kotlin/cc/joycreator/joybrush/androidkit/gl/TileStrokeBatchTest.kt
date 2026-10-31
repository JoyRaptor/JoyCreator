package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.grain.GrainMath
import cc.joycreator.joybrush.core.paint.Dab
import org.junit.Assert.*
import org.junit.Test

/**
 * The tile board's one-pass-a-frame painting (TileStrokeBatch) draws exactly what painting each pen event did. Each tile is
 * its own picture, so a tile's pixels follow from the dabs drawn into it, in order, and the per-batch uniforms each dab
 * reads; both plans are compared as that list per tile.
 */
class TileStrokeBatchTest {
    private val size = 256
    // Not on the tile grid, and the stroke below crosses its right edge, so copies land on the far side.
    private val board = RectPx(100, 60, 400, 300)

    /** What the shader reads per dab: the dab, and the batch lean (null for a dab that carries its own contact). */
    private fun drawn(d: Dab, uniformsFrom: Dab): Pair<Dab, List<Float>?> =
        d to if (StampPass.isLive(d)) null
        else listOf(GrainMath.tiltAmount(uniformsFrom.tilt), GrainMath.leanX(uniformsFrom.azimuth), GrainMath.leanY(uniformsFrom.azimuth))

    /** Pen events crossing the wrap edge; the lean changes every few events and some events carry live contact. */
    private fun events(): List<List<Dab>> = (0 until 24).map { e ->
        (0 until 5).map { i ->
            val t = e * 5 + i
            val tilt = 0.2f + 0.1f * (e / 4)          // a new lean every fourth event
            val live = e % 7 == 3                      // and now and then a live-contact event
            Dab(x = 380f + t * 3.1f, y = 120f + (t % 9) * 14f, radius = 18f + (t % 3), tilt = tilt, azimuth = 0.7f,
                travelKnown = true, hardness = if (live) 0.6f else Float.NaN)
        }
    }

    private fun perEvent(evs: List<List<Dab>>): Map<Long, List<Pair<Dab, List<Float>?>>> {
        val out = LinkedHashMap<Long, MutableList<Pair<Dab, List<Float>?>>>()
        for (ev in evs) for ((key, list) in TilePainting.bucketsOnBoard(board, ev, size))
            out.getOrPut(key) { ArrayList() } += list.map { drawn(it, ev.last()) }
        return out
    }

    private fun batched(evs: List<List<Dab>>, runs: IntArray): Map<Long, List<Pair<Dab, List<Float>?>>> {
        val out = LinkedHashMap<Long, MutableList<Pair<Dab, List<Float>?>>>()
        val batch = TileStrokeBatch<Dab>()
        fun paint() {
            if (batch.isEmpty()) return
            val from = requireNotNull(batch.uniformsFrom)
            for ((key, list) in batch.take()) out.getOrPut(key) { ArrayList() } += list.map { drawn(it, from) }
            runs[0]++
        }
        // As GlPaintEngine.addDabs: a lean the waiting run does not share paints the run first; the frame paints the rest.
        for ((i, ev) in evs.withIndex()) {
            val lean = TileStrokeBatch.leanOf(ev)
            if (!batch.fits(lean)) paint()
            batch.add(TilePainting.bucketsOnBoard(board, ev, size), lean, ev.last())
            if (i % 10 == 9) paint()   // a frame every ten events
        }
        paint()
        return out
    }

    @Test fun oneRunAFrameDrawsWhatEachEventDrew() {
        val evs = events()
        val runs = intArrayOf(0)
        val expected = perEvent(evs)
        assertTrue("the stroke must cross the wrap edge", evs.flatten().any { it.x + it.radius > board.x + board.w })
        assertTrue("copies must land on more tiles than the pen touched", expected.size >= 4)
        assertEquals(expected, batched(evs, runs))
        assertTrue("events are batched (${runs[0]} runs for ${evs.size} events)", runs[0] < evs.size / 2)
    }

    @Test fun aLiveEventJoinsAnyRunAndADifferentLeanDoesNot() {
        val plain = listOf(Dab(10f, 10f, 4f, tilt = 0.5f, azimuth = 1f))
        val other = listOf(Dab(10f, 10f, 4f, tilt = 0.9f, azimuth = 1f))
        val live = listOf(Dab(10f, 10f, 4f, tilt = 0.9f, azimuth = 2f, anchor = 0.1f))
        assertNull(TileStrokeBatch.leanOf(live))
        val b = TileStrokeBatch<Dab>()
        b.add(mapOf(1L to live), TileStrokeBatch.leanOf(live), live.last())
        assertTrue(b.fits(TileStrokeBatch.leanOf(plain)))
        b.add(mapOf(1L to plain), TileStrokeBatch.leanOf(plain), plain.last())
        assertSame("the run's uniforms come from the event whose lean they must be", plain.last(), b.uniformsFrom)
        assertTrue(b.fits(TileStrokeBatch.leanOf(live)))
        b.add(mapOf(2L to live), null, live.last())
        assertSame(plain.last(), b.uniformsFrom)
        assertFalse(b.fits(TileStrokeBatch.leanOf(other)))
        try { b.add(mapOf(1L to other), TileStrokeBatch.leanOf(other), other.last()); fail("must refuse") } catch (_: IllegalArgumentException) {}
        assertEquals(mapOf(1L to live + plain, 2L to live), b.take())
        assertTrue(b.isEmpty()); assertNull(b.uniformsFrom); assertTrue(b.fits(TileStrokeBatch.leanOf(other)))
    }

    @Test fun a_different_tip_waits_for_the_run_to_paint() {
        val b = TileStrokeBatch<Int>()
        assertTrue(b.fits(null, "a"))
        b.add(mapOf(1L to listOf(1)), null, null, "a")
        assertTrue(b.fits(null, "a")); assertFalse(b.fits(null, "b"))
        try { b.add(mapOf(1L to listOf(2)), null, null, "b"); fail("must refuse") } catch (_: IllegalArgumentException) {}
        b.take()
        assertTrue(b.fits(null, "b"))
    }
}
