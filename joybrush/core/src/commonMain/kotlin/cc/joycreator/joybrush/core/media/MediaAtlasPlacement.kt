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
    const val PASTE_REACH = 12
    const val ERASE_REACH = 3
    const val LOOK_REACH = 56
    fun dryReach(smearPx: Double): Int = ceil(smearPx).toInt() + 4

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
    fun table(resident: Map<Long, Int>): Table {
        if (resident.isEmpty()) return Table(0, 0, 1, 1, intArrayOf(-1))
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var maxX = Int.MIN_VALUE; var maxY = Int.MIN_VALUE
        for (k in resident.keys) {
            val x = Tiles.tx(k); val y = Tiles.ty(k)
            if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y
        }
        val w = maxX - minX + 1; val h = maxY - minY + 1
        require(w.toLong() * h <= MAX_TILES) { "resident tiles spread over too large a table" }
        val data = IntArray(w * h) { -1 }
        for ((k, slot) in resident) { require(slot >= 0) { "a slot index is not negative" }; data[(Tiles.ty(k) - minY) * w + (Tiles.tx(k) - minX)] = slot }
        return Table(minX, minY, w, h, data)
    }

    /** `u_atlasGrid` = (x, y, w, h); [data] row-major from (x, y), the R32I texture's contents. */
    class Table(val x: Int, val y: Int, val w: Int, val h: Int, val data: IntArray) {
        /** What the shader reads for tile ([tx], [ty]): its slot, or −1 (outside the grid included). */
        fun slotOf(tx: Int, ty: Int): Int = if (tx < x || ty < y || tx >= x + w || ty >= y + h) -1 else data[(ty - y) * w + (tx - x)]
    }
}
