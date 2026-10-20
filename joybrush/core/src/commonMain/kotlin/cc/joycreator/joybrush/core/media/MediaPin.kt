package cc.joycreator.joybrush.core.media

/**
 * What a PINNED media window does (JB-2.12a's Phase E).
 *
 * A pinned window does not move: the copies' wet paint must live in ONE water field, and a window
 * that moved would flush and reload (10–16 ms a frame on a Note 9) and cut the water at its edge.
 * So while the window is pinned every move is refused, and a frame whose paint would not fit in
 * the window is REFUSED rather than clipped: a clipped dab is a lost dab, and a lost dab in one
 * copy is a broken picture.
 *
 * Pure, so the decisions are proved without a GPU.
 */
object MediaPin {

    /** What the pinned window does with a frame. */
    enum class Decision {
        /** The frame's paint is inside the window: it is drawn, whole, in this frame. */
        RUN_WHOLE,

        /** The frame's paint would fall off the window: it is REFUSED, never clipped. */
        REFUSE,
    }

    /**
     * Whether the window may move at all: it may not while it is pinned.
     *
     * This is the one switch every move of the window goes through ([cc.joycreator.joybrush.androidkit.gl.media.MediaWindow]
     * asks it before it moves), so a new way of moving the window cannot forget the pin.
     */
    fun mayMove(pinned: Boolean): Boolean = !pinned

    /**
     * What a pinned window does with a frame whose paint is [rect] (document px, the dabs' own
     * extent): it runs the frame whole when the paint is inside the window, and refuses it when it
     * is not. A frame with nothing to paint runs whole.
     *
     * This is the PER-SIDE test: the rectangle is inside the window or it is not, side by side.
     * It is NOT the safe zone about the symmetry centre ([MediaSafety.insideZone]), which is judged
     * from the centre and not from the window's edges.
     */
    fun decide(tx: Int, ty: Int, rect: DoubleArray?): Decision =
        if (rect == null || MediaWindowMath.within(tx, ty, rect)) Decision.RUN_WHOLE else Decision.REFUSE
}
