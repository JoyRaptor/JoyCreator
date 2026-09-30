package cc.joycreator.joybrush.core.paint

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * One footprint of the tuft engine (R9 §3B): what the brush touches the paper with at one moment. Positions and sizes are
 * document pixels.
 *
 * The shape is a TEARDROP (an uneven capsule): a circle of radius [ra] at the belly [ax],[ay] — where the pen is —
 * joined smoothly to a circle of radius [rb] at the trailing tip [bx],[by]. The same primitive draws all three kinds of
 * mark, so the GPU needs one program:
 *
 *  - [KIND_FOOTPRINT]: the brush itself, with bristle streaks, paper tooth and splay applied from [dry], [bias],
 *    [splay] and [arc];
 *  - [KIND_PLAIN]: a solid mark with none of that — a spatter droplet (a teardrop thrown along the flick) or a stray
 *    hair (a thin segment from where the hair was to where it is, both radii equal).
 *
 * `jb_tuft.frag` and [TuftMath] draw exactly this; any change to one is made to the other in the same commit.
 *
 * @property flow how much this footprint adds (0..1), as a dab's flow.
 * @property cap the ceiling the stroke builds to (the brush's opacity in WASH mode), as a dab's cap.
 * @property dry 0 = fully loaded (solid) … 1 = nearly empty (only a few bristles and the paper's peaks take ink).
 * @property bias −1..1 across the brush: positive puts the ink on the +normal side (the inside of a sweeping curve)
 *   and dries the other side (O10). The normal is the tip direction turned a quarter turn anticlockwise.
 * @property splay 0 = bristles together … 1 = spread apart: split gaps and a ragged edge (O11).
 * @property arc distance along the stroke at the belly, doc px — the streaks are laid in stroke space, so overlapping
 *   footprints agree about where each streak is.
 */
data class TuftStamp(
    val ax: Float,
    val ay: Float,
    val bx: Float,
    val by: Float,
    val ra: Float,
    val rb: Float,
    val flow: Float = 1f,
    val cap: Float = 1f,
    val dry: Float = 0f,
    val bias: Float = 0f,
    val splay: Float = 0f,
    val arc: Float = 0f,
    val kind: Int = KIND_FOOTPRINT,
    /** 0..1: how lightly the belly END of a laid-over brush grazes the paper — dry and scratchy there, solid at the tip. */
    val graze: Float = 0f,
) {
    companion object {
        const val KIND_FOOTPRINT = 0
        const val KIND_PLAIN = 1

        /** A stray hair: a plain segment that STUTTERS on the paper's tooth, as much as its [dry] says (O4). */
        const val KIND_HAIR = 2

        /** Floats per stamp in the GPU instance buffer: four vec4s. Must match `jb_tuft.vert`. */
        const val FLOATS = 16
    }
}

/**
 * What `jb_tuft.frag` takes once per STROKE (the per-footprint numbers ride on each [TuftStamp]).
 *
 * @property bristles how many streaks run across the brush's full width.
 * @property streakPx how far along the stroke one streak runs before it changes, document px.
 * @property tooth 0..1: how much the page's tooth breaks up the dry parts.
 * @property seed a per-stroke offset into the streak pattern.
 * @property action 0..1: bristle marks even in a loaded brush — broken edges (the Bristle action slider).
 * @property paperAsset the packaged grain picture the page's tooth is read from.
 * @property paperPitchPx document px per repeat of [paperAsset] — a property of the page, not of the brush.
 */
data class TuftShading(
    val bristles: Float,
    val streakPx: Float,
    val tooth: Float,
    val seed: Float,
    val action: Float,
    val paperAsset: String,
    val paperPitchPx: Float,
)

/**
 * The CPU twin of `jb_tuft.frag`'s shape, and the tile bookkeeping for tuft stamps. Pure maths: the phone's GPU draws the
 * same teardrop, and the tests check the shape here.
 */
object TuftMath {

    /** Anti-aliasing margin around a stamp's bounds, px (the shader's quad is this much bigger than the shape). */
    const val MARGIN_PX = 2f

    /**
     * Signed distance from (px, py) to the teardrop: negative inside. Inigo Quilez's uneven capsule, with the degenerate
     * case — one circle inside the other, which the formula cannot take — answered as the nearer of the two circles.
     */
    fun distance(px: Float, py: Float, s: TuftStamp): Float {
        val dx = s.bx - s.ax
        val dy = s.by - s.ay
        val h = dx * dx + dy * dy
        val b = s.ra - s.rb
        if (h <= b * b + 1e-6f) {
            val da = hypot(px - s.ax, py - s.ay) - s.ra
            val db = hypot(px - s.bx, py - s.by) - s.rb
            return min(da, db)
        }
        val qx0 = px - s.ax
        val qy0 = py - s.ay
        // q = (across, along) / h, across made positive: the shape is symmetric about its axis.
        val qx = abs(qx0 * dy - qy0 * dx) / h
        val qy = (qx0 * dx + qy0 * dy) / h
        val cx = sqrt(h - b * b)
        val cy = b
        val k = cx * qy - cy * qx
        val m = cx * qx + cy * qy
        val n = qx * qx + qy * qy
        return when {
            k < 0f -> sqrt(h * n) - s.ra
            k > cx -> sqrt(h * (n + 1f - 2f * qy)) - s.rb
            else -> m - s.ra
        }
    }

    /** Plain coverage of the teardrop at a pixel centre, 0..1, with the shader's one-pixel antialiased edge. */
    fun coverage(px: Float, py: Float, s: TuftStamp): Float = (0.5f - distance(px, py, s)).coerceIn(0f, 1f)

    /** The stamp's bounds with the antialiasing margin: left, top, right, bottom. */
    fun bounds(s: TuftStamp): FloatArray {
        val l = min(s.ax - s.ra, s.bx - s.rb) - MARGIN_PX
        val t = min(s.ay - s.ra, s.by - s.rb) - MARGIN_PX
        val r = max(s.ax + s.ra, s.bx + s.rb) + MARGIN_PX
        val btm = max(s.ay + s.ra, s.by + s.rb) + MARGIN_PX
        return floatArrayOf(l, t, r, btm)
    }

    /** Every tile a stamp can touch. */
    fun touchedBy(s: TuftStamp, size: Int = Tiles.SIZE): List<Long> {
        val bb = bounds(s)
        val x0 = floor(bb[0] / size).toInt()
        val x1 = floor(bb[2] / size).toInt()
        val y0 = floor(bb[1] / size).toInt()
        val y1 = floor(bb[3] / size).toInt()
        val out = ArrayList<Long>((x1 - x0 + 1) * (y1 - y0 + 1))
        for (ty in y0..y1) for (tx in x0..x1) out.add(Tiles.key(tx, ty))
        return out
    }

    /** Groups stamps by the tiles they touch, keeping each tile's stamps in stroke order. */
    fun bucket(stamps: List<TuftStamp>, size: Int = Tiles.SIZE): Map<Long, List<TuftStamp>> {
        val m = LinkedHashMap<Long, MutableList<TuftStamp>>()
        for (s in stamps) for (k in touchedBy(s, size)) m.getOrPut(k) { ArrayList() }.add(s)
        return m
    }

    private fun hypot(x: Float, y: Float): Float = sqrt(x * x + y * y)
}
