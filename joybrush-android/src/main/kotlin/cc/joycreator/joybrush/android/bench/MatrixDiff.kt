package cc.joycreator.joybrush.android.bench

import cc.joycreator.joybrush.core.doc.RectPx
import cc.joycreator.joybrush.core.paint.Tiles

/**
 * The pixel arithmetic and the verdict of the device matrix ([MatrixBench]); plain JVM so a unit test can pin it.
 *
 * A plane is the tiles of one layer plane by engine tile key, each tile premultiplied RGBA8 (4 bytes a pixel, row 0 on
 * top). A tile one side lacks is blank, so "never allocated" and "allocated but empty" are the same picture.
 */
object MatrixDiff {

    /** What changed between two planes. [insidePx]/[outsidePx] split [px] by [zone]; [stripPx] is the part of [insidePx] in the zone's left [strip] columns. */
    data class Counts(val px: Int, val tiles: Int, val insidePx: Int, val outsidePx: Int, val stripPx: Int)

    fun count(before: Map<Long, ByteArray>, after: Map<Long, ByteArray>, zone: RectPx? = null, strip: Int = 0): Counts {
        var px = 0; var tiles = 0; var inside = 0; var strips = 0
        val size = Tiles.SIZE
        for (key in before.keys + after.keys) {
            val a = before[key]; val b = after[key]
            if (a == null && b == null) continue
            val n = (a ?: b)!!.size
            val x = a ?: ByteArray(n); val y = b ?: ByteArray(n)
            if (java.util.Arrays.equals(x, y)) continue
            val ox = Tiles.tx(key) * size; val oy = Tiles.ty(key) * size
            var here = 0
            for (i in 0 until n / 4) {
                val o = i * 4
                if (x[o] == y[o] && x[o + 1] == y[o + 1] && x[o + 2] == y[o + 2] && x[o + 3] == y[o + 3]) continue
                here++
                if (zone != null) {
                    val dx = ox + i % size; val dy = oy + i / size
                    if (dx >= zone.x && dx < zone.x + zone.w && dy >= zone.y && dy < zone.y + zone.h) {
                        inside++
                        if (dx < zone.x + strip) strips++
                    }
                }
            }
            if (here > 0) { px += here; tiles++ }
        }
        return Counts(px, tiles, inside, px - inside, strips)
    }

    /** True when [a] and [b] are the same picture, byte for byte. */
    fun same(a: Map<Long, ByteArray>, b: Map<Long, ByteArray>): Boolean = count(a, b).px == 0

    /**
     * One word for a cell. [changedPx] is what the stroke did to the layer as drawn; [undoSteps] how many history steps it
     * left; [undoExact] whether one undo gave the before-picture back (null: nothing to undo). Anything but "works" and
     * "refused" is a failure to flag; "refused" is a valid outcome only because the person is told in words.
     */
    fun verdict(error: String?, refused: Boolean, changedPx: Int, undoSteps: Int, undoExact: Boolean?, board: String,
                wrapPx: Int, framePx: Int, sharedInZonePx: Int): String = when {
        error != null -> "error"
        changedPx == 0 -> if (refused) "refused" else "noop"
        undoSteps <= 0 -> "undo-missing"
        undoExact == false -> "undo-bad"
        board == "tile" && wrapPx == 0 -> "nowrap"
        board == "animation" && (sharedInZonePx > 0 || framePx == 0) -> "scope"
        else -> "works"
    }
}
