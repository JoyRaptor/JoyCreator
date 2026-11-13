package cc.joycreator.joybrush.core.media

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Which parts of the water's rectangle a step has to draw (the wet sheet, tasks/joybrush/specs/WET_SHEET_20261110.md).
 *
 * The water's passes ran over the one rectangle that holds all the water. Two marks at the top and the bottom of the page
 * made that the whole page, most of it dry paper: on the Note 9 the water fell to 0.37 of the clock (2026-11-10). This
 * keeps, per block of [block] cells, whether it held water or dampness at the last few measurements
 * (MediaLayerEngine.takeWaterActivity) or was just wetted by the brush, and gives the step the rectangles to draw: the
 * live blocks with a ring round them, in bigger squares so they are few.
 *
 * Why leaving a block out is safe. Every pass writes the other half of a ping-pong pair, so a block that is not drawn
 * keeps, in the half being written, what it held two steps before. That is the same as its current half only once the
 * block has been drawn at rest for at least two steps. So a block leaves the drawn set only after [HOLD] measurements in a
 * row found it dry, and measurements are taken steps apart; until then it is drawn like any other. Water cannot reach a
 * block that is not drawn: it moves under half a cell a step, and the ring is a whole block wide.
 *
 * Pure: tested on the JVM. Blocks are counted from the grid's origin, rows from the bottom, as the measurement's.
 */
class WaterCull(val bw: Int, val bh: Int, val block: Int = 16) {
    private val life = IntArray(bw * bh) { HOLD }

    /** Measurements taken since the last [reset]: until there is one, everything is drawn. */
    var readings = 0
        private set

    /** Nothing is known any more (the state was loaded, cleared or dried from outside): everything is drawn again. */
    fun reset() { life.fill(HOLD); readings = 0 }

    /**
     * A measurement: per block (max water depth mm, max dampness, max outflow, -), [bw] x [bh] of them. Returns what the
     * whole sheet holds: [RUNNING] (water deeper than [minDepth] stands somewhere: it can still move), [DAMP] (only a film
     * or dampness is left) or [DRY]. A block is live while it holds any water or dampness at all.
     */
    fun reading(a: FloatArray, minDepth: Float = 0f): Int {
        var water = false; var damp = false
        for (i in 0 until min(life.size, a.size / 4)) {
            val live = a[i * 4] > 0f || a[i * 4 + 1] > 0f
            if (a[i * 4] > minDepth) water = true
            if (live) damp = true
            if (live) life[i] = HOLD else if (life[i] > 0) life[i]--
        }
        readings++
        return if (water) RUNNING else if (damp) DAMP else DRY
    }

    /** How many blocks are live, and how many are live or in the ring of [grow] blocks round a live one (a measure of what a step must draw at the least). */
    fun counts(grow: Int = 1): IntArray {
        var live = 0; var ringed = 0
        for (by in 0 until bh) for (bx in 0 until bw) {
            if (life[by * bw + bx] > 0) { live++; ringed++; continue }
            var near = false
            var ny = max(0, by - grow)
            while (!near && ny <= min(bh - 1, by + grow)) {
                for (nx in max(0, bx - grow)..min(bw - 1, bx + grow)) if (life[ny * bw + nx] > 0) { near = true; break }
                ny++
            }
            if (near) ringed++
        }
        return intArrayOf(live, ringed)
    }

    /**
     * The blocks a step must draw, one byte each ([bw] x [bh], rows from the bottom as the grid's): 255 where the block is
     * live or within [grow] blocks of a live one, else 0. Null until something has been measured (everything is drawn).
     * With grow 1 this is exactly the set [parts] gathers into rectangles: the same blocks, without the rectangles' slack.
     */
    fun mask(grow: Int = 1): ByteArray? {
        if (readings == 0) return null
        // Along x, then along y: a square of side 2 x grow + 1 round every live block.
        val row = BooleanArray(bw * bh)
        for (by in 0 until bh) {
            var reach = -1   // the last column a live block seen so far still covers
            for (bx in 0 until bw) {
                if (life[by * bw + bx] > 0) {
                    for (x in max(max(0, bx - grow), reach + 1)..bx) row[by * bw + x] = true
                    reach = bx + grow
                } else if (bx <= reach) row[by * bw + bx] = true
            }
        }
        val out = ByteArray(bw * bh)
        for (bx in 0 until bw) {
            var reach = -1
            for (by in 0 until bh) {
                if (row[by * bw + bx]) {
                    for (y in max(max(0, by - grow), reach + 1)..by) out[y * bw + bx] = -1
                    reach = by + grow
                } else if (by <= reach) out[by * bw + bx] = -1
            }
        }
        return out
    }

    /** The brush put water on cells [x0], [y0] .. [x1], [y1]: those blocks are live at once. */
    fun wetted(x0: Float, y0: Float, x1: Float, y1: Float) {
        val bx0 = max(0, floor(x0 / block).toInt()); val by0 = max(0, floor(y0 / block).toInt())
        val bx1 = min(bw, ceil(x1 / block).toInt()); val by1 = min(bh, ceil(y1 / block).toInt())
        for (by in by0 until by1) for (bx in bx0 until bx1) life[by * bw + bx] = HOLD
    }

    /**
     * The rectangles (cells: x0, y0, x1, y1) a step over [cells] draws, or null for all of it: when nothing has been
     * measured yet, or when leaving the rest out would save under a sixth of the work.
     * [coarse]: the side of the squares the live blocks are gathered into, in blocks, so the rectangles are few.
     */
    fun parts(cells: IntArray, coarse: Int = 4): List<IntArray>? {
        if (readings == 0) return null
        val cw = (bw + coarse - 1) / coarse; val ch = (bh + coarse - 1) / coarse
        val on = BooleanArray(cw * ch)
        // A live block and the ring of blocks round it, gathered into the coarse squares they touch.
        for (by in 0 until bh) for (bx in 0 until bw) {
            if (life[by * bw + bx] <= 0) continue
            for (ny in max(0, by - 1)..min(bh - 1, by + 1)) for (nx in max(0, bx - 1)..min(bw - 1, bx + 1)) on[(ny / coarse) * cw + nx / coarse] = true
        }
        // Runs along each row of squares, then runs that repeat on the next row joined into one rectangle.
        val side = block * coarse
        val out = ArrayList<IntArray>()
        val open = HashMap<Long, IntArray>()   // (x0, x1) of a run -> the rectangle it is still growing
        for (cy in 0 until ch) {
            val seen = HashSet<Long>()
            var cx = 0
            while (cx < cw) {
                if (!on[cy * cw + cx]) { cx++; continue }
                val start = cx
                while (cx < cw && on[cy * cw + cx]) cx++
                val key = start.toLong() shl 32 or cx.toLong()
                seen.add(key)
                val r = open[key]
                if (r != null) r[3] = (cy + 1) * side
                else intArrayOf(start * side, cy * side, cx * side, (cy + 1) * side).also { open[key] = it; out.add(it) }
            }
            open.keys.retainAll(seen)
        }
        // Inside the step's own rectangle only.
        val clipped = out.mapNotNull { r ->
            val x0 = max(r[0], cells[0]); val y0 = max(r[1], cells[1]); val x1 = min(r[2], cells[2]); val y1 = min(r[3], cells[3])
            if (x1 > x0 && y1 > y0) intArrayOf(x0, y0, x1, y1) else null
        }
        val whole = (cells[2] - cells[0]).toLong() * (cells[3] - cells[1])
        val drawn = clipped.sumOf { (it[2] - it[0]).toLong() * (it[3] - it[1]) }
        return if (drawn * 6 > whole * 5) null else clipped
    }

    companion object {
        /** A block is drawn until this many measurements in a row found it dry. */
        const val HOLD = 3
        const val DRY = 0
        const val DAMP = 1
        const val RUNNING = 2
    }
}
