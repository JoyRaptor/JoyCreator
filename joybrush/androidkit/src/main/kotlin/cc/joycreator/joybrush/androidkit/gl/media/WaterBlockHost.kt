package cc.joycreator.joybrush.androidkit.gl.media

import cc.joycreator.joybrush.core.media.WaterBlocks

/**
 * The host of a water job run block by block (ATLAS_ROOM_20261023, P2): it decides whether a job fits the working atlas
 * whole, and otherwise keeps each block's tiles here while the block's passes run ([MediaLayerEngine] calls it from the
 * job). Between [enter] and [leave] the block's own tiles hold the step's state (its current half) and its ring holds at
 * least what the passes read there ([WaterParkPlan] ring planes: w0, w1, flux, p0). GL thread.
 */
internal interface WaterBlockHost {
    /** Whether a job over [cells] (water cells) can run whole: every tile it touches, and its ring, resident at once. */
    fun fitsWhole(cells: IntArray, skip: Set<Long>): Boolean
    /**
     * Tiles of a job over [cells] that hold no water and lie beyond its reach: no block owns them (a whole job never ran them
     * either: they were not resident, so its passes drew nothing there). Not walls: a block's ring still reads them.
     */
    fun dry(cells: IntArray): Set<Long> = emptySet()
    /** Before [block]'s passes in step [k]. */
    fun enter(block: WaterBlocks.Block, k: Int)
    /** After them: the block's own tiles hold their next half now. */
    fun leave(block: WaterBlocks.Block, k: Int)
    /** Every block of a step has run and the water flipped: what was next is current ([k] is the step about to run). */
    fun stepDone(k: Int)
    /** The job ended (or was dropped): nothing borrowed for it may stay. */
    fun jobDone()
}
