package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.core.brush.BrushException
import cc.joycreator.joybrush.core.brush.SampleAtlasRect
import cc.joycreator.joybrush.core.brush.SampleMaskAtlas
import cc.joycreator.joybrush.core.brush.SampleMaskLimits

/** Small injectable surface; real GLES calls live only in AndroidSampleMaskGl. GL thread only. */
interface SampleMaskGl {
    fun maxTextureSize(): Int
    fun genTexture(): Int
    fun uploadR8(texture: Int, width: Int, height: Int, bytes: ByteArray, repeat: Boolean = false, contextCurrent: () -> Boolean)
    fun bindTexture(unit: Int, texture: Int)
    fun deleteTexture(texture: Int)
    fun checkError(operation: String)
}

/** Borrowed stroke/brush binding; the owner retains the sole deletion right. */
class SampledAtlasBinding internal constructor(
    val texture: Int, val width: Int, val height: Int, entries: List<SampleAtlasRect>,
) {
    private val entries = entries.toList()
    val size: Int get() = entries.size
    fun rect(index: Int): SampleAtlasRect = entries[index]
    fun rects(): List<SampleAtlasRect> = entries.toList()
}

/**
 * One active set, no cache and no LRU. Upload at brush/stroke preparation; bind once per batch;
 * end deletes the set, while the disabled white R8 placeholder survives until context shutdown.
 * An interrupted/retired context is abandoned without deleting names from its replacement.
 */
class SampledMaskTextures(private val gl: SampleMaskGl, private val physicalContext: () -> Long,
                          private val maxUploadPixels: Long = SampleMaskLimits.MAX_ATLAS_PIXELS) {
    private var context: Long? = null
    private var placeholder = 0
    private var cap = 0
    private var active: SampledAtlasBinding? = null

    val maxTextureSize: Int get() { requireCurrent(); return cap }

    fun create() {
        if (context == physicalContext() && placeholder != 0) return
        forget()
        val epoch = physicalContext(); context = epoch
        try {
            cap = gl.maxTextureSize()
            if (cap <= 0) throw BrushException("GPU did not report a usable sampled-tip texture size")
            placeholder = upload(1, 1, byteArrayOf(255.toByte()), epoch)
        } catch (failure: Throwable) { forget(); throw failure }
    }

    /** Caller packs the atlas using this owner's actual maxTextureSize before entering here. */
    fun begin(atlas: SampleMaskAtlas): SampledAtlasBinding {
        val epoch = requireCurrent()
        if (active != null) throw BrushException("finish the previous sampled-tip set before preparing another")
        if (atlas.width > cap || atlas.height > cap) throw BrushException("sample atlas exceeds this GPU's texture size $cap")
        if (maxUploadPixels !in 1L..SampleMaskLimits.MAX_ATLAS_PIXELS || atlas.width.toLong() * atlas.height > maxUploadPixels)
            throw BrushException("sample atlas exceeds the $maxUploadPixels GPU upload pixel budget")
        val texture = upload(atlas.width, atlas.height, atlas.bytes(), epoch)
        val binding = SampledAtlasBinding(texture, atlas.width, atlas.height, atlas.rects())
        active = binding
        return binding
    }

    /** There is no per-dab operation: the whole set is one texture bound for a complete batch. */
    fun bindForBatch(binding: SampledAtlasBinding?, unit: Int) {
        require(unit >= 0) { "a texture unit cannot be negative" }
        requireCurrent()
        if (binding != null && active !== binding) throw BrushException("sample atlas binding is stale or belongs to another owner")
        gl.bindTexture(unit, binding?.texture ?: placeholder)
        gl.checkError("bind sampled-tip batch")
    }

    fun end(binding: SampledAtlasBinding) {
        if (active !== binding) return // old context's close must not affect a replacement set
        active = null
        if (context == physicalContext()) {
            gl.deleteTexture(binding.texture); gl.checkError("release sampled-tip set")
        } else forget()
    }

    /** Explicit context loss: drop references, never issue GL deletion or state restoration. */
    fun forget() { active = null; placeholder = 0; cap = 0; context = null }

    /** Normal same-context shutdown deletes the owned set and placeholder. */
    fun release() {
        val epoch = context
        try {
            if (epoch != null && epoch == physicalContext()) {
                active?.let { gl.deleteTexture(it.texture) }
                if (epoch == physicalContext() && placeholder != 0) gl.deleteTexture(placeholder)
                if (epoch == physicalContext()) gl.checkError("release sampled-tip textures")
            }
        } finally { forget() }
    }

    /** Integrate this inventory into the engine's protected-texture checks; no GL calls. */
    fun heldTextureHandles(): Set<Int> = listOfNotNull(active?.texture, placeholder.takeIf { it != 0 }).toSet()

    private fun requireCurrent(): Long {
        val epoch = context
        if (epoch == null || epoch != physicalContext() || placeholder == 0) {
            forget(); throw BrushException("sampled-tip textures need the current graphics context")
        }
        return epoch
    }

    private fun upload(width: Int, height: Int, bytes: ByteArray, epoch: Long): Int {
        var texture = 0
        try {
            texture = gl.genTexture(); gl.checkError("create sampled-tip texture")
            if (texture == 0) throw BrushException("GPU could not allocate a sampled-tip texture")
            if (epoch != physicalContext()) throw BrushException("sampled-tip preparation lost its graphics context")
            gl.uploadR8(texture, width, height, bytes) { epoch == physicalContext() }
            gl.checkError("upload sampled-tip texture")
            if (epoch != physicalContext()) throw BrushException("sampled-tip preparation lost its graphics context")
            return texture
        } catch (failure: Throwable) {
            if (texture != 0 && epoch == physicalContext()) {
                try { gl.deleteTexture(texture) } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            } else if (epoch != physicalContext()) forget()
            throw failure
        }
    }
}
