package cc.joycreator.joybrush.core.brush

import cc.joycreator.joybrush.core.paint.Dab
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The pixel push of JB-1.06 ("nudge" on the board; R17: liquify is built from this later). Pure geometry: how far
 * one push dab moves the pixels under it, and how strongly. The canvas read that applies it is the engine's.
 *
 * Not to be confused with `Nudge` (JB-2.16a), which moves a SELECTION by one screen pixel. A person meets
 * this one as the brush named "Push".
 */
object Push {

    /** A dab may not displace by more than half its own radius: a larger one tears the image (LEAD_RULINGS R47). */
    const val MAX_SHIFT = 0.5f

    /**
     * The displacement one push dab applies, in DOCUMENT px, and the flow that goes with it, written into [out]
     * as `{dx, dy, flow}` (size >= 3). ALONG the stroke only (the dab's angle) and never across it, by
     * `min(amount, MAX_SHIFT) x radius`; `flow = min(amount, 1)`, so a push that moves far also moves strongly.
     * `amount <= 0` (or NaN) moves nothing.
     *
     * The angle is the stroke direction only for a brush whose tip turns with the stroke (`followDirection`);
     * for a round brush without it the angle is 0 and a push only pushes to the right. That is a real
     * constraint on the shipped Push preset, not an accident.
     */
    fun offsetFor(dab: Dab, amount: Float, out: FloatArray) {
        require(out.size >= 3) { "Push.offsetFor needs an output array of at least 3 floats, got ${out.size}" }
        if (!(amount > 0f) || !dab.radius.isFinite() || !dab.angle.isFinite()) {
            out[0] = 0f; out[1] = 0f; out[2] = 0f
            return
        }
        val shift = min(amount, MAX_SHIFT) * dab.radius
        out[0] = cos(dab.angle) * shift
        out[1] = sin(dab.angle) * shift
        out[2] = min(amount, 1f)
    }
}
