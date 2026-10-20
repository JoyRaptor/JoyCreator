package cc.joycreator.joybrush.core.media

import cc.joycreator.joybrush.core.paint.Tiles
import kotlin.math.hypot
import kotlin.math.min

/**
 * The safe zone of a media stroke under a symmetry guide (JB-2.12a's Phase E).
 *
 * ## ONE window, PINNED on the symmetry centre
 *
 * The media engine keeps ONE 1024 × 1024 window that FOLLOWS THE PEN. Under a symmetry guide the
 * window is PINNED on the symmetry centre `C` (the guide line's middle) for the whole stroke, so
 * that every copy's wet paint lives in ONE water field: the copies mix where they meet, and a
 * mirrored wash runs down on both sides. A window that moved would flush and reload (10–16 ms a
 * frame on a Note 9) and cut the water at its edge, so under a symmetry it does not move at all.
 *
 * ## The radius is COMPUTED, never assumed
 *
 * The window's origin is TILE-ALIGNED (the window reads whole tiles), so `C` lands somewhere
 * inside the window — never at its centre by accident. The safe radius is therefore computed from
 * the distances that matter, the ones from `C` to the window's edges:
 *
 *     R = min(C − left, right − C, C − top, bottom − C) − MARGIN − reach − pad
 *
 * with [MARGIN] the room the window keeps from its own edge, `reach` the brush's own reach
 * ([cc.joycreator.joybrush.core.brush.MediaSpec.reachDocPx]) and [PAD] the slack for the tile
 * rounding. A constant would be a lie: the window is placed by ROUNDING the centre
 * ([MediaWindowMath.placeForRounded]), which puts `C` between 384 and 640 px from every edge, and
 * the radius follows it from there.
 *
 * ## A negative radius is a REFUSAL, not a failure
 *
 * A brush whose reach is bigger than the usable radius has NO safe zone: there is nowhere the pen
 * may go, and every copy of every sample would fall off the window. The stroke is then refused in
 * words — [REFUSAL], the owner's sentence — and NOTHING is painted.
 *
 * Pure and total: rubbish in gives a number out, never an exception and never a NaN.
 */
object MediaSafety {

    /** The room the window keeps from its own edge, in document px (the window's own margin). */
    const val MARGIN = MediaWindowMath.MARGIN

    /** The slack for the window's tile rounding, in document px. */
    const val PAD = 4.0

    /** The refusal, in the owner's words (2026-10-19). */
    const val REFUSAL = "This is the largest area your device can safely paint with this brush."

    /** The pinned window's top-left tile for a symmetry centre at ([cx], [cy]). */
    fun pinnedTile(cx: Double, cy: Double): Pair<Int, Int> = MediaWindowMath.placeForRounded(cx, cy)

    /** The document x of a window's left edge when its top-left tile is [tx]. */
    fun originX(tx: Int): Double = tx * Tiles.SIZE.toDouble()

    /** The document y of a window's top edge when its top-left tile is [ty]. */
    fun originY(ty: Int): Double = ty * Tiles.SIZE.toDouble()

    /**
     * The distance from the symmetry centre ([cx], [cy]) to the window's NEAREST edge, in document
     * px: the room the window has for the pen and the brush's reach.
     */
    fun nearestEdge(tx: Int, ty: Int, cx: Double, cy: Double): Double {
        val ox = originX(tx)
        val oy = originY(ty)
        val px = MediaWindowMath.PX.toDouble()
        return min(min(cx - ox, ox + px - cx), min(cy - oy, oy + px - cy))
    }

    /**
     * The safe radius of a pinned window, in document px: how far the pen may be from the symmetry
     * centre and still keep every copy inside the window. Zero or less means there is NO safe
     * zone, and the stroke must be refused in words.
     */
    fun safeRadius(tx: Int, ty: Int, cx: Double, cy: Double, reach: Double): Double =
        nearestEdge(tx, ty, cx, cy) - MARGIN - reach - PAD

    /**
     * The safe radius for a symmetry centre ([cx], [cy]) and a brush that reaches [reach] document
     * px: the radius of the pin's own placement ([pinnedTile]).
     */
    fun safeRadiusFor(cx: Double, cy: Double, reach: Double): Double {
        val (tx, ty) = pinnedTile(cx, cy)
        return safeRadius(tx, ty, cx, cy, reach)
    }

    /**
     * Whether the point ([x], [y]) is inside the safe zone: the CIRCLE of radius [radius] about
     * the symmetry centre ([cx], [cy]).
     *
     * The circle, and not a square, because that is what the app draws (the dotted circle on the
     * overlay): a test that allowed the square's corners would allow strokes the person cannot
     * see are allowed. A radius of zero or less has no inside at all: there is no safe zone, and
     * the stroke is refused.
     *
     * Every copy of a sample keeps its distance from the centre — a mirror about the guide's line
     * (which runs through the centre) and a turn about the centre both fix that distance — so
     * testing the pen's own distance is the same as testing every copy's.
     */
    fun insideZone(cx: Double, cy: Double, radius: Double, x: Double, y: Double): Boolean {
        if (!(radius > 0.0)) return false
        if (!x.isFinite() || !y.isFinite()) return false
        if (!cx.isFinite() || !cy.isFinite()) return false
        return hypot(x - cx, y - cy) <= radius
    }
}
