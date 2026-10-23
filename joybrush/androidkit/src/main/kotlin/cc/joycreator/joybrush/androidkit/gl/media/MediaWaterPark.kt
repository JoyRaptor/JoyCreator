package cc.joycreator.joybrush.androidkit.gl.media

import android.opengl.GLES30
import cc.joycreator.joybrush.core.media.MediaAtlasDraw
import cc.joycreator.joybrush.core.media.WaterParkPlan

/**
 * L2 of the atlas room (tasks/joybrush/specs/ATLAS_ROOM_20261023.md): where a tile with running water waits, exactly, while
 * it is out of the working atlas ([WaterParkPlan]: 92 B a texel against a slot's 227). Units of one tile, made in chunks of
 * [cols] x [rows] units (one texture per plane per chunk), only when asked for, never more than [maxUnits]. The engine copies
 * a slot in and out ([MediaLayerEngine.parkSlot], [MediaLayerEngine.unparkSlot]). GL thread.
 */
internal class MediaWaterPark(private val wf: Int, private val maxUnits: Int, private val cols: Int = 4, private val rows: Int = 2) {
    private val perChunk = cols * rows
    private val chunks = ArrayList<IntArray>()   // per chunk: one texture per WaterParkPlan.Plane, in its order
    private val free = ArrayDeque<Int>()

    /** Units made so far (each chunk's whole). */
    val capacity: Int get() = chunks.size * perChunk
    /** Units in use. */
    val used: Int get() = capacity - free.size
    val bytes: Long get() = capacity * WaterParkPlan.bytesPerTile(MediaAtlasDraw.SIDE, wf)

    /** A free unit, making a chunk when none is left; null when [maxUnits] are made and in use. */
    fun take(): Int? {
        if (free.isEmpty() && capacity + perChunk <= maxUnits) grow()
        return free.removeFirstOrNull()
    }

    fun give(unit: Int) { require(unit in 0 until capacity && unit !in free) { "unit $unit is not in use" }; free.addLast(unit) }

    /** [plane]'s texture holding [unit]. */
    fun texture(unit: Int, plane: WaterParkPlan.Plane): Int = chunks[unit / perChunk][plane.ordinal]

    /** [unit]'s first texel in its chunk's textures, at full resolution (water planes: divide by the water's cell). */
    fun texel(unit: Int): Pair<Int, Int> {
        val i = unit % perChunk
        return (i % cols) * MediaAtlasDraw.SIDE to (i / cols) * MediaAtlasDraw.SIDE
    }

    private fun grow() {
        val w = cols * MediaAtlasDraw.SIDE; val h = rows * MediaAtlasDraw.SIDE
        chunks += IntArray(WaterParkPlan.Plane.entries.size) { i ->
            val p = WaterParkPlan.Plane.entries[i]
            val pw = if (p.water) w / wf else w; val ph = if (p.water) h / wf else h
            when (p) {
                WaterParkPlan.Plane.FLUX -> MediaTex.make(pw, ph)
                WaterParkPlan.Plane.RUN -> MediaTex.make(pw, ph, GLES30.GL_RG16F, GLES30.GL_RG, GLES30.GL_HALF_FLOAT)
                else -> MediaTex.full(pw, ph)
            }
        }
        val first = (chunks.size - 1) * perChunk
        for (u in first until first + perChunk) free.addLast(u)
    }

    fun release() {
        for (c in chunks) MediaTex.deleteTex(*c)
        chunks.clear(); free.clear()
    }
}
