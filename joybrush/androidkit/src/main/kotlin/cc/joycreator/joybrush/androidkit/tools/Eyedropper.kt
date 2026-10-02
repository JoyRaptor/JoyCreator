package cc.joycreator.joybrush.androidkit.tools

/**
 * What the eyedropper is showing right now (JB-2.03a): the ring's place, the colour under it, the colour it would replace,
 * and the little circle you slide back into to cancel. Screen px, all of it.
 *
 * @property newArgb the colour sampled under the ring (opaque). It is [oldArgb] for the first frame after the ring appears — the read of the
 *   screen is a frame behind — and the sampled colour from then on.
 * @property oldArgb the colour in use before the eyedropper started.
 * @property overCancel the touch is inside the cancel circle AND has already left it once: lifting now changes nothing, and the ring shows
 *   [oldArgb] on both halves so the cancel is visible before it happens. Read [Eyedropper.overCancel] for why the second half of that is
 *   part of the rule and not a detail.
 */
data class EyedropState(
    val x: Float,
    val y: Float,
    val newArgb: Int,
    val oldArgb: Int,
    val cancelCx: Float,
    val cancelCy: Float,
    val cancelR: Float,
    val overCancel: Boolean,
    /**
     * A FINGER is picking (owner, 2026-09-30: "needs a big version for fat fingers"): the ring is big and floats off the
     * fingertip at ([x], [y]) — the point being sampled — with a stem back to the finger at ([fingerX], [fingerY]).
     */
    val finger: Boolean = false,
    val fingerX: Float = x,
    val fingerY: Float = y,
)

/** The pure numbers of the eyedropper (JB-2.03a), so they are tests and not folklore. */
object Eyedropper {

    /** A pen held still this long (stroke cancelled, nothing committed) becomes the eyedropper. */
    const val HOLD_MS = 450L

    /** How far a PEN may wander during the hold, in screen px. A finger wobbles more, so it gets [FINGER_SLOP_PX]. */
    const val PEN_SLOP_PX = 6f
    const val FINGER_SLOP_PX = 10f

    /** A stylus-button touch that lifts within this and barely moved is an eyedropper tap. */
    const val PEN_BUTTON_TAP_MS = 250L

    /**
     * The cancel circle where a hold began, and the ring (dp). [CANCEL_CIRCLE_DP] is the circle's DIAMETER — Decision 2's "the small
     * circle where the hold began (24 dp)" is a whole circle, not a radius — so the radius is half of it. R10: these are dp and every one
     * of them is multiplied by the density at the place it is used; [cancelRadiusPx] is that multiply, in one function, so the two
     * halves of the arithmetic cannot drift apart.
     */
    const val CANCEL_CIRCLE_DP = 24f
    const val RING_DP = 44f
    const val RING_THICK_DP = 6f

    /** [CANCEL_CIRCLE_DP] as the RADIUS the hit test and the drawn circle use, in screen px: dp × density ÷ 2. */
    fun cancelRadiusPx(density: Float): Float = CANCEL_CIRCLE_DP * density / 2f

    /** A press on the colour pill that moves further than this (dp) is a drag-off, not a tap. */
    const val DRAG_OFF_DP = 8f

    /**
     * The finger eyedropper (owner, 2026-09-30): a fingertip covers what it is pointing at, so a finger samples the point
     * [FINGER_LIFT_DP] above it, and the ring there is big ([FINGER_RING_DP], [FINGER_RING_THICK_DP] thick) with a
     * crosshair at the exact pixel. A pen samples under its tip, as before: the tip is small and you can see past it.
     */
    const val FINGER_LIFT_DP = 76f
    const val FINGER_RING_DP = 92f
    const val FINGER_RING_THICK_DP = 12f

    /**
     * Where the eyedropper samples for a touch at ([x], [y]) (view px): under a pen; [FINGER_LIFT_DP] above a finger —
     * or BELOW it when there is no room above for the ring, so the ring is not cut off by the top edge of the screen.
     *
     * What this does NOT promise, and no part of the code here can: that the point is clear of the chrome, or that the ring is clear
     * of the screen's left, right and bottom edges. Only this function's own caller knows where the tool strip and the top bar are,
     * and the ring is drawn in the view's coordinate space by [EyedropperRingView]. A finger under the strip therefore samples what is
     * under the strip. That is the chrome row's geometry to give (JB-2.01), not a number to guess here.
     */
    fun samplePoint(x: Float, y: Float, finger: Boolean, density: Float): Pair<Float, Float> {
        if (!finger) return Pair(x, y)
        val lift = FINGER_LIFT_DP * density
        val room = FINGER_RING_DP * density / 2f
        return if (y - lift >= room) Pair(x, y - lift) else Pair(x, y + lift)
    }

    /**
     * The cancel circle is a way BACK to where the hold began, so it means nothing until the touch has left it (JB-2.03a Decision 2,
     * "slide back into the small circle where the hold began and lift there"). At the instant the ring appears the touch is on that
     * circle's own centre, so a rule that only asked "is the touch inside the circle?" would call the untouched start of a deliberate
     * pick a cancel and throw the colour away — the spec's own check, "long-press again and lift → red", could not pass.
     *
     * So the circle carries a second piece of state: [alreadyArmed], which is true from the first move that is OUTSIDE the circle and
     * stays true. The circle's own edge is the threshold on purpose. Arming on "moved at all" instead would let the ring show a cancel
     * while the touch is still inside the circle it never left, and a one-pixel wobble at the start of a pick would discard it.
     *
     * `r` of 0 means there is no circle (the drag-off from the colour pill): nothing is armed and nothing cancels.
     */
    fun armsCancel(touchX: Float, touchY: Float, cx: Float, cy: Float, r: Float, alreadyArmed: Boolean): Boolean =
        r > 0f && (alreadyArmed || !insideCircle(touchX, touchY, cx, cy, r))

    /**
     * True when a lift now changes nothing: there is a cancel circle, the touch has already left it, and it is back inside.
     * False at the start of a hold, so "lifting takes the colour" (Decision 2) holds without any travel at all.
     */
    fun overCancel(touchX: Float, touchY: Float, cx: Float, cy: Float, r: Float, armed: Boolean): Boolean =
        r > 0f && armed && insideCircle(touchX, touchY, cx, cy, r)

    /**
     * Whether a touch at ([x], [y]), in a view [w] × [h] px, is ON that view — the lift test for the drag-off eyedropper, which
     * cancels when it lands back on the colour pill (JB-2.03a Decision 2). The right and bottom edges are OUTSIDE: a view's width and
     * height are one past its last column and row, so `<=` calls a point one pixel beyond the swatch "on the pill" and cancels a pick
     * that should have been taken.
     */
    fun overPill(x: Float, y: Float, w: Int, h: Int): Boolean =
        x >= 0f && y >= 0f && x < w.toFloat() && y < h.toFloat()

    /**
     * The colour a person SEES at one pixel: the layer's premultiplied RGBA8 [rgba] pixel at [pixelIndex] (the index of its first byte),
     * at the layer's opacity, over the paper. Opaque. A null tile is empty, which shows the paper.
     *
     * This is NOT how the shipped eyedropper reads a colour. It reads the composited screen, every layer at its opacity and blend over
     * the paper, through `JbCanvasView.sampleScreen` (JB-2.04: one layer's pixel stopped being "what you see" the day there were two).
     * This function is that same rule written over ONE layer's bytes, and it is kept as the reference for it. No production code calls
     * it, and the eyedropper's own tests are the reason it exists.
     *
     * [pixelIndex] must be a whole pixel of [rgba] — 0, 4, 8 … — or this refuses rather than reading the neighbouring pixel's bytes.
     */
    fun seen(rgba: ByteArray?, pixelIndex: Int, layerOpacity: Float, paperArgb: Int): Int {
        val pr = (paperArgb shr 16) and 0xFF
        val pg = (paperArgb shr 8) and 0xFF
        val pb = paperArgb and 0xFF
        if (rgba == null) return 0xFF000000.toInt() or (pr shl 16) or (pg shl 8) or pb
        require(pixelIndex >= 0 && pixelIndex + 3 < rgba.size) {
            "pixel index $pixelIndex is not a whole pixel of a ${rgba.size}-byte buffer"
        }
        val o = layerOpacity.coerceIn(0f, 1f)
        val a = (rgba[pixelIndex + 3].toInt() and 0xFF) / 255f * o
        fun ch(i: Int, paper: Int): Int {
            val premult = (rgba[pixelIndex + i].toInt() and 0xFF) * o
            return (premult + paper * (1f - a) + 0.5f).toInt().coerceIn(0, 255)
        }
        return 0xFF000000.toInt() or (ch(0, pr) shl 16) or (ch(1, pg) shl 8) or ch(2, pb)
    }

    /** Is ([x], [y]) within [r] of ([cx], [cy])? The boundary counts as inside, which is what arms the cancel circle. */
    fun insideCircle(x: Float, y: Float, cx: Float, cy: Float, r: Float): Boolean {
        val dx = x - cx
        val dy = y - cy
        return dx * dx + dy * dy <= r * r
    }
}
