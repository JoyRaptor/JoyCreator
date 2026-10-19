package cc.joycreator.joybrush.core.media

/**
 * Watercolour Option S for the owner's A/B (October 18): is ALL the water still? Fed the water activity measurement (per 16
 * px block: deepest water, dampness, fastest outflow; MediaLayerEngine.takeWaterActivity). The water is moving while it
 * spreads (a block holds standing water that did not before) or anything runs faster than [flowEps] mm/s (a drip);
 * otherwise it has been quiet since the last move ([quietFor]). Blocks drying out are not movement: a pool shrinks as it
 * soaks in for minutes, and counting that kept Option S awake for the whole drying (Note 9, 2026-10-18). Water in this
 * simulation always flows a little toward its drying edge, so "no flow at all" would never come either (measured:
 * MEDIA_ATLAS_B3_20261017, 0.2-0.4 mm/s in a settling pool).
 */
class WaterQuiet(private val minMm: Float, private val flowEps: Float = 1.0f) {
    private var lastBlocks: Set<Int>? = null
    private var changedAt = Long.MIN_VALUE

    /** A measurement taken at [nowMs]: [grid] is (blocks across × blocks down) × 4 floats, as the activity query returns. */
    fun observe(grid: FloatArray, nowMs: Long) {
        val blocks = HashSet<Int>()
        var running = false
        for (i in 0 until grid.size / 4) {
            if (grid[i * 4] > minMm) blocks += i
            if (grid[i * 4 + 2] > flowEps) running = true
        }
        val spread = lastBlocks.let { last -> last == null || blocks.any { it !in last } }
        if (running || spread) changedAt = nowMs
        lastBlocks = blocks
    }

    /** How long the water has been still at [nowMs] (0 when never measured, or moving at the last measurement). */
    fun quietFor(nowMs: Long): Long = if (changedAt == Long.MIN_VALUE) 0L else nowMs - changedAt

    /** New water, a tilt, an undo: start counting again. */
    fun reset(nowMs: Long) { lastBlocks = null; changedAt = nowMs }
}
