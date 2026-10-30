package cc.joycreator.joybrush.core.media

import cc.joycreator.joybrush.core.paint.Tiles
import kotlin.math.max
import kotlin.math.min

/**
 * P2 of the atlas room (tasks/joybrush/specs/ATLAS_ROOM_20261023.md): a water step run block by block when the job's
 * tiles and their ring do not fit the working atlas. Each block runs EVERY stage of the step over rects that shrink toward
 * it (halo recompute), so no stage's output ever crosses from one block to the next:
 *
 *  - update over the block (its cells, inside the job's);
 *  - flux over the block grown by [UPDATE_READ] (the update reads its neighbours' flux and run within that);
 *  - the bead field (all three passes) over the flux rect at half resolution grown by [BEAD_CONE], inside the job's bead
 *    rect. Near the bead rect's edge both a global pass and a block's blur stale texels; the cone keeps that band
 *    ([BEAD_CONE] − the blur's 12 half cells) farther out than the flux ever reads, as the job's own margin does.
 *
 * Everything a block's passes read lies within one tile of the block: the block's own tiles hold the step's state, its
 * ring holds what is read around it (the a-half, which nothing writes during a step). Pure arithmetic: rects are
 * [x0, y0, x1, y1) integers in the stage's own grid (water cells; half cells for the bead).
 */
object WaterBlocks {
    /** How far the update reads round its pixel (jb_wet_update.frag: ±1 and the radius-3 ring), in water cells. */
    const val UPDATE_READ = 4

    /** Half cells round the flux rect where the bead field is made: the far drip taps (a drip gap and a millimetre) + the blur. */
    const val BEAD_CONE = 48

    /** One block: its own tiles (the step writes them), its ring (read only), and each stage's rect. */
    class Block(val own: List<Long>, val ring: List<Long>, val update: IntArray, val flux: IntArray, val bead: IntArray?)

    /**
     * The blocks of a job over [cells] (water cells, [wf] layer px each), with bead rect [hr] (half-resolution cells, null
     * when the paper is flat), [b] x [b] tiles a block, in row-major order. Tiles in [skip] are not simulated (walls, pools
     * set aside): never a block's own, though a ring may read them.
     */
    fun plan(cells: IntArray, hr: IntArray?, wf: Int, b: Int, skip: Set<Long> = emptySet(), side: Int = Tiles.SIZE): List<Block> {
        require(b >= 1 && wf >= 1 && side % wf == 0) { "a block is whole tiles" }
        if (cells[2] <= cells[0] || cells[3] <= cells[1]) return emptyList()
        val cs = side / wf   // a tile, in water cells
        val tx0 = (cells[0]).floorDiv(cs); val ty0 = (cells[1]).floorDiv(cs)
        val tx1 = (cells[2] - 1).floorDiv(cs); val ty1 = (cells[3] - 1).floorDiv(cs)
        val out = ArrayList<Block>()
        var by = ty0
        while (by <= ty1) {
            var bx = tx0
            while (bx <= tx1) {
                val own = ArrayList<Long>()
                for (y in by until min(by + b, ty1 + 1)) for (x in bx until min(bx + b, tx1 + 1)) {
                    val k = Tiles.key(x, y)
                    if (k !in skip) own += k
                }
                if (own.isNotEmpty()) {
                    // The block's cells: its tiles' cells, inside the job's.
                    val area = intArrayOf(max(cells[0], bx * cs), max(cells[1], by * cs),
                        min(cells[2], min(bx + b, tx1 + 1) * cs), min(cells[3], min(by + b, ty1 + 1) * cs))
                    val flux = intersect(grow(area, UPDATE_READ), cells)!!
                    val bead = hr?.let { h ->
                        // Water cells to half cells (layer px / 2): a cell is wf px, so wf / 2 half cells.
                        val half = intArrayOf((flux[0] * wf).floorDiv(2), (flux[1] * wf).floorDiv(2),
                            -(-(flux[2] * wf)).floorDiv(2), -(-(flux[3] * wf)).floorDiv(2))   // ceil, negatives too
                        intersect(grow(half, BEAD_CONE), h)
                    }
                    val ring = ArrayList<Long>()
                    for (y in by - 1..min(by + b, ty1 + 1)) for (x in bx - 1..min(bx + b, tx1 + 1)) {
                        val k = Tiles.key(x, y)
                        if (k !in own) ring += k
                    }
                    out += Block(own, ring, area, flux, bead)
                }
                bx += b
            }
            by += b
        }
        return out
    }

    fun grow(r: IntArray, d: Int) = intArrayOf(r[0] - d, r[1] - d, r[2] + d, r[3] + d)

    fun intersect(a: IntArray, b: IntArray): IntArray? {
        val x0 = max(a[0], b[0]); val y0 = max(a[1], b[1]); val x1 = min(a[2], b[2]); val y1 = min(a[3], b[3])
        return if (x1 > x0 && y1 > y0) intArrayOf(x0, y0, x1, y1) else null
    }
}
