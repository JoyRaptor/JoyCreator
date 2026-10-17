package cc.joycreator.joybrush.androidkit.gl.media

import android.opengl.GLES30
import cc.joycreator.joybrush.core.media.DabBatch
import cc.joycreator.joybrush.core.media.MediaAtlasDraw
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Media Atlas v2, slice B1 (tasks/joybrush/specs/MEDIA_ATLAS_V2_20261016.md): the GL side of drawing the media passes
 * one instance per slot, for a [MediaLayerEngine] whose shaders are built with JB_ATLAS.
 *
 * B1 is the IDENTITY layout: the window's own textures are the atlas, its [nx] × [ny] tiles are its slots in place, so
 * the result is the window engine's, bit for bit (tools/media_atlas_parity_check.js). What this class owns: the page
 * table (R32I, a slot per tile), the per-slot instance buffer, and two VAOs (the unit quad + `a_slot`/`a_clip`, and the
 * dab quad + the 24-float dab-with-slot layout). The instance lists come from the JVM-tested [MediaAtlasDraw]. GL thread.
 * Off by default ([enabled]) until B2 places tiles freely.
 */
internal class MediaAtlasGl(private val nx: Int, private val ny: Int, quadBuf: Int, broken: Boolean = false) {
    private val draw = MediaAtlasDraw()
    private val slotOf = MediaAtlasDraw.identity(nx, ny)
    private val upload = FloatUploadBuffer()
    val table: Int
    private val instBuf: Int
    private val dabBuf: Int
    private val quadVao: Int
    private val dabVao: Int

    init {
        require(nx > 0 && ny > 0) { "an atlas needs at least one slot" }
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0); table = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, table)
        val cells = ByteBuffer.allocateDirect(nx * ny * 4).order(ByteOrder.nativeOrder()).asIntBuffer()
        // [broken] (the device probe's control only): every tile's entry names its right-hand neighbour's slot.
        for (ty in 0 until ny) for (tx in 0 until nx) cells.put(if (broken) slotOf((tx + 1) % nx, ty) else slotOf(tx, ty))
        cells.position(0)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R32I, nx, ny, 0, GLES30.GL_RED_INTEGER, GLES30.GL_INT, cells)
        for ((k, v) in listOf(GLES30.GL_TEXTURE_MIN_FILTER to GLES30.GL_NEAREST, GLES30.GL_TEXTURE_MAG_FILTER to GLES30.GL_NEAREST,
                GLES30.GL_TEXTURE_WRAP_S to GLES30.GL_CLAMP_TO_EDGE, GLES30.GL_TEXTURE_WRAP_T to GLES30.GL_CLAMP_TO_EDGE))
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, k, v)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        val bufs = IntArray(2); GLES30.glGenBuffers(2, bufs, 0); instBuf = bufs[0]; dabBuf = bufs[1]
        val vaos = IntArray(2); GLES30.glGenVertexArrays(2, vaos, 0); quadVao = vaos[0]; dabVao = vaos[1]
        // Rect passes: the unit quad at 0, per instance the slot (1) and the clip rect (2), 8 floats.
        GLES30.glBindVertexArray(quadVao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadBuf)
        GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instBuf)
        for (i in 0 until 2) {
            GLES30.glEnableVertexAttribArray(1 + i); GLES30.glVertexAttribPointer(1 + i, 4, GLES30.GL_FLOAT, false, 32, i * 16)
            GLES30.glVertexAttribDivisor(1 + i, 1)
        }
        // Dab passes: the unit quad at 0, the dab's five vec4s (1-5) and its slot (6): 24 floats, 96 bytes.
        GLES30.glBindVertexArray(dabVao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadBuf)
        GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, dabBuf)
        for (i in 0 until 6) {
            GLES30.glEnableVertexAttribArray(1 + i)
            GLES30.glVertexAttribPointer(1 + i, 4, GLES30.GL_FLOAT, false, MediaAtlasDraw.DAB_FLOATS * 4, i * 16)
            GLES30.glVertexAttribDivisor(1 + i, 1)
        }
        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    /** The page-table uniforms for a pass whose target slots are `1 shl shift` (8 full resolution, 7 the bead field). */
    fun uniforms(u: UniformBank, shift: Int = MediaAtlasDraw.SHIFT) {
        u.itex("u_atlasTable", table)
        u.i4("u_atlasGrid", 0, 0, nx, ny)
        u.f1("u_atlasSlotsX", nx)
        u.f1("u_atlasShift", shift)
        u.i4("u_atlasWrap", 0, 0, 0, 0)
    }

    /** A layer-shaped pass over [rect] (x0, y0, x1, y1 at the pass's resolution): one instance per slot it touches. */
    fun drawRect(rect: FloatArray, shift: Int = MediaAtlasDraw.SHIFT) {
        val n = draw.rects(rect[0], rect[1], rect[2], rect[3], shift, nx, slotOf)
        if (n == 0) return
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instBuf)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, n * 32, upload.upload(draw.rect, n * 8), GLES30.GL_STREAM_DRAW)
        GLES30.glBindVertexArray(quadVao)
        GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, n)
    }

    /**
     * [batch]'s dabs, each once per slot its box touches (dab order kept). [boxOf] writes dab i's layer-px box into its array;
     * it may be generous: a copy outside its slot draws nothing (jb_outsideSlot).
     */
    fun drawDabs(batch: DabBatch, boxOf: (FloatArray, Int, FloatArray) -> Unit) {
        val n = draw.dabs(batch.dabs, batch.count, nx, slotOf) { i, out -> boxOf(batch.dabs, i, out) }
        if (n == 0) return
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, dabBuf)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, n * MediaAtlasDraw.DAB_FLOATS * 4, upload.upload(draw.dabs, n * MediaAtlasDraw.DAB_FLOATS), GLES30.GL_STREAM_DRAW)
        GLES30.glBindVertexArray(dabVao)
        GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, n)
    }

    fun release() {
        GLES30.glDeleteTextures(1, intArrayOf(table), 0)
        GLES30.glDeleteBuffers(2, intArrayOf(instBuf, dabBuf), 0)
        GLES30.glDeleteVertexArrays(2, intArrayOf(quadVao, dabVao), 0)
        upload.reset()
    }

    companion object {
        /** New media engines draw through the atlas (identity layout). Off until B2; the debug bench may turn it on for an A/B. */
        @Volatile var enabled = false
    }
}
