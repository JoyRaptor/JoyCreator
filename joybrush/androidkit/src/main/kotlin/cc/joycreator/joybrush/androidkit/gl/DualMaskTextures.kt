package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.core.brush.BrushException
import cc.joycreator.joybrush.core.brush.DualMask

/** Borrowed immutable identity; this owner alone may delete [texture]. */
class DualMaskBinding internal constructor(val mask: DualMask, val texture: Int) {
    val width: Int get() = mask.width
    val height: Int get() = mask.height
}

/**
 * GL-thread owner of one fixed 256x256 repeating linear R8 mask and one white placeholder.
 * Prepare once per stroke, bind once per batch, end on finish/cancel. No mask cache.
 * A failed deletion retains its name for release retry and blocks another upload; at most
 * two names can remain owned. Context replacement abandons all old names without GL calls.
 */
class DualMaskTextures(private val gl: SampleMaskGl, private val physicalContext: () -> Long) {
    private var context: Long? = null
    private var cap = 0
    private var placeholder = 0
    private var active: DualMaskBinding? = null
    private val retiring = LinkedHashSet<Int>(2)

    fun create() {
        if (context == physicalContext() && placeholder != 0 && retiring.isEmpty()) return
        if (context == physicalContext() && retiring.isNotEmpty()) release()
        else forget()
        val epoch = physicalContext(); context = epoch
        try {
            cap = gl.maxTextureSize(); checkEpoch(epoch)
            if (cap <= 0) throw BrushException("GPU did not report a usable dual-tip texture size")
            placeholder = upload(1, 1, byteArrayOf(-1), false, epoch)
        } catch (failure: Throwable) {
            if (epoch != physicalContext()) forget()
            else if (retiring.isEmpty()) { context = null; cap = 0 }
            throw failure
        }
    }

    fun begin(mask: DualMask): DualMaskBinding {
        val epoch = requireCurrent()
        if (active != null) throw BrushException("Finish the previous dual-tip mask before preparing another")
        if (retiring.isNotEmpty()) throw BrushException("Release the failed dual-tip texture before preparing another")
        if (mask.width != SIDE || mask.height != SIDE)
            throw BrushException("A dual-tip mask must contain exactly 256x256 R8 pixels")
        if (mask.width > cap || mask.height > cap)
            throw BrushException("Dual-tip mask exceeds this GPU's texture size $cap")
        val bytes = mask.bytes()
        if (bytes.size != SIDE * SIDE) throw BrushException("A dual-tip mask has the wrong byte count")
        val texture = upload(SIDE, SIDE, bytes, true, epoch)
        return DualMaskBinding(mask, texture).also { active = it }
    }

    fun bindForBatch(binding: DualMaskBinding?, unit: Int) {
        require(unit >= 0) { "A texture unit cannot be negative" }
        val epoch = requireCurrent()
        if (binding != null && active !== binding)
            throw BrushException("Dual-tip binding is stale or belongs to another owner")
        gl.bindTexture(unit, binding?.texture ?: placeholder); checkEpoch(epoch)
        gl.checkError("bind dual-tip batch"); checkEpoch(epoch)
    }

    fun end(binding: DualMaskBinding) {
        if (active !== binding) return
        active = null
        val epoch = context
        if (epoch == null || epoch != physicalContext()) { forget(); return }
        retiring += binding.texture
        deleteOwned(binding.texture, epoch)
        checkEpoch(epoch)
        gl.checkError("release dual-tip mask"); checkEpoch(epoch)
    }

    /** No GL call. In-flight/stale closes cannot delete replacement-context resources. */
    fun forget() { active = null; placeholder = 0; retiring.clear(); cap = 0; context = null }

    /** Best-effort same-context retirement; failed deletes stay owned for a bounded retry. */
    fun release() {
        val epoch = context
        if (epoch == null || epoch != physicalContext()) { forget(); return }
        active?.let { retiring += it.texture }; active = null
        if (placeholder != 0) retiring += placeholder
        placeholder = 0
        var failure: Throwable? = null
        fun record(error: Throwable) {
            val first = failure
            if (first == null) failure = error else if (first !== error) first.addSuppressed(error)
        }
        for (texture in retiring.toList()) {
            if (epoch != physicalContext()) { forget(); break }
            try { deleteOwned(texture, epoch) } catch (error: Throwable) { record(error) }
        }
        if (epoch == physicalContext()) {
            try { gl.checkError("release dual-tip textures") } catch (error: Throwable) { record(error) }
            if (epoch != physicalContext()) forget()
            else if (retiring.isEmpty()) { context = null; cap = 0 }
        } else forget()
        failure?.let { throw it }
    }

    fun heldTextureHandles(): Set<Int> = buildSet {
        if (placeholder != 0) add(placeholder)
        active?.let { add(it.texture) }
        addAll(retiring)
    }

    private fun requireCurrent(): Long {
        val epoch = context
        if (epoch == null || epoch != physicalContext()) {
            forget(); throw BrushException("Dual-tip textures need the current graphics context")
        }
        if (placeholder == 0) throw BrushException("Create dual-tip textures before preparing a mask")
        return epoch
    }

    private fun checkEpoch(epoch: Long) {
        if (epoch != physicalContext()) {
            forget(); throw BrushException("Dual-tip textures lost their graphics context")
        }
    }

    private fun deleteOwned(texture: Int, epoch: Long) {
        checkEpoch(epoch)
        gl.deleteTexture(texture)
        if (epoch != physicalContext()) { forget(); return }
        gl.checkError("delete dual-tip texture")
        if (epoch != physicalContext()) { forget(); return }
        retiring.remove(texture)
    }

    private fun upload(width: Int, height: Int, bytes: ByteArray, repeat: Boolean, epoch: Long): Int {
        var texture = 0
        try {
            checkEpoch(epoch)
            texture = gl.genTexture(); checkEpoch(epoch)
            gl.checkError("create dual-tip texture"); checkEpoch(epoch)
            if (texture == 0) throw BrushException("GPU could not allocate a dual-tip texture")
            gl.uploadR8(texture, width, height, bytes, repeat) { epoch == physicalContext() }
            checkEpoch(epoch)
            gl.checkError("upload dual-tip texture"); checkEpoch(epoch)
            return texture
        } catch (failure: Throwable) {
            if (texture != 0 && epoch == physicalContext()) {
                retiring += texture
                try { deleteOwned(texture, epoch) }
                catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
            } else if (epoch != physicalContext()) forget()
            throw failure
        }
    }

    companion object { const val SIDE = 256 }
}
