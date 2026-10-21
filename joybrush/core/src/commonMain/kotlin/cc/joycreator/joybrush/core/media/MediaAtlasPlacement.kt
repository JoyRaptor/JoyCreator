package cc.joycreator.joybrush.core.media

import cc.joycreator.joybrush.core.paint.Tiles
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Media Atlas v2, slice B2 (tasks/joybrush/specs/MEDIA_ATLAS_V2_20261016.md): which layer tiles a pass needs resident, and
 * the page table the shaders read (jb_media_atlas.glsl `u_atlasTable`, `u_atlasGrid`). Pure arithmetic over layer px and
 * tile keys ([Tiles.key]); the GL driver uploads [Table.data] as an R32I texture. Proved bit-exact against one big window
 * by tools/media_atlas_b2_check.js (free placement, scrambled slots, missing-ring control).
 *
 * THE RESIDENCY RULE. A tile must be resident while a pass WRITES it or READS it: everything within the pass's read reach
 * of what it writes. A non-resident tile reads as zero, which is only right when it truly holds nothing there.
 */
object MediaAtlasPlacement {
    /**
     * How far beyond a pass's rectangle each medium reads (and the bead writes), layer px at full resolution, at layer scale
     * 1 and the shipped constants. Measured from the shaders: wet = the bead field's margin (3 + 2·12 half cells = 54 px,
     * written) plus its blur (12 half cells = 24 px, read), which also covers the flux's drip taps (48 px) and steer ring;
     * paste = the brush's ±8 px surface taps and a bilinear; erase = its own pixel plus the cover's soft edge; the look =
     * the impasto shadow march (51 px) + gradient + bilinear. The dry apply's smear depends on the stick: [dryReach].
     */
    const val WET_REACH = 80
    /** Page-table marks of a tile taken out of the water (B3, D2): resident = slot + [WALL_BIT], not resident = [WALL_GONE]. */
    const val WALL_BIT = 1 shl 24
    const val WALL_GONE = -2
    const val PASTE_REACH = 12
    const val ERASE_REACH = 3
    const val LOOK_REACH = 56
    fun dryReach(smearPx: Double): Int = ceil(smearPx).toInt() + 4

    /**
     * What one 256² slot of an atlas costs, every store at its format: paint state 2 × (p0, p1, paper) RGBA32F = 96 B/px,
     * water 2 × (w0, w1) RGBA32F = 64, flux 2 × RGBA16F = 16, brush input 2 × RGBA16F = 16, paper bake RGBA16F 8, running
     * water RG16F 4, ground and look RGBA8 4 + 4, water bake R16F 2, fluid bake R8 1, bead 2 × RGBA16F at half size 4:
     * 219 B per layer px. Measured on the Note 9 (MediaLayerEngine.memoryReport): 14,352,384 bytes per slot, the same.
     */
    const val BYTES_PER_PX = 219L
    const val BYTES_PER_SLOT = BYTES_PER_PX * 256 * 256

    /**
     * The same with coarse water (COARSE_WATER_20261021: the water's fields at a quarter of the pixels, plus its mid-step
     * pair and terrain): paint 96, water 20 + run 1 + input 4 + mid-step 8 + terrain 2, bead 4, delta 8, bakes 11, ground
     * and look 8 = 162 B per layer px.
     */
    const val BYTES_PER_PX_COARSE = 162L
    const val BYTES_PER_SLOT_COARSE = BYTES_PER_PX_COARSE * 256 * 256

    /**
     * Slot scaling (the Lead, October 18): an atlas of clamp(floor(([availBytes] − [RESERVE_BYTES]) / [BYTES_PER_SLOT]), [MIN_SLOTS], [MAX_SLOTS]) slots,
     * rounded down to a near-square grid (columns, rows; rows at most two more than columns), sized once when the atlas is
     * made from what the phone says is free. A Note 9 with 2.76 GB available gets 6 × 6 = 36 (517 MB): about four
     * running pools of watercolour and a frame, so D2 is a last resort, not something a normal wash meets.
     */
    /**
     * The fewest slots an atlas gets (2026-10-20): one palette-knife step or one Wash frame needs about 20 tiles at once on
     * the Note 9, and an atlas of 16 (the window's size) refused them. 24 is 344 MB, about 20 % of what a Note 9 has free
     * with a drawing open (1.67 GB). Rows may be up to two more than columns, so 24 is 4 x 6 and not rounded down to 20.
     */
    const val MIN_SLOTS = 24

    /**
     * What an atlas leaves the rest of the phone (2026-10-20, was a 0.35 share of what is free): it takes what is free beyond
     * this. A tilted Wash on the Note 9 keeps about 40 tiles of water and what it reads at once; with the share, a day with
     * 1.39 GB free gave 5 x 6 slots and the wash was refused, a day with 1.88 GB gave 6 x 7 and it ran (same bench, same
     * hour). 700 MB is about half of what the Note 9 has free with a drawing open, more than twice Android's low-memory line.
     */
    const val RESERVE_BYTES = 700L shl 20

    /** The most slots (689 MB): with plenty free the share would reach 900 MB of graphics memory, more than any stroke needs. */
    const val MAX_SLOTS = 48

    fun slotGrid(availBytes: Long, bytesPerSlot: Long = BYTES_PER_SLOT): Pair<Int, Int> {
        // The most is [MAX_SLOTS] full-resolution slots' worth of memory (cheaper slots, more of them: 64 with coarse water).
        val most = MAX_SLOTS.toLong() * BYTES_PER_SLOT / bytesPerSlot
        val n = ((availBytes - RESERVE_BYTES) / bytesPerSlot).coerceIn(MIN_SLOTS.toLong(), most).toInt()
        val cols = kotlin.math.sqrt(n.toDouble()).toInt()
        return cols to minOf(cols + 2, n / cols)
    }

    /**
     * Media Atlas v2 B3, the admission rule: a frame needing [keys] fits when they and every tile that must stay ([held]:
     * held for this frame already, or by running water) number no more than [capacity]. Every other resident tile can be
     * written back to make room, so how many tiles a stroke has crossed never matters, only what one frame needs at once.
     */
    fun fits(capacity: Int, keys: Set<Long>, held: Set<Long>): Boolean = keys.size + held.count { it !in keys } <= capacity

    /** Every tile a layer-px rectangle [x0, y0, x1, y1) touches once dilated by [reach]: the tiles to make resident. */
    fun tiles(x0: Double, y0: Double, x1: Double, y1: Double, reach: Int): List<Long> {
        require(reach >= 0) { "a reach is a distance" }
        if (!(x1 > x0 && y1 > y0)) return emptyList()
        val side = Tiles.SIZE.toDouble()
        val tx0 = floor((x0 - reach) / side).toInt(); val ty0 = floor((y0 - reach) / side).toInt()
        // A half-open edge on a tile border does not touch the next tile.
        val tx1 = floor((ceil(x1 + reach) - 1) / side).toInt(); val ty1 = floor((ceil(y1 + reach) - 1) / side).toInt()
        require((tx1 - tx0 + 1).toLong() * (ty1 - ty0 + 1) <= MAX_TILES) { "a pass this large cannot be made resident" }
        return buildList { for (ty in ty0..ty1) for (tx in tx0..tx1) add(Tiles.key(tx, ty)) }
    }

    /** The most tiles one pass may ask for: more than the atlas can hold is refused before anything is drawn. */
    const val MAX_TILES = 4096

    /**
     * The page table for [resident] (tile key -> slot): the smallest grid of tiles that holds them all, row-major, −1 where
     * a tile is not resident. Empty residency = a 1 × 1 table holding −1 (a sampler must have a texture).
     */
    fun table(resident: Map<Long, Int>, walls: Set<Long> = emptySet()): Table {
        if (resident.isEmpty() && walls.isEmpty()) return Table(0, 0, 1, 1, intArrayOf(-1))
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var maxX = Int.MIN_VALUE; var maxY = Int.MIN_VALUE
        for (k in resident.keys + walls) {
            val x = Tiles.tx(k); val y = Tiles.ty(k)
            if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y
        }
        val w = maxX - minX + 1; val h = maxY - minY + 1
        require(w.toLong() * h <= MAX_TILES) { "resident tiles spread over too large a table" }
        val data = IntArray(w * h) { -1 }
        for ((k, slot) in resident) { require(slot >= 0) { "a slot index is not negative" }; data[(Tiles.ty(k) - minY) * w + (Tiles.tx(k) - minX)] = slot }
        // B3 (D2): tiles out of the water, marked for its passes (jb_media_atlas.glsl JB_WALL_BIT / JB_WALL_GONE).
        for (k in walls) {
            val i = (Tiles.ty(k) - minY) * w + (Tiles.tx(k) - minX)
            data[i] = if (data[i] >= 0) data[i] + WALL_BIT else WALL_GONE
        }
        return Table(minX, minY, w, h, data)
    }

    /** `u_atlasGrid` = (x, y, w, h); [data] row-major from (x, y), the R32I texture's contents. */
    class Table(val x: Int, val y: Int, val w: Int, val h: Int, val data: IntArray) {
        /** Tile ([tx], [ty])'s slot, or −1 (not resident, outside the grid included); a wall's mark taken off. */
        fun slotOf(tx: Int, ty: Int): Int {
            if (tx < x || ty < y || tx >= x + w || ty >= y + h) return -1
            val v = data[(ty - y) * w + (tx - x)]
            return if (v >= WALL_BIT) v - WALL_BIT else if (v < 0) -1 else v
        }
    }
}
