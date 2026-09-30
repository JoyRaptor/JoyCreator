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
