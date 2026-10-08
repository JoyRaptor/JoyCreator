package cc.joycreator.joybrush.core.vector

import kotlin.math.min

// JB-5.02 — picking the line the user meant. Every tolerance here is in SCREEN px, because that is
// the only unit a finger can aim at: the same slop is a forgiving grab zoomed out and a hair zoomed
// in. The geometry below is in doc px, so it divides by the zoom once, on the way in.

/** How far outside its own ink a tap may be and still pick a line, in screen px. */
private const val TAP_SLOP_SCREEN_PX = 12.0

/** Scores this close together (screen px) are the same place, and the more recent line wins. */
private const val TIE_SCREEN_PX = 1.0

/** Two taps this close in time are one double-tap, and the second one cycles. */
private const val CYCLE_WINDOW_MS = 400.0

/** Two taps this close in space (screen px) are one double-tap. */
private const val CYCLE_REACH_SCREEN_PX = 6.0

/**
 * JB-5.02 — picking the line a tap meant, in dense line work.
 *
 * A line is a RIBBON with a half-width, never a centreline: the ink of a line 3 px above another
 * one is 3 px of air away, and a tap that lands on the fat one is not "near the thin one" just
 * because its centreline happens to be closer. So a line is a candidate when the tap is within its
 * own half-width — interpolated at the nearest point, exactly as [pointAt] gives it —
 * plus [TAP_SLOP_SCREEN_PX] of screen slop, and it is ranked by `distance − halfWidth`: negative
 * means the tap landed on the ink, and the more negative it is the more the user hit the fat part
 * of the line rather than its edge.
 *
 * **Ranking.** At each position, find the closest remaining score, then choose the most
 * recent remaining line within one screen pixel of it. Recompute that window after removing
 * the winner: fixed clusters can put an older line before a newer tied line at a boundary.
 * This extraction defines one deterministic total order without a non-transitive comparator.
 *
 * **Cycling.** Nearby rapid taps advance a position in that order, wrapping. When the ordered
 * candidate geometry changes, start fresh. Ids need not be unique; no id lookup drives the cycle.
 * A miss forgets the cycle.
 *
 * This holds the last pick, so it belongs to one UI gesture stream and is not thread safe.
 */
class StrokePicker {

    private var lastOrder: List<InkLine> = emptyList()
    private var lastPosition = -1
    private var lastX = 0.0
    private var lastY = 0.0
    private var lastTimeMs = 0.0

    /**
     * The id of the line a tap at ([x], [y]) in doc px means, or null when it means nothing.
     *
     * @param lines in drawing order (last = most recent, drawn on top).
     * @param timeMs the tap's time, on any monotonic clock, in ms since an arbitrary origin.
     * @param screenPerDoc zoom; tolerances are in SCREEN px. Anything not a positive finite zoom
     *   (a divide-by-zero guard, a NaN from a degenerate viewport) is read as 1:1, where the
     *   tolerances are the same numbers at every zoom rather than infinite or nothing.
     */
    fun pick(
        lines: List<InkLine>,
        x: Double,
        y: Double,
        timeMs: Double,
        screenPerDoc: Double,
    ): String? {
        val zoom = if (screenPerDoc.isFinite() && screenPerDoc > 0.0) screenPerDoc else 1.0
        val ranking = rankingOf(lines, x, y, TAP_SLOP_SCREEN_PX / zoom, TIE_SCREEN_PX / zoom)
        if (ranking.isEmpty()) {
            forgetLastPick()
            return null
        }
        val order = ranking.map { lines[it.index] }
        val sameOrder = order.size == lastOrder.size && order.indices.all { order[it] == lastOrder[it] }
        val position = if (sameOrder && continuesCycle(x, y, timeMs, zoom))
            (lastPosition + 1) % ranking.size else 0
        val picked = ranking[position].id
        lastOrder = order.map { it.copy(xs = it.xs.copyOf(), ys = it.ys.copyOf(), halfWidths = it.halfWidths.copyOf()) }
        lastPosition = position
        lastX = x
        lastY = y
        lastTimeMs = timeMs
        return picked
    }

    /**
     * The ids under a tap, in the order they would be picked by repeated taps: closest remaining window first,
     * with recency deciding within each window.
     *
     * A line's score is `distance − halfWidth`, so the candidate test `score <= slop` needs no test
     * of its own: the candidates are exactly the best-scoring lines, and the slop decides how many
     * of them there are, never which one wins.
     */
    private fun rankingOf(
        lines: List<InkLine>,
        x: Double,
        y: Double,
        slop: Double,
        tie: Double,
    ): List<Candidate> {
        if (lines.isEmpty()) return emptyList()
        val scores = ArrayList<Candidate>(lines.size)
        for (i in lines.indices) {
            val line = lines[i]
            val score = scoreOf(line, x, y)
            if (score <= slop) scores.add(Candidate(i, line.id, score))
        }
        if (scores.isEmpty()) return emptyList()
        val remaining = scores.sortedBy { it.score }.toMutableList()
        val out = ArrayList<Candidate>(scores.size)
        while (remaining.isNotEmpty()) {
            val best = remaining[0].score
            var winner = 0
            for (k in 1 until remaining.size) {
                if (remaining[k].score - best > tie) break
                if (remaining[k].index > remaining[winner].index) winner = k
            }
            out.add(remaining.removeAt(winner))
        }
        return out
    }

    /**
     * How far outside its own ink the point ([px], [py]) is from [line], in doc px: negative inside
     * the ribbon, zero on the centreline, positive in the air beyond the edge.
     *
     * The nearest point of a line is the nearest point of one of its segments, and each segment's
     * is the same clamped projection [pointSegmentDistance] uses, carried one step further to
     * keep the parameter so the half-width can be interpolated at that point rather than at a
     * vertex. A line of one point is a dab: its only segment is a dot, at parameter 0.
     */
    private fun scoreOf(line: InkLine, px: Double, py: Double): Double {
        val n = line.pointCount
        val segs = segmentCountOf(n)
        var bestD = Double.MAX_VALUE
        var bestParam = 0.0
        for (i in 0 until segs) {
            val i0 = min(i, n - 1)
            val i1 = min(i + 1, n - 1)
            val ax = line.xs[i0]
            val ay = line.ys[i0]
            val dx = line.xs[i1] - ax
            val dy = line.ys[i1] - ay
            val len2 = dx * dx + dy * dy
            var t = 0.0
            if (len2 > 0.0) {
                t = ((px - ax) * dx + (py - ay) * dy) / len2
                if (t < 0.0) t = 0.0 else if (t > 1.0) t = 1.0
            }
            val d = len(px - (ax + t * dx), py - (ay + t * dy))
            // A NaN point never wins, so a line with broken coordinates simply has no candidate.
            if (d < bestD) {
                bestD = d
                bestParam = i + t
            }
        }
        return bestD - line.pointAt(bestParam).halfWidth
    }

    /** True when this tap is close enough, in time and space, to continue the last cycle. */
    private fun continuesCycle(x: Double, y: Double, timeMs: Double, zoom: Double): Boolean {
        if (lastPosition < 0) return false
        val dt = timeMs - lastTimeMs
        // A clock that went backwards (a new document reusing this picker) or that is not a number
        // at all is not a second tap: both start a new cycle rather than a nonsense one.
        if (dt.isNaN() || dt < 0.0 || dt >= CYCLE_WINDOW_MS) return false
        val reach = CYCLE_REACH_SCREEN_PX / zoom
        val dx = x - lastX
        val dy = y - lastY
        return dx * dx + dy * dy <= reach * reach
    }

    private fun forgetLastPick() {
        lastOrder = emptyList()
        lastPosition = -1
        lastX = 0.0
        lastY = 0.0
        lastTimeMs = 0.0
    }

    /** A line under consideration: where it was drawn ([index], 0 = first drawn) and its score. */
    private class Candidate(val index: Int, val id: String, val score: Double)
}
