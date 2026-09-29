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
 *   offset), so a replay draws the same marks. LEAD_RULINGS R13: this must be the EXACT seed the
 *   `BrushDabber` used, because a stroke whose seed was made up is a stroke that cannot be rebuilt.
 * @property smoothing the smoothing slider value when drawn (0..1).
 * @property screenPerDoc the zoom when drawn — smoothing is measured on screen, so replay needs it.
 * @property colorArgb straight (NOT premultiplied) sRGB, added in record version 2 (JB-5.03a). A
 *   stroke carries its own colour so it can be recoloured afterwards without touching the brush.
 * @property widthScale a multiplier on the brush's size for THIS line, 0.05..20 by convention
 *   (JB-5.03a). It exists because re-weighting must be able to thicken a line even where the pen was
 *   already at full pressure — pressure alone cannot make a maximum-pressure line any fatter.
 *
 * Both new fields are last, with defaults, so every existing call site (JB-0.01's contract, the
 * androidkit archive, JB-5.02's fixtures) still compiles unchanged.
 *
 * The record stays a PLAIN VALUE: `widthScale` is not checked or clamped here. [StrokeEdit] and
 * [StrokeCodec] both put it in range, so a record read off disk or come out of an edit is always
 * usable, but a record built by hand in a test can hold anything and `copy` can put it anywhere.
 * Clamping in `init` would make the type hostile to the plain-value rule the rest of the engine uses
 * (cf. LEAD_RULINGS R1/R10: a broken number is read as a safe one at the point that reads it).
 */
data class StrokeRecord(
    val id: String,
    val brushId: String,
    val seed: Long,
    val smoothing: Float,
    val screenPerDoc: Float,
    val samples: List<PenSample>,
    val colorArgb: Int = 0xFF000000.toInt(),
    val widthScale: Float = 1f,
) {
    init {
        require(samples.none { it.predicted }) { "predicted points are never recorded" }
    }
}
