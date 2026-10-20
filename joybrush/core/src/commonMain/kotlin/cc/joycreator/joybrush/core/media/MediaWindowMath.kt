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

    /**
     * The widest (and tallest) part of a frame a window placed by [placeForRect] always holds: its middle is within half a
     * tile of the window's, so [SPAN] / 2 fits on every side inside the margin. A frame wider than this is drawn in parts,
     * its dabs in order, the window moving to each (the window patch, 2026-10-19: dabs outside the window were dropped).
     */
    const val SPAN = PX - 2 * MARGIN - Tiles.SIZE

    /** The window's top-left tile that puts document rectangle [rect]'s middle nearest the window's middle. */
    fun placeForRect(rect: DoubleArray): Pair<Int, Int> =
        Pair(kotlin.math.round((rect[0] + rect[2]) / 2 / Tiles.SIZE - TILES / 2.0).toInt(),
            kotlin.math.round((rect[1] + rect[3]) / 2 / Tiles.SIZE - TILES / 2.0).toInt())

    /** Whether a document rectangle lies wholly on the window at ([tx], [ty]) (no margin: nothing of it would be clipped). */
    fun within(tx: Int, ty: Int, rect: DoubleArray): Boolean {
        val ox = tx * Tiles.SIZE.toDouble(); val oy = ty * Tiles.SIZE.toDouble()
        return rect[0] >= ox && rect[1] >= oy && rect[2] <= ox + PX && rect[3] <= oy + PX
    }

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

    /** How near (window px) the running water's rectangle may come to the window's edge before it counts as pressing on it. */
    const val WATER_EDGE = 16f

    private const val WET_WEIGHT = 10
    private const val FRONTIER_WEIGHT = 9

    /**
     * "Follow the water" (owner's seams, 2026-10-19): where the window should sit while water runs with no pen down, or null
     * to leave it. The water only moves inside the window, so a wash or a drip that reaches its edge, or water the window left
     * behind, stops flat there. The better window holds more of the layer's water ([wetKeys]: the tiles with a water store),
     * and has room on the edge the running water ([rect], window px) presses against: one tile row or column beyond that
     * edge counts as nearly as much as a wet tile. A move has to beat the window as it is, so a window already holding all
     * of it stays put. Pure, so it is tested without a GPU.
     */
    fun waterPlacement(wetKeys: Collection<Long>, rect: FloatArray, tx: Int, ty: Int): Pair<Int, Int>? {
        if (rect[2] <= rect[0] || rect[3] <= rect[1]) return null
        // Only water within a tile of the window counts: old frozen water elsewhere on the layer must not pull the window off the
        // water that is running, and a move is one tile at a time (the frame rate of a drip is slower than that).
        val wet = HashSet<Long>(); for (k in wetKeys) if (Tiles.tx(k) in tx - 1..tx + TILES && Tiles.ty(k) in ty - 1..ty + TILES) wet += k
        val frontier = HashSet<Long>()
        val x0 = max(0, floor(rect[0] / Tiles.SIZE).toInt()); val x1 = min(TILES - 1, ceil(rect[2] / Tiles.SIZE).toInt() - 1)
        val y0 = max(0, floor(rect[1] / Tiles.SIZE).toInt()); val y1 = min(TILES - 1, ceil(rect[3] / Tiles.SIZE).toInt() - 1)
        val edge = WATER_EDGE
        if (rect[0] <= edge) for (y in y0..y1) frontier += Tiles.key(tx - 1, ty + y)
        if (rect[2] >= PX - edge) for (y in y0..y1) frontier += Tiles.key(tx + TILES, ty + y)
        if (rect[1] <= edge) for (x in x0..x1) frontier += Tiles.key(tx + x, ty - 1)
        if (rect[3] >= PX - edge) for (x in x0..x1) frontier += Tiles.key(tx + x, ty + TILES)
        if (frontier.isEmpty() && wet.none { k -> Tiles.tx(k) < tx || Tiles.tx(k) >= tx + TILES || Tiles.ty(k) < ty || Tiles.ty(k) >= ty + TILES }) return null
        fun score(nx: Int, ny: Int): Int {
            var s = 0
            for (k in wet) if (Tiles.tx(k) in nx until nx + TILES && Tiles.ty(k) in ny until ny + TILES) s += WET_WEIGHT
            for (k in frontier) if (k !in wet && Tiles.tx(k) in nx until nx + TILES && Tiles.ty(k) in ny until ny + TILES) s += FRONTIER_WEIGHT
            return s
        }
        var best: Pair<Int, Int>? = null
        var bestScore = score(tx, ty)
        var bestMove = 0
        for (dy in -1..1) for (dx in -1..1) {
            if (dx == 0 && dy == 0) continue
            val s = score(tx + dx, ty + dy)
            val move = kotlin.math.abs(dx) + kotlin.math.abs(dy)
            if (s > bestScore || (s == bestScore && best != null && move < bestMove)) { best = Pair(tx + dx, ty + dy); bestScore = s; bestMove = move }
        }
        return best
    }
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
        // Every edge only ever moves OUT within a wet episode. Turning the phone flips which side is "downhill"; a rect
        // recomputed from the new slope alone shrank by up to 3 cm in one frame and froze every drip beyond it (owner:
        // "the water only changes direction at the next stroke"). A steady slope gives exactly what it always did.
        return floatArrayOf(
            min(rect[0], max(0f, max(paint[0] - reach(-slopeX), rect[0] - grow))),
            min(rect[1], max(0f, max(paint[1] - reach(-slopeY), rect[1] - grow))),
            max(rect[2], min(w.toFloat(), min(paint[2] + reach(slopeX), rect[2] + grow))),
            max(rect[3], min(h.toFloat(), min(paint[3] + reach(slopeY), rect[3] + grow))),
        )
    }
}
