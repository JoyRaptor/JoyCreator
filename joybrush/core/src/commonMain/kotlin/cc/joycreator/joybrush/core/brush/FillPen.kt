package cc.joycreator.joybrush.core.brush

import cc.joycreator.joybrush.core.input.PenSample
import cc.joycreator.joybrush.core.input.StrokeSmoother
import cc.joycreator.joybrush.core.shape.Pt

/**
 * The fill pen: a BRUSH whose stroke is a filled shape rather than a row of dabs (JB-1.08a,
 * LEAD_RULINGS R21 — the fill pen is a brush, not a tool).
 *
 * A stroke is a recording ([cc.joycreator.joybrush.core.stroke.StrokeRecord]) either way, which is
 * the whole point: the same recording redraws as this pen or as a pencil, so re-brushing works in
 * both directions and smoothing, reshape and nudge are the ordinary ink ones.
 *
 * This object is the MATHS only — where the shape is. Rasterising it (a non-zero winding fill, so
 * a loop drawn twice is still solid) is JB-2.05a; painting it on an ink layer is the Lead's JB-5.01.
 *
 * Pressure, tilt, azimuth and barrel are recorded in the stroke and are deliberately NOT read here:
 * a fill has no width to press on, and they are still in the recording for the day the stroke is
 * re-brushed to a pencil.
 */
object FillPen {

    /**
     * The closed outline a fill-pen stroke fills, in document px: the stroke's samples smoothed
     * exactly as a stamp stroke's would be ([StrokeSmoother.smoothAll]), then closed by a straight
     * segment from the last point back to the first. The polygon is filled NON-ZERO (JB-2.05a), so a
     * loop drawn twice is still solid. The closing segment is IMPLICIT — the list is not repeated at
     * the end, and a rasteriser closes it.
     *
     * Fewer than 3 usable points — before smoothing, and again after it — is nothing at all: a
     * polygon of fewer than three points encloses no area, so an empty list is drawn as no fill and
     * a degenerate one as no fill too. (Orchestrator rule, JB-1.08a: "fewer than 3 points" is
     * counted on BOTH sides, so a two-sample flick down a 50 px line is empty rather than a
     * 51-point sliver of zero area. See the Questions in JB-1.08a.)
     *
     * Consecutive duplicate points are dropped on both sides: a pen that stands still, or reports
     * the same reading twice, must not turn into a zero-length edge the fill rule has to think about.
     */
    fun outline(samples: List<PenSample>, smoothing: Float, screenPerDoc: Float): List<Pt> {
        val usable = withoutConsecutiveDuplicates(samples)
        if (usable.size < MIN_CORNERS) return emptyList()
        val centre = StrokeSmoother.smoothAll(usable, smoothing, screenPerDoc)
        val out = ArrayList<Pt>(centre.size)
        for (s in centre) {
            val p = Pt(s.x.toDouble(), s.y.toDouble())
            if (out.isEmpty() || out[out.size - 1] != p) out.add(p)
        }
        return if (out.size < MIN_CORNERS) emptyList() else out
    }

    /** A polygon needs three corners before it can enclose anything. */
    const val MIN_CORNERS = 3

    private fun withoutConsecutiveDuplicates(samples: List<PenSample>): List<PenSample> {
        if (samples.size < 2) return samples
        val out = ArrayList<PenSample>(samples.size)
        for (s in samples) {
            val last = out.lastOrNull()
            if (last == null || last.x != s.x || last.y != s.y) out.add(s)
        }
        return out
    }
}
