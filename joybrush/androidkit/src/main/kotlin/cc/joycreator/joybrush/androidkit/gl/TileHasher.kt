package cc.joycreator.joybrush.androidkit.gl

import android.opengl.GLES30
import cc.joycreator.joybrush.core.paint.TileFingerprint
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Fingerprints of tile textures, computed where they lie ([TileFingerprint], the same arithmetic): a save reads back only
 * the tiles whose fingerprint moved (JBX-36, 2026-11-15). Reading a tile back costs about 4 ms with the pen waiting; a
 * fingerprint is one small draw, and all of them come back in one read.
 *
 * Two passes. Each tile is drawn as 16 x 16 pixels into a sheet of unsigned integers, every pixel folding its own block
 * of 16 x 16 texels; then every tile's 256 block results are folded into one pixel of a second sheet, and that sheet is
 * read. Nothing is written to a tile.
 *
 * GL thread. Leaves the framebuffer, viewport, program, vertex array, scissor switch and texture unit 0 as it found them.
 * Returns null, and stays off for this context, when the driver cannot do it (the caller then reads every tile, as before).
 */
internal class TileHasher(private val tileSize: Int) {
    private var blocks: GlProgram? = null
    private var fold: GlProgram? = null
    private var vao = 0
    private var fbo = 0
    private var blockTex = 0
    private var outTex = 0
    private var rows = 0
    private var broken = tileSize != TileFingerprint.SIDE

    /** One fingerprint per texture of [textures]; [bytes] says which are RGBA8 (the others hold floats). */
    fun prints(textures: IntArray, bytes: BooleanArray): Array<TileFingerprint.Print>? {
        if (broken) return null
        if (textures.isEmpty()) return emptyArray()
        val saved = IntArray(8)
        GLES30.glGetIntegerv(GLES30.GL_DRAW_FRAMEBUFFER_BINDING, saved, 0)
        GLES30.glGetIntegerv(GLES30.GL_READ_FRAMEBUFFER_BINDING, saved, 1)
        GLES30.glGetIntegerv(GLES30.GL_CURRENT_PROGRAM, saved, 2)
        GLES30.glGetIntegerv(GLES30.GL_VERTEX_ARRAY_BINDING, saved, 3)
        GLES30.glGetIntegerv(GLES30.GL_ACTIVE_TEXTURE, saved, 4)
        val viewport = IntArray(4)
        GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, viewport, 0)
        val scissor = GLES30.glIsEnabled(GLES30.GL_SCISSOR_TEST)
        val blend = GLES30.glIsEnabled(GLES30.GL_BLEND)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glGetIntegerv(GLES30.GL_TEXTURE_BINDING_2D, saved, 5)
        try {
            while (GLES30.glGetError() != GLES30.GL_NO_ERROR) Unit   // an older error is not this pass's
            val need = (textures.size + COLS - 1) / COLS
            if (!ready(need)) { broken = true; return null }
            GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
            GLES30.glDisable(GLES30.GL_BLEND)
            GLES30.glBindVertexArray(vao)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, blockTex, 0)
            val p = blocks!!
            p.use()
            GLES30.glUniform1i(p.loc("uTile"), 0)
            for (i in textures.indices) {
                val x = (i % COLS) * BLOCKS; val y = (i / COLS) * BLOCKS
                GLES30.glViewport(x, y, BLOCKS, BLOCKS)
                GLES30.glUniform2i(p.loc("uOrigin"), x, y)
                GLES30.glUniform1i(p.loc("uBytes"), if (bytes[i]) 1 else 0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[i])
                GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            }
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, outTex, 0)
            val f = fold!!
            f.use()
            GLES30.glUniform1i(f.loc("uBlocks"), 0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, blockTex)
            GLES30.glViewport(0, 0, COLS, need)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            val buf = ByteBuffer.allocateDirect(COLS * need * 16).order(ByteOrder.nativeOrder())
            GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 4)
            GLES30.glReadPixels(0, 0, COLS, need, GLES30.GL_RGBA_INTEGER, GLES30.GL_UNSIGNED_INT, buf)
            if (GLES30.glGetError() != GLES30.GL_NO_ERROR) { broken = true; return null }
            val ints = buf.asIntBuffer()
            return Array(textures.size) { i -> TileFingerprint.of(ints.get(i * 4), ints.get(i * 4 + 1), ints.get(i * 4 + 2), ints.get(i * 4 + 3)) }
        } catch (e: RuntimeException) {
            android.util.Log.w("JoyBrushSave", "tile fingerprints are not available on this phone: ${e.message}")
            broken = true
            return null
        } finally {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, saved[5])
            GLES30.glActiveTexture(saved[4])
            GLES30.glBindVertexArray(saved[3])
            GLES30.glUseProgram(saved[2])
            GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, saved[0])
            GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, saved[1])
            GLES30.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
            if (scissor) GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
            if (blend) GLES30.glEnable(GLES30.GL_BLEND)
        }
    }

    private fun ready(need: Int): Boolean {
        if (blocks == null) {
            blocks = GlProgram(VERTEX, BLOCK_FRAGMENT, "tile fingerprint blocks")
            fold = GlProgram(VERTEX, FOLD_FRAGMENT, "tile fingerprint fold")
            val ids = IntArray(1)
            GLES30.glGenVertexArrays(1, ids, 0); vao = ids[0]
            GLES30.glGenFramebuffers(1, ids, 0); fbo = ids[0]
        }
        if (need <= rows) return true
        val want = maxOf(need, rows * 2, 4)
        if (blockTex != 0) GLES30.glDeleteTextures(2, intArrayOf(blockTex, outTex), 0)
        blockTex = sheet(COLS * BLOCKS, want * BLOCKS)
        outTex = sheet(COLS, want)
        rows = if (blockTex != 0 && outTex != 0) want else 0
        if (rows == 0) return false
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        for (t in intArrayOf(blockTex, outTex)) {
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, t, 0)
            if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE) return false
        }
        return true
    }

    private fun sheet(w: Int, h: Int): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA32UI, w, h, 0, GLES30.GL_RGBA_INTEGER, GLES30.GL_UNSIGNED_INT, null)
        if (GLES30.glGetError() != GLES30.GL_NO_ERROR) { GLES30.glDeleteTextures(1, ids, 0); return 0 }
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    fun release() {
        blocks?.release(); fold?.release(); blocks = null; fold = null
        if (blockTex != 0) GLES30.glDeleteTextures(2, intArrayOf(blockTex, outTex), 0)
        if (fbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        if (vao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
        blockTex = 0; outTex = 0; fbo = 0; vao = 0; rows = 0
    }

    private companion object {
        /** Tiles per row of the sheets, and blocks along a tile's side. */
        const val COLS = 64
        const val BLOCKS = 16

        const val VERTEX = """#version 300 es
void main() {
    gl_Position = vec4(float(gl_VertexID & 1) * 2.0 - 1.0, float(gl_VertexID >> 1) * 2.0 - 1.0, 0.0, 1.0);
}
"""
        val BLOCK_FRAGMENT = """#version 300 es
precision highp float;
precision highp int;
uniform highp sampler2D uTile;
uniform ivec2 uOrigin;
uniform int uBytes;
out highp uvec4 o;
const uvec4 M = uvec4(${TileFingerprint.glslMul});
void main() {
    ivec2 base = (ivec2(gl_FragCoord.xy) - uOrigin) * 16;
    uvec4 h = uvec4(${TileFingerprint.glslSeed});
    for (int y = 0; y < 16; y++) {
        for (int x = 0; x < 16; x++) {
            vec4 c = texelFetch(uTile, base + ivec2(x, y), 0);
            uvec4 v = uBytes == 1 ? uvec4(c * 255.0 + 0.5) : floatBitsToUint(c);
            ${TileFingerprint.glslStep("v")}
        }
    }
    o = h;
}
"""
        val FOLD_FRAGMENT = """#version 300 es
precision highp float;
precision highp int;
uniform highp usampler2D uBlocks;
out highp uvec4 o;
const uvec4 M = uvec4(${TileFingerprint.glslMul});
void main() {
    ivec2 base = ivec2(gl_FragCoord.xy) * 16;
    uvec4 h = uvec4(${TileFingerprint.glslSeed});
    for (int y = 0; y < 16; y++) {
        for (int x = 0; x < 16; x++) {
            uvec4 v = texelFetch(uBlocks, base + ivec2(x, y), 0);
            ${TileFingerprint.glslStep("v")}
        }
    }
    o = h;
}
"""
    }
}
