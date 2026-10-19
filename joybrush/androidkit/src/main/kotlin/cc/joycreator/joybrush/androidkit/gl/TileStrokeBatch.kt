package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.core.grain.GrainMath
import cc.joycreator.joybrush.core.paint.Dab

/**
 * A tile-board stroke's waiting paint: what the pen events since the last paint put on each 256 tile, in arrival order,
 * painted in one pass a frame (GlPaintEngine.paintWaitingTileStroke) instead of one pass per pen event.
 *
 * Why: on a tile board a dab near the wrap edge also lands on the far side, so an event touches about twice the tiles, and
 * each tile is a framebuffer switch. Painting per event made a slow frame slower (more events queue for the next one) until
 * Note 9 frames took 500 ms (tile profile, 2026-10-19).
 *
 * The pixels stay exactly those of per-event painting: tiles are separate pictures, each tile gets the same dabs in the same
 * order, and each dab is drawn with the same per-batch uniforms. The one per-batch value taken from a dab is the pen's lean
 * (StampPass.setGrainUniforms reads it from the batch's newest dab), and only dabs without their own contact read it
 * (StampPass.isLive), so an event joins the waiting run only while that lean agrees ([fits]); otherwise the run is painted
 * first.
 */
internal class TileStrokeBatch<T> {
    private val waiting = LinkedHashMap<Long, ArrayList<T>>()
    private var lean: FloatArray? = null

    /** The dab the run's per-batch uniforms are set from (stamp dabs; null for tuft stamps, whose uniforms are the stroke's). */
    var uniformsFrom: Dab? = null
        private set

    fun isEmpty(): Boolean = waiting.isEmpty()

    /** Whether an event whose dabs read per-batch [lean] (null: none read it) can join the waiting run. */
    fun fits(lean: FloatArray?): Boolean {
        val held = this.lean
        return lean == null || held == null || held.contentEquals(lean)
    }

    /** One event's paint, bucketed by tile; [newest] is the dab its own batch would have set the uniforms from. */
    fun add(buckets: Map<Long, List<T>>, lean: FloatArray? = null, newest: Dab? = null) {
        require(fits(lean)) { "Paint the waiting run before a different pen lean" }
        if (lean != null) { this.lean = lean; uniformsFrom = newest } else if (this.lean == null) uniformsFrom = newest
        for ((key, list) in buckets) waiting.getOrPut(key) { ArrayList() }.addAll(list)
    }

    /** The waiting run, tile by tile (first touched first), each tile's paint in arrival order; the batch is then empty. */
    fun take(): Map<Long, List<T>> {
        val run = LinkedHashMap<Long, List<T>>(waiting)
        waiting.clear(); lean = null; uniformsFrom = null
        return run
    }

    fun clear() { waiting.clear(); lean = null; uniformsFrom = null }

    companion object {
        /** The per-batch lean an event's [dabs] read (setGrainUniforms, newest dab); null when every dab carries its own. */
        fun leanOf(dabs: List<Dab>): FloatArray? {
            if (dabs.all(StampPass::isLive)) return null
            val n = dabs[dabs.size - 1]
            return floatArrayOf(GrainMath.tiltAmount(n.tilt), GrainMath.leanX(n.azimuth), GrainMath.leanY(n.azimuth))
        }
    }
}
