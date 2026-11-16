package cc.joycreator.joybrush.core.chrome

import kotlin.math.cos
import kotlin.math.sin

/**
 * Which part of a board's frame strip is on screen, so only those frames get thumbnails (Codex audit
 * JB-ANIM-03, 11-15). Before, a zoomed animation board asked for every frame across its whole width:
 * past the thumbnail cache's size each new batch pushed out the last one and the strip kept drawing
 * thumbnails while nothing changed.
 *
 * The chrome view is turned by the canvas rotation around a pivot, so the screen's corners are turned
 * back into the view's own unturned frame and the x range they span is the window (a little wider at an
 * angle, never wider than the screen's diagonal), plus [prefetch] px each side.
 */
object FrameStripWindow {
    /**
     * [screen]: the screen in the chrome view's unturned coordinates. [pivotX], [pivotY], [rotation]
     * (radians, + clockwise): how the view is turned. Returns the part of the view's unturned frame the
     * screen shows, as a box, grown by [prefetch] on every side.
     */
    fun visible(screen: BoardChromeLayout.Rect, pivotX: Float, pivotY: Float, rotation: Float, prefetch: Float): BoardChromeLayout.Rect {
        val c = cos(rotation); val s = sin(rotation)
        var left = Float.POSITIVE_INFINITY; var right = Float.NEGATIVE_INFINITY
        var top = Float.POSITIVE_INFINITY; var bottom = Float.NEGATIVE_INFINITY
        for (x in floatArrayOf(screen.left, screen.right)) for (y in floatArrayOf(screen.top, screen.bottom)) {
            // A screen point back into the view's own frame: the inverse of View.rotation's clockwise turn.
            val dx = x - pivotX; val dy = y - pivotY
            val lx = pivotX + dx * c + dy * s
            val ly = pivotY - dx * s + dy * c
            left = minOf(left, lx); right = maxOf(right, lx); top = minOf(top, ly); bottom = maxOf(bottom, ly)
        }
        return BoardChromeLayout.Rect(left - prefetch, top - prefetch, right + prefetch, bottom + prefetch)
    }
}
