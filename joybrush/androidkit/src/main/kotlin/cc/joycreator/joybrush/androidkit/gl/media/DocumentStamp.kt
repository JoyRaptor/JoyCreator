package cc.joycreator.joybrush.androidkit.gl.media

/**
 * Which drawing a media place's live state was loaded from ([GlPaintEngine.documentGeneration] at the time). Once the
 * engine has moved on to another drawing, none of that state may be written back: one drawing's live paint must never land
 * in the next, even under the same layer id (audit 2026-10-18 backstop; the canvas already lets it go before a reset).
 */
internal class DocumentStamp(private val generation: () -> Long) {
    private var at: Long? = null

    /** The place has just loaded from the drawing open now. */
    fun stamp() { at = generation() }

    /** The drawing it was loaded from has been closed since (never true before the first [stamp]). */
    val stale: Boolean get() = at.let { it != null && it != generation() }
}
