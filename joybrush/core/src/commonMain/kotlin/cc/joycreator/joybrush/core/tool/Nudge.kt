package cc.joycreator.joybrush.core.tool

/**
 * Nudge: move whatever is selected by one step. The owner's rule — "nudge scales with zoom: a nudge
 * moves the same distance ON SCREEN; zoom in for fine work" (OWNER_CONSTRAINTS, JB-2.16a).
 *
 * So a nudge is a SCREEN distance, and how far that is in document px depends on the zoom: at zoom 4
 * a nudge is 0.25 document px, which is the whole point — zoom in for fine moves. There is no view
 * and no selection in here, only the one number every caller needs, so the two-finger nudge and the
 * three-finger swipe cannot end up with different ideas of how far a nudge is.
 */
object Nudge {

    /** Screen px in the small nudge. */
    const val SMALL_SCREEN_PX = 1f

    /** Screen px in the big nudge: ten times the small one, and on exactly the same scale. */
    const val BIG_SCREEN_PX = 10f

    /**
     * Document px moved by one nudge: 1 screen px (10 when [big]) at the current zoom.
     *
     * [screenPerDoc] is `ViewTransform.zoom` — screen px per document px. A value that is not a
     * usable number (zero, negative, NaN, Infinity) is read as 1, so a view that has not been laid
     * out yet nudges by a whole document px instead of by nothing at all, and the same guard
     * [SizeOpacityDrag] applies keeps the two feeling consistent.
     */
    fun stepDoc(screenPerDoc: Float, big: Boolean = false): Float {
        val ratio = if (screenPerDoc.isFinite() && screenPerDoc > 0f) screenPerDoc else 1f
        val screenPx = if (big) BIG_SCREEN_PX else SMALL_SCREEN_PX
        return screenPx / ratio
    }
}
