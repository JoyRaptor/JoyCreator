package cc.joycreator.joybrush.androidkit.preview

/**
 * The few colours a brush preview is drawn in, so the picture follows the drawer and the cache can tell two looks apart.
 *
 * Stamp, tuft, watercolour-line and fill brushes are laid in [ink] over nothing, so the drawer shows through (the old
 * stand-in did the same). The eraser cuts through a strip of [strip]. A smudge drags [bandLeft] into [bandRight].
 * Pencil, watercolour and oil can only exist on paper: they are laid in [mediaInk] on a reference paper and the
 * picture keeps that paper.
 */
data class BrushPreviewStyle(
    val ink: Int = 0xFFF2F2F5.toInt(),
    val strip: Int = 0x59F2F2F5,
    val bandLeft: Int = 0xFFE3A33C.toInt(),
    val bandRight: Int = 0xFF3C8FE3.toInt(),
    val mediaInk: Int = 0xFF2E3446.toInt(),
) {
    /** Everything that changes the picture, as one short string: part of the cache key. */
    val key: String
        get() = listOf(ink, strip, bandLeft, bandRight, mediaInk).joinToString("") { String.format("%08x", it) }
}

/** A way to call a queued preview off. Cancelling is safe from any thread and after the preview has been made. */
class BrushPreviewTicket {
    @Volatile var cancelled: Boolean = false
        private set

    fun cancel() { cancelled = true }
}
