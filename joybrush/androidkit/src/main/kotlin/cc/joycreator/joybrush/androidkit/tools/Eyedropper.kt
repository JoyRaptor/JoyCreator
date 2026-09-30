package cc.joycreator.joybrush.androidkit.tools

/**
 * What the eyedropper is showing right now (JB-2.03a): the ring's place, the colour under it, the colour it would replace,
 * and the little circle you slide back into to cancel. Screen px, all of it.
 *
 * @property newArgb the colour sampled under the ring (opaque).
 * @property oldArgb the colour in use before the eyedropper started.
 * @property overCancel the finger is inside the cancel circle: lifting now changes nothing, and the ring shows [oldArgb] on both halves so
 *   the cancel is visible before it happens.
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

    /** The cancel circle where a hold began, and the ring (dp). */
    const val CANCEL_CIRCLE_DP = 24f
    const val RING_DP = 44f
    const val RING_THICK_DP = 6f

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
     * or BELOW it when there is no room above for the ring (the top edge of the screen), so the ring is never cut off.
     */
    fun samplePoint(x: Float, y: Float, finger: Boolean, density: Float): Pair<Float, Float> {
        if (!finger) return Pair(x, y)
        val lift = FINGER_LIFT_DP * density
        val room = FINGER_RING_DP * density / 2f
        return if (y - lift >= room) Pair(x, y - lift) else Pair(x, y + lift)
    }

    /**
     * The colour a person SEES at one pixel: the layer's premultiplied RGBA8 [rgba] pixel at [pixelIndex] (the index of its first byte),
     * at the layer's opacity, over the paper. Opaque. A null tile is empty, which shows the paper. (One layer today; when there are
     * more this becomes the renderer's composite of the visible stack.)
     */
    fun seen(rgba: ByteArray?, pixelIndex: Int, layerOpacity: Float, paperArgb: Int): Int {
        val pr = (paperArgb shr 16) and 0xFF
        val pg = (paperArgb shr 8) and 0xFF
        val pb = paperArgb and 0xFF
        if (rgba == null) return 0xFF000000.toInt() or (pr shl 16) or (pg shl 8) or pb
        val o = layerOpacity.coerceIn(0f, 1f)
        val a = (rgba[pixelIndex + 3].toInt() and 0xFF) / 255f * o
        fun ch(i: Int, paper: Int): Int {
            val premult = (rgba[pixelIndex + i].toInt() and 0xFF) * o
            return (premult + paper * (1f - a) + 0.5f).toInt().coerceIn(0, 255)
        }
        return 0xFF000000.toInt() or (ch(0, pr) shl 16) or (ch(1, pg) shl 8) or ch(2, pb)
    }

    fun insideCircle(x: Float, y: Float, cx: Float, cy: Float, r: Float): Boolean {
        val dx = x - cx
        val dy = y - cy
        return dx * dx + dy * dy <= r * r
    }
}
