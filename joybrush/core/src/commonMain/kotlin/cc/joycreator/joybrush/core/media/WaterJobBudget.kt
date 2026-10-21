package cc.joycreator.joybrush.core.media

import kotlin.math.max
import kotlin.math.min

/**
 * How much water work a frame may do (tasks/joybrush/specs/WATER_JOBS_20261021.md), in the engine's units (px x stage weight,
 * MediaLayerEngine.runJob), steered by how long frames really take: a frame slower than [slowMs] shrinks it, one faster than
 * [fastMs] grows it, so the screen keeps its pace and the water takes what is left. Starts at [start].
 */
class WaterJobBudget(
    private val start: Double = 1.5e6,
    private val fastMs: Float = 28f,
    private val slowMs: Float = 45f,
    private val least: Double = 2.5e5,
    private val most: Double = 1.2e7,
) {
    var units = start
        private set

    /** After a frame that took [frameMs] (0 = unknown: unchanged); the budget for the next. */
    fun next(frameMs: Float): Double {
        if (frameMs > 0f) {
            units = when {
                frameMs > slowMs -> units * max(0.5, slowMs / frameMs.toDouble())
                frameMs < fastMs -> units * 1.15
                else -> units
            }
            units = min(most, max(least, units))
        }
        return units
    }

    fun reset() { units = start }
}
