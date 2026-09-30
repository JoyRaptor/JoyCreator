package cc.joycreator.joybrush.androidkit.gl

import android.opengl.GLES30

/**
 * The offscreen half of the composite path (JB-2.20b). GL THREAD ONLY.
 *
 * A shader-side blend needs the backdrop, and ES 3.0 cannot read the framebuffer it is writing. So the
 * stack is built in a target [target] the size of the screen, and each layer's tiles are drawn INTO it
 * while reading a copy of the stack so far from [backdrop]. Before a layer is drawn only the rectangle
 * its tiles can touch is copied (target → backdrop), so a small layer costs a small copy, not a
 * screen-sized one; pixels outside that rectangle are never read by that layer.
 *
 * RGBA8, premultiplied, NEAREST — a texel is a pixel. Two of them at 1080 x 2400 are 2 x 9.9 MB.
 *
 * Like every texture name in the engine these only mean something on the context that minted them:
 * after a loss [forget] zeroes the names and deletes nothing, and the next [ensure] makes new ones.
 */
internal class LayerCompositor {
    internal var target = 0
    internal var backdrop = 0
    internal var targetFbo = 0
    internal var backdropFbo = 0
    internal var width = 0
    internal var height = 0

    /** How many texture names are held. */
    fun heldNames(): Int = (if (target != 0) 1 else 0) + (if (backdrop != 0) 1 else 0)

    /** Makes the two screen-sized targets for a [w] x [h] screen; a no-op if they are already that size. */
    fun ensure(w: Int, h: Int) {
        if (target != 0 && w == width && h == height) return
        release()
        target = newTexture(w, h)
        backdrop = newTexture(w, h)
        val f = IntArray(2)
        GLES30.glGenFramebuffers(2, f, 0)
        targetFbo = f[0]; backdropFbo = f[1]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, targetFbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, target, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, backdropFbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, backdrop, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        width = w; height = h
    }

    /** Copies the [x],[y],[w],[h] rectangle of [target] into the same place in [backdrop]. */
    fun copyToBackdrop(x: Int, y: Int, w: Int, h: Int) {
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, targetFbo)
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, backdropFbo)
        GLES30.glBlitFramebuffer(x, y, x + w, y + h, x, y, x + w, y + h, GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST)
    }

    /** Copies the whole of [target] onto the default framebuffer. */
    fun blitToScreen() {
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, targetFbo)
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0)
        GLES30.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    /** Context lost: the names are dead. Drop them; delete nothing. */
    fun forget() {
        target = 0; backdrop = 0
        targetFbo = 0; backdropFbo = 0
        width = 0; height = 0
    }

    /** Deletes what this owns. */
    fun release() {
        val tex = intArrayOf(target, backdrop).filter { it != 0 }.toIntArray()
        if (tex.isNotEmpty()) GLES30.glDeleteTextures(tex.size, tex, 0)
        val fbo = intArrayOf(targetFbo, backdropFbo).filter { it != 0 }.toIntArray()
        if (fbo.isNotEmpty()) GLES30.glDeleteFramebuffers(fbo.size, fbo, 0)
        forget()
    }

    private fun newTexture(w: Int, h: Int): Int {
        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0])
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        return t[0]
    }
}
