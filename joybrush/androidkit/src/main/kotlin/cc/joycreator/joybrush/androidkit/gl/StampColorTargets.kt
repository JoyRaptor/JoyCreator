package cc.joycreator.joybrush.androidkit.gl

import android.opengl.GLES30

/**
 * GL-thread-only companions for colored procedural stamps. The original R stroke buffer still
 * owns coverage/opacity; these RGBA moments supply its independently accumulated straight RGB.
 * No pool: names belong only to this contact and are deleted on end, cancellation or refusal.
 */
internal class StampColorTargets(private val tileSize: Int,
    private val deleteTextures: (IntArray) -> Unit = { names -> GLES30.glDeleteTextures(names.size, names, 0) },
) {
    private val tiles = LinkedHashMap<Long, Int>()
    private var selected: Boolean? = null
    private var bytesPerTexel = 0L
    private val one = intArrayOf(GLES30.GL_COLOR_ATTACHMENT0)
    private val two = intArrayOf(GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_COLOR_ATTACHMENT1)

    init { require(tileSize > 0) }

    val enabled: Boolean get() = selected == true
    val size: Int get() = tiles.size

    /** Called only for nonempty batches; the first batch fixes this contact's storage format. */
    fun latch(hasColor: Boolean) {
        val previous = selected
        check(previous == null || previous == hasColor) { "A stroke cannot change color-moment format during contact" }
        selected = hasColor
    }

    fun textureFor(key: Long): Int? = if (enabled) tiles.getValue(key) else null
    fun heldTextureHandles(): Set<Int> = tiles.values.toSet()
    fun heldWorkingTileBytes(): Map<Int, Long> {
        val bytes = Math.multiplyExact(Math.multiplyExact(tileSize.toLong(), tileSize.toLong()), bytesPerTexel)
        return tiles.values.associateWith { bytes }
    }

    /**
     * Allocate and clear BEFORE grain samplers are bound. Caller supplies its own current-context
     * factory and clear operation. A failed batch publishes none of its fresh targets. The
     * allowance limits this contact's companions; engine replay admission also counts all owners.
     */
    fun prepare(keys: Collection<Long>, texelBytes: Long, allowanceBytes: Long,
                allocate: () -> Int, initialize: (Int) -> Unit) {
        if (!enabled) return
        require(texelBytes == 4L || texelBytes == 8L) { "Stamp color moments need RGBA8 or RGBA16F" }
        check(bytesPerTexel == 0L || bytesPerTexel == texelBytes) { "Stamp color precision changed during contact" }
        val freshKeys = keys.filter { it !in tiles }.distinct()
        val count = Math.addExact(tiles.size.toLong(), freshKeys.size.toLong())
        val required = Math.multiplyExact(Math.multiplyExact(count, tileSize.toLong()), Math.multiplyExact(tileSize.toLong(), texelBytes))
        require(allowanceBytes >= 0L && required <= allowanceBytes) { "This colored stroke needs too much working tile memory" }
        val pending = LinkedHashMap<Long, Int>()
        try {
            for (key in freshKeys) {
                val name = allocate()
                check(name != 0 && name !in tiles.values && name !in pending.values) { "Could not allocate an independent stamp color tile" }
                pending[key] = name
                initialize(name)
            }
        } catch (failure: Throwable) {
            if (pending.isNotEmpty()) deleteTextures(pending.values.toIntArray())
            throw failure
        }
        tiles.putAll(pending)
        bytesPerTexel = texelBytes
    }

    /** Bound FBO's attachment0 already names the matching original R tile. */
    fun drawWith(key: Long, draw: () -> Unit) {
        if (!enabled) { draw(); return }
        try {
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT1,
                GLES30.GL_TEXTURE_2D, tiles.getValue(key), 0)
            GLES30.glDrawBuffers(2, two, 0)
            check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) {
                "The colored stamp framebuffer is unavailable"
            }
            draw()
        } finally {
            // All other engine draws retain the historical single attachment contract.
            GLES30.glDrawBuffers(1, one, 0)
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT1,
                GLES30.GL_TEXTURE_2D, 0, 0)
        }
    }

    /** Current context only. Engine must call abandonContext before any context-loss cancellation. */
    fun clear() {
        val names = tiles.values.toIntArray()
        tiles.clear(); selected = null; bytesPerTexel = 0L
        if (names.isNotEmpty()) deleteTextures(names)
    }

    /** Names from a dead context must never be deleted in the replacement context. */
    fun abandonContext() { tiles.clear(); selected = null; bytesPerTexel = 0L }
}
