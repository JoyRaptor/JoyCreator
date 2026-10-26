package cc.joycreator.joybrush.androidkit.gl

/**
 * Transfers a newly allocated texture to the caller only after initialization succeeds.
 * A caller cannot track the returned handle if initialization throws first. Keep this
 * guard inline so ordinary tile copies gain no callback allocation in the hot path.
 * This handles thrown failures, not silent GL errors or context/epoch validation.
 */
internal inline fun allocateInitializedTexture(
    crossinline allocate: () -> Int,
    crossinline initialize: (Int) -> Unit,
    crossinline release: (Int) -> Unit,
): Int {
    val texture = allocate()
    try {
        initialize(texture)
        return texture
    } catch (failure: Throwable) {
        try {
            release(texture)
        } catch (cleanup: Throwable) {
            if (cleanup !== failure) try { failure.addSuppressed(cleanup) } catch (_: Throwable) {}
        }
        throw failure
    }
}
