package cc.joycreator.joybrush.androidkit.gl.media

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random

/**
 * The paste shaders' noise lattice, baked: each texel holds its own value and those of its right, upper and upper-right
 * neighbours (wrapping), so jb_paste.glsl's noise is one read instead of four hashes. Fixed values, so a stroke's noise
 * does not change between runs. GL thread.
 */
internal object MediaPasteNoise {
    private const val SIZE = 256

    fun make(): Int {
        val random = Random(0x4A6F79L)
        val lattice = FloatArray(SIZE * SIZE) { random.nextFloat() }
        fun at(x: Int, y: Int) = lattice[(y and (SIZE - 1)) * SIZE + (x and (SIZE - 1))]
        val data = ByteBuffer.allocateDirect(SIZE * SIZE * 16).order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (y in 0 until SIZE) for (x in 0 until SIZE) data.put(at(x, y)).put(at(x + 1, y)).put(at(x, y + 1)).put(at(x + 1, y + 1))
        data.rewind()
        return MediaTex.make(SIZE, SIZE, GLES30.GL_RGBA16F, GLES30.GL_RGBA, GLES30.GL_FLOAT, GLES30.GL_NEAREST, data)
    }
}
