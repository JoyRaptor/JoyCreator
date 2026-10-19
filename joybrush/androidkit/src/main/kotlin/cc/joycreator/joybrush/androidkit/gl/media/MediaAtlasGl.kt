package cc.joycreator.joybrush.androidkit.gl.media

import android.opengl.GLES30
import cc.joycreator.joybrush.core.media.DabBatch
import cc.joycreator.joybrush.core.media.MediaAtlasDraw
import cc.joycreator.joybrush.core.media.MediaAtlasPlacement
import cc.joycreator.joybrush.core.paint.Tiles
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
internal class MediaAtlasGl(private val nx: Int, private val ny: Int, quadBuf: Int, private val broken: Boolean = false) {
    private val draw = MediaAtlasDraw()
    /** B1: the identity table. B2 ([setResidency]): whatever the host placed where; −1 = not resident. */
    private var slotOf: (Int, Int) -> Int = MediaAtlasDraw.identity(nx, ny)
    private var grid = intArrayOf(0, 0, nx, ny)
    /** True once a host places tiles freely ([setResidency]): the engine then has no window edge. */
    var free = false; private set
    /** Free placement: tile key -> slot, as last set. */
    var resident: Map<Long, Int> = emptyMap(); private set
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
    /** Media Atlas v2 B2: the page table becomes [map] (tile key -> slot), uploaded at once; the engine loses its window edge. */
    fun setResidency(map: Map<Long, Int>) {
        for (s in map.values) require(s in 0 until nx * ny) { "slot $s is not in this atlas" }
        val t = MediaAtlasPlacement.table(map, walls)
        free = true; resident = HashMap(map)
        slotOf = t::slotOf; grid = intArrayOf(t.x, t.y, t.w, t.h)
        val cells = ByteBuffer.allocateDirect(t.data.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer()
        cells.put(t.data); cells.position(0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, table)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R32I, t.w, t.h, 0, GLES30.GL_RED_INTEGER, GLES30.GL_INT, cells)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    /**
     * A JB_ATLAS_NBR pass's reads (jb_media_atlas.glsl): into [out] (18 ints), the first texel of the slots holding tiles
     * ([tx], [ty]) + (-1..1, -1..1), row by row, x = -1 where a tile is not here and -2 for a wall when [withWalls] (the
     * water's passes, [wallsOn]): what the page table says of each, so the very texels a table read would (the probe's
     * broken table included).
     */
    fun neighbours(tx: Int, ty: Int, out: IntArray, withWalls: Boolean = false) {
        var i = 0
        for (dy in -1..1) for (dx in -1..1) {
            val x = tx + dx; val y = ty + dy
            val s = if (broken && !free) { if (x in 0 until nx && y in 0 until ny) slotOf((x + 1) % nx, y) else -1 } else slotOf(x, y)
            when {
                withWalls && Tiles.key(x, y) in walls -> { out[i] = -2; out[i + 1] = -2 }
                s < 0 -> { out[i] = -1; out[i + 1] = -1 }
                else -> { val (ax, ay) = slotTexel(s); out[i] = ax; out[i + 1] = ay }
            }
            i += 2
        }
    }

    /** Slot [slot]'s first texel (full resolution). */
    fun slotTexel(slot: Int): Pair<Int, Int> = ((slot % nx) shl MediaAtlasDraw.SHIFT) to ((slot / nx) shl MediaAtlasDraw.SHIFT)

    /** Every resident tile layer-px [rect] touches: its slot, the tile, and the part of [rect] in it (layer px, whole pixels). */
    fun forEachPart(rect: FloatArray, f: (slot: Int, tx: Int, ty: Int, x0: Int, y0: Int, x1: Int, y1: Int) -> Unit) {
        val x0 = kotlin.math.floor(rect[0]).toInt(); val y0 = kotlin.math.floor(rect[1]).toInt()
        val x1 = kotlin.math.ceil(rect[2]).toInt(); val y1 = kotlin.math.ceil(rect[3]).toInt()
        if (x1 <= x0 || y1 <= y0) return
        for (key in MediaAtlasPlacement.tiles(x0.toDouble(), y0.toDouble(), x1.toDouble(), y1.toDouble(), 0)) {
            val tx = Tiles.tx(key); val ty = Tiles.ty(key)
            val slot = slotOf(tx, ty); if (slot < 0) continue
            val s = MediaAtlasDraw.SIDE
            f(slot, tx, ty, maxOf(x0, tx * s), maxOf(y0, ty * s), minOf(x1, tx * s + s), minOf(y1, ty * s + s))
        }
    }

    /** Clears layer-px [rect] of the slot-shaped target bound to [fbo], slot by slot (a window's scissored clear). */
    fun clearRect(fbo: Int, rect: FloatArray) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
        forEachPart(rect) { slot, tx, ty, x0, y0, x1, y1 ->
            val (ax, ay) = slotTexel(slot)
            GLES30.glScissor(ax + x0 - tx * MediaAtlasDraw.SIDE, ay + y0 - ty * MediaAtlasDraw.SIDE, x1 - x0, y1 - y0)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        }
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
    }

    /** Scissor to the part of layer-px [rect] in [slot] (tile [tx], [ty]); false when there is none. */
    fun scissorSlot(slot: Int, tx: Int, ty: Int, rect: FloatArray): Boolean {
        var hit = false
        forEachPart(rect) { s, x, y, x0, y0, x1, y1 ->
            if (s == slot && x == tx && y == ty) {
                val (ax, ay) = slotTexel(s)
                GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
                GLES30.glScissor(ax + x0 - x * MediaAtlasDraw.SIDE, ay + y0 - y * MediaAtlasDraw.SIDE, x1 - x0, y1 - y0)
                hit = true
            }
        }
        return hit
    }

    fun uniforms(u: UniformBank, shift: Int = MediaAtlasDraw.SHIFT) {
        bank = u
        u.itex("u_atlasTable", table)
        u.i4("u_atlasGrid", grid[0], grid[1], grid[2], grid[3])
        u.f1("u_atlasSlotsX", nx)
        u.f1("u_atlasShift", shift)
        u.i4("u_atlasWrap", 0, 0, 0, 0)
        u.i4("u_atlasWalls", if (wallsOn) 1 else 0, 0, 0, 0)
    }

    /** B3: tiles the current pass leaves alone even though they are resident (a drip's dried tail, out of the water). */
    var skip: Set<Long> = emptySet()

    /** B3 (D2): tiles marked in the page table as out of the water; [wallsOn] = the passes now drawn see them as walls. */
    var walls: Set<Long> = emptySet(); private set
    var wallsOn = false

    /** Marks [w] as walls in the page table (uploaded at once when it changed). */
    fun setWalls(w: Set<Long>) {
        if (w == walls) return
        walls = w.toSet()
        if (free) setResidency(resident)
    }

    /** A layer-shaped pass over [rect] (x0, y0, x1, y1 at the pass's resolution): one instance per slot it touches, except [skip]. */
    fun drawRect(rect: FloatArray, shift: Int = MediaAtlasDraw.SHIFT) {
        val table = if (skip.isEmpty()) slotOf else { x: Int, y: Int -> if (Tiles.key(x, y) in skip) -1 else slotOf(x, y) }
        val n = draw.rects(rect[0], rect[1], rect[2], rect[3], shift, nx, table)
        if (n == 0) return
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instBuf)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, n * 32, upload.upload(draw.rect, n * 8), GLES30.GL_STREAM_DRAW)
        GLES30.glBindVertexArray(quadVao)
        val u = bank
        if (u == null || !u.has("u_nbrSlot")) { GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, n); return }
        // JB_ATLAS_NBR: one slot a draw, each handed its 3x3 neighbours (the instance's attributes moved to it).
        for (i in 0 until n) {
            val tx = draw.rect[i * 8 + 2].toInt(); val ty = draw.rect[i * 8 + 3].toInt()
            neighbours(tx, ty, nbrSlots, wallsOn)
            nbrTile[0] = tx; nbrTile[1] = ty; u.i2v("u_nbrTile", nbrTile); u.i2v("u_nbrSlot", nbrSlots)
            for (k in 0 until 2) GLES30.glVertexAttribPointer(1 + k, 4, GLES30.GL_FLOAT, false, 32, i * 32 + k * 16)
            GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, 1)
        }
        for (k in 0 until 2) GLES30.glVertexAttribPointer(1 + k, 4, GLES30.GL_FLOAT, false, 32, k * 16)
    }
    private val nbrSlots = IntArray(18)
    private val nbrTile = IntArray(2)
    /** The bank of the program the pass made current ([uniforms] is called for every pass before it draws). */
    private var bank: UniformBank? = null

    /**
     * [batch]'s dabs, each once per slot its box touches (dab order kept). [boxOf] writes dab i's layer-px box into its array;
     * it may be generous: a copy outside its slot draws nothing (jb_outsideSlot).
     */
    fun drawDabs(batch: DabBatch, boxOf: (FloatArray, Int, FloatArray) -> Unit) = drawDabs(batch, boxOf, slotOf)

    /**
     * [batch]'s dabs where the window build scissored them to layer-px [clip] (the wet brush input): one draw per slot,
     * each under that slot's own scissor, its dabs in order. One scissor cannot cover slots that lie apart.
     */
    fun drawDabs(batch: DabBatch, boxOf: (FloatArray, Int, FloatArray) -> Unit, clip: FloatArray) {
        forEachPart(clip) { slot, tx, ty, x0, y0, x1, y1 ->
            val (ax, ay) = slotTexel(slot)
            GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
            GLES30.glScissor(ax + x0 - tx * MediaAtlasDraw.SIDE, ay + y0 - ty * MediaAtlasDraw.SIDE, x1 - x0, y1 - y0)
            drawDabs(batch, boxOf) { x, y -> if (x == tx && y == ty) slot else -1 }
        }
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
    }

    private fun drawDabs(batch: DabBatch, boxOf: (FloatArray, Int, FloatArray) -> Unit, table: (Int, Int) -> Int) {
        val n = draw.dabs(batch.dabs, batch.count, nx, table) { i, out -> boxOf(batch.dabs, i, out) }
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
