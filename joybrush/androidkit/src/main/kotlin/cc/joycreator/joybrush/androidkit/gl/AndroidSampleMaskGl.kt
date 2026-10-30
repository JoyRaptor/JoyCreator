package cc.joycreator.joybrush.androidkit.gl

import android.opengl.GLES30
import cc.joycreator.joybrush.core.brush.BrushException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** State guards cover caller PBOs and unpack layout; do not restore dead-context names. */
class AndroidSampleMaskGl : SampleMaskGl {
    private fun integer(name: Int): Int = IntArray(1).also { GLES30.glGetIntegerv(name, it, 0) }[0]
    override fun maxTextureSize(): Int = integer(GLES30.GL_MAX_TEXTURE_SIZE)
    override fun genTexture(): Int = IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0]
    override fun deleteTexture(texture: Int) { GLES30.glDeleteTextures(1, intArrayOf(texture), 0) }
    override fun bindTexture(unit: Int, texture: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
    }
    override fun checkError(operation: String) {
        val error = GLES30.glGetError()
        if (error != GLES30.GL_NO_ERROR) throw BrushException("$operation failed with GL error $error")
    }

    override fun uploadR8(texture: Int, width: Int, height: Int, bytes: ByteArray, repeat: Boolean, contextCurrent: () -> Boolean) {
        if (width <= 0 || height <= 0 || width.toLong() * height != bytes.size.toLong())
            throw BrushException("R8 upload needs exactly one byte for each atlas pixel")
        val upload = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        upload.put(bytes).rewind()
        checkError("before sampled-tip upload")
        val active = integer(GLES30.GL_ACTIVE_TEXTURE)
        val pbo = integer(GLES30.GL_PIXEL_UNPACK_BUFFER_BINDING)
        val settings = intArrayOf(GLES30.GL_UNPACK_ALIGNMENT, GLES30.GL_UNPACK_ROW_LENGTH,
            GLES30.GL_UNPACK_IMAGE_HEIGHT, GLES30.GL_UNPACK_SKIP_ROWS, GLES30.GL_UNPACK_SKIP_PIXELS, GLES30.GL_UNPACK_SKIP_IMAGES)
        val values = settings.map(::integer)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        val binding = integer(GLES30.GL_TEXTURE_BINDING_2D)
        try {
            if (!contextCurrent()) throw BrushException("sampled-tip upload lost its graphics context")
            GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER, 0)
            for (setting in settings) GLES30.glPixelStorei(setting, if (setting == GLES30.GL_UNPACK_ALIGNMENT) 1 else 0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, width, height, 0,
                GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, upload)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            val wrap = if (repeat) GLES30.GL_REPEAT else GLES30.GL_CLAMP_TO_EDGE
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, wrap)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, wrap)
            checkError("sampled-tip R8 storage")
        } finally {
            if (contextCurrent()) {
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, binding)
                GLES30.glBindBuffer(GLES30.GL_PIXEL_UNPACK_BUFFER, pbo)
                for (i in settings.indices) GLES30.glPixelStorei(settings[i], values[i])
                GLES30.glActiveTexture(active)
            }
        }
    }
}
