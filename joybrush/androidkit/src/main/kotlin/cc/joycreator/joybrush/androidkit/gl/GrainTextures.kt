package cc.joycreator.joybrush.androidkit.gl

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES30
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The grain pictures a brush points at (JB-1.05c): `cloud_256.png` and friends from the packaged
 * `/joybrush/assets/grain/` and paper surfaces from `/joybrush/assets/paper/`. GL THREAD ONLY, like [GlPaintEngine], which owns one.
 *
 * Every texture is REPEAT with a mip chain (a paper grain seen from far away must average, not shimmer)
 * Tip pictures use RED for height; paper surfaces retain RGBA slopes, height and height².
 *
 * A texture name only means something on the context that minted it, so after a context loss
 * [forget] drops the names WITHOUT deleting them (deleting a name the new context may already have
 * handed to somebody else would delete somebody else's texture). [release] is the deleting path.
 */
class GrainTextures(
    private val loadBitmap: (String) -> Bitmap? = ::loadPackagedBitmap,
) {
    private val byName = HashMap<String, Int>()
    private val failed = HashSet<String>()

    /** A 1×1 white texel. Bound to a grain sampler whose grain is OFF, so a sampler never points at
     *  something else — above all never at the framebuffer's own attachment, which would be a feedback loop. */
    var placeholder = 0
        private set

    fun create() {
        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        placeholder = t[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, placeholder)
        val white = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        white.put(byteArrayOf(-1, -1, -1, -1)).rewind()
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, 1, 1, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, white)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    /**
     * The texture for a packaged grain picture, loaded the first time it is asked for; or null if the
     * picture is missing or unreadable (remembered, so a broken file is not re-read on every stroke).
     * The caller then draws that stroke WITHOUT that grain — a missing picture must never turn into a
     * "white" one, which would paint everywhere.
     */
    fun textureFor(assetName: String): Int? {
        byName[assetName]?.let { return it }
        if (assetName in failed) return null
        val bmp = try { loadBitmap(assetName) } catch (e: Exception) { null }
        if (bmp == null) { failed.add(assetName); return null }
        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0])
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
        bmp.recycle()
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_REPEAT)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        byName[assetName] = t[0]
        return t[0]
    }

    /** Context lost: the names are dead. Drop them; delete nothing. */
    fun forget() {
        byName.clear()
        failed.clear()
        placeholder = 0
    }

    /** Deletes every texture this owns. */
    fun release() {
        val all = ArrayList<Int>(byName.values)
        if (placeholder != 0) all.add(placeholder)
        if (all.isNotEmpty()) GLES30.glDeleteTextures(all.size, all.toIntArray(), 0)
        forget()
    }

    /** How many texture names are held (a test seam, like [GlPaintEngine.heldTextureNames]). */
    internal fun heldNames(): Int = byName.size + (if (placeholder != 0) 1 else 0)

    companion object {
        /** Reads grain or surface PNGs from their packaged resource folder. The name is a plain file name (GrainMath.assetNameFor). */
        fun loadPackagedBitmap(name: String): Bitmap? {
            val folder = if (name.startsWith("surface_")) "paper" else "grain"
            val stream = GrainTextures::class.java.getResourceAsStream("/joybrush/assets/$folder/$name") ?: return null
            return stream.use {
                val o = BitmapFactory.Options().apply {
                    inScaled = false
                    // Surface alpha is height² data: never multiply the slopes/height by it.
                    inPremultiplied = false
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                BitmapFactory.decodeStream(it, null, o)
            }
        }
    }
}
