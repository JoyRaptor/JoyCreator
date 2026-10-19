package cc.joycreator.joybrush.core.media

import kotlin.math.min

/**
 * Watercolour Option A for the owner's A/B (October 18): the brush stays instant, the water catches up. While the pen is
 * down the water runs ONE step a frame instead of [WetConstants.substeps]; the steps it skipped are owed and paid back
 * after the pen lifts, at most [MAX_PER_FRAME] steps a frame. Every step keeps its dt (1/120 s): a larger dt is unstable
 * (MEDIA_PERF_20261011). The debt is capped at [MAX_DEBT] steps (2 s of water): on a phone too slow to pay it back the
 * water simply runs later than the brush, never in a burst. Off ([enabled] false): every frame runs the shipped steps.
 */
class WaterSchedule {
    /** Water steps skipped while drawing and not yet paid back. */
    var debt = 0
        private set

    /** Steps for a frame drawn while the pen is down. */
    fun whileDrawing(enabled: Boolean, full: Int): Int {
        if (!enabled) return full
        debt = min(MAX_DEBT, debt + full - 1)
        return 1
    }

    /** Steps for a frame with the pen up and water running: the usual, plus what is owed, as far as one frame allows. */
    fun whileIdle(enabled: Boolean, full: Int): Int {
        if (!enabled || debt == 0) return full
        val extra = min(debt, MAX_PER_FRAME - full)
        debt -= extra
        return full + extra
    }

    /** The water stopped or was undone: nothing is owed any more. */
    fun reset() { debt = 0 }

    companion object {
        const val MAX_PER_FRAME = 8
        /** 2 s of water at dt = 1/120. */
        const val MAX_DEBT = 240
    }
}
