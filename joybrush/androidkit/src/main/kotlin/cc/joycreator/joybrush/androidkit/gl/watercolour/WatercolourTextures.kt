package cc.joycreator.joybrush.androidkit.gl.watercolour

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES30
import cc.joycreator.joybrush.core.brush.WatercolourSpec
import java.nio.ByteBuffer
import kotlin.math.floor
import kotlin.math.ln

/** R8 image tips are colour-independent. No media window or simulation resources are used. GL thread only. */
internal class WatercolourTextures(private val load: (String) -> Bitmap? = ::packagedBitmap) {
    data class Set(val dabs: Int, val backdrop: Int, val grain: Int, val count: Int)
    private val sets = LinkedHashMap<String, Set>()
    fun forSpec(spec: WatercolourSpec): Set {
        val key = spec.dabImages.joinToString("|") + ":" + spec.backdrop
        sets[key]?.let { return it }
        require(sets.size < 4) { "Close the drawing before loading more watercolor image sets" }
        val created = ArrayList<Int>()
        var pendingFirst: Bitmap? = null
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        try {
            val first = requireNotNull(load(spec.dabImages.first())) { "Watercolor dab image is missing" }
            pendingFirst = first
            val width = first.width; val height = first.height
            val maxLayers = IntArray(1)
            GLES30.glGetIntegerv(GLES30.GL_MAX_ARRAY_TEXTURE_LAYERS, maxLayers, 0)
            require(spec.dabImages.size <= maxLayers[0]) { "This watercolor set has too many images for this phone" }
            val array = name().also(created::add)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, array)
            val levels = floor(ln(maxOf(width, height).toDouble()) / ln(2.0)).toInt() + 1
            GLES30.glTexStorage3D(GLES30.GL_TEXTURE_2D_ARRAY, levels, GLES30.GL_R8, width, height, spec.dabImages.size)
            for ((i, path) in spec.dabImages.withIndex()) {
                val bitmap = if (i == 0) first else requireNotNull(load(path)) { "Watercolor dab image is missing: $path" }
                if (i == 0) pendingFirst = null
                try {
                    require(bitmap.width == width && bitmap.height == height) { "Watercolor dab images must have equal dimensions" }
                    GLES30.glTexSubImage3D(GLES30.GL_TEXTURE_2D_ARRAY, 0, 0, 0, i, width, height, 1,
                        GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, red(bitmap))
                } finally { bitmap.recycle() }
            }
            parameters(GLES30.GL_TEXTURE_2D_ARRAY, repeat = false)
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D_ARRAY)
            // Keep the mip chain for future zoom levels, but match Canvas tip sampling at the
            // native look level. Mip filtering visibly broadens the approved one-pixel nib.
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            val wash = requireNotNull(load(spec.backdrop)) { "Watercolor backdrop is missing" }
            val backdrop = name().also(created::add)
            try {
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, backdrop)
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, wash.width, wash.height, 0,
                    GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, red(wash))
                parameters(GLES30.GL_TEXTURE_2D, repeat = true)
                GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
            } finally { wash.recycle() }
            val grain = name().also(created::add)
            val bytes = ByteBuffer.allocateDirect(128 * 128)
            var seed = 521932
            repeat(128 * 128) {
                seed += 0x6D2B79F5
                var t = (seed xor (seed ushr 15)) * (seed or 1)
                t = t xor (t + (t xor (t ushr 7)) * (t or 61))
                val n = ((t xor (t ushr 14)).toLong() and 0xffffffffL) / 4294967296.0
                bytes.put((n * 255.0 + .5).toInt().toByte())
            }
            bytes.flip()
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, grain)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, 128, 128, 0, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, bytes)
            parameters(GLES30.GL_TEXTURE_2D, repeat = true)
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
            check(GLES30.glGetError() == GLES30.GL_NO_ERROR) { "Watercolor images could not be uploaded" }
            return Set(array, backdrop, grain, spec.dabImages.size).also { sets[key] = it }
        } catch (e: Throwable) {
            if (created.isNotEmpty()) GLES30.glDeleteTextures(created.size, created.toIntArray(), 0)
            throw e
        } finally {
            pendingFirst?.recycle()
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, 0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        }
    }
    fun forget() = sets.clear()
    fun release() {
        val names = sets.values.flatMap { listOf(it.dabs, it.backdrop, it.grain) }.toIntArray()
        if (names.isNotEmpty()) GLES30.glDeleteTextures(names.size, names, 0)
        forget()
    }
    private fun name(): Int = IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0]
    private fun parameters(target: Int, repeat: Boolean) {
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_S, if (repeat) GLES30.GL_REPEAT else GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_T, if (repeat) GLES30.GL_REPEAT else GLES30.GL_CLAMP_TO_EDGE)
    }
    companion object {
        private fun red(bitmap: Bitmap): ByteBuffer {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return ByteBuffer.allocateDirect(pixels.size).also { buffer ->
                pixels.forEach { buffer.put((it ushr 16).toByte()) }; buffer.flip()
            }
        }
        private fun packagedBitmap(path: String): Bitmap? {
            require(!path.startsWith("/") && !path.contains("..")) { "Invalid watercolor asset path" }
            return WatercolourTextures::class.java.getResourceAsStream("/joybrush/assets/$path")?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                    inScaled = false; inPremultiplied = false; inPreferredConfig = Bitmap.Config.ARGB_8888
                })
            }
        }
    }
}
