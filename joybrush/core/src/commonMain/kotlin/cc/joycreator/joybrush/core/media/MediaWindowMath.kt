package cc.joycreator.joybrush.core.media

import cc.joycreator.joybrush.core.paint.Tiles
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Where the media window sits (contract point 8, M5.3c): a tile-aligned square of [TILES] × [TILES] store tiles placed over
 * the stroke, never the whole canvas. Window px = document px − origin (media layers are raster: scale 1). Pure, so the
 * placement and the spread cap are tested without a GPU.
 */
object MediaWindowMath {
    /** Tiles across the window: 4 × 256 px = 1024 px, about 5 cm at 20 px/mm. */
    const val TILES = 4
    const val PX = TILES * Tiles.SIZE

    /** How close (window px) a stroke may come to the edge before the window moves to centre on the pen again. */
    const val MARGIN = Tiles.SIZE / 2

    /** The window's top-left tile for a pen at ([docX], [docY]): the tile under the pen in the middle. */
    fun placeFor(docX: Double, docY: Double): Pair<Int, Int> =
        Pair(floor(docX / Tiles.SIZE).toInt() - TILES / 2, floor(docY / Tiles.SIZE).toInt() - TILES / 2)

    /** Whether a document rectangle [x0, y0, x1, y1] stays [MARGIN] inside the window whose top-left tile is ([tx], [ty]). */
    fun holds(tx: Int, ty: Int, rect: DoubleArray): Boolean {
        val ox = tx * Tiles.SIZE.toDouble(); val oy = ty * Tiles.SIZE.toDouble()
        return rect[0] >= ox + MARGIN && rect[1] >= oy + MARGIN && rect[2] <= ox + PX - MARGIN && rect[3] <= oy + PX - MARGIN
    }

    /** The store tile keys a window-px rectangle touches, for a window at ([tx], [ty]). Empty for an empty rectangle. */
    fun keysIn(tx: Int, ty: Int, rect: FloatArray?): List<Long> {
        if (rect == null || rect[2] <= rect[0] || rect[3] <= rect[1]) return emptyList()
        val s = Tiles.SIZE.toFloat()
        val x0 = max(0, floor(rect[0] / s).toInt()); val x1 = min(TILES - 1, ceil(rect[2] / s).toInt() - 1)
        val y0 = max(0, floor(rect[1] / s).toInt()); val y1 = min(TILES - 1, ceil(rect[3] / s).toInt() - 1)
        val out = ArrayList<Long>()
        for (y in y0..y1) for (x in x0..x1) out += Tiles.key(tx + x, ty + y)
        return out
    }

    /** Every key of the window at ([tx], [ty]), row by row. */
    fun allKeys(tx: Int, ty: Int): List<Long> = keysIn(tx, ty, floatArrayOf(0f, 0f, PX.toFloat(), PX.toFloat()))
}

/**
 * How far water may spread on a tilted page (the lab's cap, and the Lead's memory rule): the simulated rectangle grows
 * downhill at most as fast as water can run, never further than [DOWNHILL_MM] past the painted area, and [SIDEWAYS_MM]
 * across the slope; and never past the window ([w] × [h]). A wet episode's undo step is bounded by this alone, since trim
 * always keeps the newest step.
 */
object WetSpread {
    const val DOWNHILL_MM = 40.0
    const val SIDEWAYS_MM = 10.0

    /** The next simulated rectangle (window px). Flat paper: unchanged. [cellMm] is millimetres per window px. */
    fun grow(rect: FloatArray, paint: FloatArray?, slopeX: Float, slopeY: Float, substeps: Int, cellMm: Double, w: Int, h: Int): FloatArray {
        if (paint == null || hypot(slopeX.toDouble(), slopeY.toDouble()) <= 1e-4) return rect.copyOf()
        val grow = ceil(substeps * 0.45).toFloat() + 2
        val far = ceil(DOWNHILL_MM / cellMm).toFloat(); val near = ceil(SIDEWAYS_MM / cellMm).toFloat()
        fun reach(d: Float) = if (d > 1e-4f) far else near
        return floatArrayOf(
            max(0f, max(paint[0] - reach(-slopeX), rect[0] - grow)),
            max(0f, max(paint[1] - reach(-slopeY), rect[1] - grow)),
            min(w.toFloat(), min(paint[2] + reach(slopeX), rect[2] + grow)),
            min(h.toFloat(), min(paint[3] + reach(slopeY), rect[3] + grow)),
        )
    }
}
