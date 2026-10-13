package cc.joycreator.joybrush.androidkit.select

import cc.joycreator.joybrush.core.select.Homography
import cc.joycreator.joybrush.core.select.SelectionMask
import cc.joycreator.joybrush.core.stroke.StrokeRecord
import cc.joycreator.joybrush.core.stroke.LineAddress
import cc.joycreator.joybrush.core.stroke.AddressedStroke
import cc.joycreator.joybrush.core.vector.InkLine

/**
 * JB-5.20e — everything the Select tool asks of the canvas and the engine. The UI lane owns this interface; the Lead
 * backs it from `JbCanvasView` / `GlPaintEngine` (spec §H: H4 [paintClip], H5 [liftLines] / [liftPixels] / [dimRest],
 * H10 [adjustColour]). Until it is backed, a fake drives the tests. Main thread unless a method says otherwise.
 *
 * Every edit here is ONE undo step on the engine's own history (owner: one press, one step), and an undo or redo goes
 * through that same history whatever is lifted (Lead, 2026-10-11).
 */
interface SelectionHost {

    /**
     * One line of a cel (JB-5.20a): its record, the picker's view of its geometry (from `InkReplay`, as
     * `InkEditSession.lines` builds it), and the two fields 5.20a adds for this lane.
     */
    class Line(
        val address: LineAddress,
        val geometry: InkLine,
        val record: StrokeRecord,
        val locked: Boolean,
        val groupId: String?,
    ) {
        init {
            require(address.recordId == record.id && address.recordId == geometry.id) {
                "Selection geometry and record must match their address"
            }
        }
        val id: String get() = address.recordId
        val layerId: String get() = address.layerId
        val celId: String? get() = address.celId
    }

    /** The active layer's current cel's lines, in drawing order (seq). Empty until 5.20a/5.20d land. */
    fun activeLines(): List<Line>

    /** Every visible layer's current cel's lines, for the All layers chip. */
    fun visibleLines(): List<Line>

    // ── lines (H5) ──

    /** Lifts [ids] into their own texture for a live, cheap preview while the box moves; null = no live preview. */
    fun liftLines(ids: Set<LineAddress>): LiftedLines?

    /** Replaces at each exact address, preserving seq (recolour, re-brush, re-weight, flip, nudge, tip), ONE step. */
    fun replaceLines(after: List<AddressedStroke>): Boolean

    /** Adds at each addressed destination (Duplicate, Paste), assigning new seqs on top, ONE step.
     * Every address must be vacant; validate the whole batch before writing. Input seq is not reused. */
    fun addLines(records: List<AddressedStroke>): Boolean

    /** Deletes at each exact layer/cel/record address, ONE step. */
    fun deleteLines(ids: Set<LineAddress>): Boolean

    /** Rasterize Down: the lines become pixels at their own seqs (JB-5.20 D12), ONE step. */
    fun bakeLines(ids: Set<LineAddress>): Boolean

    // ── paint (H4, H5) ──

    /** [mask] lifted off the active layer's cel (kept in place when [keepSource], for Duplicate); null = refused. */
    fun liftPixels(mask: SelectionMask, keepSource: Boolean): LiftedPixels?

    /** H4: a brush stroke that starts inside paints only inside, weighted by coverage. Null = no clip. */
    var paintClip: SelectionMask?

    // ── look ──

    /** D17 / H5: fade everything but the lifted lines toward the paper by [alpha] (0 = off). Display only. */
    fun dimRest(alpha: Float)

    /** H10: hue (degrees), saturation and brightness (1 = unchanged) of the lifted selection, live; [done] = ONE step. */
    fun adjustColour(hueDeg: Float, saturation: Float, brightness: Float, done: Boolean)

    /** The More menu offers colour adjustment only after H10 has a real preview/undo backing. */
    val canAdjustColour: Boolean get() = false

    /** R42's tap action for the S Pen button (the eyedropper by default), at a screen point. */
    fun penButtonTap(screenX: Float, screenY: Float)

    /** A one-time note or a confirmation, with an optional action ("Undo"). */
    fun toast(text: String, action: String? = null, onAction: (() -> Unit)? = null)

    /** Redraw the canvas (the lifted preview moved) and the Select overlays. */
    fun invalidate()
}

/** H5: lines lifted for a live preview. */
interface LiftedLines {
    /** Draw the lifted texture through [h] (document → document) until the next call. */
    fun setTransform(h: Homography)

    /**
     * Re-render the lifted texture from [records] (same addresses) with NO undo step: the live view of a nudge, a tip drag, a
     * weight scrub or a colour being picked. The drawing itself is unchanged until [commit]. It may run every frame of a
     * drag, so it reuses the lifted texture (no allocation per call). Addresses outside the lifted set are refused
     * in words (Lead, 2026-10-12), never silently added.
     */
    fun preview(records: List<AddressedStroke>)

    /** [after] (same addresses, same seqs) replaces them as ONE step; stays lifted at identity. */
    fun commit(after: List<AddressedStroke>): Boolean

    /** The selection ended: the lines go back into the cel's look, no step. */
    fun release()
}

/** H5: paint lifted for a live preview. */
interface LiftedPixels {
    fun setTransform(h: Homography)

    /** Warps the lifted paint back into the same layer, ONE step; stays lifted at identity in the new place.
     * False leaves its source/coverage unchanged. */
    fun commit(): Boolean

    /** Delete: the lifted paint becomes empty, ONE step. */
    fun clear(): Boolean

    /** Fill: the selection's area, weighted by its coverage, becomes [argb] over what is there, ONE step. */
    fun fill(argb: Int): Boolean

    fun release()
}
