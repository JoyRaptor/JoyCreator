package cc.joycreator.joybrush.android.select

import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import kotlin.math.abs

/**
 * Where Select's floating pieces (the row above the box, the bar at the bottom, the count pill) may sit without covering
 * Joy Brush's own controls (the tool strip, the top bar, the layer column, the round button). Found on the phone by the
 * Select bench (10-24): the row slid under the strip and the bar under the round button.
 */
internal object SelectPlacement {

    /**
     * The on-screen rectangles, in [root]'s px, of every control or panel under [root] except [mine] (and full-screen
     * layers such as the guides and the board overlay, which are not controls). A panel with a background counts whole;
     * otherwise its clickable or labelled children count one by one.
     */
    fun chromeRects(root: ViewGroup, mine: Set<View>, minPx: Float): List<RectF> {
        val out = ArrayList<RectF>()
        val at = IntArray(2)
        val origin = IntArray(2).also { root.getLocationInWindow(it) }
        fun walk(v: View, depth: Int) {
            if (v in mine || !v.isShown || v.alpha < 0.05f) return
            val fullScreen = v.width > root.width * 0.6f && v.height > root.height * 0.6f
            if (!fullScreen && v.width >= minPx && v.height >= minPx &&
                (v.background != null || v.isClickable || v.contentDescription != null)) {
                v.getLocationInWindow(at)
                val x = (at[0] - origin[0]).toFloat(); val y = (at[1] - origin[1]).toFloat()
                out += RectF(x, y, x + v.width, y + v.height)
                return
            }
            if (v is ViewGroup && depth < 5) for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1)
        }
        for (i in 0 until root.childCount) walk(root.getChildAt(i), 0)
        return out
    }

    /**
     * The left edge for a piece [w] wide on the band [top]..[bottom] nearest to [want], keeping [gap] from every rect in
     * [blocks] and from the screen's sides ([width]); null when no gap on that band is wide enough.
     */
    fun freeX(top: Float, bottom: Float, want: Float, w: Float, width: Float, gap: Float, blocks: List<RectF>): Float? {
        val spans = blocks.filter { it.bottom > top && it.top < bottom }.map { (it.left - gap) to (it.right + gap) }.sortedBy { it.first }
        val gaps = ArrayList<Pair<Float, Float>>()
        var start = gap
        val end = width - gap
        for ((a, b) in spans) { if (a > start) gaps += start to minOf(a, end); start = maxOf(start, b) }
        if (start < end) gaps += start to end
        return gaps.filter { it.second - it.first >= w }.map { (a, b) -> want.coerceIn(a, b - w) }.minByOrNull { abs(it - want) }
    }

    fun hits(r: RectF, blocks: List<RectF>): Boolean = blocks.any { RectF.intersects(it, r) }
}
