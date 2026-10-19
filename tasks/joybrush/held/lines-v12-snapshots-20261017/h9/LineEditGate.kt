package cc.joycreator.joybrush.androidkit.lines

/**
 * Editable lines v1.2 H9 and H10: when a Select edit must not run, in words. Pure, so the canvas, the engine and their
 * tests share one set of sentences.
 */
object LineEditGate {
    /** Said when the drawing under an edit is not the one it was made against (reloaded, or the graphics restarted). */
    const val CHANGED = "The drawing changed; try again."

    /** A locked or hidden layer's lines can't be changed (they may still show). null = the layer takes edits. */
    fun layerRefusal(layerName: String, locked: Boolean, visible: Boolean): String? = when {
        locked -> "\"$layerName\" is locked, so its lines can't be changed."
        !visible -> "\"$layerName\" is hidden, so its lines can't be changed."
        else -> null
    }

    /**
     * Is the edit still about this drawing? [submitted] and [now] are the line-edit epoch (bumped by every open) and the
     * graphics epoch when the edit was handed in and when its GL work came to run. null = go ahead.
     */
    fun stale(submittedEpoch: Int, epochNow: Int, submittedGraphics: Int, graphicsNow: Int, contentLost: Boolean): String? =
        if (contentLost || submittedEpoch != epochNow || submittedGraphics != graphicsNow) CHANGED else null
}
