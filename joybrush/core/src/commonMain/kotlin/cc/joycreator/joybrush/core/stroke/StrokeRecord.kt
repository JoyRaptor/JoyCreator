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
 * Fields added after the original core fields are appended with defaults, so existing call sites (JB-0.01's contract, the
 * androidkit archive, JB-5.02's fixtures) still compiles unchanged.
 *
 * @property seq (record version 3, JB-5.20 D1) the line's place in its cel's time order: pixels and lines share one
 *   increasing sequence, and what was made later covers what was made earlier. Editing a line keeps it (every edit is a
 *   `copy`). **1 or more once a line is in a cel** and unique within the cel ([cc.joycreator.joybrush.core.vector.LineRules]
 *   says so in words); 0 means "not in a cel yet", which is what a hand-built record and a lone v1/v2 recording read
 *   as. A v1/v2 file read as a list ([StrokeCodec.decodeAll]) takes its seqs from file order, 1, 2, 3...
 * @property locked (v3, owner/UI) the line cannot be selected or edited until it is unlocked. Written only when true.
 * @property groupId (v3, owner/UI) lines with the same id move and select together; a group of one is legal. Written
 *   only when set; null = ungrouped. Never blank (validation says so).
 * @property capturedPhysicalSource (v4) an immutable capture-space pixel domain and exact integer
 *   placement. Null retains ordinary stroke semantics. A captured record keeps the FULL original
 *   recipe and samples; the domain clips the resulting source image, never the trajectory.
 * @property washFrame (v5) the projective frame mapping watercolour wash-local document pixels to
 *   document pixels. Null is the identity frame for old records, preserving their original look.
 *
 * The record stays a PLAIN VALUE: `widthScale` is not checked or clamped here. [StrokeEdit] and
 * [StrokeCodec] put ordinary widths in range. Captured records instead require valid original
 * recipe values through [CapturedStrokeRules], without repair. A record built by hand in a test
 * can hold anything and `copy` can put it anywhere.
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
    val seq: Long = 0L,
    val locked: Boolean = false,
    val groupId: String? = null,
    val capturedPhysicalSource: CapturedPhysicalSource? = null,
    val washFrame: StrokeWashFrame? = null,
) {
    init {
        require(samples.none { it.predicted }) { "predicted points are never recorded" }
    }
}
