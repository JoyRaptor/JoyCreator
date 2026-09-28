package cc.joycreator.joybrush.core.stroke

import cc.joycreator.joybrush.core.input.PenSample

/**
 * A recorded stroke: exactly what the pen did, plus everything needed to redraw it identically.
 *
 * This is a CONTRACT (blueprint §2). It is what an Ink layer stores as its truth, what undo can
 * replay, what a timelapse plays back, and what lets a stroke be re-smoothed or given another brush
 * after the fact. It stores the RAW samples (calibrated, never smoothed, never predicted), because
 * smoothing can always be re-applied and can never be taken off.
 *
 * @property brushId the brush preset used (its id in the brush library or document).
 * @property seed random seed for every random choice the brush makes (scatter, jitter, grain
 *   offset), so a replay draws the same marks.
 * @property smoothing the smoothing slider value when drawn (0..1).
 * @property screenPerDoc the zoom when drawn — smoothing is measured on screen, so replay needs it.
 */
data class StrokeRecord(
    val id: String,
    val brushId: String,
    val seed: Long,
    val smoothing: Float,
    val screenPerDoc: Float,
    val samples: List<PenSample>,
) {
    init {
        require(samples.none { it.predicted }) { "predicted points are never recorded" }
    }
}
