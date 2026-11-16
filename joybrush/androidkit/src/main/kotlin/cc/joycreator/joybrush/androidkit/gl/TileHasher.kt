package cc.joycreator.joybrush.androidkit.gl

import android.opengl.GLES30
import cc.joycreator.joybrush.core.paint.TileFingerprint
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Fingerprints of tile textures, computed where they lie ([TileFingerprint], the same arithmetic): a save reads back only
 * the tiles whose fingerprint moved (JBX-36, 2026-11-15). Reading a tile back costs about 0.85 ms with the pen waiting.
 *
 * Tiles go [BATCH] at a time through three passes into sheets of unsigned integers: each tile drawn as 64 x 64 pixels,
 * every pixel folding its own 4 x 4 texels; those folded 8 x 8 at a time into 8 x 8 pixels a tile; and each tile's 64
 * into one pixel of the results sheet, a row a batch. The results are read once at the end. Nothing is written to a tile.
 *
 * GL thread. Leaves the framebuffer, viewport, program, vertex array, scissor and blend switches and texture unit 0 as
 * it found them. Returns null, and stays off for this context, when the driver cannot do it (the caller then reads
 * every tile, as before).
 */
internal class TileHasher(private val tileSize: Int) {
    private var blocks: GlProgram? = null
    private var groups: GlProgram? = null
    private var last: GlProgram? = null
    private var vao = 0
    private var fbo = 0
    private var blockTex = 0
    private var groupTex = 0
    private var outTex = 0
    private var rows = 0
    private var broken = tileSize != TileFingerprint.SIDE || TileFingerprint.BLOCK != 4 || TileFingerprint.GROUP != 8

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
            val need = (textures.size + BATCH - 1) / BATCH
            if (!ready(need)) { broken = true; return null }
            GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
            GLES30.glDisable(GLES30.GL_BLEND)
            GLES30.glBindVertexArray(vao)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
            val p = blocks!!; val g = groups!!; val f = last!!
            for (batch in 0 until need) {
                val from = batch * BATCH; val count = minOf(BATCH, textures.size - from)
                // 1. Each tile's 4 x 4 blocks.
                GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, blockTex, 0)
                p.use()
                GLES30.glUniform1i(p.loc("uTile"), 0)
                for (n in 0 until count) {
                    val x = (n % ALONG) * BLOCKS; val y = (n / ALONG) * BLOCKS
                    GLES30.glViewport(x, y, BLOCKS, BLOCKS)
                    GLES30.glUniform2i(p.loc("uOrigin"), x, y)
                    GLES30.glUniform1i(p.loc("uBytes"), if (bytes[from + n]) 1 else 0)
                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textures[from + n])
                    GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
                }
                // 2. Every tile's 8 x 8 groups of blocks, the whole sheet in one draw.
                GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, groupTex, 0)
                g.use()
                GLES30.glUniform1i(g.loc("uBelow"), 0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, blockTex)
                GLES30.glViewport(0, 0, ALONG * GROUPS, ALONG * GROUPS)
                GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
                // 3. Each tile's 64 groups into its pixel of this batch's row.
                GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, outTex, 0)
                f.use()
                GLES30.glUniform1i(f.loc("uBelow"), 0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, groupTex)
                GLES30.glViewport(0, batch, BATCH, 1)
                GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            }
            val buf = ByteBuffer.allocateDirect(BATCH * need * 16).order(ByteOrder.nativeOrder())
            GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 4)
            GLES30.glReadPixels(0, 0, BATCH, need, GLES30.GL_RGBA_INTEGER, GLES30.GL_UNSIGNED_INT, buf)
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
            groups = GlProgram(VERTEX, GROUP_FRAGMENT, "tile fingerprint groups")
            last = GlProgram(VERTEX, LAST_FRAGMENT, "tile fingerprint last")
            val ids = IntArray(1)
            GLES30.glGenVertexArrays(1, ids, 0); vao = ids[0]
            GLES30.glGenFramebuffers(1, ids, 0); fbo = ids[0]
            blockTex = sheet(ALONG * BLOCKS, ALONG * BLOCKS)
            groupTex = sheet(ALONG * GROUPS, ALONG * GROUPS)
            if (blockTex == 0 || groupTex == 0 || !complete(blockTex) || !complete(groupTex)) return false
        }
        if (need <= rows) return true
        val want = maxOf(need, rows * 2, 4)
        if (outTex != 0) GLES30.glDeleteTextures(1, intArrayOf(outTex), 0)
        outTex = sheet(BATCH, want)
        rows = if (outTex != 0 && complete(outTex)) want else 0
        return rows != 0
    }

    private fun complete(tex: Int): Boolean {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex, 0)
        return GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE
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
        blocks?.release(); groups?.release(); last?.release(); blocks = null; groups = null; last = null
        val tex = intArrayOf(blockTex, groupTex, outTex).filter { it != 0 }.toIntArray()
        if (tex.isNotEmpty()) GLES30.glDeleteTextures(tex.size, tex, 0)
        if (fbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        if (vao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
        blockTex = 0; groupTex = 0; outTex = 0; fbo = 0; vao = 0; rows = 0
    }

    private companion object {
        /** Tiles in a batch, [ALONG] x [ALONG] on the working sheets; blocks and groups along a tile's side. */
        const val ALONG = 8
        const val BATCH = ALONG * ALONG
        const val BLOCKS = 64
        const val GROUPS = 8

        const val VERTEX = """#version 300 es
void main() {
    gl_Position = vec4(float(gl_VertexID & 1) * 2.0 - 1.0, float(gl_VertexID >> 1) * 2.0 - 1.0, 0.0, 1.0);
}
"""
        val HEAD = """#version 300 es
precision highp float;
precision highp int;
out highp uvec4 o;
const uvec4 M = uvec4(${TileFingerprint.glslMul});
"""
        val BLOCK_FRAGMENT = HEAD + """uniform highp sampler2D uTile;
uniform ivec2 uOrigin;
uniform int uBytes;
void main() {
    ivec2 base = (ivec2(gl_FragCoord.xy) - uOrigin) * 4;
    uvec4 h = uvec4(${TileFingerprint.glslSeed});
    for (int y = 0; y < 4; y++) {
        for (int x = 0; x < 4; x++) {
            vec4 c = texelFetch(uTile, base + ivec2(x, y), 0);
            uvec4 v = uBytes == 1 ? uvec4(c * 255.0 + 0.5) : floatBitsToUint(c);
            ${TileFingerprint.glslStep("v")}
        }
    }
    o = h;
}
"""
        /** A pixel of the groups sheet: the 8 x 8 blocks under it (a tile's blocks start at a multiple of 64, so of 8). */
        val GROUP_FRAGMENT = HEAD + """uniform highp usampler2D uBelow;
void main() {
    ivec2 base = ivec2(gl_FragCoord.xy) * 8;
    uvec4 h = uvec4(${TileFingerprint.glslSeed});
    for (int y = 0; y < 8; y++) {
        for (int x = 0; x < 8; x++) {
            uvec4 v = texelFetch(uBelow, base + ivec2(x, y), 0);
            ${TileFingerprint.glslStep("v")}
        }
    }
    o = h;
}
"""
        /** Pixel n of a batch's row: tile n of the batch, whose 8 x 8 groups lie at (n % 8, n / 8) * 8 on the groups sheet. */
        val LAST_FRAGMENT = HEAD + """uniform highp usampler2D uBelow;
void main() {
    int n = int(gl_FragCoord.x);
    ivec2 base = ivec2(n % 8, n / 8) * 8;
    uvec4 h = uvec4(${TileFingerprint.glslSeed});
    for (int y = 0; y < 8; y++) {
        for (int x = 0; x < 8; x++) {
            uvec4 v = texelFetch(uBelow, base + ivec2(x, y), 0);
            ${TileFingerprint.glslStep("v")}
        }
    }
    o = h;
}
"""
    }
}
